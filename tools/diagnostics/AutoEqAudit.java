import com.quickmaster.processing.eq.AutoEqProcessor;
import java.util.Random;

/** Generated fixtures only; channel polarity must not change linked EQ decisions. */
public class AutoEqAudit {
    public static void main(String[] args) {
        int failures = 0;
        for (int rate : new int[]{8000,16000,22050,32000,44100,48000,96000,192000}) {
            float[] signal = new float[32769 * 2];
            Random random = new Random(823);
            for (int f=0; f<signal.length/2; f++) {
                float x=(float)(.2*random.nextGaussian()+.3*Math.sin(2*Math.PI*700*f/rate));
                signal[2*f]=signal[2*f+1]=x;
            }
            try {
                float[] positive=render(signal,rate);
                for (int i=1;i<signal.length;i+=2) signal[i]=-signal[i];
                float[] negative=render(signal,rate);
                double error=0;
                for(int i=0;i<signal.length;i++) {
                    if(!Float.isFinite(positive[i]) || !Float.isFinite(negative[i])) throw new AssertionError("nonfinite");
                    error=Math.max(error,Math.abs(positive[i]-negative[i]*(i%2==0?1:-1)));
                }
                System.out.println("AUTO_EQ_POLARITY rate="+rate+" maxError="+error);
                if(error>1e-6) failures++;
            } catch(Throwable e) { failures++; System.out.println("AUTO_EQ_FAILURE rate="+rate+" "+e); }
        }
        System.out.println("AUTO_EQ_AUDIT failures="+failures);
        if(failures>0) System.exit(1);
    }
    static float[] render(float[] input,int rate) {
        var eq=new AutoEqProcessor(); eq.setEnabled(true); eq.setAmount(1);
        eq.prepare(rate,input.length); eq.analyze(input,2); eq.prepare(rate,input.length);
        return eq.process(input.clone(),2);
    }
}
