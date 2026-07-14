package com.quickmaster.processing.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputAnalysisTest
{
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;

    @Test
    @DisplayName("Output analysis measures the rendered master, not the source")
    void measuresPostProcessingBuffer()
    {
        int frames = SAMPLE_RATE * 5;
        float[] source = new float[frames * CHANNELS];
        float[] rendered = new float[source.length];
        for (int frame = 0; frame < frames; frame++)
        {
            float sample = (float) (0.10 * Math.sin(
                    2.0 * Math.PI * 997.0 * frame / SAMPLE_RATE));
            source[frame * CHANNELS] = sample;
            source[frame * CHANNELS + 1] = sample;
            rendered[frame * CHANNELS] = sample * 2.0f;
            rendered[frame * CHANNELS + 1] = sample * 2.0f;
        }

        OutputAnalysis.Result input = OutputAnalysis.measure(
                source, CHANNELS, SAMPLE_RATE);
        OutputAnalysis.Result output = OutputAnalysis.measure(
                rendered, CHANNELS, SAMPLE_RATE);

        assertEquals(20.0 * Math.log10(2.0),
                output.integratedLufs() - input.integratedLufs(), 0.15);
        assertEquals(20.0 * Math.log10(2.0),
                output.truePeakDbtp() - input.truePeakDbtp(), 0.05);
        assertEquals(4.0, output.midPower() / input.midPower(), 1e-3);
        assertEquals(1.0, output.correlation(), 1e-6);
        assertEquals(0.0, output.sidePower(), 1e-12);
        assertTrue(output.spectrum().isReady());
    }
}
