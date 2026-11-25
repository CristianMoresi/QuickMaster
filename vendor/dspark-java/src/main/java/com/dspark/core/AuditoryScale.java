package com.dspark.core;

/**
 * The published formulas of the auditory scale.
 * <ul>
 *   <li>Bark scale after Traunmuller (1990): z = 26.81 f / (1960 + f) - 0.53,
 *       with his corrections below 2 and above 20.1 Bark.</li>
 *   <li>Critical bandwidth after Zwicker and Terhardt (1980):
 *       25 + 75 (1 + 1.4 (f / 1 kHz)^2)^0.69 Hz.</li>
 *   <li>Threshold in quiet after Terhardt (1979):
 *       3.64 (f / 1 kHz)^-0.8 - 6.5 exp(-0.6 (f / 1 kHz - 3.3)^2)
 *       + 0.001 (f / 1 kHz)^4 dB SPL.</li>
 *   <li>Specific loudness as a power law over the threshold (Zwicker), with an
 *       exponent rising from 0.23 where the threshold is lowest toward 0.30
 *       where it is high.</li>
 * </ul>
 */
public final class AuditoryScale
{
    private AuditoryScale() { }

    /** The Bark value of a frequency (Traunmuller 1990). */
    public static double hzToBark(double hz)
    {
        double f = Math.max(hz, 0.0);
        double z = 26.81 * f / (1960.0 + f) - 0.53;
        if (z < 2.0) z += 0.15 * (2.0 - z);
        else if (z > 20.1) z += 0.22 * (z - 20.1);
        return z;
    }

    /** The frequency of a Bark value (the inverse of {@link #hzToBark}). */
    public static double barkToHz(double bark)
    {
        double z = bark;
        if (z < 2.0) z = (z - 0.3) / 0.85;                 // undo z + 0.15 (2 - z)
        else if (z > 20.1) z = (z + 0.22 * 20.1) / 1.22;   // undo z + 0.22 (z - 20.1)
        z = Math.max(-0.52, Math.min(z, 26.0));
        return 1960.0 * (z + 0.53) / (26.28 - z);
    }

    /** The critical bandwidth at a frequency, in hertz (Zwicker and Terhardt 1980). */
    public static double criticalBandwidth(double hz)
    {
        double k = Math.max(hz, 0.0) / 1000.0;
        return 25.0 + 75.0 * Math.pow(1.0 + 1.4 * k * k, 0.69);
    }

    /** The threshold in quiet at a frequency, in dB SPL (Terhardt 1979), capped at 120. */
    public static double thresholdInQuietDb(double hz)
    {
        double k = Math.max(10.0, Math.min(hz, 24000.0)) / 1000.0;
        double t = 3.64 * Math.pow(k, -0.8) - 6.5 * Math.exp(-0.6 * (k - 3.3) * (k - 3.3)) + 1e-3 * k * k * k * k;
        return Math.min(t, 120.0);
    }

    /**
     * The exponent of the specific loudness at a frequency: 0.23 where the
     * threshold in quiet is lowest, rising linearly with the threshold to
     * 0.30 for 40 dB above that minimum.
     */
    public static double loudnessExponent(double hz)
    {
        final double lowest = -5.0;   // the threshold's minimum, near 3.3 kHz
        double t = (thresholdInQuietDb(hz) - lowest) / 40.0;
        return 0.23 + 0.07 * Math.max(0.0, Math.min(t, 1.0));
    }

    /**
     * The power of the dominant component of a stereo pair: the larger
     * eigenvalue of the covariance [ll lr; lr rr].
     */
    public static double dominantPower(double ll, double rr, double lr)
    {
        double half = 0.5 * (ll - rr);
        double power = 0.5 * (ll + rr) + Math.sqrt(half * half + lr * lr);
        // What is not a number reads as nothing.
        return power > 0.0 ? power : 0.0;
    }

    /**
     * The dominant power of a pair read in a band of frequencies, whose cross
     * term is complex (the mean of one spectrum times the conjugate of the
     * other): a channel that is the other one delayed reads as one source.
     */
    public static double dominantPower(double ll, double rr, double lrReal, double lrImag)
    {
        return dominantPower(ll, rr, Math.sqrt(lrReal * lrReal + lrImag * lrImag));
    }
}
