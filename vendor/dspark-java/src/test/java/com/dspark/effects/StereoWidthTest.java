package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link StereoWidth} (fast path, no bass-mono). */
class StereoWidthTest
{
    private static final float EPS = 1e-6f;

    @Test
    @DisplayName("Width 1.0 is an identity transform")
    void unityIsIdentity()
    {
        StereoWidth sw = new StereoWidth();
        sw.prepare(48000);
        sw.setWidth(1.0);
        float[] buf = { 0.5f, 0.1f, -0.3f, 0.2f };
        float[] expected = buf.clone();
        sw.process(buf, 2);
        assertEquals(expected[0], buf[0], EPS);
        assertEquals(expected[1], buf[1], EPS);
        assertEquals(expected[2], buf[2], EPS);
        assertEquals(expected[3], buf[3], EPS);
    }

    @Test
    @DisplayName("Width 0.0 collapses to mono (L == R == centre)")
    void zeroWidthIsMono()
    {
        StereoWidth sw = new StereoWidth();
        sw.prepare(48000);
        sw.setWidth(0.0);
        float[] buf = { 0.5f, 0.1f };
        sw.process(buf, 2);
        float mid = (0.5f + 0.1f) / 2f;
        assertEquals(mid, buf[0], EPS);
        assertEquals(mid, buf[1], EPS);
    }

    @Test
    @DisplayName("Width 2.0 doubles the side component")
    void doubleWidth()
    {
        StereoWidth sw = new StereoWidth();
        sw.prepare(48000);
        sw.setWidth(2.0);
        float l = 0.5f, r = 0.1f;
        float[] buf = { l, r };
        sw.process(buf, 2);
        double mid = (l + r) * 0.5;
        double side = (l - r) * 0.5 * 2.0;
        assertEquals(mid + side, buf[0], EPS);
        assertEquals(mid - side, buf[1], EPS);
    }

    @Test
    @DisplayName("Mono input passes through unchanged")
    void monoPassthrough()
    {
        StereoWidth sw = new StereoWidth();
        sw.prepare(48000);
        sw.setWidth(0.0);
        float[] buf = { 0.3f, -0.4f, 0.5f };
        float[] expected = buf.clone();
        sw.process(buf, 1);
        assertEquals(expected[0], buf[0], 0.0f);
        assertEquals(expected[1], buf[1], 0.0f);
        assertEquals(expected[2], buf[2], 0.0f);
    }
}
