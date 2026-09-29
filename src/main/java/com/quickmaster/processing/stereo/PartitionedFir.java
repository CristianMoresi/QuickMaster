package com.quickmaster.processing.stereo;

import com.dspark.core.FFTComplex;
import java.util.Arrays;

/** Uniform partitioned overlap-add convolution. One worker owns all scratch/state. */
final class PartitionedFir {
    private final int block, count;
    private final FFTComplex fft;
    private final float[][] kernels, spectra;
    private final float[] input, output, overlap, sum;
    private int position, head;

    PartitionedFir(double[] taps, int block) {
        if (taps.length == 0 || block < 2 || Integer.bitCount(block) != 1)
            throw new IllegalArgumentException("Invalid FIR dimensions");
        this.block = block; count = (taps.length + block - 1) / block;
        fft = new FFTComplex(2 * block);
        kernels = new float[count][4 * block]; spectra = new float[count][4 * block];
        input = new float[block]; output = new float[block]; overlap = new float[block];
        sum = new float[4 * block];
        for (int p = 0; p < count; p++) {
            for (int i = 0; i < block && p * block + i < taps.length; i++)
                kernels[p][2 * i] = (float)taps[p * block + i];
            fft.forward(kernels[p]);
        }
    }
    int blockLatency() { return block; }
    float process(double sample) {
        float result = output[position];
        input[position++] = (float)sample;
        if (position == block) {
            float[] current = spectra[head];
            Arrays.fill(current, 0);
            for (int i = 0; i < block; i++) current[2 * i] = input[i];
            fft.forward(current);
            // Accumulate in double: coherent partitions must not lose low-level detail.
            for (int k = 0; k < sum.length; k += 2) {
                double re = 0, im = 0;
                for (int p = 0, j = head; p < count; p++, j = j == 0 ? count - 1 : j - 1) {
                    float[] x = spectra[j], h = kernels[p];
                    re += (double)x[k] * h[k] - (double)x[k + 1] * h[k + 1];
                    im += (double)x[k] * h[k + 1] + (double)x[k + 1] * h[k];
                }
                sum[k] = (float)re; sum[k + 1] = (float)im;
            }
            fft.inverse(sum);
            for (int i = 0; i < block; i++) {
                output[i] = sum[2 * i] + overlap[i];
                overlap[i] = sum[2 * (i + block)];
            }
            head = (head + 1) % count; position = 0;
        }
        return result;
    }

    /** 150 Hz stop-band edge, 200 Hz pass-band edge; odd, symmetric Kaiser FIR. */
    static double[] bassExclusion(int rate) {
        return lowCut(rate, 175);
    }

    /** -6 dB cutoff; >=50 Hz transition except at the very bottom of the range. */
    static double[] lowCut(int rate, double hz) {
        if (rate < 8000) throw new IllegalArgumentException("Sample rate below 8 kHz");
        if (!Double.isFinite(hz) || hz < 0 || hz > 5000) throw new IllegalArgumentException("Invalid low cut");
        if (hz == 0) return new double[]{1};
        if (hz >= rate * .5) return new double[]{0};
        double transition = Math.min(Math.max(50, hz * .25), Math.min(hz * 1.5, rate - 2 * hz));
        // At sub-Nyquist extremes use the all-reject limit instead of allocating
        // unbounded FIRs for a vanishing transition outside useful source content.
        if (transition < 30) return new double[]{0};
        int order = (int)Math.ceil(94 / (2.285 * 2 * Math.PI * transition / rate));
        if ((order & 1) != 0) order++;
        double[] h = new double[order + 1];
        int center = order / 2;
        double beta = 10.2, denom = i0(beta), dc = 0;
        for (int i = 0; i <= order; i++) {
            double x = i - center, r = x / center;
            double low = x == 0 ? 2 * hz / rate : Math.sin(2 * Math.PI * hz / rate * x) / (Math.PI * x);
            h[i] = low * i0(beta * Math.sqrt(Math.max(0, 1 - r * r))) / denom;
            dc += h[i];
        }
        for (int i = 0; i <= order; i++) h[i] = -h[i] / dc;
        h[center] += 1;
        return h;
    }
    private static double i0(double x) {
        double sum = 1, term = 1;
        for (int k = 1; k < 80; k++) {
            term *= x * x / (4.0 * k * k); sum += term;
            if (term < sum * 1e-16) break;
        }
        return sum;
    }
}
