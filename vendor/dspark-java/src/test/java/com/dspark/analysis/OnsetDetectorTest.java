package com.dspark.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link OnsetDetector}. */
class OnsetDetectorTest
{
    private static final int SR = 44100;

    /** Builds a mono click train: sharp broadband bursts at a fixed interval. */
    static float[] clickTrain(int sr, double durSec, double intervalSec, double offsetSec)
    {
        int n = (int) (sr * durSec);
        float[] x = new float[n];
        Random rnd = new Random(42);
        int burst = (int) (0.008 * sr);             // 8 ms burst
        double tau = 0.003 * sr;                     // 3 ms decay
        for (double t = offsetSec; t < durSec; t += intervalSec)
        {
            int start = (int) (t * sr);
            for (int j = 0; j < burst && start + j < n; j++)
            {
                double decay = Math.exp(-j / tau);
                x[start + j] += (float) ((rnd.nextDouble() * 2 - 1) * decay * 0.9);
            }
        }
        return x;
    }

    @Test
    @DisplayName("Detects evenly spaced clicks at their true positions")
    void detectsClickPositions()
    {
        double interval = 0.5, offset = 0.1, dur = 6.0;
        float[] x = clickTrain(SR, dur, interval, offset);

        OnsetDetector det = new OnsetDetector();
        det.analyze(x, 1, SR);

        // Expected click times.
        int expected = 0;
        for (double t = offset; t < dur; t += interval) expected++;

        int count = det.getOnsetCount();
        assertTrue(count >= expected - 1 && count <= expected + 2,
                "onset count " + count + " not near expected " + expected);

        // Every true click should have a detected onset within 40 ms.
        double[] times = det.getOnsetTimesSec();
        for (double t = offset; t < dur; t += interval)
        {
            boolean matched = false;
            for (double ot : times)
            {
                if (Math.abs(ot - t) <= 0.040) { matched = true; break; }
            }
            assertTrue(matched, "no onset near click at t=" + t);
        }
    }

    @Test
    @DisplayName("Silence yields no onsets and a zero-energy ODF")
    void silenceHasNoOnsets()
    {
        float[] x = new float[SR * 2];               // 2 s of silence
        OnsetDetector det = new OnsetDetector();
        det.analyze(x, 1, SR);
        assertEquals(0, det.getOnsetCount());
    }

    @Test
    @DisplayName("ODF frame rate equals sampleRate / hop")
    void frameRate()
    {
        float[] x = clickTrain(SR, 3.0, 0.5, 0.1);
        OnsetDetector det = new OnsetDetector(1024, 512);
        det.analyze(x, 1, SR);
        assertEquals((double) SR / 512, det.getFrameRate(), 1e-9);
    }
}
