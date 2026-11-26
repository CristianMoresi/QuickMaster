package com.dspark.core;

import java.util.function.DoubleUnaryOperator;

/** 32-point Gauss-Legendre quadrature for the reference integrals of the tests. */
final class GaussLegendre
{
    private static final int N = 32;
    private static final double[] NODES = new double[N];
    private static final double[] WEIGHTS = new double[N];

    static
    {
        // Roots of P_N by Newton from the Chebyshev guesses.
        for (int i = 0; i < (N + 1) / 2; ++i)
        {
            double z = Math.cos(Math.PI * (i + 0.75) / (N + 0.5));
            double pp = 0.0;
            for (int it = 0; it < 100; ++it)
            {
                double p1 = 1.0, p2 = 0.0;
                for (int j = 1; j <= N; ++j)
                {
                    final double p3 = p2;
                    p2 = p1;
                    p1 = ((2.0 * j - 1.0) * z * p2 - (j - 1.0) * p3) / j;
                }
                pp = N * (z * p1 - p2) / (z * z - 1.0);
                final double z1 = z;
                z = z1 - p1 / pp;
                if (Math.abs(z - z1) < 1e-16) break;
            }
            NODES[i] = -z;
            NODES[N - 1 - i] = z;
            WEIGHTS[i] = WEIGHTS[N - 1 - i] = 2.0 / ((1.0 - z * z) * pp * pp);
        }
    }

    private GaussLegendre()
    {
    }

    /** Integral of f over [a, b]. */
    static double integrate(DoubleUnaryOperator f, double a, double b)
    {
        final double mid = 0.5 * (a + b), half = 0.5 * (b - a);
        double sum = 0.0;
        for (int i = 0; i < N; ++i) sum += WEIGHTS[i] * f.applyAsDouble(mid + half * NODES[i]);
        return sum * half;
    }

    /** Integral of f over [a, b], split at the given break points inside it and into equal parts. */
    static double integrate(DoubleUnaryOperator f, double a, double b, double[] breaks, int parts)
    {
        if (!(b > a)) return 0.0;
        double[] cuts = new double[breaks.length + 2];
        int n = 0;
        cuts[n++] = a;
        for (double c : breaks) if (c > a && c < b) cuts[n++] = c;
        cuts[n++] = b;
        java.util.Arrays.sort(cuts, 0, n);
        double sum = 0.0;
        for (int k = 0; k + 1 < n; ++k)
        {
            final double s = cuts[k], e = cuts[k + 1];
            final double h = (e - s) / parts;
            for (int p = 0; p < parts; ++p) sum += integrate(f, s + p * h, p == parts - 1 ? e : s + (p + 1) * h);
        }
        return sum;
    }
}
