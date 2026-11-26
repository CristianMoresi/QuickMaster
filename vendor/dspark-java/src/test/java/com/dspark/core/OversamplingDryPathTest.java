package com.dspark.core;

import com.dspark.core.Oversampling.Mode;
import com.dspark.core.Oversampling.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OversamplingDryPathTest
{
    private static final double PI = Math.PI;

    @Test
    @DisplayName("Through a twin the dry is the wet sample for sample; against the rounded delay they differ as reported (OversamplingDryPath_adds_in_phase_with_the_engine)")
    void addsInPhaseWithTheEngine()
    {
        final int block = 256, blocks = 48;
        final double rate = 48000.0, tone = 17987.0;
        for (int factor : new int[] { 2, 4 })
        {
            Oversampling engine = new Oversampling(factor, Quality.HIGH, Mode.POLYPHASE_IIR);
            engine.prepare(rate, 2, block);
            OversamplingDryPath dry = new OversamplingDryPath();
            dry.prepare(rate, 2, block, engine);
            assertEquals(engine.getLatency(), dry.getLatency());

            final int latency = engine.getLatency();
            double[] history = new double[latency];
            int historyPos = 0;
            double[][] wet = new double[2][block], aligned = new double[2][block];
            double worstAligned = 0.0, worstDelayed = 0.0;
            long n = 0;
            for (int b = 0; b < blocks; b++)
            {
                for (int i = 0; i < block; i++, n++)
                {
                    final double x = Math.sin(2.0 * PI * tone * (double) n / rate);
                    wet[0][i] = aligned[0][i] = x;
                    wet[1][i] = aligned[1][i] = -0.5 * x;
                }
                final int length = (b % 3 == 0) ? block : (b % 3 == 1 ? 17 : 200);
                double[] input = java.util.Arrays.copyOf(wet[0], length);
                engine.upsample(wet, length);
                engine.downsample(wet, length);
                dry.process(aligned, length);
                for (int i = 0; i < length; i++)
                {
                    final double delayed = latency > 0 ? history[historyPos] : input[i];
                    if (latency > 0)
                    {
                        history[historyPos] = input[i];
                        historyPos = (historyPos + 1) % history.length;
                    }
                    if (b < 12) continue;
                    for (int ch = 0; ch < 2; ch++)
                        worstAligned = Math.max(worstAligned, Math.abs(wet[ch][i] - aligned[ch][i]));
                    worstDelayed = Math.max(worstDelayed, Math.abs(wet[0][i] - delayed));
                }
                n -= (block - length);
            }
            assertEquals(0.0, worstAligned, 0.0);
            final double excess = engine.getRoundTripDelay(tone) - (double) latency;
            final double expected = 2.0 * Math.abs(Math.sin(PI * tone / rate * excess));
            assertEquals(expected, worstDelayed, 0.05, "factor " + factor);
            if (factor == 2) assertTrue(worstDelayed > 1.0);
        }

        // Linear phase: the path is the whole-sample delay of the engine.
        Oversampling engine = new Oversampling(2, Quality.HIGH);
        engine.prepare(rate, 2, block);
        OversamplingDryPath dry = new OversamplingDryPath();
        dry.prepare(rate, 2, block, engine);
        final int latency = engine.getLatency();
        assertEquals(latency, dry.getLatency());
        assertTrue(latency > 0 && latency < block - 8);
        double[][] buf = new double[2][block];
        buf[0][5] = 1.0;
        buf[1][7] = -0.25;
        dry.process(buf, block);
        for (int i = 0; i < block; i++)
        {
            assertEquals(i == 5 + latency ? 1.0 : 0.0, buf[0][i], 0.0);
            assertEquals(i == 7 + latency ? -0.25 : 0.0, buf[1][i], 0.0);
        }
        // reset() empties the line.
        buf = new double[2][block];
        buf[0][block - 1] = 1.0;
        dry.process(buf, block);
        dry.reset();
        buf = new double[2][block];
        dry.process(buf, block);
        for (int i = 0; i < block; i++) assertEquals(0.0, buf[0][i], 0.0);

        // No oversampling: nothing to match, the signal is left alone.
        Oversampling one = new Oversampling(1);
        one.prepare(rate, 2, block);
        OversamplingDryPath none = new OversamplingDryPath();
        none.prepare(rate, 2, block, one);
        assertEquals(0, none.getLatency());
        double[][] ramp = new double[2][block];
        for (int i = 0; i < block; i++) ramp[0][i] = ramp[1][i] = i;
        none.process(ramp, block);
        for (int i = 0; i < block; i++) assertEquals(i, ramp[1][i], 0.0);
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give identical output, both modes, with offsets")
    void blockSizeIndependence()
    {
        double[] signal = new double[9000];
        for (int i = 0; i < signal.length; i++) signal[i] = Math.sin(0.0137 * i) + 0.3 * Math.sin(1.9 * i);
        for (Mode mode : Mode.values())
        {
            double[] reference = null;
            for (int block : new int[] { 4096, 512, 37, 1 })
            {
                Oversampling engine = new Oversampling(4, Quality.HIGH, mode);
                engine.prepare(48000.0, 1, 4096);
                OversamplingDryPath dry = new OversamplingDryPath();
                dry.prepare(48000.0, 1, 4096, engine);
                double[][] x = { signal.clone() };
                for (int pos = 0; pos < signal.length; pos += block)
                    dry.process(x, pos, Math.min(block, signal.length - pos));
                if (reference == null) reference = x[0];
                else assertArrayEquals(reference, x[0], mode + " block " + block);
            }
        }
    }

    @Test
    @DisplayName("Linear phase: the dry path is the input delayed by the engine latency, across irregular blocks")
    void linearPhaseIsTheLatency()
    {
        Oversampling engine = new Oversampling(8, Quality.MAXIMUM);
        engine.prepare(44100.0, 1, 64);
        OversamplingDryPath dry = new OversamplingDryPath();
        dry.prepare(44100.0, 1, 64, engine);
        final int latency = engine.getLatency();
        double[] signal = new double[1000];
        for (int i = 0; i < signal.length; i++) signal[i] = i + 1;
        double[][] x = { signal.clone() };
        int pos = 0, k = 0;
        final int[] sizes = { 7, 64, 1, 33 };
        while (pos < signal.length)
        {
            final int n = Math.min(sizes[k++ % 4], signal.length - pos);
            dry.process(x, pos, n);
            pos += n;
        }
        for (int i = 0; i < signal.length; i++)
            assertEquals(i < latency ? 0.0 : signal[i - latency], x[0][i], 0.0);
    }
}
