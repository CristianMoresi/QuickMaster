package com.quickmaster.processing.dynamics.leveler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.MethodEntryRequest;

/** External JDI observer: enabled before resume, drains until VMDisconnectEvent. */
final class BoundaryJdiObserver
{
    static JsonObject observe(String classpath, String cases, String observerMode) throws Exception
    {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(60L);
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("home").setValue(System.getProperty("java.home"));
        String main = BoundaryEarlyRejectionChild.class.getName() + " " + cases;
        String options = "-Xms16m -Xmx64m -XX:+UseSerialGC --add-modules jdk.management -cp \""
                + classpath + "\"";
        arguments.get("main").setValue(main);
        arguments.get("options").setValue(options);
        arguments.get("suspend").setValue("true");
        VirtualMachine vm = connector.launch(arguments);
        Process process = vm.process();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<String> stdout = readers.submit(() -> read(process.getInputStream()));
        Future<String> stderr = readers.submit(() -> read(process.getErrorStream()));
        boolean requestEnabled = false;
        boolean detectorSeen = false;
        boolean rawMethodExists = false;
        boolean death = false;
        boolean disconnected = false;
        boolean timedOut = false;
        long detect = 0L;
        long novelty = 0L;
        String observerFailure = "";
        try
        {
            if (!observerMode.equals("BLIND"))
            {
                MethodEntryRequest request = vm.eventRequestManager().createMethodEntryRequest();
                request.addClassFilter(observerMode.equals("WRONG_FILTER") ? "missing.BoundaryDetector"
                        : BoundaryClassfileScanner.DETECTOR.replace('/', '.'));
                request.setSuspendPolicy(EventRequest.SUSPEND_NONE);
                request.enable();
                requestEnabled = request.isEnabled();
                ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
                prepare.addClassFilter(observerMode.equals("WRONG_FILTER") ? "missing.BoundaryDetector"
                        : BoundaryClassfileScanner.DETECTOR.replace('/', '.'));
                prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                prepare.enable();
            }
            vm.resume();
            while (!disconnected)
            {
                if (System.nanoTime() >= deadline)
                {
                    timedOut = true;
                    process.destroyForcibly();
                    break;
                }
                EventSet set = vm.eventQueue().remove(100L);
                if (set == null) continue;
                for (Event event : set)
                {
                    if (event instanceof ClassPrepareEvent prepared)
                    {
                        rawMethodExists = !prepared.referenceType().methodsByName("rawNovelty").isEmpty();
                    }
                    else if (event instanceof MethodEntryEvent entry
                            && entry.method().declaringType().name().equals(
                                    BoundaryClassfileScanner.DETECTOR.replace('/', '.')))
                    {
                        detectorSeen = true;
                        if (entry.method().name().equals("detect"))
                        {
                            detect++;
                        }
                        if (entry.method().name().equals("rawNovelty")) novelty++;
                    }
                    else if (event instanceof VMDeathEvent) death = true;
                    else if (event instanceof VMDisconnectEvent) disconnected = true;
                }
                set.resume();
            }
        }
        catch (VMDisconnectedException error)
        {
            observerFailure = "Disconnected before the event queue was drained";
        }
        catch (Throwable error)
        {
            observerFailure = error.getClass().getName() + ": " + error.getMessage();
            process.destroyForcibly();
        }
        finally
        {
            long remaining = Math.max(1L, deadline - System.nanoTime());
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS))
            {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(3L, TimeUnit.SECONDS);
            }
        }
        String out;
        String err;
        try
        {
            out = stdout.get(3L, TimeUnit.SECONDS).trim();
            err = stderr.get(3L, TimeUnit.SECONDS).trim();
        }
        finally
        {
            readers.shutdownNow();
        }
        JsonObject result = new JsonObject();
        result.addProperty("javaHome", Path.of(System.getProperty("java.home")).toAbsolutePath().toString());
        result.addProperty("javaVersion", System.getProperty("java.runtime.version"));
        result.addProperty("main", main);
        result.addProperty("options", options);
        result.addProperty("timeoutSeconds", 60);
        result.addProperty("pid", process.pid());
        result.addProperty("observerMode", observerMode);
        result.addProperty("requestEnabledBeforeResume", requestEnabled);
        result.addProperty("detectorSeen", detectorSeen);
        result.addProperty("rawNoveltyMethodExists", rawMethodExists);
        result.addProperty("detectEntries", detect);
        result.addProperty("noveltyEntries", novelty);
        result.addProperty("sawVmDeath", death);
        result.addProperty("drainedToVmDisconnect", disconnected);
        result.addProperty("timedOut", timedOut);
        result.addProperty("exitCode", process.isAlive() ? -1 : process.exitValue());
        result.addProperty("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        result.addProperty("observerFailure", observerFailure);
        result.addProperty("stderr", err);
        try { result.add("child", JsonParser.parseString(out)); }
        catch (RuntimeException error) { result.addProperty("invalidChildOutput", out); }
        return result;
    }

    static List<String> violations(JsonObject result, boolean invalid, boolean requiresNovelty,
                                   boolean checkBudget, boolean checkPhase)
    {
        List<String> violations = new ArrayList<>();
        if (result.get("timedOut").getAsBoolean()) violations.add("TIMEOUT");
        if (result.get("exitCode").getAsInt() != 0) violations.add("CHILD_EXIT");
        boolean observer = result.get("requestEnabledBeforeResume").getAsBoolean()
                && result.get("detectorSeen").getAsBoolean()
                && result.get("rawNoveltyMethodExists").getAsBoolean()
                && result.get("sawVmDeath").getAsBoolean()
                && result.get("drainedToVmDisconnect").getAsBoolean()
                && result.get("observerFailure").getAsString().isEmpty();
        if (!observer) violations.add("OBSERVER_INCOMPLETE");
        if (!result.has("child") || !result.get("child").isJsonObject())
        {
            violations.add("CHILD_RESULT_MISSING");
            return violations;
        }
        JsonObject child = result.getAsJsonObject("child");
        if (!child.get("allocationSupported").getAsBoolean() || !child.get("allocationEnabled").getAsBoolean())
            violations.add("ALLOCATION_COUNTER_UNAVAILABLE");
        long calls = 0L;
        JsonArray cases = child.getAsJsonArray("cases");
        if (cases.isEmpty()) violations.add("NO_CASES");
        for (var element : cases)
        {
            JsonObject row = element.getAsJsonObject();
            calls += row.get("invocations").getAsLong();
            if (!row.get("statusCorrect").getAsBoolean() || !row.get("error").getAsString().isEmpty())
                violations.add("STATUS_OR_EXCEPTION:" + row.get("name").getAsString());
            if (row.get("warmups").getAsInt() != 128 || row.get("measurements").getAsInt() != 32
                    || row.get("invocations").getAsInt() != 160)
                violations.add("INCOMPLETE_CALLS:" + row.get("name").getAsString());
            if (row.get("minimumBytes").getAsLong() < 0L
                    || row.get("maximumBytes").getAsLong() < row.get("minimumBytes").getAsLong())
                violations.add("INVALID_ALLOCATION_DELTA");
            if (checkBudget && invalid && row.get("maximumBytes").getAsLong() > 16_384L)
                violations.add("ALLOCATION_BUDGET:" + row.get("name").getAsString());
        }
        if (calls != child.get("totalInvocations").getAsLong()
                || calls != result.get("detectEntries").getAsLong()) violations.add("OBSERVER_CALL_MISMATCH");
        if (checkPhase && invalid && result.get("noveltyEntries").getAsLong() != 0L)
            violations.add("EARLY_NOVELTY");
        if (requiresNovelty && result.get("noveltyEntries").getAsLong() == 0L)
            violations.add("NOVELTY_NOT_OBSERVED");
        return violations;
    }

    private static String read(InputStream stream) throws Exception
    {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
