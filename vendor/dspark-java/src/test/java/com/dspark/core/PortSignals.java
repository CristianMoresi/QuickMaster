package com.dspark.core;

/** Deterministic test signals shared by the filter ports' tests. */
final class PortSignals
{
    private PortSignals()
    {
    }

    /** A 32-bit linear congruential noise in [-1, 1): the C++ tests' cascadeNoise. */
    static final class Lcg
    {
        private int state;

        Lcg(int seed)
        {
            state = seed;
        }

        double next()
        {
            state = state * 1664525 + 1013904223;
            return (double) (state >>> 8) / 8388608.0 - 1.0;
        }

        /** The signed draw of the C++ tests: int32(state) / 2^31. */
        double nextSigned()
        {
            state = state * 1664525 + 1013904223;
            return (double) state / 2147483648.0;
        }
    }

    /** The xorshift32 white noise of the C++ TestSignals.h, in float steps. */
    static void whiteNoise(double[] buf, int n, float amp, int seed)
    {
        int s = seed;
        for (int i = 0; i < n; ++i)
        {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            final float u = (float) (s & 0xFFFFFFFFL);
            buf[i] = amp * (u / (float) 0xFFFFFFFFL * 2.0f - 1.0f);
        }
    }

    /** fillNoise of the C++ TestBuffer: each channel seeded with seed + 7919 ch. */
    static void fillNoise(double[][] channels, int n, float amp, int seed)
    {
        for (int c = 0; c < channels.length; ++c) whiteNoise(channels[c], n, amp, seed + c * 7919);
    }

    /** A sine of a frequency, as the C++ double generateSine. */
    static double[] sine(int n, double freq, double fs, double amp)
    {
        final double[] x = new double[n];
        final double inc = 2.0 * Math.PI * freq / fs;
        for (int i = 0; i < n; ++i) x[i] = amp * Math.sin(inc * i);
        return x;
    }

    static double peak(double[] x, int from, int to)
    {
        double p = 0.0;
        for (int i = from; i < to; ++i) p = Math.max(p, Math.abs(x[i]));
        return p;
    }

    static boolean allFinite(double[] x)
    {
        for (double v : x) if (!Double.isFinite(v)) return false;
        return true;
    }

    /** 20 log10 |H| of a coefficient set at a frequency, through complex arithmetic (the C++ tests' biquadMagDb). */
    static double biquadMagDb(BiquadCoeffs c, double freq, double fs)
    {
        final double w = 2.0 * 3.14159265358979 * freq / fs;
        final double z1r = Math.cos(-w), z1i = Math.sin(-w);
        final double z2r = z1r * z1r - z1i * z1i, z2i = 2.0 * z1r * z1i;
        final double nr = c.b0 + c.b1 * z1r + c.b2 * z2r, ni = c.b1 * z1i + c.b2 * z2i;
        final double dr = 1.0 + c.a1 * z1r + c.a2 * z2r, di = c.a1 * z1i + c.a2 * z2i;
        return 20.0 * Math.log10(Math.hypot(nr, ni) / Math.hypot(dr, di));
    }

    /** The stability triangle and finite coefficients. */
    static boolean isStable(BiquadCoeffs c)
    {
        return Double.isFinite(c.b0) && Double.isFinite(c.b1) && Double.isFinite(c.b2)
            && Math.abs(c.a2) < 1.0 && Math.abs(c.a1) < 1.0 + c.a2;
    }

    static boolean same(BiquadCoeffs a, BiquadCoeffs b)
    {
        return Double.doubleToLongBits(a.b0) == Double.doubleToLongBits(b.b0)
            && Double.doubleToLongBits(a.b1) == Double.doubleToLongBits(b.b1)
            && Double.doubleToLongBits(a.b2) == Double.doubleToLongBits(b.b2)
            && Double.doubleToLongBits(a.a1) == Double.doubleToLongBits(b.a1)
            && Double.doubleToLongBits(a.a2) == Double.doubleToLongBits(b.a2);
    }
}
