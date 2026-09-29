import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.OfflineMetering;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.dynamics.*;
import com.quickmaster.processing.clip.*;
import com.dspark.effects.Saturation;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Installed-classpath, read-only corpus audit. Never writes source audio. */
class DynamicsClipsTrackAudit {
    static String hash(Path path)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)) {byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path path=Path.of(args[0]);String original=hash(path);WavFile file=new WavFile(path.toString());file.load();
        float[] source=file.getSamples();int ch=file.getChannels(),rate=file.getSampleRate();
        TrackAnalysis analysis=new TrackAnalysis();analysis.analyze(source,ch,rate);
        System.out.printf(Locale.ROOT,"SOURCE %s sha=%s rate=%d channels=%d pulseOnsets=%d superFluxOnsets=%d%n",
                path.getFileName(),original,rate,ch,analysis.getOnsetCount(),analysis.getTransientTimesSec().length);
        var peak=new PeakCompProcessor();peak.setTrackAnalysis(analysis);peak.setTargetDb(-3);
        var punch=new PunchProcessor();punch.setTrackAnalysis(analysis);punch.setAmountDb(3);
        List<AudioProcessor> stages=new ArrayList<>();stages.add(peak);stages.add(punch);
        for(var mode:Saturation.Algorithm.values()) {var soft=new SoftClipProcessor();soft.setAlgorithm(mode);soft.setSatDb(3);stages.add(soft);}
        for(var curve:HardClipProcessor.Curve.values()) {var hard=new HardClipProcessor();hard.setCurve(curve);hard.setClipDb(3);stages.add(hard);}
        int count=0;
        for(AudioProcessor stage:stages) {
            stage.setEnabled(true);stage.prepare(rate,source.length);stage.analyze(source,ch);stage.prepare(rate,source.length);
            float[] output=source.clone();
            if(stage instanceof OfflineMetering meter)meter.beginOfflineMetering(ch);
            stage.process(output,ch);
            if(stage instanceof OfflineMetering meter)meter.endOfflineMetering();
            double maxIn=0,maxOut=0,inPower=0,outPower=0,minGain=1,maxGain=1,link=0;long changed=0;
            for(int i=0;i<source.length;i++) {
                double x=source[i],y=output[i];check(Double.isFinite(y),"Nonfinite output");
                maxIn=Math.max(maxIn,Math.abs(x));maxOut=Math.max(maxOut,Math.abs(y));
                inPower+=x*x;outPower+=y*y;if(source[i]!=output[i])changed++;
                if(Math.abs(x)>1e-4) {minGain=Math.min(minGain,y/x);maxGain=Math.max(maxGain,y/x);}
            }
            if(stage instanceof AnalysisDynamicsProcessor && ch==2)for(int f=0;f<source.length/2;f++) {
                double l=source[f*2],r=source[f*2+1];
                if(Math.abs(l)>1e-3 && Math.abs(r)>1e-3)link=Math.max(link,Math.abs(output[f*2]/l-output[f*2+1]/r));
            }
            check(changed>0,"Dead stage "+stage.getClass().getSimpleName());
            double peakReduction=20*Math.log10(maxOut/maxIn);
            if(stage instanceof PeakCompProcessor p) {
                check(Math.abs(peakReduction+Math.min(3,p.getMaxReductionDb()))<.00002,"Peak target mismatch");
                check(minGain>=Math.pow(10,-3.0/20)-2e-7 && maxGain<=1.0000001,"Peak gain bound");
            } else if(stage instanceof PunchProcessor) {
                check(minGain>=1-2e-7 && maxGain<=Math.pow(10,3.0/20)+2e-7,"Punch gain bound");
            } else {
                check(Math.abs(peakReduction+3)<.00002,"Clip peak target mismatch");
                for(int first=0;first<source.length/ch;first+=1024) {
                    double a=0,b=0;int end=Math.min(source.length/ch,first+1024);
                    for(int f=first;f<end;f++)for(int c=0;c<ch;c++) {a=Math.max(a,Math.abs(source[f*ch+c]));b=Math.max(b,Math.abs(output[f*ch+c]));}
                    double expected=a>0&&b>0?20*Math.log10(b/a):0;
                    double meter=stage instanceof SoftClipProcessor s?s.getGrAtPosition(first):((HardClipProcessor)stage).getGrAtPosition(first);
                    check(Math.abs(expected-meter)<.00001,"Actual clip meter mismatch");
                }
            }
            check(link<5e-7,"Stereo image moved");
            System.out.printf(Locale.ROOT,"DYNAMICS_TRACK stage=%d type=%s peakDeltaDb=%.7f rmsDeltaDb=%.7f changed=%d minGain=%.9f maxGain=%.9f linkError=%.12g%n",
                    count++,stage.getClass().getSimpleName(),peakReduction,10*Math.log10(outPower/inPower),changed,minGain,maxGain,link);
        }
        check(hash(path).equals(original),"Source changed");
        System.out.println("DYNAMICS_CLIPS_TRACK_PASS stages="+count+" sourceUnchanged=true actualPcm=true actualClipMeters=true");
    }
}
