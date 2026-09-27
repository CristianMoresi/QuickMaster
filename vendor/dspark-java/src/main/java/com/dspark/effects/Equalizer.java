package com.dspark.effects;

import com.dspark.core.Biquad;
import com.dspark.core.BiquadCoeffs;
import com.dspark.core.FFTReal;
import com.dspark.core.WindowFunctions;

/**
 * Multi-band parametric equalizer with minimum-phase (IIR) and
 * linear-phase (FFT) modes.
 * <p>
 * In minimum-phase mode each band is a single biquad (peak, shelf, tilt,
 * low/high-pass or notch) — zero latency, the natural choice for tracking
 * and quick tone shaping. In linear-phase mode the combined magnitude
 * response of all bands is realized as a symmetric FIR applied by
 * overlap-save FFT convolution, so the phase is left untouched (no
 * smearing of transients) at the cost of {@code maxBlockSize} samples of
 * latency.
 * <p>
 * The {@link #getMagnitudeResponse} helper returns the combined curve for
 * drawing the EQ in a UI.
 */
public final class Equalizer
{
    public enum FilterMode { MINIMUM_PHASE, LINEAR_PHASE }

    public enum BandType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, NOTCH, TILT }

    /** Mutable per-band configuration. */
    public static final class Band
    {
        public BandType type = BandType.PEAK;
        public double frequency = 1000.0;
        public double gainDb = 0.0;
        public double q = 0.707;
        public boolean enabled = true;
    }

    private final int maxBands;
    private final Band[] configs;
    private Biquad[] biquads;
    private volatile int numBands = 0;

    private double sampleRate = 48000.0;
    private int maxBlockSize = 0;
    private int channels = 2;
    private volatile FilterMode mode = FilterMode.MINIMUM_PHASE;

    // Linear-phase state.
    private FFTReal lpFft;
    private int lpFftSize = 0;
    private int kernelLength = 0;       // M = 2 * maxBlockSize
    private float[] lpKernel;           // frequency-domain FIR (N+2)
    private float[] lpFftIn, lpFftOut;
    private float[][] lpPrev;           // per-channel overlap history
    private float[] lpTempFreq, lpImpulse, lpKernelSpace;
    private double[] lpWindow;
    private volatile boolean lpDirty = true;

    public Equalizer(int maxBands)
    {
        this.maxBands = Math.max(1, maxBands);
        this.configs = new Band[this.maxBands];
        for (int i = 0; i < this.maxBands; i++) configs[i] = new Band();
    }

    /**
     * Prepares the EQ.
     *
     * @param sampleRate    sample rate in Hz
     * @param maxBlockSize  maximum frames per {@link #process} call (sets
     *                      linear-phase FFT sizing and latency); pass 0 to
     *                      use minimum-phase only
     * @param channels      channel count
     */
    public void prepare(double sampleRate, int maxBlockSize, int channels)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        this.maxBlockSize = maxBlockSize;
        this.channels = Math.max(1, channels);

        biquads = new Biquad[maxBands];
        for (int i = 0; i < maxBands; i++)
        {
            biquads[i] = new Biquad(this.channels);
            biquads[i].setCoeffs(coeffsFor(configs[i]));
        }

        if (maxBlockSize > 0)
        {
            int target = maxBlockSize * 4;
            int n = 1;
            while (n < target) n <<= 1;
            lpFftSize = n;
            kernelLength = maxBlockSize * 2;
            lpFft = new FFTReal(lpFftSize);
            lpKernel = new float[lpFftSize + 2];
            lpFftIn = new float[lpFftSize];
            lpFftOut = new float[lpFftSize + 2];
            lpTempFreq = new float[lpFftSize + 2];
            lpImpulse = new float[lpFftSize];
            lpKernelSpace = new float[lpFftSize];
            lpWindow = new double[kernelLength];
            WindowFunctions.blackmanHarris(lpWindow, kernelLength);
            lpPrev = new float[this.channels][kernelLength];
            lpDirty = true;
        }
    }

    /**
     * Configures a band (and recomputes its biquad coefficients). Non-finite
     * parameter values keep the band's previous value for that field (a NaN
     * would zero the linear-phase kernel to silence).
     */
    public void setBand(int index, BandType type, double frequency, double gainDb, double q, boolean enabled)
    {
        if (index < 0 || index >= maxBands) return;
        Band b = configs[index];
        if (type != null) b.type = type;
        if (Double.isFinite(frequency)) b.frequency = frequency;
        if (Double.isFinite(gainDb)) b.gainDb = gainDb;
        if (Double.isFinite(q)) b.q = q;
        b.enabled = enabled;
        if (index >= numBands) numBands = index + 1;
        if (biquads != null) biquads[index].setCoeffs(coeffsFor(b));
        lpDirty = true;
    }

    /** Enables or disables a band without changing its parameters. */
    public void setBandEnabled(int index, boolean enabled)
    {
        if (index < 0 || index >= maxBands) return;
        configs[index].enabled = enabled;
        lpDirty = true;
    }

    public Band getBand(int index)
    {
        return (index >= 0 && index < maxBands) ? configs[index] : null;
    }

    public int getNumBands() { return numBands; }

    public void setFilterMode(FilterMode mode)
    {
        this.mode = mode;
        if (mode == FilterMode.LINEAR_PHASE) lpDirty = true;
    }

    public FilterMode getFilterMode() { return mode; }

    /** Latency in samples (0 for minimum phase, maxBlockSize for linear phase). */
    public int getLatency()
    {
        return (mode == FilterMode.LINEAR_PHASE) ? maxBlockSize : 0;
    }

    /** Clears all filter state. */
    public void reset()
    {
        if (biquads != null) for (Biquad b : biquads) b.reset();
        if (lpPrev != null) for (float[] p : lpPrev) java.util.Arrays.fill(p, 0.0f);
    }

    /**
     * Processes an interleaved buffer in place.
     *
     * @param buffer    interleaved samples
     * @param channels  number of channels
     */
    public void process(float[] buffer, int channels)
    {
        if (mode == FilterMode.LINEAR_PHASE && lpFft != null)
        {
            if (lpDirty) { recomputeLinearPhaseKernel(); lpDirty = false; }
            processLinearPhase(buffer, channels);
            return;
        }
        int active = numBands;
        for (int i = 0; i < active; i++)
        {
            if (configs[i].enabled) biquads[i].processBlock(buffer, channels);
        }
    }

    /**
     * Fills {@code magnitudes} with the combined linear magnitude response
     * of all enabled bands at the given frequencies (for drawing the curve).
     */
    public void getMagnitudeResponse(double[] frequencies, double[] magnitudes)
    {
        java.util.Arrays.fill(magnitudes, 0, frequencies.length, 1.0);
        int active = numBands;
        for (int i = 0; i < active; i++)
        {
            if (!configs[i].enabled) continue;
            BiquadCoeffs c = coeffsFor(configs[i]);
            for (int k = 0; k < frequencies.length; k++)
            {
                magnitudes[k] *= c.magnitude(frequencies[k], sampleRate);
            }
        }
    }

    /* ====================================================================
     *  Internals
     * ==================================================================== */

    private BiquadCoeffs coeffsFor(Band b)
    {
        switch (b.type)
        {
            case PEAK:       return BiquadCoeffs.peak(sampleRate, b.frequency, b.q, b.gainDb);
            case LOW_SHELF:  return BiquadCoeffs.lowShelf(sampleRate, b.frequency, b.gainDb, 1.0);
            case HIGH_SHELF: return BiquadCoeffs.highShelf(sampleRate, b.frequency, b.gainDb, 1.0);
            case LOW_PASS:   return BiquadCoeffs.lowPass(sampleRate, b.frequency, b.q);
            case HIGH_PASS:  return BiquadCoeffs.highPass(sampleRate, b.frequency, b.q);
            case NOTCH:      return BiquadCoeffs.notch(sampleRate, b.frequency, b.q);
            case TILT:       return BiquadCoeffs.tilt(sampleRate, b.frequency, b.gainDb);
            default:         return BiquadCoeffs.IDENTITY;
        }
    }

    private void recomputeLinearPhaseKernel()
    {
        int numBins = lpFftSize / 2 + 1;

        // 1. Combined magnitude of all enabled bands.
        java.util.Arrays.fill(lpTempFreq, 0.0f);
        int active = numBands;
        for (int k = 0; k < numBins; k++)
        {
            double freq = sampleRate * k / lpFftSize;
            double mag = 1.0;
            for (int b = 0; b < active; b++)
            {
                if (configs[b].enabled) mag *= coeffsFor(configs[b]).magnitude(freq, sampleRate);
            }
            lpTempFreq[2 * k] = (float) mag;   // zero phase: imag = 0
        }

        // 2. IFFT to a (wrapped) zero-phase impulse response.
        lpFft.inverse(lpTempFreq, lpImpulse);

        // 3. Shift to causal, window, zero-pad.
        int m = kernelLength;
        int halfM = m / 2;
        java.util.Arrays.fill(lpKernelSpace, 0.0f);
        for (int i = 0; i < m; i++)
        {
            int readIdx = ((i - halfM) % lpFftSize + lpFftSize) % lpFftSize;
            lpKernelSpace[i] = (float) (lpImpulse[readIdx] * lpWindow[i]);
        }

        // 4. Kernel back to the frequency domain for overlap-save.
        lpFft.forward(lpKernelSpace, lpKernel);
    }

    private void processLinearPhase(float[] buffer, int channels)
    {
        int nCh = Math.min(channels, this.channels);
        int l = buffer.length / channels;
        if (l > maxBlockSize) return;        // safety: block exceeds prepared size

        int n = lpFftSize;
        int m = kernelLength;
        int overlap = m - 1;
        int offset = m - 1;
        int numBins = n / 2 + 1;

        for (int ch = 0; ch < nCh; ch++)
        {
            float[] prev = lpPrev[ch];

            for (int i = 0; i < overlap; i++) lpFftIn[i] = prev[i];
            for (int i = 0; i < l; i++) lpFftIn[overlap + i] = buffer[i * channels + ch];
            for (int i = overlap + l; i < n; i++) lpFftIn[i] = 0.0f;

            for (int i = 0; i < overlap; i++) prev[i] = lpFftIn[l + i];

            lpFft.forward(lpFftIn, lpFftOut);

            for (int k = 0; k < numBins; k++)
            {
                float re = lpFftOut[2 * k];
                float im = lpFftOut[2 * k + 1];
                float hr = lpKernel[2 * k];
                float hi = lpKernel[2 * k + 1];
                lpFftOut[2 * k] = re * hr - im * hi;
                lpFftOut[2 * k + 1] = re * hi + im * hr;
            }

            lpFft.inverse(lpFftOut, lpFftIn);

            for (int i = 0; i < l; i++) buffer[i * channels + ch] = lpFftIn[offset + i];
        }
    }
}
