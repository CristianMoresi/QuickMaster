package com.quickmaster.ui.waveform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class WaveformPeakIndexTest
{
    @Test
    void matchesDirectPeakAcrossBlockEdgesAndRandomZoomRanges()
    {
        Random random = new Random(0x57A7E);
        float[] samples = new float[70_001 * 2];
        for (int i = 0; i < samples.length; i++) samples[i] = (random.nextFloat() * 2.0f - 1.0f);
        samples[256 * 2 + 1] = -2.0f;
        samples[65_536 * 2] = 3.0f;
        WaveformPeakIndex index = new WaveformPeakIndex(samples, 2);
        assertEquals(70_001, index.frames());
        for (int trial = 0; trial < 2_000; trial++)
        {
            int start = random.nextInt(index.frames() + 1);
            int end = Math.min(index.frames(), start + random.nextInt(1_000));
            float expected = 0.0f;
            for (int frame = start; frame < end; frame++)
                for (int channel = 0; channel < 2; channel++)
                    expected = Math.max(expected, Math.abs(samples[frame * 2 + channel]));
            assertEquals(expected, index.peak(start, end));
        }
        assertEquals(3.0f, index.peak(0, index.frames()));
        assertEquals(0.0f, index.peak(256, 256));
        assertThrows(IllegalArgumentException.class, () -> index.peak(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> index.peak(5, 4));
        assertThrows(IllegalArgumentException.class, () -> index.peak(0, index.frames() + 1));
        assertThrows(IllegalArgumentException.class, () -> new WaveformPeakIndex(new float[3], 2));
    }

    @Test
    void columnsFollowViewportAndRetainFirstAndLastFrame()
    {
        float[] samples = new float[1_000];
        samples[0] = 0.5f;
        samples[249] = -0.7f;
        samples[250] = 0.8f;
        samples[500] = 0.9f;
        samples[999] = -1.0f;
        WaveformPeakIndex index = new WaveformPeakIndex(samples, 1);
        float[] full = index.columns(WaveformViewport.fullView(10), 100, 10);
        assertEquals(0.5f, full[0]);
        assertEquals(0.8f, full[2]);
        assertEquals(0.9f, full[5]);
        assertEquals(1.0f, full[9]);

        float[] zoomed = index.columns(new WaveformViewport(10, 2, 2), 100, 4);
        assertEquals(0.7f, zoomed[0]);
        assertEquals(0.8f, zoomed[1]);
        assertEquals(0.0f, zoomed[2]);

        float[] tiny = {0.0f, 0.0f, 1.0f};
        float[] wide = new WaveformPeakIndex(tiny, 1)
                .columns(WaveformViewport.fullView(0.3), 10, 100);
        assertEquals(1.0f, wide[99]);
    }
}
