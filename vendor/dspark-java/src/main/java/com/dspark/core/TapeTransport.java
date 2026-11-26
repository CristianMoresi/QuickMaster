package com.dspark.core;

/**
 * A tape transport as a speed state: the delay of a moving tape, with the
 * wow and flutter of its capstan and reels.
 * <p>
 * The speed of the tape is a state relative to the nominal speed. A change
 * of the nominal speed ({@link #setSpeed}) leaves the tape running at the old
 * speed, which glides to the new one with inertia (one time constant to
 * speed up, another to slow down). The speed deviates by a smoothed noise,
 * the capstan's rotation (with some of its second harmonic) and the reel's
 * rotation, at rates that follow the speed of the moment, and by a scrape
 * band; the deviation is deeper at slower speeds (by the square root of the
 * ratio to 15 ips). The reproduce pitch is the speed of the moment: the read
 * point advances by one plus the deviation per sample, so the delay moves by
 * minus the deviation; a slow restoring term holds it at the head distance
 * over the speed, and its change per sample is bounded.
 * <p>
 * An echo's delay time is set the same way ({@link #setTargetDelay}): the
 * head distance is fixed, so a delay of D samples is the speed nominal / D,
 * which the tape reaches with the same inertia (the pitch bends while it
 * glides, as on a tape echo whose speed is changed).
 * <p>
 * The deviation is worked out every 16 samples (the rotations' sines, the
 * noises and the scrape at that rate, their filters at its coefficients and
 * their inputs scaled to keep their spectra) and followed by a straight line
 * between: under 100 Hz it is the deviation of every sample to better than
 * -80 dB, at a sixteenth of the trigonometry and the noise.
 * <p>
 * The noise comes from a 32-bit linear congruential generator
 * (x = 1664525 x + 1013904223, seeded 0x2C1B3C6D at every reset), one draw
 * per control point. Not thread-safe: everything runs on the owner's
 * processing thread.
 */
public final class TapeTransport
{
    /** The transport's constants. */
    public static final class Settings
    {
        /** Inertia when speeding up, in seconds. */
        public double speedUpSeconds = 0.45;
        /** Inertia when slowing down, in seconds. */
        public double slowDownSeconds = 0.7;
        /** Restoring time of the delay to the head distance, in seconds. */
        public double restoreSeconds = 1.5;
        /** Largest delay change per sample (bounds the pitch). */
        public double maxStep = 0.02;
        /** Reel speed deviation at full depth and 15 ips. */
        public double reel = 0.0012;
        /** Capstan speed deviation at full depth and 15 ips. */
        public double capstan = 0.0006;
        /** Share of the capstan's second harmonic. */
        public double capstanSecond = 0.3;
        /** Smoothed noise speed deviation (RMS) at full depth and 15 ips. */
        public double noise = 0.0004;
        /** Scrape speed deviation at full depth and 15 ips. */
        public double scrape = 0.003;

        private void copyFrom(Settings o)
        {
            speedUpSeconds = o.speedUpSeconds;
            slowDownSeconds = o.slowDownSeconds;
            restoreSeconds = o.restoreSeconds;
            maxStep = o.maxStep;
            reel = o.reel;
            capstan = o.capstan;
            capstanSecond = o.capstanSecond;
            noise = o.noise;
            scrape = o.scrape;
        }
    }

    /** Samples between control points. */
    private static final int CONTROL = 16;
    /** 1 / sqrt(CONTROL). */
    private static final double CONTROL_NOISE = 0.25;
    /** Capstan rotation: 5 Hz at 15 ips. */
    private static final double CAPSTAN_HZ_PER_IPS = 1.0 / 3.0;
    /** Reel rotation: 0.6 Hz at 15 ips. */
    private static final double REEL_HZ_PER_IPS = 0.04;
    /** Seed of the noise generator. */
    private static final int SEED = 0x2C1B3C6D;

    private final Settings s = new Settings();
    private double fs = 48000.0;
    private double nominal = 96.0;
    private double ips = 15.0;
    private double up = 0.0;
    private double down = 0.0;
    private double restore = 0.0;
    private double noiseA = 0.0;
    private double noiseNorm = 1.0;
    private double scrapeA1 = 0.0;
    private double scrapeA2 = 0.0;
    private double lo = 3.0;
    private double hi = 1e12;
    private double speed = 1.0;
    private double speedTarget = 1.0;
    private double delay = 96.0;
    private double capPhase = 0.0;
    private double reelPhase = 0.0;
    private double noise1 = 0.0;
    private double noise2 = 0.0;
    private double scrape1 = 0.0;
    private double scrape2 = 0.0;
    private int rng = SEED;
    private double devFrom = 0.0;
    private double devTo = 0.0;
    private int controlAt = 0;

    /**
     * Sets the rate, the head distance (as a delay at the nominal speed) and
     * the nominal speed with the default constants; resets.
     *
     * @see #prepare(double, double, double, Settings)
     */
    public void prepare(double sampleRate, double nominalSamples, double ips)
    {
        prepare(sampleRate, nominalSamples, ips, null);
    }

    /**
     * Sets the rate, the head distance (as a delay at the nominal speed), the
     * nominal speed and the constants; resets. The delay limits return to
     * their defaults (3 samples up).
     *
     * @param sampleRate     rate in hertz (non-finite or non-positive values are ignored)
     * @param nominalSamples the delay at the nominal speed, in samples (at least 1)
     * @param ips            nominal speed, inches per second (at least 0.1)
     * @param settings       the constants (copied; null for the defaults)
     */
    public void prepare(double sampleRate, double nominalSamples, double ips, Settings settings)
    {
        if (!Double.isFinite(sampleRate) || !(sampleRate > 0.0)) return;
        fs = sampleRate;
        s.copyFrom(settings != null ? settings : new Settings());
        nominal = max(1.0, nominalSamples);
        this.ips = max(0.1, ips);
        final double dt = 1.0 / fs;
        up = Math.exp(-dt / s.speedUpSeconds);
        down = Math.exp(-dt / s.slowDownSeconds);
        restore = dt / s.restoreSeconds;
        // The noises and the scrape run at the control rate.
        final double dc = (double) CONTROL * dt;
        noiseA = Math.exp(-2.0 * Math.PI * 3.0 * dc);
        scrapeA1 = Math.exp(-2.0 * Math.PI * 90.0 * dc);
        scrapeA2 = Math.exp(-2.0 * Math.PI * 40.0 * dc);
        {
            // RMS of a uniform noise in [-1, 1) through two such one-poles.
            final double a = noiseA;
            final double gain = (1.0 - a) * (1.0 + a * a) / ((1.0 + a) * (1.0 + a) * (1.0 + a));
            noiseNorm = 1.0 / (CONTROL_NOISE * Math.sqrt(max(1e-30, gain / 3.0)));
        }
        lo = 3.0;
        hi = 1e12;
        reset();
    }

    /** Speed at nominal, delay at the head distance, phases and noise from the start. */
    public void reset()
    {
        speed = speedTarget = 1.0;
        delay = nominal;
        capPhase = reelPhase = 0.0;
        noise1 = noise2 = scrape1 = scrape2 = 0.0;
        rng = SEED;
        devFrom = devTo = 0.0;
        controlAt = 0;
    }

    /**
     * A new nominal speed: the tape glides to it from the speed it has.
     * Non-finite, non-positive or unchanged speeds are ignored.
     */
    public void setSpeed(double ips)
    {
        if (!Double.isFinite(ips) || !(ips > 0.0) || ips == this.ips) return;
        speed *= this.ips / ips;
        this.ips = ips;
    }

    /** A new nominal speed, taken at once (no glide). Non-finite or non-positive speeds are ignored. */
    public void setSpeedNow(double ips)
    {
        if (!Double.isFinite(ips) || !(ips > 0.0)) return;
        this.ips = ips;
        speed = speedTarget = 1.0;
    }

    /**
     * A delay to glide to, in samples: the speed nominal / samples, reached
     * with the transport's inertia (the head distance stays). Non-finite
     * values are ignored.
     */
    public void setTargetDelay(double samples)
    {
        if (!Double.isFinite(samples)) return;
        speedTarget = nominal / DspMath.clamp(samples, max(lo, 1e-3), hi);
    }

    /** A delay taken at once: the speed and the delay jump to it. Non-finite values are ignored. */
    public void setTargetDelayNow(double samples)
    {
        if (!Double.isFinite(samples)) return;
        setTargetDelay(samples);
        speed = speedTarget;
        delay = DspMath.clamp(samples, lo, hi);
    }

    /** The range the delay stays in, in samples (default from 3 up). */
    public void setLimits(double lo, double hi)
    {
        this.lo = max(0.0, lo);
        this.hi = max(this.lo, hi);
        delay = DspMath.clamp(delay, this.lo, this.hi);
    }

    /** Starts the delay from a value (switching in from another reader). */
    public void syncDelay(double samples) { delay = DspMath.clamp(samples, lo, hi); }

    /** @return the delay reached, in samples */
    public double getDelay() { return delay; }

    /** @return the head distance, as the delay at the nominal speed */
    public double getNominal() { return nominal; }

    /** @return the speed of the moment relative to the nominal speed */
    public double getSpeedRatio() { return speed; }

    /**
     * The delay for the next sample.
     *
     * @param depth depth of the deviation, 0 to 1 (the transport keeps moving at 0)
     * @return the delay in samples
     */
    public double next(double depth)
    {
        speed = speedTarget + ((speed < speedTarget) ? up : down) * (speed - speedTarget);
        if (Math.abs(speed - speedTarget) < 1e-12) speed = speedTarget;
        // The deviation of the control point ahead; a straight line to it.
        if (controlAt == 0) advanceControl(depth);
        final double share = (double) (++controlAt) / (double) CONTROL;
        if (controlAt == CONTROL) controlAt = 0;
        final double deviation = devFrom + (devTo - devFrom) * share;
        final double target = nominal / speed;
        final double step = DspMath.clamp(-deviation + restore * (target - delay), -s.maxStep, s.maxStep);
        delay = DspMath.clamp(delay + step, lo, hi);
        return delay;
    }

    /**
     * The rotations, the noises and the scrape a control step on, and the
     * deviation there (the next stretch heads to it).
     */
    private void advanceControl(double depth)
    {
        final double ipsNow = ips * speed;
        final double dc = (double) CONTROL / fs;
        capPhase += CAPSTAN_HZ_PER_IPS * ipsNow * dc;
        capPhase -= Math.floor(capPhase);
        reelPhase += REEL_HZ_PER_IPS * ipsNow * dc;
        reelPhase -= Math.floor(reelPhase);
        rng = rng * 1664525 + 1013904223;
        // One draw per control step: scaled by 1 / sqrt(CONTROL) it holds
        // the spectrum the filters gave a draw per sample.
        final double n = ((double) (rng >>> 8) / 8388608.0 - 1.0) * CONTROL_NOISE;
        noise1 = noiseA * noise1 + (1.0 - noiseA) * n;
        noise2 = noiseA * noise2 + (1.0 - noiseA) * noise1;
        scrape1 = scrapeA1 * scrape1 + (1.0 - scrapeA1) * n;
        scrape2 = scrapeA2 * scrape2 + (1.0 - scrapeA2) * n;
        devFrom = devTo;
        devTo = deviationNow(depth);
    }

    /** The deviation at the control point. */
    private double deviationNow(double depth)
    {
        if (!(depth > 0.0)) return 0.0;
        final double twoPi = 2.0 * Math.PI;
        final double cap = twoPi * capPhase;
        final double reel = s.reel != 0.0 ? s.reel * Math.sin(twoPi * reelPhase) : 0.0;
        return depth * Math.sqrt(15.0 / ips)
             * (reel
              + s.capstan * (Math.sin(cap) + s.capstanSecond * Math.sin(2.0 * cap))
              + s.noise * noiseNorm * noise2
              + s.scrape * (scrape1 - scrape2));
    }

    /** The larger of two values, the first one when they compare equal or either is NaN. */
    private static double max(double x, double y)
    {
        return (x < y) ? y : x;
    }
}
