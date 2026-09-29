import com.quickmaster.audio.AudioFormatDetector;
import com.quickmaster.processing.*;
import com.quickmaster.processing.stereo.*;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.nio.file.*;
import java.util.*;

/** Real recordings, read-only. No PCM is saved or sent to an audio device. */
public class StereoTrackAudit {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static double share(float[] x) {
        double m=0,s=0; for(int i=0;i<x.length;i+=2) {double a=.5*(x[i]+(double)x[i+1]),b=.5*(x[i]-(double)x[i+1]);m+=a*a;s+=b*b;}
        return StereoImageAnalyzer.share(m,s);
    }
    public static void main(String[] args)throws Exception {
        for(String path:args) {
            Path source=Path.of(path);String identity=com.quickmaster.config.LevelerExclusionStore.identity(source);
            var file=AudioFormatDetector.loadAuto(path);file.load();float[] x=file.getSamples();int rate=file.getSampleRate();
            check(file.getChannels()==2,"Stereo source required");
            double inputShare=share(x);
            for(int mode=0;mode<5;mode++) {
                var settings=new StereoImageSettings(mode!=1 && mode!=2,1,mode==4,true,mode==1||mode>=3,
                        .75,mode==2||mode>=3,3,StereoProfile.ELECTRONIC,.12);
                var p=new StereoImageProcessor();p.setEnabled(true);p.setSettings(settings);
                var chain=new ProcessingPipeline();chain.addProcessor(p);chain.prepare(rate,x.length);
                long start=System.nanoTime();float[] y=chain.analyzeAndRender(x,2,0,null,null,null);
                double elapsed=(System.nanoTime()-start)/1e9;
                long changed=0;double midError=0,peak=0,minGain=0,maxGain=0,guard=0;
                for(int i=0;i<y.length;i+=2) {
                    check(Float.isFinite(y[i]) && Float.isFinite(y[i+1]),"Non-finite output");
                    midError=Math.max(midError,Math.abs(x[i]+(double)x[i+1]-y[i]-y[i+1]));
                    peak=Math.max(peak,Math.max(Math.abs(y[i]),Math.abs(y[i+1])));
                    if(y[i]!=x[i])changed++;if(y[i+1]!=x[i+1])changed++;
                }
                for(int f=0;f<x.length/2;f+=rate/50) {
                    double g=p.plan().levelGainDb(f/(double)rate);minGain=Math.min(g,minGain);maxGain=Math.max(g,maxGain);
                    guard=Math.min(guard,p.plan().guardGainDb(f/(double)rate));
                }
                check(midError<2e-6,"Mono sum changed: "+midError);
                check(changed>x.length/10,"Processor is inert");
                System.out.printf(Locale.US,"STEREO_TRACK song=%s mode=%d seconds=%.3f changed=%d midError=%.9g inputSide=%.5f outputSide=%.5f levelDb=[%.3f,%.3f] guardDb=%.3f samplePeak=%.5f%n",
                        source.getFileName(),mode,elapsed,changed,midError,inputShare,share(y),minGain,maxGain,guard,peak);
                if(mode==3) {
                    double[] times=new double[8];
                    for(int attempt=0;attempt<times.length;attempt++) {
                        var next=p.fork();next.adoptPlan(p);var previewChain=new ProcessingPipeline();previewChain.addProcessor(next);
                        var preview=new PreviewWindowRenderer(previewChain,x,rate,2,1);
                        long before=System.nanoTime();float[] window=preview.render(Math.min(rate*(20+attempt),x.length/2-rate),rate/4,new CancellationToken());
                        times[attempt]=(System.nanoTime()-before)/1e6;
                        check(window.length==rate/2,"Wrong preview length");
                    }
                    Arrays.sort(times);System.out.printf(Locale.US,"STEREO_PREVIEW_TRACK song=%s p50Ms=%.2f maxMs=%.2f noAudioDevice=true%n",source.getFileName(),times[4],times[7]);
                    check(times[7]<500,"Preview over 500ms: "+times[7]);
                }
            }
            check(identity.equals(com.quickmaster.config.LevelerExclusionStore.identity(source)),"Source changed");
            System.out.println("STEREO_TRACK_PASS sourceUnchanged=true modes=5 monoPreserved=true song="+source.getFileName());
        }
    }
}
