package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.BodyContextVector;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;
import com.quickmaster.processing.dynamics.leveler.model.PcmSketch;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SegmentId;
import com.quickmaster.processing.dynamics.leveler.model.SegmentLayout;
import com.quickmaster.processing.dynamics.leveler.model.StructuralBin;
import com.quickmaster.processing.dynamics.leveler.model.StructuralFrame;

/** Builds exactly 32 structural bins and a 2048-bin/channel PCM sketch per region. */
public final class SegmentDescriptorBuilder
{
    public FrozenList<SegmentDescriptor> build(float[] pcm,
                                                AudioFormat format,
                                                LoudnessTimeline loudness,
                                                FeatureTimeline features,
                                                SegmentLayout layout)
    {
        if (pcm == null || format == null || loudness == null || features == null || layout == null
                || layout.status() != com.quickmaster.processing.dynamics.leveler.model.LayoutStatus.READY)
        {
            return null;
        }
        try
        {
            format.validatePcm(pcm);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
        int segments = layout.regions().size();
        if (segments < 1 || segments > 64 || features.hopFrames() <= 0L) return null;
        long previousEnd = 0L;
        for (int segment = 0; segment < segments; segment++)
        {
            FrameRange range = layout.regions().get(segment);
            if (range.startInclusive() != previousEnd || range.endExclusive() > format.frames()) return null;
            if (range.endExclusive() != format.frames()
                    && range.endExclusive() % features.hopFrames() != 0L) return null;
            previousEnd = range.endExclusive();
        }
        if (previousEnd != format.frames()) return null;
        double[] novelty = BoundaryDetector.originalNovelty(features, format.frames(), LevelerCalibrationProfile.V1);
        if (novelty == null) return null;
        MeasuredLoudness[] regional = new MeasuredLoudness[segments];
        double[] loudnessSlope = new double[segments];
        double[] loudnessDelta = new double[segments];
        double[] loudnessConsistency = new double[segments];
        double[] activitySlope = new double[segments];
        double[] activitySpread = new double[segments];
        double[] foreground = new double[segments];
        double[] leftNovelty = new double[segments];
        double[] rightNovelty = new double[segments];
        FrozenList<?>[] bins = new FrozenList<?>[segments];
        long[] validMasks = new long[segments];
        PcmSketch[] sketches = new PcmSketch[segments];
        LoudnessAnalyzer loudnessAnalyzer = new LoudnessAnalyzer();

        for (int segment = 0; segment < segments; segment++)
        {
            FrameRange range = layout.regions().get(segment);
            regional[segment] = loudnessAnalyzer.regionalLoudness(
                    loudness, range, format.sampleRateHz());
            double[] loudnessStats = loudnessStats(loudness, range, format.sampleRateHz());
            if (loudnessStats != null)
            {
                loudnessSlope[segment] = loudnessStats[0];
                loudnessDelta[segment] = loudnessStats[1];
                loudnessConsistency[segment] = loudnessStats[2];
            }
            double[] activityStats = activityStats(features, range, format.sampleRateHz());
            activitySlope[segment] = activityStats[0];
            activitySpread[segment] = activityStats[1];
            foreground[segment] = activityStats[2];
            if (loudnessStats == null || activityStats[3] == 0.0d) regional[segment] = MeasuredLoudness.absent();
            leftNovelty[segment] = boundaryNovelty(novelty, features.hopFrames(),
                    range.startInclusive(), format.frames());
            rightNovelty[segment] = boundaryNovelty(novelty, features.hopFrames(),
                    range.endExclusive(), format.frames());
            FrozenList<StructuralBin> structuralBins = structuralBins(features, range, format.frames());
            if (structuralBins == null) return null;
            long mask = 0L;
            for (int bin = 0; bin < structuralBins.size(); bin++)
            {
                if (valid(structuralBins.get(bin))) mask |= 1L << bin;
            }
            bins[segment] = structuralBins;
            validMasks[segment] = mask;
            sketches[segment] = sketch(pcm, format, range);
        }

        Object[] descriptors = new Object[segments];
        // Context can be measured even when regional/trend confidence is absent.
        // These temporary estimates never replace reference loudness or trajectory statistics.
        MeasuredLoudness[] contextual = new MeasuredLoudness[segments];
        for (int segment = 0; segment < segments; segment++)
        {
            contextual[segment] = regional[segment].present() ? regional[segment]
                    : contextualLoudness(loudness, layout.regions().get(segment));
        }
        for (int segment = 0; segment < segments; segment++)
        {
            double currentActivity = foreground[segment];
            double previousActivity = segment > 0 ? foreground[segment - 1] : currentActivity;
            double nextActivity = segment + 1 < segments ? foreground[segment + 1] : currentActivity;
            double previousRatio = currentActivity > 1.0e-12d
                    ? previousActivity / currentActivity : 0.0d;
            double nextRatio = currentActivity > 1.0e-12d
                    ? nextActivity / currentActivity : 0.0d;
            double entry = 0.0d;
            double exit = 0.0d;
            int availability = contextual[segment].present() ? 2 : 0;
            if (segment > 0 && contextual[segment - 1].present()) availability |= 1;
            if (segment + 1 < segments && contextual[segment + 1].present()) availability |= 4;
            if (contextual[segment].present())
            {
                if ((availability & 1) != 0)
                {
                    double difference = contextual[segment].lufs() - contextual[segment - 1].lufs();
                    if (!Double.isFinite(difference)) return null;
                    entry = clamp(difference / 12.0d, -1.0d, 1.0d);
                }
                if ((availability & 4) != 0)
                {
                    double difference = contextual[segment + 1].lufs() - contextual[segment].lufs();
                    if (!Double.isFinite(difference)) return null;
                    exit = clamp(difference / 12.0d, -1.0d, 1.0d);
                }
            }
            BodyContextVector context = new BodyContextVector(previousRatio, nextRatio,
                    entry, exit, leftNovelty[segment], rightNovelty[segment], availability);
            @SuppressWarnings("unchecked")
            FrozenList<StructuralBin> segmentBins = (FrozenList<StructuralBin>) bins[segment];
            descriptors[segment] = new SegmentDescriptor(new SegmentId(segment),
                    layout.regions().get(segment), segmentBins, validMasks[segment], regional[segment],
                    loudnessSlope[segment], loudnessDelta[segment], loudnessConsistency[segment],
                    activitySlope[segment], activitySpread[segment], foreground[segment],
                    context, sketches[segment]);
        }
        return new FrozenList<SegmentDescriptor>(descriptors);
    }

    /** Context-only lower median of gated, fully contained Momentary windows. */
    private static MeasuredLoudness contextualLoudness(LoudnessTimeline timeline, FrameRange range)
    {
        long hop = timeline.hopFrames();
        long first = range.startInclusive() / hop;
        if (range.startInclusive() % hop != 0L) first++;
        long lastStart = range.endExclusive() - timeline.momentaryWindowFrames();
        if (lastStart < 0L) return MeasuredLoudness.absent();
        long last = lastStart / hop;
        if (last < first) return MeasuredLoudness.absent();
        // Missing timeline indices count against support; clipping must not make
        // three available readings out of 27 geometrically possible become 3/3.
        long possible = Math.addExact(Math.subtractExact(last, first), 1L);
        long availableLast = Math.min(last, (long) timeline.momentaryCount() - 1L);
        if (availableLast < first) return MeasuredLoudness.absent();
        int available = Math.toIntExact(availableLast - first + 1L);
        if (available < 3 || (long) available * 2L < possible) return MeasuredLoudness.absent();
        double[] values = new double[available];
        int valid = 0;
        int absoluteCount = 0;
        double absolutePowerSum = 0.0d;
        for (long index = first; index <= availableLast; index++)
        {
            int slot = Math.toIntExact(index);
            if (!timeline.momentaryValidAt(slot)) continue;
            double power = timeline.momentaryPowerAt(slot);
            if (!Double.isFinite(power) || power <= 0.0d) continue;
            MeasuredLoudness reading = LoudnessCore.fromPower(power);
            if (!reading.present() || !Double.isFinite(reading.lufs())) continue;
            double value = reading.lufs();
            values[valid++] = value;
            if (value > -70.0d)
            {
                absolutePowerSum += power;
                if (!Double.isFinite(absolutePowerSum)) return MeasuredLoudness.absent();
                absoluteCount++;
            }
        }
        if (valid < 3 || (long) valid * 2L < possible || absoluteCount == 0)
            return MeasuredLoudness.absent();
        double meanPower = absolutePowerSum / absoluteCount;
        if (!Double.isFinite(meanPower) || meanPower <= 0.0d) return MeasuredLoudness.absent();
        double relativeGate = LoudnessCore.fromPower(meanPower).lufs() - 20.0d;
        if (!Double.isFinite(relativeGate)) return MeasuredLoudness.absent();
        int selected = 0;
        for (int index = 0; index < valid; index++)
        {
            if (values[index] > -70.0d && values[index] > relativeGate)
                values[selected++] = values[index];
        }
        if (selected < 3) return MeasuredLoudness.absent();
        Arrays.sort(values, 0, selected);
        return new MeasuredLoudness(true, values[(selected - 1) / 2]);
    }

    static FrozenList<StructuralBin> structuralBins(FeatureTimeline features,
                                                     FrameRange range,
                                                     long totalFrames)
    {
        if (features == null || range == null || totalFrames <= 0L || features.hopFrames() <= 0L
                || range.startInclusive() < 0L || range.endExclusive() <= range.startInclusive()
                || range.endExclusive() > totalFrames) return null;
        long hop = features.hopFrames();
        if (1L + (totalFrames - 1L) / hop != (long) features.size()) return null;
        try
        {
            long regionFrames = range.endExclusive() - range.startInclusive();
            Math.multiplyExact(regionFrames, 32L);
            int first = Math.toIntExact(range.startInclusive() / hop);
            int last = Math.toIntExact((range.endExclusive() - 1L) / hop);
            double[][] chroma = new double[32][12];
            double[][] spectral = new double[32][8];
            double[] flux = new double[32];
            double[] activity = new double[32];
            // Each intersecting hop is read once. Per-bin additions retain ascending hop order.
            for (int featureIndex = first; featureIndex <= last; featureIndex++)
            {
                long featureStart = Math.multiplyExact((long) featureIndex, hop);
                long length = Math.min(hop, totalFrames - featureStart);
                long featureEnd = Math.addExact(featureStart, length);
                StructuralFrame frame = features.frame(featureIndex);
                if (length <= 0L || frame.centerFrame() != Math.addExact(featureStart, (length - 1L) / 2L)
                        || !Double.isFinite(frame.onsetFlux()) || !Double.isFinite(frame.activity())) return null;
                long low = Math.multiplyExact(Math.max(featureStart, range.startInclusive()) - range.startInclusive(), 32L);
                long high = Math.multiplyExact(Math.min(featureEnd, range.endExclusive()) - range.startInclusive(), 32L);
                int firstBin = Math.toIntExact(low / regionFrames);
                int lastBin = Math.toIntExact((high - 1L) / regionFrames);
                for (int component = 0; component < 12; component++)
                {
                    if (!Double.isFinite(frame.chromaAt(component))) return null;
                }
                for (int component = 0; component < 8; component++)
                {
                    if (!Double.isFinite(frame.spectralAt(component))) return null;
                }
                for (int bin = firstBin; bin <= lastBin; bin++)
                {
                    long binLow = Math.multiplyExact((long) bin, regionFrames);
                    long binHigh = Math.multiplyExact((long) bin + 1L, regionFrames);
                    long overlap = Math.min(binHigh, high) - Math.max(binLow, low);
                    for (int component = 0; component < 12; component++)
                        chroma[bin][component] += overlap * frame.chromaAt(component);
                    for (int component = 0; component < 8; component++)
                        spectral[bin][component] += overlap * frame.spectralAt(component);
                    flux[bin] += overlap * frame.onsetFlux();
                    activity[bin] += overlap * frame.activity();
                }
            }
            Object[] bins = new Object[32];
            for (int bin = 0; bin < 32; bin++)
            {
                for (int component = 0; component < 12; component++) chroma[bin][component] /= regionFrames;
                for (int component = 0; component < 8; component++) spectral[bin][component] /= regionFrames;
                bins[bin] = new StructuralBin(chroma[bin], spectral[bin], flux[bin] / regionFrames,
                        clamp(activity[bin] / regionFrames, 0.0d, 1.0d));
            }
            return new FrozenList<StructuralBin>(bins);
        }
        catch (ArithmeticException | IllegalArgumentException ex)
        {
            return null;
        }
    }

    private static PcmSketch sketch(float[] pcm, AudioFormat format, FrameRange range)
    {
        int channels = format.channels();
        int bins = 2048;
        long regionFrames = range.endExclusive() - range.startInclusive();
        double[][] values = new double[channels][];
        for (int channel = 0; channel < channels; channel++)
        {
            values[channel] = new double[bins];
            for (int bin = 0; bin < bins; bin++)
            {
                long low = Math.multiplyExact((long) bin, regionFrames);
                long high = Math.multiplyExact((long) bin + 1L, regionFrames);
                long first = Math.floorDiv(low, bins);
                long lastExclusive = ceilDiv(high, bins);
                double sum = 0.0d;
                for (long sample = first; sample < lastExclusive; sample++)
                {
                    long sampleLow = Math.multiplyExact(sample, bins);
                    long sampleHigh = Math.multiplyExact(sample + 1L, bins);
                    long overlap = Math.max(0L,
                            Math.min(high, sampleHigh) - Math.max(low, sampleLow));
                    long absoluteFrame = Math.addExact(range.startInclusive(), sample);
                    int pcmIndex = Math.toIntExact(Math.addExact(
                            Math.multiplyExact(absoluteFrame, channels), channel));
                    sum += (double) overlap * pcm[pcmIndex];
                }
                values[channel][bin] = sum / regionFrames;
            }
        }
        return new PcmSketch(channels, bins, values);
    }

    private static double[] loudnessStats(LoudnessTimeline loudness,
                                          FrameRange range,
                                          int sampleRate)
    {
        try
        {
            long hop = loudness.hopFrames();
            long window = loudness.shortTermWindowFrames();
            if (sampleRate <= 0 || range.endExclusive() < window) return null;
            long firstLong = range.startInclusive() == 0L ? 0L : 1L + (range.startInclusive() - 1L) / hop;
            long endLong = Math.min((long) loudness.shortTermCount(), 1L + (range.endExclusive() - window) / hop);
            if (firstLong >= endLong) return null;
            int first = Math.toIntExact(firstLong);
            int end = Math.toIntExact(endLong);
            int quarter = Math.max(1, (end - first) / 4);
            double[] medians = new double[2];
            long[] centers = new long[2];
            if (!loudnessQuartile(loudness, first, first + quarter, medians, centers, 0)
                    || !loudnessQuartile(loudness, end - quarter, end, medians, centers, 1)) return null;
            long distance = centers[1] - centers[0];
            double delta = medians[1] - medians[0];
            if (distance <= 0L || !Double.isFinite(delta)) return null;
            double slope = delta == 0.0d ? 0.0d : delta / (distance / (2.0d * sampleRate));
            if (!Double.isFinite(slope)) return null;
            int contiguous = 0;
            int available = 0;
            int material = 0;
            int consistent = 0;
            for (int i = first; i < end; i++)
            {
                double value = loudness.shortTermLufsAt(i);
                if (!loudness.shortTermValidAt(i) || !Double.isFinite(value))
                {
                    contiguous = 0;
                    continue;
                }
                contiguous++;
                if (contiguous < 6) continue;
                available++;
                double difference = value - loudness.shortTermLufsAt(i - 5);
                if (!Double.isFinite(difference)) return null;
                if (StrictMath.abs(difference) <= 0.05d) continue;
                material++;
                if ((delta > 0.0d && difference > 0.0d) || (delta < 0.0d && difference < 0.0d)) consistent++;
            }
            if (available == 0) return null;
            return new double[] { slope, delta == 0.0d ? 0.0d : delta,
                    delta == 0.0d || material == 0 ? 0.0d : (double) consistent / material };
        }
        catch (ArithmeticException ex)
        {
            return null;
        }
    }

    private static boolean loudnessQuartile(LoudnessTimeline loudness, int first, int end,
                                            double[] medians, long[] centers, int slot)
    {
        double[] values = new double[end - first];
        long[] times = new long[end - first];
        int count = 0;
        for (int i = first; i < end; i++)
        {
            double value = loudness.shortTermLufsAt(i);
            if (!loudness.shortTermValidAt(i) || !Double.isFinite(value)) continue;
            values[count] = value;
            times[count] = Math.addExact(Math.multiplyExact(2L,
                    Math.multiplyExact((long) i, loudness.hopFrames())), loudness.shortTermWindowFrames() - 1L);
            count++;
        }
        if (count == 0) return false;
        Arrays.sort(values, 0, count);
        // Times were accumulated in original index order, independently of the value sort.
        medians[slot] = values[(count - 1) / 2];
        centers[slot] = times[(count - 1) / 2];
        return true;
    }

    private static double[] activityStats(FeatureTimeline features,
                                          FrameRange range,
                                          int sampleRate)
    {
        int first = Math.toIntExact(range.startInclusive() / features.hopFrames());
        int end = Math.toIntExact(1L + (range.endExclusive() - 1L) / features.hopFrames());
        double[] values = new double[end - first];
        long[] centers = new long[end - first];
        int count = 0;
        double total = 0.0d;
        for (int i = first; i < end; i++)
        {
            long center = features.frame(i).centerFrame();
            if (center >= range.startInclusive() && center < range.endExclusive())
            {
                values[count] = features.frame(i).activity();
                if (!Double.isFinite(values[count])) return new double[4];
                centers[count] = center;
                total += values[count];
                count++;
            }
        }
        double slope = 0.0d;
        boolean evaluable = count >= 2 && sampleRate > 0;
        if (evaluable)
        {
            int quarter = Math.max(1, count / 4);
            double delta = medianRange(values, count - quarter, count) - medianRange(values, 0, quarter);
            long distance = centers[count - quarter + (quarter - 1) / 2] - centers[(quarter - 1) / 2];
            evaluable = distance > 0L && Double.isFinite(delta);
            if (evaluable)
            {
                slope = delta == 0.0d ? 0.0d : delta / ((double) distance / sampleRate);
                evaluable = Double.isFinite(slope);
            }
        }
        double spread = 0.0d;
        if (count > 0)
        {
            double[] sorted = new double[count];
            System.arraycopy(values, 0, sorted, 0, count);
            Arrays.sort(sorted);
            spread = sorted[nearestRank(count, 0.9d)] - sorted[nearestRank(count, 0.1d)];
        }
        return new double[] { evaluable ? slope : 0.0d, spread,
                count == 0 ? 0.0d : total / count, evaluable ? 1.0d : 0.0d };
    }

    private static double medianRange(double[] values, int start, int end)
    {
        double[] copy = new double[end - start];
        System.arraycopy(values, start, copy, 0, copy.length);
        Arrays.sort(copy);
        return copy[(copy.length - 1) / 2];
    }

    private static double boundaryNovelty(double[] novelty, long hop, long boundaryFrame, long totalFrames)
    {
        if (boundaryFrame == 0L || boundaryFrame == totalFrames) return 0.0d;
        return novelty[Math.toIntExact(boundaryFrame / hop - 1L)];
    }

    private static boolean valid(StructuralBin bin)
    {
        double chromaNorm = 0.0d;
        double spectralNorm = 0.0d;
        for (int i = 0; i < 12; i++) chromaNorm += bin.chromaAt(i) * bin.chromaAt(i);
        for (int i = 0; i < 8; i++) spectralNorm += bin.spectralAt(i) * bin.spectralAt(i);
        return Double.isFinite(chromaNorm) && Double.isFinite(spectralNorm)
                && StrictMath.sqrt(chromaNorm) > 1.0e-12d
                && StrictMath.sqrt(spectralNorm) > 1.0e-12d;
    }

    private static int nearestRank(int count, double percentile)
    {
        return Math.max(0, (int) StrictMath.ceil(percentile * count) - 1);
    }

    private static long ceilDiv(long numerator, long denominator)
    {
        return (numerator + denominator - 1L) / denominator;
    }

    private static double clamp(double value, double minimum, double maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
