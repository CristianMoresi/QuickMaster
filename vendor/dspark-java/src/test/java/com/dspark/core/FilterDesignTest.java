package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dspark.core.PortSignals.same;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The analog prototypes and digital sections of {@link FilterDesign}. */
class FilterDesignTest
{
    private static final double PI = 3.14159265358979323846;

    /** Worst distance in dB between a biquad and its prototype from 20 Hz to 20 kHz: all, and where the prototype is above -60 dB. */
    private static double[] designError(AnalogSection h, BiquadCoeffs c, double fs)
    {
        final double top = Math.min(20000.0, 0.499 * fs);
        double all = 0.0, heard = 0.0;
        for (int i = 0; i < 240; ++i)
        {
            final double f = 20.0 * Math.pow(top / 20.0, i / 239.0);
            final double a = h.magnitudeDb(f);
            final double d = 20.0 * Math.log10(Math.max(BiquadDesign.getMagnitude(c, f, fs), 1e-15));
            if (a < -100.0 && d < -100.0) continue;
            all = Math.max(all, Math.abs(a - d));
            if (a >= -60.0) heard = Math.max(heard, Math.abs(a - d));
        }
        return new double[] { all, heard };
    }

    private static boolean designStable(BiquadCoeffs c)
    {
        return Double.isFinite(c.b0) && Double.isFinite(c.b1) && Double.isFinite(c.b2)
            && Math.abs(c.a2) < 1.0 && Math.abs(c.a1) < 1.0 + c.a2;
    }

    /** A cascade as biquads against its prototype where the prototype is above -60 dB. */
    private static double cascadeError(AnalogCascade c, double fs, int[] unstable)
    {
        final BiquadCoeffs[] sections = new BiquadCoeffs[AnalogCascade.MAX_SECTIONS];
        final int n = FilterDesign.digital(c, fs, sections, AnalogCascade.MAX_SECTIONS);
        if (unstable != null)
            for (int i = 0; i < n; ++i) unstable[0] += designStable(sections[i]) ? 0 : 1;
        final double top = Math.min(20000.0, 0.499 * fs);
        double worst = 0.0;
        for (int k = 0; k <= 400; ++k)
        {
            final double hz = 20.0 * Math.pow(top / 20.0, k / 400.0);
            final double want = c.magnitudeDb(hz);
            if (want > -60.0) worst = Math.max(worst, Math.abs(FilterDesign.magnitudeDb(sections, n, hz, fs) - want));
        }
        return worst;
    }

    /** The curve a cut of a real order follows (corner at 1). */
    private static double idealCutDb(double order, double w)
    {
        if (order < 1.0) return -10.0 * order * Math.log10(1.0 + w * w);
        return -10.0 * Math.log10(1.0 + Math.pow(w, 2.0 * order));
    }

    private static double log2(double x)
    {
        return Math.log(x) / Math.log(2.0);
    }

    @Test
    @DisplayName("Digital sections follow the analog magnitude up to Nyquist, ahead of the closed forms and the bilinear transform")
    void sectionsFollowTheAnalogMagnitudeUpToNyquist()
    {
        final double[] freqs = { 30, 100, 300, 1000, 3000, 5000, 8000, 10000, 12000, 15000, 18000, 20000 };
        final double[] qs = { 0.3, 0.5, 0.7071, 1, 2, 4, 8, 16 };
        final double[] gains = { -24, -18, -6, -1, -0.1, 0.1, 1, 6, 18, 24 };
        final double[] slopes = { 0.3, 0.5, 1.0 };
        // fs, then the allowance of bell, shelf, lowPass, highPass, bandPass, notch, first.
        final double[][] allowed = {
            { 44100.0, 0.62, 0.24, 0.42, 2.40, 0.80, 3.20, 0.50 },
            { 48000.0, 0.30, 0.13, 0.20, 1.50, 0.45, 0.50, 0.33 },
            { 96000.0, 0.03, 0.03, 0.02, 0.09, 0.04, 0.04, 0.04 },
        };
        for (double[] rate : allowed)
        {
            final double fs = rate[0];
            final FilterDesign.Band band = new FilterDesign.Band(fs);
            final double[] now = new double[7], closed = new double[7], plain = new double[7];
            final int[] unstable = { 0 };
            final java.util.function.Function<AnalogSection, BiquadCoeffs> design = h -> {
                final BiquadCoeffs c = FilterDesign.digital(h, band);
                if (!designStable(c)) ++unstable[0];
                return c;
            };
            for (double f : freqs)
            {
                if (f > 0.46 * fs) continue;
                for (double q : qs)
                {
                    for (double g : gains)
                    {
                        final AnalogSection h = AnalogSection.peak(f, q, g);
                        now[0] = Math.max(now[0], designError(h, design.apply(h), fs)[0]);
                        closed[0] = Math.max(closed[0], designError(h, BiquadDesign.makePeakMatched(fs, f, q, g), fs)[0]);
                        plain[0] = Math.max(plain[0], designError(h, FilterDesign.bilinear(h, fs, f), fs)[0]);
                    }
                    AnalogSection h = AnalogSection.lowPass(f, q);
                    now[2] = Math.max(now[2], designError(h, design.apply(h), fs)[1]);
                    closed[2] = Math.max(closed[2], designError(h, BiquadDesign.makeLowPassMatched(fs, f, q), fs)[1]);
                    h = AnalogSection.highPass(f, q);
                    now[3] = Math.max(now[3], designError(h, design.apply(h), fs)[0]);
                    closed[3] = Math.max(closed[3], designError(h, BiquadDesign.makeHighPassMatched(fs, f, q), fs)[0]);
                    h = AnalogSection.bandPass(f, q);
                    now[4] = Math.max(now[4], designError(h, design.apply(h), fs)[0]);
                    closed[4] = Math.max(closed[4], designError(h, BiquadDesign.makeBandPassMatched(fs, f, q), fs)[0]);
                    h = AnalogSection.notch(f, q);
                    now[5] = Math.max(now[5], designError(h, design.apply(h), fs)[0]);
                    plain[5] = Math.max(plain[5], designError(h, FilterDesign.bilinear(h, fs, f), fs)[0]);
                }
                for (double g : gains)
                {
                    for (double slope : slopes)
                    {
                        AnalogSection h = AnalogSection.lowShelf(f, g, slope);
                        now[1] = Math.max(now[1], designError(h, design.apply(h), fs)[0]);
                        h = AnalogSection.highShelf(f, g, slope);
                        now[1] = Math.max(now[1], designError(h, design.apply(h), fs)[0]);
                    }
                    closed[1] = Math.max(closed[1], designError(AnalogSection.lowShelf(f, g), BiquadDesign.makeLowShelfMatched(fs, f, g), fs)[0]);
                    closed[1] = Math.max(closed[1], designError(AnalogSection.highShelf(f, g), BiquadDesign.makeHighShelfMatched(fs, f, g), fs)[0]);
                    AnalogSection h = AnalogSection.firstOrderLowShelf(f, g);
                    now[6] = Math.max(now[6], designError(h, design.apply(h), fs)[0]);
                    h = AnalogSection.firstOrderHighShelf(f, g);
                    now[6] = Math.max(now[6], designError(h, design.apply(h), fs)[0]);
                    h = AnalogSection.tilt(f, g);
                    now[6] = Math.max(now[6], designError(h, design.apply(h), fs)[0]);
                }
                AnalogSection h = AnalogSection.firstOrderLowPass(f);
                now[6] = Math.max(now[6], designError(h, design.apply(h), fs)[0]);
                h = AnalogSection.firstOrderHighPass(f);
                now[6] = Math.max(now[6], designError(h, design.apply(h), fs)[0]);
            }
            assertEquals(0, unstable[0]);
            for (int k = 0; k < 7; ++k)
                assertTrue(now[k] < rate[k + 1], "fs " + fs + " kind " + k + ": " + now[k] + " allowed " + rate[k + 1]);
            assertTrue(4.0 * now[0] < closed[0]);
            assertTrue(2.0 * now[1] < closed[1]);
            assertTrue(2.0 * now[2] < closed[2]);
            assertTrue(2.0 * now[3] < closed[3]);
            assertTrue(2.0 * now[4] < closed[4]);
            assertTrue(4.0 * now[0] < plain[0]);
            assertTrue(2.0 * now[5] < plain[5]);
        }
    }

    @Test
    @DisplayName("Sections keep what defines them; prototypes that are not filters come back finite")
    void sectionsKeepWhatDefinesThem()
    {
        for (double fs : new double[] { 44100.0, 96000.0 })
        {
            final FilterDesign.Band band = new FilterDesign.Band(fs);
            for (double f : new double[] { 40.0, 1000.0, 9000.0, 17000.0 })
                for (double q : new double[] { 0.4, 0.7071, 6.0 })
                {
                    BiquadCoeffs c = FilterDesign.digital(AnalogSection.highPass(f, q), band);
                    assertTrue(c.b1 == -2.0 * c.b0 && c.b2 == c.b0 && c.b0 > 0.0);
                    c = FilterDesign.digital(AnalogSection.bandPass(f, q), band);
                    assertTrue(Math.abs(c.b0 + c.b1 + c.b2) < 1e-12 * (Math.abs(c.b0) + Math.abs(c.b1) + Math.abs(c.b2)));
                    c = FilterDesign.digital(AnalogSection.firstOrderHighPass(f), band);
                    assertTrue(c.b1 == -c.b0 && c.b2 == 0.0 && c.a2 == 0.0);
                    c = FilterDesign.digital(AnalogSection.notch(f, q), band);
                    assertTrue(c.b2 == c.b0);
                    assertEquals(-2.0 * Math.cos(2.0 * PI * f / fs), c.b1 / c.b0, 1e-12);
                    assertEquals(1.0, (c.b0 + c.b1 + c.b2) / (1.0 + c.a1 + c.a2), 1e-9);
                    c = FilterDesign.digital(AnalogSection.allPass(f, q), band);
                    assertTrue(c.b0 == c.a2 && c.b1 == c.a1 && c.b2 == 1.0);
                    assertTrue(designStable(c));
                    for (double probe : new double[] { 30.0, 700.0, 5000.0, 19000.0 })
                        assertEquals(1.0, BiquadDesign.getMagnitude(c, probe, fs), 1e-9);
                    c = FilterDesign.digital(AnalogSection.firstOrderLowShelf(f, 9.0), band);
                    assertTrue(c.b2 == 0.0 && c.a2 == 0.0 && designStable(c));
                }
        }

        final FilterDesign.Band band = new FilterDesign.Band(48000.0);
        for (double bad : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
        {
            AnalogSection h = AnalogSection.peak(1000.0, 1.0, 6.0);
            h.a1 = bad;
            assertTrue(same(BiquadCoeffs.IDENTITY, FilterDesign.digital(h, band)));
            h = AnalogSection.peak(1000.0, 1.0, 6.0);
            h.b0 = bad;
            assertTrue(same(BiquadCoeffs.IDENTITY, FilterDesign.digital(h, band)));
        }
        for (AnalogSection h : new AnalogSection[] {
                AnalogSection.make(0, 0, 0, 1, 1000.0, 1e6),
                AnalogSection.peak(1e-3, 1e-3, 120.0), AnalogSection.peak(1e6, 1e3, -120.0),
                AnalogSection.lowPass(23999.0, 100.0), AnalogSection.highPass(0.5, 0.01),
                AnalogSection.notch(23990.0, 30.0), AnalogSection.lowShelf(30000.0, 24.0, 0.01) })
        {
            final BiquadCoeffs c = FilterDesign.digital(h, band);
            assertTrue(Double.isFinite(c.b0) && Double.isFinite(c.b1) && Double.isFinite(c.b2));
            assertTrue(Double.isFinite(c.a1) && Double.isFinite(c.a2));
            assertTrue(Math.abs(c.a2) <= 1.0 && Math.abs(c.a1) <= 1.0 + c.a2);
        }
    }

    @Test
    @DisplayName("A design moves smoothly with its gain, frequency and Q")
    void sectionsMoveSmoothlyWithTheirParameters()
    {
        final double fs = 48000.0;
        final FilterDesign.Band band = new FilterDesign.Band(fs);
        final java.util.function.BiFunction<BiquadCoeffs, BiquadCoeffs, Double> distance = (a, b) -> {
            double d = 0.0;
            for (int i = 0; i < 90; ++i)
            {
                final double f = 20.0 * Math.pow(1000.0, i / 89.0);
                d = Math.max(d, Math.abs(20.0 * Math.log10(Math.max(BiquadDesign.getMagnitude(a, f, fs), 1e-15))
                                         - 20.0 * Math.log10(Math.max(BiquadDesign.getMagnitude(b, f, fs), 1e-15))));
            }
            return d;
        };
        double byGain = 0.0, byFrequency = 0.0, byQ = 0.0;
        for (double f : new double[] { 3000.0, 14000.0, 19000.0 })
            for (double q : new double[] { 0.5, 8.0 })
            {
                BiquadCoeffs last = FilterDesign.digital(AnalogSection.peak(f, q, -24.0), band);
                for (int i = 1; i <= 2400; ++i)
                {
                    final BiquadCoeffs now = FilterDesign.digital(AnalogSection.peak(f, q, -24.0 + 0.02 * i), band);
                    byGain = Math.max(byGain, distance.apply(last, now));
                    last = now;
                }
                last = FilterDesign.digital(AnalogSection.peak(f * 0.5, q, 12.0), band);
                for (int i = 1; i <= 750; ++i)
                {
                    final double hz = Math.min(f * 0.5 * Math.pow(2.0, 0.002 * i), 22000.0);
                    final BiquadCoeffs now = FilterDesign.digital(AnalogSection.peak(hz, q, 12.0), band);
                    byFrequency = Math.max(byFrequency, distance.apply(last, now));
                    last = now;
                }
                last = FilterDesign.digital(AnalogSection.peak(f, 0.3, 12.0), band);
                for (int i = 1; i <= 1500; ++i)
                {
                    final BiquadCoeffs now = FilterDesign.digital(AnalogSection.peak(f, 0.3 * Math.pow(2.0, 0.004 * i), 12.0), band);
                    byQ = Math.max(byQ, distance.apply(last, now));
                    last = now;
                }
            }
        assertTrue(byGain < 0.08, "byGain " + byGain);
        assertTrue(byFrequency < 0.30, "byFrequency " + byFrequency);
        assertTrue(byQ < 0.04, "byQ " + byQ);
    }

    @Test
    @DisplayName("Band and one-off designs agree at every rate and hold the gain at DC")
    void bandAndOneOffDesignsAgreeAtEveryRate()
    {
        final FilterDesign.Band band = new FilterDesign.Band();
        assertEquals(48000.0, band.getSampleRate(), 0.0);
        band.prepare(Double.NaN);
        assertEquals(48000.0, band.getSampleRate(), 0.0);
        band.prepare(-1.0);
        assertEquals(48000.0, band.getSampleRate(), 0.0);

        for (double fs : new double[] { 8000.0, 22050.0, 44100.0, 192000.0, 768000.0 })
        {
            band.prepare(fs);
            assertEquals(fs, band.getSampleRate(), 0.0);
            assertTrue(band.getLowHz() > 0.0 && band.getLowHz() <= 20.0);
            assertTrue(band.getHighHz() <= 0.4537 * fs && band.getHighHz() >= Math.min(20000.0, 0.42 * fs));
            for (AnalogSection h : new AnalogSection[] {
                    AnalogSection.peak(0.2 * fs, 3.0, 9.0), AnalogSection.lowPass(0.1 * fs, 1.2),
                    AnalogSection.highShelf(0.3 * fs, -12.0), AnalogSection.notch(0.05 * fs, 4.0),
                    AnalogSection.peak(25.0, 0.7, -6.0), AnalogSection.firstOrderLowPass(0.02 * fs) })
            {
                final BiquadCoeffs a = FilterDesign.digital(h, band), b = FilterDesign.digital(h, fs);
                assertTrue(same(a, b));
                assertTrue(designStable(a));
                double worst = 0.0;
                for (int i = 0; i <= 60; ++i)
                {
                    final double f = band.getLowHz() * Math.pow(Math.min(band.getHighHz(), 20000.0) / band.getLowHz(), i / 60.0);
                    final double want = h.magnitudeDb(f);
                    if (want > -60.0)
                        worst = Math.max(worst, Math.abs(20.0 * Math.log10(Math.max(BiquadDesign.getMagnitude(a, f, fs), 1e-15)) - want));
                }
                assertTrue(worst < 0.5, "fs " + fs + " worst " + worst);
            }
            for (AnalogSection h : new AnalogSection[] {
                    AnalogSection.peak(25.0, 0.7, -6.0), AnalogSection.lowPass(0.1 * fs, 1.2), AnalogSection.highShelf(0.3 * fs, -12.0),
                    AnalogSection.lowShelf(0.02 * fs, 9.0, 0.5), AnalogSection.firstOrderLowShelf(0.2 * fs, -6.0) })
            {
                final BiquadCoeffs c = FilterDesign.digital(h, band);
                assertEquals(Math.abs(h.b0 / h.a0), (c.b0 + c.b1 + c.b2) / (1.0 + c.a1 + c.a2), 1e-6 * Math.abs(h.b0 / h.a0));
            }
        }

        final AnalogCascade wall = FilterDesign.ellipticHighCut(5000.0);
        final BiquadCoeffs[] some = new BiquadCoeffs[4], all = new BiquadCoeffs[AnalogCascade.MAX_SECTIONS];
        assertEquals(4, FilterDesign.digital(wall, 48000.0, some, 4));
        assertEquals(6, FilterDesign.digital(wall, new FilterDesign.Band(48000.0), all, AnalogCascade.MAX_SECTIONS));
        for (int i = 0; i < 4; ++i) assertTrue(some[i].b0 == all[i].b0 && some[i].a1 == all[i].a1 && some[i].a2 == all[i].a2);
        assertEquals(0, FilterDesign.digital(new AnalogCascade(), 48000.0, all, AnalogCascade.MAX_SECTIONS));
        // The offset form writes after what is there.
        final BiquadCoeffs[] packed = new BiquadCoeffs[8];
        int count = FilterDesign.digital(FilterDesign.lowCut(80.0, 12.0, 1.4), new FilterDesign.Band(48000.0), packed, 0, 8);
        count += FilterDesign.digital(wall, new FilterDesign.Band(48000.0), packed, count, 8 - count);
        assertEquals(7, count);
        assertTrue(same(all[5], packed[6]));
    }

    @Test
    @DisplayName("The bilinear transform maps its exact frequency exactly (prewarping)")
    void bilinearPrewarpsItsExactFrequency()
    {
        final double fs = 48000.0;
        for (double f : new double[] { 100.0, 5000.0, 15000.0, 21000.0 })
        {
            final AnalogSection lp = AnalogSection.lowPass(f, 2.0);
            final BiquadCoeffs c = FilterDesign.bilinear(lp, fs, f);
            assertEquals(lp.magnitudeDb(f), 20.0 * Math.log10(BiquadDesign.getMagnitude(c, f, fs)), 1e-9);
            // The bilinear image of the prototype at the prewarped frequency, everywhere.
            final double k = 2.0 * PI * f / Math.tan(PI * f / fs);
            for (double probe : new double[] { 20.0, 900.0, 7000.0, 19000.0 })
            {
                final double analog = k * Math.tan(PI * probe / fs) / (2.0 * PI);
                assertEquals(lp.magnitudeDb(analog), 20.0 * Math.log10(BiquadDesign.getMagnitude(c, probe, fs)), 1e-8);
            }
            final AnalogSection fo = AnalogSection.firstOrderLowPass(f);
            assertEquals(-3.0103, 20.0 * Math.log10(BiquadDesign.getMagnitude(FilterDesign.bilinear(fo, fs, f), f, fs)), 1e-4);
        }
        // From 0.49 of the rate on, the plain transform (K = 2 fs).
        final AnalogSection h = AnalogSection.peak(3000.0, 1.0, 6.0);
        assertTrue(same(FilterDesign.bilinear(h, fs, 0.0), FilterDesign.bilinear(h, fs, 0.49 * fs)));
        final double kPlain = 2.0 * fs;
        final double analog = kPlain * Math.tan(PI * 3000.0 / fs) / (2.0 * PI);
        assertEquals(h.magnitudeDb(analog), 20.0 * Math.log10(BiquadDesign.getMagnitude(FilterDesign.bilinear(h, fs, 0.0), 3000.0, fs)), 1e-9);
        // A first-order section comes out as a biquad with a common (1 + z^-1)
        // factor: the response of makeFirstOrderLowPass.
        final BiquadCoeffs rc = FilterDesign.bilinear(AnalogSection.firstOrderLowPass(1000.0), fs, 1000.0);
        final BiquadCoeffs cookbook = BiquadDesign.makeFirstOrderLowPass(fs, 1000.0);
        assertEquals(0.0, rc.b0 - rc.b1 + rc.b2, 1e-15);
        assertEquals(0.0, 1.0 - rc.a1 + rc.a2, 1e-15);
        for (double probe : new double[] { 20.0, 1000.0, 9000.0, 23000.0 })
            assertEquals(BiquadDesign.getMagnitude(cookbook, probe, fs), BiquadDesign.getMagnitude(rc, probe, fs), 1e-12);
        // A section with a0 mapping to zero gives the identity.
        assertTrue(same(BiquadCoeffs.IDENTITY, FilterDesign.bilinear(AnalogSection.make(0, 0, 1, 0, 0, 0), fs, 1000.0)));
    }

    @Test
    @DisplayName("Whole slopes are Butterworth filters; the low cut is the high cut mirrored; q acts on the sharpest pair")
    void cutOfAWholeOrderIsAButterworthFilter()
    {
        for (int order = 1; order <= 16; ++order)
        {
            final AnalogCascade high = FilterDesign.highCut(1000.0, 6.0 * order), low = FilterDesign.lowCut(1000.0, 6.0 * order);
            assertEquals((order + 1) / 2, high.count);
            assertEquals((order + 1) / 2, low.count);
            double worst = 0.0, mirror = 0.0;
            for (int i = 0; i <= 200; ++i)
            {
                final double w = Math.pow(10.0, -2.0 + 3.0 * i / 200.0);
                final double ideal = idealCutDb(order, w);
                if (ideal > -200.0) worst = Math.max(worst, Math.abs(high.magnitudeDb(1000.0 * w) - ideal));
                if (ideal > -200.0) mirror = Math.max(mirror, Math.abs(low.magnitudeDb(1000.0 / w) - high.magnitudeDb(1000.0 * w)));
            }
            assertTrue(worst < 1e-8, "order " + order + " worst " + worst);
            assertTrue(mirror < 1e-8);
        }
        for (double slope : new double[] { 12.0, 24.0, 48.0, 96.0 })
        {
            assertEquals(20.0 * Math.log10(4.0), FilterDesign.highCut(1000.0, slope, 4.0).magnitudeDb(1000.0), 1e-9);
            assertEquals(20.0 * Math.log10(0.5), FilterDesign.lowCut(1000.0, slope, 0.5).magnitudeDb(1000.0), 1e-9);
        }
        assertEquals(-10.0 * Math.log10(2.0), FilterDesign.highCut(1000.0, 6.0, 4.0).magnitudeDb(1000.0), 1e-9);
        assertEquals(FilterDesign.highCut(1000.0, 3.0).magnitudeDb(1000.0), FilterDesign.highCut(1000.0, 3.0, 4.0).magnitudeDb(1000.0), 1e-12);
        assertEquals(0, FilterDesign.highCut(1000.0, 0.0).count);
        assertEquals(0, FilterDesign.lowCut(1000.0, -3.0).count);
        assertEquals(0, FilterDesign.highCut(1000.0, Double.NaN).count);
        assertEquals(FilterDesign.highCut(1000.0, 96.0).magnitudeDb(2000.0), FilterDesign.highCut(1000.0, 500.0).magnitudeDb(2000.0), 1e-9);
    }

    @Test
    @DisplayName("Between whole slopes a cut keeps its corner and its ideal curve, and moves without a jump")
    void cutOfARealOrderFollowsItsIdealCurve()
    {
        final double[][] cases = { { 7.5, 1.0 }, { 9.0, 1.0 }, { 10.5, 0.6 }, { 15.0, 0.7 }, { 21.0, 0.1 }, { 27.0, 0.15 },
                                   { 44.0, 0.02 }, { 70.0, 0.02 }, { 93.0, 0.02 } };
        for (double[] item : cases)
        {
            final double order = item[0] / 6.0;
            final AnalogCascade c = FilterDesign.highCut(1000.0, item[0]);
            assertEquals(-10.0 * Math.log10(2.0), c.magnitudeDb(1000.0), 1e-9);
            final double deep = Math.pow(Math.pow(10.0, 3.6) - 1.0, 0.5 / order);
            assertEquals(-36.0, c.magnitudeDb(1000.0 * deep), 1e-6);
            double worst = 0.0;
            for (int i = 0; i <= 600; ++i)
            {
                final double w = Math.pow(10.0, -2.0 + 5.0 * i / 600.0);
                final double ideal = idealCutDb(order, w);
                if (ideal > -36.0) worst = Math.max(worst, Math.abs(c.magnitudeDb(1000.0 * w) - ideal));
            }
            assertTrue(worst < item[1], "slope " + item[0] + " worst " + worst);
        }

        for (double slope : new double[] { 0.6, 1.5, 3.0, 4.5, 5.7 })
        {
            final double order = slope / 6.0;
            final AnalogCascade c = FilterDesign.highCut(1000.0, slope);
            assertEquals(3, c.count);
            double worst = 0.0;
            for (int i = 0; i <= 500; ++i)
            {
                final double w = Math.pow(10.0, -2.0 + 5.0 * i / 500.0);
                worst = Math.max(worst, Math.abs(c.magnitudeDb(1000.0 * w) - idealCutDb(order, w)));
            }
            assertTrue(worst < 0.2);
        }

        double worst = 0.0, rise = 0.0;
        AnalogCascade last = FilterDesign.highCut(1000.0, 0.01);
        for (int i = 2; i <= 9600; ++i)
        {
            final AnalogCascade now = FilterDesign.highCut(1000.0, 0.01 * i);
            for (int k = 0; k <= 60; ++k)
            {
                final double hz = 20.0 * Math.pow(1000.0, k / 60.0);
                final double a = last.magnitudeDb(hz);
                if (a > -60.0) worst = Math.max(worst, Math.abs(a - now.magnitudeDb(hz)));
            }
            double lowest = 1e9;
            for (int k = 0; k <= 120; ++k)
            {
                final double level = now.magnitudeDb(10.0 * Math.pow(10.0, 4.0 * k / 120.0));
                rise = Math.max(rise, level - lowest);
                lowest = Math.min(lowest, level);
            }
            last = now;
        }
        assertTrue(worst < 0.2, "worst " + worst);
        assertTrue(rise < 0.05, "rise " + rise);
        for (int order = 1; order <= 16; ++order)
        {
            final AnalogCascade c = FilterDesign.highCut(1000.0, 6.0 * order);
            double before = c.magnitudeDb(10.0);
            boolean rises = false;
            for (int k = 1; k <= 400; ++k)
            {
                final double level = c.magnitudeDb(10.0 * Math.pow(10.0, 4.0 * k / 400.0));
                rises = rises || level > before + 1e-9;
                before = level;
            }
            assertFalse(rises);
        }
    }

    @Test
    @DisplayName("Bells and shelves of any order keep their gains, never overshoot and undo each other")
    void bellsAndShelvesOfAnyOrderKeepTheirGains()
    {
        for (int order : new int[] { 1, 2, 3, 4, 7, 8, 16 })
            for (double gain : new double[] { 12.0, -12.0, 3.0 })
            {
                final AnalogCascade low = FilterDesign.lowShelf(1000.0, gain, order), high = FilterDesign.highShelf(1000.0, gain, order);
                final AnalogCascade undo = FilterDesign.lowShelf(1000.0, -gain, order);
                assertEquals((order + 1) / 2, low.count);
                assertEquals(gain, low.magnitudeDb(0.01), 1e-6);
                assertEquals(0.5 * gain, low.magnitudeDb(1000.0), 1e-9);
                assertEquals(0.0, low.magnitudeDb(1e7), 1e-6);
                assertEquals(0.0, high.magnitudeDb(0.01), 1e-6);
                assertEquals(0.5 * gain, high.magnitudeDb(1000.0), 1e-9);
                assertEquals(gain, high.magnitudeDb(1e7), 1e-6);
                double inverse = 0.0, beyond = 0.0, mirror = 0.0;
                for (int k = 0; k <= 200; ++k)
                {
                    final double ratio = Math.pow(10.0, -2.0 + 4.0 * k / 200.0);
                    final double level = low.magnitudeDb(1000.0 * ratio);
                    inverse = Math.max(inverse, Math.abs(level + undo.magnitudeDb(1000.0 * ratio)));
                    beyond = Math.max(beyond, gain > 0.0 ? Math.max(level - gain, -level) : Math.max(gain - level, level));
                    mirror = Math.max(mirror, Math.abs(level - high.magnitudeDb(1000.0 / ratio)));
                }
                assertTrue(inverse < 1e-9);
                assertTrue(beyond < 1e-9);
                assertTrue(mirror < 1e-9);
            }
        assertEquals(9.264, FilterDesign.lowShelf(1000.0, 12.0, 1).magnitudeDb(500.0), 0.01);
        assertEquals(11.937, FilterDesign.lowShelf(1000.0, 12.0, 4).magnitudeDb(500.0), 0.01);
        assertTrue(FilterDesign.lowShelf(1000.0, 12.0, 8).magnitudeDb(500.0) > 11.999);

        double flatBefore = 0.0;
        for (int order : new int[] { 1, 2, 4, 8, 16 })
            for (double q : new double[] { 0.5, 2.0, 8.0 })
            {
                final AnalogCascade up = FilterDesign.bell(1000.0, q, 12.0, order), down = FilterDesign.bell(1000.0, q, -12.0, order);
                final double half = 0.5 / q, root = Math.sqrt(1.0 + half * half);
                assertEquals(order, up.count);
                assertEquals(12.0, up.magnitudeDb(1000.0), 1e-9);
                assertEquals(6.0, up.magnitudeDb(1000.0 * (root - half)), 1e-9);
                assertEquals(6.0, up.magnitudeDb(1000.0 * (root + half)), 1e-9);
                final AnalogSection one = AnalogSection.peak(1000.0, q, 12.0);
                double inverse = 0.0, top = -1e9, flat = 0.0, single = 0.0;
                for (int k = 0; k <= 1000; ++k)
                {
                    final double hz = 10.0 * Math.pow(10000.0, k / 1000.0);
                    final double level = up.magnitudeDb(hz);
                    inverse = Math.max(inverse, Math.abs(level + down.magnitudeDb(hz)));
                    top = Math.max(top, level);
                    if (level > 11.9) flat = Math.max(flat, Math.abs(log2(hz / 1000.0)));
                    single = Math.max(single, Math.abs(level - one.magnitudeDb(hz)));
                }
                assertTrue(inverse < 1e-9);
                assertTrue(top < 12.0 + 1e-9);
                if (order == 1) assertTrue(single < 1e-9);
                final double share = flat / log2(root + half);
                if (q == 2.0)
                {
                    assertTrue(share > flatBefore);
                    flatBefore = share;
                    if (order == 1) assertTrue(share < 0.1);
                    if (order == 16) assertTrue(share > 0.8);
                }
            }
    }

    @Test
    @DisplayName("Band-pass, band-stop and all-pass of any order; all-pass sections turn as they should")
    void bandFiltersAndAllPassOfAnyOrder()
    {
        final double q = 2.0, half = 0.5 / q, root = Math.sqrt(1.0 + half * half);
        for (int order : new int[] { 1, 2, 3, 4, 8, 16 })
        {
            final AnalogCascade pass = FilterDesign.bandPass(1000.0, q, order), stop = FilterDesign.bandStop(1000.0, q, order);
            assertEquals(order, pass.count);
            assertEquals(order, stop.count);
            assertEquals(0.0, pass.magnitudeDb(1000.0), 1e-9);
            assertEquals(-10.0 * Math.log10(2.0), pass.magnitudeDb(1000.0 * (root - half)), 1e-9);
            assertEquals(-10.0 * Math.log10(2.0), pass.magnitudeDb(1000.0 * (root + half)), 1e-9);
            assertEquals(-17.5 * order, pass.magnitudeDb(250.0), 0.1 * order);
            assertEquals(pass.magnitudeDb(250.0), pass.magnitudeDb(4000.0), 1e-9);
            assertTrue(stop.magnitudeDb(1000.0) < -250.0);
            assertEquals(-10.0 * Math.log10(2.0), stop.magnitudeDb(1000.0 * (root - half)), 1e-9);
            assertEquals(-10.0 * Math.log10(2.0), stop.magnitudeDb(1000.0 * (root + half)), 1e-9);
            assertEquals(0.0, stop.magnitudeDb(0.01), 1e-6);
            assertEquals(0.0, stop.magnitudeDb(1e7), 1e-6);

            final AnalogCascade all = FilterDesign.allPass(1000.0, 2.0, order);
            assertEquals((order + 1) / 2, all.count);
            final BiquadCoeffs[] sections = new BiquadCoeffs[AnalogCascade.MAX_SECTIONS];
            final int n = FilterDesign.digital(all, 48000.0, sections, AnalogCascade.MAX_SECTIONS);
            assertEquals(all.count, n);
            double turn = 0.0;
            for (int i = 0; i < n; ++i)
            {
                final BiquadCoeffs s = sections[i];
                assertTrue(designStable(s));
                final double atDc = (s.b0 + s.b1 + s.b2) / (1.0 + s.a1 + s.a2);
                final double atNyquist = (s.b0 - s.b1 + s.b2) / (1.0 - s.a1 + s.a2);
                assertEquals(1.0, atDc, 1e-9);
                assertEquals(1.0, Math.abs(atNyquist), 1e-9);
                turn += s.a2 == 0.0 ? 1.0 : 2.0;
                assertEquals(s.a2 == 0.0 ? -1.0 : 1.0, atNyquist, 1e-9);
            }
            assertEquals((double) order, turn, 0.0);
            for (double hz : new double[] { 20.0, 300.0, 1000.0, 4000.0, 20000.0 })
            {
                assertEquals(0.0, all.magnitudeDb(hz), 1e-9);
                assertEquals(0.0, FilterDesign.magnitudeDb(sections, n, hz, 48000.0), 1e-9);
            }
        }
        final AnalogSection usual = AnalogSection.allPass(1000.0, 3.0);
        final AnalogCascade two = FilterDesign.allPass(1000.0, 3.0, 2);
        assertEquals(usual.a1, two.sections[0].a1, 1e-9 * usual.a1);
        assertEquals(usual.a0, two.sections[0].a0, 1e-9 * usual.a0);
    }

    @Test
    @DisplayName("The tilt is a straight line across the band through 0 dB at its pivot")
    void tiltIsAStraightLineAcrossTheBand()
    {
        for (double slope : new double[] { -6.0, -4.5, -3.0, -1.5, -0.3, 0.3, 1.5, 3.0, 4.5, 6.0 })
            for (double pivot : new double[] { 100.0, 1000.0, 6000.0 })
            {
                final AnalogCascade c = FilterDesign.tilt(pivot, slope);
                assertEquals(4, c.count);
                assertEquals(0.0, c.magnitudeDb(pivot), 1e-9);
                double worst = 0.0;
                for (int k = 0; k <= 300; ++k)
                {
                    final double hz = 20.0 * Math.pow(1000.0, k / 300.0);
                    worst = Math.max(worst, Math.abs(c.magnitudeDb(hz) - slope * log2(hz / pivot)));
                }
                assertTrue(worst < 0.03 + 0.02 * Math.abs(slope));
            }
        final AnalogSection single = AnalogSection.tilt(632.4555, 30.0);
        double off = 0.0;
        for (int k = 0; k <= 300; ++k)
        {
            final double hz = 20.0 * Math.pow(1000.0, k / 300.0);
            off = Math.max(off, Math.abs(single.magnitudeDb(hz) - 3.0103 * log2(hz / 632.4555)));
        }
        assertTrue(off > 1.0);
        assertEquals(0, FilterDesign.tilt(1000.0, 0.0).count);
        assertEquals(0, FilterDesign.tilt(1000.0, Double.POSITIVE_INFINITY).count);
        assertEquals(FilterDesign.tilt(1000.0, 6.0206).magnitudeDb(4000.0), FilterDesign.tilt(1000.0, 40.0).magnitudeDb(4000.0), 1e-3);
        final int[] unstable = { 0 };
        assertTrue(cascadeError(FilterDesign.tilt(1000.0, 3.0), 48000.0, unstable) < 0.06);
        assertTrue(cascadeError(FilterDesign.tilt(1000.0, -6.0), 48000.0, unstable) < 0.15);
        assertEquals(0, unstable[0]);
    }

    @Test
    @DisplayName("The slope of a tilt stays within a ceiling at the end that rises")
    void tiltSlopeStaysWithinACeiling()
    {
        assertEquals(6.0, FilterDesign.tiltSlopeWithin(632.0, 6.0, 36.0), 0.0);
        assertEquals(-6.0, FilterDesign.tiltSlopeWithin(632.0, -6.0, 36.0), 0.0);
        assertEquals(36.0 / log2(1000.0), FilterDesign.tiltSlopeWithin(20.0, 6.0, 36.0), 1e-9);
        assertEquals(-6.0, FilterDesign.tiltSlopeWithin(20.0, -6.0, 36.0), 0.0);
        assertEquals(6.0, FilterDesign.tiltSlopeWithin(20000.0, 6.0, 36.0), 0.0);
        for (double pivot : new double[] { 20.0, 60.0, 250.0, 4000.0, 12000.0, 20000.0 })
            for (double slope : new double[] { -6.0, -3.0, 3.0, 6.0 })
            {
                final double within = FilterDesign.tiltSlopeWithin(pivot, slope, 36.0);
                assertTrue(within * slope > 0.0 && Math.abs(within) <= Math.abs(slope));
                final AnalogCascade c = FilterDesign.tilt(pivot, within);
                double top = -300.0;
                for (int k = 0; k <= 300; ++k) top = Math.max(top, c.magnitudeDb(20.0 * Math.pow(1000.0, k / 300.0)));
                assertTrue(top < 36.3);
                final double octaves = slope > 0.0 ? log2(20000.0 / pivot) : log2(pivot / 20.0);
                if (Math.abs(slope) * octaves <= 36.0) assertEquals(slope, within, 0.0);
            }
        assertTrue(Double.isNaN(FilterDesign.tiltSlopeWithin(1000.0, Double.NaN, 36.0)));
        assertEquals(6.0, FilterDesign.tiltSlopeWithin(20.0, 6.0, 0.0), 0.0);
    }

    @Test
    @DisplayName("The elliptic wall holds its ripple and its floor, and its low cut is its mirror")
    void ellipticWallHoldsItsRippleAndItsFloor()
    {
        for (int order : new int[] { 1, 2, 3, 5, 8, 12, 16 })
            for (double stopDb : new double[] { 60.0, 100.0 })
            {
                final double ripple = 0.05;
                final AnalogCascade c = FilterDesign.ellipticHighCut(1000.0, order, ripple, stopDb);
                final double ratio = FilterDesign.ellipticStopRatio(order, ripple, stopDb);
                assertEquals((order + 1) / 2, c.count);
                assertTrue(ratio > 1.0);
                double low = 1e9, high = -1e9, floor = -1e9;
                for (int k = 0; k <= 4000; ++k)
                {
                    final double level = c.magnitudeDb(1000.0 * k / 4000.0 + 1e-6);
                    low = Math.min(low, level);
                    high = Math.max(high, level);
                }
                for (int k = 0; k <= 4000; ++k)
                    floor = Math.max(floor, c.magnitudeDb(1000.0 * ratio * Math.pow(100.0, k / 4000.0)));
                assertTrue(low > -ripple - 1e-6, "order " + order + " low " + low);
                assertTrue(high < 1e-6, "order " + order + " high " + high);
                assertTrue(floor < -stopDb + 1e-4, "order " + order + " floor " + floor);
                assertEquals(-ripple, c.magnitudeDb(1000.0), 1e-6);

                final AnalogCascade mirror = FilterDesign.ellipticLowCut(1000.0, order, ripple, stopDb);
                for (double w : new double[] { 0.3, 0.9, 1.0, 1.02, ratio, 3.0 })
                    assertEquals(Math.max(c.magnitudeDb(1000.0 * w), -300.0), mirror.magnitudeDb(1000.0 / w), 1e-6);
            }
        assertTrue(FilterDesign.ellipticStopRatio() < 1.18);
        assertTrue(FilterDesign.ellipticStopRatio() > 1.16);
        assertTrue(FilterDesign.ellipticStopRatio(16) < FilterDesign.ellipticStopRatio(12));
        assertTrue(FilterDesign.ellipticStopRatio(12, 0.05, 60.0) < FilterDesign.ellipticStopRatio(12, 0.05, 100.0));
        assertTrue(FilterDesign.ellipticStopRatio(12, 1.0, 100.0) < FilterDesign.ellipticStopRatio(12, 0.05, 100.0));

        final BiquadCoeffs[] sections = new BiquadCoeffs[AnalogCascade.MAX_SECTIONS];
        final AnalogCascade wall = FilterDesign.ellipticHighCut(10000.0);
        final int n = FilterDesign.digital(wall, 48000.0, sections, AnalogCascade.MAX_SECTIONS);
        assertEquals(6, n);
        double pass = 0.0, floor = -1e9;
        for (int k = 0; k <= 600; ++k)
        {
            final double hz = 20.0 + (10000.0 - 20.0) * k / 600.0;
            pass = Math.max(pass, Math.abs(FilterDesign.magnitudeDb(sections, n, hz, 48000.0) - wall.magnitudeDb(hz)));
        }
        for (int k = 0; k <= 600; ++k)
            floor = Math.max(floor, FilterDesign.magnitudeDb(sections, n, 10000.0 * FilterDesign.ellipticStopRatio() * Math.pow(2.0, k / 600.0), 48000.0));
        assertTrue(pass < 0.6, "pass " + pass);
        assertTrue(floor < -90.0, "floor " + floor);
    }

    @Test
    @DisplayName("Cascades as biquads follow their prototypes where the plain transform is off by tens of dB")
    void cascadesAsBiquadsFollowTheirPrototypes()
    {
        final Object[][] cases = {
            { FilterDesign.highCut(1000.0, 18.0), 0.06, 0.10 },
            { FilterDesign.highCut(12000.0, 30.0), 0.20, 0.55 },
            { FilterDesign.highCut(16000.0, 96.0), 0.50, 0.90 },
            { FilterDesign.highCut(5000.0, 4.5), 0.04, 0.05 },
            { FilterDesign.highCut(8000.0, 15.0, 3.0), 0.35, 0.60 },
            { FilterDesign.lowCut(80.0, 45.0), 0.01, 0.01 },
            { FilterDesign.lowCut(30.0, 96.0), 0.01, 0.01 },
            { FilterDesign.lowCut(6000.0, 3.0), 0.04, 0.05 },
            { FilterDesign.bell(8000.0, 2.0, 12.0, 4), 0.04, 0.05 },
            { FilterDesign.bell(14000.0, 4.0, -18.0, 8), 0.15, 0.22 },
            { FilterDesign.bell(200.0, 1.0, 9.0, 16), 0.01, 0.01 },
            { FilterDesign.highShelf(10000.0, 9.0, 4), 0.05, 0.13 },
            { FilterDesign.lowShelf(150.0, -12.0, 8), 0.01, 0.01 },
            { FilterDesign.bandPass(3000.0, 1.5, 4), 1.20, 1.80 },
            { FilterDesign.bandStop(5000.0, 4.0, 3), 0.02, 0.02 },
            { FilterDesign.ellipticHighCut(18000.0), 3.40, 3.60 },
            { FilterDesign.ellipticLowCut(60.0), 0.01, 0.01 },
        };
        final int[] unstable = { 0 };
        for (Object[] item : cases)
        {
            final AnalogCascade c = (AnalogCascade) item[0];
            final double at48 = (Double) item[1], at44 = (Double) item[2];
            final double e48 = cascadeError(c, 48000.0, unstable), e44 = cascadeError(c, 44100.0, unstable), e96 = cascadeError(c, 96000.0, unstable);
            assertTrue(e48 < at48, "48 kHz " + e48 + " allowed " + at48);
            assertTrue(e44 < at44, "44.1 kHz " + e44 + " allowed " + at44);
            assertTrue(e96 < 0.6 * at48 + 0.02, "96 kHz " + e96);
        }
        assertEquals(0, unstable[0]);

        assertTrue(plainError(FilterDesign.highCut(12000.0, 30.0), 48000.0) > 30.0);
        assertTrue(plainError(FilterDesign.highCut(16000.0, 96.0), 48000.0) > 60.0);
        assertTrue(plainError(FilterDesign.bell(14000.0, 4.0, -18.0, 8), 48000.0) > 6.0);
        assertTrue(plainError(FilterDesign.ellipticHighCut(18000.0), 48000.0) > 100.0);
    }

    private static double plainError(AnalogCascade c, double fs)
    {
        final BiquadCoeffs[] sections = new BiquadCoeffs[AnalogCascade.MAX_SECTIONS];
        for (int i = 0; i < c.count; ++i)
            sections[i] = FilterDesign.bilinear(c.sections[i], fs, c.sections[i].poleFrequency());
        double worst = 0.0;
        for (int k = 0; k <= 400; ++k)
        {
            final double hz = 20.0 * Math.pow(1000.0, k / 400.0);
            final double want = c.magnitudeDb(hz);
            if (want > -60.0) worst = Math.max(worst, Math.abs(FilterDesign.magnitudeDb(sections, c.count, hz, fs) - want));
        }
        return worst;
    }

    @Test
    @DisplayName("Resonant shelves by their Q and the second-order tilt (TestToneRegressions)")
    void resonantShelvesAndSecondOrderTilt()
    {
        final double fs = 48000.0;
        final FilterDesign.Band band = new FilterDesign.Band(fs);
        double last = -1.0, corner = 0.0, plateau = 0.0, inverse = 0.0, digital = 0.0, high = 0.0;
        for (double q : new double[] { 0.7071067811865476, 1.0, 2.0, 4.0 })
        {
            final AnalogSection up = AnalogSection.lowShelfQ(1000.0, 12.0, q), down = AnalogSection.lowShelfQ(1000.0, -12.0, q);
            final AnalogSection top = AnalogSection.highShelfQ(1000.0, 12.0, q);
            corner = Math.max(corner, Math.abs(up.magnitudeDb(1000.0) - 6.0));
            plateau = Math.max(plateau, Math.max(Math.abs(up.magnitudeDb(1.0) - 12.0), Math.abs(up.magnitudeDb(200000.0))));
            final BiquadCoeffs d = FilterDesign.digital(up, band);
            double over = 0.0;
            for (double f = 20.0; f < 20000.0; f *= Math.pow(2.0, 1.0 / 24.0))
            {
                over = Math.max(over, up.magnitudeDb(f) - 12.0);
                inverse = Math.max(inverse, Math.abs(up.magnitudeDb(f) + down.magnitudeDb(f)));
                high = Math.max(high, Math.abs(top.magnitudeDb(f) - up.magnitudeDb(1.0e6 / f)));
                final double w = 2.0 * PI * f / fs;
                final double c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2.0 * w), s2 = Math.sin(2.0 * w);
                final double nr = d.b0 + d.b1 * c1 + d.b2 * c2, ni = d.b1 * s1 + d.b2 * s2;
                final double dr = 1.0 + d.a1 * c1 + d.a2 * c2, di = d.a1 * s1 + d.a2 * s2;
                digital = Math.max(digital, Math.abs(10.0 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di)) - up.magnitudeDb(f)));
            }
            assertTrue(over > last);
            last = over;
        }
        final AnalogSection tilt = AnalogSection.tiltSecondOrder(1000.0, 12.0), slow = AnalogSection.tilt(1000.0, 12.0);
        assertTrue(corner < 1e-9);
        assertTrue(plateau < 1e-3);
        assertTrue(inverse < 1e-9);
        assertTrue(high < 1e-9);
        assertTrue(digital < 0.25, "digital " + digital);
        assertEquals(-6.0, tilt.magnitudeDb(1.0), 1e-3);
        assertEquals(0.0, tilt.magnitudeDb(1000.0), 1e-9);
        assertEquals(6.0, tilt.magnitudeDb(200000.0), 1e-3);
        assertTrue(tilt.magnitudeDb(2000.0) > slow.magnitudeDb(2000.0) + 0.5);
        // At 0.7071 the shelf by its Q is the shelf of slope 1.
        final AnalogSection a = AnalogSection.lowShelfQ(800.0, 6.0, 0.7071067811865476), b = AnalogSection.lowShelf(800.0, 6.0);
        for (double f : new double[] { 50.0, 800.0, 5000.0 }) assertEquals(b.magnitudeDb(f), a.magnitudeDb(f), 1e-9);
    }

    /** The complex response of a cascade at a frequency, {re, im}. */
    private static double[] analogAt(AnalogCascade cascade, double hz)
    {
        final double w = 2.0 * PI * hz;
        double re = 1.0, im = 0.0;
        for (int i = 0; i < cascade.count; ++i)
        {
            final AnalogSection s = cascade.sections[i];
            final double nr = s.b0 - s.b2 * w * w, ni = s.b1 * w, dr = s.a0 - s.a2 * w * w, di = s.a1 * w;
            final double den = dr * dr + di * di;
            final double qr = (nr * dr + ni * di) / den, qi = (ni * dr - nr * di) / den;
            final double r = re * qr - im * qi, j = re * qi + im * qr;
            re = r;
            im = j;
        }
        return new double[] { re, im };
    }

    private static double analogDb(AnalogCascade cascade, double hz)
    {
        final double[] h = analogAt(cascade, hz);
        return 20.0 * Math.log10(Math.max(Math.hypot(h[0], h[1]), 1e-15));
    }

    private static double distance(double[] a, double[] b)
    {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static AnalogCascade[] kinds(double hz, double q, double order)
    {
        return new AnalogCascade[] {
            FilterDesign.bell(hz, q, 12.0, order), FilterDesign.lowShelf(hz, 12.0, order),
            FilterDesign.bandPass(hz, q, order), FilterDesign.bandStop(hz, q, order),
            FilterDesign.allPass(hz, 0.7071067811865476, order) };
    }

    @Test
    @DisplayName("Orders between whole ones move smoothly and keep the gains of each kind (TestToneRegressions)")
    void ordersBetweenWholeOnes()
    {
        final double hz = 1000.0, q = 2.0;
        final double low = hz * (Math.sqrt(1.0 + 0.25 / (q * q)) - 0.5 / q), high = hz * (Math.sqrt(1.0 + 0.25 / (q * q)) + 0.5 / q);
        final java.util.List<Double> grid = new java.util.ArrayList<>();
        for (double f = 20.0; f < 20000.0; f *= Math.pow(2.0, 1.0 / 12.0)) grid.add(f);
        double bellTop = 0.0, bellEdge = 0.0, shelfEnds = 0.0, shelfCorner = 0.0, passEdge = 0.0, stopEdge = 0.0, allFlat = 0.0;
        final double[] step = new double[5], seam = new double[5];
        double turned = 0.0;
        for (int whole = 2; whole <= 5; ++whole)
        {
            final AnalogCascade[] at = kinds(hz, q, whole), under = kinds(hz, q, whole - 1e-6), over = kinds(hz, q, whole + 1e-6);
            for (int kind = 0; kind < 5; ++kind)
                for (double f : grid)
                    seam[kind] = Math.max(seam[kind], Math.max(distance(analogAt(under[kind], f), analogAt(at[kind], f)),
                                                               distance(analogAt(over[kind], f), analogAt(at[kind], f))));
        }
        AnalogCascade[] before = null;
        for (int i = 0; i <= 100; ++i)
        {
            final double order = 1.0 + 0.05 * i;
            final AnalogCascade[] now = kinds(hz, q, order);
            bellTop = Math.max(bellTop, Math.abs(analogDb(now[0], hz) - 12.0));
            bellEdge = Math.max(bellEdge, Math.max(Math.abs(analogDb(now[0], low) - 6.0), Math.abs(analogDb(now[0], high) - 6.0)));
            shelfEnds = Math.max(shelfEnds, Math.max(Math.abs(analogDb(now[1], 0.01) - 12.0), Math.abs(analogDb(now[1], 1.0e7))));
            shelfCorner = Math.max(shelfCorner, Math.abs(analogDb(now[1], hz) - 6.0));
            passEdge = Math.max(passEdge, Math.max(Math.abs(analogDb(now[2], hz)),
                                Math.max(Math.abs(analogDb(now[2], low) + 3.0103), Math.abs(analogDb(now[2], high) + 3.0103))));
            stopEdge = Math.max(stopEdge, Math.max(Math.abs(analogDb(now[3], low) + 3.0103), Math.abs(analogDb(now[3], high) + 3.0103)));
            for (double f : grid) allFlat = Math.max(allFlat, Math.abs(analogDb(now[4], f)));
            final double[] wanted = { Math.cos(-0.5 * PI * order), Math.sin(-0.5 * PI * order) };
            turned = Math.max(turned, distance(analogAt(now[4], hz), wanted));
            if (before != null)
                for (int kind = 0; kind < 5; ++kind)
                    for (double f : grid)
                    {
                        final double[] b = analogAt(before[kind], f);
                        step[kind] = Math.max(step[kind], distance(analogAt(now[kind], f), b) / Math.max(1.0, Math.hypot(b[0], b[1])));
                    }
            before = now;
        }
        assertTrue(bellTop < 1e-6);
        assertTrue(bellEdge < 1e-6);
        assertTrue(shelfEnds < 1e-3);
        assertTrue(shelfCorner < 1e-9);
        assertTrue(passEdge < 0.05, "passEdge " + passEdge);
        assertTrue(stopEdge < 0.05, "stopEdge " + stopEdge);
        assertTrue(allFlat < 1e-9);
        for (int kind = 0; kind < 4; ++kind) assertTrue(step[kind] < 0.35, "step " + kind + " " + step[kind]);
        for (int kind = 0; kind < 5; ++kind) assertTrue(seam[kind] < 1e-3, "seam " + kind + " " + seam[kind]);
        assertTrue(turned < 1e-9, "turned " + turned);
    }

    @Test
    @DisplayName("Analog sections: poles, magnitudes and the cascade's copy semantics")
    void analogSectionBasics()
    {
        final AnalogSection lp = AnalogSection.lowPass(1000.0, 4.0);
        assertTrue(lp.isSecondOrder());
        assertEquals(1000.0, lp.poleFrequency(), 1e-9);
        assertEquals(20.0 * Math.log10(4.0), lp.magnitudeDb(1000.0), 1e-9);
        final AnalogSection fo = AnalogSection.firstOrderHighPass(250.0);
        assertFalse(fo.isSecondOrder());
        assertEquals(250.0, fo.poleFrequency(), 1e-9);
        assertEquals(-10.0 * Math.log10(2.0), fo.magnitudeDb(250.0), 1e-9);
        assertEquals(6.0, AnalogSection.peak(3000.0, 2.0, 6.0).magnitudeDb(3000.0), 1e-9);
        assertEquals(-300.0, AnalogSection.notch(500.0).magnitudeDb(500.0), 0.0);
        assertEquals(0.0, AnalogSection.allPass(500.0).magnitudeDb(123.0), 1e-12);

        final AnalogCascade c = new AnalogCascade();
        final AnalogSection s = AnalogSection.peak(1000.0, 1.0, 6.0);
        c.add(s);
        s.b0 = 99.0;                  // the cascade holds a copy
        assertEquals(6.0, c.magnitudeDb(1000.0), 1e-9);
        for (int i = 0; i < 20; ++i) c.add(s);
        assertEquals(AnalogCascade.MAX_SECTIONS, c.count);
        final AnalogCascade copy = new AnalogCascade(c);
        copy.sections[0].b0 = 0.0;
        assertEquals(6.0, c.sections[0].magnitudeDb(1000.0), 1e-9);
    }
}
