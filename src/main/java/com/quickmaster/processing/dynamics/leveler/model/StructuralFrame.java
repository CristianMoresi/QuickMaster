package com.quickmaster.processing.dynamics.leveler.model;

/** One structural observation at the 500 ms clock. */
public final class StructuralFrame
{
    private final long centerFrame;
    private final double[] chroma12;
    private final double[] spectral8;
    private final double onsetFlux;
    private final double activity;

    public StructuralFrame(long centerFrame,
                           double[] chroma12,
                           double[] spectral8,
                           double onsetFlux,
                           double activity)
    {
        if (centerFrame < 0L || !Double.isFinite(onsetFlux)
                || !Double.isFinite(activity) || activity < 0.0d || activity > 1.0d)
        {
            throw new IllegalArgumentException("Invalid structural frame scalars.");
        }
        this.centerFrame = centerFrame;
        this.chroma12 = copyVector(chroma12, 12, true);
        this.spectral8 = copyVector(spectral8, 8, false);
        this.onsetFlux = onsetFlux;
        this.activity = activity;
    }

    public long centerFrame() { return centerFrame; }
    public double chromaAt(int index) { return chroma12[index]; }
    public double spectralAt(int index) { return spectral8[index]; }
    public double onsetFlux() { return onsetFlux; }
    public double activity() { return activity; }

    public double[] copyChroma12() { return copyVector(chroma12, 12, true); }
    public double[] copySpectral8() { return copyVector(spectral8, 8, false); }

    private static double[] copyVector(double[] source, int expected, boolean nonNegative)
    {
        if (source == null || source.length != expected)
        {
            throw new IllegalArgumentException("Unexpected structural vector length.");
        }
        double[] copy = new double[expected];
        for (int i = 0; i < expected; i++)
        {
            if (!Double.isFinite(source[i]) || (nonNegative && source[i] < 0.0d))
            {
                throw new IllegalArgumentException("Structural vector contains an invalid value.");
            }
            copy[i] = source[i];
        }
        return copy;
    }
}
