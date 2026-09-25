package com.quickmaster.processing.dynamics.leveler.model;

import java.util.BitSet;

/** Separate Momentary-power and Short-term clocks at the 100 ms hop. */
public final class LoudnessTimeline
{
    private final int momentaryWindowFrames;
    private final int shortTermWindowFrames;
    private final int hopFrames;
    private final double[] momentaryPower;
    private final BitSet momentaryValid;
    private final double[] shortTermLufs;
    private final BitSet shortTermValid;
    private final MeasuredLoudness integrated;

    public LoudnessTimeline(int momentaryWindowFrames,
                            int shortTermWindowFrames,
                            int hopFrames,
                            double[] momentaryPower,
                            BitSet momentaryValid,
                            double[] shortTermLufs,
                            BitSet shortTermValid,
                            MeasuredLoudness integrated)
    {
        if (momentaryWindowFrames <= 0 || shortTermWindowFrames <= 0 || hopFrames <= 0
                || momentaryPower == null || momentaryValid == null
                || shortTermLufs == null || shortTermValid == null || integrated == null
                || momentaryPower.length != shortTermLufs.length)
        {
            throw new IllegalArgumentException("Invalid loudness timeline dimensions.");
        }
        this.momentaryWindowFrames = momentaryWindowFrames;
        this.shortTermWindowFrames = shortTermWindowFrames;
        this.hopFrames = hopFrames;
        this.momentaryPower = copyFinite(momentaryPower, momentaryValid, true);
        this.momentaryValid = copyMask(momentaryValid, momentaryPower.length);
        this.shortTermLufs = copyFinite(shortTermLufs, shortTermValid, false);
        this.shortTermValid = copyMask(shortTermValid, shortTermLufs.length);
        this.integrated = integrated;
    }

    public int momentaryWindowFrames() { return momentaryWindowFrames; }
    public int shortTermWindowFrames() { return shortTermWindowFrames; }
    public int hopFrames() { return hopFrames; }
    public int momentaryCount() { return momentaryPower.length; }
    public int shortTermCount() { return shortTermLufs.length; }
    public double momentaryPowerAt(int index) { return momentaryPower[index]; }
    public boolean momentaryValidAt(int index) { return momentaryValid.get(index); }
    public double shortTermLufsAt(int index) { return shortTermLufs[index]; }
    public boolean shortTermValidAt(int index) { return shortTermValid.get(index); }
    public MeasuredLoudness integrated() { return integrated; }

    public double[] copyMomentaryPower()
    {
        double[] copy = new double[momentaryPower.length];
        System.arraycopy(momentaryPower, 0, copy, 0, copy.length);
        return copy;
    }

    public double[] copyShortTermLufs()
    {
        double[] copy = new double[shortTermLufs.length];
        System.arraycopy(shortTermLufs, 0, copy, 0, copy.length);
        return copy;
    }

    public BitSet copyMomentaryValid() { return copyMask(momentaryValid, momentaryPower.length); }
    public BitSet copyShortTermValid() { return copyMask(shortTermValid, shortTermLufs.length); }

    private static double[] copyFinite(double[] source, BitSet mask, boolean nonNegative)
    {
        double[] copy = new double[source.length];
        for (int i = 0; i < source.length; i++)
        {
            double value = source[i];
            if (mask.get(i))
            {
                if (!Double.isFinite(value) || (nonNegative && value < 0.0d))
                {
                    throw new IllegalArgumentException("Valid loudness slot is invalid.");
                }
                copy[i] = value;
            }
            else
            {
                if (Double.doubleToRawLongBits(value) != Double.doubleToRawLongBits(0.0d))
                {
                    throw new IllegalArgumentException("Invalid loudness slot must be +0.0.");
                }
                copy[i] = 0.0d;
            }
        }
        return copy;
    }

    private static BitSet copyMask(BitSet source, int logicalLength)
    {
        if (source.length() > logicalLength)
        {
            throw new IllegalArgumentException("Validity mask exceeds its timeline.");
        }
        BitSet copy = new BitSet(logicalLength);
        for (int bit = 0; bit < logicalLength; bit++)
        {
            if (source.get(bit)) copy.set(bit);
        }
        return copy;
    }
}
