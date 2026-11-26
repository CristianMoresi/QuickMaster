package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CabinetModel}: the C++ suite's cases (TestCoreFilters.cpp) and the common properties. */
class CabinetModelTest
{
    /** Gain in dB of a cabinet on a steady tone (measured over the second half of a second). */
    private static double cabToneDb(CabinetModel cab, double hz)
    {
        final int n = 48000;
        final double[] x = new double[n];
        for (int i = 0; i < n; ++i)
            x[i] = (float) (0.25 * Math.sin(2.0 * 3.14159265358979323846 * hz * i / 48000.0));
        final double[] in = x.clone();
        final double[][] ch = { x };
        for (int off = 0; off < n; off += 512)
            cab.process(ch, off, Math.min(512, n - off));
        double a = 0.0, b = 0.0;
        for (int i = n / 2; i < n; ++i)
        {
            a += in[i] * in[i];
            b += x[i] * x[i];
        }
        return 10.0 * Math.log10(b / a);
    }

    private static CabinetModel fresh(double size, double brightness, double distance)
    {
        final CabinetModel cab = new CabinetModel(2);
        cab.setSize(size);
        cab.setBrightness(brightness);
        cab.setDistance(distance);
        cab.prepare(48000.0, 1);
        return cab;
    }

    @Test
    @DisplayName("CabinetModel_colours_like_a_speaker_in_a_box")
    void coloursLikeASpeakerInABox()
    {
        {
            final CabinetModel cab = fresh(0.5, 0.5, 0.0);
            assertEquals(140.0 / Math.sqrt(2.0), cab.getResonanceHz(), 1e-9);
            assertEquals(3000.0 * Math.sqrt(7.0 / 3.0), cab.getRollOffHz(), 1e-9);
            assertEquals(0.0, cabToneDb(cab, 1000.0), 1.5);
            assertTrue(cabToneDb(fresh(0.5, 0.5, 0.0), 30.0) < -15.0);
            assertTrue(cabToneDb(fresh(0.5, 0.5, 0.0), 99.0) > 1.5);
            assertTrue(cabToneDb(fresh(0.5, 0.5, 0.0), 0.62 * cab.getRollOffHz()) > 1.0);
            assertTrue(cabToneDb(fresh(0.5, 0.5, 0.0), 2.0 * cab.getRollOffHz()) < -20.0);
        }
        // Brightness moves the roll-off; size moves the resonance.
        assertTrue(cabToneDb(fresh(0.5, 1.0, 0.0), 6000.0) > cabToneDb(fresh(0.5, 0.0, 0.0), 6000.0) + 15.0);
        assertTrue(cabToneDb(fresh(1.0, 0.5, 0.0), 70.0) > cabToneDb(fresh(0.0, 0.5, 0.0), 70.0) + 6.0);

        // No latency: the first sample of an impulse is already out.
        final CabinetModel cab = fresh(0.5, 0.5, 0.6);
        final double[] impulse = new double[2048];
        impulse[0] = 1.0;
        cab.process(new double[][] { impulse }, 2048);
        assertTrue(Math.abs(impulse[0]) > 1e-6);
        int peak = 0;
        for (int i = 1; i < impulse.length; ++i)
            if (Math.abs(impulse[i]) > Math.abs(impulse[peak])) peak = i;
        assertTrue(peak < 12);
        // The box: the speaker alone, scaled as the reflections keep the level,
        // up to the first reflection (0.29 ms, 14 samples); the reflections after it.
        final CabinetModel dry = fresh(0.5, 0.5, 0.0);
        final double[] dryImpulse = new double[2048];
        dryImpulse[0] = 1.0;
        dry.process(new double[][] { dryImpulse }, 2048);
        final double energy = 0.45 * 0.45 + 0.32 * 0.32 + 0.25 * 0.25 + 0.18 * 0.18 + 0.12 * 0.12 + 0.08 * 0.08;
        final double norm = 1.0 / Math.sqrt(1.0 + 0.36 * energy);
        double before = 0.0, after = 0.0, total = 0.0;
        for (int i = 0; i < 2048; ++i)
        {
            final double d = impulse[i] - norm * dryImpulse[i];
            if (i < 14) before += d * d;
            else after += d * d;
            total += dryImpulse[i] * dryImpulse[i];
        }
        assertTrue(before < total * 1e-10);
        assertTrue(after > total * 0.05);
    }

    private static double noiseLevel(double distance)
    {
        final CabinetModel cab = new CabinetModel(2);
        cab.setDistance(distance);
        cab.prepare(48000.0, 1);
        final double[] x = new double[96000];
        int seed = 3;
        double b0 = 0.0, b1 = 0.0, b2 = 0.0;
        for (int i = 0; i < x.length; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            final double w = (double) seed / 2147483648.0;
            b0 = 0.99765 * b0 + w * 0.0990460;
            b1 = 0.96300 * b1 + w * 0.2965164;
            b2 = 0.57000 * b2 + w * 1.0526913;
            x[i] = (float) (0.05 * (b0 + b1 + b2 + w * 0.1848));
        }
        final double[][] ch = { x };
        for (int off = 0; off < 96000; off += 480)
            cab.process(ch, off, 480);
        double e = 0.0;
        for (int i = 48000; i < x.length; ++i) e += x[i] * x[i];
        return 10.0 * Math.log10(e);
    }

    @Test
    @DisplayName("CabinetModel_keeps_the_level_glides_and_ignores_wild_values")
    void keepsTheLevelGlidesAndIgnoresWildValues()
    {
        assertEquals(noiseLevel(0.0), noiseLevel(1.0), 1.5);

        // A brightness change while a tone runs: the output moves without a step.
        final CabinetModel cab = new CabinetModel(2);
        cab.prepare(48000.0, 2);
        final double[] l = new double[48128], r = new double[48128];
        for (int i = 0; i < l.length; ++i)
            l[i] = (float) (0.3 * Math.sin(2.0 * 3.14159265358979323846 * 3000.0 * i / 48000.0));
        double step = 0.0, steady = 0.0, last = 0.0;
        final double[][] p = { l, r };
        for (int off = 0; off < l.length; off += 256)
        {
            if (off == 24064) cab.setBrightness(0.0);
            cab.process(p, off, 256);
            for (int i = 0; i < 256; ++i)
            {
                final double v = (float) l[off + i];
                final double d = Math.abs(v - last);
                last = v;
                if (off >= 12000 && off < 24064) steady = Math.max(steady, d);
                if (off >= 24064 && off < 26000) step = Math.max(step, d);
                assertEquals(0.0, r[off + i], 0.0);       // the silent channel stays silent
            }
        }
        assertTrue(step < steady * 1.05, "step " + step + " steady " + steady);

        cab.setSize(0.2);
        cab.setSize(Double.NaN);
        cab.setBrightness(7.0);
        cab.setDistance(-1.0);
        assertEquals(0.2, cab.getSize(), 0.0);
        assertEquals(1.0, cab.getBrightness(), 0.0);
        assertEquals(0.0, cab.getDistance(), 0.0);
        cab.prepare(Double.NaN, 2);    // ignored
        cab.prepare(48000.0, 0);       // ignored
        final double[][] q = { new double[64], new double[64] };
        q[0][0] = Double.NaN;
        q[0][1] = 0.5;
        cab.process(q, 64);
        for (double v : q[0]) assertTrue(Double.isFinite(v));
    }

    private static double[] noise(int n, int seed)
    {
        final double[] x = new double[n];
        for (int i = 0; i < n; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            x[i] = 0.3 * (double) seed / 2147483648.0;
        }
        return x;
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give identical output; reset equals a fresh model")
    void blockPartitionAndReset()
    {
        final double[] x = noise(12000, 99), y = noise(12000, 7);
        final double[][] reference = { x.clone(), y.clone() };
        final CabinetModel a = new CabinetModel();
        a.setDistance(0.7);
        a.setSize(0.3);
        a.prepare(44100.0, 2);
        a.process(reference, 4096);
        a.process(reference, 4096, 4096);
        a.process(reference, 8192, 12000 - 8192);
        for (int block : new int[] { 1, 37, 512 })
        {
            final CabinetModel b = new CabinetModel();
            b.setDistance(0.7);
            b.setSize(0.3);
            b.prepare(44100.0, 2);
            final double[][] z = { x.clone(), y.clone() };
            for (int pos = 0; pos < 12000; pos += block)
                b.process(z, pos, Math.min(block, 12000 - pos));
            assertArrayEquals(reference[0], z[0], 0.0);
            assertArrayEquals(reference[1], z[1], 0.0);
        }
        a.reset();
        final double[][] again = { x.clone(), y.clone() };
        a.process(again, 12000);
        assertArrayEquals(reference[0], again[0], 0.0);
        assertArrayEquals(reference[1], again[1], 0.0);
    }

    @Test
    @DisplayName("Unprepared model passes audio; channels beyond the prepared count pass through")
    void passThrough()
    {
        final double[] x = noise(256, 5);
        final double[][] z = { x.clone() };
        new CabinetModel().process(z, 256);
        assertArrayEquals(x, z[0], 0.0);
        final CabinetModel mono = new CabinetModel();
        mono.prepare(48000.0, 1);
        final double[][] w = { x.clone(), x.clone() };
        mono.process(w, 256);
        assertArrayEquals(x, w[1], 0.0);
    }
}
