package com.quickmaster.processing.dynamics.leveler.model;

/** One aggregate diagnostic row; no PCM or full hashes. */
public final class DiagnosticEntry
{
    private final DiagnosticCode code;
    private final int segmentOrdinal;
    private final int relatedOrdinal;
    private final long occurrenceCount;

    public DiagnosticEntry(DiagnosticCode code,
                           int segmentOrdinal,
                           int relatedOrdinal,
                           long occurrenceCount)
    {
        if (code == null || segmentOrdinal < -1 || segmentOrdinal > 63
                || relatedOrdinal < -1 || relatedOrdinal > 63 || occurrenceCount <= 0L)
        {
            throw new IllegalArgumentException("Invalid diagnostic entry.");
        }
        this.code = code;
        this.segmentOrdinal = segmentOrdinal;
        this.relatedOrdinal = relatedOrdinal;
        this.occurrenceCount = occurrenceCount;
    }

    public DiagnosticCode code() { return code; }
    public int segmentOrdinal() { return segmentOrdinal; }
    public int relatedOrdinal() { return relatedOrdinal; }
    public long occurrenceCount() { return occurrenceCount; }
}
