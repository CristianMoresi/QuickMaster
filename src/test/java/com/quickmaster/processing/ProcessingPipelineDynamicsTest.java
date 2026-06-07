package com.quickmaster.processing;
import com.quickmaster.processing.dynamics.AnalysisDynamicsProcessor;

import com.quickmaster.audio.WavFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for chaining {@link AnalysisDynamicsProcessor}s in a
 * {@link ProcessingPipeline}: each analysis-driven stage must see the
 * <i>post-upstream</i> signal, and reordering the stages must change the
 * result. A simple "peak gate" (halves the signal when its input peak exceeds
 * a threshold) makes the data flow observable without DSP numerics.
 */
class ProcessingPipelineDynamicsTest
{
    private static final int SR = 48000;

    /** Halves the whole signal iff its analysed input peak exceeds {@code thr}. */
    static final class PeakGate extends AnalysisDynamicsProcessor
    {
        private final double thr;
        private float gain = 1.0f;
        PeakGate(double thr) { this.thr = thr; setEnabled(true); }

        @Override protected void computeFeatures(float[] s, int ch, int sr, int frames)
        {
            float peak = 0.0f;
            for (float v : s) { float a = Math.abs(v); if (a > peak) peak = a; }
            gain = (peak > thr) ? 0.5f : 1.0f;
        }

        @Override protected void mapFeaturesToGain()
        {
            float[] e = new float[envFrames];
            java.util.Arrays.fill(e, gain);
            gainEnv = e;
        }
    }

    private static WavFile mem(float[] s) { return new WavFile("m.wav", SR, 1, s, 16, false); }
    private static float[] flat(int n, float v) { float[] s = new float[n]; java.util.Arrays.fill(s, v); return s; }
    private static float maxAbs(float[] b) { float m = 0; for (float v : b) m = Math.max(m, Math.abs(v)); return m; }

    @Test
    @DisplayName("A downstream analysis stage sees the upstream-processed signal")
    void secondSeesPostUpstream()
    {
        WavFile f = mem(flat(2000, 0.8f));
        ProcessingPipeline p = new ProcessingPipeline();
        p.addProcessor(new PeakGate(0.5));     // halves 0.8 -> 0.4
        p.addProcessor(new PeakGate(0.5));     // sees 0.4 (< 0.5) -> passes
        p.process(f);
        // If the second stage saw the raw 0.8 it would halve again to 0.2.
        assertEquals(0.4f, maxAbs(f.getSamples()), 1e-3f);
    }

    @Test
    @DisplayName("Reordering the stages changes the rendered result")
    void reorderChangesResult()
    {
        ProcessingPipeline p1 = new ProcessingPipeline();
        p1.addProcessor(new PeakGate(0.5));    // 0.8 -> 0.4
        p1.addProcessor(new PeakGate(0.3));    // 0.4 > 0.3 -> 0.2
        WavFile f1 = mem(flat(2000, 0.8f));
        p1.process(f1);
        assertEquals(0.2f, maxAbs(f1.getSamples()), 1e-3f);

        ProcessingPipeline p2 = new ProcessingPipeline();
        p2.addProcessor(new PeakGate(0.3));    // 0.8 -> 0.4
        p2.addProcessor(new PeakGate(0.5));    // 0.4 < 0.5 -> passes
        WavFile f2 = mem(flat(2000, 0.8f));
        p2.process(f2);
        assertEquals(0.4f, maxAbs(f2.getSamples()), 1e-3f);
    }
}
