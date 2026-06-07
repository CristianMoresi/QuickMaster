package com.quickmaster.processing.analysis;

import com.dspark.core.FFTReal;

/**
 * Real-time spectrum analyser for the EQ display: keeps a sliding window of the
 * most recent playback samples, transforms it each UI tick, and exposes a
 * smoothed log-frequency curve (fast rise, slow fall) that moves with the audio.
 */
public final class LiveSpectrum
{
    private static final int FFT = 16384;               // fine low-frequency resolution
    private static final int GRID = 512;
    private static final double MIN_HZ = 20.0, MAX_HZ = 20000.0;
    private static final double RISE = 0.6, FALL = 0.12;  // smooth temporal display
    private static final int SMOOTH_R = 6;                // grid-smoothing radius

    private final FFTReal fft = new FFTReal(FFT);
    private final float[] win = new float[FFT];
    private final float[] ring = new float[FFT];
    private int writePos = 0;
    private long fed = 0;
    private double sampleRate = 48000.0;

    private final double[] gridHz = new double[GRID];
    private final float[] gridDb = new float[GRID];
    private double topDb = -40.0;
    private boolean ready = false;

    public LiveSpectrum()
    {
        for (int i = 0; i < FFT; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT - 1)));
        double span = Math.log10(MAX_HZ / MIN_HZ);
        for (int i = 0; i < GRID; i++) gridHz[i] = MIN_HZ * Math.pow(10.0, span * i / (GRID - 1));
        java.util.Arrays.fill(gridDb, -120.0f);
    }

    public void setSampleRate(double sr) { if (sr > 0) sampleRate = sr; }

    public void reset()
    {
        writePos = 0; fed = 0; ready = false; topDb = -40.0;
        java.util.Arrays.fill(ring, 0.0f);
        java.util.Arrays.fill(gridDb, -120.0f);
    }

    /** Adds a processed playback buffer (interleaved). */
    public void push(float[] buf, int channels)
    {
        if (channels < 1) return;
        int frames = buf.length / channels;
        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            double m = 0.0;
            for (int c = 0; c < channels; c++) m += buf[base + c];
            ring[writePos] = (float) (m / channels);
            writePos = (writePos + 1) % FFT;
            fed++;
        }
    }

    /** Recomputes the display curve from the current window. */
    public void update()
    {
        if (fed < FFT) return;
        float[] frame = new float[FFT];
        for (int i = 0; i < FFT; i++) frame[i] = ring[(writePos + i) % FFT] * win[i];
        float[] freq = new float[fft.getFrequencyDomainSize()];
        int nb = fft.getNumBins();
        float[] mag = new float[nb];
        fft.forward(frame, freq);
        fft.computeMagnitudes(freq, mag);

        double binHz = sampleRate / FFT;
        double rLo = Math.pow(2.0, -1.0 / 24.0), rHi = Math.pow(2.0, 1.0 / 24.0);
        float[] raw = new float[GRID];
        for (int i = 0; i < GRID; i++)
        {
            double f = gridHz[i];
            int kLo = Math.max(1, (int) Math.floor(f * rLo / binHz));
            int kHi = Math.min(nb - 1, (int) Math.ceil(f * rHi / binHz));
            if (kHi < kLo) kHi = kLo;
            double p = 0.0;
            for (int k = kLo; k <= kHi; k++) p += (double) mag[k] * mag[k];
            raw[i] = (float) (10.0 * Math.log10(Math.max(p / (kHi - kLo + 1), 1e-12)));
        }
        // Smooth across the grid (turns the coarse low-frequency bin steps into a
        // continuous curve), then ride the display value over time.
        double mx = -200.0;
        for (int i = 0; i < GRID; i++)
        {
            int lo = Math.max(0, i - SMOOTH_R), hi = Math.min(GRID - 1, i + SMOOTH_R);
            double s = 0.0;
            for (int j = lo; j <= hi; j++) s += raw[j];
            double sm = s / (hi - lo + 1);
            double prev = gridDb[i];
            double coef = (sm > prev) ? RISE : FALL;     // smooth: faster up, slower down
            float now = (float) (prev + (sm - prev) * coef);
            gridDb[i] = now;
            if (now > mx) mx = now;
        }
        topDb = Math.max(mx, topDb - 0.05);     // slowly-adapting display ceiling
        ready = true;
    }

    public boolean isReady() { return ready; }
    public double getMaxDb() { return topDb; }

    public double levelDbAt(double hz)
    {
        double span = Math.log10(MAX_HZ / MIN_HZ);
        double t = Math.log10(Math.max(hz, MIN_HZ) / MIN_HZ) / span;
        double gi = t * (GRID - 1);
        int i0 = (int) gi;
        if (i0 < 0) return gridDb[0];
        if (i0 >= GRID - 1) return gridDb[GRID - 1];
        double fr = gi - i0;
        return gridDb[i0] * (1 - fr) + gridDb[i0 + 1] * fr;
    }
}
