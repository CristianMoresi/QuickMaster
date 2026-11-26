package com.dspark.core;

import com.dspark.core.Oversampling.Band;
import com.dspark.core.Oversampling.Depth;
import com.dspark.core.Oversampling.Mode;
import com.dspark.core.Oversampling.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OversamplingTest
{
    private static final double PI = Math.PI;
    private static final int[] FACTORS = { 2, 4, 8, 16 };

    // ------------------------------------------------------------------
    // Helpers (the measurement routines of the C++ tests)
    // ------------------------------------------------------------------

    private static void roundTrip(Oversampling os, double[][] buffer, int offset, int n)
    {
        os.upsample(buffer, offset, n);
        os.downsample(buffer, offset, n);
    }

    /** Amplitude of a tone over a stretch of a signal (projection on its quadrature pair). */
    private static double toneAmplitude(double[] x, int from, int count, double hz, double rate)
    {
        double sn = 0.0, cs = 0.0;
        for (int i = 0; i < count; i++)
        {
            final double phase = 2.0 * PI * hz * (double) (from + i) / rate;
            sn += x[from + i] * Math.sin(phase);
            cs += x[from + i] * Math.cos(phase);
        }
        return 2.0 * Math.hypot(sn, cs) / (double) count;
    }

    /** What a tone put in at the high rate leaves at a frequency of the base rate, in dB re the tone. */
    private static double aliasDb(Oversampling os, double baseRate, double toneHz, double readHz)
    {
        final int block = 256, total = 16384;
        final int factor = os.getFactor();
        double[] out = new double[total];
        double[][] base = { new double[block] };
        for (int pos = 0; pos < total; pos += block)
        {
            double[][] hi = os.getHighRateBlock(block);
            for (int i = 0; i < os.getHighRateLength(); i++)
                hi[0][i] = 0.5 * Math.sin(2.0 * PI * toneHz * (double) ((long) pos * factor + i) / (baseRate * factor));
            os.downsample(base, block);
            System.arraycopy(base[0], 0, out, pos, block);
        }
        return 20.0 * Math.log10(toneAmplitude(out, total / 2, total / 2, readHz, baseRate) / 0.5 + 1e-15);
    }

    /** Gain of a tone of the base rate through the round trip (a whole number of cycles in the window read). */
    private static double roundTripGain(Oversampling os, double baseRate, double hz)
    {
        final int block = 256, total = 16384;
        hz = Math.rint(hz * 8192.0 / baseRate) * baseRate / 8192.0;
        double[] out = new double[total];
        double[][] base = { new double[block] };
        for (int pos = 0; pos < total; pos += block)
        {
            for (int i = 0; i < block; i++) base[0][i] = Math.sin(2.0 * PI * hz * (double) (pos + i) / baseRate);
            roundTrip(os, base, 0, block);
            System.arraycopy(base[0], 0, out, pos, block);
        }
        return toneAmplitude(out, total / 2, total / 2, hz, baseRate);
    }

    /** Level of the image of a base-rate tone in the 2x signal, in dB re the tone there. */
    private static double imageDb(Oversampling os, double baseRate, double hz)
    {
        final int block = 256, total = 16384;
        hz = Math.rint(hz * 8192.0 / baseRate) * baseRate / 8192.0;
        double[] high = new double[total * 2];
        double[][] base = { new double[block] };
        for (int pos = 0; pos < total; pos += block)
        {
            for (int i = 0; i < block; i++) base[0][i] = Math.sin(2.0 * PI * hz * (double) (pos + i) / baseRate);
            double[][] up = os.upsample(base, block);
            System.arraycopy(up[0], 0, high, pos * 2, os.getHighRateLength());
            os.downsample(base, block);
        }
        final int half = high.length / 2;
        final double tone = toneAmplitude(high, half, half, hz, 2.0 * baseRate);
        final double image = toneAmplitude(high, half, half, baseRate - hz, 2.0 * baseRate);
        return 20.0 * Math.log10(image / tone + 1e-15);
    }

    private static int argmaxAbs(double[] x)
    {
        int a = 0;
        for (int i = 1; i < x.length; i++) if (Math.abs(x[i]) > Math.abs(x[a])) a = i;
        return a;
    }

    private static double[] lcgSignal(int frames)
    {
        double[] result = new double[frames];
        int rng = 123;
        for (int i = 0; i < frames; i++)
        {
            rng = rng * 1664525 + 1013904223;
            result[i] = (double) (rng >>> 8) / 8388608.0 - 1.0;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Ports of the C++ assertions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2x Low round trip keeps an in-band sine above 40 dB SNR (Oversampling_2x_roundtrip)")
    void roundTrip2x()
    {
        Oversampling os = new Oversampling(2, Quality.LOW);
        final int blockSize = 256;
        os.prepare(44100.0, 1, blockSize);
        double[][] tb = new double[1][blockSize];
        for (int warmup = 0; warmup < 10; warmup++)
        {
            for (int i = 0; i < blockSize; i++) tb[0][i] = Math.sin(2.0 * PI * 440.0 * i / 44100.0);
            roundTrip(os, tb, 0, blockSize);
        }
        for (int i = 0; i < blockSize; i++) tb[0][i] = Math.sin(2.0 * PI * 440.0 * i / 44100.0);
        double[] original = tb[0].clone();
        roundTrip(os, tb, 0, blockSize);
        final int latency = os.getLatency();
        double errSum = 0.0, sigSum = 0.0;
        for (int i = latency + 16; i < blockSize - 16; i++)
        {
            assertTrue(Double.isFinite(tb[0][i]) && Math.abs(tb[0][i]) <= 1.5);
            double err = tb[0][i] - original[i - latency];
            errSum += err * err;
            sigSum += original[i - latency] * original[i - latency];
        }
        assertTrue(-10.0 * Math.log10(errSum / (sigSum + 1e-30)) > 40.0);
    }

    @Test
    @DisplayName("Variable block sizes keep the round trip continuous, x2 to x16 (Oversampling_variable_block_roundtrip)")
    void variableBlockRoundTrip()
    {
        for (int factor : FACTORS)
        {
            Oversampling os = new Oversampling(factor, Quality.HIGH);
            final int maxBlock = 512, total = 8192;
            os.prepare(48000.0, 1, maxBlock);
            final int latency = os.getLatency();
            double[] ref = new double[total], out = new double[total];
            for (int i = 0; i < total; i++) ref[i] = 0.5 * Math.sin(2.0 * PI * 1000.0 * i / 48000.0);
            double[][] blk = new double[1][maxBlock];
            int rng = (int) 2246822519L + factor;
            int pos = 0;
            while (pos < total)
            {
                rng = rng * 1664525 + 1013904223;
                int bs = 1 + Integer.remainderUnsigned(rng, maxBlock);
                if (bs > total - pos) bs = total - pos;
                System.arraycopy(ref, pos, blk[0], 0, bs);
                roundTrip(os, blk, 0, bs);
                System.arraycopy(blk[0], 0, out, pos, bs);
                pos += bs;
            }
            double errSum = 0.0, sigSum = 0.0;
            for (int i = latency + 1000; i < total - 16; i++)
            {
                double e = out[i] - ref[i - latency];
                errSum += e * e;
                sigSum += ref[i - latency] * ref[i - latency];
            }
            assertTrue(-10.0 * Math.log10(errSum / (sigSum + 1e-30)) > 40.0, "factor " + factor);
        }
    }

    private static double[] renderPartition(Oversampling os, double[] signal, int maximumBlock, int partition)
    {
        final int[] irregular = { 1, 5, 16, 31, 7, 137 };
        double[][] buffer = { signal.clone() };
        int position = 0, block = 0;
        while (position < signal.length)
        {
            final int requested = partition == 0 ? maximumBlock : partition == 1 ? irregular[block++ % 6] : 1;
            final int count = Math.min(requested, signal.length - position);
            roundTrip(os, buffer, position, count);
            position += count;
        }
        return buffer[0];
    }

    @Test
    @DisplayName("Exact across block partitions (Oversampling_is_exact_across_block_partitions)")
    void exactAcrossBlockPartitions()
    {
        final int frames = 1031, maximumBlock = 137;
        final double[] signal = lcgSignal(frames);
        for (int factor : FACTORS)
            for (Quality q : new Quality[] { Quality.LOW, Quality.HIGH, Quality.MAXIMUM })
            {
                Oversampling ref = new Oversampling(factor, q);
                ref.prepare(48000.0, 1, maximumBlock);
                final double[] reference = renderPartition(ref, signal, maximumBlock, 0);
                for (int partition : new int[] { 1, 2 })
                {
                    Oversampling os = new Oversampling(factor, q);
                    os.prepare(48000.0, 1, maximumBlock);
                    assertArrayEquals(reference, renderPartition(os, signal, maximumBlock, partition),
                                      "factor " + factor + " " + q + " partition " + partition);
                }
            }
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give identical output in every mode, depth and band")
    void blockSizeIndependence()
    {
        final double[] signal = lcgSignal(9000);
        final Object[][] cases = {
            { 2, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.PRESET },
            { 16, Mode.LINEAR_PHASE_FIR, Depth.DEEP, Band.PRESET },
            { 4, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE },
            { 2, Mode.POLYPHASE_IIR, Depth.PRESET, Band.PRESET },
            { 8, Mode.POLYPHASE_IIR, Depth.DEEP, Band.AUDIBLE },
        };
        for (Object[] c : cases)
        {
            double[] reference = null;
            for (int block : new int[] { 4096, 512, 37, 1 })
            {
                Oversampling os = new Oversampling((Integer) c[0], Quality.HIGH, (Mode) c[1], (Depth) c[2], (Band) c[3]);
                os.prepare(96000.0, 2, 4096);
                double[][] buffer = { signal.clone(), signal.clone() };
                for (int pos = 0; pos < signal.length; pos += block)
                    roundTrip(os, buffer, pos, Math.min(block, signal.length - pos));
                assertArrayEquals(buffer[0], buffer[1]);
                for (double v : buffer[0]) assertTrue(Double.isFinite(v));
                if (reference == null) reference = buffer[0];
                else assertArrayEquals(reference, buffer[0], c[0] + "x " + c[1] + " " + c[2] + " " + c[3] + " block " + block);
            }
        }
    }

    @Test
    @DisplayName("An impulse peaks exactly getLatency() late, Low and Maximum (Oversampling_impulse_roundtrip_latency_is_exact)")
    void impulseRoundTripLatencyIsExact()
    {
        for (Quality q : new Quality[] { Quality.LOW, Quality.MAXIMUM })
            for (int factor : FACTORS)
            {
                Oversampling os = new Oversampling(factor, q);
                final int blockSize = 2048, impulseAt = 64;
                os.prepare(48000.0, 1, blockSize);
                double[][] tb = new double[1][blockSize];
                tb[0][impulseAt] = 1.0;
                roundTrip(os, tb, 0, blockSize);
                int argmax = argmaxAbs(tb[0]);
                assertEquals(impulseAt + os.getLatency(), argmax, q + " " + factor + "x");
                assertTrue(Math.abs(tb[0][argmax]) > 0.7);
            }
    }

    @Test
    @DisplayName("The decimation-only path delays by getDecimationDelay() high-rate samples (Oversampling_decimation_only_delay_is_exact)")
    void decimationOnlyDelayIsExact()
    {
        final double fs = 48000.0, f = 50.0;
        for (int factor : FACTORS)
            for (Quality q : Quality.values())
            {
                Oversampling os = new Oversampling(factor, q);
                os.prepare(fs, 1, 64);
                double[] out = new double[400 * 64];
                double[][] base = { new double[64] };
                for (int b = 0; b < 400; b++)
                {
                    double[][] hi = os.getHighRateBlock(64);
                    assertEquals(64 * factor, os.getHighRateLength());
                    for (int i = 0; i < os.getHighRateLength(); i++)
                        hi[0][i] = Math.sin(2.0 * PI * f * (double) (b * 64 * factor + i) / (fs * factor));
                    os.downsample(base, 64);
                    System.arraycopy(base[0], 0, out, b * 64, 64);
                }
                double sn = 0.0, cs = 0.0;
                for (int m = 4800; m < 4800 + 19200; m++)
                {
                    sn += out[m] * Math.sin(2.0 * PI * f * m / fs);
                    cs += out[m] * Math.cos(2.0 * PI * f * m / fs);
                }
                final double delay = Math.atan2(-cs, sn) / (2.0 * PI * f / fs);
                assertEquals(os.getDecimationDelay() / (double) factor, delay, 1e-6, q + " " + factor + "x");
                assertEquals(1.0, 2.0 * Math.hypot(sn, cs) / 19200.0, 1e-5);
            }
    }

    @Test
    @DisplayName("The 2x stopband matches each quality preset (Oversampling_stopband_matches_quality_preset)")
    void stopbandMatchesQualityPreset()
    {
        final Quality[] qs = { Quality.LOW, Quality.MEDIUM, Quality.HIGH, Quality.MAXIMUM };
        final double[] maxDb = { -35.0, -55.0, -75.0, -90.0 };
        final int blockSize = 512, total = 8192;
        for (int c = 0; c < qs.length; c++)
        {
            Oversampling os = new Oversampling(2, qs[c]);
            os.prepare(48000.0, 1, blockSize);
            double[] out = new double[total];
            double[][] blk = new double[1][blockSize];
            for (int pos = 0; pos < total; pos += blockSize)
            {
                java.util.Arrays.fill(blk[0], 0.0);
                double[][] up = os.upsample(blk, blockSize);
                for (int i = 0; i < os.getHighRateLength(); i++)
                {
                    final double n = (double) pos * 2.0 + (double) i;
                    up[0][i] = 0.5 * Math.sin(2.0 * PI * 30000.0 * n / 96000.0);
                }
                os.downsample(blk, blockSize);
                System.arraycopy(blk[0], 0, out, pos, blockSize);
            }
            final double aliasAmp = toneAmplitude(out, total / 2, total / 2, 18000.0, 48000.0);
            final double aliasDb = 20.0 * Math.log10(aliasAmp / 0.5 + 1e-30);
            assertTrue(aliasDb < maxDb[c], qs[c] + ": " + aliasDb);
        }
    }

    @Test
    @DisplayName("A hot channel never leaks into a silent one (Oversampling_channels_are_isolated)")
    void channelsAreIsolated()
    {
        Oversampling os = new Oversampling(4, Quality.HIGH);
        final int blockSize = 256;
        os.prepare(48000.0, 2, blockSize);
        double peakSilent = 0.0, peakHot = 0.0;
        for (int b = 0; b < 8; b++)
        {
            double[][] tb = new double[2][blockSize];
            for (int i = 0; i < blockSize; i++) tb[0][i] = 0.8 * Math.sin(2.0 * PI * 1000.0 * i / 48000.0);
            roundTrip(os, tb, 0, blockSize);
            for (int i = 0; i < blockSize; i++)
            {
                peakHot = Math.max(peakHot, Math.abs(tb[0][i]));
                peakSilent = Math.max(peakSilent, Math.abs(tb[1][i]));
            }
        }
        assertTrue(peakHot > 0.5);
        assertEquals(0.0, peakSilent, 0.0);
    }

    @Test
    @DisplayName("The high-rate buffer has the prepared channels (at most 16) and count * factor samples")
    void highRateViewShape()
    {
        for (int factor : new int[] { 1, 2, 4, 8, 16 })
        {
            Oversampling os = new Oversampling(factor);
            os.prepare(48000.0, 2, 127);
            double[][] in = new double[2][127];
            for (int block = 0; block < 6; block++)
            {
                final int count = block % 2 == 0 ? 127 : 31;
                double[][] up = os.upsample(in, count);
                assertEquals(2, up.length);
                assertEquals(count * factor, os.getHighRateLength());
                os.downsample(in, count);
            }
            double[][] longer = new double[2][500];
            os.upsample(longer, 500);
            assertEquals(127 * factor, os.getHighRateLength());   // truncated to the prepared block
            os.downsample(longer, 500);
        }
        Oversampling wide = new Oversampling(2);
        wide.prepare(48000.0, 20, 16);
        assertEquals(Oversampling.MAX_CHANNELS, wide.getHighRateBlock(16).length);
    }

    @Test
    @DisplayName("factorForRate follows the host rate (Oversampling_factor_follows_the_host_rate)")
    void factorFollowsTheHostRate()
    {
        assertEquals(4, Oversampling.factorForRate(44100.0));
        assertEquals(4, Oversampling.factorForRate(48000.0));
        assertEquals(2, Oversampling.factorForRate(88200.0));
        assertEquals(2, Oversampling.factorForRate(96000.0));
        assertEquals(1, Oversampling.factorForRate(176400.0));
        assertEquals(1, Oversampling.factorForRate(192000.0));
        assertEquals(8, Oversampling.factorForRate(32000.0));
        assertEquals(8, Oversampling.factorForRate(22050.0));
        assertEquals(16, Oversampling.factorForRate(8000.0));
        assertEquals(4, Oversampling.factorForRate(44099.99999));

        assertEquals(2, Oversampling.factorForRate(44100.0, 88200.0));
        assertEquals(2, Oversampling.factorForRate(48000.0, 88200.0));
        assertEquals(1, Oversampling.factorForRate(88200.0, 88200.0));
        assertEquals(1, Oversampling.factorForRate(96000.0, 88200.0));
        assertEquals(4, Oversampling.factorForRate(8000.0, 176400.0, 4));
        assertEquals(1, Oversampling.factorForRate(8000.0, 176400.0, 0));
        assertEquals(16, Oversampling.factorForRate(8000.0, 176400.0, 1000));

        assertEquals(1, Oversampling.factorForRate(0.0));
        assertEquals(1, Oversampling.factorForRate(-48000.0));
        assertEquals(1, Oversampling.factorForRate(Double.NaN));
        assertEquals(1, Oversampling.factorForRate(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("Band.AUDIBLE shortens the first stage with the rate (Oversampling_audible_band_shortens_the_first_stage_with_the_rate)")
    void audibleBandShortensTheFirstStage()
    {
        final double[] rates = { 44100.0, 48000.0, 88200.0, 96000.0, 176400.0, 192000.0 };
        final int[] expectedTaps = { 111, 63, 23, 19, 15, 15 };
        for (int r = 0; r < rates.length; r++)
        {
            final double rate = rates[r];
            Oversampling os = new Oversampling(2, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE);
            assertEquals(127, os.getStageLength(0));
            assertEquals(64, os.getLatency());
            os.prepare(rate, 1, 512);
            final int taps = os.getStageLength(0);
            assertEquals(expectedTaps[r], taps, "rate " + rate);
            assertEquals((taps + 1) / 2, os.getLatency());
            assertEquals(0.5 - 20000.0 / rate, os.getFirstStageTransition(), 1e-12);

            double[][] block = new double[1][512];
            block[0][40] = 1.0;
            roundTrip(os, block, 0, 512);
            assertEquals(40 + os.getLatency(), argmaxAbs(block[0]));

            Oversampling preset = new Oversampling(2, Quality.HIGH);
            preset.prepare(rate, 1, 512);
            assertEquals(127, preset.getStageLength(0));
            assertEquals(64, preset.getLatency());
        }

        final Quality[] qs = { Quality.LOW, Quality.MEDIUM, Quality.HIGH, Quality.MAXIMUM };
        final int[][] lengths = { { 31, 31, 7 }, { 63, 63, 15 }, { 127, 111, 19 }, { 255, 143, 27 } };
        for (int p = 0; p < qs.length; p++)
        {
            Oversampling os = new Oversampling(2, qs[p], Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE);
            assertEquals(lengths[p][0], os.getStageLength(0));
            os.prepare(44100.0, 1, 64);
            assertEquals(lengths[p][1], os.getStageLength(0), qs[p] + " at 44.1 kHz");
            os.prepare(96000.0, 1, 64);
            assertEquals(lengths[p][2], os.getStageLength(0), qs[p] + " at 96 kHz");
        }
        Oversampling deep = new Oversampling(2, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.DEEP, Band.AUDIBLE);
        assertEquals(163, deep.getStageLength(0));
        deep.prepare(96000.0, 1, 64);
        assertEquals(27, deep.getStageLength(0));

        Oversampling os = new Oversampling(4, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE);
        os.prepare(96000.0, 1, 64);
        final int at96 = os.getStageLength(0);
        assertEquals(23, os.getStageLength(1));
        assertEquals((at96 + 1) / 2 + 6, os.getLatency());
        assertEquals(0, os.getStageLength(2));
        os.prepare(48000.0, 1, 64);
        assertTrue(os.getStageLength(0) > at96);
        os.prepare(0.0, 1, 64);
        assertEquals(127, os.getStageLength(0));
        assertEquals(70, os.getLatency());
    }

    @Test
    @DisplayName("Band.AUDIBLE keeps the preset's depth under 20 kHz (Oversampling_audible_band_keeps_the_presets_depth_under_20_kHz)")
    void audibleBandKeepsThePresetsDepth()
    {
        final Quality[] qs = { Quality.LOW, Quality.MEDIUM, Quality.HIGH, Quality.MAXIMUM };
        final double[] depths = { 38.9, 59.5, 79.2, 100.1 };
        for (int c = 0; c < qs.length; c++)
        {
            Oversampling os = new Oversampling(2, qs[c], Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE);
            os.prepare(96000.0, 1, 256);
            final double limit = -(depths[c] - 0.3);
            assertTrue(aliasDb(os, 96000.0, 78000.0, 18000.0) < limit, qs[c] + " alias 18k");
            assertTrue(aliasDb(os, 96000.0, 76000.0, 20000.0) < limit, qs[c] + " alias 20k");
            assertTrue(aliasDb(os, 96000.0, 91000.0, 5000.0) < limit, qs[c] + " alias 5k");
            assertTrue(imageDb(os, 96000.0, 18000.0) < limit, qs[c] + " image");
            final double ripple = 3.0 * Math.pow(10.0, -depths[c] / 20.0);
            assertEquals(1.0, roundTripGain(os, 96000.0, 1000.0), ripple);
            assertEquals(1.0, roundTripGain(os, 96000.0, 19900.0), ripple);
        }

        for (double rate : new double[] { 44100.0, 48000.0, 88200.0, 176400.0, 192000.0 })
        {
            Oversampling os = new Oversampling(2, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE);
            os.prepare(rate, 1, 256);
            assertTrue(aliasDb(os, rate, rate - 19000.0, 19000.0) < -78.9, "rate " + rate);
            assertTrue(aliasDb(os, rate, rate - 20000.0, 20000.0) < -78.9, "rate " + rate);
            assertEquals(1.0, roundTripGain(os, rate, 19900.0), 4e-4);
        }

        Oversampling preset = new Oversampling(2, Quality.HIGH);
        preset.prepare(96000.0, 1, 256);
        assertEquals(1.0, roundTripGain(preset, 96000.0, 30000.0), 4e-4);
        assertEquals(1.0, roundTripGain(preset, 96000.0, 40000.0), 4e-4);
    }

    @Test
    @DisplayName("Band.AUDIBLE in the polyphase mode, and its dry twin (Oversampling_audible_band_in_the_polyphase_mode)")
    void audibleBandInThePolyphaseMode()
    {
        Oversampling preset = new Oversampling(2, Quality.HIGH, Mode.POLYPHASE_IIR);
        Oversampling audible = new Oversampling(2, Quality.HIGH, Mode.POLYPHASE_IIR, Depth.PRESET, Band.AUDIBLE);
        preset.prepare(96000.0, 1, 256);
        audible.prepare(96000.0, 1, 256);
        assertEquals(0, audible.getStageLength(0));
        assertEquals(0.5 - 20000.0 / 96000.0, audible.getFirstStageTransition(), 1e-12);

        final double presetDelay = preset.getRoundTripDelay(1000.0), audibleDelay = audible.getRoundTripDelay(1000.0);
        final double presetEdge = preset.getRoundTripDelay(19000.0), audibleEdge = audible.getRoundTripDelay(19000.0);
        assertTrue(audibleDelay < presetDelay);
        assertTrue(audibleEdge - audibleDelay < presetEdge - presetDelay);
        assertTrue(aliasDb(audible, 96000.0, 78000.0, 18000.0) < -78.9);
        assertTrue(aliasDb(audible, 96000.0, 76000.0, 20000.0) < -78.9);
        assertEquals(1.0, roundTripGain(audible, 96000.0, 1000.0), 4e-4);
        assertEquals(1.0, roundTripGain(audible, 96000.0, 19900.0), 4e-4);

        // The dry path builds its twin with the band and the rate of the engine.
        OversamplingDryPath dry = new OversamplingDryPath();
        dry.prepare(96000.0, 1, 256, audible);
        assertEquals(audible.getLatency(), dry.getLatency());
        audible.reset();
        double worst = 0.0;
        for (int b = 0; b < 32; b++)
        {
            double[][] wet = new double[1][256], aligned = new double[1][256];
            for (int i = 0; i < 256; i++)
                wet[0][i] = aligned[0][i] = Math.sin(2.0 * PI * 17987.0 * (double) (b * 256 + i) / 96000.0);
            roundTrip(audible, wet, 0, 256);
            dry.process(aligned, 256);
            for (int i = 0; i < 256; i++) worst = Math.max(worst, Math.abs(wet[0][i] - aligned[0][i]));
        }
        assertEquals(0.0, worst, 0.0);

        Oversampling fir = new Oversampling(2, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE);
        fir.prepare(96000.0, 1, 256);
        OversamplingDryPath line = new OversamplingDryPath();
        line.prepare(96000.0, 1, 256, fir);
        assertEquals(fir.getLatency(), line.getLatency());
        assertTrue(line.getLatency() < 64);
    }

    @Test
    @DisplayName("Later stages are shorter: 70/73/74 at 4x/8x/16x High, images under -80 dB (CoreReg_oversampling_later_stages_are_shorter)")
    void laterStagesAreShorter()
    {
        final int[] expected = { 70, 73, 74 };
        int idx = 0;
        for (int factor : new int[] { 4, 8, 16 })
        {
            Oversampling os = new Oversampling(factor, Quality.HIGH);
            final int bs = 2048;
            os.prepare(48000.0, 1, bs);
            assertEquals(expected[idx++], os.getLatency());
            double[][] x = new double[1][bs];
            x[0][64] = 1.0;
            roundTrip(os, x, 0, bs);
            assertEquals(64 + os.getLatency(), argmaxAbs(x[0]));

            os.reset();
            final int n = 64;
            double[][] in = new double[1][n * 64];
            for (int i = 0; i < in[0].length; i++) in[0][i] = Math.sin(2.0 * PI * 15000.0 / 48000.0 * i);
            double[] hi = new double[32 * n * factor];
            double[][] out = new double[1][n];
            for (int b = 0; b < 64; b++)
            {
                double[][] up = os.upsample(in, b * n, n);
                if (b >= 32) System.arraycopy(up[0], 0, hi, (b - 32) * n * factor, os.getHighRateLength());
                os.downsample(out, n);
            }
            final double topRate = 48000.0 * factor;
            final double tone = toneAmplitude(hi, 0, hi.length, 15000.0, topRate);
            double worst = 0.0;
            for (int k = 1; k < factor; k++)
                for (double f : new double[] { k * 48000.0 - 15000.0, k * 48000.0 + 15000.0 })
                    if (f < 0.5 * topRate)
                        worst = Math.max(worst, toneAmplitude(hi, 0, hi.length, f, topRate));
            assertTrue(20.0 * Math.log10(worst / tone + 1e-30) < -80.0, "factor " + factor);
        }
    }

    @Test
    @DisplayName("Depth.DEEP stops the whole band and keeps the delay exact (CoreReg_oversampling_deep_stages_stop_the_whole_band)")
    void deepStagesStopTheWholeBand()
    {
        for (Quality q : Quality.values())
            for (int factor : FACTORS)
            {
                Oversampling os = new Oversampling(factor, q, Mode.LINEAR_PHASE_FIR, Depth.DEEP);
                assertSame(Depth.DEEP, os.getDepth());
                final int bs = 2048;
                os.prepare(48000.0, 1, bs);
                double[][] x = new double[1][bs];
                x[0][64] = 1.0;
                roundTrip(os, x, 0, bs);
                assertEquals(64 + os.getLatency(), argmaxAbs(x[0]), q + " " + factor + "x");
            }
        assertEquals(70, new Oversampling(4, Quality.HIGH).getLatency());
        assertEquals(90, new Oversampling(4, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.DEEP).getLatency());

        for (Quality q : new Quality[] { Quality.LOW, Quality.MEDIUM, Quality.HIGH })
            for (double hz : new double[] { 76000.0, 80000.0, 88500.0 })
                assertTrue(leak(4, q, Depth.DEEP, hz) < -100.0, q + " " + hz);
        assertTrue(leak(4, Quality.HIGH, Depth.PRESET, 80000.0) > -95.0);
        assertTrue(leak(8, Quality.HIGH, Depth.DEEP, 180000.0) < -100.0);
        assertTrue(leak(2, Quality.HIGH, Depth.DEEP, 28500.0) < -100.0);
    }

    private static double leak(int factor, Quality q, Depth depth, double hz)
    {
        Oversampling os = new Oversampling(factor, q, Mode.LINEAR_PHASE_FIR, depth);
        os.prepare(48000.0, 1, 256);
        final double rate = 48000.0 * factor;
        double[] out = new double[64 * 256];
        double[][] base = { new double[256] };
        for (int b = 0; b < 96; b++)
        {
            double[][] hi = os.getHighRateBlock(256);
            for (int i = 0; i < os.getHighRateLength(); i++)
                hi[0][i] = Math.sin(2.0 * PI * hz * (double) (b * 256 * factor + i) / rate);
            os.downsample(base, 256);
            if (b >= 32) System.arraycopy(base[0], 0, out, (b - 32) * 256, 256);
        }
        double folded = hz;
        while (folded > 24000.0) folded = Math.abs(folded - 48000.0 * Math.rint(folded / 48000.0));
        double c = 0.0, s = 0.0;
        final double w = 2.0 * PI * folded / 48000.0;
        for (int i = 0; i < out.length; i++)
        {
            c += out[i] * Math.cos(w * i);
            s += out[i] * Math.sin(w * i);
        }
        return 20.0 * Math.log10(2.0 * Math.hypot(c, s) / (double) out.length + 1e-30);
    }

    @Test
    @DisplayName("Polyphase IIR: 3 samples of delay, a deep stopband, an honest fractional delay (CoreReg_oversampling_iir_mode_delay_and_stopband)")
    void iirModeDelayAndStopband()
    {
        Oversampling os = new Oversampling(2, Quality.HIGH, Mode.POLYPHASE_IIR);
        final int bs = 512;
        os.prepare(48000.0, 1, bs);
        assertSame(Mode.POLYPHASE_IIR, os.getMode());
        assertEquals(3, os.getLatency());

        double[] out = new double[16 * bs];
        double[][] blk = new double[1][bs];
        long t = 0;
        for (int b = 0; b < 32; b++)
        {
            java.util.Arrays.fill(blk[0], 0.0);
            double[][] up = os.upsample(blk, bs);
            for (int i = 0; i < os.getHighRateLength(); i++)
                up[0][i] = 0.5 * Math.sin(2.0 * PI * 30000.0 / 96000.0 * (double) (t++));
            os.downsample(blk, bs);
            if (b >= 16) System.arraycopy(blk[0], 0, out, (b - 16) * bs, bs);
        }
        final double alias = toneAmplitude(out, 0, out.length, 18000.0, 48000.0) / 0.5;
        assertTrue(20.0 * Math.log10(alias + 1e-30) < -95.0);

        os.reset();
        final int len = 48000;
        double[][] y = new double[1][len];
        for (int i = 0; i < len; i++) y[0][i] = Math.sin(2.0 * PI * 1000.0 / 48000.0 * i);
        for (int b = 0; b + bs <= len; b += bs) roundTrip(os, y, b, bs);
        double c = 0.0, s = 0.0;
        final double w = 2.0 * PI * 1000.0 / 48000.0;
        for (int i = 24000; i < 24000 + 48 * 400; i++) { c += y[0][i] * Math.cos(w * i); s += y[0][i] * Math.sin(w * i); }
        final double measured = Math.atan2(-c, s) / w;
        assertEquals(os.getRoundTripDelay(1000.0), measured, 0.01);
        assertTrue(os.getRoundTripDelay(18000.0) > os.getRoundTripDelay(1000.0) + 0.5);
    }

    @Test
    @DisplayName("Polyphase IIR is exact across partitions and recovers from a NaN (CoreReg_oversampling_iir_mode_partitions_and_nan)")
    void iirModePartitionsAndNan()
    {
        Oversampling a = new Oversampling(4, Quality.MAXIMUM, Mode.POLYPHASE_IIR);
        Oversampling b = new Oversampling(4, Quality.MAXIMUM, Mode.POLYPHASE_IIR);
        a.prepare(48000.0, 1, 512);
        b.prepare(48000.0, 1, 512);
        Random g = new Random(5);
        double[] in = new double[8192];
        for (int i = 0; i < in.length; i++) in[i] = g.nextDouble() - 0.5;
        double[][] x = { in.clone() }, y = { in.clone() };
        for (int pos = 0; pos < 8192; pos += 512) roundTrip(a, x, pos, 512);
        int pos = 0;
        while (pos < 8192)
        {
            final int n = Math.min(1 + g.nextInt(512), 8192 - pos);
            roundTrip(b, y, pos, n);
            pos += n;
        }
        assertArrayEquals(x[0], y[0]);

        double[][] z = new double[1][512];
        java.util.Arrays.fill(z[0], 0.1);
        z[0][3] = Double.NaN;
        roundTrip(a, z, 0, 512);
        java.util.Arrays.fill(z[0], 0.1);
        roundTrip(a, z, 0, 512);
        for (double v : z[0]) assertTrue(!Double.isNaN(v));
    }

    @Test
    @DisplayName("Hard clipping at 2x Medium keeps the fundamental (QA_Oversampling_2x_aliasing_rejection)")
    void qaAliasingRejection2x()
    {
        final int blockSize = 1024;
        final double sr = 44100.0, freq = 8000.0;
        Oversampling os = new Oversampling(2, Quality.MEDIUM);
        os.prepare(sr, 1, blockSize);
        double[][] tb = new double[1][blockSize];
        for (int w = 0; w < 6; w++)
        {
            for (int i = 0; i < blockSize; i++) tb[0][i] = Math.sin(2.0 * PI * freq * i / sr);
            double[][] up = os.upsample(tb, blockSize);
            for (int i = 0; i < os.getHighRateLength(); i++) up[0][i] = Math.max(-0.3, Math.min(0.3, up[0][i]));
            os.downsample(tb, blockSize);
        }
        for (double v : tb[0]) assertTrue(Double.isFinite(v));
        assertTrue(toneAmplitude(tb[0], 0, blockSize, freq, sr) > 0.05);
    }

    @Test
    @DisplayName("4x Medium round trip SNR above 25 dB (QA_Oversampling_4x_roundtrip_SNR)")
    void qaRoundTrip4xSnr()
    {
        final int blockSize = 256;
        final double sr = 44100.0;
        Oversampling os = new Oversampling(4, Quality.MEDIUM);
        os.prepare(sr, 1, blockSize);
        double[][] tb = new double[1][blockSize];
        for (int w = 0; w < 20; w++)
        {
            for (int i = 0; i < blockSize; i++) tb[0][i] = Math.sin(2.0 * PI * 440.0 * i / sr);
            roundTrip(os, tb, 0, blockSize);
        }
        for (int i = 0; i < blockSize; i++) tb[0][i] = Math.sin(2.0 * PI * 440.0 * i / sr);
        double[] original = tb[0].clone();
        roundTrip(os, tb, 0, blockSize);
        final int latency = os.getLatency();
        final int start = latency + 32, end = blockSize - 32;
        assertTrue(end > start + 64);
        double errSum = 0.0, sigSum = 0.0;
        for (int i = start; i < end; i++)
        {
            final double err = tb[0][i] - original[i - latency];
            errSum += err * err;
            sigSum += original[i - latency] * original[i - latency];
        }
        assertTrue(-10.0 * Math.log10(errSum / (sigSum + 1e-30)) > 25.0);
    }

    @Test
    @DisplayName("Round trip gain is unity for a 1 kHz sine (QA_Oversampling_upsample_gain_unity)")
    void qaUpsampleGainUnity()
    {
        final int blockSize = 256;
        Oversampling os = new Oversampling(2, Quality.HIGH);
        os.prepare(48000.0, 1, blockSize);
        double[][] tb = new double[1][blockSize];
        for (int w = 0; w < 16; w++)
        {
            for (int i = 0; i < blockSize; i++) tb[0][i] = 0.5 * Math.sin(2.0 * PI * 1000.0 * i / 48000.0);
            roundTrip(os, tb, 0, blockSize);
        }
        double peak = 0.0;
        for (int i = os.getLatency() + 16; i < blockSize - 16; i++) peak = Math.max(peak, Math.abs(tb[0][i]));
        assertEquals(0.5, peak, 0.05);
    }

    private static double aliasingDb(double[] buf, int n, double baseRate)
    {
        final double nyquist = baseRate * 0.5, cutoff = nyquist * 0.45;
        double inBand = 0.0, alias = 0.0;
        for (int f = 1; f <= 200; f++)
        {
            final double freq = f * nyquist / 200.0;
            final double mag = toneAmplitude(buf, 0, n, freq, baseRate);
            if (freq <= cutoff) inBand += mag * mag;
            else alias += mag * mag;
        }
        if (inBand < 1e-20) return -120.0;
        return 10.0 * Math.log10(alias / inBand);
    }

    @Test
    @DisplayName("Oversampled clipping aliases no more than direct clipping (QA_Integration_oversampling_reduces_distortion_aliasing)")
    void qaOversamplingReducesAliasing()
    {
        final int blockSize = 2048;
        final double sr = 44100.0, freq = 5000.0;
        double[] direct = new double[blockSize];
        for (int i = 0; i < blockSize; i++) direct[i] = Math.max(-0.3, Math.min(0.3, Math.sin(2.0 * PI * freq * i / sr)));
        Oversampling os = new Oversampling(2, Quality.MEDIUM);
        os.prepare(sr, 1, blockSize);
        double[][] tb = new double[1][blockSize];
        for (int w = 0; w < 11; w++)
        {
            for (int i = 0; i < blockSize; i++) tb[0][i] = Math.sin(2.0 * PI * freq * i / sr);
            double[][] up = os.upsample(tb, blockSize);
            for (int i = 0; i < os.getHighRateLength(); i++) up[0][i] = Math.max(-0.3, Math.min(0.3, up[0][i]));
            os.downsample(tb, blockSize);
        }
        assertTrue(aliasingDb(tb[0], blockSize, sr) < aliasingDb(direct, blockSize, sr) + 1.0);
    }

    // ------------------------------------------------------------------
    // Figures documented in Oversampling.h
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Documented stage lengths and round-trip latencies, Preset and Deep")
    void documentedStageLengthsAndLatencies()
    {
        final int[][] preset = { { 31, 11, 7, 15 }, { 63, 19, 15, 15 }, { 127, 23, 23, 15 }, { 255, 31, 23, 31 } };
        final int[][] deep = { { 91, 35, 23, 31 }, { 115, 31, 23, 31 }, { 163, 31, 23, 31 }, { 255, 31, 23, 31 } };
        final Quality[] qs = Quality.values();
        for (int q = 0; q < 4; q++)
        {
            Oversampling p = new Oversampling(16, qs[q]);
            Oversampling d = new Oversampling(16, qs[q], Mode.LINEAR_PHASE_FIR, Depth.DEEP);
            for (int s = 0; s < 4; s++)
            {
                assertEquals(preset[q][s], p.getStageLength(s), qs[q] + " stage " + s);
                assertEquals(deep[q][s], d.getStageLength(s), qs[q] + " deep stage " + s);
            }
            assertEquals(0, p.getStageLength(4));
            assertEquals(0, p.getStageLength(-1));
        }
        final int[] highPreset = { 64, 70, 73, 74 }, highDeep = { 82, 90, 93, 95 };
        for (int i = 0; i < 4; i++)
        {
            assertEquals(highPreset[i], new Oversampling(FACTORS[i], Quality.HIGH).getLatency());
            assertEquals(highDeep[i], new Oversampling(FACTORS[i], Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.DEEP).getLatency());
        }
        // 31.5 base samples of decimation delay at 2x High.
        assertEquals(63, new Oversampling(2, Quality.HIGH).getDecimationDelay());
        assertEquals(0, new Oversampling(2, Quality.HIGH, Mode.POLYPHASE_IIR).getStageLength(0));
    }

    /** Peak of a half-band kernel over [edge, 1/2] of its rate on a fine grid, in dB. */
    private static double densePeakDb(Oversampling.StageDesign d, double edge)
    {
        final double[] h = FIRFilter.FIRDesign.lowPass(1.0, 0.25, d.taps, d.beta);
        final int half = (d.taps - 1) / 2, points = 20000;
        double peak = 0.0;
        for (int p = 0; p <= points; p++)
        {
            final double f = edge + (0.5 - edge) * p / points;
            double sum = h[half];
            for (int k = 1; k <= half; k++) sum += (h[half + k] + h[half - k]) * Math.cos(2.0 * PI * f * k);
            peak = Math.max(peak, Math.abs(sum));
        }
        return 20.0 * Math.log10(peak);
    }

    @Test
    @DisplayName("Documented stopband peaks of every stage over the band it protects")
    void documentedStagePeaks()
    {
        // NaN marks the three figures of the table that disagree with the
        // design they document (High stage 2 is the same design as Deep High
        // stage 2, listed at -122.3): those are held to the design rule only.
        final double[][] preset = { { -38.9, -43.7, -44.8, -91.9 }, { -59.5, -64.0, -78.0, -92.5 },
                                    { -79.2, -80.3, Double.NaN, -93.1 }, { -100.1, -109.4, Double.NaN, Double.NaN } };
        final double[][] deep = { { -104.0, -111.3, -117.7, -159.0 }, { -103.6, -102.2, -119.8, -159.4 },
                                  { -100.7, -106.3, -122.3, -160.1 } };
        final Quality[] qs = Quality.values();
        for (int q = 0; q < 4; q++)
        {
            final double stage0 = densePeakDb(Oversampling.firStageDesign(qs[q], Depth.PRESET, 0),
                                              0.25 + 0.5 * Oversampling.iirTransition(qs[q], 0));
            for (int s = 0; s < 4; s++)
            {
                final double edge = 0.25 + 0.5 * Oversampling.iirTransition(qs[q], s);
                Oversampling.StageDesign p = Oversampling.firStageDesign(qs[q], Depth.PRESET, s);
                final double peak = densePeakDb(p, edge);
                if (!Double.isNaN(preset[q][s])) assertEquals(preset[q][s], peak, 0.06, qs[q] + " stage " + s);
                assertTrue(peak <= stage0 + 1e-9, qs[q] + " stage " + s + " is no worse than stage 0");
                // The grid the fit measures on never reads deeper than 0.1 dB under the peak.
                final double grid = Oversampling.halfBandStopPeakDb(p.taps, p.beta, edge);
                assertTrue(grid <= peak + 1e-9 && peak - grid < 0.1, qs[q] + " stage " + s + " grid " + grid);
                if (q < 3)
                {
                    final double deepPeak = densePeakDb(Oversampling.firStageDesign(qs[q], Depth.DEEP, s), edge);
                    assertEquals(deep[q][s], deepPeak, 0.06, qs[q] + " deep stage " + s);
                    assertTrue(deepPeak <= -100.1, qs[q] + " deep stage " + s);
                }
            }
        }
    }

    @Test
    @DisplayName("The 2x round trip is the zero-stuffed cascade of the designed kernel, sample for sample")
    void roundTripIsTheTextbookCascade()
    {
        for (Quality q : Quality.values())
        {
            final Oversampling.StageDesign d = Oversampling.firStageDesign(q, Depth.PRESET, 0);
            final double[] h = FIRFilter.FIRDesign.lowPass(1.0, 0.25, d.taps, d.beta);
            final int n = 1024;
            final double[] x = lcgSignal(n);
            double[] stuffed = new double[2 * n];
            for (int i = 0; i < n; i++) stuffed[2 * i] = x[i];
            double[] up = new double[2 * n], down = new double[2 * n];
            for (int i = 0; i < 2 * n; i++)
            {
                double s = 0.0;
                for (int k = 0; k < h.length && k <= i; k++) s += 2.0 * h[k] * stuffed[i - k];
                up[i] = s;
            }
            for (int i = 0; i < 2 * n; i++)
            {
                double s = 0.0;
                for (int k = 0; k < h.length && k <= i; k++) s += h[k] * up[i - k];
                down[i] = s;
            }
            Oversampling os = new Oversampling(2, q);
            os.prepare(48000.0, 1, n);
            double[][] y = { x.clone() };
            roundTrip(os, y, 0, n);
            // The engine keeps the high-rate instants 2i - 2 of the full cascade.
            for (int i = 1; i < n; i++)
                assertEquals(down[2 * i - 2], y[0][i], 1e-14, q + " sample " + i);
        }
    }

    @Test
    @DisplayName("Passband flat to the preset's ripple and in-band gain within 0.01 dB, Preset and Deep")
    void passbandFlatness()
    {
        for (Quality q : Quality.values())
            for (Depth depth : Depth.values())
                for (int factor : new int[] { 2, 4 })
                {
                    Oversampling os = new Oversampling(factor, q, Mode.LINEAR_PHASE_FIR, depth);
                    os.prepare(48000.0, 1, 256);
                    final double edgeHz = (0.5 - Oversampling.firTransition(q)) * 48000.0;
                    final double delta = Math.pow(10.0, -Oversampling.firstStageDepthDb(q, depth) / 20.0);
                    for (double share : new double[] { 0.25, 0.6, 0.9 })
                    {
                        final double g = roundTripGain(os, 48000.0, share * edgeHz);
                        assertEquals(1.0, g, 3.0 * delta, q + " " + depth + " " + factor + "x at " + share * edgeHz);
                    }
                    // Low's 31-tap kernel is not exactly complementary (centre tap
                    // 0.50087 against 0.49913 for the others), which leaves 0.016 dB
                    // at 1 kHz; every other preset and depth is within 0.01 dB.
                    final double inBandDb = Math.abs(20.0 * Math.log10(roundTripGain(os, 48000.0, 1000.0)));
                    final boolean lowPreset = q == Quality.LOW && depth == Depth.PRESET;
                    assertTrue(inBandDb < (lowPreset ? 0.017 : 0.01), q + " " + depth + " " + factor + "x: " + inBandDb + " dB");
                }
    }

    @Test
    @DisplayName("Linear phase: the measured group delay equals getLatency() for every preset, depth and factor")
    void measuredGroupDelayEqualsLatency()
    {
        final double fs = 48000.0, f = 93.75;   // 4 cycles per 2048 samples
        final double w = 2.0 * PI * f / fs;
        for (Quality q : Quality.values())
            for (Depth depth : Depth.values())
                for (int factor : FACTORS)
                {
                    Oversampling os = new Oversampling(factor, q, Mode.LINEAR_PHASE_FIR, depth);
                    os.prepare(fs, 1, 512);
                    double[][] x = new double[1][512];
                    double c = 0.0, s = 0.0;
                    for (int b = 0; b < 12; b++)
                    {
                        for (int i = 0; i < 512; i++) x[0][i] = Math.sin(w * (b * 512 + i));
                        roundTrip(os, x, 0, 512);
                        if (b >= 4)
                            for (int i = 0; i < 512; i++)
                            {
                                final int m = b * 512 + i;
                                c += x[0][i] * Math.cos(w * m);
                                s += x[0][i] * Math.sin(w * m);
                            }
                    }
                    assertEquals(os.getLatency(), Math.atan2(-c, s) / w, 1e-6, q + " " + depth + " " + factor + "x");
                    assertEquals(os.getLatency(), os.getRoundTripDelay(5000.0), 0.0);
                }
    }

    @Test
    @DisplayName("Documented polyphase delays: 3.07 at 1 kHz and 4.23 at 18 kHz (2x High, 48 kHz); 2.27 with Band.AUDIBLE at 96 kHz")
    void documentedPolyphaseDelays()
    {
        Oversampling os = new Oversampling(2, Quality.HIGH, Mode.POLYPHASE_IIR);
        assertEquals(3.07, os.getRoundTripDelay(1000.0), 0.006);   // 48 kHz assumed before prepare()
        os.prepare(48000.0, 1, 64);
        assertEquals(3.07, os.getRoundTripDelay(1000.0), 0.006);
        assertEquals(4.23, os.getRoundTripDelay(18000.0), 0.006);

        Oversampling audible = new Oversampling(2, Quality.HIGH, Mode.POLYPHASE_IIR, Depth.PRESET, Band.AUDIBLE);
        audible.prepare(96000.0, 1, 64);
        assertEquals(2.27, audible.getRoundTripDelay(1000.0), 0.006);
        Oversampling preset = new Oversampling(2, Quality.HIGH, Mode.POLYPHASE_IIR);
        preset.prepare(96000.0, 1, 64);
        assertEquals(3.07, preset.getRoundTripDelay(1000.0), 0.01);
    }

    @Test
    @DisplayName("Documented polyphase depths: stage 0 Low 62, Medium 84, High 99, Maximum 109 dB; Deep over 100 dB")
    void documentedPolyphaseDepths()
    {
        final double[] stage0 = { 62.0, 84.0, 99.0, 109.0 };
        final Quality[] qs = Quality.values();
        for (int q = 0; q < 4; q++)
        {
            final double t0 = Oversampling.iirTransition(qs[q], 0);
            final int n = Oversampling.iirCoefficientCountOver(Oversampling.firstStageDepthDb(qs[q], Depth.PRESET), t0);
            assertEquals(stage0[q], Oversampling.iirAttenuationDb(n, t0), 0.5, qs[q].toString());
            for (int s = 0; s < 4; s++)
            {
                final double t = Oversampling.iirTransition(qs[q], s);
                final int deep = Oversampling.iirCoefficientCountOver(Oversampling.firstStageDepthDb(qs[q], Depth.DEEP), t);
                final double att = Oversampling.iirAttenuationDb(deep, t);
                assertTrue(att >= 100.1, qs[q] + " deep stage " + s + ": " + att);
                if (s == 0) assertTrue(att >= 104.0, qs[q] + " deep stage 0: " + att);
            }
        }
    }

    @Test
    @DisplayName("Documented polyphase worst alias per preset (Low -55, Medium -83, High -91, Maximum -107 dB) at 2x and 4x")
    void documentedPolyphaseAlias()
    {
        final double[] worst = { -55.0, -83.0, -91.0, -107.0 };
        final Quality[] qs = Quality.values();
        final double fs = 48000.0, bin = fs / 8192.0;
        for (int q = 0; q < 4; q++)
        {
            Oversampling two = new Oversampling(2, qs[q], Mode.POLYPHASE_IIR);
            two.prepare(fs, 1, 256);
            final double foldEdge = Math.floor((0.5 - Oversampling.firTransition(qs[q])) * 8192.0 - 2.0) * bin;
            for (double fold : new double[] { foldEdge, 18000.0, 1500.0 })
                assertTrue(aliasDb(two, fs, fs - fold, fold) < worst[q] + 0.5, qs[q] + " 2x fold " + fold);
            Oversampling four = new Oversampling(4, qs[q], Mode.POLYPHASE_IIR);
            four.prepare(fs, 1, 256);
            assertTrue(aliasDb(four, fs, 78000.0, 18000.0) < worst[q] + 0.5, qs[q] + " 4x 78 kHz");
            assertTrue(aliasDb(four, fs, 90000.0, 6000.0) < worst[q] + 0.5, qs[q] + " 4x 90 kHz");
        }
    }

    @Test
    @DisplayName("Documented Band.AUDIBLE figures at 96 kHz High: under -82.6 dB below 20 kHz, 19.9 kHz within 0.0006 dB, 30/40 kHz at 0.948/0.672")
    void documentedAudibleFigures()
    {
        Oversampling os = new Oversampling(2, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.AUDIBLE);
        os.prepare(96000.0, 1, 256);
        assertTrue(aliasDb(os, 96000.0, 78000.0, 18000.0) < -82.6);
        assertTrue(aliasDb(os, 96000.0, 76000.0, 20000.0) < -82.6);
        assertTrue(Math.abs(20.0 * Math.log10(roundTripGain(os, 96000.0, 19900.0))) < 0.0006);
        assertEquals(0.948, roundTripGain(os, 96000.0, 30000.0), 0.0006);
        assertEquals(0.672, roundTripGain(os, 96000.0, 40000.0), 0.0006);
    }

    @Test
    @DisplayName("Factor 1 is a pass-through with zero latency; factors are normalised to a power of two")
    void factorOneAndNormalisation()
    {
        for (Mode mode : Mode.values())
        {
            Oversampling os = new Oversampling(1, Quality.HIGH, mode);
            os.prepare(48000.0, 2, 64);
            assertEquals(0, os.getLatency());
            assertEquals(0, os.getDecimationDelay());
            assertEquals(0.0, os.getRoundTripDelay(10000.0), 0.0);
            double[] sig = lcgSignal(64);
            double[][] x = { sig.clone(), sig.clone() };
            double[][] up = os.upsample(x, 64);
            assertEquals(64, os.getHighRateLength());
            assertArrayEquals(sig, java.util.Arrays.copyOf(up[0], 64));
            os.downsample(x, 64);
            assertArrayEquals(sig, x[0]);
            assertArrayEquals(sig, x[1]);
        }
        assertEquals(2, new Oversampling(3).getFactor());
        assertEquals(1, new Oversampling(0).getFactor());
        assertEquals(1, new Oversampling(-4).getFactor());
        assertEquals(8, new Oversampling(15).getFactor());
        assertEquals(16, new Oversampling(1000).getFactor());
        Oversampling d = new Oversampling();
        assertEquals(2, d.getFactor());
        assertSame(Quality.HIGH, d.getQuality());
        assertSame(Mode.LINEAR_PHASE_FIR, d.getMode());
        assertSame(Depth.PRESET, d.getDepth());
        assertSame(Band.PRESET, d.getBand());
        assertEquals(Oversampling.firTransition(Quality.HIGH), d.getFirstStageTransition(), 0.0);
    }

    @Test
    @DisplayName("reset() equals a fresh engine, both modes")
    void resetEqualsFresh()
    {
        final double[] sig = lcgSignal(700);
        for (Mode mode : Mode.values())
        {
            Oversampling used = new Oversampling(4, Quality.HIGH, mode);
            used.prepare(48000.0, 1, 128);
            double[][] junk = { lcgSignal(300) };
            for (int p = 0; p < 300; p += 100) roundTrip(used, junk, p, 100);
            used.reset();
            Oversampling fresh = new Oversampling(4, Quality.HIGH, mode);
            fresh.prepare(48000.0, 1, 128);
            double[][] a = { sig.clone() }, b = { sig.clone() };
            for (int p = 0; p < 700; p += 100)
            {
                roundTrip(used, a, p, 100);
                roundTrip(fresh, b, p, 100);
            }
            assertArrayEquals(b[0], a[0], mode.toString());
        }
    }

    @Test
    @DisplayName("Before prepare() nothing is processed; longer blocks are truncated")
    void unpreparedAndTruncation()
    {
        Oversampling os = new Oversampling(2);
        double[][] x = { { 0.5, 0.25 } };
        assertEquals(0, os.upsample(x, 2).length);
        os.downsample(x, 2);
        assertArrayEquals(new double[] { 0.5, 0.25 }, x[0]);

        os.prepare(48000.0, 1, 16);
        double[][] y = { lcgSignal(40) };
        final double[] tail = java.util.Arrays.copyOfRange(y[0], 16, 40);
        os.upsample(y, 40);
        assertEquals(32, os.getHighRateLength());
        os.downsample(y, 40);
        assertArrayEquals(tail, java.util.Arrays.copyOfRange(y[0], 16, 40));
    }
}
