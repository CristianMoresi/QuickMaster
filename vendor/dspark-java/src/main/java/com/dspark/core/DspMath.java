package com.dspark.core;

/**
 * Common DSP math helpers shared across the library: decibel/linear
 * conversions, clamping and a few numeric constants.
 * <p>
 * Decibel conversions use the amplitude convention ({@code 20·log10})
 * throughout, since the library works on amplitude samples rather than
 * power. All methods are stateless and side-effect free.
 */
public final class DspMath
{
    /** {@code 2·π}, useful for radian-frequency calculations. */
    public static final double TWO_PI = 2.0 * Math.PI;

    /**
     * Floor returned by {@link #gainToDecibels(double)} for non-positive
     * gains, standing in for negative infinity so the result stays usable
     * in UI and metering contexts.
     */
    public static final double MINUS_INFINITY_DB = -200.0;

    private DspMath()
    {
        // Utility class — not instantiable.
    }

    /**
     * Converts a decibel value to a linear amplitude factor:
     * {@code 10^(dB / 20)}.
     *
     * @param decibels  the value in dB
     * @return the equivalent linear amplitude factor
     */
    public static double decibelsToGain(double decibels)
    {
        return Math.exp(decibels * (Math.log(10.0) / 20.0));
    }

    /**
     * Converts a linear amplitude factor to decibels: {@code 20·log10(gain)}.
     *
     * @param gain  linear amplitude factor (must be ≥ 0)
     * @return the value in dB, or {@link #MINUS_INFINITY_DB} if
     *         {@code gain <= 0}
     */
    public static double gainToDecibels(double gain)
    {
        if (gain <= 0.0)
        {
            return MINUS_INFINITY_DB;
        }
        return (20.0 / Math.log(10.0)) * Math.log(gain);
    }

    /**
     * Clamps a value to the inclusive range {@code [min, max]}.
     *
     * @param value  the value to clamp
     * @param min    lower bound
     * @param max    upper bound
     * @return the clamped value
     */
    public static double clamp(double value, double min, double max)
    {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * Clamps a {@code float} sample to the normalized range
     * {@code [-1.0, +1.0]}.
     *
     * @param sample  the sample to clamp
     * @return the clamped sample
     */
    public static float clampSample(float sample)
    {
        if (sample >  1.0f) return  1.0f;
        if (sample < -1.0f) return -1.0f;
        return sample;
    }
}
