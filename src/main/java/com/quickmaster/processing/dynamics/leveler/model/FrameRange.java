package com.quickmaster.processing.dynamics.leveler.model;

/** Non-empty half-open source-frame range. */
public final class FrameRange
{
    private final long startInclusive;
    private final long endExclusive;

    public FrameRange(long startInclusive, long endExclusive)
    {
        if (startInclusive < 0L || endExclusive <= startInclusive)
        {
            throw new IllegalArgumentException("FrameRange must be non-empty and non-negative.");
        }
        this.startInclusive = startInclusive;
        this.endExclusive = endExclusive;
    }

    public long startInclusive() { return startInclusive; }
    public long endExclusive() { return endExclusive; }
    public long lengthFrames() { return endExclusive - startInclusive; }
}
