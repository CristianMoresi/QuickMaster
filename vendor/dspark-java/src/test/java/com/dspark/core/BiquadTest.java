package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Processing tests for {@link Biquad}. */
class BiquadTest
{
    private static final double SR = 48000.0;

    @Test
    @DisplayName("Identity coefficients pass the signal through unchanged")
    void identityPassthrough()
    {
        Biquad bq = new Biquad(1);
        float[] buf = { 0.1f, -0.5f, 0.9f, -0.2f };
        float[] expected = buf.clone();
        bq.processBlock(buf, 1);
        assertArrayEqualsExact(expected, buf);
    }

    @Test
    @DisplayName("Low-pass attenuates a Nyquist-rate signal far more than DC")
    void lowPassAttenuatesHighFrequencies()
    {
        Biquad bq = new Biquad(1);
        bq.setCoeffs(BiquadCoeffs.lowPass(SR, 1000.0, BiquadCoeffs.BUTTERWORTH_Q));

        // Nyquist-rate signal: alternating +1 / -1.
        float[] hi = new float[2048];
        for (int i = 0; i < hi.length; i++) hi[i] = (i % 2 == 0) ? 1f : -1f;
        bq.processBlock(hi, 1);
        double hiPeak = peak(hi, hi.length - 256, hi.length);   // settled tail

        bq.reset();
        // DC signal: constant 1.
        float[] dc = new float[2048];
        java.util.Arrays.fill(dc, 1f);
        bq.processBlock(dc, 1);
        double dcLevel = peak(dc, dc.length - 256, dc.length);

        assertEquals(1.0, dcLevel, 0.02);          // DC passes
        assertTrue(hiPeak < 0.05, "HF should be crushed, was " + hiPeak);
    }

    private static double peak(float[] a, int from, int to)
    {
        double m = 0.0;
        for (int i = from; i < to; i++) m = Math.max(m, Math.abs(a[i]));
        return m;
    }

    private static void assertArrayEqualsExact(float[] expected, float[] actual)
    {
        for (int i = 0; i < expected.length; i++)
        {
            assertEquals(expected[i], actual[i], 0.0f);
        }
    }
}
