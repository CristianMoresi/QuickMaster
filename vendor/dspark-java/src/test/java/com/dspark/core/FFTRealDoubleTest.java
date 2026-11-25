package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link FFTRealDouble}. */
class FFTRealDoubleTest
{
    private static double[] noise(int n, long seed)
    {
        Random r = new Random(seed);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = 2.0 * r.nextDouble() - 1.0;
        return x;
    }

    @Test
    @DisplayName("Forward transform matches the direct DFT in every bin")
    void matchesDirectDft()
    {
        for (int n : new int[] { 4, 8, 64, 256 })
        {
            double[] x = noise(n, n);
            double[] f = new double[n + 2];
            new FFTRealDouble(n).forward(x, f);
            for (int k = 0; k <= n / 2; k++)
            {
                double re = 0.0, im = 0.0;
                for (int i = 0; i < n; i++)
                {
                    double a = -2.0 * Math.PI * k * i / n;
                    re += x[i] * Math.cos(a);
                    im += x[i] * Math.sin(a);
                }
                assertEquals(re, f[2 * k], 1e-11, "re, size " + n + ", bin " + k);
                assertEquals(im, f[2 * k + 1], 1e-11, "im, size " + n + ", bin " + k);
            }
        }
    }

    @Test
    @DisplayName("Inverse is the exact inverse of forward")
    void inverseUndoesForward()
    {
        for (int n : new int[] { 4, 8, 1024, 4096, 16384 })
        {
            FFTRealDouble fft = new FFTRealDouble(n);
            double[] x = noise(n, 31L * n);
            double[] f = new double[n + 2];
            double[] y = new double[n];
            fft.forward(x, f);
            fft.inverse(f, y);
            double worst = 0.0;
            for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs(y[i] - x[i]));
            assertEquals(0.0, worst, 1e-12, "size " + n);
        }
    }

    @Test
    @DisplayName("A cosine on a bin reads half the size there and nothing elsewhere")
    void cosineOnABin()
    {
        int n = 512, bin = 37;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = Math.cos(2.0 * Math.PI * bin * i / n + 0.4);
        double[] f = new double[n + 2];
        new FFTRealDouble(n).forward(x, f);
        for (int k = 0; k <= n / 2; k++)
        {
            double mag = Math.hypot(f[2 * k], f[2 * k + 1]);
            assertEquals(k == bin ? 0.5 * n : 0.0, mag, 1e-9, "bin " + k);
        }
        assertEquals(0.4, Math.atan2(f[2 * bin + 1], f[2 * bin]), 1e-12, "phase of the bin");
    }

    @Test
    @DisplayName("DC and Nyquist bins are real and exact")
    void dcAndNyquist()
    {
        int n = 16;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = 1.0 + ((i & 1) == 0 ? 0.25 : -0.25);
        double[] f = new double[n + 2];
        new FFTRealDouble(n).forward(x, f);
        assertEquals(n, f[0], 1e-12);
        assertEquals(0.0, f[1], 0.0);
        assertEquals(0.25 * n, f[n], 1e-12);
        assertEquals(0.0, f[n + 1], 0.0);
    }

    @Test
    @DisplayName("Energy is conserved between the two domains")
    void parseval()
    {
        int n = 2048;
        double[] x = noise(n, 9);
        double[] f = new double[n + 2];
        new FFTRealDouble(n).forward(x, f);
        double time = 0.0;
        for (double v : x) time += v * v;
        double freq = f[0] * f[0] + f[n] * f[n];
        for (int k = 1; k < n / 2; k++) freq += 2.0 * (f[2 * k] * f[2 * k] + f[2 * k + 1] * f[2 * k + 1]);
        assertEquals(time, freq / n, 1e-9 * time);
    }

    @Test
    @DisplayName("The offset variants read and write the same values in place")
    void offsetsMatch()
    {
        int n = 256;
        FFTRealDouble fft = new FFTRealDouble(n);
        double[] x = noise(n, 5);
        double[] plain = new double[n + 2];
        fft.forward(x, plain);

        double[] wideTime = new double[n + 20];
        System.arraycopy(x, 0, wideTime, 7, n);
        double[] wideFreq = new double[3 * (n + 2)];
        fft.forward(wideTime, 7, wideFreq, n + 2);
        for (int i = 0; i < n + 2; i++) assertEquals(plain[i], wideFreq[n + 2 + i], 0.0, "freq " + i);
        assertEquals(0.0, wideFreq[n + 1], 0.0, "nothing written before the stretch");
        assertEquals(0.0, wideFreq[2 * (n + 2)], 0.0, "nothing written after the stretch");

        double[] back = new double[n + 20];
        fft.inverse(wideFreq, n + 2, back, 11);
        for (int i = 0; i < n; i++) assertEquals(x[i], back[11 + i], 1e-13, "time " + i);
        assertEquals(0.0, back[10], 0.0);
        assertEquals(0.0, back[11 + n], 0.0);
    }

    @Test
    @DisplayName("Sizes that are not a power of two of at least 4 are refused")
    void refusesBadSizes()
    {
        assertThrows(IllegalArgumentException.class, () -> new FFTRealDouble(2));
        assertThrows(IllegalArgumentException.class, () -> new FFTRealDouble(48));
        assertThrows(IllegalArgumentException.class, () -> new FFTRealDouble(0));
    }
}
