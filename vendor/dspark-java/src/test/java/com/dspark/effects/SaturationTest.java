package com.dspark.effects;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.Consumer;

import static com.dspark.effects.SaturationSignals.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Saturation cases of the C++ TestEffectsTone.cpp and TestState.cpp, with their signals and thresholds. */
class SaturationTest
{
    private static void assertFiniteBounded(double[] x, int n, double lo, double hi)
    {
        for (int i = 0; i < n; ++i)
        {
            assertTrue(Double.isFinite(x[i]), "NaN or Inf at " + i);
            assertTrue(x[i] >= lo && x[i] <= hi, "out of bounds at " + i + ": " + x[i]);
        }
    }

    /** measureFrequencyMagnitude of the C++ TestSignals.h. */
    private static double magnitudeAt(double[] buf, int n, float freq, float sampleRate)
    {
        final double w = 2.0 * Math.PI * freq / sampleRate;
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            re += buf[i] * Math.cos(w * i);
            im += buf[i] * Math.sin(w * i);
        }
        return Math.sqrt(re * re + im * im) * 2.0 / n;
    }

    @Test
    void outputBounded()
    {
        final Saturation sat = new Saturation();
        sat.prepare(44100.0, 2, 512);
        sat.setDrive(24.0);
        sat.setMix(1.0);
        final double[][] tb = stereoSine(2, 4096, 440.0f, 44100.0f, 1.0f);
        sat.process(tb, 4096);
        assertFiniteBounded(tb[0], 4096, -5.0, 5.0);
    }

    @Test
    void antialiasingBoundedAndActive()
    {
        final Saturation aa = new Saturation();
        aa.prepare(44100.0, 2, 512);
        aa.setDrive(18.0);
        aa.setMix(1.0);
        aa.setAntialiasing(true);
        final double[][] tbAa = stereoSine(2, 4096, 2000.0f, 44100.0f, 1.0f);
        aa.process(tbAa, 4096);
        assertFiniteBounded(tbAa[0], 4096, -2.0, 2.0);

        final Saturation plain = new Saturation();
        plain.prepare(44100.0, 2, 512);
        plain.setDrive(18.0);
        plain.setMix(1.0);
        final double[][] tbPl = stereoSine(2, 4096, 2000.0f, 44100.0f, 1.0f);
        plain.process(tbPl, 4096);

        double maxDiff = 0.0;
        for (int i = 0; i < 4096; i++) maxDiff = Math.max(maxDiff, Math.abs(tbAa[0][i] - tbPl[0][i]));
        assertTrue(maxDiff > 0.001, "ADAA engaged: " + maxDiff);
    }

    @Test
    void silence()
    {
        final Saturation sat = new Saturation();
        sat.prepare(44100.0, 2, 512);
        sat.setDrive(6.0);
        final double[][] tb = new double[2][512];
        sat.process(tb, 512);
        for (double v : tb[0]) assertTrue(Math.abs(v) <= 0.001);
    }

    @Test
    void addsHarmonics()
    {
        final Saturation sat = new Saturation();
        sat.prepare(44100.0, 2, 512);
        sat.setDrive(18.0);
        sat.setMix(1.0);
        final int n = 8192;
        final double[][] tb = stereoSine(2, n, 440.0f, 44100.0f, 1.0f);
        sat.process(tb, n);
        final double mag440 = magnitudeAt(tb[0], n, 440.0f, 44100.0f);
        final double mag880 = magnitudeAt(tb[0], n, 880.0f, 44100.0f);
        assertTrue(mag880 > 0.001);
        assertTrue(mag440 > mag880);
    }

    /** Steady gain (dB) of a sine through an algorithm at neutral drive (RMS over the last 3/4). */
    private static double satFreqGainDb(Saturation.Algorithm algo, float freq, float amp)
    {
        final int n = 16384, skip = 4096;
        final Saturation sat = new Saturation();
        sat.prepare(44100.0, 2, n);
        sat.setAlgorithm(algo);
        sat.setDrive(0.0);
        sat.setMix(1.0);
        final double[][] tb = stereoSine(2, n, freq, 44100.0f, amp);
        sat.process(tb, n);
        double acc = 0.0;
        for (int i = skip; i < n; ++i) acc += tb[0][i] * tb[0][i];
        final double rms = Math.sqrt(acc / (n - skip));
        return 20.0f * (float) Math.log10(rms / (amp / Math.sqrt(2.0)) + 1e-30);
    }

    private static double satToneGainDb(Saturation.Algorithm algo, float amp)
    {
        return satFreqGainDb(algo, 1000.0f, amp);
    }

    @Test
    void tapeNoLowLevelDeadZone()
    {
        final double gQuiet = satToneGainDb(Saturation.Algorithm.TAPE, 0.00316f);
        final double gMid = satToneGainDb(Saturation.Algorithm.TAPE, 0.1f);
        assertEquals(0.0, gQuiet, 1.5);
        assertEquals(0.0, gMid, 1.5);
        assertTrue(Math.abs(gQuiet - gMid) < 1.0);
    }

    @Test
    void multistageIsGainStaged()
    {
        final double gQuiet = satToneGainDb(Saturation.Algorithm.MULTI_STAGE, 0.00316f);
        final double gMid = satToneGainDb(Saturation.Algorithm.MULTI_STAGE, 0.1f);
        assertEquals(0.0, gQuiet, 1.5);
        assertTrue(Math.abs(gQuiet - gMid) < 1.5);
    }

    @Test
    void transformerAndMultistageAreSpectrallyBalanced()
    {
        for (Saturation.Algorithm algo : new Saturation.Algorithm[] { Saturation.Algorithm.TRANSFORMER, Saturation.Algorithm.MULTI_STAGE })
        {
            final double gLow = satFreqGainDb(algo, 100.0f, 0.1f);
            final double gMid = satFreqGainDb(algo, 3000.0f, 0.1f);
            assertTrue(Math.abs(gLow - gMid) < 3.0, algo + ": " + gLow + " vs " + gMid);
        }
    }

    @Test
    void allAlgorithmsHonourNeutralDrive()
    {
        for (int ai = 0; ai < 10; ++ai)
        {
            final Saturation.Algorithm algo = Saturation.Algorithm.values()[ai];
            final double gQuiet = satToneGainDb(algo, 0.00316f);
            final double gMid = satToneGainDb(algo, 0.1f);
            assertTrue(gQuiet > -10.0, algo + " " + gQuiet);
            assertTrue(gQuiet < 1.5, algo + " " + gQuiet);
            assertTrue(Math.abs(gQuiet - gMid) < 1.5, algo + " " + gQuiet + " " + gMid);
        }
    }

    /** satRunSine: nBlocks of a phase-continuous 500 Hz stereo sine (blocks of 256 at 44.1 kHz); channel 0 of the last block. */
    private static double[] satRunSine(Saturation sat, double[] phase, int nBlocks)
    {
        final int block = 256;
        final double[][] tb = new double[2][block];
        for (int b = 0; b < nBlocks; ++b)
        {
            for (int i = 0; i < block; ++i)
            {
                final double v = 0.5f * (float) Math.sin(2.0 * 3.14159265358979 * phase[0]);
                tb[0][i] = v;
                tb[1][i] = v;
                phase[0] += 500.0 / 44100.0;
                if (phase[0] >= 1.0) phase[0] -= 1.0;
            }
            sat.process(tb, block);
        }
        return tb[0].clone();
    }

    private static double satRmsDiff(double[] a, double[] b)
    {
        double acc = 0.0;
        for (int i = 0; i < a.length; ++i)
        {
            final double d = a[i] - b[i];
            acc += d * d;
        }
        return Math.sqrt(acc / (a.length == 0 ? 1 : a.length));
    }

    @Test
    void algorithmSwitchRevertsCleanly()
    {
        final Saturation subject = new Saturation(), refSoft = new Saturation(), refDown = new Saturation();
        for (Saturation s : new Saturation[] { subject, refSoft, refDown })
        {
            s.prepare(44100.0, 2, 256);
            s.setDrive(30.0);
            s.setMix(1.0);
        }
        refDown.setAlgorithm(Saturation.Algorithm.DOWNSAMPLE);

        final double[] phS = { 0.0 }, phA = { 0.0 }, phB = { 0.0 };
        satRunSine(subject, phS, 1);
        satRunSine(refSoft, phA, 1);
        satRunSine(refDown, phB, 1);
        subject.setAlgorithm(Saturation.Algorithm.DOWNSAMPLE);
        satRunSine(subject, phS, 1);
        satRunSine(refSoft, phA, 1);
        satRunSine(refDown, phB, 1);
        subject.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
        final double[] tailSub = satRunSine(subject, phS, 40);
        final double[] tailSoft = satRunSine(refSoft, phA, 40);
        final double[] tailDown = satRunSine(refDown, phB, 40);

        assertTrue(satRmsDiff(tailSub, tailSoft) < 0.01);
        assertTrue(satRmsDiff(tailSub, tailDown) > 0.5);
        assertSame(Saturation.Algorithm.SOFT_CLIP, subject.getCurrentAlgorithm());
    }

    @Test
    void resetCompletesPendingSwitch()
    {
        final Saturation subject = new Saturation(), refDown = new Saturation();
        for (Saturation s : new Saturation[] { subject, refDown })
        {
            s.prepare(44100.0, 2, 256);
            s.setDrive(30.0);
            s.setMix(1.0);
        }
        refDown.setAlgorithm(Saturation.Algorithm.DOWNSAMPLE);

        final double[] phS = { 0.0 }, phB = { 0.0 };
        subject.setAlgorithm(Saturation.Algorithm.DOWNSAMPLE);
        satRunSine(subject, phS, 1);
        subject.reset();
        refDown.reset();
        phS[0] = phB[0] = 0.0;

        final double[] tailSub = satRunSine(subject, phS, 40);
        final double[] tailDown = satRunSine(refDown, phB, 40);
        assertTrue(satRmsDiff(tailSub, tailDown) < 0.01);
    }

    private static double satTiltGainDb(float freq, float tiltDb)
    {
        final int n = 16384, skip = 4096;
        final Saturation sat = new Saturation();
        sat.prepare(44100.0, 2, n);
        sat.setDrive(-24.0);
        sat.setMix(1.0);
        sat.setPostFilterTilt(1000.0, tiltDb);
        final double[][] tb = stereoSine(2, n, freq, 44100.0f, 0.01f);
        sat.process(tb, n);
        double acc = 0.0;
        for (int i = skip; i < n; ++i) acc += tb[0][i] * tb[0][i];
        final double rms = Math.sqrt(acc / (n - skip));
        return 20.0f * (float) Math.log10(rms / (0.01 / Math.sqrt(2.0)) + 1e-30);
    }

    @Test
    void postTiltIsATilt()
    {
        final double ref = satTiltGainDb(1000.0f, 0.0f);
        final double g200 = satTiltGainDb(200.0f, 6.0f) - ref;
        final double g1k = satTiltGainDb(1000.0f, 6.0f) - ref;
        final double g8k = satTiltGainDb(8000.0f, 6.0f) - ref;
        assertEquals(0.0, g1k, 0.75);
        assertTrue(g8k - g200 > 3.0);
        assertTrue(g8k > 1.0);
        assertTrue(g200 < -1.0);
    }

    @Test
    void invalidInputsAreIgnored()
    {
        final double nan = Double.NaN, inf = Double.POSITIVE_INFINITY;
        final Saturation subject = new Saturation(), twin = new Saturation();
        for (Saturation s : new Saturation[] { subject, twin })
        {
            s.prepare(44100.0, 2, 256);
            s.setDrive(12.0);
            s.setMix(1.0);
        }

        subject.setDrive(nan);
        subject.setCharacter(inf);
        subject.setMix(nan);
        subject.setPreFilterHpFrequency(nan);
        subject.setPostFilterTilt(nan, nan);
        subject.setOutputGain(nan);
        subject.setAnalogDrift(nan);
        subject.setSlewSensitivity(nan);
        subject.prepare(nan, -3, 0);
        subject.setAlgorithm(null);
        subject.setAlgorithm(Saturation.Algorithm.CURVE);   // the C++ clamps an id of 97 to the last one
        twin.setAlgorithm(Saturation.Algorithm.CURVE);

        final double[] phS = { 0.0 }, phT = { 0.0 };
        final double[] tailSub = satRunSine(subject, phS, 10);
        final double[] tailTwin = satRunSine(twin, phT, 10);
        for (double v : tailSub) assertTrue(Double.isFinite(v));
        assertArrayEquals(tailTwin, tailSub, 0.0);

        final Saturation os = new Saturation();
        os.setOversampling(4);
        os.prepare(44100.0, 2, 512);
        assertEquals(os.getLatency(), os.getLatencySamples());
        assertTrue(os.getLatency() > 0);
    }

    @Test
    void extraChannelsAreSafe()
    {
        final Saturation sat = new Saturation();
        sat.prepare(44100.0, 2, 256);
        sat.setDrive(12.0);
        sat.setMix(1.0);
        sat.setAnalogDrift(1.0);
        sat.setAlgorithm(Saturation.Algorithm.TUBE);

        double[][] tb = null;
        for (int b = 0; b < 20; ++b)
        {
            tb = stereoSine(4, 256, 500.0f, 44100.0f, 0.5f);
            sat.process(tb, 256);
        }
        for (int ch = 0; ch < 4; ++ch) assertFiniteBounded(tb[ch], 256, -2.0, 2.0);
    }

    private static double residualDc(double rate)
    {
        final Saturation sat = new Saturation();
        sat.setOversampling(2);
        sat.prepare(rate, 2, 512);
        sat.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
        sat.setDrive(6.0);
        sat.setDcBlocking(true);

        final int total = (int) rate;
        final int start = total - (int) (rate * 0.25);
        final double[][] tb = new double[2][512];
        double acc = 0.0;
        int count = 0;
        for (int i = 0; i < total; i += 512)
        {
            for (int k = 0; k < 512; ++k)
            {
                final double x = (float) (0.3 + 0.4 * Math.sin(TWO_PI * 1000.0 * (i + k) / rate));
                tb[0][k] = tb[1][k] = x;
            }
            sat.process(tb, 512);
            for (int k = 0; k < 512; ++k)
                if (i + k >= start) { acc += tb[0][k]; ++count; }
        }
        return acc / count;
    }

    @Test
    void dcBlockingWorksAtHighRates()
    {
        assertTrue(Math.abs(residualDc(48000.0)) < 1e-3);
        assertTrue(Math.abs(residualDc(192000.0)) < 1e-3);
    }

    // -- The curve family, minimum-phase oversampling and the stages around the curve --

    /** Harmonic k of a curve on a sine of amplitude amp, in dB, by its Fourier sum. */
    private static double harmonic(java.util.function.DoubleUnaryOperator curve, double amp, int k)
    {
        final int n = 4096;
        double re = 0.0, im = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double ph = 2.0 * Math.PI * i / n;
            final double y = curve.applyAsDouble(amp * Math.sin(ph));
            re += y * Math.cos(k * ph);
            im += y * Math.sin(k * ph);
        }
        return 20.0 * Math.log10(Math.max(2.0 * Math.sqrt(re * re + im * im) / n, 1e-15));
    }

    private static Consumer<Saturation> curve(double hardness, double curvature, double asymmetry, double drive)
    {
        return s -> {
            s.setAlgorithm(Saturation.Algorithm.CURVE);
            s.setOversampling(4);
            s.setDrive(drive);
            s.setCurveShape(hardness, curvature, asymmetry);
        };
    }

    @Test
    void curveFamilyShapesTheKneeTheTurnAndTheHalves()
    {
        final double[] tone = sine48(1000.0, 0.5, 24000);

        final double[] plain = run48(tone, curve(0.0, 1.0, 0.0, 12.0));
        final double g = Math.pow(10.0, 12.0 / 20.0);
        assertEquals(harmonic(Math::tanh, 0.5 * g, 1), line(plain, 1000.0), 0.05);
        assertEquals(harmonic(Math::tanh, 0.5 * g, 3), line(plain, 3000.0), 0.1);
        assertEquals(harmonic(Math::tanh, 0.5 * g, 5), line(plain, 5000.0), 0.3);
        assertTrue(line(plain, 2000.0) - line(plain, 1000.0) < -100.0);

        final double[] soft = run48(tone, curve(0.0, 1.0, 0.0, 0.0));
        final double[] straight = run48(tone, curve(0.6, 1.0, 0.0, 0.0));
        assertTrue(line(soft, 3000.0) - line(soft, 1000.0) > -40.0);
        assertTrue(line(straight, 3000.0) - line(straight, 1000.0) < -100.0);

        final double[] late = run48(tone, curve(0.0, 4.0, 0.0, 0.0));
        assertTrue(line(late, 3000.0) - line(late, 1000.0) < line(soft, 3000.0) - line(soft, 1000.0) - 15.0);
        final double[] early = run48(tone, curve(0.0, 0.5, 0.0, 0.0));
        assertTrue(line(early, 3000.0) - line(early, 1000.0) > line(soft, 3000.0) - line(soft, 1000.0) + 3.0);

        final double[] uneven = run48(tone, s -> { curve(0.0, 1.0, 1.0, 12.0).accept(s); s.setDcBlocking(false); });
        double top = 0.0, bottom = 0.0;
        for (int i = 12000; i < uneven.length; ++i)
        {
            top = Math.max(top, uneven[i]);
            bottom = Math.min(bottom, uneven[i]);
        }
        assertEquals(Math.tanh(2.0) / (0.5 * Math.tanh(4.0)), top / -bottom, 0.05);
        assertTrue(line(uneven, 2000.0) - line(uneven, 1000.0) > -30.0);
        final double[] mirrored = run48(tone, s -> { curve(0.0, 1.0, -1.0, 12.0).accept(s); s.setDcBlocking(false); });
        double topM = 0.0, bottomM = 0.0;
        for (int i = 12000; i < mirrored.length; ++i)
        {
            topM = Math.max(topM, mirrored[i]);
            bottomM = Math.min(bottomM, mirrored[i]);
        }
        assertEquals(Math.tanh(2.0) / (0.5 * Math.tanh(4.0)), -bottomM / topM, 0.05);
        assertTrue(-bottomM < 1.0);

        final Saturation s = new Saturation();
        s.setCurveShape(0.3, 2.0, -0.5);
        s.setCurveShape(Double.NaN, 99.0, -7.0);
        assertEquals(0.3, s.getCurveHardness(), 0.0);
        assertEquals(4.0, s.getCurveCurvature(), 0.0);
        assertEquals(-1.0, s.getCurveAsymmetry(), 0.0);
        final double[] small = run48(tone, curve(0.2, 1.5, 0.3, 6.0), 64);
        final double[] large = run48(tone, curve(0.2, 1.5, 0.3, 6.0), 2048);
        double farthest = 0.0;
        for (int i = 0; i < small.length; ++i) farthest = Math.max(farthest, Math.abs(small[i] - large[i]));
        assertTrue(farthest < 1e-6, "block split " + farthest);
    }

    private static Consumer<Saturation> base(Saturation.OversamplingMode mode, double mix)
    {
        return s -> {
            s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
            s.setOversampling(2);
            s.setOversamplingMode(mode);
            s.setMix(mix);
            s.setPreFilterHpFrequency(10.0);
        };
    }

    @Test
    void minimumPhaseOversamplingKeepsTheMixInPhase()
    {
        final Saturation linear = new Saturation(), minimum = new Saturation();
        base(Saturation.OversamplingMode.LINEAR_PHASE, 1.0).accept(linear);
        base(Saturation.OversamplingMode.MINIMUM_PHASE, 1.0).accept(minimum);
        linear.prepare(48000.0, 1, 512);
        minimum.prepare(48000.0, 1, 512);
        assertTrue(linear.getLatency() > 20);
        assertTrue(minimum.getLatency() > 0);
        assertTrue(minimum.getLatency() < 8);
        assertSame(Saturation.OversamplingMode.MINIMUM_PHASE, minimum.getOversamplingMode());

        for (double hz : new double[] { 500.0, 2000.0, 8000.0, 15000.0, 19000.0 })
        {
            final double[] in = sine48(hz, 0.01, 24000);
            final double wet = line(run48(in, base(Saturation.OversamplingMode.MINIMUM_PHASE, 1.0)), hz);
            final double half = line(run48(in, base(Saturation.OversamplingMode.MINIMUM_PHASE, 0.5)), hz);
            assertEquals(wet, half, 0.03, "minimum phase at " + hz);
            if (hz < 16000.0)
            {
                final double wetLinear = line(run48(in, base(Saturation.OversamplingMode.LINEAR_PHASE, 1.0)), hz);
                final double halfLinear = line(run48(in, base(Saturation.OversamplingMode.LINEAR_PHASE, 0.5)), hz);
                assertEquals(wetLinear, halfLinear, 0.05, "linear phase at " + hz);
            }
        }

        final double[] in = sine48(8000.0, 0.01, 24000);
        final double[] delta = run48(in, s -> {
            base(Saturation.OversamplingMode.MINIMUM_PHASE, 1.0).accept(s);
            s.setOutputMode(Saturation.OutputMode.DELTA);
        });
        assertTrue(rms(delta, 12000, 24000) < rms(in, 12000, 24000) * 0.01);

        final double[] deltaSecond = run48(in, s -> {
            base(Saturation.OversamplingMode.MINIMUM_PHASE, 1.0).accept(s);
            s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
            s.setOutputMode(Saturation.OutputMode.DELTA);
        });
        assertTrue(rms(deltaSecond, 12000, 24000) < rms(in, 12000, 24000) * 0.2);
    }

    private static double secondHarmonic(double[] tone, double amount, Saturation.Algorithm algo)
    {
        final double[] out = run48(tone, s -> {
            s.setAlgorithm(algo);
            s.setAsymmetry(amount);
        });
        return line(out, 2000.0) - line(out, 1000.0);
    }

    @Test
    void asymmetryAddsEvenHarmonicsBeforeAnyCurve()
    {
        final double[] tone = sine48(1000.0, 0.5, 24000);
        assertTrue(secondHarmonic(tone, 0.0, Saturation.Algorithm.SOFT_CLIP) < -100.0);
        final double full = secondHarmonic(tone, 1.0, Saturation.Algorithm.SOFT_CLIP);
        assertEquals(-24.1, full, 2.5);
        assertEquals(full - 6.0, secondHarmonic(tone, 0.5, Saturation.Algorithm.SOFT_CLIP), 1.0);
        assertEquals(full, secondHarmonic(tone, -1.0, Saturation.Algorithm.SOFT_CLIP), 0.5);
        assertTrue(secondHarmonic(tone, 1.0, Saturation.Algorithm.CURVE) > -30.0);

        final Saturation s = new Saturation();
        s.setAsymmetry(0.4);
        s.setAsymmetry(Double.NaN);
        assertEquals(0.4, s.getAsymmetry(), 0.0);
        s.setAsymmetry(5.0);
        assertEquals(1.0, s.getAsymmetry(), 0.0);
    }

    @Test
    void programNoiseFollowsTheProgrammeAndLeavesSilenceSilent()
    {
        final double[] in = sine48(1000.0, 0.25, 48000 * 9 / 2);
        for (int i = 48000; i < in.length; ++i) in[i] = 0.0;
        final double[] quiet = run48(in, s -> s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP));
        final double[] noisy = run48(in, s -> { s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP); s.setProgramNoise(-40.0); });
        final double[] difference = new double[in.length];
        for (int i = 0; i < in.length; ++i) difference[i] = noisy[i] - quiet[i];
        assertEquals(0.25 * 0.01 / Math.sqrt(3.0), rms(difference, 9600, 43200), 0.0005);
        double tail = 0.0;
        for (int i = in.length - 14400; i < in.length; ++i) tail = Math.max(tail, Math.abs(difference[i]));
        assertTrue(tail < 1e-7, "tail " + tail);

        final Saturation s = new Saturation();
        s.setProgramNoise(-200.0);
        assertEquals(-120.0, s.getProgramNoise(), 0.0);
        s.setProgramNoise(-5.0);
        assertEquals(-20.0, s.getProgramNoise(), 0.0);
        s.setProgramNoise(Double.NaN);
        assertEquals(-20.0, s.getProgramNoise(), 0.0);
    }

    /** Gain of the fundamental and (third) the third harmonic re the fundamental, for the dynamics. */
    private static double measureDynamics(double amp, double dynamics, double focus, double hz, double[] third)
    {
        final double[] in = sine48(hz, amp, 48000);
        final double[] out = run48(in, s -> {
            s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
            s.setDrive(12.0);
            s.setLevelTracking(dynamics);
            s.setTrackingCorner(focus);
        });
        third[0] = line(out, 3.0 * hz) - line(out, hz);
        return line(out, hz) - line(in, hz);
    }

    @Test
    void dynamicsHoldTheAmountOfSaturationWhateverTheLevel()
    {
        final double[] quiet = new double[1], loud = new double[1];
        measureDynamics(0.0316, 0.0, 0.0, 1000.0, quiet);
        measureDynamics(0.5, 0.0, 0.0, 1000.0, loud);
        assertTrue(loud[0] - quiet[0] > 20.0);

        final double[] quietHeld = new double[1], loudHeld = new double[1];
        final double gainQuiet = measureDynamics(0.0316, 1.0, 0.0, 1000.0, quietHeld);
        final double gainLoud = measureDynamics(0.5, 1.0, 0.0, 1000.0, loudHeld);
        assertTrue(Math.abs(loudHeld[0] - quietHeld[0]) < 1.5);
        assertTrue(Math.abs(gainLoud - gainQuiet) < 0.3);

        final double[] quietSpread = new double[1], loudSpread = new double[1];
        measureDynamics(0.0316, -1.0, 0.0, 1000.0, quietSpread);
        measureDynamics(0.5, -1.0, 0.0, 1000.0, loudSpread);
        assertTrue(loudSpread[0] - quietSpread[0] > loud[0] - quiet[0] + 3.0);

        final double[] heard = new double[1], deaf = new double[1];
        measureDynamics(0.5, -1.0, 0.0, 3000.0, heard);
        measureDynamics(0.5, -1.0, 200.0, 3000.0, deaf);
        assertTrue(deaf[0] < heard[0] - 6.0);

        final Saturation s = new Saturation();
        s.setLevelTracking(-3.0);
        assertEquals(-1.0, s.getLevelTracking(), 0.0);
        s.setTrackingCorner(5.0);
        assertEquals(20.0, s.getTrackingCorner(), 0.0);
        s.setTrackingCorner(-1.0);
        assertEquals(0.0, s.getTrackingCorner(), 0.0);
    }

    private static double combGain(double amount, double hz, int block)
    {
        final double[] in = sine48(hz, 0.01, 48000);
        final double[] out = run48(in, s -> {
            s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
            s.setCombLoop(amount, 200.0);
        }, block);
        return line(out, hz) - line(in, hz);
    }

    @Test
    void feedbackTunesACombInsideTheCurve()
    {
        final double dip = 20.0 * Math.log10(1.0 / 1.8), peak = 20.0 * Math.log10(1.0 / 0.2);
        assertEquals(dip, combGain(0.8, 200.0, 512), 0.3);
        assertEquals(peak, combGain(0.8, 300.0, 512), 0.8);
        assertEquals(dip, combGain(0.8, 400.0, 512), 0.3);
        assertEquals(peak, combGain(-0.8, 200.0, 512), 0.8);
        assertEquals(dip, combGain(-0.8, 300.0, 512), 0.3);

        final Saturation s = new Saturation();
        s.setCombLoop(0.5, 200.0);
        s.prepare(48000.0, 1, 512);
        assertEquals(200.0, s.getCombLoopFrequency(), 0.01);
        s.setCombLoop(0.5, 20000.0);
        assertEquals(48000.0 / (16.0 + s.getLatency()), s.getCombLoopFrequency(), 0.01);
        s.setCombLoop(3.0, Double.NaN);
        assertEquals(0.95, s.getCombLoop(), 1e-6);

        final double[] in = sine48(330.0, 0.5, 48000);
        final Consumer<Saturation> setup = x -> {
            x.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
            x.setDrive(24.0);
            x.setCombLoop(0.95, 150.0);
        };
        final double[] small = run48(in, setup, 64), large = run48(in, setup, 2048);
        double farthest = 0.0, top = 0.0;
        for (int i = 0; i < small.length; ++i)
        {
            farthest = Math.max(farthest, Math.abs(small[i] - large[i]));
            top = Math.max(top, Math.abs(small[i]));
        }
        assertEquals(0.0, farthest, 0.0);
        assertTrue(Double.isFinite(top) && top < 2.0);
    }

    private static double compensatedLevel(double[] pinkNoise, double drive, boolean compensated)
    {
        final double[] out = run48(pinkNoise, s -> {
            s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
            s.setDrive(drive);
            s.setDriveCompensation(compensated);
        });
        return 20.0 * Math.log10(rms(out, 9600, 48000) / rms(pinkNoise, 9600, 48000));
    }

    @Test
    void driveCompensationLeavesAQuarterOfTheLevelTheDriveAdds()
    {
        final double[] pinkNoise = pink(49152, -18.0, 77);
        final double plain = compensatedLevel(pinkNoise, 24.0, false) - compensatedLevel(pinkNoise, 0.0, false);
        final double kept = compensatedLevel(pinkNoise, 24.0, true) - compensatedLevel(pinkNoise, 0.0, true);
        assertTrue(plain > 6.0);
        assertEquals(0.25 * plain, kept, 0.6);
        assertTrue(Math.abs(compensatedLevel(pinkNoise, 0.0, true) - compensatedLevel(pinkNoise, 0.0, false)) < 0.5);

        // A drive moved while it runs.
        final Saturation s = new Saturation();
        s.setDriveCompensation(true);
        s.prepare(48000.0, 1, 256);
        final double[] x = pinkNoise.clone();
        final double[] buf = new double[256];
        final double[][] view = { buf };
        for (int off = 0; off < x.length; off += 256)
        {
            if (off == 12288) s.setDrive(24.0);
            System.arraycopy(x, off, buf, 0, 256);
            s.process(view, 256);
            System.arraycopy(buf, 0, x, off, 256);
        }
        assertTrue(s.getDriveCompensationDb() < -5.0, "compensation " + s.getDriveCompensationDb());
        assertTrue(s.isDriveCompensation());
        s.setDriveCompensation(false);
        System.arraycopy(x, 0, buf, 0, 256);
        s.process(view, 256);
        assertEquals(0.0, s.getDriveCompensationDb(), 0.0);
    }

    // -- Double-precision cases --

    private static double phaseAt(Saturation.Algorithm algo, double character, double hz)
    {
        final double fs = 48000.0;
        final Saturation s = new Saturation();
        s.setAlgorithm(algo);
        s.setDrive(0.0);
        s.setCharacter(character);
        s.prepare(fs, 1, 480);
        final double[] x = new double[480];
        final double[][] c = { x };
        double re = 0.0, im = 0.0;
        for (int b = 0; b < 400; ++b)
        {
            for (int i = 0; i < 480; ++i) x[i] = 0.01 * Math.sin(2.0 * Math.PI * hz * (b * 480 + i) / fs);
            s.process(c, 480);
            if (b >= 200)
                for (int i = 0; i < 480; ++i)
                {
                    final double w = 2.0 * Math.PI * hz * (b * 480 + i - s.getLatencySamples()) / fs;
                    re += x[i] * Math.sin(w);
                    im += x[i] * Math.cos(w);
                }
        }
        return Math.atan2(im, re) * 180.0 / Math.PI;
    }

    @Test
    void dcFilterOnlyWhereACurveMakesDcAndModesSwitchWithoutAStep()
    {
        final double fs = 48000.0;
        final double symmetric = phaseAt(Saturation.Algorithm.SOFT_CLIP, 0.0, 20.0);
        final double asymmetric = phaseAt(Saturation.Algorithm.TUBE, 0.0, 20.0);
        assertTrue(Math.abs(asymmetric) > Math.abs(symmetric) + 5.0, symmetric + " / " + asymmetric);

        for (Saturation.Algorithm algo : new Saturation.Algorithm[] { Saturation.Algorithm.SOFT_CLIP, Saturation.Algorithm.TUBE, Saturation.Algorithm.TAPE })
        {
            final Saturation s = new Saturation();
            s.setAlgorithm(algo);
            s.setDrive(18.0);
            s.prepare(fs, 2, 256);
            final double[] l = new double[256], r = new double[256];
            final double[][] c = { l, r };
            final double[] out = new double[200 * 256];
            for (int b = 0; b < 200; ++b)
            {
                if (b == 120) s.setProcessingMode(Saturation.ProcessingMode.MID_SIDE);
                for (int i = 0; i < 256; ++i)
                {
                    final double t = (b * 256 + i) / fs;
                    l[i] = 0.5 * Math.sin(2.0 * Math.PI * 55.0 * t);
                    r[i] = 0.5 * Math.sin(2.0 * Math.PI * 82.0 * t);
                }
                s.process(c, 256);
                System.arraycopy(l, 0, out, b * 256, 256);
            }
            double before = 0.0, after = 0.0, around = 0.0;
            for (int i = 60 * 256; i < 110 * 256; ++i) before = Math.max(before, Math.abs(out[i] - out[i - 1]));
            for (int i = 140 * 256; i < 199 * 256; ++i) after = Math.max(after, Math.abs(out[i] - out[i - 1]));
            for (int i = 118 * 256; i < 126 * 256; ++i) around = Math.max(around, Math.abs(out[i] - out[i - 1]));
            assertTrue(around < 1.3 * Math.max(before, after), algo + ": " + around / Math.max(before, after));
        }
    }

    @Test
    void curveBasesKeepTheLineAndTheCeiling()
    {
        final double fs = 48000.0;
        for (int base = 0; base <= 6; ++base)
        {
            final Saturation.CurveBase curveBase = Saturation.CurveBase.values()[base];
            final double[] stats = new double[3];
            final double small = curveLevel(curveBase, 0.01, 0.0, null, fs);
            curveLevel(curveBase, 0.5, 24.0, stats, fs);
            final double h2 = stats[0], h3 = stats[1], peak = stats[2];
            assertEquals(0.0, 20.0 * Math.log10(small), 0.05, curveBase + " small-signal gain");
            assertTrue(peak < 1.02, curveBase + " peak " + peak);
            if (base <= 4) assertTrue(h2 < -90.0, curveBase + " H2 " + h2);
            else assertTrue(h2 > -60.0, curveBase + " H2 " + h2);
            assertTrue(h3 > -60.0, curveBase + " H3 " + h3);
        }
    }

    private static double curveLevel(Saturation.CurveBase base, double amp, double drive, double[] stats, double fs)
    {
        final Saturation s = new Saturation();
        s.setAlgorithm(Saturation.Algorithm.CURVE);
        s.setCurveBase(base);
        s.setDrive(drive);
        s.setOversampling(1);
        s.setDcBlocking(false);
        s.setPreFilterHpFrequency(10.0);
        s.prepare(fs, 1, 480);
        final double[] x = new double[480];
        final double[][] c = { x };
        double a1 = 0, b1 = 0, a2 = 0, b2 = 0, a3 = 0, b3 = 0, top = 0;
        for (int b = 0; b < 200; ++b)
        {
            for (int i = 0; i < 480; ++i) x[i] = amp * Math.sin(2.0 * Math.PI * 1000.0 * (b * 480 + i) / fs);
            s.process(c, 480);
            if (b < 100) continue;
            for (int i = 0; i < 480; ++i)
            {
                final double w = 2.0 * Math.PI * 1000.0 * (b * 480 + i) / fs, v = x[i];
                a1 += v * Math.sin(w);
                b1 += v * Math.cos(w);
                a2 += v * Math.sin(2 * w);
                b2 += v * Math.cos(2 * w);
                a3 += v * Math.sin(3 * w);
                b3 += v * Math.cos(3 * w);
                top = Math.max(top, Math.abs(v));
            }
        }
        final double f = Math.hypot(a1, b1);
        if (stats != null)
        {
            stats[0] = 20.0 * Math.log10(Math.hypot(a2, b2) / f + 1e-12);
            stats[1] = 20.0 * Math.log10(Math.hypot(a3, b3) / f + 1e-12);
            stats[2] = top;
        }
        return f / (100.0 * 480.0 * 0.5 * amp);
    }

    private static void runPan(boolean touch, double pan, double amp, double[] outL, double[] outR)
    {
        final double fs = 48000.0;
        final Saturation s = new Saturation();
        s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
        s.setDrive(12.0);
        if (touch) s.setDrivePan(pan);
        s.prepare(fs, 2, 480);
        final double[] l = new double[480], r = new double[480];
        final double[][] c = { l, r };
        for (int b = 0; b < 100; ++b)
        {
            for (int i = 0; i < 480; ++i)
                l[i] = r[i] = amp * Math.sin(2.0 * Math.PI * 1000.0 * (b * 480 + i) / fs);
            s.process(c, 480);
            System.arraycopy(l, 0, outL, b * 480, 480);
            System.arraycopy(r, 0, outR, b * 480, 480);
        }
    }

    private static double thd(double[] x)
    {
        final double fs = 48000.0;
        double a1 = 0, b1 = 0, all = 0;
        for (int i = 24000; i < x.length; ++i)
        {
            final double w = 2.0 * Math.PI * 1000.0 * i / fs;
            a1 += x[i] * Math.sin(w);
            b1 += x[i] * Math.cos(w);
            all += x[i] * x[i];
        }
        final double n = x.length - 24000;
        final double fund = 2.0 * (a1 * a1 + b1 * b1) / n;
        final double rest = Math.max(1e-30, all - fund);
        return 10.0 * Math.log10(rest / fund);
    }

    @Test
    void drivePanMovesTheSaturationNotTheLevel()
    {
        final int n = 48000;
        final double[] l0 = new double[n], r0 = new double[n], l1 = new double[n], r1 = new double[n];
        runPan(false, 0.0, 0.5, l0, r0);
        runPan(true, 0.0, 0.5, l1, r1);
        assertTrue(Arrays.equals(l0, l1) && Arrays.equals(r0, r1));
        runPan(true, -1.0, 0.5, l1, r1);
        assertTrue(thd(l1) > thd(l0) + 3.0);
        assertTrue(thd(r1) < thd(l0) - 3.0);
        runPan(true, -1.0, 0.001, l1, r1);
        runPan(false, 0.0, 0.001, l0, r0);
        double el = 0, er = 0, e0 = 0;
        for (int i = 24000; i < n; ++i)
        {
            el += l1[i] * l1[i];
            er += r1[i] * r1[i];
            e0 += l0[i] * l0[i];
        }
        assertEquals(0.0, 10.0 * Math.log10(el / e0), 0.05);
        assertEquals(0.0, 10.0 * Math.log10(er / e0), 0.05);
    }

    private static double toneLevel(int section, double hz, double db, double probe)
    {
        final double fs = 48000.0;
        final Saturation s = new Saturation();
        s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
        if (section >= 0) s.setTone(section, hz, db);
        s.prepare(fs, 1, 480);
        final double[] x = new double[480];
        final double[][] c = { x };
        double e = 0;
        for (int b = 0; b < 100; ++b)
        {
            for (int i = 0; i < 480; ++i) x[i] = 0.001 * Math.sin(2.0 * Math.PI * probe * (b * 480 + i) / fs);
            s.process(c, 480);
            if (b >= 50) for (double v : x) e += v * v;
        }
        return e;
    }

    private static double[] toneRun(boolean touch, boolean move)
    {
        final double fs = 48000.0;
        final Saturation s = new Saturation();
        s.setAlgorithm(Saturation.Algorithm.SOFT_CLIP);
        if (touch)
            for (int k = 0; k < 4; ++k) s.setTone(k, 300.0 * (k + 1), 0.0);
        s.prepare(fs, 1, 480);
        final double[] x = new double[480];
        final double[][] c = { x };
        final double[] out = new double[100 * 480];
        for (int b = 0; b < 100; ++b)
        {
            if (move && b == 30) s.setTone(0, 100.0, 12.0);
            if (move && b == 60) s.setTone(0, 100.0, 0.0);
            for (int i = 0; i < 480; ++i) x[i] = 0.05 * Math.sin(2.0 * Math.PI * 100.0 * (b * 480 + i) / fs);
            s.process(c, 480);
            System.arraycopy(x, 0, out, b * 480, 480);
        }
        return out;
    }

    private static double maxStepBlocks(double[] b, int fromBlock, int toBlock)
    {
        double m = 0;
        for (int i = fromBlock * 480 + 1; i < toBlock * 480; ++i) m = Math.max(m, Math.abs(b[i] - b[i - 1]));
        return m;
    }

    @Test
    void toneSectionsAreExactAndMoveWithoutAStep()
    {
        final double base500 = toneLevel(-1, 0, 0, 500.0), base2500 = toneLevel(-1, 0, 0, 2500.0);
        assertEquals(6.0, 10.0 * Math.log10(toneLevel(1, 500.0, 6.0, 500.0) / base500), 0.1);
        assertEquals(-6.0, 10.0 * Math.log10(toneLevel(2, 2500.0, -6.0, 2500.0) / base2500), 0.1);

        final double[] a = toneRun(false, false);
        double[] b = toneRun(true, false);
        assertArrayEquals(a, b, 0.0);
        b = toneRun(false, true);
        final double regime = Math.max(maxStepBlocks(b, 10, 29), maxStepBlocks(b, 45, 59));
        assertTrue(maxStepBlocks(b, 29, 36) < regime * 1.15);
        assertTrue(maxStepBlocks(b, 59, 66) < regime * 1.15);
        assertTrue(maxStepBlocks(b, 45, 59) > maxStepBlocks(b, 10, 29) * 1.5);
    }

    // -- TestState.cpp: the automatic factor (the state round trip is not ported) --

    @Test
    void automaticOversamplingFollowsTheHostRate()
    {
        final Saturation fx = new Saturation();
        assertFalse(fx.isOversamplingAutomatic());
        fx.setAutomaticOversampling(true);
        assertTrue(fx.isOversamplingAutomatic());
        final double[][] rates = { { 44100.0, 2 }, { 48000.0, 2 }, { 88200.0, 1 }, { 96000.0, 1 }, { 192000.0, 1 } };
        for (double[] each : rates)
        {
            fx.prepare(each[0], 2, 256);
            assertEquals((int) each[1], fx.getOversamplingFactor(), "rate " + each[0]);
            for (int pass = 0; pass < 4; ++pass)
            {
                final double[][] block = stereoSine(2, 256, 1000.0f, (float) each[0], 0.25f);
                fx.process(block, 256);
                for (double[] ch : block) for (double v : ch) assertFalse(Double.isNaN(v));
            }
        }
        fx.setOversampling(4);
        assertFalse(fx.isOversamplingAutomatic());
        fx.prepare(96000.0, 2, 256);
        assertEquals(4, fx.getOversamplingFactor());

        final Saturation a = new Saturation();
        a.setAutomaticOversampling(true);
        a.prepare(48000.0, 2, 256);
        assertTrue(a.getLatency() > 0);
        a.prepare(96000.0, 2, 256);
        assertEquals(0, a.getLatency());
        final Saturation late = new Saturation();
        late.prepare(96000.0, 2, 256);
        assertEquals(2, late.getOversamplingFactor());
        late.setAutomaticOversampling(true);
        assertEquals(1, late.getOversamplingFactor());
        assertEquals(0, late.getLatency());
    }
}
