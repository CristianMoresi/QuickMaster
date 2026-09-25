package com.quickmaster.processing.dynamics.leveler.model;

/** Exact, non-truncated structural partition. */
public final class SegmentLayout
{
    private final LayoutStatus status;
    private final FrozenList<FrameRange> regions;

    public SegmentLayout(LayoutStatus status, FrozenList<FrameRange> regions)
    {
        if (status == null || regions == null)
        {
            throw new IllegalArgumentException("Layout values must not be null.");
        }
        if (status == LayoutStatus.READY)
        {
            if (regions.size() < 1 || regions.size() > 64)
            {
                throw new IllegalArgumentException("Ready layout must contain 1..64 regions.");
            }
            long cursor = 0L;
            for (int i = 0; i < regions.size(); i++)
            {
                FrameRange range = regions.get(i);
                if (range.startInclusive() != cursor)
                {
                    throw new IllegalArgumentException("Ready layout is not contiguous.");
                }
                cursor = range.endExclusive();
            }
        }
        else if (regions.size() != 0)
        {
            throw new IllegalArgumentException("Failed layout must be empty.");
        }
        this.status = status;
        this.regions = regions;
    }

    public LayoutStatus status() { return status; }
    public FrozenList<FrameRange> regions() { return regions; }
}
