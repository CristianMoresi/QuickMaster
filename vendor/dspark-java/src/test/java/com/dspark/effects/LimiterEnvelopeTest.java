package com.dspark.effects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LimiterEnvelopeTest
{
    @Test
    void keepsOutputUnderThreshold()
    {
        int frames = 8000, ch = 2;
        float[] x = new float[frames * ch];
        java.util.Random rnd = new java.util.Random(7);
        for (int n = 0; n < frames; n++)
        {
            double s = 0.3 * Math.sin(2.0 * Math.PI * 220.0 * n / 48000.0);
            if (n % 900 == 0) s = 0.95;                       // sharp transients above the ceiling
            s += 0.02 * (rnd.nextDouble() - 0.5);
            x[2 * n] = (float) s;
            x[2 * n + 1] = (float) s;
        }
        double th = 0.5;
        float[] g = LimiterEnvelope.compute(x, ch, th, 96, 480);

        assertEquals(frames, g.length);
        for (int n = 0; n < frames; n++)
        {
            assertTrue(g[n] > 0.0f && g[n] <= 1.0001f, "gain out of range at " + n + ": " + g[n]);
            double peak = Math.max(Math.abs(x[2 * n]), Math.abs(x[2 * n + 1]));
            assertTrue(peak * g[n] <= th + 1e-4, "overshoot at " + n + ": " + (peak * g[n]));
        }
    }

    @Test
    void transparentBelowThreshold()
    {
        int frames = 4000, ch = 2;
        float[] x = new float[frames * ch];
        for (int n = 0; n < frames; n++)
        {
            double s = 0.3 * Math.sin(2.0 * Math.PI * 220.0 * n / 48000.0);
            x[2 * n] = (float) s;
            x[2 * n + 1] = (float) s;
        }
        float[] g = LimiterEnvelope.compute(x, ch, 0.5, 96, 480);
        for (int n = 0; n < frames; n++)
        {
            assertEquals(1.0f, g[n], 1e-6f, "below-threshold material must pass at unity");
        }
    }
}
