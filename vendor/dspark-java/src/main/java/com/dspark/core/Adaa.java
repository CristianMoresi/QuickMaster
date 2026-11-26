package com.dspark.core;

/**
 * Second-order antiderivative antialiasing (ADAA) for memoryless curves.
 * <p>
 * Bilbao, Esqueda, Parker and Valimaki ("Antiderivative antialiasing for
 * memoryless nonlinearities", IEEE Signal Processing Letters 24(7), 2017)
 * replace y[n] = f(x[n]) by twice the second divided difference of the
 * second antiderivative F2:
 * <pre>
 *   y[n] = 2 F2[x[n], x[n-1], x[n-2]]
 *        = 2 / (x[n] - x[n-2]) * (D(x[n], x[n-1]) - D(x[n-1], x[n-2])),
 *   D(a, b) = (F2(a) - F2(b)) / (a - b),
 * </pre>
 * which is the average of f over the linear B-spline with knots at the three
 * inputs. The output is delayed by exactly one sample and attenuates a
 * linear signal by (1 + 2 cos w) / 3 (-1.3 dB at a quarter of the rate),
 * which is why it belongs inside an oversampled stage.
 * <p>
 * Numerics. The divided differences cancel: with F2 of order u^2/2 at a
 * large input u, the direct formula loses about log10(u^2 / dx^2) digits. The
 * curves here therefore evaluate each saturated region exactly (its F2 is a
 * quadratic plus, for tanh, an exponentially small remainder, and for the
 * rational knee a w ln w term with a stable closed form), and the generic
 * path falls back to Taylor forms when inputs nearly coincide:
 * D(a, b) = F1(m) + f'(m) (a - b)^2 / 24 for |a - b| &lt; 1e-3, the confluent
 * form 2 F2[xb, xb, x1] for |x[n] - x[n-2]| &lt; 1e-4, and f(mean) when all
 * three lie within 1e-4. Everything runs in double.
 * <p>
 * Curves (unit ceiling, odd unless stated): hard clip, tanh, the clamped
 * fastSin() polynomial of the Clipper's Analog mode, the golden-ratio
 * rational knee, and sin (not saturating, for the wavefolder). Each curve is
 * a stateless singleton ({@code INSTANCE}) passed to {@link Adaa2#process}.
 * <p>
 * Threading: an {@link Adaa2} is per-stream state owned by one thread; the
 * curves are pure functions. No allocation after construction.
 */
public final class Adaa
{
    private Adaa()
    {
        // Namespace class, not instantiable.
    }

    /** One input sample with the per-point values a curve needs. */
    public static final class AdaaPoint
    {
        /** Input. */
        public double u;
        /** Second antiderivative F2(u). */
        public double g;
        /** Curve specific (tanh remainder, knee logarithm). */
        public double aux;

        /** A point at the origin with zero values. */
        public AdaaPoint()
        {
        }

        /** A point with the given values. */
        public AdaaPoint(double u, double g, double aux)
        {
            this.u = u;
            this.g = g;
            this.aux = aux;
        }

        /** Sets the three values and returns this point. */
        public AdaaPoint set(double u, double g, double aux)
        {
            this.u = u;
            this.g = g;
            this.aux = aux;
            return this;
        }

        /** Copies the values of another point. */
        public void copyFrom(AdaaPoint other)
        {
            u = other.u;
            g = other.g;
            aux = other.aux;
        }
    }

    /**
     * A function family G with known derivatives d1 = G', d2 = G'', d3 = G'''
     * (for a curve f: G = F2, d1 = F1, d2 = f, d3 = f').
     */
    public interface Family
    {
        /** G(u). */
        double g(double u);

        /** G'(u). */
        double d1(double u);

        /** G''(u). */
        double d2(double u);

        /** G'''(u). */
        double d3(double u);
    }

    /** A curve f = d2 with its per-point values and its exact regions. */
    public interface Curve extends Family
    {
        /**
         * Fills {@code out} with the per-point values of input u.
         *
         * @param u    the input
         * @param out  the point to fill
         */
        void point(double u, AdaaPoint out);

        /**
         * The exact output for an input triple where the curve has one.
         *
         * @param p0  current point
         * @param p1  point one sample ago
         * @param p2  point two samples ago
         * @param y   receives the output in {@code y[0]} when true is returned
         * @return true when the triple was resolved exactly
         */
        boolean exact(AdaaPoint p0, AdaaPoint p1, AdaaPoint p2, double[] y);

        /**
         * True when {@link #value} resolves every input triple, so
         * {@link Adaa2} keeps only the inputs for this curve.
         */
        default boolean isClosedForm()
        {
            return false;
        }

        /**
         * The output for the inputs (u0, u1, u2) of a closed-form curve.
         *
         * @param u0  current input
         * @param u1  input one sample ago
         * @param u2  input two samples ago
         * @return the antialiased output
         */
        default double value(double u0, double u1, double u2)
        {
            throw new UnsupportedOperationException("not a closed-form curve");
        }
    }

    /** Generic second-difference machinery for a function family. */
    public static final class AdaaSecondDifference
    {
        /** Below this |a - b| the divided difference takes its Taylor form. */
        public static final double TOL_DIV = 1e-3;
        /** Below this spread the confluent and coincident forms apply. */
        public static final double TOL_DD2 = 1e-4;

        private AdaaSecondDifference()
        {
        }

        /**
         * The divided difference (G(a) - G(b)) / (a - b).
         *
         * @param family  the function family
         * @param a       first argument
         * @param b       second argument
         * @param ga      G(a)
         * @param gb      G(b)
         * @return the divided difference
         */
        public static double div(Family family, double a, double b, double ga, double gb)
        {
            final double d = a - b;
            if (Math.abs(d) > TOL_DIV) return (ga - gb) / d;
            final double m = 0.5 * (a + b);
            return family.d1(m) + family.d3(m) * (d * d * (1.0 / 24.0));
        }

        /**
         * Twice the second divided difference, 2 G[x0, x1, x2].
         *
         * @param family  the function family
         * @param x0      first knot
         * @param x1      second knot
         * @param x2      third knot
         * @param g0      G(x0)
         * @param g1      G(x1)
         * @param g2      G(x2)
         * @return 2 G[x0, x1, x2]
         */
        public static double twice(Family family, double x0, double x1, double x2,
                                   double g0, double g1, double g2)
        {
            final double d02 = x0 - x2;
            if (Math.abs(d02) > TOL_DD2)
                return 2.0 * (div(family, x0, x1, g0, g1) - div(family, x1, x2, g1, g2)) / d02;
            // Confluent form: x0 and x2 merge into their mean.
            final double xb = 0.5 * (x0 + x2), e = xb - x1;
            if (Math.abs(e) > TOL_DD2)
                return 2.0 * (family.d1(xb) - div(family, xb, x1, family.g(xb), g1)) / e;
            return family.d2((x0 + x1 + x2) * (1.0 / 3.0));
        }
    }

    // -----------------------------------------------------------------------
    // Hard clip: f = clamp(u, -1, 1)
    // -----------------------------------------------------------------------

    /** Hard clip f = clamp(u, -1, 1), exact for every input triple. */
    public static final class AdaaHardClip implements Curve
    {
        /** The shared stateless instance. */
        public static final AdaaHardClip INSTANCE = new AdaaHardClip();

        private AdaaHardClip()
        {
        }

        @Override
        public double g(double u)
        {
            final double a = Math.abs(u);
            return a <= 1.0 ? u * u * u * (1.0 / 6.0)
                            : Math.copySign(0.5 * a * a - 0.5 * a + 1.0 / 6.0, u);
        }

        @Override
        public double d1(double u)
        {
            final double a = Math.abs(u);
            return a <= 1.0 ? 0.5 * u * u : a - 0.5;
        }

        @Override
        public double d2(double u)
        {
            return DspMath.clamp(u, -1.0, 1.0);
        }

        @Override
        public double d3(double u)
        {
            return Math.abs(u) < 1.0 ? 1.0 : 0.0;
        }

        @Override
        public void point(double u, AdaaPoint out)
        {
            out.set(u, g(u), 0.0);
        }

        /** Closed form for every input triple (see {@link #exact}). */
        @Override
        public boolean isClosedForm()
        {
            return true;
        }

        @Override
        public double value(double u0, double u1, double u2)
        {
            return exactValue(u0, u1, u2);
        }

        /**
         * Exact in every case: clamp(t) = t - (t - 1)+ + (-1 - t)+, and the
         * B-spline average of a ramp (t - tau)+ has a closed form with no
         * subtraction of nearly equal terms, so a kink crossed in tiny steps
         * costs no accuracy. Three points on one linear piece return before
         * any ramp is evaluated.
         */
        @Override
        public boolean exact(AdaaPoint p0, AdaaPoint p1, AdaaPoint p2, double[] y)
        {
            y[0] = exactValue(p0.u, p1.u, p2.u);
            return true;
        }

        private static double exactValue(double u0, double u1, double u2)
        {
            double lo = u0, mid = u1, hi = u2;
            double t;
            if (lo > mid) { t = lo; lo = mid; mid = t; }
            if (mid > hi) { t = mid; mid = hi; hi = t; }
            if (lo > mid) { t = lo; lo = mid; mid = t; }
            if (lo >= 1.0) return 1.0;
            if (hi <= -1.0) return -1.0;
            final double mean = (lo + mid + hi) * (1.0 / 3.0);
            if (hi <= 1.0 && lo >= -1.0) return mean;
            return mean - rampAbove(lo, mid, hi, mean, 1.0) + rampBelow(lo, mid, hi, mean, -1.0);
        }

        /**
         * Average of (t - tau)+ over the linear B-spline with knots
         * a &lt;= b &lt;= c.
         */
        public static double rampAbove(double a, double b, double c, double mean, double tau)
        {
            if (tau >= c) return 0.0;
            if (tau <= a) return mean - tau;
            if (tau >= b)
            {
                final double r = c - tau;
                return r * r * r / (3.0 * (c - a) * (c - b));
            }
            final double l = tau - a;
            return (mean - tau) + l * l * l / (3.0 * (c - a) * (b - a));
        }

        /** Average of (tau - t)+ over the same B-spline (mirror of rampAbove). */
        public static double rampBelow(double a, double b, double c, double mean, double tau)
        {
            if (tau <= a) return 0.0;
            if (tau >= c) return tau - mean;
            if (tau <= b)
            {
                final double l = tau - a;
                return l * l * l / (3.0 * (c - a) * (b - a));
            }
            final double r = c - tau;
            return (tau - mean) + r * r * r / (3.0 * (c - a) * (c - b));
        }
    }

    // -----------------------------------------------------------------------
    // tanh: F1 = log cosh, F2 = integral of log cosh
    // -----------------------------------------------------------------------

    /** tanh, with F1 = log cosh and F2 = the integral of log cosh. */
    public static final class AdaaTanh implements Curve
    {
        /** The shared stateless instance. */
        public static final AdaaTanh INSTANCE = new AdaaTanh();

        /**
         * Beyond |u| = 2, F2 = sign (u^2/2 - |u| ln 2 + pi^2/24) + R with
         * |R| &lt; 0.01: the quadratic part has an exact second difference.
         */
        public static final double OUTER = 2.0;

        private AdaaTanh()
        {
        }

        @Override
        public double g(double u)
        {
            return LogCosh.integralLogCosh(u);
        }

        @Override
        public double d1(double u)
        {
            return LogCosh.logCosh(u);
        }

        @Override
        public double d2(double u)
        {
            return Math.tanh(u);
        }

        @Override
        public double d3(double u)
        {
            final double t = Math.tanh(u);
            return 1.0 - t * t;
        }

        /** Remainder family R(u) = F2 - quadratic, on one side |u| &gt;= OUTER. */
        public static final class Remainder implements Family
        {
            /** The shared stateless instance. */
            public static final Remainder INSTANCE = new Remainder();

            private Remainder()
            {
            }

            // The tail is negative, so the odd extension multiplies by the
            // sign (copySign would take its magnitude).
            @Override
            public double g(double u)
            {
                final double r = tail(Math.abs(u));
                return u < 0.0 ? -r : r;
            }

            @Override
            public double d1(double u)
            {
                return Math.log1p(Math.exp(-2.0 * Math.abs(u)));
            }

            @Override
            public double d2(double u)
            {
                final double z = Math.exp(-2.0 * Math.abs(u));
                final double v = 2.0 * z / (1.0 + z);            // tanh(u) - sign(u)
                return u < 0.0 ? v : -v;
            }

            @Override
            public double d3(double u)
            {
                final double t = Math.tanh(u);
                return 1.0 - t * t;
            }
        }

        /**
         * integralLogCoshTail for a &gt;= 1, with the series cut to the terms
         * that matter at the argument (z = e^{-2a} &lt;= e^{-4} needs nine).
         */
        public static double tail(double a)
        {
            if (a < 2.0) return LogCosh.integralLogCoshTail(a);
            if (a > 19.0) return 0.0;                 // |tail| < 1.6e-17
            final double z = Math.exp(-2.0 * a);
            double li = -1.0 / 81.0;
            for (int k = 8; k >= 1; --k)
                li = li * z + (k % 2 == 0 ? 1.0 : -1.0) / (k * k);
            return 0.5 * (li * z);
        }

        @Override
        public void point(double u, AdaaPoint out)
        {
            final double a = Math.abs(u);
            if (a < 1.0)
            {
                out.set(u, LogCosh.integralLogCosh(u), 0.0);
                return;
            }
            final double r = tail(a);
            out.set(u, Math.copySign(0.5 * a * a - a * 0.6931471805599453 + (r + 0.4112335167120566), u),
                    u < 0.0 ? -r : r);
        }

        @Override
        public boolean exact(AdaaPoint p0, AdaaPoint p1, AdaaPoint p2, double[] y)
        {
            final double lo = cppMin(p0.u, cppMin(p1.u, p2.u)), hi = cppMax(p0.u, cppMax(p1.u, p2.u));
            if (!(lo >= OUTER || hi <= -OUTER)) return false;
            final double s = lo >= OUTER ? 1.0 : -1.0;
            y[0] = s + AdaaSecondDifference.twice(Remainder.INSTANCE, p0.u, p1.u, p2.u, p0.aux, p1.aux, p2.aux);
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Clamped fastSin() polynomial: f = p(clamp(u, -pi/2, pi/2)), p(pi/2) = 1
    // -----------------------------------------------------------------------

    /** The fastSin() polynomial p(v) and its first two antiderivatives. */
    public static final class AdaaSinePolynomialTerms
    {
        /**
         * Coefficients of the reduced-range odd sine polynomial of fastSin():
         * p(v) = v (c0 + c1 v^2 + c2 v^4 + c3 v^6 + c4 v^8).
         */
        public static final double C0 = 0.99999997408724855;
        /** See {@link #C0}. */
        public static final double C1 = -0.16666646026660671;
        /** See {@link #C0}. */
        public static final double C2 = 0.0083328727116396326;
        /** See {@link #C0}. */
        public static final double C3 = -0.00019799239565814083;
        /** See {@link #C0}. */
        public static final double C4 = 2.5871610835732768e-6;

        private AdaaSinePolynomialTerms()
        {
        }

        /** The polynomial p(v). */
        public static double poly(double v)
        {
            final double v2 = v * v;
            return v * (C0 + v2 * (C1 + v2 * (C2 + v2 * (C3 + v2 * C4))));
        }

        /** Antiderivative of p, even. */
        public static double poly1(double v)
        {
            final double v2 = v * v;
            return v2 * (C0 / 2.0 + v2 * (C1 / 4.0 + v2 * (C2 / 6.0 + v2 * (C3 / 8.0 + v2 * C4 / 10.0))));
        }

        /** Second antiderivative of p, odd. */
        public static double poly2(double v)
        {
            final double v2 = v * v;
            return v * v2 * (C0 / 6.0 + v2 * (C1 / 20.0 + v2 * (C2 / 42.0 + v2 * (C3 / 72.0 + v2 * C4 / 110.0))));
        }

        /** Derivative of p. */
        public static double polySlope(double v)
        {
            final double v2 = v * v;
            return C0 + v2 * (3.0 * C1 + v2 * (5.0 * C2 + v2 * (7.0 * C3 + v2 * 9.0 * C4)));
        }
    }

    /** The clamped fastSin() polynomial f = p(clamp(u, -pi/2, pi/2)). */
    public static final class AdaaSinePolynomial implements Curve
    {
        /** The shared stateless instance. */
        public static final AdaaSinePolynomial INSTANCE = new AdaaSinePolynomial();

        /** The clamp edge pi/2. */
        public static final double HALF_PI = Math.PI * 0.5;
        /** p(pi/2). */
        public static final double EDGE = AdaaSinePolynomialTerms.poly(HALF_PI);
        /** First antiderivative of p at pi/2. */
        public static final double EDGE1 = AdaaSinePolynomialTerms.poly1(HALF_PI);
        /** Second antiderivative of p at pi/2. */
        public static final double EDGE2 = AdaaSinePolynomialTerms.poly2(HALF_PI);

        private AdaaSinePolynomial()
        {
        }

        @Override
        public double g(double u)
        {
            final double a = Math.abs(u);
            if (a <= HALF_PI) return AdaaSinePolynomialTerms.poly2(u);
            final double e = a - HALF_PI;
            return Math.copySign(EDGE2 + EDGE1 * e + 0.5 * EDGE * e * e, u);
        }

        @Override
        public double d1(double u)
        {
            final double a = Math.abs(u);
            return a <= HALF_PI ? AdaaSinePolynomialTerms.poly1(u) : EDGE1 + EDGE * (a - HALF_PI);
        }

        @Override
        public double d2(double u)
        {
            return AdaaSinePolynomialTerms.poly(DspMath.clamp(u, -HALF_PI, HALF_PI));
        }

        @Override
        public double d3(double u)
        {
            return Math.abs(u) < HALF_PI ? AdaaSinePolynomialTerms.polySlope(u) : 0.0;
        }

        @Override
        public void point(double u, AdaaPoint out)
        {
            out.set(u, g(u), 0.0);
        }

        @Override
        public boolean exact(AdaaPoint p0, AdaaPoint p1, AdaaPoint p2, double[] y)
        {
            final double lo = cppMin(p0.u, cppMin(p1.u, p2.u)), hi = cppMax(p0.u, cppMax(p1.u, p2.u));
            if (lo >= HALF_PI) { y[0] = EDGE; return true; }
            if (hi <= -HALF_PI) { y[0] = -EDGE; return true; }
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Golden-ratio rational knee: f = u for |u| <= k = 1/phi, else
    // sign (1 - r^2 / w) with r = 1 - k and w = |u| - k + r.
    // -----------------------------------------------------------------------

    /**
     * Golden-ratio rational knee: f = u for |u| &lt;= k = 1/phi, else
     * sign (1 - r^2 / w) with r = 1 - k and w = |u| - k + r.
     */
    public static final class AdaaRationalKnee implements Curve
    {
        /** The shared stateless instance. */
        public static final AdaaRationalKnee INSTANCE = new AdaaRationalKnee();

        /** 1 / phi. */
        public static final double KNEE = 0.61803398874989484820;
        /** 1 - 1 / phi. */
        public static final double RANGE = 1.0 - KNEE;
        /** ln(1 - 1/phi). */
        public static final double LOG_RANGE = -0.96242365011920689500;

        private AdaaRationalKnee()
        {
        }

        @Override
        public double g(double u)
        {
            final double a = Math.abs(u);
            if (a <= KNEE) return u * u * u * (1.0 / 6.0);
            final double e = a - KNEE, w = e + RANGE;
            return Math.copySign(KNEE * KNEE * KNEE / 6.0 + 0.5 * KNEE * KNEE * e + 0.5 * e * e
                                 - RANGE * RANGE * (w * (Math.log(w) - LOG_RANGE) - e), u);
        }

        @Override
        public double d1(double u)
        {
            final double a = Math.abs(u);
            if (a <= KNEE) return 0.5 * u * u;
            final double e = a - KNEE;
            return 0.5 * KNEE * KNEE + e - RANGE * RANGE * Math.log1p(e / RANGE);
        }

        @Override
        public double d2(double u)
        {
            final double a = Math.abs(u);
            return a <= KNEE ? u : Math.copySign(1.0 - RANGE * RANGE / (a - KNEE + RANGE), u);
        }

        @Override
        public double d3(double u)
        {
            final double a = Math.abs(u);
            if (a <= KNEE) return 1.0;
            final double w = a - KNEE + RANGE;
            return RANGE * RANGE / (w * w);
        }

        @Override
        public void point(double u, AdaaPoint out)
        {
            final double a = Math.abs(u);
            if (a <= KNEE)
            {
                out.set(u, u * u * u * (1.0 / 6.0), 0.0);
                return;
            }
            final double e = a - KNEE, w = e + RANGE, lw = Math.log(w);
            out.set(u, Math.copySign(KNEE * KNEE * KNEE / 6.0 + 0.5 * KNEE * KNEE * e + 0.5 * e * e
                                     - RANGE * RANGE * (w * (lw - LOG_RANGE) - e), u), lw);
        }

        /** ln(x / y) / (x - y) for x, y &gt; 0, with the logarithms given. */
        public static double logSlope(double x, double y, double lx, double ly)
        {
            final double q = (x - y) / (x + y);
            if (Math.abs(q) > 0.05) return (lx - ly) / (x - y);
            // ln(x/y) = 2 atanh(q): odd series in q, no cancellation.
            final double q2 = q * q;
            return 2.0 / (x + y)
                 * (1.0 + q2 * (1.0 / 3 + q2 * (1.0 / 5 + q2 * (1.0 / 7 + q2 * (1.0 / 9 + q2 * (1.0 / 11))))));
        }

        @Override
        public boolean exact(AdaaPoint p0, AdaaPoint p1, AdaaPoint p2, double[] y)
        {
            final double lo = cppMin(p0.u, cppMin(p1.u, p2.u)), hi = cppMax(p0.u, cppMax(p1.u, p2.u));
            if (lo >= -KNEE && hi <= KNEE)
            {
                y[0] = (p0.u + p1.u + p2.u) * (1.0 / 3.0);
                return true;
            }
            final boolean up = lo >= KNEE, down = hi <= -KNEE;
            if (!(up || down) || !(hi - lo > AdaaSecondDifference.TOL_DD2))
                return false;
            // Average of 1 - r^2/w over the B-spline with knots a <= b <= c in w:
            // 2/(c - a) * (c ln(c/b)/(c - b) - a ln(b/a)/(b - a)).
            // Knots in w = |u| - k + r, sorted with their logarithms.
            double w0 = Math.abs(p0.u) - KNEE + RANGE, w1 = Math.abs(p1.u) - KNEE + RANGE;
            double w2 = Math.abs(p2.u) - KNEE + RANGE, l0 = p0.aux, l1 = p1.aux, l2 = p2.aux;
            double t;
            if (w0 > w1) { t = w0; w0 = w1; w1 = t; t = l0; l0 = l1; l1 = t; }
            if (w1 > w2) { t = w1; w1 = w2; w2 = t; t = l1; l1 = l2; l2 = t; }
            if (w0 > w1) { t = w0; w0 = w1; w1 = t; t = l0; l0 = l1; l1 = t; }
            final double h = 2.0 / (w2 - w0) * (w2 * logSlope(w2, w1, l2, l1) - w0 * logSlope(w1, w0, l1, l0));
            y[0] = Math.copySign(1.0 - RANGE * RANGE * h, up ? 1.0 : -1.0);
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // sin (not saturating): F1 = -cos, F2 = -sin; bounded, so always generic.
    // -----------------------------------------------------------------------

    /** sin (not saturating): F1 = -cos, F2 = -sin; bounded, so always generic. */
    public static final class AdaaSine implements Curve
    {
        /** The shared stateless instance. */
        public static final AdaaSine INSTANCE = new AdaaSine();

        private AdaaSine()
        {
        }

        @Override
        public double g(double u)
        {
            return -Math.sin(u);
        }

        @Override
        public double d1(double u)
        {
            return -Math.cos(u);
        }

        @Override
        public double d2(double u)
        {
            return Math.sin(u);
        }

        @Override
        public double d3(double u)
        {
            return Math.cos(u);
        }

        @Override
        public void point(double u, AdaaPoint out)
        {
            out.set(u, -Math.sin(u), 0.0);
        }

        @Override
        public boolean exact(AdaaPoint p0, AdaaPoint p1, AdaaPoint p2, double[] y)
        {
            return false;
        }
    }

    /**
     * Per-stream second-order ADAA state.
     * <p>
     * {@code process(curve, u)} returns the antialiased curve output for the
     * input history (u, u[n-1], u[n-2]), centred on u[n-1]. The cached point
     * values belong to one curve: call {@link #invalidate()} when the caller
     * switches curves (they are rebuilt from the raw input history on the
     * next process()). {@link #track(double)} only records the input, so
     * antialiasing can start later from real history; {@link #reset(double)}
     * holds u as the whole history.
     */
    public static final class Adaa2
    {
        private AdaaPoint p0 = new AdaaPoint(), p1 = new AdaaPoint(), p2 = new AdaaPoint();
        private final double[] exactOut = new double[1];
        private double u1 = 0.0, u2 = 0.0;
        private boolean valid = false;

        /** Holds zero as the whole history. */
        public void reset()
        {
            reset(0.0);
        }

        /** Holds u as the whole history. */
        public void reset(double u)
        {
            u1 = u2 = u;
            valid = false;
        }

        /** Records an input without producing output. */
        public void track(double u)
        {
            u2 = u1;
            u1 = u;
            valid = false;
        }

        /** Drops the cached point values (call when switching curves). */
        public void invalidate()
        {
            valid = false;
        }

        /**
         * One antialiased sample of a curve.
         *
         * @param curve  the curve
         * @param u      the current input
         * @return the curve averaged over the last three inputs, centred one
         *         sample back
         */
        public double process(Curve curve, double u)
        {
            double y = 0.0;
            if (curve.isClosedForm())
            {
                y = curve.value(u, u1, u2);
            }
            else
            {
                if (!valid)
                {
                    curve.point(u1, p1);
                    curve.point(u2, p2);
                    valid = true;
                }
                final AdaaPoint current = p0;
                curve.point(u, current);
                if (curve.exact(current, p1, p2, exactOut))
                    y = exactOut[0];
                else
                    y = AdaaSecondDifference.twice(curve, current.u, p1.u, p2.u, current.g, p1.g, p2.g);
                p0 = p2;
                p2 = p1;
                p1 = current;
            }
            u2 = u1;
            u1 = u;
            return y;
        }

        /** Input one sample ago (the sample the output is centred on). */
        public double previousInput()
        {
            return u1;
        }

        /** Copies the whole state of another instance. */
        public void copyFrom(Adaa2 other)
        {
            p0.copyFrom(other.p0);
            p1.copyFrom(other.p1);
            p2.copyFrom(other.p2);
            u1 = other.u1;
            u2 = other.u2;
            valid = other.valid;
        }
    }

    /** The smaller of a and b, written b &lt; a ? b : a (a NaN b yields a). */
    static double cppMin(double a, double b)
    {
        return b < a ? b : a;
    }

    /** The larger of a and b, written a &lt; b ? b : a (a NaN b yields a). */
    static double cppMax(double a, double b)
    {
        return a < b ? b : a;
    }
}
