package com.dspark.effects;

import com.dspark.core.DspMath;

/**
 * Analog-style soft clipper / saturator for mastering.
 * <p>
 * The signal is shaped by a warm soft-clip curve against a ceiling: the body
 * below the knee passes through cleanly, only the material near the ceiling is
 * rounded, generating musical harmonics. Three voicings are offered —
 * {@link Algorithm#TUBE} (asymmetric knee → warm 2nd-harmonic colour),
 * {@link Algorithm#TAPE} (symmetric → odd harmonics, smooth) and
 * {@link Algorithm#TRANSFORMER} (firmer, denser). The nonlinearity is
 * <b>antiderivative anti-aliased</b> (1st-order ADAA, Parker/Zavalishin/Le&nbsp;Bivic
 * DAFx-16; Bilbao&nbsp;et&nbsp;al. IEEE&nbsp;2017), so the harmonics it generates do
 * not fold back as aliasing. A 1st-order DC blocker removes the small offset the
 * asymmetric voicings introduce, and an independent per-channel drive drift models
 * two slightly different analog channels, decorrelating the left/right harmonics
 * for a wider image.
 * <p>
 * With the ceiling solved for a target, the saturation reduces the peak by a
 * precise amount; {@link #getGainReductionDb()} reports that reduction (≤ 0).
 */
public final class Saturation
{
    /** Soft-clip voicing. */
    public enum Algorithm
    {
        /** Asymmetric knee — warm, dominant 2nd harmonic. */
        TUBE,
        /** Symmetric knee — smooth, odd harmonics. */
        TAPE,
        /** Firmer asymmetric knee — denser even + odd harmonics. */
        TRANSFORMER
    }

    private static final int MAX_CHANNELS = 8;
    private static final double DRIFT_DEPTH = 0.02;   // ±2 % drive modulation at full intensity
    private static final double ADAA_EPS = 1e-5;

    private double sampleRate = 48000.0;
    private int channels = 2;
    private boolean prepared = false;

    private volatile Algorithm algorithm = Algorithm.TUBE;
    private volatile double driveDb = 0.0;
    private volatile double ceiling = 1.0;            // linear clip ceiling
    private volatile double stereoAmount = 0.0;

    private static final double DC_R = 0.9995;   // ~3.8 Hz DC blocker (flat passband, no ripple)

    // Per-channel state.
    private final double[] prevU = new double[MAX_CHANNELS];   // ADAA: previous normalised input
    private final double[] dcX1 = new double[MAX_CHANNELS];    // DC blocker: previous input
    private final double[] dcY1 = new double[MAX_CHANNELS];    // DC blocker: previous output
    private final long[] driftState = new long[MAX_CHANNELS];
    private final double[] driftSmooth = new double[MAX_CHANNELS];

    private double driftCoef = 0.0;
    private volatile double gainReductionDb = 0.0;

    /** Prepares the saturator. Non-finite or non-positive rates are ignored. */
    public void prepare(double sampleRate, int channels)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(channels, MAX_CHANNELS));
        driftCoef = 1.0 - Math.exp(-2.0 * Math.PI * 0.5 / sampleRate); // ~0.5 Hz analog drift
        reset();
        prepared = true;
    }

    /** Clears the ADAA, DC and drift state. */
    public void reset()
    {
        java.util.Arrays.fill(prevU, 0.0);
        java.util.Arrays.fill(dcX1, 0.0);
        java.util.Arrays.fill(dcY1, 0.0);
        java.util.Arrays.fill(driftSmooth, 0.0);
        for (int ch = 0; ch < MAX_CHANNELS; ch++)
            driftState[ch] = 0x9E3779B97F4A7C15L * (ch + 1) + 0xBF58476D1CE4E5B9L;
        gainReductionDb = 0.0;
    }

    public void setAlgorithm(Algorithm a) { if (a != null) algorithm = a; }

    /** Optional extra drive into the ceiling, in dB (0 = none). Non-finite values are ignored. */
    public void setDriveDb(double dB)
    {
        if (Double.isFinite(dB)) driveDb = DspMath.clamp(dB, 0.0, 24.0);
    }

    /** Clip ceiling (linear); solve this for the desired peak. Non-finite values are ignored. */
    public void setCeiling(double linear)
    {
        if (Double.isFinite(linear)) ceiling = Math.max(1e-4, linear);
    }

    /** Stereo drift intensity (0..1) — independent per-channel harmonic variation. */
    public void setStereoAmount(double a)
    {
        if (Double.isFinite(a)) stereoAmount = DspMath.clamp(a, 0.0, 1.0);
    }

    public Algorithm getAlgorithm() { return algorithm; }
    public double getDriveDb()      { return driveDb; }

    /** Peak reduction applied vs. the (driven) input, in dB (≤ 0). */
    public double getGainReductionDb() { return gainReductionDb; }

    /**
     * Output peak the soft-clip would produce for a given input peak and ceiling,
     * at the current drive — used by the host to solve the ceiling for an exact
     * peak reduction. Uses the gentler (positive) side, which is the loudest.
     * Monotonically increasing in {@code ceilingCandidate}.
     */
    public double probeOutputPeak(double inputPeak, double ceilingCandidate)
    {
        double c = Math.max(1e-4, ceilingCandidate);
        double driven = Math.abs(inputPeak) * DspMath.decibelsToGain(driveDb);
        return c * curve(driven / c, algorithm);   // positive side
    }

    /**
     * Soft-clips an interleaved buffer in place.
     *
     * @param buffer    interleaved samples
     * @param channels  channel count
     */
    public void process(float[] buffer, int channels)
    {
        if (!prepared || channels <= 0) return;
        int nCh = Math.min(channels, this.channels);
        int frames = buffer.length / channels;

        Algorithm algo = algorithm;
        double driveGain = DspMath.decibelsToGain(driveDb);
        double c = ceiling;
        double drift = stereoAmount;
        double kp = kneePos(algo), kn = kneeNeg(algo);

        double peakDriven = 0.0, peakOut = 0.0;
        for (int f = 0; f < frames; f++)
        {
            for (int ch = 0; ch < nCh; ch++)
            {
                int idx = f * channels + ch;

                double d = driveGain;
                if (drift > 0.0) d *= 1.0 + drift * DRIFT_DEPTH * nextDrift(ch);
                double driven = buffer[idx] * d;
                double u = driven / c;

                double u0 = prevU[ch];
                double du = u - u0;
                double shaped;
                if ((u >= 0.0 ? u <= kp : -u <= kn) && (u0 >= 0.0 ? u0 <= kp : -u0 <= kn))
                    shaped = u;     // linear body: pass through unchanged (no averaging, so no HF roll-off)
                else if (Math.abs(du) > ADAA_EPS)
                    shaped = (antideriv(u, algo) - antideriv(u0, algo)) / du;   // antiderivative anti-aliasing of the peaks
                else
                    shaped = signedCurve(0.5 * (u + u0), algo);
                prevU[ch] = u;

                double out = c * shaped;

                // Standard DC blocker (flat passband): removes the offset of the
                // asymmetric voicings without rippling the fundamental.
                double y = out - dcX1[ch] + DC_R * dcY1[ch];
                dcX1[ch] = out;
                dcY1[ch] = y;
                out = y;

                buffer[idx] = (float) out;

                double aDriven = Math.abs(driven);
                double aOut = Math.abs(out);
                if (aDriven > peakDriven) peakDriven = aDriven;
                if (aOut > peakOut) peakOut = aOut;
            }
        }

        gainReductionDb = (peakDriven > 1e-6) ? DspMath.gainToDecibels(Math.min(peakOut / peakDriven, 1.0)) : 0.0;
    }

    /* ====================================================================
     *  Soft-clip curve, its antiderivative, and the per-voicing knees
     * ==================================================================== */

    private static double kneePos(Algorithm a)
    {
        switch (a)
        {
            case TUBE:        return 0.96;
            case TRANSFORMER: return 0.86;
            case TAPE:
            default:          return 0.90;
        }
    }

    private static double kneeNeg(Algorithm a)
    {
        switch (a)
        {
            case TUBE:        return 0.78;   // strongly asymmetric → warm 2nd harmonic
            case TRANSFORMER: return 0.72;
            case TAPE:
            default:          return 0.90;   // symmetric → odd only
        }
    }

    /** Magnitude of the normalised soft-clip curve for a non-negative argument. */
    private static double curve(double a, Algorithm algo)
    {
        double k = kneePos(algo);
        if (a <= k) return a;
        double r = 1.0 - k, w = a - k;
        return k + r * w / (w + r);
    }

    /** Full signed soft-clip curve (asymmetric: gentler positive, firmer negative). */
    private static double signedCurve(double u, Algorithm algo)
    {
        double a = Math.abs(u);
        double k = (u >= 0.0) ? kneePos(algo) : kneeNeg(algo);
        if (a <= k) return u;
        double r = 1.0 - k, w = a - k;
        return (u >= 0.0 ? 1.0 : -1.0) * (k + r * w / (w + r));
    }

    /** Antiderivative F1 of {@link #signedCurve}, with F1(0)=0. */
    private static double antideriv(double u, Algorithm algo)
    {
        double a = Math.abs(u);
        double k = (u >= 0.0) ? kneePos(algo) : kneeNeg(algo);
        if (a <= k) return 0.5 * a * a;
        double r = 1.0 - k, w = a - k;
        return 0.5 * k * k + w - r * r * Math.log((w + r) / r);
    }

    /** Fast per-channel band-limited drift noise (xorshift + slow one-pole). */
    private double nextDrift(int ch)
    {
        long s = driftState[ch];
        s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
        driftState[ch] = s;
        double white = (s >>> 11) * (1.0 / (1L << 53)) * 2.0 - 1.0;
        driftSmooth[ch] += (white - driftSmooth[ch]) * driftCoef;
        return driftSmooth[ch];
    }
}
