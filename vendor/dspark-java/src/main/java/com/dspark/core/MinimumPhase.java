package com.dspark.core;

/**
 * The minimum-phase kernel of a log magnitude, by its real cepstrum: what the
 * detail filter designs its kernels with.
 * <p>
 * The inverse transform of the log magnitude (real and even) is the real
 * cepstrum; folded onto the positive quefrencies it is the cepstrum of the
 * minimum-phase filter with that magnitude, whose exponential is the
 * minimum-phase spectrum. The kernel is then cut in time and tapered.
 */
public final class MinimumPhase
{
    /** A log gain is taken no lower: -120 dB. */
    public static final double LEAST_LOG_GAIN = -13.815510557964274;

    private MinimumPhase() { }

    /**
     * The kernel, cut and tapered.
     *
     * @param fft    the transform of the design (of {@code size} points)
     * @param h      on entry the log magnitude as a real spectrum (size + 2 interleaved); overwritten
     * @param c      the kernel in time: {@code length} samples, zeros after them ({@code size} values)
     * @param size   the size of the transform
     * @param taper  {@code length} weights
     * @param length the samples kept
     */
    public static void build(FFTRealDouble fft, double[] h, double[] c, int size, double[] taper, int length)
    {
        final int bins = size / 2 + 1;
        // The inverse of the log magnitude, real and even, is the real cepstrum.
        fft.inverse(h, c);
        // Fold onto the positive quefrencies: the minimum-phase cepstrum.
        for (int q = 1; q < size / 2; q++) c[q] *= 2.0;
        java.util.Arrays.fill(c, size / 2 + 1, size, 0.0);
        fft.forward(c, h);
        // Its exponential is the minimum-phase spectrum.
        for (int k = 0; k < bins; k++)
        {
            double magnitude = Math.exp(h[2 * k]);
            double angle = h[2 * k + 1];
            h[2 * k] = magnitude * Math.cos(angle);
            h[2 * k + 1] = magnitude * Math.sin(angle);
        }
        // Cut to the room the padding leaves, with a raised-cosine tail.
        fft.inverse(h, c);
        for (int q = 0; q < length; q++) c[q] *= taper[q];
        java.util.Arrays.fill(c, length, size, 0.0);
    }
}
