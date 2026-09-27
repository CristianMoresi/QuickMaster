package com.dspark.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TPDF dithering with optional first-order noise shaping.
 * <p>
 * When reducing bit depth (e.g. a 32-bit float master down to a 16-bit
 * WAV), naive truncation produces correlated quantization distortion.
 * Adding a Triangular Probability Density Function (TPDF) dither before
 * quantization replaces that distortion with a constant, uncorrelated
 * noise floor. The optional noise shaper feeds back the total
 * requantization error (dither + quantizer) through a one-sample delay,
 * first-order highpass shaping the entire noise floor toward high
 * frequencies where the ear is less sensitive.
 * <p>
 * Quantized output lies exactly on the integer level grid of the target
 * depth: levels in [-2^(bits-1), 2^(bits-1) - 1], so positive full scale
 * is one step below 1.0, exactly like the int16/int24 file writers.
 * <p>
 * Not thread-safe: use one instance per processing thread. Each channel
 * keeps its own noise-shaping error state (up to {@value #MAX_CHANNELS}).
 */
public final class Dither
{
    public static final int MAX_CHANNELS = 16;

    private static final AtomicInteger SEED = new AtomicInteger(0x193A6B54);

    private int targetBits;
    private double quantScale;
    private double quantStep;
    private double loLevel;
    private double hiLevel;
    private double errorCap;
    private boolean noiseShaping;
    private int rngState;
    private final double[] errorState = new double[MAX_CHANNELS];

    public Dither()
    {
        this(16, false);
    }

    /**
     * @param targetBits    target bit depth (8–24; float input has 24
     *                      bits of usable precision)
     * @param noiseShaping  enable first-order noise shaping
     */
    public Dither(int targetBits, boolean noiseShaping)
    {
        this.noiseShaping = noiseShaping;
        // Golden-ratio increment per instance → decorrelated seeds for L/R.
        int s = SEED.getAndAdd(0x9E3779B9);
        this.rngState = (s == 0) ? 1 : s;
        setTargetBitDepth(targetBits);
    }

    /** Sets the target bit depth (clamped to 8–24) and recomputes scaling. */
    public void setTargetBitDepth(int bits)
    {
        targetBits = Math.max(8, Math.min(24, bits));
        // Levels per unit: 2^(N-1). The representable grid is asymmetric,
        // [-levels, levels - 1], mirroring the signed-integer formats.
        long levels = 1L << (targetBits - 1);
        quantScale = (double) levels;
        quantStep = 1.0 / quantScale;
        loLevel = -quantScale;
        hiLevel = quantScale - 1.0;
        // Feedback cap: a legitimate shaping error never exceeds 1.5 LSB
        // (TPDF within (-1, 1] LSB + quantizer within +-0.5 LSB); see
        // processSample for why the cap is needed at all.
        errorCap = 2.0 * quantStep;
    }

    public int getTargetBitDepth() { return targetBits; }

    public double getQuantisationStep() { return quantStep; }

    public void setNoiseShaping(boolean enabled) { this.noiseShaping = enabled; }

    /** Clears the noise-shaping error feedback. Call on stop/flush. */
    public void reset()
    {
        java.util.Arrays.fill(errorState, 0.0);
    }

    /**
     * Dithers and quantizes one sample.
     *
     * @param input    sample in {@code [-1, +1]}
     * @param channel  channel index for independent noise shaping
     * @return the dithered, quantized sample
     */
    public float processSample(float input, int channel)
    {
        // 2-LSB peak-to-peak TPDF noise (sum of two uniform PRNG draws).
        double noise = (nextRandom() + nextRandom()) * quantStep;

        double pre = input;
        boolean shape = noiseShaping && channel >= 0 && channel < MAX_CHANNELS;
        if (shape) pre -= errorState[channel];

        double dithered = pre + noise;

        // Quantize on the integer level grid and clamp to the representable
        // range of the target depth: levels in [-2^(bits-1), 2^(bits-1) - 1],
        // so positive full scale is one step below 1.0, exactly like the
        // int16/int24 file writers.
        double level = Math.rint(dithered * quantScale);
        if (level < loLevel) level = loLevel;
        if (level > hiLevel) level = hiLevel;
        double quantised = level * quantStep;

        if (shape)
        {
            // Feed back the TOTAL requantization error e[n] = y[n] - v[n]
            // (dither + quantizer), the classic error-feedback structure
            // with the dither inside the loop: the whole noise floor is
            // shaped by (1 - z^-1), not just the quantizer error.
            // The cap bounds the loop when the level clamp engages (input at
            // or beyond positive full scale): without it the clip error
            // accumulates sample after sample and pins the output at full
            // scale long after the overload has passed.
            double err = quantised - pre;
            if (err > errorCap) err = errorCap;
            if (err < -errorCap) err = -errorCap;
            errorState[channel] = err;
        }
        return (float) quantised;
    }

    /**
     * Dithers an interleaved buffer in place, applying independent noise
     * shaping per channel.
     *
     * @param buffer    interleaved samples
     * @param channels  number of channels
     */
    public void processInterleaved(float[] buffer, int channels)
    {
        for (int i = 0; i < buffer.length; i += channels)
        {
            for (int ch = 0; ch < channels; ch++)
            {
                buffer[i + ch] = processSample(buffer[i + ch], ch);
            }
        }
    }

    /** Xorshift32 PRNG, returning a uniform value in {@code [-0.5, 0.5)}. */
    private double nextRandom()
    {
        rngState ^= rngState << 13;
        rngState ^= rngState >>> 17;
        rngState ^= rngState << 5;
        return ((rngState & 0xFFFFFFFFL) / 4294967295.0) - 0.5;
    }
}
