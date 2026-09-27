package com.dspark.core;

/**
 * Immutable set of normalized biquad coefficients
 * ({@code b0, b1, b2, a1, a2}) with static factory methods for every
 * standard filter type.
 * <p>
 * The formulas are Robert Bristow-Johnson's "Audio EQ Cookbook"
 * (plus first-order and tilt designs via the bilinear transform).
 * Every factory pre-normalizes by {@code a0}, so the processing loop
 * in {@link Biquad} never divides. Instances are immutable, which makes
 * them safe to publish to the audio thread through a {@code volatile}
 * reference without tearing.
 */
public final class BiquadCoeffs
{
    /** Pass-through coefficients (unity gain, no filtering). */
    public static final BiquadCoeffs IDENTITY = new BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0);

    /** Default Q for a maximally-flat (Butterworth) response: 1/√2. */
    public static final double BUTTERWORTH_Q = 0.7071067811865476;

    public final double b0, b1, b2, a1, a2;

    public BiquadCoeffs(double b0, double b1, double b2, double a1, double a2)
    {
        this.b0 = b0; this.b1 = b1; this.b2 = b2;
        this.a1 = a1; this.a2 = a2;
    }

    /* ====================================================================
     *  Second-order factories (Audio EQ Cookbook)
     * ==================================================================== */

    /** Low-pass filter at {@code freq} Hz with quality factor {@code q}. */
    public static BiquadCoeffs lowPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = Math.max(q, 0.001);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * q);
        double a0 = 1.0 + alpha;
        return norm(a0,
                (1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0,
                -2.0 * cw, 1.0 - alpha);
    }

    /** High-pass filter at {@code freq} Hz with quality factor {@code q}. */
    public static BiquadCoeffs highPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = Math.max(q, 0.001);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * q);
        double a0 = 1.0 + alpha;
        return norm(a0,
                (1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0,
                -2.0 * cw, 1.0 - alpha);
    }

    /**
     * Band-pass filter (constant 0 dB peak gain). The Audio EQ Cookbook BPF
     * variant with {@code b0 = alpha}, whose peak gain at the centre
     * frequency is exactly 0 dB regardless of Q - the most useful variant
     * for mixing and crossover work.
     */
    public static BiquadCoeffs bandPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = Math.max(q, 0.001);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * q);
        double a0 = 1.0 + alpha;
        return norm(a0, alpha, 0.0, -alpha, -2.0 * cw, 1.0 - alpha);
    }

    /** Parametric peaking (bell) EQ: {@code gainDb} at the centre frequency. */
    public static BiquadCoeffs peak(double sampleRate, double freq, double q, double gainDb)
    {
        freq = clampFreq(freq, sampleRate);
        q = Math.max(q, 0.001);
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * q);
        double a0 = 1.0 + alpha / A;
        return norm(a0,
                1.0 + alpha * A, -2.0 * cw, 1.0 - alpha * A,
                -2.0 * cw, 1.0 - alpha / A);
    }

    /**
     * Analog-matched peaking filter (Vicanek, Matched Second Order Digital
     * Filters, 2016). Port of DSPark C++ Core/Biquad.h at 9330f1c.
     * <p>
     * The bilinear (cookbook) peaking filter cramps near Nyquist: high-
     * frequency bells get narrower and their response is pinned at fs/2,
     * deviating audibly from the analog prototype above ~fs/6. This design
     * maps the analog poles by impulse invariance and solves a minimum-phase
     * numerator with unity at DC, exact center gain and an extremum there.
     * <p>
     * Falls back to identity for |gain| &lt; 0.01 dB. At low frequencies it
     * converges to the cookbook response (as it should).
     */
    public static BiquadCoeffs peakMatched(double sampleRate, double freq, double q, double gainDb)
    {
        freq = clampFreq(freq, sampleRate);
        q = Math.max(q, 0.001);
        if (!(Math.abs(gainDb) >= 0.01)) return IDENTITY;   // also catches NaN

        final double g = Math.pow(10.0, gainDb / 20.0);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double damping = 1.0 / (2.0 * q * Math.sqrt(g));
        final double a2 = Math.exp(-2.0 * damping * w0);
        final double a1;
        if (damping <= 1.0)
            a1 = -2.0 * Math.exp(-damping * w0) * Math.cos(Math.sqrt(1.0 - damping * damping) * w0);
        else {
            double r = Math.sqrt(damping * damping - 1.0);
            a1 = -(Math.exp(-w0 / (damping + r)) + Math.exp(-(damping + r) * w0));
        }
        final double A0 = (1.0 + a1 + a2) * (1.0 + a1 + a2);
        final double A1 = (1.0 - a1 + a2) * (1.0 - a1 + a2);
        final double A2 = -4.0 * a2;
        final double sine = Math.sin(.5 * w0), phi1 = sine * sine;
        final double phi0 = 1.0 - phi1, phi2 = 4.0 * phi0 * phi1;
        final double g2 = g * g, B0 = A0;
        final double R1 = (A0 * phi0 + A1 * phi1 + A2 * phi2) * g2;
        final double R2 = (-A0 + A1 + 4.0 * (phi0 - phi1) * A2) * g2;
        final double B2 = (R1 - R2 * phi1 - B0) / (4.0 * phi1 * phi1);
        final double B1 = R2 + B0 + 4.0 * (phi1 - phi0) * B2;
        final double sqrtB0 = Math.sqrt(Math.max(0, B0)), sqrtB1 = Math.sqrt(Math.max(0, B1));
        final double W = .5 * (sqrtB0 + sqrtB1);
        final double b0 = .5 * (W + Math.sqrt(Math.max(0, W * W + B2)));
        return new BiquadCoeffs(b0, .5 * (sqrtB0 - sqrtB1), -B2 / (4.0 * b0), a1, a2);
    }

    /** Low-shelf filter: boosts/cuts below {@code freq} by {@code gainDb}. */
    public static BiquadCoeffs lowShelf(double sampleRate, double freq, double gainDb, double slope)
    {
        freq = clampFreq(freq, sampleRate);
        slope = clamp(slope, 0.0001, 1.0);
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double radicand = (A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0;
        double alpha = sw / 2.0 * Math.sqrt(Math.max(0.0, radicand));
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1.0) + (A - 1.0) * cw + beta;
        return norm(a0,
                A * ((A + 1.0) - (A - 1.0) * cw + beta),
                2.0 * A * ((A - 1.0) - (A + 1.0) * cw),
                A * ((A + 1.0) - (A - 1.0) * cw - beta),
                -2.0 * ((A - 1.0) + (A + 1.0) * cw),
                (A + 1.0) + (A - 1.0) * cw - beta);
    }

    /** High-shelf filter: boosts/cuts above {@code freq} by {@code gainDb}. */
    public static BiquadCoeffs highShelf(double sampleRate, double freq, double gainDb, double slope)
    {
        freq = clampFreq(freq, sampleRate);
        slope = clamp(slope, 0.0001, 1.0);
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double radicand = (A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0;
        double alpha = sw / 2.0 * Math.sqrt(Math.max(0.0, radicand));
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1.0) - (A - 1.0) * cw + beta;
        return norm(a0,
                A * ((A + 1.0) + (A - 1.0) * cw + beta),
                -2.0 * A * ((A - 1.0) + (A + 1.0) * cw),
                A * ((A + 1.0) + (A - 1.0) * cw - beta),
                2.0 * ((A - 1.0) - (A + 1.0) * cw),
                (A + 1.0) - (A - 1.0) * cw - beta);
    }

    /** Notch (band-reject) filter. */
    public static BiquadCoeffs notch(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = Math.max(q, 0.001);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * q);
        double a0 = 1.0 + alpha;
        return norm(a0, 1.0, -2.0 * cw, 1.0, -2.0 * cw, 1.0 - alpha);
    }

    /** All-pass filter (flat magnitude, frequency-dependent phase). */
    public static BiquadCoeffs allPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = Math.max(q, 0.001);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * q);
        double a0 = 1.0 + alpha;
        return norm(a0, 1.0 - alpha, -2.0 * cw, 1.0 + alpha, -2.0 * cw, 1.0 - alpha);
    }

    /* ====================================================================
     *  First-order factories (already normalized, a0 = 1)
     * ==================================================================== */

    /** First-order (6 dB/oct) low-pass. */
    public static BiquadCoeffs firstOrderLowPass(double sampleRate, double freq)
    {
        freq = clampFreq(freq, sampleRate);
        double w = Math.tan(Math.PI * freq / sampleRate);
        double n = 1.0 / (1.0 + w);
        return new BiquadCoeffs(w * n, w * n, 0.0, (w - 1.0) * n, 0.0);
    }

    /** First-order (6 dB/oct) high-pass. */
    public static BiquadCoeffs firstOrderHighPass(double sampleRate, double freq)
    {
        freq = clampFreq(freq, sampleRate);
        double w = Math.tan(Math.PI * freq / sampleRate);
        double n = 1.0 / (1.0 + w);
        return new BiquadCoeffs(n, -n, 0.0, (w - 1.0) * n, 0.0);
    }

    /**
     * First-order tilt filter: pivots the spectrum around
     * {@code pivotFreq} — {@code +gainDb/2} above, {@code -gainDb/2}
     * below. The single-knob tonal-balance control found on mastering
     * EQs and channel strips (SSL, Neve, Tonelux Tilt).
     *
     * @param pivotFreq pivot frequency in Hz (typically 600–3000 Hz)
     * @param gainDb    tilt amount in dB (positive = brighter)
     */
    public static BiquadCoeffs tilt(double sampleRate, double pivotFreq, double gainDb)
    {
        pivotFreq = clampFreq(pivotFreq, sampleRate);
        double g = Math.pow(10.0, gainDb / 20.0);
        double sqrtG = Math.sqrt(g);
        double c = Math.tan(Math.PI * pivotFreq / sampleRate);
        double norm = 1.0 / (1.0 + sqrtG * c);
        return new BiquadCoeffs(
                (sqrtG + c) * norm,
                (c - sqrtG) * norm,
                0.0,
                (sqrtG * c - 1.0) * norm,
                0.0);
    }

    /* ====================================================================
     *  Frequency-response analysis
     * ==================================================================== */

    /**
     * Evaluates the magnitude response {@code |H(f)|} at a single
     * frequency, by evaluating the transfer function on the unit circle.
     * Used to draw EQ curves.
     *
     * @return linear magnitude (1.0 = unity gain)
     */
    public double magnitude(double frequency, double sampleRate)
    {
        double w = 2.0 * Math.PI * frequency / sampleRate;
        double cosW = Math.cos(w), cos2W = Math.cos(2.0 * w);
        double sinW = Math.sin(w), sin2W = Math.sin(2.0 * w);

        double nRe = b0 + b1 * cosW + b2 * cos2W;
        double nIm = -b1 * sinW - b2 * sin2W;
        double dRe = 1.0 + a1 * cosW + a2 * cos2W;
        double dIm = -a1 * sinW - a2 * sin2W;

        double numMag2 = nRe * nRe + nIm * nIm;
        double denMag2 = dRe * dRe + dIm * dIm;
        return (denMag2 > 1e-30) ? Math.sqrt(numMag2 / denMag2) : 0.0;
    }

    /* --- helpers --- */

    private static BiquadCoeffs norm(double a0,
                                     double b0, double b1, double b2,
                                     double a1, double a2)
    {
        double inv = 1.0 / a0;
        return new BiquadCoeffs(b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv);
    }

    private static double clampFreq(double freq, double sampleRate)
    {
        // A NaN frequency fails both clamp comparisons and would poison the
        // coefficients; pin it to the lower bound instead.
        if (!Double.isFinite(freq)) return 1.0;
        return clamp(freq, 1.0, sampleRate * 0.499);
    }

    private static double clamp(double v, double lo, double hi)
    {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
