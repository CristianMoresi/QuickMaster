package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Smoothers}. */
class SmoothersTest
{
    // -- Ports of the C++ assertions ---------------------------------------------------

    @Test
    @DisplayName("LinearSmoother converges")
    void linearConverges()
    {
        Smoothers.LinearSmoother s = new Smoothers.LinearSmoother();
        s.reset(44100.0, 10.0f, 0.0f);
        s.setTargetValue(1.0f);
        for (int i = 0; i < 500; ++i) s.getNextValue();
        assertEquals(1.0f, s.getNextValue(), 0.01f);
    }

    @Test
    @DisplayName("ExponentialSmoother converges")
    void exponentialConverges()
    {
        Smoothers.ExponentialSmoother s = new Smoothers.ExponentialSmoother();
        s.reset(44100.0, 20.0f, 0.0f);
        s.setTargetValue(1.0f);
        for (int i = 0; i < 4410; ++i) s.getNextValue();
        assertEquals(1.0f, s.getNextValue(), 0.05f);
    }

    @Test
    @DisplayName("ExponentialSmoother: a sign-flipping snap does not replay the previous ramp")
    void exponentialSnapDoesNotReplay()
    {
        Smoothers.ExponentialSmoother s = new Smoothers.ExponentialSmoother();
        s.reset(48000.0, 10.0f, 1.0f);
        s.setTargetValue(2.0f);
        for (int i = 0; i < 600; ++i) s.getNextValue();
        assertEquals(2.0f, s.getCurrentValue(), 1e-6f);
        s.setTargetValue(-1.0f);
        for (int i = 0; i < 600; ++i) assertEquals(-1.0f, s.getNextValue(), 1e-6f);
        assertFalse(s.isSmoothing());
    }

    @Test
    @DisplayName("OnePoleSmoother converges")
    void onePoleConverges()
    {
        Smoothers.OnePoleSmoother s = new Smoothers.OnePoleSmoother();
        s.reset(44100.0, 10.0f, 0.0f);
        s.setTargetValue(0.5f);
        for (int i = 0; i < 4410; ++i) s.getNextValue();
        assertEquals(0.5f, s.getNextValue(), 0.05f);
    }

    @Test
    @DisplayName("HermiteSmoother lands exactly without a corner and never overshoots a retarget")
    void hermiteGlide()
    {
        Smoothers.HermiteSmoother h = new Smoothers.HermiteSmoother();
        h.reset(48000.0, 10.0f, 0.0f);
        h.setTargetValue(1.0f);
        float previous = 0.0f;
        boolean monotone = true;
        for (int n = 1; n <= 480; ++n)
        {
            final float v = h.getNextValue();
            monotone = monotone && v >= previous && v <= 1.0f;
            if (n == 240) assertEquals(0.5f, v, 1e-6);
            previous = v;
        }
        assertTrue(monotone);
        assertEquals(1.0f, h.getCurrentValue(), 0.0f);
        assertFalse(h.isSmoothing());

        h.reset(48000.0, 10.0f, 0.0f);
        h.setTargetValue(1.0f);
        float a = 0.0f;
        float b = 0.0f;
        for (int n = 0; n < 240; ++n) { a = b; b = h.getNextValue(); }
        h.setTargetValue(1.5f);
        final float c = h.getNextValue();
        assertTrue(Math.abs((c - b) - (b - a)) < 2e-5f);
        float peak = c;
        for (int n = 1; n < 480; ++n) peak = Math.max(peak, h.getNextValue());
        assertTrue(peak < 1.5f + 1e-6f);
        assertEquals(1.5f, h.getCurrentValue(), 0.0f);
        assertFalse(h.isSmoothing());

        h.setTargetValue(Float.NaN);
        assertEquals(1.5f, h.getTargetValue(), 0.0f);
    }

    @Test
    @DisplayName("isSmoothing() is false at the target and true after a retarget")
    void isSmoothing()
    {
        Smoothers.LinearSmoother s = new Smoothers.LinearSmoother();
        s.reset(44100.0, 10.0f, 0.0f);
        s.setTargetValue(0.0f);
        assertFalse(s.isSmoothing());
        s.setTargetValue(1.0f);
        assertTrue(s.isSmoothing());
    }

    @Test
    @DisplayName("MultiPoleSmoother honours the common contract")
    void multiPoleContract()
    {
        Smoothers.MultiPoleSmoother s = new Smoothers.MultiPoleSmoother(4);
        s.reset(48000.0, 10.0f, 0.0f);
        assertFalse(s.isSmoothing());
        s.setTargetValue(1.0f);
        assertTrue(s.isSmoothing());
        assertEquals(1.0f, s.getTargetValue(), 1e-9f);
        for (int i = 0; i < 48000; ++i) s.getNextValue();
        assertEquals(1.0f, s.getCurrentValue(), 1e-4f);
        assertFalse(s.isSmoothing());
        s.setTargetValue(0.25f);
        s.getNextValue();
        s.skip();
        assertEquals(0.25f, s.getCurrentValue(), 1e-6f);
        assertEquals(0.25f, s.getNextValue(), 1e-6f);
        assertFalse(s.isSmoothing());
        assertThrows(IllegalArgumentException.class, () -> new Smoothers.MultiPoleSmoother(0));
    }

    @Test
    @DisplayName("SVF and Butterworth smoothers complete their overshoot and settle")
    void svfAndButterworthOvershoot()
    {
        final float target = 1e-3f;
        Smoothers.StateVariableSmoother svf = new Smoothers.StateVariableSmoother();
        svf.reset(48000.0, 100.0f, 0.707f, 0.0f);
        svf.setTargetValue(target);
        float maxSvf = 0.0f;
        for (int i = 0; i < 96000; ++i) maxSvf = Math.max(maxSvf, svf.getNextValue());
        assertTrue(maxSvf > target * 1.03f);
        assertEquals(target, svf.getCurrentValue(), 1e-6f);
        assertFalse(svf.isSmoothing());

        Smoothers.ButterworthSmoother bw = new Smoothers.ButterworthSmoother();
        bw.reset(48000.0, 100.0f, 0.0f);
        bw.setTargetValue(target);
        float maxBw = 0.0f;
        for (int i = 0; i < 96000; ++i) maxBw = Math.max(maxBw, bw.getNextValue());
        assertTrue(maxBw > target * 1.03f);
        assertEquals(target, bw.getCurrentValue(), 1e-6f);
        assertFalse(bw.isSmoothing());
    }

    @Test
    @DisplayName("CriticallyDampedSmoother never overshoots")
    void criticallyDampedNeverOvershoots()
    {
        Smoothers.CriticallyDampedSmoother s = new Smoothers.CriticallyDampedSmoother();
        s.reset(48000.0, 20.0f, 0.0f);
        s.setTargetValue(1.0f);
        float maxVal = 0.0f;
        for (int i = 0; i < 48000; ++i) maxVal = Math.max(maxVal, s.getNextValue());
        assertTrue(maxVal < 1.0f + 1e-4f);
        assertEquals(1.0f, s.getCurrentValue(), 1e-5f);
    }

    @Test
    @DisplayName("SlewLimiter: a zero or negative rate freezes, a positive one keeps its budget")
    void slewLimiterRates()
    {
        Smoothers.SlewLimiter s = new Smoothers.SlewLimiter();
        s.reset(48000.0, -100.0f, 0.5f);
        s.setTargetValue(1.0f);
        for (int i = 0; i < 100; ++i) s.getNextValue();
        assertEquals(0.5f, s.getCurrentValue(), 1e-9f);

        Smoothers.SlewLimiter ok = new Smoothers.SlewLimiter();
        ok.reset(1000.0, 100.0f, 0.0f);
        ok.setTargetValue(1.0f);
        ok.getNextValue();
        ok.getNextValue();
        assertEquals(0.2f, ok.getCurrentValue(), 1e-6f);
    }

    @Test
    @DisplayName("SlewLimiter moves at large values (double state)")
    void slewLimiterMovesAtLargeValues()
    {
        Smoothers.SlewLimiter s = new Smoothers.SlewLimiter();
        s.reset(48000.0, 20.0f, 10000.0f);
        s.setTargetValue(11000.0f);
        float v = 0.0f;
        for (int i = 0; i < 2 * 48000; ++i) v = s.getNextValue();
        assertEquals(10040.0f, v, 0.05f);
    }

    @Test
    @DisplayName("A long LinearSmoother ramp lands smoothly (double state)")
    void linearLongRampLandsSmoothly()
    {
        Smoothers.LinearSmoother l = new Smoothers.LinearSmoother();
        l.reset(192000.0, 5000.0f, 0.0f);
        l.setTargetValue(1.0f);
        double prev = 0.0;
        double maxStep = 0.0;
        for (int i = 0; i < 5 * 192000; ++i)
        {
            final double v = l.getNextValue();
            maxStep = Math.max(maxStep, Math.abs(v - prev));
            prev = v;
        }
        assertTrue(maxStep < 1e-5);
        assertEquals(1.0f, l.getCurrentValue(), 0.0f);
    }

    // -- Exact ramp lengths -------------------------------------------------------------

    @Test
    @DisplayName("LinearSmoother reaches the target in exactly (int) (fs * ms / 1000) samples")
    void linearExactLength()
    {
        Smoothers.LinearSmoother s = new Smoothers.LinearSmoother();
        s.reset(44100.0, 10.0f, 0.0f);   // 441 samples
        s.setTargetValue(1.0f);
        for (int i = 1; i < 441; ++i)
        {
            final float v = s.getNextValue();
            assertEquals(i / 441.0, v, 1e-6);
            assertTrue(v < 1.0f);
        }
        assertEquals(1.0f, s.getNextValue(), 0.0f);
        assertFalse(s.isSmoothing());
        assertEquals(1.0f, s.getNextValue(), 0.0f);

        // Truncation: 48 kHz and 10.01 ms is 480.48 samples, so 480.
        s.reset(48000.0, 10.01f, 0.0f);
        s.setTargetValue(-2.0f);
        int n = 0;
        while (s.getCurrentValue() != -2.0f) { s.getNextValue(); ++n; }
        assertEquals(480, n);

        // A ramp of 0 samples jumps at once.
        s.reset(48000.0, 0.0f, 0.0f);
        s.setTargetValue(0.75f);
        assertEquals(0.75f, s.getCurrentValue(), 0.0f);
    }

    @Test
    @DisplayName("ExponentialSmoother lands in exactly its ramp length, geometrically")
    void exponentialExactLength()
    {
        Smoothers.ExponentialSmoother s = new Smoothers.ExponentialSmoother();
        s.reset(48000.0, 10.0f, 100.0f);   // 480 samples
        s.setTargetValue(1000.0f);
        for (int i = 1; i < 480; ++i)
        {
            final float v = s.getNextValue();
            assertEquals(100.0 * Math.pow(10.0, i / 480.0), v, 1e-3);
        }
        assertEquals(1000.0f, s.getNextValue(), 0.0f);
        assertFalse(s.isSmoothing());
        // Tiny targets become +-epsilon.
        s.setTargetValue(0.0f);
        assertEquals(Smoothers.ExponentialSmoother.EPSILON, s.getTargetValue(), 0.0f);
    }

    @Test
    @DisplayName("HermiteSmoother rounds the ramp to whole samples and lands on its last one")
    void hermiteExactLength()
    {
        Smoothers.HermiteSmoother h = new Smoothers.HermiteSmoother();
        h.reset(44100.0, 10.01f, 0.0f);   // 441.441 samples, rounded to 441
        h.setTargetValue(2.0f);
        for (int i = 1; i < 441; ++i)
        {
            h.getNextValue();
            assertTrue(h.isSmoothing());
        }
        assertEquals(2.0f, h.getNextValue(), 0.0f);
        assertFalse(h.isSmoothing());

        h.reset(48000.0, 10.0105f, 0.0f);  // 480.504 samples rounds up to 481
        h.setTargetValue(1.0f);
        int n = 0;
        while (h.isSmoothing()) { h.getNextValue(); ++n; }
        assertEquals(481, n);

        h.reset(48000.0, Float.NaN, 0.0f);   // no ramp: a jump
        h.setTargetValue(1.0f);
        assertEquals(1.0f, h.getCurrentValue(), 0.0f);
        assertFalse(h.isSmoothing());
    }

    @Test
    @DisplayName("LinearSmoother.processBlock matches getNextValue, adding or multiplying, in any block split")
    void linearProcessBlock()
    {
        for (boolean multiply : new boolean[] { false, true })
            for (int block : new int[] { 1, 37, 512, 4096 })
            {
                Smoothers.LinearSmoother a = new Smoothers.LinearSmoother();
                Smoothers.LinearSmoother b = new Smoothers.LinearSmoother();
                a.reset(48000.0, 30.0f, 0.25f);
                b.reset(48000.0, 30.0f, 0.25f);
                a.setTargetValue(0.9f);
                b.setTargetValue(0.9f);
                final int total = 6000;
                float[] signal = new float[total];
                float[] expected = new float[total];
                for (int i = 0; i < total; ++i)
                {
                    signal[i] = (float) Math.sin(0.01 * i);
                    final float value = b.getNextValue();
                    expected[i] = multiply ? signal[i] * value : signal[i] + value;
                }
                float[] chunk = new float[block];
                for (int at = 0; at < total; at += block)
                {
                    final int n = Math.min(block, total - at);
                    System.arraycopy(signal, at, chunk, 0, n);
                    a.processBlock(chunk, n, multiply);
                    for (int i = 0; i < n; ++i)
                        assertEquals(expected[at + i], chunk[i], 0.0f, "block " + block + " at " + (at + i));
                }
                assertEquals(b.getCurrentValue(), a.getCurrentValue(), 0.0f);
            }
    }

    @Test
    @DisplayName("A Q of 0.707 overshoots, Q = 0.5 does not, and Q is floored at 0.01")
    void stateVariableQ()
    {
        Smoothers.StateVariableSmoother s = new Smoothers.StateVariableSmoother();
        s.reset(48000.0, 5.0f, 0.707f, 0.0f);
        s.setTargetValue(1.0f);
        float peak = 0.0f;
        for (int i = 0; i < 48000; ++i) peak = Math.max(peak, s.getNextValue());
        assertTrue(peak > 1.03f);
        s.reset(48000.0, 5.0f, 0.0f, 0.0f);   // Q floored at 0.01: heavily damped, finite
        s.setTargetValue(1.0f);
        for (int i = 0; i < 4800; ++i) assertTrue(Float.isFinite(s.getNextValue()));
        // A zero time constant moves at the fastest cutoff (0.49 fs) instead of freezing.
        s.reset(48000.0, 0.0f, 0.707f, 0.0f);
        s.setTargetValue(1.0f);
        for (int i = 0; i < 100; ++i) s.getNextValue();
        assertEquals(1.0f, s.getCurrentValue(), 1e-3f);
        for (int i = 0; i < 48000; ++i) s.getNextValue();
        assertFalse(s.isSmoothing());
        assertEquals(1.0f, s.getCurrentValue(), 0.0f);
    }

    @Test
    @DisplayName("ButterworthSmoother starts in its steady state (no first-sample click)")
    void butterworthSteadyStart()
    {
        Smoothers.ButterworthSmoother bw = new Smoothers.ButterworthSmoother();
        bw.reset(48000.0, 10.0f, 0.5f);
        bw.setTargetValue(0.5f);
        assertFalse(bw.isSmoothing());
        assertEquals(0.5f, bw.getNextValue(), 0.0f);
        bw.setTargetValue(0.6f);
        final float first = bw.getNextValue();
        assertTrue(first > 0.5f && first < 0.5001f, "first " + first);
    }

    @Test
    @DisplayName("AsymmetricSmoother rises with the attack and falls with the release")
    void asymmetric()
    {
        Smoothers.AsymmetricSmoother s = new Smoothers.AsymmetricSmoother();
        s.reset(48000.0, 1.0f, 100.0f, 0.0f);
        s.setTargetValue(1.0f);
        for (int i = 0; i < 48; ++i) s.getNextValue();   // one attack time constant
        assertEquals(1.0 - Math.exp(-1.0), s.getCurrentValue(), 1e-4);
        s.skip();
        s.setTargetValue(0.0f);
        for (int i = 0; i < 4800; ++i) s.getNextValue(); // one release time constant
        assertEquals(Math.exp(-1.0), s.getCurrentValue(), 1e-4);
    }

    // -- NaN handling, as the C++ ------------------------------------------------------

    @Test
    @DisplayName("NaN targets: Hermite ignores them, the one-pole and exponential recover at the next target")
    void nanTargets()
    {
        Smoothers.OnePoleSmoother one = new Smoothers.OnePoleSmoother();
        one.reset(48000.0, 10.0f, 0.0f);
        one.setTargetValue(Float.NaN);
        assertTrue(Float.isNaN(one.getNextValue()));   // the C++ has no guard: it snaps onto NaN
        one.setTargetValue(0.5f);
        assertEquals(0.5f, one.getNextValue(), 0.0f);  // |NaN - 0.5| > eps is false: it snaps back

        Smoothers.ExponentialSmoother exp = new Smoothers.ExponentialSmoother();
        exp.reset(48000.0, 10.0f, 1.0f);
        exp.setTargetValue(Float.NaN);
        assertTrue(Float.isNaN(exp.getCurrentValue()));
        exp.setTargetValue(2.0f);
        assertEquals(2.0f, exp.getCurrentValue(), 0.0f);

        Smoothers.LinearSmoother lin = new Smoothers.LinearSmoother();
        lin.reset(48000.0, 10.0f, 0.0f);
        lin.setTargetValue(Float.NaN);
        assertTrue(Float.isNaN(lin.getNextValue()));   // a NaN ramp, as the C++
        lin.setCurrentAndTargetValue(0.25f);
        assertEquals(0.25f, lin.getNextValue(), 0.0f);
    }
}
