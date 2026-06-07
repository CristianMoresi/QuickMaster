package com.quickmaster.processing.limit;

import com.dspark.analysis.TruePeak;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BroadbandLimiterProcessorTest
{
    private static final int SR = 48000;

    private static float[] transients(int frames, int ch)
    {
        float[] x = new float[frames * ch];
        for (int n = 0; n < frames; n++)
        {
            double s = 0.3 * Math.sin(2.0 * Math.PI * 200.0 * n / SR);
            if (n % 1200 == 0) s = 0.9;          // peaks well above the body
            for (int c = 0; c < ch; c++) x[n * ch + c] = (float) s;
        }
        return x;
    }

    @Test
    void pushReducesTruePeakByDialledAmount()
    {
        int frames = 24000, ch = 2;
        float[] x = transients(frames, ch);
        BroadbandLimiterProcessor p = new BroadbandLimiterProcessor();
        p.setEnabled(true);
        p.setPushDb(4.0);
        p.prepare(SR, frames * ch);
        p.analyze(x, ch);

        double deepest = 0.0;
        for (long f = 0; f < frames; f += 200) deepest = Math.min(deepest, p.getGrAtPosition(f));
        assertEquals(-4.0, deepest, 0.7, "loudest peak should be limited ~4 dB");

        // Make-up pushes the body up (holds the peak), so the overall RMS rises.
        double rmsBefore = rms(x);
        float[] out = x.clone();
        p.process(out, ch);
        double rmsAfter = rms(out);
        assertTrue(rmsAfter > rmsBefore * 1.1,
                "push should boost the body via make-up: rms " + rmsBefore + " -> " + rmsAfter);
    }

    private static double rms(float[] b)
    {
        double s = 0.0;
        for (float v : b) s += (double) v * v;
        return Math.sqrt(s / Math.max(1, b.length));
    }

    @Test
    void disabledIsPassthrough()
    {
        int frames = 8000, ch = 2;
        float[] x = transients(frames, ch);
        BroadbandLimiterProcessor p = new BroadbandLimiterProcessor();
        p.setEnabled(false);
        p.setPushDb(6.0);
        p.prepare(SR, frames * ch);
        p.analyze(x, ch);
        float[] out = x.clone();
        p.process(out, ch);
        assertArrayEquals(x, out, 0.0f);
        assertEquals(0, p.getLatencyFrames());
    }
}
