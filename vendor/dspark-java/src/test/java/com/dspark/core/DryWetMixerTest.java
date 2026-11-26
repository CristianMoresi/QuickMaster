package com.dspark.core;

import com.dspark.core.DryWetMixer.MixRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DryWetMixerTest
{
    /** The C++ tests' default specification: 44.1 kHz, 512-sample blocks, stereo. */
    private static DryWetMixer prepared()
    {
        DryWetMixer m = new DryWetMixer();
        m.prepare(44100.0, 2, 512);
        return m;
    }

    private static double[][] dc(int channels, int n, double v)
    {
        double[][] b = new double[channels][n];
        for (double[] ch : b) Arrays.fill(ch, v);
        return b;
    }

    @Test
    @DisplayName("Mix 0 returns the dry (DryWetMixer_mix0_returns_dry)")
    void mix0ReturnsDry()
    {
        DryWetMixer mixer = prepared();
        double[][] tb = new double[2][256];
        for (int ch = 0; ch < 2; ch++)
            for (int i = 0; i < 256; i++) tb[ch][i] = Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
        double[] dryCopy = tb[0].clone();
        mixer.pushDry(tb, 256);
        for (double[] ch : tb) Arrays.fill(ch, 0.0);
        mixer.mixWet(tb, 256, 0.0);
        for (int i = 0; i < 256; i++) assertEquals(dryCopy[i], tb[0][i], 1e-5);
    }

    @Test
    @DisplayName("Mix 1 returns the wet (DryWetMixer_mix1_returns_wet)")
    void mix1ReturnsWet()
    {
        DryWetMixer mixer = prepared();
        double[][] tb = new double[2][256];
        for (int i = 0; i < 256; i++) tb[0][i] = tb[1][i] = Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
        mixer.pushDry(tb, 256);
        tb = dc(2, 256, 0.42);
        mixer.mixWet(tb, 256, 1.0);
        assertEquals(0.42, tb[0][100], 1e-5);
    }

    @Test
    @DisplayName("Mix laws are exact: linear 1 - m, equal power sqrt(1 - m) (DryWetMixer_mix_laws_are_exact)")
    void mixLawsAreExact()
    {
        DryWetMixer linear = new DryWetMixer(), ep = new DryWetMixer();
        linear.prepare(44100.0, 2, 512);
        ep.prepare(44100.0, 2, 512);
        ep.setMixRule(MixRule.EQUAL_POWER);

        double[][] tb = dc(1, 256, 1.0);
        linear.pushDry(tb, 256);
        Arrays.fill(tb[0], 0.0);
        linear.mixWet(tb, 256, 0.25);
        assertEquals(0.75, tb[0][200], 1e-6);

        tb = dc(1, 256, 1.0);
        ep.pushDry(tb, 256);
        Arrays.fill(tb[0], 0.0);
        ep.mixWet(tb, 256, 0.25);
        assertEquals(0.8660254, tb[0][200], 1e-6);
    }

    @Test
    @DisplayName("The ramped equal-power law is NaN free on a huge block (DryWetMixer_equalpower_ramp_is_nan_free_on_huge_blocks)")
    void equalPowerRampIsNanFreeOnHugeBlocks()
    {
        final int n = 65536;
        DryWetMixer mixer = new DryWetMixer();
        mixer.prepare(48000.0, 1, n);
        mixer.setMixRule(MixRule.EQUAL_POWER);

        double[][] buf = dc(1, n, 1.0);
        mixer.pushDry(buf, n);
        Arrays.fill(buf[0], 0.0);
        mixer.mixWet(buf, n, 0.1);
        assertEquals(Math.sqrt(0.9), buf[0][n - 1], 1e-6);

        buf = dc(1, n, 1.0);
        mixer.pushDry(buf, n);
        Arrays.fill(buf[0], 0.0);
        mixer.mixWet(buf, n, 1.0);
        for (double v : buf[0]) assertTrue(!Double.isNaN(v));
        assertEquals(Math.sqrt(0.9), buf[0][0], 1e-4);
        for (int i = 1; i < n; i++) assertTrue(buf[0][i] <= buf[0][i - 1] + 1e-6, "sample " + i);

        buf = dc(1, n, 1.0);
        mixer.pushDry(buf, n);
        Arrays.fill(buf[0], 0.0);
        mixer.mixWet(buf, n, 1.0);
        assertEquals(0.0, buf[0][100], 1e-7);
    }

    private static double runBlock(DryWetMixer mixer, int b, double mix)
    {
        double[][] buf = dc(1, b, 1.0);   // dry = 1
        mixer.pushDry(buf, b);
        Arrays.fill(buf[0], 0.0);         // wet = 0
        mixer.mixWet(buf, b, mix);
        return buf[0][b - 1];             // = 1 - mix at the last sample
    }

    @Test
    @DisplayName("Small blocks ramp over at least 20 ms (DryWetMixer_small_blocks_ramp_over_the_minimum_time)")
    void smallBlocksRampOverTheMinimumTime()
    {
        final int b = 32;
        DryWetMixer mixer = new DryWetMixer();
        mixer.prepare(48000.0, 1, b);
        runBlock(mixer, b, 0.0);
        final double afterOne = runBlock(mixer, b, 1.0);
        assertEquals(31.0 / 960.0, 1.0 - afterOne, 1e-4);
        int blocks = 1;
        double last = afterOne;
        while (last > 1e-6 && blocks < 100) { last = runBlock(mixer, b, 1.0); ++blocks; }
        assertEquals(31, blocks);
        assertEquals(0.0, runBlock(mixer, b, 1.0), 1e-7);
    }

    @Test
    @DisplayName("Latency compensation delays the dry by exactly D across irregular blocks (DryWetMixer_latency_compensation_time_aligns_dry)")
    void latencyCompensationTimeAlignsDry()
    {
        final int d = 17;
        DryWetMixer mixer = new DryWetMixer();
        mixer.prepare(48000.0, 1, 64);
        mixer.setLatencyCompensation(d);
        assertEquals(d, mixer.getLatencyCompensation());
        double[][] buf = new double[1][64];
        int globalIndex = 0;
        for (int n : new int[] { 7, 32, 19 })
        {
            for (int i = 0; i < n; i++) buf[0][i] = globalIndex + i + 1;
            mixer.pushDry(buf, n);
            final double[] dry = mixer.getDryChannel(0);
            for (int i = 0; i < n; i++)
            {
                final int g = globalIndex + i;
                assertEquals(g < d ? 0.0 : (double) (g - d + 1), dry[i], 1e-9);
            }
            globalIndex += n;
        }
    }

    @Test
    @DisplayName("A latency set before prepare() is sized there; with an offset the capture reads the stretch")
    void latencyBeforePrepareAndOffsets()
    {
        DryWetMixer mixer = new DryWetMixer();
        mixer.setLatencyCompensation(3);
        mixer.prepare(48000.0, 2, 16);
        double[][] buf = new double[2][20];
        for (int i = 0; i < 20; i++) { buf[0][i] = i; buf[1][i] = -i; }
        mixer.pushDry(buf, 4, 8);
        assertEquals(8, mixer.getDryCapturedSamples());
        assertArrayEquals(new double[] { 0, 0, 0, 4, 5, 6, 7, 8 }, Arrays.copyOf(mixer.getDryChannel(0), 8));
        assertArrayEquals(new double[] { 0, 0, 0, -4, -5, -6, -7, -8 }, Arrays.copyOf(mixer.getDryChannel(1), 8));
        // mixWet with an offset blends that stretch only.
        double[][] wet = new double[2][20];
        mixer.mixWet(wet, 10, 8, 0.0);
        assertArrayEquals(new double[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 5, 6, 7, 8, 0, 0 }, wet[0]);
    }

    @Test
    @DisplayName("Without a snapshot mixWet is a pass-through (DryWetMixer_mixWet_without_snapshot_is_passthrough)")
    void mixWetWithoutSnapshotIsPassthrough()
    {
        DryWetMixer mixer = prepared();
        mixer.reset();
        double[][] tb = dc(1, 256, 0.42);
        mixer.mixWet(tb, 256, 0.0);
        assertEquals(0.42, tb[0][100], 1e-9);
    }

    @Test
    @DisplayName("The full-dry-to-half rule adds dry and wet at half (DryWetMixer_full_dry_to_half_rule)")
    void fullDryToHalfRule()
    {
        for (double m : new double[] { 0.25, 0.5, 0.75 })
        {
            DryWetMixer mixer = prepared();
            mixer.setMixRule(MixRule.FULL_DRY_TO_HALF);
            assertSame(MixRule.FULL_DRY_TO_HALF, mixer.getMixRule());
            double[][] tb = dc(1, 256, 1.0);
            mixer.pushDry(tb, 256);
            tb = dc(1, 256, 0.5);
            mixer.mixWet(tb, 256, m);
            final double dry = Math.min(1.0, 2.0 * (1.0 - m)), wet = Math.min(1.0, 2.0 * m);
            assertEquals(dry + 0.5 * wet, tb[0][200], 1e-6);
        }
    }

    @Test
    @DisplayName("Targets are clamped and NaN counts as dry; channels beyond the prepared count pass")
    void guards()
    {
        DryWetMixer mixer = new DryWetMixer();
        mixer.prepare(48000.0, 1, 8);
        assertEquals(1, mixer.getDryNumChannels());
        assertNull(mixer.getDryChannel(1));
        assertNull(mixer.getDryChannel(-1));
        double[][] tb = dc(2, 8, 1.0);
        mixer.pushDry(tb, 8);
        tb = dc(2, 8, 0.5);
        mixer.mixWet(tb, 8, Double.NaN);
        assertEquals(1.0, tb[0][7], 0.0);   // NaN -> 0: fully dry
        assertEquals(0.5, tb[1][7], 0.0);   // channel not prepared
        DryWetMixer over = new DryWetMixer();
        over.prepare(48000.0, 1, 8);
        tb = dc(1, 8, 1.0);
        over.pushDry(tb, 8);
        tb = dc(1, 8, 0.5);
        over.mixWet(tb, 8, 7.0);            // clamped to 1: fully wet
        assertEquals(0.5, tb[0][3], 0.0);
        DryWetMixer wide = new DryWetMixer();
        wide.prepare(48000.0, 40, 8);
        assertEquals(DryWetMixer.MAX_CHANNELS, wide.getDryNumChannels());
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give identical output for a constant mix, every rule, with latency")
    void blockSizeIndependence()
    {
        double[] dry = new double[9000], wet = new double[9000];
        for (int i = 0; i < dry.length; i++) { dry[i] = Math.sin(0.01 * i); wet[i] = 0.5 * Math.cos(0.03 * i); }
        for (MixRule rule : MixRule.values())
        {
            double[] reference = null;
            for (int block : new int[] { 4096, 512, 37, 1 })
            {
                DryWetMixer mixer = new DryWetMixer();
                mixer.prepare(48000.0, 1, 4096);
                mixer.setLatencyCompensation(23);
                mixer.setMixRule(rule);
                double[][] d = { dry.clone() }, w = { wet.clone() };
                for (int pos = 0; pos < dry.length; pos += block)
                {
                    final int n = Math.min(block, dry.length - pos);
                    mixer.pushDry(d, pos, n);
                    mixer.mixWet(w, pos, n, 0.3);
                }
                if (reference == null) reference = w[0];
                else assertArrayEquals(reference, w[0], rule + " block " + block);
            }
        }
    }

    @Test
    @DisplayName("reset() equals a fresh mixer")
    void resetEqualsFresh()
    {
        DryWetMixer used = new DryWetMixer(), fresh = new DryWetMixer();
        for (DryWetMixer m : new DryWetMixer[] { used, fresh })
        {
            m.prepare(48000.0, 1, 64);
            m.setLatencyCompensation(5);
            m.setMixRule(MixRule.EQUAL_POWER);
        }
        double[][] junk = dc(1, 64, 0.7);
        used.pushDry(junk, 64);
        used.mixWet(junk, 64, 0.9);
        used.reset();
        double[][] a = new double[1][64], b = new double[1][64], wa = dc(1, 64, 0.2), wb = dc(1, 64, 0.2);
        for (int i = 0; i < 64; i++) a[0][i] = b[0][i] = Math.sin(0.2 * i);
        used.pushDry(a, 64);
        fresh.pushDry(b, 64);
        used.mixWet(wa, 64, 0.4);
        fresh.mixWet(wb, 64, 0.4);
        assertArrayEquals(wb[0], wa[0]);
    }
}
