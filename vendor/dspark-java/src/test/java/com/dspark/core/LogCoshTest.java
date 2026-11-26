package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LogCosh}. */
class LogCoshTest
{
    /** log(cosh x) without cancellation: log1p(cosh x - 1) with cosh x - 1 = 2 sinh^2(x/2). */
    private static double referenceLogCosh(double x)
    {
        final double s = Math.sinh(0.5 * x);
        return Math.log1p(2.0 * s * s);
    }

    /** Integral of log(cosh t) from 0 to x by composite Gauss-Legendre quadrature. */
    private static double referenceIntegral(double x)
    {
        final int pieces = 64;
        final double h = x / pieces;
        double sum = 0.0;
        for (int k = 0; k < pieces; ++k)
            sum += GaussLegendre.integrate(LogCoshTest::referenceLogCosh, k * h, (k + 1) * h);
        return sum;
    }

    @Test
    @DisplayName("logCosh matches a cancellation-free reference on both branches")
    void logCoshMatchesReference()
    {
        // Absolute error near 1e-16 on the large-argument branch, relative
        // accuracy on the series branch.
        for (int i = -4000; i <= 4000; ++i)
        {
            final double x = i * 0.005 + 1e-4 * Math.sin(i);
            final double ref = referenceLogCosh(x);
            final double err = Math.abs(LogCosh.logCosh(x) - ref);
            assertTrue(err <= 5e-16 + 4e-16 * Math.abs(ref), "x = " + x + " err = " + err);
        }
        // Small-argument series: relative accuracy is kept near zero.
        for (double x : new double[] { 1e-8, -3e-5, 0.004, -0.0099 })
            assertEquals(referenceLogCosh(x), LogCosh.logCosh(x), 1e-15 * referenceLogCosh(x));
        assertEquals(0.0, LogCosh.logCosh(0.0), 0.0);
        // Even function.
        for (double x : new double[] { 0.003, 0.7, 4.0, 30.0 })
            assertEquals(LogCosh.logCosh(x), LogCosh.logCosh(-x), 0.0);
    }

    @Test
    @DisplayName("integralLogCosh is the integral of logCosh, odd, and continuous at |x| = 1")
    void integralMatchesQuadrature()
    {
        for (double x : new double[] { 0.001, 0.25, -0.6, 0.999, 1.0, 1.001, -1.7, 2.5, 4.0, -8.0, 15.0 })
        {
            final double ref = referenceIntegral(x);
            assertEquals(ref, LogCosh.integralLogCosh(x), 2e-15 * Math.max(1.0, Math.abs(ref)), "x = " + x);
            assertEquals(-LogCosh.integralLogCosh(x), LogCosh.integralLogCosh(-x), 0.0);
        }
        final double below = LogCosh.integralLogCosh(Math.nextDown(1.0));
        final double above = LogCosh.integralLogCosh(1.0);
        assertEquals(above, below, 1e-15);
        assertEquals(0.0, LogCosh.integralLogCosh(0.0), 0.0);
    }

    @Test
    @DisplayName("integralLogCoshTail is the exponentially small remainder, bounded by 0.07")
    void tailIsTheRemainder()
    {
        final double pi2over24 = Math.PI * Math.PI / 24.0;
        for (double a = 1.0; a <= 12.0; a += 0.25)
        {
            final double tail = LogCosh.integralLogCoshTail(a);
            assertTrue(tail <= 0.0 && tail > -0.07, "a = " + a);
            final double remainder = referenceIntegral(a) - (0.5 * a * a - a * Math.log(2.0) + pi2over24);
            assertEquals(remainder, tail, 3e-15 * Math.max(1.0, a * a), "a = " + a);
            // 0.5 Li2(-z) = -z/2 + z^2/8 - z^3/18 + ... for z = e^{-2a}
            final double z = Math.exp(-2.0 * a);
            assertEquals(-0.5 * z + 0.125 * z * z, tail, z * z * z / 18.0 + 4e-16 * z);
        }
    }
}
