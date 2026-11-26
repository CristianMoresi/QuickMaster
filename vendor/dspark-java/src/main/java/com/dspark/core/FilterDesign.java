package com.dspark.core;

/**
 * Analog prototypes and the digital sections that follow their magnitude up
 * to the Nyquist frequency.
 * <p>
 * A digital filter designed by the bilinear transform has the response of its
 * analog prototype squeezed into the band below Nyquist: a bell near the top
 * comes out narrower than asked, a shelf reaches its plateau early and a
 * low-pass falls off a cliff. The designs here describe a filter as analog
 * sections ({@link AnalogSection}, or an {@link AnalogCascade} of them) and
 * turn each into the biquad whose magnitude stays on the analog curve:
 * <ol>
 * <li>the poles are the analog poles carried over by impulse invariance, so a
 *     resonance keeps its frequency and its width wherever it is;</li>
 * <li>the numerator is the least-squares fit to the analog power over the band
 *     with those poles. On the unit circle the squared magnitude of a biquad
 *     polynomial is a quadratic in 1 - cos w, so the fit is a linear problem
 *     (M. Vicanek, "Matched Second Order Digital Filters", 2016, solves it
 *     through three points);</li>
 * <li>where that is not within a hundredth of a decibel, up to
 *     {@link #FIT_STEPS} steps move numerator and denominator together, each a
 *     linear least-squares problem weighted so that it minimises the relative
 *     error (C. K. Sanathanan and J. Koerner, 1963). Nothing is tuned or
 *     searched: the same input always takes the same few steps.</li>
 * </ol>
 * The fit looks at sixteen frequencies spread over the band and at fourteen
 * more around the poles and the zeros of the section, so a narrow peak is seen
 * however narrow it is. Up to 20 kHz every frequency counts the same; above,
 * less and less. Zeros a prototype has at zero frequency or on the frequency
 * axis (a high-pass, a band-pass, a notch) are kept exactly where they are,
 * and so is the gain at zero frequency of every section that has one.
 * <p>
 * On top of the sections, FilterDesign builds the cascades of an equalizer:
 * cuts of any slope from 0 to 96 dB per octave (the slope is a real number),
 * bells and shelves of higher order with a flat top, band-pass, band-stop and
 * all-pass of any order, a tilt of constant slope across the band, and an
 * elliptic cut that drops like a wall.
 * <p>
 * Every function is a pure function of its arguments and its work is bounded.
 * A {@link Band} is a plain value: prepare it where the sample rate is set and
 * read it afterwards.
 */
public final class FilterDesign
{
    /** Most joint steps a fit takes. */
    public static final int FIT_STEPS = 2;
    /** The Q that leaves a cut maximally flat. */
    public static final double BUTTERWORTH_Q = 0.7071067811865476;
    /** Steepest cut, in decibels per octave. */
    public static final double MAX_CUT_SLOPE = 96.0;
    /** Highest order of bells, shelves and band filters. */
    public static final int MAX_ORDER = AnalogCascade.MAX_SECTIONS;

    private static final double PI = Math.PI;
    private static final int CLUSTER_POINTS = 7;                      // frequencies around the poles, and around the zeros
    private static final int MAX_POINTS = Band.POINTS + 2 * CLUSTER_POINTS;
    private static final double FLOOR = 1e-8;                         // powers this far under the largest count for little
    private static final double CLOSE = 5.3e-6;                       // a hundredth of a decibel, as squared relative power
    private static final double ULTRASONIC_HZ = 3000.0;               // above 20 kHz an error counts half this far up
    private static final double SETTLED = 0.05;                       // a step that gains less than this share is the last

    private static final double LADDER_STEP = 4.0;                    // ratio between the cells of a ladder of real poles and zeros
    private static final double DEEP = 3981.0717055349733;            // 36 dB as a power ratio
    private static final double MIDDLE = 63.09573444801933;           // 18 dB as a power ratio

    private FilterDesign()
    {
    }

    /**
     * The frequencies a design is fitted over at one sample rate: from 20 Hz
     * to 20 kHz, or to 0.42 of the rate where that is higher, and never past
     * 0.4536 of the rate (what 20 kHz is of 44.1 kHz). An object that designs
     * sections again and again keeps one Band and saves the sines of its
     * frequencies.
     */
    public static final class Band
    {
        /** Frequencies spread evenly in octaves. */
        public static final int POINTS = 16;
        /** The band never goes past this share of the rate. */
        public static final double TOP_SHARE = 20000.0 / 44100.0 + 1e-4;

        double fs = 48000.0, lowHz = 20.0, highHz = 20000.0;
        final double[] omega2 = new double[POINTS];   // squared angular frequency of each point (rad/s)
        final double[] y = new double[POINTS];        // 1 - cos of its digital frequency
        final double[] weight = new double[POINTS];   // how much its error counts

        /** The band of 48 kHz. */
        public Band()
        {
            prepare(48000.0);
        }

        /** The band of a sample rate. */
        public Band(double sampleRate)
        {
            prepare(sampleRate);
        }

        /** Sets the sample rate (an invalid one leaves 48 kHz). */
        public void prepare(double sampleRate)
        {
            fs = (Double.isFinite(sampleRate) && sampleRate > 0.0) ? sampleRate : 48000.0;
            highHz = stdMin(TOP_SHARE * fs, stdMax(20000.0, 0.42 * fs));
            lowHz = stdMin(20.0, 0.01 * highHz);
            final double ratio = Math.pow(highHz / lowHz, 1.0 / (POINTS - 1));
            double hz = lowHz;
            for (int i = 0; i < POINTS; ++i, hz *= ratio)
            {
                final double omega = 2.0 * PI * stdMin(hz, highHz);
                omega2[i] = omega * omega;
                y[i] = yOf(omega / fs);
                weight[i] = weightOf(omega);
            }
        }

        public double getSampleRate()
        {
            return fs;
        }

        public double getLowHz()
        {
            return lowHz;
        }

        public double getHighHz()
        {
            return highHz;
        }
    }

    /* ====================================================================
     *  One section
     * ==================================================================== */

    /**
     * The bilinear transform of a section, with one frequency mapped exactly.
     *
     * @param section    analog section
     * @param sampleRate sample rate in Hz
     * @param exactHz    frequency that keeps its place (from 0.49 of the rate
     *                   up, none does and the plain transform is used)
     */
    public static BiquadCoeffs bilinear(AnalogSection section, double sampleRate, double exactHz)
    {
        final double c = (exactHz > 0.0 && exactHz < 0.49 * sampleRate)
                       ? 2.0 * PI * exactHz / Math.tan(PI * exactHz / sampleRate)
                       : 2.0 * sampleRate;
        final double c2 = c * c;
        final double a0 = section.a2 * c2 + section.a1 * c + section.a0;
        if (!(Math.abs(a0) > 0.0)) return BiquadCoeffs.IDENTITY;
        final double inverse = 1.0 / a0;
        return new BiquadCoeffs(
                (section.b2 * c2 + section.b1 * c + section.b0) * inverse,
                2.0 * (section.b0 - section.b2 * c2) * inverse,
                (section.b2 * c2 - section.b1 * c + section.b0) * inverse,
                2.0 * (section.a0 - section.a2 * c2) * inverse,
                (section.a2 * c2 - section.a1 * c + section.a0) * inverse);
    }

    /**
     * The bilinear transform of a whole analog network, polynomial to
     * polynomial, with no factoring.
     * <p>
     * {@code H(s) = (n_0 + n_1 s + ... + n_N s^N) / (d_0 + d_1 s + ... + d_N s^N)}
     * becomes {@code b(z) / a(z)} of the same order: with
     * {@code s = K (1 - z^-1) / (1 + z^-1)}, each power {@code s^j} times
     * {@code (1 + z^-1)^N} is a fixed binomial row times {@code K^j}, so the
     * digital coefficients are sums of the analog ones over those rows. The
     * result is scaled so {@code a[0] = 1}; it feeds {@link NormalizedLadderN}
     * directly.
     *
     * @param analogNum  N + 1 coefficients of the numerator, from s^0
     * @param analogDen  N + 1 coefficients of the denominator, from s^0
     * @param order      N, 1 to 16
     * @param sampleRate sample rate in Hz
     * @param exactHz    frequency that keeps its place (from 0.49 of the rate
     *                   up, or 0, none does and K = 2 fs)
     * @param digitalNum receives the N + 1 coefficients of b, from z^0
     * @param digitalDen receives the N + 1 coefficients of a, from z^0
     * @return false (outputs untouched) for an order out of range or a
     *         denominator that maps to a[0] = 0
     */
    public static boolean bilinearPolynomial(double[] analogNum, double[] analogDen, int order, double sampleRate,
                                             double exactHz, double[] digitalNum, double[] digitalDen)
    {
        if (order < 1 || order > 16 || !(sampleRate > 0.0)) return false;
        final double c = (exactHz > 0.0 && exactHz < 0.49 * sampleRate)
                       ? 2.0 * PI * exactHz / Math.tan(PI * exactHz / sampleRate)
                       : 2.0 * sampleRate;
        final double[] num = new double[17], den = new double[17], row = new double[17];
        double power = 1.0;   // c^j
        for (int j = 0; j <= order; ++j)
        {
            // The row (1 - z^-1)^j (1 + z^-1)^(order - j).
            java.util.Arrays.fill(row, 0.0);
            row[0] = 1.0;
            for (int r = 0; r < order; ++r)
            {
                final double sign = r < j ? -1.0 : 1.0;
                for (int i = r + 1; i >= 1; --i) row[i] += sign * row[i - 1];
            }
            for (int i = 0; i <= order; ++i)
            {
                num[i] += analogNum[j] * power * row[i];
                den[i] += analogDen[j] * power * row[i];
            }
            power *= c;
        }
        if (!(Math.abs(den[0]) > 0.0)) return false;
        final double inverse = 1.0 / den[0];
        for (int i = 0; i <= order; ++i)
        {
            digitalNum[i] = num[i] * inverse;
            digitalDen[i] = den[i] * inverse;
        }
        return true;
    }

    /**
     * The biquad whose magnitude follows an analog section up to Nyquist.
     * <p>
     * The section returned is minimum phase and has positive gain, whatever
     * the sign and the phase of the prototype; an all-pass prototype gives an
     * all-pass section with the poles of the prototype.
     *
     * @param section analog section with its poles in the left half plane
     * @param band    the frequencies of the fit (it carries the sample rate)
     * @return a stable section (the bilinear transform of the section when the
     *         prototype is not one this design can follow; the identity when a
     *         coefficient is not finite)
     */
    public static BiquadCoeffs digital(AnalogSection section, Band band)
    {
        final double fs = band.fs;
        if (!finite(section)) return BiquadCoeffs.IDENTITY;
        if (!usable(section)) return bilinear(section, fs, section.poleFrequency());

        final Shape shape = shapeOf(section, fs);
        final Denominator start = invariantDenominator(section, fs);
        if (shape.allPass)
        {
            if (shape.first) return new BiquadCoeffs(start.a1, 1.0, 0.0, start.a1, start.a2);
            return new BiquadCoeffs(start.a2, start.a1, 1.0, start.a1, start.a2);
        }

        // Everything is fitted in t = y / y0, with y = 1 - cos w and y0 its
        // value at the pole frequency: the terms of a section are then of the
        // order of one whether its corner is at 20 Hz or next to Nyquist.
        final double poleOmega = shape.first ? section.a0 / section.a1 : Math.sqrt(section.a0 / section.a2);
        final double y0 = yOf(DspMath.clamp(poleOmega / fs, 1e-9, PI));
        final Samples x = new Samples();
        gather(section, shape, band, y0, x);

        final int nN = shape.numeratorTerms(), nD = shape.denominatorTerms();
        final double[] p = new double[5];                   // numerator terms, then denominator terms
        p[nN] = start.d1 * y0 / start.d0;
        if (nD > 1) p[nN + 1] = start.d2 * y0 * y0 / start.d0;

        // The numerator over the poles carried from the prototype.
        final double[][] normal = new double[5][6];
        final double[] measure = new double[2];            // cost, then the worst share of it
        double cost;
        double gap;
        if (nN > 0)
        {
            pass(shape, x, p, false, normal, measure);
            if (!advance(shape, false, 1e-12, normal, p)) return bilinear(section, fs, section.poleFrequency());
            clampNumerator(shape, x, p);
        }

        // Most sections are on the curve already.
        final double[] best = new double[5];
        System.arraycopy(p, 0, best, 0, 5);
        pass(shape, x, p, true, null, measure);
        double bestCost = measure[0];
        gap = measure[1];

        // The rest: numerator and denominator together. Each pass measures
        // the section it has and prepares the step to the next.
        for (int i = 0; i < FIT_STEPS && gap > CLOSE; ++i)
        {
            pass(shape, x, p, true, normal, measure);
            cost = measure[0];
            gap = measure[1];
            if (i > 0)
            {
                if (!(cost < bestCost)) break;
                final boolean settled = bestCost - cost < SETTLED * bestCost;
                System.arraycopy(p, 0, best, 0, 5);
                bestCost = cost;
                if (settled || !(gap > CLOSE)) break;
            }
            if (!advance(shape, true, 1e-6, normal, p)) break;
            if (!positive(p[nN], nD > 1 ? p[nN + 1] : 0.0, x.tMax)) break;
            clampNumerator(shape, x, p);
            if (i + 1 == FIT_STEPS)
            {
                pass(shape, x, p, true, null, measure);
                cost = measure[0];
                gap = measure[1];
                if (cost < bestCost) System.arraycopy(p, 0, best, 0, 5);
            }
        }

        final BiquadCoeffs out = assemble(shape, x, best, y0);
        if (out == null) return bilinear(section, fs, section.poleFrequency());
        return out;
    }

    /** {@link #digital(AnalogSection, Band)} for one design: builds the Band of the rate on the spot. */
    public static BiquadCoeffs digital(AnalogSection section, double sampleRate)
    {
        return digital(section, new Band(sampleRate));
    }

    /**
     * Every section of a cascade through {@link #digital(AnalogSection, Band)}.
     *
     * @param cascade  analog cascade
     * @param band     the frequencies of the fit
     * @param out      receives the sections from index 0
     * @param capacity room in {@code out}
     * @return number of sections written
     */
    public static int digital(AnalogCascade cascade, Band band, BiquadCoeffs[] out, int capacity)
    {
        return digital(cascade, band, out, 0, capacity);
    }

    /**
     * Every section of a cascade through {@link #digital(AnalogSection, Band)},
     * written from {@code out[offset]}.
     *
     * @param cascade  analog cascade
     * @param band     the frequencies of the fit
     * @param out      receives the sections
     * @param offset   index of the first section written
     * @param capacity room in {@code out} from {@code offset}
     * @return number of sections written
     */
    public static int digital(AnalogCascade cascade, Band band, BiquadCoeffs[] out, int offset, int capacity)
    {
        final int n = Math.min(cascade.count, capacity);
        for (int i = 0; i < n; ++i)
            out[offset + i] = digital(cascade.sections[i], band);
        return n;
    }

    /** Every section of a cascade through digital(), for one design. */
    public static int digital(AnalogCascade cascade, double sampleRate, BiquadCoeffs[] out, int capacity)
    {
        return digital(cascade, new Band(sampleRate), out, 0, capacity);
    }

    /** Magnitude in decibels of {@code n} digital sections in series at a frequency. */
    public static double magnitudeDb(BiquadCoeffs[] sections, int n, double hz, double sampleRate)
    {
        double db = 0.0;
        for (int i = 0; i < n; ++i)
            db += 20.0 * Math.log10(stdMax(BiquadDesign.getMagnitude(sections[i], hz, sampleRate), 1e-15));
        return db;
    }

    /* ====================================================================
     *  Cascades: each returns an analog prototype; digital() turns it into biquads
     * ==================================================================== */

    /** {@link #highCut(double, double, double)} with the Butterworth Q. */
    public static AnalogCascade highCut(double hz, double slopeDbPerOctave)
    {
        return highCut(hz, slopeDbPerOctave, BUTTERWORTH_Q);
    }

    /**
     * High cut (low-pass) of any slope from 0 to 96 dB per octave.
     * <p>
     * The slope is a real number. Every 6 dB per octave is one unit of order,
     * and whole orders are Butterworth filters. Between them the poles sit on
     * the same semicircle at the angles of a real order, and one section with
     * a finite zero keeps the corner at -3 dB and the cascade on the curve
     * {@code 1 / (1 + w^(2 order))} down to -36 dB. Under 6 dB per octave the
     * filter is the fraction of a pole, {@code (1 + s)^(-order)}, built from
     * six real poles and zeros two octaves apart.
     *
     * @param hz               corner frequency
     * @param slopeDbPerOctave slope, 0 to 96 (0 gives an empty cascade)
     * @param q                resonance of the sharpest pole pair, as the Q of a
     *                         12 dB per octave filter (0.7071 is Butterworth at
     *                         every slope); no effect up to 6 dB per octave
     */
    public static AnalogCascade highCut(double hz, double slopeDbPerOctave, double q)
    {
        final AnalogCascade out = cutPrototype(slopeDbPerOctave, q);
        final double omega = omegaOf(hz);
        for (int i = 0; i < out.count; ++i) out.sections[i] = scaled(out.sections[i], omega);
        return out;
    }

    /** {@link #lowCut(double, double, double)} with the Butterworth Q. */
    public static AnalogCascade lowCut(double hz, double slopeDbPerOctave)
    {
        return lowCut(hz, slopeDbPerOctave, BUTTERWORTH_Q);
    }

    /** Low cut (high-pass) of any slope from 0 to 96 dB per octave: {@link #highCut} mirrored about the corner. */
    public static AnalogCascade lowCut(double hz, double slopeDbPerOctave, double q)
    {
        final AnalogCascade out = cutPrototype(slopeDbPerOctave, q);
        final double omega = omegaOf(hz);
        for (int i = 0; i < out.count; ++i) out.sections[i] = mirrored(out.sections[i], omega);
        return out;
    }

    /** {@link #lowShelf(double, double, double, double)} with the Butterworth Q. */
    public static AnalogCascade lowShelf(double hz, double gainDb, double order)
    {
        return lowShelf(hz, gainDb, order, BUTTERWORTH_Q);
    }

    /**
     * Low shelf of any order from 1, whole or not: gainDb below the corner,
     * half of it (in decibels) at the corner, 6 dB per octave of transition
     * per order. A Butterworth shelf (S. J. Orfanidis, "High-Order Digital
     * Parametric Equalizer Design", J. Audio Eng. Soc., 2005): it never
     * overshoots, and a cut is the exact inverse of the boost of the same
     * size. From order 2 up, q is the Q of the sharpest pair of poles and of
     * zeros (0.7071: the Butterworth shelf); above it the response rises over
     * the plateau before the corner and dips after it.
     */
    public static AnalogCascade lowShelf(double hz, double gainDb, double order, double q)
    {
        final AnalogCascade out = shelfPrototype(gainDb, order, q);
        final double omega = omegaOf(hz);
        for (int i = 0; i < out.count; ++i) out.sections[i] = scaled(out.sections[i], omega);
        return out;
    }

    /** {@link #highShelf(double, double, double, double)} with the Butterworth Q. */
    public static AnalogCascade highShelf(double hz, double gainDb, double order)
    {
        return highShelf(hz, gainDb, order, BUTTERWORTH_Q);
    }

    /** High shelf of any order: {@link #lowShelf} mirrored about the corner. */
    public static AnalogCascade highShelf(double hz, double gainDb, double order, double q)
    {
        final AnalogCascade out = shelfPrototype(gainDb, order, q);
        final double omega = omegaOf(hz);
        for (int i = 0; i < out.count; ++i) out.sections[i] = mirrored(out.sections[i], omega);
        return out;
    }

    /**
     * Bell of any order: the higher the order, the flatter the top and the
     * steeper the sides (6 dB per octave each per order). Order 1 is
     * {@link AnalogSection#peak}. The gain is gainDb at the centre and half of
     * it (in decibels) at the two edges hz / q apart, whatever the order,
     * whole or not; a cut is the exact inverse of the boost. One biquad per
     * order (a part of an order takes one more).
     */
    public static AnalogCascade bell(double hz, double q, double gainDb, double order)
    {
        final AnalogCascade out = new AnalogCascade();
        final AnalogCascade proto = shelfPrototype(gainDb, order, BUTTERWORTH_Q);
        final double w0 = omegaOf(hz), width = w0 / DspMath.clamp(q, 1e-3, 1e3);
        for (int i = 0; i < proto.count; ++i)
        {
            final AnalogSection s = proto.sections[i];
            if (!s.isSecondOrder())
            {
                out.add(AnalogSection.make(1.0, s.b0 * width, w0 * w0, 1.0, s.a0 * width, w0 * w0));
                continue;
            }
            final BandPair num = bandPair(s.b1, s.b0, width, w0), den = bandPair(s.a1, s.a0, width, w0);
            out.add(AnalogSection.make(1.0, num.upper1, num.upper0, 1.0, den.upper1, den.upper0));
            out.add(AnalogSection.make(1.0, num.lower1, num.lower0, 1.0, den.lower1, den.lower0));
        }
        return out;
    }

    /**
     * Butterworth band-pass: 0 dB at the centre, -3 dB at the two edges
     * hz / q apart, 6 dB per octave on each side per order. One biquad per
     * order (a part of an order takes one more: between whole orders the band
     * is that of {@link #highCut} at that slope, moved to the centre).
     */
    public static AnalogCascade bandPass(double hz, double q, double order)
    {
        return bandFilter(hz, q, order, false);
    }

    /**
     * Butterworth band-stop: a null at the centre, -3 dB at the two edges
     * hz / q apart, and a floor that widens with the order. One biquad per
     * order (a part of an order takes one more).
     */
    public static AnalogCascade bandStop(double hz, double q, double order)
    {
        return bandFilter(hz, q, order, true);
    }

    /**
     * All-pass of any order: the magnitude stays at 1 and the phase turns 180
     * degrees per order around the frequency. The poles are those of the cut
     * of the same order (q acts on the sharpest pair, from order 2 up). The
     * order is any from 1, whole or not: the phase at the frequency moves 90
     * degrees per order all the way.
     */
    public static AnalogCascade allPass(double hz, double q, double order)
    {
        final AnalogCascade out = new AnalogCascade();
        final double nu = wholeOrPart(order);
        final double w = omegaOf(hz), w2 = w * w;
        if (nu != Math.floor(nu))
        {
            // Between whole orders: whole pairs at the angles of the order and
            // one section with what is left, which turns the phase at the
            // frequency by 90 degrees per order of its share.
            final int pairs = (int) Math.floor(0.5 * nu);
            final double rest = nu - 2.0 * pairs;                       // in (0, 2), not 1
            final double sharpest = BUTTERWORTH_Q / DspMath.clamp(q, 0.1, 100.0);
            for (int i = 0; i < pairs; ++i)
            {
                final double a1 = 2.0 * Math.sin(PI * (2 * i + 1) / (2.0 * nu)) * (i == 0 ? sharpest : 1.0) * w;
                out.add(AnalogSection.make(1.0, -a1, w2, 1.0, a1, w2));
            }
            final double turn = Math.tan(0.25 * PI * rest);
            if (rest < 1.0)
            {
                out.add(AnalogSection.make(0.0, -turn, w, 0.0, turn, w));
            }
            else
            {
                final double part = rest - 1.0;
                final double narrow = 2.0 * Math.sin(PI * (2 * pairs + 1) / (2.0 * (2 * pairs + 2)));
                double damping = Math.pow(narrow, part);
                if (pairs == 0) damping *= Math.pow(sharpest, part);
                final double second = DspMath.clamp(1.0 - damping / turn, 0.0, 1.0);
                out.add(AnalogSection.make(second, -damping * w, w2, second, damping * w, w2));
            }
            return out;
        }
        final int n = (int) nu;
        for (int i = 0; i < n / 2; ++i)
        {
            double damping = 2.0 * Math.sin(PI * (2 * i + 1) / (2.0 * n));
            if (i == 0) damping *= BUTTERWORTH_Q / DspMath.clamp(q, 0.1, 100.0);
            final double a1 = damping * w;
            out.add(AnalogSection.make(1.0, -a1, w2, 1.0, a1, w2));
        }
        if (n % 2 == 1) out.add(AnalogSection.make(0.0, -1.0, w, 0.0, 1.0, w));
        return out;
    }

    /**
     * Tilt: the same slope across the whole band, 0 dB at the pivot. Eight
     * real zeros and poles two octaves apart, from 2.5 Hz to 160 kHz, in four
     * sections. The slope goes from -6 to +6 dB per octave (positive tilts
     * towards the treble); at the two ends it is one zero and one pole.
     *
     * @param pivotHz          frequency that keeps its level
     * @param slopeDbPerOctave slope, -6 to +6 (0 gives an empty cascade)
     */
    public static AnalogCascade tilt(double pivotHz, double slopeDbPerOctave)
    {
        final AnalogCascade out = new AnalogCascade();
        if (!Double.isFinite(slopeDbPerOctave)) return out;
        final double order = DspMath.clamp(slopeDbPerOctave / 6.020599913279624, -1.0, 1.0);
        if (Math.abs(order) < 1e-9) return out;
        final double toZero = Math.pow(LADDER_STEP, 0.5 * (1.0 - order));
        final double toPole = Math.pow(LADDER_STEP, 0.5 * (1.0 + order));
        double cell = 2.0 * PI * 632.4555320336759 / (LADDER_STEP * LADDER_STEP * LADDER_STEP * LADDER_STEP);
        for (int i = 0; i < 4; ++i)
        {
            final double z1 = cell * toZero, p1 = cell * toPole, z2 = z1 * LADDER_STEP, p2 = p1 * LADDER_STEP;
            out.add(AnalogSection.make(1.0 / (z1 * z2), 1.0 / z1 + 1.0 / z2, 1.0,
                                       1.0 / (p1 * p2), 1.0 / p1 + 1.0 / p2, 1.0));
            cell *= LADDER_STEP * LADDER_STEP;
        }
        final double level = out.magnitudeSquared(omegaOf(pivotHz));
        if (level > 0.0 && Double.isFinite(level))
        {
            final double gain = 1.0 / Math.sqrt(level);
            final AnalogSection first = out.sections[0];
            first.b2 *= gain;
            first.b1 *= gain;
            first.b0 *= gain;
        }
        return out;
    }

    /**
     * The slope a tilt can have at a pivot without lifting the end that rises
     * (20 kHz for a positive slope, 20 Hz for a negative one) by more than a
     * ceiling: the slope asked for, or the one that lands that end on the
     * ceiling. The end that falls is not limited.
     *
     * @param pivotHz          frequency that keeps its level
     * @param slopeDbPerOctave slope asked for, in dB per octave
     * @param ceilingDb        most the rising end may be lifted, in dB
     */
    public static double tiltSlopeWithin(double pivotHz, double slopeDbPerOctave, double ceilingDb)
    {
        if (!Double.isFinite(slopeDbPerOctave) || !Double.isFinite(pivotHz) || !(ceilingDb > 0.0)) return slopeDbPerOctave;
        final double pivot = DspMath.clamp(pivotHz, 20.0, 20000.0);
        final double octaves = slopeDbPerOctave > 0.0 ? log2(20000.0 / pivot) : log2(pivot / 20.0);
        final double rise = Math.abs(slopeDbPerOctave) * octaves;
        return rise > ceilingDb ? slopeDbPerOctave * (ceilingDb / rise) : slopeDbPerOctave;
    }

    /** {@link #ellipticHighCut(double, int, double, double)} of order 12, 0.05 dB and 100 dB. */
    public static AnalogCascade ellipticHighCut(double hz)
    {
        return ellipticHighCut(hz, 12, 0.05, 100.0);
    }

    /** {@link #ellipticHighCut(double, int, double, double)} with 0.05 dB and 100 dB. */
    public static AnalogCascade ellipticHighCut(double hz, int order)
    {
        return ellipticHighCut(hz, order, 0.05, 100.0);
    }

    /** {@link #ellipticHighCut(double, int, double, double)} with 100 dB. */
    public static AnalogCascade ellipticHighCut(double hz, int order, double rippleDb)
    {
        return ellipticHighCut(hz, order, rippleDb, 100.0);
    }

    /**
     * Elliptic high cut: flat to the corner within the ripple, then a wall
     * down to the stop level. With the defaults (order 12, 0.05 dB, 100 dB)
     * the stop band starts a fifth of an octave above the corner.
     *
     * @param hz       end of the pass band
     * @param order    1 to 16
     * @param rippleDb ripple of the pass band, 0.001 to 3 dB
     * @param stopDb   attenuation of the stop band, 20 to 140 dB
     */
    public static AnalogCascade ellipticHighCut(double hz, int order, double rippleDb, double stopDb)
    {
        final AnalogCascade out = ellipticPrototype(order, rippleDb, stopDb);
        final double omega = omegaOf(hz);
        for (int i = 0; i < out.count; ++i) out.sections[i] = scaled(out.sections[i], omega);
        return out;
    }

    /** {@link #ellipticLowCut(double, int, double, double)} of order 12, 0.05 dB and 100 dB. */
    public static AnalogCascade ellipticLowCut(double hz)
    {
        return ellipticLowCut(hz, 12, 0.05, 100.0);
    }

    /** {@link #ellipticLowCut(double, int, double, double)} with 0.05 dB and 100 dB. */
    public static AnalogCascade ellipticLowCut(double hz, int order)
    {
        return ellipticLowCut(hz, order, 0.05, 100.0);
    }

    /** {@link #ellipticLowCut(double, int, double, double)} with 100 dB. */
    public static AnalogCascade ellipticLowCut(double hz, int order, double rippleDb)
    {
        return ellipticLowCut(hz, order, rippleDb, 100.0);
    }

    /** Elliptic low cut: {@link #ellipticHighCut} mirrored about the corner. */
    public static AnalogCascade ellipticLowCut(double hz, int order, double rippleDb, double stopDb)
    {
        final AnalogCascade out = ellipticPrototype(order, rippleDb, stopDb);
        final double omega = omegaOf(hz);
        for (int i = 0; i < out.count; ++i) out.sections[i] = mirrored(out.sections[i], omega);
        return out;
    }

    /** {@link #ellipticStopRatio(int, double, double)} of order 12, 0.05 dB and 100 dB. */
    public static double ellipticStopRatio()
    {
        return ellipticStopRatio(12, 0.05, 100.0);
    }

    /** {@link #ellipticStopRatio(int, double, double)} with 0.05 dB and 100 dB. */
    public static double ellipticStopRatio(int order)
    {
        return ellipticStopRatio(order, 0.05, 100.0);
    }

    /** {@link #ellipticStopRatio(int, double, double)} with 100 dB. */
    public static double ellipticStopRatio(int order, double rippleDb)
    {
        return ellipticStopRatio(order, rippleDb, 100.0);
    }

    /**
     * Where the stop band of an elliptic cut starts, as a ratio to its corner
     * (above 1; the same for the high cut and, inverted, for the low cut).
     */
    public static double ellipticStopRatio(int order, double rippleDb, double stopDb)
    {
        final double[] rippleStop = new double[2];
        return 1.0 / ellipticModulus(order, rippleDb, stopDb, rippleStop);
    }

    /* ====================================================================
     *  The fit
     * ==================================================================== */

    /** What the numerator of a prototype is made of. */
    private static final class Shape
    {
        int kind = 0;               // 0 free; 1 one zero at DC; 2 two zeros at DC; 3 zeros on the circle
        boolean first = false;      // first-order denominator
        boolean allPass = false;    // numerator mirrors the denominator: nothing to fit
        double cosNotch = 0.0;      // cos of the notch frequency (kind 3)
        double yNotch = 0.0;        // 1 - cos of the notch frequency (kind 3)

        /** Terms of the numerator the fit moves; its value at DC is not one of them (the gain at DC is held). */
        int numeratorTerms()
        {
            if (first) return 1;
            return kind == 3 ? 0 : (kind == 2 ? 1 : 2);
        }

        int denominatorTerms()
        {
            return first ? 1 : 2;
        }
    }

    /** The squared magnitude of a denominator as d0 + d1 y + d2 y^2, and its coefficients. */
    private static final class Denominator
    {
        double d0 = 1.0, d1 = 0.0, d2 = 0.0;
        double a1 = 0.0, a2 = 0.0;
    }

    /** Where the fit looks: t of each frequency and the analog power there. */
    private static final class Samples
    {
        int n = 0;
        final double[] t = new double[MAX_POINTS];
        final double[] m = new double[MAX_POINTS];
        final double[] weight = new double[MAX_POINTS];
        double tMax = 2.0;          // t at the Nyquist frequency
        double tz = 0.0;            // t of the notch (kind 3)
        double dc = 0.0;            // analog power at DC, which the numerator keeps (kinds 0 and 3)
        double floor = 0.0;         // power under which a frequency counts for little
    }

    /**
     * How much the error at an angular frequency counts: all of it up to
     * 20 kHz, less and less above.
     */
    private static double weightOf(double omega)
    {
        final double over = stdMax(0.0, omega * (0.5 / PI) - 20000.0) * (1.0 / ULTRASONIC_HZ);
        return 1.0 / (1.0 + over * over);
    }

    /** y = 1 - cos w: the squared magnitude of a biquad polynomial is a quadratic in y. */
    private static double yOf(double w)
    {
        final double s = Math.sin(0.5 * w);
        return 2.0 * s * s;
    }

    /** sin x for x between 0 and pi / 2, to eleven digits. */
    private static double sine(double x)
    {
        final double k3 = 1.0 / 6.0, k5 = 1.0 / 20.0, k7 = 1.0 / 42.0, k9 = 1.0 / 72.0;
        final double k11 = 1.0 / 110.0, k13 = 1.0 / 156.0, k15 = 1.0 / 210.0;
        final double x2 = x * x;
        return x * (1.0 - x2 * k3 * (1.0 - x2 * k5 * (1.0 - x2 * k7 * (1.0 - x2 * k9
               * (1.0 - x2 * k11 * (1.0 - x2 * k13 * (1.0 - x2 * k15)))))));
    }

    private static boolean finite(AnalogSection h)
    {
        return Double.isFinite(h.b2) && Double.isFinite(h.b1) && Double.isFinite(h.b0)
            && Double.isFinite(h.a2) && Double.isFinite(h.a1) && Double.isFinite(h.a0);
    }

    private static boolean usable(AnalogSection h)
    {
        if (h.b2 == 0.0 && h.b1 == 0.0 && h.b0 == 0.0) return false;
        if (h.isSecondOrder()) return h.a1 / h.a2 > 0.0 && h.a0 / h.a2 > 0.0;
        return h.b2 == 0.0 && h.a1 != 0.0 && h.a0 / h.a1 > 0.0;
    }

    private static Shape shapeOf(AnalogSection h, double fs)
    {
        final Shape s = new Shape();
        s.first = !h.isSecondOrder();
        if (s.first)
        {
            if (h.b0 == 0.0) s.kind = 1;
            s.allPass = h.b1 != 0.0 && h.b1 == -h.a1 && h.b0 == h.a0;
            return s;
        }
        if (h.b0 == 0.0) s.kind = (h.b1 == 0.0) ? 2 : 1;
        else if (h.b1 == 0.0 && h.b2 != 0.0 && h.b0 / h.b2 > 0.0)
        {
            final double w = Math.sqrt(h.b0 / h.b2) / fs;
            if (w < 0.98 * PI)
            {
                s.kind = 3;
                s.yNotch = yOf(w);
                s.cosNotch = 1.0 - s.yNotch;
            }
        }
        s.allPass = h.b1 != 0.0 && h.b2 == h.a2 && h.b0 == h.a0 && h.b1 == -h.a1;
        return s;
    }

    /**
     * The denominator with the analog poles carried over by z = e^(s / fs). A
     * pole pair whose frequency nears or passes Nyquist would alias; its angle
     * is bent smoothly so that it stays under 0.99 pi.
     */
    private static Denominator invariantDenominator(AnalogSection h, double fs)
    {
        final Denominator d = new Denominator();
        if (!h.isSecondOrder())
        {
            final double x = -(h.a0 / h.a1) / fs;
            final double p = Math.exp(x), gap = -Math.expm1(x);
            d.d0 = gap * gap;
            d.d1 = 2.0 * p;
            d.d2 = 0.0;
            d.a1 = -p;
            d.a2 = 0.0;
            return d;
        }
        final double sigma = -0.5 * h.a1 / h.a2, det = sigma * sigma - h.a0 / h.a2;
        double atNyquist;
        if (det < 0.0)
        {
            final double r = Math.exp(sigma / fs), gap = -Math.expm1(sigma / fs);
            final double kFree = 0.95 * PI, kRoom = 0.04 * PI;
            double theta = Math.sqrt(-det) / fs;
            if (theta > kFree) theta = kFree + kRoom * (1.0 - Math.exp(-(theta - kFree) / kRoom));
            final double s = Math.sin(0.5 * theta), bend = 4.0 * r * s * s;
            final double atDc = gap * gap + bend, top = (1.0 + r) * (1.0 + r) - bend;
            d.d0 = atDc * atDc;
            atNyquist = top * top;
            d.d2 = 4.0 * r * r;
            d.a1 = -2.0 * r * Math.cos(theta);
            d.a2 = r * r;
        }
        else
        {
            final double root = Math.sqrt(det);
            final double x1 = (sigma + root) / fs, x2 = (sigma - root) / fs;
            final double p1 = Math.exp(x1), p2 = Math.exp(x2);
            final double gap = Math.expm1(x1) * Math.expm1(x2), top = (1.0 + p1) * (1.0 + p2);
            d.d0 = gap * gap;
            atNyquist = top * top;
            d.d2 = 4.0 * p1 * p2;
            d.a1 = -(p1 + p2);
            d.a2 = p1 * p2;
        }
        d.d1 = 0.5 * (atNyquist - d.d0) - 2.0 * d.d2;
        return d;
    }

    /** Analog power at a squared angular frequency. */
    private static double power(AnalogSection h, double omega2)
    {
        final double nr = h.b0 - h.b2 * omega2, dr = h.a0 - h.a2 * omega2;
        return (nr * nr + h.b1 * h.b1 * omega2) / (dr * dr + h.a1 * h.a1 * omega2);
    }

    /**
     * Seven frequencies around a resonance, at its centre and at half, once
     * and twice its half-power width to each side (kept inside the band), so
     * that the fit sees a peak however narrow it is.
     */
    private static void cluster(AnalogSection h, Band band, double centre, double q, double y0, Samples x)
    {
        final double half = 0.5 / stdMax(q, 0.5);
        final double r = Math.sqrt(1.0 + half * half) + half, s = Math.sqrt(r);
        final double[] factors = { 1.0 / (r * r), 1.0 / r, 1.0 / s, 1.0, s, r, r * r };
        final double low = 2.0 * PI * band.lowHz, high = 2.0 * PI * band.highHz;
        final double halfPeriod = 0.5 / band.fs, scale = 1.0 / y0;
        for (final double ratio : factors)
        {
            final double omega = DspMath.clamp(centre * ratio, low, high);
            final double sn = sine(omega * halfPeriod);
            x.t[x.n] = 2.0 * sn * sn * scale;
            x.m[x.n] = power(h, omega * omega);
            x.weight[x.n] = weightOf(omega);
            ++x.n;
        }
    }

    private static void gather(AnalogSection h, Shape shape, Band band, double y0, Samples x)
    {
        final double scale = 1.0 / y0;
        x.n = Band.POINTS;
        for (int i = 0; i < Band.POINTS; ++i)
        {
            x.t[i] = band.y[i] * scale;
            x.m[i] = power(h, band.omega2[i]);
            x.weight[i] = band.weight[i];
        }
        if (shape.first)
        {
            cluster(h, band, h.a0 / h.a1, 0.5, y0, x);
            if (h.b1 != 0.0 && h.b0 != 0.0) cluster(h, band, Math.abs(h.b0 / h.b1), 0.5, y0, x);
        }
        else
        {
            cluster(h, band, Math.sqrt(h.a0 / h.a2), Math.sqrt(h.a0 * h.a2) / Math.abs(h.a1), y0, x);
            if (shape.kind == 0)
            {
                if (h.b2 != 0.0 && h.b0 / h.b2 > 0.0 && h.b1 != 0.0)
                    cluster(h, band, Math.sqrt(h.b0 / h.b2), Math.sqrt(h.b0 * h.b2) / Math.abs(h.b1), y0, x);
                else if (h.b2 == 0.0 && h.b1 != 0.0)
                    cluster(h, band, Math.abs(h.b0 / h.b1), 0.5, y0, x);
            }
        }
        double largest = 0.0;
        for (int i = 0; i < x.n; ++i) largest = stdMax(largest, x.m[i]);
        x.floor = FLOOR * largest;
        x.tMax = 2.0 * scale;
        x.tz = shape.yNotch * scale;
        x.dc = (shape.kind == 0 || shape.kind == 3) ? (h.b0 / h.a0) * (h.b0 / h.a0) : 0.0;
    }

    /** The terms of the numerator the fit moves, at t. */
    private static void numeratorAt(Shape s, double t, double[] out)
    {
        if (s.first) out[0] = t;
        else if (s.kind == 2) out[0] = t * t;
        else if (s.kind != 3)
        {
            out[0] = t;
            out[1] = t * t;
        }
    }

    /** The part of the numerator the fit does not move, at t: what holds the gain at DC. */
    private static double heldAt(Shape s, Samples x, double t)
    {
        if (s.kind == 0) return x.dc;
        if (s.kind == 3) return x.dc * (t - x.tz) * (t - x.tz) / (x.tz * x.tz);
        return 0.0;
    }

    /** Solves n equations in place (column n is the right side). */
    private static boolean solve(int n, double[][] m)
    {
        for (int c = 0; c < n; ++c)
        {
            int pivot = c;
            for (int r = c + 1; r < n; ++r)
                if (Math.abs(m[r][c]) > Math.abs(m[pivot][c])) pivot = r;
            if (!(Math.abs(m[pivot][c]) > 1e-300)) return false;
            if (pivot != c)
                for (int k = 0; k <= n; ++k)
                {
                    final double swap = m[c][k];
                    m[c][k] = m[pivot][k];
                    m[pivot][k] = swap;
                }
            for (int r = 0; r < n; ++r)
            {
                if (r == c) continue;
                final double f = m[r][c] / m[c][c];
                for (int k = c; k <= n; ++k) m[r][k] -= f * m[c][k];
            }
        }
        for (int r = 0; r < n; ++r) m[r][n] /= m[r][r];
        return true;
    }

    /**
     * One pass over the frequencies with the section of terms p: how far it
     * is from the analog power and, when asked, the equations of the
     * least-squares step from it (the numerator alone, or numerator and
     * denominator together).
     * <p>
     * With N and D the squared magnitudes of numerator and denominator (both
     * polynomials in t), the equation N - M D = 0 at each frequency is linear
     * in their terms. Each is weighted by 1 / (M D) with the D of the pass, so
     * that the squares add up the relative error of the power. The distance
     * is the sum over the frequencies of (r - 1)^2 / r, with r the ratio of
     * the two powers: the same going over as going under.
     *
     * @param result receives the distance (index 0) and its largest term (index 1)
     */
    private static void pass(Shape s, Samples x, double[] p, boolean together, double[][] normal, double[] result)
    {
        final int nN = s.numeratorTerms(), nD = s.denominatorTerms();
        final int n = together ? nN + nD : nN;
        if (normal != null)
            for (int a = 0; a < 5; ++a)
                for (int b = 0; b < 6; ++b) normal[a][b] = 0.0;
        double cost = 0.0;
        double worst = 0.0;
        final double[] row = new double[5];
        for (int i = 0; i < x.n; ++i)
        {
            final double t = x.t[i], m = x.m[i], t2 = t * t;
            java.util.Arrays.fill(row, 0.0);
            numeratorAt(s, t, row);
            double value = heldAt(s, x, t);
            for (int j = 0; j < nN; ++j) value += p[j] * row[j];
            final double was = 1.0 + p[nN] * t + (nD > 1 ? p[nN + 1] * t2 : 0.0);
            final double want = (m + x.floor) * was, have = value + x.floor * was;
            final double u = x.weight[i] / want, off = (have - want) * u;
            final double apart = off * off * want / have;
            cost += apart;
            worst = stdMax(worst, apart);
            if (normal == null) continue;

            for (int j = 0; j < nN; ++j) row[j] *= u;
            if (together)
            {
                row[nN] = -u * m * t;
                if (nD > 1) row[nN + 1] = -u * m * t2;
            }
            for (int a = 0; a < n; ++a)
            {
                final double ra = row[a];
                for (int b = a; b < n; ++b) normal[a][b] += ra * row[b];
                normal[a][n] -= ra * off;
            }
        }
        result[0] = cost;
        result[1] = worst;
    }

    /**
     * Solves the equations of a pass and moves p by the step. Every unknown
     * is put on the same scale first, and a touch of damping keeps a
     * direction the frequencies say nothing about where it was.
     */
    private static boolean advance(Shape s, boolean together, double damping, double[][] normal, double[] p)
    {
        final int n = together ? s.numeratorTerms() + s.denominatorTerms() : s.numeratorTerms();
        if (n == 0) return true;
        final double[] scale = new double[5];
        for (int a = 0; a < n; ++a)
        {
            if (!(normal[a][a] > 0.0)) return false;
            scale[a] = 1.0 / Math.sqrt(normal[a][a]);
        }
        final double[][] system = new double[5][6];
        for (int a = 0; a < n; ++a)
        {
            for (int b = a; b < n; ++b)
            {
                final double v = normal[a][b] * scale[a] * scale[b];
                system[b][a] = v;
                system[a][b] = v;
            }
            system[a][a] = 1.0 + damping;
            system[a][n] = normal[a][n] * scale[a];
        }
        if (!solve(n, system)) return false;
        for (int a = 0; a < n; ++a)
        {
            final double move = system[a][n] * scale[a];
            if (!Double.isFinite(move)) return false;
            p[a] += move;
        }
        return true;
    }

    /** Whether 1 + d1 t + d2 t^2 stays clear of zero from DC to Nyquist (the poles stay inside the circle). */
    private static boolean positive(double d1, double d2, double tMax)
    {
        final double kMargin = 1e-9;
        final double end = 1.0 + tMax * (d1 + tMax * d2);
        if (!(end > kMargin * (1.0 + tMax * (Math.abs(d1) + tMax * Math.abs(d2))))) return false;
        if (d2 > 0.0)
        {
            final double tv = -0.5 * d1 / d2;
            if (tv > 0.0 && tv < tMax)
            {
                final double low = 1.0 + 0.5 * d1 * tv;
                if (!(low > kMargin * (1.0 + tv * (Math.abs(d1) + tv * Math.abs(d2))))) return false;
            }
        }
        return true;
    }

    /**
     * Keeps the numerator a squared magnitude: where the fit would cross zero
     * between DC and Nyquist, its zeros stop at Nyquist or on the circle. The
     * value at DC is not touched.
     */
    private static void clampNumerator(Shape s, Samples x, double[] p)
    {
        if (s.kind == 0 && s.first)
        {
            if (x.dc + p[0] * x.tMax < 0.0) p[0] = -x.dc / x.tMax;
        }
        else if (s.kind == 0)
        {
            if (x.dc + x.tMax * (p[0] + x.tMax * p[1]) < 0.0) p[1] = -(x.dc + p[0] * x.tMax) / (x.tMax * x.tMax);
            if (p[1] > 0.0 && p[0] < 0.0 && -0.5 * p[0] / p[1] < x.tMax && p[0] * p[0] > 4.0 * x.dc * p[1])
                p[0] = -2.0 * Math.sqrt(x.dc * p[1]);
        }
        else if (s.kind == 1 && !s.first)
        {
            p[0] = stdMax(p[0], 0.0);
            if (p[0] + p[1] * x.tMax < 0.0) p[1] = -p[0] / x.tMax;
        }
        else if (s.kind != 3)
        {
            p[0] = stdMax(p[0], 0.0);
        }
    }

    /**
     * The minimum-phase c0 + c1 z^-1 + c2 z^-2 whose squared magnitude is
     * k0 + k1 y + k2 y^2, into {@code c}; false when there is none.
     */
    private static boolean factor(double k0, double k1, double k2, double[] c)
    {
        // A polynomial that touches zero at DC or at Nyquist may come out a
        // rounding error under it.
        final double slack = -1e-9 * (Math.abs(k0) + 2.0 * Math.abs(k1) + 4.0 * Math.abs(k2));
        double atDc = k0, atNyquist = k0 + 2.0 * k1 + 4.0 * k2;
        if (!(atDc >= slack) || !(atNyquist >= slack)) return false;
        atDc = stdMax(atDc, 0.0);
        atNyquist = stdMax(atNyquist, 0.0);
        final double p = Math.sqrt(atDc), m = Math.sqrt(atNyquist);
        final double sum = 0.5 * (p + m);
        c[1] = 0.5 * (p - m);
        final double disc = sum * sum - k2;
        if (disc < -1e-9 * (sum * sum + Math.abs(k2))) return false;
        c[0] = 0.5 * (sum + Math.sqrt(stdMax(0.0, disc)));
        if (!(c[0] > 0.0)) return false;
        c[2] = 0.25 * k2 / c[0];
        return true;
    }

    /** From the fitted terms to the coefficients of the section; null when they do not make one. */
    private static BiquadCoeffs assemble(Shape s, Samples x, double[] p, double y0)
    {
        final int nN = s.numeratorTerms();
        final double scale = 1.0 / y0, scale2 = scale * scale;
        final double[] c = { 1.0, 0.0, 0.0 };
        if (!factor(1.0, p[nN] * scale, s.first ? 0.0 : p[nN + 1] * scale2, c)) return null;

        double e0 = 0.0, e1 = 0.0, e2 = 0.0;
        if (s.kind == 0)
        {
            final double[] e = new double[3];
            if (!factor(x.dc, p[0] * scale, s.first ? 0.0 : p[1] * scale2, e)) return null;
            e0 = e[0];
            e1 = e[1];
            e2 = e[2];
        }
        else if (s.kind == 1 && !s.first)
        {
            // y (n1 + n2 y), and y is half the squared magnitude of 1 - z^-1.
            final double[] f = new double[3];
            if (!factor(0.5 * p[0] * scale, 0.5 * p[1] * scale2, 0.0, f)) return null;
            e0 = f[0];
            e1 = f[1] - f[0];
            e2 = -f[1];
        }
        else if (s.kind == 1)
        {
            final double g = Math.sqrt(0.5 * stdMax(p[0], 0.0) * scale);
            e0 = g;
            e1 = -g;
        }
        else if (s.kind == 2)
        {
            // Two zeros at DC: y^2 is a quarter of the squared magnitude of (1 - z^-1)^2.
            final double g = 0.5 * Math.sqrt(stdMax(p[0], 0.0)) * scale;
            e0 = g;
            e1 = -2.0 * g;
            e2 = g;
        }
        else
        {
            // Zeros on the circle: (y - yz)^2 is a quarter of the squared magnitude
            // of 1 - 2 cos(wz) z^-1 + z^-2, and the gain is the one of DC.
            final double g = 0.5 * Math.sqrt(x.dc) / s.yNotch;
            e0 = g;
            e1 = -2.0 * g * s.cosNotch;
            e2 = g;
        }

        final double inverse = 1.0 / c[0];
        final double b0 = e0 * inverse, b1 = e1 * inverse, b2 = e2 * inverse;
        final double a1 = c[1] * inverse, a2 = c[2] * inverse;
        if (!(Double.isFinite(b0) && Double.isFinite(b1) && Double.isFinite(b2)
              && Double.isFinite(a1) && Double.isFinite(a2))) return null;
        return new BiquadCoeffs(b0, b1, b2, a1, a2);
    }

    /* ====================================================================
     *  Cascades: the prototypes
     * ==================================================================== */

    private static double omegaOf(double hz)
    {
        return 2.0 * PI * (Double.isFinite(hz) ? stdMax(hz, 1e-3) : 1000.0);
    }

    /** A section written for a corner of 1 rad/s, moved to omega. */
    private static AnalogSection scaled(AnalogSection n, double omega)
    {
        if (!n.isSecondOrder())
            return AnalogSection.make(0.0, n.b1, n.b0 * omega, 0.0, n.a1, n.a0 * omega);
        return AnalogSection.make(n.b2, n.b1 * omega, n.b0 * omega * omega, n.a2, n.a1 * omega, n.a0 * omega * omega);
    }

    /** A section written for a corner of 1 rad/s, mirrored (s to omega / s) and moved to omega. */
    private static AnalogSection mirrored(AnalogSection n, double omega)
    {
        if (!n.isSecondOrder())
            return AnalogSection.make(0.0, n.b0, n.b1 * omega, 0.0, n.a0, n.a1 * omega);
        return AnalogSection.make(n.b0, n.b1 * omega, n.b2 * omega * omega, n.a0, n.a1 * omega, n.a2 * omega * omega);
    }

    /** The fraction of a pole, (1 + s)^(-order) for an order under 1: three sections of real poles and zeros. */
    private static void fractionalPole(double order, AnalogCascade out)
    {
        final double gap = Math.pow(LADDER_STEP, order);                    // from each pole to its zero
        final double leaving = 1.0 - order * order * order * order;         // the last zero leaves as the order nears 1
        double pole = Math.pow(LADDER_STEP, 0.5 * (1.0 - order));
        for (int i = 0; i < 3; ++i)
        {
            final double p1 = pole, p2 = pole * LADDER_STEP;
            final double iz1 = 1.0 / (p1 * gap), iz2 = (i == 2 ? leaving : 1.0) / (p2 * gap);
            out.add(AnalogSection.make(iz1 * iz2, iz1 + iz2, 1.0, 1.0 / (p1 * p2), 1.0 / p1 + 1.0 / p2, 1.0));
            pole = p2 * LADDER_STEP;
        }
    }

    /** The low-pass of a real order with its corner (-3 dB) at 1 rad/s. See {@link #highCut}. */
    private static AnalogCascade cutPrototype(double slopeDbPerOctave, double q)
    {
        final AnalogCascade out = new AnalogCascade();
        if (!Double.isFinite(slopeDbPerOctave)) return out;
        final double order = DspMath.clamp(slopeDbPerOctave, 0.0, MAX_CUT_SLOPE) / 6.0;
        if (order < 1e-6) return out;
        if (order < 1.0)
        {
            fractionalPole(order, out);
            return out;
        }

        // Pole pairs on the unit semicircle at the angles of a real order.
        // The whole part of the order says what completes them: an odd one,
        // the last pair (two real poles when the order is exactly odd) with a
        // zero; an even one, a real pole with a zero.
        final int whole = (int) Math.floor(order);
        final int pairs = ((int) Math.floor(order + 1.0)) / 2;
        final boolean onPair = (whole % 2) == 1;
        final int plain = onPair ? pairs - 1 : pairs;          // pairs that stay as the angles put them
        final double[] damping = new double[AnalogCascade.MAX_SECTIONS];
        for (int k = 0; k < pairs; ++k)
            damping[k] = 2.0 * Math.sin(PI * (2 * k + 1) / (2.0 * order));

        // The section that completes them keeps the cascade on the curve
        // 1 / (1 + w^(2 order)) between whole orders: at the corner (-3 dB)
        // and where that curve has fallen 36 dB and, when it is a pair, 18 dB
        // too. Written as powers the conditions are linear in what the
        // section has free, so there is nothing to search. At a whole order
        // the zero cancels a pole or leaves: the Butterworth filter.
        final double deep = Math.pow(DEEP - 1.0, 1.0 / order);             // w^2 at the 36 dB point
        double atCorner = 1.0, atDeep = 1.0;                               // attenuation of the plain pairs
        for (int k = 0; k < plain; ++k)
        {
            atCorner *= damping[k] * damping[k];
            atDeep *= (1.0 - deep) * (1.0 - deep) + damping[k] * damping[k] * deep;
        }
        final double lift = Math.pow(DspMath.clamp(q, 0.1, 100.0) / BUTTERWORTH_Q, DspMath.clamp(order - 1.0, 0.0, 1.0));
        for (int k = 0; k < plain; ++k)
            out.add(AnalogSection.make(0.0, 0.0, 1.0, 1.0, damping[k] / (k == 0 ? lift : 1.0), 1.0));

        if (onPair)
        {
            // The pair 1 + alpha v + beta v^2 over 1 + z v, through three points.
            final double mid = Math.pow(MIDDLE - 1.0, 1.0 / order);
            double atMid = 1.0;
            for (int k = 0; k < plain; ++k)
                atMid *= (1.0 - mid) * (1.0 - mid) + damping[k] * damping[k] * mid;
            final double[] points = { 1.0, mid, deep };
            final double[] asked = { 2.0 / atCorner, MIDDLE / atMid, DEEP / atDeep };
            final double[][] system = new double[5][6];
            for (int i = 0; i < 3; ++i)
            {
                system[i][0] = points[i];
                system[i][1] = points[i] * points[i];
                system[i][2] = -asked[i] * points[i];
                system[i][3] = asked[i] - 1.0;
            }
            double alpha = 0.0, beta = 0.0, zero2 = -1.0;
            if (solve(3, system))
            {
                alpha = system[0][3];
                beta = system[1][3];
                zero2 = system[2][3];
            }
            final double natural2 = beta > 0.0 ? 1.0 / Math.sqrt(beta) : 0.0;     // the frequency of the pair, squared
            final double damping2 = alpha * natural2 + 2.0;
            if (zero2 > -1e-9 && zero2 <= 1.0 + 1e-9 && natural2 > 0.0 && Double.isFinite(natural2) && damping2 > 1e-6)
            {
                final double a1 = Math.sqrt(damping2 * natural2) / (plain == 0 ? lift : 1.0);
                out.add(AnalogSection.make(0.0, Math.sqrt(stdMax(zero2, 0.0)) * natural2, natural2, 1.0, a1, natural2));
            }
            else
            {
                // Two points with the pair where its angle puts it.
                final double c = 2.0 / atCorner;
                final double slope = deep * (atDeep * c - DEEP);
                double z = 0.0;
                if (Math.abs(slope) > 1e-12 * deep * DEEP)
                    z = DspMath.clamp((DEEP - atDeep * ((1.0 - deep) * (1.0 - deep) + c * deep)) / slope, 0.0, 1.0);
                out.add(AnalogSection.make(0.0, Math.sqrt(z), 1.0, 1.0,
                                           Math.sqrt(c * (1.0 + z)) / (plain == 0 ? lift : 1.0), 1.0));
            }
        }
        else
        {
            // The real pole 1 + u v over 1 + z v, through two points.
            final double c = 2.0 / atCorner;
            final double slope = deep * (atDeep * c - DEEP);
            double z = 0.0;
            if (Math.abs(slope) > 1e-12 * deep * DEEP)
                z = DspMath.clamp((DEEP - atDeep * (1.0 + (c - 1.0) * deep)) / slope, 0.0, 1.0);
            final double u = c * (1.0 + z) - 1.0;
            if (u > 1e-12) out.add(AnalogSection.make(0.0, Math.sqrt(z), 1.0, 0.0, Math.sqrt(u), 1.0));
        }
        return out;
    }

    /**
     * Butterworth low shelf with its corner at 1 rad/s: gainDb at DC, half of
     * it (in decibels) at the corner, 0 dB far above. Of any order from 1,
     * whole or not.
     */
    private static AnalogCascade shelfPrototype(double gainDb, double order, double q)
    {
        final AnalogCascade out = new AnalogCascade();
        final double nu = wholeOrPart(order);
        final double db = Double.isFinite(gainDb) ? DspMath.clamp(gainDb, -120.0, 120.0) : 0.0;
        // The Q acts on the sharpest pair, in its zeros as in its poles: the
        // gain of the pair at the corner does not depend on its damping.
        final double sharpest = BUTTERWORTH_Q / (Double.isFinite(q) ? DspMath.clamp(q, 0.05, 100.0) : BUTTERWORTH_Q);
        final double g = Math.pow(10.0, db / (20.0 * nu)), beta = Math.pow(10.0, -db / (40.0 * nu));
        if (nu == Math.floor(nu))
        {
            final int n = (int) nu;
            for (int i = 0; i < n / 2; ++i)
            {
                final double s = Math.sin(PI * (2 * i + 1) / (2.0 * n)) * (i == 0 ? sharpest : 1.0);
                out.add(AnalogSection.make(1.0, 2.0 * g * s * beta, g * g * beta * beta, 1.0, 2.0 * s * beta, beta * beta));
            }
            if (n % 2 == 1) out.add(AnalogSection.make(0.0, 1.0, g * beta, 0.0, 1.0, beta));
            return out;
        }
        // Between whole orders. Every section has its share of the gain and
        // its own corner factor (gain times the factor squared is 1), so the
        // plateaus and the half gain at the corner hold whatever the shares
        // and the dampings: whole pairs at the angles of the order, each with
        // two orders' worth of the gain, and one section with what is left.
        final int pairs = (int) Math.floor(0.5 * nu);
        final double rest = nu - 2.0 * pairs;                       // in (0, 2), not 1
        for (int i = 0; i < pairs; ++i)
        {
            final double s = Math.sin(PI * (2 * i + 1) / (2.0 * nu)) * (i == 0 ? sharpest : 1.0);
            out.add(AnalogSection.make(1.0, 2.0 * g * s * beta, g * g * beta * beta, 1.0, 2.0 * s * beta, beta * beta));
        }
        if (rest < 1.0)
        {
            // Under one order: a first-order shelf with that share, which leaves as the share goes to nothing.
            final double share = Math.pow(g, rest), factor = 1.0 / Math.sqrt(share);
            out.add(AnalogSection.make(0.0, 1.0, share * factor, 0.0, 1.0, factor));
        }
        else
        {
            // Over one: a pair. With one order's share it is the first-order
            // section times a root that its zero cancels (a damping of
            // g^(1/4) + g^(-1/4)); with two, the pair of the next whole
            // order. In between the damping goes from one to the other in
            // its logarithm.
            final double part = rest - 1.0;
            final double share = Math.pow(g, 0.5 * rest), factor = 1.0 / Math.sqrt(share);
            final double wide = Math.pow(g, 0.25) + Math.pow(g, -0.25);
            final double narrow = 2.0 * Math.sin(PI * (2 * pairs + 1) / (2.0 * (2 * pairs + 2)));
            double damping = Math.exp((1.0 - part) * Math.log(wide) + part * Math.log(narrow));
            if (pairs == 0) damping *= Math.pow(sharpest, part);     // the Q comes in between orders 1 and 2
            out.add(AnalogSection.make(1.0, damping * share * factor, share * share * factor * factor, 1.0,
                                       damping * factor, factor * factor));
        }
        return out;
    }

    /** An order from 1 to {@link #MAX_ORDER}, whole or not (1 for a value that is not a number). */
    private static double wholeOrPart(double order)
    {
        return Double.isFinite(order) ? DspMath.clamp(order, 1.0, MAX_ORDER) : 1.0;
    }

    /** The two quadratics a pair of low-pass roots turns into around a centre: s^2 + c1 s + c0 each. */
    private static final class BandPair
    {
        double upper1 = 0.0, upper0 = 0.0;
        double lower1 = 0.0, lower0 = 0.0;
    }

    /**
     * Low-pass to band-pass for the roots of S^2 + c1 S + c0: with
     * S = (s^2 + w0^2) / (width s) each root becomes two, one above the centre
     * and one below.
     */
    private static BandPair bandPair(double c1, double c0, double width, double w0)
    {
        final BandPair pair = new BandPair();
        if (c1 * c1 > 4.0 * c0)
        {
            // Two real roots: each is a quadratic of its own around the centre.
            final double spread = Math.sqrt(c1 * c1 - 4.0 * c0);
            pair.upper1 = 0.5 * (c1 + spread) * width;
            pair.upper0 = w0 * w0;
            pair.lower1 = 0.5 * (c1 - spread) * width;
            pair.lower0 = w0 * w0;
            return pair;
        }
        final Complex root = new Complex(-0.5 * c1, 0.5 * Math.sqrt(stdMax(0.0, 4.0 * c0 - c1 * c1)));
        final Complex sum = root.times(width);
        final Complex spread = Complex.sqrt(sum.times(sum).minus(4.0 * w0 * w0));
        Complex above = sum.plus(spread).times(0.5), below = sum.minus(spread).times(0.5);
        if (above.norm() < below.norm())
        {
            final Complex swap = above;
            above = below;
            below = swap;
        }
        pair.upper1 = -2.0 * above.re;
        pair.upper0 = above.norm();
        pair.lower1 = -2.0 * below.re;
        pair.lower0 = below.norm();
        return pair;
    }

    /** What a polynomial p2 S^2 + p1 S + p0 of a low-pass turns into around a centre: its quadratics and its leading term. */
    private static final class BandFactors
    {
        final double[] c1 = new double[2], c0 = new double[2];
        int count = 0;
        double lead = 1.0;
    }

    private static BandFactors bandFactors(double p2, double p1, double p0, double width, double w0)
    {
        final BandFactors f = new BandFactors();
        if (p2 != 0.0)
        {
            final BandPair pair = bandPair(p1 / p2, p0 / p2, width, w0);
            f.c1[0] = pair.upper1;
            f.c0[0] = pair.upper0;
            f.c1[1] = pair.lower1;
            f.c0[1] = pair.lower0;
            f.count = 2;
            f.lead = p2;
        }
        else if (p1 != 0.0)
        {
            f.c1[0] = p0 / p1 * width;
            f.c0[0] = w0 * w0;
            f.count = 1;
            f.lead = p1;
        }
        else
        {
            f.lead = p0;
        }
        return f;
    }

    /**
     * Adds the band-pass form of a low-pass section with its corner at
     * 1 rad/s: S = (s^2 + w0^2) / (width s). Each quadratic of the denominator
     * takes one of the numerator while there are, then width s.
     */
    private static void addBandForm(AnalogSection s, double width, double w0, AnalogCascade out)
    {
        final BandFactors num = bandFactors(s.b2, s.b1, s.b0, width, w0), den = bandFactors(s.a2, s.a1, s.a0, width, w0);
        double gain = den.lead != 0.0 ? num.lead / den.lead : 1.0;
        for (int i = 0; i < den.count; ++i)
        {
            if (i < num.count) out.add(AnalogSection.make(gain, gain * num.c1[i], gain * num.c0[i], 1.0, den.c1[i], den.c0[i]));
            else out.add(AnalogSection.make(0.0, gain * width, 0.0, 1.0, den.c1[i], den.c0[i]));
            gain = 1.0;
        }
    }

    private static AnalogCascade bandFilter(double hz, double q, double order, boolean stop)
    {
        final AnalogCascade out = new AnalogCascade();
        final double nu = wholeOrPart(order);
        final double w0 = omegaOf(hz), w02 = w0 * w0, width = w0 / DspMath.clamp(q, 1e-3, 1e3);
        if (nu != Math.floor(nu))
        {
            // Between whole orders: the cut of that slope around the centre
            // (for the band-stop, turned over first: S to 1 / S).
            final AnalogCascade proto = cutPrototype(6.0 * nu, BUTTERWORTH_Q);
            for (int i = 0; i < proto.count; ++i)
            {
                final AnalogSection p = proto.sections[i];
                addBandForm(stop ? AnalogSection.make(p.b0, p.b1, p.b2, p.a0, p.a1, p.a2) : p, width, w0, out);
            }
            return out;
        }
        final int n = (int) nu;
        final double b2 = stop ? 1.0 : 0.0, b1 = stop ? 0.0 : width, b0 = stop ? w02 : 0.0;
        for (int i = 0; i < n / 2; ++i)
        {
            final BandPair pair = bandPair(2.0 * Math.sin(PI * (2 * i + 1) / (2.0 * n)), 1.0, width, w0);
            out.add(AnalogSection.make(b2, b1, b0, 1.0, pair.upper1, pair.upper0));
            out.add(AnalogSection.make(b2, b1, b0, 1.0, pair.lower1, pair.lower0));
        }
        if (n % 2 == 1) out.add(AnalogSection.make(b2, b1, b0, 1.0, width, w02));
        return out;
    }

    /* --- Elliptic functions through the Landen transformation (S. J. Orfanidis,
     *     "Lecture Notes on Elliptic Filter Design", 2006) --- */

    /** The moduli a Landen sequence goes through on its way to zero. */
    private static final class Landen
    {
        final double[] v = new double[14];
        int count = 0;
    }

    private static Landen landen(double k, double complement)
    {
        final Landen steps = new Landen();
        double kc = complement;
        while (steps.count < 14 && k > 1e-18)
        {
            k /= 1.0 + kc;
            k *= k;
            steps.v[steps.count++] = k;
            kc = Math.sqrt((1.0 - k) * (1.0 + k));
        }
        return steps;
    }

    /** The complete elliptic integral of the modulus a sequence starts from. */
    private static double quarterPeriod(Landen steps)
    {
        double period = 0.5 * PI;
        for (int i = 0; i < steps.count; ++i) period *= 1.0 + steps.v[i];
        return period;
    }

    /** From the circular functions to cd and sn by the descending sequence. */
    private static Complex ascend(Complex w, Landen steps)
    {
        for (int i = steps.count - 1; i >= 0; --i)
            w = w.times(1.0 + steps.v[i]).dividedBy(w.times(steps.v[i]).times(w).plus(1.0));
        return w;
    }

    /** cd(u K, k), with u in units of the quarter period. */
    private static Complex ellipticCd(Complex u, Landen steps)
    {
        return ascend(Complex.cos(u.times(0.5 * PI)), steps);
    }

    /** sn(u K, k), with u in units of the quarter period. */
    private static Complex ellipticSn(Complex u, Landen steps)
    {
        return ascend(Complex.sin(u.times(0.5 * PI)), steps);
    }

    /** The u with sn(u K, k) = w. */
    private static Complex ellipticArcSn(Complex w, double k, Landen steps)
    {
        double before = k;
        for (int i = 0; i < steps.count; ++i)
        {
            final Complex root = Complex.sqrt(Complex.minus(1.0, w.times(w).times(before * before)));
            w = w.dividedBy(root.plus(1.0)).times(2.0 / (1.0 + steps.v[i]));
            before = steps.v[i];
        }
        return Complex.asin(w).times(2.0 / PI);
    }

    /**
     * The modulus of an elliptic filter (pass band edge over stop band edge)
     * from its order, ripple and stop level: the degree equation solved
     * through the nome. Also returns the two ripple factors in
     * {@code rippleStop}.
     */
    private static double ellipticModulus(int order, double rippleDb, double stopDb, double[] rippleStop)
    {
        final int n = Math.max(1, Math.min(order, MAX_ORDER));
        final double ap = Double.isFinite(rippleDb) ? DspMath.clamp(rippleDb, 1e-3, 3.0) : 0.05;
        final double as = Double.isFinite(stopDb) ? DspMath.clamp(stopDb, 20.0, 140.0) : 100.0;
        final double ripple = Math.sqrt(Math.expm1(ap * 0.23025850929940458));       // sqrt(10^(ap / 10) - 1)
        final double stop = Math.sqrt(Math.expm1(as * 0.23025850929940458));
        rippleStop[0] = ripple;
        rippleStop[1] = stop;
        final double k1 = ripple / stop, k1c = Math.sqrt((1.0 - k1) * (1.0 + k1));
        final double ratio = quarterPeriod(landen(k1c, k1)) / quarterPeriod(landen(k1, k1c));
        final double nome = Math.exp(-PI * ratio / n);
        double top = 0.0, bottom = 1.0;
        for (int m = 0; m < 12; ++m)
        {
            top += Math.pow(nome, m * (m + 1.0));
            if (m > 0) bottom += 2.0 * Math.pow(nome, 1.0 * m * m);
        }
        final double theta = top / bottom;
        return stdMin(4.0 * Math.sqrt(nome) * theta * theta, 1.0 - 1e-12);
    }

    /** Elliptic low-pass with the end of its pass band at 1 rad/s. */
    private static AnalogCascade ellipticPrototype(int order, double rippleDb, double stopDb)
    {
        final AnalogCascade out = new AnalogCascade();
        final int n = Math.max(1, Math.min(order, MAX_ORDER));
        final double[] rippleStop = new double[2];
        final double k = ellipticModulus(n, rippleDb, stopDb, rippleStop);
        final double ripple = rippleStop[0], stop = rippleStop[1];
        final double k1 = ripple / stop;
        final Landen steps = landen(k, Math.sqrt((1.0 - k) * (1.0 + k)));
        final Landen steps1 = landen(k1, Math.sqrt((1.0 - k1) * (1.0 + k1)));
        final Complex j = new Complex(0.0, 1.0);
        final Complex minusJ = new Complex(-0.0, -1.0);
        final double v0 = minusJ.times(ellipticArcSn(j.dividedBy(ripple), k1, steps1)).re / n;

        for (int i = 0; i < n / 2; ++i)
        {
            final double u = (2.0 * i + 1.0) / n;
            final double zero = 1.0 / (k * ellipticCd(new Complex(u, 0.0), steps).re);
            final Complex pole = j.times(ellipticCd(new Complex(u, -v0), steps));
            final double size = pole.norm();
            out.add(AnalogSection.make(1.0 / (zero * zero), 0.0, 1.0, 1.0 / size, -2.0 * pole.re / size, 1.0));
        }
        if (n % 2 == 1)
        {
            final double pole = j.times(ellipticSn(j.times(v0), steps)).re;
            out.add(AnalogSection.make(0.0, 0.0, 1.0, 0.0, -1.0 / pole, 1.0));
        }
        else if (out.count > 0)
        {
            // An even order ripples down from 0 dB: DC sits at the bottom of the ripple.
            final double gain = 1.0 / Math.sqrt(1.0 + ripple * ripple);
            final AnalogSection first = out.sections[0];
            first.b2 *= gain;
            first.b0 *= gain;
        }
        return out;
    }

    /** A complex number for the band and elliptic designs. */
    private static final class Complex
    {
        final double re, im;

        Complex(double re, double im)
        {
            this.re = re;
            this.im = im;
        }

        Complex plus(Complex o)
        {
            return new Complex(re + o.re, im + o.im);
        }

        Complex plus(double x)
        {
            return new Complex(re + x, im);
        }

        Complex minus(Complex o)
        {
            return new Complex(re - o.re, im - o.im);
        }

        Complex minus(double x)
        {
            return new Complex(re - x, im);
        }

        /** x - z for a real x. */
        static Complex minus(double x, Complex z)
        {
            return new Complex(x - z.re, 0.0 - z.im);
        }

        Complex times(Complex o)
        {
            return new Complex(re * o.re - im * o.im, re * o.im + im * o.re);
        }

        Complex times(double x)
        {
            return new Complex(re * x, im * x);
        }

        Complex dividedBy(double x)
        {
            return new Complex(re / x, im / x);
        }

        /** Division by Smith's method. */
        Complex dividedBy(Complex o)
        {
            final double a = re, b = im, c = o.re, d = o.im;
            if (Math.abs(c) < Math.abs(d))
            {
                final double ratio = c / d, denom = c * ratio + d;
                return new Complex((a * ratio + b) / denom, (b * ratio - a) / denom);
            }
            final double ratio = d / c, denom = d * ratio + c;
            return new Complex((b * ratio + a) / denom, (b - a * ratio) / denom);
        }

        /** The squared magnitude. */
        double norm()
        {
            return re * re + im * im;
        }

        double abs()
        {
            return Math.hypot(re, im);
        }

        /** The principal square root. */
        static Complex sqrt(Complex z)
        {
            final double x = z.re, y = z.im;
            if (x == 0.0)
            {
                final double t = Math.sqrt(Math.abs(y) / 2.0);
                return new Complex(t, y < 0.0 ? -t : t);
            }
            final double t = Math.sqrt(2.0 * (z.abs() + Math.abs(x)));
            final double u = t / 2.0;
            return x > 0.0 ? new Complex(u, y / t) : new Complex(Math.abs(y) / t, y < 0.0 ? -u : u);
        }

        static Complex cos(Complex z)
        {
            return new Complex(Math.cos(z.re) * Math.cosh(z.im), -Math.sin(z.re) * Math.sinh(z.im));
        }

        static Complex sin(Complex z)
        {
            return new Complex(Math.sin(z.re) * Math.cosh(z.im), Math.cos(z.re) * Math.sinh(z.im));
        }

        static Complex log(Complex z)
        {
            return new Complex(Math.log(z.abs()), Math.atan2(z.im, z.re));
        }

        /** asinh(z) = log(z + sqrt(z^2 + 1)). */
        static Complex asinh(Complex z)
        {
            Complex t = new Complex((z.re - z.im) * (z.re + z.im) + 1.0, 2.0 * z.re * z.im);
            t = sqrt(t);
            return log(t.plus(z));
        }

        /** asin(z) = -i asinh(i z). */
        static Complex asin(Complex z)
        {
            final Complex t = asinh(new Complex(-z.im, z.re));
            return new Complex(t.im, -t.re);
        }
    }

    /* --- Helpers --- */

    private static double log2(double x)
    {
        return Math.log(x) / Math.log(2.0);
    }

    /** max(a, b) as the comparison a &lt; b decides it (b only when a &lt; b). */
    private static double stdMax(double a, double b)
    {
        return (a < b) ? b : a;
    }

    /** min(a, b) as the comparison b &lt; a decides it (b only when b &lt; a). */
    private static double stdMin(double a, double b)
    {
        return (b < a) ? b : a;
    }
}
