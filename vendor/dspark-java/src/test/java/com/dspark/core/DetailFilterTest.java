package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DetailFilter}. */
class DetailFilterTest
{
    private static final double FS = 48000.0;

    private static double[] noise(int n, long seed)
    {
        Random r = new Random(seed);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = 0.25 * r.nextGaussian();
        return x;
    }

    /** A curve in decibels from a function of the frequency. */
    private static double[] curveOf(DoubleUnaryOperator dbAtHz)
    {
        double[] c = new double[DetailFilter.POINTS];
        for (int i = 0; i < c.length; i++) c[i] = dbAtHz.applyAsDouble(DetailFilter.frequency(i));
        return c;
    }

    private static DoubleUnaryOperator bell(double centreHz, double db, double octaves)
    {
        return hz ->
        {
            double d = Math.log(hz / centreHz) / Math.log(2.0) / octaves;
            return db * Math.exp(-0.5 * d * d);
        };
    }

    private static DetailFilter.Design fixed(double[] curve)
    {
        return (channel, c) -> System.arraycopy(curve, 0, c, 0, DetailFilter.POINTS);
    }

    /** The gain in decibels a steady sine takes through a fixed curve, once settled. */
    private static double playedDb(double sampleRate, double hz, double[] curve)
    {
        DetailFilter f = new DetailFilter();
        f.prepare(sampleRate, 1);
        int total = (int) (3.0 * sampleRate);
        double[][] x = { new double[total] };
        double w = 2.0 * Math.PI * hz / sampleRate;
        for (int i = 0; i < total; i++) x[0][i] = 0.25 * Math.sin(w * i);
        f.process(x, total, null, fixed(curve));
        // Over a whole number of periods at the end.
        int periods = (int) Math.floor(hz);
        int n = (int) Math.round(periods * sampleRate / hz);
        double s = 0.0, c = 0.0;
        for (int i = total - n; i < total; i++)
        {
            s += x[0][i] * Math.sin(w * i);
            c += x[0][i] * Math.cos(w * i);
        }
        double amplitude = 2.0 * Math.hypot(s, c) / n;
        return 20.0 * Math.log10(amplitude / 0.25);
    }

    @Test
    @DisplayName("Latency is 774 samples at 44.1 and 48 kHz and doubles by octaves with the rate, a curve every 256")
    void latencyAndHop()
    {
        int[][] expected = {
            { 44100, 774, 256 }, { 48000, 774, 256 }, { 88200, 1548, 512 }, { 96000, 1548, 512 },
            { 176400, 3096, 1024 }, { 192000, 3096, 1024 } };
        for (int[] e : expected)
        {
            DetailFilter f = new DetailFilter();
            f.prepare(e[0], 2);
            assertEquals(e[1], f.getLatency(), "latency at " + e[0]);
            assertEquals(e[2], f.getHopSize(), "hop at " + e[0]);
        }
    }

    @Test
    @DisplayName("A flat curve returns the programme to the bit, late by the latency, at every rate")
    void flatCurveIsTransparent()
    {
        for (double rate : new double[] { 22050.0, 44100.0, 48000.0, 96000.0, 192000.0 })
        {
            DetailFilter f = new DetailFilter();
            f.prepare(rate, 2);
            int total = (int) (0.5 * rate);
            double[][] in = { noise(total, 1), noise(total, 2) };
            double[][] x = { in[0].clone(), in[1].clone() };
            f.process(x, total, null, fixed(new double[DetailFilter.POINTS]));
            int latency = f.getLatency();
            for (int ch = 0; ch < 2; ch++)
            {
                for (int i = 0; i < latency; i++) assertEquals(0.0, x[ch][i], 0.0, "before the latency at " + rate);
                for (int i = latency; i < total; i++)
                    assertEquals(in[ch][i - latency], x[ch][i], 0.0, "rate " + rate + ", channel " + ch + ", sample " + i);
            }
        }
    }

    @Test
    @DisplayName("A gain alike at every frequency is played as that gain")
    void constantGain()
    {
        double[] up = curveOf(hz -> 6.0), down = curveOf(hz -> -9.0);
        for (double hz : new double[] { 40.0, 120.0, 700.0, 1300.0, 2500.0, 9000.0, 17000.0 })
        {
            assertEquals(6.0, playedDb(FS, hz, up), 0.05, hz + " Hz");
            assertEquals(-9.0, playedDb(FS, hz, down), 0.05, hz + " Hz");
        }
    }

    @Test
    @DisplayName("A broad curve is played as drawn across the whole band, the crossover included")
    void broadCurveIsPlayed()
    {
        DoubleUnaryOperator shape = hz -> bell(1200.0, 6.0, 1.0).applyAsDouble(hz) + bell(150.0, -4.0, 0.8).applyAsDouble(hz)
                + bell(8000.0, 3.0, 0.7).applyAsDouble(hz);
        double[] curve = curveOf(shape);
        for (double hz : new double[] { 60.0, 100.0, 150.0, 220.0, 400.0, 700.0, 900.0, 1200.0, 1500.0, 1800.0, 2400.0, 5000.0, 8000.0, 12000.0 })
            assertEquals(shape.applyAsDouble(hz), playedDb(FS, hz, curve), 0.15, hz + " Hz");
    }

    @Test
    @DisplayName("Fine detail is played in the low end: a narrow dip at 100 Hz and what lies beside it")
    void fineDetailInTheLowEnd()
    {
        DoubleUnaryOperator shape = bell(100.0, -6.0, 1.0 / 12.0);
        double[] curve = curveOf(shape);
        assertEquals(-6.0, playedDb(FS, 100.0, curve), 0.5, "the dip");
        assertEquals(shape.applyAsDouble(92.0), playedDb(FS, 92.0, curve), 0.6, "its side under");
        assertEquals(shape.applyAsDouble(109.0), playedDb(FS, 109.0, curve), 0.6, "its side over");
        assertEquals(0.0, playedDb(FS, 70.0, curve), 0.25, "half an octave under");
        assertEquals(0.0, playedDb(FS, 141.0, curve), 0.25, "half an octave over");
        assertEquals(0.0, playedDb(FS, 1000.0, curve), 0.05, "far from it");
    }

    @Test
    @DisplayName("The curve is played the same at other rates")
    void otherRates()
    {
        DoubleUnaryOperator shape = hz -> bell(1000.0, 5.0, 1.0).applyAsDouble(hz) + bell(200.0, -3.0, 0.5).applyAsDouble(hz);
        double[] curve = curveOf(shape);
        for (double rate : new double[] { 44100.0, 96000.0 })
            for (double hz : new double[] { 100.0, 200.0, 500.0, 1000.0, 2000.0, 6000.0 })
                assertEquals(shape.applyAsDouble(hz), playedDb(rate, hz, curve), 0.2, hz + " Hz at " + rate);
    }

    @Test
    @DisplayName("The output does not depend on how the stream is cut into blocks")
    void anyBlocks()
    {
        int total = 30000;
        double[] in = noise(total, 9);
        double[] curve = curveOf(bell(900.0, 7.0, 1.5));
        double[] reference = null;
        for (int block : new int[] { total, 1, 31, 32, 257, 4096 })
        {
            DetailFilter f = new DetailFilter();
            f.prepare(FS, 1);
            double[] out = new double[total];
            double[][] piece = { new double[Math.min(block, total)] };
            for (int at = 0; at < total; at += block)
            {
                int n = Math.min(block, total - at);
                System.arraycopy(in, at, piece[0], 0, n);
                f.process(piece, n, null, fixed(curve));
                System.arraycopy(piece[0], 0, out, at, n);
            }
            if (reference == null) reference = out;
            else assertArrayEquals(reference, out, 0.0, "blocks of " + block);
        }
    }

    @Test
    @DisplayName("The feed callback reads every sample once, in order and untouched, and a curve is drawn every hop")
    void feedAndDesignCadence()
    {
        int total = 20000;
        double[] in = noise(total, 12);
        DetailFilter f = new DetailFilter();
        f.prepare(FS, 1);
        int hop = f.getHopSize();
        int[] fed = { 0 }, designs = { 0 };
        boolean[] ok = { true };
        int position = 0;
        Random r = new Random(1);
        while (position < total)
        {
            int n = Math.min(1 + r.nextInt(700), total - position);
            double[][] piece = { new double[n] };
            System.arraycopy(in, position, piece[0], 0, n);
            final int start = position;
            int[] next = { 0 };
            f.process(piece, n, (offset, count) ->
            {
                ok[0] &= offset == next[0] && count > 0;
                for (int i = 0; i < count; i++) ok[0] &= piece[0][offset + i] == in[start + offset + i];
                next[0] = offset + count;
                fed[0] += count;
            }, (channel, c) ->
            {
                designs[0]++;
                ok[0] &= channel == 0 && c.length == DetailFilter.POINTS;
                java.util.Arrays.fill(c, 0.0);
            });
            ok[0] &= next[0] == n;
            position += n;
        }
        assertTrue(ok[0], "the feed saw every sample once, in order and untouched");
        assertEquals(total, fed[0]);
        assertEquals(total / hop, designs[0]);
        assertEquals(total / hop, f.getCurveCount());
    }

    @Test
    @DisplayName("Linked, one curve is drawn and every channel plays it; apart, each its own")
    void linkedAndApart()
    {
        int total = 24000;
        double[] in = noise(total, 5);
        double[] boost = curveOf(bell(1000.0, 6.0, 1.0)), cut = curveOf(bell(1000.0, -6.0, 1.0));

        DetailFilter linked = new DetailFilter();
        linked.prepare(FS, 2);
        linked.setLinked(true);
        assertTrue(linked.isLinked());
        double[][] a = { in.clone(), in.clone() };
        int[] calls = { 0 };
        linked.process(a, total, null, (channel, c) ->
        {
            calls[0]++;
            assertEquals(0, channel);
            System.arraycopy(boost, 0, c, 0, c.length);
        });
        assertEquals(total / linked.getHopSize(), calls[0]);
        assertArrayEquals(a[0], a[1], 0.0);

        DetailFilter apart = new DetailFilter();
        apart.prepare(FS, 2);
        double[][] b = { in.clone(), in.clone() };
        apart.process(b, total, null, (channel, c) -> System.arraycopy(channel == 0 ? boost : cut, 0, c, 0, c.length));
        assertArrayEquals(a[0], b[0], 0.0, "the boosted channel plays as the linked ones");
        double boosted = 0.0, lowered = 0.0;
        for (int i = total / 2; i < total; i++)
        {
            boosted += b[0][i] * b[0][i];
            lowered += b[1][i] * b[1][i];
        }
        assertTrue(boosted > 1.3 * lowered, "the two channels take their own curves");
    }

    @Test
    @DisplayName("reset() returns the filter to its first state")
    void resetRestores()
    {
        int total = 12000;
        double[] in = noise(total, 21);
        double[] curve = curveOf(bell(300.0, -8.0, 0.6));
        DetailFilter f = new DetailFilter();
        f.prepare(FS, 1);
        double[][] first = { in.clone() };
        f.process(first, total, null, fixed(curve));
        f.reset();
        assertEquals(0, f.getCurveCount());
        double[][] second = { in.clone() };
        f.process(second, total, null, fixed(curve));
        assertArrayEquals(first[0], second[0], 0.0);
    }

    @Test
    @DisplayName("A curve that changes is glided to: no step in the output")
    void movingCurveHasNoStep()
    {
        int total = (int) FS;
        double w = 2.0 * Math.PI * 1000.0 / FS;
        double[][] x = { new double[total] };
        for (int i = 0; i < total; i++) x[0][i] = 0.25 * Math.sin(w * i);
        DetailFilter f = new DetailFilter();
        f.prepare(FS, 1);
        double[] flat = new double[DetailFilter.POINTS], up = curveOf(hz -> 6.0);
        int[] hops = { 0 };
        f.process(x, total, null, (channel, c) -> System.arraycopy(++hops[0] > 60 ? up : flat, 0, c, 0, c.length));
        // The largest step a sine of the final level can make between two samples.
        double allowed = 0.25 * Math.pow(10.0, 6.0 / 20.0) * w * 1.02;
        double largest = 0.0;
        for (int i = 1; i < total; i++) largest = Math.max(largest, Math.abs(x[0][i] - x[0][i - 1]));
        assertTrue(largest <= allowed, "largest step " + largest + " over " + allowed);
        double end = 0.0;
        for (int i = total - 480; i < total; i++) end = Math.max(end, Math.abs(x[0][i]));
        assertEquals(0.25 * Math.pow(10.0, 6.0 / 20.0), end, 0.002, "the final level");
    }

    @Test
    @DisplayName("The filter is minimum phase: nothing sounds before the latency")
    void nothingBeforeTheLatency()
    {
        int total = 24000, at = 6000;
        double[][] x = { new double[total] };
        x[0][at] = 1.0;
        DetailFilter f = new DetailFilter();
        f.prepare(FS, 1);
        double[] curve = curveOf(hz -> bell(100.0, 6.0, 0.5).applyAsDouble(hz) + bell(3000.0, -6.0, 0.7).applyAsDouble(hz));
        f.process(x, total, null, fixed(curve));
        int arrival = at + f.getLatency();
        double before = 0.0, all = 0.0;
        for (int i = 0; i < total; i++)
        {
            all += x[0][i] * x[0][i];
            if (i < arrival) before += x[0][i] * x[0][i];
        }
        assertTrue(all > 0.5, "the impulse came through: " + all);
        assertTrue(before < 1e-6 * all, "energy before the latency: " + before / all);
    }

    @Test
    @DisplayName("Invalid layouts are ignored and an unprepared filter leaves the block alone")
    void invalidLayouts()
    {
        DetailFilter f = new DetailFilter();
        f.prepare(Double.NaN, 2);
        f.prepare(48000.0, 0);
        double[][] x = { { 0.1, 0.2, 0.3 } };
        f.process(x, 3, null, fixed(new double[DetailFilter.POINTS]));
        assertArrayEquals(new double[] { 0.1, 0.2, 0.3 }, x[0], 0.0);
        assertEquals(0, f.getLatency());
    }
}
