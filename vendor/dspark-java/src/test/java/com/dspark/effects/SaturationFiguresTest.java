package com.dspark.effects;

import com.dspark.core.Oversampling;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.function.Supplier;

import static com.dspark.effects.SaturationSignals.*;
import static org.junit.jupiter.api.Assertions.*;

/** The figures the C++ Saturation.h documents, measured on the port, and its kernels against independent references. */
class SaturationFiguresTest
{
    private static Supplier<Saturation> make(Saturation.Algorithm algo, int factor, Saturation.Antialiasing order, double drive)
    {
        return () -> {
            final Saturation s = new Saturation();
            s.setAlgorithm(algo);
            s.setOversampling(factor);
            s.setAntialiasing(order);
            s.setDrive(drive);
            s.setDcBlocking(false);
            s.prepare(48000.0, 1, 512);
            return s;
        };
    }

    private static int latency(int factor, Saturation.Antialiasing order, Saturation.OversamplingMode mode,
                               Oversampling.Band band, double rate)
    {
        final Saturation s = new Saturation();
        s.setOversampling(factor);
        s.setAntialiasing(order);
        s.setOversamplingMode(mode);
        s.setOversamplingBand(band);
        s.prepare(rate, 2, 512);
        return s.getLatency();
    }

    @Test
    void latencyPerFactorAndAntialiasingOrder()
    {
        final Saturation.OversamplingMode lin = Saturation.OversamplingMode.LINEAR_PHASE;
        final Oversampling.Band preset = Oversampling.Band.PRESET;
        final int[] factors = { 1, 2, 4, 8, 16 };
        final int[] expected = { 0, 64, 70, 93, 95 };   // setOversampling(): 64 at 2x, 70 at 4x, 93 at 8x, 95 at 16x
        for (int k = 0; k < factors.length; ++k)
        {
            final int f = factors[k];
            assertEquals(expected[k], latency(f, Saturation.Antialiasing.OFF, lin, preset, 48000.0), f + "x");
            assertEquals(expected[k], latency(f, Saturation.Antialiasing.FIRST_ORDER, lin, preset, 48000.0), f + "x first order");
            assertEquals(expected[k] + (f == 2 ? 2 : 1), latency(f, Saturation.Antialiasing.SECOND_ORDER, lin, preset, 48000.0),
                         f + "x second order");
        }
        // Minimum phase: 3 samples at 2x and 48 kHz.
        assertEquals(3, latency(2, Saturation.Antialiasing.OFF, Saturation.OversamplingMode.MINIMUM_PHASE, preset, 48000.0));
        // Audible band at 2x: 56 at 44.1 kHz, 32 at 48 kHz, 10 at 96 kHz.
        assertEquals(56, latency(2, Saturation.Antialiasing.OFF, lin, Oversampling.Band.AUDIBLE, 44100.0));
        assertEquals(32, latency(2, Saturation.Antialiasing.OFF, lin, Oversampling.Band.AUDIBLE, 48000.0));
        assertEquals(10, latency(2, Saturation.Antialiasing.OFF, lin, Oversampling.Band.AUDIBLE, 96000.0));
        // The default is 2x; automatic oversampling is 2x at 44.1 / 48 kHz and none at 96 kHz.
        final Saturation d = new Saturation();
        d.prepare(48000.0, 2, 512);
        assertEquals(64, d.getLatency());
        for (double rate : new double[] { 44100.0, 48000.0, 96000.0 })
        {
            final Saturation a = new Saturation();
            a.setAutomaticOversampling(true);
            a.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
            a.prepare(rate, 2, 512);
            assertEquals(rate < 88200.0 ? 66 : 1, a.getLatency(), "automatic, second order, " + rate);
        }
    }

    @Test
    void antialiasingLowersTheTopAsDocumented()
    {
        final Saturation.Algorithm sc = Saturation.Algorithm.SOFT_CLIP, ms = Saturation.Algorithm.MULTI_STAGE;
        // FirstOrder at 2x: -0.47 dB at 10 kHz, -1.25 at 16 kHz, -2.0 at 20 kHz.
        assertEquals(-0.47, toDb(toneGain(make(sc, 2, Saturation.Antialiasing.FIRST_ORDER, 0.0), 10000.0)), 0.02);
        assertEquals(-1.25, toDb(toneGain(make(sc, 2, Saturation.Antialiasing.FIRST_ORDER, 0.0), 16000.0)), 0.02);
        assertEquals(-2.0, toDb(toneGain(make(sc, 2, Saturation.Antialiasing.FIRST_ORDER, 0.0), 20000.0)), 0.05);
        // SecondOrder: -1.3 dB at 10 kHz and -5.9 dB at 20 kHz at 2x, -1.3 dB at 20 kHz at 4x.
        assertEquals(-1.3, toDb(toneGain(make(sc, 2, Saturation.Antialiasing.SECOND_ORDER, 0.0), 10000.0)), 0.05);
        assertEquals(-5.9, toDb(toneGain(make(sc, 2, Saturation.Antialiasing.SECOND_ORDER, 0.0), 20000.0)), 0.05);
        assertEquals(-1.3, toDb(toneGain(make(sc, 4, Saturation.Antialiasing.SECOND_ORDER, 0.0), 20000.0)), 0.05);
        // MultiStage, against its own plain response: -2.3 and -9.9 dB at 2x, -3.9 dB at 20 kHz at 4x.
        final double off10 = toDb(toneGain(make(ms, 2, Saturation.Antialiasing.OFF, 0.0), 10000.0));
        final double off20 = toDb(toneGain(make(ms, 2, Saturation.Antialiasing.OFF, 0.0), 20000.0));
        final double off20x4 = toDb(toneGain(make(ms, 4, Saturation.Antialiasing.OFF, 0.0), 20000.0));
        assertEquals(-2.3, toDb(toneGain(make(ms, 2, Saturation.Antialiasing.SECOND_ORDER, 0.0), 10000.0)) - off10, 0.1);
        assertEquals(-9.9, toDb(toneGain(make(ms, 2, Saturation.Antialiasing.SECOND_ORDER, 0.0), 20000.0)) - off20, 0.1);
        assertEquals(-3.9, toDb(toneGain(make(ms, 4, Saturation.Antialiasing.SECOND_ORDER, 0.0), 20000.0)) - off20x4, 0.1);
    }

    /** Tones from 1 to 19.25 kHz at which the header's worst aliases are reproduced. */
    private static final double[] TONES = { 1000, 3000, 5000, 7000, 9000, 11000, 12750, 13000, 15000, 15250, 17000, 19000, 19250 };

    private static double worstAlias(Saturation.Algorithm algo, int factor, Saturation.Antialiasing order, double drive, double... tones)
    {
        return Math.max(worstToneSpurDbc(make(algo, factor, order, drive), 0.5, tones),
                        worstToneSpurDbc(make(algo, factor, order, drive), 0.125, tones));
    }

    @Test
    void worstAliasAtTwiceTheRateAsDocumented()
    {
        // Worst alias over 1-19.25 kHz tones at -6/-18 dBFS, 2x, +24 dB.
        final Saturation.Antialiasing off = Saturation.Antialiasing.OFF, first = Saturation.Antialiasing.FIRST_ORDER,
            second = Saturation.Antialiasing.SECOND_ORDER;
        assertEquals(-42.1, worstAlias(Saturation.Algorithm.SOFT_CLIP, 2, second, 24.0, TONES), 0.3);
        assertEquals(-29.7, worstAlias(Saturation.Algorithm.SOFT_CLIP, 2, first, 24.0, TONES), 0.3);
        assertEquals(-15.2, worstAlias(Saturation.Algorithm.SOFT_CLIP, 2, off, 24.0, TONES), 0.3);
        assertEquals(-43.2, worstAlias(Saturation.Algorithm.TRANSFORMER, 2, second, 24.0, TONES), 0.3);
        assertEquals(-15.7, worstAlias(Saturation.Algorithm.TRANSFORMER, 2, off, 24.0, TONES), 0.3);
        assertEquals(-38.0, worstAlias(Saturation.Algorithm.TAPE, 2, second, 24.0, TONES), 0.3);
        assertEquals(-7.7, worstAlias(Saturation.Algorithm.TAPE, 2, off, 24.0, TONES), 0.3);
        assertEquals(-27.5, worstAlias(Saturation.Algorithm.EXCITER, 2, second, 24.0, TONES), 0.3);
        assertEquals(-9.8, worstAlias(Saturation.Algorithm.EXCITER, 2, off, 24.0, TONES), 0.3);
        assertEquals(-36.2, worstAlias(Saturation.Algorithm.MULTI_STAGE, 2, second, 24.0, TONES), 0.3);
        assertEquals(-5.5, worstAlias(Saturation.Algorithm.MULTI_STAGE, 2, off, 24.0, TONES), 0.3);
    }

    @Test
    void multistageChainsAsDocumented()
    {
        final Saturation.Antialiasing second = Saturation.Antialiasing.SECOND_ORDER;
        // The two-sample cascade: -83.3/-48.2/-37.1 dBc at 0/+6/+12 dB; the kernel chain -33.1/-36.2/-33.4 at +18/+24/+36 dB.
        assertEquals(-83.3, worstAlias(Saturation.Algorithm.MULTI_STAGE, 2, second, 0.0, TONES), 0.3);
        assertEquals(-48.2, worstAlias(Saturation.Algorithm.MULTI_STAGE, 2, second, 6.0, TONES), 0.3);
        assertEquals(-37.1, worstAlias(Saturation.Algorithm.MULTI_STAGE, 2, second, 12.0, TONES), 0.3);
        assertEquals(-33.1, worstAlias(Saturation.Algorithm.MULTI_STAGE, 2, second, 18.0, TONES), 0.3);
        assertEquals(-33.4, worstAlias(Saturation.Algorithm.MULTI_STAGE, 2, second, 36.0, TONES), 0.3);
        // At +18 dB both chains reach the same limit on a 19.25 kHz tone at -18 dBFS (-33.1 and -33.4 dBc).
        assertEquals(-33.25, worstToneSpurDbc(make(Saturation.Algorithm.MULTI_STAGE, 2, second, 18.0), 0.125, 19250.0), 0.25);
        // At 4x: -70.6 dBc at +12 dB (worst at 19.25 kHz) and -5.7 plain at +24 dB (worst at 14.75 kHz).
        assertEquals(-70.6, worstAlias(Saturation.Algorithm.MULTI_STAGE, 4, second, 12.0, 19250.0), 0.3);
        assertEquals(-5.7, worstAlias(Saturation.Algorithm.MULTI_STAGE, 4, Saturation.Antialiasing.OFF, 24.0, 14750.0), 0.3);
    }

    @Test
    void oversamplingTakesTheAliasOfTheDefaultCurveDown()
    {
        // A 10.1 kHz tone at -6 dBFS through the default SoftClip: an alias at
        // 17.7 kHz 34 dB down at 1x (46 dB with first order), 124 dB down at 2x.
        final double[] expected = { -34.0, -46.0, -124.0 };
        final int[] factor = { 1, 1, 2 };
        final boolean[] adaa = { false, true, false };
        for (int k = 0; k < 3; ++k)
        {
            final Saturation s = new Saturation();
            s.setOversampling(factor[k]);
            s.setAntialiasing(adaa[k]);
            s.prepare(48000.0, 1, 512);
            final double[] y = runSineDouble(s, 48000.0, 512, 48000, 10100.0, 0.5);
            final double fund = toneAmplitude(y, 24000, 24000, 10100.0, 48000.0);
            final double alias = toneAmplitude(y, 24000, 24000, 17700.0, 48000.0);
            assertEquals(expected[k], toDb(alias / fund), 0.5, "case " + k);
        }
    }

    @Test
    void audibleBandGivesUpWhatLiesOverTwentyKilohertz()
    {
        // A 30 kHz tone at 96 kHz comes back at 0.95 of its level.
        final Saturation s = new Saturation();
        s.setOversampling(2);
        s.setOversamplingBand(Oversampling.Band.AUDIBLE);
        s.prepare(96000.0, 1, 512);
        final double[] y = runSineDouble(s, 96000.0, 512, 96000, 30000.0, 0.01);
        assertEquals(0.95, toneAmplitude(y, 48000, 48000, 30000.0, 96000.0) / 0.01, 0.005);
    }

    @Test
    void firstOrderCombOfAPartialMixIsSlight()
    {
        // First order shifts the wet side by half a sample: with a partial
        // mix a comb of less than 0.2 dB below 10 kHz at 48 kHz.
        for (double hz : new double[] { 1000.0, 5000.0, 9900.0 })
        {
            final Supplier<Saturation> half = () -> {
                final Saturation s = new Saturation();
                s.setAntialiasing(true);
                s.setMix(0.5);
                s.prepare(48000.0, 1, 512);
                return s;
            };
            final Supplier<Saturation> wet = () -> {
                final Saturation s = new Saturation();
                s.setAntialiasing(true);
                s.prepare(48000.0, 1, 512);
                return s;
            };
            final double expected = 0.5 * (1.0 + toneGain(wet, hz));
            assertTrue(Math.abs(toDb(toneGain(half, hz) / expected)) < 0.2, "comb at " + hz);
        }
    }

    @Test
    void halfSampleInterpolatorResponse()
    {
        final SaturationDetail.CurveAlgorithm a = new SaturationDetail.CurveAlgorithm();
        a.reset();
        final double[] taps = new double[4];
        a.delayHalfSample(0.0, 0);   // seeds the history with zeros
        for (int k = 0; k < 4; ++k) taps[k] = a.delayHalfSample(k == 0 ? 1.0 : 0.0, 0);
        assertArrayEquals(new double[] { -0.0824, 0.5824, 0.5824, -0.0824 }, taps, 1e-15);
        double hi = -1e9, lo = 1e9;
        for (double f = 0.0; f <= 20000.0; f += 50.0)
        {
            final double db = magnitudeDb(taps, f, 96000.0);
            hi = Math.max(hi, db);
            lo = Math.min(lo, db);
        }
        // Within +0.10/-0.11 dB to 20 kHz at 96 kHz, -0.54 dB at 24 kHz, a zero at half the rate.
        // (The taps' own maximum, 1.6592 c - 0.6592 c^3 at c^2 = 0.839, is +0.114 dB at 12.6 kHz.)
        assertTrue(hi <= 0.115 && hi > 0.09, "top " + hi);
        assertTrue(lo >= -0.115 && lo < -0.10, "bottom " + lo);
        assertEquals(-0.54, magnitudeDb(taps, 24000.0, 96000.0), 0.01);
        assertTrue(magnitudeDb(taps, 48000.0, 96000.0) < -200.0);
    }

    private static double magnitudeDb(double[] taps, double f, double fs)
    {
        double re = 0.0, im = 0.0;
        for (int k = 0; k < taps.length; ++k)
        {
            re += taps[k] * Math.cos(2.0 * Math.PI * f * k / fs);
            im -= taps[k] * Math.sin(2.0 * Math.PI * f * k / fs);
        }
        return 10.0 * Math.log10(Math.max(re * re + im * im, 1e-40));
    }

    /** The measuring noise of the drive compensation. */
    private static double[] calibrationNoise()
    {
        final double[] v = new double[4096];
        int seed = 0x2545F491;
        double b0 = 0.0, b1 = 0.0, b2 = 0.0, sum = 0.0;
        for (int i = 0; i < 4096; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            final double white = (double) seed * (1.0 / 2147483648.0);
            b0 = 0.99765 * b0 + white * 0.0990460;
            b1 = 0.96300 * b1 + white * 0.2965164;
            b2 = 0.57000 * b2 + white * 1.0526913;
            v[i] = b0 + b1 + b2 + white * 0.1848;
        }
        for (int i = 1024; i < 4096; ++i) sum += v[i] * v[i];
        final double scale = Math.pow(10.0, -18.0 / 20.0) / Math.sqrt(sum / 3072.0);
        for (int i = 0; i < 4096; ++i) v[i] *= scale;
        return v;
    }

    private static double energy(Saturation.Algorithm algo, double drive, double rate, double scale)
    {
        final Saturation s = new Saturation();
        s.setAlgorithm(algo);
        s.setDrive(drive);
        s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
        s.setOutputMode(Saturation.OutputMode.WET_ONLY);
        s.setAutomaticOversampling(true);
        s.prepare(rate, 1, 256);
        final double[] x = calibrationNoise();
        for (int i = 0; i < x.length; ++i) x[i] *= scale;
        final double[] y = render(s, x, 256);
        double e = 0.0;
        for (int i = 1024; i < 4096; ++i) e += y[i] * y[i];
        return e;
    }

    @Test
    void driveCompensationTakesBackThreeQuartersOfTheMeasuredGain()
    {
        for (Saturation.Algorithm algo : new Saturation.Algorithm[] { Saturation.Algorithm.TUBE, Saturation.Algorithm.MULTI_STAGE })
            for (double drive : new double[] { -12.0, 6.0, 12.0, 24.0 })
            {
                final double rate = 44100.0;
                final double reference = energy(algo, 0.0, rate, 0.01) / (0.01 * 0.01);
                final double driven = energy(algo, drive, rate, 1.0);
                final double gainDb = 10.0 * Math.log10(driven / reference);
                final Saturation s = new Saturation();
                s.setAlgorithm(algo);
                s.setDrive(drive);
                s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
                s.setAutomaticOversampling(true);
                s.setDriveCompensation(true);
                s.prepare(rate, 2, 1024);
                final double expected = Math.max(-48.0, Math.min(24.0, -0.75 * gainDb));   // held to -48 to +24 dB
                assertEquals(expected, s.getDriveCompensationDb(), 1e-9, algo + " at " + drive + " dB");
            }
    }

    // -- Kernels against independent references --

    /** erf by its everywhere-positive series 2/sqrt(pi) e^-x^2 sum 2^n x^(2n+1) / (1 3 5 ... (2n+1)). */
    private static double erfReference(double x)
    {
        final double ax = Math.abs(x);
        double term = ax, sum = ax;
        for (int n = 1; n < 2000; ++n)
        {
            term *= 2.0 * ax * ax / (2 * n + 1);
            sum += term;
            if (term < sum * 1e-18) break;
        }
        return Math.copySign(Math.min(1.0, 2.0 / Math.sqrt(Math.PI) * Math.exp(-ax * ax) * sum), x);
    }

    @Test
    void erfMatchesItsSeries()
    {
        double worst = 0.0;
        for (int i = -7000; i <= 7000; ++i)
        {
            final double x = i * 1e-3 + 1.234e-5;
            worst = Math.max(worst, Math.abs(SaturationDetail.erf(x) - erfReference(x)));
        }
        assertTrue(worst < 1e-14, "worst " + worst);
        assertEquals(0.5204998778130465, SaturationDetail.erf(0.5), 2e-16);
        assertEquals(0.8427007929497149, SaturationDetail.erf(1.0), 2e-16);
        assertEquals(0.9953222650189527, SaturationDetail.erf(2.0), 2e-16);
        assertEquals(1.0, SaturationDetail.erf(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(-1.0, SaturationDetail.erf(Double.NEGATIVE_INFINITY), 0.0);
        assertTrue(Double.isNaN(SaturationDetail.erf(Double.NaN)));
        assertEquals(1e-300 * (1.0 + 0.12837916709551259), SaturationDetail.erf(1e-300), 1e-315);
    }

    /** L(t) = coth t - 1/t, its small-argument series written out independently. */
    private static double langevinRef(double t)
    {
        if (t < 0.5)
        {
            // (t cosh t - sinh t) / (t sinh t) = t sum_k>=1 2k t^(2k-2) / (2k+1)! over sum_k t^(2k) / (2k+1)!.
            double num = 0.0, den = 0.0, p = 1.0, prev = 1.0, fact = 1.0;   // p = t^(2k), prev = t^(2k-2), fact = (2k+1)!
            for (int k = 0; k < 30; ++k)
            {
                if (k >= 1) fact *= (2.0 * k) * (2.0 * k + 1.0);
                den += p / fact;
                if (k >= 1) num += 2.0 * k * prev / fact;
                prev = p;
                p *= t * t;
            }
            return t * num / den;
        }
        final double e = Math.exp(-2.0 * t);
        return (1.0 + e) / (1.0 - e) - 1.0 / t;
    }

    /** G(t) = ln(sinh t / t). */
    private static double gRef(double t)
    {
        if (t < 0.5)
        {
            double s = 0.0, p = t * t, fact = 6.0;
            for (int k = 1; k < 30; ++k)
            {
                s += p / fact;
                p *= t * t;
                fact *= (2.0 * k + 2.0) * (2.0 * k + 3.0);
            }
            return Math.log1p(s);
        }
        return t + Math.log((1.0 - Math.exp(-2.0 * t)) / (2.0 * t));
    }

    private static final double[] GL_X = new double[24], GL_W = new double[24];

    static
    {
        // Gauss-Legendre nodes and weights by Newton on P_24.
        final int n = GL_X.length;
        for (int i = 0; i < n; ++i)
        {
            double x = Math.cos(Math.PI * (i + 0.75) / (n + 0.5)), dp = 0.0;
            for (int it = 0; it < 100; ++it)
            {
                double p0 = 1.0, p1 = x;
                for (int k = 2; k <= n; ++k)
                {
                    final double p2 = ((2.0 * k - 1.0) * x * p1 - (k - 1.0) * p0) / k;
                    p0 = p1;
                    p1 = p2;
                }
                dp = n * (x * p1 - p0) / (x * x - 1.0);
                final double dx = p1 / dp;
                x -= dx;
                if (Math.abs(dx) < 1e-17) break;
            }
            GL_X[i] = x;
            GL_W[i] = 2.0 / ((1.0 - x * x) * dp * dp);
        }
    }

    /** Compensated composite Gauss-Legendre of G (which 0) or L^2 (which 1) over [0, x]. */
    private static double integral(int which, double x)
    {
        final int panels = Math.max(1, (int) Math.ceil(x / 0.25));
        final double h = x / panels;
        double sum = 0.0, c = 0.0;
        for (int p = 0; p < panels; ++p)
        {
            final double mid = (p + 0.5) * h;
            for (int i = 0; i < GL_X.length; ++i)
            {
                final double t = mid + 0.5 * h * GL_X[i];
                final double l = langevinRef(t);
                final double v = 0.5 * h * GL_W[i] * (which == 0 ? gRef(t) : l * l);
                final double y = v - c;
                final double s = sum + y;
                c = (s - sum) - y;
                sum = s;
            }
        }
        return sum;
    }

    @Test
    void tapeTableAntiderivativesMatchAQuadrature()
    {
        final SaturationDetail.AdaaTapeTable table = SaturationDetail.AdaaTapeTable.instance();
        final double[] lv = new double[3], out = new double[3];
        final SplittableRandom rng = new SplittableRandom(5);
        double worstP = 0.0, worstQ = 0.0, worstL = 0.0, worstG = 0.0;
        for (int k = 0; k < 300; ++k)
        {
            final double x = k < 100 ? 0.6 * rng.nextDouble() : Math.pow(2.0, -1.0 + 6.5 * rng.nextDouble());
            SaturationDetail.AdaaTapeTable.langevin(x, lv);
            table.antiderivatives(x, lv[0], out);
            final double p = integral(0, x), q = integral(1, x);
            worstL = Math.max(worstL, Math.abs(lv[0] - langevinRef(x)));
            worstG = Math.max(worstG, Math.abs(out[0] - gRef(x)) / Math.max(1.0, Math.abs(gRef(x))));
            worstP = Math.max(worstP, Math.abs(out[1] - p) / Math.max(1.0, Math.abs(p)));
            worstQ = Math.max(worstQ, Math.abs(out[2] - q) / Math.max(1.0, Math.abs(q)));
        }
        // P and Q within 5e-15 (relative, floor 1) of a compensated quadrature.
        // Q for x >= 0.5 is x - L - 2((x - G) - ln 2 + 1/x + C + B), terms near 2
        // that cancel to Q < 0.01 near x = 0.5: against a 40-digit quadrature
        // its rounding reaches 5.2e-15 at x = 0.6, and this double quadrature
        // adds up to 0.7e-15 of its own.
        assertTrue(worstP < 5e-15, "P " + worstP);
        assertTrue(worstQ < 6e-15, "Q " + worstQ);
        assertTrue(worstL < 1e-15, "L " + worstL);
        assertTrue(worstG < 1e-15, "G " + worstG);
    }

    /** The tape curve: L(x) for the x that solves h = a x - rho L(x), by bisection. */
    private static double tapeCurve(double h, double alpha)
    {
        final double a = 1.0 / (3.0 * (1.0 - alpha)), rho = alpha / (1.0 - alpha);
        final double s = h < 0.0 ? -1.0 : 1.0, ah = Math.abs(h);
        double lo = 0.0, hi = 3.0 * ah + 10.0;
        for (int it = 0; it < 200; ++it)
        {
            final double m = 0.5 * (lo + hi);
            if (a * m - rho * langevinRef(m) < ah) lo = m; else hi = m;
        }
        return s * langevinRef(0.5 * (lo + hi));
    }

    @Test
    void tapeKernelMatchesAQuadratureOfTheCurve()
    {
        // Within 1e-7 of a quadrature of the curve over the same B-spline,
        // for random knots from 1e-7 to 50 apart.
        final SplittableRandom rng = new SplittableRandom(3);
        final SaturationDetail.AdaaTape kernel = new SaturationDetail.AdaaTape();
        kernel.table = SaturationDetail.AdaaTapeTable.instance();
        double worst = 0.0;
        for (int trial = 0; trial < 300; ++trial)
        {
            final double alpha = 0.35 + 0.15 * (2.0 * rng.nextDouble() - 1.0);
            final double c = -10.0 + 20.0 * rng.nextDouble(), span = Math.pow(10.0, -7.0 + 8.7 * rng.nextDouble());
            final double[] u = { c + span * (2.0 * rng.nextDouble() - 1.0), c + span * (2.0 * rng.nextDouble() - 1.0),
                                 c + span * (2.0 * rng.nextDouble() - 1.0) };
            kernel.reset(u[2], alpha);
            kernel.process(u[1], alpha);
            final double y = kernel.process(u[0], alpha);
            java.util.Arrays.sort(u);
            double ref = 0.0;
            if (u[2] - u[0] < 1e-12)
                ref = tapeCurve(u[1], alpha);
            else
                for (int side = 0; side < 2; ++side)
                {
                    final double lo = side == 0 ? u[0] : u[1], hi = side == 0 ? u[1] : u[2];
                    if (!(hi > lo)) continue;
                    final int panels = 64;
                    final double h = (hi - lo) / panels;
                    for (int p = 0; p < panels; ++p)
                        for (int i = 0; i < GL_X.length; ++i)
                        {
                            final double t = lo + (p + 0.5) * h + 0.5 * h * GL_X[i];
                            final double w = side == 0 ? 2.0 * (t - u[0]) / ((u[2] - u[0]) * (u[1] - u[0]))
                                                       : 2.0 * (u[2] - t) / ((u[2] - u[0]) * (u[2] - u[1]));
                            ref += tapeCurve(t, alpha) * w * 0.5 * h * GL_W[i];
                        }
                }
            worst = Math.max(worst, Math.abs(y - ref));
        }
        assertTrue(worst < 1e-7, "worst " + worst);
    }
}
