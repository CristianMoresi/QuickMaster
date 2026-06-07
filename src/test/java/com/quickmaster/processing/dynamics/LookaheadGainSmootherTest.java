package com.quickmaster.processing.dynamics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LookaheadGainSmoother}. */
class LookaheadGainSmootherTest
{
    @Test
    @DisplayName("Forward sliding minimum looks ahead over the window")
    void forwardSlidingMin()
    {
        float[] a = { 1f, 1f, 0.2f, 1f, 1f, 1f };
        float[] m = LookaheadGainSmoother.forwardSlidingMin(a, 2);   // min over [i, i+2]
        // i=0 -> min(1,1,0.2)=0.2 ; i=1 -> min(1,0.2,1)=0.2 ; i=2 -> 0.2 ;
        // i=3 -> min(1,1,1)=1 ; i=4 -> 1 ; i=5 -> 1
        assertEquals(0.2f, m[0], 1e-6f);
        assertEquals(0.2f, m[1], 1e-6f);
        assertEquals(0.2f, m[2], 1e-6f);
        assertEquals(1.0f, m[3], 1e-6f);
        assertEquals(1.0f, m[5], 1e-6f);
    }

    @Test
    @DisplayName("Reaches the target on a dip, then releases over ~release time")
    void attackReachesAndReleases()
    {
        int sr = 48000;
        float[] target = new float[sr];           // 1 s, unity except one deep dip
        java.util.Arrays.fill(target, 1.0f);
        int dip = sr / 2;                          // 0.5 s
        target[dip] = 0.5f;

        float[] out = LookaheadGainSmoother.smooth(target, sr, 2.0, 0.5, 100.0);

        // Instant (look-ahead) attack: the envelope reaches the 0.5 target.
        float min = 1.0f;
        for (float v : out) min = Math.min(min, v);
        assertTrue(min <= 0.55f && min >= 0.45f, "min should reach ~0.5, was " + min);

        // Pre-emptive: the dip starts a couple ms before the input dip.
        assertTrue(out[dip - 48] < 0.99f, "gain should start dropping before the peak");

        // ~100 ms release: 100 ms after the dip the gain has recovered part-way,
        // not all the way (1 - 1/e ~= 0.63 of the way back to unity).
        float at100ms = out[dip + sr / 10];
        assertTrue(at100ms > 0.6f && at100ms < 0.95f, "release too fast/slow: " + at100ms);

        // Fully recovered well after (end of the 1 s buffer, ~0.5 s past the dip).
        assertTrue(out[out.length - 1] > 0.98f, "should recover to unity");

        // Never exceeds unity.
        for (float v : out) assertTrue(v <= 1.0001f);
    }
}
