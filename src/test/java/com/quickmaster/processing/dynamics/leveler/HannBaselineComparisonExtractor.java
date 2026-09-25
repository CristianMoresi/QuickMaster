package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.dspark.core.FFTReal;
import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ComparisonTimeline;

/** Ordered V2 comparison evidence, separate from the unchanged structural clock. */
final class HannBaselineComparisonExtractor
{
    public ComparisonTimeline extract(float[] pcm, AudioFormat format, CancellationToken token)
    {
        if (pcm == null || format == null || cancelled(token)) return null;
        try
        {
            if (format.sampleRateHz() < 40
                    || pcm.length != Math.multiplyExact(format.frames(), (long) format.channels())) return null;
            for (int i = 0; i < pcm.length; i++)
            {
                if (i % (4096 * format.channels()) == 0 && cancelled(token)) return null;
                if (!Float.isFinite(pcm[i])) return null;
            }
            int shorts = count(format, 40);
            int longs = count(format, 8);
            int shortLength = Math.multiplyExact(shorts, 52);
            int longLength = Math.multiplyExact(longs, 36);
            int shortSupport = Math.toIntExact(StrictMath.round(.050d * format.sampleRateHz()));
            int longSupport = Math.toIntExact(StrictMath.round(.250d * format.sampleRateHz()));
            int shortFft = fftSize(shortSupport);
            int longFft = fftSize(longSupport);
            long payload = Math.addExact(Math.multiplyExact(105L, shorts), Math.multiplyExact(73L, longs));
            long peakBound = Math.addExact(Math.multiplyExact(2L, payload),
                    Math.addExact(Math.multiplyExact(128L, Math.max(shortFft, longFft)), 65536L));
            if (peakBound >= 268435456L) return null;
            short[] shortValues = new short[shortLength];
            short[] longValues = new short[longLength];
            byte[] shortFlags = new byte[shorts];
            byte[] longFlags = new byte[longs];
            // Each helper invocation owns one FFT/workspace graph; none is retained.
            if (!extractBranch(pcm, format, token, 40, shortSupport, shortFft,
                    shortValues, shortFlags, 52)) return null;
            if (!extractBranch(pcm, format, token, 8, longSupport, longFft,
                    longValues, longFlags, 36) || cancelled(token)) return null;
            ComparisonTimeline result = new ComparisonTimeline(format, shortValues, longValues,
                    shortFlags, longFlags);
            return cancelled(token) ? null : result;
        }
        catch (IllegalArgumentException | ArithmeticException ex)
        {
            return null;
        }
    }

    private static boolean extractBranch(float[] pcm, AudioFormat format, CancellationToken token,
                                         int rate, int support, int size, short[] values,
                                         byte[] flags, int stride)
    {
        if (cancelled(token)) return false;
        FFTReal fft = new FFTReal(size);
        float[] time = new float[size];
        float[] frequency = new float[fft.getFrequencyDomainSize()];
        float[] magnitudes = new float[fft.getNumBins()];
        double[] power = new double[fft.getNumBins()];
        double[] scratch = new double[fft.getNumBins()];
        double[] components = new double[52];
        double hannEnergy = 0.0d;
        for (int n = 0; n < support; n++)
        {
            if ((n & 4095) == 0 && cancelled(token)) return false;
            double hann = hann(n, support);
            hannEnergy += hann * hann;
        }
        if (!(hannEnergy > 0.0d) || !Double.isFinite(hannEnergy)) return false;
        int sampleRate = format.sampleRateHz();
        int channels = format.channels();
        double divisor = channels * hannEnergy;
        for (int row = 0; row < flags.length; row++)
        {
            if (cancelled(token)) return false;
            long cellStart = Math.multiplyExact((long) row, sampleRate) / rate;
            long cellEnd = Math.min(format.frames(), Math.multiplyExact(row + 1L, sampleRate) / rate);
            if (cellEnd <= cellStart) return false;
            long center = cellStart + (cellEnd - cellStart - 1L) / 2L;
            long start = center - (support - 1L) / 2L;
            long present = Math.max(0L, Math.min(format.frames(), start + support) - Math.max(0L, start));
            if (4L * present < 3L * support) continue;
            flags[row] = 1;
            Arrays.fill(power, 0.0d);
            for (int channel = 0; channel < channels; channel++)
            {
                Arrays.fill(time, 0.0f);
                for (int n = 0; n < support; n++)
                {
                    if ((n & 4095) == 0 && cancelled(token)) return false;
                    long sourceFrame = start + n;
                    double sample = sourceFrame < 0L || sourceFrame >= format.frames() ? 0.0d
                            : pcm[Math.toIntExact(Math.addExact(Math.multiplyExact(sourceFrame, channels), channel))];
                    time[n] = (float) (sample * hann(n, support));
                }
                if (cancelled(token)) return false;
                fft.forward(time, frequency);
                fft.computeMagnitudes(frequency, magnitudes);
                for (int bin = 0; bin < power.length; bin++)
                {
                    if ((bin & 4095) == 0 && cancelled(token)) return false;
                    double magnitude = magnitudes[bin];
                    power[bin] += magnitude * magnitude;
                }
            }
            for (int bin = 0; bin < power.length; bin++)
            {
                power[bin] /= divisor;
                if (!Double.isFinite(power[bin])) return false;
            }
            Arrays.fill(components, 0.0d);
            if (stride == 52)
            {
                if (!timbre(power, sampleRate, size, scratch, components, token))
                {
                    if (cancelled(token)) return false;
                    continue;
                }
                flags[row] = 3;
            }
            else if (maximum(power, sampleRate, size, 40.0d,
                    Math.min(5000.0d, .95d * sampleRate / 2.0d)) > 0.0d) flags[row] = 3;
            if ((flags[row] & 3) != 3) continue;
            if (tonal(power, sampleRate, size, support, stride == 52 ? 200.0d : 40.0d,
                    scratch, components, token)) flags[row] = 7;
            if (cancelled(token)) return false;
            int offset = row * stride;
            for (int component = 0; component < 36; component++)
                values[offset + component] = (short) StrictMath.round(components[component] * 65535.0d);
            for (int component = 36; component < stride; component++)
                values[offset + component] = (short) StrictMath.round(components[component] * 256.0d);
        }
        return !cancelled(token);
    }

    private static boolean tonal(double[] power, int sampleRate, int size, int support,
                                 double lower, double[] scratch, double[] components,
                                 CancellationToken token)
    {
        double upper = Math.min(5000.0d, .95d * sampleRate / 2.0d);
        double peak = maximum(power, sampleRate, size, lower, upper);
        if (!(peak > 0.0d) || !Double.isFinite(peak)) return false;
        double floor = peak * 1.0e-12d;
        double duration = (double) support / sampleRate;
        double lowDistance = 2.0d / duration;
        double highDistance = 4.0d / duration;
        for (int bin = 1; bin < power.length - 1; bin++)
        {
            if ((bin & 255) == 0 && cancelled(token)) return false;
            double hz = (double) bin * sampleRate / size;
            if (hz < lower || hz > upper || !(power[bin] > power[bin - 1])
                    || !(power[bin] >= power[bin + 1]) || power[bin] < peak * 1.0e-4d) continue;
            int radius = (int) StrictMath.ceil(highDistance * size / sampleRate);
            int first = Math.max(1, bin - radius);
            int last = Math.min(power.length - 1, bin + radius);
            int samples = 0;
            for (int other = first; other <= last; other++)
            {
                double distance = (double) Math.abs(other - bin) * sampleRate / size;
                if (distance >= lowDistance && distance <= highDistance) scratch[samples++] = power[other];
            }
            if (samples == 0) continue;
            Arrays.sort(scratch, 0, samples);
            double noise = scratch[(samples - 1) / 2];
            if (power[bin] < 16.0d * Math.max(noise, floor)) continue;
            double a = StrictMath.log(Math.max(power[bin - 1], floor)) / 2.0d;
            double b = StrictMath.log(Math.max(power[bin], floor)) / 2.0d;
            double c = StrictMath.log(Math.max(power[bin + 1], floor)) / 2.0d;
            double denominator = a - 2.0d * b + c;
            double delta = denominator == 0.0d ? 0.0d : .5d * (a - c) / denominator;
            delta = Math.max(-.5d, Math.min(.5d, delta));
            double resolved = (bin + delta) * sampleRate / size;
            if (resolved < lower || resolved > upper || !Double.isFinite(resolved)) continue;
            double midi = 69.0d + 12.0d * StrictMath.log(resolved / 440.0d) / StrictMath.log(2.0d);
            double pitch = midi - 12.0d * StrictMath.floor(midi / 12.0d);
            double salience = Math.max(0.0d, Math.min(1.0d,
                    1.0d + 10.0d * StrictMath.log10(power[bin] / peak) / 40.0d));
            for (int component = 0; component < 36; component++)
            {
                double distance = StrictMath.abs(pitch - component / 3.0d);
                distance = Math.min(distance, 12.0d - distance);
                if (distance <= 1.0d)
                    components[component] = Math.max(components[component],
                            salience * (1.0d + StrictMath.cos(StrictMath.PI * distance)) / 2.0d);
            }
        }
        double sum = 0.0d;
        for (int component = 0; component < 36; component++) sum += components[component];
        if (!(sum > 0.0d) || !Double.isFinite(sum)) return false;
        for (int component = 0; component < 36; component++) components[component] /= sum;
        return true;
    }

    private static boolean timbre(double[] power, int sampleRate, int size, double[] scratch,
                                  double[] components, CancellationToken token)
    {
        double upper = Math.min(16000.0d, .95d * sampleRate / 2.0d);
        if (!(upper > 60.0d)) return false;
        double total = 0.0d;
        double peak = 0.0d;
        for (int bin = 1; bin < power.length; bin++)
        {
            double hz = (double) bin * sampleRate / size;
            if (hz < 60.0d || hz > upper) continue;
            peak = Math.max(peak, power[bin]);
        }
        if (!(peak > 0.0d) || !Double.isFinite(peak)) return false;
        for (int band = 0; band < 8; band++)
        {
            if (cancelled(token)) return false;
            double low = 60.0d * StrictMath.pow(upper / 60.0d, band / 8.0d);
            double high = band == 7 ? upper : 60.0d * StrictMath.pow(upper / 60.0d, (band + 1) / 8.0d);
            int count = 0;
            double energy = 0.0d;
            for (int bin = 1; bin < power.length; bin++)
            {
                double hz = (double) bin * sampleRate / size;
                if (hz >= low && (band == 7 ? hz <= high : hz < high))
                {
                    energy += power[bin];
                    scratch[count++] = power[bin];
                }
            }
            if (count == 0) return false;
            Arrays.sort(scratch, 0, count);
            int tails = Math.max(1, (int) StrictMath.ceil(.1d * count));
            double valley = 0.0d;
            double top = 0.0d;
            for (int i = 0; i < tails; i++)
            {
                valley += scratch[i];
                top += scratch[count - tails + i];
            }
            valley /= tails;
            top /= tails;
            components[36 + band] = energy;
            components[44 + band] = Math.max(0.0d, Math.min(40.0d, 10.0d * StrictMath.log10(
                    Math.max(top, peak * 1.0e-4d) / Math.max(valley, peak * 1.0e-4d))));
        }
        for (int band = 0; band < 8; band++) total += components[36 + band];
        if (!(total > 0.0d) || !Double.isFinite(total)) return false;
        for (int band = 0; band < 8; band++)
            components[36 + band] = 10.0d * StrictMath.log10(Math.max(components[36 + band] / total, 1.0e-4d));
        return true;
    }

    private static double maximum(double[] power, int sampleRate, int size, double low, double high)
    {
        double result = 0.0d;
        for (int bin = 1; bin < power.length; bin++)
        {
            double hz = (double) bin * sampleRate / size;
            if (hz >= low && hz <= high) result = Math.max(result, power[bin]);
        }
        return result;
    }

    private static int count(AudioFormat format, int rate)
    {
        return Math.toIntExact(Math.addExact(Math.multiplyExact(format.frames(), (long) rate),
                format.sampleRateHz() - 1L) / format.sampleRateHz());
    }

    private static int fftSize(int support)
    {
        if (support < 2) throw new IllegalArgumentException("Insufficient comparison window.");
        int size = 4;
        while (size < support) size = Math.multiplyExact(size, 2);
        return size;
    }

    private static double hann(int index, int support)
    {
        return .5d - .5d * StrictMath.cos(2.0d * StrictMath.PI * index / (support - 1.0d));
    }

    private static boolean cancelled(CancellationToken token)
    {
        return token != null && token.isCancelled();
    }
}

