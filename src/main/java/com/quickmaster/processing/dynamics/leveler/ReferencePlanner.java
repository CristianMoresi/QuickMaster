package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.quickmaster.processing.dynamics.leveler.model.ComparableGroup;
import com.quickmaster.processing.dynamics.leveler.model.ComparablePair;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.GroupingResult;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;
import com.quickmaster.processing.dynamics.leveler.model.ReferencePlan;
import com.quickmaster.processing.dynamics.leveler.model.ReferenceReason;
import com.quickmaster.processing.dynamics.leveler.model.ReferenceTarget;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;

/** Robust within-cohort reference and raw confidence-weighted target. */
public final class ReferencePlanner
{
    public ReferencePlan plan(GroupingResult groups,
                              FrozenList<SegmentDescriptor> segments,
                              LevelerCalibrationProfile profile)
    {
        if (groups == null || segments == null || profile == null || segments.size() > 64)
        {
            throw new IllegalArgumentException("Invalid reference-planning input.");
        }
        ReferenceTarget[] targets = new ReferenceTarget[segments.size()];
        double[] weights = new double[segments.size()];
        int[] indicesByOrdinal = new int[64];
        Arrays.fill(indicesByOrdinal, -1);
        for (int i = 0; i < segments.size(); i++)
        {
            int ordinal = segments.get(i).id().ordinal();
            if (indicesByOrdinal[ordinal] >= 0) throw new IllegalArgumentException("Duplicate segment ID.");
            indicesByOrdinal[ordinal] = i;
            targets[i] = unit(segments.get(i), ReferenceReason.NOT_COMPARABLE);
        }

        for (int groupIndex = 0; groupIndex < groups.groups().size(); groupIndex++)
        {
            ComparableGroup group = groups.groups().get(groupIndex);
            double[] groupWeights = waterFill(group);
            boolean complete = true;
            for (int i = 0; i < group.size(); i++)
            {
                int index = indicesByOrdinal[group.memberOrdinalAt(i)];
                if (index < 0 || !segments.get(index).regionalLoudness().present()) complete = false;
            }
            if (!complete) continue;
            double reference = weightedMedian(group, groupWeights, segments, indicesByOrdinal);
            ReferenceTarget[] groupTargets = new ReferenceTarget[group.size()];
            for (int i = 0; i < group.size(); i++)
            {
                SegmentDescriptor segment = segments.get(indicesByOrdinal[group.memberOrdinalAt(i)]);
                groupTargets[i] = target(segment, reference, group.confidence(),
                        ReferenceReason.GROUP_REFERENCE, profile.deadbandLu());
                if (!groupTargets[i].referenceLoudness().present()) complete = false;
            }
            // A cohort whose arithmetic is not finite has no valid normalized reference weights.
            for (int i = 0; i < group.size(); i++)
            {
                int index = indicesByOrdinal[group.memberOrdinalAt(i)];
                targets[index] = complete ? groupTargets[i]
                        : unit(segments.get(index), ReferenceReason.INSUFFICIENT_LOUDNESS);
                weights[index] = complete ? groupWeights[i] : 0.0d;
            }
        }

        for (int pairIndex = 0; pairIndex < groups.pairs().size(); pairIndex++)
        {
            ComparablePair pair = groups.pairs().get(pairIndex);
            int firstIndex = indicesByOrdinal[pair.firstOrdinal()];
            int secondIndex = indicesByOrdinal[pair.secondOrdinal()];
            if (firstIndex < 0 || secondIndex < 0) continue;
            SegmentDescriptor first = segments.get(firstIndex);
            SegmentDescriptor second = segments.get(secondIndex);
            if (!first.regionalLoudness().present() || !second.regionalLoudness().present()) continue;
            double reference = (first.regionalLoudness().lufs() + second.regionalLoudness().lufs()) / 2.0d;
            ReferenceTarget firstTarget = target(first, reference, pair.confidence(),
                    ReferenceReason.PAIR_REFERENCE, profile.deadbandLu());
            ReferenceTarget secondTarget = target(second, reference, pair.confidence(),
                    ReferenceReason.PAIR_REFERENCE, profile.deadbandLu());
            if (!firstTarget.referenceLoudness().present() || !secondTarget.referenceLoudness().present()) continue;
            weights[firstIndex] = 0.5d;
            weights[secondIndex] = 0.5d;
            targets[firstIndex] = firstTarget;
            targets[secondIndex] = secondTarget;
        }

        Object[] targetObjects = new Object[targets.length];
        System.arraycopy(targets, 0, targetObjects, 0, targets.length);
        return new ReferencePlan(new FrozenList<ReferenceTarget>(targetObjects), weights);
    }

    private static ReferenceTarget target(SegmentDescriptor segment,
                                          double reference,
                                          double confidence,
                                          ReferenceReason reason,
                                          double deadband)
    {
        if (!Double.isFinite(reference) || !Double.isFinite(confidence)
                || confidence < 0.0d || confidence > 1.0d || !segment.regionalLoudness().present())
        {
            return unit(segment, ReferenceReason.INSUFFICIENT_LOUDNESS);
        }
        double difference = reference - segment.regionalLoudness().lufs();
        double raw = StrictMath.copySign(Math.max(0.0d, StrictMath.abs(difference) - deadband), difference);
        if (raw == 0.0d) raw = 0.0d;
        double weighted = raw * confidence;
        if (!Double.isFinite(difference) || !Double.isFinite(raw) || !Double.isFinite(weighted))
        {
            return unit(segment, ReferenceReason.INSUFFICIENT_LOUDNESS);
        }
        return new ReferenceTarget(segment.id(), new MeasuredLoudness(true, reference),
                raw, confidence, weighted, reason);
    }

    private static ReferenceTarget unit(SegmentDescriptor segment, ReferenceReason reason)
    {
        return new ReferenceTarget(segment.id(), MeasuredLoudness.absent(),
                0.0d, 0.0d, 0.0d, reason);
    }

    private static double[] waterFill(ComparableGroup group)
    {
        int size = group.size();
        double[] utility = new double[size];
        double[] weights = new double[size];
        boolean[] active = new boolean[size];
        double remaining = 1.0d;
        for (int i = 0; i < size; i++)
        {
            utility[i] = 0.5d + 0.5d * group.memberQualityAt(i);
            active[i] = true;
        }
        while (true)
        {
            double utilitySum = 0.0d;
            for (int i = 0; i < size; i++) if (active[i]) utilitySum += utility[i];
            int capped = -1;
            for (int i = 0; i < size; i++)
            {
                if (active[i] && remaining * utility[i] / utilitySum > 0.40d)
                {
                    capped = i;
                    break;
                }
            }
            if (capped < 0)
            {
                for (int i = 0; i < size; i++)
                {
                    if (active[i]) weights[i] = remaining * utility[i] / utilitySum;
                }
                break;
            }
            weights[capped] = 0.40d;
            active[capped] = false;
            remaining -= 0.40d;
        }
        double sum = 0.0d;
        for (int i = 0; i < weights.length; i++)
        {
            if (!Double.isFinite(weights[i]) || weights[i] < 0.0d || weights[i] > 0.40d)
                throw new IllegalStateException("Reference weight exceeds its cap.");
            sum += weights[i];
        }
        if (StrictMath.abs(sum - 1.0d) > 1.0e-12d)
        {
            throw new IllegalStateException("Reference weights do not sum to one.");
        }
        return weights;
    }

    private static double weightedMedian(ComparableGroup group,
                                         double[] weights,
                                         FrozenList<SegmentDescriptor> segments,
                                         int[] indicesByOrdinal)
    {
        int[] order = new int[group.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        for (int i = 1; i < order.length; i++)
        {
            int value = order[i];
            int insertion = i;
            while (insertion > 0 && before(value, order[insertion - 1], group, segments, indicesByOrdinal))
            {
                order[insertion] = order[insertion - 1];
                insertion--;
            }
            order[insertion] = value;
        }
        double cumulative = 0.0d;
        for (int i = 0; i < order.length; i++)
        {
            int memberIndex = order[i];
            cumulative += weights[memberIndex];
            if (cumulative >= 0.5d)
            {
                return segments.get(indicesByOrdinal[group.memberOrdinalAt(memberIndex)]).regionalLoudness().lufs();
            }
        }
        return segments.get(indicesByOrdinal[group.memberOrdinalAt(order[order.length - 1])]).regionalLoudness().lufs();
    }

    private static boolean before(int firstIndex,
                                  int secondIndex,
                                  ComparableGroup group,
                                  FrozenList<SegmentDescriptor> segments,
                                  int[] indicesByOrdinal)
    {
        int firstOrdinal = group.memberOrdinalAt(firstIndex);
        int secondOrdinal = group.memberOrdinalAt(secondIndex);
        double firstLufs = segments.get(indicesByOrdinal[firstOrdinal]).regionalLoudness().lufs();
        double secondLufs = segments.get(indicesByOrdinal[secondOrdinal]).regionalLoudness().lufs();
        return firstLufs < secondLufs || (firstLufs == secondLufs && firstOrdinal < secondOrdinal);
    }
}
