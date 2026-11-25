package com.dspark.core;

/**
 * Windowed-sinc FIR design with a Kaiser window (the designs the detail
 * filter's rate change and the detail analysis' decimators are made with).
 */
public final class FirDesign
{
    private FirDesign() { }

    /**
     * Low-pass FIR, unity gain at DC.
     *
     * @param sampleRate  sample rate in Hz
     * @param cutoffHz    cutoff frequency in Hz
     * @param numTaps     number of taps (odd, at least 3; an even count is raised to the next odd one)
     * @param beta        Kaiser window beta
     * @return the coefficients
     */
    public static double[] lowPass(double sampleRate, double cutoffHz, int numTaps, double beta)
    {
        int taps = Math.max(3, numTaps);
        if ((taps & 1) == 0) taps++;
        double normFreq = cutoffHz / sampleRate;

        // Symmetric Kaiser window.
        double[] window = new double[taps];
        double invDenominator = 1.0 / besselI0(beta);
        int last = taps - 1;
        for (int i = 0; i < taps; i++)
        {
            double x = 2.0 * i / last - 1.0;
            window[i] = besselI0(beta * Math.sqrt(Math.max(0.0, 1.0 - x * x))) * invDenominator;
        }

        int centre = taps / 2;
        double fc = normFreq * 2.0;   // normalised to [0, 1] for the sinc
        double[] design = new double[taps];
        double sum = 0.0;
        for (int i = 0; i < taps; i++)
        {
            int n = i - centre;
            double x = n * Math.PI;
            double s = (n == 0) ? fc : Math.sin(fc * x) / x;
            double c = s * window[i];
            design[i] = c;
            sum += c;
        }
        if (Math.abs(sum) > 1e-10)
        {
            double invSum = 1.0 / sum;
            for (int i = 0; i < taps; i++) design[i] *= invSum;
        }
        return design;
    }

    /** Kaiser's beta for a stopband attenuation in dB (Kaiser 1974). */
    public static double estimateKaiserBeta(double attenuationDb)
    {
        if (attenuationDb > 50.0) return 0.1102 * (attenuationDb - 8.7);
        if (attenuationDb >= 21.0)
            return 0.5842 * Math.pow(attenuationDb - 21.0, 0.4) + 0.07886 * (attenuationDb - 21.0);
        return 0.0;
    }

    /**
     * Modified Bessel function of the first kind, order zero: the power
     * series, summed until a term falls under a part in 1e12 of the sum.
     */
    static double besselI0(double x)
    {
        double sum = 1.0, term = 1.0;
        final double halfX = x / 2.0;
        for (int k = 1; k < 50; k++)
        {
            final double f = halfX / (double) k;
            term *= f * f;
            sum += term;
            if (term < sum * 1e-12) break;
        }
        return sum;
    }
}
