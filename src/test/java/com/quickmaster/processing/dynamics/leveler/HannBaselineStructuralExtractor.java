package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.dspark.core.FFTReal;
import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.StructuralFrame;

/** Bounded structural clock with level-independent harmonic/timbral features. */
final class HannBaselineStructuralExtractor
{
    public FeatureTimeline extract(float[] pcm,
                                   AudioFormat format,
                                   LoudnessTimeline loudness,
                                   CancellationToken cancellation)
    {
        if (pcm == null || format == null || loudness == null) return null;
        try
        {
            format.validatePcm(pcm);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
        int sampleRate = format.sampleRateHz();
        int channels = format.channels();
        int totalFrames = Math.toIntExact(format.frames());
        int structuralHop = Math.max(1, (int) StrictMath.round(sampleRate * 0.5d));
        int count = ceilDiv(totalFrames, structuralHop);
        int fftSize = fftSize(sampleRate);
        int fftHop = fftSize / 2;
        FFTReal fft = new FFTReal(fftSize);
        float[] time = new float[fftSize];
        float[] frequency = new float[fft.getFrequencyDomainSize()];
        float[] magnitudes = new float[fft.getNumBins()];
        double[] spectrum = new double[fft.getNumBins()];
        double[] previousNormalizedMagnitude = new double[fft.getNumBins()];
        double[][] chroma = new double[count][];
        double[][] spectral = new double[count][];
        double[] rawFlux = new double[count];
        double[] activity = new double[count];
        long[] centers = new long[count];

        for (int index = 0; index < count; index++)
        {
            if (cancellation != null && cancellation.isCancelled()) return null;
            int start = Math.multiplyExact(index, structuralHop);
            int end = Math.min(totalFrames, Math.addExact(start, structuralHop));
            centers[index] = start + (end - start - 1L) / 2L;
            Arrays.fill(spectrum, 0.0d);
            int windowCount = 0;
            for (int windowStart = start; windowStart < end; windowStart += fftHop)
            {
                for (int channel = 0; channel < channels; channel++)
                {
                    for (int n = 0; n < fftSize; n++)
                    {
                        int sourceFrame = windowStart + n;
                        if ((n & 4095) == 0 && cancellation != null && cancellation.isCancelled())
                        {
                            return null;
                        }
                        double sample = sourceFrame < end
                                ? pcm[Math.multiplyExact(sourceFrame, channels) + channel] : 0.0d;
                        double hann = 0.5d - 0.5d * StrictMath.cos(
                                2.0d * StrictMath.PI * n / (fftSize - 1.0d));
                        time[n] = (float) (sample * hann);
                    }
                    fft.forward(time, frequency);
                    fft.computeMagnitudes(frequency, magnitudes);
                    for (int bin = 0; bin < magnitudes.length; bin++)
                    {
                        double magnitude = magnitudes[bin];
                        spectrum[bin] += magnitude * magnitude;
                    }
                }
                windowCount++;
            }
            double divisor = Math.max(1, Math.multiplyExact(windowCount, channels));
            for (int bin = 0; bin < spectrum.length; bin++) spectrum[bin] /= divisor;
            chroma[index] = chroma(spectrum, sampleRate, fftSize);
            spectral[index] = spectralShape(spectrum, sampleRate, fftSize);
            rawFlux[index] = onsetFlux(spectrum, previousNormalizedMagnitude);
            activity[index] = activity(loudness, start, end);
        }

        double fluxMedian = percentileLower(rawFlux, 0.5d);
        double[] deviations = new double[rawFlux.length];
        for (int i = 0; i < rawFlux.length; i++) deviations[i] = StrictMath.abs(rawFlux[i] - fluxMedian);
        double fluxMad = percentileLower(deviations, 0.5d);
        double denominator = Math.max(fluxMad, 1.0e-12d);
        Object[] frameObjects = new Object[count];
        for (int i = 0; i < count; i++)
        {
            double normalizedFlux = Math.max(0.0d, (rawFlux[i] - fluxMedian) / denominator);
            frameObjects[i] = new StructuralFrame(
                    centers[i], chroma[i], spectral[i], normalizedFlux, activity[i]);
        }
        return new FeatureTimeline(structuralHop, new FrozenList<StructuralFrame>(frameObjects));
    }

    private static int fftSize(int sampleRate)
    {
        long wanted = Math.max(1L, (long) StrictMath.ceil(sampleRate * 0.0464d));
        int size = 1;
        while (size < wanted && size < 8192) size <<= 1;
        if (size < 2048) return 2048;
        return Math.min(size, 8192);
    }

    private static double[] chroma(double[] spectrum, int sampleRate, int fftSize)
    {
        double[] result = new double[12];
        double upper = Math.min(5000.0d, 0.95d * sampleRate / 2.0d);
        for (int bin = 1; bin < spectrum.length; bin++)
        {
            double frequency = (double) bin * sampleRate / fftSize;
            if (frequency < 55.0d || frequency > upper) continue;
            double midi = 69.0d + 12.0d * (StrictMath.log(frequency / 440.0d) / StrictMath.log(2.0d));
            int lower = (int) StrictMath.floor(midi);
            double fraction = midi - lower;
            int firstClass = positiveModulo(lower, 12);
            int secondClass = positiveModulo(lower + 1, 12);
            result[firstClass] += spectrum[bin] * (1.0d - fraction);
            result[secondClass] += spectrum[bin] * fraction;
        }
        double[] sorted = new double[12];
        System.arraycopy(result, 0, sorted, 0, 12);
        Arrays.sort(sorted);
        double floor = sorted[1];
        double normSquared = 0.0d;
        for (int i = 0; i < result.length; i++)
        {
            result[i] = Math.max(0.0d, result[i] - floor);
            normSquared += result[i] * result[i];
        }
        double norm = StrictMath.sqrt(normSquared);
        if (norm > 1.0e-12d)
        {
            for (int i = 0; i < result.length; i++) result[i] /= norm;
        }
        return result;
    }

    private static double[] spectralShape(double[] spectrum, int sampleRate, int fftSize)
    {
        double[] result = new double[8];
        double upper = Math.min(16000.0d, 0.95d * sampleRate / 2.0d);
        if (upper > 60.0d)
        {
            double ratio = StrictMath.pow(upper / 60.0d, 1.0d / 8.0d);
            for (int bin = 1; bin < spectrum.length; bin++)
            {
                double frequency = (double) bin * sampleRate / fftSize;
                if (frequency < 60.0d || frequency > upper) continue;
                int band = (int) StrictMath.floor(StrictMath.log(frequency / 60.0d)
                        / StrictMath.log(ratio));
                if (band < 0) band = 0;
                if (band > 7) band = 7;
                result[band] += spectrum[bin];
            }
        }
        double mean = 0.0d;
        for (int i = 0; i < result.length; i++)
        {
            result[i] = StrictMath.log1p(result[i]);
            mean += result[i];
        }
        mean /= result.length;
        double normSquared = 0.0d;
        for (int i = 0; i < result.length; i++)
        {
            result[i] -= mean;
            normSquared += result[i] * result[i];
        }
        double norm = StrictMath.sqrt(normSquared);
        if (norm > 1.0e-12d)
        {
            for (int i = 0; i < result.length; i++) result[i] /= norm;
        }
        return result;
    }

    private static double onsetFlux(double[] spectrum, double[] previous)
    {
        double magnitudeSum = 0.0d;
        for (int i = 0; i < spectrum.length; i++) magnitudeSum += StrictMath.sqrt(spectrum[i]);
        double denominator = Math.max(magnitudeSum, 1.0e-12d);
        double flux = 0.0d;
        for (int i = 0; i < spectrum.length; i++)
        {
            double current = StrictMath.sqrt(spectrum[i]) / denominator;
            double difference = current - previous[i];
            if (difference > 0.0d) flux += difference;
            previous[i] = current;
        }
        return flux;
    }

    private static double activity(LoudnessTimeline loudness, int start, int end)
    {
        int present = 0;
        int active = 0;
        long firstIndex = ((long) start + loudness.hopFrames() - 1L) / loudness.hopFrames();
        long endIndex = ((long) end + loudness.hopFrames() - 1L) / loudness.hopFrames();
        int first = (int) Math.min(firstIndex, loudness.momentaryCount());
        int limit = (int) Math.min(endIndex, loudness.momentaryCount());
        for (int i = first; i < limit; i++)
        {
            if (loudness.momentaryValidAt(i))
            {
                present++;
                double power = loudness.momentaryPowerAt(i);
                double lufs = -0.691d + 10.0d * StrictMath.log10(Math.max(power, 1.0e-30d));
                if (lufs > -70.0d) active++;
            }
        }
        return present == 0 ? 0.0d : (double) active / present;
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

    private static int positiveModulo(int value, int divisor)
    {
        int result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private static int ceilDiv(int numerator, int denominator)
    {
        return (numerator + denominator - 1) / denominator;
    }
}

