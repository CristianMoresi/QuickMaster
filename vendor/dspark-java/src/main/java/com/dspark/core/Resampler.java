package com.dspark.core;

import java.util.concurrent.CancellationException;

/**
 * Windowed-sinc sample-rate converter (offline / batch).
 * <p>
 * Converts audio between sample rates (e.g. 96 kHz &rarr; 48 kHz for MP3
 * export, or to hit a delivery rate) with a Kaiser-windowed sinc designed
 * for each conversion:
 * <ul>
 *   <li><b>Designed kernel.</b> A quality tier is a filter specification - a
 *       passband edge as a fraction of the lower Nyquist frequency and a
 *       stopband attenuation - and the kernel length and Kaiser beta follow
 *       from it (Kaiser's design formulas). The stopband starts at the lower
 *       Nyquist, so nothing above it survives a downsample.</li>
 *   <li><b>Exact polyphase phases.</b> When the rate ratio is rational with a
 *       manageable number of phases (every common audio pair: 44.1 &harr; 48 kHz
 *       is 160/147, 96 &harr; 48 kHz is 1/2, etc.) each output reads one exact
 *       kernel phase - no phase interpolation and none of its images.</li>
 *   <li><b>Interpolated table otherwise.</b> For an unusual ratio the kernel is
 *       tabulated at {@value #TABLE_PHASES} phases and read with cubic (Lagrange)
 *       interpolation across phases, whose error lies below the Ultra stopband.</li>
 * </ul>
 * Each polyphase branch is normalized to unity DC gain so the passband stays
 * flat.
 * <p>
 * The batch path is time-aligned: output sample k interpolates the input at
 * position k / {@link #getRatio()} exactly (no latency; the buffer edges are
 * zero-padded).
 */
public final class Resampler
{
    /**
     * Quality tier as a filter specification (passband edge as a fraction of
     * the lower Nyquist, stopband attenuation in dB). The kernel length and
     * Kaiser beta are derived from the specification.
     */
    public enum Quality
    {
        /** Passband 0.80 of Nyquist, 60 dB stopband: previews. */
        DRAFT(0.80, 60.0),
        /** Passband 0.90 of Nyquist, 100 dB stopband. */
        NORMAL(0.90, 100.0),
        /** Passband 0.91 of Nyquist (20 kHz at 44.1), 140 dB stopband. */
        HIGH(0.91, 140.0),
        /** Passband 0.915 of Nyquist, 210 dB stopband: mastering. */
        ULTRA(0.915, 210.0);

        final double passband;
        final double attenuationDb;
        Quality(double passband, double attenuationDb)
        {
            this.passband = passband;
            this.attenuationDb = attenuationDb;
        }
    }

    /** Phases of the table read with cubic interpolation (non-rational ratios). */
    private static final int TABLE_PHASES = 512;
    /** Most exact phases before a ratio falls back to the interpolated table. */
    private static final long MAX_EXACT_PHASES = 4096;
    /** Most exact-table coefficients before falling back to the table. */
    private static final long MAX_EXACT_COEFFICIENTS = 1L << 21;
    /** Even kernel length cap (binds only for extreme downsampling). */
    private static final int MAX_TAPS = 8192;
    /** Largest target/source ratio {@link #prepare} accepts. */
    private static final double MAX_RATIO = 16777216.0;

    private double ratio = 1.0;
    private double sourceRate = 1.0, targetRate = 1.0;
    private int taps = 2;
    private int halfTaps = 1;
    private boolean exact = true;
    private long phasesL = 1;   // exact mode: phases per input sample (L)
    private long stepM = 1;     // exact mode: phase advance per output (M)
    private double[] table = new double[0];

    /**
     * Prepares the converter for {@code sourceRate &rarr; targetRate}.
     * Non-finite rates (or a null quality) are ignored (conservative no-op
     * keeping the previous state).
     *
     * @param sourceRate  input sample rate in Hz
     * @param targetRate  output sample rate in Hz
     * @param quality     interpolation quality
     */
    public void prepare(double sourceRate, double targetRate, Quality quality)
    {
        if (!Double.isFinite(sourceRate) || !Double.isFinite(targetRate) || quality == null)
            return;
        if (sourceRate <= 0 || targetRate <= 0)
            throw new IllegalArgumentException("Sample rates must be positive");
        double nextRatio = targetRate / sourceRate;
        if (!Double.isFinite(nextRatio) || nextRatio <= 0 || nextRatio > MAX_RATIO)
            throw new IllegalArgumentException("Unsupported conversion ratio");

        Design d = design(nextRatio, quality);
        long[] lm = rationalRatio(sourceRate, targetRate);
        long l = lm[0], m = lm[1];
        boolean ex = d.identity
                || (l > 0 && l <= MAX_EXACT_PHASES && l * (long) d.taps <= MAX_EXACT_COEFFICIENTS);
        if (d.identity) { l = 1; m = 1; }

        // Build the whole candidate table first; a failure leaves the live
        // converter untouched (commit is a set of non-throwing assignments).
        double[] nextTable;
        if (ex)
        {
            // Phase p is the kernel for fractional position p / L.
            nextTable = new double[(int) (l * d.taps)];
            buildTable(nextTable, d, l, 0, (int) l);
        }
        else
        {
            // TABLE_PHASES + 3 phases: one guard phase before position 0 and
            // two after the last, so cubic interpolation across phases never
            // reads outside the table.
            nextTable = new double[(TABLE_PHASES + 3) * d.taps];
            buildTable(nextTable, d, TABLE_PHASES, -1, TABLE_PHASES + 3);
        }

        this.sourceRate = sourceRate;
        this.targetRate = targetRate;
        this.ratio = nextRatio;
        this.taps = d.taps;
        this.halfTaps = d.taps / 2;
        this.exact = ex;
        this.phasesL = ex ? l : 0;
        this.stepM = ex ? m : 0;
        this.table = nextTable;
    }

    public double getRatio() { return ratio; }

    /** True when the ratio resolves to an exact polyphase table (no phase interpolation). */
    boolean isExact() { return exact; }

    /** Kernel length (taps) of the designed filter. */
    int tapCount() { return taps; }

    /** Output length (samples) for a given input length. */
    public int getOutputLength(int inputLength)
    {
        if (inputLength < 0) throw new IllegalArgumentException("Negative input length");
        // Integer-rate products are exact here for representable audio buffers.
        // Multiplying by the rounded ratio first can add a spurious frame:
        // 176400 * (48000.0 / 176400) is slightly greater than 48000.
        double outLen = Math.ceil(inputLength * targetRate / sourceRate);
        if (!Double.isFinite(outLen) || outLen > Integer.MAX_VALUE - 8)
            throw new IllegalArgumentException("Converted buffer exceeds the array limit");
        return (int) outLen;
    }

    /**
     * Resamples a mono buffer.
     *
     * @param input  source samples
     * @return a new buffer at the target rate
     */
    public float[] process(float[] input)
    {
        return resampleInterleaved(input, 1);
    }

    /**
     * Resamples an interleaved multi-channel buffer.
     *
     * @param interleaved  interleaved source samples
     * @param channels     number of channels
     * @return a new interleaved buffer at the target rate
     */
    public float[] resampleInterleaved(float[] interleaved, int channels)
    {
        if (interleaved == null || channels <= 0 || interleaved.length % channels != 0)
            throw new IllegalArgumentException("A complete interleaved PCM buffer is required");
        checkCancelled();
        int inFrames = interleaved.length / channels;
        int outFrames = getOutputLength(inFrames);
        long samples = (long) outFrames * channels;
        if (samples > Integer.MAX_VALUE - 8)
            throw new IllegalArgumentException("Converted buffer exceeds the array limit");
        for (int i = 0; i < interleaved.length; i++) {
            if ((i & 16383) == 0) checkCancelled();
            if (!Float.isFinite(interleaved[i])) throw new IllegalArgumentException("Non-finite PCM sample");
        }
        if (ratio == 1) return interleaved.clone();

        float[] result = new float[(int) samples];
        for (int f = 0; f < outFrames; f++)
        {
            if ((f & 255) == 0) checkCancelled();
            long intPos;
            int phase = 0;
            double frac = 0.0;
            if (exact)
            {
                // Integer position arithmetic: output k reads input k*M/L.
                long num = (long) f * stepM;
                intPos = num / phasesL;
                phase = (int) (num % phasesL);
            }
            else
            {
                double srcPos = f / ratio;
                intPos = (long) srcPos;
                frac = srcPos - intPos;
            }
            if (intPos >= inFrames) break;
            int base = f * channels;
            for (int c = 0; c < channels; c++)
                result[base + c] = interpolate(interleaved, inFrames, (int) intPos, phase, frac, channels, c);
        }
        return result;
    }

    /** Kaiser-windowed sinc kernel parameters for one conversion. */
    private static final class Design
    {
        int taps = 2;          // even kernel length, in input samples
        double cutoff = 1.0;   // sinc cutoff, 1 = input Nyquist
        double beta = 0.0;     // Kaiser beta
        boolean identity = false;
    }

    private static Design design(double ratio, Quality quality)
    {
        Design d = new Design();
        if (ratio == 1.0)
        {
            d.identity = true;
            return d;
        }
        double scale = Math.min(1.0, ratio);   // lower Nyquist / input Nyquist
        // Band edges in cycles per input sample: the passband ends at
        // passband * lower Nyquist, the stopband begins at the lower Nyquist.
        double transition = 0.5 * scale * (1.0 - quality.passband);
        d.cutoff = scale * 0.5 * (1.0 + quality.passband);
        d.beta = kaiserBeta(quality.attenuationDb);
        // Kaiser's length estimate, N = (A - 7.95) / (14.36 * df). An extreme
        // ratio (past ~24:1 at Ultra) needs a kernel longer than the cap to
        // meet the specification; rather than silently widen the transition
        // and pass a degraded conversion off as the tier, reject it.
        double n = (quality.attenuationDb - 7.95) / (14.36 * transition);
        if (n > MAX_TAPS)
            throw new IllegalArgumentException("Unsupported conversion ratio");
        d.taps = 2 * (int) Math.ceil(0.5 * n);
        d.taps = Math.max(d.taps, 4);
        return d;
    }

    /** Kaiser's beta for a stopband attenuation (Kaiser 1974). */
    private static double kaiserBeta(double a)
    {
        if (a > 50.0) return 0.1102 * (a - 8.7);
        if (a >= 21.0) return 0.5842 * Math.pow(a - 21.0, 0.4) + 0.07886 * (a - 21.0);
        return 0.0;
    }

    /**
     * {@code numPhases} kernels, phase p being the windowed sinc at fractional
     * position {@code (p + offset) / L}, each normalized to unity DC gain.
     */
    private static void buildTable(double[] dst, Design d, long L, long offset, int numPhases)
    {
        for (int p = 0; p < numPhases; p++)
        {
            checkCancelled();
            buildPhase(dst, p * d.taps, d, (double) (p + offset) / (double) L);
        }
    }

    /**
     * The kernel for one fractional position {@code frac} of the output between
     * two input samples, normalized to unity DC gain, written at {@code dst[base..]}.
     * <p>
     * Tap j weighs source sample intPos-half+1+j, so its distance from the
     * interpolation point intPos + frac is t = (j - half + 1) - frac: the
     * symmetric placement for an even-length kernel. The MINUS sign on frac is
     * essential: {@code + frac} would sample the kernel time-reversed.
     */
    private static void buildPhase(double[] dst, int base, Design d, double frac)
    {
        int half = d.taps / 2;
        if (d.identity)
        {
            for (int j = 0; j < d.taps; j++) dst[base + j] = (j == half - 1) ? 1.0 : 0.0;
            return;
        }
        double i0Beta = besselI0(d.beta);
        double sum = 0.0;
        for (int j = 0; j < d.taps; j++)
        {
            double t = (j - half + 1) - frac;
            double x = t * d.cutoff;
            double sincVal = (Math.abs(x) < 1e-12)
                    ? d.cutoff : d.cutoff * Math.sin(Math.PI * x) / (Math.PI * x);
            double wx = t / half;
            double win = (Math.abs(wx) >= 1.0)
                    ? 0.0 : besselI0(d.beta * Math.sqrt(1.0 - wx * wx)) / i0Beta;
            double v = sincVal * win;
            dst[base + j] = v;
            sum += v;
        }
        if (Math.abs(sum) > 1e-12)
        {
            double inv = 1.0 / sum;
            for (int j = 0; j < d.taps; j++) dst[base + j] *= inv;
        }
    }

    /**
     * The rate ratio as {@code L / M} (target/source) in lowest terms when both
     * are small enough for an exact phase table; {@code {0, 0}} otherwise.
     */
    private static long[] rationalRatio(double sourceRate, double targetRate)
    {
        // Integer rates (every standard one) reduce exactly.
        double rs = Math.rint(sourceRate), rt = Math.rint(targetRate);
        if (Math.abs(rs - sourceRate) < 1e-9 && Math.abs(rt - targetRate) < 1e-9
                && rs < 1e12 && rt < 1e12)
        {
            long a = (long) rt, b = (long) rs;
            long x = a, y = b;
            while (y != 0) { long r = x % y; x = y; y = r; }
            if (x > 0) return new long[] { a / x, b / x };
        }
        // Otherwise a continued-fraction approximation exact to double precision.
        double r = targetRate / sourceRate;
        long h0 = 0, h1 = 1, k0 = 1, k1 = 0;
        double v = r;
        for (int it = 0; it < 64; it++)
        {
            double fl = Math.floor(v);
            if (fl > 1e12) break;
            long an = (long) fl;
            long h2 = an * h1 + h0, k2 = an * k1 + k0;
            if (k2 > (1L << 32) || h2 > (1L << 32)) break;
            h0 = h1; h1 = h2; k0 = k1; k1 = k2;
            if (Math.abs((double) h1 / (double) k1 - r) <= 1e-15 * r) return new long[] { h1, k1 };
            double rem = v - fl;
            if (rem < 1e-15) break;
            v = 1.0 / rem;
        }
        return new long[] { 0, 0 };
    }

    /**
     * One output sample. {@code phase} selects the exact kernel phase (exact
     * mode); {@code frac} drives the cubic read across table phases otherwise.
     * Samples outside {@code [0, length)} are taken as zero (edge padding).
     */
    private float interpolate(float[] data, int length, int intPos, int phase, double frac, int stride, int channel)
    {
        // Tap j weighs data[intPos - half + 1 + j] (see buildPhase).
        int firstSrc = intPos - halfTaps + 1;
        int start = Math.max(0, -firstSrc);
        int end = (int) Math.min(taps, (long) length - firstSrc);

        if (exact)
        {
            int off = phase * taps;
            double s = 0.0;
            for (int tap = start; tap < end; tap++)
                s += data[(firstSrc + tap) * stride + channel] * table[off + tap];
            return (float) s;
        }

        // Cubic (Lagrange) interpolation across the four phases around the
        // position: error ~ (pi / (2 * TABLE_PHASES))^4, below every stopband.
        double exactPhase = frac * TABLE_PHASES;
        int p0 = (int) exactPhase;
        if (p0 > TABLE_PHASES - 1) p0 = TABLE_PHASES - 1;   // frac is < 1 by contract
        double u = exactPhase - p0;
        // Table index p0 holds position (p0 - 1)/P (one guard phase first), so
        // the four rows p0..p0+3 straddle the point for the centred cubic.
        int o0 = p0 * taps, o1 = o0 + taps, o2 = o1 + taps, o3 = o2 + taps;
        double sm = 0.0, s0 = 0.0, s1 = 0.0, s2 = 0.0;
        for (int tap = start; tap < end; tap++)
        {
            double sample = data[(firstSrc + tap) * stride + channel];
            sm += sample * table[o0 + tap];
            s0 += sample * table[o1 + tap];
            s1 += sample * table[o2 + tap];
            s2 += sample * table[o3 + tap];
        }
        double wm = -u * (u - 1.0) * (u - 2.0) / 6.0;
        double w0 = (u + 1.0) * (u - 1.0) * (u - 2.0) / 2.0;
        double w1 = -(u + 1.0) * u * (u - 2.0) / 2.0;
        double w2 = (u + 1.0) * u * (u - 1.0) / 6.0;
        return (float) (wm * sm + w0 * s0 + w1 * s1 + w2 * s2);
    }

    private static void checkCancelled()
    {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Resampling cancelled");
    }

    /** Modified Bessel I0 for the continuous Kaiser window. */
    private static double besselI0(double x)
    {
        double y = 0.25 * x * x;
        double sum = 1.0, term = 1.0;
        for (int k = 1; k <= 200; k++)
        {
            term *= y / ((double) k * (double) k);
            sum += term;
            if (term < 1e-17 * sum) break;
        }
        return sum;
    }
}
