package com.dspark.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Saturation}. */
class SaturationTest
{
    private static final int SR = 48000;

    private static float[] sineStereo(double hz, double amp, int frames)
    {
        float[] b = new float[frames * 2];
        for (int i = 0; i < frames; i++)
        {
            float s = (float) (amp * Math.sin(2.0 * Math.PI * hz * i / SR));
            b[2 * i] = s;
            b[2 * i + 1] = s;
        }
        return b;
    }

    private static float peak(float[] b)
    {
        float p = 0f;
        for (float v : b) p = Math.max(p, Math.abs(v));
        return p;
    }

    /** Magnitude of the harmonic at {@code mult}×f in the left channel, over an integer
     *  number of fundamental periods (no spectral leakage), skipping the settling head. */
    private static double harmonic(float[] b, double f, int mult)
    {
        int n = b.length / 2;
        int period = (int) Math.round(SR / f);
        int start = 2048;
        int count = ((n - start) / period) * period;
        double re = 0, im = 0;
        for (int i = start; i < start + count; i++)
        {
            double ph = mult * 2.0 * Math.PI * f * i / SR;
            re += b[2 * i] * Math.cos(ph);
            im += b[2 * i] * Math.sin(ph);
        }
        return 2.0 * Math.sqrt(re * re + im * im) / count;
    }

    @Test
    void transparentBelowKnee()
    {
        Saturation s = new Saturation();
        s.prepare(SR, 2);
        s.setAlgorithm(Saturation.Algorithm.TAPE);
        s.setCeiling(1.0);
        float[] b = sineStereo(1000.0, 0.5, 4096);
        s.process(b, 2);
        assertTrue(Math.abs(peak(b) - 0.5f) < 0.01f, "below-knee peak should be preserved, peak=" + peak(b));
        assertTrue(s.getGainReductionDb() > -0.1, "below-knee should be transparent, GR=" + s.getGainReductionDb());
    }

    @Test
    void clipsReducePeak()
    {
        Saturation s = new Saturation();
        s.prepare(SR, 2);
        s.setAlgorithm(Saturation.Algorithm.TAPE);
        s.setCeiling(0.85);
        float[] b = sineStereo(100.0, 0.95, 4096);   // gentle, master-style clip
        s.process(b, 2);
        assertTrue(peak(b) < 0.9f, "peak should be reduced from 0.95, peak=" + peak(b));
        assertTrue(s.getGainReductionDb() < -0.5, "GR should be negative, GR=" + s.getGainReductionDb());
    }

    @Test
    void staysNearCeiling()
    {
        // At base rate, 1st-order ADAA leaves a little residual aliasing — the output
        // sits at the ceiling within a few percent (the host clamps for exactness).
        Saturation s = new Saturation();
        s.prepare(SR, 2);
        s.setAlgorithm(Saturation.Algorithm.TAPE);
        s.setCeiling(0.6);
        float[] b = sineStereo(100.0, 0.9, 4096);
        s.process(b, 2);
        assertTrue(peak(b) <= 0.6f * 1.06f, "output should sit ~at the ceiling, peak=" + peak(b));
    }

    @Test
    void tubeAddsEvenHarmonics()
    {
        float[] tube = sineStereo(100.0, 0.9, 8192);
        Saturation st = new Saturation();
        st.prepare(SR, 2);
        st.setAlgorithm(Saturation.Algorithm.TUBE);
        st.setCeiling(0.6);
        st.process(tube, 2);

        float[] tape = sineStereo(100.0, 0.9, 8192);
        Saturation sp = new Saturation();
        sp.prepare(SR, 2);
        sp.setAlgorithm(Saturation.Algorithm.TAPE);
        sp.setCeiling(0.6);
        sp.process(tape, 2);

        double tubeH2 = harmonic(tube, 100.0, 2);
        double tapeH2 = harmonic(tape, 100.0, 2);
        assertTrue(tubeH2 > 0.008, "Tube should generate a 2nd harmonic, h2=" + tubeH2);
        assertTrue(tubeH2 > 3.0 * tapeH2, "Tube's 2nd harmonic should dwarf Tape's (" + tubeH2 + " vs " + tapeH2 + ")");
    }

    @Test
    void driftDecorrelatesChannels()
    {
        Saturation s = new Saturation();
        s.prepare(SR, 2);
        s.setAlgorithm(Saturation.Algorithm.TUBE);
        s.setCeiling(0.6);
        s.setStereoAmount(1.0);
        float[] b = sineStereo(220.0, 0.9, 16384);
        s.process(b, 2);
        double maxDiff = 0.0;
        for (int i = 0; i < 16384; i++) maxDiff = Math.max(maxDiff, Math.abs(b[2 * i] - b[2 * i + 1]));
        assertTrue(maxDiff > 1e-5, "drift should decorrelate L and R, diff=" + maxDiff);
    }
}
