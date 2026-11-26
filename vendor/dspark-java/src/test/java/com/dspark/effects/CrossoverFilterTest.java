package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static com.dspark.effects.CrossoverSignals.PI;
import static com.dspark.effects.CrossoverSignals.allFinite;
import static com.dspark.effects.CrossoverSignals.floatSine;
import static com.dspark.effects.CrossoverSignals.kernelResponseDb;
import static com.dspark.effects.CrossoverSignals.lcgNoise;
import static com.dspark.effects.CrossoverSignals.line;
import static com.dspark.effects.CrossoverSignals.m6Noise;
import static com.dspark.effects.CrossoverSignals.maxStep;
import static com.dspark.effects.CrossoverSignals.peak;
import static com.dspark.effects.CrossoverSignals.rms;
import static com.dspark.effects.CrossoverSignals.sine;
import static com.dspark.effects.CrossoverSignals.split;
import static com.dspark.effects.CrossoverSignals.sum;
import static com.dspark.effects.CrossoverSignals.whiteNoise;
import static com.dspark.effects.CrossoverSignals.worstSpectrumDb;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CrossoverFilter}: the assertions of the C++ tests, same signals and thresholds. */
class CrossoverFilterTest
{
    private static double[][][] bandBuffers(int bands, int channels, int n)
    {
        return new double[bands][channels][n];
    }

    // -- TestEffectsAdvanced.cpp -------------------------------------------------

    @Test
    @DisplayName("2-band LR24 sum keeps the RMS of noise (allpass)")
    void twoBandSumsToUnity()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.setCrossoverFrequency(0, 1000.0);
        x.setOrder(24);
        x.prepare(44100.0, 2, 4096);
        final double[][] in = { whiteNoise(4096, 1.0f, 12345), whiteNoise(4096, 1.0f, 12345 + 7919) };
        final double[] original = in[0].clone();
        final double[][][] views = bandBuffers(2, 2, 4096);
        x.process(in, views, 2, 4096);
        final double rmsOrig = rms(original, 512, 4096 - 512);
        final double[] s = new double[4096];
        for (int i = 0; i < 4096; ++i) s[i] = views[0][0][i] + views[1][0][i];
        assertEquals(rmsOrig, rms(s, 512, 4096 - 512), rmsOrig * 0.05);
    }

    @Test
    @DisplayName("Linear phase: band 0 + band 1 reconstruct the input level")
    void linearPhaseReconstructs()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.setCrossoverFrequency(0, 1000.0);
        x.setOrder(24);
        x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
        x.prepare(44100.0, 1, 2048);
        final double[][][] views = bandBuffers(2, 1, 2048);
        double rmsIn = 0.0, rmsSum = 0.0;
        for (int blk = 0; blk < 4; ++blk)
        {
            final double[] in = new double[2048];
            int rng = blk * 9781 + 12345;
            for (int i = 0; i < 2048; ++i)
            {
                rng = rng * 1664525 + 1013904223;
                in[i] = (float) (rng >>> 8) * (1.0f / 16777216.0f) - 0.5f;
            }
            final double[] orig = in.clone();
            x.process(new double[][] { in }, views, 2, 2048);
            if (blk == 3)
            {
                final double[] s = new double[2048];
                for (int i = 0; i < 2048; ++i) s[i] = views[0][0][i] + views[1][0][i];
                assertTrue(allFinite(s));
                rmsIn = rms(orig, 0, 2048);
                rmsSum = rms(s, 0, 2048);
            }
        }
        assertEquals(rmsIn, rmsSum, rmsIn * 0.08);
    }

    @Test
    @DisplayName("The low band attenuates 5 kHz under a 500 Hz split")
    void lowBandAttenuatesHigh()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.setCrossoverFrequency(0, 500.0);
        x.setOrder(24);
        x.prepare(44100.0, 1, 8192);
        final double[][] in = { floatSine(8192, 5000.0f, 44100.0f) };
        final double[][][] views = bandBuffers(2, 1, 8192);
        x.process(in, views, 2, 8192);
        assertTrue(peak(views[0][0], 2048, 4096) < 0.05);
    }

    @Test
    @DisplayName("The high band attenuates 100 Hz over a 2 kHz split")
    void highBandAttenuatesLow()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.setCrossoverFrequency(0, 2000.0);
        x.setOrder(24);
        x.prepare(44100.0, 1, 8192);
        final double[][] in = { floatSine(8192, 100.0f, 44100.0f) };
        final double[][][] views = bandBuffers(2, 1, 8192);
        x.process(in, views, 2, 8192);
        assertTrue(peak(views[1][0], 2048, 4096) < 0.05);
    }

    @Test
    @DisplayName("3-band LR24 sum keeps the RMS of noise")
    void threeBandSumsToUnity()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(3);
        x.setCrossoverFrequency(0, 300.0);
        x.setCrossoverFrequency(1, 3000.0);
        x.setOrder(24);
        x.prepare(44100.0, 1, 4096);
        final double[][] in = { whiteNoise(4096, 1.0f, 12345) };
        final double[] original = in[0].clone();
        final double[][][] views = bandBuffers(3, 1, 4096);
        x.process(in, views, 3, 4096);
        final double[] s = new double[4096];
        for (int i = 0; i < 4096; ++i) s[i] = views[0][0][i] + views[1][0][i] + views[2][0][i];
        final double rmsOrig = rms(original, 512, 4096 - 512);
        assertEquals(rmsOrig, rms(s, 512, 4096 - 512), rmsOrig * 0.1);
    }

    @Test
    @DisplayName("Each band is about -6 dB at the crossover frequency")
    void minus6dBAtCrossover()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.setCrossoverFrequency(0, 1000.0);
        x.setOrder(24);
        x.prepare(44100.0, 1, 8192);
        final double[][] in = { floatSine(8192, 1000.0f, 44100.0f) };
        final double[][][] views = bandBuffers(2, 1, 8192);
        x.process(in, views, 2, 8192);
        final double low = peak(views[0][0], 4096, 4096), high = peak(views[1][0], 4096, 4096);
        assertTrue(low > 0.3 && low < 0.7, "low " + low);
        assertTrue(high > 0.3 && high < 0.7, "high " + high);
    }

    @Test
    @DisplayName("Silence in, silence out")
    void silence()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.setCrossoverFrequency(0, 1000.0);
        x.prepare(44100.0, 2, 512);
        final double[][] in = new double[2][512];
        final double[][][] views = bandBuffers(2, 2, 512);
        x.process(in, views, 2, 512);
        for (int i = 0; i < 512; ++i)
        {
            assertEquals(0.0, views[0][0][i], 1e-10);
            assertEquals(0.0, views[1][0][i], 1e-10);
        }
    }

    @Test
    @DisplayName("The band sum is flat with octave-spaced splits at LR12, LR24 and LR48")
    void sumIsFlatAtOctaveSplits()
    {
        for (final int order : new int[] { 12, 24, 48 })
        {
            final CrossoverFilter x = new CrossoverFilter();
            x.setNumBands(3);
            x.setOrder(order);
            x.prepare(48000.0, 1, 8192);
            x.setCrossoverFrequency(0, 1000.0);
            x.setCrossoverFrequency(1, 2000.0);
            final double[][][] views = bandBuffers(3, 1, 8192);
            x.process(new double[][] { new double[8192] }, views, 3, 8192); // settle the 5 ms smoothing

            double worst = 0.0;
            for (final float f : new float[] { 600f, 800f, 1000f, 1200f, 1500f, 1800f, 2000f, 2400f, 3000f })
            {
                x.reset();
                final double[] in = floatSine(8192, f, 48000.0f);
                x.process(new double[][] { in }, views, 3, 8192);
                final double[] s = new double[8192];
                for (int b = 0; b < 3; ++b)
                    for (int i = 0; i < 8192; ++i) s[i] += views[b][0][i];
                final double rIn = rms(in, 4096, 4096);
                final double rSum = rms(s, 4096, 4096);
                worst = Math.max(worst, Math.abs(20.0 * Math.log10(Math.max(rSum, 1e-9) / rIn)));
            }
            assertTrue(worst < 0.75, "LR" + order + " worst " + worst);
        }
    }

    @Test
    @DisplayName("prepare() keeps the frequencies set before it")
    void preparePreservesFrequencies()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(3);
        x.setCrossoverFrequency(0, 300.0);
        x.setCrossoverFrequency(1, 3000.0);
        x.prepare(48000.0, 1, 4096);
        assertEquals(300.0, x.getCrossoverFrequency(0), 0.5);
        assertEquals(3000.0, x.getCrossoverFrequency(1), 5.0);

        final double[][] in = { floatSine(4096, 380.0f, 48000.0f) };
        final double[][][] views = bandBuffers(3, 1, 4096);
        x.process(in, views, 3, 4096);
        assertTrue(rms(views[1][0], 2048, 2048) > rms(views[0][0], 2048, 2048));
    }

    @Test
    @DisplayName("Linear phase selected after prepare() engages, and its kernels follow the setter")
    void linearPhaseEngagesAfterPrepare()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.setCrossoverFrequency(0, 500.0);
        x.setOrder(24);
        x.prepare(48000.0, 1, 512);
        x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
        assertEquals(3072, x.getLatency());

        final double[][][] views = bandBuffers(2, 1, 512);
        final double[] in = new double[512];
        int argmax = 0;
        double pk = 0.0;
        for (int k = 0; k < 8; ++k)
        {
            Arrays.fill(in, 0.0);
            if (k == 0) in[0] = 1.0;
            x.process(new double[][] { in }, views, 2, 512);
            for (int i = 0; i < 512; ++i)
            {
                final double v = Math.abs(views[0][0][i] + views[1][0][i]);
                if (v > pk)
                {
                    pk = v;
                    argmax = k * 512 + i;
                }
            }
        }
        assertEquals(x.getLatency(), argmax);
        assertEquals(1.0, pk, 0.05);

        x.setCrossoverFrequency(0, 4000.0);
        double e0 = 0.0, e1 = 0.0;
        for (int k = 0; k < 40; ++k)
        {
            for (int i = 0; i < 512; ++i)
                in[i] = (float) Math.sin(2.0f * 3.14159265f * 2000.0f * (float) (k * 512 + i) / 48000.0f);
            x.process(new double[][] { in }, views, 2, 512);
            if (k >= 32)
            {
                e0 += rms(views[0][0], 0, 512);
                e1 += rms(views[1][0], 0, 512);
            }
        }
        assertTrue(e0 > e1 * 4.0, "e0 " + e0 + " e1 " + e1);
    }

    @Test
    @DisplayName("Non-finite frequencies, invalid prepare() values and oversized blocks are ignored or clamped")
    void invalidInputsAreIgnored()
    {
        final CrossoverFilter x = new CrossoverFilter(), twin = new CrossoverFilter();
        for (final CrossoverFilter p : new CrossoverFilter[] { x, twin })
        {
            p.setNumBands(4);
            p.setCrossoverFrequency(0, 200.0);
            p.setCrossoverFrequency(1, 1000.0);
            p.setCrossoverFrequency(2, 5000.0);
            p.setOrder(24);
            p.prepare(48000.0, 2, 512);
        }
        x.setCrossoverFrequency(1, Double.NaN);
        x.setCrossoverFrequency(0, Double.POSITIVE_INFINITY);
        assertDoesNotThrow(() ->
        {
            x.prepare(Double.NaN, 2, 512);
            x.prepare(48000.0, -3, 512);
        });
        x.setFilterMode(null);
        assertSame(CrossoverFilter.FilterMode.MINIMUM_PHASE, x.getFilterMode());
        x.setFilterMode(CrossoverFilter.FilterMode.MINIMUM_PHASE);
        assertEquals(200.0, x.getCrossoverFrequency(0), 0.01);
        assertEquals(1000.0, x.getCrossoverFrequency(1), 0.01);
        assertEquals(5000.0, x.getCrossoverFrequency(2), 0.01);

        final double[][][] va = bandBuffers(4, 2, 512), vb = bandBuffers(4, 2, 512);
        int rng = 99;
        double maxDiff = 0.0;
        for (int k = 0; k < 12; ++k)
        {
            final double[][] in1 = new double[2][512], in2 = new double[2][512];
            for (int c = 0; c < 2; ++c)
                for (int i = 0; i < 512; ++i)
                {
                    rng = rng * 1664525 + 1013904223;
                    final float v = (float) (rng >>> 8) / 16777216.0f - 0.5f;
                    in1[c][i] = v;
                    in2[c][i] = v;
                }
            x.process(in1, va, 4, 512);
            twin.process(in2, vb, 4, 512);
            for (int b = 0; b < 4; ++b)
                for (int c = 0; c < 2; ++c)
                    for (int i = 0; i < 512; ++i)
                    {
                        final double d = Math.abs(va[b][c][i] - vb[b][c][i]);
                        if (!(d <= maxDiff)) maxDiff = d;
                    }
        }
        assertEquals(0.0, maxDiff, 0.0);

        // Oversized block: the processed span clamps to the prepared maxBlockSize.
        final double[][] big = new double[2][700];
        for (final double[] c : big) Arrays.fill(c, 0.25);
        final double[][][] vo = bandBuffers(4, 2, 700);
        assertEquals(4, x.process(big, vo, 4, 700));
        assertTrue(allFinite(Arrays.copyOf(vo[0][0], 512)));
        for (int i = 512; i < 700; ++i) assertEquals(0.0, vo[0][0][i], 0.0);
    }

    @Test
    @DisplayName("setCrossoverFrequency() keeps the splits sorted")
    void frequenciesStaySorted()
    {
        final CrossoverFilter xo = new CrossoverFilter(8);
        xo.prepare(48000.0, 2, 512);
        xo.setNumBands(4);
        xo.setCrossoverFrequency(2, 50.0);
        final double f0 = xo.getCrossoverFrequency(0), f1 = xo.getCrossoverFrequency(1), f2 = xo.getCrossoverFrequency(2);
        assertEquals(50.0, f0, 1.0);
        assertTrue(f0 < f1);
        assertTrue(f1 < f2);
    }

    @Test
    @DisplayName("A linear-phase design change glides over a partition and the bands keep summing")
    void linearPhaseDesignChangeGlides()
    {
        final double fs = 48000.0;
        for (final boolean alsoBands : new boolean[] { false, true })
        {
            final CrossoverFilter x = new CrossoverFilter(12);
            x.setNumBands(2);
            x.setCrossoverFrequency(0, 500.0);
            x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
            x.prepare(fs, 1, 256);
            final int latency = x.getLatency();
            final int blocks = 375;
            final double[] all = new double[blocks * 256];
            final double[] band0 = new double[blocks * 256];
            final double[][][] views = bandBuffers(3, 1, 256);
            double sumError = 0.0;
            for (int k = 0; k < blocks; ++k)
            {
                if (k == 188)
                {
                    if (alsoBands)
                    {
                        x.setNumBandsKeepingSplits(3);
                        x.setCrossoverFrequency(1, 6000.0);
                    }
                    x.setCrossoverFrequency(0, 2000.0);
                }
                final double[] in = new double[256];
                for (int i = 0; i < 256; ++i)
                {
                    final float a = (float) Math.sin(2.0 * PI * 1000.0 * (k * 256 + i) / fs);
                    final float b = (float) Math.sin(2.0 * PI * 9000.0 * (k * 256 + i) / fs);
                    in[i] = 0.5f * a + 0.2f * b;
                    all[k * 256 + i] = in[i];
                }
                for (final double[][] v : views) Arrays.fill(v[0], 0.0);
                final int n = x.process(new double[][] { in }, views, 3, 256);
                for (int i = 0; i < 256; ++i)
                {
                    band0[k * 256 + i] = views[0][0][i];
                    double s = 0.0;
                    for (int b = 0; b < n; ++b) s += views[b][0][i];
                    final int at = k * 256 + i - latency;
                    if (at >= 0) sumError = Math.max(sumError, Math.abs(s - all[at]));
                }
            }
            final int change = 188 * 256;
            final double after = maxStep(band0, change + 9600, band0.length);
            final double during = maxStep(band0, change, change + 9600);
            System.out.printf("    design change%s: band 0 step %.4f (steady %.4f), sum error %.2g%n",
                              alsoBands ? " with a band more" : "", during, after, sumError);
            assertTrue(during < after * 1.15);
            assertTrue(sumError < 1e-5);
        }
    }

    @Test
    @DisplayName("A band-count change of the minimum-phase engine fades and keeps the splits")
    void bandCountChangeFadesAndKeepsSplits()
    {
        final double fs = 48000.0;
        for (final int order : new int[] { 24, 48 })
            for (final boolean ladder : new boolean[] { false, true })
            {
                final CrossoverFilter x = new CrossoverFilter(12);
                x.setOrder(order);
                if (ladder) x.setStructure(CrossoverFilter.Structure.LADDER);
                x.setNumBands(3);
                x.setCrossoverFrequency(0, 200.0);
                x.setCrossoverFrequency(1, 2000.0);
                x.prepare(fs, 1, 256);
                final int blocks = 280;
                final double[][] rec = new double[4][blocks * 256];
                final double[][][] views = bandBuffers(4, 1, 256);
                for (int k = 0; k < blocks; ++k)
                {
                    if (k == 94) x.setNumBandsKeepingSplits(4);
                    if (k == 188) x.setNumBandsKeepingSplits(3);
                    final double[] in = new double[256];
                    for (int i = 0; i < 256; ++i)
                    {
                        final double t = (k * 256 + i) / fs;
                        in[i] = (float) (0.4 * Math.sin(2 * PI * 100 * t) + 0.3 * Math.sin(2 * PI * 3000 * t)
                                         + 0.2 * Math.sin(2 * PI * 9000 * t));
                    }
                    for (final double[][] v : views) Arrays.fill(v[0], 0.0);
                    final int n = x.process(new double[][] { in }, views, 4, 256);
                    for (int b = 0; b < 4; ++b)
                        for (int i = 0; i < 256; ++i) rec[b][k * 256 + i] = b < n ? views[b][0][i] : 0.0;
                }
                assertEquals(200.0, x.getCrossoverFrequency(0), 0.01);
                assertEquals(2000.0, x.getCrossoverFrequency(1), 0.01);
                assertEquals(6324.555, x.getCrossoverFrequency(2), 0.5);
                final int up = 94 * 256, down = 188 * 256;
                for (int b = 0; b < 3; ++b)
                {
                    final double steady = Math.max(maxStep(rec[b], up - 9600, up), maxStep(rec[b], up + 9600, down));
                    assertTrue(maxStep(rec[b], up, up + 4800) < steady * 1.1 + 1e-4, "LR" + order + " band " + b);
                    assertTrue(maxStep(rec[b], down, down + 4800) < steady * 1.1 + 1e-4, "LR" + order + " band " + b);
                }
                assertTrue(maxStep(rec[3], up, up + 4800) < maxStep(rec[3], up + 9600, down) * 1.1);
            }
    }

    @Test
    @DisplayName("The balanced tree sums like the cascade at every slope, in both structures")
    void balancedTreeSumsLikeTheCascade()
    {
        for (final int order : new int[] { 6, 12, 24, 36, 48 })
            for (final boolean ladder : new boolean[] { false, true })
                for (final int bands : new int[] { 5, 12 })
                {
                    final double[] lowCascade = new double[1], lowTree = new double[1];
                    final double[] cascade = treeOrCascadeSum(CrossoverFilter.Topology.CASCADE, order, ladder, bands,
                                                              lowCascade);
                    final double[] tree = treeOrCascadeSum(CrossoverFilter.Topology.BALANCED, order, ladder, bands,
                                                           lowTree);
                    double d = 0.0;
                    for (int i = 0; i < cascade.length; ++i) d = Math.max(d, Math.abs(cascade[i] - tree[i]));
                    assertTrue(d < 2e-6, "LR" + order + " ladder " + ladder + " bands " + bands + ": " + d);
                    if (order == 24 && bands == 5) assertTrue(lowTree[0] < lowCascade[0]);
                }
    }

    private static double[] treeOrCascadeSum(CrossoverFilter.Topology topology, int order, boolean ladder, int bands,
                                             double[] lowAt2k)
    {
        final CrossoverFilter x = new CrossoverFilter(12);
        x.setOrder(order);
        if (ladder) x.setStructure(CrossoverFilter.Structure.LADDER);
        x.setTopology(topology);
        x.setNumBands(bands);
        x.prepare(48000.0, 1, 256);
        final Random rng = new Random(3);
        final double[][][] views = bandBuffers(12, 1, 256);
        final double[] s = new double[60 * 256];
        lowAt2k[0] = 0.0;
        for (int k = 0; k < 80; ++k)
        {
            final double[] in = new double[256];
            for (int i = 0; i < 256; ++i)
                in[i] = k < 60 ? (float) (0.2 * rng.nextGaussian())
                               : (float) Math.sin(2.0 * 3.14159265358979 * 2000.0 * (k * 256 + i) / 48000.0);
            x.process(new double[][] { in }, views, bands, 256);
            for (int i = 0; i < 256; ++i)
            {
                double v = 0.0;
                for (int b = 0; b < bands; ++b) v += views[b][0][i];
                if (k < 60) s[k * 256 + i] = v;
                else if (k >= 70) lowAt2k[0] = Math.max(lowAt2k[0], Math.abs(views[0][0][i]));
            }
        }
        return s;
    }

    @Test
    @DisplayName("A topology change, and a band-count change on the tree, go over without a step")
    void topologyChangeGlides()
    {
        final double fs = 48000.0;
        for (final boolean ladder : new boolean[] { false, true })
        {
            final CrossoverFilter x = new CrossoverFilter(12);
            if (ladder) x.setStructure(CrossoverFilter.Structure.LADDER);
            x.setNumBands(4);
            x.setCrossoverFrequency(0, 200.0);
            x.setCrossoverFrequency(1, 2000.0);
            x.setCrossoverFrequency(2, 6000.0);
            x.prepare(fs, 1, 256);
            final int blocks = 380;
            final double[][] rec = new double[5][blocks * 256];
            final double[][][] views = bandBuffers(5, 1, 256);
            for (int k = 0; k < blocks; ++k)
            {
                if (k == 94) x.setTopology(CrossoverFilter.Topology.BALANCED);
                if (k == 188) x.setNumBandsKeepingSplits(5);
                if (k == 282) x.setTopology(CrossoverFilter.Topology.CASCADE);
                final double[] in = new double[256];
                for (int i = 0; i < 256; ++i)
                {
                    final double t = (k * 256 + i) / fs;
                    in[i] = (float) (0.4 * Math.sin(2 * PI * 100 * t) + 0.3 * Math.sin(2 * PI * 3000 * t)
                                     + 0.2 * Math.sin(2 * PI * 9000 * t) + 0.1 * Math.sin(2 * PI * 16000 * t));
                }
                for (final double[][] v : views) Arrays.fill(v[0], 0.0);
                final int n = x.process(new double[][] { in }, views, 5, 256);
                for (int b = 0; b < 5; ++b)
                    for (int i = 0; i < 256; ++i) rec[b][k * 256 + i] = b < n ? views[b][0][i] : 0.0;
            }
            for (final int change : new int[] { 94, 188, 282 })
            {
                final int at = change * 256;
                for (int b = 0; b < (change == 94 ? 4 : 5); ++b)
                {
                    final double steady = Math.max(maxStep(rec[b], at - 9600, at), maxStep(rec[b], at + 9600, at + 19200));
                    assertTrue(maxStep(rec[b], at, at + 4800) < steady * 1.1 + 1e-4,
                               "ladder " + ladder + " change " + change + " band " + b);
                }
            }
        }
    }

    // -- TestEffectsDynamics.cpp -------------------------------------------------

    @Test
    @DisplayName("Band arrays with fewer channels than the input are safe, in both modes")
    void narrowBandViewsAreSafe()
    {
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
        {
            final CrossoverFilter x = new CrossoverFilter();
            x.setNumBands(2);
            x.setFilterMode(mode);
            x.prepare(48000.0, 2, 256);
            final double[] l = new double[256], r = new double[256];
            Arrays.fill(l, 0.25);
            Arrays.fill(r, 0.25);
            final double[] o0 = new double[256], o1 = new double[256];
            final double[][][] out = { { o0 }, { o1 } };
            assertEquals(2, x.process(new double[][] { l, r }, out, 2, 256));
            assertTrue(allFinite(o0));
            assertTrue(allFinite(o1));
        }
    }

    @Test
    @DisplayName("A NaN in the input does not latch in the sections")
    void nonFiniteInputDoesNotLatch()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(3);
        x.prepare(48000.0, 2, 512);
        final double[] s = sine(997.0, 0.5, 48000.0, 512 * 40);
        boolean lateBad = false;
        final double[][][] views = bandBuffers(3, 2, 512);
        for (int b = 0; b < 40; ++b)
        {
            final double[] l = Arrays.copyOfRange(s, b * 512, (b + 1) * 512), r = l.clone();
            if (b == 10) l[100] = Double.NaN;
            for (final double[][] v : views) for (final double[] c : v) Arrays.fill(c, 0.0);
            x.process(new double[][] { l, r }, views, 3, 512);
            if (b > 10)
                for (int k = 0; k < 3; ++k)
                    if (!allFinite(views[k][0])) lateBad = true;
        }
        assertTrue(!lateBad);
    }

    @Test
    @DisplayName("The 8-band sum is flat to 1e-5 dB at LR24 and LR48")
    void bandSumIsFlat()
    {
        for (final int order : new int[] { 24, 48 })
        {
            final CrossoverFilter x = new CrossoverFilter();
            x.setNumBands(8);
            x.setOrder(order);
            x.prepare(48000.0, 1, 4096);
            final int n = 1 << 15;
            final double[] in = new double[n];
            in[8] = 1.0;
            final double[][] out = split(x, in, 8, 4096);
            final double worst = worstSpectrumDb(sum(out));
            assertTrue(worst < 1e-5, "LR" + order + " " + worst);
        }
    }

    @Test
    @DisplayName("Linear-phase resolution and latency do not depend on the block size")
    void linearPhaseResolutionIsBlockIndependent()
    {
        final double fs = 48000.0;
        int latency = -1;
        for (final int bs : new int[] { 64, 512, 4096 })
        {
            final CrossoverFilter x = new CrossoverFilter();
            x.setNumBands(2);
            x.setOrder(24);
            x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
            x.prepare(fs, 1, bs);
            x.setCrossoverFrequency(0, 200.0);
            final int n = 1 << 15, t0 = 8192;
            final double[] in = new double[n];
            in[t0] = 1.0;
            final double[][] out = split(x, in, 2, bs);
            if (latency < 0) latency = x.getLatency();
            assertEquals(latency, x.getLatency());
            final int centre = t0 + x.getLatency();
            final double at100 = kernelResponseDb(out[0], centre, 4096, 100.0, fs);
            final double at400 = kernelResponseDb(out[0], centre, 4096, 400.0, fs);
            System.out.printf("    maxBlock %d: band 0 %.4f dB @100 Hz, %.4f dB @400 Hz%n", bs, at100, at400);
            assertEquals(-0.527, at100, 0.02);
            assertEquals(-24.61, at400, 0.05);
        }
        assertEquals(3072, latency);
    }

    @Test
    @DisplayName("Linear phase: close splits sum to the delayed input")
    void linearPhaseCloseSplitsSumToDelayedInput()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(4);
        x.setOrder(24);
        x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
        x.prepare(48000.0, 1, 512);
        x.setCrossoverFrequency(0, 100.0);
        x.setCrossoverFrequency(1, 150.0);
        x.setCrossoverFrequency(2, 225.0);
        final int n = 1 << 15, t0 = 4096;
        final double[] in = new double[n];
        in[t0] = 1.0;
        final double[] s = sum(split(x, in, 4, 512));
        double worst = 0.0;
        for (int i = 0; i < n; ++i)
            worst = Math.max(worst, Math.abs(s[i] - ((i == t0 + x.getLatency()) ? 1.0 : 0.0)));
        assertTrue(worst < 1e-5, "worst " + worst);
    }

    @Test
    @DisplayName("Linear phase: the same input in any block partition gives bit-identical bands")
    void linearPhaseOutputIsBlockSizeIndependent()
    {
        final double[] in = lcgNoise(24000, 777, 0.3f);
        final double[][] a = split(lpThreeBands(), in, 3, 512);
        for (final int bs : new int[] { 1, 7, 333 })
        {
            final double[][] b = split(lpThreeBands(), in, 3, bs, bs == 333);
            for (int band = 0; band < 3; ++band) assertTrue(Arrays.equals(a[band], b[band]), "block " + bs);
        }
    }

    private static CrossoverFilter lpThreeBands()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(3);
        x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
        x.prepare(48000.0, 1, 512);
        return x;
    }

    @Test
    @DisplayName("The IIR glide is bit-identical at any block size")
    void glideIsBlockSizeIndependent()
    {
        final double[] in = sine(1000.0, 0.5, 48000.0, 12000);
        final double[][] a = split(gliding(), in, 2, 512);
        for (final int bs : new int[] { 1, 100, 333 })
        {
            final double[][] b = split(gliding(), in, 2, bs, bs == 333);
            assertTrue(Arrays.equals(a[0], b[0]), "block " + bs);
        }
    }

    private static CrossoverFilter gliding()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(2);
        x.prepare(48000.0, 1, 512);
        x.setCrossoverFrequency(0, 500.0);
        split(x, new double[4096], 2, 512);
        x.setCrossoverFrequency(0, 4000.0); // glides over the next blocks
        return x;
    }

    @Test
    @DisplayName("Ladders match the direct form with still splits and stay bounded under jumps")
    void ladderStructureMatchesAndHoldsUnderJumps()
    {
        final int n = 24000;
        final double[] in = lcgNoise(n, 0x13572468, 0.3f);
        for (final int order : new int[] { 12, 24, 48 })
        {
            final CrossoverFilter direct = fourBands(order, CrossoverFilter.Structure.DIRECT_FORM);
            final CrossoverFilter ladder = fourBands(order, CrossoverFilter.Structure.LADDER);
            assertSame(CrossoverFilter.Structure.LADDER, ladder.getStructure());
            final double[][] a = split(direct, in, 4, 333), b = split(ladder, in, 4, 333);
            double worst = 0.0;
            for (int band = 0; band < 4; ++band)
                for (int i = 0; i < n; ++i) worst = Math.max(worst, Math.abs(a[band][i] - b[band][i]));
            assertTrue(worst < 1e-6, "LR" + order + " " + worst);
        }

        final double[] pk = jumpPeaks(48, in);
        assertTrue(Double.isFinite(pk[1]));
        assertTrue(pk[1] < 1.0);
        assertTrue(pk[1] < 1.25 * pk[0]);
    }

    static CrossoverFilter fourBands(int order, CrossoverFilter.Structure structure)
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(4);
        x.setOrder(order);
        x.setStructure(structure);
        x.prepare(48000.0, 1, 512);
        return x;
    }

    /** Peak of the band sum with the split jumping between 100 Hz and 10 kHz every 64 samples: direct, ladder. */
    static double[] jumpPeaks(int order, double[] in)
    {
        final double[] pk = new double[2];
        for (int k = 0; k < 2; ++k)
        {
            final CrossoverFilter x = new CrossoverFilter();
            x.setNumBands(2);
            x.setOrder(order);
            x.setStructure(k == 0 ? CrossoverFilter.Structure.DIRECT_FORM : CrossoverFilter.Structure.LADDER);
            x.prepare(48000.0, 1, 64);
            final double[][][] views = bandBuffers(2, 1, 64);
            for (int off = 0, block = 0; off + 64 <= in.length; off += 64, ++block)
            {
                x.setCrossoverFrequency(0, (block % 2 == 0) ? 100.0 : 10000.0);
                x.process(new double[][] { Arrays.copyOfRange(in, off, off + 64) }, views, 2, 64);
                for (int i = 0; i < 64; ++i) pk[k] = Math.max(pk[k], Math.abs(views[0][0][i] + views[1][0][i]));
            }
        }
        return pk;
    }

    @Test
    @DisplayName("Linear phase: the bands sum to the delayed input through random live changes")
    void linearPhaseBandsSumThroughLiveChanges()
    {
        final int n = 48000 * 6, maxBlock = 700;
        final CrossoverFilter x = new CrossoverFilter();
        x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
        x.setNumBands(4);
        x.prepare(48000.0, 2, maxBlock);
        final int latency = x.getLatency();
        final double[][] in = new double[2][n], total = new double[2][n];
        int s = 7;
        for (int c = 0; c < 2; ++c)
            for (int i = 0; i < n; ++i)
            {
                s = s * 1664525 + 1013904223;
                in[c][i] = (float) (s >>> 8) / 16777216.0f - 0.5f;
            }
        final double[][][] views = bandBuffers(12, 2, maxBlock);
        int r = 99;
        int changes = 0;
        for (int pos = 0; pos < n;)
        {
            r = r * 1103515245 + 12345;
            final int m = Math.min(n - pos, 1 + (r >>> 16) % maxBlock);
            r = r * 1103515245 + 12345;
            final int ev = (r >>> 16) % 40;
            if (ev == 0)
            {
                x.setNumBands(2 + (r >>> 8) % 11);
                ++changes;
            }
            else if (ev == 1)
            {
                x.setCrossoverFrequency(0, 60.0f + (float) ((r >>> 4) % 400));
                ++changes;
            }
            else if (ev == 2)
            {
                x.setOrder((r & 1) != 0 ? 48 : 12);
                ++changes;
            }
            else if (ev == 3)
            {
                x.setOrder(24);
                ++changes;
            }
            final int got = x.process(in, pos, views, 0, 12, m);
            for (int c = 0; c < 2; ++c)
                for (int b = 0; b < got; ++b)
                    for (int i = 0; i < m; ++i) total[c][pos + i] += views[b][c][i];
            pos += m;
        }
        double err = 0.0;
        for (int c = 0; c < 2; ++c)
            for (int i = latency; i < n; ++i) err = Math.max(err, Math.abs(total[c][i] - in[c][i - latency]));
        System.out.println("    " + changes + " live changes: max |band sum - delayed input| " + err);
        assertTrue(changes > 30);
        assertTrue(err < 1e-5);
    }

    /**
     * Blocks after a move of split 0 to 230 Hz until the bands are, and stay,
     * bit-identical to those of a crossover prepared with it (-1: never).
     */
    static int landing(int block, int chans)
    {
        final CrossoverFilter a = new CrossoverFilter(), b = new CrossoverFilter();
        for (final CrossoverFilter x : new CrossoverFilter[] { a, b })
        {
            x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
            x.setNumBands(4);
        }
        b.setCrossoverFrequency(0, 230.0);
        for (final CrossoverFilter x : new CrossoverFilter[] { a, b }) x.prepare(48000.0, chans, block);
        final int warm = 16384 / block, total = warm + 2 * 48000 / block;
        final double[][][] oa = bandBuffers(4, chans, block), ob = bandBuffers(4, chans, block);
        final double[][] in = new double[chans][block];
        int landed = -1;
        int s = 3;
        for (int k = 0; k < total; ++k)
        {
            if (k == warm) a.setCrossoverFrequency(0, 230.0);
            for (int c = 0; c < chans; ++c)
                for (int i = 0; i < block; ++i)
                {
                    s = s * 1664525 + 1013904223;
                    in[c][i] = (float) (s >>> 8) / 16777216.0f - 0.5f;
                }
            a.process(in, oa, 4, block);
            b.process(in, ob, 4, block);
            boolean same = true;
            for (int band = 0; band < 4; ++band)
                for (int c = 0; c < chans; ++c) same = same && Arrays.equals(oa[band][c], ob[band][c]);
            if (k >= warm && same && landed < 0) landed = k - warm;
            if (k >= warm && !same) landed = -1;
        }
        return landed;
    }

    @Test
    @DisplayName("A linear-phase change lands on the new design, bit for bit, within 8192 samples")
    void linearPhaseChangeLandsOnTheNewDesign()
    {
        for (final int block : new int[] { 64, 512 })
        {
            final int landed = landing(block, 1);
            System.out.println("    block " + block + ": new design heard after " + landed + " blocks ("
                               + landed * block + " samples)");
            assertTrue(landed > -1);
            assertTrue(landed * block < 8193);
        }
    }

    @Test
    @DisplayName("A linear-phase change lands after the same number of blocks in mono, stereo and six channels")
    void linearPhaseLandingIsChannelIndependent()
    {
        for (final int block : new int[] { 64, 512 })
        {
            final int mono = landing(block, 1), stereo = landing(block, 2), six = landing(block, 6);
            System.out.println("    block " + block + ": heard after " + mono + " / " + stereo + " / " + six
                               + " blocks (1 / 2 / 6 channels)");
            assertTrue(mono > -1);
            assertEquals(stereo, mono);
            assertEquals(stereo, six);
            assertTrue(mono * block < 8193);
        }
    }

    @Test
    @DisplayName("Linear-phase bands match the ideal Linkwitz-Riley magnitudes")
    void linearPhaseBandsMatchTheIdealResponse()
    {
        final int[][] cfgInt = { { 12, 24 }, { 3, 12 } };
        final double[][] cfgF = { { 0.0, 0.0, 2e-6 }, { 50.0, 5000.0, 1.5e-4 } };
        for (int c = 0; c < 2; ++c)
        {
            final int bands = cfgInt[c][0], order = cfgInt[c][1];
            final CrossoverFilter x = new CrossoverFilter();
            x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
            x.setNumBands(bands);
            x.setOrder(order);
            if (cfgF[c][0] > 0.0)
            {
                x.setCrossoverFrequency(0, cfgF[c][0]);
                x.setCrossoverFrequency(1, cfgF[c][1]);
            }
            x.prepare(48000.0, 1, 512);
            final int n = 1 << 15, t0 = 4096, half = 2048;
            final double[] in = new double[n];
            in[t0] = 1.0;
            final double[][] out = split(x, in, bands, 512);
            final int centre = t0 + x.getLatency();
            final int expo = order / 6;
            final double[] cos = new double[2 * half], sin = new double[2 * half];
            double worst = 0.0;
            for (int fi = 0; fi < 120; ++fi)
            {
                final double f = 20.0 * Math.pow(1000.0, fi / 119.0);
                for (int i = -half; i < half; ++i)
                {
                    final double ph = -2.0 * PI * f * i / 48000.0;
                    cos[i + half] = Math.cos(ph);
                    sin[i + half] = Math.sin(ph);
                }
                double carry = 1.0;
                for (int b = 0; b < bands; ++b)
                {
                    double ideal = carry;
                    if (b < bands - 1)
                    {
                        final double r = Math.pow(f / x.getCrossoverFrequency(b), expo);
                        ideal = carry / (1.0 + r);
                        carry *= r / (1.0 + r);
                    }
                    double re = 0.0, im = 0.0;
                    for (int i = -half; i < half; ++i)
                    {
                        final double v = out[b][centre + i];
                        re += v * cos[i + half];
                        im += v * sin[i + half];
                    }
                    final double mag = Math.pow(10.0, 10.0 * Math.log10(re * re + im * im) / 20.0);
                    worst = Math.max(worst, Math.abs(mag - ideal));
                }
            }
            System.out.println("    " + bands + " bands LR" + order + ": worst |H - ideal| " + worst);
            assertTrue(worst < cfgF[c][2]);
        }
    }

    // -- TestEffectsTone.cpp ------------------------------------------------------

    @Test
    @DisplayName("A 6 dB/oct split sums to the input (both structures) and to the delayed input in linear phase")
    void sixDecibelSplitSumsToTheInput()
    {
        final double[] in = m6Noise(24000, 0.5, 21);
        for (final CrossoverFilter.Structure structure : CrossoverFilter.Structure.values())
        {
            final CrossoverFilter x = new CrossoverFilter(6);
            x.setNumBands(4);
            x.setOrder(6);
            x.setStructure(structure);
            x.setCrossoverFrequency(0, 200.0);
            x.setCrossoverFrequency(1, 1000.0);
            x.setCrossoverFrequency(2, 5000.0);
            x.prepare(48000.0, 1, 256);
            assertEquals(6, x.getOrder());
            final double[] s = sum(split(x, in, 4, 256));
            double worst = 0.0;
            for (int i = 0; i < in.length; ++i) worst = Math.max(worst, Math.abs(s[i] - in[i]));
            assertTrue(worst < 2e-6, structure + " " + worst);
        }
        {
            final CrossoverFilter x = new CrossoverFilter(6);
            x.setNumBands(2);
            x.setOrder(6);
            x.setCrossoverFrequency(0, 1000.0);
            x.prepare(48000.0, 1, 256);
            final double[] tone = sine(2000.0, 0.25, 48000.0, 24000);
            final double[][] bands = split(x, tone, 2, 256);
            assertEquals(-7.0, line(bands[0], 2000.0) - line(tone, 2000.0), 0.3);
        }
        {
            final CrossoverFilter x = new CrossoverFilter(6);
            x.setNumBands(3);
            x.setOrder(6);
            x.setFilterMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
            x.setCrossoverFrequency(0, 300.0);
            x.setCrossoverFrequency(1, 3000.0);
            x.prepare(48000.0, 1, 256);
            final double[][] bands = split(x, in, 3, 256);
            final int lat = x.getLatency();
            double worst = 0.0;
            for (int i = lat; i < in.length; ++i)
                worst = Math.max(worst, Math.abs(bands[0][i] + bands[1][i] + bands[2][i] - in[i - lat]));
            assertTrue(worst < 1e-4, "linear phase " + worst);
        }
    }

    private static CrossoverFilter make36(int bands, CrossoverFilter.Structure structure, CrossoverFilter.FilterMode mode)
    {
        final CrossoverFilter x = new CrossoverFilter(6);
        x.setNumBands(bands);
        x.setOrder(36);
        x.setStructure(structure);
        x.setFilterMode(mode);
        if (bands == 2) x.setCrossoverFrequency(0, 1000.0);
        else
        {
            x.setCrossoverFrequency(0, 250.0);
            x.setCrossoverFrequency(1, 1000.0);
            x.setCrossoverFrequency(2, 4000.0);
        }
        x.prepare(48000.0, 1, 256);
        return x;
    }

    @Test
    @DisplayName("A 36 dB/oct split is flat and steep, in both structures and in linear phase")
    void thirtySixDecibelSplitIsFlatAndSteep()
    {
        for (final CrossoverFilter.Structure structure : CrossoverFilter.Structure.values())
        {
            for (final double hz : new double[] { 500.0, 1000.0, 2000.0 })
            {
                final CrossoverFilter x = make36(2, structure, CrossoverFilter.FilterMode.MINIMUM_PHASE);
                final double[] tone = sine(hz, 0.25, 48000.0, 24000);
                final double[][] bands = split(x, tone, 2, 256);
                final double low = line(bands[0], hz) - line(tone, hz), high = line(bands[1], hz) - line(tone, hz);
                if (hz == 1000.0)
                {
                    assertEquals(-6.02, low, 0.05);
                    assertEquals(-6.02, high, 0.05);
                }
                if (hz == 2000.0) assertEquals(-36.1, low, 0.5);
                if (hz == 500.0) assertEquals(-36.1, high, 0.5);
            }
            for (final double hz : new double[] { 100.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 10000.0 })
            {
                final CrossoverFilter x = make36(4, structure, CrossoverFilter.FilterMode.MINIMUM_PHASE);
                final double[] tone = sine(hz, 0.25, 48000.0, 24000);
                final double[] s = sum(split(x, tone, 4, 256));
                assertEquals(0.0, line(s, hz) - line(tone, hz), 0.01, structure + " " + hz + " Hz");
            }
        }
        {
            final CrossoverFilter x = make36(2, CrossoverFilter.Structure.DIRECT_FORM,
                                             CrossoverFilter.FilterMode.LINEAR_PHASE);
            final double[] tone = sine(2000.0, 0.25, 48000.0, 24000);
            final double[][] bands = split(x, tone, 2, 256);
            assertEquals(-36.2, line(bands[0], 2000.0) - line(tone, 2000.0), 0.7);
            final double[] noise = m6Noise(24000, 0.5, 33);
            final CrossoverFilter y = make36(4, CrossoverFilter.Structure.DIRECT_FORM,
                                             CrossoverFilter.FilterMode.LINEAR_PHASE);
            final double[][] sp = split(y, noise, 4, 256);
            final int lat = y.getLatency();
            double worst = 0.0;
            for (int i = lat; i < noise.length; ++i)
                worst = Math.max(worst, Math.abs(sp[0][i] + sp[1][i] + sp[2][i] + sp[3][i] - noise[i - lat]));
            assertTrue(worst < 1e-4, "linear phase " + worst);
        }
        final CrossoverFilter x = new CrossoverFilter(6);
        x.setOrder(36);
        x.setOrder(18);
        assertEquals(36, x.getOrder());
    }
}
