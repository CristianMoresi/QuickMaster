import com.dspark.analysis.TruePeak;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.limit.*;
import java.util.*;

/** Read-only stage-by-stage measurements; no normalization can hide a limiter error. */
public final class LimiterCascadeDiagnostic {
    static boolean assertions;
    static double db(double x) { return 20*Math.log10(Math.max(x,1e-30)); }
    static double rms(float[] x) { double e=0;for(float v:x)e+=(double)v*v;return Math.sqrt(e/x.length); }
    static void measure(float[] input,int rate,int channels,String label) {
        double reference=TruePeak.measureMax(input,channels);
        System.out.println("SOURCE "+label+" rmsDb="+db(rms(input))+" truePeakDb="+db(reference));
        for(double push:new double[]{0,1,3,6}) {
            var mb=new MultibandLimiterProcessor();mb.setEnabled(true);
            for(int b=0;b<4;b++)mb.setPushDb(b,push);
            var bb=new BroadbandLimiterProcessor();bb.setEnabled(true);bb.setPushDb(3);
            var pipe=new ProcessingPipeline();pipe.addProcessor(mb);pipe.addProcessor(bb);pipe.prepare(rate,input.length);
            float[][] stages=new float[2][];
            float[] out=pipe.analyzeAndRender(input,channels,0,null,null,(pcm,index)->stages[index]=pcm);
            double mbPeak=TruePeak.measureMax(stages[0],channels),outPeak=TruePeak.measureMax(out,channels);
            double sumGr=0;int count=0;for(int f=0;f<input.length/channels;f+=48){sumGr+=bb.getGrAtPosition(f);count++;}
            System.out.printf(Locale.ROOT,"CASCADE mbPush=%.1f bbPush=%.1f mbRms=%.6f mbTP=%.6f bbAnalyzedTP=%.6f bbRms=%.6f bbTP=%.6f bbDeepest=%.6f bbMean=%.6f normalizationGain=%.6f normalizedRms=%.6f%n",
                    push,bb.getPushDb(),db(rms(stages[0])),db(mbPeak),db(bb.getTruePeak()),db(rms(out)),db(outPeak),bb.getGrDeepest(0,input.length/channels-1),sumGr/count,-1-db(outPeak),db(rms(out))-db(outPeak)-1);
            System.out.printf(Locale.ROOT,"CEILING reference=%.9f actual=%.9f safetyTrimDb=%.9f%n",bb.getCeilingTruePeak(),outPeak,bb.getSafetyTrimDb());
            if(assertions && (bb.getPushDb()!=3 || bb.getCeilingTruePeak()!=reference || outPeak>reference*(1+1e-6)))
                throw new AssertionError("Moving ceiling or invalid broadband control/output");
            for(float value:out)if(!Float.isFinite(value))throw new AssertionError("Non-finite output");
        }
        System.out.println("LIMITER_CASCADE_PASS cases=4 fixedReference=true pushUnchanged=true finite=true");
    }
    public static void main(String[] args)throws Exception {
        assertions=Arrays.asList(args).contains("--assert");
        System.out.println("JAR "+BroadbandLimiterProcessor.class.getProtectionDomain().getCodeSource().getLocation());
        if(args.length>0){var file=new WavFile(args[0]);file.load();measure(file.getSamples(),file.getSampleRate(),file.getChannels(),args[0]);}
        else {
            int rate=48000;float[] x=new float[rate*6*2];
            for(int f=0;f<x.length/2;f++)for(int c=0;c<2;c++){
                double pulse=Math.exp(-(f%24000)/180.0);
                x[f*2+c]=(float)(.04*Math.sin(2*Math.PI*65*f/rate+c*.2)+.08*Math.sin(2*Math.PI*777*f/rate+c*.6)+(.08+.55*pulse)*Math.sin(2*Math.PI*6500*f/rate+c*.9));
            }
            measure(x,rate,2,"synthetic");
        }
    }
}
