package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link MinimumPhase}. */
class MinimumPhaseTest
{
    private static final int SIZE = 2048;

    private static double[] ones(int n)
    {
        double[] t = new double[n];
        java.util.Arrays.fill(t, 1.0);
        return t;
    }

    /** The kernel of a log magnitude given per bin, uncut. */
    private static double[] kernelOf(double[] logGain)
    {
        FFTRealDouble fft = new FFTRealDouble(SIZE);
        double[] h = new double[SIZE + 2], c = new double[SIZE];
        for (int k = 0; k <= SIZE / 2; k++) h[2 * k] = logGain[k];
        MinimumPhase.build(fft, h, c, SIZE, ones(SIZE), SIZE);
        return c;
    }

    private static double[] bump(double height)
    {
        double[] g = new double[SIZE / 2 + 1];
        for (int k = 0; k <= SIZE / 2; k++)
        {
            double d = (k - 200.0) / 60.0;
            g[k] = height * Math.exp(-0.5 * d * d);
        }
        return g;
    }

    @Test
    @DisplayName("A flat magnitude is a unit impulse")
    void flatIsAnImpulse()
    {
        double[] c = kernelOf(new double[SIZE / 2 + 1]);
        assertEquals(1.0, c[0], 1e-14);
        for (int i = 1; i < SIZE; i++) assertEquals(0.0, c[i], 1e-14, "sample " + i);
    }

    @Test
    @DisplayName("A gain alike in every bin is an impulse of that gain")
    void constantGain()
    {
        double[] g = new double[SIZE / 2 + 1];
        java.util.Arrays.fill(g, Math.log(0.5));
        double[] c = kernelOf(g);
        assertEquals(0.5, c[0], 1e-13);
        for (int i = 1; i < SIZE; i++) assertEquals(0.0, c[i], 1e-13, "sample " + i);
    }

    @Test
    @DisplayName("The kernel has the magnitude asked")
    void magnitudeIsKept()
    {
        for (double height : new double[] { Math.log(2.0), -Math.log(4.0) })
        {
            double[] g = bump(height);
            double[] c = kernelOf(g);
            double[] f = new double[SIZE + 2];
            new FFTRealDouble(SIZE).forward(c, f);
            for (int k = 0; k <= SIZE / 2; k++)
                assertEquals(g[k], Math.log(Math.hypot(f[2 * k], f[2 * k + 1])), 1e-9, "bin " + k + ", height " + height);
        }
    }

    @Test
    @DisplayName("The kernel is causal with its energy at the front, a boost and its cut being inverses")
    void minimumPhase()
    {
        double[] boost = kernelOf(bump(Math.log(2.0)));
        double front = 0.0, all = 0.0;
        for (int i = 0; i < SIZE; i++)
        {
            all += boost[i] * boost[i];
            if (i < SIZE / 8) front += boost[i] * boost[i];
        }
        assertTrue(front / all > 1.0 - 1e-9, "energy at the front: " + front / all);
        double largest = 0.0;
        for (double v : boost) largest = Math.max(largest, Math.abs(v));
        assertEquals(largest, Math.abs(boost[0]), 0.0, "the first sample is the largest");

        // A minimum-phase filter times its inverse is an impulse.
        double[] cut = kernelOf(bump(-Math.log(2.0)));
        FFTRealDouble fft = new FFTRealDouble(SIZE);
        double[] a = new double[SIZE + 2], b = new double[SIZE + 2], product = new double[SIZE + 2], back = new double[SIZE];
        fft.forward(boost, a);
        fft.forward(cut, b);
        for (int k = 0; k <= SIZE / 2; k++)
        {
            product[2 * k] = a[2 * k] * b[2 * k] - a[2 * k + 1] * b[2 * k + 1];
            product[2 * k + 1] = a[2 * k] * b[2 * k + 1] + a[2 * k + 1] * b[2 * k];
        }
        fft.inverse(product, back);
        assertEquals(1.0, back[0], 1e-9);
        for (int i = 1; i < SIZE; i++) assertEquals(0.0, back[i], 1e-9, "sample " + i);
    }

    @Test
    @DisplayName("The kernel is cut to the length given and weighed by the taper")
    void cutAndTapered()
    {
        FFTRealDouble fft = new FFTRealDouble(SIZE);
        double[] g = bump(Math.log(2.0));
        double[] whole = kernelOf(g);
        int length = 300;
        double[] taper = new double[length];
        for (int i = 0; i < length; i++) taper[i] = 1.0 - 0.5 * i / length;
        double[] h = new double[SIZE + 2], c = new double[SIZE];
        for (int k = 0; k <= SIZE / 2; k++) h[2 * k] = g[k];
        MinimumPhase.build(fft, h, c, SIZE, taper, length);
        for (int i = 0; i < length; i++) assertEquals(whole[i] * taper[i], c[i], 1e-15, "sample " + i);
        for (int i = length; i < SIZE; i++) assertEquals(0.0, c[i], 0.0, "sample " + i);
    }
}
