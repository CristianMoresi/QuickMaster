package com.quickmaster.processing.analysis;

import com.dspark.core.FFTReal;
import java.util.concurrent.CancellationException;

/**
 * Whole-track, channel-power-averaged LTAS, with a Hann STFT and 75% overlap.
 * Zero-padded boundary windows cover every source sample, including short files.
 * Levels are relative spectral display values, not calibrated dBFS per bin.
 */
public final class SpectrumAnalysis {
    public static final int FFT_SIZE = SpectrumGrid.FFT_SIZE;
    private static final int HOP = FFT_SIZE / 4;
    private float[] gridDb = new float[0];
    private double maxDb = SpectrumGrid.FLOOR_DB, rate = 48000;

    public boolean isReady() { return gridDb.length == SpectrumGrid.SIZE; }
    public double getMaxDb() { return maxDb; }

    public void analyze(float[] interleaved, int channels, double sampleRate) {
        SpectrumGrid.validateFormat(channels, sampleRate);
        if (interleaved == null || interleaved.length % channels != 0)
            throw new IllegalArgumentException("Spectrum requires complete audio frames.");
        for (int i = 0; i < interleaved.length; i++) {
            if ((i & 16383) == 0) checkCancelled();
            if (!Float.isFinite(interleaved[i])) throw new IllegalArgumentException("Non-finite spectrum input.");
        }
        int frames = interleaved.length / channels;
        if (frames == 0) { gridDb = new float[0]; maxDb = SpectrumGrid.FLOOR_DB; return; }
        FFTReal fft = new FFTReal(FFT_SIZE);
        double[] power = new double[fft.getNumBins()];
        float[] win = SpectrumGrid.window();
        float[] frame = new float[FFT_SIZE], freq = new float[fft.getFrequencyDomainSize()];
        float[] mag = new float[power.length];
        double fullWeight = 0, weight = 0;
        for (float w : win) fullWeight += (double) w * w;
        for (long start = -(FFT_SIZE - HOP); start < frames; start += HOP) {
            checkCancelled();
            double windowWeight = 0;
            int first = (int) Math.max(0, -start), last = (int) Math.min(FFT_SIZE, frames - start);
            for (int i = first; i < last; i++) windowWeight += (double) win[i] * win[i];
            weight += windowWeight / fullWeight;
            for (int c = 0; c < channels; c++) {
                java.util.Arrays.fill(frame, 0);
                for (int i = first; i < last; i++)
                    frame[i] = interleaved[(int) (start + i) * channels + c] * win[i];
                fft.forward(frame, freq);
                fft.computeMagnitudes(freq, mag);
                for (int k = 0; k < power.length; k++) power[k] += (double) mag[k] * mag[k];
            }
        }
        for (int k = 0; k < power.length; k++) power[k] /= weight * channels;
        float[] raw = new float[SpectrumGrid.SIZE], db = new float[SpectrumGrid.SIZE];
        SpectrumGrid.project(power, sampleRate, raw);
        double maximum = SpectrumGrid.FLOOR_DB;
        for (int i = 0; i < db.length; i++) {
            db[i] = (float) SpectrumGrid.smoothed(raw, i, sampleRate);
            maximum = Math.max(maximum, db[i]);
        }
        checkCancelled();
        rate = sampleRate; maxDb = maximum; gridDb = db;
    }

    public double levelDbAt(double hz) {
        return isReady() ? SpectrumGrid.at(gridDb, hz, rate) : Double.NEGATIVE_INFINITY;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Spectrum analysis cancelled.");
    }
}
