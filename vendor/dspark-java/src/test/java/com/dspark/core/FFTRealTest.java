package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link FFTReal}. */
class FFTRealTest
{
    @Test
    @DisplayName("Forward of a DC signal puts all energy in bin 0")
    void dcInBinZero()
    {
        int n = 16;
        FFTReal fft = new FFTReal(n);
        float[] x = new float[n];
        java.util.Arrays.fill(x, 1.0f);
        float[] freq = new float[fft.getFrequencyDomainSize()];
        fft.forward(x, freq);

        assertEquals(16.0, freq[0], 1e-3);     // DC = sum
        assertEquals(0.0, freq[1], 1e-3);
        for (int k = 1; k <= n / 2; k++)
        {
            assertEquals(0.0, freq[2 * k], 1e-3);
            assertEquals(0.0, freq[2 * k + 1], 1e-3);
        }
    }

    @Test
    @DisplayName("Inverse exactly undoes forward (round-trip, scale = 1)")
    void roundTrip()
    {
        int n = 1024;
        FFTReal fft = new FFTReal(n);
        Random rnd = new Random(42);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = rnd.nextFloat() * 2f - 1f;

        float[] freq = new float[fft.getFrequencyDomainSize()];
        float[] y = new float[n];
        fft.forward(x, freq);
        fft.inverse(freq, y);

        for (int i = 0; i < n; i++) assertEquals(x[i], y[i], 1e-3);
    }

    @Test
    @DisplayName("A cosine concentrates its magnitude in the matching bin")
    void cosinePeakBin()
    {
        int n = 64, bin = 8;
        FFTReal fft = new FFTReal(n);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) Math.cos(2.0 * Math.PI * bin * i / n);

        float[] freq = new float[fft.getFrequencyDomainSize()];
        fft.forward(x, freq);
        float[] mags = new float[fft.getNumBins()];
        fft.computeMagnitudes(freq, mags);

        int argmax = 0;
        for (int k = 1; k < mags.length; k++) if (mags[k] > mags[argmax]) argmax = k;
        assertEquals(bin, argmax);
    }
}
