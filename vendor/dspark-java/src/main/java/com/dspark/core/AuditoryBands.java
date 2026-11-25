package com.dspark.core;

/**
 * Bands uniform on the Bark scale over a power spectrum, with the loudness law.
 * <p>
 * Each bin feeds the two bands around it with triangular weights in Bark (the
 * weights of a bin sum to one), so a band's power is the power of a band one
 * spacing wide; where a band is narrower than a bin it reads the power density
 * at its centre by linear interpolation between bins, times its width. The
 * level of a full-scale sine is the reference: its power is referred to
 * {@link #setReferenceSpl} (100 dB SPL at 0 dBFS by default) for the loudness law.
 * <p>
 * {@link #prepare} allocates; the rest runs without allocation on the owner's thread.
 */
public final class AuditoryBands
{
    private double fs = 48000.0, binHz = 23.4375, z0 = 0.0, dz = 0.3, refSpl = 100.0;
    private int numBins = 0, n = 0;
    private double[] centre = new double[0], bark = new double[0], exponent = new double[0];
    private double[] threshold = new double[0], thresholdLoudness = new double[0], widthBins = new double[0];
    private boolean[] narrow = new boolean[0];
    private int[] binBand = new int[0];
    private double[] binWeight = new double[0];
    private double[] below = new double[8], above = new double[8];

    /**
     * Lays the bands out.
     *
     * @param sampleRate rate of the spectrum (invalid values are ignored)
     * @param fftSize    size of the transform (the spectrum has fftSize / 2 + 1 bins)
     * @param numBands   number of bands, 2 to 512
     * @param loHz       centre of the lowest band
     * @param hiHz       centre of the highest band (at most 0.49 of the rate)
     */
    public void prepare(double sampleRate, int fftSize, int numBands, double loHz, double hiHz)
    {
        if (!Double.isFinite(sampleRate) || !(sampleRate > 0.0) || fftSize < 8) return;
        fs = sampleRate;
        numBins = fftSize / 2 + 1;
        binHz = fs / fftSize;
        n = Math.max(2, Math.min(numBands, 512));
        double hi = Math.max(40.0, Math.min(hiHz, 0.49 * fs));
        double lo = Math.max(10.0, Math.min(loHz, 0.5 * hi));
        z0 = AuditoryScale.hzToBark(lo);
        dz = (AuditoryScale.hzToBark(hi) - z0) / (n - 1);
        centre = new double[n];
        bark = new double[n];
        exponent = new double[n];
        threshold = new double[n];
        thresholdLoudness = new double[n];
        narrow = new boolean[n];
        widthBins = new double[n];
        for (int b = 0; b < n; b++)
        {
            bark[b] = z0 + dz * b;
            centre[b] = AuditoryScale.barkToHz(bark[b]);
            exponent[b] = AuditoryScale.loudnessExponent(centre[b]);
            // A band one spacing wide, in bins.
            widthBins[b] = (AuditoryScale.barkToHz(bark[b] + 0.5 * dz) - AuditoryScale.barkToHz(bark[b] - 0.5 * dz)) / binHz;
            narrow[b] = widthBins[b] < 2.0;
        }
        // Each bin's two bands and their triangular weights.
        binBand = new int[numBins];
        binWeight = new double[numBins];
        java.util.Arrays.fill(binBand, -2);
        for (int k = 1; k < numBins; k++)
        {
            double pos = (AuditoryScale.hzToBark(binHz * k) - z0) / dz;
            if (pos < -1.0 || pos > n) continue;
            int lower = (int) Math.floor(pos);
            binBand[k] = lower;
            binWeight[k] = 1.0 - (pos - lower);   // to `lower`; the rest to lower + 1
        }
        setReferenceSpl(refSpl);
    }

    /** The SPL of a full-scale sine for the loudness law (60 to 130 dB). */
    public void setReferenceSpl(double dbSpl)
    {
        if (!Double.isFinite(dbSpl)) return;
        refSpl = Math.max(60.0, Math.min(dbSpl, 130.0));
        for (int b = 0; b < n; b++)
        {
            threshold[b] = Math.pow(10.0, (AuditoryScale.thresholdInQuietDb(centre[b]) - refSpl) / 10.0);
            thresholdLoudness[b] = Math.pow(threshold[b], exponent[b]);
        }
    }

    public int getNumBands() { return n; }
    public int getNumBins() { return numBins; }
    /** The centre of a band in hertz. */
    public double getCentre(int band) { return centre[clampBand(band)]; }
    /** The centre of a band in Bark. */
    public double getBark(int band) { return bark[clampBand(band)]; }
    /** The spacing of the bands in Bark. */
    public double getSpacing() { return dz; }
    /** The loudness exponent of a band. */
    public double getExponent(int band) { return exponent[clampBand(band)]; }
    /** The threshold in quiet of a band, as a power relative to a full-scale sine. */
    public double getThreshold(int band) { return threshold[clampBand(band)]; }
    /** The threshold to the exponent of a band: what {@link #specificLoudness} takes off. */
    public double getThresholdLoudness(int band) { return thresholdLoudness[clampBand(band)]; }

    private int clampBand(int band) { return Math.max(0, Math.min(band, n - 1)); }

    /**
     * Band powers from a power spectrum.
     *
     * @param binPower numBins powers (|X[k]|^2 scaled so a full-scale sine reads 1 in its bin)
     * @param out      getNumBands() powers
     */
    public void bandPowers(double[] binPower, double[] out)
    {
        bandPowersInterleaved(1, binPower, out);
    }

    /**
     * The band powers of several spectra at once: each what {@link #bandPowers}
     * gives for its own.
     *
     * @param lanes    how many spectra (1 to 8)
     * @param binPower numBins groups of {@code lanes} powers, one of each spectrum
     * @param out      getNumBands() groups of {@code lanes} powers, the same way
     */
    public void bandPowersInterleaved(int lanes, double[] binPower, double[] out)
    {
        java.util.Arrays.fill(out, 0, n * lanes, 0.0);
        final double[] lo = below, hi = above;
        // The bins that feed the same two bands lie together: their sums are
        // carried from the first to the last of them.
        for (int k = 1; k < numBins;)
        {
            int lower = binBand[k];
            if (lower < -1)
            {
                k++;
                continue;
            }
            boolean intoLower = lower >= 0 && lower < n && !narrow[lower];
            boolean intoUpper = lower + 1 < n && !narrow[lower + 1];
            for (int j = 0; j < lanes; j++)
            {
                lo[j] = intoLower ? out[lanes * lower + j] : 0.0;
                hi[j] = intoUpper ? out[lanes * (lower + 1) + j] : 0.0;
            }
            for (; k < numBins && binBand[k] == lower; k++)
            {
                double w = binWeight[k], rest = 1.0 - w;
                int at = lanes * k;
                for (int j = 0; j < lanes; j++)
                {
                    double p = binPower[at + j];
                    lo[j] += w * p;
                    hi[j] += rest * p;
                }
            }
            if (intoLower) System.arraycopy(lo, 0, out, lanes * lower, lanes);
            if (intoUpper) System.arraycopy(hi, 0, out, lanes * (lower + 1), lanes);
        }
        for (int b = 0; b < n; b++)
        {
            if (!narrow[b]) continue;
            double pos = Math.max(0.0, Math.min(centre[b] / binHz, numBins - 1.0));
            int k = Math.min((int) pos, numBins - 2);
            double f = pos - k;
            int at = lanes * k;
            for (int j = 0; j < lanes; j++)
                out[lanes * b + j] = widthBins[b] * ((1.0 - f) * binPower[at + j] + f * binPower[at + j + lanes]);
        }
    }

    /**
     * Smooths values across bands in both directions with a one-pole
     * (forward, then backward): a spread like the ear's masking.
     *
     * @param v     the values, in place
     * @param count how many
     * @param coeff pole, 0 (none) to just under 1
     */
    public static void smoothAcross(double[] v, int count, double coeff)
    {
        if (count < 2 || !(coeff > 0.0)) return;
        double c = Math.min(coeff, 0.999);
        for (int b = 1; b < count; b++) v[b] += c * (v[b - 1] - v[b]);
        for (int b = count - 2; b >= 0; b--) v[b] += c * (v[b + 1] - v[b]);
    }

    /**
     * Specific loudness of band powers: (P + P_th)^a - P_th^a, with the band's
     * exponent and threshold.
     */
    public void specificLoudness(double[] bandPower, double[] out)
    {
        for (int b = 0; b < n; b++)
        {
            // What is not a number reads as nothing.
            double p = bandPower[b];
            out[b] = Math.pow((p > 0.0 ? p : 0.0) + threshold[b], exponent[b]) - thresholdLoudness[b];
        }
    }

    /** The total loudness: the specific loudness integrated over Bark (trapezoids). */
    public double totalLoudness(double[] specific)
    {
        double s = 0.0;
        for (int b = 1; b < n; b++) s += 0.5 * (specific[b - 1] + specific[b]);
        return s * dz;
    }
}
