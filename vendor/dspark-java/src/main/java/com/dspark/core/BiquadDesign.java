package com.dspark.core;

/**
 * Biquad coefficient designers: the Audio EQ Cookbook forms (Robert
 * Bristow-Johnson), first-order and tilt sections through the bilinear
 * transform, the analog-matched designs of M. Vicanek, and the ITU-R BS.1770
 * K-weighting stages.
 * <p>
 * Every designer returns a {@link BiquadCoeffs} normalised by {@code a0}
 * ({@code b0, b1, b2, a1, a2} with an implicit {@code a0 = 1}), so the
 * recursion never divides. Frequency, Q and slope are clamped to safe
 * ranges, so any finite parameter combination yields a stable filter. NaN
 * parameters are not sanitised (they propagate into the coefficients), and
 * the sample rate is trusted as it is.
 * <p>
 * The coefficients are double precision: a corner far below the sample rate
 * parks its poles a hair inside the unit circle, and only double resolves the
 * cancellation in {@code 1 + a1 + a2} that keeps them there. For DC removal
 * use {@code DcBlockFilter}, which is built for it.
 */
public final class BiquadDesign
{
    /** Default Q for a maximally flat (Butterworth) response: 1/sqrt(2). */
    public static final double BUTTERWORTH_Q = 0.7071067811865476;

    private BiquadDesign()
    {
    }

    /* ====================================================================
     *  Second-order designs (Audio EQ Cookbook)
     * ==================================================================== */

    /** Low-pass filter with the Butterworth Q. */
    public static BiquadCoeffs makeLowPass(double sampleRate, double freq)
    {
        return makeLowPass(sampleRate, freq, BUTTERWORTH_Q);
    }

    /**
     * Low-pass filter.
     *
     * @param sampleRate sample rate in Hz
     * @param freq       centre frequency in Hz
     * @param q          quality factor
     */
    public static BiquadCoeffs makeLowPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double alpha = sinw0 / (2.0 * q);

        final double a0 = 1.0 + alpha;
        return normalise(a0,
                (1.0 - cosw0) / 2.0,
                1.0 - cosw0,
                (1.0 - cosw0) / 2.0,
                -2.0 * cosw0,
                1.0 - alpha);
    }

    /** High-pass filter with the Butterworth Q. */
    public static BiquadCoeffs makeHighPass(double sampleRate, double freq)
    {
        return makeHighPass(sampleRate, freq, BUTTERWORTH_Q);
    }

    /**
     * High-pass filter.
     *
     * @param sampleRate sample rate in Hz
     * @param freq       centre frequency in Hz
     * @param q          quality factor
     */
    public static BiquadCoeffs makeHighPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double alpha = sinw0 / (2.0 * q);

        final double a0 = 1.0 + alpha;
        return normalise(a0,
                (1.0 + cosw0) / 2.0,
                -(1.0 + cosw0),
                (1.0 + cosw0) / 2.0,
                -2.0 * cosw0,
                1.0 - alpha);
    }

    /** Band-pass filter with the Butterworth Q. */
    public static BiquadCoeffs makeBandPass(double sampleRate, double freq)
    {
        return makeBandPass(sampleRate, freq, BUTTERWORTH_Q);
    }

    /**
     * Band-pass filter with a constant 0 dB peak gain: the cookbook variant
     * with {@code b0 = alpha}, exactly 0 dB at the centre whatever the Q.
     *
     * @param sampleRate sample rate in Hz
     * @param freq       centre frequency in Hz
     * @param q          quality factor
     */
    public static BiquadCoeffs makeBandPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double alpha = sinw0 / (2.0 * q);

        final double a0 = 1.0 + alpha;
        return normalise(a0,
                alpha,
                0.0,
                -alpha,
                -2.0 * cosw0,
                1.0 - alpha);
    }

    /**
     * Peaking (parametric EQ) filter with {@code A = 10^(gainDb/40)}, giving
     * the asked gain in dB at the centre frequency.
     *
     * @param sampleRate sample rate in Hz
     * @param freq       centre frequency in Hz
     * @param q          quality factor
     * @param gainDb     gain in dB (positive boosts, negative cuts)
     */
    public static BiquadCoeffs makePeak(double sampleRate, double freq, double q, double gainDb)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final double A = Math.pow(10.0, gainDb / 40.0);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double alpha = sinw0 / (2.0 * q);

        final double a0 = 1.0 + alpha / A;
        return normalise(a0,
                1.0 + alpha * A,
                -2.0 * cosw0,
                1.0 - alpha * A,
                -2.0 * cosw0,
                1.0 - alpha / A);
    }

    /**
     * Analog-matched ("de-cramped") peaking filter (M. Vicanek, "Matched
     * Second Order Digital Filters", 2016).
     * <p>
     * The poles are the analog prototype's poles mapped by impulse invariance
     * ({@code z = e^(sT)}), so the bell keeps its analog width at any
     * frequency, and the minimum-phase numerator is solved in closed form for
     * unity gain at DC, the exact peak gain at the centre and a response
     * extremum there. The prototype is the cookbook bell
     * {@code (s^2 + s w0 sqrt(G)/Q + w0^2) / (s^2 + s w0/(sqrt(G) Q) + w0^2)},
     * so Q means the same as in {@link #makePeak} and both designs converge
     * at low frequencies. Measured against that prototype over 20 Hz - 20 kHz
     * (gains +/-3..24 dB, Q 0.5..4, centres up to 20 kHz): worst deviation
     * 2.9 dB and mean 0.14 dB at 48 kHz (cookbook: 11 dB worst). Falls back to
     * the identity for |gain| below 0.01 dB.
     *
     * @param sampleRate sample rate in Hz
     * @param freq       centre frequency in Hz
     * @param q          quality factor (bandwidth = freq / Q at the half-dB gain)
     * @param gainDb     peak gain in dB
     */
    public static BiquadCoeffs makePeakMatched(double sampleRate, double freq, double q, double gainDb)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        if (Math.abs(gainDb) < 0.01)
            return new BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0);

        final double G = Math.pow(10.0, gainDb / 20.0);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;

        // Poles: impulse invariance of s^2 + 2 q w0 s + w0^2, q = 1/(2 Qpole),
        // with the prototype's pole Q = sqrt(G) Q.
        final double qd = 1.0 / (2.0 * q * Math.sqrt(G));
        double a1;
        final double a2 = Math.exp(-2.0 * qd * w0);
        if (qd <= 1.0)
        {
            a1 = -2.0 * Math.exp(-qd * w0) * Math.cos(Math.sqrt(1.0 - qd * qd) * w0);
        }
        else
        {
            // Two real poles z = e^((-q +/- r) w0), each evaluated on its own
            // (each is <= 1); -q + r is taken as -1/(q + r).
            final double r = Math.sqrt(qd * qd - 1.0);
            a1 = -(Math.exp(-w0 / (qd + r)) + Math.exp(-(qd + r) * w0));
        }

        // Magnitude matching in the paper's (A, B, phi) form: |H|^2 =
        // (B0 phi0 + B1 phi1 + B2 phi2) / (A0 phi0 + A1 phi1 + A2 phi2).
        final double A0 = (1.0 + a1 + a2) * (1.0 + a1 + a2);
        final double A1 = (1.0 - a1 + a2) * (1.0 - a1 + a2);
        final double A2 = -4.0 * a2;
        final double s = Math.sin(0.5 * w0);
        final double phi1 = s * s;
        final double phi0 = 1.0 - phi1;
        final double phi2 = 4.0 * phi0 * phi1;

        final double G2 = G * G;
        final double B0 = A0;                                           // unity at DC
        final double R1 = (A0 * phi0 + A1 * phi1 + A2 * phi2) * G2;     // |H(w0)| = G
        final double R2 = (-A0 + A1 + 4.0 * (phi0 - phi1) * A2) * G2;   // extremum at w0
        final double B2 = (R1 - R2 * phi1 - B0) / (4.0 * phi1 * phi1);
        final double B1 = R2 + B0 + 4.0 * (phi1 - phi0) * B2;

        // Minimum-phase numerator from (B0, B1, B2).
        final double sqrtB0 = Math.sqrt(stdMax(0.0, B0));
        final double sqrtB1 = Math.sqrt(stdMax(0.0, B1));
        final double W = 0.5 * (sqrtB0 + sqrtB1);
        final double b0 = 0.5 * (W + Math.sqrt(stdMax(0.0, W * W + B2)));
        final double b1 = 0.5 * (sqrtB0 - sqrtB1);
        final double b2 = -B2 / (4.0 * b0);
        return new BiquadCoeffs(b0, b1, b2, a1, a2);
    }

    /** Analog-matched low-pass with the Butterworth Q. */
    public static BiquadCoeffs makeLowPassMatched(double sampleRate, double freq)
    {
        return makeLowPassMatched(sampleRate, freq, BUTTERWORTH_Q);
    }

    /**
     * Analog-matched second-order low-pass (Vicanek 2016): impulse-invariant
     * poles of {@code 1 / (s^2 + s/Q + 1)} and a numerator solved for unity
     * gain at DC and the prototype's gain Q at the cutoff. Unlike
     * {@link #makeLowPass} it does not pin a zero at Nyquist, so a high
     * cutoff keeps its analog shape. Worst deviation from the prototype over
     * 20 Hz - 20 kHz at 48 kHz, cutoff 15 kHz, Q 0.707: 0.58 dB (bilinear
     * 9.4 dB).
     */
    public static BiquadCoeffs makeLowPassMatched(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final MatchedPoles m = matchedPoles(2.0 * Math.PI * freq / sampleRate, 1.0 / (2.0 * q));
        final double R1 = (m.A0 * m.phi0 + m.A1 * m.phi1 + m.A2 * m.phi2) * q * q;
        final double B0 = m.A0;
        final double B1 = (R1 - B0 * m.phi0) / m.phi1;
        final double b0 = 0.5 * (Math.sqrt(B0) + Math.sqrt(stdMax(0.0, B1)));
        final double b1 = Math.sqrt(B0) - b0;
        return new BiquadCoeffs(b0, b1, 0.0, m.a1, m.a2);
    }

    /** Analog-matched high-pass with the Butterworth Q. */
    public static BiquadCoeffs makeHighPassMatched(double sampleRate, double freq)
    {
        return makeHighPassMatched(sampleRate, freq, BUTTERWORTH_Q);
    }

    /**
     * Analog-matched second-order high-pass (Vicanek 2016): impulse-invariant
     * poles of {@code s^2 / (s^2 + s/Q + 1)}, a double zero at DC and the
     * gain set to the prototype's Q at the cutoff. Worst deviation over
     * 20 Hz - 20 kHz at 48 kHz, cutoff 18 kHz, Q 0.707: 0.39 dB (bilinear
     * 12.5 dB).
     */
    public static BiquadCoeffs makeHighPassMatched(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final MatchedPoles m = matchedPoles(2.0 * Math.PI * freq / sampleRate, 1.0 / (2.0 * q));
        final double b0 = Math.sqrt(stdMax(0.0, m.A0 * m.phi0 + m.A1 * m.phi1 + m.A2 * m.phi2)) * q / (4.0 * m.phi1);
        return new BiquadCoeffs(b0, -2.0 * b0, b0, m.a1, m.a2);
    }

    /** Analog-matched band-pass with the Butterworth Q. */
    public static BiquadCoeffs makeBandPassMatched(double sampleRate, double freq)
    {
        return makeBandPassMatched(sampleRate, freq, BUTTERWORTH_Q);
    }

    /**
     * Analog-matched band-pass, 0 dB at the centre (Vicanek 2016):
     * impulse-invariant poles of {@code (s/Q) / (s^2 + s/Q + 1)}, a zero at DC
     * and a numerator matching unity gain and a response extremum at the
     * centre. Worst deviation over 20 Hz - 20 kHz at 48 kHz, centre 5 kHz,
     * Q 0.707: 0.92 dB (bilinear 8.6 dB).
     */
    public static BiquadCoeffs makeBandPassMatched(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final MatchedPoles m = matchedPoles(2.0 * Math.PI * freq / sampleRate, 1.0 / (2.0 * q));
        final double R1 = m.A0 * m.phi0 + m.A1 * m.phi1 + m.A2 * m.phi2;
        final double R2 = -m.A0 + m.A1 + 4.0 * (m.phi0 - m.phi1) * m.A2;
        final double B2 = (R1 - R2 * m.phi1) / (4.0 * m.phi1 * m.phi1);
        final double B1 = R2 + 4.0 * (m.phi1 - m.phi0) * B2;
        final double b1 = -0.5 * Math.sqrt(stdMax(0.0, B1));
        final double b0 = 0.5 * (Math.sqrt(stdMax(0.0, B2 + b1 * b1)) - b1);
        final double b2 = -b0 - b1;
        return new BiquadCoeffs(b0, b1, b2, m.a1, m.a2);
    }

    /**
     * Analog-matched Butterworth low shelf (Vicanek, "Matched Two-Pole Digital
     * Shelving Filters", 2024). The prototype is the cookbook shelf with slope
     * 1 (Q = 1/sqrt(2)), half the gain at {@code freq}. The design matches DC,
     * Nyquist and two further points of the analog magnitude. Worst deviation
     * over 20 Hz - 20 kHz at 44.1/48 kHz, centres up to 18 kHz, gains
     * -12..+12 dB: 0.40 dB (bilinear 3.9 dB). Falls back to the identity for
     * |gain| below 0.01 dB.
     */
    public static BiquadCoeffs makeLowShelfMatched(double sampleRate, double freq, double gainDb)
    {
        return matchedShelf(sampleRate, freq, gainDb, true);
    }

    /** Analog-matched Butterworth high shelf (see {@link #makeLowShelfMatched}). */
    public static BiquadCoeffs makeHighShelfMatched(double sampleRate, double freq, double gainDb)
    {
        return matchedShelf(sampleRate, freq, gainDb, false);
    }

    /** Low shelf with a slope of 1. */
    public static BiquadCoeffs makeLowShelf(double sampleRate, double freq, double gainDb)
    {
        return makeLowShelf(sampleRate, freq, gainDb, 1.0);
    }

    /**
     * Low-shelf filter.
     *
     * @param sampleRate sample rate in Hz
     * @param freq       transition frequency in Hz
     * @param gainDb     shelf gain in dB
     * @param slope      shelf slope, clamped to (0, 1] (1 = the standard transition)
     */
    public static BiquadCoeffs makeLowShelf(double sampleRate, double freq, double gainDb, double slope)
    {
        freq = clampFreq(freq, sampleRate);
        // The slope must stay in (0, 1]: above 1 the radicand goes negative.
        slope = DspMath.clamp(slope, 0.0001, 1.0);
        final double A = Math.pow(10.0, gainDb / 40.0);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double radicand = (A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0;
        final double alpha = sinw0 / 2.0 * Math.sqrt(stdMax(0.0, radicand));
        final double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

        final double a0 = (A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha;
        return normalise(a0,
                A * ((A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha),
                2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0),
                A * ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha),
                -2.0 * ((A - 1.0) + (A + 1.0) * cosw0),
                (A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha);
    }

    /** High shelf with a slope of 1. */
    public static BiquadCoeffs makeHighShelf(double sampleRate, double freq, double gainDb)
    {
        return makeHighShelf(sampleRate, freq, gainDb, 1.0);
    }

    /**
     * High-shelf filter.
     *
     * @param sampleRate sample rate in Hz
     * @param freq       transition frequency in Hz
     * @param gainDb     shelf gain in dB
     * @param slope      shelf slope, clamped to (0, 1]
     */
    public static BiquadCoeffs makeHighShelf(double sampleRate, double freq, double gainDb, double slope)
    {
        freq = clampFreq(freq, sampleRate);
        slope = DspMath.clamp(slope, 0.0001, 1.0);
        final double A = Math.pow(10.0, gainDb / 40.0);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double radicand = (A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0;
        final double alpha = sinw0 / 2.0 * Math.sqrt(stdMax(0.0, radicand));
        final double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

        final double a0 = (A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha;
        return normalise(a0,
                A * ((A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha),
                -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0),
                A * ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha),
                2.0 * ((A - 1.0) - (A + 1.0) * cosw0),
                (A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha);
    }

    /** Notch filter with the Butterworth Q. */
    public static BiquadCoeffs makeNotch(double sampleRate, double freq)
    {
        return makeNotch(sampleRate, freq, BUTTERWORTH_Q);
    }

    /** Notch (band-reject) filter. */
    public static BiquadCoeffs makeNotch(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double alpha = sinw0 / (2.0 * q);

        final double a0 = 1.0 + alpha;
        return normalise(a0,
                1.0,
                -2.0 * cosw0,
                1.0,
                -2.0 * cosw0,
                1.0 - alpha);
    }

    /** All-pass filter with the Butterworth Q. */
    public static BiquadCoeffs makeAllPass(double sampleRate, double freq)
    {
        return makeAllPass(sampleRate, freq, BUTTERWORTH_Q);
    }

    /** All-pass filter (flat magnitude, frequency-dependent phase). */
    public static BiquadCoeffs makeAllPass(double sampleRate, double freq, double q)
    {
        freq = clampFreq(freq, sampleRate);
        q = stdMax(q, 0.001);
        final double w0 = 2.0 * Math.PI * freq / sampleRate;
        final double cosw0 = Math.cos(w0);
        final double sinw0 = Math.sin(w0);
        final double alpha = sinw0 / (2.0 * q);

        final double a0 = 1.0 + alpha;
        return normalise(a0,
                1.0 - alpha,
                -2.0 * cosw0,
                1.0 + alpha,
                -2.0 * cosw0,
                1.0 - alpha);
    }

    /* ====================================================================
     *  First-order designs (already normalised, a0 = 1)
     * ==================================================================== */

    /** First-order (6 dB/oct) low-pass: the bilinear transform of an RC filter. */
    public static BiquadCoeffs makeFirstOrderLowPass(double sampleRate, double frequency)
    {
        frequency = clampFreq(frequency, sampleRate);
        final double w = Math.tan(Math.PI * frequency / sampleRate);
        final double n = 1.0 / (1.0 + w);
        return new BiquadCoeffs(w * n, w * n, 0.0, (w - 1.0) * n, 0.0);
    }

    /** First-order (6 dB/oct) high-pass: the bilinear transform of a CR filter. */
    public static BiquadCoeffs makeFirstOrderHighPass(double sampleRate, double frequency)
    {
        frequency = clampFreq(frequency, sampleRate);
        final double w = Math.tan(Math.PI * frequency / sampleRate);
        final double n = 1.0 / (1.0 + w);
        return new BiquadCoeffs(n, -n, 0.0, (w - 1.0) * n, 0.0);
    }

    /**
     * First-order tilt filter: {@code -gainDb/2} at DC, unity at the pivot,
     * {@code +gainDb/2} at Nyquist (a total swing of {@code gainDb}).
     *
     * @param sampleRate sample rate in Hz
     * @param pivotFreq  pivot frequency in Hz (typically 600 to 3000 Hz)
     * @param gainDb     tilt amount in dB (positive = bright, negative = dark)
     */
    public static BiquadCoeffs makeTilt(double sampleRate, double pivotFreq, double gainDb)
    {
        pivotFreq = clampFreq(pivotFreq, sampleRate);
        final double g = Math.pow(10.0, gainDb / 20.0);
        final double sqrtG = Math.sqrt(g);
        final double c = Math.tan(Math.PI * pivotFreq / sampleRate);

        // First-order tilt shelf through the bilinear transform: DC gain
        // 1/sqrt(g), pivot gain 1, Nyquist gain sqrt(g).
        final double norm = 1.0 / (1.0 + sqrtG * c);
        return new BiquadCoeffs(
                (sqrtG + c) * norm,
                (c - sqrtG) * norm,
                0.0,
                (sqrtG * c - 1.0) * norm,
                0.0);
    }

    /**
     * ITU-R BS.1770 K-weighting, stage 1: the head-related high shelf. A
     * pre-warped analog parameterisation that reproduces the official table 1
     * coefficients at 48 kHz to machine precision (max error 8.9e-16) and
     * stays within the standard's tolerance at every other rate. Cascade with
     * {@link #makeKWeightingHighPass} for the full K filter.
     */
    public static BiquadCoeffs makeKWeightingShelf(double sampleRate)
    {
        final double G = 3.999843853973347;      // dB
        final double Q = 0.7071752369554196;
        final double fc = 1681.9744509555319;    // Hz
        final double K = Math.tan(Math.PI * fc / sampleRate);
        final double Vh = Math.pow(10.0, G / 20.0);
        final double Vb = Math.pow(Vh, 0.4996667741545416);
        final double a0 = 1.0 + K / Q + K * K;
        return new BiquadCoeffs(
                (Vh + Vb * K / Q + K * K) / a0,
                2.0 * (K * K - Vh) / a0,
                (Vh - Vb * K / Q + K * K) / a0,
                2.0 * (K * K - 1.0) / a0,
                (1.0 - K / Q + K * K) / a0);
    }

    /**
     * ITU-R BS.1770 K-weighting, stage 2: the RLB high-pass. The official
     * table 2 numerator is exactly [1, -2, 1], not normalised to unity
     * passband gain (it passes about +0.04 dB); the -0.691 dB constant of the
     * LUFS formula is tied to this exact cascade.
     */
    public static BiquadCoeffs makeKWeightingHighPass(double sampleRate)
    {
        final double Q = 0.5003270373238773;
        final double fc = 38.13547087602444;     // Hz
        final double K = Math.tan(Math.PI * fc / sampleRate);
        final double a0 = 1.0 + K / Q + K * K;
        return new BiquadCoeffs(
                1.0,
                -2.0,
                1.0,
                2.0 * (K * K - 1.0) / a0,
                (1.0 - K / Q + K * K) / a0);
    }

    /* ====================================================================
     *  Transparent set with the same poles
     * ==================================================================== */

    /**
     * The set with the poles of {@code c} whose numerator equals its
     * denominator: {1, a1, a2} over {1, a1, a2}. Its transfer function is 1 at
     * every frequency with the poles (and the memory of a filter running on
     * them) still there, so a direct-form filter whose state is signal, such
     * as {@link BiquadCascade}, can move to it to leave the path and back to
     * return, and what it adds to its input fades at the rate of its own
     * poles. A bell at 0 dB already is this set.
     */
    public static BiquadCoeffs bypassed(BiquadCoeffs c)
    {
        return new BiquadCoeffs(1.0, c.a1, c.a2, c.a1, c.a2);
    }

    /* ====================================================================
     *  Frequency response
     * ==================================================================== */

    /**
     * Magnitude {@code |H(f)|} of a coefficient set, the transfer function
     * {@code B(z)/A(z)} evaluated at {@code z = e^(j 2 pi f / fs)}: the same
     * numbers the recursion runs on, so the drawn curve is the realised one.
     *
     * @return linear magnitude (1.0 = unity gain)
     */
    public static double getMagnitude(BiquadCoeffs c, double frequency, double sampleRate)
    {
        final double w = 2.0 * Math.PI * frequency / sampleRate;
        final double cosW = Math.cos(w);
        final double cos2W = Math.cos(2.0 * w);
        final double sinW = Math.sin(w);
        final double sin2W = Math.sin(2.0 * w);

        final double nRe = c.b0 + c.b1 * cosW + c.b2 * cos2W;
        final double nIm = -c.b1 * sinW - c.b2 * sin2W;
        final double dRe = 1.0 + c.a1 * cosW + c.a2 * cos2W;
        final double dIm = -c.a1 * sinW - c.a2 * sin2W;

        final double numMag2 = nRe * nRe + nIm * nIm;
        final double denMag2 = dRe * dRe + dIm * dIm;

        return (denMag2 > 1e-30) ? Math.sqrt(numMag2 / denMag2) : 0.0;
    }

    /**
     * Magnitudes for a batch of frequencies.
     *
     * @param frequencies input frequencies in Hz
     * @param magnitudes  receives the magnitudes (at least as long as {@code frequencies})
     */
    public static void getMagnitudeForFrequencyArray(BiquadCoeffs c, double[] frequencies, double[] magnitudes,
                                                     double sampleRate)
    {
        for (int i = 0; i < frequencies.length; ++i)
            magnitudes[i] = getMagnitude(c, frequencies[i], sampleRate);
    }

    /** {@link #getMagnitudeForFrequencyArray(BiquadCoeffs, double[], double[], double)} for float curve buffers. */
    public static void getMagnitudeForFrequencyArray(BiquadCoeffs c, float[] frequencies, float[] magnitudes,
                                                     double sampleRate)
    {
        for (int i = 0; i < frequencies.length; ++i)
            magnitudes[i] = (float) getMagnitude(c, frequencies[i], sampleRate);
    }

    /* ====================================================================
     *  Helpers
     * ==================================================================== */

    /** Impulse-invariant poles of s^2 + 2 q w0 s + w0^2 and the A / phi terms of the matching form. */
    private static final class MatchedPoles
    {
        double a1, a2, A0, A1, A2, phi0, phi1, phi2;
    }

    private static MatchedPoles matchedPoles(double w0, double q)
    {
        final MatchedPoles m = new MatchedPoles();
        m.a2 = Math.exp(-2.0 * q * w0);
        if (q <= 1.0)
        {
            m.a1 = -2.0 * Math.exp(-q * w0) * Math.cos(Math.sqrt(1.0 - q * q) * w0);
        }
        else
        {
            // Two real poles, evaluated one by one (each <= 1); -q + r as -1/(q + r).
            final double r = Math.sqrt(q * q - 1.0);
            m.a1 = -(Math.exp(-w0 / (q + r)) + Math.exp(-(q + r) * w0));
        }
        m.A0 = (1.0 + m.a1 + m.a2) * (1.0 + m.a1 + m.a2);
        m.A1 = (1.0 - m.a1 + m.a2) * (1.0 - m.a1 + m.a2);
        m.A2 = -4.0 * m.a2;
        final double s = Math.sin(0.5 * w0);
        m.phi1 = s * s;
        m.phi0 = 1.0 - m.phi1;
        m.phi2 = 4.0 * m.phi0 * m.phi1;
        return m;
    }

    /**
     * Vicanek's two-pole Butterworth shelf. A high shelf of gain G is designed
     * directly; a low shelf is the high shelf of 1/G with the numerator scaled
     * by G (unity at Nyquist, G at DC).
     */
    private static BiquadCoeffs matchedShelf(double sampleRate, double freq, double gainDb, boolean low)
    {
        freq = clampFreq(freq, sampleRate);
        if (!(Math.abs(gainDb) >= 0.01)) return new BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0);
        final double kPi = Math.PI;
        final double G = Math.pow(10.0, (low ? -gainDb : gainDb) / 20.0);
        final double fc = freq / (0.5 * sampleRate);          // in units of Nyquist
        final double fc2 = fc * fc, fc4 = fc2 * fc2;
        final double hNy = (fc4 + G) / (fc4 + 1.0 / G);       // |H|^2 at Nyquist
        final double f1 = fc / Math.sqrt(0.160 + 1.543 * fc2);
        final double f2 = fc / Math.sqrt(0.947 + 3.806 * fc2);
        double ph1 = Math.sin(0.5 * kPi * f1);
        ph1 *= ph1;
        double ph2 = Math.sin(0.5 * kPi * f2);
        ph2 *= ph2;
        final double f14 = f1 * f1 * f1 * f1, f24 = f2 * f2 * f2 * f2;
        final double h1 = (fc4 + f14 * G) / (fc4 + f14 / G);
        final double h2 = (fc4 + f24 * G) / (fc4 + f24 / G);
        final double d1 = (h1 - 1.0) * (1.0 - ph1), d2 = (h2 - 1.0) * (1.0 - ph2);
        final double c11 = -ph1 * d1, c21 = -ph2 * d2;
        final double c12 = (hNy - h1) * ph1 * ph1, c22 = (hNy - h2) * ph2 * ph2;
        final double alpha1 = (c22 * d1 - c12 * d2) / (c11 * c22 - c12 * c21);
        final double alpha2 = (d1 - c11 * alpha1) / c12;
        final double beta1 = alpha1, beta2 = hNy * alpha2;
        final double A1 = alpha2, A2 = 0.25 * (alpha1 - alpha2);
        final double B1 = beta2, B2 = 0.25 * (beta1 - beta2);
        final double V = 0.5 * (1.0 + Math.sqrt(stdMax(0.0, A1)));
        final double Wn = 0.5 * (1.0 + Math.sqrt(stdMax(0.0, B1)));
        final double a0 = 0.5 * (V + Math.sqrt(stdMax(0.0, V * V + A2)));
        final double b0 = 0.5 * (Wn + Math.sqrt(stdMax(0.0, Wn * Wn + B2)));
        final double scale = low ? Math.pow(10.0, gainDb / 20.0) : 1.0;
        return new BiquadCoeffs(
                scale * b0 / a0,
                scale * (1.0 - Wn) / a0,
                scale * (-0.25 * B2 / b0) / a0,
                (1.0 - V) / a0,
                (-0.25 * A2 / a0) / a0);
    }

    /** Divides a raw coefficient set by a0. */
    private static BiquadCoeffs normalise(double a0, double b0r, double b1r, double b2r, double a1r, double a2r)
    {
        final double invA0 = 1.0 / a0;
        return new BiquadCoeffs(b0r * invA0, b1r * invA0, b2r * invA0, a1r * invA0, a2r * invA0);
    }

    /** The frequency clamp of every designer: [1 Hz, max(1 Hz, 0.499 fs)], a NaN passes through. */
    private static double clampFreq(double freq, double sampleRate)
    {
        return DspMath.clamp(freq, 1.0, stdMax(1.0, sampleRate * 0.499));
    }

    /** max(a, b) as the comparison a &lt; b decides it (b only when a &lt; b). */
    private static double stdMax(double a, double b)
    {
        return (a < b) ? b : a;
    }
}
