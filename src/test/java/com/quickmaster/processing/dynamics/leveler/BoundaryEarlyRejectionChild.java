package com.quickmaster.processing.dynamics.leveler;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.LayoutStatus;
import com.quickmaster.processing.dynamics.leveler.model.SegmentLayout;
import com.quickmaster.processing.dynamics.leveler.model.StructuralFrame;

/** Small-heap measurement process; fixtures and result checks are outside allocation deltas. */
public final class BoundaryEarlyRejectionChild
{
    record Case(String name, long frames, long hop, long[] centers, boolean valid) { }
    record Measurement(String name, long frames, long hop, int count, int invocations,
                       int warmups, int measurements, long minimumBytes, long maximumBytes,
                       boolean statusCorrect, String error) { }
    record Result(String javaHome, String javaVersion, boolean allocationSupported,
                  boolean allocationEnabled, int totalInvocations, List<Measurement> cases) { }

    public static void main(String[] args)
    {
        List<Measurement> results = new ArrayList<>();
        int calls = 0;
        boolean supported = false;
        boolean enabled = false;
        boolean correct = true;
        try
        {
            com.sun.management.ThreadMXBean allocations =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            supported = allocations.isThreadAllocatedMemorySupported();
            if (!supported) throw new IllegalStateException("Thread allocation counter unavailable");
            allocations.setThreadAllocatedMemoryEnabled(true);
            enabled = allocations.isThreadAllocatedMemoryEnabled();
            if (!enabled) throw new IllegalStateException("Thread allocation counter not enabled");
            long thread = Thread.currentThread().getId();
            if (allocations.getThreadAllocatedBytes(thread) < 0L)
                throw new IllegalStateException("Thread allocation counter returned a negative reading");
            for (Case testCase : cases(args[0]))
            {
                Measurement measurement = measure(testCase, allocations, thread);
                results.add(measurement);
                calls += measurement.invocations;
                correct &= measurement.statusCorrect && measurement.error.isEmpty();
            }
        }
        catch (Throwable error)
        {
            correct = false;
            results.add(new Measurement("SETUP", 0L, 0L, 0, 0, 0, 0, -1L, -1L,
                    false, error.getClass().getName() + ": " + error.getMessage()));
        }
        Result result = new Result(System.getProperty("java.home"), System.getProperty("java.runtime.version"),
                supported, enabled, calls, results);
        System.out.println(new Gson().toJson(result));
        if (!correct) System.exit(2);
    }

    private static Measurement measure(Case testCase, com.sun.management.ThreadMXBean allocations,
                                       long thread)
    {
        FeatureTimeline features = timeline(testCase);
        BoundaryDetector detector = new BoundaryDetector();
        LevelerCalibrationProfile profile = LevelerCalibrationProfile.V1;
        int calls = 0;
        int warmups = 0;
        int measurements = 0;
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        boolean correct = true;
        String failure = "";
        try
        {
            for (int i = 0; i < 128; i++)
            {
                calls++;
                SegmentLayout result = detector.detect(features, testCase.frames, profile);
                BoundaryAllocationSink.clear();
                correct &= matches(result, testCase);
                warmups++;
            }
            for (int i = 0; i < 32; i++)
            {
                calls++;
                long before = allocations.getThreadAllocatedBytes(thread);
                SegmentLayout result = detector.detect(features, testCase.frames, profile);
                long after = allocations.getThreadAllocatedBytes(thread);
                BoundaryAllocationSink.clear();
                if (before < 0L || after < before)
                    throw new IllegalStateException("Invalid allocation readings");
                long allocated = after - before;
                minimum = Math.min(minimum, allocated);
                maximum = Math.max(maximum, allocated);
                correct &= matches(result, testCase);
                measurements++;
            }
        }
        catch (Throwable error)
        {
            BoundaryAllocationSink.clear();
            failure = error.getClass().getName() + ": " + error.getMessage();
            correct = false;
        }
        return new Measurement(testCase.name, testCase.frames, testCase.hop, features.size(), calls,
                warmups, measurements, minimum, maximum, correct, failure);
    }

    private static boolean matches(SegmentLayout layout, Case testCase)
    {
        if (layout == null) return false;
        if (!testCase.valid)
            return layout.status() == LayoutStatus.INSUFFICIENT_FEATURES && layout.regions().size() == 0;
        return layout.status() == LayoutStatus.READY && layout.regions().size() == 1
                && layout.regions().get(0).startInclusive() == 0L
                && layout.regions().get(0).endExclusive() == testCase.frames;
    }

    private static FeatureTimeline timeline(Case testCase)
    {
        Object[] frames = new Object[testCase.centers.length];
        for (int i = 0; i < frames.length; i++)
            frames[i] = new StructuralFrame(testCase.centers[i], new double[12], new double[8], 0.0d, 0.0d);
        return new FeatureTimeline(testCase.hop, new FrozenList<StructuralFrame>(frames));
    }

    private static List<Case> cases(String selection)
    {
        List<Case> invalid = List.of(
                new Case("small", 2L, 1L, new long[] { 0L }, false),
                new Case("alias", 4_294_967_297L, 1L, new long[] { 0L }, false),
                new Case("mib", 4_296_015_872L, 1L, new long[] { 0L }, false),
                new Case("heap", 5_368_709_119L, 1L, new long[] { 0L }, false),
                new Case("overflow", Long.MAX_VALUE, 1L, new long[] { 0L }, false),
                new Case("center", 48_000L, 24_000L, new long[] { 11_999L, 36_000L }, false));
        if (selection.equals("invalid")) return invalid;
        if (selection.equals("positive")) return List.of(
                new Case("positive", 48_000L, 24_000L, new long[] { 11_999L, 35_999L }, true));
        if (selection.equals("minimum")) return List.of(new Case("minimum", 1L, 1L, new long[] { 0L }, true));
        if (selection.equals("maximum")) return List.of(new Case("maximum", Long.MAX_VALUE,
                Long.MAX_VALUE, new long[] { 4_611_686_018_427_387_903L }, true));
        for (Case candidate : invalid) if (candidate.name.equals(selection)) return List.of(candidate);
        throw new IllegalArgumentException("Unknown child selection: " + selection);
    }
}
