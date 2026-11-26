package com.dspark.effects;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Common properties of the Saturation port and the way QuickMaster renders through it. */
class SaturationPropertiesTest
{
    /** A deterministic stereo programme: bass, partials with tremolo, gated noise, peak 0.9. */
    static double[][] programme(double fs, double seconds)
    {
        final int n = (int) (fs * seconds);
        final double[][] x = new double[2][n];
        int seed = 0x1234567;
        double lowL = 0.0, lowR = 0.0, peak = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double t = i / fs;
            final double env = 0.6 + 0.4 * Math.sin(2.0 * Math.PI * 0.5 * t);
            final double bass = env * (0.45 * Math.sin(2.0 * Math.PI * 55.0 * t) + 0.2 * Math.sin(2.0 * Math.PI * 110.0 * t + 0.3));
            final double mid = (0.5 + 0.5 * Math.sin(2.0 * Math.PI * 4.0 * t))
                             * (0.15 * Math.sin(2.0 * Math.PI * 440.0 * t) + 0.1 * Math.sin(2.0 * Math.PI * 660.0 * t + 1.0));
            seed = seed * 1664525 + 1013904223;
            final double w1 = (double) seed / 2147483648.0;
            seed = seed * 1664525 + 1013904223;
            final double w2 = (double) seed / 2147483648.0;
            lowL += 0.2 * (w1 - lowL);
            lowR += 0.2 * (w2 - lowR);
            final double gate = ((int) (t * 4.0) % 2 == 0) ? 0.35 : 0.02;
            x[0][i] = bass + mid + gate * lowL + 0.05 * Math.sin(2.0 * Math.PI * 7300.0 * t);
            x[1][i] = 0.9 * bass + 0.8 * mid + gate * lowR + 0.04 * Math.sin(2.0 * Math.PI * 9100.0 * t + 0.5);
            peak = Math.max(peak, Math.max(Math.abs(x[0][i]), Math.abs(x[1][i])));
        }
        for (double[] ch : x) for (int i = 0; i < n; ++i) ch[i] *= 0.9 / peak;
        return x;
    }

    /** Renders x[from, to) through a processor in blocks; returns the output (to - from samples). */
    static double[][] renderStereo(Saturation s, double[][] x, int from, int to, int block)
    {
        final int n = to - from;
        final double[][] out = new double[x.length][n];
        final double[][] buf = new double[x.length][block];
        for (int off = 0; off < n; off += block)
        {
            final int m = Math.min(block, n - off);
            for (int c = 0; c < x.length; ++c) System.arraycopy(x[c], from + off, buf[c], 0, m);
            s.process(buf, m);
            for (int c = 0; c < x.length; ++c) System.arraycopy(buf[c], 0, out[c], off, m);
        }
        return out;
    }

    /** The configuration QuickMaster renders with. */
    static Consumer<Saturation> quickMaster(Saturation.Algorithm algo, double drive)
    {
        return s -> {
            s.setAutomaticOversampling(true);
            s.setAlgorithm(algo);
            s.setDrive(drive);
            s.setCharacter(0.0);
            s.setAnalogDrift(0.0);
            s.setDcBlocking(true);
            s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
            s.setProcessingMode(Saturation.ProcessingMode.STEREO);
            s.setOutputMode(Saturation.OutputMode.NORMAL);
            s.setMix(1.0);
            s.setDriveCompensation(true);
        };
    }

    static Saturation prepared(Consumer<Saturation> setup, double fs, int channels, int maxBlock)
    {
        final Saturation s = new Saturation();
        setup.accept(s);
        s.prepare(fs, channels, maxBlock);
        return s;
    }

    @Test
    void stretchesFromRestJoinOneContinuousPass()
    {
        for (double fs : new double[] { 44100.0, 48000.0 })
            for (Saturation.Algorithm algo : new Saturation.Algorithm[] { Saturation.Algorithm.TUBE, Saturation.Algorithm.MULTI_STAGE })
            {
                final double drive = algo == Saturation.Algorithm.TUBE ? 7.3 : 4.1;
                final double[][] x = programme(fs, 6.0);
                final int total = x[0].length;
                final int join = (int) (4.0 * fs) + 17;
                final int start = join - (int) (1.5 * fs);
                final Saturation whole = prepared(quickMaster(algo, drive), fs, 2, 4096);
                final Saturation stretch = prepared(quickMaster(algo, drive), fs, 2, 4096);
                assertEquals(whole.getLatency(), stretch.getLatency());
                final double[][] a = renderStereo(whole, x, 0, total, 4096);
                final double[][] b = renderStereo(stretch, x, start, total, 4096);
                double peak = 0.0, worst = 0.0;
                for (double[] ch : x) for (double v : ch) peak = Math.max(peak, Math.abs(v));
                for (int c = 0; c < 2; ++c)
                    for (int t = join; t < total; ++t) worst = Math.max(worst, Math.abs(a[c][t] - b[c][t - start]));
                System.out.printf("join %s at %.0f Hz: latency %d, compensation %.3f dB, worst %.3g of the peak%n",
                                  algo, fs, whole.getLatency(), whole.getDriveCompensationDb(), worst / peak);
                assertTrue(worst <= 1e-6 * peak, algo + " at " + fs + ": " + worst / peak);

                // Nothing random with drift 0: a second continuous pass is the same to the bit.
                final double[][] again = renderStereo(prepared(quickMaster(algo, drive), fs, 2, 4096), x, 0, total, 4096);
                assertArrayEquals(a[0], again[0], 0.0);
                assertArrayEquals(a[1], again[1], 0.0);
            }
    }

    @Test
    void parametersSetBeforePrepareAreSettledFromTheFirstSample()
    {
        final List<Consumer<Saturation>> setups = new ArrayList<>();
        setups.add(quickMaster(Saturation.Algorithm.TUBE, 7.3));
        setups.add(quickMaster(Saturation.Algorithm.MULTI_STAGE, 4.1));
        setups.add(s -> { s.setAlgorithm(Saturation.Algorithm.TAPE); s.setDrive(9.0); s.setCharacter(0.4); s.setMix(0.6);
                          s.setOutputGain(-3.0); s.setPreFilterHpFrequency(45.0); s.setPostFilterTilt(2000.0, 3.0); });
        setups.add(s -> { s.setAlgorithm(Saturation.Algorithm.CURVE); s.setCurveShape(0.2, 1.5, 0.3); s.setDrive(12.0);
                          s.setTone(0, 120.0, 4.0); s.setTone(3, 9000.0, -3.0); s.setAntialiasing(true); });
        for (Consumer<Saturation> setup : setups)
        {
            final double fs = 48000.0;
            final double[][] x = programme(fs, 1.0);
            final double[][] junk = programme(fs, 0.5);
            final Saturation a = prepared(setup, fs, 2, 512);
            final double[][] ya = renderStereo(a, x, 0, x[0].length, 512);

            // The same settings reached at run time and then settled by reset().
            final Saturation b = new Saturation();
            b.prepare(fs, 2, 512);
            setup.accept(b);
            renderStereo(b, junk, 0, junk[0].length, 512);
            b.reset();
            final double[][] yb = renderStereo(b, x, 0, x[0].length, 512);
            assertEquals(a.getDriveCompensationDb(), b.getDriveCompensationDb(), 0.0);
            assertArrayEquals(ya[0], yb[0], 0.0);
            assertArrayEquals(ya[1], yb[1], 0.0);
        }
    }

    private static List<Consumer<Saturation>> variety()
    {
        final List<Consumer<Saturation>> v = new ArrayList<>();
        v.add(s -> { });
        v.add(quickMaster(Saturation.Algorithm.TUBE, 7.3));
        v.add(quickMaster(Saturation.Algorithm.MULTI_STAGE, 4.1));
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.MULTI_STAGE); s.setDrive(18.0); s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER); });
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.TAPE); s.setDrive(12.0); s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER); s.setOversampling(4); });
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.TRANSFORMER); s.setDrive(12.0); s.setCharacter(0.5); s.setProcessingMode(Saturation.ProcessingMode.MID_ONLY); });
        // (A drive pan set before prepare() glides from the centre across the first block, as in the C++.)
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.HARD_CLIP); s.setDrive(9.0); s.setAntialiasing(true); s.setAnalogDrift(0.7); });
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.EXCITER); s.setDrive(6.0); s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER); s.setOversampling(1); });
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.WAVEFOLDER); s.setDrive(15.0); s.setCharacter(-0.3); s.setMix(0.5); });
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.BITCRUSHER); s.setDrive(20.0); });
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.DOWNSAMPLE); s.setDrive(30.0); s.setOutputMode(Saturation.OutputMode.DELTA); });
        v.add(s -> { s.setAlgorithm(Saturation.Algorithm.CURVE); s.setCurveBase(Saturation.CurveBase.VALVE); s.setCurveShape(0.1, 2.0, 0.2); s.setDrive(10.0); });
        v.add(s -> { s.setOversamplingMode(Saturation.OversamplingMode.MINIMUM_PHASE); s.setMix(0.5); s.setDrive(12.0);
                     s.setAsymmetry(0.5); s.setLevelTracking(0.5); s.setTone(1, 800.0, 3.0); s.setSlewSensitivity(0.4); });
        v.add(s -> { s.setDrive(12.0); s.setCombLoop(0.6, 180.0); s.setCombLoopClosing(true); s.setProgramNoise(-50.0); s.setAdaptiveBlend(true); });
        return v;
    }

    @Test
    void blockSizesGiveTheSameOutput()
    {
        final double fs = 48000.0;
        final double[][] x = programme(fs, 0.5);
        int index = 0;
        for (Consumer<Saturation> setup : variety())
        {
            final double[][] ref = renderStereo(prepared(setup, fs, 2, 4096), x, 0, x[0].length, 4096);
            for (int block : new int[] { 1, 37, 512 })
            {
                final double[][] y = renderStereo(prepared(setup, fs, 2, 4096), x, 0, x[0].length, block);
                for (int c = 0; c < 2; ++c)
                {
                    for (double v : y[c]) assertTrue(Double.isFinite(v));
                    assertArrayEquals(ref[c], y[c], 0.0, "configuration " + index + ", block " + block);
                }
            }
            ++index;
        }
    }

    @Test
    void resetEqualsAFreshInstance()
    {
        // The Bitcrusher's dither generator, the programme noise and the drift
        // generators run on across reset() (as in the C++): left out here.
        final double fs = 44100.0;
        final double[][] x = programme(fs, 0.5);
        int index = 0;
        for (Consumer<Saturation> setup : variety())
        {
            if (index == 6 || index == 9 || index == 13) { ++index; continue; }
            final Saturation used = prepared(setup, fs, 2, 512);
            renderStereo(used, programme(fs, 0.3), 0, (int) (0.3 * fs), 512);
            used.reset();
            final double[][] y = renderStereo(used, x, 0, x[0].length, 512);
            final double[][] ref = renderStereo(prepared(setup, fs, 2, 512), x, 0, x[0].length, 512);
            for (int c = 0; c < 2; ++c) assertArrayEquals(ref[c], y[c], 0.0, "configuration " + index);
            ++index;
        }
    }

    /** The centre of a 1e-4 impulse's response around the reported latency, and that latency. */
    private static double[] impulseCentre(int factor, Saturation.Antialiasing order)
    {
        final Saturation s = new Saturation();
        s.setOversampling(factor);
        s.setAntialiasing(order);
        s.setDcBlocking(false);
        s.setPreFilterHpFrequency(10.0);
        s.prepare(48000.0, 1, 512);
        final double[] x = new double[4096];
        x[1000] = 1e-4;
        final double[] y = SaturationSignals.render(s, x, 512);
        final int l = s.getLatency();
        double moment = 0.0, mass = 0.0;
        for (int i = 1000 + l - 6; i <= 1000 + l + 6; ++i)
        {
            moment += i * y[i];
            mass += y[i];
        }
        return new double[] { moment / mass - 1000.0, l };
    }

    @Test
    void reportedLatencyIsTheMeasuredDelay()
    {
        // The oversamplers and the kernels are symmetric about their delay; the
        // pre high-pass (at the base rate, before them) adds the same small
        // offset everywhere, which the plain 1x response measures.
        final double offset = impulseCentre(1, Saturation.Antialiasing.OFF)[0];
        for (int factor : new int[] { 1, 2, 4, 8, 16 })
            for (Saturation.Antialiasing order : Saturation.Antialiasing.values())
            {
                if (order == Saturation.Antialiasing.FIRST_ORDER) continue;   // half a sample, uncompensated by design
                final double[] c = impulseCentre(factor, order);
                assertEquals(c[1], c[0] - offset, 0.02, factor + "x " + order);
            }
    }

    @Test
    void zeroMixIsTheInputLateByTheLatency()
    {
        final double fs = 48000.0;
        final double[][] x = programme(fs, 0.25);
        for (Saturation.Algorithm algo : Saturation.Algorithm.values())
            for (int factor : new int[] { 1, 2, 4 })
                for (Saturation.Antialiasing order : new Saturation.Antialiasing[] { Saturation.Antialiasing.OFF, Saturation.Antialiasing.SECOND_ORDER })
                {
                    final Saturation s = prepared(t -> {
                        t.setAlgorithm(algo);
                        t.setOversampling(factor);
                        t.setAntialiasing(order);
                        t.setDrive(12.0);
                        t.setMix(0.0);
                    }, fs, 2, 512);
                    // Second order holds the first sample in its base-rate delay (no step
                    // from zero history); the oversampler's own delay starts from silence.
                    final int l = s.getLatency();
                    final int held = order == Saturation.Antialiasing.SECOND_ORDER ? (factor == 2 ? 2 : 1) : 0;
                    final double[][] y = renderStereo(s, x, 0, x[0].length, 512);
                    for (int c = 0; c < 2; ++c)
                        for (int i = 0; i < y[c].length; ++i)
                            assertEquals(i >= l - held ? x[c][Math.max(0, i - l)] : 0.0, y[c][i], 0.0,
                                         algo + " " + factor + "x " + order + " at " + i);
                }
    }

    @Test
    void extremeSettingsStayFinite()
    {
        final double fs = 44100.0;
        final double[][] x = programme(fs, 0.3);
        for (double[] ch : x) for (int i = 0; i < ch.length; ++i) ch[i] *= 4.0;   // +12 dB over full scale
        for (Saturation.Algorithm algo : Saturation.Algorithm.values())
            for (Saturation.Antialiasing order : Saturation.Antialiasing.values())
            {
                final Saturation s = prepared(t -> {
                    t.setAlgorithm(algo);
                    t.setAntialiasing(order);
                    t.setDrive(48.0);
                    t.setCharacter(1.0);
                    t.setSlewSensitivity(1.0);
                    t.setAnalogDrift(1.0);
                    t.setAsymmetry(-1.0);
                    t.setCombLoop(0.95, 20.0);
                    t.setLevelTracking(-1.0);
                    t.setDriveCompensation(true);
                }, fs, 2, 256);
                final double[][] y = renderStereo(s, x, 0, x[0].length, 256);
                for (double[] ch : y) for (double v : ch) assertTrue(Double.isFinite(v), algo + " " + order);
            }
    }
}
