package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SpectralFocus}. */
class SpectralFocusTest
{
    private static final int N = 2048;

    private static double[] hann()
    {
        double[] w = new double[N];
        WindowFunctions.hann(w, N);
        return w;
    }

    private static double[] spectrumOf(double[] frame, double[] window)
    {
        double[] x = new double[N];
        for (int i = 0; i < N; i++) x[i] = frame[i] * window[i];
        double[] f = new double[N + 2];
        new FFTRealDouble(N).forward(x, f);
        return f;
    }

    @Test
    @DisplayName("A noise reads through the focus what it reads through the whole window")
    void noiseKeepsItsLevel()
    {
        double[] w = hann();
        SpectralFocus focus = new SpectralFocus();
        focus.prepare(w, N, N, 1.0 / 6.0, 1.0);
        int bins = focus.getNumBins();
        assertEquals(N / 2 + 1, bins);
        double[] focused = new double[bins], sumFocused = new double[bins], sumPlain = new double[bins];
        Random r = new Random(17);
        double[] frame = new double[N];
        for (int pass = 0; pass < 400; pass++)
        {
            for (int i = 0; i < N; i++) frame[i] = r.nextGaussian();
            double[] f = spectrumOf(frame, w);
            focus.power(f, focused);
            for (int k = 0; k < bins; k++)
            {
                sumFocused[k] += focused[k];
                sumPlain[k] += f[2 * k] * f[2 * k] + f[2 * k + 1] * f[2 * k + 1];
            }
        }
        // Away from the two ends of the spectrum, where the smoothing has no
        // bins beyond to read: within a width of the top it reads high.
        int last = (int) (0.8 * bins);
        for (int k = 8; k < last; k += 37)
            assertEquals(1.0, sumFocused[k] / sumPlain[k], 0.2, "bin " + k);
        // And over that stretch as a whole, to a few parts in a hundred.
        double a = 0.0, b = 0.0;
        for (int k = 8; k < last; k++)
        {
            a += sumFocused[k];
            b += sumPlain[k];
        }
        assertEquals(1.0, a / b, 0.03);
    }

    @Test
    @DisplayName("The top of the spectrum is read through a short stretch around the window's peak, the low end through nearly all of it")
    void highBinsFocusInTime()
    {
        double[] w = hann();
        SpectralFocus focus = new SpectralFocus();
        focus.prepare(w, N, N, 1.0 / 6.0, 1.0);
        int bins = focus.getNumBins();
        int peak = N / 2;

        // Flat windows of ones: the clicks are weighed by the focus alone.
        double[] ones = new double[N];
        java.util.Arrays.fill(ones, 1.0);
        double[] atPeak = new double[N], away = new double[N];
        atPeak[peak] = 1.0;
        away[peak - N / 4] = 1.0;
        double[] pPeak = new double[bins], pAway = new double[bins];
        focus.power(spectrumOf(atPeak, ones), pPeak);
        focus.power(spectrumOf(away, ones), pAway);

        int low = 4, high = 800;
        double keptLow = pAway[low] / pPeak[low], keptHigh = pAway[high] / pPeak[high];
        assertTrue(keptLow > 0.02, "the low end still reads a click a quarter of the window away: " + keptLow);
        assertTrue(keptHigh < 1e-3 * keptLow, "the top does not: " + keptHigh + " against " + keptLow);
        for (int k = 0; k < bins; k++) assertTrue(focus.getGain(k) >= 1.0 - 1e-9, "gain under one at bin " + k);
        assertTrue(focus.getGain(high) > focus.getGain(low), "a shorter stretch holds less energy and is scaled up more");
    }

    @Test
    @DisplayName("smooth() gives what power() squares")
    void smoothAndPowerAgree()
    {
        double[] w = hann();
        SpectralFocus focus = new SpectralFocus();
        focus.prepare(w, N, N, 1.0 / 3.0, 1.0);
        int bins = focus.getNumBins();
        Random r = new Random(5);
        double[] frame = new double[N];
        for (int i = 0; i < N; i++) frame[i] = r.nextGaussian();
        double[] f = spectrumOf(frame, w);
        double[] re = new double[bins], im = new double[bins], p = new double[bins];
        focus.smooth(f, re, im);
        focus.power(f, p);
        for (int k = 0; k < bins; k++)
            assertEquals(focus.getGain(k) * (re[k] * re[k] + im[k] * im[k]), p[k], 1e-12 * (1.0 + p[k]), "bin " + k);
    }

    @Test
    @DisplayName("A padded transform is read from the window's start")
    void paddedTransform()
    {
        double[] w = hann();
        SpectralFocus focus = new SpectralFocus();
        focus.prepare(w, N, 2 * N, 1.0 / 6.0, 2.0);
        assertEquals(N + 1, focus.getNumBins());
        // A steady sine on a bin: the focus keeps it where it is.
        int bin = 300;   // of the padded transform
        double[] x = new double[2 * N];
        for (int i = 0; i < N; i++) x[i] = w[i] * Math.cos(2.0 * Math.PI * bin * i / (2.0 * N));
        double[] f = new double[2 * N + 2];
        new FFTRealDouble(2 * N).forward(x, f);
        double[] p = new double[N + 1];
        focus.power(f, p);
        int top = 0;
        for (int k = 1; k <= N; k++)
            if (p[k] > p[top]) top = k;
        assertEquals(bin, top, 2, "the line stays at its bin");
    }
}
