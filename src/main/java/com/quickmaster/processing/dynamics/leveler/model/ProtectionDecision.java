package com.quickmaster.processing.dynamics.leveler.model;

/** Protection result for the aliased descriptor identity. */
public final class ProtectionDecision
{
    private final SegmentId id;
    private final ProtectionFlags flags;

    public ProtectionDecision(SegmentId id, ProtectionFlags flags)
    {
        if (id == null || flags == null)
        {
            throw new IllegalArgumentException("Protection decision values must not be null.");
        }
        this.id = id;
        this.flags = flags;
    }

    public SegmentId id() { return id; }
    public ProtectionFlags flags() { return flags; }
    public boolean isBlocked() { return flags.reasonBits() != 0L; }
}
