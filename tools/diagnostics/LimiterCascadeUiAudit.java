import com.dspark.analysis.TruePeak;
import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.processing.*;
import com.quickmaster.processing.limit.*;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.layout.Region;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Real file load, knobs, publication, A/B, final PCM and screenshots; no source writes. */
public final class LimiterCascadeUiAudit {
    static MainController c;static Region root;static javafx.stage.Stage stage;
    static Object get(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception{return get(c,n);}
    static Object call(String n,Class<?>[] types,Object...args)throws Exception{var m=MainController.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(c,args);}
    static Object call(String n)throws Exception{return call(n,new Class<?>[0]);}
    static <T>T fx(Callable<T> action)throws Exception{var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable t){f.completeExceptionally(t);}});return f.get(60,TimeUnit.SECONDS);}
    static void check(boolean valid,String s){if(!valid)throw new AssertionError(s);}
    static void ready()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
        while(System.nanoTime()<end){if(fx(()->get("loadedFile")!=null&&!(boolean)get("fileLoadPending")&&get("outputAnalysisGeneration").equals(get("levelerReadyGeneration"))&&(int)get("analyzeJobs")==0&&!(boolean)get("exporting")))return;Thread.sleep(20);}
        throw new AssertionError("Limiter publication timed out");
    }
    static float[] pcm()throws Exception{return (float[])get(get("player"),"publishedRender");}
    static double rms(float[] x){double e=0;for(float v:x)e+=(double)v*v;return 10*Math.log10(e/x.length);}
    static void bands(double value)throws Exception{
        for(Object k:(Object[])get("mbPushKnobs"))k.getClass().getMethod("setValue",double.class).invoke(k,value);
        ((PauseTransition)get("dynRefreshDebounce")).stop();call("syncLiveAnalysis");
        check(((BroadbandLimiterProcessor)get("broadband")).getPushDb()==3,"Band gesture changed broadband Push");
    }
    static void verify(String label)throws Exception {
        record State(float[] source,float[] actual,int rate,int channels,double[] bands,double reference,double requested){}
        State s=fx(()->{
            AudioFile file=(AudioFile)get("loadedFile");var preset=(ChainPreset)call("capturePreset");var bb=(BroadbandLimiterProcessor)get("broadband");
            return new State(file.getSamples(),pcm(),file.getSampleRate(),file.getChannels(),preset.mbPushDb,bb.getCeilingTruePeak(),bb.getPushDb());
        });
        check(s.requested==3,"Push value was overwritten");check(s.reference==TruePeak.measureMax(s.source,s.channels),"Ceiling follows multiband instead of module input");
        var mb=new MultibandLimiterProcessor();mb.setEnabled(true);for(int b=0;b<4;b++)mb.setPushDb(b,s.bands[b]);
        var bb=new BroadbandLimiterProcessor();bb.setEnabled(true);bb.setPushDb(3);
        var pipe=new ProcessingPipeline();pipe.addProcessor(mb);pipe.addProcessor(bb);pipe.addProcessor(new PeakNormalizer(-1));pipe.prepare(s.rate,s.source.length);
        float[] cold=pipe.analyzeAndRender(s.source,s.channels,0,null,null,null);
        check(Arrays.equals(cold,s.actual),"Published PCM differs from cold serial render");
        double tp=20*Math.log10(TruePeak.measureMax(s.actual,s.channels));check(Math.abs(tp+1)<.00002,"Wrong delivery ceiling");
        System.out.printf(Locale.ROOT,"LIMITER_UI_PCM %s push=%.1f reference=%.9f rms=%.6f truePeak=%.6f bitExact=true%n",label,s.requested,s.reference,rms(s.actual),tp);
    }
    static void shot(Path path)throws Exception{
        root.applyCss();root.layout();call("drawWaveform");var img=root.snapshot(null,null);
        var png=new BufferedImage((int)img.getWidth(),(int)img.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,img.getPixelReader().getArgb(x,y));
        ImageIO.write(png,"png",path.toFile());
    }
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        String before=com.quickmaster.config.LevelerExclusionStore.identity(source);
        System.out.println("JAR "+MainController.class.getProtectionDomain().getCodeSource().getLocation());Platform.startup(()->{});
        try {
            fx(()->{
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                Scene scene=new Scene(root,1360,830);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.setMinSize(1360,830);root.setPrefSize(1360,830);root.setMaxSize(1360,830);
                stage=new javafx.stage.Stage();stage.setTitle("QuickMaster — Limiter cascade audit");stage.setScene(scene);stage.show();
                ChainPreset p=(ChainPreset)call("capturePreset");p.autoEqOn=p.eqOn=p.dynamicsOn=p.clipOn=false;p.limitOn=true;p.bbPushDb=3;p.mbPushDb=new double[4];p.normalizerOn=true;p.normalizerTargetDbtp=-1;p.osOn=false;
                call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
                for(Object module:(List<?>)get("chainModules"))if(get(module,"name").equals("Limit"))call("selectModule",new Class<?>[]{module.getClass()},module);
                call("loadAudioFile",new Class<?>[]{java.io.File.class},source.toFile());return null;
            });ready();verify("A-bands-zero");float[] a=fx(()->pcm());
            fx(()->{call("onSelectSlotB");bands(3);return null;});ready();verify("B-bands-three");float[] b=fx(()->pcm());
            check(rms(b)>rms(a)+.2,"Multiband improvement was cancelled on the reported song");
            fx(()->{shot(output.resolve("limiter-cascade.png"));call("onSelectSlotA");check(pcm()==a,"A cache mismatch");call("onSelectSlotB");check(pcm()==b,"B cache mismatch");bands(1);bands(6);bands(2);return null;});
            ready();verify("latest-rapid-edit");
            check(before.equals(com.quickmaster.config.LevelerExclusionStore.identity(source)),"Source file changed");
            System.out.printf(Locale.ROOT,"LIMITER_UI_PASS pushUnchanged=true fixedReference=true stagedColdExact=true cachedAB=true latestEdit=true sourceUnchanged=true normalizedRmsIncreaseDb=%.6f%n",rms(b)-rms(a));
        }finally{fx(()->{if(c!=null)c.shutdown();if(stage!=null)stage.close();return null;});Platform.exit();}
    }
}
