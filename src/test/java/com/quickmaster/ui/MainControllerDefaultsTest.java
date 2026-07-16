package com.quickmaster.ui;

import com.quickmaster.audio.AudioFileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainControllerDefaultsTest
{
    @Test
    @DisplayName("Exports default to 48 kHz and 24-bit")
    void deliveryDefaults()
    {
        assertEquals(48_000, MainController.DEFAULT_EXPORT_SAMPLE_RATE);
        assertEquals(24, MainController.DEFAULT_EXPORT_BIT_DEPTH);
    }

    @Test
    @DisplayName("Exports keep the source basename without a mastered suffix")
    void exportFileNamesKeepSourceBasename()
    {
        String source = new File("album", "Song.name.wav").getPath();

        assertEquals("Song.name.wav", MainController.exportFileName(source, ".wav"));
        assertEquals("Song.name.mp3", MainController.exportFileName(source, "mp3"));
    }

    @Test
    @DisplayName("Batch I/O operations retry transient nested I/O failures")
    void batchIoRetriesTransientFailures() throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();

        String result = MainController.retryIo(() ->
        {
            if (attempts.incrementAndGet() < 3)
                throw new AudioFileException("WAV read failed", new IOException("file busy"));
            return "loaded";
        }, 3, 0, () -> false, null);

        assertEquals("loaded", result);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("Batch retries do not repeat non-I/O failures")
    void batchIoDoesNotRetryValidationFailures()
    {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(AudioFileException.class, () -> MainController.retryIo(() ->
        {
            attempts.incrementAndGet();
            throw new AudioFileException("Unsupported WAV encoding");
        }, 3, 0, () -> false, null));

        assertEquals(1, attempts.get());
    }
}
