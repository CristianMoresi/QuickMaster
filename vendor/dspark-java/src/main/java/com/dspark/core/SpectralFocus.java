package com.dspark.core;

/**
 * A frame's spectrum read through a window that shortens as frequency rises,
 * from the one transform of the whole window.
 * <p>
 * Smoothing a complex spectrum across frequency is the same as multiplying
 * the frame by a weight in time: the wider the smoothing, the narrower the
 * weight. With the time origin moved to the window's peak, a smoothing whose
 * width grows with frequency (a one-pole run both ways) weighs the low bins
 * by nearly the whole window and the high ones by a short stretch around its
 * peak: the low end keeps its resolution in frequency and the top gains it in
 * time, where the ear has it.
 * <p>
 * {@link #power} gives the power of each bin scaled so that a noise, or a
 * tone summed over its lobe, reads what it reads through the whole window.
 * <p>
 * {@link #prepare} allocates; the rest runs on the owner's thread.
 */
public final class SpectralFocus
{
    private int n = 0, m = 0, bins = 0;
    private double[] coeff = new double[0], turnRe = new double[0], turnIm = new double[0], gain = new double[0];
    private double[] re = new double[0], im = new double[0];

    /**
     * Sets the window, the transform and the width.
     *
     * @param window       the analysis window the frames are made with
     * @param windowSize   its length, at least 2
     * @param transform    the transform's size (windowSize or more, for a
     *                     zero-padded frame that starts at the window's start)
     * @param widthOctaves width of the smoothing, 0.05 to 4 octaves
     * @param minBins      narrowest smoothing, in bins (1 for an unpadded spectrum)
     */
    public void prepare(double[] window, int windowSize, int transform, double widthOctaves, double minBins)
    {
        n = Math.max(2, windowSize);
        m = Math.max(n, transform);
        bins = m / 2 + 1;
        int peak = 0;
        double energy = 0.0;
        for (int i = 0; i < n; i++)
        {
            if (window[i] > window[peak]) peak = i;
            energy += window[i] * window[i];
        }
        double width = Math.max(0.05, Math.min(Double.isFinite(widthOctaves) ? widthOctaves : 1.0 / 3.0, 4.0));
        double least = Double.isFinite(minBins) ? Math.max(1.0, minBins) : 1.0;
        double span = Math.pow(2.0, 0.5 * width) - Math.pow(2.0, -0.5 * width);
        coeff = new double[bins];
        turnRe = new double[bins];
        turnIm = new double[bins];
        gain = new double[bins];
        re = new double[bins];
        im = new double[bins];
        double step = 2.0 * Math.PI / m;
        for (int k = 0; k < bins; k++)
        {
            double b = Math.max(least, span * k);
            coeff[k] = b / (b + 2.0);
            // The origin of time moved to the window's peak: a turn of the phase by bin.
            double angle = step * (((long) k * peak) % m);
            turnRe[k] = Math.cos(angle);
            turnIm[k] = Math.sin(angle);
        }
        // The energy of the whole window over that of each bin's own: found at
        // a few coefficients (it moves slowly with the bin) and read between them.
        double[] cosine = new double[n];
        for (int i = 0; i < n; i++) cosine[i] = Math.cos(step * (i - peak));
        // The knots sit evenly in the logarithm of the smoothing's width, where
        // the logarithm of the gain is close to a line.
        final int knots = 48;
        double wLow = Math.log(widthOf(coeff[0])), wHigh = Math.log(widthOf(coeff[bins - 1]));
        double[] knot = new double[knots];
        for (int j = 0; j < knots; j++)
        {
            double b = Math.exp(wLow + (wHigh - wLow) * j / (knots - 1.0));
            knot[j] = Math.log(gainOf(window, cosine, energy, b / (b + 2.0)));
        }
        for (int k = 0; k < bins; k++)
        {
            double x = wHigh > wLow ? (Math.log(widthOf(coeff[k])) - wLow) / (wHigh - wLow) * (knots - 1.0) : 0.0;
            int j = Math.max(0, Math.min((int) x, knots - 2));
            double t = x - j;
            gain[k] = Math.exp(knot[j] + t * (knot[j + 1] - knot[j]));
        }
    }

    /** The smoothing's width in bins for a coefficient. */
    private static double widthOf(double c) { return 2.0 * c / (1.0 - c); }

    private double gainOf(double[] window, double[] cosine, double energy, double c)
    {
        double own = 0.0;
        double top = (1.0 - c) * (1.0 - c), flat = 1.0 + c * c;
        for (int i = 0; i < n; i++)
        {
            double w = window[i] * top / (flat - 2.0 * c * cosine[i]);
            own += w * w;
        }
        return own > 0.0 ? energy / own : 1.0;
    }

    public int getNumBins() { return bins; }

    /** The scale of a bin's power (see {@link #power}). */
    public double getGain(int bin) { return gain[Math.max(0, Math.min(bin, bins - 1))]; }

    /**
     * The smoothed complex spectrum of a frame.
     *
     * @param spectrum the frame's transform, interleaved (real, imaginary), getNumBins() pairs
     * @param outRe    getNumBins() values: the real parts
     * @param outIm    getNumBins() values: the imaginary parts
     */
    public void smooth(double[] spectrum, double[] outRe, double[] outIm)
    {
        for (int k = 0; k < bins; k++)
        {
            double xr = spectrum[2 * k], xi = spectrum[2 * k + 1];
            outRe[k] = xr * turnRe[k] - xi * turnIm[k];
            outIm[k] = xr * turnIm[k] + xi * turnRe[k];
        }
        for (int k = 1; k < bins; k++)
        {
            double c = coeff[k];
            outRe[k] += c * (outRe[k - 1] - outRe[k]);
            outIm[k] += c * (outIm[k - 1] - outIm[k]);
        }
        for (int k = bins - 2; k >= 0; k--)
        {
            double c = coeff[k];
            outRe[k] += c * (outRe[k + 1] - outRe[k]);
            outIm[k] += c * (outIm[k + 1] - outIm[k]);
        }
    }

    /**
     * The power of each bin through its own window, scaled to read as through
     * the whole one.
     *
     * @param spectrum the frame's transform, interleaved, getNumBins() pairs
     * @param out      getNumBins() powers
     */
    public void power(double[] spectrum, double[] out)
    {
        smooth(spectrum, re, im);
        for (int k = 0; k < bins; k++) out[k] = gain[k] * (re[k] * re[k] + im[k] * im[k]);
    }
}
