package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * History independence of {@link TransformerModel} (stretches rendered from
 * rest join a continuous pass), the branch delta at programme level, and the
 * measurements its report gives: level against drive, cost and folded
 * harmonics.
 */
class TransformerModelContinuityTest
{
    private static final double TWO_PI = 2.0 * Math.PI;

    /**
     * A deterministic stereo song-like programme: a loud opening (bass notes
     * and kicks near full scale), then a moderate section with bass, kick,
     * chords and noise hats.
     */
    static double[][] songProgramme(int n, double fs)
    {
        return songProgramme(n, fs, 0.75);
    }

    /** The song programme with the section after the loud opening scaled by {@code restScale} (0.75: -16.5 dBFS RMS). */
    static double[][] songProgramme(int n, double fs, double restScale)
    {
        final double[][] x = new double[2][n];
        final double[] notes = { 55.0, 41.2, 49.0, 36.7, 61.7, 46.2 };
        int rng = 2024;
        double hat = 0.0;
        double hatLp = 0.0;
        for (int i = 0; i < n; i++)
        {
            final double t = i / fs;
            final int beat = (int) (t / 0.5);
            final double tb = t - beat * 0.5;
            final double f0 = notes[beat % notes.length];
            final double att = Math.min(1.0, tb / 0.01);
            double bass = 0.0;
            for (int h = 1; h <= 4; h++)
                bass += Math.sin(TWO_PI * f0 * h * t + 0.4 * h) / (h * h);
            bass *= 0.32 * att * Math.exp(-tb / 0.6);
            final double kickPhase = TWO_PI * (40.0 * tb + 25.0 * 0.03 * (1.0 - Math.exp(-tb / 0.03)));
            final double kick = 0.45 * Math.sin(kickPhase) * Math.exp(-tb / 0.09);
            final double chord = 0.05 * (Math.sin(TWO_PI * 220.0 * t) + Math.sin(TWO_PI * 277.2 * t + 1.0)
                    + Math.sin(TWO_PI * 329.6 * t + 2.0));
            rng = rng * 1664525 + 1013904223;
            final double w = (double) (rng >>> 8) / 8388608.0 - 1.0;
            hatLp += 0.3 * (w - hatLp);
            final double tq = t - (int) (t / 0.125) * 0.125;
            hat = 0.06 * (w - hatLp) * Math.exp(-tq / 0.02);
            final double scale = t < 2.0 ? 1.45 : restScale;
            x[0][i] = scale * (bass + kick + chord + hat);
            x[1][i] = scale * (0.9 * bass + kick - chord + 0.8 * hat + 0.03 * Math.sin(TWO_PI * 1500.0 * t));
        }
        return x;
    }

    private static TransformerModel quickMasterInstance(double fs, double drive, double size, double mix)
    {
        final TransformerModel tr = new TransformerModel();
        tr.setAutomaticOversampling(true);
        tr.setDrive(drive);
        tr.setCoreSize(size);
        tr.setMix(mix);
        tr.prepare(fs, 2, 4096);
        return tr;
    }

    private static double[][] slice(double[][] x, int from, int to)
    {
        final double[][] y = new double[x.length][];
        for (int c = 0; c < x.length; c++) y[c] = java.util.Arrays.copyOfRange(x[c], from, to);
        return y;
    }

    /** Worst |a - b| over channels and a range of b's samples, b starting at sample {@code shift} of a. */
    private static double worst(double[][] a, double[][] b, int shift, int from, int to)
    {
        double w = 0.0;
        for (int c = 0; c < a.length; c++)
            for (int i = from; i < to; i++)
                w = Math.max(w, Math.abs(a[c][i + shift] - b[c][i]));
        return w;
    }

    /**
     * Joins of stretches: per configuration {rate, size, drive, mix, rest
     * scale, warm-up in s}, the peak-relative worst |continuous - stretch| over
     * the first 0.25 s, over 1.25 s to 1.5 s, and over the second after the join.
     * The stretch starts from rest 2.5 s into the song, after its loud opening.
     */
    private static double[][] joins(List<double[]> configs)
    {
        final double[][] results = new double[configs.size()][];
        IntStream.range(0, configs.size()).parallel().forEach(k ->
        {
            final double[] cfg = configs.get(k);
            final double fs = cfg[0];
            final int start = (int) (2.5 * fs);
            final int n = start + (int) (cfg[5] * fs);   // the join
            final int end = n + (int) (1.0 * fs);
            final double[][] x = songProgramme(end, fs, cfg[4]);
            double peak = 0.0;
            for (double[] ch : x) for (double v : ch) peak = Math.max(peak, Math.abs(v));

            final TransformerModel whole = quickMasterInstance(fs, cfg[2], cfg[1], cfg[3]);
            final double[][] a = slice(x, 0, end);
            TransformerModelTest.run(whole, a, 4096);

            final TransformerModel stretch = quickMasterInstance(fs, cfg[2], cfg[1], cfg[3]);
            final double[][] b = slice(x, start, end);
            TransformerModelTest.run(stretch, b, 4096);

            final int q = (int) (0.25 * fs);
            results[k] = new double[] {
                worst(a, b, start, 0, q) / peak,
                worst(a, b, start, 5 * q, 6 * q) / peak,
                worst(a, b, start, n - start, end - start) / peak
            };
        });
        for (int k = 0; k < configs.size(); k++)
        {
            final double[] cfg = configs.get(k);
            final double[] r = results[k];
            System.out.printf("  %.1f kHz size %.1f drive %+5.1f mix %.1f programme %.1f dBFS RMS, warm-up %.1f s: "
                    + "|a-b|/peak first 0.25 s %.2e, at 1.25-1.5 s %.2e, after the join %.2e%n",
                    cfg[0] / 1000.0, cfg[1], cfg[2], cfg[3], cfg[4] == 0.75 ? -16.5 : -24.4, cfg[5], r[0], r[1], r[2]);
        }
        return results;
    }

    @Test
    @DisplayName("Stretches started from rest 1.5 s early join one continuous pass within 1e-6 of the peak")
    void stretchesJoinAContinuousPass()
    {
        final List<double[]> configs = new ArrayList<>();
        for (double fs : new double[] { 44100.0, 48000.0 })
            for (double size : new double[] { 0.0, 0.5, 1.0 })
                for (double drive : new double[] { 0.0, 12.0, 24.0 })
                    configs.add(new double[] { fs, size, drive, 1.0, 0.75, 1.5 });
        configs.add(new double[] { 48000.0, 0.5, 12.0, 0.5, 0.75, 1.5 });
        configs.add(new double[] { 44100.0, 0.0, 24.0, 0.3, 0.75, 1.5 });
        for (double size : new double[] { 0.0, 0.5, 1.0 })
            configs.add(new double[] { 48000.0, size, 12.0, 1.0, 0.30, 1.5 });

        final double[][] r = joins(configs);
        double worstAll = 0.0;
        for (int k = 0; k < configs.size(); k++)
        {
            final double[] cfg = configs.get(k);
            worstAll = Math.max(worstAll, r[k][2]);
            assertTrue(r[k][2] <= 1e-6, String.format("%.1f Hz size %.1f drive %.1f mix %.1f: %.3e",
                    cfg[0], cfg[1], cfg[2], cfg[3], r[k][2]));
        }
        System.out.printf("  worst after the join: %.3e of the peak%n", worstAll);
    }

    @Test
    @DisplayName("A lightly driven core forgets its history slower: the join needs a longer warm-up")
    void lightlyDrivenCoreNeedsALongerWarmUp()
    {
        // The irreversible magnetization forgets an initial state at a rate
        // proportional to the field swing: at -12 dB drive, or at 0 dB on a
        // quieter programme, 1.5 s of warm-up leaves more than 1e-6.
        final List<double[]> configs = new ArrayList<>();
        for (double size : new double[] { 0.0, 0.5, 1.0 })
            configs.add(new double[] { 48000.0, size, -12.0, 1.0, 0.75, 1.5 });
        for (double size : new double[] { 0.0, 0.5, 1.0 })
            configs.add(new double[] { 48000.0, size, 0.0, 1.0, 0.30, 1.5 });
        for (double size : new double[] { 0.0, 0.5, 1.0 })
            configs.add(new double[] { 48000.0, size, -12.0, 1.0, 0.75, 6.0 });
        for (double size : new double[] { 0.0, 0.5, 1.0 })
            configs.add(new double[] { 48000.0, size, 0.0, 1.0, 0.30, 4.0 });
        final double[][] r = joins(configs);
        for (int k = 0; k < 6; k++)
            assertTrue(r[k][2] < 1e-3, "short warm-up " + k + ": " + r[k][2]);
        for (int k = 6; k < configs.size(); k++)
            assertTrue(r[k][2] <= 1e-6, "long warm-up " + k + ": " + r[k][2]);
    }

    /** Coherent amplitude of a 100 Hz programme-level tone after a history, over the last 0.5 s of 1.5 s. */
    private static double toneAfter(double[] history, double size, double drive, double[] waveOut)
    {
        final double fs = 48000.0;
        final TransformerModel tr = new TransformerModel();
        tr.setDrive(drive);
        tr.setCoreSize(size);
        tr.prepare(fs, 1, 512);
        final int toneLen = (int) (1.5 * fs);
        final double[][] x = new double[1][history.length + toneLen];
        System.arraycopy(history, 0, x[0], 0, history.length);
        for (int i = 0; i < toneLen; i++)
            x[0][history.length + i] = 0.25 * Math.sin(TWO_PI * 100.0 * i / fs);
        TransformerModelTest.run(tr, x, 512);
        final int from = history.length + toneLen - 24000;
        System.arraycopy(x[0], from, waveOut, 0, 24000);
        return TransformerModelTest.toneAmplitude(x[0], from, 24000, 100.0, fs);
    }

    @Test
    @DisplayName("Branch delta at programme level: 0.000 dB whatever the history")
    void branchDeltaAtProgrammeLevel()
    {
        final double fs = 48000.0;
        // Histories that leave the core on different branches: a positive and
        // a negative full-scale 20 Hz half wave (remanence), and a loud 30 Hz
        // tone stopped at its peak; each followed by 0.1 s of silence.
        final int halfWave = (int) (fs / 40.0);
        final int gap = (int) (0.1 * fs);
        final double[] pos = new double[halfWave + gap];
        final double[] neg = new double[halfWave + gap];
        for (int i = 0; i < halfWave; i++)
        {
            pos[i] = 0.95 * Math.sin(Math.PI * i / halfWave);
            neg[i] = -pos[i];
        }
        final int burst = (int) (0.3 * fs) + (int) (fs / 120.0);
        final double[] loud = new double[burst + gap];
        for (int i = 0; i < burst; i++) loud[i] = 0.9 * Math.sin(TWO_PI * 30.0 * i / fs);

        double worstDb = 0.0;
        double worstWave = 0.0;
        final double[] w0 = new double[24000];
        final double[] w1 = new double[24000];
        for (double size : new double[] { 0.0, 0.5, 1.0 })
            for (double drive : new double[] { 0.0, 12.0, 24.0 })
            {
                final double rest = toneAfter(new double[0], size, drive, w0);
                for (double[] history : new double[][] { pos, neg, loud })
                {
                    final double after = toneAfter(history, size, drive, w1);
                    final double db = 20.0 * Math.log10(after / rest);
                    worstDb = Math.max(worstDb, Math.abs(db));
                    for (int i = 0; i < 24000; i++) worstWave = Math.max(worstWave, Math.abs(w0[i] - w1[i]));
                    assertTrue(Math.abs(db) < 0.0005, "size " + size + " drive " + drive + ": " + db + " dB");
                }
            }
        System.out.printf("  branch delta at programme level: worst %.2e dB, worst waveform difference %.2e%n", worstDb, worstWave);
    }

    /** Pink noise (Paul Kellet's filter over LCG white noise), scaled to an RMS level. */
    private static double[] pinkNoise(int n, double rmsDb, int seed)
    {
        final double[] x = new double[n];
        int rng = seed;
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        double acc = 0.0;
        for (int i = 0; i < n; i++)
        {
            rng = rng * 1664525 + 1013904223;
            final double w = (double) (rng >>> 8) / 8388608.0 - 1.0;
            b0 = 0.99886 * b0 + w * 0.0555179;
            b1 = 0.99332 * b1 + w * 0.0750759;
            b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856;
            b4 = 0.55000 * b4 + w * 0.5329522;
            b5 = -0.7616 * b5 - w * 0.0168980;
            x[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
            b6 = w * 0.115926;
            acc += x[i] * x[i];
        }
        final double g = Math.pow(10.0, rmsDb / 20.0) / Math.sqrt(acc / n);
        for (int i = 0; i < n; i++) x[i] *= g;
        return x;
    }

    /** Output over input RMS in dB, stereo, after 0.5 s, of a signal through a drive at 48 kHz. */
    private static double levelDb(double[] signal, double drive)
    {
        final TransformerModel tr = new TransformerModel();
        tr.setAutomaticOversampling(true);
        tr.setDrive(drive);
        tr.prepare(48000.0, 2, 512);
        final double[][] x = { signal.clone(), signal.clone() };
        TransformerModelTest.run(tr, x, 512);
        final int lat = tr.getLatency();
        double in = 0.0;
        double out = 0.0;
        for (int i = 24000; i < signal.length - lat; i++)
        {
            in += signal[i] * signal[i];
            out += x[0][i + lat] * x[0][i + lat];
        }
        return 10.0 * Math.log10(out / in);
    }

    @Test
    @DisplayName("Level against drive: 1 kHz at -12 dBFS, 100 Hz at -12 dBFS, pink noise at -18 dBFS RMS")
    void levelAgainstDrive()
    {
        final int n = 72000;
        final double[] sine1k = new double[n];
        final double[] sine100 = new double[n];
        final double amp = Math.pow(10.0, -12.0 / 20.0);
        for (int i = 0; i < n; i++)
        {
            sine1k[i] = amp * Math.sin(TWO_PI * 1000.0 * i / 48000.0);
            sine100[i] = amp * Math.sin(TWO_PI * 100.0 * i / 48000.0);
        }
        final double[] pink = pinkNoise(n, -18.0, 4321);
        for (double drive : new double[] { -12.0, 0.0, 12.0, 24.0 })
        {
            final double a = levelDb(sine1k, drive);
            final double b = levelDb(sine100, drive);
            final double c = levelDb(pink, drive);
            System.out.printf("  drive %+5.1f dB: 1 kHz -12 dBFS %+6.2f dB, 100 Hz -12 dBFS %+6.2f dB, pink -18 dBFS RMS %+6.2f dB%n",
                    drive, a, b, c);
            assertTrue(Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c));
            // Drive changes iron, not volume, at the calibration point.
            assertTrue(Math.abs(b) < 0.5, "100 Hz at -12 dBFS, drive " + drive + ": " + b + " dB");
        }
    }

    @Test
    @DisplayName("Cost of process() per stereo frame at 48 kHz (measured, not asserted)")
    void costPerStereoFrame()
    {
        for (int factor : new int[] { 2, 1, 4 })
        {
            final TransformerModel tr = new TransformerModel();
            if (factor == 2) tr.setAutomaticOversampling(true);
            else tr.setOversampling(factor);
            tr.prepare(48000.0, 2, 512);
            final double[][] x = songProgramme(48000 * 4, 48000.0);
            final double[][] work = new double[2][x[0].length];
            for (int pass = 0; pass < 4; pass++)
            {
                System.arraycopy(x[0], 0, work[0], 0, x[0].length);
                System.arraycopy(x[1], 0, work[1], 0, x[1].length);
                TransformerModelTest.run(tr, work, 512);
            }
            long best = Long.MAX_VALUE;
            for (int pass = 0; pass < 3; pass++)
            {
                System.arraycopy(x[0], 0, work[0], 0, x[0].length);
                System.arraycopy(x[1], 0, work[1], 0, x[1].length);
                final long t0 = System.nanoTime();
                TransformerModelTest.run(tr, work, 512);
                best = Math.min(best, System.nanoTime() - t0);
            }
            System.out.printf("  %dx%s: %.1f ns per stereo frame at 48 kHz (%.2f%% of real time)%n", factor,
                    factor == 2 ? " (default, automatic)" : "", (double) best / x[0].length,
                    100.0 * best * 1e-9 / (x[0].length / 48000.0));
        }
    }

    /**
     * Worst folded harmonic below 20 kHz (dB re the fundamental) of a tone
     * through one setting, over an analysis window of {@code len} samples
     * (the tone on one of its bins).
     */
    private static double worstFold(int factor, double drive, double size, double freq, double amp, int len)
    {
        final double fs = 48000.0;
        final TransformerModel tr = new TransformerModel();
        tr.setOversampling(factor);
        tr.setDrive(drive);
        tr.setCoreSize(size);
        tr.prepare(fs, 1, 512);
        final int warm = 12000;
        final double[][] x = new double[1][warm + len];
        for (int i = 0; i < x[0].length; i++) x[0][i] = amp * Math.sin(TWO_PI * freq * i / fs);
        TransformerModelTest.run(tr, x, 512);
        final double fund = goertzel(x[0], warm, len, freq, fs);
        double worst = -400.0;
        for (int k = 2; k <= 31; k++)
        {
            final double h = k * freq;
            if (h <= fs / 2) continue;
            double fold = h % fs;
            if (fold > fs / 2) fold = fs - fold;
            if (fold >= 20000.0 || fold < 20.0) continue;
            boolean onHarmonic = false;
            for (int j = 1; j * freq < fs / 2; j++)
                if (Math.abs(j * freq - fold) < 8.0) onHarmonic = true;
            if (onHarmonic) continue;
            worst = Math.max(worst, 20.0 * Math.log10(goertzel(x[0], warm, len, fold, fs) / fund));
        }
        return worst;
    }

    private static double goertzel(double[] x, int from, int n, double f, double fs)
    {
        final double w = TWO_PI * f / fs;
        final double c = 2.0 * Math.cos(w);
        double s1 = 0.0;
        double s2 = 0.0;
        for (int i = 0; i < n; i++)
        {
            final double s = x[from + i] + c * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return Math.sqrt(Math.max(s1 * s1 + s2 * s2 - c * s1 * s2, 0.0));
    }

    @Test
    @EnabledIfSystemProperty(named = "transformer.sweep", matches = "true")
    @DisplayName("Folded-harmonic sweep over 1-15 kHz tones (opt-in: -Dtransformer.sweep=true)")
    void foldedHarmonicSweep()
    {
        // The coarse grid holds the 10.1 kHz tone of the header (1 Hz bins);
        // the fine one steps by 100 Hz (4 Hz bins).
        final double[][] settings = { { 1, 0 }, { 1, 24 }, { 2, 0 }, { 2, 24 }, { 4, 0 }, { 4, 24 } };
        for (boolean fine : new boolean[] { false, true })
        {
            final List<Double> tones = new ArrayList<>();
            if (fine)
                for (int f = 1000; f <= 15000; f += 100) tones.add((double) (f + 4));
            else
                for (int f = 1000; f < 15000; f += 1000) tones.add((double) (f + 103));
            final int len = fine ? 12000 : 48000;
            for (double[] s : settings)
            {
                final double[] worst = { -400.0 };
                final double[] where = new double[3];
                IntStream.range(0, tones.size() * 6).parallel().forEach(k ->
                {
                    final double f = tones.get(k / 6);
                    final double amp = (k % 2 == 0) ? 0.5 : Math.pow(10.0, -18.0 / 20.0);
                    final double size = ((k / 2) % 3) * 0.5;
                    final double db = worstFold((int) s[0], s[1], size, f, amp, len);
                    synchronized (worst)
                    {
                        if (db > worst[0])
                        {
                            worst[0] = db;
                            where[0] = f;
                            where[1] = amp;
                            where[2] = size;
                        }
                    }
                });
                System.out.printf("  %s grid, %dx, drive %+3.0f dB: worst fold %.1f dB (tone %.0f Hz, %.0f dBFS, core %.1f)%n",
                        fine ? "100 Hz" : "1 kHz + 103 Hz", (int) s[0], s[1], worst[0], where[0],
                        20.0 * Math.log10(where[1]), where[2]);
            }
        }
    }
}
