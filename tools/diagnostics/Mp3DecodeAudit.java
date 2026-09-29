import com.dspark.io.Mp3Decoder;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.Locale;

/** Offline synthetic/reference check; no playback and no source mutation. */
public class Mp3DecodeAudit {
    public static void main(String[] args) throws Exception {
        long start=System.nanoTime();
        var decoded=Mp3Decoder.decode(Path.of(args[0]));
        float[] pcm=decoded.samples();
        double seconds=(System.nanoTime()-start)*1e-9;
        ByteBuffer bytes=ByteBuffer.allocate(pcm.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float value:pcm)bytes.putFloat(value);
        Files.write(Path.of(args[1]),bytes.array());
        System.out.printf(Locale.ROOT,"JAVA_DECODE rate=%d channels=%d frames=%d seconds=%.4f bitrate=%d vbr=%s%n",
                decoded.sampleRate(),decoded.channels(),pcm.length/decoded.channels(),seconds,decoded.bitrateKbps(),decoded.variableBitrate());
        for(int ref=2;ref<args.length;ref++) {
            var values=ByteBuffer.wrap(Files.readAllBytes(Path.of(args[ref]))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            double peak=0,error=0,energy=0;int clamp=0,offGrid=0;
            if(values.remaining()!=pcm.length)throw new AssertionError("Different lengths: "+args[ref]+" expected="+values.remaining()+" actual="+pcm.length);
            for(float actual:pcm) {
                float expected=values.get();
                peak=Math.max(peak,Math.abs(actual-expected));error+=(actual-expected)*(double)(actual-expected);energy+=expected*(double)expected;
                if(Math.abs(actual)>1)clamp++;
                if(Math.abs(actual*32768-Math.rint(actual*32768))>1e-5)offGrid++;
            }
            System.out.printf(Locale.ROOT,"REFERENCE %s maxError=%.9g errorDb=%.3f overFullScale=%d offPcm16Grid=%d%n",
                    args[ref],peak,10*Math.log10(Math.max(error,1e-300)/Math.max(energy,1e-300)),clamp,offGrid);
        }
    }
}
