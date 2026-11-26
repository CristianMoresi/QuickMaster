package com.dspark.effects;

import java.util.function.Consumer;

/**
 * Signals, renders and measurements shared by the {@link TapeMachine} tests:
 * the helpers of the C++ test suite (runTapeSine, tapeToneMag, l10*, sat8*,
 * m7*, analogProgram), with the inputs rounded to float as the C++ tests
 * build them. A render whose C++ original feeds the same signal to both
 * channels and reads the left one runs mono here: the channels of the machine
 * are independent unless bleed, hiss or the alignment tolerance is on, so the
 * left output is the same to the bit.
 */
final class TapeSignals
{
    static final double PI_SHORT = 3.14159265358979;
    static final double PI = 3.14159265358979323846;

    private TapeSignals()
    {
    }

    // -- TestEffectsTone: runTapeSine / tapeToneMag / neutralTape -------------------

    /** Stereo blocks of 512 (the same tone on both channels); the left output. */
    static double[] runTapeSine(TapeMachine tape, double freq, double amp, double seconds)
    {
        return runTapeSine(tape, freq, amp, seconds, 2);
    }

    /** Blocks of 512 of {@code channels} channels (the same tone on each); the first output. */
    static double[] runTapeSine(TapeMachine tape, double freq, double amp, double seconds, int channels)
    {
        final int total = (int) (48000.0 * seconds / 512) * 512;
        final double[] out = new double[total];
        final double[][] buf = new double[channels][512];
        int n = 0;
        for (int b = 0; b < total / 512; ++b)
        {
            for (int i = 0; i < 512; ++i, ++n)
            {
                final float v = (float) amp * (float) Math.sin(2.0 * PI_SHORT * freq * n / 48000.0);
                for (final double[] c : buf) c[i] = v;
            }
            tape.process(buf, 512);
            System.arraycopy(buf[0], 0, out, b * 512, 512);
        }
        return out;
    }

    static double tapeToneMag(double[] x, double freq)
    {
        final int from = x.length / 2;
        final int n = x.length - from;
        double re = 0, im = 0;
        for (int i = 0; i < n; ++i)
        {
            final double ph = 2.0 * PI_SHORT * freq * (double) i / 48000.0;
            re += x[from + i] * Math.cos(ph);
            im += x[from + i] * Math.sin(ph);
        }
        return 2.0 * Math.sqrt(re * re + im * im) / (double) n;
    }

    /** Prepared at 48 kHz stereo, then losses, head bump and wow set to 0 (after prepare, as the C++). */
    static void neutralTape(TapeMachine tape)
    {
        neutralTape(tape, 2);
    }

    /** {@link #neutralTape(TapeMachine)} with a channel count. */
    static void neutralTape(TapeMachine tape, int channels)
    {
        tape.prepare(48000.0, channels, 512);
        tape.setLossEffects(0.0);
        tape.setHeadBump(0.0);
        tape.setWowFlutter(0.0);
    }

    // -- analogProgram / analogInsertionDb ----------------------------------------------

    static float analogProgram(int n, float amp)
    {
        final double[] f = { 100, 200, 400, 800, 1600, 3200, 6400 };
        double s = 0;
        for (int k = 0; k < 7; ++k)
            s += Math.sin(2.0 * PI_SHORT * f[k] * n / 48000.0 + k * 1.7);
        return amp * (float) (s / 2.6457513);
    }

    /** Program-loudness insertion gain (dB): 2 s of the program, the second second measured. */
    static double analogInsertionDb(TapeMachine fx, float amp)
    {
        final double[][] buf = new double[1][512];
        final int total = 96000, measFrom = 48000;
        double inSq = 0, outSq = 0;
        int n = 0;
        while (n < total)
        {
            for (int i = 0; i < 512; ++i) buf[0][i] = analogProgram(n + i, amp);
            fx.process(buf, 512);
            for (int i = 0; i < 512; ++i)
                if (n + i >= measFrom)
                {
                    final double s = analogProgram(n + i, amp);
                    inSq += s * s;
                    outSq += buf[0][i] * buf[0][i];
                }
            n += 512;
        }
        return 10.0 * Math.log10(outSq / Math.max(inSq, 1e-30));
    }

    // -- l10 helpers ------------------------------------------------------------------

    static double[] l10Tone(double hz, double amp, int n)
    {
        final double[] v = new double[n];
        for (int i = 0; i < n; ++i)
            v[i] = (float) (amp * Math.sin(2.0 * PI * hz * i / 48000.0));
        return v;
    }

    /** Renders in blocks of 512 through a prepared machine; returns { left, right } (copies). */
    static double[][] l10Render(TapeMachine tape, double[] left, double[] right)
    {
        final double[] l = left.clone(), r = right.clone();
        final double[][] block = new double[2][512];
        for (int off = 0; off < l.length; off += 512)
        {
            final int n = Math.min(512, l.length - off);
            System.arraycopy(l, off, block[0], 0, n);
            System.arraycopy(r, off, block[1], 0, n);
            tape.process(block, n);
            System.arraycopy(block[0], 0, l, off, n);
            System.arraycopy(block[1], 0, r, off, n);
        }
        return new double[][] { l, r };
    }

    /** Renders a mono signal in blocks of 512 through a prepared machine. */
    static double[] monoRender(TapeMachine tape, double[] in)
    {
        final double[] x = in.clone();
        final double[][] block = new double[1][512];
        for (int off = 0; off < x.length; off += 512)
        {
            final int n = Math.min(512, x.length - off);
            System.arraycopy(x, off, block[0], 0, n);
            tape.process(block, n);
            System.arraycopy(block[0], 0, x, off, n);
        }
        return x;
    }

    /** A new machine, set up before prepare() at 48 kHz stereo (512), both channels rendered. */
    static double[][] l10Run(Consumer<TapeMachine> setup, double[] left, double[] right)
    {
        final TapeMachine tape = new TapeMachine();
        setup.accept(tape);
        tape.prepare(48000.0, 2, 512);
        return l10Render(tape, left, right);
    }

    /** {@link #l10Run} with the same signal on both channels, the left one returned (rendered mono). */
    static double[] l10RunLeft(Consumer<TapeMachine> setup, double[] in)
    {
        final TapeMachine tape = new TapeMachine();
        setup.accept(tape);
        tape.prepare(48000.0, 1, 512);
        return monoRender(tape, in);
    }

    /** Level of a frequency over the second half, Hann window, in dB. */
    static double l10Line(double[] v, double hz)
    {
        final int from = v.length / 2, n = v.length - from;
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double ph = 2.0 * PI * hz * (double) i / 48000.0;
            final double w = 0.5 - 0.5 * Math.cos(2.0 * PI * (double) i / (double) n);
            re += w * v[from + i] * Math.cos(ph);
            im += w * v[from + i] * Math.sin(ph);
        }
        return 20.0 * Math.log10(Math.max(4.0 * Math.sqrt(re * re + im * im) / (double) n, 1e-15));
    }

    /** Mean level of a noise between two frequencies (lines on a grid), in dB. */
    static double l10Band(double[] v, double lo, double hi)
    {
        double sum = 0.0;
        int count = 0;
        for (double f = lo; f <= hi; f *= 1.05, ++count) sum += Math.pow(10.0, l10Line(v, f) / 10.0);
        return 10.0 * Math.log10(sum / count);
    }

    static double l10RmsDb(double[] v)
    {
        double s = 0.0;
        for (int i = v.length / 2; i < v.length; ++i) s += v[i] * v[i];
        return 10.0 * Math.log10(Math.max(s / (double) (v.length - v.length / 2), 1e-30));
    }

    /** Largest minus smallest period of a tone over its nominal, second half. */
    static double l10PitchSpread(double[] v, double hz)
    {
        double prev = -1.0, lo = 1e9, hi = 0.0;
        for (int i = v.length / 2; i + 1 < v.length; ++i)
            if (v[i] <= 0.0 && v[i + 1] > 0.0)
            {
                final double t = (double) i + v[i] / (v[i] - v[i + 1]);
                if (prev >= 0.0)
                {
                    lo = Math.min(lo, t - prev);
                    hi = Math.max(hi, t - prev);
                }
                prev = t;
            }
        return (hi - lo) / (48000.0 / hz);
    }

    /** THD of a 1 kHz tone (harmonics 2 to 5), as a ratio. */
    static double l10Thd(double[] v)
    {
        final double f = l10Line(v, 1000.0);
        double sum = 0.0;
        for (int k = 2; k <= 5; ++k) sum += Math.pow(10.0, (l10Line(v, 1000.0 * k) - f) / 10.0);
        return Math.sqrt(sum);
    }

    // -- sat8 helpers -----------------------------------------------------------------

    static double[] sat8Sine(double hz, double amp, int n)
    {
        final double[] v = new double[n];
        for (int i = 0; i < n; ++i)
            v[i] = (float) (amp * Math.sin(2.0 * PI * hz * i / 48000.0));
        return v;
    }

    /** Pink noise at an RMS level in dBFS. */
    static double[] sat8Pink(int n, double rmsDb, int seed)
    {
        final double[] raw = new double[n];
        final float[] v = new float[n];
        double b0 = 0.0, b1 = 0.0, b2 = 0.0, sum = 0.0;
        for (int i = 0; i < n; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            final double white = (double) seed / 2147483648.0;
            b0 = 0.99765 * b0 + white * 0.0990460;
            b1 = 0.96300 * b1 + white * 0.2965164;
            b2 = 0.57000 * b2 + white * 1.0526913;
            final double pink = b0 + b1 + b2 + white * 0.1848;
            v[i] = (float) pink;
            sum += pink * pink;
        }
        final double scale = Math.pow(10.0, rmsDb / 20.0) / Math.sqrt(sum / n);
        for (int i = 0; i < n; ++i) raw[i] = (float) (v[i] * scale);
        return raw;
    }

    /** Level in dB of the line at hz over the last 4800 samples (hz a multiple of 10). */
    static double sat8Line(double[] v, double hz)
    {
        final int n = 4800, from = v.length - n;
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double ph = 2.0 * PI * hz * (double) i / 48000.0;
            re += v[from + i] * Math.cos(ph);
            im += v[from + i] * Math.sin(ph);
        }
        return 20.0 * Math.log10(Math.max(2.0 * Math.sqrt(re * re + im * im) / (double) n, 1e-15));
    }

    // -- m7 helpers -------------------------------------------------------------------

    /** A sum of tones { frequency, amplitude } over a DC offset. */
    static double[] m7Tones(double[][] list, int n, double dc)
    {
        final float[] v = new float[n];
        java.util.Arrays.fill(v, (float) dc);
        for (final double[] tone : list)
            for (int i = 0; i < n; ++i)
                v[i] += (float) (tone[1] * Math.sin(2.0 * PI * tone[0] * i / 48000.0));
        final double[] out = new double[n];
        for (int i = 0; i < n; ++i) out[i] = v[i];
        return out;
    }

    /** The left channel of a machine with a steady transport. */
    static double[] m7Tape(Consumer<TapeMachine> setup, double[] in)
    {
        return l10RunLeft(t -> { t.setWowFlutter(0.0); setup.accept(t); }, in);
    }

    /** Gain of a tone through the machine, in dB. */
    static double m7Gain(Consumer<TapeMachine> setup, double hz, double amp, int n)
    {
        final double[] in = m7Tones(new double[][] { { hz, amp } }, n, 0.0);
        return l10Line(m7Tape(setup, in), hz) - l10Line(in, hz);
    }

    static double m7Gain(Consumer<TapeMachine> setup, double hz, double amp)
    {
        return m7Gain(setup, hz, amp, 96000);
    }

    // -- toneThrough ------------------------------------------------------------------

    /** A tone in stereo blocks of 256 through a machine; the left channel. */
    static double[] toneThrough(TapeMachine p, double freq, float amp, int length)
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
            System.arraycopy(ch[0], 0, outL, off, n);
        }
        return outL;
    }

    static double worstDifference(double[] a, double[] b, int from)
    {
        double worst = 0.0;
        for (int i = from; i < a.length; ++i) worst = Math.max(worst, Math.abs(a[i] - b[i]));
        return worst;
    }

    static double maxAbsDiff(double[] a, double[] b, int from, int to)
    {
        double worst = 0.0;
        for (int i = from; i < to; ++i) worst = Math.max(worst, Math.abs(a[i] - b[i]));
        return worst;
    }

    static double dB(double x)
    {
        return 20.0 * Math.log10(Math.max(x, 1e-30));
    }
}
