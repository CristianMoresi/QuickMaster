package com.dspark.effects;

import com.dspark.core.DspMath;

/**
 * Peak normalizer.
 * <p>
 * Scans the audio once to find the maximum absolute sample, computes a
 * single gain factor that brings that peak to a configurable target in
 * dBFS, and applies that factor uniformly to every sample. The factor is
 * bidirectional: quiet material is amplified, loud material attenuated.
 * <p>
 * This is <i>normalization</i>, not loudness maximization: a single
 * static gain, no limiting or dynamics. For loudness-style processing,
 * follow it with a {@link Limiter}.
 * <p>
 * Two-phase use: call {@link #analyze(float[])} once (it computes the
 * gain), then {@link #process(float[])} per buffer. {@code gain} is
 * {@code volatile} so a re-analysis on another thread is visible to the
 * audio thread on its next buffer.
 */
public final class Normalizer
{
    public static final double MIN_TARGET_DBFS = -60.0;
    public static final double MAX_TARGET_DBFS = 0.0;
    public static final double DEFAULT_TARGET_DBFS = 0.0;

    private volatile double targetDbfs;
    private volatile boolean enabled = true;
    private volatile double gain = 1.0;

    public Normalizer()
    {
        this(DEFAULT_TARGET_DBFS);
    }

    public Normalizer(double targetDbfs)
    {
        setTargetDbfs(targetDbfs);
    }

    /** Sets the target peak in dBFS, in [{@value #MIN_TARGET_DBFS}, {@value #MAX_TARGET_DBFS}]. */
    public void setTargetDbfs(double targetDbfs)
    {
        // The explicit NaN check matters: a NaN fails both comparisons below
        // and would otherwise slip through and poison the gain.
        if (Double.isNaN(targetDbfs)
                || targetDbfs < MIN_TARGET_DBFS || targetDbfs > MAX_TARGET_DBFS)
        {
            throw new IllegalArgumentException(
                    "targetDbfs must be in [" + MIN_TARGET_DBFS + ", " + MAX_TARGET_DBFS
                            + "]; got " + targetDbfs);
        }
        this.targetDbfs = targetDbfs;
    }

    public double getTargetDbfs() { return targetDbfs; }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Linear gain factor computed by the last {@link #analyze(float[])}. */
    public double getGain() { return gain; }

    /** The applied gain in dB, or {@link Double#NEGATIVE_INFINITY} if zero. */
    public double getGainDb()
    {
        return (gain <= 0.0) ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(gain);
    }

    /**
     * Scans the whole audio and computes the gain needed to bring the
     * peak to the target. Digital silence yields unity gain.
     *
     * @param samples  interleaved samples (channel layout is irrelevant
     *                 — the peak is global)
     */
    public void analyze(float[] samples)
    {
        if (samples == null || samples.length == 0)
        {
            gain = 1.0;
            return;
        }
        float maxAbs = 0.0f;
        for (float s : samples)
        {
            float abs = (s >= 0.0f) ? s : -s;
            if (abs > maxAbs) maxAbs = abs;
        }
        gain = (maxAbs == 0.0f) ? 1.0 : DspMath.decibelsToGain(targetDbfs) / maxAbs;
    }

    /**
     * Applies the previously computed gain in place, clamping the result
     * to {@code [-1, +1]}. No-op when disabled.
     *
     * @param buffer  interleaved samples to scale
     */
    public void process(float[] buffer)
    {
        if (!enabled) return;
        float g = (float) gain;
        for (int i = 0; i < buffer.length; i++)
        {
            buffer[i] = DspMath.clampSample(buffer[i] * g);
        }
    }
}
