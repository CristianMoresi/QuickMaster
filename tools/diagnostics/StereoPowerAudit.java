import com.quickmaster.audio.AudioFormatDetector;
import com.quickmaster.processing.*;
import com.quickmaster.processing.stereo.*;
import java.util.*;

/** Read-only real-song acceptance for power and explicit gain ownership. */
public class StereoPowerAudit {
    public static void main(String[] args) throws Exception {
        var file=AudioFormatDetector.loadAuto(args[0]);file.load();int rate=file.getSampleRate();
        boolean verify=Arrays.asList(args).contains("--assert");
        boolean whole=Arrays.asList(args).contains("--whole");
        if(file.getChannels()!=2)throw new IllegalArgumentException("Require stereo source");
        String identity=com.quickmaster.config.LevelerExclusionStore.identity(java.nio.file.Path.of(args[0]));
        System.out.println("STEREO_POWER_SOURCE file="+java.nio.file.Path.of(args[0]).getFileName()+" whole="+whole);
        for(int start:whole?new int[]{0}:new int[]{30,90,180}) for(boolean lows:new boolean[]{false,true}) {
            float[] x=whole?file.getSamples().clone():Arrays.copyOfRange(file.getSamples(),start*rate*2,(start+8)*rate*2);
            var stereo=new StereoImageProcessor();stereo.setEnabled(true);
            stereo.setSettings(new StereoImageSettings(true,1,lows,true,false,0,false,3,StereoProfile.AUTO,.12));
            var normalizer=new PeakNormalizer();normalizer.setEnabled(false);
            var chain=new ProcessingPipeline();chain.addProcessor(stereo);chain.addProcessor(normalizer);chain.prepare(rate,x.length);
            float[] y=chain.analyzeAndRender(x,2,0,null,null,null);
            double m2=0,om2=0,s2=0,d2=0,sd=0,peak=0;
            for(int i=0;i<x.length;i+=2) {
                double m=.5*(x[i]+(double)x[i+1]),s=.5*(x[i]-(double)x[i+1]);
                double om=.5*(y[i]+(double)y[i+1]),d=.5*(y[i]-(double)y[i+1])-s;
                m2+=m*m;om2+=om*om;s2+=s*s;d2+=d*d;sd+=s*d;
                peak=Math.max(peak,Math.max(Math.abs(y[i]),Math.abs(y[i+1])));
            }
            double novel=10*Math.log10((d2-(s2>0?sd*sd/s2:0))/m2),midGain=10*Math.log10(om2/m2);
            System.out.printf(Locale.US,"STEREO_POWER start=%d lows=%s gainDb=%.6f midGainDb=%.6f newOrthogonalVsMidDb=%.3f samplePeak=%.6f%n",start,lows,normalizer.getGainDb(),midGain,novel,peak);
            if(verify && (normalizer.isSafetyEnabled()||Math.abs(normalizer.getGainDb())>1e-9||Math.abs(midGain)>1e-6))
                throw new AssertionError("Stereo silently changed full-mix gain");
            if(verify&&!lows&&novel< -6)throw new AssertionError("Maximum new stereo is still too weak");
        }
        if(!identity.equals(com.quickmaster.config.LevelerExclusionStore.identity(java.nio.file.Path.of(args[0]))))
            throw new AssertionError("Source changed");
        if(verify)System.out.println("STEREO_POWER_PASS explicitGain=true strongNewDelta=true noWindowsAudio=true");
    }
}
