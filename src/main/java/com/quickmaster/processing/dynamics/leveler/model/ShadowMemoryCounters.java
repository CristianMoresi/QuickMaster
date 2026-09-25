package com.quickmaster.processing.dynamics.leveler.model;

/** Production diagnostics only; the test auditor remains authoritative. */
public final class ShadowMemoryCounters
{
    private final long denseEnvelopeCount;
    private final long denseEnvelopeElements;
    private final long timelineArrayCount;
    private final long timelinePrimitiveElements;
    private final long descriptorArrayCount;
    private final long descriptorPrimitiveElements;
    private final long sketchArrayCount;
    private final long sketchPrimitiveElements;
    private final long matrixScoreCount;
    private final long directBufferBytes;
    private final long retainedPcmRefs;
    private final long retainedChunkCount;
    private final long retainedSpectrumCount;
    private final long maxChunkFrames;
    private final long loudnessHops;
    private final long structuralHops;
    private final long segments;
    private final long pairs;

    public ShadowMemoryCounters(long denseEnvelopeCount,
                                long denseEnvelopeElements,
                                long timelineArrayCount,
                                long timelinePrimitiveElements,
                                long descriptorArrayCount,
                                long descriptorPrimitiveElements,
                                long sketchArrayCount,
                                long sketchPrimitiveElements,
                                long matrixScoreCount,
                                long directBufferBytes,
                                long retainedPcmRefs,
                                long retainedChunkCount,
                                long retainedSpectrumCount,
                                long maxChunkFrames,
                                long loudnessHops,
                                long structuralHops,
                                long segments,
                                long pairs)
    {
        long[] values = new long[] { denseEnvelopeCount, denseEnvelopeElements, timelineArrayCount,
                timelinePrimitiveElements, descriptorArrayCount, descriptorPrimitiveElements,
                sketchArrayCount, sketchPrimitiveElements, matrixScoreCount, directBufferBytes,
                retainedPcmRefs, retainedChunkCount, retainedSpectrumCount, maxChunkFrames,
                loudnessHops, structuralHops, segments, pairs };
        for (int i = 0; i < values.length; i++)
        {
            if (values[i] < 0L) throw new IllegalArgumentException("Memory counters must be non-negative.");
        }
        if ((denseEnvelopeCount != 0L && denseEnvelopeCount != 1L)
                || (denseEnvelopeCount == 0L && denseEnvelopeElements != 0L)
                || directBufferBytes != 0L || retainedPcmRefs != 0L
                || retainedChunkCount != 0L || retainedSpectrumCount != 0L
                || maxChunkFrames > 65536L || segments > 64L || pairs > 2016L)
        {
            throw new IllegalArgumentException("Shadow memory counters violate M-004 caps.");
        }
        this.denseEnvelopeCount = denseEnvelopeCount;
        this.denseEnvelopeElements = denseEnvelopeElements;
        this.timelineArrayCount = timelineArrayCount;
        this.timelinePrimitiveElements = timelinePrimitiveElements;
        this.descriptorArrayCount = descriptorArrayCount;
        this.descriptorPrimitiveElements = descriptorPrimitiveElements;
        this.sketchArrayCount = sketchArrayCount;
        this.sketchPrimitiveElements = sketchPrimitiveElements;
        this.matrixScoreCount = matrixScoreCount;
        this.directBufferBytes = directBufferBytes;
        this.retainedPcmRefs = retainedPcmRefs;
        this.retainedChunkCount = retainedChunkCount;
        this.retainedSpectrumCount = retainedSpectrumCount;
        this.maxChunkFrames = maxChunkFrames;
        this.loudnessHops = loudnessHops;
        this.structuralHops = structuralHops;
        this.segments = segments;
        this.pairs = pairs;
    }

    public long denseEnvelopeCount() { return denseEnvelopeCount; }
    public long denseEnvelopeElements() { return denseEnvelopeElements; }
    public long timelineArrayCount() { return timelineArrayCount; }
    public long timelinePrimitiveElements() { return timelinePrimitiveElements; }
    public long descriptorArrayCount() { return descriptorArrayCount; }
    public long descriptorPrimitiveElements() { return descriptorPrimitiveElements; }
    public long sketchArrayCount() { return sketchArrayCount; }
    public long sketchPrimitiveElements() { return sketchPrimitiveElements; }
    public long matrixScoreCount() { return matrixScoreCount; }
    public long directBufferBytes() { return directBufferBytes; }
    public long retainedPcmRefs() { return retainedPcmRefs; }
    public long retainedChunkCount() { return retainedChunkCount; }
    public long retainedSpectrumCount() { return retainedSpectrumCount; }
    public long maxChunkFrames() { return maxChunkFrames; }
    public long loudnessHops() { return loudnessHops; }
    public long structuralHops() { return structuralHops; }
    public long segments() { return segments; }
    public long pairs() { return pairs; }
}
