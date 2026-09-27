package com.quickmaster.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class AtomicAudioWriteTest {
    @TempDir Path dir;

    @Test void partialEncoderFailurePreservesDestinationAndCleansOwnedTemporary() throws Exception {
        Path target = dir.resolve("master.wav");
        Files.writeString(target, "previous master");
        assertThrows(AudioFileException.class, () -> AtomicAudioWrite.write(target.toString(), stage -> {
            Files.writeString(stage, "partial new audio");
            throw new IOException("simulated disk full");
        }));
        assertEquals("previous master", Files.readString(target));
        assertOnly(target);
    }

    @Test void cancellationAfterEncodingDoesNotCommit() throws Exception {
        Path target = dir.resolve("master.wav");
        Files.writeString(target, "previous master");
        try {
            assertThrows(CancellationException.class, () -> AtomicAudioWrite.write(target.toString(), stage -> {
                Files.writeString(stage, "completed candidate");
                Thread.currentThread().interrupt();
            }));
        } finally { Thread.interrupted(); }
        assertEquals("previous master", Files.readString(target));
        assertOnly(target);
    }

    @Test void runtimeEncoderFailureAlsoPreservesDestination() throws Exception {
        Path target = dir.resolve("master.wav");
        Files.writeString(target, "previous master");
        assertThrows(IllegalStateException.class, () -> AtomicAudioWrite.write(target.toString(), stage -> {
            Files.writeString(stage, "partial");
            throw new IllegalStateException("encoder failed");
        }));
        assertEquals("previous master", Files.readString(target));
        assertOnly(target);
    }

    @Test void failedNewExportLeavesNoOutputOrStaging() throws Exception {
        Path target = dir.resolve("new.wav");
        assertThrows(AudioFileException.class, () -> AtomicAudioWrite.write(target.toString(), stage -> {
            Files.writeString(stage, "partial");
            throw new IOException("write failed");
        }));
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test void successfulWriteReplacesOnlyRequestedFile() throws Exception {
        Path target = dir.resolve("master.wav");
        Files.writeString(target, "previous master");
        Path sibling = dir.resolve("master.wav.tagtmp");
        Files.writeString(sibling, "unrelated user file");
        AtomicAudioWrite.write(target.toString(), stage -> Files.writeString(stage, "new master"));
        assertEquals("new master", Files.readString(target));
        assertEquals("unrelated user file", Files.readString(sibling));
        try (var files = Files.list(dir)) { assertEquals(2, files.count()); }
    }

    @Test void aDirectoryCannotBecomeAnAudioFile() throws Exception {
        Path target = Files.createDirectory(dir.resolve("master.wav"));
        assertThrows(AudioFileException.class, () -> AtomicAudioWrite.write(target.toString(), stage -> fail("must validate first")));
        assertTrue(Files.isDirectory(target));
    }

    private void assertOnly(Path target) throws IOException {
        try (var files = Files.list(dir)) { assertEquals(java.util.List.of(target), files.toList()); }
    }
}
