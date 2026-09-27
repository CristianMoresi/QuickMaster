package com.dspark.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Clipper}. */
class ClipperTest
{
    private static final int SR = 48000;

    private static float[] dc(double v, int frames, int ch)
    {
        float[] b = new float[frames * ch];
        java.util.Arrays.fill(b, (float) v);
        return b;
    }

    @Test
    void hardClipClampsToCeiling()
    {
        Clipper c = new Clipper();
        c.prepare(SR, 2);
        c.setMode(Clipper.Mode.HARD);
        c.setCeilingDb(-6.0);
        float[] b = dc(1.0, 64, 2);
        c.process(b, 2);
        double ceil = Math.pow(10.0, -6.0 / 20.0);
        // Frame 0 is the ADAA start-up transient (average from the zero state).
        for (int i = 2; i < b.length; i++) assertEquals(ceil, b[i], 1e-3);
    }

    @Test
    void belowCeilingUnchanged()
    {
        Clipper c = new Clipper();
        c.prepare(SR, 2);
        c.setMode(Clipper.Mode.HARD);
        c.setCeilingDb(-6.0);
        float[] b = dc(0.3, 64, 2);
        c.process(b, 2);
        for (float v : b) assertEquals(0.3, v, 1e-4);
        assertEquals(0.0, c.getGainReductionDb(), 1e-3);
    }

    @Test
    void reportsExactGainReduction()
    {
        Clipper c = new Clipper();
        c.prepare(SR, 2);
        c.setMode(Clipper.Mode.HARD);
        c.setCeilingDb(-6.0);
        float[] b = dc(1.0, 64, 2);     // peak 1.0 clipped to −6 dB → GR −6 dB
        c.process(b, 2);
        assertEquals(-6.0, c.getGainReductionDb(), 0.1);
    }

    @Test
    void softClipStaysUnderCeilingAndReduces()
    {
        Clipper c = new Clipper();
        c.prepare(SR, 2);
        c.setMode(Clipper.Mode.SOFT);
        c.setCeilingDb(0.0);
        int n = 2048;
        float[] b = new float[n * 2];
        for (int i = 0; i < n; i++)
        {
            float s = (float) (1.5 * Math.sin(2.0 * Math.PI * 100.0 * i / SR));
            b[2 * i] = s;
            b[2 * i + 1] = s;
        }
        c.process(b, 2);
        float peak = 0f;
        for (float v : b) peak = Math.max(peak, Math.abs(v));
        assertTrue(peak <= 1.0001f, "soft clip should not exceed the ceiling, peak=" + peak);
        assertTrue(c.getGainReductionDb() < 0.0, "soft clip should report reduction");
    }
}
