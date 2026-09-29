import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.dynamics.PunchProcessor;
import java.util.*;

/** Independent waveform annotations at different rates and hop phases. */
class PunchAttackAudit {
    public static void main(String[] args) {
        int cases=0;double worst=0;
        for(int rate:new int[]{44100,48000,96000})for(int phase=0;phase<5;phase++) {
            float[] x=new float[rate*6];int[] starts=new int[18];Random random=new Random(2817);
            int length=(int)Math.round(.008*rate);
            for(int k=0;k<starts.length;k++) {
                int start=(int)Math.round((.203+phase*.001+k*.311)*rate);starts[k]=start;
                double amplitude=k<9?.025:.9;
                for(int j=0;j<length;j++)x[start+j]=(float)(amplitude*(2*random.nextDouble()-1)*Math.exp(-j/(.003*rate)));
            }
            var analysis=new TrackAnalysis();analysis.analyze(x,1,rate);
            if(analysis.getTransientTimesSec().length!=starts.length)throw new AssertionError("Lost or extra attack rate="+rate+" phase="+phase+" count="+analysis.getTransientTimesSec().length);
            var p=new PunchProcessor();p.setEnabled(true);p.setAmountDb(6);p.setTrackAnalysis(analysis);p.prepare(rate,x.length);p.analyze(x,1);
            float[] out=x.clone();p.process(out,1);
            for(int start:starts) {
                double before=0,after=0;
                for(int j=0;j<length;j++){before+=(double)x[start+j]*x[start+j];after+=(double)out[start+j]*out[start+j];}
                double db=10*Math.log10(after/before);worst=Math.max(worst,Math.abs(6-db));
                if(Math.abs(6-db)>.15)throw new AssertionError("Attack not covered rate="+rate+" phase="+phase+" frame="+start+" boostDb="+db);
                cases++;
            }
        }
        System.out.printf(Locale.ROOT,"PUNCH_ATTACK_PASS bursts=%d rates=3 phases=5 maxBoostErrorDb=%.9f%n",cases,worst);
    }
}
