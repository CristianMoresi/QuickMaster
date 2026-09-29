package com.quickmaster.audio;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Independent container parsing: no production metadata reader as the oracle. */
class FreshExportMetadataTest {
    private static final String BRAND = "Made with QuickMaster by Cristian Moresi";
    private static final Path VECTORS = Path.of("vendor/dspark-java/src/test/resources/mp3");
    @TempDir Path dir;

    @TestFactory Stream<DynamicTest> wavExportsRebuildOnlyTwoDescriptiveFields() {
        return Stream.of(16, 24, 32, 33).flatMap(encoding -> Stream.of(1, 2).map(channels ->
                DynamicTest.dynamicTest("wav-" + encoding + "-" + channels, () -> {
                    int bits = Math.min(32, encoding); boolean floating = encoding == 33;
                    float[] pcm = signal(8193, channels);
                    if (floating) { pcm[0] = 1.75f; pcm[1] = -2.25f; }
                    Path source = dir.resolve("source.wav"), target = dir.resolve("master.wav");
                    new WavFile("memory", 48000, channels, pcm, bits, floating).save(source.toString());
                    byte[] decorated = decorateWav(Files.readAllBytes(source));
                    Files.write(source, decorated);
                    WavFile loaded = new WavFile(source.toString()); loaded.load();
                    float[] decoded = loaded.getSamples().clone();
                    Instant before = Instant.now();
                    AudioExport.write(loaded, source.toString(), target.toString());
                    Map<String, byte[]> chunks = riffChunks(Files.readAllBytes(target));
                    assertEquals(floating ? Set.of("fmt ", "fact", "data", "LIST")
                            : Set.of("fmt ", "data", "LIST"), chunks.keySet());
                    Map<String, String> fields = infoFields(chunks.get("LIST"));
                    assertEquals(Set.of("ICRD", "ISFT"), fields.keySet());
                    assertEquals(BRAND, fields.get("ISFT"));
                    assertRecent(Instant.parse(fields.get("ICRD")), before);
                    WavFile result = new WavFile(target.toString()); result.load();
                    assertArrayEquals(decoded, result.getSamples(), floating ? 0 : bits == 16 ? .0000611f : .00000024f);
                    assertArrayEquals(decoded, loaded.getSamples());
                    assertArrayEquals(decorated, Files.readAllBytes(source));
                })));
    }

    @TestFactory Stream<DynamicTest> mp3ExportsDiscardSourceId3v1v2ApeArtworkAndPrivateFields() {
        return Stream.of(3, 4).flatMap(version -> Stream.of(1, 2).map(channels ->
                DynamicTest.dynamicTest("id3v2." + version + "-" + channels, () -> {
                    String vector = channels == 1 ? "mono-44100-vbr.mp3" : "stereo-48000-cbr.mp3";
                    byte[] decorated = decorateMp3(Files.readAllBytes(VECTORS.resolve(vector)), version);
                    Path source = dir.resolve("source.mp3"), target = dir.resolve("master.mp3");
                    Files.write(source, decorated);
                    Mp3File loaded = new Mp3File(source.toString()); loaded.load();
                    float[] pcm = loaded.getSamples().clone();
                    Instant before = Instant.now();
                    AudioExport.write(loaded, source.toString(), target.toString());
                    assertFreshMp3(Files.readAllBytes(target), before);
                    Mp3File result = new Mp3File(target.toString()); result.load();
                    assertEquals(loaded.getChannels(), result.getChannels());
                    assertEquals(loaded.getSampleRate(), result.getSampleRate());
                    assertTrue(result.getSamples().length >= pcm.length);
                    assertTrue(result.getSamples().length < pcm.length + 12000);
                    assertArrayEquals(pcm, loaded.getSamples());
                    assertArrayEquals(decorated, Files.readAllBytes(source));
                })));
    }

    @Test void crossFormatAndBatchUseTheSameFreshMetadataPolicy() throws Exception {
        Path wav = dir.resolve("wave.wav"), mp3 = dir.resolve("encoded.mp3");
        new WavFile("memory", 48000, 2, signal(4800, 2), 32, true).save(wav.toString());
        Files.write(wav, decorateWav(Files.readAllBytes(wav)));
        Files.write(mp3, decorateMp3(Files.readAllBytes(VECTORS.resolve("stereo-48000-cbr.mp3")), 4));
        byte[] wavOriginal = Files.readAllBytes(wav), mp3Original = Files.readAllBytes(mp3);
        List<Path> sources = List.of(wav, mp3);
        for (String extension : List.of(".wav", ".mp3")) {
            Path folder = Files.createDirectory(dir.resolve(extension.substring(1)));
            List<Path> targets = AudioExport.planBatch(sources, folder, extension);
            for (int i = 0; i < sources.size(); i++) {
                AudioFile input = i == 0 ? new WavFile(wav.toString()) : new Mp3File(mp3.toString());
                input.load();
                AudioFile output = extension.equals(".wav")
                        ? new WavFile("memory", input.getSampleRate(), input.getChannels(), input.getSamples(), 32, true)
                        : new Mp3File("memory", input.getSampleRate(), input.getChannels(), input.getSamples(), 320, false);
                Instant before = Instant.now();
                AudioExport.write(output, sources.get(i).toString(), targets.get(i).toString());
                byte[] bytes = Files.readAllBytes(targets.get(i));
                if (extension.equals(".mp3")) assertFreshMp3(bytes, before);
                else {
                    Map<String, String> info = infoFields(riffChunks(bytes).get("LIST"));
                    assertEquals(Map.of("ISFT", BRAND, "ICRD", info.get("ICRD")), info);
                    assertRecent(Instant.parse(info.get("ICRD")), before);
                }
            }
        }
        assertArrayEquals(wavOriginal, Files.readAllBytes(wav));
        assertArrayEquals(mp3Original, Files.readAllBytes(mp3));
    }

    @Test void alreadyLoadedAudioDoesNotNeedToReopenItsSourceForTags() throws Exception {
        Path target = dir.resolve("master.wav");
        WavFile output = new WavFile("missing.wav", 48000, 1, signal(31, 1), 32, true);
        Instant before = Instant.now();
        AudioExport.write(output, dir.resolve("missing.wav").toString(), target.toString());
        Map<String, String> info = infoFields(riffChunks(Files.readAllBytes(target)).get("LIST"));
        assertEquals(BRAND, info.get("ISFT"));
        assertRecent(Instant.parse(info.get("ICRD")), before);
    }

    @TestFactory Stream<DynamicTest> explicitSameFileReplacementDoesNotKeepAnyOldTags() {
        return Stream.of(".wav", ".mp3").map(extension -> DynamicTest.dynamicTest(extension, () -> {
            Path caseDir = Files.createDirectory(dir.resolve(extension.substring(1)));
            Path target = caseDir.resolve("replace" + extension);
            AudioFile loaded;
            if (extension.equals(".wav")) {
                new WavFile("memory", 48000, 2, signal(4097, 2), 32, true).save(target.toString());
                Files.write(target, decorateWav(Files.readAllBytes(target)));
                loaded = new WavFile(target.toString());
            } else {
                Files.write(target, decorateMp3(Files.readAllBytes(VECTORS.resolve("stereo-48000-cbr.mp3")), 4));
                loaded = new Mp3File(target.toString());
            }
            loaded.load(); float[] originalPcm = loaded.getSamples().clone(); Instant before = Instant.now();
            AudioExport.write(loaded, target.toString());
            byte[] result = Files.readAllBytes(target);
            if (extension.equals(".mp3")) assertFreshMp3(result, before);
            else {
                Map<String, byte[]> chunks = riffChunks(result);
                assertEquals(Set.of("fmt ", "fact", "data", "LIST"), chunks.keySet());
                assertEquals(Set.of("ICRD", "ISFT"), infoFields(chunks.get("LIST")).keySet());
            }
            assertArrayEquals(originalPcm, loaded.getSamples());
            try (var entries = Files.list(caseDir)) { assertEquals(1, entries.count()); }
        }));
    }

    @Test void metadataEncodingIsDeterministicUtcAndDoesNotInventArtistCredits() {
        ExportMetadata fields = new ExportMetadata(Instant.parse("2026-10-25T01:02:03.987654321Z"));
        byte[] chunk = fields.wavInfoChunk();
        assertEquals(Map.of("ICRD", "2026-10-25T01:02:03Z", "ISFT", BRAND),
                infoFields(Arrays.copyOfRange(chunk, 8, chunk.length)));
        assertArrayEquals(chunk, fields.wavInfoChunk());
        byte[] tag = fields.mp3Tag();
        assertArrayEquals(tag, fields.mp3Tag());
        assertTrue(new String(tag, StandardCharsets.US_ASCII).contains("2026-10-25T01:02:03"));
        for (String unwanted : List.of("TPE1", "TCOM", "TCOP", "COMM", "APIC", "PRIV"))
            assertFalse(new String(tag, StandardCharsets.US_ASCII).contains(unwanted));
    }

    @Test void cancellationAfterFreshTagsWereWrittenKeepsTheOriginalDestination() throws Exception {
        Path target = dir.resolve("keep.wav");
        byte[] original = "original file with its metadata".getBytes(StandardCharsets.US_ASCII);
        Files.write(target, original);
        WavFile candidate = new WavFile("memory", 48000, 1, signal(31, 1), 32, true) {
            @Override public void save(String path) throws AudioFileException {
                super.save(path); Thread.currentThread().interrupt();
            }
        };
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> AudioExport.write(candidate, target.toString()));
        } finally { Thread.interrupted(); }
        assertArrayEquals(original, Files.readAllBytes(target));
        try (var entries = Files.list(dir)) { assertEquals(1, entries.count()); }
    }

    private static void assertRecent(Instant actual, Instant before) {
        assertFalse(actual.isBefore(before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        assertFalse(actual.isAfter(Instant.now()));
    }

    private static void assertFreshMp3(byte[] bytes, Instant before) {
        assertEquals("ID3", ascii(bytes, 0, 3)); assertEquals(4, bytes[3]);
        assertEquals(0, bytes[4]); assertEquals(0, bytes[5]);
        int end = 10 + synchsafe(bytes, 6);
        Map<String, String> frames = new LinkedHashMap<>();
        for (int p = 10; p < end;) {
            assertTrue(p + 10 <= end);
            String id = ascii(bytes, p, 4); int length = synchsafe(bytes, p + 4);
            assertTrue(length > 0 && p + 10 + length <= end);
            assertEquals(0, bytes[p + 8]); assertEquals(0, bytes[p + 9]);
            assertEquals(3, bytes[p + 10], "UTF-8 text frame");
            assertNull(frames.put(id, new String(bytes, p + 11, length - 1, StandardCharsets.UTF_8)));
            p += 10 + length;
        }
        assertEquals(Set.of("TDEN", "TSSE"), frames.keySet());
        assertEquals(BRAND, frames.get("TSSE"));
        assertRecent(LocalDateTime.parse(frames.get("TDEN")).toInstant(ZoneOffset.UTC), before);
        assertEquals(0xff, bytes[end] & 255); assertEquals(0xe0, bytes[end + 1] & 0xe0);
        assertNotEquals("TAG", ascii(bytes, bytes.length - 128, 3));
        assertNotEquals("APETAGEX", ascii(bytes, bytes.length - 32, 8));
        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("PRIVATE_SOURCE"));
    }

    static Map<String, byte[]> riffChunks(byte[] bytes) {
        assertEquals("RIFF", ascii(bytes, 0, 4)); assertEquals("WAVE", ascii(bytes, 8, 4));
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(bytes.length - 8, b.getInt(4));
        Map<String, byte[]> result = new LinkedHashMap<>(); int p = 12;
        while (p < bytes.length) {
            assertTrue(p + 8 <= bytes.length);
            String id = ascii(bytes, p, 4); int length = b.getInt(p + 4);
            assertTrue(length >= 0 && (long)p + 8 + length <= bytes.length);
            assertNull(result.put(id, Arrays.copyOfRange(bytes, p + 8, p + 8 + length)), "duplicate " + id);
            p += 8 + length + (length & 1);
        }
        assertEquals(bytes.length, p); return result;
    }

    static Map<String, String> infoFields(byte[] bytes) {
        assertNotNull(bytes, "A fresh INFO list is required");
        assertEquals("INFO", ascii(bytes, 0, 4));
        Map<String, String> result = new LinkedHashMap<>();
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int p = 4;
        while (p < bytes.length) {
            assertTrue(p + 8 <= bytes.length); int length = b.getInt(p + 4);
            assertTrue(length > 0 && p + 8 + length <= bytes.length);
            assertEquals(0, bytes[p + 8 + length - 1]);
            assertNull(result.put(ascii(bytes, p, 4), ascii(bytes, p + 8, length - 1)));
            if ((length & 1) != 0) assertEquals(0, bytes[p + 8 + length]);
            p += 8 + length + (length & 1);
        }
        assertEquals(bytes.length, p); return result;
    }

    static byte[] decorateWav(byte[] clean) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.writeBytes(clean);
        // Include identifying, timeline, proprietary, artwork and unknown chunks.
        out.writeBytes(riffChunk("LIST", concat("INFO".getBytes(StandardCharsets.US_ASCII),
                riffChunk("INAM", "PRIVATE_SOURCE_TITLE\0".getBytes(StandardCharsets.US_ASCII)))));
        for (String id : List.of("bext", "iXML", "axml", "id3 ", "cue ", "smpl", "acid", "JUNK"))
            out.writeBytes(riffChunk(id, ("PRIVATE_SOURCE_" + id).getBytes(StandardCharsets.US_ASCII)));
        byte[] result = out.toByteArray(); ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length - 8);
        return result;
    }

    static byte[] decorateMp3(byte[] original, int version) {
        int begin = ascii(original, 0, 3).equals("ID3") ? 10 + synchsafe(original, 6) : 0;
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (String id : List.of("TIT2", "TPE1", "TALB", "TCOP", "COMM", "APIC", "PRIV", "TXXX")) {
            byte[] payload = ("\0PRIVATE_SOURCE_" + id).getBytes(StandardCharsets.US_ASCII);
            frames.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
            frames.writeBytes(version == 4 ? syncBytes(payload.length) : ByteBuffer.allocate(4).putInt(payload.length).array());
            frames.writeBytes(new byte[2]); frames.writeBytes(payload);
        }
        byte[] header = concat(new byte[]{'I','D','3',(byte)version,0,0}, syncBytes(frames.size()));
        byte[] ape = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .put("APETAGEX".getBytes(StandardCharsets.US_ASCII)).putInt(2000).putInt(32).array();
        byte[] v1 = new byte[128]; System.arraycopy("TAGPRIVATE_SOURCE".getBytes(StandardCharsets.US_ASCII), 0, v1, 0, 17);
        return concat(header, frames.toByteArray(), Arrays.copyOfRange(original, begin, original.length), ape, v1);
    }

    private static byte[] riffChunk(String id, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length + (payload.length & 1)).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.getBytes(StandardCharsets.US_ASCII)).putInt(payload.length).put(payload).array();
    }
    private static byte[] concat(byte[]... parts) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] part : parts) out.writeBytes(part); return out.toByteArray(); }
    private static byte[] syncBytes(int n) { return new byte[]{(byte)(n >>> 21 & 127),(byte)(n >>> 14 & 127),(byte)(n >>> 7 & 127),(byte)(n & 127)}; }
    private static int synchsafe(byte[] b, int p) { int n=0; for(int i=0;i<4;i++){assertEquals(0,b[p+i]&128);n=n*128+(b[p+i]&127);}return n; }
    private static String ascii(byte[] b, int p, int n) { return new String(b, p, n, StandardCharsets.US_ASCII); }
    private static float[] signal(int frames, int ch) { float[] pcm=new float[frames*ch]; for(int i=0;i<pcm.length;i++)pcm[i]=(float)(.4*Math.sin(.071*i));return pcm; }
}
