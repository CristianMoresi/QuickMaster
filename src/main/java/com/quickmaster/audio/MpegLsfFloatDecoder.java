package com.quickmaster.audio;

import com.dspark.io.Mp3Stream;
import javazoom.jl.decoder.*;
import java.io.IOException;
import java.util.concurrent.CancellationException;

/** MPEG-2/2.5 compatibility, outside the MPEG-1-only DSPark port.
 * Captures synthesis floats before JLayer's default short conversion/clamp.
 * Never used as an error fallback for a damaged MPEG-1 stream. */
final class MpegLsfFloatDecoder {
    private MpegLsfFloatDecoder() {}

    static Mp3Stream.Audio decode(Mp3Stream stream) throws IOException {
        if(stream.version()==1) throw new IOException("MPEG-1 must use the DSPark decoder");
        float[] pcm=new float[stream.rawFrames()*stream.channels()];
        FloatOutput output=new FloatOutput(pcm,stream.channels());
        Decoder decoder=new Decoder();decoder.setOutputBuffer(output);
        Bitstream bits=new Bitstream(stream.openAudioStream());
        try {
            Header header;
            while((header=bits.readFrame())!=null) {
                if(Thread.currentThread().isInterrupted()) throw new CancellationException("MP3 decoding cancelled");
                if(header.layer()!=3||header.frequency()!=stream.sampleRate()
                        ||(header.mode()==Header.SINGLE_CHANNEL?1:2)!=stream.channels())
                    throw new IOException("MPEG-2/2.5 frame format changed");
                decoder.decodeFrame(header,bits);
                bits.closeFrame();
            }
            if(output.frames*stream.channels()!=pcm.length) throw new IOException("Incomplete MPEG-2/2.5 decode");
            return stream.finish(pcm);
        } catch(JavaLayerException|IllegalStateException ex) {
            throw new IOException("Cannot decode MPEG-2/2.5 audio",ex);
        } finally {
            try {bits.close();}catch(BitstreamException ignored) { /* byte-array stream */ }
        }
    }

    private static final class FloatOutput extends Obuffer {
        private final float[] samples;
        private final int channels;
        private final int[] counts=new int[2];
        private int frames;
        FloatOutput(float[] samples,int channels){this.samples=samples;this.channels=channels;}
        @Override public void appendSamples(int channel,float[] block) {
            for(int i=0;i<32;i++) {
                int at=(frames+counts[channel]++)*channels+channel;
                if(at>=samples.length)throw new IllegalStateException("MP3 decoder exceeded declared length");
                // Decoder.initialize in the pinned JLayer 1.0.1 uses 32700,
                // not the PCM16 full-scale constant. Undo that exact scale.
                samples[at]=block[i]/32700f;
            }
        }
        @Override public void append(int channel,short value) {
            throw new IllegalStateException("Unexpected PCM16 decoder path");
        }
        @Override public void write_buffer(int value) {
            if(channels==2&&counts[0]!=counts[1])throw new IllegalStateException("Unbalanced MP3 channels");
            frames+=counts[0];counts[0]=counts[1]=0;
        }
        @Override public void clear_buffer(){counts[0]=counts[1]=0;}
        @Override public void close(){}
        @Override public void set_stop_flag(){}
    }
}
