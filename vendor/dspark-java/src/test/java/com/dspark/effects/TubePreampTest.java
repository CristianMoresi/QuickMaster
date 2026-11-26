package com.dspark.effects;

import com.dspark.core.Oversampling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.dspark.effects.TubeTestSupport.TWO_PI;
import static com.dspark.effects.TubeTestSupport.analogInsertionDb;
import static com.dspark.effects.TubeTestSupport.analogWorstDelta;
import static com.dspark.effects.TubeTestSupport.maxStep;
import static com.dspark.effects.TubeTestSupport.renderMono;
import static com.dspark.effects.TubeTestSupport.runSine;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of {@link TubePreamp}: the C++ suite's TubePreamp assertions
 * (TestEffectsTone.cpp, TestAnalogAudit.cpp, TestEffectsAdvanced.cpp,
 * TestState.cpp) on the double port, the documented latencies and the
 * common properties. The C++ tests that run TubePreamp&lt;float&gt; feed the
 * same float-rounded signals here.
 */
class TubePreampTest
{
    private static TubePreamp prepared(double rate, int channels, int block)
    {
        final TubePreamp amp = new TubePreamp();
        amp.prepare(rate, channels, block);
        return amp;
    }

    private static boolean allFinite(double[] x)
    {
        for (double v : x) if (!Double.isFinite(v)) return false;
        return true;
    }

    private static float fsin(float x)
    {
        return (float) Math.sin(x);
    }

    @Test
    @DisplayName("Documented latency 0/71/93/94/96 for 1/2/4/8/16x, any stage count")
    void documentedLatencies()
    {
        final int[] factors = { 1, 2, 4, 8, 16 };
        final int[] expected = { 0, 71, 93, 94, 96 };
        for (int k = 0; k < factors.length; ++k)
        {
            final TubePreamp amp = new TubePreamp();
            amp.setOversampling(factors[k]);
            amp.setStages(2);
            amp.prepare(48000.0, 1, 64);
            assertEquals(factors[k], amp.getOversamplingFactor());
            assertEquals(expected[k], amp.getLatency());
            assertEquals(amp.getLatency(), amp.getLatencySamples());
        }
        // Automatic oversampling: 2x at 44.1 and 48 kHz, none at 96 kHz.
        for (double rate : new double[] { 44100.0, 48000.0, 96000.0 })
        {
            final TubePreamp amp = new TubePreamp();
            amp.setAutomaticOversampling(true);
            amp.prepare(rate, 2, 512);
            assertEquals(rate < 88200.0 ? 2 : 1, amp.getOversamplingFactor());
            assertEquals(rate < 88200.0 ? 71 : 0, amp.getLatency());
        }
    }

    @Test
    @DisplayName("TubePreamp_single_stage_is_even_dominant")
    void singleStageIsEvenDominant()
    {
        // Asymmetric triode at moderate drive: H2 above H3.
        final TubePreamp amp = prepared(48000.0, 2, 512);
        amp.setStages(1);
        amp.setSag(0.0);
        amp.setDrive(6.0);
        final double[][] buf = new double[2][512];
        final int total = (48000 / 512) * 512;
        final double[] out = new double[total];
        int n = 0;
        for (int b = 0; b < total / 512; ++b)
        {
            for (int i = 0; i < 512; ++i, ++n)
            {
                buf[0][i] = 0.5f * fsin(2.0f * 3.14159265f * 220.0f * (float) n / 48000.0f);
                buf[1][i] = buf[0][i];
            }
            amp.process(buf, 512);
            for (int i = 0; i < 512; ++i)
                out[b * 512 + i] = (float) buf[0][i];
        }
        final java.util.function.DoubleUnaryOperator hMag = freq -> {
            final int from = out.length / 2;
            double re = 0, im = 0;
            for (int i = from; i < out.length; ++i)
            {
                final double ph = 2.0 * 3.14159265358979 * freq * (double) (i - from) / 48000.0;
                re += out[i] * Math.cos(ph);
                im += out[i] * Math.sin(ph);
            }
            return Math.sqrt(re * re + im * im);
        };
        final double h1 = hMag.applyAsDouble(220.0), h2 = hMag.applyAsDouble(440.0), h3 = hMag.applyAsDouble(660.0);
        assertTrue(h2 > h3);               // triode asymmetry
        assertTrue(h2 / h1 > 0.005);       // audibly warm
        assertTrue(allFinite(out));
    }

    @Test
    @DisplayName("TubePreamp_supply_sags_under_load")
    void supplySagsUnderLoad()
    {
        final TubePreamp amp = prepared(48000.0, 2, 512);
        amp.setStages(2);
        amp.setDrive(12.0);
        amp.setSag(1.0);
        final double[][] buf = new double[2][512];
        final float inc = (float) (2.0 * Math.PI) * 220.0f / 48000.0f;
        for (int b = 0; b < 50; ++b)
        {
            for (int c = 0; c < 2; ++c)
                for (int i = 0; i < 512; ++i)
                    buf[c][i] = 0.5f * fsin(inc * (float) i);
            amp.process(buf, 512);
        }
        assertTrue(amp.getSupplyVoltage() < 270.0);   // B+ drooped from 300 V
        assertTrue(amp.getSupplyVoltage() > 150.0);   // but sane
    }

    @Test
    @DisplayName("TubePreamp_stable_at_max_drive")
    void stableAtMaxDrive()
    {
        final TubePreamp amp = prepared(48000.0, 2, 512);
        amp.setStages(2);
        amp.setDrive(36.0);
        amp.setSag(1.0);
        final double[][] buf = new double[2][512];
        int rng = 77;
        for (int b = 0; b < 100; ++b)
        {
            for (int i = 0; i < 512; ++i)
            {
                rng = rng * 1664525 + 1013904223;
                buf[0][i] = (b / 10) % 2 != 0 ? 0.0f
                    : ((float) (rng >>> 8) / 8388608.0f - 1.0f);
                buf[1][i] = buf[0][i];
            }
            amp.process(buf, 512);
            assertTrue(allFinite(buf[0]));
        }
    }

    @Test
    @DisplayName("TubePreamp_default_rejects_high_gain_foldback")
    void defaultRejectsHighGainFoldback()
    {
        // At 2x a 15 kHz, -6 dBFS tone through two stages at +24 dB: the
        // continuous-time core measures -95.8 / -97.4 / -98.9 dBc in C++.
        for (double sourceFrequency : new double[] { 14000.0, 15000.0, 18000.0 })
        {
            final TubePreamp amp = new TubePreamp();
            amp.setStages(2);
            amp.setDrive(24.0);
            amp.prepare(48000.0, 2, 256);
            final int length = 48000;
            final double[][] audio = new double[2][256];
            final double[] tail = new double[length / 2];
            int tailCount = 0;
            for (int pos = 0; pos < length; pos += 256)
            {
                final int count = Math.min(256, length - pos);
                for (int i = 0; i < count; ++i)
                    audio[0][i] = audio[1][i] = Math.pow(10.0, -6.0 / 20.0)
                        * Math.sin(2.0 * Math.PI * sourceFrequency * (pos + i) / 48000.0);
                amp.process(audio, count);
                for (int i = 0; i < count; ++i)
                    if (pos + i >= length / 2) tail[tailCount++] = audio[0][i];
            }
            final java.util.function.DoubleUnaryOperator amplitude = frequency -> {
                double re = 0.0, im = 0.0;
                for (int i = 0; i < tail.length; ++i)
                {
                    final double phase = 2.0 * Math.PI * frequency * (double) i / 48000.0;
                    re += tail[i] * Math.cos(phase);
                    im += tail[i] * Math.sin(phase);
                }
                return Math.hypot(re, im);
            };
            final double fundamental = amplitude.applyAsDouble(sourceFrequency);
            double folded = 0.0;
            final int spacing = gcd((int) sourceFrequency, 48000);
            for (int frequency = spacing; frequency <= 20000; frequency += spacing)
            {
                final double harmonic = frequency / sourceFrequency;
                if (harmonic >= 1.0 && Math.abs(harmonic - Math.rint(harmonic)) < 1e-9) continue;
                folded = Math.max(folded, amplitude.applyAsDouble(frequency));
            }
            final double dbc = 20.0 * Math.log10(folded / fundamental);
            System.out.printf("foldback %.0f Hz: %.1f dBc%n", sourceFrequency, dbc);
            assertTrue(fundamental > 1.0);
            assertTrue(dbc < -80.0, sourceFrequency + " Hz: " + dbc);
            assertEquals(2, amp.getOversamplingFactor());
            assertEquals(71, amp.getLatency());
        }
    }

    private static int gcd(int a, int b)
    {
        while (b != 0) { final int t = a % b; a = b; b = t; }
        return a;
    }

    @Test
    @DisplayName("TubePreamp_dry_mix_and_stage_changes_keep_reported_latency")
    void dryMixAndStageChangesKeepReportedLatency()
    {
        final int total = 513;
        for (int factor : new int[] { 1, 2, 4, 8, 16 })
        {
            final TubePreamp dry = new TubePreamp(), wet = new TubePreamp(), mixed = new TubePreamp();
            for (TubePreamp amp : new TubePreamp[] { dry, wet, mixed })
            {
                amp.setOversampling(factor);
                amp.prepare(48000.0, 1, 64);
            }
            dry.setMix(0.0);
            mixed.setMix(0.5);
            dry.reset();
            mixed.reset();
            final int latency = dry.getLatency();
            final double[] source = new double[total];
            for (int n = 0; n < total; ++n)
                source[n] = 0.05 * Math.sin(0.7 * n) + (n == 7 ? 0.4 : 0.0);
            final double[][] d = new double[1][37], w = new double[1][37], m = new double[1][37];
            for (int pos = 0; pos < total; pos += 37)
            {
                final int count = Math.min(37, total - pos);
                final int stageCount = (pos / 111) % 2 + 1;
                for (TubePreamp amp : new TubePreamp[] { dry, wet, mixed }) amp.setStages(stageCount);
                for (int j = 0; j < count; ++j)
                    d[0][j] = w[0][j] = m[0][j] = source[pos + j];
                dry.process(d, count);
                wet.process(w, count);
                mixed.process(m, count);
                assertEquals(latency, dry.getLatency());
                assertEquals(latency, wet.getLatency());
                for (int j = 0; j < count; ++j)
                {
                    final double expected = pos + j >= latency ? source[pos + j - latency] : 0.0;
                    assertEquals(expected, d[0][j], 1e-14);
                    assertEquals(0.5 * (expected + w[0][j]), m[0][j], 1e-13);
                }
            }
        }
    }

    @Test
    @DisplayName("TubePreamp_dc_operating_point (independent solve of the circuit equations)")
    void dcOperatingPoint()
    {
        for (int stageCount : new int[] { 1, 2 })
        {
            final TubePreamp amp = new TubePreamp();
            amp.setStages(stageCount);
            amp.setSag(0.3);
            amp.prepare(48000.0, 1, 256);
            final double[][] audio = new double[1][256];
            for (int n = 0; n < 50; ++n)
            {
                Arrays.fill(audio[0], 0.0);
                amp.process(audio, 256);
            }
            // At DC, the cathode is Ip*Rk and the common supply is
            // 300 - stages*Rsag*Ip. Solve that complete circuit independently.
            double lo = 0.0, hi = 0.003;
            for (int n = 0; n < 65; ++n)
            {
                final double ip = 0.5 * (lo + hi);
                final double current = TubePreampDetailTest.referenceTriodeCurrent(
                    300.0 - (101500.0 + stageCount * 12000.0) * ip, -1500.0 * ip);
                if (ip > current) hi = ip;
                else lo = ip;
            }
            final double expectedSupply = 300.0 - stageCount * 12000.0 * (lo + hi) * 0.5;
            assertEquals(expectedSupply, amp.getSupplyVoltage(), 2e-5);
            assertEquals(2, amp.getOversamplingFactor());
            assertEquals(71, amp.getLatency());
            System.out.printf("DC supply, %d stage(s): %.9f V (independent %.9f V)%n", stageCount,
                              amp.getSupplyVoltage(), expectedSupply);
        }
    }

    @Test
    @DisplayName("TubePreamp_insertion_loudness_is_neutral")
    void insertionLoudnessIsNeutral()
    {
        final TubePreamp amp = prepared(48000.0, 2, 512);
        assertEquals(0.0, analogInsertionDb(amp, 0.1f), 3.0);
    }

    private static double levelAt(float driveDb, float amplitude)
    {
        final TubePreamp tp = prepared(48000.0, 2, 512);
        tp.setDrive(driveDb);
        return analogInsertionDb(tp, amplitude);
    }

    @Test
    @DisplayName("TubePreamp_drive_knob_is_alive_and_monotonic")
    void driveKnobIsAliveAndMonotonic()
    {
        final double lo = levelAt(-12.0f, 0.1f);
        final double mid = levelAt(0.0f, 0.1f);
        final double hi = levelAt(24.0f, 0.1f);
        final double max = levelAt(36.0f, 0.1f);
        assertTrue(lo < mid - 1.0);
        assertTrue(hi > mid - 1.0);
        assertTrue(max < mid + 11.0);
        assertTrue(max > mid - 2.0);
        final double hotMid = levelAt(0.0f, 0.32f);
        final double hotMax = levelAt(36.0f, 0.32f);
        assertTrue(hotMax > hotMid - 3.0);
    }

    @Test
    @DisplayName("TubePreamp_default_tone_is_not_overcoloured")
    void defaultToneIsNotOvercoloured()
    {
        double lo = 1e9, hi = -1e9;
        for (double f : new double[] { 100.0, 400.0, 800.0, 1600.0, 3200.0, 6400.0 })
        {
            final TubePreamp amp = prepared(48000.0, 2, 512);
            final double[][] buf = new double[2][512];
            final int total = 96000, measFrom = 48000;
            double inSq = 0, outSq = 0;
            int n = 0;
            while (n < total)
            {
                for (int i = 0; i < 512; ++i)
                {
                    buf[0][i] = 0.0316f * fsin(2.0f * 3.14159265f * (float) f * (n + i) / 48000.0f);
                    buf[1][i] = buf[0][i];
                }
                amp.process(buf, 512);
                for (int i = 0; i < 512; ++i)
                    if (n + i >= measFrom)
                    {
                        final double s = 0.0316 * Math.sin(2.0 * 3.14159265358979 * f * (n + i) / 48000.0);
                        inSq += s * s;
                        final double o = (float) buf[0][i];
                        outSq += o * o;
                    }
                n += 512;
            }
            final double g = 10.0 * Math.log10(outSq / Math.max(inSq, 1e-30));
            lo = Math.min(lo, g);
            hi = Math.max(hi, g);
        }
        assertTrue(hi - lo < 6.0, "peak to valley " + (hi - lo));
    }

    @Test
    @DisplayName("TubePreamp_drive_drag_is_click_free")
    void driveDragIsClickFree()
    {
        final TubePreamp amp = prepared(48000.0, 2, 512);
        final double stat = analogWorstDelta(amp, amp::setDrive, -12.0f, 36.0f, false);
        final double drag = analogWorstDelta(amp, amp::setDrive, -12.0f, 36.0f, true);
        assertTrue(drag < 2.0 * stat + 1e-4, "drag " + drag + " static " + stat);
    }

    @Test
    @DisplayName("TubePreamp_stage_switch_preserves_silence_and_latency")
    void stageSwitchPreservesSilenceAndLatency()
    {
        for (int factor : new int[] { 1, 2, 16 })
            for (double input : new double[] { 0.0, 0.1 })
            {
                final TubePreamp amp = new TubePreamp();
                amp.setOversampling(factor);
                amp.setDrive(36.0);
                amp.setSag(1.0);
                amp.prepare(44100.0, 1, 257);
                final int latency = amp.getLatencySamples();
                final double[][] samples = new double[1][257];
                double peak = 0.0;
                for (int block = input == 0.0 ? 0 : -96; block < 100; ++block)
                {
                    if (block == 8 || block == 10 || block == 48) amp.setStages(2);
                    if (block == 9 || block == 13 || block == 80) amp.setStages(1);
                    Arrays.fill(samples[0], input);
                    amp.process(samples, 257);
                    for (double x : samples[0])
                    {
                        assertTrue(Double.isFinite(x));
                        if (block >= 0) peak = Math.max(peak, Math.abs(x));
                    }
                    assertEquals(latency, amp.getLatencySamples());
                }
                // A topology change must not turn its changed quiescent plate
                // load into a signal.
                assertTrue(peak < (input == 0.0 ? 1e-7 : 1e-6), "factor " + factor + " input " + input + ": " + peak);
            }
    }

    @Test
    @DisplayName("TubePreamp_stage_switch_is_smooth_and_reaches_the_requested_circuit")
    void stageSwitchIsSmoothAndReachesTheRequestedCircuit()
    {
        final int frameCount = 24576, change = 8197, maxBlock = 257;
        final double sampleRate = 48000.0;
        for (double drive : new double[] { -12.0, 36.0 })
        {
            final double[][] output = new double[4][];
            for (int run = 0; run < 4; ++run)
            {
                final double[] samples = new double[frameCount];
                output[run] = samples;
                for (int i = 0; i < frameCount; ++i)
                    samples[i] = 0.2 * Math.sin(2.0 * Math.PI * 40.0 * i / sampleRate);
                final TubePreamp amp = new TubePreamp();
                final int initial = run % 2 + 1;
                amp.setStages(initial);
                amp.setDrive(drive);
                amp.setSag(1.0);
                amp.prepare(sampleRate, 1, maxBlock);
                final double[][] p = { samples };
                int pos = 0;
                while (pos < frameCount)
                {
                    if (run >= 2)
                    {
                        if (pos == change || pos == change + 911) amp.setStages(3 - initial);
                        if (pos == change + 733) amp.setStages(initial);
                    }
                    int count = Math.min(maxBlock, frameCount - pos);
                    for (int event : new int[] { change, change + 733, change + 911 })
                        if (pos < event) count = Math.min(count, event - pos);
                    amp.process(p, pos, count);
                    pos += count;
                }
            }
            double staticDelta = 0.0, movingDelta = 0.0;
            for (int i = change; i < frameCount; ++i)
                for (int mode = 0; mode < 2; ++mode)
                {
                    final double[] fixed = output[mode], moving = output[mode + 2];
                    staticDelta = Math.max(staticDelta, Math.abs(fixed[i] - fixed[i - 1]));
                    movingDelta = Math.max(movingDelta, Math.abs(moving[i] - moving[i - 1]));
                }
            assertTrue(movingDelta < 1.15 * staticDelta + 1e-6, "moving " + movingDelta + " static " + staticDelta);
            for (int mode = 0; mode < 2; ++mode)
            {
                final double[] target = output[1 - mode], moving = output[mode + 2];
                double error = 0.0, energy = 0.0;
                for (int i = frameCount - 2048; i < frameCount; ++i)
                {
                    error += (moving[i] - target[i]) * (moving[i] - target[i]);
                    energy += target[i] * target[i];
                }
                assertTrue(error < energy * 1e-5, "drive " + drive + " mode " + mode);
            }
        }
    }

    @Test
    @DisplayName("TubePreamp_output_automation_follows_the_sample_clock")
    void outputAutomationFollowsTheSampleClock()
    {
        final int frames = 4096, boundary = 173;
        final TubePreamp reference = new TubePreamp(), automated = new TubePreamp();
        for (TubePreamp amp : new TubePreamp[] { reference, automated })
        {
            amp.setOversampling(1);
            amp.setDrive(12.0);
            amp.setOutput(-3.0);
            amp.prepare(48000.0, 1, frames);
        }
        final double[] baseline = new double[frames], output = new double[frames];
        for (int i = 0; i < frames; ++i)
            baseline[i] = output[i] = 0.1 * Math.sin(2.0 * Math.PI * 997.0 * i / 48000.0);
        reference.process(new double[][] { baseline }, frames);
        final double[][] b = { output };
        automated.process(b, 0, boundary);
        automated.setOutput(12.0);
        automated.process(b, boundary, frames - boundary);
        double maximumError = 0.0;
        for (int i = boundary; i < frames; ++i)
        {
            // Independent closed-form response of a 30 ms one-pole in dB.
            final double db = 15.0 * (1.0 - Math.exp(-(i - boundary + 1.0) / (0.030 * 48000.0)));
            final double expected = baseline[i] * Math.pow(10.0, db / 20.0);
            maximumError = Math.max(maximumError, Math.abs(output[i] - expected));
        }
        assertTrue(maximumError < 1e-11, "error " + maximumError);
    }

    @Test
    @DisplayName("TubePreamp_invalid_inputs_are_ignored")
    void invalidInputsAreIgnored()
    {
        final TubePreamp dut = new TubePreamp(), twin = new TubePreamp();
        for (TubePreamp t : new TubePreamp[] { dut, twin })
        {
            t.setDrive(6.0);
            t.setSag(0.3);
            t.prepare(48000.0, 2, 512);
        }
        dut.setDrive(Double.NaN);
        dut.setDrive(Double.POSITIVE_INFINITY);
        dut.setTreble(Double.NaN);
        dut.setBass(Double.NaN);
        dut.setMiddle(Double.NaN);
        dut.setSag(Double.NaN);
        dut.setOutput(Double.NaN);
        dut.setMix(Double.NEGATIVE_INFINITY);
        dut.prepare(Double.NaN, 2, 512);
        dut.prepare(48000.0, -3, 512);
        dut.prepare(48000.0, 2, 0);
        dut.setOversamplingMode(null);
        dut.setGridModel(null);
        dut.setOversampling(3);
        dut.setOversampling(0);
        dut.setOversampling(32);

        assertEquals(6.0, dut.getDrive(), 1e-6);
        assertEquals(0.5, dut.getTreble(), 1e-6);
        assertEquals(0.3, dut.getSag(), 1e-6);
        assertEquals(1.0, dut.getMix(), 1e-6);
        assertEquals(1, dut.getStages());
        assertEquals(2, dut.getOversamplingFactor());

        final double[][] ta = new double[2][512], tb = new double[2][512];
        double maxDiff = 0.0;
        int nonFinite = 0;
        for (int k = 0; k < 25; ++k)
        {
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < 512; ++i)
                {
                    final float v = 0.4f * fsin(2.0f * 3.14159265f * (220.0f + 60.0f * ch)
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
        assertEquals(0.0, maxDiff, 0.0);
        // Setters clamp.
        dut.setDrive(99.0);
        assertEquals(36.0, dut.getDrive(), 0.0);
        dut.setDrive(-99.0);
        assertEquals(-12.0, dut.getDrive(), 0.0);
        dut.setOutput(40.0);
        assertEquals(12.0, dut.getOutput(), 0.0);
        dut.setOutput(-40.0);
        assertEquals(-24.0, dut.getOutput(), 0.0);
        dut.setStages(7);
        assertEquals(2, dut.getStages());
        dut.setStages(-1);
        assertEquals(1, dut.getStages());
        dut.setTreble(2.0);
        assertEquals(1.0, dut.getTreble(), 0.0);
    }

    @Test
    @DisplayName("TubePreamp_oversampling_configurable_and_reported")
    void oversamplingConfigurableAndReported()
    {
        final TubePreamp t = prepared(48000.0, 2, 512);
        t.setOversampling(1);
        final int l1 = t.getLatency();
        assertEquals(1, t.getOversamplingFactor());
        assertEquals(0, l1);
        t.setOversampling(2);
        final int l2 = t.getLatency();
        assertEquals(2, t.getOversamplingFactor());
        t.setOversampling(4);
        final int l4 = t.getLatency();
        assertEquals(4, t.getOversamplingFactor());
        assertTrue(l1 < l2);
        assertTrue(l2 < l4);
        assertEquals(t.getLatency(), t.getLatencySamples());
        t.setOversampling(5);
        t.setOversampling(0);
        assertEquals(4, t.getOversamplingFactor());
    }

    @Test
    @DisplayName("Automatic_oversampling_follows_the_host_rate (TubePreamp)")
    void automaticOversamplingFollowsTheHostRate()
    {
        final TubePreamp fx = new TubePreamp();
        assertFalse(fx.isOversamplingAutomatic());
        fx.setAutomaticOversampling(true);
        assertTrue(fx.isOversamplingAutomatic());
        final double[][] rateFactor = { { 48000.0, 2 }, { 96000.0, 1 } };
        for (double[] each : rateFactor)
        {
            fx.prepare(each[0], 2, 256);
            assertEquals((int) each[1], fx.getOversamplingFactor());
            final double[][] block = new double[2][256];
            final float inc = (float) (2.0 * Math.PI) * 1000.0f / (float) each[0];
            for (int pass = 0; pass < 4; ++pass)
            {
                for (int c = 0; c < 2; ++c)
                    for (int i = 0; i < 256; ++i)
                        block[c][i] = 0.25f * fsin(inc * (float) i);
                fx.process(block, 256);
                assertTrue(allFinite(block[0]) && allFinite(block[1]));
            }
        }
        fx.setOversampling(4);
        assertFalse(fx.isOversamplingAutomatic());
        fx.prepare(96000.0, 2, 256);
        assertEquals(4, fx.getOversamplingFactor());
        // Turned on after prepare(), it takes effect at once.
        final TubePreamp late = prepared(96000.0, 2, 256);
        assertEquals(2, late.getOversamplingFactor());
        late.setAutomaticOversampling(true);
        assertEquals(1, late.getOversamplingFactor());
        assertEquals(0, late.getLatency());
    }

    @Test
    @DisplayName("TubePreamp_survives_nonfinite_input")
    void survivesNonfiniteInput()
    {
        final TubePreamp t = prepared(48000.0, 1, 512);
        t.setDrive(6.0);
        final double[][] p = new double[1][512];
        final float inc = (float) (2.0 * Math.PI) * 220.0f / 48000.0f;
        for (int i = 0; i < 512; ++i) p[0][i] = 0.3f * fsin(inc * (float) i);
        p[0][64] = Double.NaN;
        p[0][192] = Double.POSITIVE_INFINITY;
        p[0][300] = Double.NEGATIVE_INFINITY;
        t.process(p, 512);
        double energy = 0.0;
        boolean finite = true;
        for (int blk = 0; blk < 40; ++blk)
        {
            final double[][] b = new double[1][512];
            for (int i = 0; i < 512; ++i) b[0][i] = 0.3f * fsin(inc * (float) i);
            t.process(b, 512);
            if (blk >= 35)
                for (int i = 0; i < 512; ++i)
                {
                    if (!Double.isFinite(b[0][i])) finite = false;
                    energy += b[0][i] * b[0][i];
                }
        }
        assertTrue(finite);
        assertTrue(energy > 1e-3);
    }

    // -- Cabinet --------------------------------------------------------------------

    /** Level in dB of the line at hz over the last 4800 samples. */
    private static double line(double[] v, double hz)
    {
        final int n = 4800, from = v.length - n;
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double ph = 2.0 * 3.14159265358979323846 * hz * (double) i / 48000.0;
            re += v[from + i] * Math.cos(ph);
            im += v[from + i] * Math.sin(ph);
        }
        return 20.0 * Math.log10(Math.max(2.0 * Math.sqrt(re * re + im * im) / (double) n, 1e-15));
    }

    /** Returns {output, step, steady, after}. */
    private static double[][] cabinetRun(double hz, boolean on, int switchAt)
    {
        final TubePreamp tp = new TubePreamp();
        tp.setCabinet(on && switchAt == 0);
        tp.prepare(48000.0, 1, 256);
        final double[] x = new double[49152];
        for (int i = 0; i < x.length; ++i)
            x[i] = (float) (0.05 * Math.sin(2.0 * 3.14159265358979323846 * hz * i / 48000.0));
        double last = 0.0, step = 0.0, steady = 0.0, after = 0.0;
        final double[][] ch = { x };
        for (int off = 0; off < x.length; off += 256)
        {
            if (switchAt != 0 && off == switchAt) tp.setCabinet(true);
            tp.process(ch, off, 256);
            for (int i = off; i < off + 256; ++i)
            {
                x[i] = (float) x[i];
                final double d = Math.abs(x[i] - last);
                last = x[i];
                if (off + 4096 < switchAt && off > 8192) steady = Math.max(steady, d);
                if (switchAt != 0 && off >= switchAt && off < switchAt + 2048) step = Math.max(step, d);
                if (switchAt != 0 && off >= switchAt + 4096 && off < switchAt + 8192) after = Math.max(after, d);
            }
        }
        return new double[][] { x, { step, steady, after } };
    }

    @Test
    @DisplayName("TubePreamp_cabinet_stage_colours_the_wet_signal_without_latency")
    void cabinetStageColoursTheWetSignalWithoutLatency()
    {
        final double[] open = cabinetRun(8000.0, false, 0)[0];
        final double[] boxed = cabinetRun(8000.0, true, 0)[0];
        assertTrue(line(boxed, 8000.0) < line(open, 8000.0) - 10.0);
        final double[] mid = cabinetRun(1000.0, true, 0)[0];
        final double[] midOpen = cabinetRun(1000.0, false, 0)[0];
        assertEquals(line(midOpen, 1000.0), line(mid, 1000.0), 2.0);

        // Switched on while it plays: no click.
        final double[] r = cabinetRun(1000.0, true, 24576)[1];
        assertTrue(r[0] < Math.max(r[1], r[2]) * 1.1, "step " + r[0] + " steady " + r[1] + " after " + r[2]);

        final TubePreamp a = prepared(48000.0, 1, 256);
        final int latency = a.getLatency();
        a.setCabinet(true);
        a.getCabinet().setSize(0.8);
        a.getCabinet().setDistance(0.6);
        assertEquals(latency, a.getLatency());
        assertTrue(a.isCabinet());
        assertEquals(0.8, a.getCabinet().getSize(), 0.0);
        assertEquals(0.6, a.getCabinet().getDistance(), 0.0);
    }

    // -- Minimum-phase dry path -------------------------------------------------------

    private static double[] toneThrough(TubePreamp p, double freq, float amp, int length)
    {
        final int block = 256;
        final double[] outL = new double[length];
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
            p.process(ch, n);
            for (int i = 0; i < n; ++i) outL[off + i] = (float) ch[0][i];
        }
        return outL;
    }

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
                if (off + i + delay < length) out[off + i + delay] = (float) ch[0][i];
        }
        return out;
    }

    @Test
    @DisplayName("TubePreamp_and_TapeMachine_dry_path_crosses_minimum_phase_filters (TubePreamp)")
    void dryPathCrossesMinimumPhaseFilters()
    {
        final TubePreamp p = new TubePreamp();
        p.setMix(0.0);
        p.setOversamplingMode(TubePreamp.OversamplingMode.MINIMUM_PHASE);
        p.prepare(48000.0, 2, 256);
        final TubePreamp linear = prepared(48000.0, 2, 256);
        assertTrue(p.getLatency() < linear.getLatency());
        assertSame(TubePreamp.OversamplingMode.MINIMUM_PHASE, p.getOversamplingMode());

        final Oversampling os = new Oversampling(2, Oversampling.Quality.HIGH, Oversampling.Mode.POLYPHASE_IIR,
                                                 Oversampling.Depth.PRESET);
        os.prepare(48000.0, 2, 256);
        final double[] got = toneThrough(p, 15000.0, 0.2f, 9600);
        final double[] want = roundTripThrough(os, 15000.0, 0.2f, 9600, p.getLatency() - os.getLatency());
        double worst = 0.0;
        for (int i = 2400; i < got.length; ++i) worst = Math.max(worst, Math.abs(got[i] - want[i]));
        assertTrue(worst < 1e-6, "worst " + worst);
    }

    // -- TestAnalogAudit / TestEffectsAdvanced ----------------------------------------

    @Test
    @DisplayName("AnalogAudit_oversize_blocks_equal_split_blocks (TubePreamp)")
    void oversizeBlocksEqualSplitBlocks()
    {
        for (int variant = 0; variant < 2; ++variant)
        {
            final TubePreamp a = new TubePreamp(), b = new TubePreamp();
            for (TubePreamp p : new TubePreamp[] { a, b })
            {
                if (variant == 1) p.setOversampling(1);
                p.prepare(48000.0, 1, 256);
                if (variant == 0) p.setDrive(12.0);
            }
            final double[] ya = new double[4096], yb = new double[4096];
            for (int i = 0; i < ya.length; ++i)
                ya[i] = yb[i] = (float) (0.3 * Math.sin(0.05 * (double) i));
            renderMono(a, ya, 1024);
            renderMono(b, yb, 256);
            double worst = 0.0;
            for (int i = 0; i < ya.length; ++i) worst = Math.max(worst, Math.abs(ya[i] - yb[i]));
            assertTrue(worst < 1e-6);
        }
    }

    @Test
    @DisplayName("AnalogAudit_tube_sag_change_is_ramped")
    void sagChangeIsRamped()
    {
        final double fs = 48000.0;
        final TubePreamp tube = prepared(fs, 1, 64);
        final long[] t = { 0 };
        runSine(tube, fs, 64, 24000, 220.0, 0.1, t);
        final double[] before = runSine(tube, fs, 64, 9600, 220.0, 0.1, t);
        tube.setSag(1.0);
        final double[] after = runSine(tube, fs, 64, 9600, 220.0, 0.1, t);
        assertTrue(maxStep(after, 0, after.length) < 2.0 * maxStep(before, 0, before.length));
    }

    @Test
    @DisplayName("Wet_dry_mix_ramps_over_20_ms_with_small_blocks (TubePreamp)")
    void wetDryMixRampsOver20MsWithSmallBlocks()
    {
        final TubePreamp wet = new TubePreamp(), dry = new TubePreamp(), subject = new TubePreamp();
        for (TubePreamp fx : new TubePreamp[] { wet, dry, subject })
        {
            fx.setDrive(12.0);
            fx.prepare(48000.0, 1, 32);
        }
        dry.setMix(0.0);
        dry.reset();
        final int warm = 32 * 150;
        final double[] in = new double[warm + 32];
        for (int i = 0; i < in.length; ++i)
            in[i] = 0.5f * fsin(6.2831853f * 220.0f * (float) i / 48000.0f);
        final double[] a = in.clone(), b = in.clone(), c = in.clone();
        for (int off = 0; off < warm + 32; off += 32)
        {
            if (off == warm) subject.setMix(0.0);
            wet.process(new double[][] { a }, off, 32);
            dry.process(new double[][] { b }, off, 32);
            subject.process(new double[][] { c }, off, 32);
        }
        double num = 0.0, den = 0.0;
        for (int i = warm + 24; i < warm + 32; ++i)
        {
            final double d = (float) a[i] - (float) b[i];
            num += ((float) c[i] - (float) b[i]) * d;
            den += d * d;
        }
        assertTrue((den > 0.0 ? num / den : -1.0) > 0.9);
    }

    // -- Common properties ----------------------------------------------------------

    private static double[][] programSignal(int channels, int n, double amplitude)
    {
        final double[][] x = new double[channels][n];
        int seed = 12345;
        double b0 = 0, b1 = 0, b2 = 0;
        for (int i = 0; i < n; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            final double white = (double) seed / 2147483648.0;
            b0 = 0.99765 * b0 + white * 0.0990460;
            b1 = 0.96300 * b1 + white * 0.2965164;
            b2 = 0.57000 * b2 + white * 1.0526913;
            final double pink = b0 + b1 + b2 + white * 0.1848;
            for (int c = 0; c < channels; ++c)
                x[c][i] = amplitude * (0.15 * pink + 0.6 * Math.sin(TWO_PI * (220.0 + 110.0 * c) * i / 48000.0));
        }
        return x;
    }

    private static TubePreamp configured(int factor, int stageCount, double drive)
    {
        final TubePreamp amp = new TubePreamp();
        amp.setOversampling(factor);
        amp.setStages(stageCount);
        amp.setDrive(drive);
        amp.setTreble(0.3);
        amp.setMix(0.8);
        amp.prepare(48000.0, 2, 4096);
        return amp;
    }

    private static double[][] render(TubePreamp amp, double[][] x, int block)
    {
        final double[][] y = { x[0].clone(), x[1].clone() };
        for (int pos = 0; pos < y[0].length; pos += block)
            amp.process(y, pos, Math.min(block, y[0].length - pos));
        return y;
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 samples give identical output")
    void blockPartitionIsInvisible()
    {
        final double[][] x = programSignal(2, 12000, 0.3);
        for (int factor : new int[] { 1, 2 })
            for (int stageCount : new int[] { 1, 2 })
            {
                final double[][] reference = render(configured(factor, stageCount, 12.0), x, 4096);
                for (int block : new int[] { 1, 37, 512 })
                {
                    final double[][] y = render(configured(factor, stageCount, 12.0), x, block);
                    assertArrayEquals(reference[0], y[0], 0.0, "block " + block);
                    assertArrayEquals(reference[1], y[1], 0.0, "block " + block);
                }
                assertTrue(allFinite(reference[0]) && allFinite(reference[1]));
            }
    }

    @Test
    @DisplayName("reset() equals a fresh instance; the same input gives the same bits")
    void resetEqualsFreshInstance()
    {
        final double[][] x = programSignal(2, 9000, 0.3);
        for (int factor : new int[] { 1, 2, 4 })
        {
            final TubePreamp used = configured(factor, 2, 18.0);
            render(used, programSignal(2, 7000, 0.5), 512);
            used.reset();
            final double[][] afterReset = render(used, x, 512);
            final double[][] fresh = render(configured(factor, 2, 18.0), x, 512);
            assertArrayEquals(fresh[0], afterReset[0], 0.0);
            assertArrayEquals(fresh[1], afterReset[1], 0.0);
            final double[][] again = render(configured(factor, 2, 18.0), x, 512);
            assertArrayEquals(fresh[0], again[0], 0.0);
        }
    }

    @Test
    @DisplayName("The reported latency equals the measured delay of the wet path")
    void latencyEqualsMeasuredDelay()
    {
        // A near-linear setting (-12 dB drive, no sag): the impulse response's
        // centre of energy and the phase of a 1 kHz tone both sit at the
        // reported latency, against the 1x point circuit (latency 0).
        final int n = 8192, bin = 171;
        double[] phase = new double[5];
        final int[] factors = { 1, 2, 4, 8, 16 };
        for (int k = 0; k < factors.length; ++k)
        {
            final TubePreamp amp = new TubePreamp();
            amp.setOversampling(factors[k]);
            amp.setDrive(-12.0);
            amp.setSag(0.0);
            amp.prepare(48000.0, 1, 512);
            final int latency = amp.getLatency();
            final double[] y = new double[2 * n + latency];
            for (int i = 0; i < y.length; ++i) y[i] = 0.1 * Math.sin(TWO_PI * bin * i / n);
            renderMono(amp, y, 512);
            double re = 0.0, im = 0.0;
            for (int i = 0; i < n; ++i)
            {
                final double w = TWO_PI * bin * i / n;
                re += y[n + latency + i] * Math.cos(w);
                im -= y[n + latency + i] * Math.sin(w);
            }
            phase[k] = Math.atan2(im, re);
        }
        for (int k = 1; k < factors.length; ++k)
        {
            double d = phase[k] - phase[0];
            d = Math.atan2(Math.sin(d), Math.cos(d));
            final double delay = -d * n / (TWO_PI * bin);
            assertTrue(Math.abs(delay) < 0.05, "factor " + factors[k] + ": " + delay);
        }
    }

    @Test
    @DisplayName("Processing, tone changes and stage changes allocate nothing")
    void processingAllocatesNothing()
    {
        final java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) return;
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        final long id = Thread.currentThread().getId();
        final double[][] x = programSignal(2, 512 * 64, 0.4);
        for (int factor : new int[] { 1, 2 })
            for (TubePreamp.GridModel model : TubePreamp.GridModel.values())
            {
                final TubePreamp amp = new TubePreamp();
                amp.setOversampling(factor);
                amp.setGridModel(model);
                amp.setDrive(12.0);
                amp.prepare(48000.0, 2, 512);
                final double[][] buf = new double[2][512];
                for (int pass = 0; pass < 2; ++pass)
                {
                    final long before = threads.getThreadAllocatedBytes(id);
                    for (int b = 0; b < 64; ++b)
                    {
                        if (b == 10) amp.setTreble(0.8 - 0.3 * pass);
                        if (b == 20) amp.setStages(2);
                        if (b == 40) amp.setStages(1);
                        if (b == 45) amp.setDrive(20.0 - pass);
                        if (b == 50) amp.setMix(0.5);
                        System.arraycopy(x[0], b * 512, buf[0], 0, 512);
                        System.arraycopy(x[1], b * 512, buf[1], 0, 512);
                        amp.process(buf, 512);
                    }
                    final long allocated = threads.getThreadAllocatedBytes(id) - before;
                    if (pass == 1)
                        assertTrue(allocated < 1024, factor + "x " + model + ": " + allocated + " bytes");
                }
            }
    }

    @Test
    @DisplayName("Settings made before prepare() apply calibrated from the first sample")
    void settingsBeforePrepareStartCalibrated()
    {
        final double[][] x = programSignal(2, 6000, 0.4);
        for (int factor : new int[] { 1, 2 })
        {
            // The same settings before and after prepare(): the same bits.
            final TubePreamp before = new TubePreamp();
            before.setOversampling(factor);
            before.setDrive(17.0);
            before.setStages(2);
            before.setSag(0.8);
            before.setBass(0.2);
            before.setOutput(-20.0);
            before.prepare(48000.0, 2, 512);
            final TubePreamp after = new TubePreamp();
            after.setOversampling(factor);
            after.prepare(48000.0, 2, 512);
            after.setDrive(17.0);
            after.setStages(2);
            after.setSag(0.8);
            after.setBass(0.2);
            after.setOutput(-20.0);
            final double[][] a = render(before, x, 512), b = render(after, x, 512);
            assertArrayEquals(a[0], b[0], 0.0);
            assertArrayEquals(a[1], b[1], 0.0);

            // No ramp from the defaults: with only the trim differing, the
            // output is 20 dB down from the very first sample.
            final TubePreamp unity = new TubePreamp();
            unity.setOversampling(factor);
            unity.setDrive(17.0);
            unity.setStages(2);
            unity.setSag(0.8);
            unity.setBass(0.2);
            unity.prepare(48000.0, 2, 512);
            final double[][] u = render(unity, x, 512);
            for (int i = 0; i < 6000; ++i)
                assertEquals(0.1 * u[0][i], a[0][i], 1e-12 * Math.abs(u[0][i]) + 1e-18, "sample " + i);
        }
    }

    @Test
    @DisplayName("Mix 0 is the dry input delayed by the latency; unprepared passes through")
    void neutralSettings()
    {
        final double[][] x = programSignal(2, 3000, 0.5);
        final TubePreamp amp = new TubePreamp();
        amp.setMix(0.0);
        amp.setDrive(36.0);
        amp.prepare(48000.0, 2, 256);
        final int latency = amp.getLatency();
        final double[][] y = render(amp, x, 256);
        for (int c = 0; c < 2; ++c)
            for (int i = 0; i < y[c].length; ++i)
                assertEquals(i >= latency ? x[c][i - latency] : 0.0, y[c][i], 0.0);
        final TubePreamp idle = new TubePreamp();
        final double[][] z = render(idle, x, 256);
        assertArrayEquals(x[0], z[0], 0.0);
        // Channels beyond the prepared count pass through untouched.
        final TubePreamp mono = prepared(48000.0, 1, 256);
        final double[][] w = render(mono, x, 256);
        assertArrayEquals(x[1], w[1], 0.0);
    }
}
