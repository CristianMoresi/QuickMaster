package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import com.dspark.core.DspMath;

/**
 * <b>Peak Comp</b> - look-ahead peak reducer.
 * <p>
 * Lowers the loudest peaks of the signal by a chosen amount. A
 * <b>Gain Reduction Target</b> of &minus;2&nbsp;dB places a ceiling 2&nbsp;dB
 * below the track's absolute peak and shaves every peak above it down to that
 * ceiling, so the new absolute peak is exactly 2&nbsp;dB lower; the body of the
 * sound, which lies below the ceiling, is left untouched. The reduction is
 * limited to the headroom between the absolute peak and the loudest sustained
 * level, so it only shaves the peaks that rise above the body; that headroom is
 * the control's minimum.
 * <p>
 * The release is the duration of the longest transient in the track (floored at
 * {@value #MIN_RELEASE_MS}&nbsp;ms), so the gain recovers as soon as a peak has
 * passed, without pumping the following audio.
 */
public final class PeakCompProcessor extends AnalysisDynamicsProcessor
{
    /** Fixed look-ahead and attack times in ms (near-instant, click-free). */
    public static final double LOOKAHEAD_MS = 2.0;
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
        this.targetDb = DspMath.clamp(db, MIN_TARGET_DB, MAX_TARGET_DB);
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
        double pDb = DspMath.gainToDecibels(Math.max(peak, 1e-6));
        double sDb = DspMath.gainToDecibels(Math.max(loudestSustain, 1e-6));
        this.maxReductionDb = Math.max(0.0, pDb - sDb);

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

        float[] target = new float[n];
        for (int i = 0; i < n; i++)
        {
            target[i] = (mag[i] > ceiling) ? (float) (ceiling / mag[i]) : 1.0f;
        }
        gainEnv = LookaheadGainSmoother.smooth(target, envRate, LOOKAHEAD_MS, ATTACK_MS, releaseMs);
    }

    private static double coeff(double ms, int sampleRate)
    {
        return Math.exp(-1.0 / (sampleRate * Math.max(ms, 0.01) / 1000.0));
    }
}
