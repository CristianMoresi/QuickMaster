import com.quickmaster.audio.*;
import com.quickmaster.processing.*;
import com.quickmaster.processing.eq.*;
import com.quickmaster.processing.stereo.*;
import com.quickmaster.processing.clip.*;
import com.quickmaster.processing.dynamics.*;
import com.quickmaster.processing.limit.*;
import com.dspark.effects.MasterEqualizer;
import com.dspark.analysis.TruePeak;
import java.util.*;

/** Real-song excerpt in memory, adverse ordering and downstream nonlinear stages. */
public class StereoRoutingAudit {
    public static void main(String[] args)throws Exception {
        var original=AudioFormatDetector.loadAuto(args[0]);original.load();int rate=original.getSampleRate();
        if(original.getChannels()!=2)throw new IllegalArgumentException("Stereo required");
        float[] source=Arrays.copyOfRange(original.getSamples(),rate*60*2,rate*68*2),saved=source.clone();
        int cases=0;
        for(int order=0;order<3;order++)for(int factor:new int[]{1,4}) {
            var stereo=new StereoImageProcessor();stereo.setEnabled(true);
            stereo.setSettings(new StereoImageSettings(true,.75,false,false,true,.75,true,3,StereoProfile.ROCK,.12));
            var eq=new EqualizerProcessor();eq.setEnabled(true);eq.setAutoGainEnabled(false);eq.setNumBands(1);
            var band=new MasterEqualizer.Band();band.frequency=857;band.gainDb=9;band.phase=MasterEqualizer.BandPhase.LINEAR;eq.setBand(0,band);
            var peak=new PeakCompProcessor();peak.setEnabled(true);peak.setTargetDb(-1);
            var soft=new SoftClipProcessor();soft.setEnabled(true);soft.setSatDb(1);
            var hard=new HardClipProcessor();hard.setEnabled(true);hard.setClipDb(1);
            var multi=new MultibandLimiterProcessor();multi.setEnabled(true);for(int b=0;b<4;b++)multi.setPushDb(b,1);
            var broad=new BroadbandLimiterProcessor();broad.setEnabled(true);broad.setPushDb(3);
            var norm=new PeakNormalizer(-1);norm.setEnabled(true);
            var chain=new ProcessingPipeline();
            if(order==0)chain.addProcessor(stereo);
            chain.addProcessor(eq);chain.addProcessor(peak);
            if(order==1)chain.addProcessor(stereo);
            chain.addProcessor(soft);chain.addProcessor(hard);chain.addProcessor(multi);chain.addProcessor(broad);
            if(order==2)chain.addProcessor(stereo);
            chain.addProcessor(norm);
            var audio=new WavFile("in-memory",rate,2,source.clone(),32,true);
            chain.processOversampled(audio,factor,null);
            if(audio.getSamples().length!=source.length)throw new AssertionError("Length changed");
            for(float v:audio.getSamples())if(!Float.isFinite(v))throw new AssertionError("Non-finite output");
            double tp=20*Math.log10(TruePeak.measureMax(audio.getSamples(),2));
            if(tp>-1+.001)throw new AssertionError("Final ceiling exceeded: "+tp);
            if(stereo.plan()==null)throw new AssertionError("Missing stereo plan");
            if(!Arrays.equals(saved,source))throw new AssertionError("Input changed");
            System.out.printf(Locale.US,"STEREO_ROUTING order=%d os=%d dBTP=%.6f targetSide=%.6f%n",order,factor,tp,stereo.plan().targetSideShare());cases++;
        }
        System.out.println("STEREO_ROUTING_PASS cases="+cases+" upstreamAndDownstream=true sourceUnchanged=true");
    }
}
