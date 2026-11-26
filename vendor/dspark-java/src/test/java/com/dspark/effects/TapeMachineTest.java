package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dspark.effects.TapeSignals.analogInsertionDb;
import static com.dspark.effects.TapeSignals.neutralTape;
import static com.dspark.effects.TapeSignals.runTapeSine;
import static com.dspark.effects.TapeSignals.tapeToneMag;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TapeMachine}: the TapeMachine cases of the C++ TestEffectsTone.cpp
 * (response, low end, distortion, wow, latency, mirror image, drive knob,
 * invalid inputs, oversampling, non-finite input) with their thresholds, and
 * the common properties (finite output, block sizes, reset, latency).
 */
class TapeMachineTest
{
    @Test
    @DisplayName("TapeMachine_response_flat_at_reference_level")
    void responseFlatAtReferenceLevel()
    {
        for (final double f : new double[] { 100.0, 1000.0, 8000.0, 14000.0 })
        {
            final TapeMachine tape = new TapeMachine();
            neutralTape(tape, 1);
            final double[] out = runTapeSine(tape, f, 0.25, 1.0, 1);
            final double db = 20.0 * Math.log10(tapeToneMag(out, f) / 0.25);
            assertEquals(0.0, db, 0.75, f + " Hz");
        }
    }

    @Test
    @DisplayName("TapeMachine_low_end_is_aligned")
    void lowEndIsAligned()
    {
        for (final double f : new double[] { 40.0, 60.0, 90.0 })
        {
            final TapeMachine tape = new TapeMachine();
            neutralTape(tape, 1);
            final double[] out = runTapeSine(tape, f, 0.25, 1.5, 1);
            final double db = 20.0 * Math.log10(tapeToneMag(out, f) / 0.25);
            assertEquals(0.0, db, 1.0, f + " Hz");
        }
        final TapeMachine tape = new TapeMachine();
        neutralTape(tape, 1);
        final double[] out = runTapeSine(tape, 25.0, 0.25, 2.0, 1);
        double dc = 0.0;
        final int from = out.length / 2;
        for (int i = from; i < out.length; ++i) dc += out[i];
        dc /= (double) (out.length - from);
        assertTrue(Math.abs(dc) < 0.005, "standing DC " + dc);
    }

    @Test
    @DisplayName("TapeMachine_distortion_is_odd_dominant_and_grows_with_drive")
    void distortionIsOddDominantAndGrowsWithDrive()
    {
        double thdPrev = -1.0;
        for (final double drive : new double[] { 0.0, 6.0, 12.0, 24.0 })
        {
            final TapeMachine tape = new TapeMachine();
            neutralTape(tape, 1);
            tape.setDrive(drive);
            final double[] out = runTapeSine(tape, 1000.0, 0.5, 1.0, 1);
            final double h1 = tapeToneMag(out, 1000.0);
            final double h2 = tapeToneMag(out, 2000.0);
            final double h3 = tapeToneMag(out, 3000.0);
            assertTrue(h3 > h2 * 3.0, "odd >> even at drive " + drive);
            final double thd = h3 / h1;
            assertTrue(thd > thdPrev, "more drive, more saturation at " + drive);
            thdPrev = thd;
        }
        assertTrue(thdPrev > 0.04, "crunch at +24 dB: " + thdPrev);
        assertTrue(thdPrev < 0.30, "not broken at +24 dB: " + thdPrev);
    }

    private static double pitchDeviation(double wf)
    {
        final TapeMachine tape = new TapeMachine();
        neutralTape(tape, 1);
        tape.setDrive(-6.0);
        tape.setWowFlutter(wf);
        final double[] out = runTapeSine(tape, 3000.0, 0.25, 4.0, 1);
        double prevCross = -1.0, minP = 1e9, maxP = 0.0;
        for (int i = out.length / 2; i + 1 < out.length; ++i)
        {
            if (out[i] <= 0.0 && out[i + 1] > 0.0)
            {
                final double fr = out[i] / (out[i] - out[i + 1]);
                final double tc = (double) i + fr;
                if (prevCross >= 0.0)
                {
                    minP = Math.min(minP, tc - prevCross);
                    maxP = Math.max(maxP, tc - prevCross);
                }
                prevCross = tc;
            }
        }
        return (maxP - minP) / (48000.0 / 3000.0);
    }

    @Test
    @DisplayName("TapeMachine_wow_flutter_modulates_pitch")
    void wowFlutterModulatesPitch()
    {
        assertTrue(pitchDeviation(0.0) < 1e-4, "transport off: no modulation");
        final double dev = pitchDeviation(1.0);
        assertTrue(dev > 0.002, "full depth: clearly audible wow, " + dev);
        assertTrue(dev < 0.02, "within worn-machine territory, " + dev);
    }

    @Test
    @DisplayName("TapeMachine_latency_matches_reported")
    void latencyMatchesReported()
    {
        final TapeMachine tape = new TapeMachine();
        neutralTape(tape);
        tape.setDrive(-12.0);
        final int latency = tape.getLatency();
        final double[][] buf = new double[2][512];
        final double[] out = new double[8 * 512];
        for (int b = 0; b < 8; ++b)
        {
            java.util.Arrays.fill(buf[0], 0.0);
            java.util.Arrays.fill(buf[1], 0.0);
            if (b == 0) buf[0][0] = buf[1][0] = 0.05f;
            tape.process(buf, 512);
            System.arraycopy(buf[0], 0, out, b * 512, 512);
        }
        int peakAt = 0;
        double peak = 0;
        for (int i = 0; i < out.length; ++i)
        {
            assertTrue(Double.isFinite(out[i]));
            if (Math.abs(out[i]) > peak)
            {
                peak = Math.abs(out[i]);
                peakAt = i;
            }
        }
        assertEquals(latency, peakAt, 1.0);
    }

    @Test
    @DisplayName("TapeMachine_bias_carrier_leaves_no_mirror_image")
    void biasCarrierLeavesNoMirrorImage()
    {
        for (final double f : new double[] { 10100.0, 5000.0 })
        {
            final TapeMachine tape = new TapeMachine();
            tape.prepare(48000.0, 1, 512);
            tape.setWowFlutter(0.0);
            final double[] out = runTapeSine(tape, f, 0.0316, 1.0, 1);
            final double image = 20.0 * Math.log10(tapeToneMag(out, 24000.0 - f) / tapeToneMag(out, f));
            System.out.printf("  [tape] %.0f Hz: image at %.0f Hz %.1f dB%n", f, 24000.0 - f, image);
            assertTrue(image < -60.0, "image " + image + " dB");
        }
    }

    private static double insertionAt(double driveDb)
    {
        final TapeMachine tm = new TapeMachine();
        tm.prepare(48000.0, 1, 512);
        tm.setDrive(driveDb);
        return analogInsertionDb(tm, 0.1f);
    }

    @Test
    @DisplayName("TapeMachine_drive_knob_is_alive_and_monotonic")
    void driveKnobIsAliveAndMonotonic()
    {
        final double lo = insertionAt(-12.0), mid = insertionAt(0.0), hi = insertionAt(24.0);
        System.out.printf("  [tape] program level at drive -12 / 0 / +24: %.2f / %.2f / %.2f dB%n", lo, mid, hi);
        assertTrue(lo < mid - 1.0);
        assertTrue(hi > mid - 1.0);
        assertTrue(hi < mid + 8.0);
    }

    @Test
    @DisplayName("TapeMachine_invalid_inputs_are_ignored")
    void invalidInputsAreIgnored()
    {
        final TapeMachine dut = new TapeMachine();
        final TapeMachine twin = new TapeMachine();
        for (final TapeMachine t : new TapeMachine[] { dut, twin })
        {
            t.setDrive(6.0);
            t.setBias(0.5);
            t.setWowFlutter(0.15);
            t.prepare(48000.0, 2, 512);
        }
        dut.setDrive(Double.NaN);
        dut.setDrive(Double.POSITIVE_INFINITY);
        dut.setBias(Double.NaN);
        dut.setLossEffects(Double.NaN);
        dut.setHeadBump(Double.NaN);
        dut.setWowFlutter(Double.NaN);
        dut.setNoise(Double.NaN);
        dut.setMix(Double.NEGATIVE_INFINITY);
        // Java enums cannot be out of range: a null is the invalid value, and it is ignored.
        dut.setSpeed(null);
        dut.setStandard(null);
        dut.prepare(Double.NaN, 2, 512);
        dut.prepare(48000.0, -3, 512);
        assertSame(TapeMachine.Speed.IPS_15, dut.getSpeed());
        assertSame(TapeMachine.Standard.NAB, dut.getStandard());
        for (final TapeMachine t : new TapeMachine[] { dut, twin })
        {
            t.setSpeed(TapeMachine.Speed.IPS_3_75);
            t.setStandard(TapeMachine.Standard.CCIR);
        }
        assertEquals(6.0, dut.getDrive(), 1e-6);
        assertEquals(0.5, dut.getBias(), 1e-6);
        assertEquals(0.15, dut.getWowFlutter(), 1e-6);
        assertEquals(1.0, dut.getMix(), 1e-6);

        final double[][] ta = new double[2][512];
        final double[][] tb = new double[2][512];
        double maxDiff = 0.0;
        int nonFinite = 0;
        for (int k = 0; k < 25; ++k)
        {
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < 512; ++i)
                {
                    final float v = 0.7f * (float) Math.sin(2.0f * 3.14159265f * (180.0f + 40.0f * ch)
                                                            * (float) (k * 512 + i) / 48000.0f);
                    ta[ch][i] = v;
                    tb[ch][i] = v;
                }
            dut.process(ta, 512);
            twin.process(tb, 512);
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < 512; ++i)
                {
                    if (!Double.isFinite(ta[ch][i])) ++nonFinite;
                    maxDiff = Math.max(maxDiff, Math.abs(ta[ch][i] - tb[ch][i]));
                }
        }
        assertEquals(0, nonFinite);
        assertEquals(0.0, maxDiff);
    }

    @Test
    @DisplayName("TapeMachine_oversampling_configurable_and_reported")
    void oversamplingConfigurableAndReported()
    {
        final TapeMachine t = new TapeMachine();
        t.prepare(48000.0, 2, 512);
        final int base4x = t.getLatency();
        t.setOversampling(1);
        final int l1 = t.getLatency();
        assertEquals(1, t.getOversamplingFactor());
        t.setOversampling(2);
        final int l2 = t.getLatency();
        assertEquals(2, t.getOversamplingFactor());
        t.setOversampling(4);
        final int l4 = t.getLatency();
        assertEquals(4, t.getOversamplingFactor());
        t.setOversampling(8);
        final int l8 = t.getLatency();
        assertEquals(8, t.getOversamplingFactor());
        assertEquals(base4x, l4);
        assertTrue(l1 < l2 && l2 < l4 && l4 < l8, l1 + " " + l2 + " " + l4 + " " + l8);
        assertEquals(t.getLatency(), t.getLatencySamples());
        t.setOversampling(3);
        t.setOversampling(0);
        t.setOversampling(-4);
        t.setOversampling(32);
        assertEquals(8, t.getOversamplingFactor());
        System.out.printf("  [tape] latency at 48 kHz: 1x %d, 2x %d, 4x %d, 8x %d%n", l1, l2, l4, l8);
    }

    @Test
    @DisplayName("TapeMachine_survives_nonfinite_input")
    void survivesNonfiniteInput()
    {
        final TapeMachine t = new TapeMachine();
        t.prepare(48000.0, 1, 512);
        t.setWowFlutter(0.0);
        final double[][] p = { sine220() };
        p[0][64] = Double.NaN;
        p[0][192] = Double.POSITIVE_INFINITY;
        p[0][300] = Double.NEGATIVE_INFINITY;
        t.process(p, 512);
        double energy = 0.0;
        boolean finite = true;
        for (int blk = 0; blk < 60; ++blk)
        {
            final double[][] b = { sine220() };
            t.process(b, 512);
            if (blk >= 55)
                for (int i = 0; i < 512; ++i)
                {
                    if (!Double.isFinite(b[0][i])) finite = false;
                    energy += b[0][i] * b[0][i];
                }
        }
        assertTrue(finite);
        assertTrue(energy > 1e-3, "recovered signal, not stuck silence: " + energy);
    }

    /** The C++ generateSine(buf, 512, 220, 48000, 0.3), in float. */
    private static double[] sine220()
    {
        final double[] v = new double[512];
        final float inc = (float) (2.0 * Math.PI) * 220.0f / 48000.0f;
        for (int i = 0; i < 512; ++i) v[i] = 0.3f * (float) Math.sin(inc * (float) i);
        return v;
    }

    // -- Properties ---------------------------------------------------------------------

    /** A stereo programme: pink noise and a low tone on the left, a different noise and a high tone on the right. */
    private static double[][] programme(int n)
    {
        final double[] l = TapeSignals.sat8Pink(n, -18.0, 7);
        final double[] r = TapeSignals.sat8Pink(n, -20.0, 99);
        for (int i = 0; i < n; ++i)
        {
            l[i] += 0.2 * Math.sin(2.0 * Math.PI * 110.0 * i / 48000.0);
            r[i] += 0.1 * Math.sin(2.0 * Math.PI * 5300.0 * i / 48000.0);
        }
        return new double[][] { l, r };
    }

    /** Renders a stereo signal in blocks of a given size. */
    private static double[][] renderBlocks(TapeMachine t, double[][] in, int block)
    {
        final double[][] out = { in[0].clone(), in[1].clone() };
        final double[][] buf = new double[2][block];
        for (int off = 0; off < out[0].length; off += block)
        {
            final int n = Math.min(block, out[0].length - off);
            for (int ch = 0; ch < 2; ++ch) System.arraycopy(out[ch], off, buf[ch], 0, n);
            t.process(buf, n);
            for (int ch = 0; ch < 2; ++ch) System.arraycopy(buf[ch], 0, out[ch], off, n);
        }
        return out;
    }

    private static TapeMachine busyMachine(int variant)
    {
        final TapeMachine t = new TapeMachine();
        t.setDrive(9.0);
        t.setNoise(-60.0);
        t.setChannelBleed(-40.0);
        t.setEvenHarmonics(0.5);
        t.setOutputIron(0.7);
        t.setAlignmentTolerance(0.8);
        t.setMix(0.8);
        if (variant == 1)
        {
            t.setElectronics(TapeMachine.Electronics.CIRCUIT);
            t.setHeadModel(TapeMachine.HeadModel.CONTOUR);
            t.setTransport(TapeMachine.Transport.SPEED);
            t.setBias(0.8);
            t.setOversamplingMode(TapeMachine.OversamplingMode.MINIMUM_PHASE);
        }
        if (variant == 2)
        {
            t.setBias(0.2);
            t.setOversampling(1);
            t.setSpeed(TapeMachine.Speed.IPS_3_75);
            t.setStandard(TapeMachine.Standard.CCIR);
        }
        t.prepare(48000.0, 2, 4096);
        return t;
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 samples give the same output, to the bit")
    void blockSizesGiveTheSameOutput()
    {
        final double[][] in = programme(12000);
        for (int variant = 0; variant < 3; ++variant)
        {
            final double[][] ref = renderBlocks(busyMachine(variant), in, 4096);
            for (final int block : new int[] { 1, 37, 512 })
            {
                final double[][] got = renderBlocks(busyMachine(variant), in, block);
                assertArrayEquals(ref[0], got[0], 0.0, "variant " + variant + ", block " + block);
                assertArrayEquals(ref[1], got[1], 0.0, "variant " + variant + ", block " + block);
            }
            for (final double[] c : ref)
                for (final double v : c) assertTrue(Double.isFinite(v));
        }
    }

    @Test
    @DisplayName("reset() gives the output of a fresh instance, to the bit")
    void resetEqualsFreshInstance()
    {
        final double[][] in = programme(9600);
        for (int variant = 0; variant < 2; ++variant)
        {
            final TapeMachine used = busyMachine(variant);
            renderBlocks(used, programme(7000), 512);
            used.reset();
            final double[][] got = renderBlocks(used, in, 512);
            final double[][] want = renderBlocks(busyMachine(variant), in, 512);
            assertArrayEquals(want[0], got[0], 0.0);
            assertArrayEquals(want[1], got[1], 0.0);
        }
    }

    @Test
    @DisplayName("Loud, DC and full-scale square input stay finite and bounded")
    void extremeInputStaysFinite()
    {
        final TapeMachine t = new TapeMachine();
        t.setDrive(24.0);
        t.setBias(0.0);
        t.prepare(48000.0, 2, 512);
        final double[][] buf = new double[2][512];
        double peak = 0.0;
        for (int k = 0; k < 60; ++k)
        {
            for (int i = 0; i < 512; ++i)
            {
                buf[0][i] = ((k * 512 + i) / 40 % 2 == 0) ? 4.0 : -4.0;
                buf[1][i] = 1.0;
            }
            t.process(buf, 512);
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < 512; ++i)
                {
                    assertTrue(Double.isFinite(buf[ch][i]));
                    peak = Math.max(peak, Math.abs(buf[ch][i]));
                }
        }
        assertTrue(peak < 20.0, "peak " + peak);
    }

    @Test
    @DisplayName("An unprepared instance passes audio through; channels beyond the prepared count pass untouched")
    void passThroughOutsideThePreparedChannels()
    {
        final TapeMachine t = new TapeMachine();
        final double[][] a = { { 0.1, -0.2, 0.3 } };
        t.process(a, 3);
        assertArrayEquals(new double[] { 0.1, -0.2, 0.3 }, a[0], 0.0);
        t.prepare(48000.0, 1, 64);
        final double[][] b = new double[2][64];
        for (int i = 0; i < 64; ++i)
        {
            b[0][i] = 0.1 * Math.sin(i * 0.3);
            b[1][i] = 0.1 * Math.sin(i * 0.3);
        }
        final double[] second = b[1].clone();
        t.process(b, 64);
        assertArrayEquals(second, b[1], 0.0);
    }
}
