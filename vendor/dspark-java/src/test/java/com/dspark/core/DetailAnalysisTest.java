package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DetailAnalysis}. */
class DetailAnalysisTest
{
    private static final double FS = 48000.0;
    private static final int HOP = 256;

    private static int pointOf(double hz)
    {
        return (int) Math.round((DetailAnalysis.POINTS - 1) * Math.log(hz / 20.0) / Math.log(1000.0));
    }

    private static double[] sine(double hz, double amplitude, int samples)
    {
        double[] x = new double[samples];
        double w = 2.0 * Math.PI * hz / FS;
        for (int i = 0; i < samples; i++) x[i] = amplitude * Math.sin(w * i);
        return x;
    }

    /** Feeds a channel set hop by hop, with an update after each. */
    private static void run(DetailAnalysis a, double[][] x, int chunk)
    {
        int samples = x[0].length;
        for (int at = 0; at < samples;)
        {
            int toHop = HOP - at % HOP;
            int m = Math.min(Math.min(chunk, toHop), samples - at);
            for (int ch = 0; ch < x.length; ch++) a.feed(ch, x[ch], at, m);
            at += m;
            if (at % HOP == 0) a.update(x.length);
        }
    }

    private static double[] readingOf(double hz, double amplitude, double width, boolean exhaustive)
    {
        DetailAnalysis a = new DetailAnalysis();
        a.setExhaustive(exhaustive);
        a.prepare(FS, 1);
        a.setWidth(width);
        run(a, new double[][] { sine(hz, amplitude, (int) (1.5 * FS)) }, HOP);
        double[] power = new double[DetailAnalysis.POINTS];
        a.read(0, power);
        return power;
    }

    @Test
    @DisplayName("The grid runs evenly in log frequency from 20 Hz to 20 kHz")
    void grid()
    {
        assertEquals(20.0, DetailAnalysis.frequency(0), 1e-12);
        assertEquals(20000.0, DetailAnalysis.frequency(DetailAnalysis.POINTS - 1), 1e-8);
        double step = DetailAnalysis.frequency(1) / DetailAnalysis.frequency(0);
        for (int i = 2; i < DetailAnalysis.POINTS; i += 97)
            assertEquals(step, DetailAnalysis.frequency(i) / DetailAnalysis.frequency(i - 1), 1e-12);
        assertEquals(20.0, DetailAnalysis.frequency(-3), 1e-12, "points out of range are clamped");
    }

    @Test
    @DisplayName("A full-scale sine reads 1 at its point to 0.4 dB, at every frequency and every width")
    void sineReadsItsLevel()
    {
        // Between two bins of a transform the line reads a little under its
        // level (the window's own shape); where the band is wider than the
        // line's lobe it reads all of it.
        for (double width : new double[] { 1.0 / 24.0, 1.0 / 12.0, 1.0 / 4.0, 1.0 })
            for (double nominal : new double[] { 50.0, 100.0, 440.0, 1000.0, 5000.0, 15000.0 })
            {
                int p = pointOf(nominal);
                double hz = DetailAnalysis.frequency(p);
                double[] power = readingOf(hz, 1.0, width, false);
                assertEquals(1.0, power[p], 0.09, "width " + width + ", " + hz + " Hz");
                assertTrue(power[p] <= 1.0 + 1e-3, "never over its level: " + power[p]);
            }
        // At the top a band of an octave holds the whole lobe on its flat part: the level to two parts in a hundred.
        for (double nominal : new double[] { 5000.0, 10000.0, 15000.0 })
        {
            int p = pointOf(nominal);
            assertEquals(1.0, readingOf(DetailAnalysis.frequency(p), 1.0, 1.0, false)[p], 0.02, nominal + " Hz through an octave");
        }
    }

    @Test
    @DisplayName("The reading is the power: half the amplitude reads a quarter")
    void readingIsPower()
    {
        int p = pointOf(1000.0);
        double[] power = readingOf(DetailAnalysis.frequency(p), 0.5, 1.0 / 12.0, false);
        assertEquals(0.25, power[p], 0.015);
    }

    @Test
    @DisplayName("A line between two points is in both, and nothing reads away from it")
    void lineBetweenPoints()
    {
        int p = pointOf(1000.0);
        double between = Math.sqrt(DetailAnalysis.frequency(p) * DetailAnalysis.frequency(p + 1));
        double[] power = readingOf(between, 1.0, 1.0 / 12.0, false);
        assertEquals(1.0, power[p], 0.1);
        assertEquals(1.0, power[p + 1], 0.1);
        assertEquals(power[p], power[p + 1], 0.05, "alike in both");
        assertTrue(power[pointOf(250.0)] < 1e-7, "two octaves under: " + power[pointOf(250.0)]);
        assertTrue(power[pointOf(4000.0)] < 1e-7, "two octaves over: " + power[pointOf(4000.0)]);
    }

    @Test
    @DisplayName("A noise reads the power inside the band of each point, where that band is wider than the window's own line")
    void noiseReadsItsBand()
    {
        final double width = 1.0, variance = 0.25;
        DetailAnalysis a = new DetailAnalysis();
        a.setExhaustive(true);
        a.prepare(FS, 1);
        a.setWidth(width);
        Random r = new Random(23);
        int samples = (int) (6 * FS);
        double[] x = new double[samples];
        for (int i = 0; i < samples; i++) x[i] = Math.sqrt(variance) * r.nextGaussian();
        double[] power = new double[DetailAnalysis.POINTS], mean = new double[DetailAnalysis.POINTS];
        int reads = 0;
        for (int at = 0; at + HOP <= samples; at += HOP)
        {
            a.feed(0, x, at, HOP);
            a.update(1);
            if (at < FS) continue;
            a.read(0, power);
            for (int i = 0; i < power.length; i++) mean[i] += power[i];
            reads++;
        }
        double up = Math.pow(2.0, 0.5 * width), down = Math.pow(2.0, -0.5 * width);
        double halfStep = Math.pow(1000.0, 0.5 / (DetailAnalysis.POINTS - 1));
        for (double hz : new double[] { 1000.0, 2000.0, 4000.0, 10000.0 })
        {
            int p = pointOf(hz);
            double f = DetailAnalysis.frequency(p);
            // A band that rises from the width's lower edge to a top one step of the grid across, and falls to the upper one.
            double band = f * (0.5 * (up + halfStep) - 0.5 * (1.0 / halfStep + down));
            double expected = 4.0 * variance * band / FS;
            assertEquals(1.0, mean[p] / reads / expected, 0.06, hz + " Hz");
        }
    }

    @Test
    @DisplayName("Linked channels read the mean of their powers; apart, each its own")
    void linkedAndApart()
    {
        int p = pointOf(1000.0);
        double[][] x = { sine(DetailAnalysis.frequency(p), 1.0, (int) FS), new double[(int) FS] };
        double[] power = new double[DetailAnalysis.POINTS];

        DetailAnalysis apart = new DetailAnalysis();
        apart.prepare(FS, 2);
        run(apart, x, HOP);
        apart.read(0, power);
        assertEquals(1.0, power[p], 0.06);
        apart.read(1, power);
        assertEquals(0.0, power[p], 1e-12);

        DetailAnalysis linked = new DetailAnalysis();
        linked.setLinked(true);
        linked.prepare(FS, 2);
        run(linked, x, HOP);
        linked.read(0, power);
        assertEquals(0.5, power[p], 0.03);
        linked.read(1, power);
        assertEquals(0.5, power[p], 0.03);
        linked.read(7, power);
        assertEquals(0.0, power[p], 0.0, "a channel out of range reads nothing");
    }

    @Test
    @DisplayName("The reading does not depend on how the samples are fed")
    void feedingInAnyPieces()
    {
        Random r = new Random(4);
        int samples = (int) FS;
        double[][] x = { new double[samples] };
        for (int i = 0; i < samples; i++) x[0][i] = 0.3 * r.nextGaussian() + 0.4 * Math.sin(0.01 * i);
        double[] reference = null;
        for (int chunk : new int[] { HOP, 1, 13, 100 })
        {
            DetailAnalysis a = new DetailAnalysis();
            a.prepare(FS, 1);
            run(a, x, chunk);
            double[] power = new double[DetailAnalysis.POINTS];
            a.read(0, power);
            if (reference == null) reference = power;
            else
                for (int i = 0; i < power.length; i++) assertEquals(reference[i], power[i], 0.0, "chunk " + chunk + ", point " + i);
        }
    }

    @Test
    @DisplayName("Read in turns or at every hop, a steady signal reads the same")
    void turnsAndExhaustive()
    {
        for (double nominal : new double[] { 60.0, 300.0, 2000.0 })
        {
            int p = pointOf(nominal);
            double hz = DetailAnalysis.frequency(p);
            double inTurns = readingOf(hz, 0.8, 1.0 / 12.0, false)[p];
            double atEveryHop = readingOf(hz, 0.8, 1.0 / 12.0, true)[p];
            assertEquals(inTurns, atEveryHop, 0.02 * inTurns, hz + " Hz");
        }
    }

    @Test
    @DisplayName("The window is long in the low end and short at the top, within its bounds")
    void windowFollowsFrequency()
    {
        DetailAnalysis a = new DetailAnalysis();
        a.prepare(FS, 1);
        a.setWidth(1.0 / 12.0);
        assertEquals(16384.0 / FS, a.getWindowSeconds(0), 1e-12, "the longest window at 20 Hz");
        assertEquals(1024.0 / FS, a.getWindowSeconds(DetailAnalysis.POINTS - 1), 1e-12, "the shortest at 20 kHz");
        for (int i = 1; i < DetailAnalysis.POINTS; i++)
            assertTrue(a.getWindowSeconds(i) <= a.getWindowSeconds(i - 1) + 1e-15, "not falling at " + i);
        // Four periods of the width where that lies inside the bounds.
        int p = pointOf(1000.0);
        assertEquals(4.0 / (DetailAnalysis.frequency(p) / 12.0), a.getWindowSeconds(p), 1e-9);
        // A broader width asks for a shorter window.
        double fine = a.getWindowSeconds(pointOf(300.0));
        a.setWidth(1.0);
        assertTrue(a.getWindowSeconds(pointOf(300.0)) < fine);
    }

    @Test
    @DisplayName("reset() clears what was read, and the widths are clamped")
    void resetAndClamps()
    {
        DetailAnalysis a = new DetailAnalysis();
        a.prepare(FS, 1);
        run(a, new double[][] { sine(1000.0, 1.0, (int) FS) }, HOP);
        a.reset();
        a.update(1);
        double[] power = new double[DetailAnalysis.POINTS];
        a.read(0, power);
        for (double v : power) assertEquals(0.0, v, 0.0);

        a.setWidth(10.0);
        assertEquals(DetailAnalysis.WIDEST, a.getWidth(), 0.0);
        a.setWidth(0.0);
        assertEquals(DetailAnalysis.FINEST, a.getWidth(), 0.0);
        a.setWidth(Double.NaN);
        assertEquals(DetailAnalysis.FINEST, a.getWidth(), 0.0);
    }

    @Test
    @DisplayName("Samples that are not numbers are read as silence")
    void notANumber()
    {
        DetailAnalysis a = new DetailAnalysis();
        a.prepare(FS, 1);
        double[] x = sine(1000.0, 1.0, (int) FS);
        for (int i = 0; i < x.length; i += 500) x[i] = (i % 1000 == 0) ? Double.NaN : Double.POSITIVE_INFINITY;
        run(a, new double[][] { x }, HOP);
        double[] power = new double[DetailAnalysis.POINTS];
        a.read(0, power);
        for (double v : power) assertTrue(Double.isFinite(v));
    }

    @Test
    @DisplayName("The window scales by octaves with the rate, so the reading holds at 96 kHz")
    void higherRate()
    {
        DetailAnalysis a = new DetailAnalysis();
        a.prepare(96000.0, 1);
        a.setWidth(1.0 / 12.0);
        assertEquals(2048.0 / 96000.0, a.getWindowSeconds(DetailAnalysis.POINTS - 1), 1e-12);
        int p = pointOf(1000.0);
        double hz = DetailAnalysis.frequency(p);
        int samples = 2 * 96000;
        double[] x = new double[samples];
        for (int i = 0; i < samples; i++) x[i] = Math.sin(2.0 * Math.PI * hz * i / 96000.0);
        for (int at = 0; at + 512 <= samples; at += 512)
        {
            a.feed(0, x, at, 512);
            a.update(1);
        }
        double[] power = new double[DetailAnalysis.POINTS];
        a.read(0, power);
        assertEquals(1.0, power[p], 0.06);
    }
}
