package com.dspark.effects;

import com.dspark.core.FFTRealDouble;
import com.dspark.core.Oversampling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OversampledClipper}: the assertions of the C++ Clipper tests, its documented figures and properties. */
class OversampledClipperTest
{
    private static final double TWO_PI = 2.0 * Math.PI;

    // -- Helpers of the C++ tests -------------------------------------------------

    /** Coherent amplitude of frequency f over x[from, from + n). */
    private static double toneAmplitude(double[] x, int from, int n, double f, double fs)
    {
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double w = TWO_PI * f * i / fs;
            re += x[from + i] * Math.cos(w);
            im += x[from + i] * Math.sin(w);
        }
        return 2.0 * Math.sqrt(re * re + im * im) / n;
    }

    /** A mono sine through a processor in blocks, continuing at sample t[0]. */
    private static double[] runSine(OversampledClipper p, double fs, int block, int total, double f, double amp,
                                    long[] t, boolean asFloat)
    {
        final double[] out = new double[total];
        final double[] buf = new double[block];
        for (int pos = 0; pos < total; pos += block)
        {
            for (int i = 0; i < block; ++i)
            {
                final double v = amp * Math.sin(TWO_PI * f * (double) (t[0]++) / fs);
                buf[i] = asFloat ? (float) v : v;
            }
            p.process(new double[][] { buf }, block);
            System.arraycopy(buf, 0, out, pos, Math.min(block, total - pos));
        }
        return out;
    }

    private static double maxStep(double[] x)
    {
        double m = 0.0;
        for (int i = 1; i < x.length; ++i) m = Math.max(m, Math.abs(x[i] - x[i - 1]));
        return m;
    }

    private static double toDb(double x)
    {
        return 20.0 * Math.log10(Math.max(x, 1e-30));
    }

    /** Worst non-harmonic component below 20 kHz relative to a coherent tone at odd bin b. */
    private static double worstSpurDbc(double[] frame, int b, double fs)
    {
        final int n = frame.length;
        final double[] spec = new double[n + 2];
        new FFTRealDouble(n).forward(frame, spec);
        final double fund = Math.hypot(spec[2 * b], spec[2 * b + 1]);
        double worst = 0.0;
        final int maxBin = (int) (20000.0 / fs * n);
        for (int k = 1; k <= maxBin; ++k)
            if (k % b != 0) worst = Math.max(worst, Math.hypot(spec[2 * k], spec[2 * k + 1]));
        return toDb(worst / fund);
    }

    private static int oddBin(double f, double fs, int n)
    {
        int b = (int) Math.round(f / fs * n);
        if ((b & 1) == 0) ++b;
        return b;
    }

    /** Worst spur over coherent -6 dBFS tones (7 / 12.75 / 15.25 / 19.25 kHz) after 0.2 s. */
    private static double worstToneSpurDbc(Supplier<OversampledClipper> make)
    {
        final double fs = 48000.0;
        final int n = 16384;
        double w = -400.0;
        for (final double f : new double[] { 7000.0, 12750.0, 15250.0, 19250.0 })
        {
            final int b = oddBin(f, fs, n);
            final double[] y = runSine(make.get(), fs, 512, 9600 + n, b * fs / n, 0.5, new long[1], false);
            w = Math.max(w, worstSpurDbc(Arrays.copyOfRange(y, 9600, 9600 + n), b, fs));
        }
        return w;
    }

    /** Gain of a 0.01 tone at f after 0.25 s. */
    private static double toneGain(Supplier<OversampledClipper> make, double f)
    {
        final double[] y = runSine(make.get(), 48000.0, 512, 24000, f, 0.01, new long[1], false);
        return toneAmplitude(y, 12000, 12000, f, 48000.0) / 0.01;
    }

    /** A stereo tone in blocks of 256; returns the left channel. */
    private static double[] toneThrough(OversampledClipper p, double freq, float amp, int length)
    {
        final double[] outL = new double[length];
        final double[] l = new double[256], r = new double[256];
        for (int off = 0; off < length; off += 256)
        {
            final int n = Math.min(256, length - off);
            for (int i = 0; i < n; ++i)
            {
                final float x = amp * (float) Math.sin(2.0 * Math.PI * freq * (off + i) / 48000.0);
                l[i] = x;
                r[i] = x;
            }
            p.process(new double[][] { l, r }, n);
            System.arraycopy(l, 0, outL, off, n);
        }
        return outL;
    }

    private static OversampledClipper make(OversampledClipper.Mode mode, int factor, double gainDb, double mix,
                                           boolean antialias, double fs, int block, int channels)
    {
        final OversampledClipper c = new OversampledClipper();
        c.setMode(mode);
        c.setOversampling(factor);
        c.setInputGain(gainDb);
        c.setMix(mix);
        c.setAntialiasing(antialias);
        c.prepare(fs, channels, block);
        return c;
    }

    // -- TestEffectsTone.cpp ----------------------------------------------------------

    @Test
    @DisplayName("Non-finite setters and invalid prepare() values are ignored; unprepared passes through")
    void invalidInputsAreIgnored()
    {
        final OversampledClipper dut = new OversampledClipper(), twin = new OversampledClipper();
        for (final OversampledClipper c : new OversampledClipper[] { dut, twin })
        {
            c.setMode(OversampledClipper.Mode.SOFT);
            c.setCeiling(-1.0);
            c.setInputGain(12.0);
            c.setSlewLimit(0.3);
            c.setMix(0.8);
            c.setOversampling(2);
            c.prepare(48000.0, 2, 512);
        }
        dut.setCeiling(Double.NaN);
        dut.setCeiling(Double.POSITIVE_INFINITY);
        dut.setInputGain(Double.NaN);
        dut.setInputGain(Double.NEGATIVE_INFINITY);
        dut.setSlewLimit(Double.NaN);
        dut.setMix(Double.NaN);
        dut.prepare(48000.0, -3, 512);
        dut.prepare(Double.NaN, 2, 0);
        assertEquals(-1.0, dut.getCeiling(), 1e-6);
        assertEquals(12.0, dut.getInputGain(), 1e-6);
        assertEquals(0.3, dut.getSlewLimit(), 1e-6);
        assertEquals(0.8, dut.getMix(), 1e-6);
        dut.setMode(null);
        assertSame(OversampledClipper.Mode.SOFT, dut.getMode());

        double maxDiff = 0.0;
        int nonFinite = 0;
        for (int k = 0; k < 15; ++k)
        {
            final double[][] ta = new double[2][512], tb = new double[2][512];
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < 512; ++i)
                {
                    final float v = 0.9f * (float) Math.sin(2.0f * 3.14159265f * (220.0f + 200.0f * ch)
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

        final OversampledClipper fresh = new OversampledClipper();
        final double[] tf = new double[256];
        for (int i = 0; i < 256; ++i) tf[i] = 1.5f * (float) Math.sin(2.0f * 3.14159265f * 300.0f * (float) i / 48000.0f);
        final double[] orig = tf.clone();
        fresh.process(new double[][] { tf }, 256);
        assertTrue(Arrays.equals(orig, tf));
    }

    @Test
    @DisplayName("Every mode is bounded by the ceiling under drive and passes quiet material at unity")
    void modesClipToCeilingAndPassQuiet()
    {
        final double ceiling = Math.pow(10.0f, -1.0f / 20.0f);
        for (final OversampledClipper.Mode m : OversampledClipper.Mode.values())
        {
            final OversampledClipper c = new OversampledClipper();
            c.setMode(m);
            c.setCeiling(-1.0);
            c.setInputGain(18.0);
            c.prepare(48000.0, 1, 512);
            final double[] tb = new double[512];
            final float inc = (float) (2.0 * Math.PI) * 997.0f / 48000.0f;
            for (int i = 0; i < 512; ++i) tb[i] = 0.9f * (float) Math.sin(inc * (float) i);
            c.process(new double[][] { tb }, 512);
            double pk = 0.0;
            for (final double v : tb) pk = Math.max(pk, Math.abs(v));
            assertTrue(pk < ceiling * 1.001, m + " " + pk);
            assertTrue(pk > ceiling * 0.5, m + " " + pk);

            final OversampledClipper q = new OversampledClipper();
            q.setMode(m);
            q.setCeiling(0.0);
            q.prepare(48000.0, 1, 512);
            final double[] tq = new double[512];
            for (int i = 0; i < 512; ++i) tq[i] = 0.05f * (float) Math.sin(inc * (float) i);
            final double[] orig = tq.clone();
            q.process(new double[][] { tq }, 512);
            double quietDiff = 0.0;
            for (int i = 0; i < 512; ++i) quietDiff = Math.max(quietDiff, Math.abs(tq[i] - orig[i]));
            assertTrue(quietDiff < 2e-4, m + " " + quietDiff);
        }
    }

    @Test
    @DisplayName("Input gain glides over 20 ms instead of stepping")
    void inputGainGlides()
    {
        final OversampledClipper c = new OversampledClipper();
        c.setMode(OversampledClipper.Mode.HARD);
        c.prepare(48000.0, 1, 512);
        final double[] tb = new double[512];
        for (int blk = 0; blk < 12; ++blk)
        {
            if (blk == 10) c.setInputGain(12.0);
            for (int i = 0; i < 512; ++i)
                tb[i] = 0.05f * (float) Math.sin(6.2831853f * 441.0f * (float) (blk * 512 + i) / 48000.0f);
            final double[] in = tb.clone();
            c.process(new double[][] { tb }, 512);
            if (blk == 10)
            {
                double num = 0.0, den = 0.0;
                for (int i = 0; i < 48; ++i)
                {
                    num += tb[i] * in[i];
                    den += in[i] * in[i];
                }
                assertTrue(num / den < 1.3);
            }
            if (blk == 11)
            {
                double num = 0.0, den = 0.0;
                for (int i = 500; i < 512; ++i)
                {
                    num += tb[i] * in[i];
                    den += in[i] * in[i];
                }
                assertEquals(Math.pow(10.0, 12.0 / 20.0), num / den, 1e-3);
            }
        }
    }

    @Test
    @DisplayName("Minimum-phase oversampling: the dry copy crosses the same filters, so dry and wet agree")
    void minimumPhaseOversamplingMixesInPhase()
    {
        final int[] latency = new int[2];
        for (final OversampledClipper.OversamplingMode mode : OversampledClipper.OversamplingMode.values())
        {
            final double[][] out = new double[2][];
            for (int wet = 0; wet < 2; ++wet)
            {
                final OversampledClipper c = new OversampledClipper();
                c.setMode(OversampledClipper.Mode.HARD);
                c.setCeiling(0.0);
                c.setMix(wet == 1 ? 1.0 : 0.0);
                c.setOversampling(2);
                c.setOversamplingMode(mode);
                c.prepare(48000.0, 2, 256);
                assertSame(mode, c.getOversamplingMode());
                latency[mode.ordinal()] = c.getLatency();
                out[wet] = toneThrough(c, 18000.0, 0.25f, 9600);
            }
            if (mode == OversampledClipper.OversamplingMode.MINIMUM_PHASE)
            {
                double worst = 0.0;
                for (int i = 2400; i < 9600; ++i) worst = Math.max(worst, Math.abs(out[0][i] - out[1][i]));
                assertTrue(worst < 1e-5, "worst " + worst);
            }
        }
        assertTrue(latency[1] < latency[0]);
    }

    @Test
    @DisplayName("The audible oversampling band: 56 / 32 / 10 samples at 2x and 44.1 / 48 / 96 kHz (64 with the preset)")
    void audibleOversamplingBand()
    {
        final double[] rates = { 44100.0, 48000.0, 96000.0 };
        final int[] expected = { 56, 32, 10 };
        for (int r = 0; r < 3; ++r)
        {
            final OversampledClipper clip = new OversampledClipper();
            clip.setOversampling(2);
            clip.prepare(rates[r], 2, 512);
            assertEquals(64, clip.getLatency());
            clip.setOversamplingBand(Oversampling.Band.AUDIBLE);
            assertSame(Oversampling.Band.AUDIBLE, clip.getOversamplingBand());
            clip.prepare(rates[r], 2, 512);
            assertEquals(expected[r], clip.getLatency());
        }
    }

    // -- TestAnalogAudit.cpp ----------------------------------------------------------

    @Test
    @DisplayName("Blocks of 1024 (in pieces of the prepared 256) and of 256 agree")
    void longBlocksAreProcessedInPieces()
    {
        final double[] x = new double[4096];
        for (int i = 0; i < x.length; ++i) x[i] = (float) (0.3 * Math.sin(0.05 * i));
        final double[] ya = x.clone(), yb = x.clone();
        final OversampledClipper a = new OversampledClipper(), b = new OversampledClipper();
        for (final OversampledClipper p : new OversampledClipper[] { a, b })
        {
            p.setOversampling(2);
            p.prepare(48000.0, 1, 256);
            p.setInputGain(24.0);
        }
        for (int off = 0; off < 4096; off += 1024) a.process(new double[][] { ya }, off, 1024);
        for (int off = 0; off < 4096; off += 256) b.process(new double[][] { yb }, off, 256);
        double worst = 0.0;
        for (int i = 0; i < 4096; ++i) worst = Math.max(worst, Math.abs(ya[i] - yb[i]));
        assertTrue(worst < 1e-6, "worst " + worst);
    }

    @Test
    @DisplayName("A change of the stage count keeps the total gain")
    void stageChangeKeepsTotalGain()
    {
        final double fs = 48000.0;
        final OversampledClipper clip = new OversampledClipper();
        clip.prepare(fs, 1, 64);
        clip.setInputGain(18.0);
        final long[] t = { 0 };
        runSine(clip, fs, 64, 9600, 220.0, 0.1, t, true);
        final double[] before = runSine(clip, fs, 64, 9600, 220.0, 0.1, t, true);
        clip.setStages(4);
        final double[] after = runSine(clip, fs, 64, 9600, 220.0, 0.1, t, true);
        assertTrue(maxStep(after) < 1.5 * maxStep(before));
    }

    @Test
    @DisplayName("Second-order antialiasing: -38 dBc at 2x and +24 dB, 15 dB better, two base samples, aligned dry path")
    void secondOrderAntialiasing()
    {
        final double off = worstToneSpurDbc(() -> make(OversampledClipper.Mode.HARD, 2, 24.0, 1.0, false, 48000.0, 512, 1));
        final double on = worstToneSpurDbc(() -> make(OversampledClipper.Mode.HARD, 2, 24.0, 1.0, true, 48000.0, 512, 1));
        System.out.printf("    hard clip, 2x, +24 dB: worst spur %.1f dBc with antialiasing, %.1f without%n", on, off);
        assertTrue(on < -38.0);
        assertTrue(on < off - 15.0);
        assertEquals(make(OversampledClipper.Mode.HARD, 2, 24.0, 1.0, false, 48000.0, 512, 1).getLatency() + 2,
                     make(OversampledClipper.Mode.HARD, 2, 24.0, 1.0, true, 48000.0, 512, 1).getLatency());
        final double half = toneGain(() -> make(OversampledClipper.Mode.HARD, 2, 0.0, 0.5, true, 48000.0, 512, 1), 15000.0);
        final double full = toneGain(() -> make(OversampledClipper.Mode.HARD, 2, 0.0, 1.0, true, 48000.0, 512, 1), 15000.0);
        assertEquals(0.5 * (1.0 + full), half, 1e-3);
    }

    @Test
    @DisplayName("At 16x every decimation stage is 100 dB deep: -105 dBc; latencies 73 at 8x and 95 at 16x")
    void decimatesDeepAtHighFactors()
    {
        final double fs = 48000.0;
        final int n = 16384;
        final OversampledClipper p = make(OversampledClipper.Mode.HARD, 16, 24.0, 1.0, true, fs, 512, 1);
        final int b = oddBin(19250.0, fs, n);
        final int settle = 48000 + p.getLatency();
        final int total = (settle + n + 511) / 512 * 512;
        final double[] y = new double[total];
        for (int i = 0; i < total; ++i) y[i] = 0.5 * Math.sin(TWO_PI * (b * fs / n) * i / fs);
        for (int pos = 0; pos < total; pos += 512) p.process(new double[][] { y }, pos, 512);
        final double[] spectrum = new double[n + 2];
        new FFTRealDouble(n).forward(Arrays.copyOfRange(y, settle, settle + n), spectrum);
        double w = 0.0;
        for (int k = (int) Math.ceil(20.0 / fs * n); k <= (int) (20000.0 / fs * n); ++k)
            if (k % b != 0) w = Math.max(w, Math.hypot(spectrum[2 * k], spectrum[2 * k + 1]));
        final double worst = toDb(w / Math.hypot(spectrum[2 * b], spectrum[2 * b + 1]));
        System.out.printf("    hard clip, 16x, +24 dB, antialiased: worst spur %.1f dBc%n", worst);
        assertTrue(worst < -105.0);
        assertEquals(73, make(OversampledClipper.Mode.HARD, 8, 24.0, 1.0, false, fs, 512, 1).getLatency());
        assertEquals(95, make(OversampledClipper.Mode.HARD, 16, 24.0, 1.0, false, fs, 512, 1).getLatency());
    }

    // -- Documented figures and properties ------------------------------------------

    @Test
    @DisplayName("The antialiasing delay is ceil(4 / factor) base samples and its kernel droops by (1 + 2 cos w) / 3")
    void antialiasingDelayAndDroop()
    {
        final int[] factors = { 1, 2, 4, 8, 16 };
        final int[] extra = { 4, 2, 1, 1, 1 };
        for (int k = 0; k < factors.length; ++k)
        {
            final OversampledClipper c = make(OversampledClipper.Mode.HARD, factors[k], 0.0, 1.0, false, 48000.0, 512, 1);
            final int plain = c.getLatency();
            c.setAntialiasing(true);
            assertEquals(plain + extra[k], c.getLatency(), "factor " + factors[k]);
        }
        final double[][] cases = { { 2, 10000.0, -1.3 }, { 2, 20000.0, -5.9 }, { 4, 20000.0, -1.3 } };
        for (final double[] c : cases)
        {
            final int factor = (int) c[0];
            final double on = toneGain(() -> make(OversampledClipper.Mode.HARD, factor, 0.0, 1.0, true, 48000.0, 512, 1), c[1]);
            final double offGain = toneGain(() -> make(OversampledClipper.Mode.HARD, factor, 0.0, 1.0, false, 48000.0, 512, 1), c[1]);
            final double w = TWO_PI * c[1] / (48000.0 * factor);
            final double kernelDb = toDb((1.0 + 2.0 * Math.cos(w)) / 3.0);
            final double measured = toDb(on / offGain);
            System.out.printf("    %dx, %.0f Hz: kernel %.2f dB (formula %.2f, documented %.1f)%n", factor, c[1], measured, kernelDb, c[2]);
            assertEquals(kernelDb, measured, 0.01);
            assertEquals(c[2], measured, 0.05);
        }
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 samples give the same output; reset() equals a fresh instance")
    void blockSizeIndependenceAndReset()
    {
        final double[] in = new double[12288];
        int s = 17;
        for (int i = 0; i < in.length; ++i)
        {
            s = s * 1664525 + 1013904223;
            in[i] = 1.2 * Math.sin(TWO_PI * 700.0 * i / 48000.0) + 0.3 * ((double) s / 2147483648.0);
        }
        for (final boolean antialias : new boolean[] { false, true })
        {
            double[] ref = null;
            for (final int block : new int[] { 4096, 512, 37, 1 })
            {
                final OversampledClipper c = make(OversampledClipper.Mode.HARD, 4, 0.0, 1.0, antialias, 48000.0, 4096, 1);
                c.setCeiling(-3.0);
                c.reset();
                final double[] y = in.clone();
                for (int off = 0; off < y.length; off += block) c.process(new double[][] { y }, off, Math.min(block, y.length - off));
                assertTrue(CrossoverSignals.allFinite(y));
                if (ref == null) ref = y;
                else
                {
                    double worst = 0.0;
                    for (int i = 0; i < y.length; ++i) worst = Math.max(worst, Math.abs(y[i] - ref[i]));
                    assertTrue(worst < 1e-12, "antialias " + antialias + " block " + block + ": " + worst);
                }
            }
            final OversampledClipper used = make(OversampledClipper.Mode.HARD, 4, 6.0, 0.7, antialias, 48000.0, 512, 1);
            final double[] warm = in.clone();
            used.process(new double[][] { warm }, warm.length);
            used.reset();
            final double[] a = in.clone(), b = in.clone();
            used.process(new double[][] { a }, a.length);
            make(OversampledClipper.Mode.HARD, 4, 6.0, 0.7, antialias, 48000.0, 512, 1).process(new double[][] { b }, b.length);
            assertTrue(Arrays.equals(a, b), "reset, antialias " + antialias);
        }
    }

    @Test
    @DisplayName("The reported latency is the measured delay of a quiet impulse, linear and minimum phase")
    void latencyIsTheMeasuredDelay()
    {
        for (final int factor : new int[] { 1, 2, 4 })
        {
            final OversampledClipper c = make(OversampledClipper.Mode.HARD, factor, 0.0, 1.0, factor == 4, 48000.0, 512, 1);
            final double[] x = new double[2048];
            x[100] = 0.5;
            c.process(new double[][] { x }, x.length);
            int argmax = 0;
            for (int i = 0; i < x.length; ++i) if (Math.abs(x[i]) > Math.abs(x[argmax])) argmax = i;
            assertEquals(100 + c.getLatency(), argmax, "factor " + factor);
        }
        final double[] rates = { 44100.0, 48000.0, 96000.0 };
        for (final double rate : rates)
            System.out.printf("    %.1f kHz: latency at 4x %d samples%n", rate / 1000.0,
                              make(OversampledClipper.Mode.HARD, 4, 0.0, 1.0, false, rate, 512, 2).getLatency());
    }
}
