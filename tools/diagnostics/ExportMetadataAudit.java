import com.quickmaster.audio.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Runs against an explicitly supplied app classpath. Creates only audit outputs.
 * Compare payload hashes in fresh JVMs to keep the existing dither sequence equal.
 * Never opens an audio device; an optional private source is read only. */
public class ExportMetadataAudit {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("output-directory [source.wav]");
        Path folder = Files.createDirectories(Path.of(args[0]));
        System.out.println("APP_JAR " + AudioFile.class.getProtectionDomain().getCodeSource().getLocation());
        for (int rate : new int[]{32000, 44100, 48000}) for (int channels : new int[]{1, 2}) {
            float[] pcm = new float[8193 * channels];
            for (int i=0; i<pcm.length; i++) pcm[i]=(float)(.4*Math.sin(.071*i));
            export(folder, "r"+rate+"-c"+channels, pcm, rate, channels);
        }
        if (args.length == 2) {
            Path source=Path.of(args[1]); String hash=sha(Files.readAllBytes(source));
            WavFile input=new WavFile(source.toString()); input.load();
            int from=30*input.getSampleRate()*input.getChannels();
            int until=Math.min(input.getSamples().length,from+3*input.getSampleRate()*input.getChannels());
            export(folder,"private-excerpt",Arrays.copyOfRange(input.getSamples(),from,until),input.getSampleRate(),input.getChannels());
            if(!hash.equals(sha(Files.readAllBytes(source))))throw new AssertionError("Source changed");
            System.out.println("PRIVATE_SOURCE_UNCHANGED " + hash);
        }
        System.out.println("EXPORT_AUDIT_PASS");
    }

    private static void export(Path folder,String name,float[] pcm,int rate,int channels)throws Exception {
        for(int encoding:new int[]{16,24,32,33}){
            Path file=folder.resolve(name+"-"+encoding+".wav");
            new WavFile("memory",rate,channels,pcm,Math.min(encoding,32),encoding==33).save(file.toString());
            byte[] raw=Files.readAllBytes(file); ByteBuffer data=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            byte[] payload=null;
            for(int p=12;p+8<=raw.length;){
                int size=data.getInt(p+4);
                if(size<0||(long)p+8+size>raw.length)throw new AssertionError("Invalid RIFF");
                if(ascii(raw,p,4).equals("data"))payload=Arrays.copyOfRange(raw,p+8,p+8+size);
                p+=8+size+(size&1);
            }
            if(payload==null)throw new AssertionError("Missing data");
            System.out.println("PAYLOAD " + file.getFileName()+" "+sha(payload));
        }
        Path file=folder.resolve(name+".mp3");
        new Mp3File("memory",rate,channels,pcm,320,false).save(file.toString());
        byte[] raw=Files.readAllBytes(file); int begin=0;
        while(ascii(raw,begin,3).equals("ID3")){
            int size=0;for(int i=6;i<10;i++)size=(size<<7)+(raw[begin+i]&127);
            begin+=10+size;
        }
        if((raw[begin]&255)!=255||(raw[begin+1]&0xe0)!=0xe0)throw new AssertionError("Missing MPEG sync");
        System.out.println("PAYLOAD "+file.getFileName()+" "+sha(Arrays.copyOfRange(raw,begin,raw.length)));
    }
    private static String ascii(byte[] x,int p,int n){return new String(x,p,n,StandardCharsets.US_ASCII);}
    private static String sha(byte[] x)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x));}
}
