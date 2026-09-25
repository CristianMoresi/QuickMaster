package com.quickmaster.processing.analysis;

import com.dspark.analysis.OnsetDetector;
import com.dspark.core.FFTReal;
import com.dspark.core.WindowFunctions;
import java.util.Arrays;

/** Spectral flux of pooled channel energy, without a phase-cancelling waveform downmix. */
final class StereoOnsetDetector
{
    private static final int FFT_SIZE = OnsetDetector.DEFAULT_FFT_SIZE;
    private static final int HOP = OnsetDetector.DEFAULT_HOP;

    record Result(double[] times, double[] durations, float[] strengths, float[] novelty, double frameRate) { }

    private StereoOnsetDetector() { }

    static Result analyze(float[] stereo, double sampleRate)
    {
        int frames = stereo.length / 2;
        if (frames < FFT_SIZE) return new Result(new double[0], new double[0], new float[0], new float[0], 0.0);
        FFTReal fft = new FFTReal(FFT_SIZE);
        double[] window = new double[FFT_SIZE];
        WindowFunctions.hann(window, FFT_SIZE);
        float[] input = new float[FFT_SIZE];
        float[] spectrum = new float[fft.getFrequencyDomainSize()];
        float[] magnitudes = new float[fft.getNumBins()];
        double[] power = new double[magnitudes.length];
        float[] previous = new float[magnitudes.length];
        float[] novelty = new float[1 + (frames - FFT_SIZE) / HOP];
        float maximum = 0.0f;
        for (int row = 0; row < novelty.length; row++)
        {
            Arrays.fill(power, 0.0);
            int first = row * HOP;
            for (int channel = 0; channel < 2; channel++)
            {
                for (int i = 0; i < FFT_SIZE; i++) input[i] = (float)(stereo[(first + i) * 2 + channel] * window[i]);
                fft.forward(input, spectrum);
                fft.computeMagnitudes(spectrum, magnitudes);
                for (int bin = 1; bin < magnitudes.length; bin++) power[bin] += (double)magnitudes[bin] * magnitudes[bin];
            }
            float flux = 0.0f;
            for (int bin = 1; bin < magnitudes.length; bin++)
            {
                float pooled = (float)Math.sqrt(power[bin] * 0.5);
                flux += Math.max(0.0f, pooled - previous[bin]);
                previous[bin] = pooled;
            }
            novelty[row] = flux;
            maximum = Math.max(maximum, flux);
        }
        if (maximum > 0.0f) for (int i = 0; i < novelty.length; i++) novelty[i] /= maximum;
        return pick(novelty, sampleRate / HOP);
    }

    private static Result pick(float[] novelty, double frameRate)
    {
        // Keep the vendor detector's temporal conventions and threshold so mono
        // and stereo transients share one clock. Pooling happens before flux,
        // preserving attacks in either channel without double-counting them.
        int radius = Math.max(1, (int)Math.round(0.1 * frameRate));
        int gap = Math.max(1, (int)Math.round(0.05 * frameRate));
        int[] selected = new int[novelty.length];
        int count = 0, last = -gap;
        for (int i = 1; i < novelty.length - 1; i++)
        {
            if (novelty[i] < novelty[i - 1] || novelty[i] < novelty[i + 1] || i - last < gap) continue;
            int from = Math.max(0, i - radius), to = Math.min(novelty.length - 1, i + radius);
            float total = 0.0f;
            for (int j = from; j <= to; j++) total += novelty[j];
            if (novelty[i] < total / (to - from + 1) + 0.06f) continue;
            selected[count++] = i;
            last = i;
        }
        double[] times = new double[count], durations = new double[count];
        float[] strengths = new float[count];
        int maxDuration = (int)Math.ceil(0.12 * frameRate);
        for (int i = 0; i < count; i++)
        {
            int onset = selected[i], end = onset;
            for (int j = onset + 1; j < novelty.length && j - onset <= maxDuration; j++)
            {
                end = j;
                if (novelty[j] <= 0.35f * novelty[onset]) break;
            }
            times[i] = onset / frameRate;
            durations[i] = Math.max(0.02, Math.min(0.12, (end - onset) / frameRate));
            strengths[i] = novelty[onset];
        }
        return new Result(times, durations, strengths, novelty, frameRate);
    }
}
