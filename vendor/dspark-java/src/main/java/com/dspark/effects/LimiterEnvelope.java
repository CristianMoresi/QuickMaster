package com.dspark.effects;

/**
 * Offline look-ahead brickwall gain envelope.
 * <p>
 * Given the whole signal in advance, computes a per-frame linear gain
 * {@code g[i]} (stereo-linked) such that {@code |x[i]| * g[i] <= threshold} for
 * every sample, with a smooth exponential <b>look-ahead attack</b> (the gain
 * ducks <i>before</i> a peak, so there are no clicks) and an exponential
 * release. Because the anticipation is baked into the envelope, applying
 * {@code g[i]} to {@code x[i]} directly is correct: the limiter has <b>zero
 * latency</b>.
 * <p>
 * The envelope is built with two single-pass slews over the instantaneous
 * brickwall gain: a backward pass that ramps the gain down ahead of each peak
 * (the look-ahead attack) and a forward pass that ramps it back up afterwards
 * (the release). Both passes only ever take the minimum against the required
 * gain, so the no-overshoot guarantee is exact regardless of the slew rates.
 */
public final class LimiterEnvelope
{
    private LimiterEnvelope() { }

    /**
     * Computes the look-ahead brickwall gain envelope.
     *
     * @param interleaved    the (band) signal, interleaved
     * @param channels       channel count
     * @param threshold      brickwall threshold (linear, &gt; 0)
     * @param attackSamples  look-ahead attack length in samples (&ge; 1)
     * @param releaseSamples release length in samples (&ge; 1)
     * @return per-frame linear gain (length = frames), every value in (0, 1]
     */
    public static float[] compute(float[] interleaved, int channels, double threshold,
                                  int attackSamples, int releaseSamples)
    {
        int frames = (channels > 0) ? interleaved.length / channels : 0;
        float[] peakMap = new float[frames];
        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            float peak = 0.0f;
            for (int c = 0; c < channels; c++)
            {
                float a = Math.abs(interleaved[base + c]);
                if (a > peak) peak = a;
            }
            peakMap[f] = peak;
        }
        return computeFromPeaks(peakMap, threshold, attackSamples, releaseSamples);
    }

    /**
     * Same as {@link #compute} but driven by a pre-computed per-frame peak map
     * (the stereo-linked, or true-peak, magnitude of each frame). Caching the
     * peak map lets a "push" control recompute the envelope instantly when the
     * threshold changes, with no re-analysis.
     *
     * @param peakMap        per-frame peak magnitude
     * @param threshold      brickwall threshold (linear, &gt; 0)
     * @param attackSamples  look-ahead attack length in samples (&ge; 1)
     * @param releaseSamples release length in samples (&ge; 1)
     * @return per-frame linear gain (length = peakMap.length), every value in (0, 1]
     */
    public static float[] computeFromPeaks(float[] peakMap, double threshold,
                                           int attackSamples, int releaseSamples)
    {
        int frames = peakMap.length;
        float[] g = new float[frames];
        if (frames == 0) return g;
        // A NaN threshold fails every comparison below and would silently
        // disable limiting; fall back to full scale.
        if (!Double.isFinite(threshold)) threshold = 1.0;
        if (threshold <= 0.0) threshold = 1e-9;

        // The backward pass may overwrite instantaneous requirements once read.
        // One double track suffices; a second full-length array is unnecessary.
        double[] a = new double[frames];
        for (int f = 0; f < frames; f++)
        {
            double peak = peakMap[f];
            a[f] = (peak > threshold) ? threshold / peak : 1.0;
        }

        int atk = Math.max(1, attackSamples);
        int rel = Math.max(1, releaseSamples);
        // Multiplicative per-sample slews spanning a wide range over the window,
        // so the ramp reaches any practical depth within it. Exponential in the
        // linear-gain domain (i.e. linear in dB) - the transparent shape.
        double atkRise = Math.exp(Math.log(1.0e4) / atk);
        double relRise = Math.exp(Math.log(1.0e4) / rel);

        // Backward pass: ramp the gain down ahead of each dip (look-ahead attack).
        for (int i = frames - 2; i >= 0; i--)
        {
            double cap = a[i + 1] * atkRise;
            a[i] = Math.min(a[i], cap);
        }

        // Forward pass: limit how fast the gain recovers (release). Drops follow
        // the already-smooth attack curve; rises are slewed.
        double prev = a[0];
        g[0] = (float) a[0];
        for (int i = 1; i < frames; i++)
        {
            double cap = prev * relRise;
            double v = Math.min(a[i], cap);
            prev = v;
            g[i] = (float) v;
        }
        return g;
    }
}
