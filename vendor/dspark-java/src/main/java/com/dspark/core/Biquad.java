package com.dspark.core;

import java.util.Arrays;

/**
 * Biquad filter using Transposed Direct Form II (TDF-II) with
 * per-channel state.
 * <p>
 * Coefficients are held in an immutable {@link BiquadCoeffs} published
 * through a {@code volatile} reference: the UI thread can swap them via
 * {@link #setCoeffs(BiquadCoeffs)} while the audio thread is running,
 * and the audio thread always reads a complete, consistent set (no
 * torn reads). Internal state is kept in {@code double} to avoid the
 * precision loss TDF-II can suffer at low frequencies in {@code float}.
 */
public final class Biquad
{
    private volatile BiquadCoeffs coeffs = BiquadCoeffs.IDENTITY;

    private final double[] z1;
    private final double[] z2;

    /**
     * Creates a biquad able to filter up to {@code maxChannels}
     * independent channels (each with its own state).
     *
     * @param maxChannels  maximum number of channels (≥ 1)
     */
    public Biquad(int maxChannels)
    {
        if (maxChannels < 1)
        {
            throw new IllegalArgumentException("maxChannels must be >= 1; got " + maxChannels);
        }
        this.z1 = new double[maxChannels];
        this.z2 = new double[maxChannels];
    }

    /** Publishes a new coefficient set, effective on the next sample. */
    public void setCoeffs(BiquadCoeffs c)
    {
        this.coeffs = c;
    }

    /** Returns the coefficient set currently in use. */
    public BiquadCoeffs getCoeffs()
    {
        return coeffs;
    }

    /** Clears all per-channel state to avoid ringing or clicks. */
    public void reset()
    {
        Arrays.fill(z1, 0.0);
        Arrays.fill(z2, 0.0);
    }

    /**
     * Filters one sample on the given channel.
     *
     * @param input    input sample
     * @param channel  channel index (0-based)
     * @return the filtered sample
     */
    public float processSample(float input, int channel)
    {
        BiquadCoeffs c = coeffs;     // single volatile read
        double x = input;
        double y = c.b0 * x + z1[channel];
        z1[channel] = c.b1 * x - c.a1 * y + z2[channel];
        z2[channel] = c.b2 * x - c.a2 * y;
        return (float) y;
    }

    /**
     * Filters an interleaved buffer in place.
     *
     * @param buffer    interleaved float samples
     * @param channels  number of channels (≤ the configured maximum)
     */
    public void processBlock(float[] buffer, int channels)
    {
        BiquadCoeffs c = coeffs;     // snapshot once per block
        for (int i = 0; i < buffer.length; i += channels)
        {
            for (int ch = 0; ch < channels; ch++)
            {
                int idx = i + ch;
                double x = buffer[idx];
                double y = c.b0 * x + z1[ch];
                z1[ch] = c.b1 * x - c.a1 * y + z2[ch];
                z2[ch] = c.b2 * x - c.a2 * y;
                buffer[idx] = (float) y;
            }
        }
    }
}
