import com.dspark.analysis.TruePeak;
import com.dspark.effects.MasterEqualizer;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.*;
import com.quickmaster.processing.eq.EqualizerProcessor;
import java.nio.file.*;
import java.util.*;

/** Whole-song, fixed-gain oracle; never writes source or rendered private audio. */
class EqAutoGainTrackAudit {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static String hash(Path path)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)){byte[] block=new byte[65536];for(int n;(n=in.read(block))>=0;)digest.update(block,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args)throws Exception {
        Path path=Path.of(args[0]);String before=hash(path);var f=new WavFile(path.toString());f.load();float[] source=f.getSamples();
        for(var type:new MasterEqualizer.BandType[]{MasterEqualizer.BandType.BELL,MasterEqualizer.BandType.HIGH_SHELF}) {
            float[][] renders=new float[2][];double applied=0;
            for(int mode=0;mode<2;mode++) {
                var e=new EqualizerProcessor();e.setNumBands(1);e.setAutoGainEnabled(mode==1);
                var band=new MasterEqualizer.Band();band.type=type;band.frequency=type==MasterEqualizer.BandType.BELL?857:4000;band.gainDb=18.8;band.q=.71;e.setBand(0,band);
                var p=new ProcessingPipeline();p.addProcessor(e);var norm=new PeakNormalizer();norm.setEnabled(false);p.addProcessor(norm);p.prepare(f.getSampleRate(),source.length);
                renders[mode]=p.analyzeAndRender(source,f.getChannels(),0,null,null,null);
                if(mode==1)applied=e.getAutoGainDb()+norm.getGainDb();
            }
            float gain=(float)Math.pow(10,applied/20);double error=0;long over=0;
            for(int i=0;i<source.length;i++){error=Math.max(error,Math.abs(renders[1][i]-renders[0][i]*gain));if(Math.abs(renders[0][i])>1)over++;}
            double tp=TruePeak.measureMax(renders[1],f.getChannels());check(tp<=1.000001,"Output overload");check(error<3e-7,"Nonlinear compensation");check(over>0,"Fixture does not exercise overload");
            System.out.printf(Locale.US,"EQ_TRACK type=%s gain=%.9f outputDbtp=%.9f rawOver=%d scalarError=%.9g%n",type,applied,20*Math.log10(tp),over,error);
        }
        check(before.equals(hash(path)),"Source changed");
        System.out.println("EQ_TRACK_PASS cases=2 scalar=true outputSafe=true sourceSha256="+before);
    }
}
