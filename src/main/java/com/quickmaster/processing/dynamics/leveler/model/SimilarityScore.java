package com.quickmaster.processing.dynamics.leveler.model;

/** Four-factor pair score with one explicit rejection channel. */
public final class SimilarityScore
{
    private final double h;
    private final double t;
    private final double a;
    private final double c;
    private final int chromaRotation;
    private final int validBins;
    private final SimilarityRejectionReason rejectionReason;

    public SimilarityScore(double h,
                           double t,
                           double a,
                           double c,
                           int chromaRotation,
                           int validBins,
                           SimilarityRejectionReason rejectionReason)
    {
        if (!finiteUnit(h) || !finiteUnit(t) || !finiteUnit(a) || !finiteUnit(c)
                || chromaRotation < 0 || chromaRotation > 11
                || validBins < 0 || validBins > 32 || rejectionReason == null)
        {
            throw new IllegalArgumentException("Invalid similarity score.");
        }
        if (rejectionReason == SimilarityRejectionReason.NONE && validBins < 24)
        {
            throw new IllegalArgumentException("Accepted score needs at least 24 valid bins.");
        }
        this.h = h;
        this.t = t;
        this.a = a;
        this.c = c;
        this.chromaRotation = chromaRotation;
        this.validBins = validBins;
        this.rejectionReason = rejectionReason;
    }

    public double h() { return h; }
    public double t() { return t; }
    public double a() { return a; }
    public double c() { return c; }
    public int chromaRotation() { return chromaRotation; }
    public int validBins() { return validBins; }
    public SimilarityRejectionReason rejectionReason() { return rejectionReason; }
    public boolean isAccepted() { return rejectionReason == SimilarityRejectionReason.NONE; }

    private static boolean finiteUnit(double value)
    {
        return Double.isFinite(value) && value >= 0.0d && value <= 1.0d;
    }
}
