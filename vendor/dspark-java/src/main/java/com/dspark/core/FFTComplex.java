package com.dspark.core;

/**
 * Stockham radix-4 autosort complex FFT, with a closing radix-2 pass for odd
 * log2 sizes. Port of DSPark C++ Core/FFT.h at 9330f1cd29164f6d33e7876bc422627a200ec919.
 * The public layout remains interleaved [re, im]; internal split arrays avoid
 * bit reversal and are reused without allocation. Inverse is normalized by 1/N.
 * An instance is stateful: use one per concurrent worker. No incubator modules
 * or native CPU extensions are required by this Java 17 implementation. The
 * butterflies and twiddles use double precision; only the public I/O rounds to
 * float, avoiding accumulated rounding of large coherent spectral peaks.
 */
public final class FFTComplex {
    private final int size;
    private final double[] realA, imagA, realB, imagB, twiddles;

    public FFTComplex(int size) {
        if (size < 2 || (size & (size - 1)) != 0)
            throw new IllegalArgumentException("FFTComplex size must be a power of two >= 2");
        this.size = size;
        realA = new double[size]; imagA = new double[size];
        realB = new double[size]; imagB = new double[size];
        int count = 0;
        for (int n = size; n >= 4; n /= 4) count += 6 * (n / 4);
        twiddles = new double[count];
        int offset = 0;
        for (int n = size; n >= 4; n /= 4) {
            int quarter = n / 4;
            for (int p = 0; p < quarter; p++) {
                double angle = -2.0 * Math.PI * p / n;
                for (int k = 1; k <= 3; k++) {
                    twiddles[offset + (2 * k - 2) * quarter + p] = Math.cos(k * angle);
                    twiddles[offset + (2 * k - 1) * quarter + p] = Math.sin(k * angle);
                }
            }
            offset += 6 * quarter;
        }
    }

    public int getSize() { return size; }
    public void forward(float[] data) { transform(data, false); }
    public void inverse(float[] data) { transform(data, true); }

    private void transform(float[] data, boolean inverse) {
        for (int i = 0; i < size; i++) {
            realA[i] = data[2 * i];
            imagA[i] = inverse ? -data[2 * i + 1] : data[2 * i + 1];
        }
        double[] xr = realA, xi = imagA, yr = realB, yi = imagB;
        int n = size, stride = 1, offset = 0;
        while (n >= 4) {
            int quarter = n / 4;
            for (int p = 0; p < quarter; p++) {
                double w1r = twiddles[offset + p], w1i = twiddles[offset + quarter + p];
                double w2r = twiddles[offset + 2 * quarter + p], w2i = twiddles[offset + 3 * quarter + p];
                double w3r = twiddles[offset + 4 * quarter + p], w3i = twiddles[offset + 5 * quarter + p];
                int a = stride * p, b = a + stride * quarter;
                int c = b + stride * quarter, d = c + stride * quarter;
                int out = stride * 4 * p;
                for (int q = 0; q < stride; q++) {
                    double apcR = xr[a + q] + xr[c + q], apcI = xi[a + q] + xi[c + q];
                    double amcR = xr[a + q] - xr[c + q], amcI = xi[a + q] - xi[c + q];
                    double bpdR = xr[b + q] + xr[d + q], bpdI = xi[b + q] + xi[d + q];
                    double bmdR = xr[b + q] - xr[d + q], bmdI = xi[b + q] - xi[d + q];
                    double t1r = amcR + bmdI, t1i = amcI - bmdR;
                    double t2r = apcR - bpdR, t2i = apcI - bpdI;
                    double t3r = amcR - bmdI, t3i = amcI + bmdR;
                    int y = out + q;
                    yr[y] = apcR + bpdR; yi[y] = apcI + bpdI;
                    y += stride;
                    yr[y] = t1r * w1r - t1i * w1i; yi[y] = t1r * w1i + t1i * w1r;
                    y += stride;
                    yr[y] = t2r * w2r - t2i * w2i; yi[y] = t2r * w2i + t2i * w2r;
                    y += stride;
                    yr[y] = t3r * w3r - t3i * w3i; yi[y] = t3r * w3i + t3i * w3r;
                }
            }
            double[] swap = xr; xr = yr; yr = swap;
            swap = xi; xi = yi; yi = swap;
            offset += 6 * quarter; n /= 4; stride *= 4;
        }
        if (n == 2) {
            for (int q = 0; q < stride; q++) {
                yr[q] = xr[q] + xr[q + stride]; yi[q] = xi[q] + xi[q + stride];
                yr[q + stride] = xr[q] - xr[q + stride]; yi[q + stride] = xi[q] - xi[q + stride];
            }
            xr = yr; xi = yi;
        }
        double scale = inverse ? 1.0 / size : 1.0;
        double imScale = inverse ? -scale : scale;
        for (int i = 0; i < size; i++) {
            data[2 * i] = (float) (xr[i] * scale);
            data[2 * i + 1] = (float) (xi[i] * imScale);
        }
    }
}
