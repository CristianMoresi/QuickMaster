package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityScore;
import com.quickmaster.processing.dynamics.leveler.model.StructuralBin;

/** Bounded H/T/A/C comparison with a single joint validity mask. */
public final class SegmentComparator
{
    public SimilarityScore compare(SegmentDescriptor first,
                                   SegmentDescriptor second,
                                   AudioFormat source,
                                   FeatureTimeline features,
                                   LevelerCalibrationProfile profile)
    {
        if (first == null || second == null || source == null || features == null || profile == null
                || source.frames() <= 0L || first.range().startInclusive() < 0L
                || second.range().startInclusive() < 0L
                || first.range().endExclusive() > source.frames() || second.range().endExclusive() > source.frames()
                || first.range().lengthFrames() <= 0L || second.range().lengthFrames() <= 0L)
        {
            return rejected(SimilarityRejectionReason.NON_FINITE, 0);
        }
        long delta = Math.max(1L, StrictMath.round(0.5d * source.sampleRateHz()));
        if (features.hopFrames() != delta
                || 1L + (source.frames() - 1L) / delta != (long) features.size())
            return rejected(SimilarityRejectionReason.NON_FINITE, 0);

        double[] scores = new double[4];
        int[] details = new int[2];
        SimilarityRejectionReason reason = kernel(first.bins(), second.bins(),
                first.validBinMask(), second.validBinMask(), first.range().lengthFrames(),
                second.range().lengthFrames(), profile, scores, details);
        if (reason != SimilarityRejectionReason.NONE) return rejected(reason, details[1]);
        double harmonic = scores[0];
        double timbre = scores[1];
        double alignment = scores[2];
        double combined = scores[3];
        int rotation = details[0];
        int validCount = details[1];
        for (int slot = 0; slot < 8; slot++)
        {
            boolean changeFirst = slot < 4;
            FrameRange original = changeFirst ? first.range() : second.range();
            boolean changeStart = slot % 4 < 2;
            long boundary = changeStart ? original.startInclusive() : original.endExclusive();
            long changed = slot % 2 == 0 ? (boundary < delta ? 0L : boundary - delta)
                    : (source.frames() - boundary < delta ? source.frames() : boundary + delta);
            long start = changeStart ? changed : original.startInclusive();
            long end = changeStart ? original.endExclusive() : changed;
            if (start >= end) return rejected(SimilarityRejectionReason.UNSTABLE_BOUNDARY, validCount);
            FrozenList<StructuralBin> perturbed = SegmentDescriptorBuilder.structuralBins(
                    features, new FrameRange(start, end), source.frames());
            if (perturbed == null) return rejected(SimilarityRejectionReason.UNSTABLE_BOUNDARY, validCount);
            reason = kernel(changeFirst ? perturbed : first.bins(), changeFirst ? second.bins() : perturbed,
                    changeFirst ? 0xFFFF_FFFFL : first.validBinMask(),
                    changeFirst ? second.validBinMask() : 0xFFFF_FFFFL,
                    changeFirst ? end - start : first.range().lengthFrames(),
                    changeFirst ? second.range().lengthFrames() : end - start, profile, scores, details);
            if (reason != SimilarityRejectionReason.NONE || !stableAlignment(alignment, scores[2]))
                return rejected(SimilarityRejectionReason.UNSTABLE_BOUNDARY, validCount);
        }
        return new SimilarityScore(harmonic, timbre, alignment, combined,
                rotation, validCount, SimilarityRejectionReason.NONE);
    }

    /** One score kernel for the central observation and every independently re-extracted variant. */
    private static SimilarityRejectionReason kernel(FrozenList<StructuralBin> first,
                                                     FrozenList<StructuralBin> second,
                                                     long firstMask, long secondMask,
                                                     long firstDuration, long secondDuration,
                                                     LevelerCalibrationProfile profile,
                                                     double[] scores, int[] details)
    {
        details[0] = 0;
        details[1] = 0;
        double ratio = (double) firstDuration / secondDuration;
        if (!Double.isFinite(ratio) || ratio < profile.durationRatioMinimum()
                || ratio > profile.durationRatioMaximum())
        {
            return SimilarityRejectionReason.DURATION_RATIO;
        }

        int[] validIndices = new int[32];
        int validCount = 0;
        int invalidRun = 0;
        int maximumInvalidRun = 0;
        for (int bin = 0; bin < 32; bin++)
        {
            boolean mask = (firstMask & (1L << bin)) != 0L
                    && (secondMask & (1L << bin)) != 0L
                    && valid(first.get(bin)) && valid(second.get(bin));
            if (mask)
            {
                validIndices[validCount++] = bin;
                invalidRun = 0;
            }
            else
            {
                invalidRun++;
                if (invalidRun > maximumInvalidRun) maximumInvalidRun = invalidRun;
            }
        }
        details[1] = validCount;
        if (validCount < profile.minimumValidBins())
        {
            return SimilarityRejectionReason.INSUFFICIENT_VALID_BINS;
        }
        if (maximumInvalidRun > profile.maximumInvalidRun())
        {
            return SimilarityRejectionReason.INVALID_BIN_RUN;
        }

        double[] rotationMeans = new double[12];
        for (int rotation = 0; rotation < 12; rotation++)
        {
            double sum = 0.0d;
            for (int i = 0; i < validCount; i++)
            {
                sum += clamp(chromaCosine(first.get(validIndices[i]),
                        second.get(validIndices[i]), rotation), 0.0d, 1.0d);
            }
            rotationMeans[rotation] = sum / validCount;
        }
        int bestRotation = 0;
        for (int rotation = 1; rotation < 12; rotation++)
        {
            if (rotationMeans[rotation] > rotationMeans[bestRotation] + 1.0e-12d)
            {
                bestRotation = rotation;
            }
        }
        if (bestRotation != 0)
        {
            int bestPerBin = 0;
            for (int i = 0; i < validCount; i++)
            {
                StructuralBin a = first.get(validIndices[i]);
                StructuralBin b = second.get(validIndices[i]);
                double chosen = chromaCosine(a, b, bestRotation);
                double maximum = chosen;
                for (int rotation = 0; rotation < 12; rotation++)
                {
                    double candidate = chromaCosine(a, b, rotation);
                    if (candidate > maximum) maximum = candidate;
                }
                if (chosen >= maximum - 1.0e-12d) bestPerBin++;
            }
            if (bestPerBin * 5 < validCount * 4)
            {
                return SimilarityRejectionReason.AMBIGUOUS;
            }
        }

        double rho = validCount / 32.0d;
        double harmonicRaw = rotationMeans[bestRotation];
        double harmonic = rho * clamp(harmonicRaw - (bestRotation == 0 ? 0.0d : 0.05d), 0.0d, 1.0d);
        double timbreSum = 0.0d;
        for (int i = 0; i < validCount; i++)
        {
            StructuralBin a = first.get(validIndices[i]);
            StructuralBin b = second.get(validIndices[i]);
            double timbre = 0.8d * ((1.0d + clamp(spectralCosine(a, b), -1.0d, 1.0d)) / 2.0d)
                    + 0.2d * (1.0d - StrictMath.abs(a.activity() - b.activity()));
            timbreSum += timbre;
        }
        double timbre = rho * (timbreSum / validCount);
        double alignment = rho * clamp(1.0d - dtwCost(first, second, validIndices, validCount,
                bestRotation), 0.0d, 1.0d);
        double combined = Math.min(harmonic, Math.min(timbre + 0.05d, alignment + 0.03d));
        if (!finiteUnit(harmonic) || !finiteUnit(timbre) || !finiteUnit(alignment)
                || !finiteUnit(combined))
        {
            return SimilarityRejectionReason.NON_FINITE;
        }
        scores[0] = harmonic;
        scores[1] = timbre;
        scores[2] = alignment;
        scores[3] = combined;
        details[0] = bestRotation;
        return SimilarityRejectionReason.NONE;
    }

    private static boolean stableAlignment(double central, double variant)
    {
        return StrictMath.abs(variant - central) <= 0.05d;
    }

    private static double dtwCost(FrozenList<StructuralBin> first,
                                  FrozenList<StructuralBin> second,
                                  int[] validIndices,
                                  int validCount,
                                  int rotation)
    {
        double[] previousCost = new double[validCount + 1];
        double[] currentCost = new double[validCount + 1];
        int[] previousLength = new int[validCount + 1];
        int[] currentLength = new int[validCount + 1];
        Arrays.fill(previousCost, Double.POSITIVE_INFINITY);
        previousCost[0] = 0.0d;
        for (int i = 1; i <= validCount; i++)
        {
            Arrays.fill(currentCost, Double.POSITIVE_INFINITY);
            Arrays.fill(currentLength, 0);
            int firstBin = validIndices[i - 1];
            int minimumJ = Math.max(1, i - 4);
            int maximumJ = Math.min(validCount, i + 4);
            for (int j = minimumJ; j <= maximumJ; j++)
            {
                int secondBin = validIndices[j - 1];
                // Compression removes invalid bins, not the original clock of the DTW band.
                if (StrictMath.abs(firstBin - secondBin) > 4) continue;
                StructuralBin a = first.get(firstBin);
                StructuralBin b = second.get(secondBin);
                double local = 0.5d * (1.0d - clamp(chromaCosine(a, b, rotation), 0.0d, 1.0d));
                local += 0.3d * (1.0d - ((1.0d + clamp(spectralCosine(a, b), -1.0d, 1.0d)) / 2.0d));
                local += 0.2d * StrictMath.abs(a.activity() - b.activity());
                double predecessor = previousCost[j - 1];
                int predecessorLength = previousLength[j - 1];
                if (previousCost[j] < predecessor - 1.0e-12d)
                {
                    predecessor = previousCost[j];
                    predecessorLength = previousLength[j];
                }
                if (currentCost[j - 1] < predecessor - 1.0e-12d)
                {
                    predecessor = currentCost[j - 1];
                    predecessorLength = currentLength[j - 1];
                }
                if (Double.isFinite(predecessor))
                {
                    currentCost[j] = predecessor + local;
                    currentLength[j] = predecessorLength + 1;
                }
            }
            double[] swapCost = previousCost;
            previousCost = currentCost;
            currentCost = swapCost;
            int[] swapLength = previousLength;
            previousLength = currentLength;
            currentLength = swapLength;
        }
        return previousLength[validCount] == 0 ? Double.NaN
                : previousCost[validCount] / previousLength[validCount];
    }

    private static double chromaCosine(StructuralBin first, StructuralBin second, int rotation)
    {
        double dot = 0.0d;
        double firstNorm = 0.0d;
        double secondNorm = 0.0d;
        for (int component = 0; component < 12; component++)
        {
            double a = first.chromaAt(component);
            double b = second.chromaAt((component + rotation) % 12);
            dot += a * b;
            firstNorm += a * a;
            secondNorm += b * b;
        }
        if (firstNorm <= 1.0e-24d || secondNorm <= 1.0e-24d) return 0.0d;
        return dot / (StrictMath.sqrt(firstNorm) * StrictMath.sqrt(secondNorm));
    }

    private static double spectralCosine(StructuralBin first, StructuralBin second)
    {
        double dot = 0.0d;
        double firstNorm = 0.0d;
        double secondNorm = 0.0d;
        for (int component = 0; component < 8; component++)
        {
            double a = first.spectralAt(component);
            double b = second.spectralAt(component);
            dot += a * b;
            firstNorm += a * a;
            secondNorm += b * b;
        }
        if (firstNorm <= 1.0e-24d || secondNorm <= 1.0e-24d) return 0.0d;
        return dot / (StrictMath.sqrt(firstNorm) * StrictMath.sqrt(secondNorm));
    }

    private static boolean valid(StructuralBin bin)
    {
        if (bin == null || !Double.isFinite(bin.activity())
                || bin.activity() < 0.0d || bin.activity() > 1.0d) return false;
        double chromaNorm = 0.0d;
        double spectralNorm = 0.0d;
        for (int i = 0; i < 12; i++)
        {
            if (!Double.isFinite(bin.chromaAt(i))) return false;
            chromaNorm += bin.chromaAt(i) * bin.chromaAt(i);
        }
        for (int i = 0; i < 8; i++)
        {
            if (!Double.isFinite(bin.spectralAt(i))) return false;
            spectralNorm += bin.spectralAt(i) * bin.spectralAt(i);
        }
        return Double.isFinite(chromaNorm) && Double.isFinite(spectralNorm)
                && StrictMath.sqrt(chromaNorm) > 1.0e-12d
                && StrictMath.sqrt(spectralNorm) > 1.0e-12d;
    }

    private static SimilarityScore rejected(SimilarityRejectionReason reason, int validBins)
    {
        return new SimilarityScore(0.0d, 0.0d, 0.0d, 0.0d, 0,
                Math.max(0, Math.min(32, validBins)), reason);
    }

    private static boolean finiteUnit(double value)
    {
        return Double.isFinite(value) && value >= 0.0d && value <= 1.0d;
    }

    private static double clamp(double value, double minimum, double maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
