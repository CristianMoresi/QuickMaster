package com.dspark.effects;

import com.dspark.core.Biquad;
import com.dspark.core.BiquadCoeffs;

/**
 * Removes DC offset and subsonic content with a high-pass filter.
 * <p>
 * Defaults to a 5 Hz corner (transparent DC removal), but the cutoff is
 * configurable so the same module doubles as a gentle low-cut/rumble
 * filter (e.g. 20–40 Hz) on a mastering chain.
 */
public final class DcBlocker
{
    /** Maximum channels supported by the internal filter. */
    public static final int MAX_CHANNELS = 8;
    public static final double DEFAULT_CUTOFF_HZ = 5.0;

    private final Biquad biquad = new Biquad(MAX_CHANNELS);
    private double sampleRate = 48000.0;
    private double cutoffHz = DEFAULT_CUTOFF_HZ;
    private boolean enabled = true;

    /**
     * Prepares the filter for the given sample rate and resets state.
     * Non-finite or non-positive rates are ignored.
     */
    public void prepare(double sampleRate)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        updateCoeffs();
        biquad.reset();
    }

    /**
     * Sets the high-pass cutoff in Hz. Non-finite or non-positive values are
     * ignored (a NaN would poison every coefficient rebuild).
     */
    public void setCutoffHz(double cutoffHz)
    {
        if (!Double.isFinite(cutoffHz) || cutoffHz <= 0.0) return;
        this.cutoffHz = cutoffHz;
        updateCoeffs();
    }

    public double getCutoffHz() { return cutoffHz; }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Filters an interleaved buffer in place. No-op when disabled.
     *
     * @param buffer    interleaved samples
     * @param channels  number of channels (≤ {@value #MAX_CHANNELS})
     */
    public void process(float[] buffer, int channels)
    {
        if (!enabled) return;
        biquad.processBlock(buffer, channels);
    }

    private void updateCoeffs()
    {
        biquad.setCoeffs(BiquadCoeffs.highPass(sampleRate, cutoffHz, BiquadCoeffs.BUTTERWORTH_Q));
    }
}
