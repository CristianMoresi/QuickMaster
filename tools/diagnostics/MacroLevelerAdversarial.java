import com.quickmaster.processing.dynamics.MacroLevelerProcessor;
import java.util.*;

/** Supplemental adversarial PCM cases, independent of the engine's active-region mask. */
public class MacroLevelerAdversarial {
    public static void main(String[] args) {
        int rate=8000;float[] pcm=new float[40*rate];Random random=new Random(715391);
        for(int f=0;f<pcm.length;f++)pcm[f]=f<30*rate?(float)(.2*Math.sin(2*Math.PI*440*f/rate)):(float)(.001*random.nextGaussian());
        var p=new MacroLevelerProcessor();p.setEnabled(true);p.setLeveling(1);p.prepare(rate,pcm.length);p.analyze(pcm,1);
        float[] out=p.process(pcm.clone(),1);
        double before=rms(pcm,35*rate,39*rate),after=rms(out,35*rate,39*rate);
        System.out.printf(Locale.ROOT,"RESIDUAL_NOISE input=%.6f output=%.6f gain=%.6f%n",before,after,after-before);
        if(args.length>0&&args[0].equals("--assert")&&after-before>.1)throw new AssertionError("Residual noise lifted into musical foreground");
    }
    private static double rms(float[] pcm,int from,int to){double power=0;for(int i=from;i<to;i++)power+=(double)pcm[i]*pcm[i];return 10*Math.log10(power/(to-from));}
}
