package com.quickmaster.processing.clip;
import com.quickmaster.processing.AudioProcessor;

import com.dspark.effects.Saturation;

/**
 * Soft-Clip stage ("Soft-Clip") - analog-style saturation that brings the program
 * peak down by exactly the dialled amount.
 * <p>
 * The user dials a <b>peak reduction</b> in dB; because the whole signal is known
 * in advance, the soft-clip ceiling is solved (a binary search over the chosen
 * voicing's curve) so the song's maximum peak ends up exactly that much lower. The
 * body below the ceiling passes through untouched, only the peaks are warmed and
 * rounded - so the harmonics scale with the reduction and it is transparent at
 * 0&nbsp;dB. A subtle per-channel drift keeps the left/right harmonics a touch
 * different. Disabled by default; the downstream Peak Normalizer turns the reduced
 * peak into loudness.
 * <p>
 * The gain-reduction meter is driven by {@link #getGrAtPosition(long)}: the
 * reduction is precomputed on the <i>base-rate</i> program (a per-hop envelope), so
 * the meter shows the reduction the listener actually gets and reads identically
 * whether the chain runs at the base rate or oversampled (an oversampled chain only
 * differs by the inter-sample peaks, which the program-level reduction ignores).
 */
public final class SoftClipProcessor implements AudioProcessor
{
    public static final double DEFAULT_SAT_DB = 1.0;
    public static final double MIN_SAT_DB = 0.0;
    public static final double MAX_SAT_DB = 12.0;

    /** Gain-reduction envelope resolution, in base-rate frames (~21 ms at 48 kHz). */
    private static final int HOP = 1024;

    private final Saturation sat = new Saturation();
    private volatile boolean enabled = false;
    private volatile double satDb = DEFAULT_SAT_DB;
    private volatile Saturation.Algorithm algorithm = Saturation.Algorithm.TUBE;
    private volatile double cachedPeak = 0.0;
    private volatile float clampLevel = 1.0f;       // exact output peak (= peak - satDb)
    private volatile double solvedCeiling = 1.0;    // the ceiling recompute() settled on
    private volatile float[] hopPeak = new float[0];// input sample-peak per hop (base rate)
    private volatile float[] grEnv = new float[0];  // reduction per hop (dB, <= 0), for the meter

    public SoftClipProcessor()
    {
        sat.setAlgorithm(algorithm);
        sat.setDriveDb(0.0);
        sat.setStereoAmount(0.3);   // subtle, true-stereo harmonics
    }

    public double getSatDb() { return satDb; }

    public void setSatDb(double db)
    {
        satDb = clamp(db, MIN_SAT_DB, MAX_SAT_DB);
        recompute();
    }

    public Saturation.Algorithm getAlgorithm() { return algorithm; }

    public void setAlgorithm(Saturation.Algorithm a)
    {
        if (a == null) return;
        algorithm = a;
        sat.setAlgorithm(a);
        recompute();
    }

    /**
     * Gain reduction (dB, <= 0) applied to the program at base-rate position
     * {@code baseFrame}. Drives the meter; independent of the oversampling factor.
     */
    public double getGrAtPosition(long baseFrame)
    {
        if (!enabled || satDb <= 1e-6) return 0.0;
        float[] env = grEnv;
        if (env.length == 0) return 0.0;
        long h = baseFrame / HOP;
        if (h < 0) h = 0; else if (h >= env.length) h = env.length - 1;
        return env[(int) h];
    }

    /** The post-upstream peak this stage last analysed (linear, 0..1). */
    public double getCachedPeak() { return cachedPeak; }

    /** Adopts an analysed peak (from a background re-render) and re-solves the ceiling. */
    public void setAnalyzedPeak(double peak)
    {
        cachedPeak = peak;
        recompute();
    }

    /**
     * Adopts the full analysis (program peak AND the per-hop envelope) from a
     * background re-render copy, so the meter envelope stays consistent with the
     * peak the ceiling was solved for.
     */
    public void adoptAnalysis(SoftClipProcessor other)
    {
        cachedPeak = other.cachedPeak;
        hopPeak = other.hopPeak;
        recompute();
    }

    /** Solves the ceiling so the soft-clip brings the cached peak down by {@code satDb}. */
    private void recompute()
    {
        if (satDb <= 1e-6 || cachedPeak <= 1e-6)
        {
            solvedCeiling = Math.max(1.0, cachedPeak) * 64.0;
            sat.setCeiling(solvedCeiling);   // no clipping (transparent)
            clampLevel = Float.MAX_VALUE;
            buildGrEnv();
            return;
        }
        double target = cachedPeak * Math.pow(10.0, -satDb / 20.0);   // peak - satDb
        double lo = target, hi = cachedPeak * 2.0;                    // output peak grows with the ceiling
        for (int it = 0; it < 44; it++)
        {
            double mid = 0.5 * (lo + hi);
            if (sat.probeOutputPeak(cachedPeak, mid) > target) hi = mid; else lo = mid;
        }
        solvedCeiling = 0.5 * (lo + hi);
        sat.setCeiling(solvedCeiling);
        clampLevel = (float) target;   // safety clamp catches any residual-aliasing overshoot
        buildGrEnv();
    }

    /** Builds the per-hop gain-reduction envelope (the reduction at each hop's base-rate peak). */
    private void buildGrEnv()
    {
        float[] hp = hopPeak;
        float[] env = new float[hp.length];
        if (satDb > 1e-6 && cachedPeak > 1e-6)
        {
            for (int h = 0; h < hp.length; h++)
            {
                double e = hp[h];
                if (e > 1e-6)
                {
                    double out = sat.probeOutputPeak(e, solvedCeiling);
                    env[h] = (float) Math.max(Math.min(20.0 * Math.log10(out / e), 0.0), -satDb);
                }
            }
        }
        grEnv = env;
    }

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        sat.prepare(sampleRate, 2);
        sat.setAlgorithm(algorithm);
        recompute();
    }

    @Override
    public boolean usesAnalysis() { return true; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        int frames = (channels > 0) ? samples.length / channels : 0;
        float peak = 0.0f;
        for (float s : samples) { float a = (s >= 0.0f) ? s : -s; if (a > peak) peak = a; }
        cachedPeak = peak;

        int hops = (frames + HOP - 1) / HOP;
        float[] hp = new float[Math.max(1, hops)];
        for (int h = 0; h < hops; h++)
        {
            int start = h * HOP, end = Math.min(start + HOP, frames);
            float mx = 0.0f;
            for (int f = start; f < end; f++)
                for (int c = 0; c < channels; c++)
                {
                    float a = Math.abs(samples[f * channels + c]);
                    if (a > mx) mx = a;
                }
            hp[h] = mx;
        }
        hopPeak = hp;
        recompute();
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (!enabled || satDb <= 1e-6) return buffer;   // 0 dB = no saturation, no peak change
        sat.process(buffer, channels);
        float lim = clampLevel;
        if (lim < Float.MAX_VALUE)
            for (int i = 0; i < buffer.length; i++)
            {
                if (buffer[i] > lim) buffer[i] = lim;
                else if (buffer[i] < -lim) buffer[i] = -lim;
            }
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    private static double clamp(double v, double lo, double hi)
    {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
