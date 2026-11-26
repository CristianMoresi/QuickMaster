package com.dspark.effects;

/**
 * Mid/Side stereo encoding and decoding.
 * <p>
 * Converts between Left/Right and Mid/Side representations, for stereo
 * processing that needs independent control over the centre image (mid) and
 * the stereo width (side). Static operations need no prepare() or reset()
 * and add no delay. The caller owns the buffers.
 * <p>
 * Convention: M = (L + R) / 2, S = (L - R) / 2 on encode; L = M + S,
 * R = M - S on decode. The 0.5 lives on the encode side so a full-scale
 * correlated (mono) signal cannot clip the mid channel; decode() is the
 * exact algebraic inverse of encode(). The round trip is mathematically
 * lossless; in floating point it is exact to within one rounding of the
 * inner sums (bit-exact whenever l+r and l-r round exactly, e.g. silence or
 * equal channels).
 * <p>
 * Buffers with fewer than two channels are left untouched; channels beyond
 * the first two are ignored.
 * <p>
 * Threading: stateless and re-entrant; safe to call from any thread on
 * disjoint buffers.
 */
public final class MidSide
{
    private MidSide()
    {
        // Utility class, not instantiable.
    }

    /**
     * Encodes one sample frame from Left/Right to Mid/Side in place:
     * left[index] becomes the mid and right[index] the side. Bit-identical
     * to {@link #encode(double[][], int)} applied to the same frame.
     *
     * @param left   left samples (the frame's sample becomes mid)
     * @param right  right samples (the frame's sample becomes side)
     * @param index  index of the frame
     */
    public static void encodeSample(double[] left, double[] right, int index)
    {
        final double l = left[index];
        final double r = right[index];
        left[index] = (l + r) * 0.5;
        right[index] = (l - r) * 0.5;
    }

    /**
     * Decodes one sample frame from Mid/Side to Left/Right in place:
     * mid[index] becomes the left and side[index] the right. Bit-identical
     * to {@link #decode(double[][], int)} applied to the same frame.
     *
     * @param mid    mid samples (the frame's sample becomes left)
     * @param side   side samples (the frame's sample becomes right)
     * @param index  index of the frame
     */
    public static void decodeSample(double[] mid, double[] side, int index)
    {
        final double m = mid[index];
        final double s = side[index];
        mid[index] = m + s;
        side[index] = m - s;
    }

    /**
     * Encodes a stereo buffer from Left/Right to Mid/Side in place:
     * M = (L + R) * 0.5, S = (L - R) * 0.5. The 0.5 scaling preserves peak
     * amplitude (unity round-trip gain) and avoids clipping when summing
     * highly correlated signals.
     *
     * @param channels    planar channels; channel 0 becomes mid, 1 side.
     *                    Fewer than two channels: no-op. The two channels
     *                    must be distinct arrays.
     * @param numSamples  samples per channel (non-positive: no-op)
     */
    public static void encode(double[][] channels, int numSamples)
    {
        if (channels == null || channels.length < 2 || numSamples <= 0) return;

        final double[] left = channels[0];
        final double[] right = channels[1];
        for (int i = 0; i < numSamples; ++i)
        {
            final double l = left[i];
            final double r = right[i];
            left[i] = (l + r) * 0.5;   // Mid
            right[i] = (l - r) * 0.5;  // Side
        }
    }

    /**
     * Decodes a stereo buffer from Mid/Side back to Left/Right in place:
     * L = M + S, R = M - S.
     *
     * @param channels    planar channels holding M/S; channel 0 becomes left,
     *                    1 right. Fewer than two channels: no-op. The two
     *                    channels must be distinct arrays.
     * @param numSamples  samples per channel (non-positive: no-op)
     */
    public static void decode(double[][] channels, int numSamples)
    {
        if (channels == null || channels.length < 2 || numSamples <= 0) return;

        final double[] mid = channels[0];
        final double[] side = channels[1];
        for (int i = 0; i < numSamples; ++i)
        {
            final double m = mid[i];
            final double s = side[i];
            mid[i] = m + s;    // Left
            side[i] = m - s;   // Right
        }
    }

    /**
     * Single-precision {@link #encodeSample(double[], double[], int)}, with
     * the arithmetic done in float.
     */
    public static void encodeSample(float[] left, float[] right, int index)
    {
        final float l = left[index];
        final float r = right[index];
        left[index] = (l + r) * 0.5f;
        right[index] = (l - r) * 0.5f;
    }

    /**
     * Single-precision {@link #decodeSample(double[], double[], int)}, with
     * the arithmetic done in float.
     */
    public static void decodeSample(float[] mid, float[] side, int index)
    {
        final float m = mid[index];
        final float s = side[index];
        mid[index] = m + s;
        side[index] = m - s;
    }

    /** Single-precision {@link #encode(double[][], int)}, with the arithmetic done in float. */
    public static void encode(float[][] channels, int numSamples)
    {
        if (channels == null || channels.length < 2 || numSamples <= 0) return;

        final float[] left = channels[0];
        final float[] right = channels[1];
        for (int i = 0; i < numSamples; ++i)
        {
            final float l = left[i];
            final float r = right[i];
            left[i] = (l + r) * 0.5f;
            right[i] = (l - r) * 0.5f;
        }
    }

    /** Single-precision {@link #decode(double[][], int)}, with the arithmetic done in float. */
    public static void decode(float[][] channels, int numSamples)
    {
        if (channels == null || channels.length < 2 || numSamples <= 0) return;

        final float[] mid = channels[0];
        final float[] side = channels[1];
        for (int i = 0; i < numSamples; ++i)
        {
            final float m = mid[i];
            final float s = side[i];
            mid[i] = m + s;
            side[i] = m - s;
        }
    }
}
