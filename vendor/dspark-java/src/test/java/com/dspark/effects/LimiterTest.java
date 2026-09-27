package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Limiter}. */
class LimiterTest
{
    private static final double SR = 48000.0;

    private static float[] sine(double amp, double seconds)
    {
        int n = (int) (SR * seconds);
        float[] b = new float[n];
        double step = 2.0 * Math.PI * 1000.0 / SR;
        for (int i = 0; i < n; i++) b[i] = (float) (amp * Math.sin(step * i));
        return b;
    }

    private static double tailPeak(float[] b)
    {
        double m = 0.0;
        for (int i = Math.max(0, b.length - 4096); i < b.length; i++) m = Math.max(m, Math.abs(b[i]));
        return m;
    }

    @Test
    @DisplayName("A loud signal is held at or below the ceiling")
    void brickwall()
    {
        Limiter lim = new Limiter();
        lim.prepare(SR, 1);
        lim.setCeilingDb(-6.0);

        float[] loud = sine(1.0, 1.0);
        lim.process(loud, 1);

        double ceil = Math.pow(10.0, -6.0 / 20.0);   // ~0.5012
        double peak = tailPeak(loud);
        assertTrue(peak <= ceil * 1.05, "exceeded ceiling: " + peak);
        assertTrue(peak >= ceil * 0.85, "over-attenuated: " + peak);
    }

    @Test
    @DisplayName("A signal below the ceiling passes through unchanged")
    void belowCeilingPasses()
    {
        Limiter lim = new Limiter();
        lim.prepare(SR, 1);
        lim.setCeilingDb(-6.0);

        float[] quiet = sine(0.1, 0.5);
        lim.process(quiet, 1);
        assertEquals(0.1, tailPeak(quiet), 0.01);
    }

    @Test
    @DisplayName("True-peak detection still limits without errors")
    void truePeakMode()
    {
        Limiter lim = new Limiter();
        lim.prepare(SR, 2);
        lim.setCeilingDb(-1.0);
        lim.setTruePeak(true);

        float[] loud = new float[(int) SR];        // 0.5 s stereo
        double step = 2.0 * Math.PI * 5000.0 / SR;  // 5 kHz stresses ISP
        for (int i = 0; i < loud.length; i += 2)
        {
            float s = (float) (0.99 * Math.sin(step * (i / 2)));
            loud[i] = s; loud[i + 1] = s;
        }
        lim.process(loud, 2);

        double ceil = Math.pow(10.0, -1.0 / 20.0);
        assertTrue(tailPeak(loud) <= ceil * 1.05);
        assertTrue(lim.getLatency() > 0);
    }

    @Test
    @DisplayName("True-peak mode holds the ceiling under a torture program (post warm-up)")
    void truePeakCeilingCompliance()
    {
        Limiter lim = new Limiter();
        lim.prepare(SR, 2);
        lim.setCeilingDb(-1.0);
        lim.setTruePeak(true);

        // Aggressive program: 10 Hz bursts of a 997 + ~12 kHz mix plus noise.
        int n = (int) SR * 2;
        float[] b = new float[n * 2];
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < n; i++)
        {
            double env = (i / 4800) % 2 == 0 ? 1.0 : 0.3;
            double s = env * (0.8 * Math.sin(2.0 * Math.PI * 997.0 * i / SR)
                    + 0.6 * Math.sin(2.0 * Math.PI * 11997.0 * i / SR + 1.0)
                    + 0.2 * (rnd.nextDouble() * 2.0 - 1.0));
            b[2 * i] = (float) s;
            b[2 * i + 1] = (float) (s * 0.9);
        }
        lim.process(b, 2);

        // Streaming per-frame TP, skipping the start (the measurement filter
        // itself overshoots on a cold-start signal onset).
        com.dspark.analysis.TruePeak tL = new com.dspark.analysis.TruePeak();
        com.dspark.analysis.TruePeak tR = new com.dspark.analysis.TruePeak();
        double maxTp = 0.0;
        for (int i = 4096; i < n; i++)
        {
            maxTp = Math.max(maxTp, tL.process(b[2 * i]));
            maxTp = Math.max(maxTp, tR.process(b[2 * i + 1]));
        }
        double maxTpDb = 20.0 * Math.log10(maxTp);
        assertTrue(maxTpDb <= -1.0 + 0.1,
                "output true peak exceeded the -1 dBTP ceiling: " + maxTpDb + " dBTP");
    }

    @Test
    @DisplayName("Non-finite parameters are ignored and the limiter keeps working")
    void nanParametersIgnored()
    {
        Limiter lim = new Limiter();
        lim.prepare(SR, 1);
        lim.setCeilingDb(-6.0);
        lim.setReleaseMs(Double.NaN);
        lim.setCeilingDb(Double.NaN);
        lim.setLookahead(Double.NaN);
        lim.prepare(Double.NaN, 1);   // invalid re-prepare: keep state

        float[] loud = sine(1.0, 1.0);
        lim.process(loud, 1);
        double ceil = Math.pow(10.0, -6.0 / 20.0);
        double peak = tailPeak(loud);
        assertTrue(Double.isFinite(peak) && peak <= ceil * 1.05,
                "limiter poisoned or ceiling lost: " + peak);
        assertEquals(-6.0, lim.getCeilingDb(), 1e-9);
    }

    @Test
    @DisplayName("Two-arg prepare preserves a configured look-ahead")
    void preparePreservesLookahead()
    {
        Limiter lim = new Limiter();
        lim.prepare(SR, 1);
        lim.setLookahead(5.0);
        int lat5 = lim.getLatency();
        lim.prepare(SR, 1);           // re-prepare without look-ahead argument
        assertEquals(lat5, lim.getLatency(), "re-prepare must keep the 5 ms look-ahead");
    }
}
