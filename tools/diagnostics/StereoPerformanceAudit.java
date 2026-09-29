import com.quickmaster.audio.AudioFormatDetector;
import com.quickmaster.processing.*;
import com.quickmaster.processing.stereo.*;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.util.*;

/** Serial timings of actual local preview rendering; no playback/remote PCM. */
public class StereoPerformanceAudit {
    public static void main(String[] args)throws Exception {
        var file=AudioFormatDetector.loadAuto(args[0]);file.load();int rate=file.getSampleRate();float[] pcm=file.getSamples();
        if(file.getChannels()!=2)throw new IllegalArgumentException("Require stereo");
        for(int factor:new int[]{1,4}) for(boolean regulation:new boolean[]{false,true}) {
            double[] times=new double[12];
            for(int i=-2;i<times.length;i++) {
                var stereo=new StereoImageProcessor();stereo.setEnabled(true);
                stereo.setSettings(new StereoImageSettings(true,.75,false,true,regulation,.75,regulation,3,StereoProfile.ELECTRONIC,.12));
                var pipe=new ProcessingPipeline();pipe.addProcessor(stereo);var normalizer=new PeakNormalizer();normalizer.setEnabled(false);pipe.addProcessor(normalizer);
                long start=System.nanoTime();var preview=new PreviewWindowRenderer(pipe,pcm,rate,2,factor);
                float[] out=preview.render(rate*(60+Math.max(0,i)),rate/2,new CancellationToken());
                double elapsed=(System.nanoTime()-start)/1e6;if(i>=0)times[i]=elapsed;
                for(float value:out)if(!Float.isFinite(value))throw new AssertionError("Invalid preview PCM");
            }
            Arrays.sort(times);double p95=times[(int)Math.ceil(.95*times.length)-1];
            System.out.printf(Locale.US,"STEREO_PERFORMANCE os=%d regulation=%s harmonics=automatic p50Ms=%.3f p95Ms=%.3f n=%d%n",factor,regulation,times[6],p95,times.length);
            if(p95>(factor==1?250:500))throw new AssertionError("Preview latency budget exceeded");
        }
        System.out.println("STEREO_PERFORMANCE_PASS serial=true noAudioDevice=true fullQualityHarmonics=true");
    }
}
