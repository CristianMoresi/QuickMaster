package com.quickmaster.ui;

import java.util.function.DoubleUnaryOperator;

/** Display-only spectrum projection. Never feeds EQ, Auto EQ or metering.
 * The 4.5 dB/octave slope and 1 kHz pivot match Pro-Q 4's analyzer defaults.
 * Source: https://www.fabfilter.com/downloads/pdf/help/ffproq4-manual.pdf */
final class SpectrumDisplay {
    static final double TILT_DB_PER_OCTAVE = 4.5;
    static final double PIVOT_HZ = 1000.0;
    private static final double FLOOR_DB = -120.0;
    private static final double RANGE_DB = 80.0;
    private static final double HEADROOM_DB = 6.0;
    private double topDb = Double.NaN;

    static double tiltedDb(double frequencyHz, double measuredDb) {
        // Keep silence and bins above Nyquist at the floor, not a rising line.
        if (!Double.isFinite(measuredDb) || measuredDb <= FLOOR_DB) return FLOOR_DB;
        return Math.max(FLOOR_DB, measuredDb
                + TILT_DB_PER_OCTAVE * Math.log(frequencyHz / PIVOT_HZ) / Math.log(2));
    }

    float[] project(double[] frequencies, int count, double height,
                    DoubleUnaryOperator measuredDb, boolean live) {
        float[] y = new float[count];
        double peak = FLOOR_DB;
        for (int k = 0; k < count; k++) {
            double db = tiltedDb(frequencies[k], measuredDb.applyAsDouble(frequencies[k]));
            y[k] = (float) db;
            peak = Math.max(peak, db);
        }
        // Auto-ranging must follow the tilted spectrum, not its unweighted peak.
        // Match the existing live display's slow release (0.05 dB per UI tick).
        double target = peak + HEADROOM_DB;
        topDb = live && Double.isFinite(topDb) ? Math.max(target, topDb - .05) : target;
        for (int k = 0; k < count; k++)
            y[k] = y[k] <= FLOOR_DB ? (float) height
                    : (float) Math.max(0, Math.min(height, height * (topDb - y[k]) / RANGE_DB));
        return y;
    }
}
