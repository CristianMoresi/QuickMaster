package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link TonalBalance}. No vectors of the C++ class
 * exist to compare against, so each test states a property the class
 * documents and measures it on the audio and on the curve.
 */
class TonalBalanceTest
{
    private static final double FS = 48000.0;

    // -- Signals ---------------------------------------------------------------

    private static double[] white(int n, long seed, double rms)
    {
        Random r = new Random(seed);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = rms * r.nextGaussian();
        return x;
    }

    /** Pink noise by Kellet's three poles, scaled to the RMS asked. */
    private static double[] pink(int n, long seed, double rms)
    {
        Random r = new Random(seed);
        double[] x = new double[n];
        double b0 = 0.0, b1 = 0.0, b2 = 0.0, power = 0.0;
        for (int i = 0; i < n; i++)
        {
            double w = r.nextGaussian();
            b0 = 0.99765 * b0 + w * 0.0990460;
            b1 = 0.96300 * b1 + w * 0.2965164;
            b2 = 0.57000 * b2 + w * 1.0526913;
            x[i] = b0 + b1 + b2 + w * 0.1848;
            power += x[i] * x[i];
        }
        double scale = rms / Math.sqrt(power / n);
        for (int i = 0; i < n; i++) x[i] *= scale;
        return x;
    }

    /** A peaking equalizer (RBJ) run over a signal, in place. */
    private static void peaking(double[] x, double hz, double db, double q)
    {
        double a = Math.pow(10.0, db / 40.0), w = 2.0 * Math.PI * hz / FS, alpha = Math.sin(w) / (2.0 * q);
        double b0 = 1.0 + alpha * a, b1 = -2.0 * Math.cos(w), b2 = 1.0 - alpha * a;
        double a0 = 1.0 + alpha / a, a1 = -2.0 * Math.cos(w), a2 = 1.0 - alpha / a;
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        for (int i = 0; i < x.length; i++)
        {
            double y = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = y;
            x[i] = y;
        }
    }

    // -- Running ---------------------------------------------------------------

    private static final class Result
    {
        double[][] out;
        final double[] curve = new double[TonalBalance.POINTS];
        final double[] correction = new double[TonalBalance.BANDS];
        double loudness, change;
        int curves;
    }

    private static Result run(TonalBalance tb, double[]... in)
    {
        Result r = new Result();
        tb.setCurveListener(c ->
        {
            System.arraycopy(c, 0, r.curve, 0, c.length);
            r.curves++;
        });
        r.out = new double[in.length][];
        for (int ch = 0; ch < in.length; ch++) r.out[ch] = in[ch].clone();
        tb.process(r.out, in[0].length);
        for (int b = 0; b < TonalBalance.BANDS; b++) r.correction[b] = tb.getCorrection(b);
        r.loudness = tb.getLoudness();
        r.change = tb.getLoudnessChange();
        tb.setCurveListener(null);
        return r;
    }

    private static TonalBalance prepared(int channels)
    {
        TonalBalance tb = new TonalBalance();
        tb.prepare(FS, channels);
        return tb;
    }

    /** Only the broad balance: the detail off. */
    private static TonalBalance balanceOnly(int channels, double balance, double range)
    {
        TonalBalance tb = prepared(channels);
        tb.setBalance(balance);
        tb.setFillDips(0.0);
        tb.setTrimPeaks(0.0);
        tb.setRange(range);
        return tb;
    }

    private static int bandOf(TonalBalance tb, double hz)
    {
        int best = 0;
        for (int b = 1; b < TonalBalance.BANDS; b++)
            if (Math.abs(Math.log(tb.getBandCentre(b) / hz)) < Math.abs(Math.log(tb.getBandCentre(best) / hz))) best = b;
        return best;
    }

    private static int pointOf(double hz)
    {
        return (int) Math.round((TonalBalance.POINTS - 1) * Math.log(hz / 20.0) / Math.log(1000.0));
    }

    private static double rms(double[] v)
    {
        double s = 0.0;
        for (double x : v) s += x * x;
        return Math.sqrt(s / v.length);
    }

    private static int seconds(double s) { return (int) (s * FS); }

    // -- Layout ----------------------------------------------------------------

    @Test
    @DisplayName("Latency is 774 samples at 44.1 and 48 kHz and doubles with the rate; a curve is drawn every 256")
    void latencyAndHop()
    {
        int[][] expected = { { 44100, 774, 256 }, { 48000, 774, 256 }, { 96000, 1548, 512 }, { 192000, 3096, 1024 } };
        for (int[] e : expected)
            for (TonalBalance.Quality q : TonalBalance.Quality.values())
            {
                TonalBalance tb = new TonalBalance();
                tb.setQuality(q);
                tb.prepare(e[0], 2);
                assertEquals(e[1], tb.getLatency(), "latency at " + e[0] + ", " + q);
                assertEquals(e[2], tb.getCurveHop(), "hop at " + e[0] + ", " + q);
                assertEquals(q, tb.getQualityInUse());
            }
        TonalBalance tb = prepared(2);
        assertEquals(20.0, tb.getBandCentre(0), 1e-9);
        assertEquals(20000.0, tb.getBandCentre(TonalBalance.BANDS - 1), 1e-6);
        assertEquals(20.0, TonalBalance.getCurveFrequency(0), 1e-12);
    }

    @Test
    @DisplayName("With every amount at zero the programme returns to the bit, late by the latency")
    void nothingAskedIsTransparent()
    {
        int n = seconds(1.0);
        double[] left = pink(n, 1, 0.1), right = white(n, 2, 0.1);
        TonalBalance tb = balanceOnly(2, 0.0, 6.0);
        Result r = run(tb, left, right);
        int latency = tb.getLatency();
        for (int i = latency; i < n; i++)
        {
            assertEquals(left[i - latency], r.out[0][i], 0.0, "left " + i);
            assertEquals(right[i - latency], r.out[1][i], 0.0, "right " + i);
        }
        for (double c : r.correction) assertEquals(0.0, c, 0.0);
        for (double c : r.curve) assertEquals(0.0, c, 0.0);
        assertEquals(n / tb.getCurveHop(), r.curves);
    }

    @Test
    @DisplayName("Silence stays silence and asks for nothing")
    void silence()
    {
        int n = seconds(1.0);
        TonalBalance tb = prepared(2);
        tb.setBalance(1.0);
        tb.setFillDips(1.0);
        tb.setTrimPeaks(1.0);
        Result r = run(tb, new double[n], new double[n]);
        for (int ch = 0; ch < 2; ch++)
            for (double v : r.out[ch]) assertEquals(0.0, v, 0.0);
        for (double c : r.correction) assertEquals(0.0, c, 1e-12);
        for (double c : r.curve) assertEquals(0.0, c, 1e-12);
        assertEquals(-200.0, r.loudness, 0.0);
        assertEquals(0.0, r.change, 0.0);
        assertEquals(0.0, tb.getLoudnessRaised(), 0.0);
        assertEquals(0.0, tb.getLoudnessLowered(), 0.0);
        assertEquals(0.0, tb.getCentroidShift(), 0.0);
    }

    // -- The broad balance -------------------------------------------------------

    @Test
    @DisplayName("A bright programme is darkened: its top comes down against its low end, far more than a pink one")
    void brightProgrammeIsDarkened()
    {
        int n = seconds(5.0);
        TonalBalance forWhite = balanceOnly(1, 1.0, 12.0), forPink = balanceOnly(1, 1.0, 12.0);
        Result w = run(forWhite, white(n, 3, 0.1));
        Result p = run(forPink, pink(n, 3, 0.1));
        int low = bandOf(forWhite, 150.0), high = bandOf(forWhite, 8000.0);
        double whiteTilt = w.correction[high] - w.correction[low], pinkTilt = p.correction[high] - p.correction[low];
        assertTrue(w.correction[high] < -1.0, "the top of a white noise is lowered: " + w.correction[high]);
        assertTrue(w.correction[low] > 1.0, "its low end is raised: " + w.correction[low]);
        assertTrue(whiteTilt < pinkTilt - 3.0, "white " + whiteTilt + " dB against pink " + pinkTilt + " dB");
        // The curve the filter plays says the same.
        assertTrue(w.curve[pointOf(8000.0)] < w.curve[pointOf(150.0)] - 2.0);
    }

    @Test
    @DisplayName("The balance is a share of the way: twice the amount asks for more, never for less")
    void balanceScalesTheCorrection()
    {
        int n = seconds(4.0);
        double[] x = white(n, 4, 0.1);
        TonalBalance half = balanceOnly(1, 0.5, 24.0), full = balanceOnly(1, 1.0, 24.0);
        Result a = run(half, x), b = run(full, x);
        int low = bandOf(half, 150.0), high = bandOf(half, 8000.0);
        double halfTilt = a.correction[high] - a.correction[low], fullTilt = b.correction[high] - b.correction[low];
        assertTrue(halfTilt < -1.0, "half the balance still darkens: " + halfTilt);
        assertEquals(2.0, fullTilt / halfTilt, 0.25, "full " + fullTilt + " against half " + halfTilt);
    }

    @Test
    @DisplayName("Tilt moves the reference: a positive one asks for a brighter programme")
    void tiltMovesTheReference()
    {
        int n = seconds(4.0);
        double[] x = pink(n, 5, 0.1);
        double[] tilts = { -3.0, 0.0, 3.0 };
        double[] slope = new double[3];
        for (int i = 0; i < 3; i++)
        {
            TonalBalance tb = balanceOnly(1, 1.0, 24.0);
            tb.setTilt(tilts[i]);
            Result r = run(tb, x);
            slope[i] = r.correction[bandOf(tb, 8000.0)] - r.correction[bandOf(tb, 250.0)];
        }
        assertTrue(slope[0] < slope[1] - 3.0, "a negative tilt darkens: " + slope[0] + " against " + slope[1]);
        assertTrue(slope[2] > slope[1] + 3.0, "a positive tilt brightens: " + slope[2] + " against " + slope[1]);
    }

    @Test
    @DisplayName("The asymmetry gives the lift and the cut their own shares of the balance")
    void asymmetry()
    {
        int n = seconds(4.0);
        double[] x = white(n, 6, 0.1);
        TonalBalance lifting = balanceOnly(1, 0.5, 24.0), cutting = balanceOnly(1, 0.5, 24.0);
        lifting.setBalanceAsymmetry(1.0);
        cutting.setBalanceAsymmetry(-1.0);
        Result up = run(lifting, x), down = run(cutting, x);
        int low = bandOf(lifting, 100.0), high = bandOf(lifting, 10000.0);
        // Before the offset that keeps the loudness, one only lifts and the other only cuts:
        // what is left of that is which end moves against the middle of the band.
        double mid = 0.5 * (up.correction[bandOf(lifting, 900.0)] + up.correction[bandOf(lifting, 1500.0)]);
        assertTrue(up.correction[low] - mid > 2.0 * Math.abs(up.correction[high] - mid) || up.correction[low] - up.correction[high] > 2.0,
                "lift only: low " + up.correction[low] + ", mid " + mid + ", high " + up.correction[high]);
        double span = up.correction[low] - up.correction[high], otherSpan = down.correction[low] - down.correction[high];
        assertTrue(span > 1.0 && otherSpan > 1.0, "both still darken a white noise: " + span + ", " + otherSpan);
        // The lifted low end is larger with the lift share doubled than with it at nothing.
        assertTrue(up.correction[low] > down.correction[low] + 1.0, up.correction[low] + " against " + down.correction[low]);
        assertTrue(down.correction[high] < up.correction[high] - 1.0, down.correction[high] + " against " + up.correction[high]);
    }

    @Test
    @DisplayName("No correction passes the range, in the bands or on the curve")
    void rangeLimits()
    {
        int n = seconds(4.0);
        double[] x = white(n, 7, 0.1);
        peaking(x, 2500.0, 18.0, 6.0);
        for (double range : new double[] { 1.5, 3.0, 6.0 })
            for (double knee : new double[] { 0.0, 4.0 })
            {
                TonalBalance tb = prepared(1);
                tb.setBalance(1.0);
                tb.setFillDips(1.0);
                tb.setTrimPeaks(1.0);
                tb.setTilt(-6.0);
                tb.setRange(range);
                tb.setRangeKnee(knee);
                double[] worst = { 0.0 };
                tb.setCurveListener(c ->
                {
                    for (double v : c) worst[0] = Math.max(worst[0], Math.abs(v));
                });
                double[][] buffer = { x.clone() };
                tb.process(buffer, n);
                double largest = 0.0;
                for (int b = 0; b < TonalBalance.BANDS; b++) largest = Math.max(largest, Math.abs(tb.getCorrection(b)));
                assertTrue(largest <= range + 1e-9, "band correction " + largest + " over a range of " + range);
                assertTrue(worst[0] <= range + 1e-9, "curve " + worst[0] + " over a range of " + range);
                assertTrue(largest > 0.8 * range, "the range is reached with this programme: " + largest + " of " + range);
            }
        TonalBalance none = prepared(1);
        none.setBalance(1.0);
        none.setRange(0.0);
        Result r = run(none, x);
        for (double c : r.curve) assertEquals(0.0, c, 0.0, "a range of nothing leaves the programme alone");
    }

    @Test
    @DisplayName("The total loudness is kept: the model says so and a second reading of the output agrees")
    void loudnessIsKept()
    {
        int n = seconds(5.0);
        for (int kind = 0; kind < 2; kind++)
        {
            double[] x = kind == 0 ? white(n, 8, 0.1) : pink(n, 8, 0.1);
            if (kind == 1) peaking(x, 300.0, 9.0, 0.7);
            TonalBalance tb = balanceOnly(1, 1.0, 6.0);
            Result r = run(tb, x);
            assertEquals(0.0, r.change, 1e-6, "the change the model reports, kind " + kind);
            assertTrue(tb.getLoudnessRaised() > 0.01 && tb.getLoudnessLowered() < -0.01,
                    "the correction redistributes: +" + tb.getLoudnessRaised() + " " + tb.getLoudnessLowered());
            assertEquals(-tb.getLoudnessLowered(), tb.getLoudnessRaised(), 1e-4, "what is raised is what is lowered");

            TonalBalance reader = balanceOnly(1, 0.0, 6.0);
            Result after = run(reader, r.out[0]);
            assertEquals(r.loudness, after.loudness, 0.12, "loudness read again on the output, kind " + kind);
        }
    }

    /** The lowest and the highest value of every curve drawn while a programme runs through the processor. */
    private static double[] curveSpan(TonalBalance tb, double[] x)
    {
        double[] span = { 0.0, 0.0 };
        tb.setCurveListener(c ->
        {
            for (double v : c)
            {
                span[0] = Math.min(span[0], v);
                span[1] = Math.max(span[1], v);
            }
        });
        tb.process(new double[][] { x.clone() }, x.length);
        tb.setCurveListener(null);
        return span;
    }

    /** Everything on: the balance all the way, the peaks and the dips in full, a wide range. */
    private static TonalBalance everything()
    {
        TonalBalance tb = prepared(1);
        tb.setBalance(1.0);
        tb.setTrimPeaks(1.0);
        tb.setFillDips(1.0);
        tb.setRange(12.0);
        return tb;
    }

    @Test
    @DisplayName("Raising and lowering take their own shares: with one at nothing no point of the curve goes that way")
    void raisingAndLowering()
    {
        int n = seconds(5.0);
        // A white noise with a resonance and a hole: there is broad tone to move, a peak to trim and a dip to fill.
        double[] x = white(n, 8, 0.1);
        peaking(x, 2500.0, 12.0, 5.0);
        peaking(x, 700.0, -12.0, 5.0);

        double[] both = curveSpan(everything(), x);
        assertTrue(both[0] < -3.0 && both[1] > 3.0, "with both whole the curve goes both ways: " + both[0] + " to " + both[1]);

        // Lowering alone: the cut of the balance and the trimming of peaks, and nothing over zero.
        TonalBalance lowers = everything();
        lowers.setRaising(0.0);
        double[] down = curveSpan(lowers, x);
        assertEquals(0.0, down[1], 0.0, "no point of the curve is over zero");
        assertTrue(down[0] < -3.0, "and what is in excess is lowered: " + down[0]);
        double most = -1e9;
        for (int b = 0; b < TonalBalance.BANDS; b++) most = Math.max(most, lowers.getCorrection(b));
        assertTrue(most <= 0.0, "no band is raised: " + most);
        assertTrue(lowers.getLoudnessChange() < -0.1, "the loudness goes down with it: " + lowers.getLoudnessChange() + " dB");
        assertEquals(0.0, lowers.getLoudnessRaised(), 0.0);

        // Raising alone: the lift of the balance and the filling of dips, and nothing under zero.
        TonalBalance raises = everything();
        raises.setLowering(0.0);
        double[] up = curveSpan(raises, x);
        assertEquals(0.0, up[0], 0.0, "no point of the curve is under zero");
        assertTrue(up[1] > 3.0, "and what is lacking is raised: " + up[1]);
        double least = 1e9;
        for (int b = 0; b < TonalBalance.BANDS; b++) least = Math.min(least, raises.getCorrection(b));
        assertTrue(least >= 0.0, "no band is lowered: " + least);
        assertTrue(raises.getLoudnessChange() > 0.1, "the loudness goes up with it: " + raises.getLoudnessChange() + " dB");
        assertEquals(0.0, raises.getLoudnessLowered(), 0.0);

        // A share between is between: half the raising leaves less over zero than all of it, more than none.
        TonalBalance half = everything();
        half.setRaising(0.5);
        double[] partly = curveSpan(half, x);
        assertTrue(partly[1] > 0.2 * both[1] && partly[1] < 0.9 * both[1], "half the raising: " + partly[1] + " of " + both[1]);
        assertTrue(half.getLoudnessChange() < 0.0 && half.getLoudnessChange() > lowers.getLoudnessChange(),
                "and gives back part of the loudness: " + half.getLoudnessChange() + " against " + lowers.getLoudnessChange());

        // Both whole are the defaults: the same output as a processor that was never told.
        TonalBalance told = everything(), untold = everything();
        assertEquals(1.0, untold.getRaising(), 0.0);
        assertEquals(1.0, untold.getLowering(), 0.0);
        told.setRaising(1.0);
        told.setLowering(1.0);
        Result a = run(untold, x), b = run(told, x);
        assertArrayEquals(a.out[0], b.out[0], 0.0);
        assertEquals(0.0, a.change, 1e-6, "and the loudness is kept");
    }

    @Test
    @DisplayName("What comes out is closer to the reference: a second pass asks for far less")
    void outputIsCloserToTheReference()
    {
        int n = seconds(6.0);
        double[] x = white(n, 9, 0.1);
        TonalBalance first = balanceOnly(1, 1.0, 12.0), second = balanceOnly(1, 1.0, 12.0);
        first.setKnee(0.1);
        second.setKnee(0.1);
        Result a = run(first, x);
        Result b = run(second, a.out[0]);
        double firstAsk = 0.0, secondAsk = 0.0;
        for (int band = bandOf(first, 60.0); band <= bandOf(first, 12000.0); band++)
        {
            firstAsk += a.correction[band] * a.correction[band];
            secondAsk += b.correction[band] * b.correction[band];
        }
        assertTrue(Math.sqrt(firstAsk) > 10.0, "the first pass asks for a real correction: " + Math.sqrt(firstAsk));
        assertTrue(secondAsk < 0.2 * 0.2 * firstAsk, "second pass " + Math.sqrt(secondAsk) + " against first " + Math.sqrt(firstAsk));
    }

    @Test
    @DisplayName("The frequency range bounds the corrections: nothing is asked outside it")
    void frequencyRange()
    {
        int n = seconds(4.0);
        double[] x = white(n, 10, 0.1);
        peaking(x, 1000.0, 12.0, 4.0);
        TonalBalance tb = prepared(1);
        tb.setBalance(1.0);
        tb.setFillDips(1.0);
        tb.setTrimPeaks(1.0);
        tb.setRange(12.0);
        tb.setFrequencyRange(3000.0, 300.0);   // in either order
        assertEquals(300.0, tb.getLowFrequency(), 0.0);
        assertEquals(3000.0, tb.getHighFrequency(), 0.0);
        Result r = run(tb, x);
        for (double hz : new double[] { 30.0, 60.0, 120.0, 7000.0, 12000.0, 18000.0 })
            assertEquals(0.0, r.curve[pointOf(hz)], 1e-9, "outside the range at " + hz + " Hz");
        assertTrue(Math.abs(r.curve[pointOf(1000.0)]) > 1.0, "inside the range the peak is trimmed: " + r.curve[pointOf(1000.0)]);
    }

    // -- The detail ----------------------------------------------------------------

    @Test
    @DisplayName("A peak that stands over both of its sides is trimmed, and only there")
    void peaksAreTrimmed()
    {
        int n = seconds(4.0);
        double[] x = pink(n, 11, 0.1);
        double w = 2.0 * Math.PI * 3000.0 / FS;
        for (int i = 0; i < n; i++) x[i] += 0.2 * Math.sin(w * i);
        TonalBalance tb = prepared(1);
        tb.setBalance(0.0);
        tb.setFillDips(0.0);
        tb.setTrimPeaks(1.0);
        Result r = run(tb, x);
        double at = r.curve[pointOf(3000.0)];
        assertTrue(at < -4.0, "the peak is lowered: " + at);
        assertTrue(at >= -6.0 - 1e-9, "no further than the range: " + at);
        // Away from the peak nothing is trimmed: what is left there is the
        // offset that gives back the loudness the peak lost, by how present each band is.
        double least = Double.POSITIVE_INFINITY, most = Double.NEGATIVE_INFINITY;
        for (double hz : new double[] { 200.0, 800.0, 1500.0, 6000.0, 12000.0 })
        {
            least = Math.min(least, r.curve[pointOf(hz)]);
            most = Math.max(most, r.curve[pointOf(hz)]);
        }
        assertTrue(least > 0.0 && most < 2.5, "the offset that keeps the loudness: " + least + " to " + most);
        assertTrue(at < least - 4.5, "the peak stands under the rest: " + at + " against " + least);
        assertEquals(0.0, r.change, 1e-6, "and the loudness is kept");
        // The sine comes out lower by what the curve says there.
        double s = 0.0, c = 0.0;
        int from = n - seconds(0.5);
        for (int i = from; i < n; i++)
        {
            s += r.out[0][i] * Math.sin(w * i);
            c += r.out[0][i] * Math.cos(w * i);
        }
        double played = 20.0 * Math.log10(2.0 * Math.hypot(s, c) / (n - from) / 0.2);
        assertEquals(at, played, 0.35, "the gain the sine took");

        TonalBalance off = prepared(1);
        off.setBalance(0.0);
        off.setFillDips(0.0);
        off.setTrimPeaks(0.0);
        Result untouched = run(off, x);
        for (double v : untouched.curve) assertEquals(0.0, v, 0.0, "with the amount at zero nothing is trimmed");
    }

    @Test
    @DisplayName("A dip under both of its sides is filled, and only there")
    void dipsAreFilled()
    {
        int n = seconds(4.0);
        double[] x = pink(n, 12, 0.1);
        peaking(x, 1000.0, -15.0, 2.0);
        TonalBalance tb = prepared(1);
        tb.setBalance(0.0);
        tb.setFillDips(1.0);
        tb.setTrimPeaks(0.0);
        Result r = run(tb, x);
        double at = r.curve[pointOf(1000.0)];
        assertTrue(at > 2.5, "the dip is raised: " + at);
        assertTrue(at <= 6.0 + 1e-9, "no further than the range: " + at);
        for (double hz : new double[] { 100.0, 250.0, 4000.0, 10000.0 })
            assertTrue(Math.abs(r.curve[pointOf(hz)]) < 0.8, "away from the dip at " + hz + " Hz: " + r.curve[pointOf(hz)]);
        for (double v : r.curve) assertTrue(v > -0.8, "filling a dip trims nothing: " + v);
    }

    @Test
    @DisplayName("A smooth programme has neither peaks nor dips: a slope is not one")
    void slopeIsNeither()
    {
        int n = seconds(4.0);
        double[] x = pink(n, 13, 0.1);
        TonalBalance tb = prepared(1);
        tb.setBalance(0.0);
        tb.setFillDips(1.0);
        tb.setTrimPeaks(1.0);
        Result r = run(tb, x);
        for (int i = pointOf(60.0); i <= pointOf(12000.0); i++)
            assertTrue(Math.abs(r.curve[i]) < 1.0, "at " + TonalBalance.getCurveFrequency(i) + " Hz: " + r.curve[i]);
    }

    @Test
    @DisplayName("A finer detail trims a narrow peak more narrowly")
    void detailSetsTheWidth()
    {
        int n = seconds(4.0);
        double[] x = pink(n, 14, 0.1);
        double w = 2.0 * Math.PI * 2000.0 / FS;
        for (int i = 0; i < n; i++) x[i] += 0.2 * Math.sin(w * i);
        int[] width = new int[2];
        double[] details = { 1.0 / 2.0, 1.0 / 12.0 };
        for (int k = 0; k < 2; k++)
        {
            TonalBalance tb = prepared(1);
            tb.setBalance(0.0);
            tb.setFillDips(0.0);
            tb.setTrimPeaks(1.0);
            tb.setDetail(details[k]);
            Result r = run(tb, x);
            for (double v : r.curve)
                if (v < -2.0) width[k]++;
            assertTrue(r.curve[pointOf(2000.0)] < -3.0, "the peak is trimmed at a detail of " + details[k] + ": " + r.curve[pointOf(2000.0)]);
        }
        assertTrue(width[0] > 2 * width[1], "points trimmed: " + width[0] + " at a half, " + width[1] + " at a twelfth");
    }

    // -- Channels ----------------------------------------------------------------

    @Test
    @DisplayName("Both channels take the same curve")
    void channelsShareTheCurve()
    {
        int n = seconds(2.0);
        double[] left = white(n, 15, 0.1);
        double[] right = new double[n];
        for (int i = 0; i < n; i++) right[i] = 0.5 * left[i];
        TonalBalance tb = prepared(2);
        tb.setBalance(1.0);
        tb.setRange(12.0);
        Result r = run(tb, left, right);
        for (int i = 0; i < n; i++) assertEquals(0.5 * r.out[0][i], r.out[1][i], 0.0, "sample " + i);
        assertTrue(Math.abs(r.curve[pointOf(8000.0)]) > 1.0, "and the curve is not flat");
    }

    @Test
    @DisplayName("One source reads the same wherever it sits in the image")
    void sourceAnywhereInTheImage()
    {
        int n = seconds(4.0);
        double[] x = white(n, 16, 0.1);
        peaking(x, 400.0, 8.0, 1.0);
        Result mono = run(balanceOnly(1, 1.0, 12.0), x);
        Result leftOnly = run(balanceOnly(2, 1.0, 12.0), x, new double[n]);
        double[] l = new double[n], rr = new double[n];
        for (int i = 0; i < n; i++)
        {
            l[i] = Math.sqrt(0.8) * x[i];
            rr[i] = -Math.sqrt(0.2) * x[i];
        }
        Result panned = run(balanceOnly(2, 1.0, 12.0), l, rr);
        for (int b = 4; b < TonalBalance.BANDS - 4; b++)
        {
            assertEquals(mono.correction[b], leftOnly.correction[b], 1e-9, "left only, band " + b);
            assertEquals(mono.correction[b], panned.correction[b], 0.05, "panned and inverted, band " + b);
        }
    }

    // -- Streaming -----------------------------------------------------------------

    @Test
    @DisplayName("The output does not depend on how the stream is cut into blocks")
    void anyBlocks()
    {
        int n = seconds(1.5);
        double[] left = white(n, 17, 0.1), right = pink(n, 18, 0.1);
        double[][] reference = null;
        for (int block : new int[] { n, 1, 63, 64, 500, 4096 })
        {
            TonalBalance tb = prepared(2);
            tb.setBalance(1.0);
            tb.setFillDips(1.0);
            tb.setTrimPeaks(1.0);
            double[][] out = new double[2][n];
            double[][] piece = new double[2][Math.min(block, n)];
            for (int at = 0; at < n; at += block)
            {
                int m = Math.min(block, n - at);
                System.arraycopy(left, at, piece[0], 0, m);
                System.arraycopy(right, at, piece[1], 0, m);
                tb.process(piece, m);
                System.arraycopy(piece[0], 0, out[0], at, m);
                System.arraycopy(piece[1], 0, out[1], at, m);
            }
            if (reference == null) reference = out;
            else
            {
                assertArrayEquals(reference[0], out[0], 0.0, "left, blocks of " + block);
                assertArrayEquals(reference[1], out[1], 0.0, "right, blocks of " + block);
            }
        }
    }

    @Test
    @DisplayName("Interleaved floats are processed as the planar doubles are")
    void interleavedMatchesPlanar()
    {
        int n = seconds(1.0);
        float[] interleaved = new float[2 * n];
        double[][] planar = new double[2][n];
        Random r = new Random(19);
        for (int i = 0; i < n; i++)
            for (int ch = 0; ch < 2; ch++)
            {
                interleaved[2 * i + ch] = (float) (0.1 * r.nextGaussian());
                planar[ch][i] = interleaved[2 * i + ch];
            }
        TonalBalance a = prepared(2), b = prepared(2);
        a.setBalance(1.0);
        b.setBalance(1.0);
        b.process(planar, n);
        // In two pieces, to cross a block edge.
        float[] head = java.util.Arrays.copyOfRange(interleaved, 0, 2 * 7001);
        float[] tail = java.util.Arrays.copyOfRange(interleaved, 2 * 7001, 2 * n);
        a.processInterleaved(head, 2);
        a.processInterleaved(tail, 2);
        for (int i = 0; i < n; i++)
            for (int ch = 0; ch < 2; ch++)
            {
                float got = i < 7001 ? head[2 * i + ch] : tail[2 * (i - 7001) + ch];
                assertEquals((float) planar[ch][i], got, 0.0f, "sample " + i + ", channel " + ch);
            }
    }

    @Test
    @DisplayName("reset() and a new prepare() both start again from nothing")
    void resetRestores()
    {
        int n = seconds(1.5);
        double[] x = white(n, 20, 0.1);
        TonalBalance tb = prepared(1);
        tb.setBalance(1.0);
        Result first = run(tb, x);
        tb.reset();
        assertEquals(0.0, tb.getCorrection(30), 0.0);
        assertEquals(-200.0, tb.getLoudness(), 0.0);
        Result second = run(tb, x);
        assertArrayEquals(first.out[0], second.out[0], 0.0);
        tb.prepare(FS, 1);
        Result third = run(tb, x);
        assertArrayEquals(first.out[0], third.out[0], 0.0);
    }

    @Test
    @DisplayName("Samples that are not numbers are taken as silence and nothing breaks")
    void notANumber()
    {
        int n = seconds(1.0);
        double[] x = white(n, 21, 0.1);
        for (int i = 1000; i < n; i += 3001) x[i] = (i & 1) == 0 ? Double.NaN : Double.NEGATIVE_INFINITY;
        TonalBalance tb = prepared(1);
        tb.setBalance(1.0);
        tb.setFillDips(1.0);
        tb.setTrimPeaks(1.0);
        Result r = run(tb, x);
        for (double v : r.out[0]) assertTrue(Double.isFinite(v));
        for (double v : r.curve) assertTrue(Double.isFinite(v));
        for (double v : r.correction) assertTrue(Double.isFinite(v));
        assertTrue(Double.isFinite(r.loudness) && Double.isFinite(r.change));
    }

    @Test
    @DisplayName("The detail moves while playing, at the same latency and without a step")
    void detailMovesWhilePlaying()
    {
        int n = seconds(3.0);
        double w = 2.0 * Math.PI * 1000.0 / FS;
        double[] x = pink(n, 22, 0.03);
        for (int i = 0; i < n; i++) x[i] += 0.25 * Math.sin(w * i);
        TonalBalance tb = prepared(1);
        tb.setBalance(0.0);
        tb.setFillDips(0.0);
        tb.setTrimPeaks(1.0);
        int latency = tb.getLatency();
        double[][] buffer = { x.clone() };
        double[][] piece = { new double[4800] };
        double[] details = { 1.0 / 4.0, 1.0 / 24.0, 1.0, 1.0 / 6.0, 1.0 / 12.0, 1.0 / 2.0 };
        for (int at = 0, k = 0; at < n; at += 4800, k++)
        {
            tb.setDetail(details[(k / 5) % details.length]);
            int m = Math.min(4800, n - at);
            System.arraycopy(x, at, piece[0], 0, m);
            tb.process(piece, m);
            System.arraycopy(piece[0], 0, buffer[0], at, m);
        }
        assertEquals(latency, tb.getLatency());
        // The noise taken off, what is left is a sine whose level glides: no step larger than a sine's own.
        double largest = 0.0;
        for (int i = latency + 1; i < n; i++)
        {
            double a = buffer[0][i] - x[i - latency], b = buffer[0][i - 1] - x[i - 1 - latency];
            largest = Math.max(largest, Math.abs(a - b));
        }
        assertTrue(largest < 0.25 * w * 1.5, "largest step of the change: " + largest);
    }

    @Test
    @DisplayName("Render quality reads more often and lands on the same corrections for a steady programme")
    void renderQuality()
    {
        int n = seconds(4.0);
        double[] x = white(n, 23, 0.1);
        peaking(x, 700.0, 10.0, 3.0);
        TonalBalance realtime = prepared(1), render = new TonalBalance();
        render.setQuality(TonalBalance.Quality.RENDER);
        render.prepare(FS, 1);
        assertEquals(TonalBalance.Quality.RENDER, render.getQualityInUse());
        assertEquals(TonalBalance.Quality.REALTIME, realtime.getQualityInUse());
        Result a = run(realtime, x), b = run(render, x);
        for (int band = 2; band < TonalBalance.BANDS - 2; band++)
            assertEquals(a.correction[band], b.correction[band], 0.5, "band " + band);
        for (int i = pointOf(40.0); i < pointOf(16000.0); i += 7)
            assertEquals(a.curve[i], b.curve[i], 0.75, "curve at " + TonalBalance.getCurveFrequency(i) + " Hz");

        TonalBalance following = new TonalBalance();
        following.setQualityFollowsRender(true);
        following.setOfflineRendering(true);
        following.prepare(FS, 1);
        assertEquals(TonalBalance.Quality.RENDER, following.getQualityInUse());
        assertEquals(TonalBalance.Quality.REALTIME, following.getQuality());
    }

    @Test
    @DisplayName("Every option runs: the even-power reference, the common response, the local slope and the rest")
    void everyOptionRuns()
    {
        int n = seconds(2.0);
        double[] left = pink(n, 24, 0.1), right = white(n, 25, 0.05);
        for (int option = 0; option < 7; option++)
        {
            TonalBalance tb = new TonalBalance();
            switch (option)
            {
                case 0 -> tb.setReference(TonalBalance.Reference.EVEN_POWER);
                case 1 -> { tb.setResponse(TonalBalance.Response.COMMON); tb.setResponseTime(30.0); }
                case 2 -> tb.setLocalSlope(true);
                case 3 -> tb.setLevelCompensation(true);
                case 4 -> tb.setTimeFocus(true);
                case 5 -> tb.setListeningCompensation(12.0);
                default -> { tb.setBalanceBias(3.0); tb.setFloor(60.0); tb.setContrastKnee(1.0); tb.setKnee(6.0); }
            }
            tb.setBalance(1.0);
            tb.setFillDips(1.0);
            tb.setTrimPeaks(1.0);
            tb.prepare(FS, 2);
            Result r = run(tb, left, right);
            for (int ch = 0; ch < 2; ch++)
            {
                for (double v : r.out[ch]) assertTrue(Double.isFinite(v), "option " + option);
                assertTrue(rms(r.out[ch]) > 0.01, "option " + option + " still sounds");
            }
            for (double c : r.correction) assertTrue(Math.abs(c) <= 6.0 + 1e-9, "option " + option + ": " + c);
            if (option != 5) assertEquals(0.0, r.change, 0.02, "option " + option + " keeps the loudness");
        }
    }

    @Test
    @DisplayName("Controls are clamped to their ranges and values that are not numbers ignored")
    void controls()
    {
        TonalBalance tb = new TonalBalance();
        assertEquals(0.5, tb.getBalance(), 0.0);
        assertEquals(0.3, tb.getFillDips(), 0.0);
        assertEquals(0.5, tb.getTrimPeaks(), 0.0);
        assertEquals(0.0, tb.getTilt(), 0.0);
        assertEquals(6.0, tb.getRange(), 0.0);
        assertEquals(3.0, tb.getKnee(), 0.0);
        assertEquals(3.0, tb.getContrastKnee(), 0.0);
        assertEquals(0.25, tb.getDetail(), 0.0);
        assertEquals(30.0, tb.getFloor(), 0.0);
        assertEquals(40.0, tb.getLowFrequency(), 0.0);
        assertEquals(16000.0, tb.getHighFrequency(), 0.0);
        assertEquals(100.0, tb.getReferenceLevel(), 0.0);
        assertEquals(60.0, tb.getResponseTime(), 0.0);
        assertEquals(TonalBalance.Reference.FLAT_LOUDNESS, tb.getReference());
        assertEquals(TonalBalance.Response.BY_BAND, tb.getResponse());
        assertEquals(TonalBalance.Quality.REALTIME, tb.getQuality());

        tb.setBalance(7.0);
        tb.setFillDips(-1.0);
        tb.setTrimPeaks(2.0);
        tb.setTilt(40.0);
        tb.setRange(99.0);
        tb.setKnee(0.0);
        tb.setContrastKnee(100.0);
        tb.setDetail(0.0);
        tb.setRangeKnee(9.0);
        tb.setFloor(1.0);
        tb.setBalanceAsymmetry(-5.0);
        tb.setBalanceBias(50.0);
        tb.setRaising(-3.0);
        tb.setLowering(9.0);
        tb.setResponseTime(1.0);
        tb.setListeningCompensation(-99.0);
        tb.setReferenceLevel(10.0);
        assertEquals(0.0, tb.getRaising(), 0.0);
        assertEquals(1.0, tb.getLowering(), 0.0);
        assertEquals(1.0, tb.getBalance(), 0.0);
        assertEquals(0.0, tb.getFillDips(), 0.0);
        assertEquals(1.0, tb.getTrimPeaks(), 0.0);
        assertEquals(6.0, tb.getTilt(), 0.0);
        assertEquals(24.0, tb.getRange(), 0.0);
        assertEquals(0.1, tb.getKnee(), 0.0);
        assertEquals(12.0, tb.getContrastKnee(), 0.0);
        assertEquals(1.0 / 24.0, tb.getDetail(), 0.0);
        assertEquals(6.0, tb.getRangeKnee(), 0.0);
        assertEquals(10.0, tb.getFloor(), 0.0);
        assertEquals(-1.0, tb.getBalanceAsymmetry(), 0.0);
        assertEquals(6.0, tb.getBalanceBias(), 0.0);
        assertEquals(10.0, tb.getResponseTime(), 0.0);
        assertEquals(-30.0, tb.getListeningCompensation(), 0.0);
        assertEquals(60.0, tb.getReferenceLevel(), 0.0);

        tb.setBalance(Double.NaN);
        tb.setTilt(Double.POSITIVE_INFINITY);
        tb.setRange(Double.NaN);
        tb.setDetail(Double.NaN);
        tb.setFrequencyRange(Double.NaN, 100.0);
        tb.setRaising(Double.NaN);
        tb.setLowering(0.25);
        assertEquals(0.0, tb.getRaising(), 0.0);
        assertEquals(0.25, tb.getLowering(), 0.0);
        tb.setReference(null);
        tb.setResponse(null);
        tb.setQuality(null);
        assertEquals(1.0, tb.getBalance(), 0.0);
        assertEquals(6.0, tb.getTilt(), 0.0);
        assertEquals(24.0, tb.getRange(), 0.0);
        assertEquals(1.0 / 24.0, tb.getDetail(), 0.0);
        assertEquals(40.0, tb.getLowFrequency(), 0.0);
        assertEquals(TonalBalance.Reference.FLAT_LOUDNESS, tb.getReference());
        assertEquals(TonalBalance.Response.BY_BAND, tb.getResponse());
        assertEquals(TonalBalance.Quality.REALTIME, tb.getQuality());
    }

    @Test
    @DisplayName("An unprepared or badly prepared processor leaves the block alone")
    void unprepared()
    {
        TonalBalance tb = new TonalBalance();
        double[][] x = { { 0.1, -0.2, 0.3 } };
        tb.process(x, 3);
        assertArrayEquals(new double[] { 0.1, -0.2, 0.3 }, x[0], 0.0);
        tb.prepare(Double.NaN, 2);
        tb.prepare(48000.0, 0);
        tb.process(x, 3);
        assertArrayEquals(new double[] { 0.1, -0.2, 0.3 }, x[0], 0.0);
        float[] f = { 0.5f, 0.25f };
        tb.processInterleaved(f, 2);
        assertEquals(0.5f, f[0], 0.0f);
    }
}
