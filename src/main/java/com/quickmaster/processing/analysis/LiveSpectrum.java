package com.quickmaster.processing.analysis;

import com.dspark.core.FFTReal;
import java.util.Arrays;

/**
 * Stereo-energy live spectrum. The audio thread only writes a ring; the UI
 * copies a coherent window under a short lock and performs FFT outside it.
 * FFT/display scratch is reused, with no per-tick audio arrays.
 */
public final class LiveSpectrum {
    private static final int FFT = SpectrumGrid.FFT_SIZE;
    private static final double RISE = .6, FALL = .12;
    private final Object ringLock = new Object();
    private final FFTReal fft = new FFTReal(FFT);
    private final float[] win = SpectrumGrid.window();
    private final float[][] ring = {new float[FFT], new float[FFT]};
    private final float[][] snapshot = {new float[FFT], new float[FFT]};
    private final float[] frame = new float[FFT], freq = new float[fft.getFrequencyDomainSize()];
    private final float[] mag = new float[fft.getNumBins()], raw = new float[SpectrumGrid.SIZE];
    private final double[] power = new double[fft.getNumBins()];
    private final float[] gridDb = new float[SpectrumGrid.SIZE];
    private int writePos, channels = 1;
    private long fed, revision, transformed = -1;
    private double sampleRate = 48000, topDb = -40;
    private boolean ready;

    public LiveSpectrum() { Arrays.fill(gridDb, SpectrumGrid.FLOOR_DB); }

    public synchronized void setSampleRate(double sr) {
        SpectrumGrid.validateFormat(1, sr);
        if (sampleRate != sr) { reset(); sampleRate = sr; }
    }

    public synchronized void reset() {
        synchronized (ringLock) {
            writePos = 0; fed = 0; channels = 1;
            for (float[] buffer : ring) Arrays.fill(buffer, 0);
        }
        transformed = -1; ready = false; topDb = -40;
        Arrays.fill(gridDb, SpectrumGrid.FLOOR_DB);
    }

    /** Audio-thread writer; never waits for a transform to complete. */
    public void push(float[] buf, int channelCount) {
        SpectrumGrid.validateFormat(channelCount, 48000);
        if (buf == null || buf.length % channelCount != 0)
            throw new IllegalArgumentException("Spectrum requires complete audio frames.");
        for (float value : buf) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Non-finite spectrum input.");
        synchronized (ringLock) {
            if (channels != channelCount) {
                channels = channelCount; writePos = 0; fed = 0;
                for (float[] buffer : ring) Arrays.fill(buffer, 0);
            }
            for (int f = 0; f < buf.length / channels; f++) {
                for (int c = 0; c < channels; c++) {
                    float value = buf[f * channels + c];
                    ring[c][writePos] = value;
                }
                writePos = (writePos + 1) % FFT; fed++;
            }
            revision++;
        }
    }

    /** UI consumer; transform buffers and curve are confined to this monitor. */
    public synchronized void update() {
        int count;
        long sequence;
        synchronized (ringLock) {
            if (fed < FFT) return;
            count = channels; sequence = revision;
            if (sequence != transformed) for (int c = 0; c < count; c++) {
                int tail = FFT - writePos;
                System.arraycopy(ring[c], writePos, snapshot[c], 0, tail);
                System.arraycopy(ring[c], 0, snapshot[c], tail, writePos);
            }
        }
        if (sequence != transformed) {
            Arrays.fill(power, 0);
            for (int c = 0; c < count; c++) {
                for (int i = 0; i < FFT; i++) frame[i] = snapshot[c][i] * win[i];
                fft.forward(frame, freq); fft.computeMagnitudes(freq, mag);
                for (int k = 0; k < power.length; k++) power[k] += (double) mag[k] * mag[k] / count;
            }
            SpectrumGrid.project(power, sampleRate, raw);
            transformed = sequence;
        }
        double maximum = SpectrumGrid.FLOOR_DB;
        for (int i = 0; i < gridDb.length; i++) {
            double target = SpectrumGrid.smoothed(raw, i, sampleRate);
            gridDb[i] += (float) ((target - gridDb[i]) * (target > gridDb[i] ? RISE : FALL));
            maximum = Math.max(maximum, gridDb[i]);
        }
        topDb = Math.max(maximum, topDb - .05); ready = true;
    }

    public synchronized boolean isReady() { return ready; }
    public synchronized double getMaxDb() { return topDb; }
    public synchronized double levelDbAt(double hz) { return SpectrumGrid.at(gridDb, hz, sampleRate); }
}
