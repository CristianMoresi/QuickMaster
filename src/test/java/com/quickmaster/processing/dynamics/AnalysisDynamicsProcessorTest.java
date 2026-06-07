package com.quickmaster.processing.dynamics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AnalysisDynamicsProcessor} (envelope application + rate independence). */
class AnalysisDynamicsProcessorTest
{
    /** Concrete subclass that builds a linear gain ramp the length of the audio. */
    static final class RampDynamics extends AnalysisDynamicsProcessor
    {
        private final float g0, g1;
        RampDynamics(float g0, float g1) { this.g0 = g0; this.g1 = g1; setEnabled(true); }

        @Override protected void computeFeatures(float[] s, int ch, int sr, int frames) { }

        @Override protected void mapFeaturesToGain()
        {
            float[] e = new float[envFrames];
            for (int i = 0; i < envFrames; i++)
            {
                float t = (envFrames <= 1) ? 0f : (float) i / (envFrames - 1);
                e[i] = g0 + (g1 - g0) * t;
            }
            gainEnv = e;
        }
    }

    private static float[] ones(int n) { float[] a = new float[n]; java.util.Arrays.fill(a, 1.0f); return a; }

    @Test
    @DisplayName("Constant 0.5 gain halves the signal")
    void constantGainHalves()
    {
        RampDynamics p = new RampDynamics(0.5f, 0.5f);
        p.prepare(1000, 100);
        p.analyze(new float[100], 1);
        p.prepare(1000, 100);
        float[] out = p.process(ones(100), 1);
        for (float v : out) assertEquals(0.5f, v, 1e-4f);
    }

    @Test
    @DisplayName("Disabled stage is a passthrough")
    void disabledPassthrough()
    {
        RampDynamics p = new RampDynamics(0.5f, 0.5f);
        p.prepare(1000, 100);
        p.analyze(new float[100], 1);
        p.setEnabled(false);
        p.prepare(1000, 100);
        float[] out = p.process(ones(100), 1);
        for (float v : out) assertEquals(1.0f, v, 1e-6f);
    }

    @Test
    @DisplayName("prepare() resets position but keeps the analysed envelope")
    void prepareKeepsEnvelope()
    {
        RampDynamics p = new RampDynamics(0.5f, 0.5f);
        p.prepare(1000, 100);
        p.analyze(new float[100], 1);
        assertTrue(p.isAnalyzed());
        p.prepare(1000, 100);                 // re-prepare (as the oversampled path does)
        assertTrue(p.isAnalyzed(), "envelope must survive prepare()");
        float[] out = p.process(ones(10), 1);
        assertEquals(0.5f, out[0], 1e-4f);
    }

    @Test
    @DisplayName("Envelope is applied by time, so it is rate-invariant (oversampling-safe)")
    void rateInvariantByTime()
    {
        // Base rate.
        RampDynamics base = new RampDynamics(0.2f, 0.9f);
        base.prepare(1000, 1000);
        base.analyze(new float[1000], 1);     // envRate = 1000, ramp over 1000 frames
        base.prepare(1000, 1000);
        float[] outBase = base.process(ones(1000), 1);
        float gAtFrame100 = outBase[100];     // time = 0.1 s

        // 2x rate: the same time (0.1 s) is frame 200.
        RampDynamics os = new RampDynamics(0.2f, 0.9f);
        os.prepare(1000, 1000);
        os.analyze(new float[1000], 1);       // envRate stays 1000 (analysed at base)
        os.prepare(2000, 2000);               // oversampled re-prepare
        float[] outOs = os.process(ones(400), 1);
        float gAtTime0p1 = outOs[200];        // frame 200 @ 2000 Hz = 0.1 s

        assertEquals(gAtFrame100, gAtTime0p1, 1e-3f);
    }

    @Test
    @DisplayName("getGainReductionDb reports the applied reduction")
    void reportsGainReduction()
    {
        RampDynamics p = new RampDynamics(0.5f, 0.5f);
        p.prepare(1000, 100);
        p.analyze(new float[100], 1);
        p.prepare(1000, 100);
        p.process(ones(100), 1);
        assertEquals(-6.02, p.getGainReductionDb(), 0.1);   // 20*log10(0.5)
    }
}
