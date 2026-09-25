package com.quickmaster.processing.dynamics.leveler.model;

/** Compact immutable union of the eight irrevocable protection reasons. */
public final class ProtectionFlags
{
    private final long reasonBits;

    public ProtectionFlags(long reasonBits)
    {
        if ((reasonBits & ~0xFFL) != 0L)
        {
            throw new IllegalArgumentException("Protection bits must fit in bits 0..7.");
        }
        this.reasonBits = reasonBits;
    }

    public long reasonBits() { return reasonBits; }
    public boolean isBlocked() { return reasonBits != 0L; }
    public boolean containsBit(int bit)
    {
        if (bit < 0 || bit > 7) throw new IllegalArgumentException("Protection bit must be 0..7.");
        return (reasonBits & (1L << bit)) != 0L;
    }
}
