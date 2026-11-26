package com.dspark.effects;

import com.dspark.core.wdf.ToneStackFMV;
import com.dspark.effects.TubePreampDetail.CoreDesign;
import com.dspark.effects.TubePreampDetail.CurrentTable;
import com.dspark.effects.TubePreampDetail.GridClamp;
import com.dspark.effects.TubePreampDetail.GridTable;
import com.dspark.effects.TubePreampDetail.ToneModes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The circuit helpers of {@link TubePreamp}: the tabulated plate current
 * against an independent load-line solve, the phi functions, the modal tone
 * circuit against the analog state space, the Farrow reconstruction and the
 * output projection, and the grid conduction table.
 */
class TubePreampDetailTest
{
    // -- Independent references (no production derivatives or interpolation) -----

    static double referenceTriodeCurrent(double vp, double vg)
    {
        vp = Math.max(vp, 0.0);
        final double z = 600.0 * (0.01 + vg / Math.sqrt(300.0 + vp * vp));
        final double e = vp / 600.0 * Math.log1p(Math.exp(z));
        return 2.0 / 1060.0 * Math.pow(e, 1.4);
    }

    /** Kirchhoff's equation bisected to its unique root. */
    static double referenceTriodeLoad(double supply, double grid, double cathodeR)
    {
        double lo = 0.0, hi = supply / (100000.0 + cathodeR);
        for (int i = 0; i < 65; ++i)
        {
            final double mid = 0.5 * (lo + hi);
            final double cathode = cathodeR * mid;
            if (mid > referenceTriodeCurrent(supply - 100000.0 * mid - cathode, grid - cathode))
                hi = mid;
            else
                lo = mid;
        }
        return 0.5 * (lo + hi);
    }

    @Test
    @DisplayName("TubePreamp_current_surface_matches_implicit_circuit")
    void currentSurfaceMatchesImplicitCircuit()
    {
        // The most curved cells are at low supply near cutoff. A nearest or
        // bilinear lookup fails this tolerance; it also protects the capacitor
        // feedback term, which changes with the internal sample rate.
        for (double rate : new double[] { 8000.0, 44100.0, 96000.0, 384000.0, 3072000.0 })
        {
            final CurrentTable table = new CurrentTable(rate);
            final double h = 0.5 / (rate * 22e-6);
            final double cathodeR = h / (1.0 + h / 1500.0);
            int seed = 51;
            for (int n = 0; n < 6000; ++n)
            {
                seed = seed * 1664525 + 1013904223;
                final double supply = 80.0 + 224.0 * ((double) Integer.toUnsignedLong(seed) / 4294967296.0);
                seed = seed * 1664525 + 1013904223;
                final double grid = -16.0 + 17.0 * ((double) Integer.toUnsignedLong(seed) / 4294967296.0);
                final double measured = table.eval(supply, grid);
                final double expected = referenceTriodeLoad(supply, grid, cathodeR);
                assertEquals(expected, measured, 5e-10);   // < 50 uV at the plate load
                assertTrue(measured >= 0.0);
            }
            // Rounded coordinates at the final grid edge.
            for (double supply : new double[] { 80.0, 192.0, Math.nextDown(304.0) })
                for (double grid : new double[] { -16.0, -1.03125, Math.nextDown(1.0) })
                {
                    assertTrue(table.covers(supply, grid));
                    assertEquals(referenceTriodeLoad(supply, grid, cathodeR), table.eval(supply, grid), 5e-10);
                }
            // Deep cutoff is bounded by the unloaded Koren current.
            for (double supply : new double[] { 80.0, 128.0, 192.0, 303.999 })
            {
                assertTrue(referenceTriodeCurrent(supply, -0.08 * supply) < 1e-27);
                assertTrue(table.covers(supply, -1000.0));
                assertEquals(0.0, table.eval(supply, -1000.0), 1e-30);
                for (double ratio : new double[] { -0.04, -0.025 })
                {
                    final double grid = supply * ratio;
                    for (double offset : new double[] { -1e-8, 0.0, 1e-8 })
                        assertEquals(referenceTriodeLoad(supply, grid + offset, cathodeR),
                                     table.eval(supply, grid + offset), 5e-10);
                }
            }
            assertFalse(table.covers(304.0, 0.0));
            assertFalse(table.covers(300.0, Double.NaN));
            assertFalse(table.covers(300.0, Double.NEGATIVE_INFINITY));
        }
    }

    @Test
    @DisplayName("Current slice equals the full surface to rounding")
    void sliceMatchesSurface()
    {
        // The slice evaluates the same bicubic in the Hermite basis with a
        // reciprocal supply; the surface in Horner form with a division.
        final CurrentTable table = new CurrentTable(Double.POSITIVE_INFINITY);
        final CurrentTable.Slice slice = new CurrentTable.Slice();
        int seed = 7;
        for (int n = 0; n < 4000; ++n)
        {
            seed = seed * 1664525 + 1013904223;
            final double supply = 80.0 + 224.0 * ((double) Integer.toUnsignedLong(seed) / 4294967296.0);
            seed = seed * 1664525 + 1013904223;
            final double grid = -30.0 + 30.9 * ((double) Integer.toUnsignedLong(seed) / 4294967296.0);
            slice.set(table, supply);
            assertTrue(slice.inside);
            final double full = table.eval(supply, grid);
            assertEquals(full, slice.eval(grid), 1e-18 + 1e-12 * full);
        }
    }

    @Test
    @DisplayName("TubePreamp_phi_functions_match_their_definition")
    void phiFunctionsMatchTheirDefinition()
    {
        // phi_k(w) = sum_j w^j / (j + k)! weights the exact forced response of
        // each tone-circuit mode. Independent evaluation: a 60-term series for
        // |w| < 1, otherwise (e^w - sum_{j<k} w^j / j!) / w^k.
        final double[] points = { -2000.0, -1042.0, -40.0, -7.5, -1.0, -0.5000001, -0.4999999, -0.3,
            -0.1, -0.02, -1e-3, -1e-9, 0.0, 1e-9, 1e-3, 0.02, 0.1, 0.3, 0.4999999, 0.5000001, 1.0, 3.0 };
        final double[] p = new double[5];
        for (double w : points)
        {
            TubePreampDetail.phi(w, p);
            for (int k = 0; k <= 4; ++k)
            {
                double expected = 0.0;
                if (Math.abs(w) < 1.0)
                {
                    double term = 1.0;
                    for (int j = 1; j <= k; ++j) term /= j;
                    for (int j = 0; j < 60; ++j)
                    {
                        expected += term;
                        term *= w / (j + k + 1);
                    }
                }
                else
                {
                    double partial = 0.0, term = 1.0;
                    for (int j = 0; j < k; ++j)
                    {
                        partial += term;
                        term *= w / (j + 1);
                    }
                    expected = (Math.exp(w) - partial) / Math.pow(w, k);
                }
                assertEquals(expected, p[k], 1e-12 * Math.abs(expected), "w = " + w + ", k = " + k);
            }
        }
    }

    /** c (sI - a)^-1 b + d by complex elimination with partial pivoting; returns {re, im}. */
    static double[] stackResponse(ToneStackFMV.AnalogStateSpace ss, double sIm)
    {
        final double[][] mr = new double[3][4], mi = new double[3][4];
        for (int i = 0; i < 3; ++i)
        {
            for (int j = 0; j < 3; ++j)
            {
                mr[i][j] = -ss.a[i][j];
                mi[i][j] = i == j ? sIm : 0.0;
            }
            mr[i][3] = ss.b[i];
            mi[i][3] = 0.0;
        }
        for (int k = 0; k < 3; ++k)
        {
            int pivot = k;
            for (int i = k + 1; i < 3; ++i)
                if (Math.hypot(mr[i][k], mi[i][k]) > Math.hypot(mr[pivot][k], mi[pivot][k])) pivot = i;
            double[] t = mr[pivot]; mr[pivot] = mr[k]; mr[k] = t;
            t = mi[pivot]; mi[pivot] = mi[k]; mi[k] = t;
            for (int i = k + 1; i < 3; ++i)
            {
                final double den = mr[k][k] * mr[k][k] + mi[k][k] * mi[k][k];
                final double fr = (mr[i][k] * mr[k][k] + mi[i][k] * mi[k][k]) / den;
                final double fi = (mi[i][k] * mr[k][k] - mr[i][k] * mi[k][k]) / den;
                for (int j = k; j < 4; ++j)
                {
                    final double re = fr * mr[k][j] - fi * mi[k][j];
                    final double im = fr * mi[k][j] + fi * mr[k][j];
                    mr[i][j] -= re;
                    mi[i][j] -= im;
                }
            }
        }
        final double[] xr = new double[3], xi = new double[3];
        for (int i = 2; i >= 0; --i)
        {
            double vr = mr[i][3], vi = mi[i][3];
            for (int k = i + 1; k < 3; ++k)
            {
                vr -= mr[i][k] * xr[k] - mi[i][k] * xi[k];
                vi -= mr[i][k] * xi[k] + mi[i][k] * xr[k];
            }
            final double den = mr[i][i] * mr[i][i] + mi[i][i] * mi[i][i];
            xr[i] = (vr * mr[i][i] + vi * mi[i][i]) / den;
            xi[i] = (vi * mr[i][i] - vr * mi[i][i]) / den;
        }
        return new double[] { ss.c[0] * xr[0] + ss.c[1] * xr[1] + ss.c[2] * xr[2] + ss.d,
                              ss.c[0] * xi[0] + ss.c[1] * xi[1] + ss.c[2] * xi[2] };
    }

    @Test
    @DisplayName("TubePreamp_tone_modes_reproduce_the_analog_state_space")
    void toneModesReproduceTheAnalogStateSpace()
    {
        // The modal form must keep the transfer function of the analog state
        // space over the whole control cube, including bass 0 / middle 1,
        // where one mode spans about a thousand time constants per 96 kHz
        // interval.
        final double rate = 96000.0;
        double stiffest = 0.0;
        for (double t : new double[] { 0.0, 0.5, 1.0 })
            for (double b : new double[] { 0.0, 0.5, 1.0 })
                for (double m : new double[] { 0.0, 0.5, 1.0 })
                {
                    final ToneStackFMV stack = new ToneStackFMV(38e3, 1e6);
                    stack.prepare(rate);
                    stack.setControls(t, b, m);
                    final ToneStackFMV.AnalogStateSpace ss = stack.analogStateSpace();
                    final ToneModes modes = new ToneModes();
                    modes.design(ss, rate);
                    for (int k = 0; k < 3; ++k)
                    {
                        assertTrue(modes.lambda[k] < 0.0);
                        stiffest = Math.min(stiffest, modes.lambda[k]);
                    }
                    for (int i = 0; i < 3; ++i)
                        for (int j = 0; j < 3; ++j)
                        {
                            double product = 0.0;
                            for (int k = 0; k < 3; ++k) product += modes.toModal[i][k] * modes.toPhysical[k][j];
                            assertEquals(i == j ? 1.0 : 0.0, product, 1e-12);
                        }
                    for (double f : new double[] { 20.0, 200.0, 2000.0, 20000.0, 45000.0 })
                    {
                        final double w = 2.0 * Math.PI * f;
                        double mr = modes.direct, mi = 0.0;
                        for (int k = 0; k < 3; ++k)
                        {
                            // gamma beta rate / (j w - lambda rate)
                            final double num = modes.gamma[k] * modes.beta[k] * rate;
                            final double dr = -modes.lambda[k] * rate, di = w;
                            final double den = dr * dr + di * di;
                            mr += num * dr / den;
                            mi += -num * di / den;
                        }
                        final double[] expected = stackResponse(ss, w);
                        final double error = Math.hypot(mr - expected[0], mi - expected[1]);
                        assertTrue(error / Math.hypot(expected[0], expected[1]) < 1e-9,
                                   "t " + t + " b " + b + " m " + m + " f " + f);
                    }
                }
        assertTrue(stiffest < -500.0);
    }

    @Test
    @DisplayName("TubePreamp_core_reconstruction_and_projection_are_accurate")
    void coreReconstructionAndProjectionAreAccurate()
    {
        // Input reconstruction: the Farrow polynomial of each internal interval
        // reproduces every complex tone up to 22 kHz at 48 kHz (scaled with the
        // factor) at all interval positions. Output projection: the order-6
        // B-spline pieces form a partition of unity, and the linear-phase FIR
        // inverts their sinc^6 droop over the same band.
        final double[] bounds = { -80.0, -100.0, -104.0, -100.0 };
        int index = 0;
        for (int factor : new int[] { 2, 4, 8, 16 })
        {
            final CoreDesign design = new CoreDesign(factor);
            final int taps = design.taps, degree = design.degree;
            final double band = 22000.0 / (48000.0 * factor);
            double worst = 0.0;
            for (int iw = 0; iw <= 200; ++iw)
            {
                final double w = 2.0 * Math.PI * band * iw / 200.0;
                for (int it = 0; it <= 100; ++it)
                {
                    final double tau = it / 100.0;
                    double vr = 0.0, vi = 0.0;
                    for (int j = 0; j < taps; ++j)
                    {
                        final int row = j * (degree + 1);
                        double c = design.farrow[row + degree];
                        for (int p = degree - 1; p >= 0; --p) c = c * tau + design.farrow[row + p];
                        final double phase = w * (j - taps / 2 + 1);
                        vr += c * Math.cos(phase);
                        vi += c * Math.sin(phase);
                    }
                    worst = Math.max(worst, Math.hypot(vr - Math.cos(w * tau), vi - Math.sin(w * tau)));
                }
            }
            assertTrue(20.0 * Math.log10(worst) < bounds[index], "factor " + factor + ": " + 20.0 * Math.log10(worst));

            for (int i = 0; i <= 100; ++i)
            {
                final double d = -0.5 + i / 100.0;
                double sum = 0.0;
                for (double[] piece : design.kernel)
                {
                    double v = 0.0;
                    for (int p = CoreDesign.KERNEL_ORDER - 1; p >= 0; --p)
                        v = v * d + piece[p];
                    assertTrue(v > -1e-15);
                    sum += v;
                }
                assertEquals(1.0, sum, 1e-13);
            }

            final double[] c = design.compensation;
            int first = 0;
            while (first < c.length && c[first] == 0.0) ++first;
            final int half = (c.length - 1 - first) / 2, centre = first + half;
            for (int q = 1; q <= half; ++q) assertEquals(c[centre - q], c[centre + q], 0.0);
            for (int i = 0; i <= 400; ++i)
            {
                final double f = band * i / 400.0;
                double response = 0.0;
                for (int n = 0; n < c.length; ++n)
                    response += c[n] * Math.cos(2.0 * Math.PI * f * ((double) n - (double) centre));
                final double sinc = f == 0.0 ? 1.0 : Math.sin(Math.PI * f) / (Math.PI * f);
                assertTrue(Math.abs(20.0 * Math.log10(response * Math.pow(sinc, 6))) < 1e-4);
            }
            ++index;
        }
    }

    @Test
    @DisplayName("Core design latencies")
    void coreDesignLatencies()
    {
        // 7/3/1/1 base samples of core delay at 2/4/8/16x.
        assertEquals(7, new CoreDesign(2).latency);
        assertEquals(3, new CoreDesign(4).latency);
        assertEquals(1, new CoreDesign(8).latency);
        assertEquals(1, new CoreDesign(16).latency);
    }

    @Test
    @DisplayName("AnalogAudit_tube_grid_conduction_is_cathode_referenced (tables)")
    void gridConductionTable()
    {
        // Independent solve of w + Rs * Ig(w) = d with the published 12AX7 fit.
        final java.util.function.DoubleUnaryOperator ig = v -> {
            final double z = 9.901 * v;
            final double sp = z > 40.0 ? z : Math.log1p(Math.exp(z));
            return 6.177e-4 * Math.pow(sp / 9.901, 1.314);
        };
        final java.util.function.DoubleUnaryOperator root = d -> {
            double lo = d - 68e3 * ig.applyAsDouble(d), hi = d;
            for (int i = 0; i < 200; ++i)
            {
                final double m = 0.5 * (lo + hi);
                if (m + 68e3 * ig.applyAsDouble(m) - d > 0.0) hi = m;
                else lo = m;
            }
            return 0.5 * (lo + hi);
        };
        final GridTable table = new GridTable();
        double worst = 0.0;
        for (double d = -3.0; d <= 90.0; d += 1.0 / 512.0)
            worst = Math.max(worst, Math.abs(table.eval(d) - root.applyAsDouble(d)));
        assertTrue(worst < 1e-7, "worst " + worst);
        assertEquals(0.9363, 1.2 + table.eval(1.0 - 1.2), 5e-4);
        assertEquals(0.624, GridClamp.value(1.0), 1e-3);
        // Documented: 20 V to 1.730 V before the capacitor charges (Clamp: 0.700 V).
        assertEquals(1.730, 1.2 + table.eval(20.0 - 1.2), 2e-3);
        assertEquals(0.700, GridClamp.value(20.0), 1e-12);
    }
}
