package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.ProtectionDecision;
import com.quickmaster.processing.dynamics.leveler.model.ProtectionFlags;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;

/** Irrevocable union of the eight D-301 protection gates. */
public final class ProtectionClassifier
{
    public ProtectionDecision classify(int segmentIndex,
                                       FrozenList<SegmentDescriptor> all,
                                       LevelerCalibrationProfile profile)
    {
        if (all == null || profile == null || segmentIndex < 0 || segmentIndex >= all.size())
        {
            throw new IllegalArgumentException("Invalid protection input.");
        }
        SegmentDescriptor current = all.get(segmentIndex);
        long bits = 0L;
        if (!current.regionalLoudness().present() || current.foregroundRatio() <= 0.5d) bits |= 1L;
        if (segmentIndex == 0) bits |= 1L << 1;
        if (segmentIndex == all.size() - 1) bits |= 1L << 2;
        if (StrictMath.abs(current.loudnessDeltaLu()) >= 3.0d
                && StrictMath.abs(current.loudnessSlopeLuPerSec()) >= 0.25d
                && current.loudnessConsistency() >= 0.75d)
        {
            bits |= 1L << 3;
        }
        if (breakOrBreakdown(segmentIndex, all)) bits |= 1L << 4;
        if (transition(current, all)) bits |= 1L << 5;
        if ((StrictMath.abs(current.loudnessSlopeLuPerSec()) >= 0.10d
                && StrictMath.abs(current.loudnessDeltaLu()) >= 2.0d)
                || StrictMath.abs(current.activitySlopePerSec()) >= 0.01d)
        {
            bits |= 1L << 6;
        }
        if (macroBuildup(segmentIndex, all)) bits |= 1L << 7;
        return new ProtectionDecision(current.id(), new ProtectionFlags(bits));
    }

    private static boolean breakOrBreakdown(int index, FrozenList<SegmentDescriptor> all)
    {
        if (index <= 0 || index + 1 >= all.size()) return false;
        SegmentDescriptor previous = all.get(index - 1);
        SegmentDescriptor current = all.get(index);
        SegmentDescriptor next = all.get(index + 1);
        if (!previous.regionalLoudness().present() || !current.regionalLoudness().present()
                || !next.regionalLoudness().present()) return false;
        return current.regionalLoudness().lufs() <= previous.regionalLoudness().lufs() - 3.0d
                && current.regionalLoudness().lufs() <= next.regionalLoudness().lufs() - 3.0d
                && current.foregroundRatio() <= 0.75d * previous.foregroundRatio()
                && current.foregroundRatio() <= 0.75d * next.foregroundRatio();
    }

    private static boolean transition(SegmentDescriptor current,
                                      FrozenList<SegmentDescriptor> all)
    {
        double[] edgeNovelty = new double[Math.multiplyExact(all.size(), 2)];
        double[] spreads = new double[all.size()];
        for (int i = 0; i < all.size(); i++)
        {
            edgeNovelty[i * 2] = all.get(i).context().leftNoveltyMad();
            edgeNovelty[i * 2 + 1] = all.get(i).context().rightNoveltyMad();
            spreads[i] = all.get(i).activitySpread();
        }
        double noveltyMedian = lowerPercentile(edgeNovelty, 0.5d);
        double[] noveltyDeviation = deviations(edgeNovelty, noveltyMedian);
        double noveltyMad = lowerPercentile(noveltyDeviation, 0.5d);
        double spreadMedian = lowerPercentile(spreads, 0.5d);
        double[] spreadDeviation = deviations(spreads, spreadMedian);
        double spreadMad = lowerPercentile(spreadDeviation, 0.5d);
        double noveltyThreshold = noveltyMedian + 3.0d * noveltyMad;
        double spreadThreshold = spreadMedian + 3.0d * spreadMad;
        return current.context().leftNoveltyMad() > noveltyThreshold
                && current.context().rightNoveltyMad() > noveltyThreshold
                && current.activitySpread() > spreadThreshold;
    }

    private static boolean macroBuildup(int index, FrozenList<SegmentDescriptor> all)
    {
        int first = Math.max(0, index - 2);
        int last = Math.min(all.size() - 1, index + 2);
        if (last - first < 3) return false;
        int loudnessIntervals = 0;
        int loudnessSameSign = 0;
        double loudnessTotal = 0.0d;
        int activityIntervals = 0;
        int activitySameSign = 0;
        double activityTotal = 0.0d;
        double loudnessSign = 0.0d;
        double activitySign = 0.0d;
        for (int i = first + 1; i <= last; i++)
        {
            SegmentDescriptor previous = all.get(i - 1);
            SegmentDescriptor current = all.get(i);
            if (previous.regionalLoudness().present() && current.regionalLoudness().present())
            {
                double difference = current.regionalLoudness().lufs() - previous.regionalLoudness().lufs();
                if (loudnessIntervals == 0) loudnessSign = StrictMath.signum(difference);
                if (StrictMath.signum(difference) == loudnessSign) loudnessSameSign++;
                loudnessTotal += difference;
                loudnessIntervals++;
            }
            double activityDifference = current.foregroundRatio() - previous.foregroundRatio();
            if (activityIntervals == 0) activitySign = StrictMath.signum(activityDifference);
            if (StrictMath.signum(activityDifference) == activitySign) activitySameSign++;
            activityTotal += activityDifference;
            activityIntervals++;
        }
        boolean loudnessBuild = loudnessIntervals >= 3 && loudnessSameSign * 4 >= loudnessIntervals * 3
                && StrictMath.abs(loudnessTotal) >= 3.0d;
        boolean activityBuild = activityIntervals >= 3 && activitySameSign * 4 >= activityIntervals * 3
                && StrictMath.abs(activityTotal) >= 0.20d;
        return loudnessBuild || activityBuild;
    }

    private static double[] deviations(double[] values, double median)
    {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = StrictMath.abs(values[i] - median);
        return result;
    }

    private static double lowerPercentile(double[] values, double percentile)
    {
        if (values.length == 0) return 0.0d;
        double[] sorted = new double[values.length];
        System.arraycopy(values, 0, sorted, 0, values.length);
        Arrays.sort(sorted);
        int index = Math.max(0, (int) StrictMath.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }
}
