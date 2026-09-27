package com.dspark.core;

import java.util.Arrays;

/**
 * Power-of-two circular buffer for audio delay lines.
 * <p>
 * Capacity is rounded up to the next power of two so the wrap-around is a
 * cheap bit-mask. {@link #read(int)} returns a sample at an integer delay
 * (0 = most recently pushed).
 */
public final class RingBuffer
{
    private float[] buffer = new float[0];
    private int capacity = 0;
    private int mask = 0;
    private int writePos = 0;

    /** Allocates the buffer, rounding capacity up to a power of two. */
    public void prepare(int maxSamples)
    {
        if (maxSamples < 1) maxSamples = 1;
        // Clamp before the round-up: above 2^30 the doubling would overflow
        // int and spin the loop forever.
        if (maxSamples > (1 << 30)) maxSamples = 1 << 30;
        capacity = 1;
        while (capacity < maxSamples) capacity <<= 1;
        mask = capacity - 1;
        buffer = new float[capacity];
        writePos = 0;
    }

    /** Clears the buffer to silence. */
    public void reset()
    {
        if (capacity > 0) Arrays.fill(buffer, 0.0f);
        writePos = 0;
    }

    /** Writes one sample, advancing the write head. */
    public void push(float sample)
    {
        if (capacity == 0) return;
        buffer[writePos] = sample;
        writePos = (writePos + 1) & mask;
    }

    /**
     * Reads a sample at an integer delay.
     *
     * @param delaySamples  delay in samples (0 = most recent)
     * @return the delayed sample
     */
    public float read(int delaySamples)
    {
        if (capacity == 0) return 0.0f;
        int idx = (writePos - 1 - delaySamples) & mask;
        return buffer[idx];
    }

    public int getCapacity() { return capacity; }
}
