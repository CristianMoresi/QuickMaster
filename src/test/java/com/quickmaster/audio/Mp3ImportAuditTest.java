package com.quickmaster.audio;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class Mp3ImportAuditTest {
    private static final Path FIXTURES = Path.of("vendor/dspark-java/src/test/resources/mp3");
    @TempDir Path temp;

    @TestFactory Stream<DynamicTest> applicationApiDecodesAllMpegVersionsAsFloat() {
        return Stream.of("stereo-48000-cbr", "mono-44100-vbr", "stereo-32000-lowrate", "mono-48000-quiet",
                "stereo-48000-headroom", "mono-24000-mpeg2", "stereo-11025-mpeg25").map(name -> DynamicTest.dynamicTest(name, () -> {
            Path source = FIXTURES.resolve(name + ".mp3"); byte[] original = Files.readAllBytes(source);
            Mp3File mp3 = new Mp3File(source.toString()); mp3.load();
            FloatBuffer reference = ByteBuffer.wrap(Files.readAllBytes(FIXTURES.resolve(name + ".ffmpeg.f32le")))
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] samples = mp3.getSamples();
            assertEquals(reference.remaining(), samples.length, "Gapless output length");
            assertEquals(name.startsWith("mono") ? 1 : 2, mp3.getChannels());
            assertEquals(Integer.parseInt(name.split("-")[1]), mp3.getSampleRate());
            double peak = 0, error = 0, energy = 0;
            for (float sample : samples) {
                assertTrue(Float.isFinite(sample)); double expected = reference.get();
                double delta = sample - expected; peak = Math.max(peak, Math.abs(delta)); error += delta * delta; energy += expected * expected;
            }
            assertTrue(peak < 4e-6, "Maximum sample error " + peak);
            if (!name.contains("quiet")) assertTrue(error / energy < 1e-10, "Relative reference error");
            assertArrayEquals(original, Files.readAllBytes(source), "Never rewrite source metadata or audio");
            float[] baseline = samples.clone(); mp3.getSamples()[0] = .75f; mp3.reset();
            assertArrayEquals(baseline, mp3.getSamples());
        }));
    }

    @Test void failedReloadCannotReplacePreviouslyLoadedAudio() throws Exception {
        Path path = temp.resolve("track.mp3"); Files.copy(FIXTURES.resolve("stereo-48000-cbr.mp3"), path);
        Mp3File mp3 = new Mp3File(path.toString()); mp3.load();
        float[] prior = mp3.getSamples().clone(); int bitrate = mp3.getBitrate();
        Files.write(path, new byte[]{1, 2, 3, 4});
        assertThrows(AudioFileException.class, mp3::load);
        assertArrayEquals(prior, mp3.getSamples()); assertEquals(48000, mp3.getSampleRate());
        assertEquals(2, mp3.getChannels()); assertEquals(bitrate, mp3.getBitrate());
    }

    @Test void cancelledImportPublishesNothing() throws Exception {
        Mp3File mp3 = new Mp3File(FIXTURES.resolve("stereo-48000-cbr.mp3").toString());
        try {
            Thread.currentThread().interrupt(); assertThrows(CancellationException.class, mp3::load);
        } finally { Thread.interrupted(); }
        assertNull(mp3.getSamples()); assertEquals(0, mp3.getSampleRate());
    }
}
