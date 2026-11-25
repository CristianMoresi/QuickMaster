package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link FirDesign}. */
class FirDesignTest
{
    private static double magnitudeDb(double[] taps, double normFreq)
    {
        double re = 0.0, im = 0.0;
        for (int i = 0; i < taps.length; i++)
        {
            double a = -2.0 * Math.PI * normFreq * i;
            re += taps[i] * Math.cos(a);
            im += taps[i] * Math.sin(a);
        }
        return 20.0 * Math.log10(Math.hypot(re, im) + 1e-300);
    }

    @Test
    @DisplayName("The low-pass is symmetric with unity gain at DC")
    void symmetricUnityDc()
    {
        double[] taps = FirDesign.lowPass(48000.0, 6000.0, 101, FirDesign.estimateKaiserBeta(80.0));
        assertEquals(101, taps.length);
        double sum = 0.0;
        for (int i = 0; i < taps.length; i++)
        {
            sum += taps[i];
            assertEquals(taps[i], taps[taps.length - 1 - i], 1e-15, "tap " + i);
        }
        assertEquals(1.0, sum, 1e-12);
    }

    @Test
    @DisplayName("An even tap count is raised to the next odd one")
    void evenCountIsRaised()
    {
        assertEquals(65, FirDesign.lowPass(1.0, 0.1, 64, 6.0).length);
        assertEquals(3, FirDesign.lowPass(1.0, 0.1, 1, 6.0).length);
    }

    @Test
    @DisplayName("The rate changer's design is flat in its passband and 100 dB down from the slow Nyquist")
    void rateChangerSpecification()
    {
        // The design of the detail filter's decimator by 8: pass edge 0.35 of
        // the slow rate, stop from half of it.
        final int factor = 8;
        final double depth = 100.0, passEdge = 0.35;
        double transition = (0.5 - passEdge) / factor;
        int half = (int) Math.ceil(0.5 * (depth - 7.95) / (14.36 * transition));
        double[] taps = FirDesign.lowPass(1.0, 0.5 * (0.5 + passEdge) / factor, 2 * half + 1, FirDesign.estimateKaiserBeta(depth));

        double ripple = 0.0;
        for (double f = 0.0; f <= passEdge / factor; f += 0.0005) ripple = Math.max(ripple, Math.abs(magnitudeDb(taps, f)));
        assertTrue(ripple < 0.001, "passband ripple " + ripple + " dB");

        double leak = -400.0;
        for (double f = 0.5 / factor; f <= 0.5; f += 0.0005) leak = Math.max(leak, magnitudeDb(taps, f));
        assertTrue(leak < -97.0, "stopband " + leak + " dB");
    }

    @Test
    @DisplayName("Kaiser's beta follows his three ranges")
    void kaiserBeta()
    {
        assertEquals(0.0, FirDesign.estimateKaiserBeta(10.0), 0.0);
        assertEquals(0.0, FirDesign.estimateKaiserBeta(20.9), 0.0);
        assertEquals(0.5842 * Math.pow(19.0, 0.4) + 0.07886 * 19.0, FirDesign.estimateKaiserBeta(40.0), 1e-12);
        assertEquals(0.1102 * 91.3, FirDesign.estimateKaiserBeta(100.0), 1e-12);
    }

    @Test
    @DisplayName("The modified Bessel function reads its tabulated values")
    void besselValues()
    {
        // The series stops at a part in 1e12 of the sum.
        assertEquals(1.0, FirDesign.besselI0(0.0), 0.0);
        assertEquals(1.2660658777520084, FirDesign.besselI0(1.0), 1e-12);
        assertEquals(27.239871823604442, FirDesign.besselI0(5.0), 27.3e-12);
        assertEquals(2815.716628466254, FirDesign.besselI0(10.0), 2816e-12);
    }
}
