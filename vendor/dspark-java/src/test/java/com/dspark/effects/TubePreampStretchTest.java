package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dspark.effects.TubeTestSupport.TWO_PI;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline use of {@link TubePreamp}: stretches rendered from rest join a
 * continuous pass, the cost of process(), and the level against the drive.
 */
class TubePreampStretchTest
{
    /** Pink noise at an RMS level in dBFS (the C++ suite's generator). */
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
            v[i] = p;
            sum += p * p;
        }
        final double scale = Math.pow(10.0, rmsDb / 20.0) / Math.sqrt(sum / n);
        for (int i = 0; i < n; ++i) v[i] *= scale;
        return v;
    }

    /** A music-like stereo programme: pink noise, a bass line and chords under a slow envelope. */
    static double[][] programme(int n, double rate)
    {
        final double[] noiseL = pink(n, -24.0, 11), noiseR = pink(n, -24.0, 29);
        final double[][] x = new double[2][n];
        for (int i = 0; i < n; ++i)
        {
            final double t = i / rate;
            final double env = 0.55 + 0.45 * Math.sin(TWO_PI * 0.7 * t);
            final double beat = Math.exp(-8.0 * ((t * 2.0) % 1.0));
            final double bass = 0.25 * beat * Math.sin(TWO_PI * 55.0 * t);
            final double chord = 0.08 * (Math.sin(TWO_PI * 261.6 * t) + Math.sin(TWO_PI * 329.6 * t)
                                         + Math.sin(TWO_PI * 392.0 * t + 0.3));
            final double hat = 0.05 * Math.exp(-40.0 * ((t * 4.0) % 1.0)) * Math.sin(TWO_PI * 9100.0 * t);
            x[0][i] = env * (bass + chord + hat) + noiseL[i];
            x[1][i] = env * (bass + 0.8 * chord - hat) + noiseR[i];
        }
        return x;
    }

    /** The QuickMaster configuration: reference tone, cabinet off, mix 1, automatic oversampling. */
    static TubePreamp quickMaster(int stageCount, double drive, double rate)
    {
        final TubePreamp amp = new TubePreamp();
        amp.setAutomaticOversampling(true);
        amp.setDrive(drive);
        amp.setStages(stageCount);
        amp.setTreble(0.5);
        amp.setBass(0.5);
        amp.setMiddle(0.5);
        amp.setCabinet(false);
        amp.setMix(1.0);
        amp.prepare(rate, 2, 4096);
        return amp;
    }

    static double[][] renderStretch(TubePreamp amp, double[][] x, int from, int to)
    {
        final double[][] y = new double[2][to - from];
        System.arraycopy(x[0], from, y[0], 0, to - from);
        System.arraycopy(x[1], from, y[1], 0, to - from);
        for (int pos = 0; pos < y[0].length; pos += 4096)
            amp.process(y, pos, Math.min(4096, y[0].length - pos));
        return y;
    }

    @Test
    @DisplayName("A stretch started from rest 1.5 s early joins one continuous pass within 1e-6 of the peak")
    void stretchesJoinAContinuousPass()
    {
        for (double rate : new double[] { 44100.0, 48000.0 })
            for (int stageCount : new int[] { 1, 2 })
            {
                final double drive = 7.3;
                final int total = (int) (3.6 * rate);
                final int join = (int) (2.0 * rate);
                final double[][] x = programme(total, rate);
                final double[][] whole = renderStretch(quickMaster(stageCount, drive, rate), x, 0, total);
                double peak = 0.0;
                for (double[] ch : whole)
                    for (double v : ch) peak = Math.max(peak, Math.abs(v));
                final StringBuilder line = new StringBuilder();
                for (double warmup : new double[] { 0.25, 0.5, 1.0, 1.5 })
                {
                    final int start = join - (int) Math.round(warmup * rate);
                    final double[][] part = renderStretch(quickMaster(stageCount, drive, rate), x, start, total);
                    double worst = 0.0;
                    for (int c = 0; c < 2; ++c)
                        for (int j = join - start; j < part[c].length; ++j)
                            worst = Math.max(worst, Math.abs(part[c][j] - whole[c][start + j]));
                    line.append(String.format("  %.2f s: %.2e", warmup, worst / peak));
                    if (warmup == 1.5)
                        assertTrue(worst <= 1e-6 * peak, rate + " Hz " + stageCount + " stage(s): " + worst / peak);
                }
                System.out.printf("stretch join %.0f Hz, %d stage(s), drive %.1f dB (error / peak):%s%n",
                                  rate, stageCount, drive, line);
            }
    }

    @Test
    @DisplayName("Cost of process() per stereo frame at 48 kHz")
    void measureCost()
    {
        final double rate = 48000.0;
        final int n = (int) (5.0 * rate);
        final double[][] x = programme(n, rate);
        final String[] names = { "defaults (1 stage, 0 dB, 2x)", "QuickMaster 1 stage +12 dB", "QuickMaster 2 stages +12 dB",
                                 "defaults at 4x" };
        for (int k = 0; k < names.length; ++k)
        {
            double best = Double.MAX_VALUE;
            for (int round = 0; round < 2; ++round)
            {
                final TubePreamp amp;
                if (k == 0) { amp = new TubePreamp(); amp.prepare(rate, 2, 4096); }
                else if (k == 3) { amp = new TubePreamp(); amp.setOversampling(4); amp.prepare(rate, 2, 4096); }
                else amp = quickMaster(k, 12.0, rate);
                final double[][] y = { x[0].clone(), x[1].clone() };
                // First 2 s warm the JIT; the rest is timed.
                final int warm = (int) (2.0 * rate);
                for (int pos = 0; pos < warm; pos += 4096) amp.process(y, pos, Math.min(4096, warm - pos));
                final long t0 = System.nanoTime();
                for (int pos = warm; pos < n; pos += 4096) amp.process(y, pos, Math.min(4096, n - pos));
                final long t1 = System.nanoTime();
                best = Math.min(best, (double) (t1 - t0) / (n - warm));
            }
            System.out.printf("cost, %s: %.0f ns per stereo frame (%.1f %% of real time)%n",
                              names[k], best, best * rate / 1e7);
        }
    }

    private static double levelDb(TubePreamp amp, double[] in, int settle)
    {
        final double[][] y = { in.clone(), in.clone() };
        for (int pos = 0; pos < in.length; pos += 4096) amp.process(y, pos, Math.min(4096, in.length - pos));
        final int latency = amp.getLatency();
        double si = 0.0, so = 0.0;
        for (int i = settle; i + latency < in.length; ++i)
        {
            si += in[i] * in[i];
            so += y[0][i + latency] * y[0][i + latency];
        }
        return 10.0 * Math.log10(so / si);
    }

    @Test
    @DisplayName("Level against drive: +0.25 dB/dB residual slope on programme material")
    void levelAgainstDrive()
    {
        final double rate = 48000.0;
        final int n = (int) (1.2 * rate), settle = (int) (0.4 * rate);
        final double[] sine = new double[n];
        for (int i = 0; i < n; ++i) sine[i] = Math.pow(10.0, -12.0 / 20.0) * Math.sin(TWO_PI * 1000.0 * i / rate);
        final double[] noise = pink(n, -18.0, 4242);
        final double[] drives = { -12.0, 0.0, 12.0, 24.0 };
        for (int stageCount : new int[] { 1, 2 })
        {
            final double[] sineDb = new double[drives.length], noiseDb = new double[drives.length];
            for (int k = 0; k < drives.length; ++k)
            {
                sineDb[k] = levelDb(quickMaster(stageCount, drives[k], rate), sine, settle);
                noiseDb[k] = levelDb(quickMaster(stageCount, drives[k], rate), noise, settle);
            }
            System.out.printf("level, %d stage(s), drive -12/0/+12/+24 dB: 1 kHz at -12 dBFS %+.2f/%+.2f/%+.2f/%+.2f dB,"
                              + " pink at -18 dBFS RMS %+.2f/%+.2f/%+.2f/%+.2f dB%n", stageCount,
                              sineDb[0], sineDb[1], sineDb[2], sineDb[3], noiseDb[0], noiseDb[1], noiseDb[2], noiseDb[3]);
            // Backing off is audible; pushing never collapses the level.
            assertTrue(noiseDb[0] < noiseDb[1] - 1.0);
            assertTrue(noiseDb[3] > noiseDb[1] - 2.0);
            assertTrue(sineDb[0] < sineDb[1] - 1.0);
        }
        // At the calibration point the programme passes at unity.
        assertEquals(0.0, levelDb(quickMaster(1, 0.0, rate), noise, settle), 1.5);
    }
}
