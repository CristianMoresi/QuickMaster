package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TransformerModel}: every TransformerModel case of the C++
 * suite (TestEffectsTone.cpp, TestAnalogAudit.cpp, TestEffectsAdvanced.cpp,
 * TestState.cpp), the figures of the header and the common properties.
 */
class TransformerModelTest
{
    private static final double PI_TEST = 3.14159265358979;
    private static final double TWO_PI = 2.0 * Math.PI;

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static TransformerModel prepared(double fs, int block, int ch)
    {
        final TransformerModel tr = new TransformerModel();
        tr.prepare(fs, ch, block);
        return tr;
    }

    /** Processes whole channels in blocks of {@code block} samples. */
    static void run(TransformerModel tr, double[][] x, int block)
    {
        final int n = x[0].length;
        for (int off = 0; off < n; off += block)
            tr.process(x, off, Math.min(block, n - off));
    }

    private static double toDb(double x) { return 20.0 * Math.log10(Math.max(x, 1e-30)); }

    /** Coherent amplitude of frequency f over x[from, from + n) (TestAnalogAudit.cpp toneAmplitude). */
    static double toneAmplitude(double[] x, int from, int n, double f, double fs)
    {
        double re = 0.0;
        double im = 0.0;
        for (int i = 0; i < n; i++)
        {
            final double w = TWO_PI * f * i / fs;
            re += x[from + i] * Math.cos(w);
            im += x[from + i] * Math.sin(w);
        }
        return 2.0 * Math.sqrt(re * re + im * im) / n;
    }

    /** A mono sine run through a processor in blocks, the phase carried in t (TestAnalogAudit.cpp runSine). */
    private static double[] runSine(TransformerModel p, double fs, int block, int total, double f, double amp, long[] t)
    {
        final double[][] buf = new double[1][block];
        final double[] out = new double[total];
        int w = 0;
        while (w < total)
        {
            for (int i = 0; i < block; i++)
                buf[0][i] = (float) (amp * Math.sin(TWO_PI * f * (double) (t[0]++) / fs));
            p.process(buf, block);
            for (int i = 0; i < block && w < total; i++) out[w++] = buf[0][i];
        }
        return out;
    }

    /** THD (harmonics 2 to 5 over the fundamental) of a stereo sine at 48 kHz, drive set after prepare(). */
    private static double xfmrThdAt(double freq, double amp, double drive)
    {
        final TransformerModel tr = prepared(48000.0, 512, 2);
        tr.setDrive(drive);
        tr.setResonance(0.0);

        final int total = (48000 / 512) * 512;
        final double[] out = new double[total];
        final double[][] buf = new double[2][512];
        int n = 0;
        for (int b = 0; b < total / 512; b++)
        {
            for (int i = 0; i < 512; i++, n++)
            {
                buf[0][i] = amp * (float) Math.sin(2.0 * PI_TEST * freq * n / 48000.0);
                buf[1][i] = buf[0][i];
            }
            tr.process(buf, 512);
            System.arraycopy(buf[0], 0, out, b * 512, 512);
        }
        final double h1 = bin(out, out.length / 2, freq);
        double harm = 0.0;
        for (int k = 2; k <= 5; k++)
        {
            final double h = bin(out, out.length / 2, freq * k);
            harm += h * h;
        }
        return Math.sqrt(harm) / h1;
    }

    /** Magnitude of the correlation with a tone of f from sample {@code from} to the end, at 48 kHz. */
    private static double bin(double[] out, int from, double f)
    {
        double re = 0.0;
        double im = 0.0;
        for (int i = from; i < out.length; i++)
        {
            final double ph = 2.0 * PI_TEST * f * (double) (i - from) / 48000.0;
            re += out[i] * Math.cos(ph);
            im += out[i] * Math.sin(ph);
        }
        return Math.sqrt(re * re + im * im);
    }

    /** Pink-weighted multitone (one tone per octave, 100 to 6400 Hz): programme-like (TestEffectsTone.cpp analogProgram). */
    private static double analogProgram(int n, float amp)
    {
        final double[] f = { 100, 200, 400, 800, 1600, 3200, 6400 };
        double s = 0.0;
        for (int k = 0; k < 7; k++)
            s += Math.sin(2.0 * PI_TEST * f[k] * n / 48000.0 + k * 1.7);
        return amp * (float) (s / 2.6457513);
    }

    /** The 1 kHz test tone of analogWorstDelta, in float as the C++. */
    private static float tone1k(int n)
    {
        return 0.25f * (float) Math.sin(2.0f * 3.14159265f * 1000.0f * (float) n / 48000.0f);
    }

    /** Worst sample-to-sample delta of a 1 kHz tone with the drive held or dragged (TestEffectsTone.cpp analogWorstDelta). */
    private static double analogWorstDelta(TransformerModel fx, float dbFrom, float dbTo, boolean drag)
    {
        if (drag) return worstDeltaPass(fx, dbFrom, dbFrom, dbTo, true);
        double worst = 0.0;
        for (float db : new float[] { dbFrom, 0.5f * (dbFrom + dbTo), dbTo })
            worst = Math.max(worst, worstDeltaPass(fx, db, dbFrom, dbTo, false));
        return worst;
    }

    private static double worstDeltaPass(TransformerModel fx, float fixedDb, float dbFrom, float dbTo, boolean drag)
    {
        final double[][] buf = new double[2][512];
        fx.reset();
        fx.setDrive(fixedDb);
        double md = 0.0;
        double prev = 0.0;
        int n = 0;
        int block = 0;
        final int total = drag ? 96000 : 36000;
        while (n < total)
        {
            if (drag && n >= 24000)
            {
                final float t = (float) (block * 0.02f % 2.0f);
                final float x = t < 1.0f ? t : 2.0f - t;
                fx.setDrive(dbFrom + (dbTo - dbFrom) * x);
            }
            for (int i = 0; i < 512; i++)
            {
                buf[0][i] = tone1k(n + i);
                buf[1][i] = buf[0][i];
            }
            fx.process(buf, 512);
            for (int i = 0; i < 512; i++)
            {
                final double v = buf[0][i];
                if (n + i > 9600) md = Math.max(md, Math.abs(v - prev));
                prev = v;
            }
            n += 512;
            ++block;
        }
        return md;
    }

    // ------------------------------------------------------------------------
    // TestEffectsTone.cpp
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Distortion is LF weighted (TransformerModel_distortion_is_LF_weighted)")
    void distortionIsLfWeighted()
    {
        // Core saturation acts on flux (integral of voltage): at equal level,
        // distortion concentrates in the low end.
        final double t60 = xfmrThdAt(60.0, 0.5, 6.0);
        final double t1k = xfmrThdAt(1000.0, 0.5, 6.0);
        System.out.printf("  THD at +6 dB drive, -6 dBFS: 60 Hz %.4f, 1 kHz %.5f%n", t60, t1k);
        assertTrue(t60 > 5.0 * t1k, "t60 " + t60 + " t1k " + t1k);
        assertTrue(t60 > 0.03, "audible iron at +6 dB drive: " + t60);
        assertTrue(t60 < 0.5, "but not broken: " + t60);
    }

    @Test
    @DisplayName("Programme level is transparent (TransformerModel_program_level_transparent)")
    void programLevelTransparent()
    {
        final TransformerModel tr = prepared(48000.0, 512, 2);
        tr.setDrive(-12.0);
        tr.setCoreSize(1.0);
        tr.setResonance(0.0);

        final double[][] buf = new double[2][512];
        final int total = (48000 / 512) * 512;
        final double[] out = new double[total];
        int n = 0;
        for (int b = 0; b < total / 512; b++)
        {
            for (int i = 0; i < 512; i++, n++)
            {
                buf[0][i] = tone1k(n);
                buf[1][i] = buf[0][i];
            }
            tr.process(buf, 512);
            System.arraycopy(buf[0], 0, out, b * 512, 512);
        }
        final int from = out.length / 2;
        final double g = 2.0 * bin(out, from, 1000.0) / (double) (out.length - from) / 0.25;
        System.out.printf("  programme-level 1 kHz gain at -12 dB drive, big core: %.3f dB%n", 20.0 * Math.log10(g));
        assertEquals(0.0, 20.0 * Math.log10(g), 2.0);
    }

    /** Level in dB of the 10.1 kHz tone's third folded to 17.7 kHz, at a factor (and the latency into lat[0]). */
    static double foldedDb(int factor, int[] lat)
    {
        final TransformerModel tr = new TransformerModel();
        tr.setOversampling(factor);
        tr.prepare(48000.0, 1, 512);
        tr.setDrive(24.0);
        tr.setCoreSize(0.0);
        lat[0] = tr.getLatency();
        final double[][] x = new double[1][96000];
        for (int i = 0; i < 96000; i++)
            x[0][i] = 0.5 * Math.sin(2.0 * PI_TEST * 10103.0 * (double) i / 48000.0);
        run(tr, x, 512);
        return 20.0 * Math.log10(bin(x[0], 48000, 17691.0) / bin(x[0], 48000, 10103.0));
    }

    @Test
    @DisplayName("Oversampling keeps folded harmonics out (TransformerModel_oversampling_keeps_folded_harmonics_out)")
    void oversamplingKeepsFoldedHarmonicsOut()
    {
        // The third of a 10.1 kHz tone is at 30.3 kHz: at 1x it folds to
        // 17.7 kHz, at the 2x default the downsampler removes it.
        final int[] lat1 = { -1 };
        final int[] lat2 = { -1 };
        final double at1x = foldedDb(1, lat1);
        final double at2x = foldedDb(2, lat2);
        System.out.printf("  10.1 kHz third folded to 17.7 kHz: %.2f dB at 1x (latency %d), %.2f dB at 2x (latency %d)%n",
                at1x, lat1[0], at2x, lat2[0]);
        assertTrue(at1x > -45.0, "the defect the default removes: " + at1x);
        assertTrue(at2x < -90.0, "at 2x: " + at2x);
        assertEquals(0, lat1[0]);
        assertTrue(lat2[0] > 0);
        // The header's figure for that tone at 1x and +24 dB is -37.5 dB,
        // measured on the core's earlier plain-trapezoid integration (a
        // transcription of that core in this chain gives -37.51 dB); the
        // alpha-transform core of Hysteresis gives -37.19 dB.
        assertEquals(-37.5, at1x, 0.35);
        assertEquals(-37.19, at1x, 0.02);
    }

    @Test
    @DisplayName("Folded third of the 10.1 kHz tone at the default drive, 1x (header: -61.3 dB)")
    void foldedThirdAtDefaultDrive()
    {
        final TransformerModel tr = new TransformerModel();
        tr.setOversampling(1);
        tr.setCoreSize(0.0);
        tr.prepare(48000.0, 1, 512);
        final double[][] x = new double[1][96000];
        for (int i = 0; i < 96000; i++)
            x[0][i] = 0.5 * Math.sin(2.0 * PI_TEST * 10103.0 * (double) i / 48000.0);
        run(tr, x, 512);
        final double db = 20.0 * Math.log10(bin(x[0], 48000, 17691.0) / bin(x[0], 48000, 10103.0));
        System.out.printf("  10.1 kHz third folded to 17.7 kHz at the default drive, 1x: %.2f dB%n", db);
        // Header: -61.3 dB on the plain-trapezoid core (-61.37 dB transcribed);
        // the alpha-transform core gives -61.03 dB.
        assertEquals(-61.3, db, 0.35);
    }

    @Test
    @DisplayName("Fully dry, the output is the input delayed by getLatency() (TransformerModel_dry_path_matches_the_reported_latency)")
    void dryPathMatchesTheReportedLatency()
    {
        for (int factor : new int[] { 1, 2, 4 })
        {
            final TransformerModel tr = new TransformerModel();
            tr.setOversampling(factor);
            tr.setMix(0.0);   // before prepare(), which seeds the mix ramp at its target
            tr.prepare(48000.0, 1, 256);
            final int lat = tr.getLatency();
            final double[] x = new double[4000];
            for (int i = 0; i < x.length; i++) x[i] = 0.3f * (float) Math.sin(0.037f * (float) i);
            final double[][] y = { x.clone() };
            int off = 0;
            for (int len : new int[] { 1, 700, 256, 33, 2000, 1010 })   // 700 and 2000 exceed the prepared maximum
            {
                tr.process(y, off, len);
                off += len;
            }
            double worst = 0.0;
            for (int i = lat; i < x.length; i++)
                worst = Math.max(worst, Math.abs(y[0][i] - x[i - lat]));
            assertTrue(worst < 1e-7, "factor " + factor + ": " + worst);
            assertEquals(0.0, worst, "to the bit at factor " + factor);
            assertEquals(factor, tr.getOversamplingFactor());
        }

        final TransformerModel b = new TransformerModel();
        b.prepare(48000.0, 2, 512);
        b.setOversampling(4);
        assertEquals(4, b.getOversamplingFactor());
        assertEquals(b.getLatency(), b.getLatencySamples());
    }

    /** Coherent magnitude of a 0.03 sine of hz through one instance at a mix (the second half of 96000 samples). */
    private static double mixLine(double size, double amount, double hz)
    {
        final int n = 96000;
        final TransformerModel tr = new TransformerModel();
        tr.setCoreSize(size);
        tr.setMix(amount);
        tr.prepare(48000.0, 1, 256);
        final double[][] x = new double[1][n];
        for (int i = 0; i < n; i++)
            x[0][i] = (float) (0.03 * Math.sin(6.283185307179586476925 * hz * (double) i / 48000.0));
        run(tr, x, 256);
        double re = 0.0;
        double im = 0.0;
        for (int i = n / 2; i < n; i++)
        {
            re += x[0][i] * Math.cos(6.283185307179586476925 * hz * (double) i / 48000.0);
            im += x[0][i] * Math.sin(6.283185307179586476925 * hz * (double) i / 48000.0);
        }
        return Math.hypot(re, im);
    }

    @Test
    @DisplayName("A partial mix keeps the phase of the magnetizing corner (TransformerModel_mix_keeps_the_phase_of_the_magnetizing_corner)")
    void mixKeepsThePhaseOfTheMagnetizingCorner()
    {
        double worst = 0.0;
        for (double size : new double[] { 0.0, 0.5 })
            for (double hz : new double[] { 25.0, 40.0, 100.0 })
            {
                // The ends are deterministic: computed once per size and frequency.
                final double dry = mixLine(size, 0.0, hz);
                final double wet = mixLine(size, 1.0, hz);
                for (double mix : new double[] { 0.25, 0.5, 0.75 })
                {
                    final double inPhase = (1.0 - mix) * dry + mix * wet;
                    final double db = 20.0 * Math.log10(mixLine(size, mix, hz) / inPhase);
                    worst = Math.min(worst, db);
                    assertTrue(db > -0.06, "size " + size + " mix " + mix + " " + hz + " Hz: " + db);
                }
            }
        System.out.printf("  worst partial-mix loss against an in-phase sum: %.4f dB%n", worst);
    }

    @Test
    @DisplayName("Stable under bursts (TransformerModel_stable_under_bursts)")
    void stableUnderBursts()
    {
        final TransformerModel tr = prepared(48000.0, 512, 2);
        tr.setDrive(24.0);
        final double[][] buf = new double[2][512];
        int rng = 5;
        for (int b = 0; b < 100; b++)
        {
            for (int i = 0; i < 512; i++)
            {
                rng = rng * 1664525 + 1013904223;
                buf[0][i] = (b / 10) % 2 != 0 ? 0.0 : ((float) (rng >>> 8) / 8388608.0f - 1.0f);
                buf[1][i] = buf[0][i];
            }
            tr.process(buf, 512);
            for (int i = 0; i < 512; i++)
                assertTrue(Double.isFinite(buf[0][i]), "block " + b + " sample " + i);
        }
    }

    @Test
    @DisplayName("Insertion loudness is neutral (TransformerModel_insertion_loudness_is_neutral)")
    void insertionLoudnessIsNeutral()
    {
        final TransformerModel tr = prepared(48000.0, 512, 2);
        final double[][] buf = new double[2][512];
        final int total = 96000;
        final int measFrom = 48000;
        double inSq = 0.0;
        double outSq = 0.0;
        int n = 0;
        while (n < total)
        {
            for (int i = 0; i < 512; i++)
            {
                buf[0][i] = analogProgram(n + i, 0.1f);
                buf[1][i] = buf[0][i];
            }
            tr.process(buf, 512);
            for (int i = 0; i < 512; i++)
                if (n + i >= measFrom)
                {
                    final double s = analogProgram(n + i, 0.1f);
                    inSq += s * s;
                    outSq += buf[0][i] * buf[0][i];
                }
            n += 512;
        }
        final double db = 10.0 * Math.log10(outSq / Math.max(inSq, 1e-30));
        System.out.printf("  insertion loudness at the defaults: %.3f dB%n", db);
        assertEquals(0.0, db, 3.0);
    }

    @Test
    @DisplayName("Dragging the drive is click free (TransformerModel_drive_drag_is_click_free)")
    void driveDragIsClickFree()
    {
        // The model differentiates its output: a gain step would click, and a
        // linear ramp of the (hScale, mScale) pair would pass through
        // over-gained states. The geometric ramps hold the drag at the static
        // behaviour.
        final TransformerModel tr = prepared(48000.0, 512, 2);
        final double stat = analogWorstDelta(tr, -12.0f, 24.0f, false);
        final double drag = analogWorstDelta(tr, -12.0f, 24.0f, true);
        System.out.printf("  worst delta: static %.5f, drag %.5f%n", stat, drag);
        assertTrue(drag < 2.0 * stat + 1e-4, "static " + stat + " drag " + drag);
    }

    @Test
    @DisplayName("Invalid inputs are ignored (TransformerModel_invalid_inputs_are_ignored)")
    void invalidInputsAreIgnored()
    {
        final TransformerModel dut = new TransformerModel();
        final TransformerModel twin = new TransformerModel();
        for (TransformerModel t : new TransformerModel[] { dut, twin })
        {
            t.setDrive(6.0);
            t.prepare(48000.0, 2, 512);
        }

        dut.setDrive(Double.NaN);
        dut.setDrive(Double.POSITIVE_INFINITY);
        dut.setCoreSize(Double.NaN);
        dut.setResonance(Double.NaN);
        dut.setMix(Double.NEGATIVE_INFINITY);
        dut.prepare(Double.NaN, 2, 512);
        dut.prepare(Double.POSITIVE_INFINITY, 2, 512);
        dut.prepare(48000.0, -3, 512);
        dut.prepare(48000.0, 2, 0);
        dut.setOversampling(3);
        dut.setOversampling(32);
        dut.setOversampling(0);

        assertEquals(6.0, dut.getDrive(), 1e-6);
        assertEquals(0.5, dut.getCoreSize(), 1e-6);
        assertEquals(0.3, dut.getResonance(), 1e-6);
        assertEquals(1.0, dut.getMix(), 1e-6);
        assertEquals(2, dut.getOversamplingFactor());

        final double[][] ta = new double[2][512];
        final double[][] tb = new double[2][512];
        double maxDiff = 0.0;
        int nonFinite = 0;
        for (int k = 0; k < 25; k++)
        {
            for (int ch = 0; ch < 2; ch++)
                for (int i = 0; i < 512; i++)
                {
                    final double v = 0.4f * (float) Math.sin(2.0f * 3.14159265f * (110.0f + 40.0f * ch)
                            * (float) (k * 512 + i) / 48000.0f);
                    ta[ch][i] = v;
                    tb[ch][i] = v;
                }
            dut.process(ta, 512);
            twin.process(tb, 512);
            for (int ch = 0; ch < 2; ch++)
                for (int i = 0; i < 512; i++)
                {
                    if (!Double.isFinite(ta[ch][i])) ++nonFinite;
                    maxDiff = Math.max(maxDiff, Math.abs(ta[ch][i] - tb[ch][i]));
                }
        }
        assertEquals(0, nonFinite);
        assertEquals(0.0, maxDiff);
    }

    @Test
    @DisplayName("Survives non-finite input (TransformerModel_survives_nonfinite_input)")
    void survivesNonfiniteInput()
    {
        final TransformerModel t = prepared(48000.0, 512, 1);
        t.setDrive(12.0);
        final double inc = TWO_PI * 220.0 / 48000.0;
        final double[][] b1 = new double[1][512];
        for (int i = 0; i < 512; i++) b1[0][i] = 0.3 * Math.sin(inc * i);
        b1[0][100] = Double.NaN;
        b1[0][300] = Double.POSITIVE_INFINITY;
        t.process(b1, 512);
        final double[][] b2 = new double[1][512];
        for (int i = 0; i < 512; i++) b2[0][i] = 0.3 * Math.sin(inc * i);
        t.process(b2, 512);
        for (int i = 0; i < 512; i++)
            assertTrue(Double.isFinite(b2[0][i]), "sample " + i);
        for (int i = 0; i < 512; i++)
            assertTrue(Double.isFinite(b1[0][i]), "poisoned block sample " + i);
    }

    // ------------------------------------------------------------------------
    // TestAnalogAudit.cpp
    // ------------------------------------------------------------------------

    /** Median time in microseconds of {@code blocks} 64-sample stereo calls, optionally moving the drive first. */
    private static double medianBlockMicros(TransformerModel p, int blocks, boolean automate, long[] t)
    {
        final int block = 64;
        final double[][] buf = new double[2][block];
        final double[] times = new double[blocks];
        for (int k = 0; k < blocks; k++)
        {
            for (int i = 0; i < block; i++)
            {
                final double v = (float) (0.25 * Math.sin(TWO_PI * 440.0 * (double) (t[0]++) / 48000.0));
                buf[0][i] = v;
                buf[1][i] = v;
            }
            if (automate) p.setDrive((float) (6.0 * Math.sin(0.1 * k)));
            final long t0 = System.nanoTime();
            p.process(buf, block);
            times[k] = (System.nanoTime() - t0) * 1e-3;
        }
        Arrays.sort(times);
        return times[blocks / 2];
    }

    @Test
    @DisplayName("Drive automation does not spike the callback (AnalogAudit_automation_does_not_spike_the_callback)")
    void automationDoesNotSpikeTheCallback()
    {
        final TransformerModel xf = prepared(48000.0, 64, 2);
        final long[] t = { 0 };
        // JIT warm-up of both paths, then the C++ measurement.
        for (int w = 0; w < 6; w++)
        {
            medianBlockMicros(xf, 400, false, t);
            medianBlockMicros(xf, 400, true, t);
        }
        medianBlockMicros(xf, 200, false, t);
        final double idle = medianBlockMicros(xf, 400, false, t);
        final double busy = medianBlockMicros(xf, 300, true, t);
        System.out.printf("  64-sample stereo block: idle %.2f us, drive automation %.2f us (x%.2f)%n", idle, busy, busy / idle);
        assertTrue(busy / idle < 3.0, "idle " + idle + " busy " + busy);
    }

    @Test
    @DisplayName("reset() keeps the resonance bell (AnalogAudit_transformer_reset_keeps_resonance)")
    void resetKeepsResonance()
    {
        final double fs = 48000.0;
        final TransformerModel xf = new TransformerModel();
        xf.setResonance(1.0);
        xf.prepare(fs, 1, 512);
        final long[] t = { 0 };
        final double[] a = runSine(xf, fs, 512, 48000, 15000.0, 0.05, t);
        xf.reset();
        final double[] b = runSine(xf, fs, 512, 48000, 15000.0, 0.05, t);
        final double ga = toneAmplitude(a, 24000, 24000, 15000.0, fs);
        final double gb = toneAmplitude(b, 24000, 24000, 15000.0, fs);
        assertTrue(Math.abs(toDb(gb / ga)) < 0.05, "before " + ga + " after " + gb);
    }

    // ------------------------------------------------------------------------
    // TestEffectsAdvanced.cpp
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("The mix ramps over 20 ms with small blocks (Wet_dry_mix_ramps_over_20_ms_with_small_blocks)")
    void wetDryMixRampsOver20MsWithSmallBlocks()
    {
        final TransformerModel wet = new TransformerModel();
        final TransformerModel dry = new TransformerModel();
        final TransformerModel subject = new TransformerModel();
        for (TransformerModel fx : new TransformerModel[] { wet, dry, subject })
        {
            fx.setDrive(12.0);
            fx.prepare(48000.0, 1, 32);
        }
        dry.setMix(0.0);
        dry.reset();
        final int warm = 32 * 150;
        final double[] in = new double[warm + 32];
        for (int i = 0; i < in.length; i++)
            in[i] = 0.5f * (float) Math.sin(6.2831853f * 220.0f * (float) i / 48000.0f);
        final double[][] a = { in.clone() };
        final double[][] b = { in.clone() };
        final double[][] c = { in.clone() };
        for (int off = 0; off < warm + 32; off += 32)
        {
            if (off == warm) subject.setMix(0.0);   // wet -> dry at a block edge
            wet.process(a, off, 32);
            dry.process(b, off, 32);
            subject.process(c, off, 32);
        }
        double num = 0.0;
        double den = 0.0;
        for (int i = warm + 24; i < warm + 32; i++)
        {
            final double d = a[0][i] - b[0][i];
            num += (c[0][i] - b[0][i]) * d;
            den += d * d;
        }
        final double effective = den > 0.0 ? num / den : -1.0;
        assertTrue(effective > 0.9, "effective mix after one 32-sample block: " + effective);
    }

    // ------------------------------------------------------------------------
    // TestState.cpp (the state blob itself is not part of the port)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Automatic oversampling follows the host rate (Automatic_oversampling_follows_the_host_rate)")
    void automaticOversamplingFollowsTheHostRate()
    {
        final TransformerModel fx = new TransformerModel();
        assertFalse(fx.isOversamplingAutomatic(), "off by default");
        fx.setAutomaticOversampling(true);
        assertTrue(fx.isOversamplingAutomatic());

        final double[][] rates = { { 44100.0, 2 }, { 48000.0, 2 }, { 88200.0, 1 }, { 96000.0, 1 }, { 192000.0, 1 } };
        for (double[] each : rates)
        {
            fx.prepare(each[0], 2, 256);
            assertEquals((int) each[1], fx.getOversamplingFactor(), "rate " + each[0]);
            final double[][] block = new double[2][256];
            for (int pass = 0; pass < 4; pass++)
            {
                for (int ch = 0; ch < 2; ch++)
                    for (int i = 0; i < 256; i++)
                        block[ch][i] = 0.25f * (float) Math.sin(TWO_PI * 1000.0 * i / each[0]);
                fx.process(block, 256);
                for (int ch = 0; ch < 2; ch++)
                    for (int i = 0; i < 256; i++)
                        assertTrue(Double.isFinite(block[ch][i]));
            }
        }

        fx.setOversampling(4);
        assertFalse(fx.isOversamplingAutomatic());
        fx.prepare(96000.0, 2, 256);
        assertEquals(4, fx.getOversamplingFactor());

        // Turned on while prepared, it takes effect at once.
        final TransformerModel late = prepared(96000.0, 256, 2);
        assertEquals(2, late.getOversamplingFactor());
        assertTrue(late.getLatency() > 0);
        late.setAutomaticOversampling(true);
        assertEquals(1, late.getOversamplingFactor());
        assertEquals(0, late.getLatency());
    }

    // ------------------------------------------------------------------------
    // Figures of the header
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Latency: 0 at 1x, 64 samples at 2x, 70 at 4x; automatic at 44.1/48/96 kHz")
    void latencyFigures()
    {
        final TransformerModel fx = new TransformerModel();
        assertEquals(2, fx.getOversamplingFactor(), "2x by default");
        fx.prepare(48000.0, 2, 512);
        assertEquals(64, fx.getLatency());
        fx.setOversampling(4);
        assertEquals(70, fx.getLatency());
        fx.setOversampling(1);
        assertEquals(0, fx.getLatency());
        fx.setOversampling(2);
        assertEquals(64, fx.getLatency());

        final int[] expected = { 64, 64, 0 };
        final double[] rates = { 44100.0, 48000.0, 96000.0 };
        for (int r = 0; r < rates.length; r++)
        {
            final TransformerModel auto = new TransformerModel();
            auto.setAutomaticOversampling(true);
            auto.prepare(rates[r], 2, 512);
            assertEquals(expected[r], auto.getLatency(), "rate " + rates[r]);
            System.out.printf("  automatic oversampling at %.1f kHz: factor %d, latency %d%n",
                    rates[r] / 1000.0, auto.getOversamplingFactor(), auto.getLatency());
        }
    }

    @Test
    @DisplayName("The integrator/differentiator pair cancels the leak exactly; the residue stays within 0.15 dB")
    void linearPartNullTest()
    {
        // The model's linear path with the core replaced by a gain:
        // flux = leak flux + T/2 (x + x1); v = (g flux - leak g flux1) 2/T - rho v1
        // equals g (1 + z^-1) / (1 + rho z^-1) applied to x, for every leak.
        final double rho = 0.974;
        for (double fs : new double[] { 44100.0, 48000.0, 88200.0, 96000.0, 192000.0 })
            for (double size : new double[] { 0.0, 0.5, 1.0 })
            {
                final double corner = 40.0 * Math.pow(0.125, size);
                final double leak = Math.exp(-2.0 * Math.PI * corner / fs);
                final double halfT = 0.5 / fs;
                final double invHalfT = 2.0 * fs;
                final double g = 1234.5;
                double flux = 0.0, vPrev = 0.0, mPrev = 0.0, vPrev2 = 0.0;
                double refX1 = 0.0, refY1 = 0.0;
                double worst = 0.0;
                double peak = 0.0;
                int rng = 77;
                for (int i = 0; i < 20000; i++)
                {
                    rng = rng * 1664525 + 1013904223;
                    final double x = (double) (rng >>> 8) / 8388608.0 - 1.0;
                    final double fluxNew = leak * flux + halfT * (x + vPrev);
                    vPrev = x;
                    final double m = g * fluxNew;
                    final double v = (m - leak * mPrev) * invHalfT - rho * vPrev2;
                    vPrev2 = v;
                    mPrev = m;
                    flux = fluxNew;
                    final double ref = g * (x + refX1) - rho * refY1;
                    refX1 = x;
                    refY1 = ref;
                    worst = Math.max(worst, Math.abs(v - ref));
                    peak = Math.max(peak, Math.abs(ref));
                }
                assertTrue(worst < 1e-9 * peak, fs + " Hz size " + size + ": " + worst / peak);
            }

        // The residue (1 + z^-1) / (1 + rho z^-1) over 20 Hz - 20 kHz at the
        // internal rates of 1x, 2x and 4x at 44.1 and 48 kHz.
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double fs : new double[] { 44100.0, 48000.0, 88200.0, 96000.0, 176400.0, 192000.0 })
            for (double f = 20.0; f <= 20000.0; f *= 1.01)
            {
                final double w = 2.0 * Math.PI * f / fs;
                final double num = Math.hypot(1.0 + Math.cos(w), Math.sin(w));
                final double den = Math.hypot(1.0 + rho * Math.cos(w), rho * Math.sin(w));
                final double db = 20.0 * Math.log10(num / den);
                lo = Math.min(lo, db);
                hi = Math.max(hi, db);
            }
        System.out.printf("  linear residue over 20 Hz - 20 kHz: %.4f to %.4f dB%n", lo, hi);
        assertTrue(lo > -0.15 && hi < 0.15, lo + " .. " + hi);
    }

    // ------------------------------------------------------------------------
    // Common properties
    // ------------------------------------------------------------------------

    /** A deterministic stereo test programme: LF tones, a mid tone and noise. */
    static double[][] testProgramme(int n, double fs, double scale)
    {
        final double[][] x = new double[2][n];
        int rng = 12345;
        for (int i = 0; i < n; i++)
        {
            rng = rng * 1664525 + 1013904223;
            final double noise = (double) (rng >>> 8) / 8388608.0 - 1.0;
            final double t = i / fs;
            final double env = 0.6 + 0.4 * Math.sin(TWO_PI * 0.7 * t);
            final double l = env * (0.35 * Math.sin(TWO_PI * 55.0 * t) + 0.15 * Math.sin(TWO_PI * 220.0 * t + 0.3))
                    + 0.08 * Math.sin(TWO_PI * 2500.0 * t) + 0.05 * noise;
            final double r = env * (0.30 * Math.sin(TWO_PI * 82.4 * t + 1.0) + 0.12 * Math.sin(TWO_PI * 330.0 * t))
                    + 0.08 * Math.sin(TWO_PI * 3700.0 * t + 0.5) - 0.05 * noise;
            x[0][i] = scale * l;
            x[1][i] = scale * r;
        }
        return x;
    }

    private static TransformerModel configured(double fs, int maxBlock, double drive, double size, double res, double mix)
    {
        final TransformerModel tr = new TransformerModel();
        tr.setAutomaticOversampling(true);
        tr.setDrive(drive);
        tr.setCoreSize(size);
        tr.setResonance(res);
        tr.setMix(mix);
        tr.prepare(fs, 2, maxBlock);
        return tr;
    }

    private static double[][] copy(double[][] x)
    {
        final double[][] y = new double[x.length][];
        for (int c = 0; c < x.length; c++) y[c] = x[c].clone();
        return y;
    }

    @Test
    @DisplayName("Processing in blocks of 1, 37, 512 and 4096 samples gives identical output")
    void blockSizeIndependence()
    {
        final int n = 12000;
        final double[][] x = testProgramme(n, 48000.0, 1.0);
        for (double mix : new double[] { 1.0, 0.6 })
            for (int factor : new int[] { 1, 2, 4 })
            {
                double[][] reference = null;
                for (int block : new int[] { 4096, 1, 37, 512 })
                {
                    final TransformerModel tr = new TransformerModel();
                    tr.setOversampling(factor);
                    tr.setDrive(9.0);
                    tr.setCoreSize(0.3);
                    tr.setMix(mix);
                    tr.prepare(48000.0, 2, 4096);
                    final double[][] y = copy(x);
                    run(tr, y, block);
                    if (reference == null) reference = y;
                    else
                        for (int c = 0; c < 2; c++)
                            assertArrayEquals(reference[c], y[c], 0.0, "factor " + factor + " mix " + mix + " block " + block);
                }
            }
    }

    @Test
    @DisplayName("Blocks longer than the prepared maximum are processed in pieces, identically")
    void longBlocksAreSplit()
    {
        final int n = 9000;
        final double[][] x = testProgramme(n, 48000.0, 1.0);
        final TransformerModel a = configured(48000.0, 256, 6.0, 0.5, 0.3, 1.0);
        final TransformerModel b = configured(48000.0, 256, 6.0, 0.5, 0.3, 1.0);
        final double[][] ya = copy(x);
        final double[][] yb = copy(x);
        run(a, ya, 256);
        a.process(new double[2][0], 0);
        b.process(yb, n);
        for (int c = 0; c < 2; c++) assertArrayEquals(ya[c], yb[c], 0.0);
    }

    @Test
    @DisplayName("reset() equals a fresh instance")
    void resetEqualsFreshInstance()
    {
        final double[][] x = testProgramme(20000, 48000.0, 1.0);
        final double[][] other = testProgramme(15000, 48000.0, 0.7);
        for (int factor : new int[] { 1, 2 })
        {
            final TransformerModel used = new TransformerModel();
            final TransformerModel fresh = new TransformerModel();
            for (TransformerModel t : new TransformerModel[] { used, fresh })
            {
                t.setOversampling(factor);
                t.setDrive(12.0);
                t.setCoreSize(0.8);
                t.setResonance(0.7);
                t.setMix(0.8);
                t.prepare(48000.0, 2, 512);
            }
            run(used, copy(other), 512);
            used.reset();
            final double[][] ya = copy(x);
            final double[][] yb = copy(x);
            run(used, ya, 512);
            run(fresh, yb, 512);
            for (int c = 0; c < 2; c++) assertArrayEquals(yb[c], ya[c], 0.0, "factor " + factor);
        }
    }

    @Test
    @DisplayName("Parameters set before prepare() start calibrated: no ramp from the defaults")
    void parametersBeforePrepareStartCalibrated()
    {
        // An instance configured before prepare() and one configured, prepared,
        // then left to settle and reset() produce the same output from the
        // first sample.
        final double[][] x = testProgramme(8000, 48000.0, 1.0);
        final TransformerModel early = configured(48000.0, 512, 18.0, 0.2, 0.6, 1.0);
        final TransformerModel late = new TransformerModel();
        late.setAutomaticOversampling(true);
        late.prepare(48000.0, 2, 512);
        late.setDrive(18.0);
        late.setCoreSize(0.2);
        late.setResonance(0.6);
        run(late, testProgramme(48000, 48000.0, 0.5), 512);   // calibration lands, ramps settle
        late.reset();
        final double[][] ya = copy(x);
        final double[][] yb = copy(x);
        run(early, ya, 512);
        run(late, yb, 512);
        double worst = 0.0;
        for (int c = 0; c < 2; c++)
            for (int i = 0; i < x[c].length; i++)
                worst = Math.max(worst, Math.abs(ya[c][i] - yb[c][i]));
        assertTrue(worst < 1e-9, "worst difference " + worst);

        // And the drive is not a fade from 0 dB: the first stretch of a +18 dB
        // instance differs from a 0 dB one at once.
        final TransformerModel neutral = configured(48000.0, 512, 0.0, 0.2, 0.6, 1.0);
        final double[][] yc = copy(x);
        run(neutral, yc, 512);
        double early500 = 0.0;
        for (int i = 0; i < 500; i++) early500 = Math.max(early500, Math.abs(ya[0][i] - yc[0][i]));
        assertTrue(early500 > 1e-3, "difference over the first 500 samples " + early500);
    }

    @Test
    @DisplayName("Finite output over the whole parameter range, and silence in gives silence out")
    void finiteOutputAndSilence()
    {
        final double[][] x = testProgramme(6000, 48000.0, 1.5);
        for (double drive : new double[] { -12.0, 0.0, 24.0 })
            for (double size : new double[] { 0.0, 1.0 })
                for (double res : new double[] { 0.0, 1.0 })
                    for (int factor : new int[] { 1, 2, 16 })
                    {
                        final TransformerModel tr = new TransformerModel();
                        tr.setOversampling(factor);
                        tr.setDrive(drive);
                        tr.setCoreSize(size);
                        tr.setResonance(res);
                        tr.prepare(44100.0, 2, 512);
                        final double[][] y = copy(x);
                        run(tr, y, 512);
                        for (int c = 0; c < 2; c++)
                            for (double v : y[c])
                                assertTrue(Double.isFinite(v) && Math.abs(v) < 20.0, "drive " + drive + " size " + size + " res " + res + " factor " + factor + ": " + v);
                    }

        final TransformerModel tr = prepared(48000.0, 512, 2);
        final double[][] silence = new double[2][4096];
        run(tr, silence, 512);
        for (int c = 0; c < 2; c++)
            for (double v : silence[c]) assertEquals(0.0, v);
    }

    @Test
    @DisplayName("Unprepared passes through; channels beyond the prepared count pass untouched")
    void passThroughCases()
    {
        final double[][] x = testProgramme(1000, 48000.0, 1.0);
        final double[][] y = copy(x);
        new TransformerModel().process(y, 1000);
        for (int c = 0; c < 2; c++) assertArrayEquals(x[c], y[c], 0.0);

        final TransformerModel mono = prepared(48000.0, 256, 1);
        final double[][] z = copy(x);
        run(mono, z, 256);
        assertArrayEquals(x[1], z[1], 0.0);
        assertFalse(Arrays.equals(x[0], z[0]));

        // The oversampler keeps 16 channels: further ones pass untouched.
        final TransformerModel wide = prepared(48000.0, 256, 18);
        final double[][] many = new double[18][];
        for (int c = 0; c < 18; c++) many[c] = x[c & 1].clone();
        run(wide, many, 256);
        assertFalse(Arrays.equals(x[1], many[15]));
        assertArrayEquals(x[0], many[16], 0.0);
        assertArrayEquals(x[1], many[17], 0.0);
    }

    @Test
    @DisplayName("The reported latency equals the measured delay of the wet path")
    void latencyEqualsMeasuredWetDelay()
    {
        for (double fs : new double[] { 44100.0, 48000.0 })
            for (int factor : new int[] { 1, 2, 4 })
            {
                final TransformerModel tr = new TransformerModel();
                tr.setOversampling(factor);
                tr.setResonance(0.0);
                tr.setDrive(-12.0);
                tr.setCoreSize(1.0);
                tr.prepare(fs, 1, 512);
                final int n = 16384;
                final double[] x = new double[n];
                int rng = 99;
                double lp = 0.0;
                double slow = 0.0;
                for (int i = 0; i < n; i++)
                {
                    rng = rng * 1664525 + 1013904223;
                    final double w = (double) (rng >>> 8) / 8388608.0 - 1.0;
                    // Band-limited noise: a one-pole low-pass near 6 kHz minus one near 300 Hz.
                    lp += 0.55 * (w - lp);
                    slow += 0.04 * (lp - slow);
                    x[i] = 0.05 * (lp - slow);
                }
                final double[][] y = { x.clone() };
                run(tr, y, 512);
                int best = -1;
                double bestCorr = -Double.MAX_VALUE;
                for (int lag = 0; lag <= 100; lag++)
                {
                    double c = 0.0;
                    for (int i = 4000; i < n - 200; i++) c += x[i] * y[0][i + lag];
                    if (c > bestCorr)
                    {
                        bestCorr = c;
                        best = lag;
                    }
                }
                assertEquals(tr.getLatency(), best, fs + " Hz factor " + factor);
            }
    }
}
