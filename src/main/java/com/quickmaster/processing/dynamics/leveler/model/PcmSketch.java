package com.quickmaster.processing.dynamics.leveler.model;

/** Bounded relative-clock PCM sketch; it never owns the source PCM. */
public final class PcmSketch
{
    private final int channels;
    private final int bins;
    private final double[][] values;

    public PcmSketch(int channels, int bins, double[][] values)
    {
        if ((channels != 1 && channels != 2) || bins != 2048 || values == null
                || values.length != channels)
        {
            throw new IllegalArgumentException("Invalid PCM sketch dimensions.");
        }
        this.channels = channels;
        this.bins = bins;
        this.values = new double[channels][];
        for (int channel = 0; channel < channels; channel++)
        {
            if (values[channel] == null || values[channel].length != bins)
            {
                throw new IllegalArgumentException("Invalid PCM sketch channel.");
            }
            this.values[channel] = new double[bins];
            for (int bin = 0; bin < bins; bin++)
            {
                double value = values[channel][bin];
                if (!Double.isFinite(value))
                {
                    throw new IllegalArgumentException("PCM sketch must be finite.");
                }
                this.values[channel][bin] = value;
            }
        }
    }

    public int channels() { return channels; }
    public int bins() { return bins; }
    public double valueAt(int channel, int bin) { return values[channel][bin]; }

    public double[][] copyValues()
    {
        double[][] copy = new double[channels][];
        for (int channel = 0; channel < channels; channel++)
        {
            copy[channel] = new double[bins];
            System.arraycopy(values[channel], 0, copy[channel], 0, bins);
        }
        return copy;
    }
}
