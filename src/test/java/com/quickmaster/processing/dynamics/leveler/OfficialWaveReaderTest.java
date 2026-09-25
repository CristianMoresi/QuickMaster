package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quickmaster.processing.dynamics.leveler.model.*;

class OfficialWaveReaderTest
{
    @TempDir Path temp;

    @Test
    void pcm16And24AndExtensibleDecodeSignedUnitEndpointsAndPhysicalEof() throws Exception
    {
        for (int bits : new int[] {16, 24})
            for (boolean extended : new boolean[] {false, true})
            {
                int max = (1 << (bits - 1)) - 1;
                int min = -(1 << (bits - 1));
                byte[] wave = wave(bits, 1, extended, new int[] {min, 0, max}, false);
                Path file = temp.resolve("pcm-" + bits + "-" + extended + ".wav");
                Files.write(file, wave);
                RequiredOfficialReading required = required(file, bits, 1, extended, 3, ReadingKind.MOMENTARY, ReadoutMode.STEADY_EOF);
                try (OfficialWaveReader reader = new OfficialWaveReader(file, required))
                {
                    float[] output = new float[3];
                    assertEquals(3, reader.readPcm(output));
                    assertArrayEquals(new float[] {-1f, 0f, max / (float) (1 << (bits - 1))}, output);
                    assertFalse(reader.eofReached());
                    assertEquals(-1, reader.readPcm(output));
                    reader.finish();
                    assertTrue(reader.eofReached());
                    assertEquals(3L, reader.framesRead());
                    assertEquals(wave.length, reader.bytesRead());
                }
            }
    }

    @Test
    void unknownOddChunkWithPaddingIsBoundedAndIncompleteDecodeCannotClaimEof() throws Exception
    {
        Path file = temp.resolve("bounded-junk.wav");
        Files.write(file, wave(16, 2, false, new int[] {-10, 10, 0, 0, 15, -15}, true));
        try (OfficialWaveReader reader = new OfficialWaveReader(file, required(file, 16, 2, false, 3,
                ReadingKind.MOMENTARY, ReadoutMode.STEADY_EOF)))
        {
            assertEquals(1, reader.readPcm(new float[2]));
            assertThrows(IllegalArgumentException.class, reader::finish);
            assertFalse(reader.eofReached());
            assertEquals(2, reader.readPcm(new float[4]));
            reader.finish();
        }
    }

    @Test
    void hostileHeadersAreRejectedAfterMatchingMutantHashRatherThanOnlyByHash() throws Exception
    {
        byte[] plain = wave(16, 2, false, new int[] {0, 1, -1, 0}, false);
        for (int offset : new int[] {0, 8, 20, 22, 24, 28, 32, 34, 36})
        {
            byte[] mutant = plain.clone();
            mutant[offset] ^= 1;
            Path file = temp.resolve("header-" + offset + ".wav");
            Files.write(file, mutant);
            RequiredOfficialReading req = required(file, 16, 2, false, 2, ReadingKind.MOMENTARY, ReadoutMode.STEADY_EOF);
            assertThrows(IllegalArgumentException.class, () -> new OfficialWaveReader(file, req), "offset " + offset);
        }
        byte[] extended = wave(24, 2, true, new int[] {0, 1, -1, 0}, false);
        for (int offset : new int[] {36, 38, 40, 44, 55})
        {
            byte[] mutant = extended.clone();
            mutant[offset] ^= 1;
            Path file = temp.resolve("waveex-" + offset + ".wav");
            Files.write(file, mutant);
            RequiredOfficialReading req = required(file, 24, 2, true, 2, ReadingKind.MOMENTARY, ReadoutMode.STEADY_EOF);
            assertThrows(IllegalArgumentException.class, () -> new OfficialWaveReader(file, req), "WAVEEX offset " + offset);
        }
    }

    @Test
    void duplicateChunksOverflowTruncationAndUnpinnedShortRiffAreRejected() throws Exception
    {
        byte[] original = wave(16, 1, false, new int[] {1, 2, 3, 4}, false);
        byte[] shortRiff = original.clone();
        ByteBuffer.wrap(shortRiff).order(ByteOrder.LITTLE_ENDIAN).putInt(4, shortRiff.length - 16);
        byte[] hugeData = original.clone();
        ByteBuffer.wrap(hugeData).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1);
        byte[] missingData = original.clone();
        ByteBuffer.wrap(missingData).order(ByteOrder.LITTLE_ENDIAN).putInt(36, 0x4b4e554a);
        byte[] duplicateFmt = new byte[original.length + 24];
        System.arraycopy(original, 0, duplicateFmt, 0, 36);
        System.arraycopy(original, 12, duplicateFmt, 36, 24);
        System.arraycopy(original, 36, duplicateFmt, 60, original.length - 36);
        ByteBuffer.wrap(duplicateFmt).order(ByteOrder.LITTLE_ENDIAN).putInt(4, duplicateFmt.length - 8);
        byte[] duplicateData = Arrays.copyOf(original, original.length + 16);
        System.arraycopy(original, 36, duplicateData, original.length, 16);
        ByteBuffer.wrap(duplicateData).order(ByteOrder.LITTLE_ENDIAN).putInt(4, duplicateData.length - 8);
        int index = 0;
        for (byte[] bytes : new byte[][] {shortRiff, hugeData, missingData, duplicateFmt, duplicateData, Arrays.copyOf(original, original.length - 1)})
        {
            Path file = temp.resolve("hostile-" + index++ + ".wav");
            Files.write(file, bytes);
            RequiredOfficialReading req = required(file, 16, 1, false, 4, ReadingKind.MOMENTARY, ReadoutMode.STEADY_EOF);
            assertThrows(IllegalArgumentException.class, () -> new OfficialWaveReader(file, req));
        }
    }

    @Test
    void fileMutationAndTraversalCannotBeAcceptedAsTheOriginal() throws Exception
    {
        Path file = temp.resolve("mutation.wav");
        byte[] bytes = wave(16, 1, false, new int[] {1, 2, 3, 4}, false);
        Files.write(file, bytes);
        RequiredOfficialReading req = required(file, 16, 1, false, 4, ReadingKind.MOMENTARY, ReadoutMode.STEADY_EOF);
        byte[] mutant = bytes.clone(); mutant[45] ^= 1;
        Files.write(file, mutant);
        assertThrows(IllegalArgumentException.class, () -> new OfficialWaveReader(file, req));
        assertThrows(IllegalArgumentException.class, () -> OfficialWaveReader.validateRegular(temp.resolve("sub/../mutation.wav")));
        assertThrows(IllegalArgumentException.class, () -> OfficialWaveReader.validateRegular(temp));
    }

    @Test
    void hardlinkedSourceIsRejectedWithoutFollowingAnAlias() throws Exception
    {
        Path original = temp.resolve("hardlink-source.wav");
        Files.write(original, wave(16, 1, false, new int[] {1, 2, 3, 4}, false));
        Path alias = temp.resolve("hardlink-alias.wav");
        Files.createLink(alias, original);
        assertThrows(IllegalArgumentException.class, () -> OfficialWaveReader.validateRegular(original));
        assertThrows(IllegalArgumentException.class, () -> OfficialWaveReader.validateRegular(alias));
    }

    @Test
    void constantIntervalRetainsAnInteriorOutlierEvenWhenTheFinalWindowHasSettled() throws Exception
    {
        int[] samples = new int[400000];
        for (int i = 0; i < samples.length; i++)
        {
            int amplitude = i >= 160000 && i < 180000 ? 12000 : 1000;
            samples[i] = (int) (amplitude * Math.sin(2 * Math.PI * 997 * i / 48000));
        }
        Path file = temp.resolve("constant-interior-outlier.wav");
        Files.write(file, wave(16, 1, false, samples, false));
        RequiredOfficialReading required = required(file, 16, 1, false, samples.length, ReadingKind.MOMENTARY, ReadoutMode.CONSTANT_INTERVAL);
        OfficialSignalEvidence measured = OfficialLoudnessRunner.measureReading(file, required);
        LoudnessCore eof = new LoudnessCore(48000, ChannelLayout.MONO_MAIN);
        float[] frame = new float[1];
        for (int sample : samples) { frame[0] = sample / 32768.0f; eof.acceptFrame(frame, 0); }
        double finalValue = LoudnessCore.fromPower(eof.momentaryPower()).lufs();
        assertTrue(measured.measuredLufs() > finalValue + 15d);
        assertTrue(measured.maxEndFrame() < 200000L);
        assertEquals(samples.length - 48000L, measured.readoutCount());
        assertTrue(measured.minimumLufs() <= finalValue);
        OfficialSignalEvidence steady = OfficialLoudnessRunner.measureReading(file,
                required(file, 16, 1, false, samples.length, ReadingKind.MOMENTARY, ReadoutMode.STEADY_EOF));
        assertEquals(Double.doubleToRawLongBits(finalValue), Double.doubleToRawLongBits(steady.measuredLufs()));
        assertEquals(samples.length, steady.firstReadoutEndFrame());
        assertEquals(samples.length, steady.lastReadoutEndFrame());
        assertEquals(1L, steady.readoutCount()); assertEquals(1, steady.resetCount()); assertTrue(steady.eofReached());
    }

    @Test
    void maximumUsesEveryCompleteFrameBoundaryWithUniquePeakOffTheHundredMillisecondGrid() throws Exception
    {
        int frames = 90000;
        int[] samples = new int[frames];
        for (int i = 23457; i < 23457 + 19200; i++) samples[i] = (i & 1) == 0 ? 12000 : -12000;
        Path file = temp.resolve("single-off-grid-peak.wav");
        Files.write(file, wave(16, 1, false, samples, false));
        RequiredOfficialReading req = required(file, 16, 1, false, frames, ReadingKind.MOMENTARY, ReadoutMode.MAXIMUM_FULL_WINDOWS);
        OfficialSignalEvidence actual = OfficialLoudnessRunner.measureReading(file, req);
        assertEquals(frames - 19200L + 1L, actual.readoutCount());
        assertEquals(19200L, actual.firstReadoutEndFrame());
        assertEquals(frames, actual.lastReadoutEndFrame());
        assertEquals(1, actual.resetCount()); assertTrue(actual.eofReached());
        assertNotEquals(0L, actual.maxEndFrame() % 4800L);
        assertEquals(actual.minEndFrame(), actual.maxEndFrame());
        assertEquals(Double.doubleToRawLongBits(actual.measuredLufs()), Double.doubleToRawLongBits(actual.minimumLufs()));
        LoudnessCore independent = new LoudnessCore(48000, ChannelLayout.MONO_MAIN);
        float[] frame = new float[1];
        double max = -Double.MAX_VALUE;
        double gridMax = -Double.MAX_VALUE;
        long firstArgmax = 0L;
        for (int i = 0; i < frames; i++)
        {
            frame[0] = samples[i] / 32768.0f;
            independent.acceptFrame(frame, 0);
            if (i + 1 < 19200) continue;
            MeasuredLoudness value = LoudnessCore.fromPower(independent.momentaryPower());
            if (value.present() && value.lufs() > max) { max = value.lufs(); firstArgmax = i + 1L; }
            if (value.present() && (i + 1) % 4800 == 0) gridMax = Math.max(gridMax, value.lufs());
        }
        assertEquals(firstArgmax, actual.maxEndFrame());
        assertEquals(Double.doubleToRawLongBits(max), Double.doubleToRawLongBits(actual.measuredLufs()));
        assertTrue(max > gridMax, "A 100 ms-only mutant must miss this maximum.");
    }

    @Test
    void constantIntervalCountsAllBoundariesAndRejectsAnyAbsentMember() throws Exception
    {
        int[] samples = new int[160000];
        for (int i = 0; i < samples.length; i++) samples[i] = (int) (2000 * Math.sin(2 * Math.PI * 997 * i / 48000));
        for (ReadingKind kind : new ReadingKind[] {ReadingKind.MOMENTARY, ReadingKind.SHORT_TERM})
        {
            Path file = temp.resolve("constant-" + kind + ".wav");
            Files.write(file, wave(16, 1, false, samples, false));
            RequiredOfficialReading req = required(file, 16, 1, false, samples.length, kind, ReadoutMode.CONSTANT_INTERVAL);
            OfficialSignalEvidence actual = OfficialLoudnessRunner.measureReading(file, req);
            long first = kind == ReadingKind.MOMENTARY ? 48001L : 144001L;
            assertEquals(first, actual.firstReadoutEndFrame());
            assertEquals(samples.length - first + 1L, actual.readoutCount());
            assertEquals(samples.length, actual.lastReadoutEndFrame());
            assertTrue(actual.minimumLufs() <= actual.measuredLufs());
            assertEquals(0L, actual.completeGatingBlocks());
        }
        Path silent = temp.resolve("absent-interval.wav");
        Files.write(silent, wave(16, 1, false, new int[160000], false));
        RequiredOfficialReading req = required(silent, 16, 1, false, 160000, ReadingKind.SHORT_TERM, ReadoutMode.CONSTANT_INTERVAL);
        assertThrows(IllegalStateException.class, () -> OfficialLoudnessRunner.measureReading(silent, req));
    }

    static byte[] wave(int bits, int channels, boolean extended, int[] samples, boolean junk)
    {
        int fmt = extended ? 40 : 16;
        int data = samples.length * (bits / 8);
        ByteBuffer out = ByteBuffer.allocate(12 + 8 + fmt + (junk ? 12 : 0) + 8 + data + (data & 1)).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46464952).putInt(out.capacity() - 8).putInt(0x45564157);
        out.putInt(0x20746d66).putInt(fmt).putShort((short) (extended ? 65534 : 1)).putShort((short) channels);
        out.putInt(48000).putInt(48000 * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        if (extended) out.putShort((short) 22).putShort((short) bits).putInt(channels == 1 ? 1 : 3)
                .put(HexFormat.of().parseHex("0100000000001000800000aa00389b71"));
        if (junk) out.putInt(0x4b4e554a).putInt(3).put(new byte[] {1, 2, 3, 0});
        out.putInt(0x61746164).putInt(data);
        for (int sample : samples)
        {
            out.put((byte) sample).put((byte) (sample >> 8));
            if (bits == 24) out.put((byte) (sample >> 16));
        }
        if ((data & 1) != 0) out.put((byte) 0);
        return out.array();
    }

    static RequiredOfficialReading required(Path file, int bits, int channels, boolean extended, long frames,
                                             ReadingKind kind, ReadoutMode mode) throws Exception
    {
        long first = mode == ReadoutMode.CONSTANT_INTERVAL ? (kind == ReadingKind.SHORT_TERM ? 144001L : 48001L)
                : mode == ReadoutMode.MAXIMUM_FULL_WINDOWS ? (kind == ReadingKind.SHORT_TERM ? 144000L : 19200L) : frames;
        long count = mode == ReadoutMode.CONSTANT_INTERVAL || mode == ReadoutMode.MAXIMUM_FULL_WINDOWS ? frames - first + 1L : 1L;
        return new RequiredOfficialReading("EBU-TECH-3341-V4", "LTS-5.0", 1, file.getFileName().toString(),
                OfficialWaveReader.sha256(file), 48000, channels, channels == 1 ? ChannelLayout.MONO_MAIN : ChannelLayout.STEREO_LR,
                kind, mode, LoudnessValueKind.FINITE, -23d, .1d, bits, extended ? 65534 : 1,
                extended ? (channels == 1 ? 1 : 3) : 0, channels * bits / 8, frames, Files.size(file), frames * channels * bits / 8,
                Files.size(file), first, frames, count, mode == ReadoutMode.EOF_INTEGRATED ? Math.max(0L, 1L + (frames - 19200L) / 4800L) : 0L);
    }
}
