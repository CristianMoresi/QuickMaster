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
    private final FFTReal fft;

    public SpectralEngine(int fftSize, int hop)
    {
        this.n = fftSize;
        this.hop = hop;
        this.fft = new FFTReal(fftSize);
        this.numBins = fft.getNumBins();
        this.win = new float[fftSize];
        for (int i = 0; i < fftSize; i++)
            win[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (fftSize - 1)));
    }

    public int getNumBins() { return numBins; }
    public int getFftSize() { return n; }
    public double binToHz(int bin, double sampleRate) { return (double) bin * sampleRate / n; }

    /** Number of STFT frames produced for a signal of the given length. */
    public int frameCount(int len)
    {
        int count = 0;
        for (int start = -(n - hop); start < len; start += hop) count++;
        return Math.max(0, count);
    }

    /** Forward STFT only: hands each frame's magnitudes to {@code fm}. */
    public void analyze(float[] x, FrameMagnitudes fm)
    {
        float[] frame = new float[n];
        float[] freq = new float[fft.getFrequencyDomainSize()];
        float[] mag = new float[numBins];
        int idx = 0;
        for (int start = -(n - hop); start < x.length; start += hop)
        {
            for (int i = 0; i < n; i++)
            {
                int s = start + i;
                frame[i] = (s >= 0 && s < x.length) ? x[s] * win[i] : 0.0f;
            }
            fft.forward(frame, freq);
            fft.computeMagnitudes(freq, mag);
            fm.accept(mag, idx++);
        }
    }

    /** Full STFT round-trip applying {@code gain} per frame; returns the processed channel. */
    public float[] render(float[] x, BinGain gain)
    {
        int len = x.length;
        float[] out = new float[len];
        double[] norm = new double[len];
        float[] frame = new float[n];
        float[] freq = new float[fft.getFrequencyDomainSize()];
        int idx = 0;
        for (int start = -(n - hop); start < len; start += hop)
        {
            for (int i = 0; i < n; i++)
            {
                int s = start + i;
                frame[i] = (s >= 0 && s < len) ? x[s] * win[i] : 0.0f;
            }
            fft.forward(frame, freq);
            gain.apply(freq, idx++);
            fft.inverse(freq, frame);
            for (int i = 0; i < n; i++)
            {
                int s = start + i;
                if (s >= 0 && s < len)
                {
                    out[s] += frame[i] * win[i];
                    norm[s] += (double) win[i] * win[i];
                }
            }
        }
        for (int s = 0; s < len; s++)
            out[s] = (norm[s] > 1e-9) ? (float) (out[s] / norm[s]) : x[s];
        return out;
    }
}
