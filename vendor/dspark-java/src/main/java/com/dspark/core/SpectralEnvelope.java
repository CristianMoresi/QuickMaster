package com.dspark.core;

/**
 * The envelope of a spectrum by a smoothing whose width grows with frequency.
 * <p>
 * A one-pole runs across the bins forward and then backward (no shift: the
 * two passes are symmetric), and its coefficient at bin k follows a width
 * proportional to the frequency: about {@code widthOctaves} of an octave, so
 * the envelope keeps the shape of the spectrum at the ear's scale while a
 * line or a narrow peak stands out of it.
 * <p>
 * {@link #prepare} allocates; {@link #compute} runs on the owner's thread.
 */
public final class SpectralEnvelope
{
    private int n = 0, passes = 1;
    private double width = 1.0 / 3.0, minBins = 1.0, taperBin = 0.0, taperExponent = 0.0;
    private double[] coeff = new double[0];
    private final double[] carry = new double[8];

    /**
     * Sets the bins and the width.
     *
     * @param numBins      bins of the spectra (fftSize / 2 + 1), at least 2
     * @param widthOctaves width of the smoothing, 0.05 to 4 octaves
     * @param minBins      narrowest smoothing, in bins (1 for an unpadded spectrum)
     */
    public void prepare(int numBins, double widthOctaves, double minBins)
    {
        n = Math.max(2, numBins);
        this.minBins = Double.isFinite(minBins) ? Math.max(1.0, minBins) : 1.0;
        coeff = new double[n];
        setWidth(widthOctaves);
    }

    /** {@link #prepare(int, double, double)} with the narrowest smoothing at one bin. */
    public void prepare(int numBins, double widthOctaves)
    {
        prepare(numBins, widthOctaves, 1.0);
    }

    /** Width of the smoothing in octaves (0.05 to 4; non-finite values are ignored). */
    public void setWidth(double widthOctaves)
    {
        if (!Double.isFinite(widthOctaves)) return;
        width = Math.max(0.05, Math.min(widthOctaves, 4.0));
        // A one-pole run both ways has an equivalent width of about
        // 2 c / (1 - c) bins; the width wanted at bin k is k (2^(w/2) -
        // 2^(-w/2)) bins, at least minBins. Two passes of a smoothing
        // 1 / sqrt(2) as wide have the same equivalent width as one.
        double span = (Math.pow(2.0, 0.5 * width) - Math.pow(2.0, -0.5 * width)) / Math.sqrt(passes);
        for (int k = 0; k < n; k++)
        {
            double bins = span * k;
            if (taperExponent > 0.0 && taperBin > 0.0 && k > taperBin) bins *= Math.pow(taperBin / k, taperExponent);
            bins = Math.max(minBins, bins);
            coeff[k] = bins / (bins + 2.0);
        }
    }

    /** Passes of the smoothing: 1 (the default) or 2 (same equivalent width, shorter tails). */
    public void setPasses(int passes)
    {
        this.passes = passes >= 2 ? 2 : 1;
        setWidth(width);
    }

    /**
     * Narrows the smoothing above a corner: its width in octaves falls as
     * (corner / frequency) to the exponent (0 keeps it constant; at most 2).
     */
    public void setTaper(double cornerBin, double exponent)
    {
        if (!Double.isFinite(cornerBin) || !Double.isFinite(exponent)) return;
        taperBin = Math.max(0.0, cornerBin);
        taperExponent = Math.max(0.0, Math.min(exponent, 2.0));
        setWidth(width);
    }

    public int getPasses() { return passes; }
    public double getWidth() { return width; }
    public int getNumBins() { return n; }

    /**
     * The envelope of a spectrum.
     *
     * @param in  numBins values
     * @param out numBins values (may be {@code in})
     */
    public void compute(double[] in, double[] out)
    {
        if (out != in) System.arraycopy(in, 0, out, 0, n);
        computeInterleaved(1, out);
    }

    /**
     * The envelopes of several spectra at once, in place: each what
     * {@link #compute} gives for its own.
     *
     * @param lanes  how many spectra (1 to 8)
     * @param values numBins groups of {@code lanes} values, one of each spectrum
     */
    public void computeInterleaved(int lanes, double[] values)
    {
        if (n < 2) return;
        final double[] c = coeff;
        final double[] y = carry;
        for (int pass = 0; pass < passes; pass++)
        {
            System.arraycopy(values, 0, y, 0, lanes);
            for (int k = 1; k < n; k++)
            {
                int at = lanes * k;
                double ck = c[k];
                for (int j = 0; j < lanes; j++)
                {
                    double v = values[at + j];
                    double s = v + ck * (y[j] - v);
                    y[j] = s;
                    values[at + j] = s;
                }
            }
            for (int k = n - 2; k >= 0; k--)
            {
                int at = lanes * k;
                double ck = c[k];
                for (int j = 0; j < lanes; j++)
                {
                    double v = values[at + j];
                    double s = v + ck * (y[j] - v);
                    y[j] = s;
                    values[at + j] = s;
                }
            }
        }
    }
}
