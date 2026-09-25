package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;

class LoudnessSharedCoreCancellationTest
{
    @Test
    void cancellationInsideActualAdapterStopsWithin4096Frames() throws Exception { observe("frames"); }

    @Test
    void cancellationInsideActualIntegratedStopsWithin4096Blocks() throws Exception { observe("blocks"); }

    private static void observe(String mode) throws Exception
    {
        var connector = Bootstrap.virtualMachineManager().defaultConnector();
        var arguments = connector.defaultArguments();
        arguments.get("home").setValue(System.getProperty("java.home"));
        arguments.get("options").setValue("-Xmx192m -cp \"" + System.getProperty("java.class.path") + "\"");
        arguments.get("main").setValue(LoudnessSharedCoreCancellationChild.class.getName() + " " + mode);
        arguments.get("suspend").setValue("true");
        VirtualMachine vm = connector.launch(arguments);
        Process child = vm.process();
        boolean death = false, disconnect = false, finish = false, cancellationWritten = false;
        ObjectReference core = null;
        long workAfterCancellation = -1L;
        int lufsCalls = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        try
        {
            for (String owner : List.of(LoudnessCore.class.getName(), LoudnessSharedCoreCancellationChild.class.getName()))
            {
                ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
                prepare.addClassFilter(owner);
                prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                prepare.enable();
            }
            vm.resume();
            while (!disconnect && System.nanoTime() < deadline)
            {
                EventSet events = vm.eventQueue().remove(200);
                if (events == null) continue;
                for (Event event : events)
                {
                    if (event instanceof ClassPrepareEvent prepared)
                    {
                        String method = prepared.referenceType().name().equals(LoudnessCore.class.getName())
                                ? mode.equals("frames") ? "acceptFrame" : "lufs" : "finish";
                        BreakpointRequest point = vm.eventRequestManager().createBreakpointRequest(
                                prepared.referenceType().methodsByName(method).get(0).location());
                        point.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                        point.enable();
                    }
                    else if (event instanceof BreakpointEvent hit)
                    {
                        String method = hit.location().method().name();
                        if (method.equals("finish"))
                        {
                            assertTrue(((BooleanValue) hit.thread().frame(0).getArgumentValues().get(0)).value());
                            finish = true;
                            workAfterCancellation = core == null ? lufsCalls : ((LongValue) core.getValue(
                                    core.referenceType().fieldByName("framesSeen"))).value();
                        }
                        else
                        {
                            if (method.equals("lufs")) lufsCalls++;
                            if (!cancellationWritten)
                            {
                                List<Value> callerArguments = hit.thread().frame(1).getArgumentValues();
                                ObjectReference token = (ObjectReference) callerArguments.get(callerArguments.size() - 1);
                                // The only debugger write is the poll input, equivalent to CancellationToken.cancel().
                                token.setValue(token.referenceType().fieldByName("cancelled"), vm.mirrorOf(true));
                                cancellationWritten = true;
                                if (mode.equals("frames"))
                                {
                                    core = hit.thread().frame(0).thisObject();
                                    core.disableCollection();
                                    hit.request().disable();
                                }
                            }
                        }
                    }
                    else if (event instanceof VMDeathEvent) death = true;
                    else if (event instanceof VMDisconnectEvent) disconnect = true;
                }
                events.resume();
            }
            assertTrue(disconnect, "No observer timeout or unfinished child is a passing cancellation test.");
            assertTrue(death);
            assertTrue(finish);
            assertTrue(cancellationWritten);
            assertTrue(child.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(workAfterCancellation > 0 && workAfterCancellation <= 4_096L,
                    "Actual work after cancellation=" + workAfterCancellation);
            Path evidence = Path.of("target/leveler-cancellation");
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve(mode + ".json"), "{\"mode\":\"" + mode
                    + "\",\"workAfterCancellation\":" + workAfterCancellation
                    + ",\"inputOnlyDebuggerWrite\":true,\"productTransformed\":false,\"exitCode\":0,\"death\":true,\"disconnect\":true}",
                    StandardCharsets.UTF_8);
        }
        finally
        {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); }
        }
    }
}
