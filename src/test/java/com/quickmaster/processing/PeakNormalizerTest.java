package com.quickmaster.processing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link PeakNormalizer} pipeline wrapper: the
 * {@link AudioProcessor} contract (two-phase analyze/process, enabled /
 * disabled passthrough, zero latency, faithful delegation) plus the
 * declaration that it analyses the upstream-processed signal.
 */
class PeakNormalizerTest
{
    private static final double EPSILON = 1e-4;

    private PeakNormalizer normalizer;

    @BeforeEach
    void setUp()
    {
        normalizer = new PeakNormalizer();
    }

    @Test
    @DisplayName("Default constructor targets -0.1 dBTP, unity gain until analysed")
    void defaultConstructorIsUnity()
    {
        assertEquals(-0.1, normalizer.getTargetDbfs(), 0.0);
        assertEquals(1.0, normalizer.getGain(), 0.0);
    }

    @Test
    @DisplayName("Reports zero latency and requests upstream analysis")
    void contractFlags()
    {
        assertEquals(0, normalizer.getLatencyFrames());
        assertTrue(normalizer.usesAnalysis());
    }

    @Test
    @DisplayName("Target values outside [-60, 0] dBFS are rejected")
    void targetOutOfRangeIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> normalizer.setTargetDbfs(-60.1));
        assertThrows(IllegalArgumentException.class, () -> normalizer.setTargetDbfs(0.1));
        assertThrows(IllegalArgumentException.class, () -> new PeakNormalizer(-100.0));
        assertThrows(IllegalArgumentException.class, () -> new PeakNormalizer(5.0));
    }

    @Test
    @DisplayName("Digital silence yields unity gain (no division by zero)")
    void digitalSilenceYieldsUnityGain()
    {
        normalizer.analyze(new float[1024], 2);
        assertEquals(1.0, normalizer.getGain(), 0.0);
    }

    @Test
    @DisplayName("Quiet input is amplified so its true peak reaches the target")
    void quietInputIsAmplifiedToTarget()
    {
        float[] buffer = { 0.25f, -0.5f, 0.10f, -0.30f };   // peak ~0.5
        normalizer.analyze(buffer, 2);
        // The gain scales the analysed true peak exactly to the -0.1 dBTP ceiling.
        double targetLin = Math.pow(10.0, -0.1 / 20.0);
        assertEquals(targetLin / normalizer.getAnalyzedPeak(), normalizer.getGain(), EPSILON);
        assertTrue(normalizer.getGain() > 1.0, "quiet input should be amplified");
    }

    @Test
    @DisplayName("Loud input is attenuated to the target peak")
    void loudInputIsAttenuatedToTarget()
    {
        normalizer.setTargetDbfs(-6.0);
        float[] buffer = { 0.9f, -0.7f, 0.4f, -0.2f };
        normalizer.analyze(buffer, 2);

        float[] output = normalizer.process(buffer.clone(), 2);
        assertEquals(Math.pow(10.0, -6.0 / 20.0), maxAbs(output), EPSILON);
        for (float s : output) assertTrue(Math.abs(s) <= 1.0f);
    }

    @Test
    @DisplayName("Disabled processor leaves the buffer unchanged")
    void disabledIsPassthrough()
    {
        normalizer.analyze(new float[] { 0.5f, -0.5f }, 2);
        normalizer.setEnabled(false);

        float[] input    = { 0.2f, -0.3f, 0.4f, -0.1f };
        float[] expected = input.clone();
        assertArrayEquals(expected, normalizer.process(input, 2), 0.0f);
    }

    private static float maxAbs(float[] buffer)
    {
        float max = 0.0f;
        for (float s : buffer)
        {
            float abs = (s >= 0.0f) ? s : -s;
            if (abs > max) max = abs;
        }
        return max;
    }
}
