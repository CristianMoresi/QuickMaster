package com.dspark.core;

import java.util.Arrays;

/**
 * One second-order section as a normalized ladder (A. H. Gray and J. D.
 * Markel, "A Normalized Digital Filter Structure", IEEE Trans. ASSP 23(3),
 * 1975): two rotations and three taps, passive under any change of its
 * coefficients.
 * <p>
 * The denominator {@code 1 + a1 z^-1 + a2 z^-2} is realised as two plane
 * rotations, one per reflection coefficient ({@code k2 = a2},
 * {@code k1 = a1 / (1 + a2)}), and the numerator as three taps on the signals
 * between them:
 * <pre>
 *   f1 = c2 u  - s2 x2        g2 = s2 u  + c2 x2
 *   f0 = c1 f1 - s1 x1        g1 = s1 f1 + c1 x1
 *   y  = v0 f0 + v1 g1 + v2 g2
 *   x2 &lt;- g1                  x1 &lt;- f0
 * </pre>
 * with {@code s_i = k_i} and {@code c_i = sqrt(1 - k_i^2)}. The taps of a
 * numerator {@code b0 + b1 z^-1 + b2 z^-2} are {@code v2 = b2},
 * {@code v1 = (b1 - b2 a1) / c2} and
 * {@code v0 = (b0 - b2 a2 - (b1 - b2 a1) k1) / (c1 c2)}.
 * <p>
 * The step from {@code (u, x1, x2)} to {@code (g2, x1, x2)} is orthogonal, so
 * with no input the energy of the memory can only leave, whatever the angles
 * are and however they change from one sample to the next. A linear ramp
 * between two settings keeps each pair {@code (c, s)} on or inside the unit
 * circle, so it is passive along the way as well. The price is eleven
 * products a sample against five: a filter that sits still is better served
 * by {@link BiquadFilter} or {@link BiquadCascade}.
 */
public final class NormalizedLadder
{
    /** The two rotations and the three taps (the identity by default). */
    public static final class Coeffs
    {
        /** Inner rotation: s1 = k1 = a1 / (1 + a2). */
        public double c1 = 1.0, s1 = 0.0;
        /** Outer rotation: s2 = k2 = a2. */
        public double c2 = 1.0, s2 = 0.0;
        /** Taps on f0, g1 and g2. */
        public double v0 = 1.0, v1 = 0.0, v2 = 0.0;

        /** The identity ladder. */
        public Coeffs()
        {
        }

        /** A copy of another ladder. */
        public Coeffs(Coeffs o)
        {
            set(o);
        }

        /** Copies another ladder's rotations and taps into this one. */
        public void set(Coeffs o)
        {
            c1 = o.c1;
            s1 = o.s1;
            c2 = o.c2;
            s2 = o.s2;
            v0 = o.v0;
            v1 = o.v1;
            v2 = o.v2;
        }
    }

    private static final double LIMIT = 1.0 - 1e-12;

    private final int maxChannels;
    private final Coeffs current = new Coeffs();
    private final Coeffs target = new Coeffs();
    private final Coeffs step = new Coeffs();
    private int rampLeft = 0;
    private final double[] x1;       // f0 one sample ago
    private final double[] x2;       // g1 one sample ago

    // Scratch of the block paths.
    private final Coeffs walk = new Coeffs();
    private final Coeffs after = new Coeffs();

    /** A section for up to 8 channels. */
    public NormalizedLadder()
    {
        this(8);
    }

    /**
     * A section for up to {@code maxChannels} channels.
     *
     * @param maxChannels largest number of channels (at least 1)
     */
    public NormalizedLadder(int maxChannels)
    {
        if (maxChannels < 1)
            throw new IllegalArgumentException("maxChannels must be >= 1; got " + maxChannels);
        this.maxChannels = maxChannels;
        x1 = new double[maxChannels];
        x2 = new double[maxChannels];
    }

    /**
     * The ladder of a biquad. A set whose poles are on or outside the unit
     * circle has a reflection coefficient of magnitude one or more; it is
     * brought just inside, so the section stays passive whatever it is given.
     *
     * @param b normalised biquad coefficients
     * @return rotations and taps with the same transfer function
     */
    public static Coeffs fromBiquad(BiquadCoeffs b)
    {
        final Coeffs l = new Coeffs();
        fromBiquad(b, l);
        return l;
    }

    /** {@link #fromBiquad(BiquadCoeffs)} into an existing ladder. */
    public static void fromBiquad(BiquadCoeffs b, Coeffs l)
    {
        final double k2 = DspMath.clamp(b.a2, -LIMIT, LIMIT);
        final double k1 = DspMath.clamp(b.a1 / (1.0 + k2), -LIMIT, LIMIT);
        // The denominator the rotations realise (the given one for a stable set).
        final double a1 = k1 * (1.0 + k2);
        final double a2 = k2;
        l.s1 = k1;
        l.c1 = Math.sqrt(1.0 - k1 * k1);
        l.s2 = k2;
        l.c2 = Math.sqrt(1.0 - k2 * k2);
        l.v2 = b.b2;
        final double middle = b.b1 - b.b2 * a1;
        l.v1 = middle / l.c2;
        l.v0 = (b.b0 - b.b2 * a2 - middle * k1) / (l.c1 * l.c2);
    }

    /**
     * The biquad a ladder realises (for curves and checks).
     *
     * @param l rotations and taps
     * @return normalised biquad coefficients
     */
    public static BiquadCoeffs toBiquad(Coeffs l)
    {
        final double a2 = l.s2;
        final double a1 = l.s1 * (1.0 + l.s2);
        final double b2 = l.v2;
        final double b1 = l.v2 * a1 + l.v1 * l.c2;
        final double b0 = l.v2 * a2 + l.v1 * l.c2 * l.s1 + l.v0 * l.c1 * l.c2;
        return new BiquadCoeffs(b0, b1, b2, a1, a2);
    }

    /** Makes a biquad's response the section's own from the next sample; ends a ramp. */
    public void setCoeffs(BiquadCoeffs b)
    {
        fromBiquad(b, current);
        target.set(current);
        rampLeft = 0;
    }

    /** Sets rotations and taps directly from the next sample; ends a ramp. */
    public void setLadder(Coeffs l)
    {
        current.set(l);
        target.set(l);
        rampLeft = 0;
    }

    /**
     * Moves the rotations and the taps linearly to those of a biquad. The ramp
     * starts from the values in use and lands exactly on the target after
     * {@code samples} samples; along it each rotation stays on or inside the
     * unit circle.
     *
     * @param b       response to arrive at
     * @param samples length of the ramp in samples; 0 or less sets it at once
     */
    public void rampCoeffs(BiquadCoeffs b, int samples)
    {
        if (samples <= 0)
        {
            setCoeffs(b);
            return;
        }
        fromBiquad(b, target);
        rampLeft = samples;
        final double inverse = 1.0 / (double) samples;
        step.c1 = (target.c1 - current.c1) * inverse;
        step.s1 = (target.s1 - current.s1) * inverse;
        step.c2 = (target.c2 - current.c2) * inverse;
        step.s2 = (target.s2 - current.s2) * inverse;
        step.v0 = (target.v0 - current.v0) * inverse;
        step.v1 = (target.v1 - current.v1) * inverse;
        step.v2 = (target.v2 - current.v2) * inverse;
    }

    /** Rotations and taps in use now (a copy; mid ramp, where the ramp is). */
    public Coeffs getLadder()
    {
        return new Coeffs(current);
    }

    /** Whether the coefficients are moving. */
    public boolean isRamping()
    {
        return rampLeft > 0;
    }

    /** Largest number of channels. */
    public int getMaxChannels()
    {
        return maxChannels;
    }

    /** Clears the memory of every channel. Coefficients and ramp are kept. */
    public void reset()
    {
        Arrays.fill(x1, 0.0);
        Arrays.fill(x2, 0.0);
    }

    /**
     * Makes this section a copy of another for as many channels: its
     * coefficients, ramp and memory. Allocates nothing.
     *
     * @param other a section with the same maximum channels
     * @throws IllegalArgumentException if the channel counts differ
     */
    public void copyFrom(NormalizedLadder other)
    {
        if (other == this) return;
        if (other.maxChannels != maxChannels)
            throw new IllegalArgumentException("copyFrom needs a section with the same channel count");
        current.set(other.current);
        target.set(other.target);
        step.set(other.step);
        rampLeft = other.rampLeft;
        System.arraycopy(other.x1, 0, x1, 0, maxChannels);
        System.arraycopy(other.x2, 0, x2, 0, maxChannels);
    }

    /** The energy in a channel's memory, x1^2 + x2^2 (it only falls with no input). */
    public double getStateEnergy(int channel)
    {
        final int ch = Math.max(0, Math.min(channel, maxChannels - 1));
        return x1[ch] * x1[ch] + x2[ch] * x2[ch];
    }

    /** Runs every channel of a planar buffer through the section in place, from index 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, channels.length, 0, numSamples);
    }

    /** Runs every channel of a planar buffer through the section in place, from {@code offset}. */
    public void process(double[][] channels, int offset, int numSamples)
    {
        process(channels, channels.length, offset, numSamples);
    }

    /**
     * Runs a block through the section, in place. Channels beyond the
     * section's maximum are left untouched; every channel walks the same
     * ramp, which moves on by the block's samples. A memory a non-finite
     * sample has reached is cleared at the end of the block.
     *
     * @param channels    the channels
     * @param numChannels channels to process (the first ones of {@code channels})
     * @param offset      index of the first sample in each channel
     * @param numSamples  samples to process
     */
    public void process(double[][] channels, int numChannels, int offset, int numSamples)
    {
        final int nCh = Math.min(numChannels, maxChannels);
        if (nCh <= 0 || numSamples <= 0) return;

        final int moving = Math.min(rampLeft, numSamples);
        after.set(current);
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] data = channels[ch];
            final Coeffs c = walk;
            c.set(current);
            for (int i = 0; i < moving; ++i)
            {
                addStep(c);
                data[offset + i] = advance(data[offset + i], c, ch);
            }
            if (moving > 0 && moving == rampLeft) c.set(target);   // landed: exactly the target from here
            for (int i = moving; i < numSamples; ++i)
                data[offset + i] = advance(data[offset + i], c, ch);
            recover(ch);
            after.set(c);
        }
        current.set(after);
        rampLeft -= moving;
    }

    /**
     * One sample of one channel, in double, with the coefficients as they
     * are. Ramps advance in {@link #process}, or by the caller's
     * {@link #advanceRamp} once per frame.
     *
     * @param input   input sample
     * @param channel channel index in [0, maxChannels)
     */
    public double processSample(double input, int channel)
    {
        return advance(input, current, channel);
    }

    /** {@link #processSegment(double[], int, int, int)} from index 0. */
    public void processSegment(double[] data, int numSamples, int channel)
    {
        processSegment(data, 0, numSamples, channel);
    }

    /**
     * Runs one channel's samples through the section in place, walking the
     * ramp from where it stands without moving it on. For owners that run
     * their channels one after another over the same frames: every channel
     * walks the same coefficients, and {@link #advanceRamp} by the same count,
     * once all of them have run, moves the ramp on (the result is
     * {@link #process}'s, to the bit).
     *
     * @param data       samples of the channel, filtered in place
     * @param offset     index of the first sample
     * @param numSamples number of samples
     * @param channel    channel whose memory the samples run through
     */
    public void processSegment(double[] data, int offset, int numSamples, int channel)
    {
        if (numSamples <= 0) return;
        final int moving = Math.min(rampLeft, numSamples);
        final Coeffs c = walk;
        c.set(current);
        for (int i = 0; i < moving; ++i)
        {
            addStep(c);
            data[offset + i] = advance(data[offset + i], c, channel);
        }
        if (moving > 0 && moving == rampLeft) c.set(target);
        for (int i = moving; i < numSamples; ++i)
            data[offset + i] = advance(data[offset + i], c, channel);
        recover(channel);
    }

    /**
     * Moves the ramp on by a number of samples (for {@link #processSample}
     * callers).
     *
     * @param samples samples gone by; a ramp with fewer left lands on its target
     */
    public void advanceRamp(int samples)
    {
        if (rampLeft <= 0 || samples <= 0) return;
        if (samples >= rampLeft)
        {
            current.set(target);
            rampLeft = 0;
            return;
        }
        rampLeft -= samples;
        for (int i = 0; i < samples; ++i) addStep(current);
    }

    private void addStep(Coeffs c)
    {
        c.c1 += step.c1;
        c.s1 += step.s1;
        c.c2 += step.c2;
        c.s2 += step.s2;
        c.v0 += step.v0;
        c.v1 += step.v1;
        c.v2 += step.v2;
    }

    /** The two rotations and the taps for one sample of one channel. */
    private double advance(double u, Coeffs c, int ch)
    {
        final double m1 = x1[ch], m2 = x2[ch];
        final double f1 = c.c2 * u - c.s2 * m2;
        final double g2 = c.s2 * u + c.c2 * m2;
        final double f0 = c.c1 * f1 - c.s1 * m1;
        final double g1 = c.s1 * f1 + c.c1 * m1;
        x2[ch] = g1;
        x1[ch] = f0;
        return c.v0 * f0 + c.v1 * g1 + c.v2 * g2;
    }

    private void recover(int ch)
    {
        if (!Double.isFinite(x1[ch] + x2[ch]))
        {
            x1[ch] = 0.0;
            x2[ch] = 0.0;
        }
    }
}
