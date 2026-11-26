package com.dspark.core;

/**
 * Click-free parameter smoother with selectable curves, in double precision.
 * <p>
 * Wraps a parameter value in a per-sample or per-block ramp (exponential,
 * linear, adaptive chase, or disabled) so that automation and edits never
 * step audibly. A default-constructed smoother is functional at 44.1 kHz
 * with a 20 ms ramp; {@link #prepare} adapts it to the real rate.
 * <p>
 * Timing of {@code rampTimeMs} per curve:
 * <ul>
 *   <li><b>Exponential</b>: one-pole time constant (63% arrival). The value
 *       snaps exactly onto the target once within a relative epsilon (1e-7
 *       of max(1, |target|)), about 16 time constants after a unit step, and
 *       {@link #isSmoothing()} then reports false.</li>
 *   <li><b>Linear</b>: constant velocity chosen at each retarget so that any
 *       step, whatever its size or unit, arrives exactly on target after
 *       rampTimeMs.</li>
 *   <li><b>Chase</b>: adaptive (not clocked): gentle right after a target
 *       change, accelerating as it settles. rampTimeMs is the time to cover
 *       99% of any step; the value then snaps onto the target after about
 *       2.06 times that. The curve is the same at every sample rate.</li>
 * </ul>
 * <b>Step-dependent ramps (opt-in, Linear only).</b>
 * {@link #setAdaptiveRamp} makes the duration of each linear ramp depend on
 * the size of the step that starts it: a small move glides over the slow
 * time, a large jump lands in the fast time; in between the duration
 * follows a square law of how close the step is to nothing. An edit in mid
 * ramp starts from the value already reached.
 * <p>
 * Non-finite targets are ignored. Not thread-safe: set targets from the
 * processing thread (for example at the start of each block).
 */
public final class SmoothedParameter
{
    /** The smoothing curve. */
    public enum SmoothingType
    {
        /** One-pole IIR: natural, musical interpolation. */
        EXPONENTIAL,
        /** Rate-limited ramp: constant velocity, exact target arrival. */
        LINEAR,
        /** Instant snapping, no smoothing applied. */
        DISABLED,
        /** Adaptive speed: gentle start after a jump, accelerating settle. */
        CHASE
    }

    /** 99% time of the unstretched chase curve, in ms. */
    private static final double CHASE_NATURAL_MS = 171.0;
    /** Speed bounds of the unstretched chase curve (time constants in samples at 44.1 kHz). */
    private static final double CHASE_MIN_SPEED = 350.0;
    private static final double CHASE_MAX_SPEED = 2500.0;
    /** Relative arrival threshold. */
    private static final double EPSILON = 1e-7;

    private double current = 0.0;
    private double target = 0.0;
    private SmoothingType type = SmoothingType.EXPONENTIAL;

    private double expCoeff = 0.0;
    private double linearRate = 0.0;
    private double rampSamples = 882.0;

    private double adaptiveSpan = 0.0;
    private double adaptiveFastMs = 20.0;
    private double adaptiveSlowMs = 150.0;

    private double chaseSpeed = 350.0;
    private double chaseMultDecay = 0.9999;
    private double chaseAddDecay = 0.01;
    private double chaseRateScale = 1.0;
    private double chaseMinSpeed = 350.0;
    private double chaseMaxSpeed = 2500.0;

    private double sampleRate = 44100.0;
    private double rampTimeMs = 20.0;

    private double snapEps = EPSILON;

    /** A smoother at 44.1 kHz with a 20 ms ramp, value and target 0. */
    public SmoothedParameter()
    {
        prepare(sampleRate, rampTimeMs);
    }

    /** {@link #prepare(double, double)} with a 20 ms ramp. */
    public void prepare(double sampleRate)
    {
        prepare(sampleRate, 20.0);
    }

    /**
     * Precalculates the coefficients for the sample rate and timing.
     *
     * @param sampleRate sample rate in Hz (at least 1; NaN becomes 1)
     * @param rampTimeMs smoothing duration in ms. 0 (or any non-positive or
     *                   NaN value) means no smoothing: every curve lands on a
     *                   new target at the next sample.
     */
    public void prepare(double sampleRate, double rampTimeMs)
    {
        this.sampleRate = max(1.0, sampleRate);
        this.rampTimeMs = max(0.0, rampTimeMs);

        // Exponential: one-pole coefficient.
        final double tauSamples = this.sampleRate * this.rampTimeMs / 1000.0;
        expCoeff = (tauSamples > 0.0) ? Math.exp(-1.0 / tauSamples) : 0.0;

        // Linear: ramp length in samples; the per-sample rate is sized from
        // the remaining distance so an in-flight ramp keeps its new duration.
        rampSamples = max(1.0, this.rampTimeMs * this.sampleRate / 1000.0);
        updateLinearRate();

        // Chase: the speed constants are tuned at 44.1 kHz in samples, where
        // they cover 99% of a step in CHASE_NATURAL_MS. The decay of the
        // speed and the speed itself both scale with the rate, and the whole
        // curve is stretched by stretch = rampTimeMs / CHASE_NATURAL_MS. The
        // speed recursion v <- m v - a is affine, so its exact stretched step
        // is v <- m^(1/stretch) v - a stretch (1 - m^(1/stretch)) / (1 - m)
        // with the speed itself and its bounds scaled by stretch.
        final double srRatio = 44100.0 / this.sampleRate;
        final double m = Math.pow(0.9999, srRatio);
        final double stretch = max(this.rampTimeMs, 1e-3) / CHASE_NATURAL_MS;
        if (stretch == 1.0)
        {
            chaseMultDecay = m;
            chaseAddDecay = 0.01 * srRatio;
        }
        else
        {
            final double ms = Math.pow(m, 1.0 / stretch);
            chaseMultDecay = ms;
            chaseAddDecay = 0.01 * srRatio * stretch * (1.0 - ms) / (1.0 - m);
        }
        chaseMinSpeed = CHASE_MIN_SPEED * stretch;
        chaseMaxSpeed = CHASE_MAX_SPEED * stretch;
        chaseRateScale = 1.0 / srRatio;
        if (chaseSpeed < chaseMinSpeed || chaseSpeed > chaseMaxSpeed) chaseSpeed = chaseMinSpeed;
    }

    /** Sets the smoothing curve (null is ignored); a switch to Linear mid-flight keeps the full ramp time. */
    public void setSmoothingType(SmoothingType type)
    {
        if (type == null) return;
        this.type = type;
        updateLinearRate();
    }

    /** @return the current smoothing curve */
    public SmoothingType getSmoothingType() { return type; }

    /**
     * Makes the duration of every Linear ramp depend on its step.
     * <p>
     * A step of {@code span} or more takes {@code fastMs}; a step that tends
     * to nothing takes {@code slowMs}; a step d in between takes
     * fastMs + (slowMs - fastMs) * (1 - d / span)^2. The ramp stays linear
     * and lands exactly on the target after that time. Only the Linear curve
     * uses it; the ramp time given to prepare() then no longer applies to it.
     *
     * @param span   step size from which a ramp takes the fast time. Zero,
     *               negative or non-finite (or a non-finite time) turns the
     *               feature off (the default) and restores prepare()'s ramp time.
     * @param fastMs duration of a ramp over {@code span} or more, in ms (at least 0)
     * @param slowMs duration towards which a vanishing step tends, in ms (at least 0)
     */
    public void setAdaptiveRamp(double span, double fastMs, double slowMs)
    {
        if (!(span > 0.0) || !Double.isFinite(span) || !Double.isFinite(fastMs) || !Double.isFinite(slowMs))
        {
            adaptiveSpan = 0.0;
        }
        else
        {
            adaptiveSpan = span;
            adaptiveFastMs = max(0.0, fastMs);
            adaptiveSlowMs = max(0.0, slowMs);
        }
        updateLinearRate();
    }

    /** @return whether step-dependent ramps are on */
    public boolean hasAdaptiveRamp() { return adaptiveSpan > 0.0; }

    /**
     * Updates the target value; safe to call continuously. NaN and
     * infinities are ignored.
     */
    public void setTargetValue(double newTarget)
    {
        if (!Double.isFinite(newTarget)) return;
        if (newTarget != target)
        {
            target = newTarget;
            snapEps = snapEpsilonFor(newTarget);
            updateLinearRate();
            if (type == SmoothingType.CHASE)
                chaseSpeed = chaseMaxSpeed;   // Reset chase velocity on target change
        }
    }

    /** Advances one sample and returns the new smoothed value. */
    public double getNextValue()
    {
        final double tgt = target;
        if (current == tgt) return target;

        switch (type)
        {
            case EXPONENTIAL:
            {
                current = tgt + expCoeff * (current - tgt);
                break;
            }
            case LINEAR:
            {
                if (current < tgt)
                    current = min(current + linearRate, tgt);
                else
                    current = max(current - linearRate, tgt);
                break;
            }
            case DISABLED:
            {
                current = tgt;
                break;
            }
            case CHASE:
            {
                if (rampTimeMs <= 0.0) { current = tgt; break; }
                // The speed only falls after its reset to the maximum, so
                // only the floor is applied.
                chaseSpeed = max(chaseMinSpeed, chaseSpeed * chaseMultDecay - chaseAddDecay);
                final double speed = chaseSpeed * chaseRateScale;
                current = (current * speed + tgt) / (speed + 1.0);
                break;
            }
            default:
                break;
        }

        // Exact arrival: snap once within a relative epsilon of the target.
        if (Math.abs(current - tgt) < snapEps)
            current = tgt;

        return current;
    }

    /** Fills the whole buffer with the next smoothed values. */
    public void processBlock(double[] buffer)
    {
        processBlock(buffer, 0, buffer.length);
    }

    /**
     * Computes a block of smoothed values, sample-exact with as many
     * {@link #getNextValue()} calls (a settled smoother fills the target).
     *
     * @param buffer     output
     * @param offset     first index written
     * @param numSamples values to compute
     */
    public void processBlock(double[] buffer, int offset, int numSamples)
    {
        final double tgt = target;
        final int end = offset + numSamples;
        if (current == tgt)
        {
            for (int i = offset; i < end; ++i) buffer[i] = target;
            return;
        }

        double cur = current;
        final double eps = snapEps;
        int i = offset;

        switch (type)
        {
            case EXPONENTIAL:
            {
                final double coeff = expCoeff;
                for (; i < end; ++i)
                {
                    cur = tgt + coeff * (cur - tgt);
                    if (Math.abs(cur - tgt) < eps) { cur = tgt; break; }
                    buffer[i] = cur;
                }
                break;
            }

            case LINEAR:
            {
                final double rate = linearRate;
                for (; i < end; ++i)
                {
                    if (cur < tgt)      cur = min(cur + rate, tgt);
                    else if (cur > tgt) cur = max(cur - rate, tgt);
                    if (Math.abs(cur - tgt) < eps) { cur = tgt; break; }
                    buffer[i] = cur;
                }
                break;
            }

            case DISABLED:
                cur = tgt;
                break;

            case CHASE:
            {
                if (rampTimeMs <= 0.0) { cur = tgt; break; }
                double speed = chaseSpeed;
                final double multDecay = chaseMultDecay;
                final double addDecay = chaseAddDecay;
                final double rateScale = chaseRateScale;
                final double lo = chaseMinSpeed;
                for (; i < end; ++i)
                {
                    speed = max(lo, speed * multDecay - addDecay);   // falls only: no ceiling needed
                    final double scaled = speed * rateScale;
                    cur = (cur * scaled + tgt) / (scaled + 1.0);
                    if (Math.abs(cur - tgt) < eps) { cur = tgt; break; }
                    buffer[i] = cur;
                }
                chaseSpeed = speed;
                break;
            }

            default:
                break;
        }

        // Settled (or Disabled): the remainder of the block is the target.
        for (; i < end; ++i)
            buffer[i] = target;

        current = cur;
    }

    /** @return the current value without advancing */
    public double getCurrentValue() { return current; }

    /** @return the target value */
    public double getTargetValue() { return target; }

    /** @return whether the value is still moving towards the target */
    public boolean isSmoothing() { return current != target; }

    /** Jumps to the target. */
    public void skip()
    {
        current = target;
        chaseSpeed = chaseMinSpeed;
    }

    /** {@link #reset(double)} to 0. */
    public void reset()
    {
        reset(0.0);
    }

    /** Hard-resets both the current value and the target to {@code value}. */
    public void reset(double value)
    {
        target = value;
        snapEps = snapEpsilonFor(value);
        current = value;
        chaseSpeed = chaseMinSpeed;
    }

    /** Updates the sample rate and the ramp time (same as {@link #prepare(double, double)}). */
    public void setRampTime(double sampleRate, double rampTimeMs)
    {
        prepare(sampleRate, rampTimeMs);
    }

    /** The arrival threshold for a target. */
    private static double snapEpsilonFor(double t)
    {
        return EPSILON * max(1.0, Math.abs(t));
    }

    private void updateLinearRate()
    {
        final double distance = Math.abs(target - current);
        double samples = rampSamples;
        if (adaptiveSpan > 0.0)
        {
            final double closeness = max(0.0, 1.0 - distance / adaptiveSpan);
            final double ms = adaptiveFastMs + (adaptiveSlowMs - adaptiveFastMs) * closeness * closeness;
            samples = max(1.0, ms * sampleRate / 1000.0);
        }
        linearRate = distance / samples;
    }

    /** The larger of two values, the first one when they compare equal or either is NaN. */
    private static double max(double x, double y) { return (x < y) ? y : x; }

    /** The smaller of two values, the first one when they compare equal or either is NaN. */
    private static double min(double x, double y) { return (y < x) ? y : x; }
}
