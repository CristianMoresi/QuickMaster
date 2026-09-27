package com.quickmaster.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class MetadataPreserverTest {
    @TempDir Path dir;

    @Test void broadcastMetadataCannotMislabelTheNewMasterWithSourceLoudnessOrTimeline() throws Exception {
        Path src=dir.resolve("broadcast.wav"), dst=dir.resolve("master.wav");
        new WavFile("memory",48000,1,new float[]{.1f,.2f},24,false).save(src.toString());
        byte[] raw=Files.readAllBytes(src), tagged=Arrays.copyOf(raw,raw.length+610);
        ByteBuffer data=ByteBuffer.wrap(tagged).order(ByteOrder.LITTLE_ENDIAN);
        int payload=raw.length+8;
        data.putInt(4,tagged.length-8); data.position(raw.length);
        data.putInt(0x74786562).putInt(602); data.put(payload,(byte)'Q');
        data.putLong(payload+338,48000L*3600); data.putShort(payload+346,(short)2);
        for(int i=0;i<5;i++) data.putShort(payload+412+2*i,(short)(-1234+i));
        Files.write(src,tagged);
        new WavFile("memory",44100,1,new float[]{.7f},24,false).save(dst.toString());
        int destinationPayload=(int)Files.size(dst)+8;
        MetadataPreserver.preserve(src.toString(),dst.toString());
        ByteBuffer result=ByteBuffer.wrap(Files.readAllBytes(dst)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals('Q',result.get(destinationPayload));
        assertEquals(0,result.getLong(destinationPayload+338));
        for(int i=0;i<5;i++) assertEquals(0x7fff,result.getShort(destinationPayload+412+2*i));
        assertArrayEquals(tagged,Files.readAllBytes(src));
        WavFile read=new WavFile(dst.toString()); read.load(); assertEquals(1,read.getSamples().length);
    }

    @Test void sourceEqualsDestinationIsAnExactNoOp() throws Exception {
        Path mp3 = dir.resolve("same.mp3");
        byte[] source = taggedMp3((byte) 42);
        Files.write(mp3, source);
        MetadataPreserver.preserve(mp3.toString(), mp3.toString());
        assertArrayEquals(source, Files.readAllBytes(mp3));
    }

    @Test void preservesAudioAndReplacesRatherThanDuplicatesId3Tags() throws Exception {
        Path src = dir.resolve("source.mp3"), dst = dir.resolve("destination.mp3");
        byte[] source = taggedMp3((byte) 42), destination = taggedMp3((byte) 7);
        destination[11] = (byte) 99; // audio payload distinct from source
        Files.write(src, source);
        Files.write(dst, destination);
        MetadataPreserver.preserve(src.toString(), dst.toString());
        byte[] expected = source.clone();
        expected[11] = (byte) 99;
        assertArrayEquals(expected, Files.readAllBytes(dst));
        assertArrayEquals(source, Files.readAllBytes(src));
    }

    @Test void aUserFileWithTheOldTemporaryNameIsNeverOverwritten() throws Exception {
        Path src = dir.resolve("source.mp3"), dst = dir.resolve("destination.mp3");
        Files.write(src, taggedMp3((byte) 42));
        Files.write(dst, new byte[]{1, 2, 3});
        Path unrelated = dir.resolve("destination.mp3.tagtmp");
        Files.writeString(unrelated, "user content");
        MetadataPreserver.preserve(src.toString(), dst.toString());
        assertEquals("user content", Files.readString(unrelated));
    }

    @Test void metadataCancellationPreservesDestination() throws Exception {
        Path src = dir.resolve("source.mp3"), dst = dir.resolve("destination.mp3");
        Files.write(src, taggedMp3((byte) 42));
        byte[] original = {1, 2, 3};
        Files.write(dst, original);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> MetadataPreserver.preserve(src.toString(), dst.toString()));
        } finally { Thread.interrupted(); }
        assertArrayEquals(original, Files.readAllBytes(dst));
    }

    @Test void malformedTagSizesDoNotCopyAudioAsMetadata() throws Exception {
        Path src = dir.resolve("malformed.mp3"), dst = dir.resolve("destination.mp3");
        byte[] source = taggedMp3((byte) 42);
        source[6] = (byte) 0x80; // forbidden high bit in a synchsafe tag size
        Files.write(src, source);
        byte[] audio = {1, 2, 3};
        Files.write(dst, audio);
        MetadataPreserver.preserve(src.toString(), dst.toString());
        // The independent, valid ID3v1 tag may be retained, but never the invalid ID3v2.
        byte[] result = Files.readAllBytes(dst);
        assertEquals(audio.length + 128, result.length);
        assertArrayEquals(audio, Arrays.copyOf(result, audio.length));
    }

    @Test void wavMetadataRemainsAlignedAndAudioUnchanged() throws Exception {
        Path src = dir.resolve("source.wav"), dst = dir.resolve("destination.wav");
        new WavFile("memory", 48000, 1, new float[]{0.1f}, 24, false).save(src.toString());
        byte[] raw = Files.readAllBytes(src);
        byte[] tagged = Arrays.copyOf(raw, raw.length + 12);
        ByteBuffer data = ByteBuffer.wrap(tagged).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(4, tagged.length - 8);
        data.position(raw.length);
        data.putInt(0x5453494c).putInt(4).putInt(0x4f464e49); // LIST(INFO)
        Files.write(src, tagged);
        new WavFile("memory", 48000, 1, new float[]{0.4f}, 24, false).save(dst.toString());
        byte[] before = Files.readAllBytes(dst);
        MetadataPreserver.preserve(src.toString(), dst.toString());
        byte[] result = Files.readAllBytes(dst);
        assertEquals(before.length + 12, result.length);
        assertEquals(result.length - 8, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        int offset = AudioIoAuditTest.dataOffset(before);
        assertArrayEquals(Arrays.copyOfRange(before, offset, offset + 3), Arrays.copyOfRange(result, offset, offset + 3));
        assertEquals(0x5453494c, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(before.length));
    }

    static byte[] taggedMp3(byte value) {
        byte[] result = new byte[10 + 1 + 3 + 128];
        result[0] = 'I'; result[1] = 'D'; result[2] = '3'; result[3] = 4;
        result[9] = 1; result[10] = value;
        result[11] = 1; result[12] = 2; result[13] = 3;
        result[14] = 'T'; result[15] = 'A'; result[16] = 'G'; result[17] = value;
        return result;
    }
}
