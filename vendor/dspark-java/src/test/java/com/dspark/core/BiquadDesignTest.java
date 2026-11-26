package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dspark.core.PortSignals.biquadMagDb;
import static com.dspark.core.PortSignals.isStable;
import static com.dspark.core.PortSignals.same;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The coefficient designers of {@link BiquadDesign}. */
class BiquadDesignTest
{
    private static final double PI_T = 3.14159265358979;

    /** The cookbook bell's analog prototype in dB. */
    private static double analogPeakDb(double f0, double q, double gainDb, double freq)
    {
        final double g = Math.pow(10.0, gainDb / 20.0);
        final double w0 = 2.0 * PI_T * f0;
        final double b = w0 / (q * Math.sqrt(g));
        final double w = 2.0 * PI_T * freq;
        final double d2 = (w * w - w0 * w0) * (w * w - w0 * w0);
        return 10.0 * Math.log10((d2 + g * g * b * b * w * w) / (d2 + b * b * w * w));
    }

    private static double worstAudibleErrorDb(BiquadCoeffs c, double fs, double f0, double q, double g)
    {
        double worst = 0.0;
        for (double f = 20.0; f <= 20000.0; f *= 1.02)
            worst = Math.max(worst, Math.abs(biquadMagDb(c, f, fs) - analogPeakDb(f0, q, g, f)));
        return worst;
    }

    /** The analog prototypes of the matched designs (TestCoreRegressions analogDb). */
    private static double analogDb(int type, double f, double f0, double q, double gainDb)
    {
        final double sr = 0.0, si = f / f0;                    // s = j f / f0
        final double a = Math.pow(10.0, gainDb / 40.0);
        final double r = Math.sqrt(a) * Math.sqrt(2.0);
        final double s2r = sr * sr - si * si, s2i = 2.0 * sr * si;
        double nr, ni, dr, di;
        switch (type)
        {
            case 0: nr = 1.0; ni = 0.0; dr = s2r + sr / q + 1.0; di = s2i + si / q; break;
            case 1: nr = s2r; ni = s2i; dr = s2r + sr / q + 1.0; di = s2i + si / q; break;
            case 2: nr = sr / q; ni = si / q; dr = s2r + sr / q + 1.0; di = s2i + si / q; break;
            case 3: nr = a * (s2r + r * sr + a); ni = a * (s2i + r * si); dr = a * s2r + r * sr + 1.0; di = a * s2i + r * si; break;
            default: nr = a * (a * s2r + r * sr + 1.0); ni = a * (a * s2i + r * si); dr = s2r + r * sr + a; di = s2i + r * si; break;
        }
        return 20.0 * Math.log10(Math.hypot(nr, ni) / Math.hypot(dr, di));
    }

    private static double worstAnalogError(BiquadCoeffs c, int type, double fs, double f0, double q, double gainDb)
    {
        double worst = 0.0;
        for (double f = 20.0; f <= 20000.0 && f < 0.495 * fs; f *= Math.pow(2.0, 1.0 / 24.0))
        {
            final double a = analogDb(type, f, f0, q, gainDb);
            if (a < -60.0) continue;
            worst = Math.max(worst, Math.abs(20.0 * Math.log10(BiquadDesign.getMagnitude(c, f, fs)) - a));
        }
        return worst;
    }

    @Test
    @DisplayName("Matched peak follows the analog bell, far closer than the cookbook")
    void matchedPeakFollowsTheAnalogBell()
    {
        final double[][] cases = {
            { 48000.0, 16000.0, 1.0, 6.0, 0.25 },
            { 48000.0, 12000.0, 1.0, -9.0, 0.90 },
            { 48000.0, 10000.0, 0.7, 12.0, 0.65 },
            { 44100.0, 15000.0, 0.7, -12.0, 1.25 },
        };
        for (double[] k : cases)
        {
            final BiquadCoeffs m = BiquadDesign.makePeakMatched(k[0], k[1], k[2], k[3]);
            final BiquadCoeffs r = BiquadDesign.makePeak(k[0], k[1], k[2], k[3]);
            assertEquals(k[3], biquadMagDb(m, k[1], k[0]), 0.01);
            final double errM = worstAudibleErrorDb(m, k[0], k[1], k[2], k[3]);
            assertTrue(errM < k[4], "worst " + errM);
            assertTrue(errM < 0.3 * worstAudibleErrorDb(r, k[0], k[1], k[2], k[3]));
        }
    }

    @Test
    @DisplayName("Matched peak converges to the cookbook at low frequencies; cut and identity")
    void matchedPeakConvergesToCookbookAtLowFrequencies()
    {
        final BiquadCoeffs m = BiquadDesign.makePeakMatched(48000.0, 500.0, 1.0, 6.0);
        final BiquadCoeffs r = BiquadDesign.makePeak(48000.0, 500.0, 1.0, 6.0);
        for (double f = 50.0; f <= 8000.0; f *= 1.2)
            assertEquals(biquadMagDb(r, f, 48000.0), biquadMagDb(m, f, 48000.0), 0.15);

        final BiquadCoeffs cut = BiquadDesign.makePeakMatched(48000.0, 12000.0, 1.0, -9.0);
        assertEquals(-9.0, biquadMagDb(cut, 12000.0, 48000.0), 0.05);
        final BiquadCoeffs flat = BiquadDesign.makePeakMatched(48000.0, 1000.0, 1.0, 0.0);
        assertEquals(0.0, biquadMagDb(flat, 1000.0, 48000.0), 1e-9);
    }

    @Test
    @DisplayName("Matched peak is stable and finite everywhere")
    void matchedPeakIsStableAndFiniteEverywhere()
    {
        for (double fs : new double[] { 44100.0, 48000.0, 768000.0 })
            for (double f0 : new double[] { 10.0, 1000.0, 20000.0, fs * 0.499 })
                for (double q : new double[] { 0.001, 0.3, 0.707, 10.0, 100.0 })
                    for (double g : new double[] { -30.0, -0.5, 0.5, 30.0 })
                    {
                        final BiquadCoeffs c = BiquadDesign.makePeakMatched(fs, f0, q, g);
                        assertTrue(Double.isFinite(c.b0) && Double.isFinite(c.b1) && Double.isFinite(c.b2)
                                   && Double.isFinite(c.a1) && Double.isFinite(c.a2));
                        assertTrue(Math.abs(c.a2) < 1.0);
                        assertTrue(Math.abs(c.a1) < 1.0 + c.a2 + 1e-12);
                    }
    }

    @Test
    @DisplayName("First-order sections are the exact bilinear image of RC and CR")
    void firstOrderSectionsMatchBilinearPrototype()
    {
        final double fs = 48000.0, fc = 1000.0;
        final BiquadCoeffs lp = BiquadDesign.makeFirstOrderLowPass(fs, fc);
        final BiquadCoeffs hp = BiquadDesign.makeFirstOrderHighPass(fs, fc);
        final double t0 = Math.tan(Math.PI * fc / fs);
        for (double f = 20.0; f <= 22000.0; f *= 1.3)
        {
            final double w = Math.tan(Math.PI * f / fs) / t0;
            assertEquals(20.0 * Math.log10(1.0 / Math.sqrt(1.0 + w * w)), biquadMagDb(lp, f, fs), 1e-6);
            assertEquals(20.0 * Math.log10(w / Math.sqrt(1.0 + w * w)), biquadMagDb(hp, f, fs), 1e-6);
        }
        assertEquals(-3.0103, biquadMagDb(lp, fc, fs), 0.001);
        assertEquals(-3.0103, biquadMagDb(hp, fc, fs), 0.001);
        assertEquals(0.0, biquadMagDb(lp, 0.0, fs), 1e-9);
        assertEquals(0.0, biquadMagDb(hp, fs / 2.0, fs), 1e-9);
    }

    @Test
    @DisplayName("Tilt: -g/2 at DC, 0 dB at the pivot, +g/2 at Nyquist")
    void tiltPivotAndEndpointGains()
    {
        final double fs = 48000.0, pivot = 1000.0, g = 8.0;
        final BiquadCoeffs bright = BiquadDesign.makeTilt(fs, pivot, g);
        assertEquals(-g / 2.0, biquadMagDb(bright, 0.0, fs), 0.001);
        assertEquals(0.0, biquadMagDb(bright, pivot, fs), 0.001);
        assertEquals(g / 2.0, biquadMagDb(bright, fs / 2.0, fs), 0.001);
        final BiquadCoeffs dark = BiquadDesign.makeTilt(fs, pivot, -g);
        assertEquals(g / 2.0, biquadMagDb(dark, 0.0, fs), 0.001);
        assertEquals(-g / 2.0, biquadMagDb(dark, fs / 2.0, fs), 0.001);
        assertEquals(biquadMagDb(bright, 3000.0, fs), 20.0 * Math.log10(BiquadDesign.getMagnitude(bright, 3000.0, fs)), 1e-9);
    }

    @Test
    @DisplayName("Matched low-pass, high-pass and band-pass follow their analog prototypes")
    void matchedLowPassHighPassBandPassFollowAnalog()
    {
        final double fs = 48000.0, q = 0.7071;
        assertTrue(worstAnalogError(BiquadDesign.makeLowPassMatched(fs, 15000.0, q), 0, fs, 15000.0, q, 0.0) < 0.7);
        assertTrue(worstAnalogError(BiquadDesign.makeHighPassMatched(fs, 18000.0, q), 1, fs, 18000.0, q, 0.0) < 0.5);
        assertTrue(worstAnalogError(BiquadDesign.makeBandPassMatched(fs, 5000.0, q), 2, fs, 5000.0, q, 0.0) < 1.0);
        // The figures of the doc comments.
        assertTrue(worstAnalogError(BiquadDesign.makeLowPassMatched(fs, 15000.0, 0.7071067811865476), 0, fs, 15000.0, 0.7071067811865476, 0.0) < 0.59);
        assertTrue(worstAnalogError(BiquadDesign.makeHighPassMatched(fs, 18000.0, 0.7071067811865476), 1, fs, 18000.0, 0.7071067811865476, 0.0) < 0.40);
        assertTrue(worstAnalogError(BiquadDesign.makeBandPassMatched(fs, 5000.0, 0.7071067811865476), 2, fs, 5000.0, 0.7071067811865476, 0.0) < 0.93);
        for (double sr : new double[] { 44100.0, 48000.0, 192000.0, 768000.0 })
            for (double f0 : new double[] { 5.0, 20.0, 1000.0, 0.3 * sr, 0.49 * sr })
                for (double qq : new double[] { 0.1, 0.5, 0.7071, 4.0, 20.0 })
                {
                    final BiquadCoeffs lp = BiquadDesign.makeLowPassMatched(sr, f0, qq);
                    final BiquadCoeffs hp = BiquadDesign.makeHighPassMatched(sr, f0, qq);
                    final BiquadCoeffs bp = BiquadDesign.makeBandPassMatched(sr, f0, qq);
                    assertTrue(isStable(lp) && isStable(hp) && isStable(bp));
                    assertEquals(1.0, (lp.b0 + lp.b1 + lp.b2) / (1.0 + lp.a1 + lp.a2), 1e-6);
                    assertEquals(20.0 * Math.log10(qq), 20.0 * Math.log10(BiquadDesign.getMagnitude(lp, f0, sr)), 0.05);
                }
    }

    @Test
    @DisplayName("Matched shelves follow the analog shelf and hold their DC and Nyquist gains")
    void matchedShelvesFollowAnalog()
    {
        double worst = 0.0;
        for (double fs : new double[] { 44100.0, 48000.0 })
            for (double f0 : new double[] { 1000.0, 5000.0, 10000.0, 15000.0, 18000.0 })
                for (double g : new double[] { -12.0, 6.0, 12.0 })
                {
                    worst = Math.max(worst, worstAnalogError(BiquadDesign.makeLowShelfMatched(fs, f0, g), 3, fs, f0, 0.7071, g));
                    worst = Math.max(worst, worstAnalogError(BiquadDesign.makeHighShelfMatched(fs, f0, g), 4, fs, f0, 0.7071, g));
                }
        assertTrue(worst < 0.5, "worst " + worst);
        for (double fs : new double[] { 44100.0, 192000.0, 768000.0 })
            for (double f0 : new double[] { 10.0, 100.0, 0.25 * fs, 0.49 * fs })
                for (double g : new double[] { -40.0, -24.0, -0.5, 0.5, 24.0, 40.0 })
                {
                    final BiquadCoeffs ls = BiquadDesign.makeLowShelfMatched(fs, f0, g);
                    final BiquadCoeffs hs = BiquadDesign.makeHighShelfMatched(fs, f0, g);
                    assertTrue(isStable(ls) && isStable(hs));
                    assertEquals(g, 20.0 * Math.log10(Math.abs((ls.b0 + ls.b1 + ls.b2) / (1.0 + ls.a1 + ls.a2))), 1e-3);
                    assertEquals(0.0, 20.0 * Math.log10(Math.abs((hs.b0 + hs.b1 + hs.b2) / (1.0 + hs.a1 + hs.a2))), 1e-3);
                }
        // Identity under a hundredth of a decibel.
        assertTrue(same(BiquadCoeffs.IDENTITY, BiquadDesign.makeLowShelfMatched(48000.0, 1000.0, 0.005)));
        assertTrue(same(BiquadCoeffs.IDENTITY, BiquadDesign.makeHighShelfMatched(48000.0, 1000.0, -0.005)));
    }

    @Test
    @DisplayName("K-weighting stages reproduce the ITU-R BS.1770 tables at 48 kHz")
    void kWeightingMatchesTheOfficialTables()
    {
        final BiquadCoeffs pre = BiquadDesign.makeKWeightingShelf(48000.0);
        assertEquals(1.53512485958697, pre.b0, 2e-15);
        assertEquals(-2.69169618940638, pre.b1, 2e-15);
        assertEquals(1.19839281085285, pre.b2, 2e-15);
        assertEquals(-1.69065929318241, pre.a1, 2e-15);
        assertEquals(0.73248077421585, pre.a2, 2e-15);
        final BiquadCoeffs rlb = BiquadDesign.makeKWeightingHighPass(48000.0);
        assertTrue(rlb.b0 == 1.0 && rlb.b1 == -2.0 && rlb.b2 == 1.0);
        assertEquals(-1.99004745483398, rlb.a1, 1e-13);
        assertEquals(0.99007225036621, rlb.a2, 1e-13);
        // The cascade passes about +0.69 dB at 997 Hz (the -0.691 of the LUFS formula).
        final double db = 20.0 * Math.log10(BiquadDesign.getMagnitude(pre, 997.0, 48000.0) * BiquadDesign.getMagnitude(rlb, 997.0, 48000.0));
        assertEquals(0.691, db, 0.01);
    }

    @Test
    @DisplayName("bypassed() keeps the poles and is flat")
    void bypassedKeepsThePolesAndIsFlat()
    {
        final BiquadCoeffs bell = BiquadDesign.makePeak(48000.0, 1000.0, 4.0, 12.0);
        final BiquadCoeffs flat = BiquadDesign.bypassed(bell);
        assertTrue(flat.a1 == bell.a1 && flat.a2 == bell.a2 && flat.b0 == 1.0 && flat.b1 == bell.a1 && flat.b2 == bell.a2);
        assertEquals(1.0, BiquadDesign.getMagnitude(flat, 1000.0, 48000.0), 1e-12);
        assertEquals(1.0, BiquadDesign.getMagnitude(flat, 15000.0, 48000.0), 1e-12);
        // A bell at 0 dB already is that set.
        final BiquadCoeffs zero = BiquadDesign.makePeak(48000.0, 1000.0, 4.0, 0.0);
        assertTrue(same(zero, BiquadDesign.bypassed(zero)));
    }

    @Test
    @DisplayName("Batch magnitudes equal the single evaluations")
    void batchMagnitudes()
    {
        final BiquadCoeffs c = BiquadDesign.makeHighShelf(44100.0, 3000.0, 5.0, 0.7);
        final double[] f = { 20.0, 100.0, 1000.0, 10000.0, 20000.0 };
        final double[] m = new double[f.length];
        final float[] ff = { 20f, 100f, 1000f, 10000f, 20000f };
        final float[] mf = new float[f.length];
        BiquadDesign.getMagnitudeForFrequencyArray(c, f, m, 44100.0);
        BiquadDesign.getMagnitudeForFrequencyArray(c, ff, mf, 44100.0);
        for (int i = 0; i < f.length; ++i)
        {
            assertEquals(BiquadDesign.getMagnitude(c, f[i], 44100.0), m[i], 0.0);
            assertEquals((float) BiquadDesign.getMagnitude(c, f[i], 44100.0), mf[i], 0.0f);
            assertEquals(c.magnitude(f[i], 44100.0), m[i], 0.0);
        }
    }

    @Test
    @DisplayName("For finite parameters the cookbook designs equal the older BiquadCoeffs factories to the bit")
    void finiteDesignsEqualTheOlderFactories()
    {
        final double[] rates = { 8000.0, 44100.0, 48000.0, 96000.0, 768000.0 };
        final double[] freqs = { -5.0, 0.0, 0.5, 1.0, 20.0, 997.0, 12000.0, 23999.0, 1e6 };
        final double[] qs = { -1.0, 0.0, 0.0005, 0.3, 0.7071067811865476, 4.0, 100.0 };
        final double[] gains = { -40.0, -6.0, -0.005, 0.0, 0.005, 3.0, 24.0 };
        final double[] slopes = { -1.0, 0.0, 0.5, 1.0, 2.0 };
        for (double fs : rates)
            for (double f : freqs)
            {
                assertTrue(same(BiquadCoeffs.firstOrderLowPass(fs, f), BiquadDesign.makeFirstOrderLowPass(fs, f)));
                assertTrue(same(BiquadCoeffs.firstOrderHighPass(fs, f), BiquadDesign.makeFirstOrderHighPass(fs, f)));
                for (double q : qs)
                {
                    assertTrue(same(BiquadCoeffs.lowPass(fs, f, q), BiquadDesign.makeLowPass(fs, f, q)));
                    assertTrue(same(BiquadCoeffs.highPass(fs, f, q), BiquadDesign.makeHighPass(fs, f, q)));
                    assertTrue(same(BiquadCoeffs.bandPass(fs, f, q), BiquadDesign.makeBandPass(fs, f, q)));
                    assertTrue(same(BiquadCoeffs.notch(fs, f, q), BiquadDesign.makeNotch(fs, f, q)));
                    assertTrue(same(BiquadCoeffs.allPass(fs, f, q), BiquadDesign.makeAllPass(fs, f, q)));
                    for (double g : gains)
                    {
                        assertTrue(same(BiquadCoeffs.peak(fs, f, q, g), BiquadDesign.makePeak(fs, f, q, g)));
                        assertTrue(same(BiquadCoeffs.peakMatched(fs, f, q, g), BiquadDesign.makePeakMatched(fs, f, q, g)));
                    }
                }
                for (double g : gains)
                {
                    assertTrue(same(BiquadCoeffs.tilt(fs, f, g), BiquadDesign.makeTilt(fs, f, g)));
                    for (double slope : slopes)
                    {
                        assertTrue(same(BiquadCoeffs.lowShelf(fs, f, g, slope), BiquadDesign.makeLowShelf(fs, f, g, slope)));
                        assertTrue(same(BiquadCoeffs.highShelf(fs, f, g, slope), BiquadDesign.makeHighShelf(fs, f, g, slope)));
                    }
                }
            }
        assertTrue(same(BiquadCoeffs.lowPass(48000.0, 1000.0, BiquadCoeffs.BUTTERWORTH_Q), BiquadDesign.makeLowPass(48000.0, 1000.0)));
    }

    @Test
    @DisplayName("Non-finite parameters: the current designs propagate NaN and clamp +Inf to 0.499 fs")
    void nonFiniteParametersFollowTheCurrentClamp()
    {
        final double nan = Double.NaN, inf = Double.POSITIVE_INFINITY;
        // A NaN frequency propagates (the older factory pinned it to 1 Hz).
        assertTrue(Double.isNaN(BiquadDesign.makeLowPass(48000.0, nan, 0.7).b0));
        assertFalse(Double.isNaN(BiquadCoeffs.lowPass(48000.0, nan, 0.7).b0));
        // +Inf is clamped to the top of the range (the older factory used 1 Hz).
        assertTrue(same(BiquadDesign.makeLowPass(48000.0, 0.499 * 48000.0, 0.7), BiquadDesign.makeLowPass(48000.0, inf, 0.7)));
        assertTrue(same(BiquadCoeffs.lowPass(48000.0, 1.0, 0.7), BiquadCoeffs.lowPass(48000.0, inf, 0.7)));
        // A NaN gain in the matched peak propagates (the older factory returned the identity).
        assertTrue(Double.isNaN(BiquadDesign.makePeakMatched(48000.0, 1000.0, 1.0, nan).a1));
        assertTrue(same(BiquadCoeffs.IDENTITY, BiquadCoeffs.peakMatched(48000.0, 1000.0, 1.0, nan)));
        // The matched shelves fall back to the identity for a NaN gain.
        assertTrue(same(BiquadCoeffs.IDENTITY, BiquadDesign.makeLowShelfMatched(48000.0, 1000.0, nan)));
    }
}
