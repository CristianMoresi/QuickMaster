package com.quickmaster.processing.clip;
import com.quickmaster.processing.ProcessingPipeline;

import com.dspark.effects.Saturation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link HardClipProcessor} and {@link SoftClipProcessor}. */
class ClipProcessorsTest
{
    private static final int SR = 48000;

    private static float[] sine(double hz, double amp, int frames, int ch)
    {
        float[] b = new float[frames * ch];
        for (int i = 0; i < frames; i++)
        {
            float s = (float) (amp * Math.sin(2.0 * Math.PI * hz * i / SR));
            for (int c = 0; c < ch; c++) b[i * ch + c] = s;
        }
        return b;
    }

    private static float peak(float[] b)
    {
        float p = 0f;
        for (float v : b) p = Math.max(p, Math.abs(v));
        return p;
    }

    @Test
    void hardClipReducesPeakByExactDb()
    {
        HardClipProcessor p = new HardClipProcessor();
        p.setEnabled(true);
        p.setCurve(HardClipProcessor.Curve.HARD);
        p.setClipDb(2.0);
        p.prepare(SR, 0);
        float[] sig = sine(200.0, 0.9, 4096, 2);
        p.analyze(sig, 2);
        float[] out = sig.clone();
        p.process(out, 2);

        double expected = 0.9 * Math.pow(10.0, -2.0 / 20.0);   // peak down by exactly 2 dB
        assertEquals(expected, peak(out), 0.01);
        assertEquals(-2.0, p.getGrAtPosition(0), 0.2);
    }

    @Test
    void grMeterIsBaseRateAndBounded()
    {
        // The GR meter reads the reduction of the BASE-rate program (a per-hop
        // envelope, via getGrAtPosition), so it is independent of the oversampling
        // factor by construction, never exceeds the dialled amount, and at the loudest
        // hop reaches ~ the dialled reduction. This is what failed before: the live
        // per-buffer measure at the oversampled rate inflated past the dial.
        int frames = 32768, ch = 2;
        java.util.Random rnd = new java.util.Random(42);
        float[] sig = new float[frames * ch];
        for (int i = 0; i < frames; i++)
        {
            float s = (float) (0.9 * (2.0 * rnd.nextDouble() - 1.0));   // broadband, sharp transients
            sig[2 * i] = s; sig[2 * i + 1] = s;
        }
        SoftClipProcessor sc = new SoftClipProcessor();
        sc.setEnabled(true);
        sc.setSatDb(1.0);
        sc.prepare(SR, frames);
        sc.analyze(sig, ch);

        double worst = 0.0, best = -99.0;
        for (long f = 0; f < frames; f += 256)
        {
            double gr = sc.getGrAtPosition(f);
            worst = Math.min(worst, gr);
            best = Math.max(best, gr);
        }
        assertTrue(worst >= -1.001, "meter exceeded the dialled -1 dB: " + worst);
        assertTrue(best <= 0.0001, "meter went positive: " + best);
        assertTrue(worst <= -0.7, "meter never reached near the dialled reduction: " + worst);
    }

    @Test
    void adoptAnalysisKeepsMeterConsistent()
    {
        // The live-analysis adoption must copy the whole analysis (peak AND per-hop
        // envelope). Adopting only the peak left the envelope inconsistent with the
        // ceiling, so the meter read ~0 after the user touched a knob.
        float[] sig = sine(200.0, 0.9, 8192, 2);
        SoftClipProcessor copy = new SoftClipProcessor();
        copy.setEnabled(true); copy.setSatDb(1.0);
        copy.prepare(SR, 8192);
        copy.analyze(sig, 2);

        SoftClipProcessor live = new SoftClipProcessor();
        live.setEnabled(true); live.setSatDb(1.0);
        live.prepare(SR, 8192);          // no analyze on the live clip; only the adoption
        live.adoptAnalysis(copy);

        double worst = 0.0;
        for (long f = 0; f < 8192; f += 256) worst = Math.min(worst, live.getGrAtPosition(f));
        assertTrue(worst <= -0.7, "meter dead after adoption: " + worst);
        assertTrue(worst >= -1.001, "meter exceeded the dial after adoption: " + worst);
    }

    @Test
    void hardClipTransparentAtZero()
    {
        HardClipProcessor p = new HardClipProcessor();
        p.setEnabled(true);
        p.setClipDb(0.0);
        p.prepare(SR, 0);
        float[] sig = sine(200.0, 0.9, 4096, 2);
        p.analyze(sig, 2);
        float[] out = sig.clone();
        p.process(out, 2);
        assertEquals(0.9, peak(out), 0.002);
        assertEquals(0.0, p.getGrAtPosition(0), 0.1);
    }

    @Test
    void hardClipDisabledPassesThrough()
    {
        HardClipProcessor p = new HardClipProcessor();
        p.setClipDb(3.0);
        p.prepare(SR, 0);
        float[] sig = sine(200.0, 0.9, 1024, 2);
        p.analyze(sig, 2);
        float[] out = sig.clone();
        p.process(out, 2);
        assertArrayEquals(sig, out, 1e-6f);
    }

    @Test
    void softClipReducesPeakByApproxDb()
    {
        SoftClipProcessor p = new SoftClipProcessor();
        p.setEnabled(true);
        p.setAlgorithm(Saturation.Algorithm.TAPE);
        p.setSatDb(2.0);
        p.prepare(SR, 0);
        float[] sig = sine(200.0, 0.8, 8192, 2);
        p.analyze(sig, 2);
        float[] out = sig.clone();
        p.process(out, 2);

        double expected = 0.8 * Math.pow(10.0, -2.0 / 20.0);   // peak down by ~2 dB (solved)
        assertEquals(expected, peak(out), 0.02);
        assertTrue(p.getGrAtPosition(0) < -1.5, "GR should be near -2 dB, was " + p.getGrAtPosition(0));
    }

    @Test
    void softClipTransparentAtZero()
    {
        SoftClipProcessor p = new SoftClipProcessor();
        p.setEnabled(true);
        p.setAlgorithm(Saturation.Algorithm.TAPE);
        p.setSatDb(0.0);
        p.prepare(SR, 0);
        float[] sig = sine(200.0, 0.8, 8192, 2);
        p.analyze(sig, 2);
        float[] out = sig.clone();
        p.process(out, 2);
        assertEquals(0.8, peak(out), 0.02);
        assertTrue(p.getGrAtPosition(0) > -0.4, "0 dB should be ~transparent, GR=" + p.getGrAtPosition(0));
    }

    @Test
    void clipsReducePeakWhenAnalysedViaPipeline()
    {
        // Mimics the live path: the pipeline's analyze() must feed each clip its
        // post-upstream signal so the cached peak (and the ceiling) are correct.
        SoftClipProcessor soft = new SoftClipProcessor();
        soft.setEnabled(true);
        soft.setSatDb(2.0);
        HardClipProcessor hard = new HardClipProcessor();
        hard.setEnabled(true);
        hard.setClipDb(2.0);

        ProcessingPipeline pSoft = new ProcessingPipeline();
        pSoft.addProcessor(soft);
        float[] sig = sine(200.0, 0.9, 8192, 2);
        pSoft.prepare(SR, sig.length);
        pSoft.analyze(sig, 2);
        float[] outSoft = pSoft.execute(sig.clone(), 2);
        assertEquals(0.9 * Math.pow(10.0, -2.0 / 20.0), peak(outSoft), 0.03);

        ProcessingPipeline pHard = new ProcessingPipeline();
        pHard.addProcessor(hard);
        pHard.prepare(SR, sig.length);
        pHard.analyze(sig, 2);
        float[] outHard = pHard.execute(sig.clone(), 2);
        assertEquals(0.9 * Math.pow(10.0, -2.0 / 20.0), peak(outHard), 0.01);
    }

    @Test
    void softClipDisabledPassesThrough()
    {
        SoftClipProcessor p = new SoftClipProcessor();
        p.setSatDb(2.0);
        p.prepare(SR, 0);
        float[] sig = sine(200.0, 0.8, 1024, 2);
        p.analyze(sig, 2);
        float[] out = sig.clone();
        p.process(out, 2);
        assertArrayEquals(sig, out, 1e-6f);
    }
}
