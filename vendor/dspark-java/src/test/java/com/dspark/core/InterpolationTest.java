package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleUnaryOperator;

import static com.dspark.core.Interpolation.interpolateAllpass;
import static com.dspark.core.Interpolation.interpolateCubic;
import static com.dspark.core.Interpolation.interpolateHermite;
import static com.dspark.core.Interpolation.interpolateLagrange;
import static com.dspark.core.Interpolation.interpolateLinear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Interpolation}. */
class InterpolationTest
{
    @Test
    @DisplayName("Linear: midpoints and exact samples")
    void linearMidpointAndExact()
    {
        final double[] buf = { 0.0, 1.0, 2.0, 3.0 };
        assertEquals(0.5, interpolateLinear(buf, 4, 0.5), 1e-5);
        assertEquals(1.5, interpolateLinear(buf, 4, 1.5), 1e-5);
        final double[] tens = { 10.0, 20.0, 30.0, 40.0 };
        assertEquals(30.0, interpolateLinear(tens, 4, 2.0), 1e-5);
    }

    @Test
    @DisplayName("Hermite returns the samples at integer positions and stays bounded")
    void hermiteAtIntegers()
    {
        final double[] buf = { 0.0, 0.3, 0.7, 1.0, 0.8, 0.4, 0.1, 0.0 };
        for (int i = 1; i < 6; ++i)
            assertEquals(buf[i], interpolateHermite(buf, 8, i), 1e-5);
        for (double pos = 1.0; pos < 6.0; pos += 0.25)
        {
            final double val = interpolateHermite(buf, 8, pos);
            assertFalse(Double.isNaN(val));
            assertTrue(val >= -0.5 && val <= 1.5);
        }
    }

    @Test
    @DisplayName("Linear, Hermite and Lagrange reconstruct polynomials of degree 1, 2 and 3")
    void polynomialReproductionOrders()
    {
        final DoubleUnaryOperator p1 = x -> 0.7 * x - 0.3;
        final DoubleUnaryOperator p2 = x -> 0.4 * x * x - 0.9 * x + 0.2;
        final DoubleUnaryOperator p3 = x -> 0.3 * x * x * x - 0.5 * x * x + 0.2 * x + 0.1;

        double maxLin = 0.0, maxHer = 0.0, maxLag = 0.0;
        for (int k = 1; k <= 4; ++k)
            for (int s = 0; s <= 16; ++s)
            {
                final double frac = s / 16.0;
                final double x = k + frac;
                maxLin = Math.max(maxLin, Math.abs(interpolateLinear(p1.applyAsDouble(k), p1.applyAsDouble(k + 1.0), frac)
                                                   - p1.applyAsDouble(x)));
                maxHer = Math.max(maxHer, Math.abs(interpolateHermite(p2.applyAsDouble(k - 1.0), p2.applyAsDouble(k),
                                                   p2.applyAsDouble(k + 1.0), p2.applyAsDouble(k + 2.0), frac)
                                                   - p2.applyAsDouble(x)));
                maxLag = Math.max(maxLag, Math.abs(interpolateLagrange(p3.applyAsDouble(k - 1.0), p3.applyAsDouble(k),
                                                   p3.applyAsDouble(k + 1.0), p3.applyAsDouble(k + 2.0), frac)
                                                   - p3.applyAsDouble(x)));
            }
        assertTrue(maxLin < 1e-12);
        assertTrue(maxHer < 1e-12);
        assertTrue(maxLag < 1e-12);
        assertEquals(p3.applyAsDouble(2.375),
                     interpolateLagrange(p3.applyAsDouble(1.0), p3.applyAsDouble(2.0),
                                         p3.applyAsDouble(3.0), p3.applyAsDouble(4.0), 0.375), 1e-5);
    }

    @Test
    @DisplayName("Each order is exact for every polynomial of its degree and not one degree above")
    void exactOnPolynomialsOfTheirOrder()
    {
        final java.util.Random rng = new java.util.Random(3);
        for (int trial = 0; trial < 50; ++trial)
        {
            final double c0 = rng.nextGaussian(), c1 = rng.nextGaussian(), c2 = rng.nextGaussian(), c3 = rng.nextGaussian();
            final double x0 = 10.0 * rng.nextGaussian();
            final DoubleUnaryOperator deg1 = x -> c0 + c1 * (x - x0);
            final DoubleUnaryOperator deg2 = x -> c0 + (x - x0) * (c1 + c2 * (x - x0));
            final DoubleUnaryOperator deg3 = x -> c0 + (x - x0) * (c1 + (x - x0) * (c2 + c3 * (x - x0)));
            final double base = Math.floor(x0) + rng.nextInt(5) - 2;
            final double frac = rng.nextDouble();
            final double x = base + frac;
            final double scale = 1.0 + Math.abs(c0) + Math.abs(c1) + Math.abs(c2) + Math.abs(c3);
            assertEquals(deg1.applyAsDouble(x),
                         interpolateLinear(deg1.applyAsDouble(base), deg1.applyAsDouble(base + 1), frac), 1e-13 * scale);
            assertEquals(deg2.applyAsDouble(x),
                         interpolateHermite(deg2.applyAsDouble(base - 1), deg2.applyAsDouble(base),
                                            deg2.applyAsDouble(base + 1), deg2.applyAsDouble(base + 2), frac), 1e-12 * scale);
            assertEquals(deg3.applyAsDouble(x),
                         interpolateLagrange(deg3.applyAsDouble(base - 1), deg3.applyAsDouble(base),
                                             deg3.applyAsDouble(base + 1), deg3.applyAsDouble(base + 2), frac), 1e-12 * scale);
        }
        // One degree above: a quadratic through the linear reader, a cubic
        // through Hermite, a quartic through Lagrange all leave an error.
        final double f = 0.25;
        assertTrue(Math.abs(interpolateLinear(0.0, 1.0, f) - f * f) > 0.05);
        assertTrue(Math.abs(interpolateHermite(-1.0, 0.0, 1.0, 8.0, f) - f * f * f) > 0.05);
        assertTrue(Math.abs(interpolateLagrange(1.0, 0.0, 1.0, 16.0, f) - f * f * f * f) > 0.05);
    }

    @Test
    @DisplayName("Hermite is C1 across segments")
    void hermiteIsC1()
    {
        final double[] y = { 0.12, -0.68, 0.91, 0.33, -0.55, 0.77, -0.21 };
        for (int k = 1; k <= 3; ++k)
        {
            final double endK = interpolateHermite(y[k - 1], y[k], y[k + 1], y[k + 2], 1.0);
            final double startK1 = interpolateHermite(y[k], y[k + 1], y[k + 2], y[k + 3], 0.0);
            assertEquals(y[k + 1], endK, 1e-12);
            assertEquals(y[k + 1], startK1, 1e-12);
            final double h = 1e-6;
            final double left = interpolateHermite(y[k - 1], y[k], y[k + 1], y[k + 2], 1.0 - h);
            final double right = interpolateHermite(y[k], y[k + 1], y[k + 2], y[k + 3], h);
            final double tangent = (y[k + 2] - y[k]) * 0.5;
            assertEquals(tangent, (endK - left) / h, 1e-4);
            assertEquals(tangent, (right - startK1) / h, 1e-4);
        }
    }

    @Test
    @DisplayName("Buffer overloads wrap circularly, bit-exact with the raw-sample overloads")
    void bufferOverloadsWrap()
    {
        final double[] buf = { 0.31, -0.72, 0.55, 0.18, -0.93, 0.42, -0.11, 0.66 };
        final int n = 8;
        assertEquals(interpolateHermite(buf[7], buf[0], buf[1], buf[2], 0.25), interpolateHermite(buf, n, 0.25), 0.0);
        assertEquals(interpolateLagrange(buf[7], buf[0], buf[1], buf[2], 0.25), interpolateLagrange(buf, n, 0.25), 0.0);
        assertEquals(interpolateHermite(buf[6], buf[7], buf[0], buf[1], 0.5), interpolateHermite(buf, n, 7.5), 0.0);
        assertEquals(interpolateLagrange(buf[6], buf[7], buf[0], buf[1], 0.5), interpolateLagrange(buf, n, 7.5), 0.0);
        assertEquals(interpolateLinear(buf[7], buf[0], 0.5), interpolateLinear(buf, n, 7.5), 0.0);
        assertEquals(interpolateHermite(buf[5], buf[6], buf[7], buf[0], 0.5), interpolateHermite(buf, n, 6.5), 0.0);
        assertEquals(interpolateHermite(buf, n, 3.7), interpolateCubic(buf, n, 3.7), 0.0);
    }

    @Test
    @DisplayName("Out-of-range positions are clamped; NaN reads position 0; empty buffers read 0")
    void outOfRangePositions()
    {
        final double[] buf = { 0.31, -0.72, 0.55, 0.18 };
        assertEquals(buf[0], interpolateLinear(buf, 4, Double.NaN), 0.0);
        assertEquals(buf[0], interpolateLinear(buf, 4, -3.0), 0.0);
        assertEquals(buf[3], interpolateLinear(buf, 4, 9.5), 0.0);
        assertEquals(interpolateHermite(buf[3], buf[0], buf[1], buf[2], 0.0), interpolateHermite(buf, 4, Double.NaN), 0.0);
        assertEquals(interpolateLagrange(buf[2], buf[3], buf[0], buf[1], 0.0), interpolateLagrange(buf, 4, 4.0), 0.0);
        assertEquals(0.0, interpolateHermite(buf, 0, 0.5), 0.0);
        assertEquals(0.0, interpolateLinear(buf, -1, 0.5), 0.0);
    }

    @Test
    @DisplayName("Sinc reader: exact at integers and transparent to 18 kHz at 48 kHz")
    void sincExactAndTransparent()
    {
        final Interpolation.SincInterpolator sinc = new Interpolation.SincInterpolator();
        final double[] ring = new double[256];
        for (int i = 0; i < ring.length; ++i)
            ring[i] = Math.sin(0.37 * i) + 0.2 * Math.cos(2.9 * i);
        for (long p = 0; p < 256; p += 7)   // wraps both ends of the ring
            assertEquals(ring[(int) p], sinc.readRing(ring, 255, p, 0.0), 0.0);

        for (double hz : new double[] { 1000.0, 10000.0, 15000.0, 18000.0 })
        {
            final double w = 6.283185307179586 * hz / 48000.0;
            for (int i = 0; i < ring.length; ++i) ring[i] = Math.sin(w * i);
            double worst = 0.0;
            for (int step = 1; step < 64; ++step)
            {
                final double frac = step / 64.0;
                worst = Math.max(worst, Math.abs(sinc.readRing(ring, 255, 100, frac) - Math.sin(w * (100.0 + frac))));
            }
            assertTrue(20.0 * Math.log10(worst) < -90.0, hz + " Hz: " + 20.0 * Math.log10(worst));
        }
    }

    /**
     * Worst error (dB) of the sinc reader against the ideal fractional delay
     * of a complex tone, over the positions k / (256 * sub) for k in
     * [0, 256 * sub): sub = 1 visits the tabulated phases, larger values
     * also the positions between them.
     */
    private static double sincWorstDb(Interpolation.SincInterpolator sinc, double hz, double rate, int sub)
    {
        final double w = 2.0 * Math.PI * hz / rate;
        final double[] cr = new double[512], sr = new double[512];
        for (int i = 0; i < 512; ++i)
        {
            cr[i] = Math.cos(w * i);
            sr[i] = Math.sin(w * i);
        }
        double worst = 0.0;
        for (int k = 0; k < 256 * sub; ++k)
        {
            final double frac = k / (256.0 * sub);
            final double re = sinc.readRing(cr, 511, 200, frac) - Math.cos(w * (200.0 + frac));
            final double im = sinc.readRing(sr, 511, 200, frac) - Math.sin(w * (200.0 + frac));
            worst = Math.max(worst, Math.hypot(re, im));
        }
        return 20.0 * Math.log10(worst);
    }

    @Test
    @DisplayName("Sinc reader: documented error figures")
    void sincDocumentedFigures()
    {
        final Interpolation.SincInterpolator sinc = new Interpolation.SincInterpolator();
        // Below -96 dB up to 15 kHz at 44.1 and 48 kHz and to 18 kHz at
        // 48 kHz, at the tabulated phases.
        assertTrue(sincWorstDb(sinc, 15000.0, 44100.0, 1) < -96.0);
        assertTrue(sincWorstDb(sinc, 15000.0, 48000.0, 1) < -96.0);
        assertTrue(sincWorstDb(sinc, 18000.0, 48000.0, 1) < -96.0);
        // Between the phases the linear phase interpolation adds its error.
        assertTrue(sincWorstDb(sinc, 15000.0, 44100.0, 4) < -90.0);
        assertTrue(sincWorstDb(sinc, 15000.0, 48000.0, 4) < -96.0);
        assertTrue(sincWorstDb(sinc, 18000.0, 48000.0, 4) < -96.0);
        // Near Nyquist: -50 dB at 48 kHz and -17 dB at 44.1 kHz at 20 kHz.
        assertEquals(-50.0, sincWorstDb(sinc, 20000.0, 48000.0, 4), 3.0);
        assertEquals(-17.0, sincWorstDb(sinc, 20000.0, 44100.0, 4), 3.0);
        assertEquals(15, Interpolation.SincInterpolator.BEFORE);
        assertEquals(16, Interpolation.SincInterpolator.AFTER);
    }

    @Test
    @DisplayName("Sinc reader: the gathered ring read equals the contiguous read")
    void sincGatherEqualsContiguous()
    {
        final Interpolation.SincInterpolator sinc = new Interpolation.SincInterpolator();
        final double[] ring = new double[64];
        for (int i = 0; i < ring.length; ++i) ring[i] = Math.sin(0.41 * i) * Math.cos(0.07 * i * i);
        final double[] window = new double[Interpolation.SincInterpolator.TAPS];
        for (long pos = -3; pos < 80; ++pos)
            for (double frac : new double[] { 0.0, 0.13, 0.5, 0.99 })
            {
                final long first = pos - Interpolation.SincInterpolator.BEFORE;
                for (int t = 0; t < window.length; ++t) window[t] = ring[(int) ((first + t) & 63)];
                assertEquals(sinc.read(window, 0, frac), sinc.readRing(ring, 63, pos, frac), 0.0);
            }
        // Unit DC gain at every phase.
        final double[] ones = new double[Interpolation.SincInterpolator.TAPS];
        java.util.Arrays.fill(ones, 1.0);
        for (int step = 0; step < 300; ++step)
            assertEquals(1.0, sinc.read(ones, 0, step / 300.0), 1e-14);
    }

    @Test
    @DisplayName("Stretched sinc: keeps the band and blocks what would fold")
    void stretchedSincKeepsTheBand()
    {
        final Interpolation.StretchedSincReader reader = new Interpolation.StretchedSincReader();
        assertEquals(7, Interpolation.StretchedSincReader.stepFor(2.0));
        assertEquals(8, Interpolation.StretchedSincReader.stepFor(2.001));
        assertEquals(0, Interpolation.StretchedSincReader.stepFor(0.5));
        assertEquals(Interpolation.StretchedSincReader.STEPS - 1, Interpolation.StretchedSincReader.stepFor(9.0));

        final int step = Interpolation.StretchedSincReader.stepFor(2.0);
        final double[] ring = new double[512];
        for (boolean passband : new boolean[] { true, false })
        {
            final double hz = passband ? 6000.0 : 16000.0;
            final double w = 6.283185307179586 * hz / 48000.0;
            for (int i = 0; i < ring.length; ++i) ring[i] = Math.sin(w * i);
            double worst = 0.0;
            for (int k = 0; k < 64; ++k)
            {
                final double frac = k / 64.0;
                final double got = reader.readRing(ring, 511, 250, frac, step);
                final double want = passband ? Math.sin(w * (250.0 + frac)) : 0.0;
                worst = Math.max(worst, Math.abs(got - want));
            }
            final double db = 20.0 * Math.log10(worst);
            assertTrue(db < (passband ? -85.0 : -90.0), hz + " Hz: " + db);
        }
    }

    /** Level (dB) through the rate-2 kernel of a tone at ratio times the output Nyquist. */
    private static double stretchedLevel(Interpolation.StretchedSincReader rd, double[] ring, int mask, int step, double ratio)
    {
        final double f = ratio * 0.25;   // cycles per input sample; output Nyquist is 0.25 at rate 2
        for (int i = 0; i <= mask; ++i) ring[i] = Math.sin(2.0 * Math.PI * f * i);
        double p = 0.0;
        int n = 0;
        for (int i = 1000; i < 30000; i += 2)
        {
            final double v = rd.readRing(ring, mask, i, 0.25, step);
            p += v * v;
            ++n;
        }
        return 10.0 * Math.log10(2.0 * p / n + 1e-30);
    }

    @Test
    @DisplayName("Stretched sinc: no fold above the output Nyquist, documented band figures")
    void stretchedSincDoesNotFold()
    {
        final Interpolation.StretchedSincReader rd = new Interpolation.StretchedSincReader();
        final int step = Interpolation.StretchedSincReader.stepFor(2.0);
        final int mask = (1 << 16) - 1;
        final double[] ring = new double[1 << 16];
        assertTrue(stretchedLevel(rd, ring, mask, step, 1.05) < -90.0);
        assertTrue(stretchedLevel(rd, ring, mask, step, 1.0) < -40.0);
        assertEquals(0.0, stretchedLevel(rd, ring, mask, step, 0.5), 0.1);
        assertEquals(18, rd.reach(0));   // ceil(16 * 2^(1/8))
        assertEquals(64, rd.reach(Interpolation.StretchedSincReader.STEPS - 1));

        // Kernel magnitude from a complex tone, at every phase: flat to 0.7
        // of the output Nyquist, -2 dB at 0.8, about -46 dB at the output
        // Nyquist and below -100 dB from 1.05x upward.
        final double[] cr = new double[1024], sr = new double[1024];
        for (double ratio : new double[] { 0.7, 0.8, 1.0, 1.05, 1.2, 1.5 })
        {
            final double w = 2.0 * Math.PI * ratio * 0.25;
            for (int i = 0; i < 1024; ++i)
            {
                cr[i] = Math.cos(w * i);
                sr[i] = Math.sin(w * i);
            }
            for (int k = 0; k < 128; ++k)
            {
                final double frac = k / 128.0;
                final double db = 20.0 * Math.log10(Math.hypot(rd.readRing(cr, 1023, 500, frac, step),
                                                               rd.readRing(sr, 1023, 500, frac, step)));
                if (ratio == 0.7) assertEquals(0.0, db, 0.1);
                else if (ratio == 0.8) assertEquals(-2.0, db, 0.25);
                else if (ratio == 1.0) assertEquals(-46.0, db, 1.0);
                else assertTrue(db < -100.0, ratio + ": " + db);
            }
        }
    }

    @Test
    @DisplayName("Allpass: frac 1 is a pure delay, unity magnitude, phase delay equals frac, stable at the smallest frac")
    void allpassDelayMagnitudeAndStability()
    {
        {
            final double[] state = { 0.0 };
            double prev = 0.87;
            int rng = 12345;
            for (int i = 0; i < 64; ++i)
            {
                rng = rng * 1664525 + 1013904223;
                final double cur = (0.25 + 0.75 * (double) (rng >>> 8) / (double) (1 << 24)) * ((rng & 1) != 0 ? 1.0 : -1.0);
                final double out = interpolateAllpass(cur, prev, 1.0, state);
                assertEquals(prev, out, 0.0);
                prev = cur;
            }
        }
        {
            final double fs = 48000.0, f = 1000.0;
            final double w = 2.0 * Math.PI * f / fs;
            final double[] state = { 0.0 };
            double prev = 0.0, sumIn = 0.0, sumOut = 0.0;
            for (int i = 0; i < 25000; ++i)
            {
                final double cur = Math.sin(w * i);
                final double out = interpolateAllpass(cur, prev, 0.5, state);
                if (i >= 1000) { sumIn += cur * cur; sumOut += out * out; }
                prev = cur;
            }
            assertEquals(1.0, Math.sqrt(sumOut / sumIn), 1e-3);
        }
        {
            final double fs = 48000.0, f = 100.0;
            final double w = 2.0 * Math.PI * f / fs;
            final double[] state = { 0.0 };
            double prev = 0.0, reIn = 0.0, imIn = 0.0, reOut = 0.0, imOut = 0.0;
            final int settle = 4800, span = 24000;
            for (int i = 0; i < settle + span; ++i)
            {
                final double cur = Math.sin(w * i);
                final double out = interpolateAllpass(cur, prev, 0.3, state);
                if (i >= settle)
                {
                    final double c = Math.cos(w * i), s = Math.sin(w * i);
                    reIn += cur * c;  imIn += cur * s;
                    reOut += out * c; imOut += out * s;
                }
                prev = cur;
            }
            double dphi = Math.atan2(reIn, imIn) - Math.atan2(reOut, imOut);
            while (dphi > Math.PI) dphi -= 2.0 * Math.PI;
            while (dphi < -Math.PI) dphi += 2.0 * Math.PI;
            assertEquals(0.3, dphi / w, 0.05);
        }
        {
            final double[] state = { 0.0 };
            double prev = 0.0, maxAbs = 0.0, lastAbs = 0.0;
            for (int i = 0; i < 30000; ++i)
            {
                final double cur = (i == 0) ? 1.0 : 0.0;
                final double out = interpolateAllpass(cur, prev, 0.001, state);
                assertFalse(Double.isNaN(out));
                maxAbs = Math.max(maxAbs, Math.abs(out));
                lastAbs = Math.abs(out);
                prev = cur;
            }
            assertTrue(maxAbs < 2.0);
            assertTrue(lastAbs < 1e-3);
        }
        // Per-tap state indexing and the zero clamp of frac.
        final double[] states = { 0.0, 0.0 };
        final double[] single = { 0.0 };
        for (int i = 0; i < 20; ++i)
        {
            final double x = Math.sin(0.3 * i), xp = Math.sin(0.3 * (i - 1));
            assertEquals(interpolateAllpass(x, xp, 0.001, single), interpolateAllpass(x, xp, -5.0, states, 1), 0.0);
        }
        assertEquals(0.0, states[0], 0.0);
    }
}
