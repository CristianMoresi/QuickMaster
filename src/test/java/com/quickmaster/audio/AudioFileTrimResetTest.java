package com.quickmaster.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the non-destructive {@code trim} / {@code reset}
 * behaviour defined on {@link AudioFile}, exercised through the
 * concrete {@link WavFile} subclass (no disk I/O involved).
 */
class AudioFileTrimResetTest
{
    private static final int SR = 1000;       // 1000 Hz keeps the maths simple
    private static final int CH = 2;          // stereo

    private WavFile file;

    @BeforeEach
    void setUp()
    {
        // 1000 stereo frames = 2000 interleaved samples = 1.0 s.
        float[] samples = new float[2000];
        for (int i = 0; i < samples.length; i++)
        {
            samples[i] = i / 2000.0f;
        }
        file = new WavFile("mem.wav", SR, CH, samples, 16, false);
    }

    @Test
    @DisplayName("Duration reflects sample count, channels and rate")
    void durationIsCorrect()
    {
        assertEquals(1.0, file.getDuration(), 1e-9);
    }

    @Test
    @DisplayName("Trim removes the requested seconds from each end")
    void trimRemovesEnds()
    {
        file.trim(0.2, 0.3);   // remove 200 + 300 frames -> 500 frames remain
        assertEquals(500 * CH, file.getSamples().length);
        // First remaining sample is the original sample at frame 200.
        assertEquals(200 * CH / 2000.0f, file.getSamples()[0], 1e-9f);
    }

    @Test
    @DisplayName("Reset restores the original samples after a trim")
    void resetRestoresOriginal()
    {
        file.trim(0.2, 0.3);
        file.reset();
        assertEquals(2000, file.getSamples().length);
        assertEquals(0.0f, file.getSamples()[0], 0.0f);
    }

    @Test
    @DisplayName("Trim is non-cumulative: each call starts from the original")
    void trimIsNonCumulative()
    {
        file.trim(0.2, 0.0);   // -> 800 frames
        file.trim(0.1, 0.0);   // starts from original -> 900 frames, not 700
        assertEquals(900 * CH, file.getSamples().length);
    }

    @Test
    @DisplayName("A trim that would leave no audio is rejected")
    void overTrimIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> file.trim(0.6, 0.6));
    }
}
