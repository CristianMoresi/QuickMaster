package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Adaa}. */
class AdaaTest
{
    private static final double HALF_PI = Math.PI * 0.5;
    private static final double KNEE = 0.61803398874989484820;
    private static final double RANGE = 1.0 - KNEE;

    /** The reduced-range sine polynomial of fastSin() (DspMath.h). */
    private static double fastSinPoly(double v)
    {
        final double v2 = v * v;
        return v * (0.99999997408724855 + v2 * (-0.16666646026660671 + v2 * (0.0083328727116396326
                 + v2 * (-0.00019799239565814083 + v2 * 2.5871610835732768e-6))));
    }

    /** A curve under test: the kernel, the plain curve and its kinks. */
    private static final class Case
    {
        final String name;
        final Adaa.Curve curve;
        final DoubleUnaryOperator plain;
        final double[] kinks;

        Case(String name, Adaa.Curve curve, DoubleUnaryOperator plain, double[] kinks)
        {
            this.name = name;
            this.curve = curve;
            this.plain = plain;
            this.kinks = kinks;
        }
    }

    private static final Case[] CASES = {
        new Case("hard clip", Adaa.AdaaHardClip.INSTANCE, u -> Math.max(-1.0, Math.min(1.0, u)), new double[] { -1.0, 1.0 }),
        new Case("tanh", Adaa.AdaaTanh.INSTANCE, Math::tanh, new double[0]),
        new Case("sine polynomial", Adaa.AdaaSinePolynomial.INSTANCE,
                 u -> fastSinPoly(Math.max(-HALF_PI, Math.min(HALF_PI, u))), new double[] { -HALF_PI, HALF_PI }),
        new Case("rational knee", Adaa.AdaaRationalKnee.INSTANCE,
                 u -> Math.abs(u) <= KNEE ? u : Math.signum(u) * (1.0 - RANGE * RANGE / (Math.abs(u) - KNEE + RANGE)),
                 new double[] { -KNEE, KNEE }),
        new Case("sine", Adaa.AdaaSine.INSTANCE, Math::sin, new double[0]),
    };

    /**
     * Average of f over the linear B-spline with knots at the three inputs,
     * by quadrature split at the middle knot and at the kinks of f.
     */
    private static double splineAverage(DoubleUnaryOperator f, double[] kinks, double x0, double x1, double x2)
    {
        double[] k = { x0, x1, x2 };
        java.util.Arrays.sort(k);
        final double lo = k[0], mid = k[1], hi = k[2], span = hi - lo;
        if (!(span > 0.0)) return f.applyAsDouble(lo);
        // Each piece is integrated in its distance s from the outer knot, so
        // the linear weight stays exact for knots much closer than their size.
        double sum = 0.0;
        if (mid > lo)
        {
            final double w = mid - lo, c = 2.0 / (span * w);
            final double[] at = new double[kinks.length];
            for (int i = 0; i < kinks.length; ++i) at[i] = kinks[i] - lo;
            sum += GaussLegendre.integrate(s -> f.applyAsDouble(lo + s) * c * s, 0.0, w, at, 4);
        }
        if (hi > mid)
        {
            final double w = hi - mid, c = 2.0 / (span * w);
            final double[] at = new double[kinks.length];
            for (int i = 0; i < kinks.length; ++i) at[i] = hi - kinks[i];
            sum += GaussLegendre.integrate(s -> f.applyAsDouble(hi - s) * c * s, 0.0, w, at, 4);
        }
        return sum;
    }

    @Test
    @DisplayName("Every kernel equals the B-spline average of its curve over random knots")
    void kernelsEqualTheSplineAverage()
    {
        final Random rng = new Random(0x5EED);
        for (Case c : CASES)
        {
            double worst = 0.0;
            for (int trial = 0; trial < 4000; ++trial)
            {
                final double centre = -6.0 + 12.0 * rng.nextDouble();
                final double scale = Math.pow(10.0, -7.0 + 7.3 * rng.nextDouble());
                final double x2 = centre + scale * (rng.nextDouble() - 0.5);
                final double x1 = centre + scale * (rng.nextDouble() - 0.5);
                final double x0 = centre + scale * (rng.nextDouble() - 0.5);
                final Adaa.Adaa2 state = new Adaa.Adaa2();
                state.reset(x2);
                state.process(c.curve, x1);
                final double y = state.process(c.curve, x0);
                final double ref = splineAverage(c.plain, c.kinks, x0, x1, x2);
                assertTrue(Double.isFinite(y), c.name);
                worst = Math.max(worst, Math.abs(y - ref));
            }
            assertTrue(worst < 1e-7, c.name + ": worst error " + worst);
        }
    }

    @Test
    @DisplayName("The hard clip is exact when a kink is crossed in tiny steps")
    void hardClipExactAcrossTheKink()
    {
        final Case c = CASES[0];
        double worst = 0.0;
        for (int i = 0; i < 2000; ++i)
        {
            final double step = 1e-9 * (1 + i % 7);
            final double x1 = 1.0 + (i - 1000) * 1e-10;
            final double x0 = x1 + step, x2 = x1 - step;
            final Adaa.Adaa2 state = new Adaa.Adaa2();
            state.reset(x2);
            state.process(c.curve, x1);
            final double y = state.process(c.curve, x0);
            worst = Math.max(worst, Math.abs(y - splineAverage(c.plain, c.kinks, x0, x1, x2)));
        }
        assertTrue(worst < 1e-15, "worst error " + worst);
    }

    @Test
    @DisplayName("Equal consecutive samples give finite outputs at the plain curve")
    void equalSamplesAreFinite()
    {
        final double[] values = { 0.0, 0.3, -0.7, 1.0, -1.0, KNEE, -KNEE, HALF_PI, -HALF_PI, 2.0, -2.0,
                                  5.0, -19.5, 25.0, 1e3, -1e6, 1e150 };
        final double[] offsets = { 0.0, 1e-12, 1e-6, 5e-5, 2e-4, 1e-3, 0.1 };
        for (Case c : CASES)
            for (double u : values)
            {
                // All three equal: the output is the curve itself.
                final Adaa.Adaa2 still = new Adaa.Adaa2();
                still.reset(u);
                for (int i = 0; i < 4; ++i)
                {
                    final double y = still.process(c.curve, u);
                    assertTrue(Double.isFinite(y), c.name + " at " + u);
                    // The coincident form evaluates the curve at the mean of
                    // the three inputs, which may round one ulp off u.
                    assertEquals(c.plain.applyAsDouble(u), y, 1e-12 + 4.0 * Math.ulp(u), c.name + " at " + u);
                }
                if (Math.abs(u) > 1e9) continue;
                for (double d : offsets)
                {
                    final double v = u + d;
                    final double[][] patterns = { { u, u, v }, { u, v, v }, { u, v, u }, { v, u, u } };
                    for (double[] p : patterns)
                    {
                        final Adaa.Adaa2 state = new Adaa.Adaa2();
                        state.reset(p[2]);
                        state.process(c.curve, p[1]);
                        final double y = state.process(c.curve, p[0]);
                        assertTrue(Double.isFinite(y), c.name + " at " + u + " + " + d);
                        // A knee knot exactly at +-KNEE carries the inner point
                        // values (no logarithm) into the outer exact form, so it
                        // is only checked for finiteness (see kneeKnotOnTheKnee).
                        if (c.curve == Adaa.AdaaRationalKnee.INSTANCE && Math.abs(u) == KNEE && d != 0.0) continue;
                        // At large |u| the rounding of the knot midpoints (one
                        // ulp of u, over a spacing of d) bounds the accuracy.
                        final double conditioning = 8.0 * Math.ulp(u) / Math.max(d, 1e-4);
                        assertEquals(splineAverage(c.plain, c.kinks, p[0], p[1], p[2]), y,
                                     1e-7 + conditioning, c.name + " at " + u + " + " + d);
                    }
                }
            }
    }

    @Test
    @DisplayName("Rational knee: point values at the knee and the exact form just outside it")
    void kneeKnotOnTheKnee()
    {
        final Case c = CASES[3];
        final Adaa.AdaaPoint p = new Adaa.AdaaPoint();
        // |u| <= KNEE takes the inner branch: no logarithm is stored.
        Adaa.AdaaRationalKnee.INSTANCE.point(KNEE, p);
        assertEquals(0.0, p.aux, 0.0);
        final double above = Math.nextUp(KNEE);
        Adaa.AdaaRationalKnee.INSTANCE.point(above, p);
        assertEquals(Math.log(above - KNEE + RANGE), p.aux, 0.0);
        // One ulp outside the knee the outer exact form holds.
        for (double d : new double[] { 1e-6, 2e-4, 1e-3, 0.1 })
            for (double s : new double[] { 1.0, -1.0 })
            {
                final double u = s * above, v = s * (above + d);
                final Adaa.Adaa2 state = new Adaa.Adaa2();
                state.reset(v);
                state.process(c.curve, u);
                final double y = state.process(c.curve, u);
                assertEquals(splineAverage(c.plain, c.kinks, u, u, v), y, 1e-7, "d = " + d);
            }
    }

    /**
     * Largest |y[n] - f(u[n-1])| for a slow sine of amplitude amp, over 16
     * sub-sample phases (so the kinks are met at every grid position).
     */
    private static double slowError(Case c, double amp, double cyclesPerSample)
    {
        final int n = (int) Math.ceil(1.0 / cyclesPerSample) + 2;
        double worst = 0.0;
        for (int k = 0; k < 16; ++k)
        {
            final Adaa.Adaa2 state = new Adaa.Adaa2();
            final double shift = k / 16.0;
            double prev = amp * Math.sin(2.0 * Math.PI * cyclesPerSample * (shift - 2.0)) + 0.11;
            state.reset(prev);
            prev = amp * Math.sin(2.0 * Math.PI * cyclesPerSample * (shift - 1.0)) + 0.11;
            state.track(prev);
            for (int i = 0; i < n; ++i)
            {
                final double u = amp * Math.sin(2.0 * Math.PI * cyclesPerSample * (i + shift)) + 0.11;
                final double y = state.process(c.curve, u);
                worst = Math.max(worst, Math.abs(y - c.plain.applyAsDouble(prev)));
                prev = u;
            }
        }
        return worst;
    }

    @Test
    @DisplayName("Slow signals converge to the plain curve, centred one sample back")
    void slowSignalsConverge()
    {
        final double amp = 3.0, f = 20.0 / 48000.0;
        final double dx = amp * 2.0 * Math.PI * f;   // largest step between samples
        for (Case c : CASES)
        {
            final double e1 = slowError(c, amp, f);
            final double e2 = slowError(c, amp, f / 2.0);
            if (c.curve == Adaa.AdaaHardClip.INSTANCE)
            {
                // A slope discontinuity: the error is first order in the step.
                assertTrue(e1 < dx / 4.0, c.name + ": " + e1);
                assertTrue(e2 < 0.6 * e1, c.name + ": " + e1 + " -> " + e2);
            }
            else
            {
                // Smooth slope: second order, |f''| var / 2 with var = dx^2 / 6.
                assertTrue(e1 < 6.0 * dx * dx / 12.0, c.name + ": " + e1);
                assertTrue(e2 < 0.35 * e1, c.name + ": " + e1 + " -> " + e2);
            }
        }
    }

    @Test
    @DisplayName("A linear signal is delayed by one sample and weighted (1 + 2 cos w) / 3")
    void linearResponse()
    {
        // Inside the linear region of the hard clip the kernel is the mean of
        // the three inputs.
        for (double w : new double[] { 0.1, 0.6545, Math.PI / 4.0, Math.PI / 2.0 })
        {
            final Adaa.Adaa2 state = new Adaa.Adaa2();
            state.reset(0.0);
            double re = 0.0, im = 0.0;
            final int n = 20000, settle = 10;
            for (int i = 0; i < n + settle; ++i)
            {
                final double y = state.process(Adaa.AdaaHardClip.INSTANCE, 0.5 * Math.cos(w * i));
                if (i >= settle)
                {
                    re += y * Math.cos(w * (i - 1));
                    im += y * Math.sin(w * (i - 1));
                }
            }
            final double gain = 2.0 * Math.hypot(re, im) / (0.5 * n);
            assertEquals((1.0 + 2.0 * Math.cos(w)) / 3.0, gain, 2e-3, "w = " + w);
            // Phase centred on the previous sample: no residual phase.
            assertTrue(Math.abs(im) < 1e-3 * Math.abs(re) + 1e-6, "w = " + w);
        }
        // A ramp inside the linear region comes out as the previous input.
        final Adaa.Adaa2 state = new Adaa.Adaa2();
        state.reset(-0.52);
        state.track(-0.51);
        double prev = -0.51;
        for (int i = 0; i < 100; ++i)
        {
            final double u = -0.5 + 0.01 * i;
            assertEquals(prev, state.process(Adaa.AdaaHardClip.INSTANCE, u), 1e-15);
            assertEquals(u, state.previousInput(), 0.0);
            prev = u;
        }
    }

    @Test
    @DisplayName("reset, track and invalidate rebuild the cached points from the raw history")
    void historyHandling()
    {
        final Random rng = new Random(77);
        final double[] x = new double[600];
        for (int i = 0; i < x.length; ++i) x[i] = 4.0 * (rng.nextDouble() - 0.5);

        // Switching curves after invalidate() equals a stream that only
        // tracked the same history.
        final Adaa.Adaa2 a = new Adaa.Adaa2(), b = new Adaa.Adaa2();
        for (int i = 0; i < 300; ++i)
        {
            a.process(Adaa.AdaaTanh.INSTANCE, x[i]);
            b.track(x[i]);
        }
        a.invalidate();
        for (int i = 300; i < 600; ++i)
            assertEquals(b.process(Adaa.AdaaRationalKnee.INSTANCE, x[i]),
                         a.process(Adaa.AdaaRationalKnee.INSTANCE, x[i]), 0.0);

        // reset() equals a fresh instance; reset(u) holds u as the history.
        final Adaa.Adaa2 used = new Adaa.Adaa2(), fresh = new Adaa.Adaa2();
        for (int i = 0; i < 100; ++i) used.process(Adaa.AdaaSine.INSTANCE, x[i]);
        used.reset();
        for (int i = 0; i < 200; ++i)
            assertEquals(fresh.process(Adaa.AdaaSine.INSTANCE, x[i]), used.process(Adaa.AdaaSine.INSTANCE, x[i]), 0.0);
        final Adaa.Adaa2 held = new Adaa.Adaa2(), manual = new Adaa.Adaa2();
        held.reset(0.8);
        manual.track(0.8);
        manual.track(0.8);
        for (int i = 0; i < 50; ++i)
            assertEquals(manual.process(Adaa.AdaaTanh.INSTANCE, x[i]), held.process(Adaa.AdaaTanh.INSTANCE, x[i]), 0.0);

        // copyFrom continues identically.
        final Adaa.Adaa2 copy = new Adaa.Adaa2();
        copy.copyFrom(held);
        for (int i = 50; i < 150; ++i)
            assertEquals(held.process(Adaa.AdaaTanh.INSTANCE, x[i]), copy.process(Adaa.AdaaTanh.INSTANCE, x[i]), 0.0);
    }

    @Test
    @DisplayName("The families have the stated derivatives")
    void familyDerivatives()
    {
        for (Case c : CASES)
            for (double u : new double[] { -3.3, -1.2, -0.4, 0.05, 0.5, 0.9, 1.4, 2.7, 6.0 })
            {
                final double h = 1e-5;
                final double dg = (c.curve.g(u + h) - c.curve.g(u - h)) / (2.0 * h);
                final double dd1 = (c.curve.d1(u + h) - c.curve.d1(u - h)) / (2.0 * h);
                assertEquals(c.curve.d1(u), dg, 1e-8 * Math.max(1.0, Math.abs(c.curve.g(u))), c.name + " g' at " + u);
                assertEquals(c.curve.d2(u), dd1, 1e-8 * Math.max(1.0, Math.abs(c.curve.d1(u))), c.name + " d1' at " + u);
                assertEquals(c.plain.applyAsDouble(u), c.curve.d2(u), 1e-15, c.name + " d2 at " + u);
                final double dd2 = (c.curve.d2(u + h) - c.curve.d2(u - h)) / (2.0 * h);
                assertEquals(c.curve.d3(u), dd2, 1e-6, c.name + " d2' at " + u);
                final Adaa.AdaaPoint p = new Adaa.AdaaPoint();
                c.curve.point(u, p);
                assertEquals(u, p.u, 0.0);
                assertEquals(c.curve.g(u), p.g, 1e-14 * Math.max(1.0, Math.abs(p.g)), c.name + " point at " + u);
            }
        // The tanh remainder: F2 minus its outer quadratic.
        final Adaa.Family r = Adaa.AdaaTanh.Remainder.INSTANCE;
        for (double u : new double[] { 2.0, 2.5, -3.0, 7.0, 18.0, 25.0 })
        {
            final double a = Math.abs(u);
            final double quadratic = Math.signum(u) * (0.5 * a * a - a * Math.log(2.0) + Math.PI * Math.PI / 24.0);
            assertEquals(LogCosh.integralLogCosh(u) - quadratic, r.g(u), 1e-14 * a * a, "R at " + u);
            assertEquals(Math.tanh(u) - Math.signum(u), r.d2(u), 2e-16, "R'' at " + u);
            assertEquals(LogCosh.integralLogCoshTail(a), Adaa.AdaaTanh.tail(a), a > 19.0 ? 1.6e-17 : 1e-18, "tail at " + a);
        }
        assertEquals(0.0, Adaa.AdaaTanh.tail(19.5), 0.0);
        assertEquals(1.0, Adaa.AdaaSinePolynomial.EDGE, 3e-8);
    }
}
