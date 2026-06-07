package com.quickmaster.processing.dynamics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LevelerProcessor} (continuous loudness rider toward the median). */
class LevelerProcessorTest
{
    private static final int SR = 44100;

    private static float[] sig(double amp1, double sec1, double amp2, double sec2)
    {
        int n1 = (int) (sec1 * SR), n2 = (int) (sec2 * SR);
        float[] s = new float[n1 + n2];
        double step = 2.0 * Math.PI * 220.0 / SR;
        for (int i = 0; i < s.length; i++)
        {
            double amp = (i < n1) ? amp1 : amp2;
            s[i] = (float) (amp * Math.sin(step * i));
        }
        return s;
    }

    private static float[] gainEnvelope(LevelerProcessor p, int frames)
    {
        p.prepare(SR, frames);
        float[] ones = new float[frames];
        java.util.Arrays.fill(ones, 1.0f);
        return p.process(ones, 1);
    }

    @Test
    @DisplayName("A loud section (minority) is turned down toward the quieter body")
    void turnsLoudSectionDown()
    {
        float[] s = sig(0.8, 6.0, 0.22, 14.0);     // loud 6 s, quiet body 14 s -> median = quiet
        LevelerProcessor p = new LevelerProcessor();
        p.setEnabled(true);
        p.setLeveling(1.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, s.length);
        assertTrue(env[(int) (3.0 * SR)] < 0.6f, "loud section should come down, was " + env[(int) (3.0 * SR)]);
        assertTrue(env[(int) (15.0 * SR)] > 0.85f, "quiet body ~ unity, was " + env[(int) (15.0 * SR)]);
    }

    @Test
    @DisplayName("A quiet section (minority) is turned up toward the louder body")
    void turnsQuietSectionUp()
    {
        float[] s = sig(0.22, 6.0, 0.8, 14.0);     // quiet 6 s, loud body 14 s -> median = loud
        LevelerProcessor p = new LevelerProcessor();
        p.setEnabled(true);
        p.setLeveling(1.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, s.length);
        assertTrue(env[(int) (3.0 * SR)] > 1.3f, "quiet section should come up, was " + env[(int) (3.0 * SR)]);
        assertTrue(env[(int) (15.0 * SR)] > 0.9f && env[(int) (15.0 * SR)] < 1.1f,
                "loud body ~ unity, was " + env[(int) (15.0 * SR)]);
    }

    @Test
    @DisplayName("Leveling scales the effect; 0 and disabled pass through")
    void scalesAndNoEffect()
    {
        float[] s = sig(0.8, 6.0, 0.22, 14.0);
        LevelerProcessor p = new LevelerProcessor();
        p.setEnabled(true);

        p.setLeveling(0.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-3f);

        p.setLeveling(0.5);
        p.analyze(s, 1);
        float half = gainEnvelope(p, s.length)[(int) (3.0 * SR)];
        p.setLeveling(1.0);
        p.analyze(s, 1);
        float full = gainEnvelope(p, s.length)[(int) (3.0 * SR)];
        assertTrue(half > full, "more leveling = more reduction (" + half + " vs " + full + ")");

        p.setLeveling(1.0);
        p.setEnabled(false);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-6f);
    }

    @Test
    @DisplayName("Higher speed settles into a new section sooner")
    void speedControlsSettling()
    {
        float[] s = sig(0.22, 8.0, 0.8, 12.0);     // quiet 8 s (boosted) -> loud body
        LevelerProcessor p = new LevelerProcessor();
        p.setEnabled(true);
        p.setLeveling(1.0);
        p.prepare(SR, s.length);

        p.setSpeed(1.0);
        p.analyze(s, 1);
        float fast = gainEnvelope(p, s.length)[(int) (9.0 * SR)];   // 1 s into the loud part
        p.setSpeed(0.0);
        p.analyze(s, 1);
        float slow = gainEnvelope(p, s.length)[(int) (9.0 * SR)];
        assertTrue(fast < slow - 0.1f, "fast should have settled toward the loud level (fast="
                + fast + ", slow=" + slow + ")");
    }

    @Test
    @DisplayName("A quiet passage is not dipped before a louder part arrives")
    void noEarlyDipBeforeLoudPart()
    {
        // Quiet (boosted) then loud; just before the boundary the boost must hold,
        // not drop early.
        float[] s = sig(0.25, 6.0, 0.8, 14.0);
        LevelerProcessor p = new LevelerProcessor();
        p.setEnabled(true);
        p.setLeveling(1.0);
        p.setSpeed(0.5);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, s.length);
        float mid = env[(int) (3.0 * SR)];          // middle of the quiet part
        float justBefore = env[(int) (5.5 * SR)];   // still in the quiet part
        assertTrue(justBefore > 0.9f * mid, "quiet tail dipped early (mid=" + mid
                + ", justBefore=" + justBefore + ")");
    }

    @Test
    @DisplayName("A sub-second break is ignored, not regulated")
    void ignoresShortBreak()
    {
        int n = 10 * SR;
        float[] s = new float[n];
        double step = 2.0 * Math.PI * 220.0 / SR;
        for (int i = 0; i < n; i++) s[i] = (float) (0.6 * Math.sin(step * i));
        for (int i = (int) (5.0 * SR); i < (int) (5.5 * SR); i++) s[i] = 0.0f;   // 0.5 s break

        LevelerProcessor p = new LevelerProcessor();
        p.setEnabled(true);
        p.setLeveling(1.0);
        p.setSpeed(1.0);                        // even at max speed
        p.prepare(SR, n);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, n);

        float atBreak = env[(int) (5.25 * SR)];
        float around = env[(int) (2.0 * SR)];
        assertTrue(Math.abs(atBreak - around) < 0.1f,
                "short break should not be regulated (break=" + atBreak + ", around=" + around + ")");
    }
}
