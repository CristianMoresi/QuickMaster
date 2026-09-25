package com.quickmaster.processing.dynamics.leveler.model;

/** Immutable structural feature clock. */
public final class FeatureTimeline
{
    private final long hopFrames;
    private final FrozenList<StructuralFrame> frames;

    public FeatureTimeline(long hopFrames, FrozenList<StructuralFrame> frames)
    {
        if (hopFrames <= 0L || frames == null)
        {
            throw new IllegalArgumentException("Invalid feature timeline.");
        }
        long previous = -1L;
        for (int i = 0; i < frames.size(); i++)
        {
            long center = frames.get(i).centerFrame();
            if (center <= previous)
            {
                throw new IllegalArgumentException("Feature centers must increase.");
            }
            previous = center;
        }
        this.hopFrames = hopFrames;
        this.frames = frames;
    }

    public long hopFrames() { return hopFrames; }
    public int size() { return frames.size(); }
    public StructuralFrame frame(int index) { return frames.get(index); }
    public FrozenList<StructuralFrame> frames() { return frames; }
}
