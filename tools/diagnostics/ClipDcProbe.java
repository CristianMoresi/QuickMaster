import com.quickmaster.processing.clip.SoftClipProcessor;
import com.dspark.effects.Saturation;
import java.util.Locale;

class ClipDcProbe {
    public static void main(String[] args) {
        for(var algorithm:Saturation.Algorithm.values())for(double db:new double[]{.001,1,6,12}) {
            int rate=48000;float[] samples=new float[4*rate];
            for(int i=0;i<samples.length;i++)samples[i]=(float)(.9*Math.sin(2*Math.PI*100*i/rate));
            var processor=new SoftClipProcessor();processor.setAlgorithm(algorithm);processor.setSatDb(db);processor.setEnabled(true);
            processor.prepare(rate,samples.length);processor.analyze(samples,1);processor.process(samples,1);
            double mean=0,peak=0;for(int i=rate;i<samples.length;i++) {mean+=samples[i];peak=Math.max(peak,Math.abs(samples[i]));}
            System.out.printf(Locale.ROOT,"SOFT_DC algo=%s db=%.3f mean=%.9f peak=%.9f%n",algorithm,db,mean/(samples.length-rate),peak);
        }
    }
}
