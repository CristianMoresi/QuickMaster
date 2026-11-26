package com.dspark.core;

/**
 * Double-precision FFT for real-valued signals whose inverse can also run in
 * steps, for callers that spread a long transform over several calls.
 * <p>
 * Layout and scaling are those of {@link FFTRealDouble}, and so are the
 * results: {@code N + 2} interleaved doubles hold bins 0 to {@code N/2}
 * ({@code freq[2k]} the real part, {@code freq[2k+1]} the imaginary part),
 * {@link #forward} is not normalized and {@link #inverse} is the exact
 * inverse (normalized by 1/N).
 * <p>
 * {@link #inverseStep} splits {@link #inverse} into
 * {@link #getInverseStepCount()} steps. Step 0 reads the spectrum, the last
 * step writes the samples, and running steps 0 to count - 1 in order, with no
 * other inverse on this instance in between, writes exactly what
 * {@link #inverse} writes, bit for bit. The count is the one of the reference
 * x86-64 build of the library (a real transform of two lanes, with a
 * four-lane variant chosen at run time): one step that reads the spectrum
 * and one per radix-4 pass of the longer of the two plans,
 * {@code (log2 N - 1) / 2} passes from 16 points on and one below. A caller
 * that paces its work by steps therefore paces it the same way.
 * <p>
 * An instance is stateful: use one per concurrent worker.
 */
public final class FFTRealDoubleStepped
{
    private final FFTRealDouble fft;
    private final double[] stepSpectrum;
    private final int stepCount;

    /**
     * A transform of {@code size} real samples.
     *
     * @param size number of real samples: a power of two, at least 4
     * @throws IllegalArgumentException if the size is invalid
     */
    public FFTRealDoubleStepped(int size)
    {
        if (size < 4 || (size & (size - 1)) != 0)
            throw new IllegalArgumentException("FFTRealDoubleStepped size must be a power of two >= 4");
        fft = new FFTRealDouble(size);
        stepSpectrum = new double[size + 2];
        stepCount = inverseStepCountFor(size);
    }

    /** Steps of the inverse for a transform of {@code size} samples (see the class notes). */
    private static int inverseStepCountFor(int size)
    {
        if (size < 16) return 2;
        final int log2 = Integer.numberOfTrailingZeros(size);
        return (log2 - 1) / 2 + 1;
    }

    /** Number of real input samples (N). */
    public int getSize() { return fft.getSize(); }

    /** Number of frequency bins (N/2 + 1, including DC and Nyquist). */
    public int getNumBins() { return fft.getNumBins(); }

    /** Frequency-domain buffer size in doubles (N + 2). */
    public int getFrequencyDomainSize() { return fft.getFrequencyDomainSize(); }

    /** Forward transform: N reals to N + 2 interleaved complex doubles (distinct arrays). */
    public void forward(double[] timeData, double[] freqData)
    {
        fft.forward(timeData, 0, freqData, 0);
    }

    /**
     * Forward transform between stretches of larger arrays: N reals from
     * {@code timeData[timeOffset]} to N + 2 interleaved complex doubles at
     * {@code freqData[freqOffset]}.
     */
    public void forward(double[] timeData, int timeOffset, double[] freqData, int freqOffset)
    {
        fft.forward(timeData, timeOffset, freqData, freqOffset);
    }

    /** Inverse transform: N + 2 interleaved complex doubles to N reals (distinct arrays). */
    public void inverse(double[] freqData, double[] timeData)
    {
        fft.inverse(freqData, 0, timeData, 0);
    }

    /**
     * Inverse transform between stretches of larger arrays: N + 2 interleaved
     * complex doubles at {@code freqData[freqOffset]} to N reals at
     * {@code timeData[timeOffset]}. The spectrum is left untouched.
     */
    public void inverse(double[] freqData, int freqOffset, double[] timeData, int timeOffset)
    {
        fft.inverse(freqData, freqOffset, timeData, timeOffset);
    }

    /** Number of steps {@link #inverseStep} splits the inverse into (fixed for the object). */
    public int getInverseStepCount()
    {
        return stepCount;
    }

    /**
     * Runs one step of the inverse transform.
     *
     * @param step       step index, 0 to getInverseStepCount() - 1 (0 or less reads the spectrum)
     * @param freqData   input spectrum, N + 2 doubles (read by step 0 only)
     * @param freqOffset index of the spectrum in {@code freqData}
     * @param timeData   output, N reals (written by the last step only)
     * @param timeOffset index of the first sample in {@code timeData}
     */
    public void inverseStep(int step, double[] freqData, int freqOffset, double[] timeData, int timeOffset)
    {
        if (step <= 0)
        {
            System.arraycopy(freqData, freqOffset, stepSpectrum, 0, stepSpectrum.length);
            return;
        }
        if (step == stepCount - 1)
            fft.inverse(stepSpectrum, 0, timeData, timeOffset);
    }

    /** {@link #inverseStep(int, double[], int, double[], int)} with whole arrays. */
    public void inverseStep(int step, double[] freqData, double[] timeData)
    {
        inverseStep(step, freqData, 0, timeData, 0);
    }
}
