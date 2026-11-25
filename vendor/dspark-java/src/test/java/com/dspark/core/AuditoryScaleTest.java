package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AuditoryScale}. */
class AuditoryScaleTest
{
    @Test
    @DisplayName("The Bark scale reads Traunmuller's published values")
    void barkValues()
    {
        assertEquals(26.81 * 1000.0 / 2960.0 - 0.53, AuditoryScale.hzToBark(1000.0), 1e-12);
        // Under 2 Bark and over 20.1 Bark his corrections apply.
        double low = 26.81 * 100.0 / 2060.0 - 0.53;
        assertEquals(low + 0.15 * (2.0 - low), AuditoryScale.hzToBark(100.0), 1e-12);
        double high = 26.81 * 12000.0 / 13960.0 - 0.53;
        assertEquals(high + 0.22 * (high - 20.1), AuditoryScale.hzToBark(12000.0), 1e-12);
    }

    @Test
    @DisplayName("The Bark scale rises with frequency and its inverse undoes it")
    void barkRoundTrip()
    {
        double before = Double.NEGATIVE_INFINITY;
        for (double hz = 20.0; hz <= 20000.0; hz *= 1.01)
        {
            double z = AuditoryScale.hzToBark(hz);
            assertTrue(z > before, "not rising at " + hz);
            before = z;
            assertEquals(hz, AuditoryScale.barkToHz(z), 1e-9 * hz, "round trip at " + hz);
        }
    }

    @Test
    @DisplayName("The critical bandwidth is 100 Hz in the low end and widens with frequency")
    void criticalBandwidth()
    {
        assertEquals(100.0, AuditoryScale.criticalBandwidth(0.0), 1e-12);
        assertEquals(25.0 + 75.0 * Math.pow(2.4, 0.69), AuditoryScale.criticalBandwidth(1000.0), 1e-12);
        assertTrue(AuditoryScale.criticalBandwidth(10000.0) > 2000.0);
        assertTrue(AuditoryScale.criticalBandwidth(100.0) < 105.0);
    }

    @Test
    @DisplayName("The threshold in quiet follows Terhardt: high in the low end, lowest near 3.3 kHz")
    void thresholdInQuiet()
    {
        assertEquals(3.64 - 6.5 * Math.exp(-0.6 * 2.3 * 2.3) + 1e-3, AuditoryScale.thresholdInQuietDb(1000.0), 1e-12);
        assertTrue(AuditoryScale.thresholdInQuietDb(50.0) > 35.0);
        double lowest = Double.POSITIVE_INFINITY, at = 0.0;
        for (double hz = 100.0; hz <= 16000.0; hz *= 1.005)
        {
            double t = AuditoryScale.thresholdInQuietDb(hz);
            if (t < lowest)
            {
                lowest = t;
                at = hz;
            }
        }
        assertTrue(at > 3000.0 && at < 3700.0, "lowest threshold at " + at);
        assertTrue(lowest < -4.0 && lowest > -6.0, "lowest threshold " + lowest);
        assertEquals(120.0, AuditoryScale.thresholdInQuietDb(24000.0), 0.0, "capped");
    }

    @Test
    @DisplayName("The loudness exponent is 0.23 where the ear is most sensitive and 0.30 where it is least")
    void loudnessExponent()
    {
        assertEquals(0.23, AuditoryScale.loudnessExponent(3300.0), 1e-3);
        assertEquals(0.30, AuditoryScale.loudnessExponent(30.0), 1e-12);
        for (double hz = 20.0; hz <= 20000.0; hz *= 1.1)
        {
            double a = AuditoryScale.loudnessExponent(hz);
            assertTrue(a >= 0.23 && a <= 0.30 + 1e-12, "exponent " + a + " at " + hz);
        }
    }

    @Test
    @DisplayName("The dominant power is the larger eigenvalue of the stereo covariance")
    void dominantPower()
    {
        // One source in both channels alike: all of its power.
        assertEquals(2.0, AuditoryScale.dominantPower(1.0, 1.0, 1.0), 1e-12);
        // Two sources with nothing in common: the louder one.
        assertEquals(3.0, AuditoryScale.dominantPower(3.0, 1.0, 0.0), 1e-12);
        // One channel alone.
        assertEquals(0.7, AuditoryScale.dominantPower(0.7, 0.0, 0.0), 1e-12);
        // Out of phase is still one source.
        assertEquals(2.0, AuditoryScale.dominantPower(1.0, 1.0, -1.0), 1e-12);
        // A source panned: gains g and h give g^2 + h^2 of its power.
        double g = 0.8, h = 0.3;
        assertEquals(g * g + h * h, AuditoryScale.dominantPower(g * g, h * h, g * h), 1e-12);
        // One channel the other delayed by a quarter of a period: one source.
        assertEquals(2.0, AuditoryScale.dominantPower(1.0, 1.0, 0.0, 1.0), 1e-12);
        assertEquals(0.0, AuditoryScale.dominantPower(0.0, 0.0, 0.0), 0.0);
        // What is not a number reads as nothing.
        assertEquals(0.0, AuditoryScale.dominantPower(Double.NaN, 1.0, 0.0), 0.0);
        assertEquals(0.0, AuditoryScale.dominantPower(1.0, 1.0, Double.NaN, 0.0), 0.0);
    }
}
