package com.quickmaster.processing.analysis;

import com.dspark.core.FFTReal;

/**
 * Offline STFT processor with weighted overlap-add (WOLA) resynthesis.
 * <p>
 * A Hann window is applied on both analysis and synthesis with 75% overlap; the
 * output is normalised by the running sum of squared windows, so an identity
 * gain reconstructs the input exactly and the processed output is time-aligned
 * with the input (zero latency, linear phase - only magnitude is changed). The
 * same frame sequence is used by {@link #analyze} and {@link #render}, so a frame
 * index means the same thing in both.
 */
public final class SpectralEngine
{
    /** Multiplies the frequency-domain bins of one frame (in place). */
    public interface BinGain { void apply(float[] freqData, int frameIndex); }

    /** Receives each frame's magnitude spectrum during analysis. */
    public interface FrameMagnitudes { void accept(float[] magnitudes, int frameIndex); }

    private final int n;
    private final int hop;
    private final int numBins;
    private final float[] win;
    private final double[] normalization;
    private final int firstStart;
    private final FFTReal fft;

    public SpectralEngine(int fftSize, int hop)
    {
        if (fftSize < 4 || (fftSize & (fftSize - 1)) != 0 || hop < 1 || hop >= fftSize)
            throw new IllegalArgumentException("STFT needs a power-of-two FFT and an overlapping positive hop.");
        this.n = fftSize;
        this.hop = hop;
        this.fft = new FFTReal(fftSize);
        this.numBins = fft.getNumBins();
        this.win = new float[fftSize];
        for (int i = 0; i < fftSize; i++)
            win[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (fftSize - 1)));
        // Zero-padded boundary windows have the same complete overlap as the
        // interior. Its denominator is periodic, not a whole-track allocation.
        normalization = new double[hop];
        for (int i = 0; i < fftSize; i++) normalization[i % hop] += (double) win[i] * win[i];
        firstStart = -((fftSize - 1) / hop) * hop;
    }

    public int getNumBins() { return numBins; }
    public int getFftSize() { return n; }
    public double binToHz(int bin, double sampleRate) { return (double) bin * sampleRate / n; }

    /** Number of STFT frames produced for a signal of the given length. */
    public int frameCount(int len)
    {
        if (len < 0) throw new IllegalArgumentException("Negative signal length.");
        if (len == 0) return 0;
        return Math.toIntExact(((long) len - 1 - firstStart) / hop + 1);
    }

    /** Forward STFT only: hands each frame's magnitudes to {@code fm}. */
    public void analyze(float[] x, FrameMagnitudes fm)
    {
        float[] frame = new float[n];
        float[] freq = new float[fft.getFrequencyDomainSize()];
        float[] mag = new float[numBins];
        int idx = 0;
        for (long start = firstStart; x.length > 0 && start < x.length; start += hop)
        {
            checkCancelled();
            for (int i = 0; i < n; i++)
            {
                long s = start + i;
                frame[i] = (s >= 0 && s < x.length) ? x[(int) s] * win[i] : 0.0f;
            }
            fft.forward(frame, freq);
            fft.computeMagnitudes(freq, mag);
            fm.accept(mag, idx++);
        }
    }

    /** Channel-linked energy, invariant under independent channel polarity. */
    public void analyzePower(float[] interleaved, int channels, FrameMagnitudes powerConsumer)
    {
        if (channels < 1 || channels > 2 || interleaved.length % channels != 0)
            throw new IllegalArgumentException("STFT requires complete mono/stereo frames.");
        int frames = interleaved.length / channels;
        float[] frame = new float[n], freq = new float[fft.getFrequencyDomainSize()];
        float[] mag = new float[numBins], power = new float[numBins];
        int idx = 0;
        for (long start = firstStart; frames > 0 && start < frames; start += hop)
        {
            checkCancelled();
            java.util.Arrays.fill(power, 0);
            for (int c = 0; c < channels; c++)
            {
                for (int i = 0; i < n; i++)
                {
                    long s = start + i;
                    frame[i] = (s >= 0 && s < frames) ? interleaved[(int)s * channels + c] * win[i] : 0;
                }
                fft.forward(frame, freq);
                fft.computeMagnitudes(freq, mag);
                for (int k = 0; k < numBins; k++) power[k] += mag[k] * mag[k] / channels;
            }
            powerConsumer.accept(power, idx++);
        }
    }

    /** Full STFT round-trip applying {@code gain} per frame; returns the processed channel. */
    public float[] render(float[] x, BinGain gain)
    {
        int len = x.length;
        float[] out = new float[len];
        float[] frame = new float[n];
        float[] freq = new float[fft.getFrequencyDomainSize()];
        int idx = 0;
        for (long start = firstStart; len > 0 && start < len; start += hop)
        {
            checkCancelled();
            for (int i = 0; i < n; i++)
            {
                long s = start + i;
                frame[i] = (s >= 0 && s < len) ? x[(int) s] * win[i] : 0.0f;
            }
            fft.forward(frame, freq);
            gain.apply(freq, idx++);
            fft.inverse(freq, frame);
            for (int i = 0; i < n; i++)
            {
                long s = start + i;
                if (s >= 0 && s < len)
                {
                    out[(int) s] += frame[i] * win[i];
                }
            }
        }
        for (int s = 0; s < len; s++)
        {
            if ((s & 16383) == 0) checkCancelled();
            double norm = normalization[s % hop];
            out[s] = (norm > 1e-9) ? (float) (out[s] / norm) : x[s];
        }
        return out;
    }

    private static void checkCancelled()
    {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("STFT cancelled.");
    }
}
