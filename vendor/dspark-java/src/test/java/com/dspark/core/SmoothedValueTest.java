package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SmoothedValue}. */
class SmoothedValueTest
{
    @Test
    @DisplayName("Exponential smoothing converges toward the target")
    void exponentialConverges()
    {
        SmoothedValue sv = new SmoothedValue();
        sv.prepare(48000, 5.0);
        sv.reset(0.0);
        sv.setTargetValue(1.0);

        double prev = sv.getNextValue();
        for (int i = 0; i < 48000; i++)
        {
            double v = sv.getNextValue();
            assertTrue(v >= prev - 1e-9, "should be monotonic increasing");
            prev = v;
        }
        assertEquals(1.0, sv.getCurrentValue(), 1e-3);
    }

    @Test
    @DisplayName("Disabled smoothing snaps instantly")
    void disabledSnaps()
    {
        SmoothedValue sv = new SmoothedValue();
        sv.prepare(48000, 50.0);
        sv.setSmoothingType(SmoothedValue.Type.DISABLED);
        sv.reset(0.0);
        sv.setTargetValue(0.75);
        assertEquals(0.75, sv.getNextValue(), 0.0);
    }

    @Test
    @DisplayName("skip() jumps straight to the target")
    void skipJumps()
    {
        SmoothedValue sv = new SmoothedValue();
        sv.prepare(48000, 50.0);
        sv.reset(0.0);
        sv.setTargetValue(0.5);
        sv.skip();
        assertEquals(0.5, sv.getCurrentValue(), 0.0);
        assertTrue(!sv.isSmoothing());
    }
}
