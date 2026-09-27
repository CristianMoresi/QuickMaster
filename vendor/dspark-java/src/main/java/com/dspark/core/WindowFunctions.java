package com.dspark.core;

/**
 * Standard window functions for spectral analysis and FIR design.
 * <p>
 * Windows taper a frame's edges to reduce spectral leakage in the FFT.
 * Each method fills {@code size} values; windows are generated in the
 * periodic (WOLA) convention suitable for overlap-add and analysis.
 */
public final class WindowFunctions
{
    private WindowFunctions() { }

    /** Rectangular window (all ones — i.e. no windowing). */
    public static void rectangular(double[] out, int size)
    {
        for (int i = 0; i < size; i++) out[i] = 1.0;
    }

    /** Hann (raised cosine) window. */
    public static void hann(double[] out, int size)
    {
        if (size == 1) { out[0] = 1.0; return; }
        for (int i = 0; i < size; i++)
        {
            out[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / size);
        }
    }

    /** Hamming window. */
    public static void hamming(double[] out, int size)
    {
        if (size == 1) { out[0] = 1.0; return; }
        for (int i = 0; i < size; i++)
        {
            out[i] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / size);
        }
    }

    /** Blackman window. */
    public static void blackman(double[] out, int size)
    {
        if (size == 1) { out[0] = 1.0; return; }
        for (int i = 0; i < size; i++)
        {
            double x = (double) i / size;
            out[i] = 0.42 - 0.5 * Math.cos(2.0 * Math.PI * x)
                          + 0.08 * Math.cos(4.0 * Math.PI * x);
        }
    }

    /** Blackman-Harris (4-term) window — very low side lobes (~-92 dB). */
    public static void blackmanHarris(double[] out, int size)
    {
        if (size == 1) { out[0] = 1.0; return; }
        double a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
        for (int i = 0; i < size; i++)
        {
            double x = (double) i / size;
            out[i] = a0 - a1 * Math.cos(2.0 * Math.PI * x)
                        + a2 * Math.cos(4.0 * Math.PI * x)
                        - a3 * Math.cos(6.0 * Math.PI * x);
        }
    }

    /** Kaiser window with shape parameter {@code beta}. */
    public static void kaiser(double[] out, int size, double beta)
    {
        if (size == 1) { out[0] = 1.0; return; }
        int n = size;
        double denom = besselI0(beta);
        for (int i = 0; i < size; i++)
        {
            double x = 2.0 * i / n - 1.0;
            double arg = beta * Math.sqrt(Math.max(0.0, 1.0 - x * x));
            out[i] = besselI0(arg) / denom;
        }
    }

    /** Multiplies an interleaved/mono float signal by a window, in place. */
    public static void apply(float[] signal, double[] window, int size)
    {
        for (int i = 0; i < size; i++) signal[i] *= (float) window[i];
    }

    /** Coherent gain (mean) of a window, for amplitude correction. */
    public static double coherentGain(double[] window, int size)
    {
        double sum = 0.0;
        for (int i = 0; i < size; i++) sum += window[i];
        return (size > 0) ? sum / size : 0.0;
    }

    private static double besselI0(double x)
    {
        double sum = 1.0, term = 1.0, half = x / 2.0;
        for (int k = 1; k <= 50; k++)
        {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < sum * 1e-12) break;
        }
        return sum;
    }
}
