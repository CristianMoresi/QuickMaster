package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static com.dspark.effects.TapeSignals.l10Band;
import static com.dspark.effects.TapeSignals.l10Line;
import static com.dspark.effects.TapeSignals.l10PitchSpread;
import static com.dspark.effects.TapeSignals.l10Render;
import static com.dspark.effects.TapeSignals.l10RmsDb;
import static com.dspark.effects.TapeSignals.l10Run;
import static com.dspark.effects.TapeSignals.l10RunLeft;
import static com.dspark.effects.TapeSignals.l10Thd;
import static com.dspark.effects.TapeSignals.l10Tone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TapeMachine}: the machine around the tape, the cases of the C++
 * TestEffectsTone.cpp section "TapeMachine: the machine around the tape" with
 * their thresholds.
 */
class TapeMachineAroundTest
{
    private static double spread(TapeMachine.Speed sp, double depth, double[] in)
    {
        return l10PitchSpread(l10RunLeft(t -> {
            t.setTransport(TapeMachine.Transport.SPEED);
            t.setSpeed(sp);
            t.setWowFlutter(depth);
            t.setLossEffects(0.0);
        }, in), 3000.0);
    }

    @Test
    @DisplayName("TapeMachine_speed_transport_follows_the_speed")
    void speedTransportFollowsTheSpeed()
    {
        final double[] in = l10Tone(3000.0, 0.1, 48000 * 6);
        assertTrue(spread(TapeMachine.Speed.IPS_15, 0.0, in) < 1e-4);
        final double at15 = spread(TapeMachine.Speed.IPS_15, 1.0, in);
        final double at75 = spread(TapeMachine.Speed.IPS_7_5, 1.0, in);
        System.out.printf("  [tape] speed transport pitch spread: %.5f at 15 ips, %.5f at 7.5 ips%n", at15, at75);
        assertTrue(at15 > 0.0015);
        assertTrue(at15 < 0.015);
        assertTrue(at75 > 1.2 * at15);

        final TapeMachine tape = new TapeMachine();
        tape.setTransport(TapeMachine.Transport.SPEED);
        tape.setWowFlutter(0.0);
        tape.prepare(48000.0, 1, 512);
        assertSame(TapeMachine.Transport.SPEED, tape.getTransport());
        final double[] l = l10Tone(3000.0, 0.1, 48000 * 4);
        final double[][] block = new double[1][512];
        for (int off = 0; off < l.length; off += 512)
        {
            if (off == 512 * 94) tape.setSpeed(TapeMachine.Speed.IPS_7_5);
            final int n = Math.min(512, l.length - off);
            System.arraycopy(l, off, block[0], 0, n);
            tape.process(block, n);
            System.arraycopy(block[0], 0, l, off, n);
        }
        for (final double x : l) assertTrue(Double.isFinite(x));
        assertTrue(l10PitchSpread(l, 3000.0) < 0.01);
    }

    @Test
    @DisplayName("TapeMachine_hiss_is_read_off_the_tape_and_hum_comes_after")
    void hissIsReadOffTheTapeAndHumComesAfter()
    {
        final double[] silence = new double[96000];
        final java.util.function.Function<Consumer<TapeMachine>, double[][]> hiss =
            setup -> l10Run(t -> { t.setNoise(-50.0); setup.accept(t); }, silence, silence);
        final double[][] plain = hiss.apply(t -> { });
        assertEquals(-50.0, l10RmsDb(plain[0]), 1.0);
        assertTrue(l10Band(plain[0], 200.0, 1000.0) - l10Band(plain[0], 6000.0, 14000.0) > 4.0);
        double ab = 0.0, aa = 0.0, bb = 0.0;
        for (int i = 48000; i < 96000; ++i)
        {
            ab += plain[0][i] * plain[1][i];
            aa += plain[0][i] * plain[0][i];
            bb += plain[1][i] * plain[1][i];
        }
        assertTrue(Math.abs(ab / Math.sqrt(aa * bb)) < 0.1);
        final double base = l10RmsDb(plain[0]);
        assertEquals(-5.0, l10RmsDb(hiss.apply(t -> t.setTrackFormat(TapeMachine.TrackFormat.FULL))[0]) - base, 0.7);
        assertEquals(3.0, l10RmsDb(hiss.apply(t -> t.setTrackFormat(TapeMachine.TrackFormat.QUARTER))[0]) - base, 0.7);
        assertEquals(-6.0, l10RmsDb(hiss.apply(t -> t.setCalibrationLevel(6.0))[0]) - base, 0.7);

        for (int m = 0; m < 2; ++m)
        {
            final TapeMachine.Mains mains = TapeMachine.Mains.values()[m];
            final double[][] humOut = l10Run(t -> t.setHum(-60.0, mains), silence, silence);
            final double f = m == 1 ? 60.0 : 50.0;
            assertEquals(-60.0, l10RmsDb(humOut[0]), 0.3);
            assertEquals(6.02, l10Line(humOut[0], f) - l10Line(humOut[0], 2.0 * f), 0.3);
            double worst = 0.0;
            for (int i = 0; i < silence.length; ++i) worst = Math.max(worst, Math.abs(humOut[0][i] - humOut[1][i]));
            assertEquals(0.0, worst);
        }
    }

    private static double leak(double hz, boolean on)
    {
        final double[] silence = new double[72000];
        final double[][] out = l10Run(t -> { if (on) t.setChannelBleed(-50.0); }, l10Tone(hz, 0.25, 72000), silence);
        return l10Line(out[1], hz) - l10Line(out[0], hz);
    }

    @Test
    @DisplayName("TapeMachine_channel_bleed_rises_with_frequency")
    void channelBleedRisesWithFrequency()
    {
        assertEquals(-50.0, leak(1000.0, true), 1.0);
        assertEquals(-50.0 + 18.39, leak(10000.0, true), 1.0);
        assertTrue(leak(1000.0, false) < -110.0);
    }

    private static double h2Of(double hz, double a)
    {
        final double[] in = l10Tone(hz, 0.5, 96000);
        final double[] out = l10RunLeft(t -> t.setOutputIron(a), in);
        return l10Line(out, 2.0 * hz) - l10Line(out, hz);
    }

    @Test
    @DisplayName("TapeMachine_asymmetry_and_transformer_add_their_harmonics")
    void asymmetryAndTransformerAddTheirHarmonics()
    {
        final double[] tone = l10Tone(1000.0, 0.25, 72000);
        final double[] h2 = new double[3];
        final double[] amounts = { 0.0, 0.5, 1.0 };
        for (int k = 0; k < 3; ++k)
        {
            final double a = amounts[k];
            final double[] out = l10RunLeft(t -> t.setEvenHarmonics(a), tone);
            h2[k] = l10Line(out, 2000.0) - l10Line(out, 1000.0);
        }
        assertTrue(h2[2] > h2[0] + 20.0);
        assertEquals(6.02, h2[2] - h2[1], 1.5);

        final double iron40 = h2Of(40.0, 1.0);
        System.out.printf("  [tape] second harmonic of 40 Hz: %.1f dBc plain, %.1f dBc with the iron%n", h2Of(40.0, 0.0), iron40);
        assertTrue(iron40 > h2Of(40.0, 0.0) + 40.0);
        assertTrue(iron40 > h2Of(1000.0, 1.0) + 15.0);
        final double[] mid = l10Tone(1000.0, 0.5, 72000);
        final double[] plainMid = l10RunLeft(t -> { }, mid);
        final double[] bentMid = l10RunLeft(t -> t.setOutputIron(1.0), mid);
        assertEquals(l10Line(plainMid, 1000.0), l10Line(bentMid, 1000.0), 0.1);
        assertEquals(l10Line(plainMid, 3000.0), l10Line(bentMid, 3000.0), 0.5);

        final TapeMachine a = new TapeMachine(), b = new TapeMachine();
        a.setOutputIron(1.0);
        a.prepare(48000.0, 2, 512);
        b.prepare(48000.0, 2, 512);
        final double[] la = l10Tone(60.0, 0.4, 96000), ra = la.clone(), lb = la.clone(), rb = la.clone();
        final double[][] pa = new double[2][512], pb = new double[2][512];
        for (int off = 0; off < la.length; off += 512)
        {
            if (off == 512 * 40) a.setOutputIron(0.0);
            final int n = Math.min(512, la.length - off);
            System.arraycopy(la, off, pa[0], 0, n);
            System.arraycopy(ra, off, pa[1], 0, n);
            System.arraycopy(lb, off, pb[0], 0, n);
            System.arraycopy(rb, off, pb[1], 0, n);
            a.process(pa, n);
            b.process(pb, n);
            System.arraycopy(pa[0], 0, la, off, n);
            System.arraycopy(pb[0], 0, lb, off, n);
        }
        double worst = 0.0;
        for (int i = 48000; i < la.length; ++i) worst = Math.max(worst, Math.abs(la[i] - lb[i]));
        assertEquals(0.0, worst);
    }

    private static double response(Consumer<TapeMachine> setup, double hz)
    {
        final double[] in = l10Tone(hz, 0.0316, 36000);
        return l10Line(l10RunLeft(setup, in), hz) - l10Line(in, hz);
    }

    @Test
    @DisplayName("TapeMachine_head_contour_circuit_and_trims")
    void headContourCircuitAndTrims()
    {
        final Consumer<TapeMachine> bump = t -> t.setHeadBump(1.0);
        final Consumer<TapeMachine> contour = t -> { t.setHeadBump(1.0); t.setHeadModel(TapeMachine.HeadModel.CONTOUR); };
        assertTrue(response(contour, 130.0) - response(bump, 130.0) < -0.5);
        assertEquals(response(bump, 1000.0), response(contour, 1000.0), 0.1);

        final Consumer<TapeMachine> ideal = t -> { };
        final Consumer<TapeMachine> circuit = t -> t.setElectronics(TapeMachine.Electronics.CIRCUIT);
        assertEquals(response(ideal, 1000.0), response(circuit, 1000.0), 0.3);
        assertEquals(response(ideal, 100.0), response(circuit, 100.0), 0.5);
        final double top = response(circuit, 20000.0) - response(ideal, 20000.0);
        System.out.printf("  [tape] the circuit against the ideal electronics at 20 kHz: %.2f dB%n", top);
        assertTrue(top < -0.2);

        final double hfTrim = response(t -> t.setAlignment(3.0, 0.0), 16000.0) - response(ideal, 16000.0);
        final double lfTrim = response(t -> t.setAlignment(0.0, -3.0), 31.5) - response(ideal, 31.5);
        assertTrue(hfTrim > 2.4 && hfTrim < 3.3, "HF trim " + hfTrim);
        assertTrue(lfTrim > -3.3 && lfTrim < -2.2, "LF trim " + lfTrim);
    }

    @Test
    @DisplayName("TapeMachine_calibration_formulation_and_monitor")
    void calibrationFormulationAndMonitor()
    {
        final double[] tone = l10Tone(1000.0, 0.25, 72000);
        final double[] plain = l10RunLeft(t -> { }, tone);
        final double[] hot = l10RunLeft(t -> t.setCalibrationLevel(6.0), tone);
        assertTrue(l10Thd(hot) > 2.5 * l10Thd(plain));
        assertEquals(l10Line(plain, 1000.0), l10Line(hot, 1000.0), 0.5);
        final double[] high = l10RunLeft(t -> t.setFormulation(TapeMachine.Formulation.HIGH_OUTPUT), tone);
        final double[] vintage = l10RunLeft(t -> t.setFormulation(TapeMachine.Formulation.VINTAGE), tone);
        assertTrue(l10Thd(high) < l10Thd(plain));
        assertTrue(l10Thd(vintage) > l10Thd(plain));

        final double[] prog = new double[48000];
        int seed = 5;
        for (int i = 0; i < prog.length; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            prog[i] = 0.2f * ((float) (seed >>> 8) / 8388608.0f - 1.0f);
        }
        final TapeMachine input = new TapeMachine();
        input.setMonitor(TapeMachine.Monitor.SOURCE);
        input.prepare(48000.0, 2, 512);
        final double[] monitored = l10Render(input, prog, prog)[0];
        final int lat = input.getLatency();
        double worst = 0.0;
        for (int i = lat; i < prog.length; ++i) worst = Math.max(worst, Math.abs(monitored[i] - prog[i - lat]));
        assertTrue(worst < 1e-6);

        final double[] in = l10Tone(15000.0, 0.0316, 36000);
        final double sync = l10Line(l10RunLeft(t -> t.setMonitor(TapeMachine.Monitor.RECORD_HEAD), in), 15000.0);
        final double play = l10Line(l10RunLeft(t -> { }, in), 15000.0);
        assertTrue(sync < play - 0.3);
    }

    @Test
    @DisplayName("TapeMachine_machine_controls_clamp_and_ignore_invalid_values")
    void machineControlsClampAndIgnoreInvalidValues()
    {
        final TapeMachine t = new TapeMachine();
        t.setHum(Double.NaN);
        t.setChannelBleed(Double.POSITIVE_INFINITY);
        t.setEvenHarmonics(Double.NaN);
        t.setOutputIron(-1.0);
        t.setAlignment(Double.NaN, 2.0);
        t.setCalibrationLevel(20.0);
        // Java enums cannot be out of range; null stands for the invalid value and is ignored.
        t.setTransport(null);
        t.setMonitor(null);
        t.setFormulation(null);
        t.setTrackFormat(null);
        t.setHeadModel(null);
        t.setElectronics(null);
        assertEquals(-200.0, t.getHum());
        assertEquals(-200.0, t.getChannelBleed());
        assertEquals(0.0, t.getEvenHarmonics());
        assertEquals(0.0, t.getOutputIron());
        assertEquals(0.0, t.getAlignmentHf());
        assertEquals(0.0, t.getAlignmentLf());
        assertEquals(9.0, t.getCalibrationLevel());
        assertSame(TapeMachine.Transport.MODULATED, t.getTransport());
        assertSame(TapeMachine.Monitor.PLAY_HEAD, t.getMonitor());
        assertSame(TapeMachine.Formulation.CLASSIC, t.getFormulation());
        assertSame(TapeMachine.TrackFormat.HALF, t.getTrackFormat());
        assertSame(TapeMachine.HeadModel.BUMP, t.getHeadModel());
        assertSame(TapeMachine.Electronics.IDEAL, t.getElectronics());
        assertSame(TapeMachine.Mains.HZ_50, t.getMains());
        t.setHum(-500.0, TapeMachine.Mains.HZ_60);
        t.setChannelBleed(0.0);
        t.setEvenHarmonics(3.0);
        t.setOutputIron(2.0);
        t.setAlignment(-9.0, 9.0);
        t.setRecordEmphasis(-40.0);
        t.setNoise(10.0);
        t.setDrive(99.0);
        t.setBias(-1.0);
        t.setMix(2.0);
        t.setAlignmentTolerance(5.0);
        assertEquals(-200.0, t.getHum());
        assertSame(TapeMachine.Mains.HZ_60, t.getMains());
        assertEquals(-20.0, t.getChannelBleed());
        assertEquals(1.0, t.getEvenHarmonics());
        assertEquals(1.0, t.getOutputIron());
        assertEquals(-6.0, t.getAlignmentHf());
        assertEquals(6.0, t.getAlignmentLf());
        assertEquals(-6.0, t.getRecordEmphasis());
        assertEquals(-20.0, t.getNoise());
        assertEquals(24.0, t.getDrive());
        assertEquals(0.0, t.getBias());
        assertEquals(1.0, t.getMix());
        assertEquals(1.0, t.getAlignmentTolerance());
        // The C++ ordinals: stored states keep their meaning.
        assertEquals(0, TapeMachine.Speed.IPS_7_5.ordinal());
        assertEquals(2, TapeMachine.Speed.IPS_30.ordinal());
        assertEquals(3, TapeMachine.Speed.IPS_3_75.ordinal());
    }
}
