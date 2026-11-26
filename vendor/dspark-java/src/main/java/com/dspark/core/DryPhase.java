package com.dspark.core;

import java.util.Arrays;

/**
 * Gives the dry side of a mix the phase that a high-pass leaves in the wet
 * side, and none of its magnitude.
 * <p>
 * A first-order all-pass that leads: two one-pole high-passes at its corner
 * turn the phase exactly as much, and a second-order Butterworth high-pass
 * within a few degrees of it with the corner at {@link #BUTTERWORTH_SHARE} of
 * its own. A wet side behind such a filter and a dry side without it are a
 * quarter of a turn or more apart at the bottom, and their mix loses there
 * what neither side loses by itself.
 * <p>
 * It fades in and out over 20 ms ({@link #aim}). Faded out it returns its
 * input to the bit, and it goes on following it, so that it starts from where
 * the programme is. {@link #prepare} allocates; the rest does not.
 */
public final class DryPhase
{
    /** The corner for a second-order Butterworth high-pass, over that of the filter. */
    public static final double BUTTERWORTH_SHARE = 0.8;

    private double[][] state = new double[0][];   // per channel: the last input and the last output
    private double sampleRate = 48000.0;
    private double step = 1.0 / 960.0;
    private double corner = -1.0;
    private double pole = 0.0;
    private double amount = 0.0;
    private double target = 0.0;

    /**
     * Sizes the state (allocates) and forgets the corner.
     *
     * @param sampleRate  the rate it runs at
     * @param channels    the channels it keeps
     */
    public void prepare(double sampleRate, int channels)
    {
        this.sampleRate = sampleRate;
        final double fade = sampleRate * 0.02;
        step = 1.0 / ((1.0 < fade) ? fade : 1.0);
        state = new double[Math.max(channels, 0)][2];
        corner = -1.0;
    }

    /** Clears the memory (the amount stays). */
    public void reset()
    {
        for (double[] s : state) Arrays.fill(s, 0.0);
    }

    /**
     * Moves the corner (a tangent, when it changes).
     *
     * @param hz  the corner in hertz
     */
    public void setCorner(double hz)
    {
        if (hz == corner) return;
        corner = hz;
        final double t = Math.tan(Math.PI * DspMath.clamp(hz / sampleRate, 0.0, 0.45));
        pole = (1.0 - t) / (1.0 + t);
    }

    /**
     * Where the amount goes.
     *
     * @param on  whether the dry side is turned
     */
    public void aim(boolean on) { target = on ? 1.0 : 0.0; }

    /** Puts the amount where it goes, without the fade. */
    public void land() { amount = target; }

    /**
     * Moves the amount by a frame and returns it.
     *
     * @return the amount of this frame
     */
    public double advance()
    {
        if (amount != target)
        {
            if (amount < target)
            {
                final double up = amount + step;
                amount = (up < target) ? up : target;
            }
            else
            {
                final double down = amount - step;
                amount = (target < down) ? down : target;
            }
        }
        return amount;
    }

    /**
     * A sample of a channel, with the amount that {@link #advance} returned.
     *
     * @param channel  the channel (one that was not prepared passes)
     * @param x        the dry sample
     * @param amount   the amount of this frame
     * @return the turned sample
     */
    public double processSample(int channel, double x, double amount)
    {
        if (channel < 0 || channel >= state.length) return x;
        final double[] s = state[channel];
        final double in = x;
        double y = pole * (in + s[1]) - s[0];
        if (!Double.isFinite(y))
        {
            s[0] = 0.0;
            s[1] = 0.0;
            return x;
        }
        if (Math.abs(y) < 1e-30) y = 0.0;
        s[0] = in;
        s[1] = y;
        if (amount == 0.0) return x;
        return amount == 1.0 ? y : in + amount * (y - in);
    }

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] buffer, int numSamples)
    {
        process(buffer, 0, numSamples);
    }

    /**
     * A stretch of a buffer, in place, advancing the amount once per frame.
     *
     * @param buffer      the dry side
     * @param offset      first sample in every channel
     * @param numSamples  number of samples
     */
    public void process(double[][] buffer, int offset, int numSamples)
    {
        final int channels = Math.min(buffer.length, state.length);
        for (int i = 0; i < numSamples; i++)
        {
            final double a = advance();
            for (int ch = 0; ch < channels; ch++)
            {
                final double[] data = buffer[ch];
                data[offset + i] = processSample(ch, data[offset + i], a);
            }
        }
    }
}
