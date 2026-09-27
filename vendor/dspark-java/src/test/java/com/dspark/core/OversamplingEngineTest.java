package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OversamplingEngineTest
{
    @Test
    @DisplayName("Streaming up+down round-trip preserves a sine's energy (mono, 4x)")
    void streamingRoundTripPreservesEnergy()
    {
        int sr = 48000, factor = 4, block = 256, blocks = 40;
        OversamplingEngine eng = new OversamplingEngine();
        eng.prepare(factor, 1, block, OversamplingEngine.Quality.HIGH);

        double inSq = 0.0, outSq = 0.0;
        int counted = 0, idx = 0;
        float[] base = new float[block];
        float[] out = new float[block];
        for (int b = 0; b < blocks; b++)
        {
            for (int i = 0; i < block; i++, idx++)
                base[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * idx / sr));

            float[] hi = eng.upsample(base, block);
            assertEquals(block * factor, hi.length);
            eng.downsample(hi, block, out);             // identity processing at the high rate

            for (float v : out) assertTrue(Float.isFinite(v));
            if (b >= 8)                                 // skip the filter warm-up
                for (int i = 0; i < block; i++) { inSq += base[i] * base[i]; outSq += out[i] * out[i]; counted++; }
        }
        double inRms = Math.sqrt(inSq / counted), outRms = Math.sqrt(outSq / counted);
        assertTrue(Math.abs(outRms - inRms) < 0.03, "in RMS " + inRms + ", out RMS " + outRms);
    }

    @Test
    @DisplayName("Reported round-trip latency equals the impulse peak and is bounded (4x, 16x)")
    void latencyMatchesImpulsePeak()
    {
        for (int factor : new int[] { 2, 4, 8, 16 })
        {
            int block = 1024;
            OversamplingEngine eng = new OversamplingEngine();
            eng.prepare(factor, 1, block, OversamplingEngine.Quality.HIGH);
            int lat = eng.getLatencyBaseFrames();
            assertTrue(lat > 0 && lat < block, "factor " + factor + " latency out of range: " + lat);

            float[] in = new float[block];
            in[0] = 1.0f;                               // unit impulse on a freshly reset engine
            float[] hi = eng.upsample(in, block);
            float[] out = new float[block];
            eng.downsample(hi, block, out);
            int peak = 0;
            double pv = -1.0;
            for (int i = 0; i < block; i++) { double a = Math.abs(out[i]); if (a > pv) { pv = a; peak = i; } }
            assertEquals(lat, peak, "factor " + factor + ": reported latency must equal the impulse peak");
        }
    }

    @Test
    @DisplayName("Factor 1 has zero latency")
    void factorOneZeroLatency()
    {
        OversamplingEngine eng = new OversamplingEngine();
        eng.prepare(1, 2, 64, OversamplingEngine.Quality.HIGH);
        assertEquals(0, eng.getLatencyBaseFrames());
    }

    @Test
    @DisplayName("Factor 1 passes through unchanged (stereo)")
    void factorOnePassThrough()
    {
        OversamplingEngine eng = new OversamplingEngine();
        eng.prepare(1, 2, 4, OversamplingEngine.Quality.MEDIUM);
        float[] in = { 0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f, 0.4f, -0.4f };   // 4 stereo frames
        float[] hi = eng.upsample(in, 4);
        float[] out = new float[8];
        eng.downsample(hi, 4, out);
        assertArrayEquals(in, out, 1e-6f);
    }
}
