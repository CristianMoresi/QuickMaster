package com.dspark.core;

import java.util.Arrays;

/**
 * Real-time, block-based power-of-two oversampler — a Java port of the DSPark
 * C++ {@code Oversampling} module (cascaded polyphase Kaiser half-band FIR).
 * <p>
 * Unlike {@link Oversampler} (offline, whole-buffer), this engine is
 * <b>stateful</b> and allocation-free in {@link #upsample}/{@link #downsample}:
 * it keeps per-channel filter history across blocks, so a stream processed
 * block-by-block is continuous. Intended for the playback path: upsample a
 * base-rate block to {@code N×}, run the chain at the higher rate, downsample.
 * <p>
 * Buffers are interleaved at the boundary; internally it de-interleaves per
 * channel and cascades 2× half-band stages. Causal (the FIR group delay is the
 * engine's latency), which is fine for monitoring.
 */
public final class OversamplingEngine
{
    /** Anti-aliasing filter quality: taps per 2× stage and Kaiser beta. */
    public enum Quality
    {
        LOW(31, 3.395), MEDIUM(63, 5.653), HIGH(127, 7.857), MAXIMUM(255, 10.056);
        final int taps;
        final double beta;
        Quality(int taps, double beta) { this.taps = taps; this.beta = beta; }
    }

    private int factor = 1;
    private int numStages = 0;
    private int channels = 1;
    private int maxBlockFrames = 0;
    private int latencyBaseFrames = 0;           // round-trip group delay, in base frames
    private int upsampleLatencyHiFrames = 0;     // upsample-only group delay, in high-rate frames
    private HalfBandStage[] stages = new HalfBandStage[0];
    private float[] highBuf = new float[0];      // interleaved high-rate scratch
    private float[][] chan = new float[0][];     // per-channel scratch (high-rate length)

    /**
     * Configures for a factor (power of two), channel count and max base block.
     * A non-power-of-two factor is rounded DOWN to the nearest power of two:
     * an incoherent pair (e.g. factor 3 driving one 2x stage) would emit the
     * wrong number of samples per block and overrun the scratch buffers.
     */
    public void prepare(int factor, int channels, int maxBlockFrames, Quality q)
    {
        int f = Math.max(1, Math.min(factor, 64));
        this.factor = Integer.highestOneBit(f);
        this.channels = Math.max(1, channels);
        this.maxBlockFrames = Math.max(1, maxBlockFrames);
        this.numStages = Integer.numberOfTrailingZeros(this.factor);

        stages = new HalfBandStage[numStages];
        for (int s = 0; s < numStages; s++)
        {
            int stageMaxIn = this.maxBlockFrames * (1 << s);     // base frames entering stage s
            stages[s] = new HalfBandStage(q.taps, q.beta, this.channels, stageMaxIn);
        }
        highBuf = new float[this.maxBlockFrames * this.factor * this.channels];
        chan = new float[this.channels][this.maxBlockFrames * this.factor];
        reset();

        // Measure the up→down round-trip latency once (an impulse through the
        // real stages), then clear the state that measurement left behind. This
        // lets an offline renderer align/flush an oversampled pass exactly.
        this.latencyBaseFrames = (this.factor <= 1) ? 0 : measureLatencyBaseFrames();
        reset();
        this.upsampleLatencyHiFrames = (this.factor <= 1) ? 0 : measureUpsampleLatencyHiFrames();
        reset();
    }

    public int getFactor() { return factor; }

    /**
     * Round-trip latency of an {@code upsample → downsample} pass, in base-rate
     * frames — the group delay the cascaded half-band filters introduce. Zero at
     * factor 1. An offline renderer drops this many leading output frames (and
     * feeds an equal silent tail) to keep an oversampled render time-aligned.
     */
    public int getLatencyBaseFrames() { return latencyBaseFrames; }

    /**
     * Group delay of the upsample path alone, in high-rate frames. A signal
     * entering an oversampled chain lags the source by this much, so a host
     * that indexes position-aware stages (fades, precomputed envelopes) by
     * source position should subtract it from the high-rate position it
     * reports, keeping those stages aligned with the audio they modulate.
     */
    public int getUpsampleLatencyHiFrames() { return upsampleLatencyHiFrames; }

    public void reset() { for (HalfBandStage s : stages) s.reset(); }

    /**
     * Upsamples one interleaved base block ({@code frames} frames) and returns
     * the interleaved high-rate buffer (length {@code frames*factor*channels}).
     */
    public float[] upsample(float[] in, int frames)
    {
        if (factor == 1)
        {
            System.arraycopy(in, 0, highBuf, 0, frames * channels);
            return highBuf;
        }
        for (int c = 0; c < channels; c++)
        {
            for (int i = 0; i < frames; i++) chan[c][i] = in[i * channels + c];
            int len = frames;
            for (int s = 0; s < numStages; s++) { stages[s].upsample(c, chan[c], len); len *= 2; }
        }
        int hi = frames * factor;
        for (int c = 0; c < channels; c++)
            for (int i = 0; i < hi; i++) highBuf[i * channels + c] = chan[c][i];
        return highBuf;
    }

    /**
     * Downsamples an interleaved high-rate buffer ({@code frames*factor} frames)
     * back to base rate, writing {@code frames} frames into {@code out}.
     */
    public void downsample(float[] high, int frames, float[] out)
    {
        if (factor == 1)
        {
            System.arraycopy(high, 0, out, 0, frames * channels);
            return;
        }
        int hi = frames * factor;
        for (int c = 0; c < channels; c++)
        {
            for (int i = 0; i < hi; i++) chan[c][i] = high[i * channels + c];
            int len = hi;
            for (int s = numStages - 1; s >= 0; s--) { stages[s].downsample(c, chan[c], len); len /= 2; }
            for (int i = 0; i < frames; i++) out[i * channels + c] = chan[c][i];
        }
    }

    /**
     * Pushes a unit impulse through {@code upsample → downsample}
     * and returns the frame index of the peak in the base-rate output — i.e. the
     * round-trip group delay. Scan a complete filter tail even when the caller
     * requests one-frame blocks; a short buffer must not truncate the measurement.
     */
    private int measureLatencyBaseFrames()
    {
        int n = maxBlockFrames;
        float[] in = new float[n * channels];
        in[0] = 1.0f;                       // unit impulse, channel 0, frame 0
        float[] out = new float[n * channels];
        int peakFrame = 0;
        double peak = -1.0;
        for (int start = 0; start < Quality.MAXIMUM.taps * 2; start += n)
        {
            downsample(upsample(in, n), n, out);
            for (int i = 0; i < n; i++) {
                double a = Math.abs(out[i * channels]);
                if (a > peak) { peak = a; peakFrame = start + i; }
            }
            in[0] = 0;
        }
        return peakFrame;
    }

    /** Impulse through {@code upsample} alone: the peak index is the up-path delay. */
    private int measureUpsampleLatencyHiFrames()
    {
        int n = maxBlockFrames;
        float[] in = new float[n * channels];
        in[0] = 1.0f;
        int hi = n * factor;
        int peakFrame = 0;
        double peak = -1.0;
        for (int start = 0; start < Quality.MAXIMUM.taps * 2; start += n)
        {
            float[] up = upsample(in, n);
            for (int i = 0; i < hi; i++) {
                double a = Math.abs(up[i * channels]);
                if (a > peak) { peak = a; peakFrame = start * factor + i; }
            }
            in[0] = 0;
        }
        return peakFrame;
    }

    /* --- one cascaded 2× half-band stage, stateful per channel --- */

    private static final class HalfBandStage
    {
        private final double[] evenTaps;     // even-index coeffs (polyphase up branch)
        private final double centerTap;
        private final int numEven;
        private final int nTaps;
        private final int delaySamples;
        private final double[][] upHist;     // [ch][numEven + maxIn]
        private final double[][] downEvenHist, downOddHist; // contiguous polyphase histories

        HalfBandStage(int taps, double beta, int channels, int maxInFrames)
        {
            double[] full = halfBandCoeffs(taps, beta);
            int halfOrder = (taps - 1) / 2;
            this.nTaps = taps;
            this.centerTap = full[halfOrder];
            this.delaySamples = (halfOrder + 1) / 2;
            this.numEven = (taps + 1) / 2;
            this.evenTaps = new double[numEven];
            for (int i = 0, j = 0; i < taps; i += 2, j++) evenTaps[j] = full[i];
            this.upHist = new double[channels][numEven + maxInFrames];
            this.downEvenHist = new double[channels][numEven - 1 + maxInFrames];
            this.downOddHist = new double[channels][numEven - 1 + maxInFrames];
        }

        void reset()
        {
            for (double[] h : upHist) Arrays.fill(h, 0.0);
            for (double[] h : downEvenHist) Arrays.fill(h, 0.0);
            for (double[] h : downOddHist) Arrays.fill(h, 0.0);
        }

        /** Reads {@code buf[0..len)}, writes {@code buf[0..2*len)} in place. */
        void upsample(int c, float[] buf, int len)
        {
            double[] hist = upHist[c];
            int nt = numEven;
            for (int i = 0; i < len; i++) hist[nt + i] = buf[i];
            for (int i = 0; i < len; i++)
            {
                double sum = 0.0;
                for (int k = 0; k < nt; k++) sum += evenTaps[k] * hist[i + k];
                buf[2 * i]     = (float) (sum * 2.0);
                buf[2 * i + 1] = (float) (hist[i + nt - delaySamples] * centerTap * 2.0);
            }
            System.arraycopy(hist, len, hist, 0, nt);     // carry the trailing nt samples
        }

        /** Reads {@code buf[0..len)}, writes {@code buf[0..len/2)} in place. */
        void downsample(int c, float[] buf, int len)
        {
            double[] even = downEvenHist[c], odd = downOddHist[c];
            int hLen = numEven - 1;
            int outLen = len / 2;
            for (int i = 0; i < outLen; i++) {
                even[hLen + i] = buf[2 * i];
                odd[hLen + i] = buf[2 * i + 1];
            }
            for (int n = 0; n < outLen; n++)
            {
                // All odd-index half-band taps are zero except the center.
                // Keep that center in the original accumulation order.
                int center = (nTaps - 1) / 4;
                double sum = 0;
                for (int k = 0; k <= center; k++) sum += evenTaps[k] * even[n + k];
                sum += centerTap * odd[n + center];
                for (int k = center + 1; k < numEven; k++) sum += evenTaps[k] * even[n + k];
                buf[n] = (float) sum;
            }
            System.arraycopy(even, outLen, even, 0, hLen);
            System.arraycopy(odd, outLen, odd, 0, hLen);
        }
    }

    /** Kaiser-windowed half-band low-pass (fc = 0.25), unity DC gain. */
    private static double[] halfBandCoeffs(int taps, double beta)
    {
        double[] h = new double[taps];
        int c = (taps - 1) / 2;
        double fc = 0.25;
        double i0b = besselI0(beta);
        double sum = 0.0;
        for (int nn = 0; nn < taps; nn++)
        {
            double x = nn - c;
            double sinc = (x == 0.0) ? 2.0 * fc : Math.sin(2.0 * Math.PI * fc * x) / (Math.PI * x);
            double r = (2.0 * nn / (taps - 1)) - 1.0;
            double w = besselI0(beta * Math.sqrt(Math.max(0.0, 1.0 - r * r))) / i0b;
            h[nn] = sinc * w;
            sum += h[nn];
        }
        for (int nn = 0; nn < taps; nn++) h[nn] /= sum;
        return h;
    }

    private static double besselI0(double x)
    {
        double sum = 1.0, term = 1.0, xx = x * x;
        for (int k = 1; k < 30; k++)
        {
            term *= xx / (4.0 * k * k);
            sum += term;
            if (term < 1e-12 * sum) break;
        }
        return sum;
    }
}
