package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Dither}. */
class DitherTest
{
    @Test
    @DisplayName("Output lands exactly on the 16-bit quantization grid")
    void outputIsQuantized()
    {
        Dither d = new Dither(16, false);
        double step = d.getQuantisationStep();
        for (int i = 0; i < 5000; i++)
        {
            float in = (float) Math.sin(i * 0.01) * 0.7f;
            float out = d.processSample(in, 0);
            double k = out / step;
            assertTrue(Math.abs(k - Math.rint(k)) < 1e-3,
                    "output not on grid: " + out);
            assertTrue(out >= -1.0f && out <= 1.0f, "out of range: " + out);
        }
    }

    @Test
    @DisplayName("Dithering silence stays within a couple of quantization steps")
    void silenceStaysSmall()
    {
        Dither d = new Dither(16, false);
        double step = d.getQuantisationStep();
        for (int i = 0; i < 1000; i++)
        {
            float out = d.processSample(0.0f, 0);
            assertTrue(Math.abs(out) <= 2.0 * step + 1e-9, "noise too large: " + out);
        }
    }

    @Test
    @DisplayName("Positive full scale clamps to the int grid maximum (one step below 1.0)")
    void fullScaleClampsToGridMax()
    {
        Dither d = new Dither(16, false);
        double step = d.getQuantisationStep();
        double gridMax = 1.0 - step;    // 32767/32768
        for (int i = 0; i < 2000; i++)
        {
            float out = d.processSample(1.0f, 0);
            assertTrue(out <= (float) gridMax + 1e-9,
                    "positive full scale must clamp to 32767/32768, was " + out);
        }
    }

    @Test
    @DisplayName("Shaping recovers promptly after a sustained overload (bounded feedback)")
    void shapingRecoversAfterOverload()
    {
        Dither d = new Dither(16, true);
        for (int i = 0; i < 5000; i++) d.processSample(1.5f, 0);   // hard overload
        // Back to silence: within a few samples the output must sit near 0,
        // not pinned at full scale by accumulated clip error.
        float out = 0.0f;
        for (int i = 0; i < 8; i++) out = d.processSample(0.0f, 0);
        assertTrue(Math.abs(out) <= 4.0 * d.getQuantisationStep(),
                "output still pinned after overload: " + out);
    }

    @Test
    @DisplayName("Noise shaping preserves the long-term average (unbiased)")
    void noiseShapingIsUnbiased()
    {
        Dither d = new Dither(16, true);
        double sumIn = 0.0, sumOut = 0.0;
        int n = 20000;
        for (int i = 0; i < n; i++)
        {
            float in = 0.123f;             // constant, between grid points
            sumIn += in;
            sumOut += d.processSample(in, 0);
        }
        // Mean output should track mean input to well within a quantization step.
        assertTrue(Math.abs(sumOut - sumIn) / n < d.getQuantisationStep(),
                "mean drifted: in=" + (sumIn / n) + " out=" + (sumOut / n));
    }

    @Test
    @DisplayName("Non-finite input is sanitised and does not poison the error feedback")
    void nonFiniteInputIsSanitised()
    {
        Dither d = new Dither(16, true);   // shaping on: a NaN would park in the loop
        double step = d.getQuantisationStep();
        double gridMax = 1.0 - step;

        // NaN is treated as silence and leaves no residue behind.
        assertTrue(Float.isFinite(d.processSample(Float.NaN, 0)), "NaN output not finite");
        float afterNaN = 0.0f;
        for (int i = 0; i < 8; i++) afterNaN = d.processSample(0.0f, 0);
        assertTrue(Math.abs(afterNaN) <= 4.0 * step, "feedback poisoned by NaN: " + afterNaN);

        // Infinities clip to full scale, not to a non-finite value.
        float pos = d.processSample(Float.POSITIVE_INFINITY, 0);
        float neg = d.processSample(Float.NEGATIVE_INFINITY, 0);
        assertTrue(Float.isFinite(pos) && pos <= (float) gridMax + 1e-9, "+Inf not clamped: " + pos);
        assertTrue(Float.isFinite(neg) && neg >= -1.0f - 1e-9, "-Inf not clamped: " + neg);
    }

    @Test
    @DisplayName("Lipshitz 5-tap shaper stays on the grid and tracks the mean")
    void lipshitz5StaysOnGridAndUnbiased()
    {
        Dither d = new Dither(16, true);
        d.setShaper(Dither.Shaper.LIPSHITZ5);
        assertEquals(Dither.Shaper.LIPSHITZ5, d.getShaper());

        double step = d.getQuantisationStep();
        double sumIn = 0.0, sumOut = 0.0;
        int n = 20000;
        for (int i = 0; i < n; i++)
        {
            float in = 0.137f;             // constant, between grid points
            float out = d.processSample(in, 0);
            double k = out / step;
            assertTrue(Math.abs(k - Math.rint(k)) < 1e-3, "output not on grid: " + out);
            sumIn += in;
            sumOut += out;
        }
        assertTrue(Math.abs(sumOut - sumIn) / n < step,
                "mean drifted under Lipshitz5: in=" + (sumIn / n) + " out=" + (sumOut / n));
    }
}
