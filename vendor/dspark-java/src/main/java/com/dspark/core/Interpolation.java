package com.dspark.core;

/**
 * Fractional-position interpolation for delay lines, tables and buffers.
 * <p>
 * Static interpolators used for interpolated buffer reads and modulated
 * delay lines; none allocates or locks. {@link SincInterpolator} is the
 * high-fidelity reader for resampling a stream (pitch shifting, varispeed),
 * and {@link StretchedSincReader} its band-limited variant for read rates
 * above 1; both own kernel tables built on construction.
 * <pre>
 * | Method    | Points | Quality       | CPU Cost  | Use case                     |
 * |-----------|--------|---------------|-----------|------------------------------|
 * | Linear    | 2      | Low           | Ultra-Low | LFOs, crossfades             |
 * | Hermite   | 4      | Good+         | Low       | Modulated delays (default)   |
 * | Lagrange  | 4      | High          | Low       | Precision fractional reads   |
 * | Allpass   | 2      | Frequency-dep | Low       | Static fractional delays     |
 * | Sinc      | 32     | Transparent   | Medium    | Resampling a stream          |
 * </pre>
 * Polynomial accuracy: Linear reconstructs degree-1 signals exactly, Hermite
 * (Catmull-Rom) degree 2, Lagrange degree 3. Hermite is the recommended
 * default for modulated delay lines (chorus, vibrato, reverb modulation): it
 * is stateless and C1-continuous across segments, so fast {@code frac}
 * changes never destabilise anything, unlike the recursive Allpass
 * interpolator.
 * <p>
 * Two overload families:
 * <ul>
 * <li>Raw-sample overloads (y0..y3, frac): no bounds logic at all, the
 *     fastest path. The container handles buffer wrapping.</li>
 * <li>(buffer, length, position) overloads: treat the buffer as CIRCULAR;
 *     neighbours needed beyond either end wrap around to the other end.
 *     Meant for ring buffers and periodic tables. On a plain linear buffer,
 *     positions within one sample of the edges (4-point methods) blend in
 *     samples from the opposite end. A position outside [0, length) is
 *     clamped to the nearest valid sample (NaN reads position 0).</li>
 * </ul>
 * Threading: all functions are pure and re-entrant; the Allpass state lives
 * in the caller. The sinc readers allocate their tables in the constructor;
 * their reads allocate nothing and are safe from any thread.
 */
public final class Interpolation
{
    private Interpolation()
    {
        // Utility class, not instantiable.
    }

    /**
     * Linear interpolation between two adjacent samples.
     *
     * @param y0    sample at index n
     * @param y1    sample at index n+1
     * @param frac  fractional position (0.0 to 1.0)
     * @return the interpolated sample
     */
    public static double interpolateLinear(double y0, double y1, double frac)
    {
        return y0 + frac * (y1 - y0);
    }

    /**
     * Linear interpolation reading directly from a circular buffer: reading
     * at position length - 0.5 interpolates between the last and the first
     * sample.
     *
     * @param buffer    the buffer
     * @param length    size of the buffer in samples (must be &gt; 0)
     * @param position  absolute read position, 0 &lt;= position &lt; length
     * @return the interpolated sample (0 for a non-positive length)
     */
    public static double interpolateLinear(double[] buffer, int length, double position)
    {
        if (length <= 0) return 0.0;

        // An out-of-range position (including NaN) is clamped before the
        // integer conversion, so the reads stay inside the buffer.
        int idx0;
        double frac;
        if (!(position >= 0.0)) { idx0 = 0; frac = 0.0; }
        else if (position >= (double) length) { idx0 = length - 1; frac = 0.0; }
        else
        {
            idx0 = (int) position;
            frac = position - (double) idx0;
        }

        int idx1 = idx0 + 1;
        if (idx1 >= length) idx1 = 0;

        return interpolateLinear(buffer[idx0], buffer[idx1], frac);
    }

    /**
     * 4-point, 3rd-order Hermite interpolation (optimized x-form).
     * Mathematically identical to Catmull-Rom but evaluated with fewer
     * multiplications than the standard polynomial form.
     *
     * @param y0    sample at index n-1
     * @param y1    sample at index n
     * @param y2    sample at index n+1
     * @param y3    sample at index n+2
     * @param frac  fractional position between y1 and y2 (0.0 to 1.0)
     * @return the interpolated sample
     */
    public static double interpolateHermite(double y0, double y1, double y2, double y3, double frac)
    {
        final double c = (y2 - y0) * 0.5;
        final double v = y1 - y2;
        final double w = c + v;
        final double a = w + v + (y3 - y1) * 0.5;
        final double b = w + a;
        return (((a * frac) - b) * frac + c) * frac + y1;
    }

    /**
     * 4-point Hermite interpolation reading directly from a circular buffer.
     * Interpolates between buffer[floor(position)] and the next sample; the
     * two outer neighbours wrap around the buffer ends.
     *
     * @param buffer    the buffer
     * @param length    size of the buffer in samples (must be &gt; 0)
     * @param position  absolute read position, 0 &lt;= position &lt; length
     * @return the interpolated sample (0 for a non-positive length)
     */
    public static double interpolateHermite(double[] buffer, int length, double position)
    {
        if (length <= 0) return 0.0;

        int idx1;
        double frac;
        if (!(position >= 0.0)) { idx1 = 0; frac = 0.0; }
        else if (position >= (double) length) { idx1 = length - 1; frac = 0.0; }
        else
        {
            idx1 = (int) position;
            frac = position - (double) idx1;
        }

        final int idx0 = (idx1 > 0) ? idx1 - 1 : length - 1;
        int idx2 = idx1 + 1; if (idx2 >= length) idx2 -= length;
        int idx3 = idx2 + 1; if (idx3 >= length) idx3 -= length;

        return interpolateHermite(buffer[idx0], buffer[idx1], buffer[idx2], buffer[idx3], frac);
    }

    /**
     * Alias of {@link #interpolateHermite(double[], int, double)}
     * (Catmull-Rom evaluated in Hermite form).
     */
    public static double interpolateCubic(double[] buffer, int length, double position)
    {
        return interpolateHermite(buffer, length, position);
    }

    /**
     * 4-point Lagrange interpolation from discrete samples. Reconstructs
     * polynomials up to degree 3 exactly (the divisions of the Lagrange basis
     * are precomputed constants).
     *
     * @param y0    sample at index n-1
     * @param y1    sample at index n
     * @param y2    sample at index n+1
     * @param y3    sample at index n+2
     * @param frac  fractional position between y1 and y2 (0.0 to 1.0)
     * @return the interpolated sample
     */
    public static double interpolateLagrange(double y0, double y1, double y2, double y3, double frac)
    {
        final double d = frac;
        final double dm1 = d - 1.0;
        final double dm2 = d - 2.0;
        final double dp1 = d + 1.0;

        final double l0 = -(d * dm1 * dm2) * INV6;
        final double l1 =  (dp1 * dm1 * dm2) * INV2;
        final double l2 = -(dp1 * d * dm2) * INV2;
        final double l3 =  (dp1 * d * dm1) * INV6;

        return l0 * y0 + l1 * y1 + l2 * y2 + l3 * y3;
    }

    private static final double INV6 = 1.0 / 6.0;
    private static final double INV2 = 0.5;

    /**
     * 4-point Lagrange interpolation reading directly from a circular buffer.
     * Interpolates between buffer[floor(position)] and the next sample; the
     * two outer neighbours wrap around the buffer ends.
     *
     * @param buffer    the buffer
     * @param length    size of the buffer in samples (must be &gt; 0)
     * @param position  absolute read position, 0 &lt;= position &lt; length
     * @return the interpolated sample (0 for a non-positive length)
     */
    public static double interpolateLagrange(double[] buffer, int length, double position)
    {
        if (length <= 0) return 0.0;

        int idx1;
        double frac;
        if (!(position >= 0.0)) { idx1 = 0; frac = 0.0; }
        else if (position >= (double) length) { idx1 = length - 1; frac = 0.0; }
        else
        {
            idx1 = (int) position;
            frac = position - (double) idx1;
        }

        final int idx0 = (idx1 > 0) ? idx1 - 1 : length - 1;
        int idx2 = idx1 + 1; if (idx2 >= length) idx2 -= length;
        int idx3 = idx2 + 1; if (idx3 >= length) idx3 -= length;

        return interpolateLagrange(buffer[idx0], buffer[idx1], buffer[idx2], buffer[idx3], frac);
    }

    /**
     * Allpass interpolation (first-order Thiran) for fractional delay.
     * <p>
     * Perfectly flat magnitude response at every frequency, unlike polynomial
     * interpolators (which low-pass); the cost is a frequency-dependent phase
     * response and a recursive state.
     * <p>
     * The interpolator is IIR (recursive): do NOT modulate {@code frac}
     * quickly (e.g. audio-rate LFOs): the state carries history filtered with
     * the previous coefficient and produces loud clicks. Best for static or
     * slowly changing delays; use interpolateHermite for modulated delay
     * lines.
     * <p>
     * The filter is stable for any frac &gt; 0, but as frac approaches 0 the
     * pole approaches z = -1 and the interpolator rings for thousands of
     * samples near Nyquist. Canonical usage keeps the fractional delay in
     * [0.5, 1.5): when the fractional part f of the total delay is below 0.5,
     * read one integer sample earlier and pass frac = f + 1 instead.
     *
     * @param currentSample   the current input sample (x[n])
     * @param previousSample  the previous input sample (x[n-1])
     * @param frac            fractional delay in samples, relative to x[n]
     *                        (values below 0.001 act as 0.001)
     * @param state           allpass filter states (y[n-1]), maintained by the
     *                        caller; initialise to 0 and keep one state per
     *                        delay tap per channel
     * @param stateIndex      index of this tap's state in {@code state}
     * @return the interpolated sample value (y[n])
     */
    public static double interpolateAllpass(double currentSample, double previousSample,
                                            double frac, double[] state, int stateIndex)
    {
        // frac == 0 would place the pole exactly at z = -1 (a marginally
        // stable resonator at Nyquist) and frac == -1 would divide by zero:
        // clamp away from both. The clamp changes the delivered delay only
        // for out-of-range requests.
        final double safeFrac = (frac < 0.001) ? 0.001 : frac;
        final double coeff = (1.0 - safeFrac) / (1.0 + safeFrac);

        double output = coeff * (currentSample - state[stateIndex]) + previousSample;

        // Cut the recursive tail below about -300 dBFS so a decaying state
        // never reaches the denormal range.
        if (Math.abs(output) < 1e-15) output = 0.0;

        state[stateIndex] = output;
        return output;
    }

    /**
     * {@link #interpolateAllpass(double, double, double, double[], int)} with
     * the state in {@code state[0]}.
     */
    public static double interpolateAllpass(double currentSample, double previousSample,
                                            double frac, double[] state)
    {
        return interpolateAllpass(currentSample, previousSample, frac, state, 0);
    }

    /** Sum of a[aOffset + i] * b[bOffset + i] for i in [0, count), in index order. */
    static double dotProduct(double[] a, int aOffset, double[] b, int bOffset, int count)
    {
        double sum = 0.0;
        for (int i = 0; i < count; ++i) sum += a[aOffset + i] * b[bOffset + i];
        return sum;
    }

    /** Modified Bessel I0 (power series, converges for Kaiser betas). */
    static double besselI0(double x)
    {
        double sum = 1.0, term = 1.0;
        final double halfX = x / 2.0;
        for (int k = 1; k < 60; ++k)
        {
            final double f = halfX / (double) k;
            term *= f * f;
            sum += term;
            if (term < sum * 1e-17) break;
        }
        return sum;
    }

    /**
     * 32-tap Kaiser-windowed sinc reader for transparent fractional reads.
     * <p>
     * The reader for resampling a stream at a fixed or varying rate. Its
     * worst-case error against the ideal fractional delay, at the tabulated
     * phases, stays below -96 dB up to 15 kHz at 44.1 and 48 kHz (and
     * 18 kHz at 48 kHz); between the phases the linear phase interpolation
     * raises the 15 kHz figure at 44.1 kHz to about -94 dB. A 4-point cubic
     * (Hermite / Catmull-Rom) reader reaches -24 dB at 10 kHz and -12 dB at
     * 15 kHz, and because its error changes with the fractional position, a
     * moving read point turns it into modulation noise.
     * <p>
     * The kernel is a full-band sinc (cutoff at Nyquist) under a Kaiser
     * window (beta 10), tabulated at 256 fractional phases, each normalized
     * to unit DC gain, and linearly interpolated between phases. Integer
     * positions are an exact identity. Near Nyquist the 32 taps cannot hold
     * the band: at 20 kHz the error is -50 dB at 48 kHz and -17 dB at
     * 44.1 kHz.
     * <p>
     * Reading position intPos + frac needs the samples intPos - 15 through
     * intPos + 16.
     */
    public static final class SincInterpolator
    {
        /** Kernel length. */
        public static final int TAPS = 32;
        /** Samples needed before intPos. */
        public static final int BEFORE = TAPS / 2 - 1;
        /** Samples needed after intPos. */
        public static final int AFTER = TAPS / 2;
        /** Tabulated fractional phases. */
        public static final int PHASES = 256;

        private final double[] table = new double[(PHASES + 1) * TAPS];

        /** Builds the kernel table (allocates). */
        public SincInterpolator()
        {
            final double beta = 10.0;
            final double pi = 3.14159265358979323846;
            final double invI0Beta = 1.0 / besselI0(beta);
            final double halfSpan = (double) TAPS / 2.0;
            final double[] h = new double[TAPS];

            for (int p = 0; p <= PHASES; ++p)
            {
                final int row = p * TAPS;
                final double phase = (double) p / PHASES;
                if (p == 0 || p == PHASES)
                {
                    // Integer positions: an exact identity (sin(pi * k) rounds
                    // to about 1e-16, not 0, so the sinc is not evaluated here).
                    java.util.Arrays.fill(table, row, row + TAPS, 0.0);
                    table[row + (p == 0 ? BEFORE : BEFORE + 1)] = 1.0;
                    continue;
                }
                double sum = 0.0;
                for (int t = 0; t < TAPS; ++t)
                {
                    final double x = (double) (t - BEFORE) - phase;
                    final double r = x / halfSpan;
                    final double window = (r * r < 1.0)
                        ? besselI0(beta * Math.sqrt(1.0 - r * r)) * invI0Beta
                        : 0.0;
                    h[t] = Math.sin(pi * x) / (pi * x) * window;
                    sum += h[t];
                }
                for (int t = 0; t < TAPS; ++t)
                    table[row + t] = h[t] / sum;   // unit DC gain
            }
        }

        /**
         * Interpolates TAPS consecutive samples at x[offset + BEFORE] + frac.
         *
         * @param x       samples intPos - BEFORE through intPos + AFTER start
         *                at {@code x[offset]}
         * @param offset  index of sample intPos - BEFORE
         * @param frac    fractional position in [0, 1)
         * @return the interpolated value
         */
        public double read(double[] x, int offset, double frac)
        {
            final double p = frac * (double) PHASES;
            final int ip = clampInt((int) p, 0, PHASES - 1);
            final double t = p - (double) ip;
            final int h0 = ip * TAPS;
            final int h1 = h0 + TAPS;   // the table holds PHASES + 1 rows
            final double a = dotProduct(table, h0, x, offset, TAPS);
            final double b = dotProduct(table, h1, x, offset, TAPS);
            return a + t * (b - a);
        }

        /**
         * Reads a power-of-two ring buffer at intPos + frac.
         *
         * @param ring    ring storage
         * @param mask    ring size minus one (the size must be a power of two)
         * @param intPos  integer read position (masked, so any value works)
         * @param frac    fractional position in [0, 1)
         * @return the interpolated value
         */
        public double readRing(double[] ring, long mask, long intPos, double frac)
        {
            final long first = intPos - BEFORE;
            final int start = (int) (first & mask);
            if ((long) start + TAPS <= mask + 1)
                return read(ring, start, frac);   // contiguous: no gather
            final double p = frac * (double) PHASES;
            final int ip = clampInt((int) p, 0, PHASES - 1);
            final double t = p - (double) ip;
            final int h0 = ip * TAPS;
            final int h1 = h0 + TAPS;
            double a = 0.0, b = 0.0;
            for (int k = 0; k < TAPS; ++k)
                a += table[h0 + k] * ring[(int) ((first + k) & mask)];
            for (int k = 0; k < TAPS; ++k)
                b += table[h1 + k] * ring[(int) ((first + k) & mask)];
            return a + t * (b - a);
        }
    }

    /**
     * Band-limited fractional reader for read rates from 1 to 4.
     * <p>
     * A read point that advances by r &gt; 1 samples per output sample
     * decimates: content above Nyquist / r folds back unless the kernel's
     * cutoff drops to Nyquist / r. This reader tabulates such kernels (the
     * sinc stretched by r under a Kaiser window, beta 10, about 32 r taps)
     * for 16 rates in eighth-octave steps from 2^(1/8) to 4, at 64 phases
     * each, and reads with the kernel of the nearest tabulated rate at or
     * above the requested one. The kernel's cutoff sits at CUTOFF = 0.85 of
     * the output Nyquist, so its transition band ends near the output
     * Nyquist: content at the output Nyquist folds back at about -46 dB and
     * content from 1.05x upward below -100 dB. The response is flat to 0.7
     * of the output Nyquist and -2 dB at 0.8, and the band gives up at most a
     * further eighth of an octave to the rate table. For rates at or below 1
     * use {@link SincInterpolator}.
     * <p>
     * The constructor builds about 60 k coefficients (allocates); reads
     * allocate nothing and are safe from any thread.
     */
    public static final class StretchedSincReader
    {
        /** Tabulated rates. */
        public static final int STEPS = 16;
        /** Rate resolution. */
        public static final int STEPS_PER_OCTAVE = 8;
        /** Phases per rate. */
        public static final int PHASES = 64;
        /** Kernel length at rate 4. */
        public static final int MAX_TAPS = 128;
        /** Kernel cutoff, fraction of the output Nyquist. */
        public static final double CUTOFF = 0.85;

        private final double[] table;
        private final int[] offset = new int[STEPS];
        private final int[] half = new int[STEPS];

        /** Builds the kernel tables (allocates). */
        public StretchedSincReader()
        {
            final double beta = 10.0;
            final double pi = 3.14159265358979323846;
            final double invI0Beta = 1.0 / besselI0(beta);

            int total = 0;
            for (int k = 0; k < STEPS; ++k)
            {
                final double rate = exp2((double) (k + 1) / STEPS_PER_OCTAVE);
                half[k] = (int) Math.ceil(16.0 * rate);
                offset[k] = total;
                total += (PHASES + 1) * (2 * half[k]);
            }
            table = new double[total];

            final double[] h = new double[MAX_TAPS];
            for (int k = 0; k < STEPS; ++k)
            {
                final double rate = exp2((double) (k + 1) / STEPS_PER_OCTAVE);
                final int taps = 2 * half[k];
                for (int p = 0; p <= PHASES; ++p)
                {
                    final double phase = (double) p / PHASES;
                    double sum = 0.0;
                    for (int t = 0; t < taps; ++t)
                    {
                        final double u = ((double) (t - (half[k] - 1)) - phase) / rate;
                        final double r = u / 16.0;
                        final double window = (r * r < 1.0)
                            ? besselI0(beta * Math.sqrt(1.0 - r * r)) * invI0Beta
                            : 0.0;
                        final double uc = u * CUTOFF;
                        final double sinc = (uc == 0.0) ? 1.0 : Math.sin(pi * uc) / (pi * uc);
                        h[t] = sinc * window;
                        sum += h[t];
                    }
                    final int row = offset[k] + p * taps;
                    for (int t = 0; t < taps; ++t)
                        table[row + t] = h[t] / sum;   // unit DC gain
                }
            }
        }

        /**
         * The table for a read rate: the nearest tabulated rate at or above
         * it. Costs a log2, so look it up once per constant rate.
         *
         * @param rate  read rate, clamped to [2^(1/8), 4]
         * @return the step index in [0, STEPS)
         */
        public static int stepFor(double rate)
        {
            final double steps = Math.ceil(log2(rate < 1.0 ? 1.0 : rate) * STEPS_PER_OCTAVE - 1e-9);
            return clampInt((int) steps - 1, 0, STEPS - 1);
        }

        /**
         * Reads a power-of-two ring buffer at intPos + frac with the kernel of
         * a rate step. It needs the samples intPos - reach(step) + 1 through
         * intPos + reach(step).
         *
         * @param ring    ring storage
         * @param mask    ring size minus one (the size must be a power of two)
         * @param intPos  integer read position (masked, so any value works)
         * @param frac    fractional position in [0, 1)
         * @param step    rate step from {@link #stepFor(double)}
         * @return the interpolated value
         */
        public double readRing(double[] ring, long mask, long intPos, double frac, int step)
        {
            final int hk = half[step];
            final int taps = 2 * hk;
            final double p = frac * (double) PHASES;
            final int ip = clampInt((int) p, 0, PHASES - 1);
            final double t = p - (double) ip;
            final int h0 = offset[step] + ip * taps;
            final int h1 = h0 + taps;

            final long first = intPos - (hk - 1);
            final int start = (int) (first & mask);
            if ((long) start + taps > mask + 1)
            {
                // The window straddles the ring's end: read it masked.
                double a = 0.0, b = 0.0;
                for (int k = 0; k < taps; ++k)
                    a += table[h0 + k] * ring[(int) ((first + k) & mask)];
                for (int k = 0; k < taps; ++k)
                    b += table[h1 + k] * ring[(int) ((first + k) & mask)];
                return a + t * (b - a);
            }
            final double a = dotProduct(table, h0, ring, start, taps);
            final double b = dotProduct(table, h1, ring, start, taps);
            return a + t * (b - a);
        }

        /**
         * Samples a read of this step reaches on either side of intPos.
         *
         * @param step  rate step
         * @return the half kernel length
         */
        public int reach(int step)
        {
            return half[step];
        }
    }

    private static int clampInt(int v, int lo, int hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }

    /** 2^x, exact at integer x. */
    private static double exp2(double x)
    {
        return Math.pow(2.0, x);
    }

    /** log2(x), exact at powers of two. */
    private static double log2(double x)
    {
        int e = Math.getExponent(x);
        if (e >= Double.MIN_EXPONENT && e <= Double.MAX_EXPONENT && x == Math.scalb(1.0, e)) return e;
        return Math.log(x) / Math.log(2.0);
    }
}
