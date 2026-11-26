package com.dspark.core;

/**
 * Jiles-Atherton magnetic hysteresis with an implicit Newton-Raphson solver.
 * <p>
 * The physical core shared by tape and transformer modelling: magnetization
 * M responds to the applied field H with saturation, memory (remanence) and
 * rate-dependent loop losses, following the Jiles-Atherton model (Jiles and
 * Atherton 1986; audio-rate treatment after Chowdhury, DAFx-19):
 * <pre>
 *   M_an(Q) = Ms * L(Q),   L(x) = coth(x) - 1/x,   Q = (H + alpha*M) / a
 *
 *   phi1  = (1-c) * dltM * (M_an - M) / [ (1-c)*dlt*k - alpha*(M_an - M) ]
 *           with dlt = sign(dH/dt), dltM the reversal gate
 *
 *   dM/dt = [ phi1 + c*(Ms/a)*L'(Q) ] / [ 1 - c*alpha*(Ms/a)*L'(Q) ] * dH/dt
 * </pre>
 * The gate dltM zeroes the irreversible term whenever magnetization would
 * move unphysically against the drive direction at reversal points.
 * <p>
 * Integration uses the alpha transform (a damped trapezoid) for both the
 * field derivative and the magnetization integral:
 * <pre>
 *   dH_n = (1+a) fs (H_n - H_{n-1}) - a dH_{n-1}
 *   M_n  = M_{n-1} + T/(1+a) * (W_n + a W_{n-1}),   a = 0.9
 * </pre>
 * The pair is exact on the linear part (the derivative and the integral
 * cancel), and the derivative's pole sits at z = -a instead of on the unit
 * circle, so a transient leaves no permanent alternating dH/dt behind and
 * the loop keeps its remanence. Solved per sample by Newton-Raphson on M_n,
 * seeded by an explicit Euler predictor (or, for a field with a period of
 * its own, by the same phase of the last two periods:
 * {@link #setPredictorPeriod}); converges in 1-3 iterations at audio rates.
 * All internal state is double: the model mixes quantities spanning about
 * nine orders of magnitude.
 * <p>
 * Mono processor: hold one instance per channel. Not thread-safe: the owner
 * calls {@link #setParameters} and {@link #processSample} from its own
 * processing thread.
 */
public final class Hysteresis
{
    /** The longest period the predictor follows, in samples. */
    public static final int MAX_PERIOD = 8;

    /** Damping of the alpha transform: the derivative's homogeneous mode decays by 0.9 per sample. */
    private static final double ALPHA = 0.9;

    /** dM/dt kept: two of the longest periods. */
    private static final int RING = 16;

    private double ms = 3.5e5;
    private double a = 2.2e4;
    private double alpha = 1.6e-3;
    private double k = 2.7e4;
    private double c = 1.7e-1;
    private double chi = 3.5e5 / 2.2e4;
    private double tolerance = 1e-9;
    private boolean bySlope = false;

    private double invFs2 = 0.5 / 48000.0;
    private double fs2 = 2.0 * 48000.0;

    private double m = 0.0;
    private double hPrev = 0.0;
    private double hDotPrev = 0.0;
    private double wPrev = 0.0;
    private final double[] past = new double[RING];
    private int pastAt = 0;
    private int pastCount = 0;
    private int period = 0;

    // Results of evaluate() and langevin().
    private double evalW;
    private double evalDwdm;
    private double lanL;
    private double lanLp;
    private double lanLpp;

    /**
     * Prepares the integrator for the given sample rate and resets the
     * state. A non-positive or non-finite rate is ignored.
     */
    public void prepare(double sampleRate)
    {
        if (!(sampleRate > 0.0) || !Double.isFinite(sampleRate)) return;
        invFs2 = 0.5 / sampleRate;
        fs2 = 2.0 * sampleRate;
        reset();
    }

    /** Clears magnetization and integrator state. */
    public void reset()
    {
        m = 0.0;
        hPrev = 0.0;
        hDotPrev = 0.0;
        wPrev = 0.0;
        java.util.Arrays.fill(past, 0.0);
        pastAt = 0;
        pastCount = 0;
    }

    /**
     * Sets the Jiles-Atherton parameters. Defaults follow the audio-tape
     * calibration from the DAFx literature. A call with any non-finite
     * argument is ignored.
     *
     * @param ms    saturation magnetization (A/m), at least 1 [3.5e5]
     * @param a     anhysteretic shape parameter (A/m), at least 1 [2.2e4]
     * @param alpha inter-domain coupling, at least 0 [1.6e-3]
     * @param k     coercivity / loop-loss parameter (A/m), at least 1 [2.7e4]
     * @param c     reversible magnetization fraction, 0 to 0.999 [0.17]
     */
    public void setParameters(double ms, double a, double alpha, double k, double c)
    {
        if (!Double.isFinite(ms) || !Double.isFinite(a) || !Double.isFinite(alpha)
            || !Double.isFinite(k) || !Double.isFinite(c))
            return;
        this.ms = max(ms, 1.0);
        this.a = max(a, 1.0);
        this.alpha = max(alpha, 0.0);
        this.k = max(k, 1.0);
        this.c = DspMath.clamp(c, 0.0, 0.999);
        this.chi = this.ms / this.a;
    }

    /**
     * Where the solver stops: a Newton step under this share of Ms.
     * <p>
     * At the default (1e-9) the model is evaluated once more at the point
     * reached. A looser stop takes that last evaluation from the slope of the
     * step just made, and from the second step on stops as well when what is
     * left, read from the rate the steps shrink at (a cube over a square, as
     * Newton's), is under it. A non-finite or non-positive value is ignored.
     *
     * @param relative the share of Ms, clamped to 1e-12 to 1e-3
     */
    public void setTolerance(double relative)
    {
        if (!(relative > 0.0) || !Double.isFinite(relative)) return;
        tolerance = DspMath.clamp(relative, 1e-12, 1e-3);
        bySlope = tolerance > 1e-9;
    }

    /**
     * The period of the field in samples, for the predictor (0, the default,
     * for none). With a field that repeats itself every {@code period}
     * samples beside a slower programme (a bias carrier), the Newton steps
     * start from dM/dt at the same phase of the last two periods, carried on
     * in a line, in place of the last one: fewer steps, the same answer
     * within the tolerance.
     *
     * @param period 0, or 1 to {@link #MAX_PERIOD} samples (others are ignored)
     */
    public void setPredictorPeriod(int period)
    {
        if (period < 0 || period > MAX_PERIOD || period == this.period) return;
        this.period = period;
        pastCount = 0;
    }

    /** @return the period the predictor follows (0: none) */
    public int getPredictorPeriod() { return period; }

    /** @return the saturation magnetization Ms (A/m) */
    public double getSaturation() { return ms; }

    /**
     * Small-signal susceptibility dM/dH around the demagnetized state: the
     * reversible-branch slope c*chi_an/(1 - c*alpha*chi_an), chi_an = Ms/(3a).
     */
    public double getSmallSignalSusceptibility()
    {
        final double chiAn = ms / (3.0 * a);
        return c * chiAn / (1.0 - c * alpha * chiAn);
    }

    /**
     * Processes one sample of applied field H and returns the magnetization
     * M. A non-finite field is ignored: the last magnetization is returned
     * and the state is left as it was.
     *
     * @param fieldH applied magnetic field (A/m scale of the JA parameters)
     * @return the magnetization
     */
    public double processSample(double fieldH)
    {
        if (!Double.isFinite(fieldH)) return m;

        final double h = fieldH;
        final double hDot = (1.0 + ALPHA) * 0.5 * fs2 * (h - hPrev) - ALPHA * hDotPrev;

        // Explicit predictor, then the matching alpha-transform corrector
        // with Newton steps.
        final double gain = 1.0 / (1.0 + ALPHA);
        final double step = 2.0 * invFs2 * gain;          // T / (1 + a)
        final double rhs = m + step * ALPHA * wPrev;
        double mNew = m + 2.0 * invFs2 * wPrev;
        if (period > 0 && pastCount >= 2 * period)
        {
            // W at this phase a period and two periods ago, carried on: the
            // corrector's own equation then gives the M it implies.
            final double one = past[(pastAt + RING - period) & (RING - 1)];
            final double two = past[(pastAt + RING - 2 * period) & (RING - 1)];
            mNew = rhs + step * (2.0 * one - two);
        }

        double w = 0.0;
        double dwdm = 0.0;
        boolean settled = false;
        final double stop = tolerance * ms;
        double before = 0.0;
        for (int it = 0; it < 4; ++it)
        {
            evaluate(mNew, h, hDot);
            w = evalW;
            dwdm = evalDwdm;
            final double g = mNew - rhs - step * w;
            final double gp = 1.0 - step * dwdm;
            final double dm = g / gp;
            mNew -= dm;
            final double size = Math.abs(dm);
            // Loose: the steps shrinking as Newton's do (each under half the
            // last), what is left is about step^3 / last^2.
            final boolean shrunk = bySlope && it > 0 && size < 0.5 * before && size * size * size < stop * before * before;
            before = size;
            if (size < stop || shrunk)
            {
                if (bySlope)
                {
                    w -= dwdm * dm;
                    settled = true;
                }
                break;
            }
        }
        if (!settled || Math.abs(mNew) > ms)
        {
            // Physical clamp: |M| cannot exceed the saturation magnetization.
            mNew = DspMath.clamp(mNew, -ms, ms);
            evaluate(mNew, h, hDot);
            w = evalW;
        }
        wPrev = w;
        if (period > 0)
        {
            past[pastAt] = w;
            pastAt = (pastAt + 1) & (RING - 1);
            if (pastCount < 2 * period) ++pastCount;
        }
        hPrev = h;
        hDotPrev = hDot;
        m = mNew;
        return mNew;
    }

    /** The larger of two values, the first one when they compare equal. */
    private static double max(double x, double y)
    {
        return (x < y) ? y : x;
    }

    /** Langevin function and its first two derivatives, numerically safe near 0. */
    private void langevin(double x)
    {
        final double ax = Math.abs(x);
        if (ax < 1e-3)
        {
            // Taylor: L = x/3 - x^3/45, L' = 1/3 - x^2/15, L'' = -2x/15
            final double x2 = x * x;
            lanL = x * (1.0 / 3.0 - x2 / 45.0);
            lanLp = 1.0 / 3.0 - x2 / 15.0;
            lanLpp = -2.0 * x / 15.0;
        }
        else if (ax > 30.0)
        {
            // csch^2 underflows to 0 well before this point.
            lanL = (x > 0.0 ? 1.0 : -1.0) - 1.0 / x;
            lanLp = 1.0 / (x * x);
            lanLpp = -2.0 / (x * x * x);
        }
        else
        {
            // With u = e^(2x) - 1: coth = 1 + 2/u and csch^2 = 4 (u + 1)/u^2,
            // one exponential and one division (expm1 keeps u whole for a
            // small x, where e^(2x) - 1 would lose its digits).
            final double u = Math.expm1(2.0 * x);
            final double r = 1.0 / u;
            final double coth = 1.0 + 2.0 * r;
            final double csch2 = 4.0 * (u + 1.0) * r * r;
            final double ix = 1.0 / x;
            lanL = coth - ix;
            lanLp = ix * ix - csch2;
            lanLpp = 2.0 * (csch2 * coth - ix * ix * ix);
        }
    }

    /** Evaluates W = dM/dt and dW/dM at (m, h, hDot) into evalW and evalDwdm. */
    private void evaluate(double mAt, double h, double hDot)
    {
        if (hDot == 0.0)
        {
            evalW = 0.0;
            evalDwdm = 0.0;
            return;
        }

        final double q = (h + alpha * mAt) / a;
        langevin(q);
        final double l = lanL;
        final double lp = lanLp;
        final double lpp = lanLpp;

        final double mAn = ms * l;
        final double dM = mAn - mAt;
        final double delta = (hDot > 0.0) ? 1.0 : -1.0;
        final double deltaM = (dM * delta > 0.0) ? 1.0 : 0.0;   // gate reversal

        // Guard the JA denominator singularity (can cross zero at hard drive).
        final double oneMc = 1.0 - c;
        double d1 = oneMc * delta * k - alpha * dM;
        final double d1Min = 0.01 * oneMc * k;
        if (Math.abs(d1) < d1Min)
            d1 = (d1 >= 0.0) ? d1Min : -d1Min;

        final double invD1 = 1.0 / d1;
        final double phi1 = oneMc * deltaM * dM * invD1;
        final double cChiLp = c * chi * lp;

        final double num = phi1 + cChiLp;
        // Guard the reversible-branch denominator too: physical parameter
        // sets satisfy c*alpha*Ms/(3a) < 1 so den stays near 1, but an extreme
        // set makes it sweep through zero as Q moves, spiking dM/dt for a
        // sample. The M clamp recovers afterwards; this bounds the spike.
        double den = 1.0 - alpha * cChiLp;
        if (Math.abs(den) < 0.01)
            den = (den >= 0.0) ? 0.01 : -0.01;
        final double invDen = 1.0 / den;
        evalW = hDot * num * invDen;

        // Analytic dW/dM for the Newton step.
        final double alphaOverA = alpha / a;
        final double dmAn = ms * lp * alphaOverA - 1.0;          // d(M_an - M)/dM
        final double dPhi1 = oneMc * deltaM * dmAn * (oneMc * delta * k) * (invD1 * invD1);
        final double dLp = lpp * alphaOverA;
        final double dNum = dPhi1 + c * chi * dLp;
        final double dDen = -alpha * c * chi * dLp;
        evalDwdm = hDot * (dNum * den - num * dDen) * (invDen * invDen);
    }
}
