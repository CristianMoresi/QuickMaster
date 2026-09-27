package com.dspark.analysis;

import com.dspark.core.DspMath;

/**
 * Per-channel peak and RMS envelope follower for real-time metering.
 * <p>
 * Peak tracking uses asymmetric attack/release one-pole smoothing; RMS
 * uses a symmetric one-pole integration window. Level readouts are taken
 * from a UI thread while audio is fed on another thread (single writer,
 * plain reads — adequate for a meter).
 */
public final class LevelFollower
{
    public static final int MAX_CHANNELS = 8;
    private static final double ANTI_DENORMAL = 1e-15;

    private double sampleRate = 44100.0;
    private int channels = 2;

    private double attackMs = 1.0;
    private double releaseMs = 100.0;
    private double rmsWindowMs = 300.0;

    private double attackCoeff = 0.0;
    private double releaseCoeff = 0.0;
    private double rmsCoeff = 0.0;

    private final double[] peak = new double[MAX_CHANNELS];
    private final double[] rmsAccum = new double[MAX_CHANNELS];

    /**
     * Prepares the follower for the given sample rate and channel count.
     * A non-finite or non-positive sample rate is ignored (conservative
     * no-op keeping the previous state).
     */
    public void prepare(double sampleRate, int channels)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(channels, MAX_CHANNELS));
        updateCoefficients();
        reset();
    }

    // Non-finite ballistics are ignored: +Inf drives the one-pole
    // coefficient to exp(-0) = 1 (a silently FROZEN meter) and a NaN
    // poisons the recursion.
    public void setAttackMs(double ms)
    {
        if (Double.isFinite(ms)) { attackMs = Math.max(0.001, ms); updateCoefficients(); }
    }

    public void setReleaseMs(double ms)
    {
        if (Double.isFinite(ms)) { releaseMs = Math.max(0.001, ms); updateCoefficients(); }
    }

    public void setRmsWindowMs(double ms)
    {
        if (Double.isFinite(ms)) { rmsWindowMs = Math.max(0.001, ms); updateCoefficients(); }
    }

    public double getAttackMs() { return attackMs; }
    public double getReleaseMs() { return releaseMs; }
    public double getRmsWindowMs() { return rmsWindowMs; }

    /** Clears all envelope state. */
    public void reset()
    {
        java.util.Arrays.fill(peak, 0.0);
        java.util.Arrays.fill(rmsAccum, 0.0);
    }

    /**
     * Updates the level envelopes from an interleaved buffer (read-only).
     *
     * @param buffer    interleaved samples
     * @param channels  number of channels
     */
    public void process(float[] buffer, int channels)
    {
        int ch = Math.min(channels, MAX_CHANNELS);
        for (int i = 0; i < buffer.length; i += channels)
        {
            for (int c = 0; c < ch; c++)
            {
                double x = buffer[i + c];
                double abs = Math.abs(x);
                double coeff = (abs > peak[c]) ? attackCoeff : releaseCoeff;
                peak[c] = abs + coeff * (peak[c] - abs) + ANTI_DENORMAL;

                double sq = x * x;
                rmsAccum[c] = sq + rmsCoeff * (rmsAccum[c] - sq) + ANTI_DENORMAL;
            }
        }

        // Signal-NaN recovery: one non-finite input sample would stick in
        // both recursions permanently (the meter would report NaN until
        // reset). Sanitize the published state once per block: the poisoned
        // channel restarts from zero and re-measures on the next block.
        for (int c = 0; c < ch; c++)
        {
            if (!Double.isFinite(peak[c])) peak[c] = 0.0;
            if (!Double.isFinite(rmsAccum[c])) rmsAccum[c] = 0.0;
        }
    }

    /** Current peak level (linear) for a channel. */
    public double getPeakLevel(int channel)
    {
        if (channel < 0 || channel >= MAX_CHANNELS) return 0.0;
        return peak[channel];
    }

    /** Current RMS level (linear) for a channel. */
    public double getRmsLevel(int channel)
    {
        if (channel < 0 || channel >= MAX_CHANNELS) return 0.0;
        return Math.sqrt(Math.max(rmsAccum[channel], 0.0));
    }

    /** Current peak level in dBFS for a channel. */
    public double getPeakLevelDb(int channel)
    {
        return DspMath.gainToDecibels(getPeakLevel(channel));
    }

    /** Current RMS level in dBFS for a channel. */
    public double getRmsLevelDb(int channel)
    {
        if (channel < 0 || channel >= MAX_CHANNELS) return -100.0;
        double sq = rmsAccum[channel];
        if (sq <= 1e-10) return -100.0;
        return 10.0 * Math.log10(sq);
    }

    private void updateCoefficients()
    {
        if (sampleRate <= 0.0) return;
        attackCoeff = Math.exp(-1.0 / (sampleRate * attackMs / 1000.0));
        releaseCoeff = Math.exp(-1.0 / (sampleRate * releaseMs / 1000.0));
        rmsCoeff = Math.exp(-1.0 / (sampleRate * rmsWindowMs / 1000.0));
    }
}
