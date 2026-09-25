package com.quickmaster.processing.dynamics.leveler.model;

/** Validated source-clock format for one shadow analysis. */
public final class AudioFormat
{
    private final int sampleRateHz;
    private final int channels;
    private final long frames;

    public AudioFormat(int sampleRateHz, int channels, long frames)
    {
        if (sampleRateHz <= 0 || (channels != 1 && channels != 2) || frames <= 0L)
        {
            throw new IllegalArgumentException("Unsupported Leveler audio format.");
        }
        Math.multiplyExact(frames, (long) channels);
        if (frames > Integer.MAX_VALUE / channels)
        {
            throw new IllegalArgumentException("PCM does not fit a Java float array.");
        }
        this.sampleRateHz = sampleRateHz;
        this.channels = channels;
        this.frames = frames;
    }

    public int sampleRateHz() { return sampleRateHz; }
    public int channels() { return channels; }
    public long frames() { return frames; }

    public void validatePcm(float[] pcm)
    {
        if (pcm == null || pcm.length != Math.multiplyExact((int) frames, channels))
        {
            throw new IllegalArgumentException("PCM length does not match its format.");
        }
        for (int i = 0; i < pcm.length; i++)
        {
            if (!Float.isFinite(pcm[i]))
            {
                throw new IllegalArgumentException("PCM contains a non-finite sample.");
            }
        }
    }
}
