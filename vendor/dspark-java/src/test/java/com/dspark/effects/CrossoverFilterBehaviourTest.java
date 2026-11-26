package com.dspark.effects;

import com.dspark.core.BiquadCoeffs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.dspark.effects.CrossoverSignals.PI;
import static com.dspark.effects.CrossoverSignals.allFinite;
import static com.dspark.effects.CrossoverSignals.kernelResponseDb;
import static com.dspark.effects.CrossoverSignals.lcgNoise;
import static com.dspark.effects.CrossoverSignals.line;
import static com.dspark.effects.CrossoverSignals.maxStep;
import static com.dspark.effects.CrossoverSignals.sine;
import static com.dspark.effects.CrossoverSignals.split;
import static com.dspark.effects.CrossoverSignals.sum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CrossoverFilter}: the figures its documentation states, and the common properties of the port. */
class CrossoverFilterBehaviourTest
{
    private static final CrossoverFilter.FilterMode MP = CrossoverFilter.FilterMode.MINIMUM_PHASE;
    private static final CrossoverFilter.FilterMode LP = CrossoverFilter.FilterMode.LINEAR_PHASE;

    /** Runs planar channels through a crossover in blocks of {@code block}; returns [band][channel][sample]. */
    private static double[][][] splitChannels(CrossoverFilter x, double[][] in, int bands, int block)
    {
        final int chans = in.length, n = in[0].length;
        final double[][][] out = new double[bands][chans][n];
        for (int off = 0; off < n; off += block)
        {
            final int m = Math.min(block, n - off);
            assertEquals(bands, x.process(in, off, out, off, bands, m));
        }
        return out;
    }

    private static boolean sameValues(double[][][] a, double[][][] b)
    {
        for (int band = 0; band < a.length; ++band)
            for (int c = 0; c < a[band].length; ++c)
                for (int i = 0; i < a[band][c].length; ++i)
                    if (!(a[band][c][i] == b[band][c][i])) return false;
        return true;
    }

    private static double[][] stereoNoise(int n, int seed)
    {
        return new double[][] { lcgNoise(n, seed, 0.3f), lcgNoise(n, seed + 1, 0.3f) };
    }

    private static CrossoverFilter configured(int bands, int order, CrossoverFilter.FilterMode mode,
                                              CrossoverFilter.Structure structure, CrossoverFilter.Topology topology,
                                              double... splits)
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(bands);
        x.setOrder(order);
        x.setFilterMode(mode);
        x.setStructure(structure);
        x.setTopology(topology);
        for (int s = 0; s < splits.length; ++s) x.setCrossoverFrequency(s, splits[s]);
        return x;
    }

    @Test
    @DisplayName("Defaults, the log-spaced default splits (100 Hz to 10 kHz) and the setter ranges")
    void defaultsAndRanges()
    {
        final CrossoverFilter x = new CrossoverFilter();
        assertEquals(2, x.getNumBands());
        assertEquals(24, x.getOrder());
        assertEquals(1000.0, x.getCrossoverFrequency(0), 1e-9);
        assertSame(MP, x.getFilterMode());
        assertSame(CrossoverFilter.Structure.DIRECT_FORM, x.getStructure());
        assertSame(CrossoverFilter.Topology.CASCADE, x.getTopology());
        x.setFilterMode(LP);
        assertEquals(0, x.getLatency()); // no engine before prepare()
        x.setNumBands(3);
        assertEquals(100.0 * Math.pow(100.0, 1.0 / 3.0), x.getCrossoverFrequency(0), 1e-9);
        assertEquals(100.0 * Math.pow(100.0, 2.0 / 3.0), x.getCrossoverFrequency(1), 1e-9);
        assertEquals(0.0, x.getCrossoverFrequency(-1), 0.0);
        assertEquals(0.0, x.getCrossoverFrequency(11), 0.0);
        x.setNumBands(99);
        assertEquals(12, x.getNumBands());
        x.setNumBands(0);
        assertEquals(2, x.getNumBands());
        x.setCrossoverFrequency(0, 0.25);
        assertEquals(1.0, x.getCrossoverFrequency(0), 0.0); // floored to 1 Hz
        x.setCrossoverFrequency(1, 500.0);                  // index beyond the splits in use: ignored
        assertTrue(x.getCrossoverFrequency(1) != 500.0);
        for (final int order : new int[] { 6, 12, 24, 36, 48 })
        {
            x.setOrder(order);
            assertEquals(order, x.getOrder());
        }
        x.setOrder(18);
        assertEquals(48, x.getOrder());

        final CrossoverFilter six = new CrossoverFilter(6);
        six.setNumBands(10);
        assertEquals(6, six.getNumBands());
        six.setNumBandsKeepingSplits(1);
        assertEquals(2, six.getNumBands());
        assertThrows(IllegalArgumentException.class, () -> new CrossoverFilter(1));
    }

    @Test
    @DisplayName("Latency: 0 in IIR; three quarters of the FIR in linear phase, equal to the measured delay")
    void latencyIsTheMeasuredDelay()
    {
        final double[] rates = { 44100.0, 48000.0, 96000.0 };
        final int[] expected = { 3072, 3072, 6144 };
        for (int r = 0; r < rates.length; ++r)
        {
            final CrossoverFilter iir = configured(3, 24, MP, CrossoverFilter.Structure.DIRECT_FORM,
                                                   CrossoverFilter.Topology.CASCADE, 300.0, 3000.0);
            iir.prepare(rates[r], 2, 512);
            assertEquals(0, iir.getLatency());

            final CrossoverFilter x = configured(3, 24, LP, CrossoverFilter.Structure.DIRECT_FORM,
                                                 CrossoverFilter.Topology.CASCADE, 300.0, 3000.0);
            x.prepare(rates[r], 2, 512);
            assertEquals(expected[r], x.getLatency(), "rate " + rates[r]);
            final int n = 3 * expected[r], t0 = 700;
            final double[] in = new double[n];
            in[t0] = 1.0;
            final double[] s = sum(split(x, in, 3, 512));
            int argmax = 0;
            for (int i = 0; i < n; ++i) if (Math.abs(s[i]) > Math.abs(s[argmax])) argmax = i;
            assertEquals(t0 + x.getLatency(), argmax);
            for (int i = 0; i < n; ++i) assertEquals(i == argmax ? 1.0 : 0.0, s[i], 1e-12);
            System.out.printf("    %.1f kHz: latency %d (IIR 0)%n", rates[r] / 1000.0, x.getLatency());
        }
    }

    @Test
    @DisplayName("Each branch is 6 dB down at the split (12 to 48 dB/oct); 6 dB/oct is 3 dB in IIR, 6 dB in linear phase")
    void branchLevelsAtTheSplit()
    {
        final double[] tone = sine(1000.0, 0.25, 48000.0, 24000);
        final double ref = line(tone, 1000.0);
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
            for (final int order : new int[] { 6, 12, 24, 36, 48 })
            {
                final CrossoverFilter x = configured(2, order, mode, CrossoverFilter.Structure.DIRECT_FORM,
                                                     CrossoverFilter.Topology.CASCADE, 1000.0);
                x.prepare(48000.0, 1, 256);
                final double[][] bands = split(x, tone, 2, 256);
                final double expected = (order == 6 && mode == MP) ? -3.0103 : -6.0206;
                assertEquals(expected, line(bands[0], 1000.0) - ref, 0.01, mode + " " + order);
                assertEquals(expected, line(bands[1], 1000.0) - ref, 0.01, mode + " " + order);
            }
    }

    @Test
    @DisplayName("Linear phase: band 0 within 0.03 dB of ideal through the split, LR24 at 50 Hz and LR48 at 100 Hz")
    void linearPhaseStaysOnTheIdealAtLowSplits()
    {
        final int[] orders = { 24, 48 };
        final double[] splits = { 50.0, 100.0 };
        for (int c = 0; c < 2; ++c)
        {
            final CrossoverFilter x = configured(2, orders[c], LP, CrossoverFilter.Structure.DIRECT_FORM,
                                                 CrossoverFilter.Topology.CASCADE, splits[c]);
            x.prepare(48000.0, 1, 512);
            final int n = 1 << 15, t0 = 4096;
            final double[] in = new double[n];
            in[t0] = 1.0;
            final double[] band0 = split(x, in, 2, 512)[0];
            final int centre = t0 + x.getLatency();
            double worst = 0.0;
            final double[] ratios = orders[c] == 24 ? new double[] { 0.25, 0.5, 0.7071, 1.0, 1.4142, 2.0 }
                                                    : new double[] { 0.25, 0.5, 0.7071, 1.0, 1.4142 };
            for (final double r : ratios)
            {
                final double ideal = 20.0 * Math.log10(1.0 / (1.0 + Math.pow(r, orders[c] / 6)));
                worst = Math.max(worst, Math.abs(kernelResponseDb(band0, centre, 4096, r * splits[c], 48000.0) - ideal));
            }
            System.out.printf("    LR%d at %.0f Hz: band 0 within %.4f dB of ideal%n", orders[c], splits[c], worst);
            assertTrue(worst < 0.03);
        }
    }

    @Test
    @DisplayName("Linear phase: at the default splits two to four of the eight kernel partitions remain per band")
    void keptPartitionsAtTheDefaultSplits()
    {
        for (int bands = 2; bands <= 12; ++bands)
        {
            final CrossoverFilter x = new CrossoverFilter();
            x.setFilterMode(LP);
            x.setNumBands(bands);
            x.prepare(48000.0, 2, 512);
            for (int b = 0; b < bands - 1; ++b)
            {
                final int kept = x.keptLinearPhasePartitions(b);
                assertTrue(kept >= 2 && kept <= 4, bands + " bands, band " + b + ": " + kept);
            }
        }
    }

    @Test
    @DisplayName("LR12 and LR36 come out with the odd bands polarity-inverted; LR24 does not")
    void oddBandsOfTheOddSlopesAreInverted()
    {
        for (final int order : new int[] { 12, 24, 36 })
            for (final CrossoverFilter.Structure structure : CrossoverFilter.Structure.values())
            {
                final double sign = (order == 24) ? 1.0 : -1.0;
                final CrossoverFilter x = configured(3, order, MP, structure, CrossoverFilter.Topology.CASCADE,
                                                     100.0, 1000.0);
                x.prepare(48000.0, 1, 256);
                // At the geometric middle of band 1 the phases of its two
                // splits cancel, and far above the top split they vanish, so
                // the sign of each band is all that is left.
                final double[] mid = sine(316.0, 0.5, 48000.0, 24000);
                final double[] high = sine(16000.0, 0.5, 48000.0, 24000);
                assertTrue(correlation(split(x, mid, 3, 256)[1], mid) * sign > 0.99, order + " band 1");
                x.reset();
                assertTrue(correlation(split(x, high, 3, 256)[2], high) > 0.9, order + " band 2");
            }
    }

    /** Correlation coefficient of the last 4800 samples. */
    private static double correlation(double[] a, double[] b)
    {
        double ab = 0, aa = 0, bb = 0;
        for (int i = a.length - 4800; i < a.length; ++i)
        {
            ab += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return ab / Math.sqrt(aa * bb);
    }

    @Test
    @DisplayName("The first-order allpass is -90 degrees at its frequency and passes every frequency at unity")
    void firstOrderAllPassPhase()
    {
        for (final double fc : new double[] { 50.0, 1000.0, 15000.0 })
        {
            final BiquadCoeffs c = CrossoverFilter.makeFirstOrderAllPass(48000.0, fc);
            final double[] at = response(c, fc, 48000.0);
            assertEquals(-90.0, Math.toDegrees(Math.atan2(at[1], at[0])), 1e-9, "fc " + fc);
            for (final double f : new double[] { 10.0, 300.0, 5000.0, 23000.0 })
            {
                final double[] h = response(c, f, 48000.0);
                assertEquals(1.0, Math.hypot(h[0], h[1]), 1e-12);
            }
        }
    }

    /** H(e^jw) of a biquad as (re, im). */
    private static double[] response(BiquadCoeffs c, double f, double fs)
    {
        final double w = 2.0 * PI * f / fs;
        final double c1 = Math.cos(w), s1 = -Math.sin(w), c2 = Math.cos(2 * w), s2 = -Math.sin(2 * w);
        final double nr = c.b0 + c.b1 * c1 + c.b2 * c2, ni = c.b1 * s1 + c.b2 * s2;
        final double dr = 1.0 + c.a1 * c1 + c.a2 * c2, di = c.a1 * s1 + c.a2 * s2;
        final double d = dr * dr + di * di;
        return new double[] { (nr * dr + ni * di) / d, (ni * dr - nr * di) / d };
    }

    @Test
    @DisplayName("Balanced tree: band 0 of six at LR24 is -102 dB at 2 kHz against -78 in the cascade")
    void balancedSkirtsDiffer()
    {
        final double[] tone = sine(2000.0, 1.0, 48000.0, 48000);
        final double[] expected = { -78.0, -102.0 };
        int k = 0;
        for (final CrossoverFilter.Topology topology : CrossoverFilter.Topology.values())
        {
            final CrossoverFilter x = new CrossoverFilter();
            x.setNumBands(6);
            x.setTopology(topology);
            x.prepare(48000.0, 1, 256);
            final double level = line(split(x, tone, 6, 256)[0], 2000.0) - line(tone, 2000.0);
            System.out.printf("    %s: band 0 at 2 kHz %.2f dB%n", topology, level);
            assertEquals(expected[k++], level, 1.0);
        }
    }

    @Test
    @DisplayName("Ladders give the direct form's band responses (to 4e-9) at every slope and in the tree")
    void laddersMatchTheDirectForm()
    {
        final double[] in = lcgNoise(12000, 0x13572468, 0.3f);
        for (final CrossoverFilter.Topology topology : CrossoverFilter.Topology.values())
            for (final int order : new int[] { 6, 12, 24, 36, 48 })
            {
                final double[][] a = split(configured(5, order, MP, CrossoverFilter.Structure.DIRECT_FORM, topology),
                                           in, 5, 333);
                final double[][] b = split(configured(5, order, MP, CrossoverFilter.Structure.LADDER, topology),
                                           in, 5, 333);
                double worst = 0.0;
                for (int band = 0; band < 5; ++band)
                    for (int i = 0; i < in.length; ++i) worst = Math.max(worst, Math.abs(a[band][i] - b[band][i]));
                assertTrue(worst < 4e-9, topology + " LR" + order + ": " + worst);
            }
        final double[] in2 = lcgNoise(24000, 0x13572468, 0.3f);
        for (final int order : new int[] { 24, 48 })
        {
            final double[] pk = CrossoverFilterTest.jumpPeaks(order, in2);
            System.out.printf("    split jumping 100 Hz / 10 kHz every 64 samples, LR%d: band-sum peak %.3f direct, %.3f ladder%n",
                              order, pk[0], pk[1]);
        }
    }

    @Test
    @DisplayName("Linear phase: band 0's largest step through a design change is the one of its new steady state")
    void linearPhaseDesignChangeStep()
    {
        final CrossoverFilter x = configured(2, 24, LP, CrossoverFilter.Structure.DIRECT_FORM,
                                             CrossoverFilter.Topology.CASCADE, 500.0);
        x.prepare(48000.0, 1, 256);
        final int blocks = 375, change = 188;
        final double[] tone = sine(1000.0, 0.5, 48000.0, blocks * 256);
        final double[] band0 = new double[blocks * 256];
        final double[][][] views = new double[2][1][256];
        for (int k = 0; k < blocks; ++k)
        {
            if (k == change) x.setCrossoverFrequency(0, 2000.0);
            x.process(new double[][] { tone }, k * 256, views, 0, 2, 256);
            System.arraycopy(views[0][0], 0, band0, k * 256, 256);
        }
        final int at = change * 256;
        final double steady = maxStep(band0, at + 9600, band0.length);
        final double during = maxStep(band0, at, at + 9600);
        System.out.printf("    1 kHz at 0.5, split 500 Hz -> 2 kHz: band 0 step %.4f during, %.4f steady%n", during, steady);
        assertTrue(steady > 0.060 && steady < 0.062);
        assertTrue(during <= steady * 1.001);
    }

    @Test
    @DisplayName("setNumBandsKeepingSplits(): a split that comes back takes the frequency it had")
    void splitsComeBack()
    {
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(4);
        x.setCrossoverFrequency(0, 200.0);
        x.setCrossoverFrequency(1, 2000.0);
        x.setCrossoverFrequency(2, 6000.0);
        x.setNumBandsKeepingSplits(2);
        x.setNumBandsKeepingSplits(4);
        assertEquals(200.0, x.getCrossoverFrequency(0), 0.0);
        assertEquals(2000.0, x.getCrossoverFrequency(1), 0.0);
        assertEquals(6000.0, x.getCrossoverFrequency(2), 0.0);
        x.setNumBandsKeepingSplits(2);
        x.setCrossoverFrequency(0, 9000.0);
        x.setNumBandsKeepingSplits(4);
        assertEquals(Math.sqrt(9000.0 * 20000.0), x.getCrossoverFrequency(1), 1e-9);
        assertEquals(Math.sqrt(x.getCrossoverFrequency(1) * 20000.0), x.getCrossoverFrequency(2), 1e-9);
    }

    @Test
    @DisplayName("Channels beyond the prepared count, or beyond 16, pass through on band 0 with the other bands silent")
    void extraChannelsPassThrough()
    {
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
        {
            final CrossoverFilter x = configured(3, 24, mode, CrossoverFilter.Structure.DIRECT_FORM,
                                                 CrossoverFilter.Topology.CASCADE, 300.0, 3000.0);
            x.prepare(48000.0, 18, 256);
            final double[][] in = new double[18][];
            for (int c = 0; c < 18; ++c) in[c] = lcgNoise(256, 100 + c, 0.3f);
            final double[][][] out = new double[3][18][256];
            for (final double[][] band : out) for (final double[] ch : band) Arrays.fill(ch, 7.0);
            assertEquals(3, x.process(in, out, 3, 256));
            for (int c = 16; c < 18; ++c)
            {
                assertTrue(Arrays.equals(in[c], out[0][c]));
                for (int i = 0; i < 256; ++i)
                {
                    assertEquals(0.0, out[1][c][i], 0.0);
                    assertEquals(0.0, out[2][c][i], 0.0);
                }
            }
            for (int c = 0; c < 16; ++c) assertTrue(out[1][c][100] != 7.0);

            final CrossoverFilter mono = configured(2, 24, mode, CrossoverFilter.Structure.DIRECT_FORM,
                                                    CrossoverFilter.Topology.CASCADE, 1000.0);
            mono.prepare(48000.0, 1, 256);
            final double[][] st = { lcgNoise(256, 5, 0.3f), lcgNoise(256, 6, 0.3f) };
            final double[][][] o2 = new double[2][2][256];
            assertEquals(2, mono.process(st, o2, 2, 256));
            assertTrue(Arrays.equals(st[1], o2[0][1]));
            for (final double v : o2[1][1]) assertEquals(0.0, v, 0.0);
        }
    }

    @Test
    @DisplayName("process() returns the bands written: 0 unprepared, for null outputs, under 2 bands or no samples")
    void processReturnValues()
    {
        final double[][] in = { new double[256] };
        final double[][][] out = new double[4][1][256];
        final CrossoverFilter x = new CrossoverFilter();
        x.setNumBands(4);
        assertEquals(0, x.process(in, out, 4, 256));
        x.prepare(48000.0, 1, 256);
        assertEquals(0, x.process(in, null, 4, 256));
        assertEquals(0, x.process(in, out, 1, 256));
        assertEquals(0, x.process(in, out, 4, 0));
        assertEquals(4, x.process(in, out, 12, 256)); // clamped to the arrays given
        assertEquals(4, x.process(in, out, 4, 256));

        // Arrays past the bands in use may be missing.
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
        {
            final CrossoverFilter z = configured(3, 24, mode, CrossoverFilter.Structure.DIRECT_FORM,
                                                 CrossoverFilter.Topology.CASCADE, 300.0, 3000.0);
            z.prepare(48000.0, 1, 256);
            final CrossoverFilter ref = configured(3, 24, mode, CrossoverFilter.Structure.DIRECT_FORM,
                                                   CrossoverFilter.Topology.CASCADE, 300.0, 3000.0);
            ref.prepare(48000.0, 1, 256);
            final double[][] noise = { lcgNoise(256, 3, 0.3f) };
            final double[][][] sparse = new double[12][][];
            for (int b = 0; b < 3; ++b) sparse[b] = new double[1][256];
            final double[][][] full = new double[3][1][256];
            assertEquals(3, z.process(noise, sparse, 12, 256));
            assertEquals(3, ref.process(noise, full, 3, 256));
            for (int b = 0; b < 3; ++b) assertTrue(Arrays.equals(full[b][0], sparse[b][0]), mode + " band " + b);
        }

        // Linear phase, fewer outputs than bands: the extra bands fold into the
        // last one, so the sum is still the delayed input.
        final CrossoverFilter y = configured(4, 24, LP, CrossoverFilter.Structure.DIRECT_FORM,
                                             CrossoverFilter.Topology.CASCADE);
        y.prepare(48000.0, 1, 512);
        final double[] noise = lcgNoise(12288, 9, 0.3f);
        final double[] s = sum(split(y, noise, 3, 512));
        final int lat = y.getLatency();
        double worst = 0.0;
        for (int i = lat; i < noise.length; ++i) worst = Math.max(worst, Math.abs(s[i] - noise[i - lat]));
        assertTrue(worst < 1e-12, "fold " + worst);
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 samples give bit-identical bands")
    void blockSizeIndependence()
    {
        final double[][] in = stereoNoise(3 * 4096, 31);
        final Object[][] cfgs = {
            { 4, 24, MP, CrossoverFilter.Structure.DIRECT_FORM, CrossoverFilter.Topology.CASCADE },
            { 5, 48, MP, CrossoverFilter.Structure.LADDER, CrossoverFilter.Topology.BALANCED },
            { 3, 6, MP, CrossoverFilter.Structure.DIRECT_FORM, CrossoverFilter.Topology.CASCADE },
            { 4, 24, LP, CrossoverFilter.Structure.DIRECT_FORM, CrossoverFilter.Topology.CASCADE },
        };
        for (final Object[] c : cfgs)
        {
            double[][][] ref = null;
            for (final int block : new int[] { 4096, 512, 37, 1 })
            {
                final CrossoverFilter x = configured((Integer) c[0], (Integer) c[1], (CrossoverFilter.FilterMode) c[2],
                                                     (CrossoverFilter.Structure) c[3], (CrossoverFilter.Topology) c[4]);
                x.prepare(48000.0, 2, 4096);
                final double[][][] out = splitChannels(x, in, (Integer) c[0], block);
                if (ref == null) ref = out;
                else
                    for (int b = 0; b < out.length; ++b)
                        for (int ch = 0; ch < 2; ++ch)
                            assertTrue(Arrays.equals(ref[b][ch], out[b][ch]), Arrays.toString(c) + " block " + block);
            }
        }
    }

    @Test
    @DisplayName("reset() equals a fresh instance, and two instances give the same bits")
    void resetEqualsFresh()
    {
        final double[][] first = stereoNoise(8192, 41), second = stereoNoise(8192, 43);
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
        {
            final CrossoverFilter used = configured(5, 48, mode, CrossoverFilter.Structure.LADDER,
                                                    CrossoverFilter.Topology.BALANCED, 120.0, 800.0, 3000.0, 9000.0);
            used.prepare(48000.0, 2, 512);
            splitChannels(used, first, 5, 512);
            used.reset();
            final double[][][] a = splitChannels(used, second, 5, 512);
            final CrossoverFilter fresh = configured(5, 48, mode, CrossoverFilter.Structure.LADDER,
                                                     CrossoverFilter.Topology.BALANCED, 120.0, 800.0, 3000.0, 9000.0);
            fresh.prepare(48000.0, 2, 512);
            final double[][][] b = splitChannels(fresh, second, 5, 512);
            assertTrue(sameValues(a, b), mode.toString());
            final CrossoverFilter twin = configured(5, 48, mode, CrossoverFilter.Structure.LADDER,
                                                    CrossoverFilter.Topology.BALANCED, 120.0, 800.0, 3000.0, 9000.0);
            twin.prepare(48000.0, 2, 512);
            assertTrue(sameValues(b, splitChannels(twin, second, 5, 512)), mode + " twin");
        }
    }

    @Test
    @DisplayName("Settings made before prepare() play from the first sample: no ramp from the defaults")
    void settingsBeforePrepareAreInPlaceAtOnce()
    {
        final double[][] in = stereoNoise(8192, 51);
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
            for (final CrossoverFilter.Structure structure : CrossoverFilter.Structure.values())
            {
                final CrossoverFilter before = configured(4, 48, mode, structure, CrossoverFilter.Topology.CASCADE,
                                                          150.0, 1500.0, 7000.0);
                before.prepare(48000.0, 2, 512);
                final double[][][] a = splitChannels(before, in, 4, 512);

                final CrossoverFilter after = new CrossoverFilter();
                after.setFilterMode(mode);
                after.setStructure(structure);
                after.prepare(48000.0, 2, 512);
                after.setNumBands(4);
                after.setOrder(48);
                after.setCrossoverFrequency(0, 150.0);
                after.setCrossoverFrequency(1, 1500.0);
                after.setCrossoverFrequency(2, 7000.0);
                splitChannels(after, new double[2][48000], 4, 512); // glide or design, then settle
                after.reset();
                final double[][][] b = splitChannels(after, in, 4, 512);
                assertTrue(sameValues(a, b), mode + " " + structure);
            }
    }

    @Test
    @DisplayName("Output stays finite with extreme splits, loud and non-finite input, in every configuration")
    void finiteUnderExtremes()
    {
        final double[] loud = lcgNoise(4096, 61, 1000.0f);
        loud[100] = Double.POSITIVE_INFINITY;
        loud[2000] = Double.NaN;
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
            for (final CrossoverFilter.Structure structure : CrossoverFilter.Structure.values())
                for (final CrossoverFilter.Topology topology : CrossoverFilter.Topology.values())
                    for (final int order : new int[] { 6, 12, 24, 36, 48 })
                    {
                        final CrossoverFilter x = configured(4, order, mode, structure, topology, 1.0, 5000.0, 1e9);
                        x.prepare(48000.0, 1, 512);
                        for (final double[] band : split(x, loud, 4, 512))
                            assertTrue(allFinite(band), mode + " " + structure + " " + topology + " " + order);
                    }
    }

    @Test
    @DisplayName("Cost of process() in ns per stereo frame at 48 kHz (printed)")
    void cost()
    {
        final int n = 48000;
        final double[][] in = stereoNoise(n, 71);
        final Object[][] cfgs = { { 2, MP }, { 4, MP }, { 6, MP }, { 12, MP }, { 2, LP }, { 6, LP }, { 12, LP } };
        final CrossoverFilter[] xs = new CrossoverFilter[cfgs.length];
        final double[][][][] views = new double[cfgs.length][][][];
        for (int k = 0; k < cfgs.length; ++k)
        {
            xs[k] = new CrossoverFilter();
            xs[k].setNumBands((Integer) cfgs[k][0]);
            xs[k].setFilterMode((CrossoverFilter.FilterMode) cfgs[k][1]);
            xs[k].prepare(48000.0, 2, 512);
            views[k] = new double[(Integer) cfgs[k][0]][2][512];
        }
        for (int warm = 0; warm < 8; ++warm)
            for (int k = 0; k < cfgs.length; ++k) runSecond(xs[k], in, views[k]);
        for (int k = 0; k < cfgs.length; ++k)
        {
            double best = Double.MAX_VALUE;
            for (int rep = 0; rep < 5; ++rep)
            {
                final long t0 = System.nanoTime();
                runSecond(xs[k], in, views[k]);
                best = Math.min(best, (System.nanoTime() - t0) / (double) n);
            }
            System.out.printf("    %s %d bands LR24: %.1f ns per stereo frame%n", cfgs[k][1], cfgs[k][0], best);
        }
    }

    private static void runSecond(CrossoverFilter x, double[][] in, double[][][] views)
    {
        final int n = in[0].length;
        for (int off = 0; off < n; off += 512) x.process(in, off, views, 0, views.length, Math.min(512, n - off));
    }

    @Test
    @DisplayName("A tone glides with a moving split and lands on the new split's response")
    void glideLandsOnTheNewSplit()
    {
        final CrossoverFilter x = configured(2, 24, MP, CrossoverFilter.Structure.DIRECT_FORM,
                                             CrossoverFilter.Topology.CASCADE, 500.0);
        x.prepare(48000.0, 1, 256);
        split(x, new double[4096], 2, 256);
        x.setCrossoverFrequency(0, 2000.0);
        final double[] tone = sine(1000.0, 0.5, 48000.0, 24000);
        final double[][] moved = split(x, tone, 2, 256);
        final CrossoverFilter direct = configured(2, 24, MP, CrossoverFilter.Structure.DIRECT_FORM,
                                                  CrossoverFilter.Topology.CASCADE, 2000.0);
        direct.prepare(48000.0, 1, 256);
        final double[][] ref = split(direct, tone, 2, 256);
        // After the 5 ms smoothing (and the sections' own settling) both carry the same band 0.
        double apart = 0.0;
        for (int i = tone.length - 4800; i < tone.length; ++i) apart = Math.max(apart, Math.abs(moved[0][i] - ref[0][i]));
        assertTrue(apart < 1e-9, "apart " + apart);
        assertEquals(20.0 * Math.log10(1.0 / (1.0 + Math.pow(0.5, 4))), line(moved[0], 1000.0) - line(tone, 1000.0), 0.01);
    }
}
