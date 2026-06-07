package com.quickmaster.processing.limit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultibandLimiterProcessorTest
{
    private static final int SR = 48000;

    private static float[] mix(int frames, int ch)
    {
        float[] x = new float[frames * ch];
        for (int n = 0; n < frames; n++)
        {
            double s = 0.5 * Math.sin(2.0 * Math.PI * 60.0 * n / SR)
                     + 0.3 * Math.sin(2.0 * Math.PI * 800.0 * n / SR)
                     + 0.2 * Math.sin(2.0 * Math.PI * 9000.0 * n / SR);
            for (int c = 0; c < ch; c++) x[n * ch + c] = (float) s;
        }
        return x;
    }

    @Test
    void transparentReconstructionAtZeroPush()
    {
        int frames = 24000, ch = 2;
        float[] x = mix(frames, ch);
        MultibandLimiterProcessor p = new MultibandLimiterProcessor();
        p.setEnabled(true);
        for (int b = 0; b < MultibandLimiterProcessor.BANDS; b++) p.setPushDb(b, 0.0);  // unity bands
        p.prepare(SR, frames * ch);
        p.analyze(x, ch);
        int lat = new com.dspark.effects.MultibandCrossover().getLatency();

        float[] out = x.clone();
        p.process(out, ch);
        for (int n = lat; n < frames - lat; n++)
        {
            for (int c = 0; c < ch; c++)
            {
                assertEquals(x[(n - lat) * ch + c], out[n * ch + c], 3e-3,
                        "zero-push should reconstruct the delayed input at n=" + n);
            }
        }
    }

    @Test
    void pushReducesBandByDialledAmount()
    {
        int frames = 24000, ch = 2;
        float[] x = mix(frames, ch);
        MultibandLimiterProcessor p = new MultibandLimiterProcessor();
        p.setEnabled(true);
        p.setPushDb(0, 3.0);                      // 3 dB on the LOW band
        p.setPushDb(2, 0.0);                      // leave HIGH-MID un-pushed (default is 1 dB)
        p.prepare(SR, frames * ch);
        p.analyze(x, ch);

        double deepest = 0.0;
        for (long f = 0; f < frames; f += 200) deepest = Math.min(deepest, p.getBandGrAtPosition(0, f));
        assertEquals(-3.0, deepest, 0.6, "LOW band should be limited by ~3 dB at its loudest");

        // An untouched band shows no reduction.
        double hi = 0.0;
        for (long f = 0; f < frames; f += 200) hi = Math.min(hi, p.getBandGrAtPosition(2, f));
        assertEquals(0.0, hi, 1e-6, "un-pushed band must show no reduction");
    }

    @Test
    void pushBoostsBodyAndHoldsPeak()
    {
        // Loud LOW (0.8) then quiet LOW (0.2). Push 6 pushes the quiet body UP
        // (make-up) toward the peak, while holding the loudest peak.
        int frames = 48000, ch = 2;
        float[] x = new float[frames * ch];
        for (int n = 0; n < frames; n++)
        {
            double a = (n < frames / 2) ? 0.8 : 0.2;
            double s = a * Math.sin(2.0 * Math.PI * 60.0 * n / SR);
            for (int c = 0; c < ch; c++) x[n * ch + c] = (float) s;
        }
        MultibandLimiterProcessor p = new MultibandLimiterProcessor();
        p.setEnabled(true);
        p.setPushDb(0, 6.0);
        p.prepare(SR, frames * ch);
        p.analyze(x, ch);
        p.setPlaybackPosition(0);
        float[] out = x.clone();
        p.process(out, ch);

        double loud = maxAbsRange(out, ch, frames / 4, frames / 2 - 3000);
        double quiet = maxAbsRange(out, ch, 3 * frames / 4, frames);
        assertTrue(loud > 0.6, "loudest peak should be held ~0.8, was " + loud);
        assertTrue(quiet > 0.3, "quiet body should be pushed up toward 0.4, was " + quiet);
    }

    @Test
    void oversampledProcessingStaysFinite()
    {
        // At a high oversampling rate the crossover uses FFT block convolution so it
        // stays affordable. Verify the multiband runs oversampled without breaking and
        // produces finite output.
        int frames = 8192, ch = 2, F = 16;
        float[] base = new float[frames * ch];
        for (int n = 0; n < frames; n++)
        {
            double a = (n < frames / 2) ? 0.8 : 0.2;
            double s = a * Math.sin(2.0 * Math.PI * 60.0 * n / SR);
            for (int c = 0; c < ch; c++) base[n * ch + c] = (float) s;
        }
        MultibandLimiterProcessor p = new MultibandLimiterProcessor();
        p.setEnabled(true);
        p.setPushDb(0, 6.0);
        p.prepare(SR, frames * ch);
        p.analyze(base, ch);
        p.prepare(SR * F, frames * F * ch);
        p.setPlaybackPosition(0);

        com.dspark.core.OversamplingEngine oe = new com.dspark.core.OversamplingEngine();
        oe.prepare(F, ch, 1024, com.dspark.core.OversamplingEngine.Quality.HIGH);
        for (int start = 0; start < frames; start += 1024)
        {
            int n = Math.min(1024, frames - start);
            float[] chunk = new float[n * ch];
            System.arraycopy(base, start * ch, chunk, 0, n * ch);
            float[] up = oe.upsample(chunk, n);
            float[] osBuf = java.util.Arrays.copyOf(up, n * F * ch);
            p.process(osBuf, ch);
            for (float s : osBuf) assertTrue(Float.isFinite(s), "oversampled output must be finite");
        }
    }

    private static double maxAbsRange(float[] b, int ch, int fromFrame, int toFrame)
    {
        double m = 0.0;
        for (int i = fromFrame * ch; i < toFrame * ch && i < b.length; i++)
        {
            double a = Math.abs(b[i]);
            if (a > m) m = a;
        }
        return m;
    }

    @Test
    void makeupAppliesWhenProcessedInBlocks()
    {
        // Loud-then-quiet LOW, processed in blocks (live style): the quiet body must
        // come out boosted, proving the envelope is applied correctly per block.
        int frames = 48000, ch = 2;
        float[] sig = new float[frames * ch];
        for (int n = 0; n < frames; n++)
        {
            double a = (n < frames / 2) ? 0.8 : 0.2;
            double s = a * Math.sin(2.0 * Math.PI * 60.0 * n / SR);
            for (int c = 0; c < ch; c++) sig[n * ch + c] = (float) s;
        }
        MultibandLimiterProcessor p = new MultibandLimiterProcessor();
        p.setEnabled(true);
        p.setPushDb(0, 6.0);
        p.prepare(SR, frames * ch);
        p.analyze(sig, ch);
        p.setPlaybackPosition(0);

        float[] out = new float[frames * ch];
        int block = 4096;
        for (int start = 0; start < frames; start += block)
        {
            int n = Math.min(block, frames - start);
            float[] buf = new float[n * ch];
            System.arraycopy(sig, start * ch, buf, 0, n * ch);
            p.process(buf, ch);
            System.arraycopy(buf, 0, out, start * ch, n * ch);
        }
        double quiet = maxAbsRange(out, ch, 3 * frames / 4, frames);
        assertTrue(quiet > 0.3, "blocked make-up should boost the quiet body toward 0.4, was " + quiet);
    }

    private static double maxAbs(float[] b)
    {
        double m = 0.0;
        for (float s : b) { double a = Math.abs(s); if (a > m) m = a; }
        return m;
    }

    @Test
    void disabledIsPassthrough()
    {
        int frames = 8000, ch = 2;
        float[] x = mix(frames, ch);
        MultibandLimiterProcessor p = new MultibandLimiterProcessor();
        p.setEnabled(false);
        p.setPushDb(0, 4.0);
        p.prepare(SR, frames * ch);
        p.analyze(x, ch);
        float[] out = x.clone();
        p.process(out, ch);
        assertArrayEquals(x, out, 0.0f, "disabled limiter must not alter the signal");
        assertEquals(0, p.getLatencyFrames(), "disabled limiter must report zero latency");
    }
}
