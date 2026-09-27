package com.dspark.core;

/**
 * Offline power-of-two oversampler — a Java port of the filter design from the
 * DSPark C++ {@code Oversampling} module (Kaiser-windowed half-band FIR).
 * <p>
 * Upsample a whole interleaved buffer to {@code N×}, run nonlinear processing
 * (compression, limiting) at the higher rate, then downsample — which removes
 * the aliasing those stages would otherwise fold back into the audio band.
 * <p>
 * This is the <b>offline</b> (whole-buffer) variant used for rendering/export:
 * it convolves directly rather than using the C++ block-based polyphase engine,
 * but with the same half-band Kaiser filter, and it exploits the half-band
 * structure (half of the taps are exactly zero) to skip those taps. The
 * symmetric FIR is applied zero-phase, so an up→down round-trip is time-aligned.
 */
public final class Oversampler
{
    /** Anti-aliasing filter quality: taps per 2× stage and Kaiser beta. */
    public enum Quality
    {
        LOW(31, 3.395), MEDIUM(63, 5.653), HIGH(127, 7.857), MAXIMUM(255, 10.056);
        final int taps;
        final double beta;
        Quality(int taps, double beta) { this.taps = taps; this.beta = beta; }
    }

    private Oversampler() { }

    /**
     * Upsamples an interleaved buffer by a power-of-two {@code factor}.
     * A non-power-of-two factor is rounded DOWN to the nearest power of two
     * so the stage count and the output length stay coherent.
     */
    public static float[] upsample(float[] interleaved, int channels, int factor, Quality q)
    {
        factor = normalizeFactor(factor);
        if (factor <= 1) return interleaved.clone();
        Tap fir = halfBand(q.taps, q.beta);
        int frames = interleaved.length / channels;
        int outFrames = frames * factor;
        float[] out = new float[outFrames * channels];
        int stages = Integer.numberOfTrailingZeros(factor);
        for (int c = 0; c < channels; c++)
        {
            float[] mono = deinterleave(interleaved, channels, c, frames);
            for (int s = 0; s < stages; s++) mono = up2x(mono, fir);
            for (int i = 0; i < outFrames; i++) out[i * channels + c] = mono[i];
        }
        return out;
    }

    /** Downsamples an interleaved buffer by a power-of-two {@code factor}. */
    public static float[] downsample(float[] interleaved, int channels, int factor, Quality q)
    {
        factor = normalizeFactor(factor);
        if (factor <= 1) return interleaved.clone();
        Tap fir = halfBand(q.taps, q.beta);
        int frames = interleaved.length / channels;
        int outFrames = frames / factor;
        float[] out = new float[outFrames * channels];
        int stages = Integer.numberOfTrailingZeros(factor);
        for (int c = 0; c < channels; c++)
        {
            float[] mono = deinterleave(interleaved, channels, c, frames);
            for (int s = 0; s < stages; s++) mono = down2x(mono, fir);
            for (int i = 0; i < outFrames; i++) out[i * channels + c] = mono[i];
        }
        return out;
    }

    /* --- internals --- */

    /**
     * Coherent factor normalization: clamp into [1, 64] and round DOWN to a
     * power of two, so the stage count always matches the length scaling.
     */
    private static int normalizeFactor(int factor)
    {
        int f = Math.max(1, Math.min(factor, 64));
        return Integer.highestOneBit(f);
    }

    /** Nonzero half-band taps: centred offsets and (already gain-scaled) values. */
    private record Tap(int[] offset, double[] value) { }

    private static float[] deinterleave(float[] in, int channels, int c, int frames)
    {
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) mono[i] = in[i * channels + c];
        return mono;
    }

    private static float[] up2x(float[] in, Tap fir)
    {
        int n = in.length;
        float[] stuffed = new float[n * 2];
        for (int i = 0; i < n; i++) stuffed[2 * i] = in[i];     // zero-stuff
        return convolve(stuffed, fir, 2.0);                     // ×2 restores the level
    }

    private static float[] down2x(float[] in, Tap fir)
    {
        float[] filtered = convolve(in, fir, 1.0);
        int m = in.length / 2;
        float[] out = new float[m];
        for (int i = 0; i < m; i++) out[i] = filtered[2 * i];   // decimate
        return out;
    }

    /** Zero-phase convolution with the nonzero half-band taps. */
    private static float[] convolve(float[] x, Tap fir, double gain)
    {
        int n = x.length;
        int[] off = fir.offset();
        double[] val = fir.value();
        float[] y = new float[n];
        for (int i = 0; i < n; i++)
        {
            double s = 0.0;
            for (int t = 0; t < off.length; t++)
            {
                int idx = i + off[t];
                if (idx >= 0 && idx < n) s += val[t] * x[idx];
            }
            y[i] = (float) (s * gain);
        }
        return y;
    }

    /** Designs a Kaiser-windowed half-band (fc = 0.25) and keeps the nonzero taps. */
    private static Tap halfBand(int taps, double beta)
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
        // Keep only the meaningful taps (a half-band has ~half its taps == 0).
        int nz = 0;
        for (double v : h) if (Math.abs(v) > 1e-9) nz++;
        int[] off = new int[nz];
        double[] val = new double[nz];
        int j = 0;
        for (int k = 0; k < taps; k++)
            if (Math.abs(h[k]) > 1e-9) { off[j] = k - c; val[j] = h[k] / sum; j++; }   // unity DC gain
        return new Tap(off, val);
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
