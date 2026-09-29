import com.dspark.io.*;
import javazoom.jl.decoder.*;
import java.nio.*;
import java.nio.file.*;

/** Independent JLayer float oracle for the very quiet mono fixture. No playback. */
public class Mp3QuietReferenceAudit {
    public static void main(String[] args) throws Exception {
        var stream=Mp3Stream.read(Path.of(args[0]));
        var nativePort=Mp3Decoder.decode(stream).samples();
        var out=new Out(stream.rawFrames()*stream.channels());
        var decoder=new Decoder();decoder.setOutputBuffer(out);
        var bits=new Bitstream(stream.openAudioStream());Header header;
        while((header=bits.readFrame())!=null){decoder.decodeFrame(header,bits);bits.closeFrame();}
        bits.close();var third=stream.finish(out.pcm).samples();
        var ff=ByteBuffer.wrap(Files.readAllBytes(Path.of(args[1]))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        double max=0,thirdMax=0,thirdFf=0;int index=0;
        for(int i=0;i<nativePort.length;i++) {
            float ref=ff.get();double delta=Math.abs(nativePort[i]-ref);
            if(delta>max){max=delta;index=i;}
            thirdMax=Math.max(thirdMax,Math.abs(nativePort[i]-third[i]));
            thirdFf=Math.max(thirdFf,Math.abs(third[i]-ref));
        }
        System.out.println("QUIET maxPortFfmpeg="+max+" maxIndex="+index+" portJlayer="+thirdMax+" jlayerFfmpeg="+thirdFf);
        if (thirdMax > 2e-11) throw new AssertionError("Sub-PCM16 reference mismatch");
        if (args.length > 2) {
            ByteBuffer output=ByteBuffer.allocate(third.length*4).order(ByteOrder.LITTLE_ENDIAN);
            for(float sample:third)output.putFloat(sample);
            Files.write(Path.of(args[2]),output.array());
        }
    }
    static class Out extends Obuffer {
        float[] pcm;int pos;Out(int size){pcm=new float[size];}
        public void append(int ch,short value){throw new AssertionError();}
        public void appendSamples(int ch,float[] block){for(float f:block)pcm[pos++]=f/32700f;}
        public void clear_buffer(){}public void write_buffer(int val){}public void close(){}public void set_stop_flag(){}
    }
}
