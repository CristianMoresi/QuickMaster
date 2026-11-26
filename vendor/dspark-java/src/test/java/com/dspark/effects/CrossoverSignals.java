package com.dspark.effects;

import com.dspark.core.FFTRealDouble;

/** Signals and measurements shared by the crossover tests (the C++ tests' helpers). */
final class CrossoverSignals
{
    static final double PI = 3.14159265358979323846;

    private CrossoverSignals()
    {
    }

    /**
     * Runs a mono signal through a crossover in blocks (random sizes in
     * [1, block] when {@code randomSizes}), collecting every band (the C++
     * crossoverSplit()).
     */
    static double[][] split(CrossoverFilter x, double[] in, int bands, int block, boolean randomSizes)
    {
        final int n = in.length;
        final double[][] out = new double[bands][n];
        final double[][] ic = { in };
        final double[][][] views = new double[bands][][];
        for (int b = 0; b < bands; ++b) views[b] = new double[][] { out[b] };
        int rng = 0x2468ACE1;
        for (int off = 0; off < n;)
        {
            int m = block;
            if (randomSizes)
            {
                rng = rng * 1664525 + 1013904223;
                m = 1 + (rng >>> 8) % block;
            }
            m = Math.min(m, n - off);
            x.process(ic, off, views, off, bands, m);
            off += m;
        }
        return out;
    }

    /** {@link #split} in blocks of a fixed size. */
    static double[][] split(CrossoverFilter x, double[] in, int bands, int block)
    {
        return split(x, in, bands, block, false);
    }

    /** Zero-phase magnitude (dB) of a kernel centred at {@code centre}, at {@code f}. */
    static double kernelResponseDb(double[] h, int centre, int halfSpan, double f, double fs)
    {
        double re = 0, im = 0;
        for (int i = -halfSpan; i < halfSpan; ++i)
        {
            final double v = h[centre + i];
            final double ph = -2.0 * PI * f * i / fs;
            re += v * Math.cos(ph);
            im += v * Math.sin(ph);
        }
        return 10.0 * Math.log10(re * re + im * im);
    }

    /** The xorshift32 white noise of the C++ TestSignals.h, in float steps. */
    static double[] whiteNoise(int n, float amp, int seed)
    {
        final double[] buf = new double[n];
        int s = seed;
        for (int i = 0; i < n; ++i)
        {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            final float u = (float) (s & 0xFFFFFFFFL);
            buf[i] = amp * (u / (float) 0xFFFFFFFFL * 2.0f - 1.0f);
        }
        return buf;
    }

    /** A float sine as the C++ generateSine(float*): inc = 2 pi f / fs in float. */
    static double[] floatSine(int n, float freq, float fs)
    {
        final double[] x = new double[n];
        final float inc = (float) (2.0 * Math.PI) * freq / fs;
        for (int i = 0; i < n; ++i) x[i] = (float) Math.sin(inc * (float) i);
        return x;
    }

    /** A sine rounded to float, as the C++ makeSine / sat8Sine. */
    static double[] sine(double freq, double amp, double fs, int n)
    {
        final double[] v = new double[n];
        for (int i = 0; i < n; ++i) v[i] = (float) (amp * Math.sin(2.0 * PI * freq * i / fs));
        return v;
    }

    /** The C++ m6Noise: int32(seed) / 2^31 of an LCG, scaled, rounded to float. */
    static double[] m6Noise(int n, double amp, int seed)
    {
        final double[] v = new double[n];
        for (int i = 0; i < n; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            v[i] = (float) (amp * ((double) seed / 2147483648.0));
        }
        return v;
    }

    /** The LCG noise 0.3 * (u / 2^23 - 1) of the C++ crossover tests, in float. */
    static double[] lcgNoise(int n, int seed, float amp)
    {
        final double[] v = new double[n];
        int rng = seed;
        for (int i = 0; i < n; ++i)
        {
            rng = rng * 1664525 + 1013904223;
            v[i] = amp * ((float) (rng >>> 8) / 8388608.0f - 1.0f);
        }
        return v;
    }

    static double rms(double[] x, int from, int n)
    {
        if (n <= 0) return 0.0;
        double sum = 0.0;
        for (int i = from; i < from + n; ++i) sum += x[i] * x[i];
        return Math.sqrt(sum / n);
    }

    static double peak(double[] x, int from, int n)
    {
        double p = 0.0;
        for (int i = from; i < from + n; ++i) p = Math.max(p, Math.abs(x[i]));
        return p;
    }

    static boolean allFinite(double[] x)
    {
        for (final double v : x) if (!Double.isFinite(v)) return false;
        return true;
    }

    /** The level (dB) of the tone at {@code hz} over the last 4800 samples at 48 kHz (the C++ sat8Line). */
    static double line(double[] v, double hz)
    {
        final int n = 4800, from = v.length - n;
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double ph = 2.0 * PI * hz * i / 48000.0;
            re += v[from + i] * Math.cos(ph);
            im += v[from + i] * Math.sin(ph);
        }
        return 20.0 * Math.log10(Math.max(2.0 * Math.sqrt(re * re + im * im) / n, 1e-15));
    }

    /** Largest step between neighbouring samples over (from, to). */
    static double maxStep(double[] x, int from, int to)
    {
        double m = 0.0;
        for (int i = from + 1; i < to; ++i) m = Math.max(m, Math.abs(x[i] - x[i - 1]));
        return m;
    }

    /** The sum of the bands, sample by sample. */
    static double[] sum(double[][] bands)
    {
        final double[] s = new double[bands[0].length];
        for (final double[] band : bands)
            for (int i = 0; i < s.length; ++i) s[i] += band[i];
        return s;
    }

    /** Worst |10 log10 |X|^2| over the bins of a signal's spectrum. */
    static double worstSpectrumDb(double[] signal)
    {
        final int n = signal.length;
        final FFTRealDouble fft = new FFTRealDouble(n);
        final double[] spec = new double[n + 2];
        fft.forward(signal, spec);
        double worst = 0.0;
        for (int k = 0; k <= n / 2; ++k)
        {
            final double re = spec[2 * k], im = spec[2 * k + 1];
            worst = Math.max(worst, Math.abs(10.0 * Math.log10(re * re + im * im)));
        }
        return worst;
    }
}
