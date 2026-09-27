package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link Normalizer}. */
class NormalizerTest
{
    private static final double EPS = 1e-4;

    private static float maxAbs(float[] b)
    {
        float m = 0f;
        for (float s : b) m = Math.max(m, Math.abs(s));
        return m;
    }

    @Test
    @DisplayName("Quiet input is amplified so its peak reaches 0 dBFS")
    void quietAmplified()
    {
        Normalizer n = new Normalizer(0.0);
        float[] buf = { 0.25f, -0.5f, 0.1f, -0.3f };
        n.analyze(buf);
        assertEquals(2.0, n.getGain(), EPS);
        n.process(buf);
        assertEquals(1.0, maxAbs(buf), EPS);
    }

    @Test
    @DisplayName("Loud input is attenuated to a -6 dBFS target")
    void loudAttenuated()
    {
        Normalizer n = new Normalizer(-6.0);
        float[] buf = { 0.9f, -0.7f, 0.4f };
        n.analyze(buf);
        double expectedGain = Math.pow(10.0, -6.0 / 20.0) / 0.9;
        assertEquals(expectedGain, n.getGain(), EPS);
        n.process(buf);
        assertEquals(Math.pow(10.0, -6.0 / 20.0), maxAbs(buf), EPS);
    }

    @Test
    @DisplayName("Digital silence yields unity gain")
    void silenceUnity()
    {
        Normalizer n = new Normalizer();
        n.analyze(new float[512]);
        assertEquals(1.0, n.getGain(), 0.0);
    }

    @Test
    @DisplayName("Disabled normalizer leaves the buffer unchanged")
    void disabledPassthrough()
    {
        Normalizer n = new Normalizer(0.0);
        n.analyze(new float[] { 0.5f });
        n.setEnabled(false);
        float[] buf = { 0.2f, -0.3f };
        n.process(buf);
        assertEquals(0.2f, buf[0], 0.0f);
        assertEquals(-0.3f, buf[1], 0.0f);
    }

    @Test
    @DisplayName("Target outside [-60, 0] dBFS is rejected")
    void targetRange()
    {
        assertThrows(IllegalArgumentException.class, () -> new Normalizer(0.1));
        assertThrows(IllegalArgumentException.class, () -> new Normalizer(-60.1));
    }
}
