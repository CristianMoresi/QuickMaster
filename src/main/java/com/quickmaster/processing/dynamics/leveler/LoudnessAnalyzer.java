package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;
import java.util.BitSet;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;

/** Deterministic double-precision loudness measurement. */
public final class LoudnessAnalyzer
{
    public LoudnessTimeline analyze(float[] pcm, AudioFormat format, CancellationToken cancellation)
    {
        if (pcm == null || format == null || (cancellation != null && cancellation.isCancelled())) return null;
        try
        {
            if (pcm.length != format.frames() * format.channels()) return null;
            for (int i = 0; i < pcm.length; i++)
            {
                if ((i & 4095) == 0 && cancellation != null && cancellation.isCancelled()) return null;
                if (!Float.isFinite(pcm[i])) return null;
            }
            return analyzeValidated(pcm, format, cancellation);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }

    private static LoudnessTimeline analyzeValidated(float[] pcm, AudioFormat format,
                                                       CancellationToken cancellation)
    {
        int sampleRate = format.sampleRateHz();
        long shortFrames = 3L * sampleRate;
        if (2L * shortFrames - 1L > Integer.MAX_VALUE
                || 16L * shortFrames + 1024L >= 268435456L) return null;
        int hopFrames = Math.max(1, (int) StrictMath.round(0.1d * sampleRate));
        int momentaryWindowFrames = Math.max(1, (int) StrictMath.round(0.4d * sampleRate));
        int shortTermWindowFrames = Math.max(1, (int) shortFrames);
        int count = ceilDiv(format.frames(), hopFrames);
        if (momentaryWindowFrames > shortFrames
                || 16L * shortFrames + 42L * count + 1024L >= 268435456L) return null;
        double[] momentaryPower = new double[count];
        double[] shortTermPower = new double[count];
        BitSet momentaryValid = new BitSet(count);
        BitSet shortTermValid = new BitSet(count);
        boolean complete = KWeightingAdapter.fillWindowPowers(pcm, format,
                momentaryWindowFrames, shortTermWindowFrames, hopFrames,
                momentaryPower, momentaryValid, shortTermPower, shortTermValid, cancellation);
        if (!complete) return null;

        double[] shortTermLufs = new double[count];
        BitSet shortTermMeasured = new BitSet(count);
        for (int i = 0; i < count; i++)
        {
            if ((i & 4095) == 0 && cancellation != null && cancellation.isCancelled()) return null;
            if (shortTermValid.get(i))
            {
                MeasuredLoudness reading = LoudnessCore.fromPower(shortTermPower[i]);
                if (reading.present())
                {
                    shortTermLufs[i] = reading.lufs();
                    shortTermMeasured.set(i);
                }
            }
        }
        int completeBlocks = format.frames() < momentaryWindowFrames ? 0
                : (int) ((format.frames() - momentaryWindowFrames) / hopFrames + 1L);
        MeasuredLoudness integrated = LoudnessCore.integrated(momentaryPower, completeBlocks,
                new long[3], cancellation);
        if (integrated == null || (cancellation != null && cancellation.isCancelled())) return null;
        return new LoudnessTimeline(momentaryWindowFrames, shortTermWindowFrames, hopFrames,
                momentaryPower, momentaryValid, shortTermLufs, shortTermMeasured, integrated);
    }

    public MeasuredLoudness regionalLoudness(LoudnessTimeline timeline,
                                             FrameRange range,
                                             int sampleRateHz)
    {
        if (timeline == null || range == null || sampleRateHz <= 0) return MeasuredLoudness.absent();
        long duration = range.endExclusive() - range.startInclusive();
        if (duration < StrictMath.round(3.0d * sampleRateHz)) return MeasuredLoudness.absent();

        // Only starts in [ceil(start/hop), floor((end-window)/hop)] qualify.
        // Divide before adding so even a legal Long.MAX_VALUE endpoint is safe.
        long firstIndex = range.startInclusive() / timeline.hopFrames();
        if (range.startInclusive() % timeline.hopFrames() != 0L) firstIndex++;
        long lastStart = range.endExclusive() - timeline.shortTermWindowFrames();
        if (lastStart < 0L || firstIndex >= timeline.shortTermCount()) return MeasuredLoudness.absent();
        long endIndex = lastStart / timeline.hopFrames();
        if (endIndex >= timeline.shortTermCount()) endIndex = timeline.shortTermCount() - 1L;
        if (endIndex < firstIndex) return MeasuredLoudness.absent();
        int first = (int) firstIndex;
        int limit = (int) endIndex + 1;
        int possible = limit - first;
        int valid = 0;
        double[] values = new double[possible];
        for (int i = first; i < limit; i++)
        {
            if (timeline.shortTermValidAt(i)) values[valid++] = timeline.shortTermLufsAt(i);
        }
        if (possible == 0 || valid < 3 || (long) valid * 2L < possible) return MeasuredLoudness.absent();

        double absolutePowerSum = 0.0d;
        int absoluteCount = 0;
        for (int i = 0; i < valid; i++)
        {
            if (values[i] > -70.0d)
            {
                absolutePowerSum += powerFromLufs(values[i]);
                absoluteCount++;
            }
        }
        if (absoluteCount == 0) return MeasuredLoudness.absent();
        double absoluteLufs = lufsFromPower(absolutePowerSum / absoluteCount);
        double regionalGate = absoluteLufs - 20.0d;
        int selected = 0;
        for (int i = 0; i < valid; i++)
        {
            if (values[i] > -70.0d && values[i] > regionalGate) values[selected++] = values[i];
        }
        if (selected < 3) return MeasuredLoudness.absent();
        Arrays.sort(values, 0, selected);
        return new MeasuredLoudness(true, values[(selected - 1) / 2]);
    }

    public static double[] integratedGateFromBlockLufs(double[] blockLufs)
    {
        if (blockLufs == null) throw new IllegalArgumentException("Block vector must not be null.");
        double[] blockPower = new double[blockLufs.length];
        double absolutePower = 0.0d;
        int absoluteCount = 0;
        for (int i = 0; i < blockLufs.length; i++)
        {
            double value = blockLufs[i];
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Block LUFS must be finite.");
            blockPower[i] = powerFromLufs(value);
            if (value > -70.0d)
            {
                absolutePower += blockPower[i];
                absoluteCount++;
            }
        }
        MeasuredLoudness integrated = LoudnessCore.integrated(blockPower, blockPower.length,
                new long[3], null);
        if (absoluteCount == 0) return new double[] { 0.0d, 0.0d, 0.0d };
        double absoluteLufs = lufsFromPower(absolutePower / absoluteCount);
        double relativeGate = absoluteLufs - 10.0d;
        return new double[] { absoluteLufs, relativeGate, integrated.lufs() };
    }

    private static double powerFromLufs(double lufs)
    {
        double power = StrictMath.pow(10.0d, (lufs + 0.691d) / 10.0d);
        if (!Double.isFinite(power) || power <= 0.0d)
            throw new IllegalArgumentException("LUFS cannot be represented as positive finite power.");
        return power;
    }

    private static double lufsFromPower(double power)
    {
        MeasuredLoudness reading = LoudnessCore.fromPower(power);
        if (!reading.present()) throw new IllegalArgumentException("A positive power is required.");
        return reading.lufs();
    }

    private static int ceilDiv(long numerator, int denominator)
    {
        return Math.toIntExact((numerator + denominator - 1L) / denominator);
    }
}
