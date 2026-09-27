package com.dspark.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LevelFollower}. */
class LevelFollowerTest
{
    private static final double SR = 48000.0;

    @Test
    @DisplayName("RMS of a 0.5-amplitude sine settles near 0.354 (0.5/sqrt2)")
    void rmsOfSine()
    {
        LevelFollower lf = new LevelFollower();
        lf.prepare(SR, 1);
        float[] buf = new float[(int) SR];   // 1 s mono
        double step = 2.0 * Math.PI * 1000.0 / SR;
        for (int i = 0; i < buf.length; i++) buf[i] = (float) (0.5 * Math.sin(step * i));
        lf.process(buf, 1);

        assertEquals(0.5 / Math.sqrt(2.0), lf.getRmsLevel(0), 0.02);
        assertEquals(0.5, lf.getPeakLevel(0), 0.03);
    }

    @Test
    @DisplayName("Peak decays after the signal stops")
    void peakDecays()
    {
        LevelFollower lf = new LevelFollower();
        lf.prepare(SR, 1);
        lf.setReleaseMs(50.0);

        float[] hit = new float[1000];
        java.util.Arrays.fill(hit, 1.0f);
        lf.process(hit, 1);
        double afterHit = lf.getPeakLevel(0);

        float[] silence = new float[(int) SR];   // 1 s of silence
        lf.process(silence, 1);
        double afterSilence = lf.getPeakLevel(0);

        assertTrue(afterHit > 0.9, "peak should rise to ~1, was " + afterHit);
        assertTrue(afterSilence < 0.01, "peak should decay, was " + afterSilence);
    }
}
