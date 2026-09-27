package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Resampler}. */
class ResamplerTest
{
    @Test
    @DisplayName("A constant (DC) signal keeps its level after resampling")
    void dcGainIsUnity()
    {
        Resampler r = new Resampler();
        r.prepare(48000, 96000, Resampler.Quality.NORMAL);
        float[] in = new float[1024];
        java.util.Arrays.fill(in, 0.5f);
        float[] out = r.process(in);
        // Check the middle, away from the sinc edge transients.
        assertEquals(0.5, out[out.length / 2], 0.01);
    }

    @Test
    @DisplayName("Upsampling 2x roughly doubles the length")
    void upsampleLength()
    {
        Resampler r = new Resampler();
        r.prepare(48000, 96000, Resampler.Quality.NORMAL);
        float[] out = r.process(new float[1000]);
        assertEquals(2000, out.length, 2);
    }

    @Test
    @DisplayName("A sine keeps its amplitude through 2x upsampling")
    void sineAmplitudePreserved()
    {
        Resampler r = new Resampler();
        r.prepare(48000, 96000, Resampler.Quality.HIGH);
        int n = 4800;
        float[] in = new float[n];
        double step = 2.0 * Math.PI * 1000.0 / 48000.0;
        for (int i = 0; i < n; i++) in[i] = (float) (0.5 * Math.sin(step * i));
        float[] out = r.process(in);

        double mid = 0.0;
        for (int i = out.length / 4; i < 3 * out.length / 4; i++) mid = Math.max(mid, Math.abs(out[i]));
        assertEquals(0.5, mid, 0.03);
    }

    @Test
    @DisplayName("44.1k to 48k conversion is sample-accurate against the ideal sine")
    void conversionIsTimeAlignedAndClean()
    {
        // Exercises every polyphase fraction (non-trivial ratio). The batch
        // path is time-aligned: out[k] must match the source sine evaluated
        // at k / ratio, with no sub-sample jitter (a reversed-frac kernel
        // reads at intPos - frac and errs by ~6e-2 here).
        Resampler r = new Resampler();
        r.prepare(44100, 48000, Resampler.Quality.ULTRA);
        int n = 44100;
        float[] in = new float[n];
        double step = 2.0 * Math.PI * 1000.0 / 44100.0;
        for (int i = 0; i < n; i++) in[i] = (float) (0.5 * Math.sin(step * i));
        float[] out = r.process(in);

        double maxErr = 0.0;
        double outStep = 2.0 * Math.PI * 1000.0 / 48000.0;
        for (int k = out.length / 4; k < 3 * out.length / 4; k++)
        {
            double ideal = 0.5 * Math.sin(outStep * k);
            maxErr = Math.max(maxErr, Math.abs(out[k] - ideal));
        }
        assertTrue(maxErr < 1e-3, "max error vs ideal sine was " + maxErr);
    }

    @Test
    @DisplayName("Quality tiers match the documented 20 kHz droop (44.1 to 48)")
    void qualityTiersMatchDocumentedDroop()
    {
        // Reference table (same as the C++ Resampler docs): Draft -3.3 dB,
        // Normal -0.6 dB, High -0.02 dB, Ultra flat at 20 kHz for 44.1->48.
        assertDroop(Resampler.Quality.DRAFT, -3.3, 0.4);
        assertDroop(Resampler.Quality.NORMAL, -0.6, 0.2);
        assertDroop(Resampler.Quality.HIGH, -0.02, 0.1);
        assertDroop(Resampler.Quality.ULTRA, 0.0, 0.05);
    }

    private static void assertDroop(Resampler.Quality q, double wantDb, double tol)
    {
        Resampler r = new Resampler();
        r.prepare(44100, 48000, q);
        int n = 44100 * 2;
        float[] in = new float[n];
        for (int i = 0; i < n; i++) in[i] = (float) Math.sin(2.0 * Math.PI * 20000.0 * i / 44100.0);
        float[] out = r.process(in);
        double amp = goertzel(out, out.length / 4, 3 * out.length / 4, 20000.0, 48000.0);
        double gotDb = 20.0 * Math.log10(amp);
        assertEquals(wantDb, gotDb, tol, q + " 20 kHz gain");
    }

    /** Goertzel amplitude of a real tone at {@code freq} over x[from..to). */
    private static double goertzel(float[] x, int from, int to, double freq, double sr)
    {
        double w = 2.0 * Math.PI * freq / sr;
        double c = 2.0 * Math.cos(w);
        double s1 = 0.0, s2 = 0.0;
        for (int i = from; i < to; i++)
        {
            double s0 = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double re = s1 - s2 * Math.cos(w);
        double im = s2 * Math.sin(w);
        return 2.0 * Math.hypot(re, im) / (to - from);
    }

    @Test
    @DisplayName("Invalid prepare keeps the previous state")
    void invalidPrepareKeepsState()
    {
        Resampler r = new Resampler();
        r.prepare(48000, 96000, Resampler.Quality.NORMAL);
        r.prepare(Double.NaN, 96000, Resampler.Quality.NORMAL);
        r.prepare(48000, Double.POSITIVE_INFINITY, Resampler.Quality.NORMAL);
        assertEquals(2.0, r.getRatio(), 0.0, "invalid prepare must not change the ratio");
        float[] out = r.process(new float[100]);
        assertEquals(200, out.length, 2);
    }

    @Test
    @DisplayName("Interleaved stereo resampling preserves channel count")
    void interleavedStereo()
    {
        Resampler r = new Resampler();
        r.prepare(44100, 22050, Resampler.Quality.NORMAL);
        float[] in = new float[2000];          // 1000 stereo frames
        float[] out = r.resampleInterleaved(in, 2);
        assertTrue(out.length % 2 == 0);
        assertEquals(500, out.length / 2, 2);   // 44100 -> 22050 halves the frames
    }
}
