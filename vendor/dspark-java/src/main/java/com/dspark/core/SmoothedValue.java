package com.dspark.core;

/**
 * Click-free parameter smoother for real-time audio.
 * <p>
 * Ramps a value toward its target with a configurable curve. Intended to
 * be advanced once per sample inside the processing loop. Not thread-safe
 * by design (no memory barriers in the hot path): update the target from
 * the audio thread, e.g. at the start of each block.
 */
public final class SmoothedValue
{
    public enum Type
    {
        /** One-pole IIR — natural, musical interpolation. */
        EXPONENTIAL,
        /** Constant-velocity ramp with exact arrival. */
        LINEAR,
        /** Instant snapping (no smoothing). */
        DISABLED,
        /** Adaptive speed (Airwindows-style): fast attack, smooth settle. */
        CHASE
    }

    private static final double EPSILON = 1e-7;

    private double current = 0.0;
    private double target = 0.0;
    private Type type = Type.EXPONENTIAL;

    private double expCoeff = 0.0;
    private double linearRate = 0.0;
    private double rampSamples = 882.0;

    private double chaseSpeed = 350.0;
    private double chaseMultDecay = 0.9999;
    private double chaseAddDecay = 0.01;

    private double sampleRate = 44100.0;
    private double rampTimeMs = 20.0;

    public SmoothedValue() { prepare(sampleRate, rampTimeMs); }

    /** Precomputes coefficients for the given sample rate and ramp time. */
    public void prepare(double sampleRate, double rampTimeMs)
    {
        this.sampleRate = Math.max(1.0, sampleRate);
        this.rampTimeMs = Double.isNaN(rampTimeMs) ? 0.0 : Math.max(0.0, rampTimeMs);
        double tau = this.rampTimeMs / 1000.0;
        expCoeff = tau > 0.0 ? Math.exp(-1.0 / (this.sampleRate * tau)) : 0.0;
        rampSamples = Math.max(1.0, this.rampTimeMs * this.sampleRate / 1000.0);
        updateLinearRate();
        double srRatio = 44100.0 / this.sampleRate;
        chaseMultDecay = Math.pow(0.9999, srRatio);
        chaseAddDecay = 0.01 * srRatio;
    }

    public void setSmoothingType(Type type) { this.type = type; updateLinearRate(); }

    public Type getSmoothingType() { return type; }

    /**
     * Sets the destination value; safe to call continuously. NaN is ignored
     * (it would poison the recursion and the arrival checks permanently).
     */
    public void setTargetValue(double newTarget)
    {
        if (Double.isNaN(newTarget)) return;
        if (newTarget != target)
        {
            target = newTarget;
            updateLinearRate();
            if (type == Type.CHASE) chaseSpeed = 2500.0;
        }
    }

    /** Advances one sample and returns the new smoothed value. */
    public double getNextValue()
    {
        if (current == target) return current;
        if (rampTimeMs <= 0.0) { current = target; return current; }

        switch (type)
        {
            case EXPONENTIAL:
                current = target + expCoeff * (current - target);
                break;
            case LINEAR:
                if (current < target) current = Math.min(current + linearRate, target);
                else current = Math.max(current - linearRate, target);
                break;
            case DISABLED:
                current = target;
                break;
            case CHASE:
                chaseSpeed = Math.max(350.0, Math.min(2500.0, chaseSpeed * chaseMultDecay - chaseAddDecay));
                current = (current * chaseSpeed + target) / (chaseSpeed + 1.0);
                break;
        }

        // Exact arrival: snap once within a relative epsilon of the target.
        // Relative because with large magnitudes (e.g. a frequency of 10000)
        // a purely absolute threshold would be finer than the float ulp.
        double eps = EPSILON * Math.max(1.0, Math.abs(target));
        if (Math.abs(current - target) < eps) current = target;
        return current;
    }

    public double getCurrentValue() { return current; }

    public double getTargetValue() { return target; }

    public boolean isSmoothing() { return current != target; }

    /** Jumps instantly to the target. */
    public void skip() { current = target; chaseSpeed = 350.0; }

    /** Hard-resets both current and target to {@code value}. */
    public void reset(double value) { current = target = value; chaseSpeed = 350.0; }

    private void updateLinearRate() { linearRate = Math.abs(target - current) / rampSamples; }
}
