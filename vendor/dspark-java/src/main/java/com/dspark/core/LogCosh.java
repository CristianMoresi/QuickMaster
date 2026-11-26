package com.dspark.core;

/**
 * log(cosh(x)) and its integral, the antiderivatives the tanh antialiasing
 * (ADAA) kernels difference.
 * <p>
 * Double only: the large-argument identity subtracts ln 2 from a value near
 * it, which costs about 14 bits just above the series range; double keeps
 * the absolute error near 1e-16 there.
 * <p>
 * Threading: pure functions.
 */
public final class LogCosh
{
    private LogCosh()
    {
        // Utility class, not instantiable.
    }

    /**
     * Stable log(cosh(x)), shared by the tanh antiderivative kernels.
     *
     * @param x  the argument
     * @return log(cosh(x))
     */
    public static double logCosh(double x)
    {
        final double a = Math.abs(x);
        // The usual large-argument identity subtracts nearly equal numbers
        // around zero. This series also preserves small-signal ADAA accuracy.
        if (a < 0.01)
        {
            final double z = x * x;
            return z * (0.5 + z * (-1.0 / 12.0 + z * (1.0 / 45.0 + z * (-17.0 / 2520.0))));
        }
        return a + Math.log1p(Math.exp(-2.0 * a)) - 0.6931471805599453;
    }

    /**
     * For a &gt;= 1: integralLogCosh(a) - (a^2/2 - a ln 2 + pi^2/24), the
     * exponentially small part 0.5 Li2(-e^{-2a}) (|value| &lt; 0.07). The
     * dilogarithm series is cut after 16 terms, below 5e-18 at a &gt;= 1.
     *
     * @param a  the argument, at least 1
     * @return the tail of the integral
     */
    public static double integralLogCoshTail(double a)
    {
        final double z = Math.exp(-2.0 * a);
        double li = 1.0 / 256.0;
        for (int k = 15; k >= 1; --k)
            li = li * z + (k % 2 == 0 ? 1.0 : -1.0) / (k * k);
        return 0.5 * (li * z);
    }

    /**
     * Integral of log(cosh(x)), with value zero at the origin. A Chebyshev
     * polynomial handles |x| &lt; 1 without cancellation. The outer branch
     * integrates the exponentially convergent log1p expansion; its omitted
     * dilogarithm tail is below 5e-18 at |x| &gt;= 1.
     *
     * @param x  the argument
     * @return the integral of log(cosh(t)) from 0 to x
     */
    public static double integralLogCosh(double x)
    {
        final double a = Math.abs(x);
        if (a < 1.0)
        {
            final double z = a * a;
            double p = 0.0;
            for (double c : INTEGRAL_COEFFICIENTS) p = p * z + c;
            return x * z * p;
        }
        // Halving is exact, so this equals 0.5 * (li + pi^2 / 12) bit for bit.
        return Math.copySign(0.5 * a * a - a * 0.6931471805599453
            + (integralLogCoshTail(a) + 0.4112335167120566), x);
    }

    private static final double[] INTEGRAL_COEFFICIENTS = {
        -7.98389023185534e-11, 8.6684720203723614e-10,
        -4.7997095383377807e-9, 1.8850617466346369e-8,
        -6.1974137503985441e-8, 1.8914247025489175e-7,
        -5.6812797392185565e-7, 1.7247571922095787e-6,
        -5.3522004257752879e-6, 1.7105344959469917e-5,
        -5.6815608632124677e-5, 0.00019881353180096906,
        -0.00074955908287299841, 0.0031746031746026004,
        -0.016666666666666659, 0.16666666666666666
    };
}
