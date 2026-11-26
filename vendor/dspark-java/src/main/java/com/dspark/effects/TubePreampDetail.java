package com.dspark.effects;

import com.dspark.core.wdf.ToneStackFMV;

/**
 * Circuit helpers of {@link TubePreamp}: the grid models, the tabulated
 * implicit plate current, the fixed design of the continuous-time core, the
 * phi functions of the exact modal response and the modal form of the tone
 * circuit.
 */
final class TubePreampDetail
{
    private TubePreampDetail()
    {
    }

    /** The larger of two values, the first when they compare equal or either is NaN. */
    static double max(double a, double b)
    {
        return (a < b) ? b : a;
    }

    /** The smaller of two values, the first when they compare equal or either is NaN. */
    static double min(double a, double b)
    {
        return (b < a) ? b : a;
    }

    /** A value clamped into [lo, hi] (a NaN passes through). */
    static double clamp(double v, double lo, double hi)
    {
        return (v < lo) ? lo : ((hi < v) ? hi : v);
    }

    /** Soft clamp of the grid voltage toward +0.7 V. */
    static final class GridClamp
    {
        private GridClamp()
        {
        }

        /** Identity below 0 V, 0.7*tanh(v/0.7) above (soft clamp toward +0.7 V). */
        static double value(double v)
        {
            if (v <= 0.0) return v;
            if (v >= 13.0) return 0.7;   // 0.7*(1 - 2e^-37) rounds to 0.7
            final double e = Math.expm1(v * (-2.0 / 0.7));
            return -0.7 * e / (2.0 + e);
        }
    }

    /**
     * Grid conduction referenced to the cathode (TubePreamp.GridModel.CONDUCTION).
     * The grid current is the 12AX7 fit of Dempwolf and Zoelzer, "A
     * physically-motivated triode model for circuit simulations" (DAFx-11):
     * Ig(v) = Gg * (softplus(Cg * v) / Cg)^xi of the grid-to-cathode voltage
     * v, with Gg = 6.177e-4, xi = 1.314, Cg = 9.901. Its constant 8e-8 A
     * leakage term is left out: through the 1 M ohm grid leak it would only
     * shift the rest bias by 80 mV. The grid is driven through a fixed
     * 68 k ohm source behind the input coupling network; for a
     * source-minus-cathode voltage d the grid-to-cathode voltage w solves
     * w + Rs * Ig(w) = d. The left side increases with w, so the root is
     * unique and lies in [d - Rs * Ig(d), d].
     */
    static final class GridConduction
    {
        static final double GG = 6.177e-4, XI = 1.314, CG = 9.901;
        /** Source resistance (ohm). */
        static final double RS = 68e3;
        /** Grid coupling capacitor (F). */
        static final double CC = 22e-9;
        /** Grid leak resistor (ohm). */
        static final double RG = 1e6;

        private GridConduction()
        {
        }

        /** Grid current (A) at grid-to-cathode voltage v. */
        static double current(double v)
        {
            final double z = CG * v;
            double sp;
            if (z > 30.0) sp = z;
            else if (z < -30.0) sp = Math.exp(z);
            else sp = Math.log1p(Math.exp(z));
            final double base = sp / CG;
            final double pm1 = Math.pow(base, XI - 1.0);
            return GG * pm1 * base;
        }

        /** Derivative of the grid current at grid-to-cathode voltage v (A/V). */
        static double slope(double v)
        {
            final double z = CG * v;
            double sp, sig;
            if (z > 30.0) { sp = z; sig = 1.0; }
            else if (z < -30.0) { sp = Math.exp(z); sig = sp; }
            else
            {
                final double e = Math.exp(z);
                sp = Math.log1p(e);
                sig = e / (1.0 + e);
            }
            final double base = sp / CG;
            final double pm1 = Math.pow(base, XI - 1.0);
            return GG * XI * pm1 * sig;
        }

        /** Root of w + RS * Ig(w) = d (see {@link #solve(double, double)}). */
        static double solve(double d)
        {
            return solve(d, RS);
        }

        /** Root of w + rs * Ig(w) = d: Newton inside the bracket, bisection
         *  when a step leaves it. */
        static double solve(double d, double rs)
        {
            if (!Double.isFinite(d)) return 0.0;
            double lo = d - rs * current(d), hi = d;
            double w = d > 0.0 ? min(hi, max(lo, Math.pow(d / (rs * GG), 1.0 / XI))) : d;
            for (int it = 0; it < 100; ++it)
            {
                // Current and slope at w, as current() and slope() compute them.
                final double z = CG * w;
                double sp, sig;
                if (z > 30.0) { sp = z; sig = 1.0; }
                else if (z < -30.0) { sp = Math.exp(z); sig = sp; }
                else
                {
                    final double e = Math.exp(z);
                    sp = Math.log1p(e);
                    sig = e / (1.0 + e);
                }
                final double base = sp / CG;
                final double pm1 = Math.pow(base, XI - 1.0);
                final double slope = GG * XI * pm1 * sig;
                final double cur = GG * pm1 * base;

                final double f = w + rs * cur - d;
                if (f > 0.0) hi = w;
                else lo = w;
                final double next = w - f / (1.0 + rs * slope);
                final double step = next > lo && next < hi ? next : 0.5 * (lo + hi);
                if (Math.abs(step - w) <= 1e-15 * max(1.0, Math.abs(w)) || hi - lo <= 1e-15)
                {
                    w = step;
                    break;
                }
                w = step;
            }
            return w;
        }
    }

    /**
     * Cubic Hermite table of the GridConduction root w(d) for one source
     * resistance rs: steps of 1/64 V on [-1.5, 8) and 1/8 V on [8, 72] V,
     * value and exact slope dw/dd = 1 / (1 + rs * dIg/dw) per node (17.9 kB).
     * Largest error against solve() on a 1/4096 V sweep is 1.5e-8 V. Below
     * -1.5 V the source drop is under 1e-8 V and w = d; above 72 V solve()
     * runs.
     */
    static final class GridTable
    {
        static final double LO = -1.5, MID = 8.0, HI = 72.0;
        static final int FINE = 64, COARSE = 8;
        static final int FINE_CELLS = (int) ((MID - LO) * FINE);
        static final int CELLS = FINE_CELLS + (int) ((HI - MID) * COARSE);

        /** Per cell the cubic in its offset t in [0, 1]: ((a3 t + a2) t + a1) t + a0, as a3, a2, a1, a0. */
        final double[] cells = new double[CELLS * 4];
        /** Source resistance the table solves for. */
        final double rs;

        GridTable()
        {
            this(GridConduction.RS);
        }

        GridTable(double sourceResistance)
        {
            rs = sourceResistance;
            final double[] w = new double[CELLS + 1], dw = new double[CELLS + 1];
            for (int i = 0; i <= CELLS; ++i)
            {
                final double d = i <= FINE_CELLS ? LO + (double) i / FINE
                                                 : MID + (double) (i - FINE_CELLS) / COARSE;
                final double root = GridConduction.solve(d, rs);
                final double slope = GridConduction.slope(root);
                w[i] = root;
                dw[i] = 1.0 / (1.0 + rs * slope);
            }
            // Each cell's Hermite cubic through the node values and slopes,
            // in the monomial form eval() reads.
            for (int i = 0; i < CELLS; ++i)
            {
                final double h = i < FINE_CELLS ? 1.0 / FINE : 1.0 / COARSE;
                final double w0 = w[i], w1 = w[i + 1];
                final double m0 = h * dw[i], m1 = h * dw[i + 1];
                final double diff = w1 - w0;
                cells[4 * i] = m0 + m1 - 2.0 * diff;
                cells[4 * i + 1] = 3.0 * diff - 2.0 * m0 - m1;
                cells[4 * i + 2] = m0;
                cells[4 * i + 3] = w0;
            }
        }

        /** Grid-to-cathode voltage for a source-minus-cathode voltage d. */
        double eval(double d)
        {
            if (d <= LO) return d;
            if (!(d < HI)) return GridConduction.solve(d, rs);
            final double pos = d < MID ? (d - LO) * FINE : FINE_CELLS + (d - MID) * COARSE;
            final int i = Math.min((int) pos, CELLS - 1);
            final double t = pos - i;
            final int c = 4 * i;
            return ((cells[c] * t + cells[c + 1]) * t + cells[c + 2]) * t + cells[c + 3];
        }
    }

    /**
     * Koren 12AX7 plate current and its tabulated implicit load line.
     * <p>
     * With S = B+ - A and G = Vgrid - A, the trapezoidal cathode equation
     * Vk = A + B*Ip leaves a two-dimensional implicit load line:
     * Ip = Koren(S - (RL+B)*Ip, G - B*Ip). B is fixed by the rate given to
     * the constructor: the 1x point circuit passes its sample rate, the
     * continuous core passes infinity (B = 0) and freezes A per interval.
     * R = G/S aligns the cutoff knee across supply voltages. The
     * 156672-byte table has less than 5e-10 A error in the independent
     * load-line check. Bicubic Hermite interpolation keeps current and both
     * first derivatives continuous at cell edges. Every channel and both
     * calibration passes of an instance share it.
     */
    static final class CurrentTable
    {
        static final double MU = 100.0, EX = 1.4, KG1 = 1060.0;
        static final double KP = 600.0, KVB = 300.0;
        static final double RL = 100e3, RK = 1.5e3, CK = 22e-6;
        static final int NS = 18, NG = 272;

        /** Per node: current y, dI/dS, dI/dR and d2I/dSdR (4 values). */
        final double[] nodes = new double[NS * NG * 4];
        final double kb;

        /**
         * Koren plate current and its partial derivatives.
         *
         * @param vpk  plate-to-cathode voltage
         * @param vgk  grid-to-cathode voltage
         * @param d    receives dIp/dVpk in d[0] and dIp/dVgk in d[1]
         * @return the plate current
         */
        static double koren(double vpk, double vgk, double[] d)
        {
            vpk = max(vpk, 0.0);
            final double s = Math.sqrt(KVB + vpk * vpk);
            final double u = KP * (1.0 / MU + vgk / s);

            double sp, sig;                  // softplus(u), logistic(u)
            if (u > 30.0) { sp = u; sig = 1.0; }
            else if (u < -30.0) { sp = Math.exp(u); sig = sp; }
            else
            {
                final double eu = Math.exp(u);
                sp = Math.log1p(eu);
                sig = eu / (1.0 + eu);
            }

            final double e1 = (vpk / KP) * sp;
            if (e1 <= 0.0)
            {
                d[0] = 0.0;
                d[1] = 0.0;
                return 0.0;
            }
            final double e1ex1 = Math.pow(e1, EX - 1.0);
            final double ip = 2.0 * e1ex1 * e1 / KG1;
            final double dIpdE1 = 2.0 * EX * e1ex1 / KG1;

            final double dUdVpk = -KP * vgk * vpk / (s * s * s);
            final double dE1dVpk = sp / KP + (vpk / KP) * sig * dUdVpk;
            final double dE1dVgk = vpk * sig / s;
            d[0] = dIpdE1 * dE1dVpk;
            d[1] = dIpdE1 * dE1dVgk;
            return ip;
        }

        /** Inverse Koren grid voltage relative to the cathode, for a positive
         *  plate-to-cathode voltage and plate current. The large-argument
         *  branch avoids overflowing expm1 while retaining double precision. */
        static double gridForCurrent(double plate, double current)
        {
            final double e1 = Math.pow(current * KG1 * 0.5, 1.0 / EX);
            final double z = e1 * KP / plate;
            final double u = z > 50.0 ? z : Math.log(Math.expm1(z));
            return Math.sqrt(KVB + plate * plate) * (u / KP - 1.0 / MU);
        }

        /**
         * Builds the table for a cathode bypass sampled at a rate.
         *
         * @param rate  internal rate of the trapezoidal cathode, or infinity
         *              for a frozen cathode (B = 0)
         */
        CurrentTable(double rate)
        {
            kb = (0.5 / (rate * CK)) / (1.0 + 0.5 / (rate * CK * RK));
            final double[] n = new double[4];
            final double[] kd = new double[2];
            for (int si = 0; si < NS; ++si)
                for (int gi = 0; gi < NG; ++gi)
                {
                    final double s = si <= 6 ? 80.0 + 8.0 * si
                                             : 128.0 + 16.0 * (si - 6);
                    // Tail cells must still resolve the exponential enough
                    // to keep the cubic nonnegative.
                    final double r = gi <= 16 ? -0.08 + gi * 0.0025
                        : (gi <= 31 ? -0.04 + (gi - 16) * 0.001
                                    : -0.025 + (gi - 31) / 6400.0);
                    solve(s, s * r, n, kd);
                    final double y = n[0];
                    double ds = n[1];
                    double dg = n[2];
                    ds += r * dg;              // dI/dS with R held fixed
                    dg *= s;                   // dI/dR with S held fixed
                    // Differentiate the implicit dI/dR, not sampled currents;
                    // 0.001 V is much smaller than the 8/16 V supply cells.
                    solve(s + 0.001, (s + 0.001) * r, n, kd);
                    final double upper = n[2];
                    solve(s - 0.001, (s - 0.001) * r, n, kd);
                    final double lower = n[2];
                    final double dsg = ((s + 0.001) * upper - (s - 0.001) * lower) / 0.002;
                    final int at = (si * NG + gi) * 4;
                    nodes[at] = y;
                    nodes[at + 1] = ds;
                    nodes[at + 2] = dg;
                    nodes[at + 3] = dsg;
                }
        }

        /**
         * The implicit load-line current at a supply and grid voltage.
         *
         * @param supply  S
         * @param grid    G
         * @param out     receives the current, dI/dS, dI/dG and 0
         * @param kd      scratch of two values
         */
        void solve(double supply, double grid, double[] out, double[] kd)
        {
            // Positive current is bounded by the plate load. Safeguard Newton
            // with that bracket: setup converges faster than pure bisection
            // without accepting a root on an unphysical branch.
            double low = 0.0, high = supply / (RL + kb);
            double y = 0.5 * high, p, dp, dg;
            for (int k = 0; k < 60; ++k)
            {
                p = koren(supply - (RL + kb) * y, grid - kb * y, kd);
                dp = kd[0];
                dg = kd[1];
                final double f = y - p;
                // An absolute 1e-15 A stop leaves arbitrary residuals in
                // cutoff nodes whose true current is much smaller. Those
                // inconsistent values/slopes can make their cubic negative.
                if (Math.abs(f) <= max(1e-30, 1e-13 * max(y, p))) break;
                if (f > 0.0) high = y;
                else low = y;
                final double slope = 1.0 + (RL + kb) * dp + kb * dg;
                final double next = y - f / slope;
                y = next >= low && next <= high ? next : 0.5 * (low + high);
            }
            p = koren(supply - (RL + kb) * y, grid - kb * y, kd);
            dp = kd[0];
            dg = kd[1];
            final double den = 1.0 + (RL + kb) * dp + kb * dg;
            out[0] = y;
            out[1] = dp / den;
            out[2] = dg / den;
            out[3] = 0.0;
        }

        static double cubic(double a, double b, double da, double db, double t)
        {
            final double diff = b - a;
            return (((da + db - 2.0 * diff) * t + (3.0 * diff - 2.0 * da - db)) * t + da) * t + a;
        }

        boolean covers(double s, double g)
        {
            return s >= 80.0 && s < 304.0 && Double.isFinite(g) && g < 1.0;
        }

        double eval(double s, double g)
        {
            // Below this cutoff the unloaded Koren current is < 1e-27 A.
            if (g <= -0.08 * s) return 0.0;
            final double step = s < 128.0 ? 8.0 : 16.0;
            final double r = g / s;
            final double rstep = r < -0.04 ? 0.0025 : (r < -0.025 ? 0.001 : 1.0 / 6400.0);
            final double sp = s < 128.0 ? (s - 80.0) / 8.0 : 6.0 + (s - 128.0) / 16.0;
            final double gp = r < -0.04 ? (r + 0.08) * 400.0
                : (r < -0.025 ? 16.0 + (r + 0.04) * 1000.0
                              : 31.0 + (r + 0.025) * 6400.0);
            // Adding the grid offset can round a value just below the upper
            // edge onto that edge. Use the final cell, with t = 1, there.
            final int si = Math.min((int) sp, NS - 2);
            final int gi = Math.min((int) gp, NG - 2);
            final double st = sp - si, gt = gp - gi;
            final int a = (si * NG + gi) * 4;
            final int b = ((si + 1) * NG + gi) * 4;
            final int c = (si * NG + gi + 1) * 4;
            final int d = ((si + 1) * NG + gi + 1) * 4;
            final double[] t = nodes;
            final double p0 = cubic(t[a], t[b], t[a + 1] * step, t[b + 1] * step, st);
            final double p1 = cubic(t[c], t[d], t[c + 1] * step, t[d + 1] * step, st);
            final double m0 = rstep * cubic(t[a + 2], t[b + 2], t[a + 3] * step, t[b + 3] * step, st);
            final double m1 = rstep * cubic(t[c + 2], t[d + 2], t[c + 3] * step, t[d + 3] * step, st);
            return max(0.0, cubic(p0, p1, m0, m1, gt));
        }

        /** The surface restricted to one supply S: the S blend weights are
         *  computed once, so repeated grid evaluations at a frozen supply
         *  cost one R cell each. The same bicubic as eval(), in the Hermite
         *  basis (equal to rounding). */
        static final class Slice
        {
            double[] table = null;
            int row0 = 0, row1 = 0;
            double s = 0.0, invS = 0.0, cut = 0.0;
            double h00 = 0.0, h01 = 0.0, h10 = 0.0, h11 = 0.0;
            boolean inside = false;

            void set(CurrentTable owner, double supply)
            {
                s = supply;
                inside = supply >= 80.0 && supply < 304.0;
                if (!inside) return;
                table = owner.nodes;
                invS = 1.0 / supply;
                cut = -0.08 * supply;
                final double step = supply < 128.0 ? 8.0 : 16.0;
                final double sp = supply < 128.0 ? (supply - 80.0) / 8.0
                                                 : 6.0 + (supply - 128.0) / 16.0;
                final int si = Math.min((int) sp, NS - 2);
                final double t = sp - si;
                h00 = (2.0 * t - 3.0) * t * t + 1.0;
                h01 = (3.0 - 2.0 * t) * t * t;
                h10 = step * ((t - 2.0) * t + 1.0) * t;
                h11 = step * (t - 1.0) * t * t;
                row0 = si * NG * 4;
                row1 = row0 + NG * 4;
            }

            void copyFrom(Slice o)
            {
                table = o.table;
                row0 = o.row0;
                row1 = o.row1;
                s = o.s;
                invS = o.invS;
                cut = o.cut;
                h00 = o.h00;
                h01 = o.h01;
                h10 = o.h10;
                h11 = o.h11;
                inside = o.inside;
            }

            /** Requires inside and g below the table's grid range. */
            double eval(double g)
            {
                if (g <= cut) return 0.0;
                final double r = g * invS;
                double gp, rstep;
                if (r < -0.04) { gp = (r + 0.08) * 400.0; rstep = 0.0025; }
                else if (r < -0.025) { gp = 16.0 + (r + 0.04) * 1000.0; rstep = 0.001; }
                else { gp = 31.0 + (r + 0.025) * 6400.0; rstep = 1.0 / 6400.0; }
                final int gi = Math.min((int) gp, NG - 2);
                final double gt = gp - gi;
                final double[] n = table;
                final int a = row0 + gi * 4;
                final int c = a + 4;
                final int b = row1 + gi * 4;
                final int d = b + 4;
                final double p0 = h00 * n[a] + h01 * n[b] + h10 * n[a + 1] + h11 * n[b + 1];
                final double p1 = h00 * n[c] + h01 * n[d] + h10 * n[c + 1] + h11 * n[d + 1];
                final double m0 = rstep * (h00 * n[a + 2] + h01 * n[b + 2] + h10 * n[a + 3] + h11 * n[b + 3]);
                final double m1 = rstep * (h00 * n[c + 2] + h01 * n[d + 2] + h10 * n[c + 3] + h11 * n[d + 3]);
                final double diff = p1 - p0;
                final double v = (((m0 + m1 - 2.0 * diff) * gt + (3.0 * diff - 2.0 * m0 - m1)) * gt
                                  + m0) * gt + p0;
                return v > 0.0 ? v : 0.0;
            }
        }
    }

    /** Fixed coefficients of the continuous-time core for one factor. */
    static final class CoreDesign
    {
        /** Order of the output B-spline. */
        static final int KERNEL_ORDER = 6;
        /** Farrow taps: x[n-taps/2+1] .. x[n+taps/2]. */
        final int taps;
        /** Polynomial degree in the interval coordinate. */
        final int degree;
        /** taps x (degree+1), monomial in tau. */
        final double[] farrow;
        /** B-spline piece for output m = n + k, k = -2..3, in powers of (tau - 1/2). */
        final double[][] kernel = new double[KERNEL_ORDER][KERNEL_ORDER];
        /** Linear-phase droop FIR plus padding. */
        final double[] compensation;
        /** Base-rate samples added by the core. */
        final int latency;
        /** Oversampling factor of the design. */
        final int osFactor;

        // Least-squares Farrow fits over 0..22 kHz at 48 kHz (scaled with
        // the base rate), interpolating the two interval samples. Max error
        // vs the ideal band-limited interpolant: -82.8 dB (2x), -104.2 dB
        // (4x), -108.2 dB (8x), -104.6 dB (16x). The droop compensation
        // inverts sinc(f)^6 over the same band to better than 2e-6 dB.
        private static final double[] FARROW_2 = {
            4.2321905086886495e-10, 0.0059466381516058153, -0.002278523941500805,
            -0.0054952548957599556, -0.014968825413515578, 0.054109227099701757,
            -0.078269013079604377, 0.062385435811858708, -0.025290994449854602,
            0.0038613094605228345, -2.2707239900454428e-09, -0.04509673531374573,
            0.02130560198974633, 0.044927139987920424, 0.068139628956554815,
            -0.29034355992063943, 0.41888697256651169, -0.33093026606533477,
            0.13329150667830314, -0.020180282199047366, 6.3151437033992178e-09,
            0.19516443759783259, -0.125629932201473, -0.18510777189666724,
            -0.11518179249706405, 0.79144728674092368, -1.1628931982523558,
            0.91250354516673049, -0.36515236023089487, 0.0548497671327925,
            -1.1679462053512646e-08, -0.73810415625364523, 0.8470174584244009,
            0.17008113400299396, 0.090159156301663379, -1.4018192300222059,
            2.141392397438906, -1.6757774481811469, 0.66635226461915309,
            -0.099301542452499336, 1.0000000156560307, -0.15092152990607394,
            -1.4813400355254824, 0.32501007016593614, -0.10665904644708368,
            1.7796410825385522, -2.8452200456412218, 2.2313784198552291,
            -0.8823072013373332, 0.13041824116835923, -1.5700830890361179e-08,
            1.0030991668852851, 0.84865934877928584, -0.74002126985840833,
            0.25444017376234029, -1.7071024411426592, 2.8159173504397854,
            -2.2212010942574856, 0.87438979546714801, -0.12818098509269454,
            1.1781332260547313e-08, -0.37253030413198274, -0.12785657025701674,
            0.56505142242672013, -0.33848444021117219, 1.2561039702068575,
            -2.0800938843434165, 1.6517199081920575, -0.64812744525532984,
            0.094217309828563259, -6.4097566291092411e-09, 0.13220572975363162,
            0.022984358585025653, -0.23015119647358298, 0.23722601584356839,
            -0.68523256280710365, 1.1138842977913868, -0.88863883259602172,
            0.3478529827090574, -0.050130774674655959, 2.3207754129408065e-09,
            -0.034616695799025515, -0.0030424597428906587, 0.06570936592805314,
            -0.092489611102646821, 0.25062568572871546, -0.39729342975322812,
            0.31732189085987816, -0.12390188239748591, 0.017687129761438363,
            -4.3623536317367461e-10, 0.0049101708741372896, 0.00017308418349044246,
            -0.010091070767974901, 0.017824294982185494, -0.04733411530281835,
            0.073532096156291082, -0.058588400350969086, 0.022788475313836876,
            -0.0032145338738844057
        };
        private static final double[] COMP_2 = {
            2.71867910879388, -1.2024111681479335, 0.46738582076148377,
            -0.16331116573163179, 0.048832017855856398, -0.011604018931486495,
            0.0019406397776269202, -0.00017174220997273029
        };
        private static final double[] FARROW_4 = {
            -1.2900536553478881e-06, -0.0080151506262985533, -0.17777590204833338,
            1.3139119710287313, -4.0413832196153869, 6.3642884759250222,
            -4.992867908276553, 1.5418432994555842, 7.9871493323757734e-06,
            0.088046779796333074, 1.0604467092541447, -8.1687752563172111,
            25.099313210118467, -39.488286729375986, 30.975170253454831,
            -9.5659246558470752, -2.2105953216776039e-05, -0.55815096062851943,
            -2.3938696401524284, 22.40917634184877, -69.6019865941457,
            109.51192256668676, -85.894556644533552, 26.527491731519852,
            1.0000353884966167, -0.33265191629960283, 3.674166593014272,
            -35.4328783727806, 111.52063276854375, -175.64384089126642,
            137.76206099998669, -42.547532060929456, -3.5359293796327108e-05,
            1.098889851519979, -4.2836042287146743, 35.135653508220415,
            -111.44779322067839, 175.80749233768327, -137.90312082065205,
            42.592525392586815, 2.205090483678061e-05, -0.37161767694349446,
            3.0655130552579171, -21.923092860130158, 69.500034073335797,
            -109.8183373403734, 86.15979318327652, -26.612319122360798,
            -7.9535630817034395e-06, 0.095783904254697808, -1.1276037484567922,
            7.9530205187897351, -25.075497255063709, 39.67283956818612,
            -31.136230566192772, 9.6176971986688393, 1.2822935194890068e-06,
            -0.012285737644740915, 0.18272380080694042, -1.2870126749889277,
            4.0467156665242037, -6.4061717578660602, 5.0298415014987468,
            -1.5538123482944795
        };
        private static final double[] COMP_4 = {
            0.77932253944179963, 0.48450011964418033, -0.63543552895607291,
            0.36687547570613865, -0.13057476517676106, 0.027680644656114826,
            -0.0027072253534879587
        };
        private static final double[] FARROW_8 = {
            2.1428912092597181e-06, -0.068053152547346507, 0.82615315111595389,
            -2.25344764450442, 2.3863455617383647, -0.89100287142393453,
            -1.0400687109947751e-05, 0.076025067275552194, -3.5474940938342541,
            10.692630341379365, -11.548180887960177, 4.3270436164777122,
            1.0000204893461682, -1.474312011333621, 7.0561524197541718,
            -20.727998495189027, 22.674059266177732, -8.5279485269151927,
            -2.0475134054884214e-05, 2.1464836357794721, -7.6380358894712534,
            20.54461472771839, -22.577478060052183, 8.5244628827979447,
            1.0378974319923476e-05, -0.83433580274857921, 4.1702488442657986,
            -10.417228839352022, 11.403009710027204, -4.3217178780150958,
            -2.1353801282180795e-06, 0.15419278048117258, -0.8670223096885199,
            2.1614225669978211, -2.3377483222244333, 0.88916021323615935
        };
        private static final double[] COMP_8 = {
            2.0670375203804419, -0.6482179311307088, 0.12681513715972098,
            -0.012115968043045313
        };
        private static final double[] FARROW_16 = {
            -4.7274945894591747e-08, -0.33475471468652312, 0.50168301501965318,
            -0.16692822036410673, 1.0000001518786812, -0.49831699527905698,
            -1.0024720850935107, 0.50078884218257014, -1.6291311300334343e-07,
            1.0008871433692914, 0.4999030230846026, -0.50078992841108072,
            5.8345140851491506e-08, -0.16782377216653666, 0.00089437399553421448,
            0.16692931835075694
        };
        private static final double[] COMP_16 = {
            2.2958105734077243, -0.82005508291669238, 0.19585990127822545,
            -0.023710110331518937
        };

        CoreDesign(int factor)
        {
            osFactor = factor;
            final double[] half;
            final int padding;
            switch (factor)
            {
            case 2:  taps = 10; farrow = FARROW_2;  half = COMP_2;  padding = 0; break;
            case 4:  taps = 8;  farrow = FARROW_4;  half = COMP_4;  padding = 0; break;
            case 8:  taps = 6;  farrow = FARROW_8;  half = COMP_8;  padding = 0; break;
            default: taps = 4;  farrow = FARROW_16; half = COMP_16; padding = 9; break;
            }
            // half[0] is the centre tap; mirror, then delay by the padding.
            final int h = half.length - 1;
            compensation = new double[2 * h + 1 + padding];
            for (int q = 0; q <= h; ++q)
            {
                compensation[padding + h + q] = half[q];
                compensation[padding + h - q] = half[q];
            }
            degree = taps - 1;
            // Interval n is processed when x[n + taps/2] arrives; its output
            // moments complete z[n - 2]; the FIR centre adds its half length.
            final int delay = taps / 2 + 2 + h + padding;
            latency = delay / factor;
            // Order-6 B-spline: B(u) = sum_j (-1)^j C(6,j) (u + 3 - j)_+^5 / 120.
            // For output m = n + k and tau = 1/2 + d, u = k - 1/2 - d; every
            // truncated power keeps one sign over the interval, so each piece
            // is an exact polynomial in d.
            final double[] binom6 = {1, 6, 15, 20, 15, 6, 1};
            final double[] binom5 = {1, 5, 10, 10, 5, 1};
            for (int ko = 0; ko < KERNEL_ORDER; ++ko)
            {
                final int k = ko - 2;
                for (int j = 0; j <= 6; ++j)
                {
                    final double c = k + 2.5 - j;
                    if (c <= 0.0) continue;
                    final double sign = (j & 1) != 0 ? -1.0 : 1.0;
                    for (int p = 0; p <= 5; ++p)
                    {
                        final double term = sign * binom6[j] * binom5[p] * Math.pow(c, 5 - p)
                                          * ((p & 1) != 0 ? -1.0 : 1.0) / 120.0;
                        kernel[ko][p] += term;
                    }
                }
            }
        }
    }

    /** phi_k(w) = sum_j w^j/(j+k)!, k = 0..4 (phi_0 = exp). Small |w|: a
     *  length-adapted Taylor series of phi_4 and the exact downward
     *  recurrence; otherwise the upward recurrence from exp. */
    static void phi(double w, double[] p)
    {
        final double aw = Math.abs(w);
        if (aw < 0.5)
        {
            final int n = aw < 1e-3 ? 3 : (aw < 0.02 ? 5 : (aw < 0.1 ? 7 : 10));
            double v = INV_FACTORIAL[4 + n];
            for (int j = n - 1; j >= 0; --j) v = v * w + INV_FACTORIAL[4 + j];
            p[4] = v;
            p[3] = w * v + INV_FACTORIAL[3];
            p[2] = w * p[3] + INV_FACTORIAL[2];
            p[1] = w * p[2] + 1.0;
            p[0] = w * p[1] + 1.0;
        }
        else
        {
            p[0] = Math.exp(w);
            p[1] = (p[0] - 1.0) / w;
            p[2] = (p[1] - 1.0) / w;
            p[3] = (p[2] - 0.5) / w;
            p[4] = (p[3] - 1.0 / 6.0) / w;
        }
    }

    private static final double[] INV_FACTORIAL = {1.0, 1.0, 1.0 / 2, 1.0 / 6, 1.0 / 24, 1.0 / 120,
        1.0 / 720, 1.0 / 5040, 1.0 / 40320, 1.0 / 362880, 1.0 / 3628800,
        1.0 / 39916800, 1.0 / 479001600, 1.0 / 6227020800.0,
        1.0 / 87178291200.0, 1.0 / 1307674368000.0};

    /** Modal form of the FMV analog state space (symmetric similarity). */
    static final class ToneModes
    {
        /** Poles per internal sample (lambda * T). */
        final double[] lambda = new double[3];
        /** phi(lambda): the phi values of a whole-interval piece. */
        final double[][] unitPhi = new double[3][5];
        /** Input gain per internal sample. */
        final double[] beta = new double[3];
        /** Output gain. */
        final double[] gamma = new double[3];
        /** Feedthrough. */
        double direct = 0.0;
        final double[][] toModal = new double[3][3];
        final double[][] toPhysical = new double[3][3];

        // Design scratch.
        private final double[] r = new double[3], ri = new double[3];
        private final double[][] s = new double[3][3], q = new double[3][3];

        void copyFrom(ToneModes o)
        {
            for (int m = 0; m < 3; ++m)
            {
                lambda[m] = o.lambda[m];
                System.arraycopy(o.unitPhi[m], 0, unitPhi[m], 0, 5);
                beta[m] = o.beta[m];
                gamma[m] = o.gamma[m];
                System.arraycopy(o.toModal[m], 0, toModal[m], 0, 3);
                System.arraycopy(o.toPhysical[m], 0, toPhysical[m], 0, 3);
            }
            direct = o.direct;
        }

        void design(ToneStackFMV.AnalogStateSpace ss, double sampleRate)
        {
            // a = -C^-1 G with symmetric G: S = C^(1/2) a C^(-1/2) is symmetric.
            for (int i = 0; i < 3; ++i)
            {
                r[i] = Math.sqrt(ss.capacitance[i]);
                ri[i] = 1.0 / r[i];
            }
            for (int i = 0; i < 3; ++i)
                for (int j = 0; j < 3; ++j)
                {
                    s[i][j] = r[i] * ss.a[i][j] * ri[j];
                    q[i][j] = 0.0;
                }
            for (int i = 0; i < 3; ++i)
                for (int j = i + 1; j < 3; ++j)
                    s[i][j] = s[j][i] = 0.5 * (s[i][j] + s[j][i]);
            for (int i = 0; i < 3; ++i) q[i][i] = 1.0;
            // Cyclic Jacobi rotations; robust also for coincident eigenvalues.
            for (int sweep = 0; sweep < 32; ++sweep)
            {
                double off = 0.0, norm = 0.0;
                for (int i = 0; i < 3; ++i)
                    for (int j = 0; j < 3; ++j)
                    {
                        norm += s[i][j] * s[i][j];
                        if (i != j) off += s[i][j] * s[i][j];
                    }
                if (off <= 1e-30 * norm) break;
                for (int p = 0; p < 2; ++p)
                    for (int k = p + 1; k < 3; ++k)
                    {
                        if (s[p][k] == 0.0) continue;
                        final double theta = 0.5 * (s[k][k] - s[p][p]) / s[p][k];
                        final double t = (theta >= 0.0 ? 1.0 : -1.0)
                            / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                        final double c = 1.0 / Math.sqrt(t * t + 1.0), sn = t * c;
                        for (int i = 0; i < 3; ++i)
                        {
                            final double sip = s[i][p], sik = s[i][k];
                            s[i][p] = c * sip - sn * sik;
                            s[i][k] = sn * sip + c * sik;
                        }
                        for (int i = 0; i < 3; ++i)
                        {
                            final double spi = s[p][i], ski = s[k][i];
                            s[p][i] = c * spi - sn * ski;
                            s[k][i] = sn * spi + c * ski;
                        }
                        for (int i = 0; i < 3; ++i)
                        {
                            final double qip = q[i][p], qik = q[i][k];
                            q[i][p] = c * qip - sn * qik;
                            q[i][k] = sn * qip + c * qik;
                        }
                    }
            }
            final double period = 1.0 / sampleRate;
            for (int m = 0; m < 3; ++m)
            {
                lambda[m] = s[m][m] * period;
                phi(lambda[m], unitPhi[m]);
                double bm = 0.0, gm = 0.0;
                for (int i = 0; i < 3; ++i)
                {
                    toModal[m][i] = q[i][m] * r[i];      // Q^T C^(1/2)
                    toPhysical[i][m] = ri[i] * q[i][m];  // C^(-1/2) Q
                    bm += toModal[m][i] * ss.b[i];
                    gm += ss.c[i] * toPhysical[i][m];
                }
                beta[m] = bm * period;
                gamma[m] = gm;
            }
            direct = ss.d;
        }
    }
}
