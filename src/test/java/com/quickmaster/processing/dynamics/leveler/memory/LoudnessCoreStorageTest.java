package com.quickmaster.processing.dynamics.leveler.memory;

import static org.junit.jupiter.api.Assertions.*;
import com.quickmaster.processing.dynamics.leveler.LoudnessCore;
import com.quickmaster.processing.dynamics.leveler.LoudnessCoreBaseline;
import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/** R1 exact sizes and R3 exact allocation; missing instrumentation is an infrastructure failure. */
public class LoudnessCoreStorageTest
{
    private static volatile Object retained;
    private static volatile double consumed;

    @Test void ownedStorageAndAllocationsInBoundedInstrumentedChild() throws Exception
    {
        Path root = Files.createTempDirectory(Path.of(System.getProperty("qm.coreEvidence", System.getProperty("java.io.tmpdir"))), "core-storage-");
        Path classes = Path.of(ShadowReachabilityAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path agent = AgentJarBuilder.create(classes, root.resolve("agent.jar"));
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xms32m", "-Xmx512m", "-javaagent:" + agent, "-cp", System.getProperty("java.class.path"),
                getClass().getName(), System.getProperty("qm.coreProbe", "full"), root.toString());
        Files.writeString(root.resolve("command.txt"), String.join("\n", command));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        child.getOutputStream().close();
        if (!child.waitFor(60, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(); fail("Storage child timeout " + root); }
        String output = Files.readString(root.resolve("child.log"));
        System.out.println(output);
        assertEquals(0, child.exitValue(), "Storage child failed: " + root + "\n" + output);
    }

    public static void main(String[] args) throws Exception
    {
        assertTrue(ShadowReachabilityAgent.present(), "AGENT_MISSING");
        // Deliberately first: baseline must reach a causal object-size assertion, not a descriptor error.
        deepSize(new LoudnessCore(48_000, ChannelLayout.MONO_MAIN), new IdentityHashMap<>(), true);
        if (args[0].equals("red")) return;
        verifyLayoutBounds();
        for (int rate : new int[] {1,3,29,31,500,10_922,10_923,21_845,21_846,44_100,48_000,96_000,192_000,384_000})
            for (ChannelLayout layout : ChannelLayout.values())
            {
                // Retain both fresh cores only within this rate/layout pair. This
                // checks for shared owned storage without retaining every core in
                // the bounded child heap until the maximum-rate construction.
                IdentityHashMap<Object, Boolean> pair = new IdentityHashMap<>();
                for (int fresh = 0; fresh < 2; fresh++) verifyCore(new LoudnessCore(rate, layout), pair);
            }
        verifyCore(new LoudnessCore(5_592_383, ChannelLayout.SURROUND_5_1), new IdentityHashMap<>());
        assertThrows(IllegalArgumentException.class, () -> new LoudnessCore(5_592_384, ChannelLayout.MONO_MAIN));
        for (long rate = 1; rate <= 5_592_383; rate++)
        {
            long n = 3 * rate, k = (n + 1) / 2, q = (n - 1) / 32768 + 1 + (k - 1) / 32768 + 1;
            assertTrue(8 * (n + k) + 40 * q + 704 <= 16 * n + 1024, "Universal conservative layout bound");
        }
        exactAllocations();
        jfrConstruction(Path.of(args[1]));
        System.out.println("CORE_STORAGE_PASS maxArrayBytes=262176 chunkCap=32768 maxRate=5592383");
    }

    public static long deepSize(Object core, IdentityHashMap<Object, Boolean> identities, boolean cap) throws Exception
    {
        assertNull(identities.put(core, Boolean.TRUE), "CORE_ALIAS");
        long total = ShadowReachabilityAgent.shallowSize(core);
        ArrayDeque<Object> arrays = new ArrayDeque<>();
        for (var field : MemoryAccess.fields(core.getClass()))
            if (field.getType().isArray()) arrays.add(MemoryAccess.get(core, field.getName()));
        while (!arrays.isEmpty())
        {
            Object array = arrays.removeFirst();
            assertNull(identities.put(array, Boolean.TRUE), "ARRAY_ALIAS");
            long bytes = ShadowReachabilityAgent.shallowSize(array);
            if (cap) assertTrue(bytes <= 262_176L,
                    "CORE_ARRAY_TOO_LARGE type=" + array.getClass().getName() + " length=" + Array.getLength(array) + " shallow=" + bytes);
            total += bytes;
            if (!array.getClass().getComponentType().isPrimitive())
                for (int i = 0; i < Array.getLength(array); i++) arrays.add(Objects.requireNonNull(Array.get(array, i), "NULL_CHUNK"));
        }
        return total; // ChannelLayout is a shared enum boundary; no other owned reference is omitted.
    }

    public static void verifyCore(Object core, IdentityHashMap<Object, Boolean> identities) throws Exception
    {
        long total = deepSize(core, identities, true);
        int n = (Integer) MemoryAccess.get(core, "shortTermWindowFrames"), k = (int) ((n + 1L) / 2);
        assertTrue(total <= 16L * n + 1024L, "CORE_TOTAL_BUDGET " + total);
        long fixed = ShadowReachabilityAgent.shallowSize(core);
        for (String name : List.of("coefficients", "shelfZ1", "shelfZ2", "highPassZ1", "highPassZ2"))
        {
            double[] values = (double[]) MemoryAccess.get(core, name);
            fixed += ShadowReachabilityAgent.shallowSize(values);
            if (!name.equals("coefficients")) for (double v : values) assertEquals(0L, Double.doubleToRawLongBits(v));
        }
        assertTrue(fixed <= 640, "FIXED_STORAGE_BOUND");
        verifyChunks(MemoryAccess.get(core, "weightedPowerRing"), n);
        verifyChunks(MemoryAccess.get(core, "weightedPowerTree"), k);
    }

    private static void verifyChunks(Object value, int length)
    {
        double[][] pages = assertInstanceOf(double[][].class, value);
        assertEquals((length - 1) / 32768 + 1, pages.length, "OUTER_EXTENT");
        int actual = 0;
        for (int p = 0; p < pages.length; p++)
        {
            assertEquals(Math.min(32768, length - p * 32768), pages[p].length, "CHUNK_EXTENT");
            for (double v : pages[p]) assertEquals(0L, Double.doubleToRawLongBits(v), "INITIAL_RAW_PLUS_ZERO");
            actual += pages[p].length;
        }
        assertEquals(length, actual, "NO_PADDING");
    }

    private static void verifyLayoutBounds()
    {
        for (int n = 0; n <= 1024; n++)
        {
            assertTrue(ShadowReachabilityAgent.shallowSize(new Object[n]) <= 32L + 8L * n, "REFERENCE_LAYOUT_BOUND");
            assertTrue(ShadowReachabilityAgent.shallowSize(new double[n]) <= 32L + 8L * n, "ARRAY_HEADER_BOUND");
        }
        for (int n : new int[] {32766,32767,32768})
            assertTrue(ShadowReachabilityAgent.shallowSize(new double[n]) <= 32L + 8L * n);
    }

    private static void exactAllocations() throws Exception
    {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < 100; i++) retained = new LoudnessCore(31, ChannelLayout.MONO_MAIN);
        for (int rate : new int[] {31,48_000,96_000})
        {
            long before = bean.getThreadAllocatedBytes(thread);
            LoudnessCore core = new LoudnessCore(rate, ChannelLayout.MONO_MAIN);
            long bytes = bean.getThreadAllocatedBytes(thread) - before;
            long owned = deepSize(core, new IdentityHashMap<>(), true);
            assertEquals(owned, bytes, "CONSTRUCTOR_NO_DISCARDED_STORAGE rate=" + rate);
            retained = core;
            float[] pcm = {0.03125f};
            for (int i = 0; i < rate * 3 + 100_000; i++) core.acceptFrame(pcm, 0);
            for (int i = 0; i < 100_000; i++) consumed = core.momentaryPower() + core.shortTermPower() + core.framesSeen();
            for (int trial = 0; trial < 3; trial++)
            {
                before = bean.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 100_000; i++)
                {
                    core.acceptFrame(pcm, 0);
                    consumed = core.momentaryPower() + core.shortTermPower() + core.framesSeen();
                }
                long frameBytes = bean.getThreadAllocatedBytes(thread) - before;
                assertEquals(0, frameBytes, "FRAME_GETTER_ALLOCATION rate=" + rate);
            }
            System.out.println("CORE_ALLOCATION rate=" + rate + " constructor=" + bytes + " retained=" + owned + " framesAndGetters=0");
        }
    }

    private static void jfrConstruction(Path root) throws Exception
    {
        for (boolean baseline : new boolean[] {true, false})
        {
            Path file = root.resolve(baseline ? "baseline-constructor.jfr" : "candidate-constructor.jfr");
            try (Recording recording = new Recording())
            {
                recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
                recording.enable("jdk.GarbageCollection");
                recording.start();
                for (int i = 0; i < 8; i++) retained = baseline
                        ? new LoudnessCoreBaseline(48_000, ChannelLayout.MONO_MAIN)
                        : new LoudnessCore(48_000, ChannelLayout.MONO_MAIN);
                recording.stop(); recording.dump(file);
            }
            long count = 0, large = 0;
            for (var event : RecordingFile.readAllEvents(file))
                if (event.getEventType().getName().equals("jdk.ObjectAllocationOutsideTLAB") && event.getStackTrace() != null
                        && event.getStackTrace().getFrames().stream().anyMatch(frame -> frame.getMethod().getType().getName().equals(
                                baseline ? LoudnessCoreBaseline.class.getName() : LoudnessCore.class.getName())))
                { count++; if (event.getLong("allocationSize") > 262176) large++; }
            if (baseline) assertTrue(large >= 2, "JFR_BASELINE_CAUSAL_CONTROL");
            else assertEquals(0, large, "CORE_OUTSIDE_TLAB_LARGE");
            System.out.println("CORE_JFR baseline=" + baseline + " outsideTLAB=" + count + " large=" + large);
        }
    }
}
