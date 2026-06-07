package com.quickmaster.processing.dynamics;

import com.dspark.core.DspMath;

/**
 * <b>Leveler</b> - automatic loudness rider that evens out the song's sections.
 * <p>
 * Tooltip: <i>"Reduces the volume difference between the sections of the song so
 * the master is consistent from start to finish."</i>
 * <p>
 * It measures the K-weighted loudness along the track and keeps only changes
 * that last at least {@value #MIN_SECTION_SEC} seconds (a median filter removes
 * shorter events - drum breaks, single hits - so they are never treated as
 * sections). The resulting section level is pushed toward the track's
 * <b>median loudness</b>: louder sections come down, quieter ones come up, and a
 * section already at the median is left alone. The gain rides between sections
 * <b>asymmetrically</b>: it anticipates a rise (lifts a quiet part before it
 * begins) but only falls once a louder part actually starts (a quiet passage is
 * never dipped early).
 * <p>
 * Controls: <b>Leveling</b> ({@code 0}&nbsp;= off, {@code 1}&nbsp;= every section
 * at the median) and <b>Speed</b> ({@code 0}&nbsp;= slow/gentle,
 * {@code 1}&nbsp;= fast/agile - how quickly the gain rides between sections).
 */
public final class LevelerProcessor extends AnalysisDynamicsProcessor
{
    /** Default and bounds for the leveling amount (0 = off, 1 = full). */
    public static final double DEFAULT_LEVELING = 0.5;
    public static final double MIN_LEVELING = 0.0;
    public static final double MAX_LEVELING = 1.0;
    /** Default and bounds for the speed (0 = slow, 1 = fast). */
    public static final double DEFAULT_SPEED = 0.5;
    public static final double MIN_SPEED = 0.0;
    public static final double MAX_SPEED = 1.0;

    /** Shortest level change treated as a section (shorter events are ignored). */
    public static final double MIN_SECTION_SEC = 3.0;
    /** Loudness is measured per this many frames. */
    private static final int HOP_FRAMES = 2048;
    /** Gain ride time at the slow / fast ends of the Speed control, in seconds. */
    private static final double SLOW_RESPONSE_SEC = 2.0;
    private static final double FAST_RESPONSE_SEC = 0.3;
    /** Bounds on the correction (down more than up, to avoid lifting noise too far). */
    private static final double MAX_CUT_DB = 12.0;
    private static final double MAX_BOOST_DB = 9.0;
    private static final double SILENCE_FLOOR_LUFS = -55.0;
    private static final double LUFS_OFFSET = -0.691;          // BS.1770-4

    private volatile double leveling = DEFAULT_LEVELING;
    private volatile double speed = DEFAULT_SPEED;

    // Per-hop section loudness (median-filtered), the hop size, and the track median.
    private float[] sectionLoud = new float[0];
    private int hopFrames = HOP_FRAMES;
    private int totalFrames = 0;
    private double medianLufs = SILENCE_FLOOR_LUFS;

    public double getLeveling() { return leveling; }

    /** Sets the leveling amount in {@code [0,1]}. */
    public void setLeveling(double v)
    {
        this.leveling = DspMath.clamp(v, MIN_LEVELING, MAX_LEVELING);
    }

    public double getSpeed() { return speed; }

    /** Sets the speed in {@code [0,1]} (0 = slow, 1 = fast). */
    public void setSpeed(double v)
    {
        this.speed = DspMath.clamp(v, MIN_SPEED, MAX_SPEED);
    }

    /** The track's median section loudness (the level sections are pulled toward). */
    public double getMedianLufs() { return medianLufs; }

    private double responseSec()
    {
        return SLOW_RESPONSE_SEC + speed * (FAST_RESPONSE_SEC - SLOW_RESPONSE_SEC);
    }

    @Override
    protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames)
    {
        this.totalFrames = frames;
        this.hopFrames = HOP_FRAMES;

        double[] kc = kWeightingCoeffs(sampleRate);
        double pB0 = kc[0], pB1 = kc[1], pB2 = kc[2], pA1 = kc[3], pA2 = kc[4];
        double rB0 = kc[5], rB1 = kc[6], rB2 = kc[7], rA1 = kc[8], rA2 = kc[9];
        double z1a = 0, z2a = 0, z1b = 0, z2b = 0;

        int numHops = Math.max(1, (frames + HOP_FRAMES - 1) / HOP_FRAMES);
        double[] hopSumSq = new double[numHops];
        long[] hopCount = new long[numHops];
        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            double mono = 0.0;
            for (int c = 0; c < channels; c++) mono += samples[base + c];
            mono /= channels;

            double v = pB0 * mono + z1a;
            z1a = pB1 * mono - pA1 * v + z2a;
            z2a = pB2 * mono - pA2 * v;
            double w = rB0 * v + z1b;
            z1b = rB1 * v - rA1 * w + z2b;
            z2b = rB2 * v - rA2 * w;

            int h = f / HOP_FRAMES;
            hopSumSq[h] += w * w;
            hopCount[h]++;
        }
        float[] hopLoud = new float[numHops];
        for (int h = 0; h < numHops; h++)
        {
            double ms = (hopCount[h] > 0) ? hopSumSq[h] / hopCount[h] : 0.0;
            hopLoud[h] = (float) (LUFS_OFFSET + 10.0 * Math.log10(Math.max(ms, 1e-12)));
        }

        // Median filter: keep only changes lasting >= MIN_SECTION_SEC; drop shorter
        // events (breaks, single hits) so they are never mistaken for a section.
        int half = Math.max(1, (int) (MIN_SECTION_SEC * sampleRate / HOP_FRAMES) / 2);
        float[] section = new float[numHops];
        int[] hist = new int[700];
        for (int h = 0; h < numHops; h++)
        {
            int lo = Math.max(0, h - half), hi = Math.min(numHops - 1, h + half);
            float m = median(hopLoud, lo, hi);
            section[h] = m;
            if (m > SILENCE_FLOOR_LUFS)
            {
                int bin = (int) ((m + 65.0) * 10.0);
                if (bin < 0) bin = 0; else if (bin >= hist.length) bin = hist.length - 1;
                hist[bin]++;
            }
        }
        this.sectionLoud = section;
        this.medianLufs = percentile(hist, 0.5);
    }

    @Override
    protected void mapFeaturesToGain()
    {
        int hops = sectionLoud.length;
        float[] env = new float[totalFrames];
        if (hops == 0 || totalFrames == 0 || leveling <= 0.0)
        {
            java.util.Arrays.fill(env, 1.0f);
            gainEnv = env;
            return;
        }
        double hopSec = (double) hopFrames / envRate;
        double resp = responseSec();

        // Target gain per hop, toward the median.
        float[] target = new float[hops];
        for (int h = 0; h < hops; h++)
        {
            double delta = DspMath.clamp(medianLufs - sectionLoud[h], -MAX_CUT_DB, MAX_BOOST_DB);
            target[h] = (float) DspMath.decibelsToGain(leveling * delta);
        }

        // Anticipate rises, hold through falls until the boundary, then a causal glide.
        int look = Math.max(1, (int) (resp * 0.5 / hopSec));
        float[] lead = forwardSlidingMax(target, look);
        double a = Math.exp(-hopSec / (resp * 0.4));
        float[] gain = new float[hops];
        double g = lead[0];
        for (int h = 0; h < hops; h++) { g = a * g + (1 - a) * lead[h]; gain[h] = (float) g; }

        // Upsample the per-hop gain to a per-frame envelope (linear interpolation).
        for (int f = 0; f < totalFrames; f++)
        {
            double hp = (double) f / hopFrames;
            int h0 = (int) hp;
            if (h0 >= hops - 1) { env[f] = gain[hops - 1]; continue; }
            double frac = hp - h0;
            env[f] = (float) (gain[h0] + frac * (gain[h0 + 1] - gain[h0]));
        }
        gainEnv = env;
    }

    private static float median(float[] a, int lo, int hi)
    {
        int n = hi - lo + 1;
        float[] tmp = new float[n];
        System.arraycopy(a, lo, tmp, 0, n);
        java.util.Arrays.sort(tmp);
        return tmp[n / 2];
    }

    /** {@code res[i] = max(a[i .. min(i+look, n-1)])}, O(n) via a monotonic deque. */
    private static float[] forwardSlidingMax(float[] a, int look)
    {
        int n = a.length;
        float[] res = new float[n];
        int[] dq = new int[n];
        int head = 0, tail = 0;
        for (int p = n - 1; p >= 0; p--)
        {
            while (tail > head && a[dq[tail - 1]] <= a[p]) tail--;
            dq[tail++] = p;
            if (dq[head] > p + look) head++;
            res[p] = a[dq[head]];
        }
        return res;
    }

    private static double percentile(int[] hist, double p)
    {
        long total = 0;
        for (int h : hist) total += h;
        if (total == 0) return SILENCE_FLOOR_LUFS;
        long target = (long) Math.ceil(p * total);
        long cum = 0;
        for (int b = 0; b < hist.length; b++)
        {
            cum += hist[b];
            if (cum >= target) return b / 10.0 - 65.0;
        }
        return 0.0;
    }

    /**
     * BS.1770-4 K-weighting biquad coefficients (high-shelf + RLB high-pass) as
     * {@code [pB0,pB1,pB2,pA1,pA2, rB0,rB1,rB2,rA1,rA2]}.
     */
    private static double[] kWeightingCoeffs(double sr)
    {
        double fc = 1681.97, g = 3.99984, q = 0.70710678;
        double a = Math.pow(10.0, g / 40.0);
        double w0 = 2.0 * Math.PI * fc / sr;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cosw0 = Math.cos(w0);
        double sqrtA = Math.sqrt(a);
        double a0 = (a + 1.0) - (a - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
        double pB0 = (a * ((a + 1.0) + (a - 1.0) * cosw0 + 2.0 * sqrtA * alpha)) / a0;
        double pB1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosw0)) / a0;
        double pB2 = (a * ((a + 1.0) + (a - 1.0) * cosw0 - 2.0 * sqrtA * alpha)) / a0;
        double pA1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosw0)) / a0;
        double pA2 = (((a + 1.0) - (a - 1.0) * cosw0 - 2.0 * sqrtA * alpha)) / a0;

        double fc2 = 38.13547, q2 = 0.500327;
        double w02 = 2.0 * Math.PI * fc2 / sr;
        double alpha2 = Math.sin(w02) / (2.0 * q2);
        double cosw02 = Math.cos(w02);
        double a02 = 1.0 + alpha2;
        double rB0 = ((1.0 + cosw02) / 2.0) / a02;
        double rB1 = (-(1.0 + cosw02)) / a02;
        double rB2 = ((1.0 + cosw02) / 2.0) / a02;
        double rA1 = (-2.0 * cosw02) / a02;
        double rA2 = (1.0 - alpha2) / a02;

        return new double[] { pB0, pB1, pB2, pA1, pA2, rB0, rB1, rB2, rA1, rA2 };
    }
}
