package com.quickmaster.processing.dynamics.leveler.model;

/** Content-free diagnostics for a completed shadow run. */
public final class ShadowDiagnostics
{
    private final ShadowAnalysisStatus status;
    private final long completedPhaseBits;
    private final FrozenList<DiagnosticEntry> entries;
    private final StandardValidationReport standardValidation;
    private final ShadowMemoryCounters memoryCounters;

    public ShadowDiagnostics(ShadowAnalysisStatus status,
                             long completedPhaseBits,
                             FrozenList<DiagnosticEntry> entries,
                             StandardValidationReport standardValidation,
                             ShadowMemoryCounters memoryCounters)
    {
        if (status == null || entries == null || standardValidation == null || memoryCounters == null
                || (completedPhaseBits & ~0x1FFL) != 0L || !isPrefix(completedPhaseBits))
        {
            throw new IllegalArgumentException("Invalid shadow diagnostics.");
        }
        if ((status == ShadowAnalysisStatus.SHADOW_READY || status == ShadowAnalysisStatus.DONE)
                && completedPhaseBits != 0x1FFL)
        {
            throw new IllegalArgumentException("Complete shadow status requires all phases.");
        }
        this.status = status;
        this.completedPhaseBits = completedPhaseBits;
        this.entries = entries;
        this.standardValidation = standardValidation;
        this.memoryCounters = memoryCounters;
    }

    public ShadowAnalysisStatus status() { return status; }
    public long completedPhaseBits() { return completedPhaseBits; }
    public FrozenList<DiagnosticEntry> entries() { return entries; }
    public StandardValidationReport standardValidation() { return standardValidation; }
    public ShadowMemoryCounters memoryCounters() { return memoryCounters; }

    private static boolean isPrefix(long bits)
    {
        return bits == 0L || (bits & (bits + 1L)) == 0L;
    }
}
