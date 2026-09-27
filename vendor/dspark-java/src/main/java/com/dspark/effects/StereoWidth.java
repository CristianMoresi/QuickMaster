package com.dspark.effects;

/**
 * Stereo-width control via Mid/Side processing, with an optional
 * phase-compensated bass-mono crossover.
 * <p>
 * Width 0 collapses the signal to mono, 1 leaves it unchanged, and
 * values above 1 widen it. When bass-mono is enabled, low frequencies
 * below the cutoff are summed to mono (a common mastering move to keep
 * the low end tight and vinyl-safe); a matching all-pass on the Mid
 * channel keeps it phase-aligned with the high-passed Side so the centre
 * image stays intact.
 */
public final class StereoWidth
{
    private static final double ANTI_DENORMAL = 1e-15;

    private double sampleRate = 48000.0;

    private volatile double width = 1.0;
    private volatile boolean bassMono = false;
    private volatile double bassMonoCutoff = 100.0;
    private volatile double bassMonoCoeff = 0.0;

    private double sideState = 0.0;
    private double midState = 0.0;

    /**
     * Prepares the processor for the given sample rate and resets state.
     * Non-finite or non-positive rates are ignored.
     */
    public void prepare(double sampleRate)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        updateBassMonoCoeff(bassMonoCutoff);
        reset();
    }

    /**
     * Sets the width: 0 = mono, 1 = unchanged, &gt;1 = wider. Non-finite
     * values are ignored (a NaN would silently poison the image).
     */
    public void setWidth(double width)
    {
        if (Double.isFinite(width)) this.width = Math.max(0.0, width);
    }

    public double getWidth() { return width; }

    /**
     * Enables/disables bass-mono and sets its crossover frequency.
     *
     * @param enabled   true to collapse lows to mono
     * @param cutoffHz  crossover frequency in Hz
     */
    public void setBassMono(boolean enabled, double cutoffHz)
    {
        // A non-finite cutoff would poison the side filter state permanently;
        // keep the previous frequency and only honour the on/off switch.
        if (Double.isFinite(cutoffHz) && cutoffHz > 0.0)
        {
            this.bassMonoCutoff = cutoffHz;
            updateBassMonoCoeff(cutoffHz);
        }
        this.bassMono = enabled;
    }

    public boolean isBassMono() { return bassMono; }

    public double getBassMonoCutoff() { return bassMonoCutoff; }

    /** Clears the crossover filter state. */
    public void reset()
    {
        sideState = 0.0;
        midState = 0.0;
    }

    /**
     * Processes an interleaved buffer in place. Non-stereo input passes
     * through unchanged (there is no side signal to widen).
     *
     * @param buffer    interleaved samples
     * @param channels  number of channels
     */
    public void process(float[] buffer, int channels)
    {
        if (channels != 2) return;

        final double w = width;
        if (bassMono)
        {
            final double coeff = bassMonoCoeff;
            for (int i = 0; i < buffer.length; i += 2)
            {
                double l = buffer[i];
                double r = buffer[i + 1];

                double mid = (l + r) * 0.5;
                double side = (l - r) * 0.5 * w;

                // Side: 1-pole high-pass (keep only the highs in the sides).
                double sideHp = side - sideState;
                sideState += coeff * sideHp + ANTI_DENORMAL;
                sideState -= ANTI_DENORMAL;
                side = sideHp;

                // Mid: 1-pole all-pass (low-pass minus high-pass) to match
                // the Side filter's phase, preserving the centre image.
                double midHp = mid - midState;
                midState += coeff * midHp + ANTI_DENORMAL;
                midState -= ANTI_DENORMAL;
                mid = midState - midHp;

                buffer[i] = (float) (mid + side);
                buffer[i + 1] = (float) (mid - side);
            }
        }
        else
        {
            for (int i = 0; i < buffer.length; i += 2)
            {
                double l = buffer[i];
                double r = buffer[i + 1];
                double mid = (l + r) * 0.5;
                double side = (l - r) * 0.5 * w;
                buffer[i] = (float) (mid + side);
                buffer[i + 1] = (float) (mid - side);
            }
        }
    }

    private void updateBassMonoCoeff(double cutoff)
    {
        if (sampleRate > 0.0)
        {
            bassMonoCoeff = 1.0 - Math.exp(-2.0 * Math.PI * cutoff / sampleRate);
        }
    }
}
