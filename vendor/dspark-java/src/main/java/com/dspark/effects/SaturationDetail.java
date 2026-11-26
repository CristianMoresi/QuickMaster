package com.dspark.effects;

import com.dspark.core.Adaa;
import com.dspark.core.BiquadCoeffs;
import com.dspark.core.BiquadDesign;
import com.dspark.core.BiquadFilter;
import com.dspark.core.LogCosh;

import java.util.Arrays;

/**
 * The curves of {@link Saturation}: one class per algorithm, the antialiasing
 * kernels of the Exciter and Tape curves, and the numeric helpers they share.
 * <p>
 * Every algorithm takes a sample, the drive gain and the character, and keeps
 * its own memory per channel (up to 16). With second-order antialiasing every
 * algorithm delays by the same number of processing samples (the common
 * delay the owner latches each block): the kernels delay by one and are
 * padded to it, the curves without a kernel are delayed by all of it, so a
 * crossfade between any two stays aligned.
 */
final class SaturationDetail
{
    private SaturationDetail()
    {
    }

    // -- Numeric helpers ------------------------------------------------------

    /** v clamped to [lo, hi]; a NaN passes through. */
    static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }

    /** v clamped to [lo, hi]. */
    static int clamp(int v, int lo, int hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }

    /** The larger of a and b, written a &lt; b ? b : a (a NaN b yields a). */
    static double max(double a, double b)
    {
        return a < b ? b : a;
    }

    /** The smaller of a and b, written b &lt; a ? b : a (a NaN b yields a). */
    static double min(double a, double b)
    {
        return b < a ? b : a;
    }

    /** Pade [5,4] tanh on an input clamped to [-3, 3]. */
    static double fastTanh(double x)
    {
        x = clamp(x, -3.0, 3.0);
        final double x2 = x * x;
        final double x4 = x2 * x2;
        return x * (945.0 + 105.0 * x2 + x4) / (945.0 + 420.0 * x2 + 15.0 * x4);
    }

    /** Decibels to gain; -100 dB and below is silence. */
    static double decibelsToGain(double dB)
    {
        return dB <= -100.0 ? 0.0 : Math.exp(dB * 0.11512925464970228420);
    }

    /** Gain to decibels, at least -100 dB (also for a gain of zero or less). */
    static double gainToDecibels(double gain)
    {
        return gain > 0.0 ? max(-100.0, 8.6858896380650365530 * Math.log(gain)) : -100.0;
    }

    /** Maps value from [inMin, inMax] to [outMin, outMax]; outMin when the input range is empty. */
    static double mapRange(double value, double inMin, double inMax, double outMin, double outMax)
    {
        if (inMin == inMax) return outMin;
        return outMin + (outMax - outMin) * ((value - inMin) / (inMax - inMin));
    }

    /** Rounds half away from zero. */
    static double round(double x)
    {
        if (!(Math.abs(x) < 4503599627370496.0)) return x;   // integral, infinite or NaN
        final double a = Math.abs(x);
        double t = Math.floor(a);
        if (a - t >= 0.5) t += 1.0;
        return Math.copySign(t, x);
    }

    /** Rounds half away from zero, to a long. */
    static long lround(double x)
    {
        return (long) round(x);
    }

    private static final double ERX = 8.45062911510467529297e-01;
    private static final double EFX = 1.28379167095512586316e-01;
    private static final double EFX8 = 1.02703333676410069053e+00;
    private static final double PP0 = 1.28379167095512558561e-01, PP1 = -3.25042107247001499370e-01,
        PP2 = -2.84817495755985104766e-02, PP3 = -5.77027029648944159157e-03, PP4 = -2.37630166566501626084e-05;
    private static final double QQ1 = 3.97917223959155352819e-01, QQ2 = 6.50222499887672944485e-02,
        QQ3 = 5.08130628187576562776e-03, QQ4 = 1.32494738004321644526e-04, QQ5 = -3.96022827877536812320e-06;
    private static final double PA0 = -2.36211856075265944077e-03, PA1 = 4.14856118683748331666e-01,
        PA2 = -3.72207876035701323847e-01, PA3 = 3.18346619901161753674e-01, PA4 = -1.10894694282396677476e-01,
        PA5 = 3.54783043256182359371e-02, PA6 = -2.16637559486879084300e-03;
    private static final double QA1 = 1.06420880400844228286e-01, QA2 = 5.40397917702171048937e-01,
        QA3 = 7.18286544141962662868e-02, QA4 = 1.26171219808761642112e-01, QA5 = 1.36370839120290507362e-02,
        QA6 = 1.19844998467991074170e-02;
    private static final double RA0 = -9.86494403484714822705e-03, RA1 = -6.93858572707181764372e-01,
        RA2 = -1.05586262253232909814e+01, RA3 = -6.23753324503260060396e+01, RA4 = -1.62396669462573470355e+02,
        RA5 = -1.84605092906711035994e+02, RA6 = -8.12874355063065934246e+01, RA7 = -9.81432934416914548592e+00;
    private static final double SA1 = 1.96512716674392571292e+01, SA2 = 1.37657754143519042600e+02,
        SA3 = 4.34565877475229228821e+02, SA4 = 6.45387271733267880336e+02, SA5 = 4.29008140027567833386e+02,
        SA6 = 1.08635005541779435134e+02, SA7 = 6.57024977031928170135e+00, SA8 = -6.04244152148580987438e-02;
    private static final double RB0 = -9.86494292470009928597e-03, RB1 = -7.99283237680523006574e-01,
        RB2 = -1.77579549177547519889e+01, RB3 = -1.60636384855821916062e+02, RB4 = -6.37566443368389627722e+02,
        RB5 = -1.02509513161107724954e+03, RB6 = -4.83519191608651397019e+02;
    private static final double SB1 = 3.03380607434824582924e+01, SB2 = 3.25792512996573918826e+02,
        SB3 = 1.53672958608443695994e+03, SB4 = 3.19985821950859553908e+03, SB5 = 2.55305040643316442583e+03,
        SB6 = 4.74528541206955367215e+02, SB7 = -2.24409524465858183362e+01;

    /**
     * The error function in double precision: rational approximations on
     * |x| &lt; 0.84375 and [0.84375, 1.25), the complementary function's
     * exp(-x^2 - 0.5625 + R/S) form up to 6, and +-1 beyond (the classic
     * fdlibm algorithm, within one unit in the last place).
     */
    static double erf(double x)
    {
        final long bits = Double.doubleToRawLongBits(x);
        final int hx = (int) (bits >> 32);
        final int ix = hx & 0x7fffffff;
        if (ix >= 0x7ff00000)
        {
            final int i = (hx >>> 31) << 1;
            return (double) (1 - i) + 1.0 / x;
        }
        if (ix < 0x3feb0000)
        {
            if (ix < 0x3e300000)
            {
                if (ix < 0x00800000) return 0.125 * (8.0 * x + EFX8 * x);
                return x + EFX * x;
            }
            final double z = x * x;
            final double r = PP0 + z * (PP1 + z * (PP2 + z * (PP3 + z * PP4)));
            final double s = 1.0 + z * (QQ1 + z * (QQ2 + z * (QQ3 + z * (QQ4 + z * QQ5))));
            final double y = r / s;
            return x + x * y;
        }
        if (ix < 0x3ff40000)
        {
            final double s = Math.abs(x) - 1.0;
            final double p = PA0 + s * (PA1 + s * (PA2 + s * (PA3 + s * (PA4 + s * (PA5 + s * PA6)))));
            final double q = 1.0 + s * (QA1 + s * (QA2 + s * (QA3 + s * (QA4 + s * (QA5 + s * QA6)))));
            return hx >= 0 ? ERX + p / q : -ERX - p / q;
        }
        if (ix >= 0x40180000)
            return hx >= 0 ? 1.0 - 1e-300 : 1e-300 - 1.0;
        final double ax = Math.abs(x);
        final double s = 1.0 / (ax * ax);
        final double r, ss;
        if (ix < 0x4006DB6E)
        {
            r = RA0 + s * (RA1 + s * (RA2 + s * (RA3 + s * (RA4 + s * (RA5 + s * (RA6 + s * RA7))))));
            ss = 1.0 + s * (SA1 + s * (SA2 + s * (SA3 + s * (SA4 + s * (SA5 + s * (SA6 + s * (SA7 + s * SA8)))))));
        }
        else
        {
            r = RB0 + s * (RB1 + s * (RB2 + s * (RB3 + s * (RB4 + s * (RB5 + s * RB6)))));
            ss = 1.0 + s * (SB1 + s * (SB2 + s * (SB3 + s * (SB4 + s * (SB5 + s * (SB6 + s * SB7))))));
        }
        final double z = Double.longBitsToDouble(Double.doubleToRawLongBits(ax) & 0xFFFFFFFF00000000L);
        final double e = Math.exp(-z * z - 0.5625) * Math.exp((z - ax) * (z + ax) + r / ss);
        return hx >= 0 ? 1.0 - e / ax : e / ax - 1.0;
    }

    private static Adaa.Adaa2[] newAdaa2(int n)
    {
        final Adaa.Adaa2[] a = new Adaa.Adaa2[n];
        for (int i = 0; i < n; ++i) a[i] = new Adaa.Adaa2();
        return a;
    }

    private static BiquadFilter[] newFilters(int n)
    {
        final BiquadFilter[] f = new BiquadFilter[n];
        for (int i = 0; i < n; ++i) f[i] = new BiquadFilter(1);
        return f;
    }

    /**
     * A function of the last argument it was asked for, kept: the same bits
     * give the same value, so a bias that holds still costs one evaluation.
     */
    static final class Memo
    {
        private long bits = Double.doubleToRawLongBits(Double.NaN);
        private double value = Double.NaN;

        double tanh(double x)
        {
            final long b = Double.doubleToRawLongBits(x);
            if (b != bits)
            {
                bits = b;
                value = Math.tanh(x);
            }
            return value;
        }

        double sin(double x)
        {
            final long b = Double.doubleToRawLongBits(x);
            if (b != bits)
            {
                bits = b;
                value = Math.sin(x);
            }
            return value;
        }
    }

    /** logCosh(u), reusing the value kept for the previous input when u is that input to the bit. */
    private static double logCoshAt(double u, long[] keptBits, double[] kept, int c)
    {
        return Double.doubleToRawLongBits(u) == keptBits[c] ? kept[c] : LogCosh.logCosh(u);
    }

    // -- Common base ------------------------------------------------------------

    /** What every algorithm shares: the antialiasing order and the common second-order delay. */
    abstract static class SaturationAlgorithm
    {
        static final int AA_CH = 16;

        /** Longest common second-order delay, in processing samples. */
        static final int MAX_DELAY = 4;

        /** The order asked for (0 off, 1 first, 2 second). */
        int antialias = 0;
        /** The order latched for the block. */
        int order = 0;
        /** The common second-order delay latched for the block. */
        int delay = 1;
        /** Channels whose order-2 history must be seeded. */
        int armMask = ~0;
        /** Channels whose delay line must be seeded. */
        int delayArm = ~0;
        /** Channels whose interpolator must be seeded. */
        int halfArm = ~0;
        final double[][] alignHold = new double[AA_CH][MAX_DELAY];
        final double[][] halfHold = new double[AA_CH][3];

        /** Prepares the algorithm for a rate, a channel count and a block size. */
        abstract void prepare(double sampleRate, int numChannels, int maxBlockSize);

        /** Clears the memory (filters, phase, history). */
        abstract void reset();

        /** Updates what depends on the block's drive gain, character and processing rate. */
        void update(double driveGain, double character, double sampleRate)
        {
        }

        /** The algorithm this class is. */
        abstract Saturation.Algorithm getType();

        /**
         * The antiderivative antialiasing order of the memoryless curves:
         * 0 off, 1 first order, 2 second order. Order 2 delays every
         * algorithm by the same number of processing samples (latchOrder()).
         */
        final void setAntialias(int o)
        {
            antialias = clamp(o, 0, 2);
        }

        /** {@link #latchOrder(int)} with a common delay of one sample. */
        final void latchOrder()
        {
            latchOrder(1);
        }

        /**
         * Latches the order for the coming block and the common second-order
         * delay (1 to {@link #MAX_DELAY} processing samples). Entering order 2
         * arms every channel: its first sample seeds the kernel history and
         * the delay line with the current input.
         */
        final void latchOrder(int commonDelay)
        {
            final int o = antialias;
            if (o == 2 && order != 2)
            {
                armMask = ~0;
                delayArm = ~0;
                halfArm = ~0;
            }
            order = o;
            delay = clamp(commonDelay, 1, MAX_DELAY);
        }

        /** The latched order. */
        final int order()
        {
            return order;
        }

        /** The latched common second-order delay (processing samples). */
        final int commonDelay()
        {
            return delay;
        }

        /** Full common delay, for algorithms without a second-order kernel. */
        final double align(double y, int ch)
        {
            return delayBy(y, ch, delay);
        }

        /** The common delay less the one sample of a second-order kernel. */
        final double padKernel(double y, int ch)
        {
            return delay > 1 ? delayBy(y, ch, delay - 1) : y;
        }

        /** True once per channel after entering order 2 (or a reset). */
        final boolean takeArm(int c)
        {
            final int bit = 1 << (c & (AA_CH - 1));
            if ((armMask & bit) == 0) return false;
            armMask &= ~bit;
            return true;
        }

        /** Delays y by n (1 to MAX_DELAY) samples on channel ch. */
        final double delayBy(double y, int ch, int n)
        {
            final int c = ch & (AA_CH - 1);
            final double[] line = alignHold[c];
            final int bit = 1 << c;
            if ((delayArm & bit) != 0)
            {
                delayArm &= ~bit;
                Arrays.fill(line, y);
            }
            final double out = line[n - 1];
            for (int k = MAX_DELAY - 1; k > 0; --k) line[k] = line[k - 1];
            line[0] = y;
            return out;
        }

        /**
         * Delays y by 1.5 samples on channel ch with a linear-phase 4-tap
         * interpolator (its own history, seeded like the delay line).
         * Coefficients -a, 0.5 + a, 0.5 + a, -a with a = 0.0824: within
         * +0.10/-0.11 dB to 20 kHz at 96 kHz, -0.54 dB at 24 kHz, a zero at
         * half the rate.
         */
        final double delayHalfSample(double y, int ch)
        {
            final double kOuter = -0.0824, kInner = 0.5824;
            final int c = ch & (AA_CH - 1);
            final double[] line = halfHold[c];
            final int bit = 1 << c;
            if ((halfArm & bit) != 0)
            {
                halfArm &= ~bit;
                Arrays.fill(line, y);
            }
            final double out = kOuter * (y + line[2]) + kInner * (line[0] + line[1]);
            line[2] = line[1];
            line[1] = line[0];
            line[0] = y;
            return out;
        }

        final void resetAlign()
        {
            for (double[] line : alignHold) Arrays.fill(line, 0.0);
            for (double[] line : halfHold) Arrays.fill(line, 0.0);
            armMask = ~0;
            delayArm = ~0;
            halfArm = ~0;
        }
    }

    // -- SoftClip (tanh) ----------------------------------------------------------

    /** tanh(x + bias) - tanh(bias), bias = 0.3 character. */
    static final class TanhAlgorithm extends SaturationAlgorithm
    {
        private final double[] prevX = new double[AA_CH];
        private final Adaa.Adaa2[] adaa2 = newAdaa2(AA_CH);
        private final double[] prevBias = new double[AA_CH];
        private final long[] keptBits = new long[AA_CH];   // first order: the last logCosh() argument and value
        private final double[] kept = new double[AA_CH];
        private final Memo restTanh = new Memo(), biasTanh = new Memo();

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
            reset();
        }

        @Override
        void reset()
        {
            Arrays.fill(prevX, 0.0);
            for (Adaa.Adaa2 a : adaa2) a.reset();
            Arrays.fill(prevBias, 0.0);
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.SOFT_CLIP;
        }

        /** Second-order sample (one sample of delay). */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final double x = sample * drive;
            final double bias = character * 0.3;
            final int c = ch & (AA_CH - 1);
            // Second order on u = x + bias; the resting offset tanh(bias)
            // is taken one sample back, where the output is centred.
            final double u = x + bias;
            if (takeArm(c))
            {
                adaa2[c].reset(u);
                prevBias[c] = bias;
            }
            final double y = adaa2[c].process(Adaa.AdaaTanh.INSTANCE, u);
            final double tb = restTanh.tanh(prevBias[c]);
            prevBias[c] = bias;
            prevX[c] = x;
            return y - tb;
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            final double x = sample * drive;
            final double bias = character * 0.3;
            final int c = ch & (AA_CH - 1);
            if (order == 0)
            {
                // Keep the first-order history current, so enabling it
                // mid-stream starts from the real previous input.
                prevX[c] = x;
                return fastTanh(x + bias) - fastTanh(bias);
            }

            // First order on f(x) = tanh(x + bias) - tanh(bias), whose
            // antiderivative is log(cosh(x + bias)) - tanh(bias) x.
            final double xd = x;
            final double b = bias;
            final double x0 = prevX[c];
            prevX[c] = xd;
            final double tb = biasTanh.tanh(b);
            final double dx = xd - x0;
            if (Math.abs(dx) > 1e-7)
            {
                final double u = xd + b;
                final double f = LogCosh.logCosh(u);
                final double f0 = logCoshAt(x0 + b, keptBits, kept, c);
                keptBits[c] = Double.doubleToRawLongBits(u);
                kept[c] = f;
                return (f - f0) / dx - tb;
            }
            return Math.tanh(0.5 * (xd + x0) + b) - tb;
        }
    }

    // -- Tube -------------------------------------------------------------------

    /**
     * A biased tanh, DC-free at rest and normalised to unit small-signal
     * slope: f(x) = (tanh(x + b) - tanh(b)) / (1 - tanh(b)^2), with
     * b = -0.7 (0.15 + 0.5 character). The curve is smooth everywhere and its
     * even harmonics grow with level as a triode's do; negative excursions
     * compress first.
     */
    static final class TubeAlgorithm extends SaturationAlgorithm
    {
        private final double[] prevX = new double[AA_CH];
        private final Adaa.Adaa2[] adaa2 = newAdaa2(AA_CH);
        private final double[] prevBias = new double[AA_CH];
        private final long[] keptBits = new long[AA_CH];   // first order: the last logCosh() argument and value
        private final double[] kept = new double[AA_CH];
        private final Memo restTanh = new Memo(), biasTanh = new Memo();

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
            reset();
        }

        @Override
        void reset()
        {
            Arrays.fill(prevX, 0.0);
            for (Adaa.Adaa2 a : adaa2) a.reset();
            Arrays.fill(prevBias, 0.0);
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.TUBE;
        }

        /** Second-order sample (one sample of delay). */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final double x = sample * drive;
            final double b = -0.7 * (0.15 + character * 0.5);
            final int c = ch & (AA_CH - 1);
            // Second order on u = x + b; offset and slope normalisation use
            // the bias one sample back, where the output is centred.
            final double u = x + b;
            if (takeArm(c))
            {
                adaa2[c].reset(u);
                prevBias[c] = b;
            }
            final double y = adaa2[c].process(Adaa.AdaaTanh.INSTANCE, u);
            final double tb = restTanh.tanh(prevBias[c]);
            prevBias[c] = b;
            prevX[c] = x;
            return (y - tb) / (1.0 - tb * tb);
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            final double x = sample * drive;
            final double b = -0.7 * (0.15 + character * 0.5);
            final int c = ch & (AA_CH - 1);
            if (order == 0)
            {
                prevX[c] = x;
                final double tb = fastTanh(b);
                return (fastTanh(x + b) - tb) / (1.0 - tb * tb);
            }

            // First order: antiderivative (log(cosh(x + b)) - tanh(b) x) / (1 - tanh(b)^2).
            final double xd = x;
            final double bd = b;
            final double tb = biasTanh.tanh(bd);
            final double norm = 1.0 / (1.0 - tb * tb);
            final double x0 = prevX[c];
            prevX[c] = xd;
            final double dx = xd - x0;
            if (Math.abs(dx) > 1e-7)
            {
                final double u = xd + bd;
                final double f = LogCosh.logCosh(u);
                final double f0 = logCoshAt(x0 + bd, keptBits, kept, c);
                keptBits[c] = Double.doubleToRawLongBits(u);
                kept[c] = f;
                return ((f - f0) / dx - tb) * norm;
            }
            return (Math.tanh(0.5 * (xd + x0) + bd) - tb) * norm;
        }
    }

    // -- HardClip ---------------------------------------------------------------

    /** clamp(x + bias, -1, 1) - clamp(bias, -1, 1), bias = 0.3 character. */
    static final class HardClipAlgorithm extends SaturationAlgorithm
    {
        private final double[] prevX = new double[AA_CH];
        private final Adaa.Adaa2[] adaa2 = newAdaa2(AA_CH);
        private final double[] prevBias = new double[AA_CH];

        /** Antiderivative of clamp(u, -1, 1): u^2/2 inside, |u| - 1/2 outside. */
        private static double g(double u)
        {
            final double a = Math.abs(u);
            return (a <= 1.0) ? 0.5 * u * u : a - 0.5;
        }

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
            reset();
        }

        @Override
        void reset()
        {
            Arrays.fill(prevX, 0.0);
            for (Adaa.Adaa2 a : adaa2) a.reset();
            Arrays.fill(prevBias, 0.0);
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.HARD_CLIP;
        }

        /** Second-order sample: the exact kernel on u = d + bias (one sample of delay). */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final double bias = character * 0.3;
            final double d = sample * drive;
            final int c = ch & (AA_CH - 1);
            final double u = d + bias;
            if (takeArm(c))
            {
                adaa2[c].reset(u);
                prevBias[c] = bias;
            }
            final double y = adaa2[c].process(Adaa.AdaaHardClip.INSTANCE, u);
            final double cbPrev = clamp(prevBias[c], -1.0, 1.0);
            prevBias[c] = bias;
            prevX[c] = d;
            return y - cbPrev;
        }

        /** Plain, or first-order antialiased over every sample. */
        double processSample(double sample, double drive, double character, int ch)
        {
            final double bias = character * 0.3;
            final double cb = clamp(bias, -1.0, 1.0);
            final double d = sample * drive;
            final int c = ch & (AA_CH - 1);
            if (order == 0)
            {
                prevX[c] = d;
                return clamp(d + bias, -1.0, 1.0) - cb;
            }

            final double dd = d, b = bias;
            final double d0 = prevX[c];
            prevX[c] = dd;
            final double u = dd + b, u0 = d0 + b, diff = dd - d0;
            if (Math.abs(diff) > 1e-7)
                return (g(u) - g(u0)) / diff - cb;
            return clamp(0.5 * (u + u0), -1.0, 1.0) - cb;
        }
    }

    // -- Exciter ----------------------------------------------------------------

    /**
     * Exact second-order kernel of the Exciter curve f(u) = clamp(p(u), -1, 1),
     * p(u) = u + A u^2 - B u^3 on |u| &lt;= 10 and constant beyond. f is
     * piecewise polynomial, so its average over the linear B-spline with knots
     * at three inputs has a closed form: the interval is split at the middle
     * knot, at +-10 and where p crosses +-1 (bracketed Newton on the monotone
     * pieces of p), and each piece contributes its exact moment against the
     * linear weight, written about the piece centre. When no crossing can lie
     * between the knots the average is the closed form of the cubic in the
     * complete symmetric polynomials of the knots.
     */
    static final class AdaaExciter
    {
        static final double K_B = 0.15, K_LIMIT = 10.0;

        /** Quadratic coefficient (0.25 character). */
        double a = 0.0;
        /** Critical points of p, ascending. */
        final double[] crit = { 0.0, 0.0 };
        final double[] critValue = { 0.0, 0.0 };

        private final double[] cuts = new double[12];
        private final double[] piece = new double[4];

        void setCharacter(double value)
        {
            if (value == a && critValue[1] != 0.0) return;
            a = value;
            final double r = Math.sqrt(a * a + 3.0 * K_B);
            crit[0] = (a - r) / (3.0 * K_B);
            crit[1] = (a + r) / (3.0 * K_B);
            critValue[0] = p(crit[0]);
            critValue[1] = p(crit[1]);
        }

        double p(double u)
        {
            return u * (1.0 + u * (a - K_B * u));
        }

        double f(double u)
        {
            return clamp(p(clamp(u, -K_LIMIT, K_LIMIT)), -1.0, 1.0);
        }

        /** Average of f over the linear B-spline with knots u0, u1, u2. */
        double average(double u0, double u1, double u2)
        {
            double lo = u0, mid = u1, hi = u2;
            double t0;
            if (lo > mid) { t0 = lo; lo = mid; mid = t0; }
            if (mid > hi) { t0 = mid; mid = hi; hi = t0; }
            if (lo > mid) { t0 = lo; lo = mid; mid = t0; }
            final double span = hi - lo;
            if (!(span > 1e-9)) return f((u0 + u1 + u2) * (1.0 / 3.0));
            if (lo >= -K_LIMIT && hi <= K_LIMIT)
            {
                double top = max(p(lo), p(hi)), bottom = min(p(lo), p(hi));
                for (int k = 0; k < 2; ++k)
                    if (crit[k] > lo && crit[k] < hi)
                    {
                        top = max(top, critValue[k]);
                        bottom = min(bottom, critValue[k]);
                    }
                if (top <= 1.0 && bottom >= -1.0)
                {
                    // E[u^k] = 2 h_k / ((k + 1)(k + 2)) for the complete symmetric h_k.
                    final double h1 = u0 + u1 + u2;
                    final double h2 = u0 * u0 + u1 * u1 + u2 * u2 + u0 * u1 + u1 * u2 + u0 * u2;
                    final double h3 = u0 * u0 * u0 + u1 * u1 * u1 + u2 * u2 * u2
                                    + u0 * u0 * (u1 + u2) + u1 * u1 * (u0 + u2) + u2 * u2 * (u0 + u1)
                                    + u0 * u1 * u2;
                    return h1 * (1.0 / 3.0) + a * h2 * (1.0 / 6.0) - K_B * h3 * (1.0 / 10.0);
                }
                if (bottom >= 1.0) return 1.0;
                if (top <= -1.0) return -1.0;
            }

            // General case: break points inside (lo, hi), at most 1 + 2 + 6.
            int n = 0;
            cuts[n++] = lo;
            cuts[n++] = mid;
            if (-K_LIMIT > lo && -K_LIMIT < hi) cuts[n++] = -K_LIMIT;
            if (K_LIMIT > lo && K_LIMIT < hi) cuts[n++] = K_LIMIT;
            final double pa = max(lo, -K_LIMIT), pb = min(hi, K_LIMIT);
            if (pa < pb)
            {
                piece[0] = pa;
                int pieces = 1;
                for (int k = 0; k < 2; ++k)
                    if (crit[k] > pa && crit[k] < pb) piece[pieces++] = crit[k];
                piece[pieces] = pb;
                for (int k = 0; k < pieces; ++k)
                    for (int side = 0; side < 2; ++side)
                    {
                        final double level = side == 0 ? -1.0 : 1.0;
                        double s = piece[k], e = piece[k + 1];
                        double fs = p(s) - level, fe = p(e) - level;
                        if (!(fs * fe < 0.0)) continue;
                        double t = s + (e - s) * fs / (fs - fe);
                        for (int it = 0; it < 60; ++it)
                        {
                            final double ft = p(t) - level;
                            if (ft == 0.0) break;
                            if ((ft < 0.0) == (fs < 0.0)) { s = t; fs = ft; } else { e = t; fe = ft; }
                            final double slope = 1.0 + t * (2.0 * a - 3.0 * K_B * t);
                            double next = t - ft / slope;
                            if (!(next > s && next < e)) next = 0.5 * (s + e);
                            if (Math.abs(next - t) <= 1e-15 * (1.0 + Math.abs(t))) { t = next; break; }
                            t = next;
                        }
                        if (t > lo && t < hi) cuts[n++] = t;
                    }
            }
            cuts[n++] = hi;
            for (int k = 1; k < n; ++k)   // insertion sort: at most 11 points
                for (int j = k; j > 0 && cuts[j - 1] > cuts[j]; --j)
                {
                    final double tmp = cuts[j - 1];
                    cuts[j - 1] = cuts[j];
                    cuts[j] = tmp;
                }

            // Linear weight: rising on [lo, mid], falling on [mid, hi]; a side
            // narrower than 1e-12 of the span carries no measurable mass.
            final boolean rising = mid - lo > 1e-12 * span, falling = hi - mid > 1e-12 * span;
            final double riseSlope = rising ? 2.0 / (span * (mid - lo)) : 0.0;
            final double fallSlope = falling ? -2.0 / (span * (hi - mid)) : 0.0;
            double sum = 0.0;
            for (int k = 0; k + 1 < n; ++k)
            {
                final double s = cuts[k], e = cuts[k + 1];
                if (!(e > s)) continue;
                final double c = 0.5 * (s + e), hh = 0.5 * (e - s);
                final boolean up = c < mid;
                if ((up && !rising) || (!up && !falling)) continue;
                final double w0 = up ? riseSlope * (c - lo) : -fallSlope * (hi - c);
                final double w1 = up ? riseSlope : fallSlope;
                double g0, g1 = 0.0, g2 = 0.0, g3 = 0.0;
                if (Math.abs(c) > K_LIMIT) g0 = f(c);
                else
                {
                    g0 = p(c);
                    if (g0 > 1.0 || g0 < -1.0) g0 = g0 > 1.0 ? 1.0 : -1.0;
                    else
                    {
                        g1 = 1.0 + c * (2.0 * a - 3.0 * K_B * c);
                        g2 = a - 3.0 * K_B * c;
                        g3 = -K_B;
                    }
                }
                final double h3 = hh * hh * hh;
                sum += 2.0 * hh * g0 * w0 + (2.0 / 3.0) * h3 * (g2 * w0 + g1 * w1)
                     + 0.4 * h3 * hh * hh * g3 * w1;
            }
            return sum;
        }
    }

    /** Polynomial waveshaper x + 0.25 character x^2 - 0.15 x^3, clamped to +-1. */
    static final class ExciterAlgorithm extends SaturationAlgorithm
    {
        private final double[] u1 = new double[AA_CH], u2 = new double[AA_CH];
        private final AdaaExciter curve = new AdaaExciter();

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
            reset();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.EXCITER;
        }

        @Override
        void reset()
        {
            Arrays.fill(u1, 0.0);
            Arrays.fill(u2, 0.0);
            resetAlign();
        }

        /** Second-order sample: the exact kernel on u = in * drive (one sample of delay). */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final double u = sample * drive;
            final int c = ch & (AA_CH - 1);
            if (takeArm(c)) u1[c] = u2[c] = u;
            curve.setCharacter(0.25 * character);
            final double y = curve.average(u, u1[c], u2[c]);
            u2[c] = u1[c];
            u1[c] = u;
            return y;
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            final double x = clamp(sample * drive, -10.0, 10.0);
            final double x2 = x * x;
            final double x3 = x2 * x;
            final double result = x + character * 0.25 * x2 - 0.15 * x3;
            return clamp(result, -1.0, 1.0);
        }
    }

    // -- Wavefolder -------------------------------------------------------------

    /**
     * Folder curve sin(in drive + bias) - sin(bias), bias = character pi / 4:
     * unit small-signal gain and a +-1 ceiling. At neutral drive the curve
     * never reaches its first peak; folds appear once in drive crosses pi/2.
     * Always first-order antialiased (second order with SecondOrder).
     */
    static final class WavefolderAlgorithm extends SaturationAlgorithm
    {
        private static final int MAX_CH = 16;
        private final double[] lastX = new double[MAX_CH];
        private final Adaa.Adaa2[] adaa2 = newAdaa2(MAX_CH);
        private final double[] prevBias = new double[MAX_CH];
        private final Memo restSin = new Memo(), biasSin = new Memo();

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
            reset();
        }

        @Override
        void reset()
        {
            Arrays.fill(lastX, 0.0);
            for (Adaa.Adaa2 a : adaa2) a.reset();
            Arrays.fill(prevBias, 0.0);
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.WAVEFOLDER;
        }

        /** Second-order sample (one sample of delay). */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final double bias = character * (Math.PI / 4.0);
            final double x = sample * drive + bias;
            final int c = ch & (MAX_CH - 1);
            // Second order on sin; the resting offset sin(bias) is taken one
            // sample back, at the centre.
            if (takeArm(c))
            {
                adaa2[c].reset(x);
                prevBias[c] = bias;
            }
            final double y = adaa2[c].process(Adaa.AdaaSine.INSTANCE, x);
            final double sbPrev = restSin.sin(prevBias[c]);
            prevBias[c] = bias;
            lastX[c] = x;
            return y - sbPrev;
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            final double bias = character * (Math.PI / 4.0);
            final double x = sample * drive + bias;
            final int c = ch & (MAX_CH - 1);
            final double sb = biasSin.sin(bias);

            // Mean of sin over [x0, x] as sin(mid) sinc(half): exact, with
            // no cancellation.
            final double half = 0.5 * (x - lastX[c]);
            final double mid = 0.5 * (x + lastX[c]);
            final double sinc = (Math.abs(half) > 1e-4) ? Math.sin(half) / half
                                                        : 1.0 - half * half / 6.0;
            lastX[c] = x;
            return Math.sin(mid) * sinc - sb;
        }
    }

    // -- Bitcrusher -------------------------------------------------------------

    /** Quantisation to 16 down to 2 bits (by the drive) with TPDF dither from a xorshift32. */
    static final class BitcrusherAlgorithm extends SaturationAlgorithm
    {
        private int rngState = 0x9E3779B9;
        private double steps = 1.0;
        private double invSteps = 1.0;

        private double nextRandom()
        {
            rngState ^= rngState << 13;
            rngState ^= rngState >>> 17;
            rngState ^= rngState << 5;
            final double scale = 1.0 / (double) 0xFFFFFFFFL;
            return (double) (rngState & 0xFFFFFFFFL) * scale - 0.5;
        }

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
            if (rngState == 0) rngState = 1;   // xorshift fixed point guard
        }

        @Override
        void reset()
        {
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.BITCRUSHER;
        }

        @Override
        void update(double drive, double character, double sampleRate)
        {
            final double clamped = clamp(drive, 1.0, 100.0);
            final double bitDepth = mapRange(clamped, 1.0, 100.0, 16.0, 2.0);
            steps = Math.pow(2.0, bitDepth);
            invSteps = 1.0 / steps;
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            // TPDF: the difference of two independent uniforms, +-1 LSB peak.
            final double first = nextRandom();
            final double dither = (first - nextRandom()) * invSteps;
            return invSteps * round((sample + dither) * steps);
        }
    }

    // -- Tape second-order kernel -----------------------------------------------

    /**
     * Antiderivatives of the anhysteretic tape curve, for its second-order
     * kernel. With x the solution of H = a x - rho L(x) the curve output is
     * L(x), and with G = ln(sinh x / x), P = int_0^x G and Q = int_0^x L^2,
     * F1(H) = a G - rho L^2 / 2 and
     * F2(H) = a^2 P - a rho G L + (a rho / 2) Q + rho^2 L^3 / 6.
     * P(x) = x (1 + G) - x^2/2 - pi^2/12 + A(x) and
     * Q(x) = x - L - 2 R(x), R(x) = (x - G) - ln 2 + 1/x + C + B(x), where
     * A = Li2(e)/2 - x ln(1 - e) and B = ln(1 - e) - 2 sum_k E1(2 k x)
     * (e = exp(-2x)) are tabulated once as degree-11 Chebyshev pieces, eight
     * per octave on [0.5, 32), and are zero beyond 32; below 0.5 P, Q, L, its
     * slope and G come from their Taylor series.
     */
    static final class AdaaTapeTable
    {
        private static final class Holder
        {
            static final AdaaTapeTable INSTANCE = new AdaaTapeTable();
        }

        /** The shared table, built on first use. */
        static AdaaTapeTable instance()
        {
            return Holder.INSTANCE;
        }

        private static final int K_DEG = 11, K_CELLS = 48;
        private static final double K_START = 0.5, K_END = 32.0;
        private static final long K_FIRST_CELL = 0x1FF0L;   // bits(0.5) >> 49
        private static final double K_PI_SQUARED_OVER_12 = 0.82246703342411321824;
        private static final double K_LN2 = 0.69314718055994530942;
        // Taylor coefficients in u = x^2: L = x sum kL u^n, its slope sum kLp u^n,
        // G = u sum kG u^n, P = x^3 sum kP u^n, Q = x^3 sum kQ u^n.
        private static final double[] K_L = { 0.3333333333333333, -0.022222222222222223, 0.0021164021164021165,
            -0.00021164021164021165, 2.1377799155576935e-05, -2.1644042808063972e-06, 2.1925947851873778e-07,
            -2.2214608789979678e-08, 2.2507846516808994e-09, -2.2805151204592183e-10, 2.3106432599002624e-11,
            -2.3411706819824882e-12, 2.3721017400233653e-13 };
        private static final double[] K_LP = { 0.3333333333333333, -0.06666666666666667, 0.010582010582010581,
            -0.0014814814814814814, 0.0001924001924001924, -2.380844708887037e-05, 2.8503732207435913e-06,
            -3.332191318496952e-07, 3.8263339078575285e-08, -4.332978728872515e-09, 4.852350845790551e-10,
            -5.3846925685597234e-11, 5.930254350058414e-12 };
        private static final double[] K_G = { 0.16666666666666666, -0.005555555555555556, 0.0003527336860670194,
            -2.6455026455026456e-05, 2.1377799155576935e-06, -1.803670234005331e-07, 1.5661391322766983e-08,
            -1.3884130493737299e-09, 1.2504359176004997e-10, -1.1402575602296091e-11, 1.0502923908637557e-12,
            -9.754877841593701e-14, 9.123468230859098e-15 };
        private static final double[] K_P = { 0.05555555555555555, -0.0011111111111111111, 5.039052658100277e-05,
            -2.9394473838918285e-06, 1.9434362868706303e-07, -1.3874386415425623e-08, 1.0440927548511323e-09,
            -8.167135584551352e-11, 6.581241671581577e-12, -5.429797905855281e-13, 4.5664886559293725e-14,
            -3.9019511366374804e-15, 3.379062307725592e-16 };
        private static final double[] K_Q = { 0.037037037037037035, -0.002962962962962963, 0.00027210884353741496,
            -2.6128421190149586e-05, 2.5579323318621057e-06, -2.5299170598315896e-07, 2.5176556628643635e-08,
            -2.5155828459962992e-09, 2.520569343665452e-10, -2.5307045227479064e-11, 2.5447507412853134e-12,
            -2.5618698792252346e-13, 2.5814742395034203e-14 };

        private final double[][] aRows = new double[K_CELLS][K_DEG + 1];
        private final double[][] bRows = new double[K_CELLS][K_DEG + 1];
        private final double[] mid = new double[K_CELLS];
        private final double[] invHalf = new double[K_CELLS];
        private final double cR;

        /** L (out[0]) and its slope (out[1]) for x &gt;= 0; out[2] is exp(-2x) from 0.5 on (else 0). */
        static void langevin(double x, double[] out)
        {
            if (x < K_START)
            {
                final double u = x * x;
                double l = K_L[12], lp = K_LP[12];
                for (int n = 11; n >= 0; --n)
                {
                    l = l * u + K_L[n];
                    lp = lp * u + K_LP[n];
                }
                out[0] = x * l;
                out[1] = lp;
                out[2] = 0.0;
                return;
            }
            final double e = Math.exp(-2.0 * x), om = 1.0 - e;
            out[0] = (1.0 + e) / om - 1.0 / x;
            out[1] = 1.0 / (x * x) - 4.0 * e / (om * om);
            out[2] = e;
        }

        /** G (out[0]) = ln(sinh x / x), P (out[1]) and Q (out[2]) for x &gt;= 0, given L(x). */
        void antiderivatives(double x, double l, double[] out)
        {
            antiderivatives(x, l, x < K_START ? 0.0 : Math.exp(-2.0 * x), out);
        }

        /** {@link #antiderivatives(double, double, double[])} given also e = exp(-2x) (used from x = 0.5). */
        void antiderivatives(double x, double l, double e, double[] out)
        {
            if (x < K_START)
            {
                final double u = x * x, x3 = u * x;
                double g = K_G[12], pp = K_P[12], q = K_Q[12];
                for (int n = 11; n >= 0; --n)
                {
                    g = g * u + K_G[n];
                    pp = pp * u + K_P[n];
                    q = q * u + K_Q[n];
                }
                out[0] = u * g;
                out[1] = x3 * pp;
                out[2] = x3 * q;
                return;
            }
            final double gg = x + Math.log((1.0 - e) / (2.0 * x));
            double av = 0.0, bv = 0.0;
            if (x < K_END)
            {
                // Eight cells per octave: exponent and top three mantissa bits.
                final int k = (int) ((Double.doubleToRawLongBits(x) >>> 49) - K_FIRST_CELL);
                final double t = (x - mid[k]) * invHalf[k];
                // The two Clenshaw recurrences, side by side.
                final double[] ca = aRows[k], cb = bRows[k];
                double a1 = 0.0, a2 = 0.0, b1 = 0.0, b2 = 0.0;
                final double t2 = 2.0 * t;
                for (int n = K_DEG; n >= 1; --n)
                {
                    final double a0 = ca[n] + t2 * a1 - a2;
                    a2 = a1;
                    a1 = a0;
                    final double b0 = cb[n] + t2 * b1 - b2;
                    b2 = b1;
                    b1 = b0;
                }
                av = ca[0] + t * a1 - a2;
                bv = cb[0] + t * b1 - b2;
            }
            out[0] = gg;
            out[1] = x * (1.0 + gg) - 0.5 * x * x - K_PI_SQUARED_OVER_12 + av;
            out[2] = x - l - 2.0 * ((x - gg) - K_LN2 + 1.0 / x + cR + bv);
        }

        /** E1(z) for z &gt;= 1: modified Lentz evaluation of its continued fraction. */
        private static double e1(double z)
        {
            double b = z + 1.0, c = 1e300, d = 1.0 / b, h = d;
            for (int i = 1; i < 300; ++i)
            {
                final double an = -(double) i * i;
                b += 2.0;
                d = 1.0 / (an * d + b);
                c = b + an / c;
                final double del = c * d;
                h *= del;
                if (Math.abs(del - 1.0) < 4e-16) break;
            }
            return h * Math.exp(-z);
        }

        /** T(x) = int_x^inf 2 / (t (e^2t - 1)) dt = 2 sum_k E1(2 k x), x &gt;= 0.5. */
        private static double tail(double x)
        {
            double t = 0.0;
            for (int k = 1; k < 400; ++k)
            {
                final double v = e1(2.0 * k * x);
                t += v;
                if (v < 1e-25) break;
            }
            return 2.0 * t;
        }

        private AdaaTapeTable()
        {
            final double[] av = new double[K_DEG + 1], bv = new double[K_DEG + 1];
            for (int k = 0; k < K_CELLS; ++k)
            {
                final double lo = Math.scalb(1.0 + (k % 8) / 8.0, k / 8 - 1);
                final double hi = lo + Math.scalb(1.0 / 8.0, k / 8 - 1);
                mid[k] = 0.5 * (lo + hi);
                invHalf[k] = 2.0 / (hi - lo);
                for (int j = 0; j <= K_DEG; ++j)
                {
                    final double x = 0.5 * (lo + hi)
                                   + 0.5 * (hi - lo) * Math.cos(Math.PI * (j + 0.5) / (K_DEG + 1));
                    final double e = Math.exp(-2.0 * x);
                    double li = 0.0, ek = e;
                    for (int m = 1; m < 400 && ek > 1e-26; ++m, ek *= e)
                        li += ek / ((double) m * m);
                    av[j] = 0.5 * li - x * Math.log1p(-e);
                    bv[j] = Math.log1p(-e) - tail(x);
                }
                for (int n = 0; n <= K_DEG; ++n)
                {
                    double sa = 0.0, sb = 0.0;
                    for (int j = 0; j <= K_DEG; ++j)
                    {
                        final double w = Math.cos(Math.PI * n * (j + 0.5) / (K_DEG + 1));
                        sa += av[j] * w;
                        sb += bv[j] * w;
                    }
                    aRows[k][n] = (n == 0 ? 1.0 : 2.0) * sa / (K_DEG + 1);
                    bRows[k][n] = (n == 0 ? 1.0 : 2.0) * sb / (K_DEG + 1);
                }
            }
            // C = R(0.5) - ln 0.5 - 2 + T(0.5), R(0.5) = sum kL[n] 0.5^(2n+1) / (2n+1).
            double r = 0.0;
            for (int n = 12; n >= 0; --n) r = r * 0.25 + K_L[n] / (2 * n + 1);
            cR = 0.5 * r + K_LN2 - 2.0 + tail(0.5);
        }
    }

    /**
     * Second-order kernel of the tape curve (one stream): the curve's average
     * over the linear B-spline with knots at the last three inputs, from F2
     * differences. Close inputs use the Euler-Maclaurin form of the divided
     * difference, (F1(a) + F1(b))/2 - (a - b)(f(a) - f(b))/12, and the
     * confluent and coincident forms written with the same per-sample data.
     * A change of the curve (character) re-evaluates the two held inputs on
     * the new curve.
     */
    static final class AdaaTape
    {
        /** A solved input: H, the internal variable x, the output f, its slope and the two antiderivatives. */
        static final class Point
        {
            double h, x, f, fp, f1, f2;

            void copyFrom(Point o)
            {
                h = o.h;
                x = o.x;
                f = o.f;
                fp = o.fp;
                f1 = o.f1;
                f2 = o.f2;
            }
        }

        AdaaTapeTable table = null;
        /** Curve of the points held. */
        double alpha = -1.0, a = 1.0, rho = 0.0;
        /** Inputs one and two samples ago. */
        double h1 = 0.0, h2 = 0.0;
        Point p1 = new Point(), p2 = new Point();
        private Point p0 = new Point();
        private final Point merged = new Point();
        private final double[] lv = new double[3];
        private final double[] gpq = new double[3];

        /** Holds u as the whole history (arming). */
        void reset(double u, double alphaIn)
        {
            h1 = h2 = u;
            setCurve(alphaIn);
            point(u, 3.0 * u, p1);
            p2.copyFrom(p1);
        }

        /** The antialiased output for input H, centred one sample back. */
        double process(double hIn, double alphaIn)
        {
            if (alphaIn != alpha)
            {
                setCurve(alphaIn);
                point(h1, p1.x, p1);
                point(h2, p2.x, p2);
            }
            point(hIn, p1.x, p0);
            final double y = average(p0, p1, p2);
            final Point spare = p2;
            p2 = p1;
            p1 = p0;
            p0 = spare;
            h2 = h1;
            h1 = hIn;
            return y;
        }

        void setCurve(double alphaIn)
        {
            alpha = alphaIn;
            a = 1.0 / (3.0 * (1.0 - alpha));
            rho = alpha / (1.0 - alpha);
        }

        /** Solves H = a x - rho L(x) by Newton from the seed (slope in [1/3, a]) into p. */
        void point(double hIn, double seed, Point p)
        {
            final double s = hIn < 0.0 ? -1.0 : 1.0, h = Math.abs(hIn);
            double x = max(0.0, s * seed), l, lp;
            for (int it = 0; it < 40; ++it)
            {
                AdaaTapeTable.langevin(x, lv);
                l = lv[0];
                lp = lv[1];
                final double step = (a * x - rho * l - h) / (a - rho * lp);
                x = max(0.0, x - step);
                if (!(Math.abs(step) > 1e-15 * (1.0 + x))) break;
            }
            AdaaTapeTable.langevin(x, lv);
            l = lv[0];
            lp = lv[1];
            table.antiderivatives(x, l, lv[2], gpq);
            final double g = gpq[0], pp = gpq[1], q = gpq[2];
            p.h = hIn;
            p.x = s * x;
            p.f = s * l;
            p.fp = lp / (a - rho * lp);
            p.f1 = a * g - 0.5 * rho * l * l;
            p.f2 = s * (a * a * pp - a * rho * g * l + 0.5 * a * rho * q + rho * rho * l * l * l * (1.0 / 6.0));
        }

        static double div(Point p, Point q)
        {
            final double d = p.h - q.h;
            if (Math.abs(d) > 3e-3) return (p.f2 - q.f2) / d;
            return 0.5 * (p.f1 + q.f1) - d * (p.f - q.f) * (1.0 / 12.0);
        }

        double average(Point q0, Point q1, Point q2)
        {
            final double d02 = q0.h - q2.h;
            if (Math.abs(d02) > 1e-3) return 2.0 * (div(q0, q1) - div(q1, q2)) / d02;
            // q0 and q2 merge into their mean b: F1, F2 and f at b from both sides.
            final double dd = 0.125 * d02 * d02;
            final Point b = merged;
            b.h = 0.5 * (q0.h + q2.h);
            b.fp = 0.5 * (q0.fp + q2.fp);
            b.f = 0.5 * (q0.f + q2.f);
            b.f1 = 0.5 * (q0.f1 + q2.f1) - dd * b.fp;
            b.f2 = 0.5 * (q0.f2 + q2.f2) - dd * b.f;
            b.x = 0.0;
            final double e = b.h - q1.h;
            if (Math.abs(e) > 1e-4) return 2.0 * (b.f1 - div(b, q1)) / e;
            return q1.f + q1.fp * ((q0.h + q1.h + q2.h) * (1.0 / 3.0) - q1.h);
        }
    }

    // -- Tape -------------------------------------------------------------------

    /**
     * Anhysteretic Langevin magnetisation (tape under AC bias) between a
     * drive-dependent 80 Hz head bump and a high-frequency roll-off. The
     * static curve is M = Ms L((H + alpha M) / a), solved per sample by
     * Newton-Raphson from the previous sample's M; Ms = 3a and
     * out = (1 - alpha) M give unit small-signal gain and a +-1 ceiling.
     */
    static final class TapeAlgorithm extends SaturationAlgorithm
    {
        private static final int MAX_CH = 16;
        private final BiquadFilter[] preFilters = newFilters(MAX_CH);
        private final BiquadFilter[] postFilters = newFilters(MAX_CH);
        /** Per-channel Newton warm start. */
        private final double[] m = new double[MAX_CH];
        /** Second-order kernels (set up in prepare()). */
        private final AdaaTape[] kernel = new AdaaTape[MAX_CH];
        private int numChannels = 0;
        private double lastDrive = -1.0;
        private double lastSampleRate = 0.0;

        TapeAlgorithm()
        {
            for (int i = 0; i < MAX_CH; ++i) kernel[i] = new AdaaTape();
        }

        /** Langevin function L(x) = coth(x) - 1/x, with its series near zero and asymptote far out. */
        static double langevin(double x)
        {
            final double ax = Math.abs(x);
            if (ax < 0.5)
            {
                final double x2 = x * x;
                return x * (1.0 / 3.0 - x2 * (1.0 / 45.0 - x2 * (2.0 / 945.0)));
            }
            if (ax > 20.0) return Math.copySign(1.0, x) - 1.0 / x;
            return 1.0 / Math.tanh(x) - 1.0 / x;
        }

        /** dL/dx = 1/x^2 - csch^2(x), with the matching series and asymptote. */
        static double langevinDeriv(double x)
        {
            final double ax = Math.abs(x);
            if (ax < 0.5)
            {
                final double x2 = x * x;
                return 1.0 / 3.0 - x2 * (1.0 / 15.0 - x2 * (2.0 / 189.0));
            }
            if (ax > 20.0) return 1.0 / (x * x);
            final double s = Math.sinh(x);
            return 1.0 / (x * x) - 1.0 / (s * s);
        }

        @Override
        void prepare(double sampleRate, int channels, int maxBlockSize)
        {
            numChannels = Math.min(channels, MAX_CH);
            for (AdaaTape k : kernel) k.table = AdaaTapeTable.instance();
            // A re-prepare can widen the channel count at an unchanged rate
            // and drive: the next update() designs every channel again.
            lastDrive = -1.0;
            lastSampleRate = 0.0;
            reset();
        }

        @Override
        void reset()
        {
            for (BiquadFilter f : preFilters) f.reset();
            for (BiquadFilter f : postFilters) f.reset();
            Arrays.fill(m, 0.0);
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.TAPE;
        }

        @Override
        void update(double drive, double character, double sampleRate)
        {
            // Both filters depend only on drive and rate.
            if (drive == lastDrive && sampleRate == lastSampleRate) return;
            lastDrive = drive;
            lastSampleRate = sampleRate;

            final double driveDb = gainToDecibels(drive);
            final double bumpGain = 1.5 + min(driveDb * 0.05, 3.0);
            final BiquadCoeffs peakCoeffs = BiquadDesign.makePeak(sampleRate, 80.0, 0.6, bumpGain);
            final double lpFreq = max(6000.0, 19000.0 - driveDb * 200.0);
            final BiquadCoeffs lpCoeffs = BiquadDesign.makeLowPass(sampleRate, lpFreq, 0.55);

            for (int ch = 0; ch < numChannels; ++ch)
            {
                preFilters[ch].setCoeffs(peakCoeffs);
                postFilters[ch].setCoeffs(lpCoeffs);
            }
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            final double filtered = preFilters[ch].processSample(sample, 0);
            final double h = filtered * drive;

            // character sets the mean-field coupling: a harder knee and more
            // mid-level bloom as alpha rises.
            final double alpha = 0.35 + 0.15 * character;
            final double a = 1.0 / (3.0 * (1.0 - alpha));
            final double ms = 3.0 * a;

            double mm = m[ch];
            for (int it = 0; it < 3; ++it)
            {
                final double x = (h + alpha * mm) / a;
                final double f = mm - ms * langevin(x);
                final double fp = 1.0 - 3.0 * alpha * langevinDeriv(x);
                mm -= f / fp;
            }
            mm = clamp(mm, -ms, ms);
            m[ch] = mm;

            return postFilters[ch].processSample((1.0 - alpha) * mm, 0);
        }

        /**
         * Second-order sample: the AdaaTape kernel on the magnetisation curve
         * between the same filters (one sample of delay). The plain path's
         * warm start follows, so switching back is seamless.
         */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final double filtered = preFilters[ch].processSample(sample, 0);
            final double h = filtered * drive;
            final double alpha = 0.35 + 0.15 * character;
            final AdaaTape k = kernel[ch];
            if (takeArm(ch)) k.reset(h, alpha);
            final double y = k.process(h, alpha);
            m[ch] = k.p1.f / (1.0 - k.alpha);   // M = Ms L, Ms = 1 / (1 - alpha)
            return postFilters[ch].processSample(y, 0);
        }
    }

    // -- Transformer ------------------------------------------------------------

    /**
     * Band-dependent saturation thresholds at unity small-signal gain, split
     * at 250 Hz: lows reach saturation first (tanh(1.4 x) / 1.4, ceiling
     * 0.71), highs barely do (tanh(0.85 x) / 0.85, ceiling 1.18); the linear
     * response is flat.
     */
    static final class TransformerAlgorithm extends SaturationAlgorithm
    {
        private static final int MAX_CH = 16;
        private final BiquadFilter[] lpFilters = newFilters(MAX_CH);
        private final Adaa.Adaa2[] adaaLow = newAdaa2(MAX_CH), adaaHigh = newAdaa2(MAX_CH);
        private final double[] prevRestLow = new double[MAX_CH], prevRestHigh = new double[MAX_CH];
        /** First-order history (MultiStage). */
        private final double[] firstLow = new double[MAX_CH], firstHigh = new double[MAX_CH];
        /** logCosh() of that history. */
        private final double[] firstLowF = new double[MAX_CH], firstHighF = new double[MAX_CH];
        private int numChannels = 0;
        private double lastSampleRate = 0.0;
        private final Memo restLowTanh = new Memo(), restHighTanh = new Memo(), biasLowTanh = new Memo(), biasHighTanh = new Memo();
        /** The first-order output of the last processBoth(). */
        double bothFirst = 0.0;
        /** The second-order output of the last processBoth(). */
        double bothSecond = 0.0;

        @Override
        void prepare(double sampleRate, int channels, int maxBlockSize)
        {
            numChannels = Math.min(channels, MAX_CH);
            lastSampleRate = 0.0;   // channels added by a re-prepare get coefficients
            reset();
        }

        @Override
        void reset()
        {
            for (BiquadFilter f : lpFilters) f.reset();
            for (Adaa.Adaa2 a : adaaLow) a.reset();
            for (Adaa.Adaa2 a : adaaHigh) a.reset();
            Arrays.fill(prevRestLow, 0.0);
            Arrays.fill(prevRestHigh, 0.0);
            Arrays.fill(firstLow, 0.0);
            Arrays.fill(firstHigh, 0.0);
            Arrays.fill(firstLowF, 0.0);
            Arrays.fill(firstHighF, 0.0);
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.TRANSFORMER;
        }

        @Override
        void update(double drive, double character, double sampleRate)
        {
            // The crossover is fixed at 250 Hz: only a new rate redesigns it.
            if (sampleRate == lastSampleRate) return;
            lastSampleRate = sampleRate;

            final BiquadCoeffs c = BiquadDesign.makeLowPass(sampleRate, 250.0, 0.707);
            for (int ch = 0; ch < numChannels; ++ch)
                lpFilters[ch].setCoeffs(c);
        }

        /** Second-order sample (one sample of delay). */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final double low = lpFilters[ch].processSample(sample, 0);
            final double high = sample - low;
            final double bias = character * 0.2;
            final double kLo = drive * 1.4;
            final double kHi = drive * 0.85;
            final int c = ch & (MAX_CH - 1);
            // Second order on each band's tanh input; resting offsets one
            // sample back, where the outputs are centred.
            final double uLo = (low + bias) * kLo;
            final double uHi = (high + bias) * kHi;
            if (takeArm(c))
                armSecond(c, uLo, uHi, bias, kLo, kHi);
            return secondBands(c, uLo, uHi, bias, kLo, kHi);
        }

        /**
         * Both forms from one band split, for the MultiStage blend at 2x:
         * {@link #bothFirst} as processFirst() (half a sample of delay) and
         * {@link #bothSecond} as processSecond() (one sample), each with its
         * own history.
         */
        void processBoth(double sample, double drive, double character, int ch)
        {
            final double low = lpFilters[ch].processSample(sample, 0);
            final double high = sample - low;
            final double bias = character * 0.2;
            final double kLo = drive * 1.4;
            final double kHi = drive * 0.85;
            final int c = ch & (MAX_CH - 1);
            final double uLo = (low + bias) * kLo;
            final double uHi = (high + bias) * kHi;
            if (takeArm(c))
            {
                armSecond(c, uLo, uHi, bias, kLo, kHi);
                armFirst(c, uLo, uHi);
            }
            bothFirst = firstBands(c, uLo, uHi, bias, kLo, kHi);
            bothSecond = secondBands(c, uLo, uHi, bias, kLo, kHi);
        }

        private void armSecond(int c, double uLo, double uHi, double bias, double kLo, double kHi)
        {
            adaaLow[c].reset(uLo);
            adaaHigh[c].reset(uHi);
            prevRestLow[c] = bias * kLo;
            prevRestHigh[c] = bias * kHi;
        }

        private void armFirst(int c, double uLo, double uHi)
        {
            firstLow[c] = uLo;
            firstHigh[c] = uHi;
            firstLowF[c] = LogCosh.logCosh(uLo);
            firstHighF[c] = LogCosh.logCosh(uHi);
        }

        private double secondBands(int c, double uLo, double uHi, double bias, double kLo, double kHi)
        {
            final double yLo = adaaLow[c].process(Adaa.AdaaTanh.INSTANCE, uLo) - restLowTanh.tanh(prevRestLow[c]);
            final double yHi = adaaHigh[c].process(Adaa.AdaaTanh.INSTANCE, uHi) - restHighTanh.tanh(prevRestHigh[c]);
            prevRestLow[c] = bias * kLo;
            prevRestHigh[c] = bias * kHi;
            return yLo / 1.4 + yHi / 0.85;
        }

        private static double mean(double u, double u0, double f, double f0)
        {
            final double d = u - u0;
            return Math.abs(d) > 1e-7 ? (f - f0) / d : Math.tanh(0.5 * (u + u0));
        }

        /** First-order means of each band's tanh; one logCosh() per band. */
        private double firstBands(int c, double uLo, double uHi, double bias, double kLo, double kHi)
        {
            final double fLo = LogCosh.logCosh(uLo), fHi = LogCosh.logCosh(uHi);
            final double yLo = mean(uLo, firstLow[c], fLo, firstLowF[c]) - biasLowTanh.tanh(bias * kLo);
            final double yHi = mean(uHi, firstHigh[c], fHi, firstHighF[c]) - biasHighTanh.tanh(bias * kHi);
            firstLow[c] = uLo;
            firstHigh[c] = uHi;
            firstLowF[c] = fLo;
            firstHighF[c] = fHi;
            return yLo / 1.4 + yHi / 0.85;
        }

        /**
         * First-order sample (half a sample of delay), for the MultiStage
         * cascade at 2x. The history is seeded when order 2 is armed.
         */
        double processFirst(double sample, double drive, double character, int ch)
        {
            final double low = lpFilters[ch].processSample(sample, 0);
            final double high = sample - low;
            final double bias = character * 0.2;
            final double kLo = drive * 1.4;
            final double kHi = drive * 0.85;
            final int c = ch & (MAX_CH - 1);
            final double uLo = (low + bias) * kLo;
            final double uHi = (high + bias) * kHi;
            if (takeArm(c))
                armFirst(c, uLo, uHi);
            return firstBands(c, uLo, uHi, bias, kLo, kHi);
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            final double low = lpFilters[ch].processSample(sample, 0);
            final double high = sample - low;
            final double bias = character * 0.2;
            final double kLo = drive * 1.4;
            final double kHi = drive * 0.85;
            final double satLow = (fastTanh((low + bias) * kLo) - fastTanh(bias * kLo)) / 1.4;
            final double satHigh = (fastTanh((high + bias) * kHi) - fastTanh(bias * kHi)) / 0.85;
            return satLow + satHigh;
        }
    }

    // -- Downsample -------------------------------------------------------------

    /** Sample-and-hold rate reduction (by the drive, 1 to 50) behind an anti-aliasing low-pass. */
    static final class DownsampleAlgorithm extends SaturationAlgorithm
    {
        private static final int MAX_CH = 16;
        private final BiquadFilter[] aaFilters = newFilters(MAX_CH);
        private final double[] lastSample = new double[MAX_CH];
        private final int[] counter = new int[MAX_CH];
        private int numChannels = 0;
        private int reduction = 1;

        @Override
        void prepare(double sampleRate, int channels, int maxBlockSize)
        {
            numChannels = Math.min(channels, MAX_CH);
            reset();
        }

        @Override
        void reset()
        {
            for (BiquadFilter f : aaFilters) f.reset();
            Arrays.fill(lastSample, 0.0);
            Arrays.fill(counter, 0);
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.DOWNSAMPLE;
        }

        @Override
        void update(double drive, double character, double sampleRate)
        {
            final double clamped = clamp(drive, 1.0, 100.0);
            reduction = Math.max(1, (int) mapRange(clamped, 1.0, 100.0, 1.0, 50.0));

            final BiquadCoeffs c = BiquadDesign.makeLowPass(sampleRate, sampleRate / (2.5 * reduction), 0.707);
            for (int ch = 0; ch < numChannels; ++ch)
                aaFilters[ch].setCoeffs(c);
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            final double filtered = aaFilters[ch].processSample(sample, 0);
            if (++counter[ch] >= reduction)
            {
                counter[ch] = 0;
                lastSample[ch] = filtered;
            }
            return lastSample[ch];
        }
    }

    // -- Curve ------------------------------------------------------------------

    /**
     * A soft clipper shaped by three controls on a base curve with unit slope
     * at the origin and a ceiling of 1: hardness (0 to 0.95), how far it stays
     * a straight line; curvature (0.5 to 4), the exponent p of
     * f(u) = base(u^p)^(1/p); asymmetry (-1 to 1), a ceiling of 2^-asymmetry
     * on one half. It has no antiderivative kernel: with antialiasing on it
     * is delayed to the common alignment.
     */
    static final class CurveAlgorithm extends SaturationAlgorithm
    {
        private double hardness = 0.0, curvature = 1.0, asymmetry = 0.0;
        private int base = 0;
        private double knee = 0.0, span = 1.0, invSpan = 1.0, power = 1.0, invPower = 1.0, negative = 1.0, positive = 1.0;
        private int baseNow = 0;

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
        }

        @Override
        void reset()
        {
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.CURVE;
        }

        /** Sets the three controls; values that are not finite keep what they had. */
        void setShape(double h, double c, double a)
        {
            if (Double.isFinite(h)) hardness = clamp(h, 0.0, 0.95);
            if (Double.isFinite(c)) curvature = clamp(c, 0.5, 4.0);
            if (Double.isFinite(a)) asymmetry = clamp(a, -1.0, 1.0);
        }

        double getHardness()
        {
            return hardness;
        }

        double getCurvature()
        {
            return curvature;
        }

        double getAsymmetry()
        {
            return asymmetry;
        }

        /** Sets the base curve (an index of Saturation.CurveBase). */
        void setBase(int b)
        {
            base = clamp(b, 0, 6);
        }

        int getBase()
        {
            return base;
        }

        @Override
        void update(double drive, double character, double sampleRate)
        {
            baseNow = base;
            knee = hardness;
            span = 1.0 - knee;
            invSpan = 1.0 / span;
            power = curvature;
            invPower = 1.0 / power;
            final double asym = asymmetry;
            negative = (asym > 0.0) ? Math.pow(2.0, asym) : 1.0;
            positive = (asym < 0.0) ? Math.pow(2.0, -asym) : 1.0;
        }

        /**
         * The base curve beyond the knee, v &gt;= 0: tanh; algebraic
         * v / sqrt(1 + v^2); arctangent (2 / pi) atan(pi v / 2); the error
         * function erf(sqrt(pi) v / 2); a cubic that reaches 1 at v = 1.5; a
         * valve, 1 - e^-v on the positive half and algebraic on the negative
         * one; a rectifier, tanh on the positive half and tanh(v) e^-v on the
         * negative one.
         */
        double bend(double v, boolean neg)
        {
            switch (baseNow)
            {
                case 1: return v / Math.sqrt(1.0 + v * v);
                case 2: return 0.6366197723675814 * Math.atan(1.5707963267948966 * v);
                case 3: return erf(0.886226925452758 * v);
                case 4: return v >= 1.5 ? 1.0 : v - (4.0 / 27.0) * v * v * v;
                case 5: return neg ? v / Math.sqrt(1.0 + v * v) : -Math.expm1(-v);
                case 6: return neg ? Math.tanh(v) * Math.exp(-v) : Math.tanh(v);
                default: return Math.tanh(v);
            }
        }

        /** The curve on a driven sample. */
        double shape(double x)
        {
            final double side = (x < 0.0) ? negative : positive;
            final double u = Math.abs(x) * side;
            if (u <= knee) return x;
            final double v = (u - knee) * invSpan;
            final double bent;
            if (baseNow == 0)
                bent = (power == 1.0) ? Math.tanh(v) : Math.pow(Math.tanh(Math.pow(v, power)), invPower);
            else
            {
                final boolean neg = x < 0.0;
                bent = (power == 1.0) ? bend(v, neg) : Math.pow(max(0.0, bend(Math.pow(v, power), neg)), invPower);
            }
            return Math.copySign((knee + span * bent) / side, x);
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            return shape(sample * drive);
        }
    }

    // -- MultiStage -------------------------------------------------------------

    /** Serial cascade Tube, Tape, Transformer, gain-staged so it is transparent at neutral drive. */
    static final class MultiStageAlgorithm extends SaturationAlgorithm
    {
        private final TubeAlgorithm tube = new TubeAlgorithm();
        private final TapeAlgorithm tape = new TapeAlgorithm();
        private final TransformerAlgorithm xfmr = new TransformerAlgorithm();

        @Override
        void prepare(double sampleRate, int numChannels, int maxBlockSize)
        {
            tube.prepare(sampleRate, numChannels, maxBlockSize);
            tape.prepare(sampleRate, numChannels, maxBlockSize);
            xfmr.prepare(sampleRate, numChannels, maxBlockSize);
        }

        @Override
        void reset()
        {
            tube.reset();
            tape.reset();
            xfmr.reset();
            resetAlign();
        }

        @Override
        Saturation.Algorithm getType()
        {
            return Saturation.Algorithm.MULTI_STAGE;
        }

        @Override
        void update(double drive, double character, double sampleRate)
        {
            // The stages are private instances: forward the orders that
            // processSecond() needs for the common delay.
            final boolean second = order == 2;
            final int d = delay;
            tube.setAntialias(second ? (d == 2 || d == 4 ? 1 : 2) : order);
            tube.latchOrder();
            tape.setAntialias(second && d >= 2 ? 2 : 0);
            tape.latchOrder();
            xfmr.setAntialias(second && d >= 2 ? 2 : 0);
            xfmr.latchOrder();
            tape.update(drive * 0.6, character, sampleRate);
            xfmr.update(drive * 0.8, character, sampleRate);
        }

        double processSample(double sample, double drive, double character, int ch)
        {
            // Inter-stage makeup undoes each stage's fixed small-signal
            // factor (1/0.5, 1/0.6, 1/0.8).
            final double tubeOut = tube.processSample(sample, drive * 0.5, character, ch) * 2.0;
            final double tapeOut = tape.processSample(tubeOut, drive * 0.6, character, ch) * (1.0 / 0.6);
            return xfmr.processSample(tapeOut, drive * 0.8, character, ch) * (1.0 / 0.8);
        }

        /**
         * Second order, delayed by exactly the common delay:
         * 1 (1x) the tube kernel only; 2 a first-order tube, the tape kernel
         * and a first-order transformer; 4 (2x) a first-order tube and the
         * tape kernel, then two transformer chains blended by drive
         * (smoothstep from +14 to +16 dB): the first-order transformer padded
         * by two samples, and the transformer kernel with a 1.5-sample
         * linear-phase interpolator; 3 (4x and above) the kernels of all
         * three stages.
         */
        double processSecond(double sample, double drive, double character, int ch)
        {
            final int d = delay;
            if (d == 1)
            {
                final double tubeOut = tube.processSecond(sample, drive * 0.5, character, ch) * 2.0;
                final double tapeOut = tape.processSample(tubeOut, drive * 0.6, character, ch) * (1.0 / 0.6);
                return xfmr.processSample(tapeOut, drive * 0.8, character, ch) * (1.0 / 0.8);
            }
            if (d == 2)
            {
                final double tubeOut = tube.processSample(sample, drive * 0.5, character, ch) * 2.0;
                final double tapeOut = tape.processSecond(tubeOut, drive * 0.6, character, ch) * (1.0 / 0.6);
                return xfmr.processFirst(tapeOut, drive * 0.8, character, ch) * (1.0 / 0.8);
            }
            if (d == 4)
            {
                // Both transformer forms run on every sample, so the blend
                // between them never meets stale history.
                final double tubeOut = tube.processSample(sample, drive * 0.5, character, ch) * 2.0;
                final double tapeOut = tape.processSecond(tubeOut, drive * 0.6, character, ch) * (1.0 / 0.6);
                xfmr.processBoth(tapeOut, drive * 0.8, character, ch);
                final double low = delayBy(xfmr.bothFirst * (1.0 / 0.8), ch, 2);
                final double high = delayHalfSample(xfmr.bothSecond * (1.0 / 0.8), ch);
                // Smoothstep in drive gain from +14 to +16 dB, where the two
                // chains alias alike.
                final double kFrom = 5.011872336272722, kSpan = 6.309573444801933 - 5.011872336272722;
                final double u = clamp((drive - kFrom) / kSpan, 0.0, 1.0);
                return low + (u * u * (3.0 - 2.0 * u)) * (high - low);
            }
            final double tubeOut = tube.processSecond(sample, drive * 0.5, character, ch) * 2.0;
            final double tapeOut = tape.processSecond(tubeOut, drive * 0.6, character, ch) * (1.0 / 0.6);
            return xfmr.processSecond(tapeOut, drive * 0.8, character, ch) * (1.0 / 0.8);
        }
    }
}
