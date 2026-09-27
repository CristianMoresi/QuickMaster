package com.dspark.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation tests for {@link LoudnessMeter}, anchored to the EBU R128 /
 * BS.1770 reference behaviour.
 */
class LoudnessMeterTest
{
    private static final double SR = 48000.0;

    /** Stereo 1 kHz sine at the given dBFS, {@code seconds} long. */
    private static float[] sine(double dbfs, double seconds)
    {
        int frames = (int) (SR * seconds);
        float amp = (float) Math.pow(10.0, dbfs / 20.0);
        float[] buf = new float[frames * 2];
        double step = 2.0 * Math.PI * 1000.0 / SR;
        for (int n = 0; n < frames; n++)
        {
            float s = (float) (amp * Math.sin(step * n));
            buf[2 * n] = s;
            buf[2 * n + 1] = s;
        }
        return buf;
    }

    @Test
    @DisplayName("A -23 dBFS 1 kHz sine reads close to -23 LUFS (EBU reference)")
    void referenceLevel()
    {
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(SR);
        m.process(sine(-23.0, 4.0), 2);

        // Exact BS.1770-5 K-weighting: well inside the EBU Tech 3341
        // +-0.1 LU tolerance (plus the 0.05 LU histogram bin width).
        assertEquals(-23.0, m.getIntegratedLufs(), 0.15);
        assertEquals(-23.0, m.getMomentaryLufs(), 0.15);
        assertEquals(-23.0, m.getShortTermLufs(), 0.15);
    }

    @Test
    @DisplayName("The meter is a no-op before prepare (no miscalibrated readings)")
    void noOpBeforePrepare()
    {
        LoudnessMeter m = new LoudnessMeter();
        m.process(sine(-23.0, 1.0), 2);
        assertEquals(LoudnessMeter.SILENCE_LUFS, m.getIntegratedLufs(), 0.0);
    }

    @Test
    @DisplayName("A NaN block is dropped and measurement resumes clean")
    void nanRecovery()
    {
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(SR);
        m.process(sine(-23.0, 2.0), 2);

        float[] poison = sine(-23.0, 0.5);
        poison[100] = Float.NaN;
        m.process(poison, 2);

        m.process(sine(-23.0, 2.0), 2);
        double momentary = m.getMomentaryLufs();
        assertTrue(Double.isFinite(momentary), "momentary must recover after a NaN block");
        assertEquals(-23.0, momentary, 0.2);
        assertEquals(-23.0, m.getIntegratedLufs(), 0.2);
    }

    @Test
    @DisplayName("Integrated loudness tracks level changes (10 dB louder = ~10 LU)")
    void monotonicWithLevel()
    {
        LoudnessMeter quiet = new LoudnessMeter();
        quiet.prepare(SR);
        quiet.process(sine(-33.0, 3.0), 2);

        LoudnessMeter loud = new LoudnessMeter();
        loud.prepare(SR);
        loud.process(sine(-23.0, 3.0), 2);

        assertEquals(10.0, loud.getIntegratedLufs() - quiet.getIntegratedLufs(), 0.2);
    }

    @Test
    @DisplayName("A steady level has a near-zero loudness range")
    void steadyLevelHasSmallLra()
    {
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(SR);
        m.process(sine(-23.0, 8.0), 2);
        assertTrue(m.getLoudnessRange() < 1.0, "LRA should be ~0 for a steady tone");
    }

    @Test
    @DisplayName("EBU Tech 3341 case-3 style gating: quiet flanks are gated out")
    void gatingVector()
    {
        // 10 s at -36, 60 s at -23, 10 s at -36 (997 Hz stereo): the relative
        // gate must drop the -36 flanks; expected -23.0 +- 0.1 (case 3).
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(SR);
        m.process(sine997(-36.0, 10.0), 2);
        m.process(sine997(-23.0, 60.0), 2);
        m.process(sine997(-36.0, 10.0), 2);
        assertEquals(-23.0, m.getIntegratedLufs(), 0.1);
    }

    @Test
    @DisplayName("EBU Tech 3342 case-1 style LRA: 20s@-20 + 20s@-30 = 10 LU")
    void lraVector()
    {
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(SR);
        m.process(sine997(-20.0, 20.0), 2);
        m.process(sine997(-30.0, 20.0), 2);
        assertEquals(10.0, m.getLoudnessRange(), 1.0);
    }

    /** Stereo 997 Hz sine (the EBU reference frequency) at the given dBFS. */
    private static float[] sine997(double dbfs, double seconds)
    {
        int frames = (int) (SR * seconds);
        float amp = (float) Math.pow(10.0, dbfs / 20.0);
        float[] buf = new float[frames * 2];
        double step = 2.0 * Math.PI * 997.0 / SR;
        for (int n = 0; n < frames; n++)
        {
            float s = (float) (amp * Math.sin(step * n));
            buf[2 * n] = s;
            buf[2 * n + 1] = s;
        }
        return buf;
    }

    @Test
    @DisplayName("Silence reads the silence floor")
    void silence()
    {
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(SR);
        m.process(new float[(int) SR * 2], 2);   // 1 s of stereo silence
        assertEquals(LoudnessMeter.SILENCE_LUFS, m.getIntegratedLufs(), 0.0);
    }

    @Test
    @DisplayName("Offline convenience matches an instance measurement")
    void offlineConvenience()
    {
        float[] buf = sine(-20.0, 3.0);
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(SR);
        m.process(buf, 2);
        assertEquals(m.getIntegratedLufs(),
                LoudnessMeter.measureIntegrated(buf, SR, 2), 1e-9);
    }
}
