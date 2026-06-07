package com.quickmaster.processing.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SpectralEngine} (STFT WOLA: reconstruction, scaling, selectivity). */
class SpectralEngineTest
{
    private static final int SR = 48000;
    private final SpectralEngine eng = new SpectralEngine(1024, 256);

    private static float[] sine(double hz, int n, double amp)
    {
        float[] x = new float[n];
        double step = 2.0 * Math.PI * hz / SR;
        for (int i = 0; i < n; i++) x[i] = (float) (amp * Math.sin(step * i));
        return x;
    }

    private static double rms(float[] b, int from, int to)
    {
        double s = 0; for (int i = from; i < to; i++) s += (double) b[i] * b[i];
        return Math.sqrt(s / (to - from));
    }

    private static float maxAbs(float[] b, int from, int to)
    {
        float m = 0; for (int i = from; i < to; i++) m = Math.max(m, Math.abs(b[i]));
        return m;
    }

    @Test
    @DisplayName("Identity gain reconstructs the input exactly")
    void reconstructsIdentity()
    {
        int n = 24000;
        float[] x = sine(440, n, 0.6);
        for (int i = 0; i < n; i++) x[i] += (float) (0.3 * Math.sin(2 * Math.PI * 3000.0 / SR * i));
        float[] out = eng.render(x, (freq, idx) -> { });
        for (int i = 4000; i < n - 4000; i++) assertEquals(x[i], out[i], 1e-3f);
    }

    @Test
    @DisplayName("A flat bin gain scales the signal")
    void flatGainScales()
    {
        int n = 24000;
        float[] x = sine(500, n, 0.7);
        float[] out = eng.render(x, (freq, idx) ->
        {
            for (int k = 0; k < freq.length; k++) freq[k] *= 0.5f;
        });
        for (int i = 4000; i < n - 4000; i++) assertEquals(0.5f * x[i], out[i], 2e-3f);
    }

    @Test
    @DisplayName("Zeroing high bins removes a high tone but passes a low one")
    void frequencySelective()
    {
        int n = 24000, nb = eng.getNumBins();
        int c = 0;
        while (c < nb && eng.binToHz(c, SR) <= 1000.0) c++;
        final int cut = c;
        SpectralEngine.BinGain lowpass = (freq, idx) ->
        {
            for (int k = cut; k < nb; k++) { freq[2 * k] = 0; freq[2 * k + 1] = 0; }
        };
        float[] high = eng.render(sine(6000, n, 0.6), lowpass);
        assertTrue(maxAbs(high, 4000, n - 4000) < 0.06f, "high tone not removed: " + maxAbs(high, 4000, n - 4000));

        float[] lowIn = sine(200, n, 0.6);
        float[] low = eng.render(lowIn, lowpass);
        double ratio = rms(low, 4000, n - 4000) / rms(lowIn, 4000, n - 4000);
        assertTrue(ratio > 0.8 && ratio < 1.2, "low tone should pass, ratio=" + ratio);
    }
}
