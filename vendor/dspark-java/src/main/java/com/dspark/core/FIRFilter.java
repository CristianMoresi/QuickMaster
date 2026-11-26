package com.dspark.core;

import java.util.Arrays;

/**
 * FIR (finite impulse response) filter with windowed-sinc coefficient design.
 * <p>
 * FIR filters give a linear phase response: they do not distort the phase of
 * the signal, which makes them the tool for mastering-grade equalisation,
 * linear-phase crossovers and sample rate conversion. A symmetric kernel of
 * N taps delays every frequency by (N - 1) / 2 samples.
 * <p>
 * {@link FIRDesign} designs the coefficients (Kaiser-windowed sinc, all maths
 * in double). The filter itself is a direct-form convolution over a mirrored
 * delay line: {@link #processBlock} runs the block correlation kernel for
 * kernels up to 257 taps (the same output for any partition of the stream)
 * and a per-sample dot product for longer ones; {@link #processSample} always
 * runs the per-sample dot product. The two entry points sum in a different
 * order, so they agree to rounding, not bit for bit.
 * <p>
 * {@link #setCoefficients} takes effect at the next processing call and
 * never allocates. Not thread safe: one owner drives the whole filter.
 */
public final class FIRFilter
{
    /**
     * Static methods designing FIR coefficients by the windowed-sinc method:
     * an ideal (sinc) impulse response multiplied by a Kaiser window.
     * <p>
     * The number of taps sets the steepness and the stopband attenuation:
     * {@code N ~ 4 / (transitionWidth / sampleRate)} for a Kaiser window, so a
     * 1 kHz transition band at 48 kHz takes about 192 taps. Use an odd number
     * of taps for symmetric (type I) filters; an even or too small request is
     * raised to the next odd count of at least 3.
     */
    public static final class FIRDesign
    {
        private FIRDesign() { }

        /** The Kaiser beta the designs use when none is given. */
        public static final double DEFAULT_BETA = 5.0;

        /**
         * Low-pass FIR, unity gain at DC.
         *
         * @param sampleRate  sample rate in Hz
         * @param cutoffHz    cutoff frequency in Hz (-6 dB point)
         * @param numTaps     number of taps (odd, at least 3)
         * @param beta        Kaiser window beta; higher means more attenuation
         * @return the coefficients
         */
        public static double[] lowPass(double sampleRate, double cutoffHz, int numTaps, double beta)
        {
            numTaps = sanitizeTaps(numTaps);
            return designSinc(cutoffHz / sampleRate, numTaps, beta, false);
        }

        /** {@link #lowPass(double, double, int, double)} with beta {@value #DEFAULT_BETA}. */
        public static double[] lowPass(double sampleRate, double cutoffHz, int numTaps)
        {
            return lowPass(sampleRate, cutoffHz, numTaps, DEFAULT_BETA);
        }

        /**
         * High-pass FIR, made by spectrally inverting a low-pass: unity gain at
         * the Nyquist frequency.
         *
         * @param sampleRate  sample rate in Hz
         * @param cutoffHz    cutoff frequency in Hz
         * @param numTaps     number of taps (odd, at least 3)
         * @param beta        Kaiser window beta
         * @return the coefficients
         */
        public static double[] highPass(double sampleRate, double cutoffHz, int numTaps, double beta)
        {
            numTaps = sanitizeTaps(numTaps);
            return designSinc(cutoffHz / sampleRate, numTaps, beta, true);
        }

        /** {@link #highPass(double, double, int, double)} with beta {@value #DEFAULT_BETA}. */
        public static double[] highPass(double sampleRate, double cutoffHz, int numTaps)
        {
            return highPass(sampleRate, cutoffHz, numTaps, DEFAULT_BETA);
        }

        /**
         * Band-pass FIR: a low-pass at the high cutoff less a low-pass at the
         * low cutoff.
         *
         * @param sampleRate    sample rate in Hz
         * @param lowCutoffHz   lower cutoff frequency in Hz
         * @param highCutoffHz  upper cutoff frequency in Hz
         * @param numTaps       number of taps (odd, at least 3)
         * @param beta          Kaiser window beta
         * @return the coefficients
         */
        public static double[] bandPass(double sampleRate, double lowCutoffHz, double highCutoffHz,
                                        int numTaps, double beta)
        {
            numTaps = sanitizeTaps(numTaps);
            double[] lp1 = designSinc(highCutoffHz / sampleRate, numTaps, beta, false);
            double[] lp2 = designSinc(lowCutoffHz / sampleRate, numTaps, beta, false);
            for (int i = 0; i < numTaps; i++)
                lp1[i] -= lp2[i];
            return lp1;
        }

        /** {@link #bandPass(double, double, double, int, double)} with beta {@value #DEFAULT_BETA}. */
        public static double[] bandPass(double sampleRate, double lowCutoffHz, double highCutoffHz, int numTaps)
        {
            return bandPass(sampleRate, lowCutoffHz, highCutoffHz, numTaps, DEFAULT_BETA);
        }

        /**
         * Band-stop (notch) FIR: the spectral inversion of the band-pass.
         *
         * @param sampleRate    sample rate in Hz
         * @param lowCutoffHz   lower cutoff frequency in Hz
         * @param highCutoffHz  upper cutoff frequency in Hz
         * @param numTaps       number of taps (odd, at least 3)
         * @param beta          Kaiser window beta
         * @return the coefficients
         */
        public static double[] bandStop(double sampleRate, double lowCutoffHz, double highCutoffHz,
                                        int numTaps, double beta)
        {
            numTaps = sanitizeTaps(numTaps);
            double[] bp = bandPass(sampleRate, lowCutoffHz, highCutoffHz, numTaps, beta);
            // Spectral inversion: negate all and add 1 to the centre tap.
            int centre = numTaps / 2;
            for (int i = 0; i < numTaps; i++)
                bp[i] = -bp[i];
            bp[centre] += 1.0;
            return bp;
        }

        /** {@link #bandStop(double, double, double, int, double)} with beta {@value #DEFAULT_BETA}. */
        public static double[] bandStop(double sampleRate, double lowCutoffHz, double highCutoffHz, int numTaps)
        {
            return bandStop(sampleRate, lowCutoffHz, highCutoffHz, numTaps, DEFAULT_BETA);
        }

        /**
         * Kaiser's estimate of the odd number of taps that reaches a stopband
         * attenuation over a transition band.
         *
         * @param sampleRate     sample rate in Hz
         * @param transitionHz   width of the transition band in Hz (above 0)
         * @param attenuationDb  stopband attenuation in dB (positive, for example 60)
         * @return the estimated number of taps, odd and at least 3
         */
        public static int estimateTaps(double sampleRate, double transitionHz, double attenuationDb)
        {
            // A zero or negative transition width is floored.
            final double ratio = transitionHz / sampleRate;
            final double normTransition = (ratio < 1e-6) ? 1e-6 : ratio;
            int n = (int) Math.ceil((attenuationDb - 7.95) / (14.36 * normTransition));
            if (n < 3) n = 3;
            if (n % 2 == 0) ++n;
            return n;
        }

        /**
         * Kaiser's window parameter for a stopband attenuation.
         *
         * @param attenuationDb  attenuation in dB (positive)
         * @return the Kaiser beta
         */
        public static double estimateKaiserBeta(double attenuationDb)
        {
            if (attenuationDb > 50.0)
                return 0.1102 * (attenuationDb - 8.7);
            else if (attenuationDb >= 21.0)
                return 0.5842 * Math.pow(attenuationDb - 21.0, 0.4) + 0.07886 * (attenuationDb - 21.0);
            else
                return 0.0;
        }

        /**
         * Spectral inversion (high-pass, band-stop) breaks for even tap counts
         * (a type II FIR has a forced zero at Nyquist): the next odd count of
         * at least 3.
         */
        private static int sanitizeTaps(int numTaps)
        {
            if (numTaps < 3) numTaps = 3;
            return numTaps | 1;
        }

        /**
         * Windowed-sinc design: normalised cutoff (cutoff / rate, 0 to 0.5),
         * unity gain at DC, optionally inverted into a high-pass.
         */
        private static double[] designSinc(double normFreq, int numTaps, double beta, boolean invert)
        {
            double[] design = new double[numTaps];
            double[] window = new double[numTaps];

            WindowFunctions.kaiser(window, numTaps, beta, false);

            final int centre = numTaps / 2;
            final double fc = normFreq * 2.0;   // normalised to [0, 1] for the sinc

            // The windowed sinc and its DC sum in one pass.
            double sum = 0.0;
            for (int i = 0; i < numTaps; i++)
            {
                final int n = i - centre;
                final double x = (double) n * Math.PI;
                final double s = (n == 0) ? fc : Math.sin(fc * x) / x;
                final double c = s * window[i];
                design[i] = c;
                sum += c;
            }

            // Unity gain at DC (low-pass) or at Nyquist (high-pass).
            if (Math.abs(sum) > 1e-10)
            {
                final double invSum = 1.0 / sum;
                for (int i = 0; i < numTaps; i++) design[i] *= invSum;
            }

            if (invert)
            {
                for (int i = 0; i < numTaps; i++) design[i] = -design[i];
                design[centre] += 1.0;
            }
            return design;
        }
    }

    /** Kernel history plus one chunk of {@link #processBlock}; kernels up to BLOCK_SCRATCH / 2 + 1 taps use the block kernel. */
    private static final int BLOCK_SCRATCH = 512;

    private int maxTaps = 0;
    private int numChannels = 0;
    private boolean prepared = false;

    private double[] activeCoeffs = new double[0];   // the kernel, reversed
    private int activeTaps = 0;

    private double[] delayLineBuffer = new double[0];   // numChannels mirrored lines of 2 * maxTaps
    private int[] writePositions = new int[0];
    private final double[] lin = new double[BLOCK_SCRATCH];

    /**
     * Allocates the coefficient buffer and the delay lines, and clears the
     * kernel (the filter passes its input until coefficients are set).
     *
     * @param maxTaps      largest number of coefficients the filter will take
     * @param numChannels  number of channels it processes
     */
    public void prepare(int maxTaps, int numChannels)
    {
        this.maxTaps = Math.max(0, maxTaps);
        this.numChannels = Math.max(0, numChannels);
        activeCoeffs = new double[this.maxTaps];
        activeTaps = 0;
        delayLineBuffer = new double[this.numChannels * this.maxTaps * 2];
        writePositions = new int[this.numChannels];
        prepared = true;
    }

    /**
     * Sets the kernel. Ignored before {@link #prepare}, for an empty set and
     * for a set longer than the prepared maximum.
     *
     * @param coeffs  the coefficients, the first one meeting the newest sample
     */
    public void setCoefficients(double[] coeffs)
    {
        if (!prepared) return;
        final int numTaps = coeffs.length;
        if (numTaps == 0 || numTaps > maxTaps) return;
        // Stored reversed for the direct dot product.
        for (int k = 0; k < numTaps; k++)
            activeCoeffs[k] = coeffs[numTaps - 1 - k];
        activeTaps = numTaps;
    }

    /** Clears the delay lines (the kernel stays). */
    public void reset()
    {
        Arrays.fill(delayLineBuffer, 0.0);
        Arrays.fill(writePositions, 0);
    }

    /**
     * Filters a planar block in place. Channels beyond the prepared count
     * pass untouched.
     *
     * @param buffer      the channels
     * @param numSamples  samples per channel, from index 0
     */
    public void processBlock(double[][] buffer, int numSamples)
    {
        processBlock(buffer, 0, numSamples);
    }

    /**
     * Filters a stretch of a planar block in place.
     *
     * @param buffer      the channels
     * @param offset      first sample of the stretch in every channel
     * @param numSamples  samples per channel
     */
    public void processBlock(double[][] buffer, int offset, int numSamples)
    {
        if (!prepared || activeTaps == 0) return;
        final int channels = Math.min(buffer.length, numChannels);
        for (int ch = 0; ch < channels; ch++)
            processChannel(buffer[ch], offset, numSamples, ch);
    }

    /**
     * Filters a one-channel block in place, as channel 0.
     *
     * @param data        the samples
     * @param offset      first sample of the block
     * @param numSamples  number of samples
     */
    public void processBlock(double[] data, int offset, int numSamples)
    {
        if (!prepared || activeTaps == 0 || numChannels < 1) return;
        processChannel(data, offset, numSamples, 0);
    }

    private void processChannel(double[] data, int offset, int numSamples, int ch)
    {
        final int currentTaps = activeTaps;
        final double[] currentCoeffs = activeCoeffs;
        final double[] dl = delayLineBuffer;
        final int base = ch * (maxTaps * 2);
        int wp = writePositions[ch];

        final int hist = currentTaps - 1;
        if (hist > BLOCK_SCRATCH / 2)
        {
            for (int i = 0; i < numSamples; i++)
            {
                final double x = data[offset + i];
                dl[base + wp] = x;
                dl[base + wp + maxTaps] = x;
                data[offset + i] = dotProduct(currentCoeffs, 0, dl, base + wp + maxTaps - currentTaps + 1, currentTaps);
                if (++wp >= maxTaps) wp = 0;
            }
            writePositions[ch] = wp;
            return;
        }

        // History followed by one chunk.
        final int chunkMax = BLOCK_SCRATCH - hist;
        for (int start = 0; start < numSamples; start += chunkMax)
        {
            final int n = Math.min(chunkMax, numSamples - start);
            final int chunk = offset + start;
            // The newest (taps - 1) samples are contiguous in the mirror,
            // oldest first, ending just before the next write slot.
            System.arraycopy(dl, base + wp + maxTaps - hist, lin, 0, hist);
            System.arraycopy(data, chunk, lin, hist, n);
            for (int i = 0; i < n; i++)
            {
                final double x = data[chunk + i];
                dl[base + wp] = x;
                dl[base + wp + maxTaps] = x;
                if (++wp >= maxTaps) wp = 0;
            }
            firCorrelate(lin, 0, currentCoeffs, 0, currentTaps, data, chunk, n, 1.0);
        }
        writePositions[ch] = wp;
    }

    /**
     * Filters one sample through the per-sample dot product.
     *
     * @param input    the sample
     * @param channel  the channel (one that was not prepared passes the input)
     * @return the filtered sample
     */
    public double processSample(double input, int channel)
    {
        if (!prepared) return input;
        if (channel < 0 || channel >= numChannels) return input;

        final int currentTaps = activeTaps;
        if (currentTaps == 0) return input;

        final int base = channel * (maxTaps * 2);
        int wp = writePositions[channel];
        delayLineBuffer[base + wp] = input;
        delayLineBuffer[base + wp + maxTaps] = input;

        final double output = dotProduct(activeCoeffs, 0, delayLineBuffer, base + wp + maxTaps - currentTaps + 1, currentTaps);

        if (++wp >= maxTaps) wp = 0;
        writePositions[channel] = wp;
        return output;
    }

    /**
     * The group delay of the kernel, (taps - 1) / 2 samples (integer
     * division), or 0 without coefficients.
     *
     * @return the latency in samples
     */
    public int getLatency()
    {
        final int taps = activeTaps;
        return taps > 0 ? (taps - 1) / 2 : 0;
    }

    /**
     * Block FIR as a valid correlation: {@code y[i] = gain * sum_j h[j] * x[i + j]}
     * for i in [0, n), reading x[0 .. n + taps - 1). Each output is one
     * multiply-add chain over the taps in order, then the gain. y must not
     * overlap x or h.
     */
    static void firCorrelate(double[] x, int xOff, double[] h, int hOff, int taps,
                             double[] y, int yOff, int n, double gain)
    {
        for (int i = 0; i < n; i++)
        {
            double a = 0.0;
            final int xp = xOff + i;
            for (int j = 0; j < taps; j++)
                a = h[hOff + j] * x[xp + j] + a;
            y[yOff + i] = a * gain;
        }
    }

    /**
     * Dot product {@code sum a[i] * b[i]} in two-lane pairs: lanes 0 and 1
     * of two accumulators take the products four at a time, a remaining pair
     * goes to the first accumulator, the accumulators are added lane by lane
     * and then across, and an odd last product is added at the end.
     */
    static double dotProduct(double[] a, int aOff, double[] b, int bOff, int count)
    {
        double s00 = 0.0, s01 = 0.0, s10 = 0.0, s11 = 0.0;
        int i = 0;
        for (; i + 3 < count; i += 4)
        {
            s00 = s00 + a[aOff + i] * b[bOff + i];
            s01 = s01 + a[aOff + i + 1] * b[bOff + i + 1];
            s10 = s10 + a[aOff + i + 2] * b[bOff + i + 2];
            s11 = s11 + a[aOff + i + 3] * b[bOff + i + 3];
        }
        for (; i + 1 < count; i += 2)
        {
            s00 = s00 + a[aOff + i] * b[bOff + i];
            s01 = s01 + a[aOff + i + 1] * b[bOff + i + 1];
        }
        double sum = (s00 + s10) + (s01 + s11);
        for (; i < count; i++) sum += a[aOff + i] * b[bOff + i];
        return sum;
    }
}
