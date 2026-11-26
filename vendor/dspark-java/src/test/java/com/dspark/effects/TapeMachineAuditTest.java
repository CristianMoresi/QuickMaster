package com.dspark.effects;

import com.dspark.core.Oversampling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static com.dspark.effects.TapeSignals.toneThrough;
import static com.dspark.effects.TapeSignals.worstDifference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TapeMachine}: the TapeMachine cases of the C++ TestAnalogAudit.cpp,
 * TestEffectsAdvanced.cpp (the mix ramp), TestState.cpp (the automatic
 * factor) and TestEffectsTone.cpp (the dry path through minimum-phase
 * filters), with their thresholds.
 */
class TapeMachineAuditTest
{
    private static final double TWO_PI = 2.0 * Math.PI;

    private static double toDb(double x) { return 20.0 * Math.log10(Math.max(x, 1e-30)); }

    /** Coherent amplitude of frequency f over x[from, from + n). */
    private static double toneAmplitude(double[] x, int from, int n, double f, double fs)
    {
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double w = TWO_PI * f * (double) i / fs;
            re += x[from + i] * Math.cos(w);
            im += x[from + i] * Math.sin(w);
        }
        return 2.0 * Math.sqrt(re * re + im * im) / (double) n;
    }

    /** Runs a mono sine through a processor in blocks and returns the output; t counts the samples. */
    private static double[] runSine(TapeMachine p, double fs, int block, int total, double f, double amp, long[] t)
    {
        final double[][] buf = new double[1][block];
        final double[] out = new double[total + block];
        int have = 0;
        while (have < total)
        {
            for (int i = 0; i < block; ++i)
                buf[0][i] = (float) (amp * Math.sin(TWO_PI * f * (double) (t[0]++) / fs));
            p.process(buf, block);
            System.arraycopy(buf[0], 0, out, have, block);
            have += block;
        }
        return Arrays.copyOf(out, total);
    }

    /** Largest third difference: a click detector that ignores the tone itself. */
    private static double maxD3(double[] x)
    {
        double m = 0.0;
        for (int i = 3; i < x.length; ++i)
            m = Math.max(m, Math.abs(x[i] - 3.0 * x[i - 1] + 3.0 * x[i - 2] - x[i - 3]));
        return m;
    }

    /** Median time of a 64-sample stereo block over a number of blocks, optionally moving the drive first. */
    private static double medianBlockNanos(TapeMachine p, int blocks, boolean automate)
    {
        final int block = 64;
        final double[][] ch = new double[2][block];
        final long[] times = new long[blocks];
        long t = 0;
        for (int k = 0; k < blocks; ++k)
        {
            for (int i = 0; i < block; ++i)
            {
                final double v = (float) (0.25 * Math.sin(TWO_PI * 440.0 * (double) (t++) / 48000.0));
                ch[0][i] = v;
                ch[1][i] = v;
            }
            if (automate) p.setDrive((float) (6.0 * Math.sin(0.1 * k)));
            final long t0 = System.nanoTime();
            p.process(ch, block);
            times[k] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        return (double) times[blocks / 2];
    }

    @Test
    @DisplayName("AnalogAudit_automation_does_not_spike_the_callback (tape)")
    void automationDoesNotSpikeTheCallback()
    {
        final TapeMachine tape = new TapeMachine();
        tape.prepare(48000.0, 2, 64);
        medianBlockNanos(tape, 600, false);
        medianBlockNanos(tape, 300, true);
        final double idle = medianBlockNanos(tape, 400, false);
        final double busy = medianBlockNanos(tape, 300, true);
        System.out.printf("  [tape] 64-sample block: %.1f us idle, %.1f us with the drive moving every block%n", idle / 1000.0, busy / 1000.0);
        assertTrue(busy / idle < 3.0, "busy / idle " + busy / idle);
    }

    @Test
    @DisplayName("AnalogAudit_tape_drive_change_is_ramped")
    void tapeDriveChangeIsRamped()
    {
        final double fs = 48000.0;
        final TapeMachine tape = new TapeMachine();
        tape.setWowFlutter(0.0);
        tape.prepare(fs, 1, 64);
        final long[] t = { 0 };
        runSine(tape, fs, 64, 48000, 220.0, 0.1, t);
        final double[] before = runSine(tape, fs, 64, 12000, 220.0, 0.1, t);
        tape.setDrive(24.0);
        final double[] during = runSine(tape, fs, 64, 9600, 220.0, 0.1, t);
        runSine(tape, fs, 64, 48000, 220.0, 0.1, t);
        final double[] after = runSine(tape, fs, 64, 12000, 220.0, 0.1, t);
        final double steady = Math.max(maxD3(before), maxD3(after));
        System.out.printf("  [tape] drive 0 -> +24 dB: third difference %.2f times the steady one%n", maxD3(during) / steady);
        assertTrue(maxD3(during) / steady < 20.0);
    }

    @Test
    @DisplayName("AnalogAudit_tape_at_1x_survives_noise")
    void tapeAt1xSurvivesNoise()
    {
        final double fs = 96000.0;
        final TapeMachine tape = new TapeMachine();
        tape.setOversampling(1);
        tape.setWowFlutter(0.0);
        tape.prepare(fs, 1, 512);
        final long[] t = { 0 };
        final double[] y0 = runSine(tape, fs, 512, 3 << 15, 1000.0, 0.1, t);
        final double[][] buf = new double[1][512];
        // The C++ draws std::normal_distribution(0, 0.05) from std::mt19937(3); the
        // same distribution from java.util.Random(3) here.
        final Random rng = new Random(3);
        for (int k = 0; k < (int) (fs * 5.0 / 512.0); ++k)
        {
            for (int i = 0; i < 512; ++i) buf[0][i] = (float) (0.05 * rng.nextGaussian());
            tape.process(buf, 512);
        }
        final double[] y1 = runSine(tape, fs, 512, 3 << 15, 1000.0, 0.1, t);
        final double before = toDb(toneAmplitude(y0, 2 << 15, 1 << 15, 1000.0, fs));
        final double after = toDb(toneAmplitude(y1, 2 << 15, 1 << 15, 1000.0, fs));
        assertTrue(before > -21.0, "before " + before);
        assertTrue(Math.abs(after - before) < 0.5, "before " + before + ", after " + after);
    }

    @Test
    @DisplayName("AnalogAudit_oversize_blocks_equal_split_blocks (tape)")
    void oversizeBlocksEqualSplitBlocks()
    {
        final TapeMachine a = new TapeMachine(), b = new TapeMachine();
        for (final TapeMachine p : new TapeMachine[] { a, b })
        {
            p.setWowFlutter(0.0);
            p.prepare(48000.0, 1, 256);
        }
        final double[] x = new double[4096];
        for (int i = 0; i < x.length; ++i) x[i] = (float) (0.3 * Math.sin(0.05 * (double) i));
        final double[] ya = x.clone(), yb = x.clone();
        final double[][] big = new double[1][1024], small = new double[1][256];
        for (int off = 0; off < x.length; off += 1024)
        {
            System.arraycopy(ya, off, big[0], 0, 1024);
            a.process(big, 1024);
            System.arraycopy(big[0], 0, ya, off, 1024);
        }
        for (int off = 0; off < x.length; off += 256)
        {
            System.arraycopy(yb, off, small[0], 0, 256);
            b.process(small, 256);
            System.arraycopy(small[0], 0, yb, off, 256);
        }
        assertTrue(worstDifference(ya, yb, 0) < 1e-6);
    }

    private static double gain18k(double fs, double bias)
    {
        final TapeMachine tape = new TapeMachine();
        tape.setWowFlutter(0.0);
        tape.setBias(bias);
        tape.prepare(fs, 1, 512);
        final long[] t = { 0 };
        final int settle = (int) (fs * 0.25);
        final double[] y = runSine(tape, fs, 512, 2 * settle, 18000.0, 0.0316, t);
        return toDb(toneAmplitude(y, settle, settle, 18000.0, fs) / 0.0316);
    }

    @Test
    @DisplayName("AnalogAudit_tape_response_is_rate_independent")
    void tapeResponseIsRateIndependent()
    {
        final double a48 = gain18k(48000.0, 0.5), a192 = gain18k(192000.0, 0.5);
        final double b48 = gain18k(48000.0, 1.0), b192 = gain18k(192000.0, 1.0);
        System.out.printf("  [tape] 18 kHz: %.2f dB at 48 kHz, %.2f at 192 kHz (bias 0.5); %.2f / %.2f (bias 1)%n", a48, a192, b48, b192);
        assertTrue(Math.abs(a48 - a192) < 0.5);
        assertTrue(Math.abs(b48 - b192) < 1.0);
    }

    @Test
    @DisplayName("AnalogAudit_tape_hiss_level_matches_setting")
    void tapeHissLevelMatchesSetting()
    {
        final TapeMachine tape = new TapeMachine();
        tape.setWowFlutter(0.0);
        tape.setNoise(-50.0);
        tape.prepare(48000.0, 1, 512);
        final double[][] buf = new double[1][512];
        double acc = 0.0;
        int count = 0;
        for (int k = 0; k < 400; ++k)
        {
            Arrays.fill(buf[0], 0.0);
            tape.process(buf, 512);
            if (k > 200)
                for (final double v : buf[0])
                {
                    acc += v * v;
                    ++count;
                }
        }
        assertEquals(-50.0, 10.0 * Math.log10(acc / count), 0.5);
    }

    @Test
    @DisplayName("Wet_dry_mix_ramps_over_20_ms_with_small_blocks (tape)")
    void wetDryMixRampsOver20msWithSmallBlocks()
    {
        final TapeMachine wet = new TapeMachine(), dry = new TapeMachine(), subject = new TapeMachine();
        for (final TapeMachine fx : new TapeMachine[] { wet, dry, subject })
        {
            fx.setDrive(12.0);
            fx.prepare(48000.0, 1, 32);
        }
        dry.setMix(0.0);
        dry.reset();
        final int warm = 32 * 150;
        final double[] in = new double[warm + 32];
        for (int i = 0; i < in.length; ++i) in[i] = 0.5f * (float) Math.sin(6.2831853f * 220.0f * (float) i / 48000.0f);
        final double[] a = in.clone(), b = in.clone(), c = in.clone();
        final double[][] pa = new double[1][32], pb = new double[1][32], pc = new double[1][32];
        for (int off = 0; off < warm + 32; off += 32)
        {
            if (off == warm) subject.setMix(0.0);
            System.arraycopy(a, off, pa[0], 0, 32);
            System.arraycopy(b, off, pb[0], 0, 32);
            System.arraycopy(c, off, pc[0], 0, 32);
            wet.process(pa, 32);
            dry.process(pb, 32);
            subject.process(pc, 32);
            System.arraycopy(pa[0], 0, a, off, 32);
            System.arraycopy(pb[0], 0, b, off, 32);
            System.arraycopy(pc[0], 0, c, off, 32);
        }
        double num = 0.0, den = 0.0;
        for (int i = warm + 24; i < warm + 32; ++i)
        {
            final double d = a[i] - b[i];
            num += (c[i] - b[i]) * d;
            den += d * d;
        }
        final double mix = den > 0.0 ? num / den : -1.0;
        assertTrue(mix > 0.9, "effective mix after one block " + mix);
    }

    @Test
    @DisplayName("Automatic_oversampling_follows_the_host_rate (tape)")
    void automaticOversamplingFollowsTheHostRate()
    {
        final TapeMachine fx = new TapeMachine();
        assertFalse(fx.isOversamplingAutomatic());
        fx.setAutomaticOversampling(true);
        assertTrue(fx.isOversamplingAutomatic());
        final double[][] expected = { { 44100.0, 4 }, { 96000.0, 2 }, { 192000.0, 1 } };
        for (final double[] each : expected)
        {
            fx.prepare(each[0], 2, 256);
            assertEquals((int) each[1], fx.getOversamplingFactor(), each[0] + " Hz");
            final double[][] block = new double[2][256];
            for (int pass = 0; pass < 4; ++pass)
            {
                for (int i = 0; i < 256; ++i)
                    block[0][i] = block[1][i] = 0.25f * (float) Math.sin(TWO_PI * 1000.0 * i / each[0]);
                fx.process(block, 256);
                for (int i = 0; i < 256; ++i) assertTrue(Double.isFinite(block[0][i]) && Double.isFinite(block[1][i]));
            }
        }
        final TapeMachine other = new TapeMachine();
        other.prepare(192000.0, 2, 256);
        other.setAutomaticOversampling(true);
        assertTrue(other.isOversamplingAutomatic());
        assertEquals(1, other.getOversamplingFactor());

        fx.setOversampling(4);
        assertFalse(fx.isOversamplingAutomatic());
        fx.prepare(96000.0, 2, 256);
        assertEquals(4, fx.getOversamplingFactor());
    }

    /** The same tone through a bare round trip of an engine, late by {@code delay}. */
    private static double[] roundTripThrough(Oversampling os, double freq, float amp, int length, int delay)
    {
        final int block = 256;
        final double[] out = new double[length];
        final double[][] ch = new double[2][block];
        for (int off = 0; off < length; off += block)
        {
            final int n = Math.min(block, length - off);
            for (int i = 0; i < n; ++i)
            {
                final float x = amp * (float) Math.sin(2.0 * Math.PI * freq * (off + i) / 48000.0);
                ch[0][i] = x;
                ch[1][i] = x;
            }
            os.upsample(ch, n);
            os.downsample(ch, n);
            for (int i = 0; i < n; ++i)
                if (off + i + delay < length) out[off + i + delay] = ch[0][i];
        }
        return out;
    }

    @Test
    @DisplayName("TubePreamp_and_TapeMachine_dry_path_crosses_minimum_phase_filters (tape)")
    void dryPathCrossesMinimumPhaseFilters()
    {
        final TapeMachine p = new TapeMachine();
        p.setMix(0.0);
        p.setOversamplingMode(TapeMachine.OversamplingMode.MINIMUM_PHASE);
        p.prepare(48000.0, 2, 256);
        final TapeMachine linear = new TapeMachine();
        linear.prepare(48000.0, 2, 256);
        assertTrue(p.getLatency() < linear.getLatency());
        final Oversampling os = new Oversampling(4, Oversampling.Quality.HIGH, Oversampling.Mode.POLYPHASE_IIR);
        os.prepare(48000.0, 2, 256);
        final double[] got = toneThrough(p, 15000.0, 0.2f, 9600);
        final double[] want = roundTripThrough(os, 15000.0, 0.2f, 9600, p.getLatency() - os.getLatency());
        assertTrue(worstDifference(got, want, 2400) < 1e-6);
    }
}
