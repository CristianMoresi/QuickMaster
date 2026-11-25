package com.quickmaster.processing.eq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of the offline render of the Auto EQ: its stretches, its joins and what it keeps for display. */
class TonalBalanceRenderTest
{
    private static final int RATE = 22050;
    private static final TonalBalanceRender.Settings FULL =
            TonalBalanceRender.Settings.even(1.0, 1.0, 1.0, 1.0 / 4.0, 12.0);

    /** A programme whose tone moves: dark and bright stretches, a tone that comes and goes, level changes. */
    private static float[] programme(double seconds, int channels, long seed)
    {
        int frames = (int) (seconds * RATE);
        float[] x = new float[frames * channels];
        Random r = new Random(seed);
        double low = 0.0, lower = 0.0;
        for (int f = 0; f < frames; f++)
        {
            double t = (double) f / RATE;
            double white = r.nextGaussian();
            low += 0.08 * (white - low);
            lower += 0.01 * (white - lower);
            double section = Math.floor(t / 7.0) % 3.0;
            double noise = section == 0.0 ? 0.08 * white : section == 1.0 ? 0.6 * low : 0.3 * low + 2.0 * lower;
            double tone = (t % 11.0 < 5.0 ? 0.12 : 0.0) * Math.sin(2.0 * Math.PI * 1800.0 * t);
            double level = 0.6 + 0.4 * Math.sin(2.0 * Math.PI * t / 23.0);
            double v = level * (noise + tone);
            x[f * channels] = (float) v;
            if (channels == 2) x[f * channels + 1] = (float) (0.7 * v + 0.02 * r.nextGaussian());
        }
        return x;
    }

    private static double rms(float[] x, int from, int to)
    {
        double s = 0.0;
        for (int i = from; i < to; i++) s += (double) x[i] * x[i];
        return Math.sqrt(s / Math.max(1, to - from));
    }

    @Test
    @DisplayName("A render is the same whatever the threads it is given")
    void sameOnAnyThreads()
    {
        float[] x = programme(100.0, 2, 1);
        var one = TonalBalanceRender.render(x, 2, RATE, FULL, 1, () -> false);
        var four = TonalBalanceRender.render(x, 2, RATE, FULL, 4, () -> false);
        var many = TonalBalanceRender.render(x, 2, RATE, FULL, 64, () -> false);
        assertArrayEquals(one.samples(), four.samples(), 0.0f);
        assertArrayEquals(one.samples(), many.samples(), 0.0f);
        assertArrayEquals(one.curves(), four.curves());
        assertArrayEquals(one.curves(), many.curves());
        assertArrayEquals(one.joinMismatchDb(), four.joinMismatchDb(), 0.0);
        assertArrayEquals(one.joinMismatchDb(), many.joinMismatchDb(), 0.0);
        assertEquals(one.carriedOn(), four.carriedOn());
        assertEquals(one.carriedOn(), many.carriedOn());
        assertEquals(4, one.joinMismatchDb().length, "five stretches of twenty seconds");
        assertEquals(x.length, one.samples().length);
    }

    @Test
    @DisplayName("Two stretches that draw the same curves at their join render as one piece does, with no seam")
    void stretchesMatchOnePiece()
    {
        float[] x = programme(100.0, 1, 2);
        var onePiece = TonalBalanceRender.render(x, 1, RATE, FULL, 1, () -> false, TonalBalanceRender.Plan.ONE_PIECE);
        // Every join a crossfade, whatever the curves say: what the warm-up alone achieves.
        var stretches = TonalBalanceRender.render(x, 1, RATE, FULL, 4, () -> false,
                new TonalBalanceRender.Plan(30.0, 15.0, Double.POSITIVE_INFINITY));
        assertEquals(0, stretches.carriedOn());
        assertEquals(0, onePiece.joinMismatchDb().length);
        for (double mismatch : stretches.joinMismatchDb())
            assertTrue(mismatch < 0.05, "the curves of the two stretches at a join: " + mismatch + " dB apart");
        float[] a = onePiece.samples(), b = stretches.samples();
        float[] difference = new float[a.length];
        for (int i = 0; i < a.length; i++) difference[i] = a[i] - b[i];

        // The first stretch is the one piece's own beginning.
        int firstEnd = (int) ((long) a.length / 3);
        for (int i = 0; i < firstEnd; i++) assertEquals(a[i], b[i], 0.0f, "sample " + i + " of the first stretch");

        // After a join the two differ by what the warm-up leaves of an older past: measured at
        // 106 dB under the programme over the two later stretches, 74 dB at the worst frame.
        double programmeRms = rms(a, 0, a.length), differenceRms = rms(difference, firstEnd, a.length);
        assertTrue(differenceRms < 1e-4 * programmeRms,
                "difference " + 20.0 * Math.log10(differenceRms / programmeRms) + " dB under the programme");
        double worst = 0.0;
        for (float d : difference) worst = Math.max(worst, Math.abs(d));
        assertTrue(worst < 1e-3 * programmeRms,
                "largest difference " + 20.0 * Math.log10(worst / programmeRms) + " dB under the programme");

        // Nothing steps at a join, and seconds later the two are one to the precision of the samples.
        for (int k = 1; k < 3; k++)
        {
            int at = (int) ((long) k * a.length / 3);
            double around = rms(difference, at - 200, at + RATE / 5);
            assertTrue(around < 2e-4 * programmeRms, "join " + k + ": " + 20.0 * Math.log10(around / programmeRms) + " dB");
            double later = rms(difference, at + 16 * RATE, at + 17 * RATE);
            assertTrue(later < 1e-6 * programmeRms, "16 s after join " + k + ": " + 20.0 * Math.log10(later / programmeRms) + " dB");
        }
    }

    /** A loud, bass-heavy opening, then a long passage that is all but silent, then the loud one again. */
    private static float[] programmeWithABreak()
    {
        int frames = 48 * RATE;
        float[] x = new float[frames];
        Random r = new Random(8);
        double low = 0.0;
        for (int f = 0; f < frames; f++)
        {
            double t = (double) f / RATE, white = r.nextGaussian();
            low += 0.02 * (white - low);
            boolean loud = t < 12.0 || t >= 40.0;
            x[f] = (float) (loud ? 0.05 * white + 1.2 * low + 0.2 * Math.sin(2.0 * Math.PI * 55.0 * t)
                                 : 0.0005 * Math.sin(2.0 * Math.PI * 440.0 * t));
        }
        return x;
    }

    @Test
    @DisplayName("Where a stretch lacks a past that still counts, the stream before it carries on and the render is the one piece again")
    void aJoinThatDoesNotMatchIsCarriedOn()
    {
        float[] x = programmeWithABreak();
        var settings = TonalBalanceRender.Settings.even(0.5, 0.5, 0.3, 1.0 / 4.0, 6.0);
        // Stretches of twelve seconds warmed up on four: the one that begins inside the quiet
        // passage never hears the loud opening, which the stream before it still remembers.
        var onePiece = TonalBalanceRender.render(x, 1, RATE, settings, 1, () -> false, new TonalBalanceRender.Plan(1e9, 4.0, 0.0));
        var unchecked = TonalBalanceRender.render(x, 1, RATE, settings, 4, () -> false,
                new TonalBalanceRender.Plan(12.0, 4.0, Double.POSITIVE_INFINITY));
        var checked = TonalBalanceRender.render(x, 1, RATE, settings, 4, () -> false, new TonalBalanceRender.Plan(12.0, 4.0, 0.005));
        var alone = TonalBalanceRender.render(x, 1, RATE, settings, 1, () -> false, new TonalBalanceRender.Plan(12.0, 4.0, 0.005));

        assertEquals(3, checked.joinMismatchDb().length);
        double largest = 0.0;
        for (double m : checked.joinMismatchDb()) largest = Math.max(largest, m);
        assertTrue(largest > 0.05, "a join of this programme does not match: " + largest + " dB");
        assertTrue(checked.carriedOn() >= 1, "so a stream carries on");
        assertEquals(0, unchecked.carriedOn());
        assertArrayEquals(checked.samples(), alone.samples(), 0.0f, "the same on one thread as on four");
        assertEquals(checked.carriedOn(), alone.carriedOn());

        // Where the loud programme comes back, the unchecked stretches differ from the one piece; the checked ones do not.
        double apartUnchecked = 0.0, apartChecked = 0.0, level = 0.0;
        for (int i = 40 * RATE; i < 42 * RATE; i++)
        {
            apartUnchecked += Math.pow(unchecked.samples()[i] - onePiece.samples()[i], 2);
            apartChecked += Math.pow(checked.samples()[i] - onePiece.samples()[i], 2);
            level += Math.pow(onePiece.samples()[i], 2);
        }
        assertTrue(apartChecked < 1e-8 * level,
                "checked: " + 10.0 * Math.log10(apartChecked / level + 1e-30) + " dB under the programme where it comes back");
        assertTrue(apartUnchecked > 100.0 * apartChecked,
                "unchecked: " + 10.0 * Math.log10(apartUnchecked / level + 1e-30) + " dB, checked: "
                        + 10.0 * Math.log10(apartChecked / level + 1e-30) + " dB");
        double worst = 0.0, peak = 0.0;
        for (int i = 0; i < x.length; i++)
        {
            worst = Math.max(worst, Math.abs(checked.samples()[i] - onePiece.samples()[i]));
            peak = Math.max(peak, Math.abs(onePiece.samples()[i]));
        }
        assertTrue(worst < 1e-3 * peak, "largest difference from the one piece: " + 20.0 * Math.log10(worst / peak) + " dB under its peak");
    }

    @Test
    @DisplayName("A stream that carries on through every join renders the one piece, to the bit")
    void carriedOnThroughEveryJoinIsTheOnePiece()
    {
        float[] x = programme(42.0, 2, 9);
        var onePiece = TonalBalanceRender.render(x, 2, RATE, FULL, 1, () -> false, new TonalBalanceRender.Plan(1e9, 6.0, 0.0));
        // No two stretches agree under this tolerance: the first stream takes every stretch, each in its turn,
        // those taken ahead of their turn by a stream that does not stand included.
        var never = new TonalBalanceRender.Plan(6.0, 6.0, -1.0);
        var chained = TonalBalanceRender.render(x, 2, RATE, FULL, 4, () -> false, never);
        assertEquals(6, chained.joinMismatchDb().length);
        assertEquals(6, chained.carriedOn());
        assertEquals(6, chained.rounds(), "each stretch waits for the one before it");
        assertArrayEquals(onePiece.samples(), chained.samples(), 0.0f);

        var alone = TonalBalanceRender.render(x, 2, RATE, FULL, 1, () -> false, never);
        assertArrayEquals(chained.samples(), alone.samples(), 0.0f, "the same on one thread as on four");
        assertArrayEquals(chained.curves(), alone.curves());
        assertEquals(chained.rounds(), alone.rounds());
    }

    /** Loud and bass-heavy, but for two passages that are all but silent: 22 to 45 s and 82 to 105 s. */
    private static float[] programmeWithTwoBreaks()
    {
        int frames = 120 * RATE;
        float[] x = new float[frames];
        Random r = new Random(8);
        double low = 0.0;
        for (int f = 0; f < frames; f++)
        {
            double t = (double) f / RATE, white = r.nextGaussian();
            low += 0.02 * (white - low);
            boolean loud = t < 22.0 || (t >= 45.0 && t < 82.0) || t >= 105.0;
            x[f] = (float) (loud ? 0.05 * white + 1.2 * low + 0.2 * Math.sin(2.0 * Math.PI * 55.0 * t)
                                 : 0.0005 * Math.sin(2.0 * Math.PI * 440.0 * t));
        }
        return x;
    }

    @Test
    @DisplayName("Joins that follow one another are settled in order, and those of passages apart in the same pass")
    void passagesApartAreSettledTogether()
    {
        float[] x = programmeWithTwoBreaks();
        var settings = TonalBalanceRender.Settings.even(0.5, 0.5, 0.3, 1.0 / 4.0, 6.0);
        // Stretches of ten seconds warmed up on fifteen: in each quiet passage two joins in a row leave the
        // stream that came from the loud programme (measured: 0.02 and 3.8 dB; under 0.001 dB elsewhere).
        var plan = new TonalBalanceRender.Plan(10.0, 15.0, 0.005);
        var onePiece = TonalBalanceRender.render(x, 1, RATE, settings, 1, () -> false, new TonalBalanceRender.Plan(1e9, 15.0, 0.0));
        var checked = TonalBalanceRender.render(x, 1, RATE, settings, 8, () -> false, plan);
        var fewer = TonalBalanceRender.render(x, 1, RATE, settings, 3, () -> false, plan);

        double[] mismatch = checked.joinMismatchDb();
        assertEquals(11, mismatch.length);
        assertTrue(mismatch[3] > 0.5 && mismatch[9] > 0.5,
                "a stretch that begins inside a quiet passage: " + mismatch[3] + " and " + mismatch[9] + " dB");
        for (int join : new int[] { 1, 2, 5, 6, 7, 8, 11 })
            assertTrue(mismatch[join - 1] < 0.005, "join " + join + " in the loud programme: " + mismatch[join - 1] + " dB");
        assertTrue(checked.carriedOn() >= 2, "a stream carries on in each passage: " + checked.carriedOn());
        assertTrue(checked.rounds() < checked.carriedOn(),
                checked.carriedOn() + " stretches carried on in " + checked.rounds() + " passes");
        assertArrayEquals(checked.samples(), fewer.samples(), 0.0f, "the same on three threads as on eight");
        assertEquals(checked.carriedOn(), fewer.carriedOn());
        assertEquals(checked.rounds(), fewer.rounds());

        // Measured: 102 dB under the programme over the whole of it, 96 dB under its peak at the worst sample.
        double apart = 0.0, level = 0.0, worst = 0.0, peak = 0.0;
        for (int i = 0; i < x.length; i++)
        {
            double d = checked.samples()[i] - onePiece.samples()[i];
            apart += d * d;
            level += Math.pow(onePiece.samples()[i], 2);
            worst = Math.max(worst, Math.abs(d));
            peak = Math.max(peak, Math.abs(onePiece.samples()[i]));
        }
        assertTrue(apart < 1e-9 * level, "from the one piece: " + 10.0 * Math.log10(apart / level + 1e-30) + " dB under the programme");
        assertTrue(worst < 1e-4 * peak, "largest difference from the one piece: " + 20.0 * Math.log10(worst / peak + 1e-30) + " dB under its peak");
    }

    @Test
    @DisplayName("A mono programme takes the correction of its stereo copy")
    void monoIsItsStereoCopy()
    {
        float[] mono = programme(12.0, 1, 3);
        float[] stereo = new float[2 * mono.length];
        for (int f = 0; f < mono.length; f++) stereo[2 * f] = stereo[2 * f + 1] = mono[f];
        var m = TonalBalanceRender.render(mono, 1, RATE, FULL, 1, () -> false);
        var s = TonalBalanceRender.render(stereo, 2, RATE, FULL, 1, () -> false);
        for (int f = 0; f < mono.length; f++)
        {
            assertEquals(m.samples()[f], s.samples()[2 * f], 0.0f, "left " + f);
            assertEquals(m.samples()[f], s.samples()[2 * f + 1], 0.0f, "right " + f);
        }
        assertArrayEquals(m.curves(), s.curves());
    }

    @Test
    @DisplayName("The render is as long as the source and aligned with it")
    void alignedWithTheSource()
    {
        float[] x = programme(8.0, 1, 4);
        var settings = TonalBalanceRender.Settings.even(0.5, 0.5, 0.3, 1.0 / 4.0, 6.0);
        float[] y = TonalBalanceRender.render(x, 1, RATE, settings, 1, () -> false).samples();
        assertEquals(x.length, y.length);
        int best = 0;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int lag = -40; lag <= 40; lag++)
        {
            double sum = 0.0;
            for (int i = RATE; i < x.length - RATE; i++) sum += (double) x[i] * y[i + lag];
            if (sum > bestValue)
            {
                bestValue = sum;
                best = lag;
            }
        }
        assertEquals(0, best, "the lag at which the render matches the source best");
    }

    @Test
    @DisplayName("The opening is already balanced: the correction at the first frame is that of the frames after it")
    void openingIsBalanced()
    {
        int frames = 6 * RATE;
        float[] x = new float[frames];
        Random r = new Random(5);
        for (int i = 0; i < frames; i++) x[i] = (float) (0.1 * r.nextGaussian());
        var balance = TonalBalanceRender.Settings.even(1.0, 0.0, 0.0, 1.0 / 4.0, 12.0);
        var result = TonalBalanceRender.render(x, 1, RATE, balance, 1, () -> false);
        int points = TonalBalanceRender.CURVE_POINTS;
        int top = (int) TonalBalanceRender.curvePosition(6000.0), bottom = (int) TonalBalanceRender.curvePosition(150.0);
        short[] c = result.curves();
        int later = result.curveCount() / 2;
        double tiltAtStart = 0.01 * (c[top] - c[bottom]);
        double tiltLater = 0.01 * (c[later * points + top] - c[later * points + bottom]);
        assertTrue(tiltLater < -3.0, "a white noise is darkened: " + tiltLater + " dB from 150 Hz to 6 kHz");
        assertEquals(tiltLater, tiltAtStart, 1.0, "and from its first frame");
    }

    @Test
    @DisplayName("A curve is kept about twelve times a second, within the range, along the whole programme")
    void curvesKept()
    {
        float[] x = programme(40.0, 2, 6);
        var settings = new TonalBalanceRender.Settings(1.0, 1.0, 1.0, 0.5, 1.0, 1.0 / 12.0, 3.0);
        var result = TonalBalanceRender.render(x, 2, RATE, settings, 2, () -> false, new TonalBalanceRender.Plan(15.0, 15.0, 0.005));
        int frames = x.length / 2;
        assertEquals((frames + result.curveSpacing() - 1) / result.curveSpacing(), result.curveCount());
        assertEquals(12.0, (double) RATE / result.curveSpacing(), 2.5);
        assertEquals(result.curveCount() * TonalBalanceRender.CURVE_POINTS, result.curves().length);
        int moving = 0;
        for (int g = 0; g < result.curveCount(); g++)
        {
            boolean any = false;
            for (int i = 0; i < TonalBalanceRender.CURVE_POINTS; i++)
            {
                short v = result.curves()[g * TonalBalanceRender.CURVE_POINTS + i];
                assertTrue(Math.abs(v) <= 300, "curve " + g + " point " + i + ": " + v);
                any |= v != 0;
            }
            if (any) moving++;
        }
        assertEquals(result.curveCount(), moving, "every curve, across the joins too, is drawn");
        assertEquals(20.0, TonalBalanceRender.curveFrequency(0), 1e-9);
        assertEquals(1000.0, TonalBalanceRender.curveFrequency((int) Math.round(TonalBalanceRender.curvePosition(1000.0))), 10.0);
        assertEquals(0.0, TonalBalanceRender.curvePosition(5.0), 0.0);
        assertEquals(TonalBalanceRender.CURVE_POINTS - 1.0, TonalBalanceRender.curvePosition(30000.0), 0.0);
    }

    @Test
    @DisplayName("A render can be stopped, on one thread or on several")
    void cancellable()
    {
        float[] x = programme(70.0, 1, 7);
        for (int workers : new int[] { 1, 3 })
        {
            AtomicInteger polls = new AtomicInteger();
            assertThrows(CancellationException.class,
                    () -> TonalBalanceRender.render(x, 1, RATE, FULL, workers, () -> polls.incrementAndGet() > 20));
        }
        assertThrows(CancellationException.class, () -> TonalBalanceRender.render(x, 1, RATE, FULL, 2, () -> true));
    }

    @Test
    @DisplayName("Programmes of a few frames are rendered too")
    void tinyProgrammes()
    {
        for (int frames : new int[] { 1, 2, 7, 300 })
        {
            float[] x = new float[2 * frames];
            for (int i = 0; i < x.length; i++) x[i] = (float) (0.2 * Math.sin(0.3 * i));
            var result = TonalBalanceRender.render(x, 2, 48000, FULL, 4, () -> false);
            assertEquals(x.length, result.samples().length);
            for (float v : result.samples()) assertTrue(Float.isFinite(v));
            assertEquals(1, result.curveCount());
        }
    }

    @Test
    @DisplayName("Outside the programme a position reads the programme mirrored at its ends")
    void mirroredPositions()
    {
        assertEquals(0, TonalBalanceRender.mirrored(0, 5));
        assertEquals(4, TonalBalanceRender.mirrored(4, 5));
        assertEquals(0, TonalBalanceRender.mirrored(-1, 5));
        assertEquals(1, TonalBalanceRender.mirrored(-2, 5));
        assertEquals(4, TonalBalanceRender.mirrored(-5, 5));
        assertEquals(4, TonalBalanceRender.mirrored(-6, 5));
        assertEquals(4, TonalBalanceRender.mirrored(5, 5));
        assertEquals(3, TonalBalanceRender.mirrored(6, 5));
        assertEquals(0, TonalBalanceRender.mirrored(9, 5));
        assertEquals(0, TonalBalanceRender.mirrored(10, 5));
        assertEquals(0, TonalBalanceRender.mirrored(-123456789L, 1));
        for (long p = -1000; p < 1000; p++)
        {
            int f = TonalBalanceRender.mirrored(p, 37);
            assertTrue(f >= 0 && f < 37);
        }
    }

    @Test
    @DisplayName("Settings that can correct nothing say so")
    void asksNothing()
    {
        assertTrue(new TonalBalanceRender.Settings(0.0, 0.0, 0.0, 0.3, 1.0, 0.25, 6.0).asksNothing());
        assertTrue(TonalBalanceRender.Settings.even(1.0, 1.0, 1.0, 0.25, 0.0).asksNothing());
        assertTrue(!TonalBalanceRender.Settings.even(0.0, 0.1, 0.0, 0.25, 6.0).asksNothing());
        assertTrue(!new TonalBalanceRender.Settings(0.1, 0.0, 0.0, 0.0, 1.0, 0.25, 6.0).asksNothing());
        assertTrue(new TonalBalanceRender.Settings(1.0, 1.0, 1.0, 0.0, 0.0, 0.25, 6.0).asksNothing());
    }
}
