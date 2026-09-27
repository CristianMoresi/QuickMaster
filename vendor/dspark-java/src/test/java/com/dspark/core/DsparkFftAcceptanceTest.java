package com.dspark.core;
import java.util.*;

/** Independent numerical oracle for the existing and future Java FFT port. No audio files. */
public final class DsparkFftAcceptanceTest {
    @org.junit.jupiter.api.Test void independentOracle() { main(new String[0]); }
    private static final Random RANDOM = new Random(20260927);
    private static float[] noise(int length) {
        float[] result = new float[length];
        for (int i = 0; i < length; i++) result[i] = RANDOM.nextFloat() * 2 - 1;
        return result;
    }
    private static void near(double expected, double actual, double tolerance, String context) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(context + ": expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
    }
    private static void complexDft(int size) {
        FFTComplex fft = new FFTComplex(size);
        // Reuse the same instance for unrelated inputs to catch retained transform state.
        for (int repetition = 0; repetition < 3; repetition++) {
            float[] input = noise(size * 2), output = input.clone();
            fft.forward(output);
            double errorEnergy = 0, referenceEnergy = 0, maximumError = 0;
            for (int k = 0; k < size; k++) {
                double re = 0, im = 0;
                for (int j = 0; j < size; j++) {
                    double angle = -2 * Math.PI * j * k / size;
                    double c = Math.cos(angle), s = Math.sin(angle);
                    re += input[2*j] * c - input[2*j+1] * s;
                    im += input[2*j] * s + input[2*j+1] * c;
                }
                near(re, output[2*k], 3e-6 * Math.sqrt(size), "complex real " + size + "/" + k);
                near(im, output[2*k+1], 3e-6 * Math.sqrt(size), "complex imaginary " + size + "/" + k);
                double er = re-output[2*k], ei = im-output[2*k+1];
                errorEnergy += er*er+ei*ei; referenceEnergy += re*re+im*im;
                maximumError = Math.max(maximumError, Math.max(Math.abs(er), Math.abs(ei)));
            }
            double relativeRms = Math.sqrt(errorEnergy/referenceEnergy);
            near(0, relativeRms, 1e-6, "complex relative RMS " + size);
            fft.inverse(output);
            for (int i = 0; i < input.length; i++) near(input[i], output[i], 2e-6, "complex round trip " + size);
            System.out.printf(Locale.ROOT, "COMPLEX_DFT_PASS size=%d repetition=%d maxAbsError=%.9g relativeRms=%.9g%n",
                    size, repetition, maximumError, relativeRms);
        }
    }
    private static void real(int size) {
        FFTReal fft = new FFTReal(size);
        float[] input = noise(size), frequency = new float[size+2], restored = new float[size];
        fft.forward(input, frequency); fft.inverse(frequency, restored);
        for (int i = 0; i < size; i++) near(input[i], restored[i], 2e-6, "real round trip " + size);
        // Analytic impulse, DC, Nyquist and an off-axis sine test phase and endpoint packing.
        for (int pattern = 0; pattern < 4; pattern++) {
            Arrays.fill(input, 0);
            for (int i = 0; i < size; i++) input[i] = switch (pattern) {
                case 0 -> i == 0 ? 1 : 0;
                case 1 -> 1;
                case 2 -> (i & 1) == 0 ? 1 : -1;
                default -> (float)Math.sin(2*Math.PI*i/size);
            };
            fft.forward(input, frequency);
            for (int k = 0; k <= size/2; k++) {
                double re = pattern == 0 ? 1 : pattern == 1 && k == 0 || pattern == 2 && k == size/2 ? size : 0;
                double im = pattern == 3 && k == 1 ? -size/2.0 : 0;
                near(re, frequency[2*k], 3e-6*Math.sqrt(size), "real spectrum real " + size + "/" + pattern + "/" + k);
                near(im, frequency[2*k+1], 3e-6*Math.sqrt(size), "real spectrum imaginary " + size + "/" + pattern + "/" + k);
            }
        }
        System.out.println("REAL_ANALYTIC_PASS size=" + size);
    }
    public static void main(String[] args) {
        System.out.println("DSPARK_SOURCE " + FFTComplex.class.getProtectionDomain().getCodeSource().getLocation());
        for (int size = 2; size <= 1024; size *= 2) complexDft(size);
        for (int size = 4; size <= 65536; size *= 2) real(size);
        for (int size : new int[]{-1,0,1,3,6,1000}) {
            try { new FFTComplex(size); throw new AssertionError("Invalid complex size accepted: " + size); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("FFT_ACCEPTANCE_PASS independentDft=true analyticEndpoints=true repeatedInstance=true");
    }
}
