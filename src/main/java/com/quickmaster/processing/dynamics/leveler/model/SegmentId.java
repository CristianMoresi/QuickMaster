package com.quickmaster.processing.dynamics.leveler.model;

/** Stable ordinal identity; display labels are derived rather than retained. */
public final class SegmentId
{
    private final int ordinal;

    public SegmentId(int ordinal)
    {
        if (ordinal < 0 || ordinal > 63)
        {
            throw new IllegalArgumentException("Segment ordinal must be in 0..63.");
        }
        this.ordinal = ordinal;
    }

    public int ordinal() { return ordinal; }
}
