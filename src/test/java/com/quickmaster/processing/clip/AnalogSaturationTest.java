package com.quickmaster.processing.clip;

import com.dspark.analysis.LoudnessMeter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The drive solver and the parallel render of the analog saturation, on circuits with memory, filters and delay. */
class AnalogSaturationTest
{
    private static final int RATE = 48000;

    /** A tanh whose drive is level-compensated at small signals, after a 20 Hz high-pass, delayed. */
    static class TestCircuit implements AnalogModel
    {
        private final int delay;
        private final double slope;
        private double drive;
        private double[][] line;
        private double[] hpX, hpY;
        private int at;
        private double a;

        TestCircuit(int delay, double slope)
        {
            this.delay = delay;
            this.slope = slope;
        }

        @Override public void prepare(double sampleRate, int channels, int maxBlock)
        {
            line = new double[channels][delay + 1];
            hpX = new double[channels];
            hpY = new double[channels];
            at = 0;
            a = Math.exp(-2.0 * Math.PI * 20.0 / sampleRate);
        }

        @Override public void reset() { prepare(RATE, line.length, 0); }
        @Override public void setDriveDb(double db) { drive = db; }
        @Override public double minDriveDb() { return -12.0; }
        @Override public double maxDriveDb() { return 24.0; }
        @Override public int latency() { return delay; }
        @Override public double alignmentLufs() { return -12.0; }

        /** The linear filter ahead of the curve, per channel; a 20 Hz high-pass here. */
        double filter(int c, double in)
        {
            double hp = a * (hpY[c] + in - hpX[c]);
            hpX[c] = in;
            hpY[c] = hp;
            return hp;
        }

        @Override public void process(double[][] x, int frames)
        {
            double k = Math.pow(10.0, drive / 20.0) * 0.5, level = Math.pow(10.0, slope * drive / 20.0);
            for (int i = 0; i < frames; i++)
            {
                for (int c = 0; c < x.length; c++)
                {
                    double hp = filter(c, x[c][i]);
                    double y = Math.tanh(k * hp) / k * level;
                    line[c][at] = y;
                    x[c][i] = line[c][(at + 1) % (delay + 1)];
                }
                at = (at + 1) % (delay + 1);
            }
        }
    }

    /**
     * The circuit with a first-order all-pass at 150 Hz ahead of its curve:
     * it moves the phase of the low end, so the peaks of a limited song rise
     * through it before anything saturates.
     */
    static final class PhaseCircuit extends TestCircuit
    {
        private double[] apX, apY;
        private double g;

        PhaseCircuit() { super(11, 0.0); }

        @Override public void prepare(double sampleRate, int channels, int maxBlock)
        {
            super.prepare(sampleRate, channels, maxBlock);
            apX = new double[channels];
            apY = new double[channels];
            double t = Math.tan(Math.PI * 150.0 / sampleRate);
            g = (t - 1.0) / (t + 1.0);
        }

        @Override double filter(int c, double in)
        {
            double y = g * in + apX[c] - g * apY[c];
            apX[c] = in;
            apY[c] = y;
            return super.filter(c, y);
        }
    }

    /** A song limited as a master is: the programme clamped at a third of its peak. */
    static float[] limited(int seconds, long seed)
    {
        float[] x = programme(seconds, seed);
        double p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        float ceiling = (float) (p / 3.0);
        for (int i = 0; i < x.length; i++) x[i] = Math.max(-ceiling, Math.min(ceiling, x[i]));
        return x;
    }

    /** Music-like stereo: noise under a moving envelope, with transients. */
    static float[] programme(int seconds, long seed)
    {
        Random r = new Random(seed);
        int frames = seconds * RATE;
        float[] x = new float[frames * 2];
        double env = 0.1, lp = 0;
        for (int f = 0; f < frames; f++)
        {
            if (f % 12000 == 0) env = 0.05 + 0.25 * r.nextDouble();
            double hit = (f % 24000) < 200 ? 3.0 * Math.exp(-(f % 24000) / 40.0) : 0.0;
            lp = 0.7 * lp + 0.3 * r.nextGaussian();
            double s = env * lp + hit * Math.sin(2 * Math.PI * 80 * f / RATE);
            x[2 * f] = (float) s;
            x[2 * f + 1] = (float) (0.8 * s + 0.2 * env * r.nextGaussian());
        }
        return x;
    }

    private static double peakDb(float[] x)
    {
        double p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return 20 * Math.log10(p);
    }

    /** The whole input through one continuous circuit, its delay taken out, at a drive and a gain. */
    static float[] continuous(float[] in, Supplier<AnalogModel> f, double drive, double norm, double gainDb)
    {
        AnalogModel m = f.get();
        m.setDriveDb(drive);
        m.prepare(RATE, 2, 2048);
        int frames = in.length / 2, lat = m.latency();
        float[] out = new float[in.length];
        AnalogSaturation.Runner run = new AnalogSaturation.Runner(m, 2, norm);
        double back = Math.pow(10, gainDb / 20) / norm;
        for (int pos = 0; pos < frames + lat; pos += 2048)
        {
            int n = Math.min(2048, frames + lat - pos);
            double[][] y = run.block(in, pos, n);
            for (int i = 0; i < n; i++)
            {
                int dst = pos + i - lat;
                if (dst < 0 || dst >= frames) continue;
                out[2 * dst] = (float) (y[0][i] * back);
                out[2 * dst + 1] = (float) (y[1][i] * back);
            }
        }
        return out;
    }

    /**
     * The circuit's linear response, rendered independently in one pass: its
     * least drive, 40 dB under its alignment, at the input's loudness.
     */
    static float[] linearResponse(float[] in, int channels, Supplier<AnalogModel> f)
    {
        AnalogModel m = f.get();
        double lufs = LoudnessMeter.measureIntegrated(in, RATE, channels);
        double target = channels == 1 ? m.alignmentLufs() - 10 * Math.log10(2) : m.alignmentLufs();
        double norm = Math.pow(10, (target - lufs) / 20) * 0.01;
        float[] ref = continuousAt(in, channels, f, m.minDriveDb(), norm);
        float g = (float) Math.pow(10, (lufs - LoudnessMeter.measureIntegrated(ref, RATE, channels)) / 20);
        for (int i = 0; i < ref.length; i++) ref[i] *= g;
        return ref;
    }

    /** The peak of every 1024-frame hop. */
    static float[] hops(float[] x, int channels)
    {
        int frames = x.length / channels;
        float[] p = new float[(frames + 1023) / 1024];
        for (int i = 0; i < x.length; i++) p[i / channels / 1024] = Math.max(p[i / channels / 1024], Math.abs(x[i]));
        return p;
    }

    /**
     * The most, in dB, any hop of the output lies below the same hop of the
     * linear response, over the hops within 60 dB of its peak.
     */
    static double mostLostDb(float[] linear, float[] out, int channels)
    {
        float[] ref = hops(linear, channels), got = hops(out, channels);
        double top = 0;
        for (float v : ref) top = Math.max(top, v);
        double floor = top * 1e-3, worst = Double.NEGATIVE_INFINITY;
        for (int h = 0; h < ref.length; h++)
            if (ref[h] > floor && got[h] > 0) worst = Math.max(worst, 20 * Math.log10(ref[h] / got[h]));
        return worst;
    }

    static double mostLostDb(float[] in, float[] out, int channels, Supplier<AnalogModel> f)
    {
        return mostLostDb(linearResponse(in, channels, f), out, channels);
    }

    static float[] continuousAt(float[] in, int channels, Supplier<AnalogModel> f, double drive, double norm)
    {
        AnalogModel m = f.get();
        m.setDriveDb(drive);
        m.prepare(RATE, channels, 2048);
        int frames = in.length / channels, lat = m.latency();
        float[] out = new float[in.length];
        AnalogSaturation.Runner run = new AnalogSaturation.Runner(m, channels, norm);
        for (int pos = 0; pos < frames + lat; pos += 2048)
        {
            int n = Math.min(2048, frames + lat - pos);
            double[][] y = run.block(in, pos, n);
            for (int i = 0; i < n; i++)
            {
                int dst = pos + i - lat;
                if (dst < 0 || dst >= frames) continue;
                for (int c = 0; c < channels; c++) out[channels * dst + c] = (float) (y[c][i] / norm);
            }
        }
        return out;
    }

    private static AnalogSaturation.Result render(float[] in, AnalogSaturation.Analysis a, double target,
                                                  Supplier<AnalogModel> f)
    {
        return AnalogSaturation.render(in, a, AnalogSaturation.linear(in, a, f, 8, null), target, f, 8, null);
    }

    @Test
    void theLinearResponseIsTheCircuitFarBelowItsSaturation()
    {
        float[] in = limited(30, 7);
        Supplier<AnalogModel> f = PhaseCircuit::new;
        var a = AnalogSaturation.analyze(in, 2, RATE, null);
        var linear = AnalogSaturation.linear(in, a, f, 8, null);
        float[] expected = hops(linearResponse(in, 2, f), 2);
        assertEquals(expected.length, linear.hopPeak().length);
        for (int h = 0; h < expected.length; h++)
            assertEquals(expected[h], linear.hopPeak()[h], 1e-5 * expected[h] + 1e-9, "hop " + h);
        assertTrue(linear.peakDb() > peakDb(in) + 0.5, "the all-pass should raise the limited peak: "
                + (linear.peakDb() - peakDb(in)) + " dB");
    }

    @Test
    void theMostSaturatedMomentLosesExactlyTheAmountAskedAndTheLoudnessIsKept()
    {
        float[] in = programme(40, 1);
        Supplier<AnalogModel> f = () -> new TestCircuit(37, 0.0);
        var a = AnalogSaturation.analyze(in, 2, RATE, null);
        float[] linear = linearResponse(in, 2, f);
        for (double target : new double[] { 0.5, 2.0, 6.0 })
        {
            var r = render(in, a, target, f);
            assertEquals(target, mostLostDb(linear, r.output(), 2), 1e-4, "reduction at " + target);
            assertEquals(target, r.solution().reductionDb(), 1e-4);
            double loud = LoudnessMeter.measureIntegrated(r.output(), RATE, 2) - a.lufs();
            assertTrue(Math.abs(loud) <= AnalogSaturation.LOUDNESS_TOLERANCE_DB + 1e-9,
                    "loudness moved " + loud + " dB at " + target);
            assertEquals(loud, r.solution().loudnessDb(), 0.01);
        }
    }

    @Test
    void aCircuitWhoseFiltersRaiseThePeakIsDrivenOnlyAsFarAsItSaturates()
    {
        float[] in = limited(40, 8);
        Supplier<AnalogModel> f = PhaseCircuit::new;
        var a = AnalogSaturation.analyze(in, 2, RATE, null);
        var r = render(in, a, 1.0, f);
        assertEquals(1.0, mostLostDb(in, r.output(), 2, f), 1e-4);
        assertTrue(!r.solution().clips(), "the knee should not be needed for 1 dB");
        assertTrue(r.solution().driveDb() < 12.0, "drive " + r.solution().driveDb());
        assertTrue(Math.abs(r.solution().loudnessDb()) <= AnalogSaturation.LOUDNESS_TOLERANCE_DB,
                "loudness " + r.solution().loudnessDb());
    }

    @Test
    void theStretchesJoinIntoOneContinuousPass()
    {
        float[] in = programme(40, 2);
        Supplier<AnalogModel> f = () -> new TestCircuit(53, 0.25);
        var a = AnalogSaturation.analyze(in, 2, RATE, null);
        var r = render(in, a, 3.0, f);
        double norm = a.alignment(-12.0);
        float[] ref = continuous(in, f, r.solution().driveDb(), norm, r.solution().gainDb());
        double diff = 0;
        for (int i = 0; i < ref.length; i++) diff = Math.max(diff, Math.abs(ref[i] - r.output()[i]));
        assertTrue(diff < 1e-6, "parallel render differs from one pass by " + diff);
    }

    @Test
    void aLevelThatRisesWithTheDriveIsCorrectedByTheRenders()
    {
        float[] in = programme(30, 3);
        Supplier<AnalogModel> f = () -> new TestCircuit(0, 0.4);
        var a = AnalogSaturation.analyze(in, 2, RATE, null);
        var r = render(in, a, 4.0, f);
        assertEquals(4.0, mostLostDb(in, r.output(), 2, f), 1e-4);
        assertTrue(Math.abs(r.solution().loudnessDb()) <= AnalogSaturation.LOUDNESS_TOLERANCE_DB,
                "loudness " + r.solution().loudnessDb());
        assertTrue(r.solution().renders() >= 1 && r.solution().renders() <= 8, "renders " + r.solution().renders());
    }

    @Test
    void zeroAndSilenceLeaveTheInputAlone()
    {
        float[] in = programme(10, 4);
        Supplier<AnalogModel> f = () -> new TestCircuit(10, 0.0);
        var a = AnalogSaturation.analyze(in, 2, RATE, null);
        assertSame(in, render(in, a, 0.0, f).output());
        float[] silence = new float[RATE * 4];
        var s = AnalogSaturation.analyze(silence, 2, RATE, null);
        assertSame(silence, render(silence, s, 3.0, f).output());
    }

    @Test
    void theSameSongSaturatesAlikeAtAnyLevel()
    {
        float[] in = programme(20, 5);
        float[] quiet = in.clone();
        for (int i = 0; i < quiet.length; i++) quiet[i] *= 0.1f;
        Supplier<AnalogModel> f = () -> new TestCircuit(5, 0.0);
        var loud = render(in, AnalogSaturation.analyze(in, 2, RATE, null), 3.0, f);
        var soft = render(quiet, AnalogSaturation.analyze(quiet, 2, RATE, null), 3.0, f);
        assertEquals(loud.solution().driveDb(), soft.solution().driveDb(), 1e-3);
        double diff = 0;
        for (int i = 0; i < in.length; i++) diff = Math.max(diff, Math.abs(loud.output()[i] * 0.1f - soft.output()[i]));
        assertTrue(diff < 1e-5, "level-dependent result " + diff);
    }

    @Test
    void whenTheLeastDriveAlreadySaturatesTooMuchTheReductionIsStillExact()
    {
        float[] in = programme(20, 6);
        Supplier<AnalogModel> f = () -> new TestCircuit(0, 0.0)
        {
            @Override public double minDriveDb() { return 18.0; }
        };
        var a = AnalogSaturation.analyze(in, 2, RATE, null);
        var r = render(in, a, 0.2, f);
        assertEquals(18.0, r.solution().driveDb(), 1e-9);
        assertEquals(0.2, mostLostDb(in, r.output(), 2, f), 1e-4);
        assertTrue(r.solution().loudnessDb() > 0.0);
    }
}
