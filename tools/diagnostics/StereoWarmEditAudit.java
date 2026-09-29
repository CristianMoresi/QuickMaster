import com.quickmaster.audio.AudioFormatDetector;
import com.quickmaster.processing.*;
import com.quickmaster.processing.stereo.*;
import java.util.*;

/** Same legacy API for comparable before/after whole-track timings. No audio output. */
public final class StereoWarmEditAudit {
    public static void main(String[] args)throws Exception {
        var file=AudioFormatDetector.loadAuto(args[0]);file.load();float[] pcm=file.getSamples();
        var owner=new StereoImageProcessor();owner.setEnabled(true);
        for(int edit=0;edit<5;edit++) {
            var p=owner.fork();p.setSettings(new StereoImageSettings(true,edit==0?1:.5,edit<3,true,edit==2||edit==4,.6,edit==2||edit==4,3,StereoProfile.ELECTRONIC,.12));
            var chain=new ProcessingPipeline();chain.addProcessor(p);chain.prepare(file.getSampleRate(),pcm.length);
            long start=System.nanoTime();float[] out=chain.analyzeAndRender(pcm,2,0,null,null,null);
            System.out.printf(Locale.US,"STEREO_WARM_EDIT edit=%d seconds=%.4f frames=%d%n",edit,(System.nanoTime()-start)/1e9,out.length/2);
            double midError=0;for(int i=0;i<out.length;i+=2){if(!Float.isFinite(out[i])||!Float.isFinite(out[i+1]))throw new AssertionError("Nonfinite");midError=Math.max(midError,Math.abs(out[i]+(double)out[i+1]-pcm[i]-pcm[i+1]));}
            if(midError>2e-6)throw new AssertionError("Mid altered "+midError);
        }
        System.out.println("STEREO_WARM_EDIT_PASS sourceReadOnly=true noAudioDevice=true");
    }
}
