package com.quickmaster.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip tests for {@link WavFile}: encode a known buffer to disk,
 * decode it back, and verify the samples survive within the precision
 * of the chosen bit depth.
 */
class WavFileRoundTripTest
{
    private static float[] ramp(int n)
    {
        float[] s = new float[n];
        for (int i = 0; i < n; i++)
        {
            // Symmetric ramp in [-0.9, +0.9], stereo-interleaved.
            s[i] = (float) (0.9 * Math.sin(i * 0.05));
        }
        return s;
    }

    @Test
    @DisplayName("16-bit integer WAV round-trips within quantisation error")
    void roundTrip16BitInt(@TempDir Path dir) throws Exception
    {
        float[] samples = ramp(2000);
        Path path = dir.resolve("test16.wav");

        new WavFile(path.toString(), 44100, 2, samples, 16, false).save(path.toString());

        WavFile loaded = new WavFile(path.toString());
        loaded.load();

        assertEquals(44100, loaded.getSampleRate());
        assertEquals(2, loaded.getChannels());
        assertEquals(16, loaded.getBitDepth());
        assertEquals(samples.length, loaded.getSamples().length);
        for (int i = 0; i < samples.length; i++)
        {
            // 16-bit step is ~3.05e-5; allow a little headroom.
            assertEquals(samples[i], loaded.getSamples()[i], 1e-4f);
        }
    }

    @Test
    @DisplayName("32-bit float WAV round-trips exactly")
    void roundTrip32BitFloat(@TempDir Path dir) throws Exception
    {
        float[] samples = ramp(1000);
        Path path = dir.resolve("test32f.wav");

        new WavFile(path.toString(), 48000, 1, samples, 32, true).save(path.toString());

        WavFile loaded = new WavFile(path.toString());
        loaded.load();

        assertEquals(48000, loaded.getSampleRate());
        assertEquals(1, loaded.getChannels());
        assertEquals(samples.length, loaded.getSamples().length);
        for (int i = 0; i < samples.length; i++)
        {
            assertEquals(samples[i], loaded.getSamples()[i], 0.0f);
        }
    }
}
