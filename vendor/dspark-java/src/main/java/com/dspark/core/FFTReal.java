package com.dspark.core;

/**
 * FFT optimized for real-valued signals (the common audio case), built on
 * a half-size complex FFT.
 * <p>
 * Frequency-domain layout: {@code N + 2} interleaved floats holding the
 * {@code N/2 + 1} non-redundant complex bins; {@code freq[2k]} is the real
 * part and {@code freq[2k+1]} the imaginary part of bin {@code k}, with bin
 * 0 = DC and bin N/2 = Nyquist. {@link #inverse} reconstructs the original
 * real signal (it is the exact inverse of {@link #forward}).
 */
public final class FFTReal
{
    private final int realSize;
    private final int halfSize;
    private final FFTComplex complexFFT;
    private final float[] postTwiddles;
    private final float[] work;

    public FFTReal(int size)
    {
        if (size < 4 || (size & (size - 1)) != 0)
        {
            throw new IllegalArgumentException("FFTReal size must be a power of two >= 4");
        }
        this.realSize = size;
        this.halfSize = size / 2;
        this.complexFFT = new FFTComplex(halfSize);
        this.postTwiddles = new float[halfSize * 2];
        for (int k = 0; k < halfSize; k++)
        {
            double angle = -2.0 * Math.PI * k / realSize;
            postTwiddles[2 * k] = (float) Math.cos(angle);
            postTwiddles[2 * k + 1] = (float) Math.sin(angle);
        }
        this.work = new float[size];
    }

    public int getSize() { return realSize; }

    /** Number of frequency bins (N/2 + 1, including DC and Nyquist). */
    public int getNumBins() { return halfSize + 1; }

    /** Frequency-domain buffer size in floats (N + 2). */
    public int getFrequencyDomainSize() { return realSize + 2; }

    /**
     * Forward transform: {@code timeData} (N reals) → {@code freqData}
     * (N+2 interleaved complex floats).
     */
    public void forward(float[] timeData, float[] freqData)
    {
        System.arraycopy(timeData, 0, work, 0, realSize);
        complexFFT.forward(work);
        unpackForward(work, freqData);
    }

    /**
     * Inverse transform: {@code freqData} (N+2 interleaved complex floats)
     * → {@code timeData} (N reals).
     */
    public void inverse(float[] freqData, float[] timeData)
    {
        packInverse(freqData, work);
        complexFFT.inverse(work);
        System.arraycopy(work, 0, timeData, 0, realSize);
    }

    /** Fills {@code magnitudes} (N/2+1 values) with each bin's magnitude. */
    public void computeMagnitudes(float[] freqData, float[] magnitudes)
    {
        for (int k = 0; k <= halfSize; k++)
        {
            float re = freqData[2 * k];
            float im = freqData[2 * k + 1];
            magnitudes[k] = (float) Math.sqrt(re * re + im * im);
        }
    }

    public static double binToFrequency(int bin, double sampleRate, int fftSize)
    {
        return (double) bin * sampleRate / fftSize;
    }

    private void unpackForward(float[] halfFFT, float[] full)
    {
        int n2 = halfSize;
        float dcRe = halfFFT[0] + halfFFT[1];
        float nyRe = halfFFT[0] - halfFFT[1];
        full[0] = dcRe;
        full[1] = 0.0f;
        full[2 * n2] = nyRe;
        full[2 * n2 + 1] = 0.0f;

        for (int k = 1; k < n2; k++)
        {
            int kConj = n2 - k;
            float hkRe = halfFFT[2 * k];
            float hkIm = halfFFT[2 * k + 1];
            float hcRe = halfFFT[2 * kConj];
            float hcIm = halfFFT[2 * kConj + 1];

            float xeRe = 0.5f * (hkRe + hcRe);
            float xeIm = 0.5f * (hkIm - hcIm);
            float xoRe = 0.5f * (hkRe - hcRe);
            float xoIm = 0.5f * (hkIm + hcIm);

            float wr = postTwiddles[2 * k];
            float wi = postTwiddles[2 * k + 1];

            float joRe = xoIm;
            float joIm = -xoRe;

            float twRe = wr * joRe - wi * joIm;
            float twIm = wr * joIm + wi * joRe;

            full[2 * k] = xeRe + twRe;
            full[2 * k + 1] = xeIm + twIm;
        }
    }

    private void packInverse(float[] full, float[] halfFFT)
    {
        int n2 = halfSize;
        float dcRe = full[0];
        float nyRe = full[2 * n2];
        halfFFT[0] = 0.5f * (dcRe + nyRe);
        halfFFT[1] = 0.5f * (dcRe - nyRe);

        for (int k = 1; k < n2; k++)
        {
            int kConj = n2 - k;
            float xkRe = full[2 * k];
            float xkIm = full[2 * k + 1];
            float xcRe = full[2 * kConj];
            float xcIm = full[2 * kConj + 1];

            float xeRe = 0.5f * (xkRe + xcRe);
            float xeIm = 0.5f * (xkIm - xcIm);
            float diffRe = 0.5f * (xkRe - xcRe);
            float diffIm = 0.5f * (xkIm + xcIm);

            float wr = postTwiddles[2 * k];
            float wi = -postTwiddles[2 * k + 1];

            float twRe = wr * diffRe - wi * diffIm;
            float twIm = wr * diffIm + wi * diffRe;

            float xoRe = -twIm;
            float xoIm = twRe;

            halfFFT[2 * k] = xeRe + xoRe;
            halfFFT[2 * k + 1] = xeIm + xoIm;
        }
    }
}
