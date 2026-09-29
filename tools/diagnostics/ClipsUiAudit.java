import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.processing.*;
import com.quickmaster.processing.clip.*;
import com.quickmaster.ui.*;
import com.dspark.effects.Saturation;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.Region;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Actual English controls, preset migration, latest worker/PCM, meter and A/B. */
class ClipsUiAudit {
    static MainController c;static Region root;static javafx.stage.Stage stage;
    static Object get(Object o,String n)throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception {return get(c,n);}
    static Object call(String n,Class<?>[] types,Object...args)throws Exception {var m=MainController.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(c,args);}
    static Object call(String n)throws Exception {return call(n,new Class<?>[0]);}
    static void check(boolean ok,String s) {if(!ok)throw new AssertionError(s);}
    static <T>T fx(Callable<T> action)throws Exception {var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable t){f.completeExceptionally(t);}});return f.get(60,TimeUnit.SECONDS);}
    static void ready()throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
        while(System.nanoTime()<until) {if(fx(()->get("loadedFile")!=null && !(boolean)get("fileLoadPending") && get("outputAnalysisGeneration").equals(get("levelerReadyGeneration")) && (int)get("analyzeJobs")==0))return;Thread.sleep(25);}
        throw new AssertionError("Publication timed out");
    }
    static float[] pcm()throws Exception {return (float[])get(get("player"),"publishedRender");}
    static void verify()throws Exception {
        record State(float[] source,float[] actual,int rate,int channels,ChainPreset preset) { }
        State s=fx(()->{AudioFile f=(AudioFile)get("loadedFile");return new State(f.getSamples(),pcm(),f.getSampleRate(),f.getChannels(),(ChainPreset)call("capturePreset"));});
        var soft=new SoftClipProcessor();soft.setEnabled(true);soft.setSatDb(s.preset.softClipDb);soft.setAlgorithm(Saturation.Algorithm.valueOf(s.preset.softClipAlgo));
        var hard=new HardClipProcessor();hard.setEnabled(true);hard.setClipDb(s.preset.hardClipDb);hard.setCurve(HardClipProcessor.Curve.valueOf(s.preset.hardClipCurve));
        var pipe=new ProcessingPipeline();pipe.addProcessor(soft);pipe.addProcessor(hard);pipe.addProcessor(new PeakNormalizer(-1));pipe.prepare(s.rate,s.source.length);
        float[] cold=pipe.analyzeAndRender(s.source,s.channels,0,null,null,null);
        check(Arrays.equals(cold,s.actual),"Published clip PCM differs from fresh cumulative render");
        fx(()-> {
            Method audible=MainController.class.getDeclaredMethod("audibleProcessor",AudioProcessor.class);audible.setAccessible(true);
            var approvedSoft=(SoftClipProcessor)audible.invoke(c,get("softClip"));
            var approvedHard=(HardClipProcessor)audible.invoke(c,get("hardClip"));
            for(int f=0;f<s.source.length/s.channels;f+=1024) {
                check(Math.abs(soft.getGrAtPosition(f)-approvedSoft.getGrAtPosition(f))<.00001,"Soft meter snapshot");
                check(Math.abs(hard.getGrAtPosition(f)-approvedHard.getGrAtPosition(f))<.00001,"Hard meter snapshot");
            }
            return null;
        });
        System.out.println("CLIPS_UI_PCM_PASS soft="+s.preset.softClipAlgo+" hard="+s.preset.hardClipCurve+" coldExact=true meters=true");
    }
    static void shot(Path path)throws Exception {
        root.applyCss();root.layout();call("drawWaveform");var img=root.snapshot(null,null);
        var png=new BufferedImage((int)img.getWidth(),(int)img.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,img.getPixelReader().getArgb(x,y));
        ImageIO.write(png,"png",path.toFile());
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        String before=com.quickmaster.config.LevelerExclusionStore.identity(source);
        Platform.startup(()->{});
        try {
            fx(()-> {
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                Scene scene=new Scene(root,1360,830);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.setMinSize(1360,830);root.setPrefSize(1360,830);root.setMaxSize(1360,830);
                stage=new javafx.stage.Stage();stage.setTitle("QuickMaster — clipping audit");stage.setScene(scene);stage.show();
                ChainPreset p=(ChainPreset)call("capturePreset");p.autoEqOn=p.eqOn=p.dynamicsOn=p.limitOn=false;p.clipOn=p.softClipOn=p.hardClipOn=true;
                p.softClipDb=2;p.softClipAlgo="TUBE";p.hardClipDb=1;p.hardClipCurve="SOFT";p.normalizerOn=true;p.normalizerTargetDbtp=-1;p.osOn=false;
                call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
                check(((ComboBox<?>)get("hardCurveCombo")).getValue()==HardClipProcessor.Curve.SOFT,"Hidden preset curve");
                ComboBox<Saturation.Algorithm> combo=(ComboBox<Saturation.Algorithm>)get("satAlgoCombo");
                check(combo.getConverter().toString(combo.getValue()).equals("Analog"),"Misleading legacy algorithm label");
                for(Object module:(List<?>)get("chainModules"))if(get(module,"name").equals("Clip"))call("selectModule",new Class<?>[]{module.getClass()},module);
                call("loadAudioFile",new Class<?>[]{java.io.File.class},source.toFile());return null;
            });
            ready();verify();float[] a=fx(()->pcm());
            fx(()-> {call("onSelectSlotB");((Knob)get("satKnob")).setValue(1);((Knob)get("satKnob")).setValue(4);
                ((ComboBox<HardClipProcessor.Curve>)get("hardCurveCombo")).setValue(HardClipProcessor.Curve.HARD);
                ((ComboBox<Saturation.Algorithm>)get("satAlgoCombo")).setValue(Saturation.Algorithm.TRANSFORMER);
                ((PauseTransition)get("dynRefreshDebounce")).stop();call("syncLiveAnalysis");return null;});
            ready();verify();float[] b=fx(()->pcm());check(!Arrays.equals(a,b),"Controls did not change audio");
            fx(()-> {
                Method audible=MainController.class.getDeclaredMethod("audibleProcessor",AudioProcessor.class);audible.setAccessible(true);
                var soft=(SoftClipProcessor)audible.invoke(c,get("softClip"));
                var hard=(HardClipProcessor)audible.invoke(c,get("hardClip"));
                AudioFile file=(AudioFile)get("loadedFile");int peakFrame=0;double largest=0;
                for(int f=0;f<file.getSamples().length/file.getChannels();f+=1024) {
                    double gr=soft.getGrAtPosition(f)+hard.getGrAtPosition(f);
                    if(gr<largest){largest=gr;peakFrame=f;}
                }
                check(largest<-1,"No visible gain reduction to inspect");
                get("player").getClass().getMethod("seekTo",double.class).invoke(get("player"),peakFrame/(double)file.getSampleRate());
                call("updateClipMeters");
                List<?> cards=(List<?>)get("clipCardList");
                for(int k=0;k<2;k++) {
                    double expected=k==0?soft.getGrAtPosition(peakFrame):hard.getGrAtPosition(peakFrame);
                    String label=((javafx.scene.control.Label)get(cards.get(k),"grLabel")).getText();
                    check(label.equals(String.format(Locale.US,"%+.1f dB",expected)),"Visible meter differs from audible PCM: "+label);
                }
                return null;
            });
            // Let the queued transport-position notification reach its labels
            // before capturing; this is a real seek, not a painted test value.
            fx(()-> {shot(output.resolve("clips-audit.png"));call("onSelectSlotA");check(pcm()==a,"A cache");
                check(((ComboBox<?>)get("hardCurveCombo")).getValue()==HardClipProcessor.Curve.SOFT,"A curve UI");
                call("onSelectSlotB");check(pcm()==b,"B cache");return null;});
            check(before.equals(com.quickmaster.config.LevelerExclusionStore.identity(source)),"Source changed");
            System.out.println("CLIPS_UI_PASS EnglishCurves=true presetRestored=true publishedPcm=true meters=true cachedAB=true sourceUnchanged=true");
        } finally {fx(()-> {if(c!=null)c.shutdown();if(stage!=null)stage.close();return null;});Platform.exit();}
    }
}
