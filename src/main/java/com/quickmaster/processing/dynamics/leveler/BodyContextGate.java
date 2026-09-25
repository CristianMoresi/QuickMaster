package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.BodyContextVector;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.PcmSketch;
import com.quickmaster.processing.dynamics.leveler.model.ProtectionDecision;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;

/** Independent body/context and gain-scaled-intent gate. */
public final class BodyContextGate
{
    public BodyEligibility evaluate(int segmentIndex,
                                    FrozenList<SegmentDescriptor> all,
                                    FrozenList<ProtectionDecision> protections)
    {
        if (all == null || protections == null || all.size() != protections.size()
                || segmentIndex < 0 || segmentIndex >= all.size())
        {
            throw new IllegalArgumentException("Invalid body-gate input.");
        }
        if (protections.get(segmentIndex).isBlocked())
        {
            return new BodyEligibility(segmentIndex, false, SimilarityRejectionReason.PROTECTED);
        }
        if (segmentIndex == 0 || segmentIndex + 1 >= all.size()
                || (protections.get(segmentIndex - 1).flags().reasonBits() & (1L << 5)) != 0L
                || (protections.get(segmentIndex + 1).flags().reasonBits() & (1L << 5)) != 0L)
        {
            return new BodyEligibility(segmentIndex, false, SimilarityRejectionReason.BODY_INELIGIBLE);
        }
        SegmentDescriptor current = all.get(segmentIndex);
        SegmentDescriptor previous = all.get(segmentIndex - 1);
        SegmentDescriptor next = all.get(segmentIndex + 1);
        boolean stable = current.foregroundRatio() >= 0.80d
                && current.regionalLoudness().present()
                && current.context().loudnessAvailabilityMask() == 7
                && current.activitySpread() <= 0.15d
                && StrictMath.abs(current.activitySlopePerSec()) < 0.01d
                && StrictMath.abs(current.loudnessSlopeLuPerSec()) < 0.10d
                && StrictMath.abs(current.loudnessDeltaLu()) < 2.0d
                && previous.foregroundRatio() >= 0.50d
                && next.foregroundRatio() >= 0.50d
                && finiteContext(current.context());
        return stable
                ? new BodyEligibility(segmentIndex, true, SimilarityRejectionReason.NONE)
                : new BodyEligibility(segmentIndex, false, SimilarityRejectionReason.BODY_INELIGIBLE);
    }

    public SimilarityRejectionReason compare(SegmentDescriptor first, SegmentDescriptor second)
    {
        if (first == null || second == null) return SimilarityRejectionReason.NON_FINITE;
        if (first.context().loudnessAvailabilityMask() != 7
                || second.context().loudnessAvailabilityMask() != 7)
        {
            return SimilarityRejectionReason.CONTEXT_MISMATCH;
        }
        if (!finiteContext(first.context()) || !finiteContext(second.context()))
        {
            return SimilarityRejectionReason.NON_FINITE;
        }
        if (!sameSignClass(first.context().entryLoudness12(), second.context().entryLoudness12())
                || !sameSignClass(first.context().exitLoudness12(), second.context().exitLoudness12()))
        {
            return SimilarityRejectionReason.CONTEXT_MISMATCH;
        }
        double distance = contextDistance(first.context(), second.context());
        if (!Double.isFinite(distance)) return SimilarityRejectionReason.NON_FINITE;
        if (distance > 1.0d) return SimilarityRejectionReason.CONTEXT_MISMATCH;
        double[] assessment = assess(first.sketch(), second.sketch(), first.context(), second.context());
        if (assessment == null) return SimilarityRejectionReason.DEGENERATE_SKETCH;
        if (ambiguous(assessment[2], assessment[3]))
        {
            return SimilarityRejectionReason.INTENT_UNIDENTIFIABLE;
        }
        return SimilarityRejectionReason.NONE;
    }

    /** Returns {lag, alpha, R_scale, D_ctx}; used by discriminating pure tests. */
    public double[] assess(PcmSketch first,
                           PcmSketch second,
                           BodyContextVector firstContext,
                           BodyContextVector secondContext)
    {
        if (first == null || second == null || firstContext == null || secondContext == null
                || firstContext.loudnessAvailabilityMask() != 7
                || secondContext.loudnessAvailabilityMask() != 7
                || first.channels() != second.channels() || first.bins() != 2048
                || second.bins() != 2048) return null;
        double contextDistance = intentContextDistance(firstContext, secondContext);
        if (!Double.isFinite(contextDistance)) return null;
        double bestResidual = Double.POSITIVE_INFINITY;
        double bestAlpha = 0.0d;
        int bestLag = 0;
        boolean found = false;
        for (int lag = -4; lag <= 4; lag++)
        {
            int overlap = 2048 - StrictMath.abs(lag);
            if ((double) overlap / 2048.0d < 0.99d) continue;
            double sx = 0.0d;
            double sy = 0.0d;
            double sxy = 0.0d;
            for (int bin = 0; bin < 2048; bin++)
            {
                int otherBin = bin + lag;
                if (otherBin < 0 || otherBin >= 2048) continue;
                for (int channel = 0; channel < first.channels(); channel++)
                {
                    double x = first.valueAt(channel, bin);
                    double y = second.valueAt(channel, otherBin);
                    sx += x * x;
                    sy += y * y;
                    sxy += x * y;
                }
            }
            double count = (double) first.channels() * overlap;
            sx /= count;
            sy /= count;
            sxy /= count;
            double normX = StrictMath.sqrt(sx);
            double normY = StrictMath.sqrt(sy);
            if (!Double.isFinite(normX) || !Double.isFinite(normY)
                    || normX <= 1.0e-12d || normY <= 1.0e-12d) continue;
            double alpha = sxy / sy;
            if (!Double.isFinite(alpha) || alpha <= 0.0d) continue;
            double residualPower = 0.0d;
            for (int bin = 0; bin < 2048; bin++)
            {
                int otherBin = bin + lag;
                if (otherBin < 0 || otherBin >= 2048) continue;
                for (int channel = 0; channel < first.channels(); channel++)
                {
                    double residual = first.valueAt(channel, bin)
                            - alpha * second.valueAt(channel, otherBin);
                    residualPower += residual * residual;
                }
            }
            residualPower /= count;
            double residual = StrictMath.sqrt(residualPower)
                    / Math.max(Math.max(normX, StrictMath.abs(alpha) * normY), 1.0e-12d);
            if (!Double.isFinite(residual) || residual < 0.0d) continue;
            if (!found || residual < bestResidual - 1.0e-12d
                    || (StrictMath.abs(residual - bestResidual) <= 1.0e-12d
                        && (StrictMath.abs(lag) < StrictMath.abs(bestLag)
                            || (StrictMath.abs(lag) == StrictMath.abs(bestLag) && lag < bestLag))))
            {
                found = true;
                bestResidual = residual;
                bestAlpha = alpha;
                bestLag = lag;
            }
        }
        return found ? new double[] { bestLag, bestAlpha, bestResidual, contextDistance } : null;
    }

    private static double contextDistance(BodyContextVector first, BodyContextVector second)
    {
        if (!finiteContext(first) || !finiteContext(second)) return Double.NaN;
        double[] tolerance = new double[] { 0.25d, 0.25d, 0.25d, 0.25d, 1.0d, 1.0d };
        double maximum = 0.0d;
        for (int i = 0; i < 6; i++)
        {
            double distance = StrictMath.abs(first.componentAt(i) - second.componentAt(i)) / tolerance[i];
            if (distance > maximum) maximum = distance;
        }
        return maximum;
    }

    private static double intentContextDistance(BodyContextVector first, BodyContextVector second)
    {
        if (!finiteContext(first) || !finiteContext(second)) return Double.NaN;
        // Different harmonic neighbors produce different boundary novelty even
        // around a literal gain-scaled copy. Novelty remains in the six-value
        // comparability gate, but cannot establish an identifiable gain intent.
        double maximum = 0.0d;
        for (int i = 0; i < 4; i++)
        {
            maximum = Math.max(maximum,
                    StrictMath.abs(first.componentAt(i) - second.componentAt(i)) / 0.25d);
        }
        return maximum;
    }

    private static boolean finiteContext(BodyContextVector context)
    {
        if (context == null) return false;
        for (int i = 0; i < 6; i++) if (!Double.isFinite(context.componentAt(i))) return false;
        return true;
    }

    private static boolean ambiguous(double residual, double contextDistance)
    {
        return Double.isFinite(residual) && residual >= 0.0d
                && Double.isFinite(contextDistance) && contextDistance >= 0.0d
                && residual <= 1.0e-4d && contextDistance <= 0.05d;
    }

    private static boolean sameSignClass(double first, double second)
    {
        return signClass(first * 12.0d) == signClass(second * 12.0d);
    }

    private static int signClass(double value)
    {
        if (value < -1.0d) return -1;
        if (value > 1.0d) return 1;
        return 0;
    }
}
