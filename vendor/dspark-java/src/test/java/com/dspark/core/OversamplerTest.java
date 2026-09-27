package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OversamplerTest
{
    @Test
    @DisplayName("Up then down round-trips a band-limited signal to near identity")
    void roundTripNearIdentity()
    {
        int n = 4096;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0));

        float[] up = Oversampler.upsample(x, 1, 4, Oversampler.Quality.HIGH);
        assertEquals(n * 4, up.length);
        float[] down = Oversampler.downsample(up, 1, 4, Oversampler.Quality.HIGH);
        assertEquals(n, down.length);

        double err = 0.0;
        int cnt = 0;
        for (int i = n / 4; i < 3 * n / 4; i++) { err += Math.abs(down[i] - x[i]); cnt++; }
        assertTrue(err / cnt < 0.02, "mean round-trip error " + (err / cnt));
    }

    @Test
    @DisplayName("Upsample lengthens by the factor and stays finite (8x, stereo)")
    void upsampleLengthAndFinite()
    {
        float[] x = new float[2000];                       // 1000 stereo frames
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(i * 0.05);
        float[] up = Oversampler.upsample(x, 2, 8, Oversampler.Quality.MEDIUM);
        assertEquals(x.length * 8, up.length);
        for (float v : up) assertTrue(Float.isFinite(v));
    }

    @Test
    @DisplayName("Factor 1 is a pass-through")
    void factorOnePassThrough()
    {
        float[] x = { 0.1f, -0.2f, 0.3f, -0.4f };
        assertArrayEquals(x, Oversampler.upsample(x, 2, 1, Oversampler.Quality.HIGH));
        assertArrayEquals(x, Oversampler.downsample(x, 2, 1, Oversampler.Quality.HIGH));
    }
}
