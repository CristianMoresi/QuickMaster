package com.quickmaster.audio;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;

/** A failed or cancelled encoder must never truncate the user's destination. */
final class AtomicAudioWrite {
    private AtomicAudioWrite() { }

    @FunctionalInterface interface Writer {
        void write(Path staging) throws IOException, AudioFileException;
    }

    static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Audio file operation cancelled.");
    }

    static void write(String destination, Writer writer) throws AudioFileException {
        checkCancelled();
        Path staging = null;
        try {
            Path target = Path.of(destination).toAbsolutePath().normalize();
            if (Files.exists(target) && !Files.isRegularFile(target))
                throw new IOException("Destination is not a regular file: " + target);
            if (Files.isSymbolicLink(target))
                throw new IOException("Refusing to replace a symbolic link: " + target);
            String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            String suffix = name.endsWith(".wav") ? ".wav" : name.endsWith(".mp3") ? ".mp3" : ".tmp";
            staging = Files.createTempFile(target.getParent(), ".quickmaster-", suffix);
            writer.write(staging);
            checkCancelled();
            try (FileChannel file = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                file.force(true);
            }
            checkCancelled();
            // Fail closed if this file system cannot atomically replace the file.
            // A non-atomic fallback could destroy the previous master on I/O failure.
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new AudioFileException("Could not safely save audio: " + destination, e);
        } finally {
            if (staging != null) {
                try { Files.deleteIfExists(staging); }
                catch (IOException e) {
                    com.quickmaster.config.AppLogger.warn("Could not remove audio staging file: " + staging);
                }
            }
        }
    }

    static void validateSamples(float[] samples, int rate, int channels) throws AudioFileException {
        checkCancelled();
        if (rate <= 0 || channels < 1 || channels > 2 || samples == null
                || samples.length == 0 || samples.length % channels != 0)
            throw new AudioFileException("Invalid audio: require a positive sample rate, mono/stereo and complete frames.");
        for (int i = 0; i < samples.length; i++) {
            if ((i & 16383) == 0) checkCancelled();
            if (!Float.isFinite(samples[i]))
                throw new AudioFileException("Non-finite audio sample at index " + i + ".");
        }
    }
}
