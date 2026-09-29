package com.dspark.io;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class Mp3DecoderTest {
    static byte[] resource(String name) throws IOException {
        try (InputStream in = Mp3DecoderTest.class.getResourceAsStream("/mp3/" + name)) {
            if (in == null) throw new FileNotFoundException(name);
            return in.readAllBytes();
        }
    }
    private static final String CBR = "stereo-48000-cbr";
    private static String[] fixtures() {
        List<String> names = new ArrayList<>(List.of(CBR, "mono-44100-vbr", "stereo-32000-lowrate",
                "stereo-44100-transients", "mono-48000-quiet", "stereo-48000-headroom"));
        for (String shape : List.of("long", "short", "mixed", "mixed-low"))
            for (int mode : new int[]{1, 3}) for (int sf : new int[]{3, 7})
                names.add("intensity-" + shape + "-" + mode + "-" + sf);
        return names.toArray(String[]::new);
    }

    @TestFactory Stream<DynamicTest> independentFloatReferenceVectors() {
        return Arrays.stream(fixtures()).map(name -> DynamicTest.dynamicTest(name, () -> {
            Mp3Stream.Audio audio = Mp3Decoder.decode(Mp3Stream.fromBytes(resource(name + ".mp3")));
            FloatBuffer reference = ByteBuffer.wrap(resource(name + ".ffmpeg.f32le"))
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            assertEquals(reference.remaining(), audio.samples().length, "Gapless frame count");
            double peak = 0, error = 0, energy = 0;
            for (float value : audio.samples()) {
                assertTrue(Float.isFinite(value));
                float expected = reference.get(); double delta = value - (double) expected;
                peak = Math.max(peak, Math.abs(delta)); error += delta * delta; energy += expected * (double) expected;
            }
            assertTrue(energy > 1e-9, "Nonempty reference");
            assertTrue(peak < (name.contains("quiet") ? 2e-7 : 4e-6), "Maximum sample error: " + peak);
            if (!name.contains("quiet")) assertTrue(error / energy < 1e-10, "Relative error must be below -100 dB");
        }));
    }

    @Test void floatingHeadroomAndSignalsBelowPcm16ArePreserved() throws Exception {
        float[] loud = Mp3Decoder.decode(Mp3Stream.fromBytes(resource("stereo-48000-headroom.mp3"))).samples();
        int overs = 0; for (float v : loud) if (Math.abs(v) > 1) overs++;
        assertTrue(overs > 9000);
        float[] quiet = Mp3Decoder.decode(Mp3Stream.fromBytes(resource("mono-48000-quiet.mp3"))).samples();
        int below = 0; for (float v : quiet) if (Math.abs(v) > 1e-8 && Math.abs(v) < 1.0 / 32768) below++;
        assertTrue(below > quiet.length * .98);
        FloatBuffer thirdOracle = ByteBuffer.wrap(resource("mono-48000-quiet.jlayer.f32le"))
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        assertEquals(quiet.length, thirdOracle.remaining());
        for (float sample : quiet) assertEquals(thirdOracle.get(), sample, 2e-11);
    }

    @Test void id3RemovalDoesNotChangeGaplessCrcOrPcm() throws Exception {
        for (String name : new String[]{CBR, "mono-44100-vbr", "mono-48000-quiet"}) {
            byte[] bytes = resource(name + ".mp3"); int first = id3End(bytes);
            var tagged = Mp3Decoder.decode(Mp3Stream.fromBytes(bytes));
            var raw = Mp3Decoder.decode(Mp3Stream.fromBytes(Arrays.copyOfRange(bytes, first, bytes.length)));
            assertArrayEquals(tagged.samples(), raw.samples());
            assertEquals(tagged.variableBitrate(), raw.variableBitrate());
        }
    }

    @Test void cbrDoesNotBecomeVbrBecauseTheInfoFrameHasADifferentBitrate() throws Exception {
        for (String name : new String[]{CBR, "mono-48000-quiet", "stereo-32000-lowrate"})
            assertFalse(Mp3Stream.fromBytes(resource(name + ".mp3")).variableBitrate());
        assertTrue(Mp3Stream.fromBytes(resource("mono-44100-vbr.mp3")).variableBitrate());
    }

    @Test void metadataAndTrailingPaddingDoNotChangeAudio() throws Exception {
        byte[] audio = resource(CBR + ".mp3");
        float[] expected = Mp3Decoder.decode(Mp3Stream.fromBytes(audio)).samples();
        byte[] withId3v1 = Arrays.copyOf(audio, audio.length + 128);
        System.arraycopy(new byte[]{'T', 'A', 'G'}, 0, withId3v1, audio.length, 3);
        assertArrayEquals(expected, Mp3Decoder.decode(Mp3Stream.fromBytes(withId3v1)).samples());
        assertArrayEquals(expected, Mp3Decoder.decode(Mp3Stream.fromBytes(Arrays.copyOf(audio, audio.length + 100))).samples());
        byte[] malformed = Arrays.copyOf(audio, audio.length + 30); malformed[malformed.length - 1] = 42;
        assertThrows(IOException.class, () -> Mp3Stream.fromBytes(malformed));
    }

    @Test void truncatedFramesHeadersAndMissingReservoirAreRejected() throws Exception {
        byte[] bytes = resource(CBR + ".mp3");
        assertThrows(IOException.class, () -> Mp3Stream.fromBytes(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IOException.class, () -> Mp3Stream.fromBytes(new byte[5]));
        byte[] badId3 = bytes.clone(); badId3[6] = (byte) 0xff;
        assertThrows(IOException.class, () -> Mp3Stream.fromBytes(badId3));
        Mp3Stream stream = Mp3Stream.fromBytes(bytes);
        int first = stream.offsets[0]; bytes[first + stream.header(first).headerSize()] = (byte) 0xff;
        assertThrows(IOException.class, () -> Mp3Decoder.decode(Mp3Stream.fromBytes(bytes)));
    }

    @Test void apeFooterOnlyAndHeaderFooterTagsStayOutsideAudio() throws Exception {
        byte[] audio = resource(CBR + ".mp3");
        float[] expected = Mp3Decoder.decode(Mp3Stream.fromBytes(audio)).samples();
        for (boolean header : new boolean[]{false, true}) {
            ByteBuffer bytes = ByteBuffer.allocate(audio.length + (header ? 64 : 32)).order(ByteOrder.LITTLE_ENDIAN);
            bytes.put(audio);
            if (header) ape(bytes, 0xa0000000);
            ape(bytes, header ? 0x80000000 : 0);
            assertArrayEquals(expected, Mp3Decoder.decode(Mp3Stream.fromBytes(bytes.array())).samples());
            bytes.putInt(bytes.capacity() - 20, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> Mp3Stream.fromBytes(bytes.array()));
        }
    }
    private static void ape(ByteBuffer bytes, int flags) {
        bytes.put(new byte[]{'A','P','E','T','A','G','E','X'}).putInt(2000).putInt(32).putInt(0).putInt(flags).putLong(0);
    }

    @Test void immutableInputAndIndependentConcurrentSynthesis() throws Exception {
        byte[] bytes = resource(CBR + ".mp3"); Mp3Stream stream = Mp3Stream.fromBytes(bytes);
        Arrays.fill(bytes, (byte) 0);
        float[] expected = Mp3Decoder.decode(stream).samples();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<float[]> a = pool.submit(() -> Mp3Decoder.decode(stream).samples());
            Future<float[]> b = pool.submit(() -> Mp3Decoder.decode(stream).samples());
            assertArrayEquals(expected, a.get(10, TimeUnit.SECONDS));
            assertArrayEquals(expected, b.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test void cancellationAndWrongMpegVersionAreExplicit() throws Exception {
        Mp3Stream stream = Mp3Stream.fromBytes(resource(CBR + ".mp3"));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> Mp3Decoder.decode(stream));
        } finally { Thread.interrupted(); }
        assertThrows(IOException.class, () -> Mp3Decoder.decode(Mp3Stream.fromBytes(resource("mono-24000-mpeg2.mp3"))));
    }

    @Test void corruptedSideInformationCannotEscapeCheckedBounds() throws Exception {
        byte[] original = resource(CBR + ".mp3"); Mp3Stream stream = Mp3Stream.fromBytes(original);
        int first = stream.offsets[0], side = first + stream.header(first).headerSize();
        Random random = new Random(722193);
        for (int trial = 0; trial < 120; trial++) {
            byte[] damaged = original.clone();
            for (int i = 0; i < 4; i++) damaged[side + random.nextInt(32)] = (byte) random.nextInt(256);
            try { Mp3Decoder.decode(Mp3Stream.fromBytes(damaged)); }
            catch (IOException expected) { /* checked corruption, not out-of-bounds or partial success */ }
        }
    }

    private static int id3End(byte[] data) {
        return 10 + ((data[6] & 127) << 21) + ((data[7] & 127) << 14) + ((data[8] & 127) << 7) + (data[9] & 127);
    }
}
