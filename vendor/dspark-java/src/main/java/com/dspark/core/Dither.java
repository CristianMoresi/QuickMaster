package com.dspark.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TPDF dithering with optional noise shaping.
 * <p>
 * When reducing bit depth (e.g. a 32-bit float master down to a 16-bit
 * WAV), naive truncation produces correlated quantization distortion.
 * Adding a Triangular Probability Density Function (TPDF) dither before
 * quantization replaces that distortion with a constant, uncorrelated
 * noise floor. The optional noise shaper feeds back the total
 * requantization error (dither + quantizer) through a filter of the past
 * errors:
 * <ul>
 *   <li>{@link Shaper#FIRST_ORDER} (default): a one-sample delay, first-order
 *       highpass shaping of the entire noise floor (about 15 dB less noise at
 *       1 kHz at 44.1 kHz, in exchange for a gentle rise towards Nyquist).</li>
 *   <li>{@link Shaper#LIPSHITZ5}: the 5-tap error filter of Lipshitz,
 *       Vanderkooy and Wannamaker, "Minimally Audible Noise Shaping"
 *       (JAES 1991), designed for 44.1 kHz, which moves the noise out of the
 *       1-5 kHz region where hearing is most sensitive.</li>
 * </ul>
 * <p>
 * Quantized output lies exactly on the integer level grid of the target
 * depth: levels in [-2^(bits-1), 2^(bits-1) - 1], so positive full scale
 * is one step below 1.0, exactly like the int16/int24 file writers.
 * <p>
 * Non-finite input is sanitised on entry (NaN becomes silence, infinities
 * clip to full scale), so a single bad sample cannot poison the error
 * feedback: before, one NaN made every later sample NaN until reset().
 * <p>
 * Not thread-safe: use one instance per processing thread. Each channel
 * keeps its own noise-shaping error state (up to {@value #MAX_CHANNELS}).
 */
public final class Dither
{
    public static final int MAX_CHANNELS = 16;

    /** Error-feedback filter used when noise shaping is enabled. */
    public enum Shaper
    {
        /** {@code (1 - z^-1)} shaping of the whole noise floor (default). */
        FIRST_ORDER,
        /** 5-tap psychoacoustic shaping for 44.1 kHz (Lipshitz et al. 1991). */
        LIPSHITZ5
    }

    private static final int MAX_ORDER = 5;

    /** Lipshitz/Vanderkooy/Wannamaker 5-tap error filter (44.1 kHz). */
    private static final double[] LIPSHITZ5 =
            { 2.033, -2.165, 1.959, -1.590, 0.6149 };

    private static final AtomicInteger SEED = new AtomicInteger(0x193A6B54);

    private int targetBits;
    private double quantScale;
    private double quantStep;
    private double loLevel;
    private double hiLevel;
    private double errorCap;
    private boolean noiseShaping;
    private Shaper shaper = Shaper.FIRST_ORDER;
    private int rngState;
    private final double[][] errorState = new double[MAX_CHANNELS][MAX_ORDER];

    public Dither()
    {
        this(16, false);
    }

    /**
     * @param targetBits    target bit depth (8 to 24; float input has 24
     *                      bits of usable precision)
     * @param noiseShaping  enable noise shaping (first order unless
     *                      {@link #setShaper} says otherwise)
     */
    public Dither(int targetBits, boolean noiseShaping)
    {
        this.noiseShaping = noiseShaping;
        // Golden-ratio increment per instance → decorrelated seeds for L/R.
        int s = SEED.getAndAdd(0x9E3779B9);
        this.rngState = (s == 0) ? 1 : s;
        setTargetBitDepth(targetBits);
    }

    /** Sets the target bit depth (clamped to 8..24) and recomputes scaling. */
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

    /** Selects the noise-shaping filter (used while shaping is enabled). */
    public void setShaper(Shaper shaper)
    {
        this.shaper = (shaper == Shaper.LIPSHITZ5) ? Shaper.LIPSHITZ5 : Shaper.FIRST_ORDER;
    }

    /** Returns the selected noise-shaping filter. */
    public Shaper getShaper() { return shaper; }

    /** Clears the noise-shaping error feedback. Call on stop/flush. */
    public void reset()
    {
        for (double[] h : errorState) java.util.Arrays.fill(h, 0.0);
    }

    /**
     * Dithers and quantizes one sample.
     *
     * @param input    sample in {@code [-1, +1]} (NaN is treated as silence)
     * @param channel  channel index for independent noise shaping
     * @return the dithered, quantized sample
     */
    public float processSample(float input, int channel)
    {
        int order = 0;
        if (noiseShaping && channel >= 0 && channel < MAX_CHANNELS)
            order = (shaper == Shaper.LIPSHITZ5) ? MAX_ORDER : 1;
        return (float) processSampleInternal(input, channel, order);
    }

    private double processSampleInternal(double input, int channel, int order)
    {
        // Sanitise on entry: NaN would pass every clamp below and park in the
        // error feedback for good. Values beyond +-2 quantise exactly like
        // +-2 (the level clamp and the error cap both bind), so clamping
        // there changes no output, and it turns infinities into full scale.
        if (Double.isNaN(input)) input = 0.0;
        if (input < -2.0) input = -2.0;
        else if (input > 2.0) input = 2.0;

        // 2-LSB peak-to-peak TPDF noise (sum of two uniform PRNG draws).
        double noise = (nextRandom() + nextRandom()) * quantStep;

        double pre = input;
        if (order == 1)
        {
            pre -= errorState[channel][0];
        }
        else if (order == MAX_ORDER)
        {
            double[] e = errorState[channel];
            pre -= LIPSHITZ5[0] * e[0] + LIPSHITZ5[1] * e[1] + LIPSHITZ5[2] * e[2]
                 + LIPSHITZ5[3] * e[3] + LIPSHITZ5[4] * e[4];
        }

        double dithered = pre + noise;

        // Quantize on the integer level grid and clamp to the representable
        // range of the target depth: levels in [-2^(bits-1), 2^(bits-1) - 1],
        // so positive full scale is one step below 1.0, exactly like the
        // int16/int24 file writers.
        double level = Math.rint(dithered * quantScale);
        if (level < loLevel) level = loLevel;
        if (level > hiLevel) level = hiLevel;
        double quantised = level * quantStep;

        if (order > 0)
        {
            // Feed back the TOTAL requantization error e[n] = y[n] - v[n]
            // (dither + quantizer), the classic error-feedback structure
            // with the dither inside the loop: the whole noise floor is
            // shaped, not just the quantizer error.
            // The cap bounds the loop when the level clamp engages (input at
            // or beyond positive full scale): without it the clip error
            // accumulates sample after sample and pins the output at full
            // scale long after the overload has passed.
            double err = quantised - pre;
            if (err > errorCap) err = errorCap;
            else if (err < -errorCap) err = -errorCap;
            double[] e = errorState[channel];
            if (order == MAX_ORDER)
            {
                e[4] = e[3]; e[3] = e[2]; e[2] = e[1]; e[1] = e[0];
            }
            e[0] = err;
        }
        return quantised;
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
