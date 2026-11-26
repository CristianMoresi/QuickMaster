package com.dspark.core;

/**
 * A collection of smoothing filters for parameter interpolation in audio.
 * <p>
 * These classes prevent artifacts like zipper noise or clicks during
 * parameter changes. They allocate nothing after construction. Every
 * smoother keeps its state in double precision while its public API is
 * single precision ({@code float}): targets are rounded to float when set and
 * values are rounded to float when returned.
 * <p>
 * Common API:
 * <ul>
 *   <li>{@code reset(sampleRate, timeMs, initialValue)}: configure.</li>
 *   <li>{@code setTargetValue(target)}: the value to smooth towards.</li>
 *   <li>{@code getNextValue()}: the next smoothed value (call per sample).</li>
 *   <li>{@code getCurrentValue()}: the current value without advancing.</li>
 *   <li>{@code getTargetValue()}: the current target.</li>
 *   <li>{@code isSmoothing()}: still moving (for most, |current - target| &gt; epsilon).</li>
 *   <li>{@code skip()}: jump to the target.</li>
 * </ul>
 * Not thread-safe: a smoother lives inside a processor and every method is
 * called from that processor's processing thread.
 */
public final class Smoothers
{
    private Smoothers() { }

    /** Single-precision constants of the smoothers. */
    public static final class Constants
    {
        private Constants() { }

        public static final float PI = (float) Math.PI;
        public static final float TWO_PI = 2.0f * (float) Math.PI;
        public static final float SQRT2 = 1.41421356237309504880f;
    }

    // ==========================================================================

    /**
     * Linear ramp smoother for predictable, uniform interpolation: a new
     * target is reached in exactly the ramp time (truncated to whole
     * samples). Use for gains, mix, pans or faders where exact timing is
     * needed.
     */
    public static final class LinearSmoother
    {
        public static final float EPSILON = 1e-6f;

        private double current = 0.0;
        private double target = 0.0;
        private double step = 0.0;
        private int stepsToGo = 0;
        private int totalSteps = 0;

        /** Configures a ramp and parks the value at 0. */
        public void reset(double sampleRate, float rampTimeMilliseconds)
        {
            reset(sampleRate, rampTimeMilliseconds, 0.0f);
        }

        /**
         * Configures the ramp length, {@code (int) (sampleRate * ms / 1000)}
         * samples, and parks current and target at the initial value.
         */
        public void reset(double sampleRate, float rampTimeMilliseconds, float initialValue)
        {
            totalSteps = (int) (sampleRate * rampTimeMilliseconds / 1000.0);
            stepsToGo = 0;
            step = 0.0;
            current = initialValue;
            target = initialValue;
        }

        /** Starts a ramp of the configured length towards the target (a ramp of 0 samples jumps). */
        public void setTargetValue(float newTarget)
        {
            if (newTarget == target) return;
            target = newTarget;
            stepsToGo = totalSteps;
            if (stepsToGo > 0)
                step = (target - current) / (double) stepsToGo;
            else
                current = target;
        }

        /** Advances one sample; the last step of a ramp lands exactly on the target. */
        public float getNextValue()
        {
            if (stepsToGo <= 0) return (float) current;
            current += step;
            --stepsToGo;
            if (stepsToGo == 0) current = target;
            return (float) current;
        }

        public float getCurrentValue() { return (float) current; }

        public float getTargetValue() { return (float) target; }

        /** Sets current and target at once and stops any ramp. */
        public void setCurrentAndTargetValue(float value)
        {
            current = value;
            target = value;
            step = 0.0;
            stepsToGo = 0;
        }

        public boolean isSmoothing() { return Math.abs(current - target) > EPSILON; }

        public void skip()
        {
            current = target;
            step = 0.0;
            stepsToGo = 0;
        }

        /** {@link #processBlock(float[], int, boolean)} adding the values. */
        public void processBlock(float[] buffer, int numSamples)
        {
            processBlock(buffer, numSamples, false);
        }

        /**
         * Block processing: advances {@code numSamples} samples.
         *
         * @param buffer     the audio block
         * @param numSamples number of samples to process
         * @param multiply   true to multiply the buffer by the values (a gain),
         *                   false to add them (an offset)
         */
        public void processBlock(float[] buffer, int numSamples, boolean multiply)
        {
            final int stepsToProcess = Math.min(numSamples, stepsToGo);
            int i = 0;

            // Ramping section
            for (; i < stepsToProcess; ++i)
            {
                current += step;
                final float value = (float) current;
                buffer[i] = multiply ? buffer[i] * value : buffer[i] + value;
            }

            stepsToGo -= stepsToProcess;
            if (stepsToGo == 0) current = target;

            // Settled section
            for (; i < numSamples; ++i)
            {
                final float value = (float) target;
                buffer[i] = multiply ? buffer[i] * value : buffer[i] + value;
            }
        }
    }

    /**
     * Exponential (multiplicative) smoother for natural, perceptual
     * responses: filter cutoffs, frequencies, linear gains. A new target is
     * reached geometrically in exactly the configured number of samples.
     * <p>
     * Geometric smoothing is only defined for same-sign transitions: when
     * the new target's sign differs from the current value, or the current
     * value is zero, the smoother snaps to the target at once. Keep this
     * class for strictly positive parameters.
     */
    public static final class ExponentialSmoother
    {
        public static final float EPSILON = 1e-10f;

        private double current = 1.0;
        private double target = 1.0;
        private double coeff = 0.0;
        private int stepsToGo = 0;
        private int totalSteps = 0;

        /** Configures the ramp and parks the value at 1. */
        public void reset(double sampleRate, float timeConstantMilliseconds)
        {
            reset(sampleRate, timeConstantMilliseconds, 1.0f);
        }

        /** Configures the ramp length in samples and parks current and target at the initial value. */
        public void reset(double sampleRate, float timeConstantMilliseconds, float initialValue)
        {
            totalSteps = (int) (sampleRate * timeConstantMilliseconds / 1000.0);
            stepsToGo = 0;
            coeff = 1.0;
            current = initialValue;
            target = initialValue;
        }

        /** Starts a geometric ramp to the target (targets under epsilon in size become +-epsilon). */
        public void setTargetValue(float newTarget)
        {
            if (Math.abs(newTarget) < EPSILON)
                newTarget = (newTarget < 0.0f) ? -EPSILON : EPSILON;

            if (newTarget == target) return;
            target = newTarget;

            if (totalSteps > 0 && Math.abs(current) > EPSILON)
            {
                final double eps = (double) EPSILON;
                final double safeCur = (current > 0.0) ? max(current, eps) : min(current, -eps);
                final double ratio = target / safeCur;
                if (ratio > 0.0)
                {
                    stepsToGo = totalSteps;
                    coeff = Math.exp(Math.log(ratio) / (double) stepsToGo);
                    return;
                }
            }

            // Snap; the ramp stops here as well.
            current = target;
            coeff = 1.0;
            stepsToGo = 0;
        }

        public float getNextValue()
        {
            if (stepsToGo <= 0) return (float) current;
            current *= coeff;
            --stepsToGo;
            if (stepsToGo == 0) current = target;
            return (float) current;
        }

        public float getCurrentValue() { return (float) current; }

        public float getTargetValue() { return (float) target; }

        public void setCurrentAndTargetValue(float value)
        {
            current = value;
            target = value;
            coeff = 1.0;
            stepsToGo = 0;
        }

        public boolean isSmoothing() { return Math.abs(current - target) > EPSILON; }

        public void skip()
        {
            current = target;
            coeff = 1.0;
            stepsToGo = 0;
        }
    }

    /**
     * One-pole exponential low-pass smoother (analog-style). Snaps onto the
     * target once within epsilon, which also keeps the state free of
     * denormals.
     */
    public static final class OnePoleSmoother
    {
        public static final float EPSILON = 1e-6f;

        private double coeff = 0.0;
        private double current = 0.0;
        private double target = 0.0;

        public void reset(double sampleRate, float timeConstantMilliseconds)
        {
            reset(sampleRate, timeConstantMilliseconds, 0.0f);
        }

        /** Configures the time constant (0 or less: no smoothing) and parks the value. */
        public void reset(double sampleRate, float timeConstantMilliseconds, float initialValue)
        {
            final double timeConstantSeconds = (double) timeConstantMilliseconds / 1000.0;
            final double tau = sampleRate * timeConstantSeconds;
            coeff = tau > 0.0 ? Math.exp(-1.0 / tau) : 0.0;
            current = initialValue;
            target = initialValue;
        }

        public void setTargetValue(float newTarget) { target = newTarget; }

        public float getNextValue()
        {
            if (!isSmoothing()) { skip(); return (float) target; }
            current = target + coeff * (current - target);
            return (float) current;
        }

        public float getCurrentValue() { return (float) current; }

        public float getTargetValue() { return (float) target; }

        public boolean isSmoothing() { return Math.abs(current - target) > EPSILON; }

        public void skip() { current = target; }
    }

    /** Cascade of identical one-pole smoothers for a steeper roll-off. */
    public static final class MultiPoleSmoother
    {
        public static final float EPSILON = 1e-6f;

        private final OnePoleSmoother[] poles;

        /** @param numPoles number of poles, at least 1 */
        public MultiPoleSmoother(int numPoles)
        {
            if (numPoles < 1) throw new IllegalArgumentException("MultiPoleSmoother needs at least one pole");
            poles = new OnePoleSmoother[numPoles];
            for (int i = 0; i < numPoles; ++i) poles[i] = new OnePoleSmoother();
        }

        /** @return the number of poles */
        public int getNumPoles() { return poles.length; }

        public void reset(double sampleRate, float timeConstantMilliseconds)
        {
            reset(sampleRate, timeConstantMilliseconds, 0.0f);
        }

        public void reset(double sampleRate, float timeConstantMilliseconds, float initialValue)
        {
            for (OnePoleSmoother pole : poles)
                pole.reset(sampleRate, timeConstantMilliseconds, initialValue);
        }

        public void setTargetValue(float newTarget) { poles[0].setTargetValue(newTarget); }

        /** Advances every pole one sample, each chasing the (float) output of the one before. */
        public float getNextValue()
        {
            float val = poles[0].getNextValue();
            for (int i = 1; i < poles.length; ++i)
            {
                poles[i].setTargetValue(val);
                val = poles[i].getNextValue();
            }
            return val;
        }

        public float getCurrentValue() { return poles[poles.length - 1].getCurrentValue(); }

        public float getTargetValue() { return poles[0].getTargetValue(); }

        /** Compares the chain's output with the global target (pole 0's target). */
        public boolean isSmoothing()
        {
            return Math.abs(poles[poles.length - 1].getCurrentValue()
                            - poles[0].getTargetValue()) > EPSILON;
        }

        /** Lands every pole on the global target. */
        public void skip()
        {
            final float t = poles[0].getTargetValue();
            for (OnePoleSmoother pole : poles)
            {
                pole.setTargetValue(t);
                pole.skip();
            }
        }
    }

    /**
     * One-pole smoother with asymmetric attack and release times: the attack
     * applies while the value rises towards the target and the release while
     * it falls, following the envelope-follower convention.
     */
    public static final class AsymmetricSmoother
    {
        public static final float EPSILON = 1e-6f;

        private double attackCoeff = 0.0;
        private double releaseCoeff = 0.0;
        private double current = 0.0;
        private double target = 0.0;

        public void reset(double sampleRate, float attackMilliseconds, float releaseMilliseconds)
        {
            reset(sampleRate, attackMilliseconds, releaseMilliseconds, 0.0f);
        }

        public void reset(double sampleRate, float attackMilliseconds, float releaseMilliseconds, float initialValue)
        {
            final double attackSeconds = (double) attackMilliseconds / 1000.0;
            final double releaseSeconds = (double) releaseMilliseconds / 1000.0;
            attackCoeff = attackSeconds > 0.0 ? Math.exp(-1.0 / (sampleRate * attackSeconds)) : 0.0;
            releaseCoeff = releaseSeconds > 0.0 ? Math.exp(-1.0 / (sampleRate * releaseSeconds)) : 0.0;
            current = initialValue;
            target = initialValue;
        }

        public void setTargetValue(float newTarget) { target = newTarget; }

        public float getNextValue()
        {
            if (!isSmoothing()) { skip(); return (float) target; }
            final double c = (target > current) ? attackCoeff : releaseCoeff;
            current = target + c * (current - target);
            return (float) current;
        }

        public float getCurrentValue() { return (float) current; }

        public float getTargetValue() { return (float) target; }

        public boolean isSmoothing() { return Math.abs(current - target) > EPSILON; }

        public void skip() { current = target; }
    }

    /** Rate limiter: caps the change per sample. A rate of 0 or less freezes the value. */
    public static final class SlewLimiter
    {
        public static final float EPSILON = 1e-6f;

        private double maxDelta = 0.0;
        private double current = 0.0;
        private double target = 0.0;

        public void reset(double sampleRate, float maxRatePerSecond)
        {
            reset(sampleRate, maxRatePerSecond, 0.0f);
        }

        public void reset(double sampleRate, float maxRatePerSecond, float initialValue)
        {
            maxDelta = max(0.0, (double) maxRatePerSecond / sampleRate);
            current = initialValue;
            target = initialValue;
        }

        public void setTargetValue(float newTarget) { target = newTarget; }

        public float getNextValue()
        {
            double delta = target - current;
            delta = DspMath.clamp(delta, -maxDelta, maxDelta);
            current += delta;
            return (float) current;
        }

        public float getCurrentValue() { return (float) current; }

        public float getTargetValue() { return (float) target; }

        public boolean isSmoothing() { return Math.abs(current - target) > EPSILON; }

        public void skip() { current = target; }
    }

    /**
     * Second-order state variable filter (TPT) smoother, its cutoff at
     * 1 / (2 pi tau). Settled when both the error and the band-pass (the
     * velocity) are under epsilon, so an overshoot is never cut short.
     */
    public static final class StateVariableSmoother
    {
        public static final float EPSILON = 1e-6f;

        private double v1 = 0.0;
        private double v2 = 0.0;
        private double g = 0.0;
        private double k = 0.0;
        private double a1 = 0.0;
        private double a2 = 0.0;
        private double a3 = 0.0;
        private double target = 0.0;
        private double lowpass = 0.0;
        private double bandpass = 0.0;
        private double highpass = 0.0;

        /** Configures with Q = 0.707 and parks the value at 0. */
        public void reset(double sampleRate, float timeConstantMilliseconds)
        {
            reset(sampleRate, timeConstantMilliseconds, 0.707f, 0.0f);
        }

        /** Configures with the given Q and parks the value at 0. */
        public void reset(double sampleRate, float timeConstantMilliseconds, float q)
        {
            reset(sampleRate, timeConstantMilliseconds, q, 0.0f);
        }

        /**
         * Configures the filter and parks it at the initial value. A time
         * constant of 0 means the fastest usable cutoff; the cutoff is kept
         * under 0.49 times the rate. Q is at least 0.01.
         */
        public void reset(double sampleRate, float timeConstantMilliseconds, float q, float initialValue)
        {
            final double timeConstantSeconds = (double) timeConstantMilliseconds / 1000.0;
            final double fs = sampleRate;
            double fc = timeConstantSeconds > 1e-9 ? 1.0 / ((double) Constants.TWO_PI * timeConstantSeconds) : fs;
            fc = min(fc, fs * 0.49);

            g = Math.tan(Math.PI * fc / fs);
            k = 1.0 / (double) max(q, 0.01f);   // TPT damping uses k = 1/Q.

            a1 = 1.0 / (1.0 + g * (g + k));
            a2 = g * a1;
            a3 = g * a2;

            v1 = 0.0;
            v2 = initialValue;
            target = initialValue;
            lowpass = initialValue;
            bandpass = 0.0;
            highpass = 0.0;
        }

        public void setTargetValue(float newTarget) { target = newTarget; }

        public float getNextValue()
        {
            if (!isSmoothing()) { skip(); return (float) target; }

            final double v0 = target;
            final double v3 = v0 - v2;
            final double v1New = a1 * v1 + a2 * v3;
            final double v2New = v2 + a2 * v1 + a3 * v3;
            v1 = 2.0 * v1New - v1;
            v2 = 2.0 * v2New - v2;

            lowpass = v2New;
            bandpass = v1New;
            highpass = v0 - k * v1New - v2New;

            return (float) lowpass;
        }

        public float getCurrentValue() { return (float) lowpass; }

        public float getTargetValue() { return (float) target; }

        public boolean isSmoothing()
        {
            return Math.abs(lowpass - target) > EPSILON || Math.abs(bandpass) > EPSILON;
        }

        public void skip()
        {
            v1 = 0.0;
            v2 = target;
            lowpass = target;
            bandpass = 0.0;
            highpass = 0.0;
        }

        public float getBandPassOutput() { return (float) bandpass; }

        public float getHighPassOutput() { return (float) highpass; }
    }

    /**
     * Butterworth (Q = 0.707) low-pass smoother, a TDF-II biquad with its
     * cutoff at 1 / (2 pi tau). Settled when both the error and the motion
     * of the last sample are small, so its overshoot is never cut short.
     */
    public static final class ButterworthSmoother
    {
        public static final float EPSILON = 1e-6f;

        private double b0 = 0.0;
        private double b1 = 0.0;
        private double b2 = 0.0;
        private double a1 = 0.0;
        private double a2 = 0.0;
        private double s1 = 0.0;
        private double s2 = 0.0;
        private double target = 0.0;
        private double lastOut = 0.0;
        private double prevOut = 0.0;
        private double velEps = 0.0;

        public void reset(double sampleRate, float timeConstantMilliseconds)
        {
            reset(sampleRate, timeConstantMilliseconds, 0.0f);
        }

        /** Configures the filter and parks it at the initial value (steady state). */
        public void reset(double sampleRate, float timeConstantMilliseconds, float initialValue)
        {
            final double sqrt2 = 1.4142135623730950488;
            final double timeConstantSeconds = (double) timeConstantMilliseconds / 1000.0;
            final double fs = sampleRate;
            double fc = timeConstantSeconds > 1e-9 ? 1.0 / ((double) Constants.TWO_PI * timeConstantSeconds) : fs;
            fc = min(fc, fs * 0.49);
            final double tanw = Math.tan(Math.PI * fc / fs);
            final double tanw2 = tanw * tanw;

            final double denom = 1.0 + sqrt2 * tanw + tanw2;

            b0 = tanw2 / denom;
            b1 = 2.0 * tanw2 / denom;
            b2 = tanw2 / denom;

            a1 = 2.0 * (tanw2 - 1.0) / denom;
            a2 = (1.0 - sqrt2 * tanw + tanw2) / denom;

            // The motion threshold: the peak per-sample velocity of a
            // residual oscillation of amplitude epsilon at fc.
            velEps = (double) EPSILON * ((double) Constants.TWO_PI * fc / fs);

            // TDF-II steady state for a constant input and output v.
            s2 = (b2 - a2) * initialValue;
            s1 = (b1 - a1) * initialValue + s2;
            target = initialValue;
            lastOut = initialValue;
            prevOut = initialValue;
        }

        public void setTargetValue(float newTarget) { target = newTarget; }

        public float getNextValue()
        {
            if (!isSmoothing()) { skip(); return (float) target; }

            final double x0 = target;
            final double y0 = b0 * x0 + s1;
            s1 = b1 * x0 - a1 * y0 + s2;
            s2 = b2 * x0 - a2 * y0;

            prevOut = lastOut;
            lastOut = y0;
            return (float) y0;
        }

        public float getCurrentValue() { return (float) lastOut; }

        public float getTargetValue() { return (float) target; }

        public boolean isSmoothing()
        {
            return Math.abs(lastOut - target) > EPSILON
                || Math.abs(lastOut - prevOut) > velEps;
        }

        public void skip()
        {
            s2 = (b2 - a2) * target;
            s1 = (b1 - a1) * target + s2;
            lastOut = target;
            prevOut = target;
        }
    }

    /**
     * Critically damped smoother: the state variable smoother at Q = 0.5
     * exactly (zeta = 1), so the approach never overshoots.
     */
    public static final class CriticallyDampedSmoother
    {
        public static final float EPSILON = StateVariableSmoother.EPSILON;

        private final StateVariableSmoother svf = new StateVariableSmoother();

        public void reset(double sampleRate, float timeConstantMilliseconds)
        {
            reset(sampleRate, timeConstantMilliseconds, 0.0f);
        }

        public void reset(double sampleRate, float timeConstantMilliseconds, float initialValue)
        {
            svf.reset(sampleRate, timeConstantMilliseconds, 0.5f, initialValue);
        }

        public void setTargetValue(float newTarget) { svf.setTargetValue(newTarget); }

        public float getNextValue() { return svf.getNextValue(); }

        public float getCurrentValue() { return svf.getCurrentValue(); }

        public float getTargetValue() { return svf.getTargetValue(); }

        public boolean isSmoothing() { return svf.isSmoothing(); }

        public void skip() { svf.skip(); }

        public float getBandPassOutput() { return svf.getBandPassOutput(); }

        public float getHighPassOutput() { return svf.getHighPassOutput(); }
    }

    /**
     * Cubic glide that lands exactly on the target after a fixed time, with
     * a continuous slope.
     * <p>
     * Each new target starts a cubic Hermite segment from the current value
     * and velocity to the target, arriving with zero velocity after exactly
     * the ramp time (rounded to whole samples). From rest the path is the
     * smoothstep 3u^2 - 2u^3: monotonic, with its 10-90% rise at 0.61 of the
     * ramp time. A retarget mid-glide keeps the current velocity, clipped to
     * the monotone limit (three times the remaining distance per ramp) so the
     * glide never passes the new target. Non-finite targets are ignored.
     */
    public static final class HermiteSmoother
    {
        private double current = 0.0;
        private double target = 0.0;
        private double start = 0.0;
        private double slope0 = 0.0;
        private double invSteps = 1.0;
        private int totalSteps = 0;
        private int stepsDone = 0;
        private int rampSteps = 0;

        public void reset(double sampleRate, float rampTimeMilliseconds)
        {
            reset(sampleRate, rampTimeMilliseconds, 0.0f);
        }

        /** Configures the ramp, rounded to whole samples (NaN or negative: none), and parks the value. */
        public void reset(double sampleRate, float rampTimeMilliseconds, float initialValue)
        {
            final double steps = sampleRate * (double) rampTimeMilliseconds / 1000.0;
            rampSteps = steps > 0.0 ? (int) min(steps + 0.5, 1.0e9) : 0;
            current = target = start = initialValue;
            slope0 = 0.0;
            totalSteps = stepsDone = 0;
            invSteps = 1.0;
        }

        public void setTargetValue(float newTarget)
        {
            if (!Float.isFinite(newTarget) || (double) newTarget == target) return;
            // Velocity of the segment in flight at its current position, in
            // value per sample.
            double velocity = 0.0;
            if (stepsDone < totalSteps)
            {
                final double u = stepsDone * invSteps;
                final double d = target - start;
                velocity = ((6.0 * u - 6.0 * u * u) * d + (3.0 * u * u - 4.0 * u + 1.0) * slope0) * invSteps;
            }
            target = newTarget;
            start = current;
            if (rampSteps <= 0)
            {
                current = start = target;
                slope0 = 0.0;
                totalSteps = stepsDone = 0;
                return;
            }
            totalSteps = rampSteps;
            stepsDone = 0;
            invSteps = 1.0 / totalSteps;
            // Start velocity in value per segment, clipped at three times the
            // distance (Fritsch-Carlson limit).
            final double d = target - start;
            double m = velocity * totalSteps;
            if (d > 0.0) m = min(m, 3.0 * d);
            else if (d < 0.0) m = max(m, 3.0 * d);
            else m = 0.0;
            slope0 = m;
        }

        public float getNextValue()
        {
            if (stepsDone >= totalSteps) return (float) current;
            if (++stepsDone == totalSteps)
            {
                current = target;   // exact arrival
                return (float) current;
            }
            final double u = stepsDone * invSteps;
            final double u2 = u * u;
            final double d = target - start;
            // Hermite basis with p(0) = start, p'(0) = slope0, p(1) = target, p'(1) = 0.
            current = start + d * (3.0 * u2 - 2.0 * u2 * u) + slope0 * (u2 * u - 2.0 * u2 + u);
            return (float) current;
        }

        public float getCurrentValue() { return (float) current; }

        public float getTargetValue() { return (float) target; }

        public boolean isSmoothing() { return stepsDone < totalSteps; }

        public void skip()
        {
            current = start = target;
            slope0 = 0.0;
            totalSteps = stepsDone = 0;
        }
    }

    // ==========================================================================

    /** The larger of two values, the first one when they compare equal or either is NaN. */
    private static double max(double x, double y) { return (x < y) ? y : x; }

    /** The smaller of two values, the first one when they compare equal or either is NaN. */
    private static double min(double x, double y) { return (y < x) ? y : x; }

    /** The larger of two floats, the first one when they compare equal or either is NaN. */
    private static float max(float x, float y) { return (x < y) ? y : x; }
}
