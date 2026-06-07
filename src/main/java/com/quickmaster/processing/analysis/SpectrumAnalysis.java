package com.quickmaster.processing.analysis;

import com.dspark.core.FFTReal;

/**
 * Long-term average spectrum (LTAS) of a track.
 * <p>
 * Runs a {@value #FFT_SIZE}-point STFT (Hann window, 75% overlap) over the whole
 * signal and averages the power per bin, then resamples it onto a logarithmic
 * frequency grid with 1/12-octave smoothing. The result is a clean average
 * spectrum used to draw the analyser behind the equalizer (and, later, to drive
 * the automatic equalisation toward a target curve).
 */
public final class SpectrumAnalysis
{
    /** STFT window size (≈2.9 Hz/bin at 48 kHz). */
    public static final int FFT_SIZE = 16384;
    private static final int HOP = FFT_SIZE / 4;          // 75% overlap
    private static final int GRID = 512;                  // log-frequency display points
    private static final int SMOOTH_R = 6;                // grid-smoothing radius
    private static final double MIN_HZ = 20.0, MAX_HZ = 20000.0;

    private final double[] gridHz = new double[GRID];
    private float[] gridDb = new float[0];
    private double maxDb = -120.0;

    public SpectrumAnalysis()
    {
        double logSpan = Math.log10(MAX_HZ / MIN_HZ);
        for (int i = 0; i < GRID; i++)
            gridHz[i] = MIN_HZ * Math.pow(10.0, logSpan * i / (GRID - 1));
    }

    public boolean isReady() { return gridDb.length == GRID; }

    /** Peak level of the average spectrum, in (relative) dB; for display ranging. */
    public double getMaxDb() { return maxDb; }

    /**
     * Computes the long-term average spectrum of the given signal.
     *
     * @param interleaved  interleaved float samples
     * @param channels     channel count (mixed down to mono)
     * @param sampleRate   sample rate in Hz
     */
    public void analyze(float[] interleaved, int channels, double sampleRate)
    {
        int frames = (channels > 0) ? interleaved.length / channels : 0;
        if (frames <= 0 || sampleRate <= 0) { gridDb = new float[0]; return; }

        FFTReal fft = new FFTReal(FFT_SIZE);
        int nb = fft.getNumBins();
        double[] power = new double[nb];
        float[] win = new float[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++)
            win[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));

        float[] frame = new float[FFT_SIZE];
        float[] freq = new float[fft.getFrequencyDomainSize()];
        float[] mag = new float[nb];
        int count = 0;
        int last = frames - FFT_SIZE;
        for (int start = 0; start <= Math.max(0, last); start += HOP)
        {
            for (int i = 0; i < FFT_SIZE; i++)
            {
                int f = start + i;
                double m = 0.0;
                if (f < frames)
                {
                    int base = f * channels;
                    for (int c = 0; c < channels; c++) m += interleaved[base + c];
                    m /= channels;
                }
                frame[i] = (float) (m * win[i]);
            }
            fft.forward(frame, freq);
            fft.computeMagnitudes(freq, mag);
            for (int k = 0; k < nb; k++) { double mv = mag[k]; power[k] += mv * mv; }
            count++;
            if (last < 0) break;                       // shorter than one window
        }
        if (count == 0) { gridDb = new float[0]; return; }
        for (int k = 0; k < nb; k++) power[k] /= count;

        // Resample onto the log grid with 1/12-octave smoothing, to dB.
        float[] db = new float[GRID];
        double rLo = Math.pow(2.0, -1.0 / 24.0), rHi = Math.pow(2.0, 1.0 / 24.0);
        double binHz = sampleRate / FFT_SIZE;
        float[] raw = new float[GRID];
        for (int i = 0; i < GRID; i++)
        {
            double f = gridHz[i];
            int kLo = (int) Math.floor(f * rLo / binHz);
            int kHi = (int) Math.ceil(f * rHi / binHz);
            if (kLo < 1) kLo = 1;
            if (kHi >= nb) kHi = nb - 1;
            if (kHi < kLo) kHi = kLo;
            double sum = 0.0;
            for (int k = kLo; k <= kHi; k++) sum += power[k];
            raw[i] = (float) (10.0 * Math.log10(Math.max(sum / (kHi - kLo + 1), 1e-12)));
        }
        // Smooth across the grid so coarse low-frequency bins don't show as steps.
        double mx = -200.0;
        for (int i = 0; i < GRID; i++)
        {
            int lo = Math.max(0, i - SMOOTH_R), hi = Math.min(GRID - 1, i + SMOOTH_R);
            double s = 0.0;
            for (int j = lo; j <= hi; j++) s += raw[j];
            db[i] = (float) (s / (hi - lo + 1));
            if (db[i] > mx) mx = db[i];
        }
        this.gridDb = db;
        this.maxDb = mx;
    }

    /** Smoothed average level in (relative) dB at a frequency. */
    public double levelDbAt(double hz)
    {
        if (gridDb.length != GRID) return Double.NEGATIVE_INFINITY;
        double logSpan = Math.log10(MAX_HZ / MIN_HZ);
        double t = Math.log10(Math.max(hz, MIN_HZ) / MIN_HZ) / logSpan;
        double gi = t * (GRID - 1);
        int i0 = (int) Math.floor(gi);
        if (i0 < 0) return gridDb[0];
        if (i0 >= GRID - 1) return gridDb[GRID - 1];
        double frac = gi - i0;
        return gridDb[i0] * (1 - frac) + gridDb[i0 + 1] * frac;
    }
}
