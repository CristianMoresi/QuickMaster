import com.dspark.effects.MasterEqualizer;
import com.quickmaster.audio.AudioFormatDetector;
import com.quickmaster.processing.*;
import com.quickmaster.processing.eq.EqualizerProcessor;
import com.quickmaster.processing.stereo.*;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.util.*;

/** Actual-song EQ-before-Stereo gain ownership, in final and provisional paths. */
public class StereoEqGainAudit {
    static EqualizerProcessor eq() {
        var eq=new EqualizerProcessor();eq.setEnabled(true);eq.setAutoGainEnabled(true);eq.setNumBands(1);
        var b=new MasterEqualizer.Band();b.enabled=true;b.frequency=857;b.gainDb=9;
        b.phase=MasterEqualizer.BandPhase.LINEAR;eq.setBand(0,b);return eq;
    }
    static ProcessingPipeline chain(boolean stereoOn,boolean lows) {
        var p=new ProcessingPipeline();p.addProcessor(eq());
        if(stereoOn) {
            var stereo=new StereoImageProcessor();stereo.setEnabled(true);
            stereo.setSettings(new StereoImageSettings(true,1,lows,true,false,0,false,3,StereoProfile.AUTO,.12));
            p.addProcessor(stereo);var norm=new PeakNormalizer();norm.setEnabled(false);p.addProcessor(norm);
        }
        return p;
    }
    static void midEquals(float[] before,float[] after,String label) {
        double error=0;
        for(int i=0;i<before.length;i+=2)
            error=Math.max(error,Math.abs(before[i]+(double)before[i+1]-after[i]-after[i+1]));
        if(error>2e-6)throw new AssertionError(label+" changed EQ output's Mid: "+error);
        System.out.printf(Locale.US,"STEREO_EQ_MID %s maxError=%.9g%n",label,error);
    }
    public static void main(String[] args)throws Exception {
        var f=AudioFormatDetector.loadAuto(args[0]);f.load();int rate=f.getSampleRate();
        float[] source=Arrays.copyOfRange(f.getSamples(),rate*90*2,rate*98*2);
        var reference=chain(false,false);reference.prepare(rate,source.length);
        float[] dry=reference.analyzeAndRender(source,2,0,null,null,null);
        double expected=((EqualizerProcessor)reference.getProcessors().get(0)).getAutoGainDb();
        for(boolean lows:new boolean[]{false,true}) {
            var p=chain(true,lows);p.prepare(rate,source.length);
            float[] wet=p.analyzeAndRender(source,2,0,null,null,null);
            midEquals(dry,wet,"final lows="+lows);
            var n=(PeakNormalizer)p.getProcessors().get(2);
            if(n.isSafetyEnabled()||n.getGainDb()!=0||Math.abs(expected-((EqualizerProcessor)p.getProcessors().get(0)).getAutoGainDb())>1e-9)
                throw new AssertionError("Downstream generation changed EQ/output gain");
            // Preview has its own local EQ compensation. Compare identical
            // windows BEFORE the separate final safety, which is inactive here.
            var preview=new PreviewWindowRenderer(chain(true,lows),source,rate,2,1);
            float[] window=preview.render(rate*2,rate/2,new CancellationToken());
            if(preview.getOutputGainDb()!=0)throw new AssertionError("Hidden preview output gain");
            for(float v:window)if(!Float.isFinite(v))throw new AssertionError("Invalid preview");
            System.out.printf(Locale.US,"STEREO_EQ_GAIN lows=%s finalEqDb=%.6f previewEqDb=%.6f outputDb=%.6f%n",lows,expected,preview.getEqGainDb(),preview.getOutputGainDb());
        }
        System.out.println("STEREO_EQ_PASS upstreamCompensationUnchanged=true noHiddenStereoGain=true");
    }
}
