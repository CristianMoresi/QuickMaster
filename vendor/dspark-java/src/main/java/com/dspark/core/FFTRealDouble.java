package com.dspark.core;

/**
 * Double-precision FFT for real-valued signals, built on a half-size complex
 * FFT. The counterpart of {@link FFTReal} for the analysis and filter-design
 * paths that the C++ framework runs in double.
 * <p>
 * Frequency-domain layout: {@code N + 2} interleaved doubles holding the
 * {@code N/2 + 1} non-redundant complex bins; {@code freq[2k]} is the real
 * part and {@code freq[2k+1]} the imaginary part of bin {@code k}, with bin
 * 0 = DC and bin N/2 = Nyquist. {@link #forward} is not normalized;
 * {@link #inverse} is normalized by 1/N, so it is the exact inverse.
 * <p>
 * An instance is stateful: use one per concurrent worker.
 */
public final class FFTRealDouble
{
    private final int realSize;
    private final int halfSize;
    private final FFTComplex complexFFT;
    private final double[] twiddleRe;
    private final double[] twiddleIm;
    private final double[] work;

    public FFTRealDouble(int size)
    {
        if (size < 4 || (size & (size - 1)) != 0)
            throw new IllegalArgumentException("FFTRealDouble size must be a power of two >= 4");
        this.realSize = size;
        this.halfSize = size / 2;
        this.complexFFT = new FFTComplex(halfSize);
        this.twiddleRe = new double[halfSize];
        this.twiddleIm = new double[halfSize];
        for (int k = 0; k < halfSize; k++)
        {
            double angle = -2.0 * Math.PI * k / realSize;
            twiddleRe[k] = Math.cos(angle);
            twiddleIm[k] = Math.sin(angle);
        }
        this.work = new double[size];
    }

    public int getSize() { return realSize; }

    /** Number of frequency bins (N/2 + 1, including DC and Nyquist). */
    public int getNumBins() { return halfSize + 1; }

    /** Frequency-domain buffer size in doubles (N + 2). */
    public int getFrequencyDomainSize() { return realSize + 2; }

    /**
     * Forward transform: {@code timeData} (N reals) to {@code freqData}
     * (N + 2 interleaved complex doubles). The arrays must be distinct.
     */
    public void forward(double[] timeData, double[] freqData)
    {
        forward(timeData, 0, freqData, 0);
    }

    /**
     * Forward transform between stretches of larger arrays: N reals from
     * {@code timeData[timeOffset]} to N + 2 interleaved complex doubles at
     * {@code freqData[freqOffset]}.
     */
    public void forward(double[] timeData, int timeOffset, double[] freqData, int freqOffset)
    {
        final double[] h = work;
        System.arraycopy(timeData, timeOffset, h, 0, realSize);
        complexFFT.forward(h);

        final int n2 = halfSize;
        final int f = freqOffset;
        freqData[f] = h[0] + h[1];
        freqData[f + 1] = 0.0;
        freqData[f + 2 * n2] = h[0] - h[1];
        freqData[f + 2 * n2 + 1] = 0.0;
        for (int k = 1; k < n2; k++)
        {
            int kc = n2 - k;
            double hkRe = h[2 * k], hkIm = h[2 * k + 1];
            double hcRe = h[2 * kc], hcIm = h[2 * kc + 1];

            double xeRe = 0.5 * (hkRe + hcRe);
            double xeIm = 0.5 * (hkIm - hcIm);
            double xoRe = 0.5 * (hkRe - hcRe);
            double xoIm = 0.5 * (hkIm + hcIm);

            double wr = twiddleRe[k], wi = twiddleIm[k];
            // -j * xo, turned by the twiddle.
            double joRe = xoIm, joIm = -xoRe;
            freqData[f + 2 * k] = xeRe + (wr * joRe - wi * joIm);
            freqData[f + 2 * k + 1] = xeIm + (wr * joIm + wi * joRe);
        }
    }

    /**
     * Inverse transform: {@code freqData} (N + 2 interleaved complex doubles)
     * to {@code timeData} (N reals). The arrays must be distinct;
     * {@code freqData} is left untouched.
     */
    public void inverse(double[] freqData, double[] timeData)
    {
        inverse(freqData, 0, timeData, 0);
    }

    /**
     * Inverse transform between stretches of larger arrays: N + 2 interleaved
     * complex doubles at {@code freqData[freqOffset]} to N reals at
     * {@code timeData[timeOffset]}.
     */
    public void inverse(double[] freqData, int freqOffset, double[] timeData, int timeOffset)
    {
        final double[] h = work;
        final int n2 = halfSize;
        final int f = freqOffset;
        double dcRe = freqData[f];
        double nyRe = freqData[f + 2 * n2];
        h[0] = 0.5 * (dcRe + nyRe);
        h[1] = 0.5 * (dcRe - nyRe);
        for (int k = 1; k < n2; k++)
        {
            int kc = n2 - k;
            double xkRe = freqData[f + 2 * k], xkIm = freqData[f + 2 * k + 1];
            double xcRe = freqData[f + 2 * kc], xcIm = freqData[f + 2 * kc + 1];

            double xeRe = 0.5 * (xkRe + xcRe);
            double xeIm = 0.5 * (xkIm - xcIm);
            double diffRe = 0.5 * (xkRe - xcRe);
            double diffIm = 0.5 * (xkIm + xcIm);

            double wr = twiddleRe[k], wi = -twiddleIm[k];
            double twRe = wr * diffRe - wi * diffIm;
            double twIm = wr * diffIm + wi * diffRe;

            // xo = j * tw
            h[2 * k] = xeRe - twIm;
            h[2 * k + 1] = xeIm + twRe;
        }
        complexFFT.inverse(h);
        System.arraycopy(h, 0, timeData, timeOffset, realSize);
    }
}
