package com.dspark.effects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultibandCrossoverTest
{
    private static float[] makeSignal(int frames, int ch)
    {
        float[] x = new float[frames * ch];
        java.util.Random rnd = new java.util.Random(11);
        for (int n = 0; n < frames; n++)
        {
            double s = 0.4 * Math.sin(2.0 * Math.PI * 60.0 * n / 48000.0)
                     + 0.3 * Math.sin(2.0 * Math.PI * 800.0 * n / 48000.0)
                     + 0.2 * Math.sin(2.0 * Math.PI * 9000.0 * n / 48000.0)
                     + 0.05 * (rnd.nextDouble() - 0.5);
            x[n * ch] = (float) s;
            if (ch > 1) x[n * ch + 1] = (float) (s * 0.9);
        }
        return x;
    }

    @Test
    void splitWholeReconstructsDelayedInput()
    {
        int frames = 24000, ch = 2;
        float[] x = makeSignal(frames, ch);
        MultibandCrossover cx = new MultibandCrossover();
        cx.prepare(48000.0, ch, new double[]{120.0, 1000.0, 6000.0});
        assertEquals(4, cx.getBands());
        int lat = cx.getLatency();
        float[][] bands = cx.splitWhole(x, ch);

        for (int n = lat; n < frames - lat; n++)
        {
            for (int c = 0; c < ch; c++)
            {
                double sum = 0.0;
                for (int b = 0; b < bands.length; b++) sum += bands[b][n * ch + c];
                assertEquals(x[(n - lat) * ch + c], sum, 2e-3,
                        "reconstruction mismatch at n=" + n + " c=" + c);
            }
        }
    }

    @Test
    void invalidPrepareKeepsPreviousState()
    {
        MultibandCrossover cx = new MultibandCrossover();
        cx.prepare(48000.0, 2, new double[]{120.0, 1000.0});
        int lat = cx.getLatency();
        assertEquals(3, cx.getBands());

        cx.prepare(Double.NaN, 2, new double[]{120.0, 1000.0});
        cx.prepare(-1.0, 2, new double[]{120.0});
        cx.prepare(48000.0, 2, null);
        cx.prepare(48000.0, 2, new double[]{120.0, Double.NaN});

        assertEquals(3, cx.getBands(), "invalid prepare must keep the previous design");
        assertEquals(lat, cx.getLatency());

        // The kept design still splits and reconstructs.
        int frames = 8192, ch = 2;
        float[] x = makeSignal(frames, ch);
        float[][] bands = cx.splitWhole(x, ch);
        for (int n = lat; n < frames - lat; n++)
        {
            double sum = 0.0;
            for (float[] band : bands) sum += band[n * ch];
            assertEquals(x[(n - lat) * ch], sum, 2e-3);
        }
    }

    @Test
    void processReconstructsAndMatchesWhole()
    {
        int frames = 24000, ch = 2;
        float[] x = makeSignal(frames, ch);
        MultibandCrossover cx = new MultibandCrossover();
        cx.prepare(48000.0, ch, new double[]{120.0, 1000.0, 6000.0});
        int lat = cx.getLatency();
        int bands = cx.getBands();

        // Stream in blocks, accumulate the band outputs into full arrays.
        float[][] full = new float[bands][frames * ch];
        int block = 4096;
        float[][] bandOut = new float[bands][block * ch];
        for (int start = 0; start < frames; start += block)
        {
            int n = Math.min(block, frames - start);
            float[] buf = new float[n * ch];
            System.arraycopy(x, start * ch, buf, 0, n * ch);
            float[][] bo = (n == block) ? bandOut : new float[bands][n * ch];
            cx.process(buf, ch, bo);
            for (int b = 0; b < bands; b++)
                System.arraycopy(bo[b], 0, full[b], start * ch, n * ch);
        }

        for (int nn = lat; nn < frames - lat; nn++)
        {
            for (int c = 0; c < ch; c++)
            {
                double sum = 0.0;
                for (int b = 0; b < bands; b++) sum += full[b][nn * ch + c];
                assertEquals(x[(nn - lat) * ch + c], sum, 2e-3,
                        "streaming reconstruction mismatch at n=" + nn);
            }
        }
    }
}
