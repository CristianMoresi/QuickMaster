package com.quickmaster.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class AudioIoAuditTest {
    @TempDir Path dir;

    @Test void trimRejectsNonFiniteAndOverflowWithoutChangingAudio() {
        WavFile file = new WavFile("memory.wav", 48000, 2, new float[960], 32, true);
        float[] original = file.getSamples();
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> file.trim(bad, bad), "trim " + bad);
            assertSame(original, file.getSamples());
        }
    }

    @Test void floatImportRejectsNonFiniteButKeepsFiniteHeadroom() throws Exception {
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            Path path = dir.resolve("bad-" + Float.floatToRawIntBits(bad) + ".wav");
            Files.write(path, floatWav(new float[]{0, bad}, 48000, 1, 8));
            assertThrows(AudioFileException.class, () -> new WavFile(path.toString()).load());
        }
        Path valid = dir.resolve("headroom.wav");
        Files.write(valid, floatWav(new float[]{1.5f, -2.25f, Float.MIN_NORMAL}, 48000, 1, 12));
        WavFile loaded = new WavFile(valid.toString());
        loaded.load();
        assertArrayEquals(new float[]{1.5f, -2.25f, Float.MIN_NORMAL}, loaded.getSamples());
    }

    @Test void truncatedPayloadIsNotAcceptedAsACompleteSong() throws Exception {
        Path path = dir.resolve("truncated.wav");
        Files.write(path, floatWav(new float[]{0.25f}, 48000, 1, 16));
        assertThrows(AudioFileException.class, () -> new WavFile(path.toString()).load());
    }

    @Test void invalidExportsMustNotReplaceExistingAudio() throws Exception {
        Path target = dir.resolve("keep.wav");
        byte[] original = {1, 2, 3, 4};
        for (WavFile invalid : new WavFile[]{
                new WavFile("memory", 48000, 1, new float[]{Float.NaN}, 32, true),
                new WavFile("memory", 48000, 1, new float[]{Float.POSITIVE_INFINITY}, 24, false),
                new WavFile("memory", 48000, 2, new float[]{0, 0, 0}, 16, false),
                new WavFile("memory", 48000, 1, new float[]{0}, 16, true)}) {
            Files.write(target, original);
            assertThrows(AudioFileException.class, () -> invalid.save(target.toString()));
            assertArrayEquals(original, Files.readAllBytes(target));
        }
    }

    @Test void cancelledWavWriteMustNotReplaceExistingAudio() throws Exception {
        Path target = dir.resolve("keep.wav");
        byte[] original = {9, 8, 7};
        Files.write(target, original);
        WavFile output = new WavFile("memory", 48000, 1, new float[64], 24, false);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> output.save(target.toString()));
        } finally { Thread.interrupted(); }
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test void integer32UsesTwosComplementNegativeEndpoint() throws Exception {
        Path target = dir.resolve("endpoint.wav");
        new WavFile("memory", 48000, 1, new float[]{-1, 0, 1}, 32, false).save(target.toString());
        byte[] raw = Files.readAllBytes(target);
        int data = dataOffset(raw);
        ByteBuffer pcm = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Integer.MIN_VALUE, pcm.getInt(data));
        assertEquals(0, pcm.getInt(data + 4));
        assertEquals(Integer.MAX_VALUE, pcm.getInt(data + 8));
    }

    @Test void oddSizedPcmHasRiffWordPadding() throws Exception {
        Path target = dir.resolve("odd.wav");
        new WavFile("memory", 48000, 1, new float[]{0.25f}, 24, false).save(target.toString());
        byte[] raw = Files.readAllBytes(target);
        int data = dataOffset(raw);
        assertEquals(3, ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(data - 4));
        assertEquals(0, raw.length % 2, "RIFF chunks require a pad byte outside the data size");
        assertEquals(raw.length - 8, ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
    }

    @Test void wavFormatMatrixPreservesFramesChannelsAndFiniteHeadroom() throws Exception {
        for (int channels : new int[]{1, 2}) for (int rate : new int[]{32000, 44100, 48000, 96000, 192000}) {
            for (int encoding : new int[]{16, 24, 32, 33}) {
                boolean floating = encoding == 33;
                int bits = floating ? 32 : encoding;
                float[] pcm = new float[8193 * channels]; // both a full block and odd tail
                for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (0.8 * Math.sin(i * 0.23));
                if (floating) { pcm[0] = 1.7f; pcm[1] = -2.3f; }
                float[] original = pcm.clone();
                Path path = dir.resolve("matrix-" + channels + "-" + rate + "-" + encoding + ".wav");
                new WavFile("memory", rate, channels, pcm, bits, floating).save(path.toString());
                WavFile read = new WavFile(path.toString()); read.load();
                assertEquals(rate, read.getSampleRate()); assertEquals(channels, read.getChannels());
                assertArrayEquals(original, pcm, "save must not mutate source PCM");
                float tolerance = floating ? 0 : bits == 16 ? 0.0000611f : 0.00000024f;
                assertArrayEquals(pcm, read.getSamples(), tolerance, "rate=" + rate + " bits=" + bits);
            }
        }
    }

    @Test void integerDitherIsUnbiasedAndNotStereoCorrelated() throws Exception {
        for (int bits : new int[]{16, 24}) {
            Path path = dir.resolve("dither-" + bits + ".wav");
            new WavFile("memory", 48000, 2, new float[96000], bits, false).save(path.toString());
            WavFile read = new WavFile(path.toString()); read.load();
            float[] pcm = read.getSamples();
            double scale = Math.scalb(1.0, bits - 1), leftSum = 0, rightSum = 0, energy = 0, cross = 0;
            for (int i = 0; i < pcm.length; i += 2) {
                double left = pcm[i] * scale, right = pcm[i + 1] * scale;
                assertTrue(Math.abs(left) <= 1 && Math.abs(right) <= 1);
                assertEquals(Math.rint(left), left); assertEquals(Math.rint(right), right);
                leftSum += left; rightSum += right; energy += left * left + right * right; cross += left * right;
            }
            assertTrue(Math.abs(leftSum / 48000) < 0.02 && Math.abs(rightSum / 48000) < 0.02);
            assertTrue(energy / 96000 > 0.20 && energy / 96000 < 0.30);
            assertTrue(Math.abs(cross / 48000) < 0.02);
        }
    }

    static byte[] floatWav(float[] pcm, int rate, int channels, int declaredBytes) {
        ByteBuffer b = ByteBuffer.allocate(44 + 4 * pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + declaredBytes).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 3).putShort((short) channels);
        b.putInt(rate).putInt(rate * channels * 4).putShort((short) (channels * 4)).putShort((short) 32);
        b.putInt(0x61746164).putInt(declaredBytes);
        for (float sample : pcm) b.putFloat(sample);
        return b.array();
    }

    static int dataOffset(byte[] raw) {
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int pos = 12; pos + 8 <= raw.length;) {
            int size = b.getInt(pos + 4);
            if (b.getInt(pos) == 0x61746164) return pos + 8;
            pos += 8 + size + (size & 1);
        }
        throw new AssertionError("Missing data chunk");
    }
}
