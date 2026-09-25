package com.quickmaster.processing.dynamics.leveler.model;

/** Accepted complete-link group and per-member quality. */
public final class ComparableGroup
{
    private final int ordinal;
    private final int[] memberOrdinals;
    private final double[] memberQuality;
    private final double confidence;

    public ComparableGroup(int ordinal, int[] memberOrdinals, double[] memberQuality, double confidence)
    {
        if (ordinal < 0 || memberOrdinals == null || memberQuality == null
                || memberOrdinals.length < 3 || memberOrdinals.length != memberQuality.length
                || !Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d)
        {
            throw new IllegalArgumentException("Invalid comparable group.");
        }
        this.ordinal = ordinal;
        this.memberOrdinals = new int[memberOrdinals.length];
        this.memberQuality = new double[memberQuality.length];
        int previous = -1;
        for (int i = 0; i < memberOrdinals.length; i++)
        {
            int member = memberOrdinals[i];
            double quality = memberQuality[i];
            if (member < 0 || member > 63 || member <= previous
                    || !Double.isFinite(quality) || quality < 0.0d || quality > 1.0d)
            {
                throw new IllegalArgumentException("Invalid comparable group member.");
            }
            this.memberOrdinals[i] = member;
            this.memberQuality[i] = quality;
            previous = member;
        }
        this.confidence = confidence;
    }

    public int ordinal() { return ordinal; }
    public int size() { return memberOrdinals.length; }
    public int memberOrdinalAt(int index) { return memberOrdinals[index]; }
    public double memberQualityAt(int index) { return memberQuality[index]; }
    public double confidence() { return confidence; }

    public int[] copyMemberOrdinals()
    {
        int[] copy = new int[memberOrdinals.length];
        System.arraycopy(memberOrdinals, 0, copy, 0, copy.length);
        return copy;
    }

    public double[] copyMemberQuality()
    {
        double[] copy = new double[memberQuality.length];
        System.arraycopy(memberQuality, 0, copy, 0, copy.length);
        return copy;
    }
}
