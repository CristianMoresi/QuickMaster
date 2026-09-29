package com.dspark.effects;

import com.dspark.core.DspMath;

/**
 * Multi-mode mastering clipper.
 * <p>
 * Shapes the signal against a ceiling with one of four curves - a brick-wall
 * {@link Mode#HARD} clamp or the soft-knee {@link Mode#SOFT} (tanh),
 * {@link Mode#ANALOG} (sine) and {@link Mode#GOLDEN_RATIO} curves - with an
 * optional multi-stage cascade (which spreads the drive across stages for a
 * smoother harmonic profile) and an optional slew limiter that tames the
 * hardest edges. The streaming path uses first-order ADAA, which reduces
 * aliasing but also changes the response and does not eliminate aliasing.
 * Hosts with explicit oversampling can use {@link #shapeSample} instead.
 * {@link #getGainReductionDb()} reports how far
 * the peak was pushed down for metering.
 */
public final class Clipper
{
    /** Clipping curve. */
    public enum Mode
    {
        /** Brick-wall digital clamp. */
        HARD,
        /** Tanh soft-knee. */
        SOFT,
        /** Sine-based soft clip (transformer-like). */
        ANALOG,
        /** Golden-ratio soft-knee: linear up to ceiling/phi, then asymptotic. */
        GOLDEN_RATIO
    }

    private static final int MAX_CHANNELS = 8;
    private static final int MAX_STAGES = 4;
    private static final double PHI = 1.6180339887498948482;
    private static final double ADAA_EPS = 1e-5;

    private double sampleRate = 48000.0;
    private int channels = 2;
    private boolean prepared = false;

    private volatile Mode mode = Mode.HARD;
    private volatile double ceilingDb = 0.0;
    private volatile double inputGainDb = 0.0;
    private volatile int stages = 1;
    private volatile double mix = 1.0;
    private volatile double slewLimitMs = 0.0;

    private final double[] slewPrev = new double[MAX_CHANNELS];
    // ADAA: previous input of each clipping stage, per channel.
    private final double[][] adaaPrev = new double[MAX_STAGES][MAX_CHANNELS];
    private volatile double gainReductionDb = 0.0;

    /** Prepares the clipper. Non-finite or non-positive rates are ignored. */
    public void prepare(double sampleRate, int channels)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(channels, MAX_CHANNELS));
        reset();
        prepared = true;
    }

    /** Clears the slew, ADAA state and metering. */
    public void reset()
    {
        java.util.Arrays.fill(slewPrev, 0.0);
        for (double[] p : adaaPrev) java.util.Arrays.fill(p, 0.0);
        gainReductionDb = 0.0;
    }

    /** Sets the clipping curve. */
    public void setMode(Mode m) { if (m != null) mode = m; }

    /** Output ceiling in dBFS (−60..+6). Non-finite values are ignored. */
    public void setCeilingDb(double dB) { if (Double.isFinite(dB)) ceilingDb = DspMath.clamp(dB, -60.0, 6.0); }

    /** Input drive applied before clipping, in dB (0..48). Non-finite values are ignored. */
    public void setInputGainDb(double dB) { if (Double.isFinite(dB)) inputGainDb = DspMath.clamp(dB, 0.0, 48.0); }

    /** Number of cascaded clipping stages (1..4). */
    public void setStages(int n) { stages = Math.max(1, Math.min(n, MAX_STAGES)); }

    /** Dry/wet ratio (0 = dry, 1 = fully clipped). Non-finite values are ignored. */
    public void setMix(double m) { if (Double.isFinite(m)) mix = DspMath.clamp(m, 0.0, 1.0); }

    /** Slew limit in ms (max rise time to full scale); 0 = off. Non-finite values are ignored. */
    public void setSlewLimitMs(double ms) { if (Double.isFinite(ms)) slewLimitMs = Math.max(0.0, ms); }

    public double getSlewLimitMs() { return slewLimitMs; }

    public Mode getMode() { return mode; }
    public double getCeilingDb() { return ceilingDb; }

    /** Peak gain reduction applied by the last processed block, in dB (≤ 0). */
    public double getGainReductionDb() { return gainReductionDb; }

    /**
     * Output peak the current curve would produce for a given input peak and
     * ceiling (single stage, no slew) — used by the host to solve the ceiling for
     * an exact peak reduction. Monotonically non-decreasing in {@code ceiling}.
     *
     * @param inputPeak  absolute input peak
     * @param ceiling    candidate ceiling (linear)
     * @return the resulting absolute output peak
     */
    public double probeOutputPeak(double inputPeak, double ceiling)
    {
        return Math.abs(shapeSample(inputPeak, ceiling));
    }

    /**
     * Signed, memoryless single-stage transfer, matching the C++ Clipper curves.
     * No drive, ADAA, slew, filtering or metering. The host owns oversampling.
     * A linear ceiling avoids the UI-specific dB range of the streaming API.
     */
    public double shapeSample(double input, double ceiling) {
        if (!Double.isFinite(input) || !Double.isFinite(ceiling) || ceiling <= 0)
            throw new IllegalArgumentException("Require finite input and positive finite ceiling.");
        return shape(mode, input, ceiling);
    }

    /**
     * Clips an interleaved buffer in place.
     *
     * @param buffer    interleaved samples
     * @param channels  channel count
     */
    public void process(float[] buffer, int channels)
    {
        if (!prepared || channels <= 0) return;
        int nCh = Math.min(channels, this.channels);
        int frames = buffer.length / channels;

        Mode m = mode;
        double ceiling = DspMath.decibelsToGain(ceilingDb);
        double totalGain = DspMath.decibelsToGain(inputGainDb);
        int nStages = stages;
        double stageGain = (nStages > 1) ? Math.pow(totalGain, 1.0 / nStages) : totalGain;
        double slewMs = slewLimitMs;
        double maxSlew = (slewMs > 0.0) ? (ceiling / (slewMs * 0.001)) / sampleRate : 0.0;
        double mixVal = mix;

        double peakIn = 0.0, peakOut = 0.0;
        for (int ch = 0; ch < nCh; ch++)
        {
            for (int f = 0; f < frames; f++)
            {
                int idx = f * channels + ch;
                double dry = buffer[idx];
                double driven = dry * totalGain;

                double s = dry;
                for (int st = 0; st < nStages; st++)
                {
                    s *= stageGain;
                    s = shapeAdaa(m, s, ceiling, st, ch);
                }

                if (maxSlew > 0.0)
                {
                    double delta = s - slewPrev[ch];
                    if (Math.abs(delta) > maxSlew) s = slewPrev[ch] + Math.copySign(maxSlew, delta);
                }
                slewPrev[ch] = s;

                double wet = dry + (s - dry) * mixVal;
                buffer[idx] = (float) wet;

                double aIn = Math.abs(driven);
                double aOut = Math.abs(wet);
                if (aIn > peakIn) peakIn = aIn;
                if (aOut > peakOut) peakOut = aOut;
            }
        }

        gainReductionDb = (peakIn > 1e-6) ? DspMath.gainToDecibels(Math.min(peakOut / peakIn, 1.0)) : 0.0;
    }

    /**
     * One clipping stage with first-order antiderivative anti-aliasing
     * (Parker/Zavalishin/Le&nbsp;Bivic DAFx-16), reducing spectral foldback.
     * Inside the curve's exactly-linear region
     * (HARD / GOLDEN_RATIO below their knee) the sample passes through
     * untouched, so the body suffers no averaging roll-off.
     */
    private double shapeAdaa(Mode m, double x, double ceiling, int stage, int ch)
    {
        double x0 = adaaPrev[stage][ch];
        adaaPrev[stage][ch] = x;

        double linearRegion = linearKnee(m, ceiling);
        if (Math.abs(x) <= linearRegion && Math.abs(x0) <= linearRegion)
        {
            return x;
        }
        double dx = x - x0;
        if (Math.abs(dx) > ADAA_EPS)
        {
            return (antideriv(m, x, ceiling) - antideriv(m, x0, ceiling)) / dx;
        }
        return shape(m, 0.5 * (x + x0), ceiling);
    }

    /** Magnitude below which the curve is exactly the identity (0 when it never is). */
    private static double linearKnee(Mode m, double ceiling)
    {
        switch (m)
        {
            case HARD:         return ceiling;
            case GOLDEN_RATIO: return ceiling / PHI;
            default:           return 0.0;       // SOFT and ANALOG bend everywhere
        }
    }

    /** Antiderivative of {@link #shape} with F(0) = 0 (even, since the curves are odd). */
    private static double antideriv(Mode m, double x, double c)
    {
        double a = Math.abs(x);
        switch (m)
        {
            case HARD:
                return (a <= c) ? 0.5 * x * x : c * a - 0.5 * c * c;
            case SOFT:
            {
                double t = a / c;
                // ln(cosh t) overflows for large t; switch to its asymptote.
                double lncosh = (t > 20.0) ? t - Math.log(2.0) : Math.log(Math.cosh(t));
                return c * c * lncosh;
            }
            case ANALOG:
            {
                double knee = c * Math.PI / 2;
                if (a <= knee) return c * c * (1.0 - Math.cos(a / c));
                return c * c + c * (a - knee);
            }
            case GOLDEN_RATIO:
            {
                double thr = c / PHI;
                if (a <= thr) return 0.5 * x * x;
                double r = c - thr;
                double e = a - thr;
                return 0.5 * thr * thr + c * e - r * r * Math.log((e + r) / r);
            }
            default:
                return 0.5 * x * x;
        }
    }

    private static double shape(Mode m, double x, double ceiling)
    {
        switch (m)
        {
            case HARD:
                return DspMath.clamp(x, -ceiling, ceiling);
            case SOFT:
                return ceiling * Math.tanh(x / ceiling);
            case ANALOG:
            {
                double n = DspMath.clamp(x / ceiling, -Math.PI / 2, Math.PI / 2);
                return ceiling * Math.sin(n);
            }
            case GOLDEN_RATIO:
            {
                double thr = ceiling / PHI;
                double a = Math.abs(x);
                if (a <= thr) return x;
                double sign = Math.copySign(1.0, x);
                double excess = a - thr;
                double range = ceiling - thr;
                return sign * (thr + (range * excess) / (excess + range));
            }
            default:
                return x;
        }
    }
}
