import com.quickmaster.processing.analysis.TrackAnalysis;
import java.util.*;

class PunchStationaryAudit {
    public static void main(String[] args) {
        int failures=0;
        for(int rate:new int[]{44100,48000,96000})for(double hz:new double[]{20,50,80,110,220,300,1000,10000}) {
            float[] x=new float[rate*3];for(int i=0;i<x.length;i++)x[i]=(float)(.5*Math.sin(2*Math.PI*hz*i/rate));
            var analysis=new TrackAnalysis();analysis.analyze(x,1,rate);
            double[] times=args.length>0&&args[0].equals("--legacy")?analysis.getOnsetTimesSec():analysis.getTransientTimesSec();
            long spurious=Arrays.stream(times).filter(t->t>.25&&t<2.75).count();
            System.out.printf(Locale.ROOT,"PUNCH_STATIONARY rate=%d hz=%.1f spurious=%d%n",rate,hz,spurious);
            if(spurious>0)failures++;
        }
        if(failures!=0)throw new AssertionError("Steady carriers classified as attacks: "+failures);
        System.out.println("PUNCH_STATIONARY_PASS cases=24");
    }
}
