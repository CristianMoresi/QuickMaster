package com.dspark.core;

import java.util.Arrays;

/**
 * Carries a dry signal through the same round trip as an {@link Oversampling}
 * engine, so that a dry/wet mix adds in phase at every frequency.
 * <p>
 * A linear-phase engine delays by a whole number of samples at every
 * frequency, and the dry path is that delay. A polyphase IIR engine does not:
 * its round-trip delay is fractional and rises towards the band edge (3.07
 * samples at 1 kHz and 4.23 at 18 kHz for 2x High at 48 kHz), so against a
 * dry signal delayed by the rounded figure a 50 % mix of an untouched wet
 * signal measures a notch above the middle of the band. Here the dry signal
 * runs through a twin of the engine with nothing between its two halves,
 * which is the same linear filter the wet signal crosses: the two add in
 * phase, and a wet path that does nothing nulls against the dry one exactly.
 * <p>
 * The twin costs what the engine's filters cost; the linear-phase case costs
 * a delay. {@link #prepare} allocates; {@link #process} does not.
 */
public final class OversamplingDryPath
{
    private Oversampling twin = null;     // polyphase IIR: the engine again, with nothing in between
    private double[] delay = new double[0];   // linear phase: one delay line per channel, side by side
    private int delaySamples = 0;
    private int numChannels = 0;
    private int writePos = 0;

    /**
     * Sizes the path for an engine.
     *
     * @param sampleRate    the base rate the engine was prepared with
     * @param numChannels   the channel count the engine was prepared with
     * @param maxBlockSize  the largest base block the engine was prepared with
     * @param engine        the engine whose round trip the dry signal has to
     *                      match, already prepared with the same values (with
     *                      {@link Oversampling.Band#AUDIBLE} its delay is known
     *                      only then); its factor, quality, mode, depth, band
     *                      and latency are read
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize, Oversampling engine)
    {
        twin = null;
        delay = new double[0];
        delaySamples = 0;
        writePos = 0;
        this.numChannels = Math.max(0, Math.min(numChannels, Oversampling.MAX_CHANNELS));
        if (engine.getFactor() <= 1 || this.numChannels == 0 || maxBlockSize <= 0)
            return;

        if (engine.getMode() == Oversampling.Mode.POLYPHASE_IIR)
        {
            twin = new Oversampling(engine.getFactor(), engine.getQuality(),
                                    engine.getMode(), engine.getDepth(), engine.getBand());
            twin.prepare(sampleRate, numChannels, maxBlockSize);
        }
        else
        {
            delaySamples = engine.getLatency();
            delay = new double[this.numChannels * Math.max(delaySamples, 1)];
        }
    }

    /** Clears the path's memory. */
    public void reset()
    {
        if (twin != null) twin.reset();
        Arrays.fill(delay, 0.0);
        writePos = 0;
    }

    /**
     * The delay of the path in base-rate samples, as the engine reports its own.
     *
     * @return the latency
     */
    public int getLatency() { return twin != null ? twin.getLatency() : delaySamples; }

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] dry, int numSamples)
    {
        process(dry, 0, numSamples);
    }

    /**
     * Passes a block of the dry signal through the path, in place. With a
     * twin, blocks longer than the prepared maxBlockSize keep their tail
     * untouched; channels beyond the prepared count are left alone.
     *
     * @param dry         base-rate channels
     * @param offset      first sample of the block in every channel
     * @param numSamples  block length
     */
    public void process(double[][] dry, int offset, int numSamples)
    {
        if (twin != null)
        {
            twin.upsample(dry, offset, numSamples);
            twin.downsample(dry, offset, numSamples);
            return;
        }
        if (delaySamples <= 0) return;

        final int nCh = Math.min(dry.length, numChannels);
        final int nS = numSamples;
        if (nS <= 0) return;
        final int length = delaySamples;
        for (int ch = 0; ch < nCh; ch++)
        {
            final double[] x = dry[ch];
            final int line = ch * length;
            int pos = writePos;
            for (int i = 0; i < nS; i++)
            {
                final double out = delay[line + pos];
                delay[line + pos] = x[offset + i];
                x[offset + i] = out;
                if (++pos == length) pos = 0;
            }
        }
        // Every channel advanced by the same count from the same position.
        writePos = (int) ((writePos + (long) nS) % length);
    }
}
