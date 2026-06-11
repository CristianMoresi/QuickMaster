package com.quickmaster.processing.eq;

import com.dspark.effects.MasterEqualizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link EqualizerProcessor} wrapper: the
 * {@link AudioProcessor} contract (passthrough when disabled or empty,
 * latency reporting) and faithful delegation to the {@link MasterEqualizer}
 * engine, whose DSP is verified by the library's own suite.
 */
class EqualizerProcessorTest
{
    private static final int SR = 48000;

    private EqualizerProcessor eq;

    @BeforeEach
    void setUp()
    {
        eq = new EqualizerProcessor();
        eq.prepare(SR, 0);
    }

    @Test
    @DisplayName("Enabled with no bands is a transparent, zero-latency passthrough")
    void emptyIsPassthrough()
    {
        float[] input    = { 0.3f, -0.4f, 0.1f, 0.9f };
        float[] expected = input.clone();
        assertArrayEquals(expected, eq.process(input, 2), 0.0f);
        assertEquals(0, eq.getLatencyFrames());
    }

    @Test
    @DisplayName("Disabled is a passthrough")
    void disabledIsPassthrough()
    {
        MasterEqualizer.Band b = new MasterEqualizer.Band();
        b.type = MasterEqualizer.BandType.GAIN; b.gainDb = 6;
        eq.setBand(0, b);
        eq.setEnabled(false);

        float[] input    = { 0.3f, -0.4f, 0.1f, 0.9f };
        float[] expected = input.clone();
        assertArrayEquals(expected, eq.process(input, 2), 0.0f);
        assertEquals(0, eq.getLatencyFrames());
    }

    @Test
    @DisplayName("A GAIN band is delegated and applied")
    void gainBandIsApplied()
    {
        MasterEqualizer.Band b = new MasterEqualizer.Band();
        b.type = MasterEqualizer.BandType.GAIN;
        b.channel = MasterEqualizer.Channel.STEREO;
        b.gainDb = 6.0;
        eq.setBand(0, b);
        assertTrue(eq.getNumBands() >= 1);

        float[] input = { 0.3f, -0.4f, 0.1f, 0.2f };
        float[] out = input.clone();
        eq.process(out, 2);

        float g = (float) Math.pow(10.0, 6.0 / 20.0);
        for (int i = 0; i < input.length; i++) assertEquals(input[i] * g, out[i], 1e-5f);
    }

    @Test
    @DisplayName("Latency is half the kernel only for an active linear-phase band")
    void latencyReporting()
    {
        assertEquals(0, eq.getLatencyFrames(), "no bands");

        MasterEqualizer.Band lin = new MasterEqualizer.Band();
        lin.type = MasterEqualizer.BandType.BELL;
        lin.phase = MasterEqualizer.BandPhase.LINEAR;
        lin.frequency = 1000; lin.gainDb = 3;
        eq.setBand(0, lin);
        // Kernel spans a constant time window, so the latency derives from the rate.
        int kernel = (int) Math.round(MasterEqualizer.KERNEL_SECONDS * SR);
        if ((kernel & 1) == 1) kernel++;
        assertEquals(kernel / 2, eq.getLatencyFrames());

        MasterEqualizer.Band min = new MasterEqualizer.Band();
        min.type = MasterEqualizer.BandType.BELL;
        min.phase = MasterEqualizer.BandPhase.MINIMUM;
        min.frequency = 1000; min.gainDb = 3;
        eq.setBand(0, min);
        assertEquals(0, eq.getLatencyFrames());
    }
}
