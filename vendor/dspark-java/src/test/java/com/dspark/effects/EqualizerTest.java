package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Equalizer}. */
class EqualizerTest
{
    private static final double SR = 48000.0;

    private static double peak(float[] b)
    {
        double m = 0.0;
        for (float v : b) m = Math.max(m, Math.abs(v));
        return m;
    }

    private static double rms(float[] b)
    {
        double s = 0.0;
        for (float v : b) s += (double) v * v;
        return Math.sqrt(s / b.length);
    }

    @Test
    @DisplayName("With no bands, minimum-phase mode is an identity")
    void minimumPhaseIdentity()
    {
        Equalizer eq = new Equalizer(8);
        eq.prepare(SR, 0, 1);
        float[] buf = { 0.1f, -0.3f, 0.5f, -0.2f, 0.4f };
        float[] expected = buf.clone();
        eq.process(buf, 1);
        for (int i = 0; i < buf.length; i++) assertEquals(expected[i], buf[i], 1e-6f);
    }

    @Test
    @DisplayName("A +6 dB peak boosts a tone at its centre frequency ~2x")
    void peakBoost()
    {
        Equalizer eq = new Equalizer(8);
        eq.prepare(SR, 0, 1);
        eq.setBand(0, Equalizer.BandType.PEAK, 1000.0, 6.0, 1.0, true);

        int n = 48000;
        float[] buf = new float[n];
        double step = 2.0 * Math.PI * 1000.0 / SR;
        for (int i = 0; i < n; i++) buf[i] = (float) (0.1 * Math.sin(step * i));
        eq.process(buf, 1);

        // Steady-state gain at the centre = 10^(6/20) ~= 1.995.
        double tail = 0.0;
        for (int i = n - 2048; i < n; i++) tail = Math.max(tail, Math.abs(buf[i]));
        assertEquals(0.1 * Math.pow(10.0, 6.0 / 20.0), tail, 0.01);
    }

    @Test
    @DisplayName("Tilt magnitude response darkens lows and brightens highs")
    void tiltResponse()
    {
        Equalizer eq = new Equalizer(8);
        eq.prepare(SR, 0, 1);
        eq.setBand(0, Equalizer.BandType.TILT, 1000.0, 6.0, 0.707, true);

        double[] freqs = { 50.0, 18000.0 };
        double[] mags = new double[2];
        eq.getMagnitudeResponse(freqs, mags);
        assertTrue(mags[0] < 1.0, "low should be cut");
        assertTrue(mags[1] > 1.0, "high should be boosted");
    }

    @Test
    @DisplayName("Linear-phase flat EQ has unity gain (delay only)")
    void linearPhaseUnityGain()
    {
        int block = 1024;
        Equalizer eq = new Equalizer(8);
        eq.prepare(SR, block, 1);
        eq.setFilterMode(Equalizer.FilterMode.LINEAR_PHASE);

        double step = 2.0 * Math.PI * 1000.0 / SR;
        float[] last = null;
        int globalN = 0;
        for (int blk = 0; blk < 12; blk++)
        {
            float[] buf = new float[block];
            for (int i = 0; i < block; i++) buf[i] = (float) (0.5 * Math.sin(step * (globalN++)));
            eq.process(buf, 1);
            last = buf;
        }
        // A flat linear-phase EQ is a pure delay: the steady-state RMS is preserved.
        assertEquals(0.5 / Math.sqrt(2.0), rms(last), 0.02);
    }
}
