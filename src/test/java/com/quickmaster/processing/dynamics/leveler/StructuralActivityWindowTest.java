package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.BitSet;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;

/** Preserves original clock, strict activity/gates, mask and selected-value order. */
class StructuralActivityWindowTest
{
    @Test
    void boundedActivityMatchesFullClockForEverySmallIntervalAndMask() throws Exception
    {
        Method activity = StructuralFeatureExtractor.class.getDeclaredMethod(
                "activity", LoudnessTimeline.class, int.class, int.class);
        activity.setAccessible(true);
        for (int hop : new int[] { 1, 3, 17, 4_410, 4_800 })
            for (int mask = 0; mask < 16; mask++)
            {
                LoudnessTimeline timeline = timeline(19, hop, mask);
                for (int startIndex = 0; startIndex <= 20; startIndex++)
                    for (int width = 0; width <= 5; width++)
                        for (int offset : new int[] { 0, 1, Math.max(1, hop - 1) })
                        {
                            int start = startIndex * hop + offset;
                            int end = start + width * hop + offset;
                            double actual = (double) activity.invoke(null, timeline, start, end);
                            assertEquals(Double.doubleToRawLongBits(fullActivity(timeline, start, end)),
                                    Double.doubleToRawLongBits(actual),
                                    "Activity clock/mask changed at " + start + ".." + end + " hop=" + hop);
                        }
            }
        LoudnessTimeline sparse = timeline(4, Integer.MAX_VALUE, 15);
        assertEquals(fullActivity(sparse, Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
                (double) activity.invoke(null, sparse, Integer.MAX_VALUE - 1, Integer.MAX_VALUE), 0d);
    }

    @Test
    void boundedRegionalSelectionPreservesOriginalFullWindowGateAndMedian()
    {
        LoudnessAnalyzer analyzer = new LoudnessAnalyzer();
        for (int hop : new int[] { 1, 3, 17, 4_410, 4_800 })
            for (int mask = 0; mask < 16; mask++)
            {
                LoudnessTimeline timeline = timeline(59, hop, mask);
                for (int start = 0; start < 61; start++)
                    for (int span : new int[] { 0, 1, 3, 7, 23, 80 })
                    {
                        FrameRange range = new FrameRange((long) start * hop + 1,
                                (long) (start + span) * hop + 2);
                        MeasuredLoudness expected = fullRegional(timeline, range, 1);
                        MeasuredLoudness actual = analyzer.regionalLoudness(timeline, range, 1);
                        assertEquals(expected.present(), actual.present());
                        assertEquals(Double.doubleToRawLongBits(expected.lufs()), Double.doubleToRawLongBits(actual.lufs()));
                    }
            }
        assertFalse(analyzer.regionalLoudness(timeline(59, 3, 15),
                new FrameRange(Long.MAX_VALUE - 30, Long.MAX_VALUE), 1).present());
    }

    @Test
    void regionalScratchAllocationDependsOnRelevantWindowsInsteadOfTrackLength()
    {
        var management = ManagementFactory.getThreadMXBean();
        assertInstanceOf(com.sun.management.ThreadMXBean.class, management);
        var allocation = (com.sun.management.ThreadMXBean) management;
        assertTrue(allocation.isThreadAllocatedMemorySupported(), "Supported-Java allocation evidence is required");
        if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        LoudnessAnalyzer analyzer = new LoudnessAnalyzer();
        LoudnessTimeline small = timeline(100, 3, 15), large = timeline(100_000, 3, 15);
        FrameRange range = new FrameRange(21, 84);
        for (int i = 0; i < 2_000; i++) analyzer.regionalLoudness(small, range, 1);
        long thread = Thread.currentThread().getId();
        long beforeSmall = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100; i++) analyzer.regionalLoudness(small, range, 1);
        long smallBytes = allocation.getThreadAllocatedBytes(thread) - beforeSmall;
        long beforeLarge = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100; i++) analyzer.regionalLoudness(large, range, 1);
        long largeBytes = allocation.getThreadAllocatedBytes(thread) - beforeLarge;
        assertTrue(largeBytes <= smallBytes + 4_096L,
                "Regional scratch follows unrelated track length: small=" + smallBytes + " large=" + largeBytes);
    }

    private static LoudnessTimeline timeline(int count, int hop, int mask)
    {
        double[] powers = new double[count], lufs = new double[count];
        BitSet valid = new BitSet(count);
        double[] levels = { -90d, -70d, Math.nextUp(-70d), -45d, -20d, -19d, -80d };
        for (int i = 0; i < count; i++)
            if ((mask & (1 << (i % 4))) != 0)
            {
                valid.set(i);
                lufs[i] = levels[i % levels.length];
                powers[i] = i % 11 == 0 ? 0d : StrictMath.pow(10d, (lufs[i] + .691d) / 10d);
            }
        return new LoudnessTimeline(4, 9, hop, powers, valid, lufs, valid, MeasuredLoudness.absent());
    }

    private static double fullActivity(LoudnessTimeline timeline, int start, int end)
    {
        int present = 0, active = 0;
        for (int i = 0; i < timeline.momentaryCount(); i++)
        {
            long position = Math.multiplyExact((long) i, timeline.hopFrames());
            if (position >= start && position < end && timeline.momentaryValidAt(i))
            {
                present++;
                double lufs = -.691d + 10d * StrictMath.log10(Math.max(timeline.momentaryPowerAt(i), 1e-30d));
                if (lufs > -70d) active++;
            }
        }
        return present == 0 ? 0d : (double) active / present;
    }

    private static MeasuredLoudness fullRegional(LoudnessTimeline timeline, FrameRange range, int rate)
    {
        if (range.endExclusive() - range.startInclusive() < StrictMath.round(3d * rate))
            return MeasuredLoudness.absent();
        int possible = 0, valid = 0;
        double[] values = new double[timeline.shortTermCount()];
        for (int i = 0; i < timeline.shortTermCount(); i++)
        {
            long start = Math.multiplyExact((long) i, timeline.hopFrames());
            long end = Math.addExact(start, timeline.shortTermWindowFrames());
            if (start >= range.startInclusive() && end <= range.endExclusive())
            {
                possible++;
                if (timeline.shortTermValidAt(i)) values[valid++] = timeline.shortTermLufsAt(i);
            }
        }
        if (possible == 0 || valid < 3 || (long) valid * 2L < possible) return MeasuredLoudness.absent();
        double sum = 0d;
        int count = 0;
        for (int i = 0; i < valid; i++)
            if (values[i] > -70d) { sum += StrictMath.pow(10d, (values[i] + .691d) / 10d); count++; }
        if (count == 0) return MeasuredLoudness.absent();
        double gate = LoudnessCore.fromPower(sum / count).lufs() - 20d;
        int selected = 0;
        for (int i = 0; i < valid; i++)
            if (values[i] > -70d && values[i] > gate) values[selected++] = values[i];
        if (selected < 3) return MeasuredLoudness.absent();
        Arrays.sort(values, 0, selected);
        return new MeasuredLoudness(true, values[(selected - 1) / 2]);
    }
}
