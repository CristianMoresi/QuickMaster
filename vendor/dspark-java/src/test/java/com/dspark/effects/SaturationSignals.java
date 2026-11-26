package com.dspark.effects;

import com.dspark.core.FFTRealDouble;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Signals and measurements shared by the Saturation tests (the C++ tests' helpers). */
final class SaturationSignals
{
    static final double TWO_PI = 2.0 * Math.PI;

    private SaturationSignals()
    {
    }

    static double toDb(double x)
    {
        return 20.0 * Math.log10(Math.max(x, 1e-30));
    }

    /** A float sine as the C++ generateSine(float*): float phase increment, float sine. */
    static double[] floatSine(int n, float freq, float sampleRate, float amp)
    {
        final double[] x = new double[n];
        final float inc = (float) (2.0 * Math.PI) * freq / sampleRate;
        for (int i = 0; i < n; ++i) x[i] = amp * (float) Math.sin(inc * (float) i);
        return x;
    }

    /** sat8Sine: amp sin(2 pi hz i / 48000), rounded to float. */
    static double[] sine48(double hz, double amp, int n)
    {
        final double[] v = new double[n];
        for (int i = 0; i < n; ++i) v[i] = (float) (amp * Math.sin(2.0 * Math.PI * hz * i / 48000.0));
        return v;
    }

    /** sat8Pink: pink noise at an RMS level in dBFS, rounded to float. */
    static double[] pink(int n, double rmsDb, int seed)
    {
        final double[] v = new double[n];
        double b0 = 0.0, b1 = 0.0, b2 = 0.0, sum = 0.0;
        for (int i = 0; i < n; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            final double white = (double) seed / 2147483648.0;
            b0 = 0.99765 * b0 + white * 0.0990460;
            b1 = 0.96300 * b1 + white * 0.2965164;
            b2 = 0.57000 * b2 + white * 1.0526913;
            final double p = b0 + b1 + b2 + white * 0.1848;
            v[i] = (float) p;
            sum += p * p;
        }
        final double scale = Math.pow(10.0, rmsDb / 20.0) / Math.sqrt(sum / n);
        for (int i = 0; i < n; ++i) v[i] = (float) (v[i] * scale);
        return v;
    }

    /** sat8Run: a mono render at 48 kHz in blocks, the processor set up before prepare(). */
    static double[] run48(double[] in, Consumer<Saturation> setup, int block)
    {
        final Saturation s = new Saturation();
        setup.accept(s);
        s.prepare(48000.0, 1, block);
        return render(s, in, block);
    }

    static double[] run48(double[] in, Consumer<Saturation> setup)
    {
        return run48(in, setup, 512);
    }

    /** A mono render of a prepared processor in blocks (a copy of the input). */
    static double[] render(Saturation s, double[] in, int block)
    {
        final double[] x = in.clone();
        final double[][] view = new double[1][];
        final double[] buf = new double[block];
        for (int off = 0; off < x.length; off += block)
        {
            final int m = Math.min(block, x.length - off);
            System.arraycopy(x, off, buf, 0, m);
            view[0] = buf;
            s.process(view, m);
            System.arraycopy(buf, 0, x, off, m);
        }
        return x;
    }

    /** sat8Line: level in dB of the line at hz over the last 4800 samples (48 kHz). */
    static double line(double[] v, double hz)
    {
        final int n = 4800, from = v.length - n;
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double ph = 2.0 * Math.PI * hz * i / 48000.0;
            re += v[from + i] * Math.cos(ph);
            im += v[from + i] * Math.sin(ph);
        }
        return 20.0 * Math.log10(Math.max(2.0 * Math.sqrt(re * re + im * im) / n, 1e-15));
    }

    static double rms(double[] v, int from, int to)
    {
        double sum = 0.0;
        for (int i = from; i < to; ++i) sum += v[i] * v[i];
        return Math.sqrt(sum / (to - from));
    }

    /** Coherent amplitude of frequency f over x[from, from + n). */
    static double toneAmplitude(double[] x, int from, int n, double f, double fs)
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

    /** runSine of the C++ audit: a mono float sine in blocks, continuing from t[0]. */
    static double[] runSine(Saturation p, double fs, int block, int total, double f, double amp, long[] t)
    {
        final double[] out = new double[total + block];
        final double[] buf = new double[block];
        final double[][] view = { buf };
        int filled = 0;
        while (filled < total)
        {
            for (int i = 0; i < block; ++i)
                buf[i] = (float) (amp * Math.sin(TWO_PI * f * (double) (t[0]++) / fs));
            p.process(view, block);
            System.arraycopy(buf, 0, out, filled, block);
            filled += block;
        }
        final double[] r = new double[total];
        System.arraycopy(out, 0, r, 0, total);
        return r;
    }

    /** runSineDouble of the C++ audit: a mono double sine in blocks from t = 0. */
    static double[] runSineDouble(Saturation p, double fs, int block, int total, double f, double amp)
    {
        final double[] out = new double[total + block];
        final double[] buf = new double[block];
        final double[][] view = { buf };
        long t = 0;
        int filled = 0;
        while (filled < total)
        {
            for (int i = 0; i < block; ++i)
                buf[i] = amp * Math.sin(TWO_PI * f * (double) (t++) / fs);
            p.process(view, block);
            System.arraycopy(buf, 0, out, filled, block);
            filled += block;
        }
        final double[] r = new double[total];
        System.arraycopy(out, 0, r, 0, total);
        return r;
    }

    static double maxStep(double[] x, int from, int to)
    {
        double m = 0.0;
        for (int i = Math.max(from, 1); i < to && i < x.length; ++i) m = Math.max(m, Math.abs(x[i] - x[i - 1]));
        return m;
    }

    /** Worst non-harmonic component below 20 kHz relative to the fundamental of a coherent tone at odd bin b. */
    static double worstSpurDbc(double[] frame, int from, int n, int b, double fs)
    {
        final FFTRealDouble fft = new FFTRealDouble(n);
        final double[] time = new double[n];
        System.arraycopy(frame, from, time, 0, n);
        final double[] spec = new double[n + 2];
        fft.forward(time, spec);
        final double fund = Math.hypot(spec[2 * b], spec[2 * b + 1]);
        double worst = 0.0;
        final int maxBin = (int) (20000.0 / fs * n);
        for (int k = 1; k <= maxBin; ++k)
            if (k % b != 0) worst = Math.max(worst, Math.hypot(spec[2 * k], spec[2 * k + 1]));
        return toDb(worst / fund);
    }

    /** Worst spur over coherent tones (7 / 12.75 / 15.25 / 19.25 kHz) at an amplitude after a 0.2 s settle. */
    static double worstToneSpurDbc(Supplier<Saturation> make, double amp, double... tones)
    {
        final double fs = 48000.0;
        final int n = 16384;
        double w = -400.0;
        for (double f : tones)
        {
            int b = (int) SaturationDetail.lround(f / fs * n);
            if ((b & 1) == 0) ++b;
            final Saturation p = make.get();
            final double[] y = runSineDouble(p, fs, 512, 9600 + n, b * fs / n, amp);
            w = Math.max(w, worstSpurDbc(y, 9600, n, b, fs));
        }
        return w;
    }

    static double worstToneSpurDbc(Supplier<Saturation> make)
    {
        return worstToneSpurDbc(make, 0.5, 7000.0, 12750.0, 15250.0, 19250.0);
    }

    /** Gain of a 0.01 tone at f after 0.25 s (48 kHz). */
    static double toneGain(Supplier<Saturation> make, double f)
    {
        final double fs = 48000.0;
        final Saturation p = make.get();
        final double[] y = runSineDouble(p, fs, 512, 24000, f, 0.01);
        return toneAmplitude(y, 12000, 12000, f, fs) / 0.01;
    }

    /** A planar stereo buffer of a float sine on every channel (TestBuffer::fillSine). */
    static double[][] stereoSine(int channels, int n, float freq, float sr, float amp)
    {
        final double[][] b = new double[channels][];
        for (int c = 0; c < channels; ++c) b[c] = floatSine(n, freq, sr, amp);
        return b;
    }
}
