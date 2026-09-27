package com.quickmaster.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class Mp3ExportAuditTest {
    @TempDir Path dir;

    @Test void nonFinitePcmIsRejectedBeforeReplacingDestination() throws Exception {
        Path target = dir.resolve("keep.mp3");
        Files.writeString(target, "existing audio");
        assertThrows(AudioFileException.class, () -> new Mp3File("memory", 48000, 1,
                new float[]{Float.NaN}, 320, false).save(target.toString()));
        assertEquals("existing audio", Files.readString(target));
    }

    @Test void interruptedEncodingDoesNotTouchDestination() throws Exception {
        Path target = dir.resolve("keep.mp3");
        Files.writeString(target, "existing audio");
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> new Mp3File("memory", 48000, 1,
                    new float[4800], 320, false).save(target.toString()));
        } finally { Thread.interrupted(); }
        assertEquals("existing audio", Files.readString(target));
    }

    @Test void incompleteStereoFrameIsRejected() {
        assertThrows(AudioFileException.class, () -> new Mp3File("memory", 48000, 2,
                new float[]{0, 0, 0}, 320, false).save(dir.resolve("bad.mp3").toString()));
    }

    @Test void encodedBlocksProduceDecodableStereoAudio() throws Exception {
        float[] samples = new float[48000 * 2];
        for (int i = 0; i < samples.length / 2; i++) {
            samples[2 * i] = (float) (0.4 * Math.sin(2 * Math.PI * 440 * i / 48000));
            samples[2 * i + 1] = (float) (0.3 * Math.sin(2 * Math.PI * 880 * i / 48000));
        }
        Path target = dir.resolve("valid.mp3");
        new Mp3File("memory", 48000, 2, samples, 320, false).save(target.toString());
        Mp3File decoded = new Mp3File(target.toString());
        decoded.load();
        assertEquals(48000, decoded.getSampleRate());
        assertEquals(2, decoded.getChannels());
        assertTrue(decoded.getSamples().length >= samples.length);
        assertTrue(decoded.getSamples().length < samples.length + 12000);
        double energy = 0;
        for (float sample : decoded.getSamples()) {
            assertTrue(Float.isFinite(sample));
            energy += sample * sample;
        }
        assertTrue(energy / decoded.getSamples().length > 0.01);
    }
}
