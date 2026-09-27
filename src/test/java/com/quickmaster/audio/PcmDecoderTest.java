package com.quickmaster.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class PcmDecoderTest {
    @TempDir Path dir;
    @Test void knownAndUnknownLengthPcmUseTheSameEndpointsAcrossBlockBoundaries() throws Exception {
        for (int bits : new int[]{16,24,32}) for (int ch : new int[]{1,2}) {
            int count = 8193*ch;
            ByteBuffer raw=ByteBuffer.allocate(count*(bits/8)).order(ByteOrder.LITTLE_ENDIAN);
            float[] expected=new float[count];
            for(int i=0;i<count;i++) {
                long value=(i%3==0?-(1L<<(bits-1)):i%3==1?(1L<<(bits-1))-1:0);
                expected[i]=(float)(value/Math.scalb(1.0,bits-1));
                for(int b=0;b<bits/8;b++) raw.put((byte)(value>>(8*b)));
            }
            for(long frames:new long[]{8193,-1}) assertArrayEquals(expected,
                    PcmDecoder.read(new ByteArrayInputStream(raw.array()),bits,false,ch,frames));
        }
    }
    @Test void partialTruncatedNonfiniteAndAbsurdLengthsAreRejected() {
        assertThrows(AudioFileException.class,()->PcmDecoder.read(new ByteArrayInputStream(new byte[3]),16,false,2,-1));
        assertThrows(AudioFileException.class,()->PcmDecoder.read(new ByteArrayInputStream(new byte[4]),16,false,1,4));
        assertThrows(AudioFileException.class,()->PcmDecoder.read(new ByteArrayInputStream(new byte[4]),16,false,1,1));
        assertThrows(AudioFileException.class,()->PcmDecoder.read(new ByteArrayInputStream(new byte[0]),16,false,2,Long.MAX_VALUE));
        byte[] nan=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array();
        assertThrows(AudioFileException.class,()->PcmDecoder.read(new ByteArrayInputStream(nan),32,true,1,1));
    }
    @Test void cancellationDuringReadIsObservedBeforePublishingAudio() {
        var input=new ByteArrayInputStream(new byte[100000]) {
            @Override public int read(byte[] b,int off,int len) { Thread.currentThread().interrupt(); return super.read(b,off,len); }
        };
        try { assertThrows(CancellationException.class,()->PcmDecoder.read(input,16,false,1,-1)); }
        finally {Thread.interrupted();}
    }
    @Test void wavReaderCannotSilentlyDropAnIncompleteStereoFrame() throws Exception {
        byte[] raw=AudioIoAuditTest.floatWav(new float[]{.5f,.5f,.3f},48000,2,12);
        Path file=dir.resolve("partial-frame.wav"); Files.write(file,raw);
        WavFile audio=new WavFile(file.toString(),44100,1,new float[]{.7f},24,false);
        float[] before=audio.getSamples();
        assertThrows(AudioFileException.class,audio::load);
        assertSame(before,audio.getSamples()); assertEquals(44100,audio.getSampleRate()); assertEquals(24,audio.getBitDepth());
    }
}
