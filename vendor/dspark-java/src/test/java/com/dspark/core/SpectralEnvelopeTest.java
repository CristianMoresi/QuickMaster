package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SpectralEnvelope}. */
class SpectralEnvelopeTest
{
    private static final int BINS = 1025;

    private static int widthAtHalf(double[] v, int at)
    {
        double half = 0.5 * v[at];
        int lo = at, hi = at;
        while (lo > 0 && v[lo - 1] >= half) lo--;
        while (hi < v.length - 1 && v[hi + 1] >= half) hi++;
        return hi - lo + 1;
    }

    private static double[] line(int at)
    {
        double[] v = new double[BINS];
        v[at] = 1.0;
        return v;
    }

    @Test
    @DisplayName("A flat spectrum stays flat")
    void keepsALevel()
    {
        SpectralEnvelope e = new SpectralEnvelope();
        e.prepare(BINS, 1.0 / 3.0);
        double[] in = new double[BINS], out = new double[BINS];
        java.util.Arrays.fill(in, 2.5);
        e.compute(in, out);
        for (int k = 0; k < BINS; k++) assertEquals(2.5, out[k], 1e-12, "bin " + k);
    }

    @Test
    @DisplayName("The smoothing widens in proportion to frequency")
    void widthFollowsFrequency()
    {
        SpectralEnvelope e = new SpectralEnvelope();
        e.prepare(BINS, 1.0 / 3.0);
        double[] low = new double[BINS], mid = new double[BINS], high = new double[BINS];
        e.compute(line(60), low);
        e.compute(line(240), mid);
        e.compute(line(960), high);
        int wLow = widthAtHalf(low, 60), wMid = widthAtHalf(mid, 240), wHigh = widthAtHalf(high, 960);
        assertTrue(wMid > 2 * wLow && wHigh > 2 * wMid, "widths " + wLow + ", " + wMid + ", " + wHigh);
        // A third of an octave around each centre, within a factor of two.
        double third = Math.pow(2.0, 1.0 / 6.0) - Math.pow(2.0, -1.0 / 6.0);
        assertEquals(1.0, wMid / (third * 240.0), 0.5, "half width of " + wMid + " bins at bin 240");
    }

    @Test
    @DisplayName("The input is left as it was when the output is another array")
    void inputUntouched()
    {
        SpectralEnvelope e = new SpectralEnvelope();
        e.prepare(BINS, 1.0 / 6.0);
        double[] in = line(300), out = new double[BINS];
        e.compute(in, out);
        assertEquals(1.0, in[300], 0.0);
        assertEquals(0.0, in[299], 0.0);
        assertTrue(out[300] < 1.0 && out[299] > 0.0);
    }

    @Test
    @DisplayName("Several spectra at once read what each reads alone")
    void interleavedMatchesSingle()
    {
        SpectralEnvelope e = new SpectralEnvelope();
        e.prepare(BINS, 1.0 / 6.0);
        int lanes = 4;
        Random r = new Random(3);
        double[][] single = new double[lanes][BINS];
        double[] together = new double[lanes * BINS];
        for (int k = 0; k < BINS; k++)
            for (int j = 0; j < lanes; j++)
            {
                single[j][k] = r.nextDouble();
                together[lanes * k + j] = single[j][k];
            }
        e.computeInterleaved(lanes, together);
        double[] out = new double[BINS];
        for (int j = 0; j < lanes; j++)
        {
            e.compute(single[j], out);
            for (int k = 0; k < BINS; k++) assertEquals(out[k], together[lanes * k + j], 1e-13, "lane " + j + " bin " + k);
        }
    }

    @Test
    @DisplayName("Two passes keep a level and shorten the tails of one")
    void twoPasses()
    {
        SpectralEnvelope one = new SpectralEnvelope(), two = new SpectralEnvelope();
        one.prepare(BINS, 1.0 / 3.0);
        two.prepare(BINS, 1.0 / 3.0);
        two.setPasses(2);
        assertEquals(2, two.getPasses());
        double[] a = new double[BINS], b = new double[BINS];
        one.compute(line(400), a);
        two.compute(line(400), b);
        assertTrue(b[400 + 300] < a[400 + 300], "far tail " + b[700] + " against " + a[700]);
        assertTrue(b[400 - 250] < a[400 - 250], "far tail under the line " + b[150] + " against " + a[150]);
        assertTrue(b[400] < a[400], "and a rounder top: " + b[400] + " against " + a[400]);
        double[] flat = new double[BINS];
        java.util.Arrays.fill(flat, 1.0);
        two.compute(flat, b);
        for (int k = 0; k < BINS; k++) assertEquals(1.0, b[k], 1e-12);
    }

    @Test
    @DisplayName("The taper narrows the smoothing above its corner and leaves it under")
    void taper()
    {
        SpectralEnvelope plain = new SpectralEnvelope(), tapered = new SpectralEnvelope();
        plain.prepare(BINS, 1.0 / 3.0);
        tapered.prepare(BINS, 1.0 / 3.0);
        tapered.setTaper(200.0, 1.0);
        double[] a = new double[BINS], b = new double[BINS];
        plain.compute(line(900), a);
        tapered.compute(line(900), b);
        assertTrue(widthAtHalf(b, 900) < widthAtHalf(a, 900));
        assertTrue(b[900] > a[900]);
        plain.compute(line(100), a);
        tapered.compute(line(100), b);
        assertEquals(a[100], b[100], 0.02 * a[100]);
    }

    @Test
    @DisplayName("Widths out of range are clamped and values that are not numbers ignored")
    void clamps()
    {
        SpectralEnvelope e = new SpectralEnvelope();
        e.prepare(BINS, 100.0);
        assertEquals(4.0, e.getWidth(), 0.0);
        e.setWidth(0.0);
        assertEquals(0.05, e.getWidth(), 0.0);
        e.setWidth(Double.NaN);
        assertEquals(0.05, e.getWidth(), 0.0);
        assertEquals(BINS, e.getNumBins());
    }
}
