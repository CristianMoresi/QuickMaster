package com.quickmaster.processing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Unit tests for {@link FadeProcessor}. */
class FadeProcessorTest
{
    private static final float EPS = 1e-6f;

    @Test
    @DisplayName("No fade configured is a passthrough")
    void noFadeIsPassthrough()
    {
        FadeProcessor fade = new FadeProcessor(0.0, 0.0);
        fade.prepare(1000, 0);

        float[] input = { 1f, 1f, 1f, 1f };
        float[] expected = input.clone();
        assertArrayEquals(expected, fade.process(input, 1), EPS);
    }

    @Test
    @DisplayName("Disabled processor leaves the buffer unchanged")
    void disabledIsPassthrough()
    {
        FadeProcessor fade = new FadeProcessor(1.0, 1.0);
        fade.prepare(1000, 0);
        fade.setEnabled(false);

        float[] input = { 1f, 1f, 1f, 1f };
        float[] expected = input.clone();
        assertArrayEquals(expected, fade.process(input, 1), 0.0f);
    }

    @Test
    @DisplayName("Linear fade-in ramps from 0 to 1 over the configured time")
    void fadeInIsLinearRamp()
    {
        // sampleRate 1000, fade-in 0.004 s -> 4 frames of ramp.
        FadeProcessor fade = new FadeProcessor(0.004, 0.0);
        fade.prepare(1000, 8);   // mono, 8 frames total

        float[] input = { 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f };
        float[] out = fade.process(input, 1);

        float[] expected = { 0f, 0.25f, 0.5f, 0.75f, 1f, 1f, 1f, 1f };
        assertArrayEquals(expected, out, EPS);
    }

    @Test
    @DisplayName("Linear fade-out ramps from 1 to 0 at the tail")
    void fadeOutIsLinearRamp()
    {
        // sampleRate 1000, fade-out 0.004 s -> last 4 frames ramp down.
        FadeProcessor fade = new FadeProcessor(0.0, 0.004);
        fade.prepare(1000, 8);

        float[] input = { 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f };
        float[] out = fade.process(input, 1);

        // Fade-out starts at frame 4: factor = 1 - (f-4)/4.
        float[] expected = { 1f, 1f, 1f, 1f, 1f, 0.75f, 0.5f, 0.25f };
        assertArrayEquals(expected, out, EPS);
    }
}
