package com.dspark.effects;

import com.dspark.core.FFTRealDouble;

/** Signals and measurements shared by the TubePreamp tests (the C++ suite's helpers). */
final class TubeTestSupport
{
    static final double TWO_PI = 2.0 * Math.PI;

    private TubeTestSupport()
    {
    }

    static double toDb(double x)
    {
        return 20.0 * Math.log10(Math.max(x, 1e-30));
    }

    /** Coherent amplitude of frequency f over x[from .. from + n). */
    static double toneAmplitude(double[] x, int from, int n, double f, double fs)
    {
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double w = TWO_PI * f * (double) i / fs;
            re += x[from + i] * Math.cos(w);
            im += x[from + i] * Math.sin(w);
        }
        return 2.0 * Math.sqrt(re * re + im * im) / (double) n;
    }

    static double maxStep(double[] x, int from, int to)
    {
        double m = 0.0;
        for (int i = Math.max(from, 1); i < to && i < x.length; ++i)
            m = Math.max(m, Math.abs(x[i] - x[i - 1]));
        return m;
    }

    /** Runs a mono buffer through a preamp in place, in blocks. */
    static void renderMono(TubePreamp amp, double[] y, int block)
    {
        final double[][] ch = { y };
        for (int pos = 0; pos < y.length; pos += block)
            amp.process(ch, pos, Math.min(block, y.length - pos));
    }

    /** A mono sine through a processor in float blocks (TestAnalogAudit runSine). */
    static double[] runSine(TubePreamp p, double fs, int block, int total, double f, double amp, long[] t)
    {
        final double[] buf = new double[block];
        final double[][] ch = { buf };
        final double[] out = new double[total + block];
        int count = 0;
        while (count < total)
        {
            for (int i = 0; i < block; ++i)
                buf[i] = (float) (amp * Math.sin(TWO_PI * f * (double) (t[0]++) / fs));
            p.process(ch, block);
            for (int i = 0; i < block; ++i) out[count++] = (float) buf[i];
        }
        return java.util.Arrays.copyOf(out, total);
    }

    /** Pink-weighted multitone (one tone per octave, 100..6400 Hz), as float. */
    static float analogProgram(int n, float amp)
    {
        final double[] f = { 100, 200, 400, 800, 1600, 3200, 6400 };
        double s = 0;
        for (int k = 0; k < 7; ++k)
            s += Math.sin(2.0 * 3.14159265358979 * f[k] * n / 48000.0 + k * 1.7);
        return amp * (float) (s / 2.6457513);
    }

    /** Program-loudness insertion gain (dB) on a stereo stream in blocks of 512. */
    static double analogInsertionDb(TubePreamp fx, float amp)
    {
        final double[][] buf = new double[2][512];
        final int total = 96000, measFrom = 48000;
        double inSq = 0, outSq = 0;
        int n = 0;
        while (n < total)
        {
            for (int i = 0; i < 512; ++i)
            {
                buf[0][i] = analogProgram(n + i, amp);
                buf[1][i] = buf[0][i];
            }
            fx.process(buf, 512);
            for (int i = 0; i < 512; ++i)
                if (n + i >= measFrom)
                {
                    final double s = analogProgram(n + i, amp);
                    inSq += s * s;
                    final double o = (float) buf[0][i];
                    outSq += o * o;
                }
            n += 512;
        }
        return 10.0 * Math.log10(outSq / Math.max(inSq, 1e-30));
    }

    interface DriveSetter
    {
        void set(float db);
    }

    /** Worst |sample-to-sample delta| of a 1 kHz tone, drive held or dragged one step per block. */
    static double analogWorstDelta(TubePreamp fx, DriveSetter setDrive, float dbFrom, float dbTo, boolean drag)
    {
        if (drag)
            return deltaPass(fx, setDrive, dbFrom, dbFrom, dbTo, true);
        double worst = 0;
        for (float db : new float[] { dbFrom, 0.5f * (dbFrom + dbTo), dbTo })
            worst = Math.max(worst, deltaPass(fx, setDrive, db, dbFrom, dbTo, false));
        return worst;
    }

    private static double deltaPass(TubePreamp fx, DriveSetter setDrive, float fixedDb, float dbFrom, float dbTo,
                                    boolean drag)
    {
        final double[][] buf = new double[2][512];
        fx.reset();
        setDrive.set(fixedDb);
        double md = 0;
        float prev = 0;
        int n = 0, block = 0;
        final int total = drag ? 96000 : 36000;
        while (n < total)
        {
            if (drag && n >= 24000)
            {
                final float t = (block * 0.02f) % 2.0f;
                final float x = t < 1.0f ? t : 2.0f - t;
                setDrive.set(dbFrom + (dbTo - dbFrom) * x);
            }
            for (int i = 0; i < 512; ++i)
            {
                buf[0][i] = (float) (0.25f * (float) Math.sin(2.0f * 3.14159265f * 1000.0f
                                                                * (float) (n + i) / 48000.0f));
                buf[1][i] = buf[0][i];
            }
            fx.process(buf, 512);
            for (int i = 0; i < 512; ++i)
            {
                final float v = (float) buf[0][i];
                if (n + i > 9600)
                    md = Math.max(md, (double) Math.abs(v - prev));
                prev = v;
            }
            n += 512;
            ++block;
        }
        return md;
    }

    /** Worst non-harmonic line from 20 Hz to 20 kHz relative to the tone at odd bin b (TestAnalogAudit). */
    static double worstLineDbc(double[] frame, int from, int n, int b, double fs)
    {
        final FFTRealDouble fft = new FFTRealDouble(n);
        final double[] spectrum = new double[n + 2];
        fft.forward(frame, from, spectrum, 0);
        double w = 0.0;
        for (int k = (int) Math.ceil(20.0 / fs * n); k <= (int) (20000.0 / fs * n); ++k)
            if (k % b != 0) w = Math.max(w, Math.hypot(spectrum[2 * k], spectrum[2 * k + 1]));
        return toDb(w / Math.hypot(spectrum[2 * b], spectrum[2 * b + 1]));
    }

    /** A tone at an odd coherent bin of an n-point frame, after a settling time (TestAnalogAudit). */
    static double coherentWorstDbc(TubePreamp amp, double fs, int n, double hz, double amplitude, int settleSamples)
    {
        int b = (int) Math.round(hz / fs * n);
        if ((b & 1) == 0) ++b;
        final int settle = settleSamples + amp.getLatency();
        final int total = (settle + n + 511) / 512 * 512;
        final double[] y = new double[total];
        for (int i = 0; i < total; ++i)
            y[i] = amplitude * Math.sin(TWO_PI * (b * fs / n) * (double) i / fs);
        renderMono(amp, y, 512);
        return worstLineDbc(y, settle, n, b, fs);
    }
}
