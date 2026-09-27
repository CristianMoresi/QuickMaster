package com.dspark.effects;

import com.dspark.core.FFTReal;

/**
 * Linear-phase complementary crossover that splits a signal into N frequency
 * bands which sum back to the original (delayed by {@link #getLatency()}).
 * <p>
 * Each band is produced by a symmetric (linear-phase) windowed-sinc FIR, so the
 * split introduces <b>no phase distortion</b> - only a constant latency. The
 * bands are formed by successive differences of cumulative low-passes
 * ({@code band0 = LP0}, {@code bandk = LPk - LP(k-1)}, {@code bandLast = delayed - LP(last)}),
 * which telescopes to a perfect reconstruction regardless of the filter shape.
 * <p>
 * The FIR length scales with the sample rate so the kernel spans a constant
 * time window ({@value #KERNEL_SECONDS}&nbsp;s): the crossover's frequency
 * response is therefore the same in Hz at any processing rate, including
 * oversampled rates, instead of its transition bands widening with the rate.
 * <p>
 * Two convolution paths share the same coefficients (so the results match):
 * {@link #splitWhole} convolves the whole buffer at once (FFT overlap-add) for
 * the offline analysis pass, and {@link #process} streams it (FFT overlap-add
 * with carried state) for playback and export, so the long FIR stays affordable
 * even at a high oversampling rate.
 */
public final class MultibandCrossover
{
    /** Kernel span in seconds (2047 taps at 44.1 kHz, the design anchor). */
    public static final double KERNEL_SECONDS = 2047.0 / 44100.0;
    /** Bounds on the scaled FIR length (odd, symmetric). */
    private static final int MIN_TAPS = 255;
    private static final int MAX_TAPS = 65535;
    private static final double BETA = 8.0;

    private int taps = 2047;
    private int lat = (taps - 1) / 2;
    private int fftN = 4096;
    private int hop = fftN - taps + 1;

    private int channels = 2;
    private int numLp = 0;                                 // number of crossovers (bands = numLp + 1)
    private double[][] lpCoef = new double[0][];           // [numLp][taps] cumulative low-passes
    private float[][] lpSpec = new float[0][];             // [numLp][fftN+2] their FFTs

    private FFTReal fft;

    // Streaming (FFT overlap-add) state.
    private float[][][] olaTail = new float[0][][];        // [channels][numLp][taps-1] overlap tails
    private float[][] delayLine = new float[0][];          // [channels][lat] input delay (top band)
    private float[] pSx = new float[0];                    // deinterleaved chunk
    private float[][] pLpBlock = new float[0][];           // [numLp][hop] low-pass outputs this chunk
    private float[] pXb = new float[0], pYt = new float[0];   // fftN time scratch
    private float[] pXs = new float[0], pYs = new float[0];   // fftN+2 spectrum scratch

    public int getLatency() { return lat; }

    public int getBands() { return numLp + 1; }

    /** Preferred overlap-add input block, in frames. Smaller blocks remain valid. */
    public int getBlockSize() { return hop; }

    /** Latency in frames a crossover prepared at {@code sampleRate} will report. */
    public static int latencyForRate(double sampleRate)
    {
        return (tapsForRate(sampleRate) - 1) / 2;
    }

    /** FIR length for a rate: a constant {@value #KERNEL_SECONDS}-second span, odd. */
    private static int tapsForRate(double sampleRate)
    {
        int t = (int) Math.round(KERNEL_SECONDS * Math.max(sampleRate, 1.0));
        if ((t & 1) == 0) t++;
        if (t < MIN_TAPS) t = MIN_TAPS;
        if (t > MAX_TAPS) t = MAX_TAPS;
        return t;
    }

    private static int nextPow2(int n)
    {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /**
     * Designs the crossover for the given rate and crossover frequencies (Hz,
     * ascending). Bands = {@code crossoverHz.length + 1}.
     * <p>
     * A non-finite or non-positive sample rate, a null frequency array or any
     * non-finite frequency is a conservative no-op keeping the previous state:
     * a NaN would otherwise poison the designed kernel to silence.
     */
    public void prepare(double sampleRate, int channels, double[] crossoverHz)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0 || crossoverHz == null) return;
        for (double f : crossoverHz) if (!Double.isFinite(f)) return;

        this.taps = tapsForRate(sampleRate);
        this.lat = (taps - 1) / 2;
        this.fftN = nextPow2(2 * taps);
        this.hop = fftN - taps + 1;
        this.fft = new FFTReal(fftN);

        this.channels = Math.max(1, channels);
        this.numLp = crossoverHz.length;
        lpCoef = new double[numLp][];
        lpSpec = new float[numLp][];

        float[] pad = new float[fftN];
        for (int k = 0; k < numLp; k++)
        {
            lpCoef[k] = designLowpass(crossoverHz[k] / sampleRate);
            java.util.Arrays.fill(pad, 0.0f);
            for (int t = 0; t < taps; t++) pad[t] = (float) lpCoef[k][t];
            float[] spec = new float[fftN + 2];
            fft.forward(pad, spec);
            lpSpec[k] = spec;
        }

        olaTail = new float[this.channels][numLp][taps - 1];
        delayLine = new float[this.channels][lat];
        pSx = new float[hop];
        pLpBlock = new float[numLp][hop];
        pXb = new float[fftN];
        pYt = new float[fftN];
        pXs = new float[fftN + 2];
        pYs = new float[fftN + 2];
        reset();
    }

    /** Clears the streaming filter history. */
    public void reset()
    {
        for (float[][] ct : olaTail) for (float[] t : ct) java.util.Arrays.fill(t, 0.0f);
        for (float[] d : delayLine) java.util.Arrays.fill(d, 0.0f);
    }

    /**
     * Splits the whole interleaved signal into bands using FFT block convolution.
     * Each returned band is interleaved and the same length as the input; the
     * bands sum to the input delayed by {@link #getLatency()}.
     */
    public float[][] splitWhole(float[] interleaved, int ch)
    {
        int frames = interleaved.length / ch;
        int bands = numLp + 1;
        float[][] out = new float[bands][interleaved.length];

        float[] x = new float[frames];
        float[][] lp = new float[numLp][frames];
        float[] xb = new float[fftN];
        float[] xs = new float[fftN + 2];
        float[] ys = new float[fftN + 2];
        float[] yt = new float[fftN];

        for (int c = 0; c < ch; c++)
        {
            for (int n = 0; n < frames; n++) x[n] = interleaved[n * ch + c];
            for (int k = 0; k < numLp; k++) java.util.Arrays.fill(lp[k], 0.0f);

            for (int blockStart = 0; blockStart < frames; blockStart += hop)
            {
                java.util.Arrays.fill(xb, 0.0f);
                int cnt = Math.min(hop, frames - blockStart);
                System.arraycopy(x, blockStart, xb, 0, cnt);
                fft.forward(xb, xs);
                for (int k = 0; k < numLp; k++)
                {
                    complexMul(xs, lpSpec[k], ys);
                    fft.inverse(ys, yt);
                    int lim = Math.min(fftN, frames - blockStart);
                    float[] lpk = lp[k];
                    for (int i = 0; i < lim; i++) lpk[blockStart + i] += yt[i];
                }
            }

            for (int n = 0; n < frames; n++)
            {
                double lower = 0.0;
                for (int k = 0; k < numLp; k++)
                {
                    double v = lp[k][n];
                    out[k][n * ch + c] = (float) (v - lower);
                    lower = v;
                }
                double xDel = (n - lat >= 0) ? x[n - lat] : 0.0;
                out[numLp][n * ch + c] = (float) (xDel - lower);
            }
        }
        return out;
    }

    /**
     * Splits an interleaved block into bands (FFT overlap-add, streaming).
     * {@code bandOut} must have {@link #getBands()} rows, each as long as
     * {@code buffer}. Produces the same result as {@link #splitWhole}, delayed by
     * {@link #getLatency()}, with per-call cost O(log N) per sample instead of
     * O(taps) - so it stays cheap at a high oversampling rate.
     */
    public void process(float[] buffer, int ch, float[][] bandOut)
    {
        int frames = buffer.length / ch;
        for (int off = 0; off < frames; off += hop)
        {
            processChunk(buffer, ch, bandOut, off, Math.min(hop, frames - off));
        }
    }

    /** One FFT block (<= {@code hop} frames): low-pass each band by overlap-add, then telescope. */
    private void processChunk(float[] buffer, int ch, float[][] bandOut, int off, int n)
    {
        for (int c = 0; c < ch; c++)
        {
            for (int i = 0; i < n; i++) pSx[i] = buffer[(off + i) * ch + c];
            java.util.Arrays.fill(pXb, 0.0f);
            System.arraycopy(pSx, 0, pXb, 0, n);
            fft.forward(pXb, pXs);

            for (int k = 0; k < numLp; k++)
            {
                complexMul(pXs, lpSpec[k], pYs);
                fft.inverse(pYs, pYt);                       // linear conv, length n + taps - 1
                float[] tail = olaTail[c][k];
                float[] lpk = pLpBlock[k];
                for (int i = 0; i < n; i++)                  // output = conv + carried tail
                {
                    float v = pYt[i];
                    if (i < taps - 1) v += tail[i];
                    lpk[i] = v;
                }
                for (int i = 0; i < taps - 1; i++)           // new tail = conv past n (+ leftover tail)
                {
                    float v = pYt[n + i];
                    if (n + i < taps - 1) v += tail[n + i];
                    tail[i] = v;
                }
            }

            float[] dl = delayLine[c];
            for (int i = 0; i < n; i++)
            {
                double lower = 0.0;
                for (int k = 0; k < numLp; k++)
                {
                    double v = pLpBlock[k][i];
                    bandOut[k][(off + i) * ch + c] = (float) (v - lower);
                    lower = v;
                }
                double xDel = (i < lat) ? dl[i] : pSx[i - lat];   // input delayed by lat
                bandOut[numLp][(off + i) * ch + c] = (float) (xDel - lower);
            }

            if (n >= lat)
            {
                for (int i = 0; i < lat; i++) dl[i] = pSx[n - lat + i];
            }
            else
            {
                int shift = lat - n;
                for (int i = 0; i < shift; i++) dl[i] = dl[i + n];
                for (int i = 0; i < n; i++) dl[shift + i] = pSx[i];
            }
        }
    }

    /* --- internals --- */

    private static void complexMul(float[] a, float[] b, float[] out)
    {
        for (int i = 0; i < a.length; i += 2)
        {
            float ar = a[i], ai = a[i + 1];
            float br = b[i], bi = b[i + 1];
            out[i] = ar * br - ai * bi;
            out[i + 1] = ar * bi + ai * br;
        }
    }

    private double[] designLowpass(double fcNorm)
    {
        double fc = Math.max(1e-5, Math.min(0.4999, fcNorm));
        int m = taps;
        double c = (m - 1) / 2.0;
        double i0Beta = besselI0(BETA);
        double[] h = new double[m];
        double sum = 0.0;
        for (int n = 0; n < m; n++)
        {
            double xx = n - c;
            double ideal = (Math.abs(xx) < 1e-9)
                    ? 2.0 * fc
                    : Math.sin(Math.PI * 2.0 * fc * xx) / (Math.PI * xx);
            double t = xx / c;
            double kaiser = (Math.abs(t) > 1.0)
                    ? 0.0 : besselI0(BETA * Math.sqrt(1.0 - t * t)) / i0Beta;
            h[n] = ideal * kaiser;
            sum += h[n];
        }
        if (sum != 0.0) for (int n = 0; n < m; n++) h[n] /= sum;   // unity DC gain
        return h;
    }

    private static double besselI0(double x)
    {
        double sum = 1.0, term = 1.0;
        for (int k = 1; k <= 25; k++)
        {
            double half = x / (2.0 * k);
            term *= half * half;
            sum += term;
            if (term < 1e-15 * sum) break;
        }
        return sum;
    }
}
