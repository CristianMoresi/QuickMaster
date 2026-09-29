package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.limit.OfflineLimiterEnvelope;

import com.dspark.core.DspMath;

/**
 * <b>Peak Comp</b> - look-ahead peak reducer.
 * <p>
 * Lowers the loudest peaks of the signal by a chosen amount. A
 * <b>Gain Reduction Target</b> of &minus;2&nbsp;dB places a ceiling 2&nbsp;dB
 * below the track's absolute peak and shaves every peak above it down to that
 * ceiling. The stereo-linked envelope also affects the body during its
 * lookahead/release, but leaves distant, below-threshold passages unchanged. The reduction is
 * limited to the headroom between the absolute peak and the loudest sustained
 * level, so it only shaves the peaks that rise above the body; that headroom is
 * the control's minimum.
 * <p>
 * The release is the duration of the longest transient in the track (floored at
 * {@value #MIN_RELEASE_MS}&nbsp;ms), so the gain recovers as soon as a peak has
 * passed. This controls transient peaks, not the macro-level of song sections.
 */
@SuppressWarnings("deprecation")
public final class PeakCompProcessor extends AnalysisDynamicsProcessor
{
    /** Its measured range configures the control before the user enables it. */
    @Override public boolean analyzeWhenBypassed() { return true; }
    /** Full, source-aligned, smooth attack span in ms. */
    public static final double LOOKAHEAD_MS = 2.0;
    /** Historical one-pole constant; the bounded envelope now uses the full lookahead span. */
    @Deprecated
    public static final double ATTACK_MS = 0.4;
    /** Release bounds in ms; the actual release is the longest transient, clamped here. */
    public static final double MIN_RELEASE_MS = 60.0;
    public static final double MAX_RELEASE_MS = 250.0;
    /** Default and bounds for the gain-reduction target (dB, &le; 0). */
    public static final double DEFAULT_TARGET_DB = -2.0;
    public static final double MIN_TARGET_DB = -18.0;
    public static final double MAX_TARGET_DB = 0.0;

    /** Slow envelope that tracks the body, so transients are excluded from it. */
    private static final double SUSTAIN_ATTACK_MS = 60.0;
    private static final double SUSTAIN_RELEASE_MS = 300.0;

    private volatile double targetDb = DEFAULT_TARGET_DB;
    private TrackAnalysis trackAnalysis;

    // Cached features (input-dependent): per-frame magnitude, the absolute peak,
    // the headroom to the loudest sustain, and the release from the longest transient.
    private float[] mag = new float[0];
    private double peakLin = 0.0;
    private double maxReductionDb = 0.0;
    private double releaseMs = MIN_RELEASE_MS;

    /** Injects the shared per-track analysis (transient durations). */
    public void setTrackAnalysis(TrackAnalysis ta) { this.trackAnalysis = ta; }

    public double getTargetDb() { return targetDb; }

    /** Sets the gain-reduction target in dB (&le; 0). */
    public void setTargetDb(double db)
    {
        if (Double.isFinite(db)) this.targetDb = DspMath.clamp(db, MIN_TARGET_DB, MAX_TARGET_DB);
    }

    @Override protected float minimumLegacyGain() {
        double gain = Math.pow(10, targetDb / 20);
        float rounded = (float) gain;
        return rounded < gain ? Math.nextUp(rounded) : rounded;
    }

    /** Headroom between the absolute peak and the loudest sustained level, in dB. */
    public double getMaxReductionDb() { return maxReductionDb; }

    /** Release time in ms (the longest transient in the track, floored). */
    public double getReleaseMs() { return releaseMs; }

    @Override
    protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames)
    {
        float[] m = new float[frames];
        double susAtk = coeff(SUSTAIN_ATTACK_MS, sampleRate);
        double susRel = coeff(SUSTAIN_RELEASE_MS, sampleRate);
        double sustain = 0.0, loudestSustain = 0.0;
        float peak = 0.0f;
        for (int f = 0; f < frames; f++)
        {
            if ((f & 16383) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            int base = f * channels;
            float a = 0.0f;
            for (int c = 0; c < channels; c++)
            {
                float v = Math.abs(samples[base + c]);
                if (v > a) a = v;
            }
            m[f] = a;
            if (a > peak) peak = a;
            sustain = (a > sustain) ? a + susAtk * (sustain - a) : a + susRel * (sustain - a);
            if (sustain > loudestSustain) loudestSustain = sustain;
        }
        this.mag = m;
        this.peakLin = peak;
        this.maxReductionDb = peak > 0 && loudestSustain > 0
                ? Math.max(0, 20 * Math.log10(peak / loudestSustain)) : 0;

        double longestTransientMs = 0.0;
        TrackAnalysis ta = trackAnalysis;
        if (ta != null && ta.getOnsetCount() > 0)
        {
            for (double d : ta.getOnsetDurationsSec())
                longestTransientMs = Math.max(longestTransientMs, d * 1000.0);
        }
        this.releaseMs = DspMath.clamp(longestTransientMs, MIN_RELEASE_MS, MAX_RELEASE_MS);
    }

    @Override
    protected void mapFeaturesToGain()
    {
        int n = mag.length;
        if (n == 0 || peakLin <= 0.0 || maxReductionDb < 1e-3)
        {
            float[] flat = new float[n];
            java.util.Arrays.fill(flat, 1.0f);
            gainEnv = flat;
            return;
        }
        double effTarget = Math.min(-targetDb, maxReductionDb);          // >= 0
        double ceiling = peakLin * DspMath.decibelsToGain(-effTarget);

        // The minimum-hold / double-box construction contains every source
        // peak, including a peak at frame zero. It has no causal warm-up leak
        // and no post-hoc hard clipping of audio.
        gainEnv = OfflineLimiterEnvelope.compute(mag, ceiling,
                Math.max(1, (int) Math.round(envRate * LOOKAHEAD_MS / 1000)),
                Math.max(1, (int) Math.round(envRate * releaseMs / 1000)));
    }

    private static double coeff(double ms, int sampleRate)
    {
        return Math.exp(-1.0 / (sampleRate * Math.max(ms, 0.01) / 1000.0));
    }
}
