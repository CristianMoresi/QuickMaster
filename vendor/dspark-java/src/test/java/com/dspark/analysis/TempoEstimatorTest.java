package com.dspark.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link TempoEstimator}. */
class TempoEstimatorTest
{
    private static final int SR = 44100;

    /** True iff {@code bpm} matches {@code target} (or an octave of it) within tol. */
    private static boolean matchesOctave(double bpm, double target, double tol)
    {
        return Math.abs(bpm - target) <= tol
            || Math.abs(bpm - 2 * target) <= tol
            || Math.abs(bpm - target / 2) <= tol;
    }

    private static double detect(double targetBpm)
    {
        double interval = 60.0 / targetBpm;
        float[] x = OnsetDetectorTest.clickTrain(SR, 8.0, interval, 0.1);
        OnsetDetector det = new OnsetDetector();
        det.analyze(x, 1, SR);
        TempoEstimator est = new TempoEstimator();
        est.estimate(det);
        return est.getBpm();
    }

    @Test
    @DisplayName("Detects 120 BPM from a click train")
    void detects120()
    {
        assertTrue(matchesOctave(detect(120.0), 120.0, 3.0));
    }

    @Test
    @DisplayName("Detects 100 BPM from a click train")
    void detects100()
    {
        assertTrue(matchesOctave(detect(100.0), 100.0, 3.0));
    }

    @Test
    @DisplayName("Detects 140 BPM from a click train")
    void detects140()
    {
        assertTrue(matchesOctave(detect(140.0), 140.0, 3.0));
    }

    @Test
    @DisplayName("Clean click train yields non-trivial confidence")
    void confidence()
    {
        double interval = 60.0 / 120.0;
        float[] x = OnsetDetectorTest.clickTrain(SR, 8.0, interval, 0.1);
        OnsetDetector det = new OnsetDetector();
        det.analyze(x, 1, SR);
        TempoEstimator est = new TempoEstimator();
        est.estimate(det);
        assertTrue(est.getConfidence() > 0.2, "confidence too low: " + est.getConfidence());
    }
}
