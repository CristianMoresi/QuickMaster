package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static com.dspark.effects.TapeSignals.l10Line;
import static com.dspark.effects.TapeSignals.l10RmsDb;
import static com.dspark.effects.TapeSignals.l10RunLeft;
import static com.dspark.effects.TapeSignals.l10Thd;
import static com.dspark.effects.TapeSignals.l10Tone;
import static com.dspark.effects.TapeSignals.m7Tones;
import static com.dspark.effects.TapeSignals.monoRender;
import static com.dspark.effects.TapeSignals.sat8Line;
import static com.dspark.effects.TapeSignals.sat8Pink;
import static com.dspark.effects.TapeSignals.sat8Sine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TapeMachine}: the figures its documentation states, measured on the
 * port (head bump frequencies, THD against the calibration level, hiss level
 * changes, latency per factor, ultrasonic residue, the drive contract, the
 * two-level carrier, the 1x carrier) and the cost of process().
 */
class TapeMachineFiguresTest
{
    /** Lift of the full head bump against none at three frequencies at once, in dB. */
    private static double[] bumpLift(TapeMachine.Speed speed, TapeMachine.TrackFormat track, double[] hz)
    {
        final double[][] list = new double[hz.length][];
        for (int k = 0; k < hz.length; ++k) list[k] = new double[] { hz[k], 0.006 };
        final double[] in = m7Tones(list, 96000, 0.0);
        final double[] on = l10RunLeft(t -> { t.setWowFlutter(0.0); t.setSpeed(speed); t.setTrackFormat(track); t.setHeadBump(1.0); }, in);
        final double[] off = l10RunLeft(t -> { t.setWowFlutter(0.0); t.setSpeed(speed); t.setTrackFormat(track); t.setHeadBump(0.0); }, in);
        final double[] lift = new double[hz.length];
        for (int k = 0; k < hz.length; ++k) lift[k] = l10Line(on, hz[k]) - l10Line(off, hz[k]);
        return lift;
    }

    @Test
    @DisplayName("The head bump sits at the speed over the head's contact: 22.5/45/90/180 Hz half-track, 72/112 Hz full/quarter at 15 ips, 2.5 dB at full")
    void headBumpFrequencies()
    {
        final Object[][] cases = {
            { TapeMachine.Speed.IPS_3_75, TapeMachine.TrackFormat.HALF, 22.5 },
            { TapeMachine.Speed.IPS_7_5, TapeMachine.TrackFormat.HALF, 45.0 },
            { TapeMachine.Speed.IPS_15, TapeMachine.TrackFormat.HALF, 90.0 },
            { TapeMachine.Speed.IPS_30, TapeMachine.TrackFormat.HALF, 180.0 },
            { TapeMachine.Speed.IPS_15, TapeMachine.TrackFormat.FULL, 72.0 },
            { TapeMachine.Speed.IPS_15, TapeMachine.TrackFormat.QUARTER, 112.5 },
        };
        for (final Object[] c : cases)
        {
            final double f0 = (Double) c[2];
            final double[] lift = bumpLift((TapeMachine.Speed) c[0], (TapeMachine.TrackFormat) c[1],
                                           new double[] { f0 / 1.25, f0, f0 * 1.25 });
            System.out.printf("  [tape] head bump %s %s: %+.3f dB at %.1f Hz, %+.3f at %.1f Hz, %+.3f at %.1f Hz%n",
                              c[0], c[1], lift[0], f0 / 1.25, lift[1], f0, lift[2], f0 * 1.25);
            // A peak at f0: 2.5 dB there, and the same lift a third of an
            // octave either side (a peak elsewhere would tilt the pair).
            assertEquals(2.5, lift[1], 0.05);
            assertEquals(lift[0], lift[2], 0.06);
            assertTrue(lift[1] > lift[0] + 0.3 && lift[1] > lift[2] + 0.3);
        }
    }

    private static double thdAtCalibration(double calDb, TapeMachine.BiasShape shape)
    {
        final double[] in = l10Tone(1000.0, Math.pow(10.0, -12.0 / 20.0), 72000);
        return l10Thd(l10RunLeft(t -> { t.setWowFlutter(0.0); t.setBiasShape(shape); t.setCalibrationLevel(calDb); }, in));
    }

    @Test
    @DisplayName("A 1 kHz tone at -12 dBFS carries 0.16 % THD at calibration 0 dB, 0.31 % at 3 dB, 0.60 % at 6 dB (two-level carrier)")
    void thdAgainstCalibrationLevel()
    {
        // The figures of setCalibrationLevel() were measured on the two-level
        // carrier, the default when they were written; the four-level one
        // keeps their ratios (about twice per 3 dB) at about 0.7 of them.
        final double[] want = { 0.16, 0.31, 0.60 };
        final double[] cal = { 0.0, 3.0, 6.0 };
        double prev = 0.0;
        for (int k = 0; k < 3; ++k)
        {
            final double two = 100.0 * thdAtCalibration(cal[k], TapeMachine.BiasShape.TWO_LEVEL);
            final double four = 100.0 * thdAtCalibration(cal[k], TapeMachine.BiasShape.FOUR_LEVEL);
            System.out.printf("  [tape] THD of 1 kHz at -12 dBFS, calibration +%.0f dB: %.3f %% (two levels), %.3f %% (four levels)%n",
                              cal[k], two, four);
            assertEquals(want[k], two, 0.05 * want[k]);
            assertTrue(four < two && four > prev, "four levels " + four);
            prev = four;
        }
    }

    private static double hissDb(Consumer<TapeMachine> setup)
    {
        return l10RmsDb(l10RunLeft(t -> { t.setWowFlutter(0.0); t.setNoise(-50.0); setup.accept(t); }, new double[96000]));
    }

    @Test
    @DisplayName("Hiss: HIGH_OUTPUT -1.5 dB, VINTAGE +3 dB, FULL track -5.0 dB, QUARTER +3.0 dB, calibration +6 dB -6 dB")
    void hissLevelChanges()
    {
        final double plain = hissDb(t -> { });
        final double high = hissDb(t -> t.setFormulation(TapeMachine.Formulation.HIGH_OUTPUT)) - plain;
        final double vintage = hissDb(t -> t.setFormulation(TapeMachine.Formulation.VINTAGE)) - plain;
        final double full = hissDb(t -> t.setTrackFormat(TapeMachine.TrackFormat.FULL)) - plain;
        final double quarter = hissDb(t -> t.setTrackFormat(TapeMachine.TrackFormat.QUARTER)) - plain;
        final double cal = hissDb(t -> t.setCalibrationLevel(6.0)) - plain;
        System.out.printf("  [tape] hiss %.2f dBFS; high output %+.2f, vintage %+.2f, full %+.2f, quarter %+.2f, calibration +6 %+.2f dB%n",
                          plain, high, vintage, full, quarter, cal);
        assertEquals(-50.0, plain, 0.5);
        // The formulation and the operating level move only the hiss's level.
        assertEquals(-1.5, high, 0.01);
        assertEquals(3.0, vintage, 0.01);
        assertEquals(-6.0, cal, 0.01);
        // The track format moves its level and its head (the bump), which the
        // hiss crosses.
        assertEquals(-5.0, full, 0.2);
        assertEquals(3.0, quarter, 0.2);
    }

    private static int measuredDelay(TapeMachine tape)
    {
        final double[] in = new double[16384];
        in[1000] = 0.05;
        final double[] out = monoRender(tape, in);
        int peak = 0;
        for (int i = 0; i < out.length; ++i)
            if (Math.abs(out[i]) > Math.abs(out[peak])) peak = i;
        return peak - 1000;
    }

    @Test
    @DisplayName("The latency is the oversampler's, the loss FIR's centre and the transport's, as measured, per factor and rate")
    void latencyPerFactor()
    {
        final StringBuilder table = new StringBuilder("  [tape] latency (measured):");
        for (final double rate : new double[] { 44100.0, 48000.0, 96000.0 })
        {
            final TapeMachine auto = new TapeMachine();
            auto.setWowFlutter(0.0);
            auto.setAutomaticOversampling(true);
            auto.prepare(rate, 1, 512);
            final int measured = measuredDelay(auto);
            table.append(String.format(" %.1f kHz auto %dx %d (%d);", rate / 1000.0, auto.getOversamplingFactor(), auto.getLatency(), measured));
            assertEquals(auto.getLatency(), measured, 1.0);
        }
        System.out.println(table);
        final StringBuilder factors = new StringBuilder("  [tape] latency at 48 kHz per factor:");
        for (final int factor : new int[] { 1, 2, 4, 8, 16 })
        {
            final TapeMachine t = new TapeMachine();
            t.setWowFlutter(0.0);
            t.setOversampling(factor);
            t.prepare(48000.0, 1, 512);
            final int measured = measuredDelay(t);
            factors.append(String.format(" %dx %d (%d);", factor, t.getLatency(), measured));
            assertEquals(t.getLatency(), measured, 1.0);
        }
        System.out.println(factors);
    }

    @Test
    @DisplayName("The ultrasonic residue of the biased core stays below -75 dB")
    void ultrasonicResidue()
    {
        // A tone whose products with the carrier do not fall on its own
        // harmonics: everything between 20 and 24 kHz that is not a harmonic
        // is what the carrier leaves.
        for (final double db : new double[] { -12.0, 0.0 })
        {
            final double hz = 997.0;
            final double[] out = l10RunLeft(t -> t.setWowFlutter(0.0), l10Tone(hz, Math.pow(10.0, db / 20.0), 72000));
            final double fund = l10Line(out, hz);
            double worst = -300.0, worstAt = 0.0;
            for (double f = 20000.0; f < 23900.0; f += 5.0)
            {
                final double k = Math.floor(f / hz + 0.5);
                if (Math.abs(f - k * hz) < 25.0) continue;
                final double line = l10Line(out, f) - fund;
                if (line > worst)
                {
                    worst = line;
                    worstAt = f;
                }
            }
            System.out.printf("  [tape] 997 Hz at %.0f dBFS: worst non-harmonic line above 20 kHz %.1f dBc at %.0f Hz%n", db, worst, worstAt);
            assertTrue(worst < -75.0);
        }
    }

    private static double levelDb(double[] out, double[] in)
    {
        double so = 0.0, si = 0.0;
        for (int i = out.length / 2; i < out.length; ++i)
        {
            so += out[i] * out[i];
            si += in[i] * in[i];
        }
        return 10.0 * Math.log10(so / si);
    }

    @Test
    @DisplayName("The drive contract: about a quarter of a dB a dB at the -12 dBFS calibration tone; programme levels against the drive")
    void driveContract()
    {
        final double[] tone = l10Tone(1000.0, Math.pow(10.0, -12.0 / 20.0), 72000);
        final double[] pink = sat8Pink(72000, -18.0, 21);
        final double[] drives = { -12.0, 0.0, 12.0, 24.0 };
        final double[] toneGain = new double[drives.length];
        for (int k = 0; k < drives.length; ++k)
        {
            final double drive = drives[k];
            final Consumer<TapeMachine> setup = t -> { t.setWowFlutter(0.0); t.setAutomaticOversampling(true); t.setDrive(drive); };
            toneGain[k] = l10Line(l10RunLeft(setup, tone), 1000.0) - l10Line(tone, 1000.0);
            final double pinkGain = levelDb(l10RunLeft(setup, pink), pink);
            System.out.printf("  [tape] drive %+.0f dB: 1 kHz at -12 dBFS %+.2f dB, pink at -18 dBFS RMS %+.2f dB%n",
                              drive, toneGain[k], pinkGain);
        }
        // The tone the makeup is calibrated on comes out at its level at 0 dB
        // of drive, and moves by the link: 0.25 dB a dB below 0 dB of drive,
        // 0.23 above.
        assertEquals(0.0, toneGain[1], 0.3);
        for (int k = 0; k < drives.length; ++k)
            assertEquals((drives[k] > 0.0 ? 0.23 : 0.25) * drives[k], toneGain[k] - toneGain[1], 0.5, "drive " + drives[k]);
        // The header's programme figures at +24 dB: a programme at -33 dBFS RMS
        // gains 11.8 dB and one at -15 dBFS 2.2 dB (the seven-tone programme
        // of the C++ insertion tests).
        final double[] gains = new double[2];
        final double[] rms = { -33.0, -15.0 };
        for (int k = 0; k < 2; ++k)
        {
            final float amp = (float) (Math.pow(10.0, rms[k] / 20.0) * Math.sqrt(2.0));
            final double[] prog = new double[96000];
            for (int i = 0; i < prog.length; ++i) prog[i] = TapeSignals.analogProgram(i, amp);
            gains[k] = levelDb(l10RunLeft(t -> t.setDrive(24.0), prog), prog);
            final double[] pinkAt = sat8Pink(96000, rms[k], 5);
            final double pinkGain = levelDb(l10RunLeft(t -> t.setDrive(24.0), pinkAt), pinkAt);
            System.out.printf("  [tape] drive +24 dB: the programme at %.0f dBFS RMS gains %.2f dB (pink noise %.2f dB)%n",
                              rms[k], gains[k], pinkGain);
        }
        assertEquals(11.8, gains[0], 0.25);
        assertEquals(2.2, gains[1], 0.25);
    }

    private static double gainAt(double hz, double db, Consumer<TapeMachine> setup)
    {
        final double[] in = sat8Sine(hz, Math.pow(10.0, db / 20.0), 24000);
        return sat8Line(l10RunLeft(t -> { t.setWowFlutter(0.0); setup.accept(t); }, in), hz) - db;
    }

    @Test
    @DisplayName("Two levels lift the gain with the level before it saturates (3.2 dB at the nominal bias, up to 9.9 dB at full)")
    void twoLevelCarrierLiftsTheGain()
    {
        // The top's gain against its quiet value, 10 kHz from -40 to +6 dBFS.
        final double[] want = { 3.2, 9.9 };
        final double[] biases = { 0.5, 1.0 };
        for (int k = 0; k < 2; ++k)
        {
            final double bias = biases[k];
            final Consumer<TapeMachine> two = t -> { t.setBiasShape(TapeMachine.BiasShape.TWO_LEVEL); t.setBias(bias); };
            final double quiet = gainAt(10000.0, -40.0, two);
            double most = -1e9;
            for (final double db : new double[] { -24.0, -18.0, -12.0, -6.0, 0.0, 6.0 })
                most = Math.max(most, gainAt(10000.0, db, two));
            System.out.printf("  [tape] two levels, bias %.1f: the 10 kHz gain rises %.2f dB over its quiet value%n", bias, most - quiet);
            assertEquals(want[k], most - quiet, 0.2);
        }
    }

    @Test
    @DisplayName("At 1x a quiet 10 kHz comes out 0.6 dB under its 4x level at the nominal bias, 4.1 with no bias")
    void oneTimesCarrierAgainstFourTimes()
    {
        for (final double bias : new double[] { 0.5, 0.0 })
        {
            final double at4 = gainAt(10000.0, -40.0, t -> t.setBias(bias));
            final double at1 = gainAt(10000.0, -40.0, t -> { t.setBias(bias); t.setOversampling(1); });
            System.out.printf("  [tape] quiet 10 kHz, bias %.1f: 1x is %.2f dB under 4x%n", bias, at4 - at1);
            assertEquals(bias == 0.5 ? 0.6 : 4.1, at4 - at1, 0.3);
        }
    }

    @Test
    @DisplayName("Cost of process() per stereo frame at 48 kHz (defaults, automatic factor; and Circuit electronics)")
    void costPerStereoFrame()
    {
        for (int e = 0; e < 2; ++e)
        {
            final TapeMachine t = new TapeMachine();
            t.setAutomaticOversampling(true);
            if (e == 1) t.setElectronics(TapeMachine.Electronics.CIRCUIT);
            t.prepare(48000.0, 2, 512);
            final double[][] b = new double[2][512];
            final double[] pinkL = sat8Pink(48000, -18.0, 3), pinkR = sat8Pink(48000, -18.0, 4);
            long n = 0;
            double best = Double.MAX_VALUE;
            for (int rep = 0; rep < 5; ++rep)
            {
                final long t0 = System.nanoTime();
                for (int k = 0; k < 90; ++k)
                {
                    for (int i = 0; i < 512; ++i, ++n)
                    {
                        b[0][i] = pinkL[(int) (n % 48000)];
                        b[1][i] = pinkR[(int) (n % 48000)];
                    }
                    t.process(b, 512);
                }
                best = Math.min(best, (double) (System.nanoTime() - t0) / (90 * 512));
            }
            System.out.printf("  [tape] %s electronics: %.0f ns per stereo frame at 48 kHz (best of 5 passes of 0.96 s)%n",
                              e == 0 ? "ideal" : "circuit", best);
            assertTrue(best < 100_000.0);
        }
    }
}
