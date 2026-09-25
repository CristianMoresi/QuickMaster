package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.dspark.core.FFTReal;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
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

/** Pauses real, unmodified extractor bytes just after a poll, then changes only the cancellation input. */
class HannCancellationTest
{
    @Test void comparisonCancellationDuringInitializationKeepsTheExistingPoll() throws Exception { observe(true, false); }
    @Test void comparisonCancellationDuringReuseKeepsTheExistingPoll() throws Exception { observe(true, true); }
    @Test void structuralCancellationDuringInitializationKeepsTheExistingPoll() throws Exception { observe(false, false); }
    @Test void structuralCancellationDuringReuseKeepsTheExistingPoll() throws Exception { observe(false, true); }

    private static void observe(boolean comparison, boolean reuse) throws Exception
    {
        String shortOwner = comparison ? "ComparisonFeatureExtractor" : "StructuralFeatureExtractor";
        String owner = "com.quickmaster.processing.dynamics.leveler." + shortOwner;
        String anchor = comparison && !reuse ? "hannWindow[n] = hann;" : "time[n] = (float) (sample * hannWindow[n]);";
        String source = HannMutant.source(shortOwner);
        assertEquals(source.indexOf(anchor), source.lastIndexOf(anchor));
        assertTrue(source.contains(anchor));
        int sampleLine = (int) source.substring(0, source.indexOf(anchor)).chars().filter(c -> c == '\n').count() + 1;
        var connector = Bootstrap.virtualMachineManager().defaultConnector();
        var arguments = connector.defaultArguments();
        arguments.get("home").setValue(System.getProperty("java.home"));
        arguments.get("options").setValue("-Xmx192m -cp \"" + System.getProperty("java.class.path") + "\"");
        arguments.get("main").setValue(HannCancellationChild.class.getName() + " " + comparison);
        arguments.get("suspend").setValue("true");
        VirtualMachine vm = connector.launch(arguments);
        Process child = vm.process();
        BreakpointRequest sample = null, poll = null;
        boolean death = false, disconnect = false, finish = false, written = false, fftSeen = false;
        int nextPollIndex = -1;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        try
        {
            for (String type : List.of(owner, FFTReal.class.getName(), CancellationToken.class.getName(),
                    HannCancellationChild.class.getName()))
            {
                ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
                prepare.addClassFilter(type);
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
                        String type = prepared.referenceType().name();
                        if (type.equals(owner))
                        {
                            var locations = prepared.referenceType().locationsOfLine(sampleLine);
                            assertEquals(1, locations.size(), "The selected sample/coefficient line must be unambiguous");
                            sample = point(vm, locations.get(0), !reuse);
                        }
                        else if (type.equals(CancellationToken.class.getName()))
                            poll = point(vm, prepared.referenceType().methodsByName("isCancelled").get(0).location(), false);
                        else if (type.equals(FFTReal.class.getName()))
                            point(vm, prepared.referenceType().methodsByName("forward").get(0).location(), reuse);
                        else point(vm, prepared.referenceType().methodsByName("finish").get(0).location(), true);
                    }
                    else if (event instanceof BreakpointEvent hit)
                    {
                        String type = hit.location().declaringType().name();
                        if (type.equals(FFTReal.class.getName()))
                        {
                            fftSeen = true;
                            assertNotNull(sample);
                            sample.enable();
                            hit.request().disable();
                        }
                        else if (type.equals(owner))
                        {
                            StackFrame frame = hit.thread().frame(0);
                            if (((IntegerValue) value(frame, "n")).value() == 1)
                            {
                                List<Value> parameters = frame.getArgumentValues();
                                ObjectReference token = (ObjectReference) parameters.get(comparison ? 2 : 3);
                                token.setValue(token.referenceType().fieldByName("cancelled"), vm.mirrorOf(true));
                                written = true;
                                hit.request().disable();
                                assertNotNull(poll);
                                poll.enable();
                            }
                        }
                        else if (type.equals(CancellationToken.class.getName()))
                        {
                            StackFrame caller = hit.thread().frame(comparison ? 2 : 1);
                            assertEquals(owner, caller.location().declaringType().name());
                            nextPollIndex = ((IntegerValue) value(caller, "n")).value();
                            hit.request().disable();
                        }
                        else
                        {
                            assertNull(hit.thread().frame(0).getArgumentValues().get(0), "Cancelled extraction must not publish");
                            finish = true;
                        }
                    }
                    else if (event instanceof VMDeathEvent) death = true;
                    else if (event instanceof VMDisconnectEvent) disconnect = true;
                }
                events.resume();
            }
            assertTrue(disconnect && death && finish && written, "No timeout or unfinished child is passing cancellation evidence");
            assertEquals(reuse, fftSeen, "Reuse observation must follow the first actual FFT input");
            assertEquals(4_096, nextPollIndex, "Cancellation at index 1 must reach the unchanged 4096-index poll");
            assertTrue(child.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            System.out.println("HANN_CANCELLATION " + shortOwner + " reuse=" + reuse
                    + " cancelledAt=1 nextPoll=4096 result=null productTransformed=false");
        }
        finally
        {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); }
        }
    }

    private static Value value(StackFrame frame, String name) throws Exception
    {
        var variable = frame.visibleVariableByName(name);
        assertNotNull(variable, "Required real-product local " + name + " at " + frame.location());
        return frame.getValue(variable);
    }

    private static BreakpointRequest point(VirtualMachine vm, com.sun.jdi.Location location, boolean enabled)
    {
        BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(location);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        if (enabled) request.enable();
        return request;
    }
}
