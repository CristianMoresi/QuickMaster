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

    @Test
    @DisplayName("The asymmetric Hann rises over its long part, peaks a fall from the end and falls over the short one")
    void asymmetricHannShape()
    {
        int size = 2048, fall = 256;
        double[] w = new double[size];
        WindowFunctions.asymmetricHann(w, size, fall);
        int peak = size - fall;
        assertEquals(0.0, w[0], 0.0);
        assertEquals(1.0, w[peak], 0.0);
        for (int i = 1; i <= peak; i++) assertTrue(w[i] > w[i - 1], "not rising at " + i);
        for (int i = peak + 1; i < size; i++) assertTrue(w[i] < w[i - 1], "not falling at " + i);
        assertTrue(w[size - 1] > 0.0 && w[size - 1] < 1e-3, "the last sample: " + w[size - 1]);
        // Each part is half a Hann window of twice its length.
        assertEquals(0.5, w[peak / 2], 1e-12);
        assertEquals(0.5, w[peak + fall / 2], 1e-12);
    }

    @Test
    @DisplayName("The asymmetric Hann clamps its fall into the window")
    void asymmetricHannClamps()
    {
        double[] w = new double[8];
        WindowFunctions.asymmetricHann(w, 8, 100);
        assertEquals(0.0, w[0], 0.0);
        assertEquals(1.0, w[1], 0.0, "a fall of size - 1 leaves a rise of one sample");
        WindowFunctions.asymmetricHann(w, 8, 0);
        assertEquals(1.0, w[7], 0.0, "a fall of one sample");
        double[] one = new double[1];
        WindowFunctions.asymmetricHann(one, 1, 1);
        assertEquals(1.0, one[0], 0.0);
    }
}
