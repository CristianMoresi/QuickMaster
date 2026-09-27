package com.quickmaster.processing.analysis;

/** Shared log-frequency projection of channel-averaged power, never a mono sum. */
final class SpectrumGrid {
    static final int FFT_SIZE = 16384, SIZE = 512, SMOOTH_RADIUS = 6;
    static final float FLOOR_DB = -120;
    static final double MIN_HZ = 20, MAX_HZ = 20000;
    private static final double SPAN = Math.log10(MAX_HZ / MIN_HZ);
    private static final double R_LO = Math.pow(2, -1.0 / 24), R_HI = Math.pow(2, 1.0 / 24);
    private static final double[] HZ = new double[SIZE];
    static {
        for (int i = 0; i < SIZE; i++) HZ[i] = MIN_HZ * Math.pow(10, SPAN * i / (SIZE - 1));
    }
    private SpectrumGrid() { }

    static void validateFormat(int channels, double rate) {
        if ((channels != 1 && channels != 2) || !Double.isFinite(rate) || rate <= 0)
            throw new IllegalArgumentException("Spectrum requires mono/stereo and a finite positive sample rate.");
    }

    static void project(double[] power, double sampleRate, float[] raw) {
        double binHz = sampleRate / FFT_SIZE;
        for (int i = 0; i < SIZE; i++) {
            if (HZ[i] > sampleRate * .5) { raw[i] = FLOOR_DB; continue; }
            int lo = Math.max(1, Math.min(power.length - 1, (int) Math.floor(HZ[i] * R_LO / binHz)));
            int hi = Math.max(lo, Math.min(power.length - 1, (int) Math.ceil(HZ[i] * R_HI / binHz)));
            double sum = 0;
            for (int k = lo; k <= hi; k++) sum += power[k];
            raw[i] = (float) (10 * Math.log10(Math.max(sum / (hi - lo + 1), 1e-12)));
        }
    }

    static double smoothed(float[] raw, int i, double rate) {
        if (HZ[i] > rate * .5) return FLOOR_DB;
        int lo = Math.max(0, i - SMOOTH_RADIUS), hi = Math.min(SIZE - 1, i + SMOOTH_RADIUS);
        double sum = 0; int count = 0;
        for (int j = lo; j <= hi; j++) if (HZ[j] <= rate * .5) { sum += raw[j]; count++; }
        return count > 0 ? sum / count : FLOOR_DB;
    }

    static double at(float[] db, double hz, double rate) {
        if (!Double.isFinite(hz)) throw new IllegalArgumentException("Frequency must be finite.");
        if (hz > rate * .5) return FLOOR_DB;
        double index = Math.log10(Math.max(hz, MIN_HZ) / MIN_HZ) / SPAN * (SIZE - 1);
        int lo = (int) Math.floor(index);
        if (lo < 0) return db[0];
        if (lo >= SIZE - 1) return db[SIZE - 1];
        double fraction = index - lo;
        return db[lo] * (1 - fraction) + db[lo + 1] * fraction;
    }

    static float[] window() {
        float[] window = new float[FFT_SIZE];
        for (int i = 0; i < window.length; i++)
            window[i] = (float) (.5 - .5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
        return window;
    }
}
