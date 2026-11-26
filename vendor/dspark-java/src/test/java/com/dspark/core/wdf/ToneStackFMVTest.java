package com.dspark.core.wdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ToneStackFMV}: the wave digital form and the analog
 * state space against the symbolic transfer function of Yeh and Smith
 * (DAFx-06) and against a nodal analysis of the schematic.
 */
class ToneStackFMVTest
{
    private static final double R_MIN = 0.5;

    // -- Complex helpers -------------------------------------------------------

    /** (re, im) of a / b. */
    private static double[] div(double ar, double ai, double br, double bi)
    {
        final double d = br * br + bi * bi;
        return new double[] { (ar * br + ai * bi) / d, (ai * br - ar * bi) / d };
    }

    /**
     * Solves the complex system (mr + j mi) x = (br + j bi) in place by
     * Gaussian elimination with partial pivoting; returns x as {re[], im[]}.
     */
    private static double[][] solve(double[][] mr, double[][] mi, double[] br, double[] bi)
    {
        final int n = br.length;
        for (int k = 0; k < n; ++k)
        {
            int p = k;
            for (int i = k + 1; i < n; ++i)
                if (Math.hypot(mr[i][k], mi[i][k]) > Math.hypot(mr[p][k], mi[p][k])) p = i;
            double[] t = mr[p]; mr[p] = mr[k]; mr[k] = t;
            t = mi[p]; mi[p] = mi[k]; mi[k] = t;
            double s = br[p]; br[p] = br[k]; br[k] = s;
            s = bi[p]; bi[p] = bi[k]; bi[k] = s;
            for (int i = k + 1; i < n; ++i)
            {
                final double[] f = div(mr[i][k], mi[i][k], mr[k][k], mi[k][k]);
                for (int j = k; j < n; ++j)
                {
                    final double re = f[0] * mr[k][j] - f[1] * mi[k][j];
                    final double im = f[0] * mi[k][j] + f[1] * mr[k][j];
                    mr[i][j] -= re;
                    mi[i][j] -= im;
                }
                br[i] -= f[0] * br[k] - f[1] * bi[k];
                bi[i] -= f[0] * bi[k] + f[1] * br[k];
            }
        }
        final double[] xr = new double[n], xi = new double[n];
        for (int i = n - 1; i >= 0; --i)
        {
            double vr = br[i], vi = bi[i];
            for (int k = i + 1; k < n; ++k)
            {
                vr -= mr[i][k] * xr[k] - mi[i][k] * xi[k];
                vi -= mr[i][k] * xi[k] + mi[i][k] * xr[k];
            }
            final double[] x = div(vr, vi, mr[i][i], mi[i][i]);
            xr[i] = x[0];
            xi[i] = x[1];
        }
        return new double[][] { xr, xi };
    }

    // -- References --------------------------------------------------------------

    /**
     * Coefficients {b1, b2, b3, a1, a2, a3} of the symbolic transfer function
     * of Yeh and Smith, H(s) = (b1 s + b2 s^2 + b3 s^3) / (1 + a1 s + a2 s^2 + a3 s^3).
     */
    private static double[] yehCoeffs(double t, double l, double m, double R1, double R3)
    {
        final double C1 = 0.25e-9, C2 = 20e-9, C3 = 20e-9;
        final double R2 = 1e6, R4 = 56e3;
        final double R3sq = R3 * R3;

        final double b1 = t * C1 * R1 + m * C3 * R3 + l * (C1 * R2 + C2 * R2) + (C1 * R3 + C2 * R3);
        final double b2 = t * (C1 * C2 * R1 * R4 + C1 * C3 * R1 * R4)
            - m * m * (C1 * C3 * R3sq + C2 * C3 * R3sq)
            + m * (C1 * C3 * R1 * R3 + C1 * C3 * R3sq + C2 * C3 * R3sq)
            + l * (C1 * C2 * R1 * R2 + C1 * C2 * R2 * R4 + C1 * C3 * R2 * R4)
            + l * m * (C1 * C3 * R2 * R3 + C2 * C3 * R2 * R3)
            + (C1 * C2 * R1 * R3 + C1 * C2 * R3 * R4 + C1 * C3 * R3 * R4);
        final double b3 = l * m * (C1 * C2 * C3 * R1 * R2 * R3 + C1 * C2 * C3 * R2 * R3 * R4)
            - m * m * (C1 * C2 * C3 * R1 * R3sq + C1 * C2 * C3 * R3sq * R4)
            + m * (C1 * C2 * C3 * R1 * R3sq + C1 * C2 * C3 * R3sq * R4)
            + t * C1 * C2 * C3 * R1 * R3 * R4 - t * m * C1 * C2 * C3 * R1 * R3 * R4
            + t * l * C1 * C2 * C3 * R1 * R2 * R4;
        final double a1 = (C1 * R1 + C1 * R3 + C2 * R3 + C2 * R4 + C3 * R4)
            + m * C3 * R3 + l * (C1 * R2 + C2 * R2);
        final double a2 = m * (C1 * C3 * R1 * R3 - C2 * C3 * R3 * R4 + C1 * C3 * R3sq + C2 * C3 * R3sq)
            + l * m * (C1 * C3 * R2 * R3 + C2 * C3 * R2 * R3)
            - m * m * (C1 * C3 * R3sq + C2 * C3 * R3sq)
            + l * (C1 * C2 * R2 * R4 + C1 * C2 * R1 * R2 + C1 * C3 * R2 * R4 + C2 * C3 * R2 * R4)
            + (C1 * C2 * R1 * R4 + C1 * C3 * R1 * R4 + C1 * C2 * R3 * R4
               + C1 * C2 * R1 * R3 + C1 * C3 * R3 * R4 + C2 * C3 * R3 * R4);
        final double a3 = l * m * (C1 * C2 * C3 * R1 * R2 * R3 + C1 * C2 * C3 * R2 * R3 * R4)
            - m * m * (C1 * C2 * C3 * R1 * R3sq + C1 * C2 * C3 * R3sq * R4)
            + m * (C1 * C2 * C3 * R3sq * R4 + C1 * C2 * C3 * R1 * R3sq
                   - C1 * C2 * C3 * R1 * R3 * R4)
            + l * C1 * C2 * C3 * R1 * R2 * R4 + C1 * C2 * C3 * R1 * R3 * R4;
        return new double[] { b1, b2, b3, a1, a2, a3 };
    }

    /** Yeh-Smith H(j omega) with the pot segments of the circuit (each pot end carries R_MIN). */
    private static double[] yehResponse(double t, double l, double m, double omega)
    {
        final double tEff = (t * 250e3 + R_MIN) / (250e3 + 2.0 * R_MIN);
        final double lEff = (l * 1e6 + R_MIN) / 1e6;
        final double mEff = (m * 25e3 + R_MIN) / (25e3 + 2.0 * R_MIN);
        final double[] k = yehCoeffs(tEff, lEff, mEff, 250e3 + 2.0 * R_MIN, 25e3 + 2.0 * R_MIN);
        // s = j w: s^2 = -w^2, s^3 = -j w^3
        final double w = omega, w2 = w * w, w3 = w2 * w;
        final double nr = -k[1] * w2, ni = k[0] * w - k[2] * w3;
        final double dr = 1.0 - k[4] * w2, di = k[3] * w - k[5] * w3;
        return div(nr, ni, dr, di);
    }

    /**
     * Vo / Vin at s = j omega by nodal analysis of the schematic (Yeh and
     * Smith, figure 1): Vin through the source resistance to node Vi; C1 from
     * Vi to the top of the treble pot (A); the treble wiper (Vo) splits R1
     * into (1 - t) R1 above and t R1 below, down to node B; R4 from Vi to the
     * slope node S; C2 from S to B; the bass rheostat l R2 from B to C; C3
     * from S to the middle wiper W; the middle pot splits R3 into (1 - m) R3
     * from C to W and m R3 from W to ground; the grid load from Vo to ground.
     * Every pot segment carries R_MIN in series.
     */
    private static double[] nodalResponse(double t, double l, double m, double rOut, double rLoad, double omega)
    {
        final int vi = 0, a = 1, vo = 2, b = 3, s = 4, c = 5, w = 6, n = 7, gnd = -1;
        final double[][] yr = new double[n][n], yi = new double[n][n];
        final double[] jr = new double[n], ji = new double[n];
        final double[][] resistors = {
            { a, vo, (1.0 - t) * 250e3 + R_MIN }, { vo, b, t * 250e3 + R_MIN },
            { vi, s, 56e3 }, { b, c, l * 1e6 + R_MIN },
            { c, w, (1.0 - m) * 25e3 + R_MIN }, { w, gnd, m * 25e3 + R_MIN }, { vo, gnd, rLoad } };
        final double[][] capacitors = { { vi, a, 0.25e-9 }, { s, b, 20e-9 }, { s, w, 20e-9 } };
        for (double[] e : resistors) stamp(yr, yi, (int) e[0], (int) e[1], 1.0 / e[2], 0.0);
        for (double[] e : capacitors) stamp(yr, yi, (int) e[0], (int) e[1], 0.0, omega * e[2]);
        // Source: 1 V behind rOut into node Vi (Norton form).
        yr[vi][vi] += 1.0 / rOut;
        jr[vi] += 1.0 / rOut;
        final double[][] x = solve(yr, yi, jr, ji);
        return new double[] { x[0][vo], x[1][vo] };
    }

    private static void stamp(double[][] yr, double[][] yi, int p, int q, double gr, double gi)
    {
        if (p >= 0) { yr[p][p] += gr; yi[p][p] += gi; }
        if (q >= 0) { yr[q][q] += gr; yi[q][q] += gi; }
        if (p >= 0 && q >= 0)
        {
            yr[p][q] -= gr; yi[p][q] -= gi;
            yr[q][p] -= gr; yi[q][p] -= gi;
        }
    }

    /** c (sI - a)^-1 b + d at s = j omega. */
    private static double[] stateSpaceResponse(ToneStackFMV.AnalogStateSpace ss, double omega)
    {
        final double[][] mr = new double[3][3], mi = new double[3][3];
        final double[] br = new double[3], bi = new double[3];
        for (int i = 0; i < 3; ++i)
        {
            for (int j = 0; j < 3; ++j)
            {
                mr[i][j] = -ss.a[i][j];
                mi[i][j] = i == j ? omega : 0.0;
            }
            br[i] = ss.b[i];
        }
        final double[][] x = solve(mr, mi, br, bi);
        double re = ss.d, im = 0.0;
        for (int i = 0; i < 3; ++i)
        {
            re += ss.c[i] * x[0][i];
            im += ss.c[i] * x[1][i];
        }
        return new double[] { re, im };
    }

    /** Frequency response of the stack at the given frequencies, from its impulse response. */
    private static double[][] measuredResponse(ToneStackFMV stack, double fs, double[] hz, int length)
    {
        final double[] h = new double[length];
        stack.reset();
        for (int i = 0; i < length; ++i) h[i] = stack.processSample(i == 0 ? 1.0 : 0.0);
        final double[][] out = new double[hz.length][2];
        for (int k = 0; k < hz.length; ++k)
        {
            final double w = 2.0 * Math.PI * hz[k] / fs;
            double re = 0.0, im = 0.0;
            for (int i = 0; i < length; ++i)
            {
                re += h[i] * Math.cos(w * i);
                im -= h[i] * Math.sin(w * i);
            }
            out[k][0] = re;
            out[k][1] = im;
        }
        double tail = 0.0;
        for (int i = length - 1000; i < length; ++i) tail = Math.max(tail, Math.abs(h[i]));
        assertTrue(tail < 1e-15, "impulse response not settled: " + tail);
        return out;
    }

    /** Analog frequency the bilinear transform maps to the digital frequency hz. */
    private static double warped(double hz, double fs)
    {
        return 2.0 * fs * Math.tan(Math.PI * hz / fs);
    }

    // -- Ported assertions -------------------------------------------------------

    @Test
    @DisplayName("State fork preserves the capacitors without sharing ports")
    void stateForkPreservesCapacitors()
    {
        for (double sampleRate : new double[] { 44100.0, 192000.0 })
            for (double tone : new double[] { 0.0, 1.0 })
                for (double offset : new double[] { 0.0, -173.0, 211.0 })
                {
                    final ToneStackFMV source = new ToneStackFMV(38e3, 1e6), twin = new ToneStackFMV(38e3, 1e6),
                                       fork = new ToneStackFMV(38e3, 1e6);
                    for (ToneStackFMV stack : new ToneStackFMV[] { source, twin, fork })
                    {
                        stack.prepare(sampleRate);
                        stack.setControls(tone, 1.0 - tone, tone);
                    }
                    for (int i = 0; i < 3072; ++i)
                    {
                        final double x = 0.3 + Math.sin(2.0 * Math.PI * 173.0 * i / sampleRate);
                        source.processSample(x);
                        twin.processSample(x);
                    }
                    fork.copyStateFrom(source, offset);
                    // Changing the source after the copy must not modify the
                    // fork's controls, port connections or capacitor history.
                    source.setControls(0.25, 0.75, 0.5);
                    double error = 0.0;
                    for (int i = 3072; i < 6144; ++i)
                    {
                        final double x = 0.3 + Math.sin(2.0 * Math.PI * 173.0 * i / sampleRate);
                        source.processSample(-x);
                        error = Math.max(error, Math.abs(fork.processSample(x + offset) - twin.processSample(x)));
                    }
                    assertEquals(0.0, error, offset == 0.0 ? 1e-14 : 1e-10,
                                 sampleRate + " Hz, tone " + tone + ", offset " + offset);
                }
    }

    /** Residual (dB) of the stack against the bilinear transform of the Yeh-Smith transfer function. */
    private static double fmvResidualDb(double t, double l, double m)
    {
        final double fs = 48000.0;
        final ToneStackFMV stack = new ToneStackFMV(0.01, 1e10);
        stack.prepare(fs);
        stack.setControls(t, Math.sqrt(l), m);   // the class maps bass^2 to l

        final double tEff = (t * 250e3 + R_MIN) / (250e3 + 2.0 * R_MIN);
        final double lEff = (l * 1e6 + R_MIN) / 1e6;
        final double mEff = (m * 25e3 + R_MIN) / (25e3 + 2.0 * R_MIN);

        final double[] k = yehCoeffs(tEff, lEff, mEff, 250e3, 25e3);
        final double b1 = k[0], b2 = k[1], b3 = k[2], a1 = k[3], a2 = k[4], a3 = k[5];
        final double c = 2.0 * fs, c2 = c * c, c3 = c2 * c;
        final double[] B = { -b1 * c - b2 * c2 - b3 * c3, -b1 * c + b2 * c2 + 3.0 * b3 * c3,
                             b1 * c + b2 * c2 - 3.0 * b3 * c3, b1 * c - b2 * c2 + b3 * c3 };
        final double[] A = { -1.0 - a1 * c - a2 * c2 - a3 * c3, -3.0 - a1 * c + a2 * c2 + 3.0 * a3 * c3,
                             -3.0 + a1 * c + a2 * c2 - 3.0 * a3 * c3, -1.0 + a1 * c - a2 * c2 + a3 * c3 };
        for (int i = 3; i >= 0; --i)
        {
            B[i] /= A[0];
            A[i] /= A[0];
        }

        final double[] x = new double[4], y = new double[4];
        int rng = 0xBEEF5EED;
        double err = 0, pw = 0;
        for (int i = 0; i < 24000; ++i)
        {
            rng = rng * 1664525 + 1013904223;
            final double in = (double) (rng >>> 8) / 8388608.0 - 1.0;
            final double yw = stack.processSample(in);
            x[3] = x[2]; x[2] = x[1]; x[1] = x[0]; x[0] = in;
            final double yr = B[0] * x[0] + B[1] * x[1] + B[2] * x[2] + B[3] * x[3]
                            - A[1] * y[0] - A[2] * y[1] - A[3] * y[2];
            y[2] = y[1]; y[1] = y[0]; y[0] = yr;
            if (i > 2400)
            {
                err += (yw - yr) * (yw - yr);
                pw += yr * yr;
            }
        }
        return 10.0 * Math.log10((err + 1e-300) / (pw + 1e-300));
    }

    @Test
    @DisplayName("The 12-port R-type nulls against the bilinear Yeh-Smith transfer function")
    void rTypeMatchesYehTransferFunction()
    {
        for (double t : new double[] { 0.1, 0.9 })
            for (double l : new double[] { 0.1, 0.9 })
                for (double m : new double[] { 0.1, 0.9 })
                {
                    final double db = fmvResidualDb(t, l, m);
                    assertTrue(db < -80.0, t + "/" + l + "/" + m + ": " + db);
                }
        assertTrue(fmvResidualDb(0.5, 0.5, 0.5) < -80.0);
    }

    @Test
    @DisplayName("The analog state space equals the Yeh-Smith analog transfer function")
    void analogStateSpaceMatchesYeh()
    {
        final double[] grid = { 0.0, 0.1, 0.5, 0.9, 1.0 };
        final ToneStackFMV.AnalogStateSpace ss = new ToneStackFMV.AnalogStateSpace();
        for (double t : grid)
            for (double l : grid)
                for (double m : grid)
                {
                    final ToneStackFMV stack = new ToneStackFMV(1e-6, 1e14);
                    stack.prepare(48000.0);
                    stack.setControls(t, Math.sqrt(l), m);
                    stack.analogStateSpace(ss);
                    for (double f : new double[] { 10.0, 100.0, 1000.0, 10000.0, 40000.0 })
                    {
                        final double omega = 2.0 * 3.14159265358979323846 * f;
                        final double[] expected = yehResponse(t, l, m, omega);
                        final double[] got = stateSpaceResponse(ss, omega);
                        final double rel = Math.hypot(got[0] - expected[0], got[1] - expected[1])
                                         / Math.hypot(expected[0], expected[1]);
                        assertTrue(rel < 1e-7, t + "/" + l + "/" + m + " at " + f + " Hz: " + rel);
                    }
                    // a = -C^-1 G with G symmetric: C a is symmetric.
                    for (int i = 0; i < 3; ++i)
                        for (int j = 0; j < 3; ++j)
                            assertEquals(ss.capacitance[i] * ss.a[i][j], ss.capacitance[j] * ss.a[j][i],
                                         1e-10 * Math.max(Math.abs(ss.capacitance[i] * ss.a[i][i]),
                                                          Math.abs(ss.capacitance[j] * ss.a[j][j])));
                }
    }

    @Test
    @DisplayName("A NaN input is not kept: the stack continues with finite output")
    void recoversAfterNonFiniteInput()
    {
        final ToneStackFMV stack = new ToneStackFMV();
        stack.prepare(48000.0);
        stack.processSample(Double.NaN);
        double y = 0.0;
        for (int i = 0; i < 1000; ++i) y = stack.processSample(0.1 * Math.sin(0.05 * i));
        assertTrue(Double.isFinite(y));
    }

    // -- Transfer function derived from the schematic ------------------------------

    @Test
    @DisplayName("Nodal analysis of the schematic reproduces the Yeh-Smith transfer function")
    void schematicMatchesYeh()
    {
        final double[] grid = { 0.0, 0.3, 0.5, 1.0 };
        for (double t : grid)
            for (double l : grid)
                for (double m : grid)
                    for (double f : new double[] { 10.0, 150.0, 1000.0, 6000.0, 30000.0 })
                    {
                        final double omega = 2.0 * Math.PI * f;
                        final double[] yeh = yehResponse(t, l, m, omega);
                        final double[] nodal = nodalResponse(t, l, m, 1e-6, 1e14, omega);
                        final double rel = Math.hypot(nodal[0] - yeh[0], nodal[1] - yeh[1]) / Math.hypot(yeh[0], yeh[1]);
                        assertTrue(rel < 1e-7, t + "/" + l + "/" + m + " at " + f + " Hz: " + rel);
                    }
    }

    @Test
    @DisplayName("Magnitude response of the wave digital stack equals the warped Yeh-Smith magnitude")
    void magnitudeMatchesYeh()
    {
        final double fs = 48000.0;
        final double[] hz = { 20.0, 100.0, 400.0, 1000.0, 3000.0, 8000.0, 15000.0, 20000.0 };
        final double[] grid = { 0.0, 0.5, 1.0 };
        final ToneStackFMV stack = new ToneStackFMV(0.01, 1e10);
        stack.prepare(fs);
        for (double t : grid)
            for (double bass : grid)
                for (double m : grid)
                {
                    stack.setControls(t, bass, m);
                    final double[][] got = measuredResponse(stack, fs, hz, 1 << 15);
                    for (int k = 0; k < hz.length; ++k)
                    {
                        final double[] yeh = yehResponse(t, bass * bass, m, warped(hz[k], fs));
                        final double dbYeh = 20.0 * Math.log10(Math.hypot(yeh[0], yeh[1]));
                        final double dbWdf = 20.0 * Math.log10(Math.hypot(got[k][0], got[k][1]));
                        assertEquals(dbYeh, dbWdf, 1e-3, t + "/" + bass + "/" + m + " at " + hz[k] + " Hz");
                    }
                }
    }

    @Test
    @DisplayName("With the preamp's 38 k source and 1 M load, wave digital, state space and schematic agree")
    void wdfStateSpaceAndSchematicAgree()
    {
        final double[] hz = { 20.0, 200.0, 1000.0, 5000.0, 12000.0, 20000.0 };
        final double[][] controls = { { 0.5, 0.5, 0.5 }, { 0.0, 1.0, 0.0 }, { 1.0, 0.0, 1.0 }, { 0.2, 0.7, 0.9 } };
        final ToneStackFMV.AnalogStateSpace ss = new ToneStackFMV.AnalogStateSpace();
        for (double fs : new double[] { 48000.0, 96000.0 })
            for (double[] c : controls)
            {
                final ToneStackFMV stack = new ToneStackFMV(38e3, 1e6);
                stack.prepare(fs);
                stack.setControls(c[0], c[1], c[2]);
                stack.analogStateSpace(ss);
                final double[][] got = measuredResponse(stack, fs, hz, 1 << 16);
                for (int k = 0; k < hz.length; ++k)
                {
                    final double omega = warped(hz[k], fs);
                    final double[] model = stateSpaceResponse(ss, omega);
                    final double[] schematic = nodalResponse(c[0], c[1] * c[1], c[2], 38e3, 1e6, omega);
                    final double mag = Math.hypot(schematic[0], schematic[1]);
                    final double relModel = Math.hypot(model[0] - schematic[0], model[1] - schematic[1]) / mag;
                    final double relWdf = Math.hypot(got[k][0] - model[0], got[k][1] - model[1]) / mag;
                    final String where = fs + " Hz rate, controls " + c[0] + "/" + c[1] + "/" + c[2] + " at " + hz[k] + " Hz";
                    assertTrue(relModel < 1e-9, where + ": state space " + relModel);
                    assertTrue(relWdf < 1e-8, where + ": wave digital " + relWdf);
                }
            }
    }

    // -- Properties ----------------------------------------------------------------

    @Test
    @DisplayName("Scattering: adapted up port, preserved DC mode, DC blocked")
    void scatteringProperties()
    {
        final double[] mode = { 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0 };
        for (double fs : new double[] { 44100.0, 96000.0 })
            for (double t : new double[] { 0.0, 0.4, 1.0 })
                for (double b : new double[] { 0.0, 0.6, 1.0 })
                {
                    final ToneStackFMV stack = new ToneStackFMV(38e3, 1e6);
                    stack.prepare(fs);
                    stack.setControls(t, b, 1.0 - t);
                    final RType r = stack.rType();
                    assertEquals(0.0, r.scattering(0, 0), 0.0);
                    for (int i = 0; i < 12; ++i)
                    {
                        double sum = 0.0;
                        for (int j = 0; j < 12; ++j) sum += r.scattering(i, j) * mode[j];
                        assertEquals(mode[i], sum, 1e-12, "row " + i);
                    }
                    // A constant input settles to zero output.
                    double y = 1.0;
                    for (int i = 0; i < (int) (2.0 * fs); ++i) y = stack.processSample(0.7);
                    assertEquals(0.0, y, 1e-9);
                }
    }

    @Test
    @DisplayName("reset() equals a fresh stack; prepare() clears the states; controls are clamped")
    void resetAndControls()
    {
        final ToneStackFMV used = new ToneStackFMV(38e3, 1e6), fresh = new ToneStackFMV(38e3, 1e6);
        used.prepare(48000.0);
        fresh.prepare(48000.0);
        used.setControls(0.3, 0.8, 0.6);
        fresh.setControls(0.3, 0.8, 0.6);
        for (int i = 0; i < 2000; ++i) used.processSample(Math.sin(0.01 * i) + 0.2);
        used.reset();
        for (int i = 0; i < 2000; ++i)
        {
            final double x = Math.cos(0.003 * i * i % 7.0);
            assertEquals(fresh.processSample(x), used.processSample(x), 0.0);
        }
        used.prepare(48000.0);
        fresh.reset();
        for (int i = 0; i < 200; ++i)
            assertEquals(fresh.processSample(0.1 * i % 1.0), used.processSample(0.1 * i % 1.0), 0.0);

        used.setControls(-2.0, 5.0, 0.25);
        assertEquals(0.0, used.getTreble(), 0.0);
        assertEquals(1.0, used.getBass(), 0.0);
        assertEquals(0.25, used.getMiddle(), 0.0);
        final ToneStackFMV clamped = new ToneStackFMV(38e3, 1e6);
        clamped.prepare(48000.0);
        clamped.setControls(0.0, 1.0, 0.25);
        used.reset();
        for (int i = 0; i < 300; ++i)
            assertEquals(clamped.processSample(Math.sin(0.2 * i)), used.processSample(Math.sin(0.2 * i)), 0.0);

        // The default controls sit at 0.5 and survive prepare().
        final ToneStackFMV neutral = new ToneStackFMV();
        neutral.prepare(44100.0);
        assertEquals(0.5, neutral.getTreble(), 0.0);
        assertEquals(0.5, neutral.getBass(), 0.0);
        assertEquals(0.5, neutral.getMiddle(), 0.0);
        final ToneStackFMV.AnalogStateSpace a = neutral.analogStateSpace();
        assertEquals(0.25e-9, a.capacitance[0], 0.0);
        assertEquals(20e-9, a.capacitance[1], 0.0);
        assertEquals(20e-9, a.capacitance[2], 0.0);
        final ToneStackFMV.AnalogStateSpace b = new ToneStackFMV.AnalogStateSpace();
        neutral.analogStateSpace(b);
        for (int i = 0; i < 3; ++i)
        {
            assertEquals(a.b[i], b.b[i], 0.0);
            assertEquals(a.c[i], b.c[i], 0.0);
            for (int j = 0; j < 3; ++j) assertEquals(a.a[i][j], b.a[i][j], 0.0);
        }
        assertEquals(a.d, b.d, 0.0);
    }
}
