package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link WindowFunctions}. */
class WindowFunctionsTest
{
    @Test
    @DisplayName("Hann window starts near 0, peaks near 1 in the middle")
    void hannShape()
    {
        int size = 1024;
        double[] w = new double[size];
        WindowFunctions.hann(w, size);
        assertEquals(0.0, w[0], 1e-9);
        assertTrue(w[size / 2] > 0.99);
    }

    @Test
    @DisplayName("Hann coherent gain is approximately 0.5")
    void hannCoherentGain()
    {
        int size = 4096;
        double[] w = new double[size];
        WindowFunctions.hann(w, size);
        assertEquals(0.5, WindowFunctions.coherentGain(w, size), 1e-3);
    }

    @Test
    @DisplayName("Blackman-Harris stays within [0, 1]")
    void blackmanHarrisBounds()
    {
        int size = 512;
        double[] w = new double[size];
        WindowFunctions.blackmanHarris(w, size);
        for (double v : w) assertTrue(v >= -1e-6 && v <= 1.0 + 1e-6);
    }
}
