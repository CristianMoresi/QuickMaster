package com.dspark.core;

import java.util.Arrays;

/**
 * A whole filter of order N as one normalized ladder (Gray and Markel),
 * designed from its polynomials, without roots.
 * <p>
 * The denominator {@code 1 + a1 z^-1 + ... + aN z^-N} is stepped down to its
 * reflection coefficients: {@code k_m} is the last coefficient of the
 * polynomial of order m, and the polynomial of order m - 1 is
 * {@code (A_m - k_m B_m) / (1 - k_m^2)}, where {@code B_m} is {@code A_m}
 * reversed. Each {@code k_m} is a plane rotation
 * ({@code s = k_m, c = sqrt(1 - k_m^2)}); the input enters at the top and
 * runs down through the rotations:
 * <pre>
 *   f_N = u
 *   f_(m-1) = c_m f_m - s_m x_m      g_m = s_m f_m + c_m x_m      (m = N .. 1)
 *   y = v_0 f_0 + v_1 g_1 + ... + v_N g_N
 *   x_1 &lt;- f_0, x_m &lt;- g_(m-1)
 * </pre>
 * The taps of a numerator come down from the top: {@code v_m} is what is left
 * of it at {@code z^-m} over the product of the c above m. The filter is
 * stable when every {@code |k_m| < 1}, which the design checks. As with the
 * second-order {@link NormalizedLadder}, with no input the memory can only
 * lose energy whatever the coefficients do, and a ramp moves every c, s and v
 * linearly with the pairs (c, s) on or inside the unit circle.
 * {@link FilterDesign#bilinearPolynomial} takes an analog network of any order
 * to the digital polynomials this class takes.
 * <p>
 * The block path runs channels two at a time and walks the same coefficients
 * in both; a memory that a non-finite sample has reached is cleared at the
 * end of the block for both channels of its pair.
 */
public final class NormalizedLadderN
{
    /** The ladder's rotations and taps. */
    public static final class Coeffs
    {
        /** c_1 .. c_N (index 0 unused). */
        public final double[] c;
        /** s_1 .. s_N, the reflection coefficients (index 0 unused). */
        public final double[] s;
        /** v_0 .. v_N, the taps. */
        public final double[] v;

        /** All zero, for a filter of an order. */
        public Coeffs(int order)
        {
            c = new double[order + 1];
            s = new double[order + 1];
            v = new double[order + 1];
        }

        /** A copy of another ladder. */
        public Coeffs(Coeffs o)
        {
            c = o.c.clone();
            s = o.s.clone();
            v = o.v.clone();
        }

        /** Copies another ladder of the same order into this one. */
        public void set(Coeffs o)
        {
            System.arraycopy(o.c, 0, c, 0, c.length);
            System.arraycopy(o.s, 0, s, 0, s.length);
            System.arraycopy(o.v, 0, v, 0, v.length);
        }
    }

    private final int order;
    private final int maxChannels;
    private final Coeffs current, target, step;
    private int rampLeft = 0;
    private final double[][] state;          // [channel][m]: x_1 .. x_N (index 0 unused)

    // Scratch.
    private final Coeffs designed, walk, after;
    private final double[][] stepped;
    private final double[] num, prod, g;

    /** A filter of an order for up to 8 channels. */
    public NormalizedLadderN(int order)
    {
        this(order, 8);
    }

    /**
     * A filter of an order for up to {@code maxChannels} channels. A new
     * filter has every rotation and tap at zero (it outputs silence) until a
     * design is set.
     *
     * @param order       order of the filter, 1 to 16
     * @param maxChannels largest number of channels (at least 1)
     */
    public NormalizedLadderN(int order, int maxChannels)
    {
        if (order < 1 || order > 16)
            throw new IllegalArgumentException("order must be in [1, 16]; got " + order);
        if (maxChannels < 1)
            throw new IllegalArgumentException("maxChannels must be >= 1; got " + maxChannels);
        this.order = order;
        this.maxChannels = maxChannels;
        current = new Coeffs(order);
        target = new Coeffs(order);
        step = new Coeffs(order);
        designed = new Coeffs(order);
        walk = new Coeffs(order);
        after = new Coeffs(order);
        state = new double[maxChannels][order + 1];
        stepped = new double[order + 1][order + 1];
        num = new double[order + 1];
        prod = new double[order + 1];
        g = new double[order + 1];
    }

    /** Order of the filter. */
    public int getOrder()
    {
        return order;
    }

    /** Largest number of channels. */
    public int getMaxChannels()
    {
        return maxChannels;
    }

    /**
     * The ladder of a digital filter {@code b(z) / a(z)}, each
     * {@code order + 1} coefficients from {@code z^0} ({@code a[0]} scales
     * both).
     *
     * @return false, and {@code out} untouched, when the denominator is not
     *         stable (a reflection coefficient of 1 or more) or a[0] is 0
     */
    public static boolean design(int order, double[] b, double[] a, Coeffs out)
    {
        final Coeffs l = new Coeffs(order);
        if (!designInto(order, b, a, l, new double[order + 1][order + 1], new double[order + 1], new double[order + 1]))
            return false;
        out.set(l);
        return true;
    }

    private static boolean designInto(int order, double[] b, double[] a, Coeffs l,
                                      double[][] stepped, double[] num, double[] prod)
    {
        if (!(Math.abs(a[0]) > 0.0)) return false;
        for (double[] row : stepped) Arrays.fill(row, 0.0);
        Arrays.fill(l.c, 0.0);
        Arrays.fill(l.s, 0.0);
        Arrays.fill(l.v, 0.0);
        // The polynomials of every order, top down (kept for the taps).
        for (int i = 0; i <= order; ++i)
        {
            stepped[order][i] = a[i] / a[0];
            num[i] = b[i] / a[0];
        }
        for (int m = order; m >= 1; --m)
        {
            final double[] am = stepped[m];
            final double k = am[m];
            if (!(Math.abs(k) < 1.0)) return false;
            l.s[m] = k;
            l.c[m] = Math.sqrt((1.0 - k) * (1.0 + k));
            final double[] below = stepped[m - 1];
            final double inverse = 1.0 / ((1.0 - k) * (1.0 + k));
            for (int i = 0; i < m; ++i)
                below[i] = (am[i] - k * am[m - i]) * inverse;
        }
        // The taps, from the top: B_m is A_m reversed.
        double above = 1.0;   // the product of the c above m
        for (int m = order; m >= 0; --m)
        {
            prod[m] = above;
            if (m >= 1) above *= l.c[m];
        }
        for (int m = order; m >= 0; --m)
        {
            final double w = num[m];
            l.v[m] = w / prod[m];
            final double[] am = stepped[m];
            for (int i = 0; i <= m; ++i) num[i] -= w * am[m - i];
        }
        return true;
    }

    /** Takes a filter b(z) / a(z) at once; false (nothing changes) if it is not stable. */
    public boolean setCoeffs(double[] b, double[] a)
    {
        if (!designInto(order, b, a, designed, stepped, num, prod)) return false;
        current.set(designed);
        target.set(designed);
        rampLeft = 0;
        return true;
    }

    /** Moves every rotation and tap linearly to a filter's over {@code samples}; false if it is not stable. */
    public boolean rampCoeffs(double[] b, double[] a, int samples)
    {
        if (!designInto(order, b, a, designed, stepped, num, prod)) return false;
        if (samples <= 0)
        {
            current.set(designed);
            target.set(designed);
            rampLeft = 0;
            return true;
        }
        target.set(designed);
        rampLeft = samples;
        final double inverse = 1.0 / (double) samples;
        for (int m = 0; m <= order; ++m)
        {
            step.c[m] = (designed.c[m] - current.c[m]) * inverse;
            step.s[m] = (designed.s[m] - current.s[m]) * inverse;
            step.v[m] = (designed.v[m] - current.v[m]) * inverse;
        }
        return true;
    }

    /** Rotations and taps in use now (a copy). */
    public Coeffs getLadder()
    {
        return new Coeffs(current);
    }

    /** Whether the coefficients are moving. */
    public boolean isRamping()
    {
        return rampLeft > 0;
    }

    /** Clears the memory of every channel. */
    public void reset()
    {
        for (double[] x : state) Arrays.fill(x, 0.0);
    }

    /** The energy of a channel's memory (the sum of the squares of its states). */
    public double getStateEnergy(int channel)
    {
        if (channel < 0 || channel >= maxChannels) return 0.0;
        double e = 0.0;
        for (double x : state[channel]) e += x * x;
        return e;
    }

    /** Runs every channel of a planar buffer in place, from index 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, channels.length, 0, numSamples);
    }

    /** Runs every channel of a planar buffer in place, from {@code offset}. */
    public void process(double[][] channels, int offset, int numSamples)
    {
        process(channels, channels.length, offset, numSamples);
    }

    /**
     * Runs a block in place; a ramp moves on by its samples. Channels beyond
     * the filter's maximum are left untouched.
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
        // Channels go two at a time; each walks the same coefficients.
        for (int ch = 0; ch < nCh; ch += 2)
        {
            final boolean pair = ch + 1 < nCh;
            walk.set(current);
            if (pair)
                runLanes(channels[ch], channels[ch + 1], offset, numSamples, moving, walk, state[ch], state[ch + 1]);
            else
                runLanes(channels[ch], null, offset, numSamples, moving, walk, state[ch], state[ch]);
            after.set(walk);
        }
        current.set(after);
        rampLeft -= moving;
    }

    /**
     * One sample of one channel, in double, with the coefficients as they are
     * (a ramp moves on with {@link #advanceRamp}).
     *
     * @param input   input sample
     * @param channel channel index in [0, maxChannels)
     */
    public double processSample(double input, int channel)
    {
        return advance(input, current, state[channel]);
    }

    /** Moves a ramp on by a number of samples (for {@link #processSample} callers). */
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
        final double n = (double) samples;
        for (int m = 0; m <= order; ++m)
        {
            current.c[m] += step.c[m] * n;
            current.s[m] += step.s[m] * n;
            current.v[m] += step.v[m] * n;
        }
    }

    /** The rotations, top down, the taps and the memory for one sample. */
    private double advance(double u, Coeffs c, double[] x)
    {
        double f = u;
        for (int m = order; m >= 1; --m)
        {
            final double xm = x[m];
            g[m] = c.s[m] * f + c.c[m] * xm;
            f = c.c[m] * f - c.s[m] * xm;
        }
        double y = c.v[0] * f;
        for (int m = 1; m <= order; ++m) y += c.v[m] * g[m];
        for (int m = order; m >= 2; --m) x[m] = g[m - 1];
        x[1] = f;
        return y;
    }

    /**
     * A block of one channel ({@code right} null, both memories the same) or
     * of a pair, {@code moving} samples of them with the ramp's steps first.
     */
    private void runLanes(double[] left, double[] right, int offset, int numSamples, int moving, Coeffs c,
                          double[] memLeft, double[] memRight)
    {
        for (int i = 0; i < moving; ++i)
        {
            for (int m = 0; m <= order; ++m)
            {
                c.c[m] += step.c[m];
                c.s[m] += step.s[m];
                c.v[m] += step.v[m];
            }
            left[offset + i] = advance(left[offset + i], c, memLeft);
            if (right != null) right[offset + i] = advance(right[offset + i], c, memRight);
        }
        if (moving > 0 && moving == rampLeft) c.set(target);
        for (int i = moving; i < numSamples; ++i)
        {
            left[offset + i] = advance(left[offset + i], c, memLeft);
            if (right != null) right[offset + i] = advance(right[offset + i], c, memRight);
        }

        double sum = 0.0;
        for (int m = 1; m <= order; ++m) sum += memLeft[m] + memRight[m];
        if (!Double.isFinite(sum))
        {
            Arrays.fill(memLeft, 0.0);
            Arrays.fill(memRight, 0.0);
        }
    }
}
