import com.quickmaster.audio.WavFile;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.ui.MainController;
import com.quickmaster.ui.waveform.WaveformViewport;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Published PCM -> independently scanned pixel peaks, actual FXML and screenshots. */
public final class ProcessedWaveformAudit {
    static MainController c;static Region root;static javafx.stage.Stage stage;static Path output;
    static Object get(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception{return get(c,n);}
    static void set(String n,Object v)throws Exception{var f=MainController.class.getDeclaredField(n);f.setAccessible(true);f.set(c,v);}
    static Object call(String n)throws Exception{var m=MainController.class.getDeclaredMethod(n);m.setAccessible(true);return m.invoke(c);}
    static <T>T fx(Callable<T> a)throws Exception{var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(a.call());}catch(Throwable e){f.completeExceptionally(e);}});return f.get(30,TimeUnit.SECONDS);}
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static void ready()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<end){if(fx(()->get("outputAnalysisGeneration").equals(get("levelerReadyGeneration"))&&!(boolean)get("exporting")&&(int)get("analyzeJobs")==0))return;Thread.sleep(10);}
        throw new AssertionError("Waveform publication timed out");
    }
    static float[] published()throws Exception{return (float[])get(get("player"),"publishedRender");}
    static void verify(String label,float[] pcm)throws Exception{
        WavFile audio=(WavFile)get("loadedFile");var view=(WaveformViewport)get("waveformViewport");
        float[] actual=(float[])get("waveformDownsampled");int channels=audio.getChannels(),rate=audio.getSampleRate();
        int first=(int)Math.ceil(view.startSec()*rate),last=Math.min(pcm.length/channels,(int)Math.ceil((view.startSec()+view.visibleSec())*rate));
        check(actual.length==(int)((Canvas)get("waveformCanvas")).getWidth(),"Wrong viewport width");
        for(int x=0;x<actual.length;x++){
            int start=first+(int)((long)x*(last-first)/actual.length);
            int end=first+(int)(((long)(x+1)*(last-first)+actual.length-1)/actual.length);
            float peak=0;for(int frame=start;frame<Math.min(last,end);frame++)for(int ch=0;ch<channels;ch++)peak=Math.max(peak,Math.abs(pcm[frame*channels+ch]));
            check(Float.floatToRawIntBits(peak)==Float.floatToRawIntBits(actual[x]),"Displayed PCM differs at pixel "+x+" in "+label);
        }
        System.out.println("WAVEFORM_PCM_EXACT "+label+" columns="+actual.length+" caption="+call("waveformCaption"));
    }
    static void shot(String name)throws Exception{
        root.applyCss();root.layout();call("drawWaveform");var image=root.snapshot(null,null);
        var png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        ImageIO.write(png,"png",output.resolve(name+".png").toFile());
    }
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]);Files.createDirectories(output);Platform.startup(()->{});
        try{
            fx(()->{
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                Scene scene=new Scene(root,1360,830);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.setMinSize(1360,830);root.setPrefSize(1360,830);root.setMaxSize(1360,830);root.resize(1360,830);root.applyCss();root.layout();
                stage=new javafx.stage.Stage();stage.setTitle("QuickMaster — Processed waveform verification");stage.setScene(scene);stage.show();
                ChainPreset p=(ChainPreset)call("capturePreset");p.autoEqOn=p.eqOn=p.dynamicsOn=p.clipOn=p.limitOn=false;p.normalizerOn=true;p.normalizerTargetDbtp=-18;
                var apply=MainController.class.getDeclaredMethod("applyPreset",ChainPreset.class,boolean.class);apply.setAccessible(true);apply.invoke(c,p,false);
                float[] pcm=new float[48000*20*2];for(int f=0;f<pcm.length/2;f++){
                    double amp=f<48000*8?.06:f<48000*14?.25:.13;
                    double transientAmplitude=Math.exp(-(f%12000)/300.0);
                    pcm[2*f]=(float)((amp+.2*transientAmplitude)*Math.sin(f*.033));pcm[2*f+1]=pcm[2*f]*.8f;
                }
                var file=new WavFile("generated",48000,2,pcm,32,true);set("loadedFile",file);((AudioPlayer)get("player")).prepare(file);
                call("resetWaveformViewport");call("downsampleForDisplay");call("syncLiveAnalysis");return null;
            });ready();
            Object aIndex=fx(()->get("waveformPeakIndex"));float[] a=fx(()->published());
            fx(()->{
                verify("processed-A",a);check(call("waveformCaption").equals("Processed A"),"No processed label");
                ((AudioPlayer)get("player")).seekTo(4);
                long pos=((AudioPlayer)get("player")).getPositionSamples();call("onToggleAB");
                verify("bypass",((WavFile)get("loadedFile")).getSamples());
                check(get("waveformPeakIndex")!=aIndex,"Bypass retained processed waveform");
                check(((AudioPlayer)get("player")).getPositionSamples()==pos,"Bypass moved transport");shot("waveform-original");
                call("onToggleAB");check(get("waveformPeakIndex")==aIndex,"Leaving Bypass rescanned PCM");
                call("onSelectSlotB");check(get("waveformPeakIndex")==aIndex,"Identical B lost waveform cache");
                ((CheckBox)get("limEnabled")).setSelected(true);Object push=get("bbPushKnob");push.getClass().getMethod("setValue",double.class).invoke(push,9);
                ((Slider)get("peakTarget")).setValue(-1);
                ((PauseTransition)get("dynRefreshDebounce")).stop();call("syncLiveAnalysis");
                check(get("waveformPeakIndex")==aIndex,"Pending settings changed waveform before PCM publication");
                verify("pending-retains-approved",a);check(call("waveformCaption").toString().contains("preparing"),"Pending waveform mislabelled");return null;
            });ready();
            Object bIndex=fx(()->get("waveformPeakIndex"));float[] b=fx(()->published());
            fx(()->{
                verify("limited-B",b);check(bIndex!=aIndex,"Limiter did not replace waveform");
                float[] fromSource=new com.quickmaster.ui.waveform.WaveformPeakIndex(((WavFile)get("loadedFile")).getSamples(),2).columns((WaveformViewport)get("waveformViewport"),48000,(int)((Canvas)get("waveformCanvas")).getWidth());
                check(!Arrays.equals(fromSource,(float[])get("waveformDownsampled")),"Limiter waveform still shows original");shot("waveform-limited");
                set("waveformViewport",new WaveformViewport(20,3,5));call("downsampleForDisplay");verify("zoomed-B",b);
                call("onSelectSlotA");check(get("waveformPeakIndex")==aIndex,"A did not restore cached waveform");verify("zoomed-A",a);
                call("onSelectSlotB");check(get("waveformPeakIndex")==bIndex,"B did not restore cached waveform");verify("cached-B",b);
                return null;
            });
            System.out.println("PROCESSED_WAVEFORM_PASS limiter=true bypass=true cachedAB=true pendingKeepsApproved=true zoom=true exactPixelPeaks=true transportUnchanged=true");
        }finally{fx(()->{if(c!=null)c.shutdown();if(stage!=null)stage.close();return null;});Platform.exit();}
    }
}
