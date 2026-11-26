package com.dspark.core;

/**
 * A first or second order analog section, in radians per second:
 * {@code (b2 s^2 + b1 s + b0) / (a2 s^2 + a1 s + a0)}.
 * <p>
 * A plain mutable value (the default is the unity section); the prototypes
 * are the cookbook's analog forms. {@link FilterDesign} turns a section into
 * the biquad whose magnitude follows it up to Nyquist, or into its bilinear
 * transform.
 */
public final class AnalogSection
{
    public double b2 = 0.0, b1 = 0.0, b0 = 1.0;
    public double a2 = 0.0, a1 = 0.0, a0 = 1.0;

    /** The unity section. */
    public AnalogSection()
    {
    }

    /** A copy of another section. */
    public AnalogSection(AnalogSection other)
    {
        set(other);
    }

    /** Copies another section's six coefficients into this one. */
    public void set(AnalogSection other)
    {
        b2 = other.b2;
        b1 = other.b1;
        b0 = other.b0;
        a2 = other.a2;
        a1 = other.a1;
        a0 = other.a0;
    }

    /** Squared magnitude at an angular frequency (rad/s). */
    public double magnitudeSquared(double omega)
    {
        final double w2 = omega * omega;
        final double nr = b0 - b2 * w2, ni = b1 * omega;
        final double dr = a0 - a2 * w2, di = a1 * omega;
        return (nr * nr + ni * ni) / (dr * dr + di * di);
    }

    /** Magnitude in decibels at a frequency in hertz. */
    public double magnitudeDb(double hz)
    {
        return 10.0 * Math.log10(stdMax(magnitudeSquared(2.0 * Math.PI * hz), 1e-30));
    }

    /** Whether the denominator is of second order. */
    public boolean isSecondOrder()
    {
        return a2 != 0.0;
    }

    /** The frequency of the poles in hertz (the corner of a first-order section). */
    public double poleFrequency()
    {
        final double omega = isSecondOrder() ? Math.sqrt(Math.abs(a0 / a2)) : Math.abs(a0 / a1);
        return omega / (2.0 * Math.PI);
    }

    /* --- Prototypes (the cookbook's analog forms) --- */

    /** Second-order low-pass with the Butterworth Q. */
    public static AnalogSection lowPass(double hz)
    {
        return lowPass(hz, BiquadDesign.BUTTERWORTH_Q);
    }

    /** Second-order low-pass, gain Q at the corner. */
    public static AnalogSection lowPass(double hz, double q)
    {
        final double w = omegaOf(hz);
        return make(0.0, 0.0, w * w, 1.0, w / safeQ(q), w * w);
    }

    /** Second-order high-pass with the Butterworth Q. */
    public static AnalogSection highPass(double hz)
    {
        return highPass(hz, BiquadDesign.BUTTERWORTH_Q);
    }

    /** Second-order high-pass, gain Q at the corner. */
    public static AnalogSection highPass(double hz, double q)
    {
        final double w = omegaOf(hz);
        return make(1.0, 0.0, 0.0, 1.0, w / safeQ(q), w * w);
    }

    /** Band-pass with the Butterworth Q. */
    public static AnalogSection bandPass(double hz)
    {
        return bandPass(hz, BiquadDesign.BUTTERWORTH_Q);
    }

    /** Band-pass, 0 dB at the centre. */
    public static AnalogSection bandPass(double hz, double q)
    {
        final double w = omegaOf(hz);
        return make(0.0, w / safeQ(q), 0.0, 1.0, w / safeQ(q), w * w);
    }

    /** Notch with the Butterworth Q. */
    public static AnalogSection notch(double hz)
    {
        return notch(hz, BiquadDesign.BUTTERWORTH_Q);
    }

    /** Notch at the centre, 0 dB away from it. */
    public static AnalogSection notch(double hz, double q)
    {
        final double w = omegaOf(hz);
        return make(1.0, 0.0, w * w, 1.0, w / safeQ(q), w * w);
    }

    /** Second-order all-pass with the Butterworth Q. */
    public static AnalogSection allPass(double hz)
    {
        return allPass(hz, BiquadDesign.BUTTERWORTH_Q);
    }

    /** Second-order all-pass (the magnitude is 1; only the phase turns). */
    public static AnalogSection allPass(double hz, double q)
    {
        final double w = omegaOf(hz);
        return make(1.0, -w / safeQ(q), w * w, 1.0, w / safeQ(q), w * w);
    }

    /** Bell: gainDb at the centre, bandwidth hz / Q at half that gain in decibels. */
    public static AnalogSection peak(double hz, double q, double gainDb)
    {
        final double w = omegaOf(hz), A = Math.pow(10.0, gainDb / 40.0);
        return make(1.0, w * A / safeQ(q), w * w, 1.0, w / (A * safeQ(q)), w * w);
    }

    /** Second-order low shelf with a slope of 1. */
    public static AnalogSection lowShelf(double hz, double gainDb)
    {
        return lowShelf(hz, gainDb, 1.0);
    }

    /** Second-order low shelf: gainDb below the corner, half of it (in decibels) at the corner. */
    public static AnalogSection lowShelf(double hz, double gainDb, double slope)
    {
        final double w = omegaOf(hz), A = Math.pow(10.0, gainDb / 40.0), d = shelfDamping(A, slope);
        return make(A, A * Math.sqrt(A) * d * w, A * A * w * w, A, Math.sqrt(A) * d * w, w * w);
    }

    /** Second-order high shelf with a slope of 1. */
    public static AnalogSection highShelf(double hz, double gainDb)
    {
        return highShelf(hz, gainDb, 1.0);
    }

    /** Second-order high shelf: gainDb above the corner, half of it (in decibels) at the corner. */
    public static AnalogSection highShelf(double hz, double gainDb, double slope)
    {
        final double w = omegaOf(hz), A = Math.pow(10.0, gainDb / 40.0), d = shelfDamping(A, slope);
        return make(A * A, A * Math.sqrt(A) * d * w, A * w * w, 1.0, Math.sqrt(A) * d * w, A * w * w);
    }

    /**
     * Second-order low shelf by its Q: gainDb below the corner, half of it (in
     * decibels) at the corner whatever the Q. At 0.7071 it is
     * {@link #lowShelf} at a slope of 1; above, the response rises over the
     * plateau before the corner and dips under 0 dB after it, more the higher
     * the Q; a cut is the exact inverse of the boost.
     */
    public static AnalogSection lowShelfQ(double hz, double gainDb, double q)
    {
        final double w = omegaOf(hz), A = Math.pow(10.0, gainDb / 40.0), d = 1.0 / DspMath.clamp(q, 0.05, 100.0);
        return make(A, A * Math.sqrt(A) * d * w, A * A * w * w, A, Math.sqrt(A) * d * w, w * w);
    }

    /** Second-order high shelf by its Q: {@link #lowShelfQ} mirrored about the corner. */
    public static AnalogSection highShelfQ(double hz, double gainDb, double q)
    {
        final double w = omegaOf(hz), A = Math.pow(10.0, gainDb / 40.0), d = 1.0 / DspMath.clamp(q, 0.05, 100.0);
        return make(A * A, A * Math.sqrt(A) * d * w, A * w * w, 1.0, Math.sqrt(A) * d * w, A * w * w);
    }

    /** Second-order tilt with the Butterworth Q. */
    public static AnalogSection tiltSecondOrder(double hz, double gainDb)
    {
        return tiltSecondOrder(hz, gainDb, BiquadDesign.BUTTERWORTH_Q);
    }

    /**
     * Second-order tilt: 0 dB at the pivot, half of gainDb down far below it
     * and half of it up far above, 12 dB per octave at most on the way; the Q
     * as in {@link #lowShelfQ}.
     */
    public static AnalogSection tiltSecondOrder(double hz, double gainDb, double q)
    {
        // A low shelf of -gainDb with half of gainDb back over the whole band.
        final double w = omegaOf(hz), A = Math.pow(10.0, -gainDb / 40.0), d = 1.0 / DspMath.clamp(q, 0.05, 100.0);
        return make(1.0, Math.sqrt(A) * d * w, A * w * w, A, Math.sqrt(A) * d * w, w * w);
    }

    /** First-order low-pass, -3 dB at the corner. */
    public static AnalogSection firstOrderLowPass(double hz)
    {
        final double w = omegaOf(hz);
        return make(0.0, 0.0, w, 0.0, 1.0, w);
    }

    /** First-order high-pass, -3 dB at the corner. */
    public static AnalogSection firstOrderHighPass(double hz)
    {
        final double w = omegaOf(hz);
        return make(0.0, 1.0, 0.0, 0.0, 1.0, w);
    }

    /** First-order low shelf: gainDb at DC, half of it (in decibels) at the corner. */
    public static AnalogSection firstOrderLowShelf(double hz, double gainDb)
    {
        final double w = omegaOf(hz), root = Math.pow(10.0, gainDb / 40.0);
        return make(0.0, 1.0, w * root, 0.0, 1.0, w / root);
    }

    /** First-order high shelf: gainDb at the top, half of it (in decibels) at the corner. */
    public static AnalogSection firstOrderHighShelf(double hz, double gainDb)
    {
        final double w = omegaOf(hz), root = Math.pow(10.0, gainDb / 40.0);
        return make(0.0, root, w, 0.0, 1.0 / root, w);
    }

    /** First-order tilt: 0 dB at the pivot, half of gainDb down far below it and half of it up far above. */
    public static AnalogSection tilt(double hz, double gainDb)
    {
        final double w = omegaOf(hz), root = Math.pow(10.0, gainDb / 40.0);
        return make(0.0, root, w, 0.0, 1.0, w * root);
    }

    /** Builds a section from its six coefficients. */
    public static AnalogSection make(double b2, double b1, double b0, double a2, double a1, double a0)
    {
        final AnalogSection s = new AnalogSection();
        s.b2 = b2;
        s.b1 = b1;
        s.b0 = b0;
        s.a2 = a2;
        s.a1 = a1;
        s.a0 = a0;
        return s;
    }

    private static double omegaOf(double hz)
    {
        return 2.0 * Math.PI * stdMax(hz, 1e-3);
    }

    private static double safeQ(double q)
    {
        return stdMax(q, 1e-3);
    }

    private static double shelfDamping(double A, double slope)
    {
        final double s = DspMath.clamp(slope, 1e-3, 1.0);
        return Math.sqrt(stdMax(0.0, (A + 1.0 / A) * (1.0 / s - 1.0) + 2.0));
    }

    /** max(a, b) as the comparison a &lt; b decides it (b only when a &lt; b). */
    private static double stdMax(double a, double b)
    {
        return (a < b) ? b : a;
    }
}
