package com.dspark.effects;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import static com.dspark.effects.SaturationSignals.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Saturation cases of the C++ TestAnalogAudit.cpp, with their signals and thresholds. */
class SaturationAuditTest
{
    @Test
    void reprepareKeepsParameters()
    {
        final double fs = 48000.0;
        final Saturation sat = new Saturation();
        sat.prepare(fs, 1, 512);
        sat.setDrive(24.0);
        sat.setMix(0.5);
        sat.setOutputGain(-6.0);
        final long[] t = { 0 };
        final double[] a = runSine(sat, fs, 512, 30720, 1000.0, 0.1, t);
        sat.prepare(fs, 1, 512);
        final double[] b = runSine(sat, fs, 512, 30720, 1000.0, 0.1, t);
        final double ra = toDb(toneAmplitude(a, 15360, 15360, 1000.0, fs));
        final double rb = toDb(toneAmplitude(b, 15360, 15360, 1000.0, fs));
        assertTrue(Math.abs(ra - rb) < 0.05, ra + " vs " + rb);
    }

    /** One 1024-sample block against four 256-sample blocks. */
    private static double oversizeCheck(Supplier<Saturation> make)
    {
        final Saturation a = make.get(), b = make.get();
        final double[] x = new double[4096];
        for (int i = 0; i < x.length; ++i) x[i] = (float) (0.3 * Math.sin(0.05 * i));
        final double[] ya = x.clone(), yb = x.clone();
        for (int off = 0; off < x.length; off += 1024)
        {
            final double[][] ch = { Arrays.copyOfRange(ya, off, off + 1024) };
            a.process(ch, 1024);
            System.arraycopy(ch[0], 0, ya, off, 1024);
        }
        for (int off = 0; off < x.length; off += 256)
        {
            final double[][] ch = { Arrays.copyOfRange(yb, off, off + 256) };
            b.process(ch, 256);
            System.arraycopy(ch[0], 0, yb, off, 256);
        }
        double worst = 0.0;
        for (int i = 0; i < x.length; ++i) worst = Math.max(worst, Math.abs(ya[i] - yb[i]));
        return worst;
    }

    @Test
    void oversizeBlocksEqualSplitBlocks()
    {
        assertTrue(oversizeCheck(() -> { final Saturation p = new Saturation(); p.prepare(48000.0, 1, 256); p.setDrive(12.0); return p; }) < 1e-6);
        assertTrue(oversizeCheck(() -> { final Saturation p = new Saturation(); p.setOversampling(1); p.prepare(48000.0, 1, 256); p.setAnalogDrift(0.8); return p; }) < 1e-6);
    }

    private static double worstHardClip(double drive, boolean adaa)
    {
        final double fs = 48000.0;
        final int n = 16384;
        double w = -400.0;
        for (double f : new double[] { 5000.0, 9000.0, 15250.0 })
        {
            int b = (int) SaturationDetail.lround(f / fs * n);
            if ((b & 1) == 0) ++b;
            final Saturation sat = new Saturation();
            sat.prepare(fs, 1, 512);
            sat.setAlgorithm(Saturation.Algorithm.HARD_CLIP);
            sat.setDrive(drive);
            sat.setAntialiasing(adaa);
            final long[] t = { 0 };
            final double[] y = runSine(sat, fs, 512, 14400 + n, b * fs / n, 0.5, t);
            w = Math.max(w, worstSpurDbc(y, 14400, n, b, fs));
        }
        return w;
    }

    @Test
    void hardclipAdaaReducesAliasing()
    {
        assertTrue(worstHardClip(6.0, true) < -100.0, "6 dB: " + worstHardClip(6.0, true));
        assertTrue(worstHardClip(12.0, true) < worstHardClip(12.0, false) - 3.0);
    }

    @Test
    void tubeLowLevelIsClean()
    {
        final double fs = 48000.0;
        final int n = 32768;
        int b = (int) SaturationDetail.lround(1000.0 / fs * n);
        if ((b & 1) == 0) ++b;
        final double f = b * fs / n;
        final Saturation sat = new Saturation();
        sat.prepare(fs, 1, 512);
        sat.setAlgorithm(Saturation.Algorithm.TUBE);
        final long[] t = { 0 };
        final double[] y = runSine(sat, fs, 512, 24000 + n, f, 0.001, t);
        final double h1 = toneAmplitude(y, 24000, n, f, fs);
        final double h2 = toneAmplitude(y, 24000, n, 2.0 * f, fs);
        assertTrue(toDb(h2 / h1) < -60.0);
    }

    private static double t63(int factor)
    {
        final double fs = 48000.0;
        final Saturation sat = new Saturation();
        sat.setOversampling(factor);
        sat.prepare(fs, 1, 64);
        sat.setAlgorithm(Saturation.Algorithm.HARD_CLIP);
        final long[] t = { 0 };
        runSine(sat, fs, 64, 24000, 4000.0, 0.01, t);
        sat.setDrive(12.0);
        final double[] y = runSine(sat, fs, 64, 9600, 4000.0, 0.01, t);
        final double target = 0.01 * (1.0 + 0.63 * (Math.pow(10.0, 12.0 / 20.0) - 1.0));
        final double latency = sat.getLatency();
        for (int i = 0; i + 12 <= y.length; i += 12)
        {
            double pk = 0.0;
            for (int j = i; j < i + 12; ++j) pk = Math.max(pk, Math.abs(y[j]));
            if (pk >= target) return (i - latency) * 1000.0 / fs;
        }
        return 1e9;
    }

    @Test
    void smoothingIgnoresOversampling()
    {
        assertTrue(Math.abs(t63(1) - t63(2)) < 3.0, t63(1) + " vs " + t63(2));
    }

    private static double[] crossfadeRun(double[] x, int block)
    {
        final int total = x.length, change = 9600;
        final Saturation sat = new Saturation();
        sat.prepare(48000.0, 1, 512);
        sat.setDrive(12.0);
        final double[] y = x.clone();
        for (int off = 0; off < total;)
        {
            if (off == change)
            {
                sat.setAlgorithm(Saturation.Algorithm.HARD_CLIP);
                sat.setDrive(24.0);
            }
            int bs = Math.min(block, total - off);
            if (off < change) bs = Math.min(bs, change - off);
            final double[][] ch = { Arrays.copyOfRange(y, off, off + bs) };
            sat.process(ch, bs);
            System.arraycopy(ch[0], 0, y, off, bs);
            off += bs;
        }
        return y;
    }

    @Test
    void crossfadeIsPartitionIndependent()
    {
        final double fs = 48000.0;
        final int total = 24000;
        final double[] x = new double[total];
        for (int i = 0; i < total; ++i)
            x[i] = (float) (0.4 * Math.sin(TWO_PI * 220.0 * i / fs) + 0.2 * Math.sin(TWO_PI * 5100.0 * i / fs));
        final double[] a = crossfadeRun(x, 512);
        final double[] b = crossfadeRun(x, 1);
        double worst = 0.0;
        for (int i = 0; i < a.length; ++i) worst = Math.max(worst, Math.abs(a[i] - b[i]));
        assertTrue(worst < 1e-4, "worst " + worst);
    }

    @Test
    void rejectsUnsupportedFactor()
    {
        final Saturation sat = new Saturation();
        sat.setOversampling(32);
        sat.prepare(48000.0, 1, 64);
        assertEquals(2, sat.getOversamplingFactor());
    }

    @Test
    void adaaEnableIsClickFree()
    {
        final double fs = 48000.0;
        final Saturation sat = new Saturation();
        sat.setOversampling(1);
        sat.prepare(fs, 1, 64);
        sat.setDrive(12.0);
        final long[] t = { 0 };
        runSine(sat, fs, 64, 24000, 220.0, 0.1, t);
        final double[] before = runSine(sat, fs, 64, 9600, 220.0, 0.1, t);
        sat.setAntialiasing(true);
        final double[] after = runSine(sat, fs, 64, 9600, 220.0, 0.1, t);
        assertTrue(maxStep(after, 0, after.length) < 2.0 * maxStep(before, 0, before.length));
    }

    private static Supplier<Saturation> softClip(Saturation.Antialiasing order, double drive, double mix)
    {
        return () -> {
            final Saturation s = new Saturation();
            s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
            s.setDrive(drive);
            s.setMix(mix);
            s.setDcBlocking(false);
            s.setAntialiasing(order);
            s.prepare(48000.0, 1, 512);
            return s;
        };
    }

    @Test
    void secondOrderAntialiasing()
    {
        final double first = worstToneSpurDbc(softClip(Saturation.Antialiasing.FIRST_ORDER, 24.0, 1.0));
        final double second = worstToneSpurDbc(softClip(Saturation.Antialiasing.SECOND_ORDER, 24.0, 1.0));
        assertTrue(second < -40.0, "second " + second);
        assertTrue(second < first - 8.0, first + " / " + second);
        assertEquals(softClip(Saturation.Antialiasing.OFF, 0.0, 1.0).get().getLatency() + 2,
                     softClip(Saturation.Antialiasing.SECOND_ORDER, 0.0, 1.0).get().getLatency());
        assertEquals(0.5 * (1.0 + toneGain(softClip(Saturation.Antialiasing.SECOND_ORDER, 0.0, 1.0), 15000.0)),
                     toneGain(softClip(Saturation.Antialiasing.SECOND_ORDER, 0.0, 0.5), 15000.0), 1e-3);
    }

    private static Supplier<Saturation> make(Saturation.Algorithm algo, int factor, Saturation.Antialiasing order)
    {
        return () -> {
            final Saturation s = new Saturation();
            s.setAlgorithm(algo);
            s.setOversampling(factor);
            s.setDrive(24.0);
            s.setDcBlocking(false);
            s.setAntialiasing(order);
            s.prepare(48000.0, 1, 512);
            return s;
        };
    }

    private static double[] quiet(Saturation.Algorithm algo, int factor, Saturation.Antialiasing order)
    {
        final Saturation s = new Saturation();
        s.setAlgorithm(algo);
        s.setOversampling(factor);
        s.setDcBlocking(false);
        s.setAntialiasing(order);
        s.prepare(48000.0, 1, 512);
        return runSineDouble(s, 48000.0, 512, 24000, 200.0, 0.01);
    }

    @Test
    void exciterTapeMultistageSecondOrder()
    {
        final Saturation.Antialiasing so = Saturation.Antialiasing.SECOND_ORDER;
        assertTrue(worstToneSpurDbc(make(Saturation.Algorithm.EXCITER, 2, so)) < -25.0);
        assertTrue(worstToneSpurDbc(make(Saturation.Algorithm.TAPE, 2, so)) < -35.0);
        assertTrue(worstToneSpurDbc(make(Saturation.Algorithm.MULTI_STAGE, 2, so)) < -25.0);
        assertTrue(worstToneSpurDbc(make(Saturation.Algorithm.MULTI_STAGE, 4, so)) < -45.0);
        for (int factor : new int[] { 1, 2, 4, 8 })
            assertEquals(make(Saturation.Algorithm.MULTI_STAGE, factor, Saturation.Antialiasing.OFF).get().getLatency()
                             + (factor == 2 ? 2 : 1),
                         make(Saturation.Algorithm.MULTI_STAGE, factor, so).get().getLatency());

        for (int factor : new int[] { 1, 2, 4, 8 })
            for (Saturation.Algorithm algo : new Saturation.Algorithm[] { Saturation.Algorithm.MULTI_STAGE, Saturation.Algorithm.TAPE,
                                                                          Saturation.Algorithm.EXCITER, Saturation.Algorithm.SOFT_CLIP })
            {
                final double[] off = quiet(algo, factor, Saturation.Antialiasing.OFF);
                final double[] second = quiet(algo, factor, so);
                final int shift = factor == 2 ? 2 : 1;
                double err = 0.0, ref = 0.0;
                for (int i = 12000; i < off.length; ++i)
                {
                    err += (second[i] - off[i - shift]) * (second[i] - off[i - shift]);
                    ref += off[i] * off[i];
                }
                assertTrue(10.0 * Math.log10(err / ref) < -55.0, algo + " at " + factor + "x: " + 10.0 * Math.log10(err / ref));
            }
    }

    private static Supplier<Saturation> multiStage(double drive)
    {
        return () -> {
            final Saturation s = new Saturation();
            s.setAlgorithm(Saturation.Algorithm.MULTI_STAGE);
            s.setDrive(drive);
            s.setDcBlocking(false);
            s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
            s.prepare(48000.0, 1, 512);
            return s;
        };
    }

    private enum Case { SWITCH, MID_ONLY, DELTA, HALF_MIX }

    private static double[] runCase(Case c, Saturation.Antialiasing order)
    {
        final Saturation s = new Saturation();
        s.setAlgorithm(c == Case.SWITCH ? Saturation.Algorithm.SOFT_CLIP : Saturation.Algorithm.MULTI_STAGE);
        s.setDcBlocking(false);
        if (c == Case.MID_ONLY) s.setProcessingMode(Saturation.ProcessingMode.MID_ONLY);
        if (c == Case.DELTA) s.setOutputMode(Saturation.OutputMode.DELTA);
        if (c == Case.HALF_MIX) s.setMix(0.5);
        s.setAntialiasing(order);
        s.prepare(48000.0, 2, 512);
        final double[] l = new double[512], r = new double[512];
        final double[][] ch = { l, r };
        final double[] out = new double[60 * 512 * 2];
        long t = 0;
        for (int b = 0; b < 60; ++b)
        {
            if (c == Case.SWITCH && b == 20) s.setAlgorithm(Saturation.Algorithm.MULTI_STAGE);
            for (int i = 0; i < 512; ++i, ++t)
            {
                final double ph = TWO_PI * 200.0 * (double) t / 48000.0;
                l[i] = 0.01 * Math.sin(ph);
                r[i] = 0.004 * Math.sin(ph + 1.3);
            }
            s.process(ch, 512);
            for (int i = 0; i < 512; ++i)
            {
                out[2 * (b * 512 + i)] = l[i];
                out[2 * (b * 512 + i) + 1] = r[i];
            }
        }
        return out;
    }

    @Test
    void multistageSecondOrderTwoSamples()
    {
        assertTrue(worstToneSpurDbc(multiStage(24.0)) < -33.0);
        assertTrue(worstToneSpurDbc(multiStage(36.0)) < -32.0);
        assertTrue(worstToneSpurDbc(multiStage(12.0)) < -35.0);

        for (int a = 0; a <= Saturation.Algorithm.MULTI_STAGE.ordinal(); ++a)
        {
            final Saturation s = new Saturation();
            s.setAlgorithm(Saturation.Algorithm.values()[a]);
            s.prepare(48000.0, 1, 512);
            final int off = s.getLatency();
            s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
            assertEquals(off + 2, s.getLatency());
            assertEquals(off + 2, s.getLatencySamples());
        }

        for (Case c : Case.values())
        {
            final double[] off = runCase(c, Saturation.Antialiasing.OFF);
            final double[] second = runCase(c, Saturation.Antialiasing.SECOND_ORDER);
            double err = 0.0, ref = 0.0;
            for (int i = 2 * 4000; i < off.length; ++i)
            {
                err += (second[i] - off[i - 4]) * (second[i] - off[i - 4]);
                ref += off[i] * off[i];
            }
            assertTrue(10.0 * Math.log10(err / ref) < -55.0, c + ": " + 10.0 * Math.log10(err / ref));
        }
    }

    @Test
    void exciterKernelIsExact()
    {
        final SaturationDetail.AdaaExciter curve = new SaturationDetail.AdaaExciter();
        final SplittableRandom rng = new SplittableRandom(11);
        double worst = 0.0;
        for (int trial = 0; trial < 400; ++trial)
        {
            curve.setCharacter(0.5 * (2.0 * rng.nextDouble() - 1.0));
            final double c = -4.0 + 8.0 * rng.nextDouble(), span = Math.pow(10.0, -6.0 + 7.0 * rng.nextDouble());
            final double[] u = { c + span * (2.0 * rng.nextDouble() - 1.0), c + span * (2.0 * rng.nextDouble() - 1.0),
                                 c + span * (2.0 * rng.nextDouble() - 1.0) };
            final double y = curve.average(u[0], u[1], u[2]);
            Arrays.sort(u);
            double ref = 0.0;
            if (u[2] - u[0] < 1e-9)
                ref = curve.f(u[1]);
            else
            {
                final int n = 8192;
                for (int side = 0; side < 2; ++side)
                {
                    final double lo = side == 0 ? u[0] : u[1], hi = side == 0 ? u[1] : u[2];
                    if (!(hi > lo)) continue;
                    for (int k = 0; k < n; ++k)
                    {
                        final double t = lo + (hi - lo) * (k + 0.5) / n;
                        final double w = side == 0 ? 2.0 * (t - u[0]) / ((u[2] - u[0]) * (u[1] - u[0]))
                                                   : 2.0 * (u[2] - t) / ((u[2] - u[0]) * (u[2] - u[1]));
                        ref += curve.f(t) * w * (hi - lo) / n;
                    }
                }
            }
            worst = Math.max(worst, Math.abs(y - ref));
        }
        assertTrue(worst < 1e-6, "worst " + worst);
    }
}
