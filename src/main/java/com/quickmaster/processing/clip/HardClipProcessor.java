package com.quickmaster.processing.clip;
import com.quickmaster.processing.AudioProcessor;

import com.dspark.effects.Clipper;

/**
 * Hard-Clip stage ("Clip") - a transparent mastering clipper that brings the
 * program peak down by exactly the dialled amount.
 * <p>
 * The user dials a <b>peak reduction</b> in dB; because the whole signal is known
 * in advance, the ceiling is set to <i>peak - reduction</i>, so the loudest peak
 * is clipped to exactly that and the song's maximum falls by precisely the dialled
 * amount. The body below the ceiling is untouched, and at 0&nbsp;dB nothing is
 * clipped (transparent). Two curves are offered: {@link Clipper.Mode#HARD}
 * (brick-wall) and {@link Clipper.Mode#SOFT} (soft knee). Disabled by default; the
 * downstream Peak Normalizer turns the reduced peak back into loudness.
 * <p>
 * The gain-reduction meter is driven by {@link #getGrAtPosition(long)}: the
 * reduction is precomputed on the <i>base-rate</i> program, so the meter reads the
 * reduction the listener actually gets, identically with or without oversampling.
 */
public final class HardClipProcessor implements AudioProcessor
{
    public static final double DEFAULT_CLIP_DB = 1.0;
    public static final double MIN_CLIP_DB = 0.0;
    public static final double MAX_CLIP_DB = 12.0;

    /** Gain-reduction envelope resolution, in base-rate frames (~21 ms at 48 kHz). */
    private static final int HOP = 1024;

    /** The two curves exposed to the user. */
    public enum Curve { HARD, SOFT }

    private final Clipper clipper = new Clipper();
    private volatile boolean enabled = false;
    private volatile double clipDb = DEFAULT_CLIP_DB;
    private volatile Curve curve = Curve.HARD;
    private volatile double cachedPeak = 0.0;
    private volatile double solvedCeiling = 1.0;     // the ceiling recompute() settled on
    private volatile float[] hopPeak = new float[0]; // input sample-peak per hop (base rate)
    private volatile float[] grEnv = new float[0];   // reduction per hop (dB, <= 0), for the meter

    public HardClipProcessor()
    {
        clipper.setMode(Clipper.Mode.HARD);
        clipper.setSlewLimitMs(0.02);   // a touch of slew to tame the hardest aliasing edges
    }

    public double getClipDb() { return clipDb; }

    public void setClipDb(double db)
    {
        clipDb = clamp(db, MIN_CLIP_DB, MAX_CLIP_DB);
        recompute();
    }

    public Curve getCurve() { return curve; }

    public void setCurve(Curve c)
    {
        if (c == null) return;
        curve = c;
        clipper.setMode(c == Curve.HARD ? Clipper.Mode.HARD : Clipper.Mode.SOFT);
        recompute();
    }

    /**
     * Gain reduction (dB, <= 0) applied to the program at base-rate position
     * {@code baseFrame}. Drives the meter; independent of the oversampling factor.
     */
    public double getGrAtPosition(long baseFrame)
    {
        if (!enabled || clipDb <= 1e-6) return 0.0;
        float[] env = grEnv;
        if (env.length == 0) return 0.0;
        long h = baseFrame / HOP;
        if (h < 0) h = 0; else if (h >= env.length) h = env.length - 1;
        return env[(int) h];
    }

    /** The post-upstream peak this stage last analysed (linear, 0..1). */
    public double getCachedPeak() { return cachedPeak; }

    /** Adopts an analysed peak (from a background re-render) and recomputes. */
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
    public void adoptAnalysis(HardClipProcessor other)
    {
        cachedPeak = other.cachedPeak;
        hopPeak = other.hopPeak;
        recompute();
    }

    /** Solves the ceiling so the loudest peak is clipped down by exactly {@code clipDb} (Hard or Soft). */
    private void recompute()
    {
        if (clipDb <= 1e-6 || cachedPeak <= 1e-6)
        {
            solvedCeiling = Math.pow(10.0, 6.0 / 20.0);
            clipper.setCeilingDb(6.0);          // no clipping (transparent)
            clipper.setInputGainDb(0.0);
            buildGrEnv();
            return;
        }
        double target = cachedPeak * Math.pow(10.0, -clipDb / 20.0);   // peak - clipDb
        double lo = target * 0.5, hi = cachedPeak * 2.0;               // output peak grows with the ceiling
        for (int it = 0; it < 44; it++)
        {
            double mid = 0.5 * (lo + hi);
            if (clipper.probeOutputPeak(cachedPeak, mid) > target) hi = mid; else lo = mid;
        }
        solvedCeiling = 0.5 * (lo + hi);
        clipper.setCeilingDb(20.0 * Math.log10(solvedCeiling));
        clipper.setInputGainDb(0.0);
        buildGrEnv();
    }

    /** Builds the per-hop gain-reduction envelope (the reduction at each hop's base-rate peak). */
    private void buildGrEnv()
    {
        float[] hp = hopPeak;
        float[] env = new float[hp.length];
        if (clipDb > 1e-6 && cachedPeak > 1e-6)
        {
            for (int h = 0; h < hp.length; h++)
            {
                double e = hp[h];
                if (e > 1e-6)
                {
                    double out = clipper.probeOutputPeak(e, solvedCeiling);
                    env[h] = (float) Math.max(Math.min(20.0 * Math.log10(out / e), 0.0), -clipDb);
                }
            }
        }
        grEnv = env;
    }

    @Override
    public void prepare(int sampleRate, int totalSamples)
    {
        clipper.prepare(sampleRate, 2);
        clipper.setMode(curve == Curve.HARD ? Clipper.Mode.HARD : Clipper.Mode.SOFT);
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
        if (!enabled || clipDb <= 1e-6) return buffer;   // 0 dB = no clipping, no peak change
        clipper.process(buffer, channels);
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
