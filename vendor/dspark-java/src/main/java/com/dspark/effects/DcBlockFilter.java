package com.dspark.effects;

import com.dspark.core.BiquadCoeffs;
import com.dspark.core.BiquadDesign;

/**
 * Removes DC offset with a configurable filter order (1 to 10).
 * <p>
 * Order 1 is a one-pole high-pass, {@code (1 - z^-1) / (1 - R z^-1)}. Orders
 * 2 to 10 are cascaded Butterworth high-pass biquads with predefined Q values
 * (floor(order / 2) stages, so an odd order behaves like the even order under
 * it). The whole core (coefficients, history and recursion) is double
 * precision: a DC blocker parks its poles a hair inside the unit circle, and
 * the denominator at DC is a cancellation only double resolves.
 * <p>
 * The DC zero is structural: the numerator of both topologies is applied as a
 * difference of the input history ({@code (1 - z^-1)} for the one-pole,
 * {@code (1 - z^-1)^2} for the biquads, the exact RBJ high-pass numerator), so
 * a constant input drives the recursion with an exact zero and steady DC
 * decays to true zero.
 * <p>
 * {@link #setOrder} and {@link #setCutoff} take effect at the next processing
 * call, which rebuilds the coefficients when either has changed. A
 * non-finite input sample enters the recursion as zero.
 */
public final class DcBlockFilter
{
    /** Most biquad stages (order 10). */
    public static final int MAX_BIQUAD_STAGES = 5;
    /** Most channels. */
    public static final int MAX_CHANNELS = 16;

    /** Butterworth Q of each stage, by stage count (float values, as designed). */
    private static final float[][] Q_TABLE = {
        {},                                                // index 0 (unused)
        { 0.7071f },                                       // order 2
        { 0.5412f, 1.3066f },                              // order 4
        { 0.5177f, 0.7071f, 1.9319f },                     // order 6
        { 0.5098f, 0.6013f, 0.8999f, 2.5628f },            // order 8
        { 0.5062f, 0.5612f, 0.7071f, 1.1013f, 3.1962f }    // order 10
    };

    /**
     * A fully decayed state (below this, after seconds of silence) is flushed
     * to zero at the end of a block, keeping the recursion out of denormal
     * territory. It sits about 600 dB below full scale and fires only when the
     * input history is negligible too.
     */
    private static final double DENORMAL_FLOOR = 1e-30;

    private double sampleRate = 48000.0;
    private int numChannels = 2;
    private double onePoleR = 0.0;

    private int order = 1;
    private double cutoffHz = 5.0;
    private double lastCutoff = -1.0;
    private int lastOrder = -1;

    // One-pole state per channel.
    private final double[] onePoleX1 = new double[MAX_CHANNELS];
    private final double[] onePoleY1 = new double[MAX_CHANNELS];

    // Second-order sections: b0 (1 - z^-1)^2 / (1 + a1 z^-1 + a2 z^-2), state per channel.
    private final double[] sectionB0 = new double[MAX_BIQUAD_STAGES];
    private final double[] sectionA1 = new double[MAX_BIQUAD_STAGES];
    private final double[] sectionA2 = new double[MAX_BIQUAD_STAGES];
    private final double[][] sx1 = new double[MAX_BIQUAD_STAGES][MAX_CHANNELS];
    private final double[][] sx2 = new double[MAX_BIQUAD_STAGES][MAX_CHANNELS];
    private final double[][] sy1 = new double[MAX_BIQUAD_STAGES][MAX_CHANNELS];
    private final double[][] sy2 = new double[MAX_BIQUAD_STAGES][MAX_CHANNELS];

    /** A filter at the documented defaults: order 1, 5 Hz at 48 kHz. */
    public DcBlockFilter()
    {
        forceUpdateCoefficients(0.0);
    }

    /** {@link #prepare(double, int, double)} for two channels, keeping the configured cutoff. */
    public void prepare(double sampleRate)
    {
        prepare(sampleRate, 2, -1.0);
    }

    /** {@link #prepare(double, int, double)} keeping the configured cutoff. */
    public void prepare(double sampleRate, int numChannels)
    {
        prepare(sampleRate, numChannels, -1.0);
    }

    /**
     * Prepares the filter: resets the states and computes the coefficients.
     *
     * @param sampleRate  sample rate in Hz; a rate that is not above 0 is ignored
     * @param numChannels channels the block path processes, clamped to [1, 16]
     * @param cutoffHz    cutoff in Hz (at least 1 Hz); a value that is not
     *                    above 0 keeps the cutoff configured now
     */
    public void prepare(double sampleRate, int numChannels, double cutoffHz)
    {
        if (!(sampleRate > 0.0)) return;
        this.sampleRate = sampleRate;
        this.numChannels = Math.max(1, Math.min(numChannels, MAX_CHANNELS));

        reset();

        if (cutoffHz > 0.0)
            setCutoff(cutoffHz);
        forceUpdateCoefficients(0.0);
    }

    /**
     * Sets the filter order: 1 is the one-pole (6 dB/oct); 2 to 10 use
     * floor(order / 2) Butterworth biquads, so odd orders behave like the
     * next lower even order. Changing the order during playback is not
     * crossfaded.
     *
     * @param order filter order, clamped to [1, 10]
     */
    public void setOrder(int order)
    {
        this.order = Math.max(1, Math.min(order, 10));
    }

    /** The current filter order. */
    public int getOrder()
    {
        return order;
    }

    /**
     * Sets the cutoff frequency, applied at the next processing call.
     *
     * @param hz cutoff in Hz, at least 1; a value that is not finite is ignored
     */
    public void setCutoff(double hz)
    {
        if (!Double.isFinite(hz)) return;
        cutoffHz = (hz < 1.0) ? 1.0 : hz;
    }

    /** The requested cutoff frequency in Hz. */
    public double getCutoff()
    {
        return cutoffHz;
    }

    /** Processes every channel of a planar buffer in place, from index 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, channels.length, 0, numSamples);
    }

    /** Processes every channel of a planar buffer in place, from {@code offset}. */
    public void process(double[][] channels, int offset, int numSamples)
    {
        process(channels, channels.length, offset, numSamples);
    }

    /**
     * Processes a planar buffer in place: the first min(numChannels, the
     * prepared channel count) channels.
     *
     * @param channels    the channels
     * @param numChannels channels in {@code channels} to consider
     * @param offset      index of the first sample in each channel
     * @param numSamples  samples to process
     */
    public void process(double[][] channels, int numChannels, int offset, int numSamples)
    {
        updateCoefficientsIfNeeded();

        // lastOrder is the order the live coefficients were designed for.
        final int currentOrder = lastOrder;
        final int nCh = Math.min(numChannels, this.numChannels);
        if (numSamples <= 0) return;

        for (int ch = 0; ch < nCh; ++ch)
        {
            if (currentOrder <= 1)
                runOnePole(channels[ch], offset, numSamples, ch);
            else
                runCascade(channels[ch], offset, numSamples, ch, Math.max(1, Math.min(currentOrder / 2, MAX_BIQUAD_STAGES)));
        }
    }

    /**
     * Processes a block of one channel in place.
     *
     * @param channel    channel index; an index outside [0, 16) leaves the data untouched
     * @param data       samples
     * @param offset     index of the first sample
     * @param numSamples number of samples
     */
    public void process(int channel, double[] data, int offset, int numSamples)
    {
        if (channel < 0 || channel >= MAX_CHANNELS) return;
        if (data == null || numSamples <= 0) return;
        updateCoefficientsIfNeeded();
        final int currentOrder = lastOrder;

        if (currentOrder <= 1)
            runOnePole(data, offset, numSamples, channel);
        else
            runCascade(data, offset, numSamples, channel, Math.max(1, Math.min(currentOrder / 2, MAX_BIQUAD_STAGES)));
    }

    /**
     * Processes one sample of one channel. The published parameters are
     * checked on every call. The same operations as the block path, except
     * that the block path flushes a fully decayed state (below 1e-30) to zero
     * at the end of each block.
     *
     * @param channel channel index; an index outside [0, 16) returns the input
     * @param input   input sample
     * @return the sample with its DC removed
     */
    public double processSample(int channel, double input)
    {
        if (channel < 0 || channel >= MAX_CHANNELS) return input;
        updateCoefficientsIfNeeded();
        final double x = Double.isFinite(input) ? input : 0.0;

        if (lastOrder <= 1)
        {
            final double y = (x - onePoleX1[channel]) + onePoleR * onePoleY1[channel];
            onePoleX1[channel] = x;
            onePoleY1[channel] = y;
            return y;
        }

        final int numStages = Math.max(1, Math.min(lastOrder / 2, MAX_BIQUAD_STAGES));
        double v = x;
        for (int s = 0; s < numStages; ++s)
        {
            final double[] x1 = sx1[s], x2 = sx2[s], y1 = sy1[s], y2 = sy2[s];
            final double d = (v - x1[channel]) - (x1[channel] - x2[channel]);
            final double y = sectionB0[s] * d - sectionA1[s] * y1[channel] - sectionA2[s] * y2[channel];
            x2[channel] = x1[channel];
            x1[channel] = v;
            y2[channel] = y1[channel];
            y1[channel] = y;
            v = y;
        }
        return v;
    }

    /** Clears every state to zero. */
    public void reset()
    {
        java.util.Arrays.fill(onePoleX1, 0.0);
        java.util.Arrays.fill(onePoleY1, 0.0);
        for (int s = 0; s < MAX_BIQUAD_STAGES; ++s)
        {
            java.util.Arrays.fill(sx1[s], 0.0);
            java.util.Arrays.fill(sx2[s], 0.0);
            java.util.Arrays.fill(sy1[s], 0.0);
            java.util.Arrays.fill(sy2[s], 0.0);
        }
    }

    private void updateCoefficientsIfNeeded()
    {
        // The order is part of the design, not just a stage count: each order
        // has its own row of Q values.
        final double cutoff = cutoffHz;
        final int o = order;
        if (cutoff != lastCutoff || o != lastOrder)
            forceUpdateCoefficients(cutoff);
    }

    private void forceUpdateCoefficients(double explicitCutoff)
    {
        final double cutoff = explicitCutoff > 0.0 ? explicitCutoff : cutoffHz;
        final double fc = cutoff;
        final int o = order;

        onePoleR = Math.exp(-Math.PI * 2.0 * fc / sampleRate);

        final int tableIdx = Math.max(1, Math.min(o / 2, 5));
        for (int s = 0; s < tableIdx; ++s)
        {
            // The RBJ high-pass numerator is b0 * (1, -2, 1) exactly, which is
            // what lets the DC zero be applied structurally in the loop.
            final BiquadCoeffs c = BiquadDesign.makeHighPass(sampleRate, fc, (double) Q_TABLE[tableIdx][s]);
            sectionB0[s] = c.b0;
            sectionA1[s] = c.a1;
            sectionA2[s] = c.a2;
        }

        lastCutoff = cutoff;
        lastOrder = o;
    }

    /** y[n] = (x[n] - x[n-1]) + R y[n-1]; the difference zeroes DC exactly. */
    private void runOnePole(double[] data, int offset, int numSamples, int channel)
    {
        final double r = onePoleR;
        double x1 = onePoleX1[channel], y1 = onePoleY1[channel];
        // A non-finite sample enters as 0: the recursion would otherwise latch it.
        for (int i = 0; i < numSamples; ++i)
        {
            final double in = data[offset + i];
            final double x = Double.isFinite(in) ? in : 0.0;
            final double y = (x - x1) + r * y1;
            x1 = x;
            y1 = y;
            data[offset + i] = y;
        }
        if (Math.abs(y1) + Math.abs(x1) < DENORMAL_FLOOR)
        {
            x1 = 0.0;
            y1 = 0.0;
        }
        onePoleX1[channel] = x1;
        onePoleY1[channel] = y1;
    }

    /** The biquad stages in direct form I, with the numerator applied as a second difference. */
    private void runCascade(double[] data, int offset, int numSamples, int channel, int numStages)
    {
        for (int i = 0; i < numSamples; ++i)
        {
            final double in = data[offset + i];
            double v = Double.isFinite(in) ? in : 0.0;
            for (int s = 0; s < numStages; ++s)
            {
                final double[] x1 = sx1[s], x2 = sx2[s], y1 = sy1[s], y2 = sy2[s];
                // (x - x1) - (x1 - x2) is exactly zero for a constant input, so
                // steady DC never reaches the recursion at all.
                final double d = (v - x1[channel]) - (x1[channel] - x2[channel]);
                final double y = sectionB0[s] * d - sectionA1[s] * y1[channel] - sectionA2[s] * y2[channel];
                x2[channel] = x1[channel];
                x1[channel] = v;
                y2[channel] = y1[channel];
                y1[channel] = y;
                v = y;
            }
            data[offset + i] = v;
        }

        for (int s = 0; s < numStages; ++s)
        {
            if (Math.abs(sy1[s][channel]) + Math.abs(sy2[s][channel])
                + Math.abs(sx1[s][channel]) + Math.abs(sx2[s][channel]) < DENORMAL_FLOOR)
            {
                sx1[s][channel] = 0.0;
                sx2[s][channel] = 0.0;
                sy1[s][channel] = 0.0;
                sy2[s][channel] = 0.0;
            }
        }
    }
}
