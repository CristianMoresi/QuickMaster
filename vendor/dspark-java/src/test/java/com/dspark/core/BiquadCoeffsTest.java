package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Magnitude-response tests for {@link BiquadCoeffs} factories. */
class BiquadCoeffsTest
{
    private static final double SR = 48000.0;

    @Test
    @DisplayName("Peak filter reaches its dB gain at the centre frequency")
    void peakGainAtCentre()
    {
        BiquadCoeffs peak = BiquadCoeffs.peak(SR, 1000.0, 1.0, 6.0);
        double linear = peak.magnitude(1000.0, SR);
        assertEquals(Math.pow(10.0, 6.0 / 20.0), linear, 1e-3);
    }

    @Test
    @DisplayName("Matched peaking (Orfanidis) hits its gain at fc and de-cramps near Nyquist")
    void peakMatchedShape()
    {
        // At low frequency it must match the cookbook bell.
        BiquadCoeffs matched = BiquadCoeffs.peakMatched(SR, 500.0, 1.0, 6.0);
        assertEquals(Math.pow(10.0, 6.0 / 20.0), matched.magnitude(500.0, SR), 0.02);

        // Near Nyquist the cookbook bell is pinned to ~unity at fs/2 while the
        // matched bell holds the analog prototype's gain there.
        double fc = 16000.0;
        BiquadCoeffs cook = BiquadCoeffs.peak(SR, fc, 1.0, 6.0);
        BiquadCoeffs match = BiquadCoeffs.peakMatched(SR, fc, 1.0, 6.0);
        assertEquals(Math.pow(10.0, 6.0 / 20.0), match.magnitude(fc, SR), 0.05);
        double nyq = SR / 2.0 - 1.0;
        assertTrue(match.magnitude(nyq, SR) > cook.magnitude(nyq, SR) + 0.05,
                "matched bell must keep gain near Nyquist where the cookbook cramps");

        // Tiny gains fall back to identity.
        BiquadCoeffs id = BiquadCoeffs.peakMatched(SR, 1000.0, 1.0, 0.0);
        assertEquals(1.0, id.magnitude(1000.0, SR), 1e-9);
    }

    @Test
    @DisplayName("A NaN frequency yields finite (pinned) coefficients, not poison")
    void nanFrequencyIsSanitized()
    {
        BiquadCoeffs c = BiquadCoeffs.peak(SR, Double.NaN, 1.0, 6.0);
        assertTrue(Double.isFinite(c.b0) && Double.isFinite(c.a1) && Double.isFinite(c.a2),
                "coefficients must stay finite for a NaN frequency");
    }

    @Test
    @DisplayName("Low-pass is ~unity at DC and attenuates above the cutoff")
    void lowPassShape()
    {
        BiquadCoeffs lp = BiquadCoeffs.lowPass(SR, 1000.0, BiquadCoeffs.BUTTERWORTH_Q);
        assertEquals(1.0, lp.magnitude(20.0, SR), 0.02);          // ~unity at DC
        assertTrue(lp.magnitude(10000.0, SR) < 0.1);              // strong HF cut
        // At the cutoff a Butterworth low-pass is ~-3 dB (0.707).
        assertEquals(0.7071, lp.magnitude(1000.0, SR), 0.02);
    }

    @Test
    @DisplayName("High-shelf approaches its dB gain well above the corner")
    void highShelfGain()
    {
        BiquadCoeffs hs = BiquadCoeffs.highShelf(SR, 4000.0, 6.0, 1.0);
        assertEquals(Math.pow(10.0, 6.0 / 20.0), hs.magnitude(20000.0, SR), 0.05);
        assertEquals(1.0, hs.magnitude(20.0, SR), 0.05);          // unity far below
    }

    @Test
    @DisplayName("Tilt brightens highs and darkens lows for positive gain")
    void tiltPivots()
    {
        BiquadCoeffs tilt = BiquadCoeffs.tilt(SR, 1000.0, 6.0);
        double low = tilt.magnitude(50.0, SR);
        double high = tilt.magnitude(18000.0, SR);
        assertTrue(low < 1.0, "low end should be cut, was " + low);
        assertTrue(high > 1.0, "high end should be boosted, was " + high);
    }

    @Test
    @DisplayName("Identity coefficients are unity at every frequency")
    void identityIsFlat()
    {
        for (double f : new double[] { 50, 500, 5000, 20000 })
        {
            assertEquals(1.0, BiquadCoeffs.IDENTITY.magnitude(f, SR), 1e-12);
        }
    }
}
