package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static com.dspark.effects.TapeSignals.l10Band;
import static com.dspark.effects.TapeSignals.l10Line;
import static com.dspark.effects.TapeSignals.l10Render;
import static com.dspark.effects.TapeSignals.m7Gain;
import static com.dspark.effects.TapeSignals.m7Tape;
import static com.dspark.effects.TapeSignals.m7Tones;
import static com.dspark.effects.TapeSignals.monoRender;
import static com.dspark.effects.TapeSignals.l10RunLeft;
import static com.dspark.effects.TapeSignals.sat8Line;
import static com.dspark.effects.TapeSignals.sat8Pink;
import static com.dspark.effects.TapeSignals.sat8Sine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TapeMachine}: the input, the bias against the top end, the record
 * emphasis trim, the iron's low end, the head by speed and track, rest and
 * block size, the mix and the bias shape: the cases of the C++
 * TestEffectsTone.cpp with their thresholds.
 */
class TapeMachineToneTest
{
    private static double[] tone(double hz, double amp, int n)
    {
        return m7Tones(new double[][] { { hz, amp } }, n, 0.0);
    }

    @Test
    @DisplayName("TapeMachine_input_is_ac_coupled")
    void inputIsAcCoupled()
    {
        final int n = 96000;
        final double[] cleanOut = m7Tape(t -> { }, tone(1000.0, 0.25, n));
        final double[] offsetOut = m7Tape(t -> { }, m7Tones(new double[][] { { 1000.0, 0.25 } }, n, 0.1));
        final double clean = l10Line(cleanOut, 2000.0) - l10Line(cleanOut, 1000.0);
        final double offset = l10Line(offsetOut, 2000.0) - l10Line(offsetOut, 1000.0);
        System.out.printf("  [tape] second harmonic of 1 kHz: %.1f dB clean, %.1f dB with DC 0.1 at the input%n", clean, offset);
        assertTrue(offset < Math.max(clean + 3.0, -75.0));
        assertEquals(m7Gain(t -> { }, 1000.0, 0.02), m7Gain(t -> { }, 40.0, 0.02), 1.0);
    }

    @Test
    @DisplayName("TapeMachine_bias_moves_the_top_end_both_ways")
    void biasMovesTheTopEndBothWays()
    {
        final double[] in = m7Tones(new double[][] { { 1000.0, 0.02 }, { 10000.0, 0.02 } }, 96000, 0.0);
        double last = 1e9, atOptimum = 0.0, low = 0.0, high = 0.0;
        for (final float bias : new float[] { 0.2f, 0.35f, 0.5f, 0.54f, 0.65f, 0.8f })
        {
            final double[] out = m7Tape(t -> t.setBias(bias), in);
            final double top = l10Line(out, 10000.0) - l10Line(out, 1000.0);
            System.out.printf("  [tape] bias %.2f: 10 kHz against 1 kHz %+.2f dB%n", bias, top);
            assertTrue(top < last - 0.05, "bias " + bias);
            last = top;
            if (bias == 0.5f) atOptimum = top;
            if (bias == 0.2f) low = top;
            if (bias == 0.8f) high = top;
        }
        assertTrue(low > atOptimum + 1.0);
        assertTrue(high < atOptimum - 1.0);
    }

    private static double thirdOf5k(double db)
    {
        final double[] out = m7Tape(t -> t.setRecordEmphasis(db), tone(5000.0, 0.5, 96000));
        return l10Line(out, 15000.0) - l10Line(out, 5000.0);
    }

    private static double hissTop(double db)
    {
        return l10Band(m7Tape(t -> { t.setRecordEmphasis(db); t.setNoise(-50.0); }, new double[96000]), 8000.0, 16000.0);
    }

    @Test
    @DisplayName("TapeMachine_record_emphasis_is_flat_at_low_level_and_loads_the_top")
    void recordEmphasisIsFlatAtLowLevelAndLoadsTheTop()
    {
        for (final double hz : new double[] { 100.0, 1000.0, 10000.0 })
            assertEquals(m7Gain(t -> t.setRecordEmphasis(0.0), hz, 0.01), m7Gain(t -> t.setRecordEmphasis(6.0), hz, 0.01), 0.3);
        final double plain = thirdOf5k(0.0), loaded = thirdOf5k(6.0), spared = thirdOf5k(-6.0);
        System.out.printf("  [tape] third harmonic of 5 kHz at -6 dBFS: %.1f dB with the trim at 0, %.1f at +6, %.1f at -6%n",
                          plain, loaded, spared);
        assertTrue(loaded > plain + 0.5);
        assertTrue(spared < plain - 1.0);
        final double hissFall = hissTop(0.0) - hissTop(6.0);
        System.out.printf("  [tape] hiss between 8 and 16 kHz falls %.2f dB with the trim at +6%n", hissFall);
        assertTrue(hissFall > 3.0);

        final TapeMachine a = new TapeMachine();
        a.setRecordEmphasis(4.0);
        a.setRecordEmphasis(Double.NaN);
        assertEquals(4.0, a.getRecordEmphasis(), 1e-6);
        a.setRecordEmphasis(40.0);
        assertEquals(6.0, a.getRecordEmphasis(), 1e-6);
    }

    private static double lift(TapeMachine.Speed speed, TapeMachine.TrackFormat format, double hz)
    {
        return m7Gain(t -> { t.setSpeed(speed); t.setTrackFormat(format); t.setHeadBump(1.0); }, hz, 0.008)
             - m7Gain(t -> { t.setSpeed(speed); t.setTrackFormat(format); t.setHeadBump(0.0); }, hz, 0.008);
    }

    @Test
    @DisplayName("TapeMachine_head_bump_follows_the_speed_and_the_track")
    void headBumpFollowsTheSpeedAndTheTrack()
    {
        final TapeMachine.Speed s15 = TapeMachine.Speed.IPS_15, s75 = TapeMachine.Speed.IPS_7_5, s375 = TapeMachine.Speed.IPS_3_75;
        final TapeMachine.TrackFormat half = TapeMachine.TrackFormat.HALF, full = TapeMachine.TrackFormat.FULL,
            quarter = TapeMachine.TrackFormat.QUARTER;
        final double at90 = lift(s15, half, 90.0);
        assertTrue(at90 > lift(s15, half, 45.0) + 0.2);
        assertTrue(at90 > lift(s15, half, 180.0) + 0.2);
        assertTrue(lift(s15, full, 72.0) > lift(s15, full, 112.0) + 0.1);
        assertTrue(lift(s15, quarter, 112.0) > lift(s15, quarter, 72.0) + 0.1);
        assertTrue(lift(s75, half, 45.0) > lift(s75, half, 22.5) + 0.2);
        assertTrue(lift(s375, half, 22.5) > lift(s375, half, 45.0) + 0.2);
    }

    @Test
    @DisplayName("TapeMachine_three_and_three_quarter_ips")
    void threeAndThreeQuarterIps()
    {
        double last = -1e9;
        for (final TapeMachine.Speed speed : new TapeMachine.Speed[] {
            TapeMachine.Speed.IPS_3_75, TapeMachine.Speed.IPS_7_5, TapeMachine.Speed.IPS_15, TapeMachine.Speed.IPS_30 })
        {
            final double top = m7Gain(t -> { t.setSpeed(speed); t.setLossEffects(1.0); }, 10000.0, 0.02);
            System.out.printf("  [tape] speed %s: 10 kHz at full losses %.2f dB%n", speed, top);
            assertTrue(top > last + 0.5);
            last = top;
        }
        for (final TapeMachine.Standard standard : TapeMachine.Standard.values())
            assertEquals(0.0, m7Gain(t -> { t.setSpeed(TapeMachine.Speed.IPS_3_75); t.setStandard(standard); }, 1000.0, 0.25), 0.5);
        assertEquals(0, TapeMachine.Speed.IPS_7_5.ordinal());
        assertEquals(2, TapeMachine.Speed.IPS_30.ordinal());
        final TapeMachine a = new TapeMachine();
        a.setSpeed(TapeMachine.Speed.IPS_3_75);
        assertSame(TapeMachine.Speed.IPS_3_75, a.getSpeed());
    }

    private static double ironDrop(double hz)
    {
        return m7Gain(t -> t.setOutputIron(1.0), hz, 0.005, 192000) - m7Gain(t -> { }, hz, 0.005, 192000);
    }

    @Test
    @DisplayName("TapeMachine_output_iron_loses_its_low_end")
    void outputIronLosesItsLowEnd()
    {
        final double at20 = ironDrop(20.0), at100 = ironDrop(100.0), at1k = ironDrop(1000.0);
        System.out.printf("  [tape] the iron at 1 against 0: %.2f dB at 20 Hz, %.2f at 100 Hz, %.2f at 1 kHz%n", at20, at100, at1k);
        assertEquals(-3.0, at20, 0.5);
        assertTrue(at100 > -0.3);
        assertEquals(0.0, at1k, 0.05);
    }

    @Test
    @DisplayName("TapeMachine_rests_when_it_is_not_heard_and_wakes_without_a_step")
    void restsWhenItIsNotHeardAndWakesWithoutAStep()
    {
        final double[] in = tone(1000.0, 0.25, 96000);
        final double[] never = m7Tape(t -> { }, in);
        final int at = 512 * 60;
        for (int way = 0; way < 2; ++way)
        {
            final TapeMachine tape = new TapeMachine();
            tape.setWowFlutter(0.0);
            if (way == 0) tape.setMix(0.0);
            else tape.setMonitor(TapeMachine.Monitor.SOURCE);
            tape.prepare(48000.0, 1, 512);
            final double[] l = in.clone();
            final double[][] block = new double[1][512];
            for (int off = 0; off < l.length; off += 512)
            {
                if (off == at)
                {
                    if (way == 0) tape.setMix(1.0);
                    else tape.setMonitor(TapeMachine.Monitor.PLAY_HEAD);
                }
                final int n = Math.min(512, l.length - off);
                System.arraycopy(l, off, block[0], 0, n);
                tape.process(block, n);
                System.arraycopy(block[0], 0, l, off, n);
            }
            final int late = tape.getLatency();
            double apart = 0.0;
            for (int i = late; i < at; ++i) apart = Math.max(apart, Math.abs(l[i] - in[i - late]));
            assertEquals(0.0, apart);
            double resting = 0.0, waking = 0.0, settled = 0.0;
            for (int i = at - 4800; i < at; ++i) resting = Math.max(resting, Math.abs(l[i] - l[i - 1]));
            for (int i = at; i < at + 4800; ++i) waking = Math.max(waking, Math.abs(l[i] - l[i - 1]));
            for (int i = l.length - 4800; i < l.length; ++i) settled = Math.max(settled, Math.abs(l[i] - l[i - 1]));
            System.out.printf("  [tape] largest step: %.5f at rest, %.5f waking, %.5f settled%n", resting, waking, settled);
            assertTrue(waking < 1.2 * Math.max(resting, settled));
            assertEquals(l10Line(never, 1000.0), l10Line(l, 1000.0), 0.05);
        }
    }

    private static double[] blockSizeRender(double[] in, int asked)
    {
        final TapeMachine tape = new TapeMachine();
        tape.prepare(48000.0, 1, 512);
        final double[] l = in.clone();
        final double[][] block = new double[1][256];
        for (int off = 0; off < l.length; off += 256)
        {
            if (off == 24064 && asked == 1) tape.prepare(48000.0, 1, 256);
            if (off == 24064 && asked == 2) tape.prepare(48000.0, 1, 512);
            final int n = Math.min(256, l.length - off);
            System.arraycopy(l, off, block[0], 0, n);
            tape.process(block, n);
            System.arraycopy(block[0], 0, l, off, n);
        }
        return l;
    }

    @Test
    @DisplayName("TapeMachine_keeps_its_memory_when_only_the_block_size_changes")
    void keepsItsMemoryWhenOnlyTheBlockSizeChanges()
    {
        final double[] in = tone(440.0, 0.25, 48000);
        final double[] steady = blockSizeRender(in, 0), resized = blockSizeRender(in, 1), again = blockSizeRender(in, 2);
        double apart = 0.0, restart = 0.0;
        for (int i = 0; i < steady.length; ++i)
        {
            apart = Math.max(apart, Math.abs(steady[i] - resized[i]));
            restart = Math.max(restart, Math.abs(steady[i] - again[i]));
        }
        assertEquals(0.0, apart);
        assertTrue(restart > 0.05);
    }

    /** How far a mix of one half falls short of the mean of the two levels, in dB. */
    private static double mixShortDb(java.util.function.BiFunction<Float, Double, double[]> render, double hz)
    {
        final double dry = Math.pow(10.0, sat8Line(render.apply(0.0f, hz), hz) / 20.0);
        final double wet = Math.pow(10.0, sat8Line(render.apply(1.0f, hz), hz) / 20.0);
        final double half = Math.pow(10.0, sat8Line(render.apply(0.5f, hz), hz) / 20.0);
        return 20.0 * Math.log10(half / (0.5 * (dry + wet)));
    }

    @Test
    @DisplayName("TapeMachine_half_mix_keeps_the_bottom")
    void halfMixKeepsTheBottom()
    {
        for (int way = 0; way < 3; ++way)
        {
            final int w = way;
            final java.util.function.BiFunction<Float, Double, double[]> render = (mix, hz) -> {
                final double[] in = sat8Sine(hz, 0.03, 72000);
                return l10RunLeft(t -> {
                    t.setWowFlutter(0.0);
                    if (w == 1) t.setOversamplingMode(TapeMachine.OversamplingMode.MINIMUM_PHASE);
                    if (w == 2) t.setOutputIron(1.0);
                    t.setMix(mix);
                }, in);
            };
            double worst = 0.0;
            for (final double hz : new double[] { 20.0, 30.0, 50.0, 100.0, 1000.0 }) worst = Math.min(worst, mixShortDb(render, hz));
            System.out.printf("  [mix] tape, setting %d: a half mix is short of the mean by %.3f dB at most%n", way, -worst);
            assertTrue(worst > (way == 2 ? -0.35 : -0.25));
        }
        final double[] in = sat8Pink(24000, -18.0, 13);
        final TapeMachine tape = new TapeMachine();
        tape.setMix(0.5);
        tape.setMonitor(TapeMachine.Monitor.SOURCE);
        tape.prepare(48000.0, 2, 512);
        final double[] out = l10Render(tape, in, in)[0];
        final int late = tape.getLatency();
        double apart = 0.0;
        for (int i = late; i < out.length; ++i) apart = Math.max(apart, Math.abs(out[i] - in[i - late]));
        assertEquals(0.0, apart);
    }

    @Test
    @DisplayName("Mix_phase_of_the_dry_side_turns_without_a_step (tape)")
    void mixPhaseOfTheDrySideTurnsWithoutAStep()
    {
        final int n = 512 * 190;
        final TapeMachine p = new TapeMachine();
        p.setWowFlutter(0.0);
        p.setMix(0.0);
        p.prepare(48000.0, 1, 512);
        final double[] in = sat8Sine(50.0, 0.1, n);
        final double[] l = in.clone();
        final double[][] block = new double[1][512];
        for (int off = 0; off < n; off += 512)
        {
            if (off == 512 * 40) p.setMix(0.5);
            if (off == 512 * 80) p.setMix(1.0);
            if (off == 512 * 110) p.setMix(0.5);
            if (off == 512 * 140) p.setMix(0.0);
            System.arraycopy(l, off, block[0], 0, 512);
            p.process(block, 512);
            System.arraycopy(block[0], 0, l, off, 512);
        }
        double moving = 0.0, still = 0.0;
        for (int i = 512 * 30; i < n; ++i) moving = Math.max(moving, Math.abs(l[i] - l[i - 1]));
        for (int i = 1; i < n; ++i) still = Math.max(still, Math.abs(in[i] - in[i - 1]));
        System.out.printf("  [mix] the largest step over the tone's: %.3f%n", moving / still);
        assertTrue(moving / still < 1.8);
    }

    private static double gainOf(double hz, double db, Consumer<TapeMachine> setup)
    {
        final double[] in = sat8Sine(hz, Math.pow(10.0, db / 20.0), 24000);
        return sat8Line(l10RunLeft(t -> { t.setWowFlutter(0.0); setup.accept(t); }, in), hz) - db;
    }

    @Test
    @DisplayName("TapeMachine_never_lifts_the_loud_parts")
    void neverLiftsTheLoudParts()
    {
        final double[][] cases = { { 0.0, 0 }, { 0.25, 0 }, { 0.5, 0 }, { 0.75, 0 }, { 0.9, 0 }, { 1.0, 0 }, { 0.5, 1 }, { 0.5, 2 } };
        final double[] levels = { -40.0, -18.0, -12.0, -6.0, 0.0, 6.0 };
        double quietest = 0.0, at0 = 0.0, classic0 = 0.0;
        for (final double[] c : cases)
        {
            final double bias = c[0];
            final TapeMachine.Formulation form = TapeMachine.Formulation.values()[(int) c[1]];
            double rise = 0.0;
            for (final double hz : new double[] { 1000.0, 10000.0 })
            {
                double last = 0.0;
                for (final double db : levels)
                {
                    final double gain = gainOf(hz, db, t -> { t.setBias(bias); t.setFormulation(form); });
                    if (db > -40.0) rise = Math.max(rise, gain - last);
                    last = gain;
                    if (hz == 1000.0 && bias == 0.5 && form == TapeMachine.Formulation.CLASSIC)
                    {
                        if (db == -40.0) quietest = gain;
                        if (db == 0.0)
                        {
                            at0 = gain;
                            classic0 = gainOf(hz, db, t -> t.setBiasShape(TapeMachine.BiasShape.TWO_LEVEL));
                        }
                    }
                }
            }
            System.out.printf("  [bias shape] bias %.2f, formulation %s: the gain rises by %.3f dB at most from a level to the next%n",
                              bias, form, rise);
            assertTrue(rise < 0.05);
        }
        System.out.printf("  [bias shape] 1 kHz: %.2f dB quiet, %.2f dB at full scale (two levels: %.2f)%n", quietest, at0, classic0);
        assertEquals(0.0, quietest, 0.5);
        assertTrue(at0 < quietest - 0.3);
    }

    private static double worstInharmonic(double hz, TapeMachine.BiasShape shape)
    {
        final double[] out = l10RunLeft(t -> { t.setWowFlutter(0.0); t.setBiasShape(shape); }, sat8Sine(hz, 0.5, 24000));
        double most = -300.0;
        for (double f = 50.0; f < 19000.0; f += 50.0)
        {
            final double order = Math.floor(f / hz + 0.5);
            if (Math.abs(f - order * hz) < 1.0) continue;
            most = Math.max(most, sat8Line(out, f));
        }
        return most - sat8Line(out, hz);
    }

    @Test
    @DisplayName("TapeMachine_four_level_bias_keeps_the_top_as_clean")
    void fourLevelBiasKeepsTheTopAsClean()
    {
        for (final double hz : new double[] { 10100.0, 13150.0, 15050.0, 19250.0 })
        {
            final double two = worstInharmonic(hz, TapeMachine.BiasShape.TWO_LEVEL);
            final double four = worstInharmonic(hz, TapeMachine.BiasShape.FOUR_LEVEL);
            System.out.printf("  [bias shape] inharmonic of %.0f Hz at -6 dBFS: %.1f dBc with two levels, %.1f with four%n", hz, two, four);
            assertTrue(four < two + 1.0);
        }
    }

    @Test
    @DisplayName("TapeMachine_four_levels_at_1x_take_two_steps")
    void fourLevelsAt1xTakeTwoSteps()
    {
        for (final double bias : new double[] { 0.0, 0.25, 0.5, 1.0 })
        {
            double rise = 0.0;
            for (final double hz : new double[] { 1000.0, 5000.0, 10000.0 })
            {
                double last = 0.0;
                for (final double db : new double[] { -40.0, -18.0, -12.0, -6.0, 0.0, 6.0 })
                {
                    final double gain = gainOf(hz, db, t -> { t.setBias(bias); t.setOversampling(1); });
                    if (db > -40.0) rise = Math.max(rise, gain - last);
                    last = gain;
                }
            }
            System.out.printf("  [bias shape] at 1x, bias %.2f: the gain rises by %.3f dB at most from a level to the next%n", bias, rise);
            assertTrue(rise < (bias > 0.0 ? 0.05 : 0.8));
        }
    }

    @Test
    @DisplayName("TapeMachine_bias_shape_keeps_the_factor_and_is_saved (factor and latency)")
    void biasShapeKeepsTheFactor()
    {
        assertSame(TapeMachine.BiasShape.FOUR_LEVEL, new TapeMachine().getBiasShape());
        final double[][] cases = { { 44100.0, 4 }, { 48000.0, 4 }, { 96000.0, 2 }, { 48000.0, 1 }, { 48000.0, 2 }, { 48000.0, 8 }, { 192000.0, 4 } };
        for (final double[] c : cases)
        {
            final double rate = c[0];
            final int factor = (int) c[1];
            final TapeMachine tape = new TapeMachine();
            tape.setWowFlutter(0.0);
            tape.setOversampling(factor);
            tape.prepare(rate, 1, 512);
            for (final TapeMachine.BiasShape shape : new TapeMachine.BiasShape[] { TapeMachine.BiasShape.FOUR_LEVEL, TapeMachine.BiasShape.TWO_LEVEL })
            {
                tape.setBiasShape(shape);
                assertEquals(factor, tape.getOversamplingFactor());
                final double[] in = new double[8192];
                in[512] = 0.05f;
                final double[] out = monoRender(tape, in);
                int peak = 0;
                for (int i = 0; i < out.length; ++i)
                    if (Math.abs(out[i]) > Math.abs(out[peak])) peak = i;
                assertEquals(512.0 + tape.getLatency(), peak, 1.0, rate + " Hz, " + factor + "x, " + shape);

                final TapeMachine other = new TapeMachine();
                other.setOversampling(factor);
                other.setBiasShape(shape);
                assertSame(shape, other.getBiasShape());
                other.prepare(rate, 1, 512);
                assertEquals(tape.getLatency(), other.getLatency());
                assertEquals(factor, other.getOversamplingFactor());
            }
        }
    }

    private static double recordPeakAt(double phase, double driveDb)
    {
        final TapeMachine tape = new TapeMachine();
        tape.setDrive(driveDb);
        TapeSignals.neutralTape(tape, 1);
        final double[][] buf = new double[1][512];
        int n = 0;
        for (int b = 0; b < 40; ++b)
        {
            for (int i = 0; i < 512; ++i, ++n)
                buf[0][i] = 0.25f * (float) Math.sin(2.0 * 3.14159265358979 * 12000.0 * n / 48000.0 + phase);
            tape.process(buf, 512);
        }
        return tape.getRecordPeakDb(0);
    }

    @Test
    @DisplayName("TapeMachine_record_peak_sees_between_samples")
    void recordPeakSeesBetweenSamples()
    {
        double lo = 1e9, hi = -1e9;
        for (int k = 0; k < 8; ++k)
        {
            final double v = recordPeakAt(0.25 * 3.14159265358979 * k / 4.0, 0.0);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        assertTrue(hi - lo < 0.8, "spread " + (hi - lo));
        assertEquals(6.0, recordPeakAt(0.0, 6.0) - recordPeakAt(0.0, 0.0), 0.15);
        assertEquals(0.0, new TapeMachine().getRecordPeak(-1));
        assertEquals(0.0, new TapeMachine().getRecordPeak(16));
        assertEquals(-100.0, new TapeMachine().getRecordPeakDb(3));
    }

    @Test
    @DisplayName("TapeMachine_alignment_tolerance_strays_by_channel")
    void alignmentToleranceStraysByChannel()
    {
        final double[][] same = runBoth(0.0, 1000.0);
        assertTrue(java.util.Arrays.equals(same[0], same[1]));
        for (final double f : new double[] { 1000.0, 10000.0 })
        {
            final double[][] lr = runBoth(1.0, f);
            assertTrue(!java.util.Arrays.equals(lr[0], lr[1]));
            final double ml = TapeSignals.tapeToneMag(lr[0], f), mr = TapeSignals.tapeToneMag(lr[1], f);
            final double[] mono = new double[lr[0].length];
            for (int i = 0; i < mono.length; ++i) mono[i] = 0.5 * (lr[0][i] + lr[1][i]);
            final double mm = TapeSignals.tapeToneMag(mono, f);
            assertTrue(Math.abs(20.0 * Math.log10(ml / mr)) < (f > 5000.0 ? 2.5 : 1.0));
            assertTrue(20.0 * Math.log10(mm / (0.5 * (ml + mr))) > -0.5);
        }
    }

    /** 94 stereo blocks of 512 of a tone at 0.25 through a neutral machine with a tolerance: { left, right }. */
    private static double[][] runBoth(double tolerance, double freq)
    {
        final TapeMachine tape = new TapeMachine();
        tape.setAlignmentTolerance(tolerance);
        TapeSignals.neutralTape(tape);
        final double[][] buf = new double[2][512];
        final double[] l = new double[94 * 512], r = new double[94 * 512];
        int n = 0;
        for (int b = 0; b < 94; ++b)
        {
            for (int i = 0; i < 512; ++i, ++n)
            {
                final float v = 0.25f * (float) Math.sin(2.0 * 3.14159265358979 * freq * n / 48000.0);
                buf[0][i] = v;
                buf[1][i] = v;
            }
            tape.process(buf, 512);
            System.arraycopy(buf[0], 0, l, b * 512, 512);
            System.arraycopy(buf[1], 0, r, b * 512, 512);
        }
        return new double[][] { l, r };
    }

    @Test
    @DisplayName("TapeMachine_treble_compresses_before_the_middle")
    void trebleCompressesBeforeTheMiddle()
    {
        final Consumer<TapeMachine> none = t -> { };
        final double quiet1 = gainOf(1000.0, -40.0, none), quiet10 = gainOf(10000.0, -40.0, none);
        for (final double db : new double[] { -12.0, -6.0, 0.0 })
        {
            final double c1 = quiet1 - gainOf(1000.0, db, none), c10 = quiet10 - gainOf(10000.0, db, none);
            System.out.printf("  [tape] %+.0f dBFS: compression 1 kHz %.2f dB, 10 kHz %.2f dB%n", db, c1, c10);
            assertTrue(c1 > -0.05);
            assertTrue(c10 > c1 + 0.15);
            if (db >= -6.0) assertTrue(c10 > c1 + 1.0);
        }
    }
}
