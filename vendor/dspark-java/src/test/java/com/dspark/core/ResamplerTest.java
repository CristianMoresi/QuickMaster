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
    @DisplayName("Common audio rates resolve to an exact polyphase table (no phase-interp images)")
    void commonRatesAreExactPolyphase()
    {
        Resampler r = new Resampler();
        // Every standard conversion QuickMaster exports to is rational and exact.
        r.prepare(44100, 48000, Resampler.Quality.ULTRA);
        assertTrue(r.isExact(), "44.1 -> 48 must be exact (160/147)");
        r.prepare(48000, 44100, Resampler.Quality.ULTRA);
        assertTrue(r.isExact(), "48 -> 44.1 must be exact (147/160)");
        r.prepare(96000, 48000, Resampler.Quality.ULTRA);
        assertTrue(r.isExact(), "96 -> 48 must be exact (1/2)");
        assertEquals(0.5, r.getRatio(), 0.0);
        r.prepare(88200, 44100, Resampler.Quality.ULTRA);
        assertTrue(r.isExact(), "88.2 -> 44.1 must be exact (1/2)");
    }

    @Test
    @DisplayName("44.1k to 48k (exact polyphase) is sample-accurate against the ideal sine")
    void conversionIsTimeAlignedAndClean()
    {
        // Exact polyphase has no phase-interpolation error: out[k] matches the
        // source sine evaluated at k / ratio to near the float floor (the old
        // interpolated-table design erred by ~6e-2 with a reversed-frac kernel
        // and ~1e-4 with linear phase interpolation).
        Resampler r = new Resampler();
        r.prepare(44100, 48000, Resampler.Quality.ULTRA);
        assertTrue(r.isExact());
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
        assertTrue(maxErr < 1e-4, "max error vs ideal sine was " + maxErr);
    }

    @Test
    @DisplayName("Normal/High/Ultra keep 20 kHz on 44.1 to 48; Draft trades the top for speed")
    void passbandHoldsTwentyKhz()
    {
        // Passband edge = tier fraction * lower Nyquist (22050 Hz). Normal
        // (19845 Hz), High (20066 Hz) and Ultra (20176 Hz) all keep 20 kHz
        // essentially flat (the cutoff, the -6 dB point, sits even higher);
        // only Draft (passband 17640 Hz) places 20 kHz past its cutoff.
        double ultra = droopDb(Resampler.Quality.ULTRA);
        double high  = droopDb(Resampler.Quality.HIGH);
        double normal = droopDb(Resampler.Quality.NORMAL);
        double draft = droopDb(Resampler.Quality.DRAFT);

        assertTrue(Math.abs(ultra) < 0.2, "Ultra 20 kHz should be flat, was " + ultra + " dB");
        assertTrue(Math.abs(high) < 0.2, "High 20 kHz should be flat, was " + high + " dB");
        assertTrue(Math.abs(normal) < 0.3, "Normal 20 kHz should be near-flat, was " + normal + " dB");
        assertTrue(draft < -1.0 && draft < high - 0.5,
                "Draft should trade the top: draft=" + draft + " dB, high=" + high + " dB");
    }

    private static double droopDb(Resampler.Quality q)
    {
        Resampler r = new Resampler();
        r.prepare(44100, 48000, q);
        int n = 44100 * 2;
        float[] in = new float[n];
        for (int i = 0; i < n; i++) in[i] = (float) Math.sin(2.0 * Math.PI * 20000.0 * i / 44100.0);
        float[] out = r.process(in);
        double amp = goertzel(out, out.length / 4, 3 * out.length / 4, 20000.0, 48000.0);
        return 20.0 * Math.log10(amp);
    }

    @Test
    @DisplayName("Content above the target Nyquist is rejected on downsample (Ultra)")
    void stopbandRejectsAboveNyquist()
    {
        // 48 kHz source, a 23 kHz tone (above the 22.05 kHz target Nyquist):
        // the designed stopband starts at the target Nyquist, so the tone must
        // be strongly attenuated rather than aliased back into the 44.1k band.
        Resampler r = new Resampler();
        r.prepare(48000, 44100, Resampler.Quality.ULTRA);
        int n = 48000 * 2;
        float[] in = new float[n];
        for (int i = 0; i < n; i++) in[i] = (float) Math.sin(2.0 * Math.PI * 23000.0 * i / 48000.0);
        float[] out = r.process(in);

        double peak = 0.0;
        for (int i = out.length / 4; i < 3 * out.length / 4; i++) peak = Math.max(peak, Math.abs(out[i]));
        double db = 20.0 * Math.log10(peak + 1e-30);
        assertTrue(db < -80.0, "out-of-band tone should be rejected below -80 dB, was " + db + " dB");
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
