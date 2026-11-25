package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DspMath}. */
class DspMathTest
{
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("0 dB is unity gain")
    void zeroDecibelsIsUnity()
    {
        assertEquals(1.0, DspMath.decibelsToGain(0.0), EPS);
    }

    @Test
    @DisplayName("±6 dB is approximately ×2 / ×0.5")
    void sixDecibelsDoublesAmplitude()
    {
        assertEquals(2.0, DspMath.decibelsToGain(6.0206), 1e-3);
        assertEquals(0.5, DspMath.decibelsToGain(-6.0206), 1e-3);
    }

    @Test
    @DisplayName("dB <-> gain round-trips")
    void decibelGainRoundTrip()
    {
        for (double db : new double[] { -24, -12, -6, -1, 0, 3, 12 })
        {
            assertEquals(db, DspMath.gainToDecibels(DspMath.decibelsToGain(db)), 1e-9);
        }
    }

    @Test
    @DisplayName("Non-positive gain maps to the dB floor")
    void nonPositiveGainIsFloor()
    {
        assertEquals(DspMath.MINUS_INFINITY_DB, DspMath.gainToDecibels(0.0), 0.0);
        assertEquals(DspMath.MINUS_INFINITY_DB, DspMath.gainToDecibels(-1.0), 0.0);
    }

    @Test
    @DisplayName("clamp and clampSample bound their inputs")
    void clampingWorks()
    {
        assertEquals(5.0, DspMath.clamp(10.0, 0.0, 5.0), 0.0);
        assertEquals(0.0, DspMath.clamp(-3.0, 0.0, 5.0), 0.0);
        assertEquals(1.0f, DspMath.clampSample(2.0f), 0.0f);
        assertEquals(-1.0f, DspMath.clampSample(-2.0f), 0.0f);
        assertTrue(Math.abs(DspMath.clampSample(0.5f) - 0.5f) < 1e-7);
    }

    @Test
    @DisplayName("fastLog stays within its series truncation of the exact logarithm over sixty decades")
    void fastLogAccuracy()
    {
        double worst = 0.0;
        for (double x = 1e-30; x < 1e30; x *= 1.0137)
            worst = Math.max(worst, Math.abs(DspMath.fastLog(x) - Math.log(x)));
        assertTrue(worst < 3.0e-8, "worst error " + worst);
        // The mantissa of a power of two folds onto 1: only the exponent is left.
        assertEquals(0.0, DspMath.fastLog(1.0), 0.0);
        assertEquals(10.0 * Math.log(2.0), DspMath.fastLog(1024.0), 0.0);
        assertEquals(-3.0 * Math.log(2.0), DspMath.fastLog(0.125), 0.0);
    }

    @Test
    @DisplayName("fastLog reads subnormal values and hands what has no logarithm to Math.log")
    void fastLogEdges()
    {
        assertEquals(Math.log(1e-310), DspMath.fastLog(1e-310), 3.0e-8);
        assertEquals(Math.log(Double.MIN_VALUE), DspMath.fastLog(Double.MIN_VALUE), 3.0e-8);
        assertEquals(Double.NEGATIVE_INFINITY, DspMath.fastLog(0.0), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, DspMath.fastLog(Double.POSITIVE_INFINITY), 0.0);
        assertTrue(Double.isNaN(DspMath.fastLog(-1.0)));
        assertTrue(Double.isNaN(DspMath.fastLog(Double.NaN)));
    }
}
