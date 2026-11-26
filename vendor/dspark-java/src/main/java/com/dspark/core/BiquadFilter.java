package com.dspark.core;

import java.util.Arrays;

/**
 * Biquad filter in Transposed Direct Form II with per-channel state, in
 * double precision throughout (coefficients, history and recursion).
 * <p>
 * A biquad whose corner sits far below the sample rate parks its poles a hair
 * inside the unit circle, and the denominator at DC, {@code 1 + a1 + a2}, is a
 * cancellation of terms of size 2 that only double resolves; in float the
 * recursion stops being contractive and a high-pass turns into a DC source.
 * <p>
 * Coefficients reach the filter in two ways. {@link #setCoeffs} stages a set
 * that the processing calls adopt at their next gate (the next sample of
 * {@link #processSample} / {@link #processSampleCore}, or the start of the
 * next {@link #process} block). {@link #setCoeffsNow} makes a set active at
 * once, for an owner that modulates its own filter. A pending staged set
 * overwrites a direct set at the next gate, so one instance should have one
 * of the two as its master.
 */
public final class BiquadFilter
{
    /** Channels a filter built with the no-argument constructor holds. */
    public static final int DEFAULT_MAX_CHANNELS = 8;

    private final int maxChannels;

    private double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;   // the active set
    private BiquadCoeffs staged = BiquadCoeffs.IDENTITY;
    private boolean coeffsDirty = false;

    private final double[] z1;
    private final double[] z2;

    /** A filter for up to {@value #DEFAULT_MAX_CHANNELS} channels. */
    public BiquadFilter()
    {
        this(DEFAULT_MAX_CHANNELS);
    }

    /**
     * A filter for up to {@code maxChannels} independent channels.
     *
     * @param maxChannels largest number of channels (at least 1)
     */
    public BiquadFilter(int maxChannels)
    {
        if (maxChannels < 1)
            throw new IllegalArgumentException("maxChannels must be >= 1; got " + maxChannels);
        this.maxChannels = maxChannels;
        this.z1 = new double[maxChannels];
        this.z2 = new double[maxChannels];
    }

    /** Largest number of channels the filter holds. */
    public int getMaxChannels()
    {
        return maxChannels;
    }

    /**
     * Stages a coefficient set: the processing calls adopt it at their next
     * gate (see the class notes).
     */
    public void setCoeffs(BiquadCoeffs c)
    {
        staged = c;
        coeffsDirty = true;
    }

    /**
     * Makes a coefficient set the active one immediately, from the next
     * processed sample, with no staging. A pending {@link #setCoeffs} set
     * still replaces it at the next gate.
     */
    public void setCoeffsNow(BiquadCoeffs c)
    {
        b0 = c.b0;
        b1 = c.b1;
        b2 = c.b2;
        a1 = c.a1;
        a2 = c.a2;
    }

    /**
     * Promotes a pending staged set to active.
     *
     * @return true if the active coefficients changed in this call
     */
    public boolean applyPendingCoeffs()
    {
        if (!coeffsDirty) return false;
        coeffsDirty = false;
        setCoeffsNow(staged);
        return true;
    }

    /** The active coefficient set (a copy). */
    public BiquadCoeffs getCoeffs()
    {
        return new BiquadCoeffs(b0, b1, b2, a1, a2);
    }

    /** Clears every channel's state. Coefficients are kept. */
    public void reset()
    {
        Arrays.fill(z1, 0.0);
        Arrays.fill(z2, 0.0);
    }

    /**
     * Filters one sample of one channel, adopting a pending staged set first.
     *
     * @param input   input sample
     * @param channel channel index in [0, maxChannels)
     * @return the filtered sample
     */
    public double processSample(double input, int channel)
    {
        return processSampleCore(input, channel);
    }

    /**
     * One recursion step in the core precision: the same filter and state as
     * {@link #processSample}, for a cascade that keeps its intermediate signal
     * in double.
     *
     * @param input   input sample
     * @param channel channel index in [0, maxChannels)
     * @return the filtered sample
     */
    public double processSampleCore(double input, int channel)
    {
        if (coeffsDirty)
            applyPendingCoeffs();

        final double output = b0 * input + z1[channel];
        z1[channel] = b1 * input - a1 * output + z2[channel];
        z2[channel] = b2 * input - a2 * output;
        return output;
    }

    /** {@link #processRampCore(double[], int, int, int, BiquadCoeffs, BiquadCoeffs)} from index 0. */
    public void processRampCore(double[] data, int n, int channel, BiquadCoeffs c0, BiquadCoeffs step)
    {
        processRampCore(data, 0, n, channel, c0, step);
    }

    /**
     * Runs {@code n} samples of one channel while the coefficients move
     * linearly. Sample {@code k} (0-based) uses {@code c0 + (k + 1) step},
     * accumulated sample by sample. With a zero step this is
     * {@link #processSampleCore} on each sample with {@code c0} active. On
     * return the active set is the last coefficients used. Pending
     * {@link #setCoeffs} sets are not adopted here.
     *
     * @param data    samples, filtered in place
     * @param offset  index of the first sample in {@code data}
     * @param n       number of samples
     * @param channel channel index in [0, maxChannels)
     * @param c0      coefficients one step before the first sample
     * @param step    per-sample coefficient increment
     */
    public void processRampCore(double[] data, int offset, int n, int channel, BiquadCoeffs c0, BiquadCoeffs step)
    {
        if (n <= 0) return;
        double s1 = z1[channel], s2 = z2[channel];
        double cb0 = c0.b0, cb1 = c0.b1, cb2 = c0.b2, ca1 = c0.a1, ca2 = c0.a2;
        final double db0 = step.b0, db1 = step.b1, db2 = step.b2, da1 = step.a1, da2 = step.a2;
        for (int k = 0; k < n; ++k)
        {
            cb0 += db0;
            cb1 += db1;
            cb2 += db2;
            ca1 += da1;
            ca2 += da2;
            final double input = data[offset + k];
            final double output = cb0 * input + s1;
            s1 = cb1 * input - ca1 * output + s2;
            s2 = cb2 * input - ca2 * output;
            data[offset + k] = output;
        }
        z1[channel] = s1;
        z2[channel] = s2;
        b0 = cb0;
        b1 = cb1;
        b2 = cb2;
        a1 = ca1;
        a2 = ca2;
    }

    /** Filters every channel of a planar buffer in place, from index 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, channels.length, 0, numSamples);
    }

    /** Filters every channel of a planar buffer in place, from {@code offset}. */
    public void process(double[][] channels, int offset, int numSamples)
    {
        process(channels, channels.length, offset, numSamples);
    }

    /**
     * Filters a planar buffer in place. A pending staged set is adopted at
     * the start of the block and the block runs on that set. Channels beyond
     * the filter's maximum are left untouched. A state that a non-finite
     * sample has reached is cleared at the end of the block, so the channel
     * recovers on the next one.
     *
     * @param channels    the channels
     * @param numChannels channels to process (the first ones of {@code channels})
     * @param offset      index of the first sample in each channel
     * @param numSamples  samples to process
     */
    public void process(double[][] channels, int numChannels, int offset, int numSamples)
    {
        applyPendingCoeffs();

        final int nCh = Math.min(numChannels, maxChannels);
        final double cb0 = b0, cb1 = b1, cb2 = b2, ca1 = a1, ca2 = a2;
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] data = channels[ch];
            double s1 = z1[ch];
            double s2 = z2[ch];
            for (int i = 0; i < numSamples; ++i)
            {
                final double input = data[offset + i];
                final double output = cb0 * input + s1;
                s1 = cb1 * input - ca1 * output + s2;
                s2 = cb2 * input - ca2 * output;
                data[offset + i] = output;
            }
            if (!Double.isFinite(s1 + s2))
            {
                s1 = 0.0;
                s2 = 0.0;
            }
            z1[ch] = s1;
            z2[ch] = s2;
        }
    }
}
