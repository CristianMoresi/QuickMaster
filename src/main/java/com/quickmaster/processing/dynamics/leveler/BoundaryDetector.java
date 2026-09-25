package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.LayoutStatus;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.SegmentLayout;
import com.quickmaster.processing.dynamics.leveler.model.StructuralFrame;

/** Deterministic local multiscale novelty and bounded retry selection. */
final class BoundaryDetector
{
    SegmentLayout detect(FeatureTimeline features, long totalFrames, LevelerCalibrationProfile profile)
    {
        if (features == null || profile == null || totalFrames <= 0L)
        {
            return failed(LayoutStatus.INSUFFICIENT_FEATURES);
        }
        long hop = features.hopFrames();
        if (hop <= 0L) return failed(LayoutStatus.INSUFFICIENT_FEATURES);
        int count = features.size();
        long expected = 1L + ((totalFrames - 1L) / hop);
        if (expected != (long) count) return failed(LayoutStatus.INSUFFICIENT_FEATURES);
        try
        {
            for (int i = 0; i < count; i++)
            {
                long start = Math.multiplyExact((long) i, hop);
                long length = Math.min(hop, totalFrames - start);
                long center = Math.addExact(start, (length - 1L) / 2L);
                if (length <= 0L || features.frame(i).centerFrame() != center)
                {
                    return failed(LayoutStatus.INSUFFICIENT_FEATURES);
                }
            }
        }
        catch (ArithmeticException ex)
        {
            return failed(LayoutStatus.INSUFFICIENT_FEATURES);
        }
        if (count == 1) return layout(totalFrames, new int[0], 0, hop);

        double[] novelty = originalNovelty(features, totalFrames, profile);
        if (novelty == null) return failed(LayoutStatus.INSUFFICIENT_FEATURES);

        double median = percentileLower(novelty, 0.5d);
        double[] deviations = new double[novelty.length];
        for (int i = 0; i < novelty.length; i++) deviations[i] = StrictMath.abs(novelty[i] - median);
        double mad = percentileLower(deviations, 0.5d);
        double initialThreshold = mad > 0.0d
                ? median + profile.noveltyMadMultiplier() * mad
                : percentileLower(novelty, 0.95d);
        int[] persistent = select(persistentContrast(features), 0.02d, hop);
        int[] selected = merge(select(novelty, initialThreshold, hop), persistent);
        if (selected.length + 1 > profile.maximumSegments())
        {
            selected = merge(select(novelty, percentileLower(novelty, 0.975d), hop), persistent);
        }
        if (selected.length + 1 > profile.maximumSegments())
        {
            selected = merge(select(novelty, percentileLower(novelty, 0.99d), hop), persistent);
        }
        if (selected.length + 1 > profile.maximumSegments())
        {
            return failed(LayoutStatus.TOO_MANY_SEGMENTS);
        }
        return layout(totalFrames, selected, selected.length, hop);
    }

    /** Isolate the start/end of a sustained gain ramp even when its content repeats. */
    SegmentLayout refineLoudnessTransitions(FeatureTimeline features, LoudnessTimeline loudness,
                                             SegmentLayout original, int sampleRateHz,
                                             long totalFrames, LevelerCalibrationProfile profile)
    {
        if (features == null || loudness == null || original == null || profile == null
                || sampleRateHz <= 0 || totalFrames <= 0L
                || original.status() != LayoutStatus.READY) return failed(LayoutStatus.INSUFFICIENT_FEATURES);
        long hop = Math.max(1L, StrictMath.round(0.5d * sampleRateHz));
        long loudnessHop = Math.max(1L, StrictMath.round(0.1d * sampleRateHz));
        if (features.hopFrames() != hop || loudness.hopFrames() != loudnessHop
                || loudness.shortTermWindowFrames() != 3L * sampleRateHz
                || 1L + (totalFrames - 1L) / hop != features.size()
                || 1L + (totalFrames - 1L) / loudnessHop != loudness.shortTermCount()
                || original.regions().get(original.regions().size() - 1).endExclusive() != totalFrames)
            return failed(LayoutStatus.INSUFFICIENT_FEATURES);
        int[] originalBoundaries = new int[original.regions().size() - 1];
        for (int i = 0; i < originalBoundaries.length; i++)
        {
            long boundary = original.regions().get(i).endExclusive();
            if (boundary % hop != 0L) return failed(LayoutStatus.INSUFFICIENT_FEATURES);
            originalBoundaries[i] = Math.toIntExact(boundary / hop);
        }
        double[] scores = new double[features.size() - 1];
        long span = 4L * sampleRateHz;
        for (int i = 0; i < scores.length; i++)
        {
            long boundary = (i + 1L) * hop;
            double before = loudnessSlope(loudness, boundary - span, boundary, totalFrames, sampleRateHz);
            double after = loudnessSlope(loudness, boundary, boundary + span, totalFrames, sampleRateHz);
            double later = loudnessSlope(loudness, boundary + span, boundary + 2L * span, totalFrames, sampleRateHz);
            double earlier = loudnessSlope(loudness, boundary - 2L * span, boundary - span, totalFrames, sampleRateHz);
            scores[i] = Math.max(rampContrast(before, after, later), rampContrast(after, before, earlier));
        }
        // Persistent slopes retain their local validity through retries: too
        // many real transitions require a diagnosed whole-track fallback.
        int[] selected = merge(originalBoundaries, select(scores, 0.25d, hop));
        return selected.length + 1 > profile.maximumSegments()
                ? failed(LayoutStatus.TOO_MANY_SEGMENTS) : layout(totalFrames, selected, selected.length, hop);
    }

    private static double rampContrast(double stationary, double nearRamp, double farRamp)
    {
        if (!Double.isFinite(stationary) || !Double.isFinite(nearRamp) || !Double.isFinite(farRamp)
                || StrictMath.abs(stationary) >= 0.10d
                || StrictMath.signum(nearRamp) != StrictMath.signum(farRamp)) return 0.0d;
        return Math.max(0.0d, Math.min(StrictMath.abs(nearRamp), StrictMath.abs(farRamp))
                - 4.0d * StrictMath.abs(stationary) - 4.0d * StrictMath.abs(nearRamp - farRamp));
    }

    private static double loudnessSlope(LoudnessTimeline loudness, long start, long end,
                                         long totalFrames, int sampleRateHz)
    {
        if (start < 0L || end > totalFrames || end - start < loudness.shortTermWindowFrames()) return Double.NaN;
        long hop = loudness.hopFrames();
        int first = Math.toIntExact(start == 0L ? 0L : 1L + (start - 1L) / hop);
        int last = Math.toIntExact((end - loudness.shortTermWindowFrames()) / hop);
        int count = last - first + 1;
        if (count < 4) return Double.NaN;
        int quarter = Math.max(1, count / 4);
        double[] scratch = new double[quarter];
        // All intervening complete windows must exist; silence or missing
        // windows cannot masquerade as the stable side of a measured ramp.
        for (int i = first; i <= last; i++)
            if (!loudness.shortTermValidAt(i)) return Double.NaN;
        for (int i = 0; i < quarter; i++) scratch[i] = loudness.shortTermLufsAt(first + i);
        double initial = lowerMedian(scratch, quarter);
        for (int i = 0; i < quarter; i++) scratch[i] = loudness.shortTermLufsAt(last - quarter + 1 + i);
        double ending = lowerMedian(scratch, quarter);
        return (ending - initial) / ((count - quarter) * hop / (double) sampleRateHz);
    }

    /**
     * A sustained local change need not be among a track's largest Q values.
     * Four adjacent two-second windows distinguish an enduring change of
     * harmonic/timbral content from an onset or a continuous progression.
     * The inner and outer cross-boundary contrasts must both exceed four
     * times the change within either side, with .02 weighted cosine-distance
     * headroom. Activity and onset flux cannot create these extra candidates.
     */
    private static double[] persistentContrast(FeatureTimeline features)
    {
        double[] scores = new double[features.size() - 1];
        double[][] chroma = new double[4][12];
        double[][] spectral = new double[4][8];
        for (int boundary = 8; boundary <= features.size() - 8; boundary++)
        {
            for (int window = 0; window < 4; window++)
            {
                Arrays.fill(chroma[window], 0.0d);
                Arrays.fill(spectral[window], 0.0d);
                int start = boundary - 8 + window * 4;
                for (int offset = 0; offset < 4; offset++)
                {
                    StructuralFrame frame = features.frame(start + offset);
                    for (int component = 0; component < 12; component++)
                        chroma[window][component] += frame.chromaAt(component) * 0.25d;
                    for (int component = 0; component < 8; component++)
                        spectral[window][component] += frame.spectralAt(component) * 0.25d;
                }
            }
            double inner = contentDistance(chroma, spectral, 1, 2);
            double outer = contentDistance(chroma, spectral, 0, 3);
            double within = Math.max(contentDistance(chroma, spectral, 0, 1),
                    contentDistance(chroma, spectral, 2, 3));
            scores[boundary - 1] = Math.max(0.0d, Math.min(inner, outer) - 4.0d * within);
        }
        return scores;
    }

    private static double contentDistance(double[][] chroma, double[][] spectral, int first, int second)
    {
        return 0.5d * vectorDistance(chroma[first], chroma[second], true)
                + 0.3d * vectorDistance(spectral[first], spectral[second], false);
    }

    /**
     * Sustained content gives a boundary its common source-clock anchor.
     * Mixing a Q plateau's early edge with exact content edges would give
     * gain-scaled repeats different relative clocks and defeat their sketch
     * ambiguity veto. Q selection itself (including its ties) is unchanged.
     */
    private static int[] merge(int[] original, int[] persistent)
    {
        if (persistent.length == 0 || original.length == 65) return original;
        if (persistent.length == 65) return persistent;
        int[] accepted = new int[65];
        int count = original.length;
        System.arraycopy(original, 0, accepted, 0, count);
        for (int boundary : persistent)
        {
            boolean separated = true;
            for (int index = 0; index < count; index++)
            {
                if (StrictMath.abs((long) boundary - accepted[index]) < 4L)
                {
                    separated = false;
                    boolean canAnchor = true;
                    for (int other = 0; other < count; other++)
                    {
                        if (other != index && StrictMath.abs((long) boundary - accepted[other]) < 4L)
                        {
                            canAnchor = false;
                            break;
                        }
                    }
                    if (canAnchor) accepted[index] = boundary;
                    break;
                }
            }
            if (separated)
            {
                accepted[count++] = boundary;
                if (count == 65) break;
            }
        }
        Arrays.sort(accepted, 0, count);
        int[] result = new int[count];
        System.arraycopy(accepted, 0, result, 0, count);
        return result;
    }

    /** Original, unthresholded Q; shared without retaining it in either consumer. */
    static double[] originalNovelty(FeatureTimeline features, long totalFrames,
                                    LevelerCalibrationProfile profile)
    {
        if (features == null || profile == null || totalFrames <= 0L) return null;
        long hop = features.hopFrames();
        if (hop <= 0L) return null;
        int size = features.size();
        if (1L + (totalFrames - 1L) / hop != (long) size) return null;
        try
        {
            for (int index = 0; index < size; index++)
            {
                long start = Math.multiplyExact((long) index, hop);
                long length = Math.min(hop, totalFrames - start);
                if (length <= 0L || features.frame(index).centerFrame()
                        != Math.addExact(start, (length - 1L) / 2L)) return null;
            }
        }
        catch (ArithmeticException ex)
        {
            return null;
        }
        if (size == 1) return new double[0];
        double[][] normalized = new double[3][];
        for (int scale = 0; scale < 3; scale++)
        {
            int radius = (int) Math.max(1L, StrictMath.round((2 << scale) / profile.structuralHopSec()));
            double[] raw = new double[size - 1];
            for (int boundary = 1; boundary < size; boundary++)
            {
                raw[boundary - 1] = rawNovelty(features, boundary, radius);
                if (!Double.isFinite(raw[boundary - 1])) return null;
            }
            normalized[scale] = normalize(raw);
        }
        double[] novelty = new double[size - 1];
        for (int index = 0; index < novelty.length; index++)
        {
            double partial = normalized[0][index] + normalized[1][index];
            novelty[index] = (partial + normalized[2][index]) / 3.0d;
            if (!Double.isFinite(novelty[index])) return null;
        }
        return novelty;
    }

    private static SegmentLayout failed(LayoutStatus status)
    {
        return new SegmentLayout(status, new FrozenList<FrameRange>(new Object[0]));
    }

    private static SegmentLayout layout(long totalFrames, int[] boundaries, int boundaryCount, long hop)
    {
        Object[] ranges = new Object[boundaryCount + 1];
        long start = 0L;
        for (int i = 0; i < boundaryCount; i++)
        {
            long end = Math.multiplyExact((long) boundaries[i], hop);
            ranges[i] = new FrameRange(start, end);
            start = end;
        }
        ranges[boundaryCount] = new FrameRange(start, totalFrames);
        return new SegmentLayout(LayoutStatus.READY, new FrozenList<FrameRange>(ranges));
    }

    private static double rawNovelty(FeatureTimeline features, int boundary, int radius)
    {
        int preStart = Math.max(0, boundary - radius);
        int postEnd = Math.min(features.size(), boundary + radius);
        double[] preChroma = new double[12];
        double[] postChroma = new double[12];
        double[] preSpectral = new double[8];
        double[] postSpectral = new double[8];
        double[] scratch = new double[Math.max(radius, 1)];
        for (int component = 0; component < 12; component++)
        {
            preChroma[component] = medianChroma(features, preStart, boundary, component, scratch);
            postChroma[component] = medianChroma(features, boundary, postEnd, component, scratch);
        }
        for (int component = 0; component < 8; component++)
        {
            preSpectral[component] = medianSpectral(features, preStart, boundary, component, scratch);
            postSpectral[component] = medianSpectral(features, boundary, postEnd, component, scratch);
        }
        double preActivity = medianScalar(features, preStart, boundary, true, scratch);
        double postActivity = medianScalar(features, boundary, postEnd, true, scratch);
        double preFlux = medianScalar(features, preStart, boundary, false, scratch);
        double postFlux = medianScalar(features, boundary, postEnd, false, scratch);

        double dChroma = vectorDistance(preChroma, postChroma, true);
        double dSpectral = vectorDistance(preSpectral, postSpectral, false);
        double dActivity = StrictMath.abs(preActivity - postActivity);
        double dFlux = StrictMath.abs(preFlux - postFlux)
                / (StrictMath.abs(preFlux) + StrictMath.abs(postFlux) + 1.0e-12d);
        double partial = 0.5d * dChroma + 0.3d * dSpectral;
        partial += 0.1d * dActivity;
        return partial + 0.1d * dFlux;
    }

    private static double vectorDistance(double[] first, double[] second, boolean chroma)
    {
        double firstNormSquared = 0.0d;
        double secondNormSquared = 0.0d;
        double dot = 0.0d;
        for (int i = 0; i < first.length; i++)
        {
            firstNormSquared += first[i] * first[i];
            secondNormSquared += second[i] * second[i];
            dot += first[i] * second[i];
        }
        double firstNorm = StrictMath.sqrt(firstNormSquared);
        double secondNorm = StrictMath.sqrt(secondNormSquared);
        if (firstNorm <= 1.0e-12d && secondNorm <= 1.0e-12d) return 0.0d;
        if (firstNorm <= 1.0e-12d || secondNorm <= 1.0e-12d) return 1.0d;
        double cosine = dot / (firstNorm * secondNorm);
        if (chroma) return 1.0d - clamp(cosine, 0.0d, 1.0d);
        return (1.0d - clamp(cosine, -1.0d, 1.0d)) / 2.0d;
    }

    private static double medianChroma(FeatureTimeline features,
                                       int start,
                                       int end,
                                       int component,
                                       double[] scratch)
    {
        int size = end - start;
        for (int i = 0; i < size; i++) scratch[i] = features.frame(start + i).chromaAt(component);
        return lowerMedian(scratch, size);
    }

    private static double medianSpectral(FeatureTimeline features,
                                         int start,
                                         int end,
                                         int component,
                                         double[] scratch)
    {
        int size = end - start;
        for (int i = 0; i < size; i++) scratch[i] = features.frame(start + i).spectralAt(component);
        return lowerMedian(scratch, size);
    }

    private static double medianScalar(FeatureTimeline features,
                                       int start,
                                       int end,
                                       boolean activity,
                                       double[] scratch)
    {
        int size = end - start;
        for (int i = 0; i < size; i++)
        {
            StructuralFrame frame = features.frame(start + i);
            scratch[i] = activity ? frame.activity() : frame.onsetFlux();
        }
        return lowerMedian(scratch, size);
    }

    private static double lowerMedian(double[] values, int size)
    {
        for (int i = 1; i < size; i++)
        {
            double value = values[i];
            int insertion = i;
            while (insertion > 0 && values[insertion - 1] > value)
            {
                values[insertion] = values[insertion - 1];
                insertion--;
            }
            values[insertion] = value;
        }
        return values[(size - 1) / 2];
    }

    private static double[] normalize(double[] raw)
    {
        double median = percentileLower(raw, 0.5d);
        double[] deviations = new double[raw.length];
        for (int i = 0; i < raw.length; i++) deviations[i] = StrictMath.abs(raw[i] - median);
        double mad = percentileLower(deviations, 0.5d);
        double denominator = mad > 0.0d
                ? mad : Math.max(percentileLower(raw, 0.95d) - median, 1.0e-12d);
        double[] normalized = new double[raw.length];
        for (int i = 0; i < raw.length; i++)
        {
            normalized[i] = Math.max(0.0d, (raw[i] - median) / denominator);
        }
        return normalized;
    }

    private static double percentileLower(double[] source, double percentile)
    {
        if (source.length == 0) return 0.0d;
        double[] sorted = new double[source.length];
        System.arraycopy(source, 0, sorted, 0, source.length);
        Arrays.sort(sorted);
        int index = (int) StrictMath.ceil(percentile * sorted.length) - 1;
        if (index < 0) index = 0;
        return sorted[index];
    }

    private static int[] select(double[] novelty, double threshold, long hop)
    {
        int[] candidates = new int[novelty.length];
        int candidateCount = 0;
        long radiusFrames = Math.multiplyExact(4L, hop);
        for (int arrayIndex = 0; arrayIndex < novelty.length; arrayIndex++)
        {
            double value = novelty[arrayIndex];
            if (!(value > threshold && value > 0.0d)) continue;
            int boundary = arrayIndex + 1;
            boolean maximum = true;
            int firstOther = Math.max(0, arrayIndex - 4);
            int lastOther = Math.min(novelty.length - 1, arrayIndex + 4);
            for (int otherIndex = firstOther; otherIndex <= lastOther; otherIndex++)
            {
                int otherBoundary = otherIndex + 1;
                long distance = StrictMath.abs(Math.multiplyExact((long) otherBoundary - boundary, hop));
                if (distance > radiusFrames) continue;
                double other = novelty[otherIndex];
                if (other > value || (Double.doubleToRawLongBits(other) == Double.doubleToRawLongBits(value)
                        && otherBoundary < boundary))
                {
                    maximum = false;
                    break;
                }
            }
            if (maximum) candidates[candidateCount++] = boundary;
        }
        sortCandidates(candidates, candidateCount, novelty);
        int[] accepted = new int[Math.min(candidateCount, 65)];
        int acceptedCount = 0;
        long separationFrames = Math.multiplyExact(4L, hop);
        for (int i = 0; i < candidateCount; i++)
        {
            int boundary = candidates[i];
            boolean separated = true;
            for (int j = 0; j < acceptedCount; j++)
            {
                long distance = StrictMath.abs(Math.multiplyExact((long) boundary - accepted[j], hop));
                if (distance < separationFrames)
                {
                    separated = false;
                    break;
                }
            }
            if (separated)
            {
                accepted[acceptedCount++] = boundary;
                if (acceptedCount == 65) break;
            }
        }
        Arrays.sort(accepted, 0, acceptedCount);
        int[] result = new int[acceptedCount];
        System.arraycopy(accepted, 0, result, 0, acceptedCount);
        return result;
    }

    private static void sortCandidates(int[] candidates, int count, double[] novelty)
    {
        if (count > 1) quickSortCandidates(candidates, 0, count - 1, novelty);
    }

    private static void quickSortCandidates(int[] candidates, int low, int high, double[] novelty)
    {
        int left = low;
        int right = high;
        int pivot = candidates[low + (high - low) / 2];
        while (left <= right)
        {
            while (candidateBefore(candidates[left], pivot, novelty)) left++;
            while (candidateBefore(pivot, candidates[right], novelty)) right--;
            if (left <= right)
            {
                int temporary = candidates[left];
                candidates[left] = candidates[right];
                candidates[right] = temporary;
                left++;
                right--;
            }
        }
        if (low < right) quickSortCandidates(candidates, low, right, novelty);
        if (left < high) quickSortCandidates(candidates, left, high, novelty);
    }

    private static boolean candidateBefore(int first, int second, double[] novelty)
    {
        double firstValue = novelty[first - 1];
        double secondValue = novelty[second - 1];
        return firstValue > secondValue || (Double.doubleToRawLongBits(firstValue)
                == Double.doubleToRawLongBits(secondValue) && first < second);
    }

    private static double clamp(double value, double minimum, double maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
