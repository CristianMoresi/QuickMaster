package com.quickmaster.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AudioExportTest {
    @TempDir Path dir;

    @Test void sameDestinationCommitsOnlyNewlyEncodedBytesWithoutCopyingOldTags() throws Exception {
        Path target = dir.resolve("original.mp3");
        byte[] original = "ID3 original metadata and audio TAG".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(target, original);
        AudioFile candidate = new WavFile("unused") {
            @Override public void save(String path) throws AudioFileException {
                try { Files.write(Path.of(path), new byte[]{9, 8, 7}); }
                catch (IOException e) { throw new AudioFileException("fake encoder", e); }
            }
        };
        AudioExport.write(candidate, target.toString(), target.toString());
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(target));
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
    }

    @Test void aFailingEncoderCannotReplaceOrStripTheSource() throws Exception {
        Path target = dir.resolve("source.wav");
        Files.writeString(target, "original with metadata");
        AudioFile candidate = new WavFile("unused") {
            @Override public void save(String path) throws AudioFileException {
                try { Files.writeString(Path.of(path), "incomplete"); }
                catch (IOException e) { throw new AudioFileException("write", e); }
                throw new AudioFileException("encoder failure");
            }
        };
        assertThrows(AudioFileException.class, () -> AudioExport.write(candidate, target.toString(), target.toString()));
        assertEquals("original with metadata", Files.readString(target));
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
    }

    @Test void batchRejectsOutputNameCollisionsBeforeWritingAnything() {
        assertThrows(IOException.class, () -> AudioExport.planBatch(
                List.of(dir.resolve("song.wav"), dir.resolve("song.mp3")), dir.resolve("masters"), ".wav"));
        assertThrows(IOException.class, () -> AudioExport.planBatch(
                List.of(dir.resolve("SONG.wav"), dir.resolve("song.mp3")), dir.resolve("masters"), ".mp3"));
    }

    @Test void batchCannotOverwriteAnySourceIncludingAnotherSongsPath() throws Exception {
        Path a = dir.resolve("a.wav"), b = dir.resolve("a.mp3");
        Files.writeString(a, "first"); Files.writeString(b, "second");
        assertThrows(IOException.class, () -> AudioExport.planBatch(List.of(a, b), dir, ".mp3"));
        assertEquals("first", Files.readString(a)); assertEquals("second", Files.readString(b));
    }

    @Test void batchRetainsNamesAndOrderInASeparateFolder() throws Exception {
        Path a = dir.resolve("a.mp3"), b = dir.resolve("b.wav"), output = dir.resolve("masters");
        assertEquals(List.of(output.resolve("a.wav"), output.resolve("b.wav")),
                AudioExport.planBatch(List.of(a, b), output, ".wav"));
        assertFalse(Files.exists(output), "preflight must not create files");
    }
}
