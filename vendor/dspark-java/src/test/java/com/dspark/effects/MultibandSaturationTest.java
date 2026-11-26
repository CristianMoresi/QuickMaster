package com.dspark.effects;

import com.dspark.core.Oversampling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

import static com.dspark.effects.CrossoverSignals.line;
import static com.dspark.effects.CrossoverSignals.m6Noise;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MultibandSaturation}: the assertions of the C++ tests (same signals and thresholds), figures and properties. */
class MultibandSaturationTest
{
    private static final double PI = 3.14159265358979323846;

    /** A sine rounded to float, at 48 kHz (the C++ sat8Sine). */
    private static double[] sine(double hz, double amp, int n)
    {
        final double[] v = new double[n];
        for (int i = 0; i < n; ++i) v[i] = (float) (amp * Math.sin(2.0 * PI * hz * i / 48000.0));
        return v;
    }

    /** 0.25 of 100 Hz plus 0.25 of 3 kHz (the C++ mbTwoTones). */
    private static double[] twoTones(int n)
    {
        final double[] v = new double[n];
        for (int i = 0; i < n; ++i)
            v[i] = (float) (0.25 * Math.sin(2.0 * PI * 100.0 * i / 48000.0) + 0.25 * Math.sin(2.0 * PI * 3000.0 * i / 48000.0));
        return v;
    }

    /** A stereo render in blocks; the processor set up before prepare() (the C++ mbRun). */
    private static double[][] run(double[] left, double[] right, Consumer<MultibandSaturation> setup, int block,
                                  BiConsumer<MultibandSaturation, Integer> during)
    {
        final MultibandSaturation m = new MultibandSaturation();
        setup.accept(m);
        m.prepare(48000.0, 2, block);
        final double[][] x = { left.clone(), right.clone() };
        for (int off = 0; off < left.length; off += block)
        {
            if (during != null) during.accept(m, off);
            m.process(x, off, Math.min(block, left.length - off));
        }
        return x;
    }

    private static double[][] run(double[] left, double[] right, Consumer<MultibandSaturation> setup)
    {
        return run(left, right, setup, 512, null);
    }

    private static double amplitude(double[] v, double hz)
    {
        return Math.pow(10.0, line(v, hz) / 20.0);
    }

    private static double peak(double[] v)
    {
        double top = 0.0;
        for (final double x : v) top = Math.max(top, Math.abs(x));
        return top;
    }

    // -- TestEffectsTone.cpp ----------------------------------------------------------

    @Test
    @DisplayName("At no drive the bands sum back flat and in time, also with bands at other factors")
    void sumsTheBandsBackFlatAndInTime()
    {
        final Consumer<MultibandSaturation> three = m ->
        {
            m.setNumBands(3);
            m.setCrossoverFrequency(0, 300.0);
            m.setCrossoverFrequency(1, 3000.0);
        };
        final Consumer<MultibandSaturation> uneven = m ->
        {
            three.accept(m);
            m.getBandSaturation(1).setOversampling(4);
            m.getBandSaturation(2).setOversampling(8);
        };
        for (final double hz : new double[] { 100.0, 300.0, 1000.0, 3000.0, 10000.0 })
        {
            final double[] in = sine(hz, 0.01, 24000);
            final double[][] even = run(in, in, three);
            final double[][] mixed = run(in, in, uneven);
            assertEquals(0.0, line(even[0], hz) - line(in, hz), 0.1, hz + " Hz even");
            assertEquals(0.0, line(mixed[0], hz) - line(in, hz), 0.15, hz + " Hz mixed");
            assertEquals(line(mixed[0], hz), line(mixed[1], hz), 1e-6);
        }
        final MultibandSaturation m = new MultibandSaturation();
        uneven.accept(m);
        m.prepare(48000.0, 2, 512);
        int slowest = 0;
        for (int b = 0; b < 3; ++b) slowest = Math.max(slowest, m.getBandSaturation(b).getLatency());
        assertEquals(slowest, m.getLatency());
        assertTrue(m.getBandSaturation(1).getLatency() > m.getBandSaturation(0).getLatency());
    }

    @Test
    @DisplayName("Each band saturates on its own: the driven high band gains harmonics, the low tone stays clean")
    void drivesOneBandAndLeavesTheOthersClean()
    {
        final double[] in = twoTones(24000);
        final DoubleFunction<Consumer<MultibandSaturation>> split = highDrive -> m ->
        {
            m.setNumBands(2);
            m.setCrossoverFrequency(0, 1000.0);
            m.setBandDrive(1, highDrive);
        };
        final double[][] out = run(in, in, split.apply(24.0));
        final double[][] calm = run(in, in, split.apply(0.0));
        assertTrue(line(out[0], 9000.0) - line(out[0], 3000.0) > -40.0);
        assertTrue(line(calm[0], 9000.0) - line(calm[0], 3000.0) < -40.0);
        assertEquals(line(calm[0], 300.0), line(out[0], 300.0), 0.5);
        assertEquals(line(calm[0], 100.0), line(out[0], 100.0), 0.05);
    }

    @Test
    @DisplayName("Level, pan, mute and solo per band, with fades; a band not heard is not computed and comes back clean")
    void levelPanMuteAndSolo()
    {
        final double[] in = twoTones(48000);
        final Consumer<MultibandSaturation> two = m ->
        {
            m.setNumBands(2);
            m.setCrossoverFrequency(0, 1000.0);
        };
        final double[][] plain = run(in, in, two);

        final double[][] quieter = run(in, in, m -> { two.accept(m); m.setBandLevel(0, -6.0); });
        assertEquals(-6.02, line(quieter[0], 100.0) - line(plain[0], 100.0), 0.1);
        assertEquals(0.0, line(quieter[0], 3000.0) - line(plain[0], 3000.0), 0.05);

        final double[][] panned = run(in, in, m -> { two.accept(m); m.setBandPan(1, -1.0); });
        System.out.printf("    panned hard left: the high tone on the right %.1f dB%n",
                          line(panned[1], 3000.0) - line(plain[1], 3000.0));
        assertTrue(line(panned[1], 3000.0) - line(plain[1], 3000.0) < -30.0);
        assertEquals(0.0, line(panned[0], 3000.0) - line(plain[0], 3000.0), 0.05);

        final boolean[] computedAfter = { true };
        final double[][] muted = run(in, in, two, 512, (m, at) ->
        {
            if (at == 24576) m.setBandMute(0, true);
            if (at + 512 >= 48000) computedAfter[0] = m.isBandComputed(0);
        });
        assertTrue(line(muted[0], 100.0) - line(plain[0], 100.0) < -70.0);
        assertFalse(computedAfter[0]);
        double step = 0.0, steady = 0.0;
        for (int i = 1; i < 48000; ++i)
        {
            final double d = Math.abs(muted[0][i] - muted[0][i - 1]);
            if (i > 20000 && i < 24000) steady = Math.max(steady, d);
            if (i >= 24576 && i < 26000) step = Math.max(step, d);
        }
        assertTrue(step < steady * 1.1);

        final double[][] soloed = run(in, in, m -> { two.accept(m); m.setBandSolo(1, true); });
        assertTrue(line(soloed[0], 100.0) - line(plain[0], 100.0) < -70.0);
        assertEquals(20.0 * Math.log10(81.0 / 82.0), line(soloed[0], 3000.0) - line(plain[0], 3000.0), 0.05);
        final double[][] back = run(in, in, m -> { two.accept(m); m.setBandMute(0, true); }, 512, (m, at) ->
        {
            if (at == 12288) m.setBandMute(0, false);
        });
        assertEquals(line(plain[0], 100.0), line(back[0], 100.0), 0.05);

        // Wild values.
        final MultibandSaturation a = new MultibandSaturation();
        two.accept(a);
        a.setBandLevel(0, -3.5);
        a.setBandLevel(0, Double.NaN);
        a.setBandLevel(9, 5.0);
        a.setBandPan(1, 4.0);
        a.setBandSolo(1, true);
        a.setBandDrive(1, 9.0);
        assertEquals(-3.5, a.getBandLevel(0), 0.0);
        assertEquals(1.0, a.getBandPan(1), 0.0);
        assertTrue(a.isBandSoloed(1));
        assertEquals(2, a.getNumBands());
    }

    @Test
    @DisplayName("The mix keeps the phase of the crossover: a half mix is the mean of the dry and the wet side")
    void mixKeepsThePhaseOfTheCrossover()
    {
        final DoubleFunction<Consumer<MultibandSaturation>> three = mix -> m ->
        {
            m.setNumBands(3);
            m.setCrossoverFrequency(0, 300.0);
            m.setCrossoverFrequency(1, 3000.0);
            m.getBandSaturation(1).setOversampling(4);
            m.setMix(mix);
        };
        for (final double hz : new double[] { 100.0, 300.0, 1000.0, 3000.0, 10000.0 })
        {
            final double[] in = sine(hz, 0.01, 24000);
            final double source = amplitude(in, hz);
            final double dry = amplitude(run(in, in, three.apply(0.0))[0], hz);
            final double half = amplitude(run(in, in, three.apply(0.5))[0], hz);
            final double wet = amplitude(run(in, in, three.apply(1.0))[0], hz);
            assertEquals(1.0, dry / source, 0.005, hz + " Hz dry");
            assertEquals(0.5 * (dry + wet), half, 0.003 * source, hz + " Hz half");
        }
        final double[] in = sine(1000.0, 0.25, 24000);
        final DoubleFunction<Consumer<MultibandSaturation>> driven = mix -> m ->
        {
            m.setNumBands(2);
            m.setCrossoverFrequency(0, 200.0);
            m.setBandLevel(1, -12.0);
            m.setMix(mix);
        };
        final double dry = amplitude(run(in, in, driven.apply(0.0))[0], 1000.0);
        final double wet = amplitude(run(in, in, driven.apply(1.0))[0], 1000.0);
        final double half = amplitude(run(in, in, driven.apply(0.5))[0], 1000.0);
        assertEquals(0.5 * (dry + wet), half, 0.01 * dry);
        assertTrue(wet < 0.5 * dry);

        final MultibandSaturation m = new MultibandSaturation();
        m.setMix(0.25);
        m.setMix(Double.NaN);
        m.setOutputClip(true);
        m.setOutputCeiling(-6.0);
        m.setOutputCeiling(Double.POSITIVE_INFINITY);
        assertEquals(0.25, m.getMix(), 1e-6);
        assertTrue(m.getOutputClip());
        assertEquals(-6.0, m.getOutputCeiling(), 1e-6);
        m.setOutputCeiling(-40.0);
        assertEquals(-24.0, m.getOutputCeiling(), 0.0);
        m.setMix(3.0);
        assertEquals(1.0, m.getMix(), 0.0);
    }

    @Test
    @DisplayName("The output clipper holds its ceiling; under it the sum leaves unchanged, late by the clipper")
    void outputClipHoldsItsCeiling()
    {
        final double[] loud = m6Noise(24000, 0.9, 41);
        final Consumer<MultibandSaturation> base6 = m ->
        {
            m.setNumBands(6);
            for (int b = 0; b < 6; ++b)
            {
                m.setBandDrive(b, 24.0);
                m.setBandLevel(b, 6.0);
            }
        };
        final double free = peak(run(loud, loud, m -> { base6.accept(m); m.setOutputClip(false); })[0]);
        final double held = peak(run(loud, loud, m -> { base6.accept(m); m.setOutputClip(true); m.setOutputCeiling(0.0); })[0]);
        final double lower = peak(run(loud, loud, m -> { base6.accept(m); m.setOutputClip(true); m.setOutputCeiling(-6.0); })[0]);
        System.out.printf("    six driven bands peak at %.2f of full scale; %.4f with the clipper, %.4f with its ceiling at -6 dB%n",
                          free, held, lower);
        assertTrue(free > 1.5);
        assertTrue(held < 1.0 + 1e-6);
        assertTrue(lower < 0.5012 + 1e-6);

        final double[] quiet = sine(1000.0, 0.05, 24000);
        final double[][] plain = run(quiet, quiet, m -> m.setNumBands(3));
        final double[][] clipped = run(quiet, quiet, m -> { m.setNumBands(3); m.setOutputClip(true); });
        final MultibandSaturation a = new MultibandSaturation(), b = new MultibandSaturation();
        a.setNumBands(3);
        b.setNumBands(3);
        b.setOutputClip(true);
        a.prepare(48000.0, 2, 512);
        b.prepare(48000.0, 2, 512);
        final int late = b.getLatency() - a.getLatency();
        assertTrue(late > 0);
        double apart = 0.0;
        for (int i = 4800; i + late < quiet.length; ++i) apart = Math.max(apart, Math.abs(clipped[0][i + late] - plain[0][i]));
        assertTrue(apart < 1e-4, "apart " + apart);
    }

    /** How far a half mix falls short of the mean of the two ends, in dB (the C++ mixShortDb). */
    private static double mixShortDb(java.util.function.BiFunction<Double, Double, double[]> render, double hz)
    {
        final double dry = amplitude(render.apply(0.0, hz), hz), wet = amplitude(render.apply(1.0, hz), hz);
        return 20.0 * Math.log10(amplitude(render.apply(0.5, hz), hz) / (0.5 * (dry + wet)));
    }

    @Test
    @DisplayName("A half mix keeps the bottom and the top, with linear and minimum-phase oversampling")
    void halfMixKeepsTheBottomAndTheTop()
    {
        for (final int way : new int[] { 0, 1, 2 })
        {
            final java.util.function.BiFunction<Double, Double, double[]> render = (mix, hz) ->
            {
                final double[] in = sine(hz, 0.02, 72000);
                return run(in, in, m ->
                {
                    if (way >= 1) m.setOversamplingMode(Saturation.OversamplingMode.MINIMUM_PHASE);
                    if (way == 2) m.getBandSaturation(1).setOversampling(4);
                    m.setMix(mix);
                })[0];
            };
            double worst = 0.0;
            for (final double hz : new double[] { 20.0, 30.0, 50.0, 1000.0, 15000.0, 19000.0 })
                worst = Math.min(worst, mixShortDb(render, hz));
            System.out.printf("    multiband, setting %d: a half mix is short of the mean by %.3f dB at most%n", way, -worst);
            assertTrue(worst > -0.25);
        }
    }

    @Test
    @DisplayName("The dry side holds a muted band at full; at zero mix it is the sum of the crossover")
    void drySideHoldsAMutedBand()
    {
        final double[] in = sine(10000.0, 0.02, 24000);
        final DoubleFunction<Double> at = mix -> line(run(in, in, m ->
        {
            m.setNumBands(2);
            m.setCrossoverFrequency(0, 1000.0);
            m.setBandMute(1, true);
            m.setMix(mix);
        })[0], 10000.0);
        final double source = line(in, 10000.0);
        assertEquals(source, at.apply(0.0), 0.05);
        assertEquals(source - 6.0206, at.apply(0.5), 0.1);
    }

    @Test
    @DisplayName("The phase of the dry side comes and goes without a step as the mix moves to each end")
    void mixPhaseTurnsWithoutAStep()
    {
        final int n = 512 * 190;
        final MultibandSaturation p = new MultibandSaturation();
        p.setMix(0.0);
        p.prepare(48000.0, 2, 512);
        final double[] in = sine(50.0, 0.1, n);
        final double[][] x = { in.clone(), in.clone() };
        for (int off = 0; off < n; off += 512)
        {
            if (off == 512 * 40) p.setMix(0.5);
            if (off == 512 * 80) p.setMix(1.0);
            if (off == 512 * 110) p.setMix(0.5);
            if (off == 512 * 140) p.setMix(0.0);
            p.process(x, off, 512);
        }
        double moving = 0.0, still = 0.0;
        for (int i = 512 * 30; i < n; ++i) moving = Math.max(moving, Math.abs(x[0][i] - x[0][i - 1]));
        for (int i = 1; i < n; ++i) still = Math.max(still, Math.abs(in[i] - in[i - 1]));
        System.out.printf("    the largest step over the tone's: %.3f%n", moving / still);
        assertTrue(moving / still < 1.5);
    }

    @Test
    @DisplayName("Every band follows the audible oversampling band: 32 samples less at 2x and 48 kHz")
    void everyBandFollowsTheAudibleBand()
    {
        final MultibandSaturation multi = new MultibandSaturation();
        multi.prepare(48000.0, 2, 512);
        multi.setOversampling(2);
        final int before = multi.getLatency();
        multi.setOversamplingBand(Oversampling.Band.AUDIBLE);
        assertEquals(before - 32, multi.getLatency());
        assertSame(Oversampling.Band.AUDIBLE, multi.getBandSaturation(0).getOversamplingBand());
        assertSame(Oversampling.Band.AUDIBLE, multi.getBandSaturation(2).getOversamplingBand());
    }

    // -- Documented behaviour and properties ------------------------------------------

    @Test
    @DisplayName("Defaults, the crossover settings it passes on, clamped indices and the latency it reports")
    void defaultsAndSettings()
    {
        final MultibandSaturation m = new MultibandSaturation();
        assertEquals(2, m.getNumBands());
        assertEquals(24, m.getOrder());
        assertEquals(1.0, m.getMix(), 0.0);
        assertFalse(m.getOutputClip());
        assertEquals(0.0, m.getOutputCeiling(), 0.0);
        m.setNumBands(9);
        assertEquals(6, m.getNumBands());
        m.setNumBandsKeepingSplits(1);
        assertEquals(2, m.getNumBands());
        m.setOrder(48);
        m.setCrossoverMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
        m.setCrossoverStructure(CrossoverFilter.Structure.LADDER);
        m.setCrossoverTopology(CrossoverFilter.Topology.BALANCED);
        m.setCrossoverFrequency(0, 400.0);
        assertEquals(48, m.getOrder());
        assertSame(CrossoverFilter.FilterMode.LINEAR_PHASE, m.getCrossoverMode());
        assertSame(CrossoverFilter.Structure.LADDER, m.getCrossoverStructure());
        assertSame(CrossoverFilter.Topology.BALANCED, m.getCrossoverTopology());
        assertEquals(400.0, m.getCrossoverFrequency(0), 0.0);
        assertSame(m.getBandSaturation(5), m.getBandSaturation(17));
        assertSame(m.getBandSaturation(0), m.getBandSaturation(-3));
        m.setBandAlgorithm(1, Saturation.Algorithm.TUBE);
        assertThrows(IllegalArgumentException.class, () -> new MultibandSaturation(1));
        assertDoesNotThrow(() -> new MultibandSaturation().reset());

        // Linear-phase crossover plus the slowest band, plus the clipper while it is on.
        m.prepare(48000.0, 2, 512);
        m.process(new double[2][512], 512);
        assertSame(Saturation.Algorithm.TUBE, m.getBandSaturation(1).getCurrentAlgorithm());
        final int bands = m.getBandSaturation(0).getLatency();
        assertEquals(3072 + bands, m.getLatency());
        m.setOutputClip(true);
        final OversampledClipper reference = new OversampledClipper();
        reference.setOversampling(4);
        reference.prepare(48000.0, 2, 512);
        assertEquals(3072 + bands + reference.getLatency(), m.getLatency());
        for (final double rate : new double[] { 44100.0, 48000.0, 96000.0 })
        {
            final MultibandSaturation d = new MultibandSaturation();
            d.prepare(rate, 2, 512);
            final MultibandSaturation auto = new MultibandSaturation();
            for (int b = 0; b < 6; ++b) auto.getBandSaturation(b).setAutomaticOversampling(true);
            auto.prepare(rate, 2, 512);
            System.out.printf("    %.1f kHz: latency %d (bands at 2x), %d (automatic oversampling)%n", rate / 1000.0,
                              d.getLatency(), auto.getLatency());
        }
    }

    @Test
    @DisplayName("The reported latency is the measured delay (linear-phase crossover, no drive)")
    void latencyIsTheMeasuredDelay()
    {
        for (final boolean uneven : new boolean[] { false, true })
        {
            final MultibandSaturation m = new MultibandSaturation();
            m.setNumBands(3);
            m.setCrossoverMode(CrossoverFilter.FilterMode.LINEAR_PHASE);
            if (uneven) m.getBandSaturation(2).setOversampling(4);
            m.prepare(48000.0, 2, 512);
            final double[][] x = new double[2][16384];
            x[0][1000] = 0.01;
            x[1][1000] = 0.01;
            m.process(x, x[0].length);
            int argmax = 0;
            for (int i = 0; i < x[0].length; ++i) if (Math.abs(x[0][i]) > Math.abs(x[0][argmax])) argmax = i;
            assertEquals(1000 + m.getLatency(), argmax, "uneven " + uneven);
        }
    }

    private static Consumer<MultibandSaturation> busy(CrossoverFilter.FilterMode mode)
    {
        return m ->
        {
            m.setNumBands(4);
            m.setCrossoverMode(mode);
            m.setCrossoverFrequency(0, 120.0);
            m.setCrossoverFrequency(1, 1000.0);
            m.setCrossoverFrequency(2, 6000.0);
            for (final int b : new int[] { 0, 1, 3 }) m.getBandSaturation(b).setAutomaticOversampling(true);
            m.getBandSaturation(2).setOversampling(4);
            m.setBandAlgorithm(1, Saturation.Algorithm.TUBE);
            m.setBandAlgorithm(2, Saturation.Algorithm.TAPE);
            m.setBandDrive(0, 6.0);
            m.setBandDrive(1, 12.0);
            m.setBandDrive(2, 3.0);
            m.setBandDrive(3, 9.0);
            m.setBandLevel(0, -2.0);
            m.setBandPan(3, 0.3);
            m.setMix(0.8);
            m.setOutputClip(true);
            m.setOutputCeiling(-1.0);
        };
    }

    private static double[][] programme(int n, int seed)
    {
        final double[] pinkL = SaturationSignals.pink(n, -16.0, seed), pinkR = SaturationSignals.pink(n, -16.0, seed + 1);
        for (int i = 0; i < n; ++i)
        {
            final double tone = 0.2 * Math.sin(2.0 * PI * 82.4 * i / 48000.0) + 0.1 * Math.sin(2.0 * PI * 1250.0 * i / 48000.0);
            pinkL[i] += tone;
            pinkR[i] += 0.8 * tone;
        }
        return new double[][] { pinkL, pinkR };
    }

    @Test
    @DisplayName("Stretch join: started from rest 1.5 s before sample N, it agrees with one pass after N within 1e-6 of the peak")
    void stretchesJoin()
    {
        final int total = 4 * 48000, n = 5 * 24000, warm = 72000, start = n - warm;
        final double[][] in = programme(total, 7);
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
        {
            final double[][] whole = run(in[0], in[1], busy(mode), 512, null);
            final MultibandSaturation late = new MultibandSaturation();
            busy(mode).accept(late);
            late.prepare(48000.0, 2, 512);
            final double[][] tail = { Arrays.copyOfRange(in[0], start, total), Arrays.copyOfRange(in[1], start, total) };
            for (int off = 0; off < tail[0].length; off += 512) late.process(tail, off, Math.min(512, tail[0].length - off));
            final double top = Math.max(peak(whole[0]), peak(whole[1]));
            double apart = 0.0;
            for (int ch = 0; ch < 2; ++ch)
                for (int i = n; i < total; ++i) apart = Math.max(apart, Math.abs(whole[ch][i] - tail[ch][i - start]));
            System.out.printf("    %s crossover: stretches %.2e apart after N (peak %.3f)%n", mode, apart / top, top);
            assertTrue(apart <= 1e-6 * top, mode + ": " + apart / top);
        }
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give the same output; reset() equals a fresh instance; same input, same bits")
    void blockSizesResetAndDeterminism()
    {
        final double[][] in = programme(12288, 21);
        for (final CrossoverFilter.FilterMode mode : CrossoverFilter.FilterMode.values())
        {
            double[][] ref = null;
            for (final int block : new int[] { 4096, 512, 37, 1 })
            {
                final MultibandSaturation m = new MultibandSaturation();
                busy(mode).accept(m);
                m.prepare(48000.0, 2, 4096);
                final double[][] x = { in[0].clone(), in[1].clone() };
                for (int off = 0; off < x[0].length; off += block) m.process(x, off, Math.min(block, x[0].length - off));
                assertTrue(CrossoverSignals.allFinite(x[0]) && CrossoverSignals.allFinite(x[1]));
                if (ref == null)
                {
                    ref = x;
                    continue;
                }
                double worst = 0.0;
                for (int ch = 0; ch < 2; ++ch)
                    for (int i = 0; i < x[0].length; ++i) worst = Math.max(worst, Math.abs(x[ch][i] - ref[ch][i]));
                System.out.printf("    %s, blocks of %d: %.2e from blocks of 4096%n", mode, block, worst);
                assertTrue(worst < 1e-9, mode + " block " + block + ": " + worst);
            }

            final MultibandSaturation used = new MultibandSaturation();
            busy(mode).accept(used);
            used.prepare(48000.0, 2, 512);
            final double[][] warmUp = programme(12288, 5);
            used.process(warmUp, warmUp[0].length);
            used.reset();
            final double[][] a = { in[0].clone(), in[1].clone() };
            used.process(a, a[0].length);
            final double[][] b = run(in[0], in[1], busy(mode), 512, null);
            final double[][] c = run(in[0], in[1], busy(mode), 512, null);
            for (int ch = 0; ch < 2; ++ch)
            {
                assertTrue(Arrays.equals(b[ch], c[ch]), "determinism " + mode);
                double worst = 0.0;
                for (int i = 0; i < a[ch].length; ++i) worst = Math.max(worst, Math.abs(a[ch][i] - b[ch][i]));
                assertTrue(worst < 1e-12, "reset " + mode + ": " + worst);
            }
        }
    }

    /** A render with level and pan moves after prepare(), in blocks of {@code block} (the prepared maximum). */
    private static double[][] glideRender(int block, boolean landing, double[][] in)
    {
        final MultibandSaturation m = new MultibandSaturation();
        m.setNumBands(3);
        m.setCrossoverFrequency(0, 200.0);
        m.setCrossoverFrequency(1, 2000.0);
        m.setBandDrive(1, 6.0);
        m.glideLanding = landing;
        m.prepare(48000.0, 2, block);
        final double[][] x = { in[0].clone(), in[1].clone() };
        final int n = x[0].length;
        for (int off = 0; off < n; off += block)
        {
            if (off >= 4800 && off - block < 4800)
            {
                m.setBandLevel(0, -6.0);
                m.setBandPan(1, 0.7);
            }
            if (off >= 12000 && off - block < 12000)
            {
                m.setBandLevel(1, 3.0);
                m.setBandPan(1, -0.4);
                m.setBandLevel(2, -60.0);
            }
            m.process(x, off, Math.min(block, n - off));
        }
        return x;
    }

    @Test
    @DisplayName("Level and pan glides in blocks of up to 20 ms are bit-identical to the unclamped C++ glide")
    void shortBlockGlidesAreUnchanged()
    {
        final double[][] in = programme(24000, 61);
        for (final int block : new int[] { 64, 512, 960 })
        {
            final double[][] clamped = glideRender(block, true, in);
            final double[][] cpp = glideRender(block, false, in);
            for (int ch = 0; ch < 2; ++ch) assertTrue(Arrays.equals(cpp[ch], clamped[ch]), "block " + block);
        }
    }

    @Test
    @DisplayName("A level and pan glide in blocks longer than 20 ms lands on its target without passing it")
    void longBlockGlideLandsOnTheTarget()
    {
        final int block = 4096, n = 8 * block;
        final double[] tone = sine(1000.0, 0.1, n);
        final double[][] in = { tone, tone };
        final java.util.function.BiFunction<Boolean, Boolean, double[][]> render = (afterPrepare, landing) ->
        {
            final MultibandSaturation m = new MultibandSaturation();
            m.setNumBands(2);
            m.setCrossoverFrequency(0, 200.0);
            if (!afterPrepare)
            {
                m.setBandLevel(1, -6.0);
                m.setBandPan(1, 0.5);
            }
            m.glideLanding = landing;
            m.prepare(48000.0, 2, block);
            if (afterPrepare)
            {
                m.setBandLevel(1, -6.0);
                m.setBandPan(1, 0.5);
            }
            final double[][] x = { in[0].clone(), in[1].clone() };
            for (int off = 0; off < n; off += block) m.process(x, off, block);
            return x;
        };
        final double[][] moved = render.apply(true, true);
        final double[][] set = render.apply(false, true);
        final double[][] unity = run(tone, tone, m -> { m.setNumBands(2); m.setCrossoverFrequency(0, 200.0); }, block, null);
        // The glide reaches the targets 20 ms into the first block and stays
        // on them: from there the output is that of the targets set before
        // prepare(), to the bit.
        for (int ch = 0; ch < 2; ++ch)
            assertTrue(Arrays.equals(Arrays.copyOfRange(set[ch], 1000, n), Arrays.copyOfRange(moved[ch], 1000, n)),
                       "channel " + ch);
        // On the way the gain goes from 1 down to the target and no further.
        final double before = peak(Arrays.copyOfRange(unity[0], 0, 1000));
        for (int ch = 0; ch < 2; ++ch)
            assertTrue(peak(Arrays.copyOfRange(moved[ch], 0, 1000)) <= before * (1.0 + 1e-9));
        final double[][] cpp = render.apply(true, false);
        System.out.printf("    blocks of 4096 after setBandLevel(1, -6): peak %.4f (target %.4f); the unclamped glide peaks at %.3g%n",
                          peak(moved[0]), peak(set[0]), Math.max(peak(cpp[0]), peak(cpp[1])));
        assertTrue(Math.max(peak(cpp[0]), peak(cpp[1])) > 10.0 * peak(set[0]));
    }

    @Test
    @DisplayName("Above 16 channels the extra channels pass through untouched, the output clip on")
    void channelsAboveSixteenPassThrough()
    {
        final int n = 4096;
        final Consumer<MultibandSaturation> setup = m ->
        {
            m.setNumBands(3);
            m.setBandDrive(1, 12.0);
            m.setMix(0.7);
            m.setOutputClip(true);
        };
        final double[][] in = new double[18][];
        for (int c = 0; c < 18; ++c) in[c] = sine(200.0 + 150.0 * c, 0.4, n);
        final MultibandSaturation wide = new MultibandSaturation();
        setup.accept(wide);
        wide.prepare(48000.0, 18, 512);
        final double[][] x = new double[18][];
        for (int c = 0; c < 18; ++c) x[c] = in[c].clone();
        wide.process(x, n);
        assertTrue(Arrays.equals(in[16], x[16]));
        assertTrue(Arrays.equals(in[17], x[17]));

        final MultibandSaturation sixteen = new MultibandSaturation();
        setup.accept(sixteen);
        sixteen.prepare(48000.0, 16, 512);
        final double[][] y = new double[16][];
        for (int c = 0; c < 16; ++c) y[c] = in[c].clone();
        sixteen.process(y, n);
        for (int c = 0; c < 16; ++c)
        {
            assertTrue(!Arrays.equals(in[c], x[c]), "channel " + c + " processed");
            assertTrue(Arrays.equals(y[c], x[c]), "channel " + c + " as with 16 prepared");
        }
    }

    @Test
    @DisplayName("The span is clamped to the shortest channel array; a negative offset does nothing")
    void spanClampsToTheShortestChannel()
    {
        final double[] left = sine(300.0, 0.3, 1000), right = sine(500.0, 0.3, 1300);
        final double[] rightTail = Arrays.copyOfRange(right, 1000, 1300);
        final Consumer<MultibandSaturation> setup = m -> { m.setNumBands(3); m.setBandDrive(0, 9.0); };
        final MultibandSaturation m = new MultibandSaturation();
        setup.accept(m);
        m.prepare(48000.0, 2, 512);
        final double[][] x = { left.clone(), right.clone() };
        assertDoesNotThrow(() -> m.process(x, 0, 1300));
        assertTrue(Arrays.equals(rightTail, Arrays.copyOfRange(x[1], 1000, 1300)));
        final double[][] ref = run(left, Arrays.copyOf(right, 1000), setup, 512, null);
        assertTrue(Arrays.equals(ref[0], x[0]));
        assertTrue(Arrays.equals(ref[1], Arrays.copyOf(x[1], 1000)));

        final double[][] z = { left.clone(), left.clone() };
        assertDoesNotThrow(() -> m.process(z, -5, 100));
        assertDoesNotThrow(() -> m.process(z, 990, 64));
        assertTrue(Arrays.equals(Arrays.copyOf(left, 990), Arrays.copyOf(z[0], 990)));
    }

    @Test
    @DisplayName("Channels beyond the prepared count pass through; non-finite input does not latch")
    void extraChannelsAndNonFiniteInput()
    {
        final MultibandSaturation m = new MultibandSaturation();
        m.setNumBands(3);
        m.prepare(48000.0, 2, 512);
        final double[][] x = { sine(440.0, 0.3, 4096), sine(440.0, 0.3, 4096), sine(660.0, 0.3, 4096) };
        final double[] third = x[2].clone();
        x[0][100] = Double.NaN;
        x[1][200] = Double.POSITIVE_INFINITY;
        m.process(x, 4096);
        assertTrue(Arrays.equals(third, x[2]));
        for (int i = 1024; i < 4096; ++i)
        {
            assertTrue(Double.isFinite(x[0][i]));
            assertTrue(Double.isFinite(x[1][i]));
        }
    }

    @Test
    @DisplayName("Output level against drive, and the cost in ns per stereo frame at 48 kHz (printed)")
    void levelAgainstDriveAndCost()
    {
        final double[] tone = sine(1000.0, Math.pow(10.0, -12.0 / 20.0), 48000);
        final double[] pinkNoise = SaturationSignals.pink(48000, -18.0, 3);
        for (final double drive : new double[] { -12.0, 0.0, 12.0, 24.0 })
        {
            final Consumer<MultibandSaturation> setup = m -> { for (int b = 0; b < 6; ++b) m.setBandDrive(b, drive); };
            final double[] t = run(tone, tone, setup)[0];
            final double[] p = run(pinkNoise, pinkNoise, setup)[0];
            System.out.printf("    drive %+5.1f dB: 1 kHz at -12 dBFS leaves at %+.2f dBFS, pink at -18 dBFS RMS at %+.2f dBFS RMS%n",
                              drive, line(t, 1000.0), 20.0 * Math.log10(CrossoverSignals.rms(p, 24000, 24000)));
            assertTrue(CrossoverSignals.allFinite(t) && CrossoverSignals.allFinite(p));
        }

        final int n = 48000;
        final double[][] in = programme(n, 31);
        final String[] names = { "default (2 bands)", "4 bands", "busy, 4 bands" };
        final MultibandSaturation[] ms = new MultibandSaturation[3];
        ms[0] = new MultibandSaturation();
        ms[1] = new MultibandSaturation();
        ms[1].setNumBands(4);
        ms[2] = new MultibandSaturation();
        busy(CrossoverFilter.FilterMode.MINIMUM_PHASE).accept(ms[2]);
        for (final MultibandSaturation m : ms) m.prepare(48000.0, 2, 512);
        final double[][] work = new double[2][n];
        for (int warm = 0; warm < 4; ++warm)
            for (final MultibandSaturation m : ms)
            {
                for (int ch = 0; ch < 2; ++ch) System.arraycopy(in[ch], 0, work[ch], 0, n);
                m.process(work, n);
            }
        for (int k = 0; k < ms.length; ++k)
        {
            double best = Double.MAX_VALUE;
            for (int rep = 0; rep < 3; ++rep)
            {
                for (int ch = 0; ch < 2; ++ch) System.arraycopy(in[ch], 0, work[ch], 0, n);
                final long t0 = System.nanoTime();
                for (int off = 0; off < n; off += 512) ms[k].process(work, off, Math.min(512, n - off));
                best = Math.min(best, (System.nanoTime() - t0) / (double) n);
            }
            System.out.printf("    %s: %.0f ns per stereo frame%n", names[k], best);
        }
    }
}
