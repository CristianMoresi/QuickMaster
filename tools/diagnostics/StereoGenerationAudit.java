import com.quickmaster.audio.*;
import com.quickmaster.processing.*;
import com.quickmaster.processing.stereo.*;
import java.util.*;

/** Read-only real-song difference, not a changed-sample-count acceptance oracle. */
public class StereoGenerationAudit {
    public static void main(String[] args)throws Exception {
        var f=AudioFormatDetector.loadAuto(args[0]);f.load();int rate=f.getSampleRate();
        for(int start:new int[]{30,90,180})for(double amount:new double[]{.5,1}) {
            float[] x=Arrays.copyOfRange(f.getSamples(),start*rate*2,(start+8)*rate*2);
            var p=new StereoImageProcessor();p.setEnabled(true);
            p.setSettings(new StereoImageSettings(true,amount,false,true,false,0,false,3,StereoProfile.AUTO,.12));
            var chain=new ProcessingPipeline();chain.addProcessor(p);chain.prepare(rate,x.length);
            long begin=System.nanoTime();float[] y=chain.analyzeAndRender(x,2,0,null,null,null);
            double m2=0,s2=0,d2=0,sd=0,out=0;
            for(int i=0;i<x.length;i+=2){double m=.5*(x[i]+(double)x[i+1]),s=.5*(x[i]-(double)x[i+1]);
                double d=.5*(y[i]-(double)y[i+1])-s;m2+=m*m;s2+=s*s;d2+=d*d;sd+=s*d;out+=(s+d)*(s+d);}
            double novel=d2-(s2>0?sd*sd/s2:0);
            System.out.printf(Locale.US,"GENERATION start=%d amount=%.1f deltaVsMidDb=%.3f novelVsMidDb=%.3f sideChangeDb=%.3f elapsedMs=%.1f%n",
                    start,amount,10*Math.log10(d2/m2),10*Math.log10(novel/m2),10*Math.log10(out/s2),(System.nanoTime()-begin)/1e6);
            if(Arrays.asList(args).contains("--assert") && amount==1 && 10*Math.log10(novel/m2)<-20)
                throw new AssertionError("Full generation is too weak on an ordinary music section");
        }
    }
}
