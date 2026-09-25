package com.quickmaster.processing.dynamics.leveler.model;

/** Strict mutual-best two-member route. */
public final class ComparablePair
{
    private final int firstOrdinal;
    private final int secondOrdinal;
    private final double confidence;
    private final double margin;

    public ComparablePair(int firstOrdinal, int secondOrdinal, double confidence, double margin)
    {
        if (firstOrdinal < 0 || secondOrdinal <= firstOrdinal || secondOrdinal > 63
                || !Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d
                || !Double.isFinite(margin) || margin < 0.0d || margin > 1.0d)
        {
            throw new IllegalArgumentException("Invalid comparable pair.");
        }
        this.firstOrdinal = firstOrdinal;
        this.secondOrdinal = secondOrdinal;
        this.confidence = confidence;
        this.margin = margin;
    }

    public int firstOrdinal() { return firstOrdinal; }
    public int secondOrdinal() { return secondOrdinal; }
    public double confidence() { return confidence; }
    public double margin() { return margin; }
}
