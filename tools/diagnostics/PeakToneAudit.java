import com.quickmaster.processing.dynamics.PeakCompProcessor;
import java.util.Locale;

/** Steady-state residual after fitting the carrier: THD+N, not a listening score. */
class PeakToneAudit {
    public static void main(String[] args) {
        for(int rate:new int[]{48000,96000})for(int hz:new int[]{20,50,100,1000})for(double target:new double[]{-1,-3}) {
            float[] input=new float[rate*4];
            for(int i=0;i<input.length;i++)input[i]=(float)(.9*Math.sin(2*Math.PI*hz*i/rate));
            var p=new PeakCompProcessor();p.setEnabled(true);p.setTargetDb(target);p.prepare(rate,input.length);p.analyze(input,1);
            float[] out=input.clone();p.process(out,1);double a=0,b=0,energy=0;
            int first=rate,frames=rate*2;
            for(int i=first;i<first+frames;i++) {
                double phase=2*Math.PI*hz*i/rate;
                a+=out[i]*Math.sin(phase);b+=out[i]*Math.cos(phase);energy+=(double)out[i]*out[i];
            }
            a*=2.0/frames;b*=2.0/frames;double residual=0;
            for(int i=first;i<first+frames;i++) {
                double phase=2*Math.PI*hz*i/rate,d=out[i]-a*Math.sin(phase)-b*Math.cos(phase);residual+=d*d;
            }
            double ratio=Math.sqrt(residual/(energy-residual));
            if(!Double.isFinite(ratio))throw new AssertionError("Nonfinite tone residual");
            System.out.printf(Locale.ROOT,"PEAK_TONE rate=%d hz=%d targetDb=%.1f allowedDb=%.7f residualDb=%.6f residualPercent=%.7f%n",
                    rate,hz,target,p.getMaxReductionDb(),20*Math.log10(ratio),ratio*100);
        }
    }
}
