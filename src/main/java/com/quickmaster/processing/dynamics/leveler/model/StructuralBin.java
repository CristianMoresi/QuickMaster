package com.quickmaster.processing.dynamics.leveler.model;

/** One of the exactly 32 relative bins retained for a segment. */
public final class StructuralBin
{
    private final double[] chroma12;
    private final double[] spectral8;
    private final double onsetFlux;
    private final double activity;

    public StructuralBin(double[] chroma12, double[] spectral8,
                         double onsetFlux, double activity)
    {
        if (chroma12 == null || chroma12.length != 12
                || spectral8 == null || spectral8.length != 8
                || !Double.isFinite(onsetFlux) || !Double.isFinite(activity)
                || activity < 0.0d || activity > 1.0d)
        {
            throw new IllegalArgumentException("Invalid structural bin.");
        }
        this.chroma12 = new double[12];
        this.spectral8 = new double[8];
        for (int i = 0; i < 12; i++)
        {
            if (!Double.isFinite(chroma12[i]) || chroma12[i] < 0.0d)
            {
                throw new IllegalArgumentException("Invalid chroma bin.");
            }
            this.chroma12[i] = chroma12[i];
        }
        for (int i = 0; i < 8; i++)
        {
            if (!Double.isFinite(spectral8[i]))
            {
                throw new IllegalArgumentException("Invalid spectral bin.");
            }
            this.spectral8[i] = spectral8[i];
        }
        this.onsetFlux = onsetFlux;
        this.activity = activity;
    }

    public double chromaAt(int index) { return chroma12[index]; }
    public double spectralAt(int index) { return spectral8[index]; }
    public double onsetFlux() { return onsetFlux; }
    public double activity() { return activity; }
}
