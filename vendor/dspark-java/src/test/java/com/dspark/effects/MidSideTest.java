package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link MidSide}. */
class MidSideTest
{
    private static float[] sine(int n, double hz, double rate)
    {
        final float[] out = new float[n];
        for (int i = 0; i < n; ++i) out[i] = (float) Math.sin(2.0 * Math.PI * hz * i / rate);
        return out;
    }

    @Test
    @DisplayName("Encode then decode restores the channels")
    void roundTrip()
    {
        final float[][] tb = { sine(256, 440.0, 44100.0), sine(256, 880.0, 44100.0) };
        final float[] origL = tb[0].clone(), origR = tb[1].clone();
        MidSide.encode(tb, 256);
        MidSide.decode(tb, 256);
        for (int i = 0; i < 256; ++i)
        {
            assertEquals(origL[i], tb[0][i], 1e-5f);
            assertEquals(origR[i], tb[1][i], 1e-5f);
        }

        // Double precision: exact to one rounding of the inner sums.
        final java.util.Random rng = new java.util.Random(9);
        final double[][] d = new double[2][1000];
        for (int i = 0; i < 1000; ++i)
        {
            d[0][i] = 2.0 * rng.nextDouble() - 1.0;
            d[1][i] = 2.0 * rng.nextDouble() - 1.0;
        }
        final double[] l = d[0].clone(), r = d[1].clone();
        MidSide.encode(d, 1000);
        MidSide.decode(d, 1000);
        for (int i = 0; i < 1000; ++i)
        {
            assertEquals(l[i], d[0][i], 4.0 * Math.ulp(2.0));
            assertEquals(r[i], d[1][i], 4.0 * Math.ulp(2.0));
        }
        // Bit-exact when l + r and l - r round exactly (equal channels).
        final double[][] eq = { { 0.3, -0.7, 1.0 }, { 0.3, -0.7, 1.0 } };
        MidSide.encode(eq, 3);
        MidSide.decode(eq, 3);
        assertArrayEquals(new double[] { 0.3, -0.7, 1.0 }, eq[0], 0.0);
        assertArrayEquals(new double[] { 0.3, -0.7, 1.0 }, eq[1], 0.0);
    }

    @Test
    @DisplayName("A mono signal is all mid")
    void monoIsAllMid()
    {
        final float[][] tb = new float[2][128];
        java.util.Arrays.fill(tb[0], 0.8f);
        java.util.Arrays.fill(tb[1], 0.8f);
        MidSide.encode(tb, 128);
        float midPeak = 0.0f, sidePeak = 0.0f;
        for (int i = 0; i < 128; ++i)
        {
            midPeak = Math.max(midPeak, Math.abs(tb[0][i]));
            sidePeak = Math.max(sidePeak, Math.abs(tb[1][i]));
        }
        assertTrue(midPeak > 0.5f);
        assertEquals(0.0f, sidePeak, 1e-5f);
    }

    @Test
    @DisplayName("Silence stays silent")
    void silence()
    {
        final float[][] tb = new float[2][64];
        MidSide.encode(tb, 64);
        for (int i = 0; i < 64; ++i)
        {
            assertEquals(0.0f, tb[0][i], 1e-10f);
            assertEquals(0.0f, tb[1][i], 1e-10f);
        }
        final double[][] d = new double[2][64];
        MidSide.encode(d, 64);
        for (double[] ch : d) for (double v : ch) assertEquals(0.0, v, 0.0);
    }

    @Test
    @DisplayName("Exact values, and the sample API bit-matches the block API")
    void exactValuesAndSampleApi()
    {
        final float[] l = { 0.8f }, r = { -0.4f };
        MidSide.encodeSample(l, r, 0);
        assertEquals(0.2f, l[0], 1e-7f);
        assertEquals(0.6f, r[0], 1e-7f);
        MidSide.decodeSample(l, r, 0);
        assertEquals(0.8f, l[0], 1e-7f);
        assertEquals(-0.4f, r[0], 1e-7f);

        final float[][] tb = new float[2][256];
        final float[] ls = new float[256], rs = new float[256];
        int rng = 21;
        for (int i = 0; i < 256; ++i)
        {
            rng = rng * 1664525 + 1013904223;
            tb[0][i] = ls[i] = (float) (rng >>> 8) / 8388608.0f - 1.0f;
            rng = rng * 1664525 + 1013904223;
            tb[1][i] = rs[i] = (float) (rng >>> 8) / 8388608.0f - 1.0f;
        }
        MidSide.encode(tb, 256);
        int diffs = 0;
        for (int i = 0; i < 256; ++i)
        {
            MidSide.encodeSample(ls, rs, i);
            if (ls[i] != tb[0][i] || rs[i] != tb[1][i]) ++diffs;
        }
        MidSide.decode(tb, 256);
        for (int i = 0; i < 256; ++i)
        {
            MidSide.decodeSample(ls, rs, i);
            if (ls[i] != tb[0][i] || rs[i] != tb[1][i]) ++diffs;
        }
        assertEquals(0, diffs);

        // The same in double precision.
        final double[][] db = new double[2][256];
        final double[] ld = new double[256], rd = new double[256];
        final java.util.Random random = new java.util.Random(21);
        for (int i = 0; i < 256; ++i)
        {
            db[0][i] = ld[i] = 2.0 * random.nextDouble() - 1.0;
            db[1][i] = rd[i] = 2.0 * random.nextDouble() - 1.0;
        }
        MidSide.encode(db, 256);
        for (int i = 0; i < 256; ++i) MidSide.encodeSample(ld, rd, i);
        assertArrayEquals(db[0], ld, 0.0);
        assertArrayEquals(db[1], rd, 0.0);
        MidSide.decode(db, 256);
        for (int i = 0; i < 256; ++i) MidSide.decodeSample(ld, rd, i);
        assertArrayEquals(db[0], ld, 0.0);
        assertArrayEquals(db[1], rd, 0.0);
    }

    @Test
    @DisplayName("Channels beyond the first two are untouched; fewer than two is a no-op")
    void extraAndMissingChannels()
    {
        final float[][] tb = { sine(128, 440.0, 48000.0), sine(128, 880.0, 48000.0), sine(128, 1320.0, 48000.0) };
        final float[] third = tb[2].clone();
        MidSide.encode(tb, 128);
        MidSide.decode(tb, 128);
        assertArrayEquals(third, tb[2], 0.0f);

        final double[][] mono = { { 0.5, -0.25 } };
        MidSide.encode(mono, 2);
        MidSide.decode(mono, 2);
        assertArrayEquals(new double[] { 0.5, -0.25 }, mono[0], 0.0);
        final double[][] pair = { { 0.5 }, { 0.25 } };
        MidSide.encode(pair, 0);
        assertArrayEquals(new double[] { 0.5 }, pair[0], 0.0);
        MidSide.encode(pair, 1);
        assertArrayEquals(new double[] { 0.375 }, pair[0], 0.0);
        assertArrayEquals(new double[] { 0.125 }, pair[1], 0.0);
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give the same output as one pass")
    void blockSizes()
    {
        final int n = 9000;
        final java.util.Random rng = new java.util.Random(4);
        final double[][] ref = new double[2][n];
        for (int i = 0; i < n; ++i)
        {
            ref[0][i] = rng.nextGaussian();
            ref[1][i] = rng.nextGaussian();
        }
        final double[][] whole = { ref[0].clone(), ref[1].clone() };
        MidSide.encode(whole, n);
        for (int block : new int[] { 1, 37, 512, 4096 })
        {
            final double[][] out = { ref[0].clone(), ref[1].clone() };
            final double[][] view = new double[2][block];
            for (int start = 0; start < n; start += block)
            {
                final int len = Math.min(block, n - start);
                System.arraycopy(out[0], start, view[0], 0, len);
                System.arraycopy(out[1], start, view[1], 0, len);
                MidSide.encode(view, len);
                System.arraycopy(view[0], 0, out[0], start, len);
                System.arraycopy(view[1], 0, out[1], start, len);
            }
            assertArrayEquals(whole[0], out[0], 0.0);
            assertArrayEquals(whole[1], out[1], 0.0);
        }
    }
}
