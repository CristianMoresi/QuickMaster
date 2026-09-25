package com.quickmaster.ui.waveform;

/** Peak lookup for repeated waveform redraws. Source audio remains the authority. */
public final class WaveformPeakIndex
{
    private static final int BLOCK_FRAMES = 256;

    private final float[] samples;
    private final int channels;
    private final int frames;
    private final float[] blockPeaks;

    public WaveformPeakIndex(float[] samples, int channels)
    {
        if (samples == null || channels <= 0 || samples.length % channels != 0)
            throw new IllegalArgumentException("Complete interleaved frames are required.");
        this.samples = samples;
        this.channels = channels;
        this.frames = samples.length / channels;
        this.blockPeaks = new float[frames == 0 ? 0 : (frames - 1) / BLOCK_FRAMES + 1];
        for (int block = 0; block < blockPeaks.length; block++)
            blockPeaks[block] = scan(block * BLOCK_FRAMES,
                    (int) Math.min(frames, (long) (block + 1) * BLOCK_FRAMES));
    }

    public int frames() { return frames; }

    /** Resamples a viewport to pixel peaks, including partial edge bins. */
    public float[] columns(WaveformViewport viewport, int sampleRate, int width)
    {
        if (viewport == null || sampleRate <= 0 || width < 0)
            throw new IllegalArgumentException("Valid waveform geometry is required.");
        if (width == 0 || frames == 0 || viewport.durationSec() == 0.0) return new float[0];
        long first = Math.max(0L, Math.min(frames,
                (long) StrictMath.ceil(viewport.startSec() * sampleRate)));
        long last = Math.max(first, Math.min(frames,
                (long) StrictMath.ceil((viewport.startSec() + viewport.visibleSec()) * sampleRate)));
        long visibleFrames = last - first;
        float[] out = new float[width];
        for (int x = 0; x < width; x++)
        {
            long start = first + (long) x * visibleFrames / width;
            long end = first + ((long) (x + 1) * visibleFrames + width - 1L) / width;
            out[x] = peak((int) start, (int) Math.min(last, end));
        }
        return out;
    }

    /** Maximum absolute sample across all channels in the half-open frame range. */
    public float peak(int start, int end)
    {
        if (start < 0 || end < start || end > frames)
            throw new IllegalArgumentException("Waveform frame range is out of bounds.");
        int firstWhole = start / BLOCK_FRAMES + (start % BLOCK_FRAMES == 0 ? 0 : 1);
        int lastWhole = end / BLOCK_FRAMES;
        if (firstWhole >= lastWhole) return scan(start, end);
        float peak = scan(start, firstWhole * BLOCK_FRAMES);
        for (int block = firstWhole; block < lastWhole; block++)
            peak = Math.max(peak, blockPeaks[block]);
        return Math.max(peak, scan(lastWhole * BLOCK_FRAMES, end));
    }

    private float scan(int start, int end)
    {
        float peak = 0.0f;
        for (int frame = start; frame < end; frame++)
        {
            int base = frame * channels;
            for (int channel = 0; channel < channels; channel++)
            {
                float sample = samples[base + channel];
                float magnitude = sample >= 0.0f ? sample : -sample;
                if (magnitude > peak) peak = magnitude;
            }
        }
        return peak;
    }
}
