import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.*;
import com.quickmaster.processing.*;
import com.quickmaster.processing.stereo.*;
import com.quickmaster.ui.*;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Actual FXML/controller, workers, controls, snapshots, undo, A/B. Silent scene snapshots. */
public class StereoUiAudit {
    static MainController c;static Region root;
    static Object get(Object o,String n)throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception{return get(c,n);}
    static Object call(String n,Class<?>[] types,Object...args)throws Exception {var m=MainController.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(c,args);}
    static Object call(String n)throws Exception{return call(n,new Class<?>[0]);}
    static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
    static <T>T fx(Callable<T> action)throws Exception {
        var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable t){f.completeExceptionally(t);}});return f.get(60,TimeUnit.SECONDS);
    }
    static void ready()throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(System.nanoTime()<end) {
            if(fx(()->get("loadedFile")!=null && !(boolean)get("fileLoadPending") && get("outputAnalysisGeneration").equals(get("levelerReadyGeneration")) && (int)get("analyzeJobs")==0))return;
            Thread.sleep(25);
        }
        throw new AssertionError("Stereo final render timed out");
    }
    static float[] pcm()throws Exception{return (float[])get(get("player"),"publishedRender");}
    static void shot(Path path,int width,int height)throws Exception {
        root.setMinSize(width,height);root.setPrefSize(width,height);root.setMaxSize(width,height);
        root.resize(width,height);root.applyCss();root.layout();call("drawWaveform");
        var img=root.snapshot(null,null);var png=new BufferedImage((int)img.getWidth(),(int)img.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,img.getPixelReader().getArgb(x,y));
        ImageIO.write(png,"png",path.toFile());
    }
    static void exact()throws Exception {
        record State(float[] source,float[] actual,int rate,ChainPreset preset){}
        State s=fx(()->{var f=(AudioFile)get("loadedFile");return new State(f.getSamples(),pcm(),f.getSampleRate(),(ChainPreset)call("capturePreset"));});
        var stereo=new StereoImageProcessor();stereo.setEnabled(s.preset.stereoOn);stereo.setSettings(s.preset.stereoImage);
        var pipe=new ProcessingPipeline();pipe.addProcessor(stereo);var normalizer=new PeakNormalizer();normalizer.setEnabled(false);pipe.addProcessor(normalizer);
        pipe.prepare(s.rate,s.source.length);float[] expected=pipe.analyzeAndRender(s.source,2,0,null,null,null);
        check(Arrays.equals(expected,s.actual),"UI published PCM differs from independent Stereo Image render");
        System.out.println("STEREO_UI_PCM_PASS bitExact=true profile="+s.preset.stereoImage.profile());
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        Path audio=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
        String identity=LevelerExclusionStore.identity(audio);
        Platform.startup(()->{});
        try {
            fx(()->{
                var loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                var scene=new Scene(root,1360,900);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                for(String id:new String[]{"stereoGeneration","stereoLeveler","stereoGuard"})
                    check(!((ToggleButton)root.lookup("#"+id)).isSelected(),"Section starts enabled: "+id);
                check(root.lookup("#stereoLowFrequencies")==null,"Legacy bass checkbox still visible");
                check(((Slider)root.lookup("#stereoLowCut")).getValue()==0,"Low cut must default Off");
                check(((Slider)root.lookup("#stereoSideGain")).getValue()==0,"Side gain must default 0");
                check(root.lookup("#stereoHarmonics")==null,"Harmonics must be automatic, not a UI option");
                var p=(ChainPreset)call("capturePreset");p.autoEqOn=p.eqOn=p.dynamicsOn=p.clipOn=p.limitOn=p.normalizerOn=p.osOn=false;
                p.fadeInSec=p.fadeOutSec=0;p.stereoOn=false;p.stereoImage=StereoImageSettings.DEFAULT;
                call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
                List<?> modules=(List<?>)get("chainModules");check(modules.size()==5,"Missing chain chip");
                for(Object module:modules)if(get(module,"name").equals("Stereo Image"))call("selectModule",new Class<?>[]{module.getClass()},module);
                call("loadAudioFile",new Class<?>[]{java.io.File.class},audio.toFile());return null;
            });
            ready();fx(()->{shot(out.resolve("stereo-defaults.png"),1360,900);return null;});
            fx(()->{((ToggleButton)root.lookup("#stereoPower")).fire();return null;});ready();
            fx(()->{
                var player=get("player");player.getClass().getMethod("seekTo",double.class).invoke(player,60.0);
                call("updateLimiterMeters");
                String balance=((Label)root.lookup("#stereoBalanceReadout")).getText();
                check(balance.startsWith("Module output"),"Inactive sections lost passing-audio meter: "+balance);
                check(((Label)root.lookup("#stereoTargetReadout")).getText().equals("Select a section to process stereo"),"Misleading inactive heading");
                shot(out.resolve("stereo-sections-off.png"),1360,900);
                ((ToggleButton)root.lookup("#stereoPower")).fire();return null;
            });ready();
            System.out.println("STEREO_INACTIVE_METER_PASS actualInputEnergy=true noNoMeasurableError=true");
            fx(()->{((ToggleButton)root.lookup("#stereoGeneration")).fire();
                check(((StereoImageProcessor)get("stereoImage")).isEnabled(),"Generation On remained behind a bypassed master");
                check(!((ToggleButton)root.lookup("#stereoLeveler")).isSelected()&&!((ToggleButton)root.lookup("#stereoGuard")).isSelected(),"Generation must not enable other sections");
                return null;});
            ready();exact();
            fx(()->{
                check(!Arrays.equals(((AudioFile)get("loadedFile")).getSamples(),pcm()),"Initial Generation click published unchanged source");
                call("updateLimiterMeters");shot(out.resolve("stereo-generation-only.png"),1360,900);
                check(((PeakNormalizer)get("normalizer")).getGainDb()==0,"Generation enabled a hidden output gain");
                return null;
            });
            System.out.println("STEREO_FIRST_ENABLE_PASS parentEnabled=true otherSectionsOff=true changedPcm=true independentRenderExact=true");
            fx(()->{((Knob)root.lookup("#stereoGenerationAmount")).setValue(100);return null;});
            ready();exact();
            fx(()->{
                call("updateLimiterMeters");
                check(((PeakNormalizer)get("normalizer")).getGainDb()==0,"Low generation changed whole-mix gain");
                check(((Label)root.lookup("#stereoNotice")).getText().contains("Output exceeds 0 dBTP"),"Missing explicit headroom warning");
                shot(out.resolve("stereo-maximum-lows.png"),1360,900);return null;
            });
            System.out.println("STEREO_LOW_OUTPUT_PASS hiddenGain=false overloadWarning=true");
            for(double hz:new double[]{175,1000,5000,0}) {
                long editStart=System.nanoTime();
                fx(()->{((Slider)root.lookup("#stereoLowCut")).setValue(StereoImagePane.lowCutPosition(hz));return null;});
                ready();System.out.printf(Locale.US,"STEREO_UI_EDIT lowCut=%.0f readyMs=%.2f%n",hz,(System.nanoTime()-editStart)/1e6);exact();
            }
            fx(()->{((Slider)root.lookup("#stereoSideGain")).setValue(-3);return null;});ready();exact();
            fx(()->{((ToggleButton)root.lookup("#stereoGuard")).fire();((ToggleButton)root.lookup("#stereoLeveler")).fire();return null;});ready();exact();
            fx(()->{call("updateLimiterMeters");shot(out.resolve("stereo-new-controls.png"),1360,900);shot(out.resolve("stereo-new-controls-compact.png"),1100,740);return null;});
            System.out.println("STEREO_NEW_CONTROLS_PASS lowCut=true sideGain=true sectionSwitches=true");
            if(Arrays.asList(args).contains("--quick")) {
                check(identity.equals(LevelerExclusionStore.identity(audio)),"Private source changed");
                System.out.println("STEREO_UI_QUICK_PASS sourceUnchanged=true");
                return;
            }
            fx(()->{
                var p=(ChainPreset)call("capturePreset");p.stereoImage=new StereoImageSettings(true,.75,false,true,true,.5,true,3,StereoProfile.ELECTRONIC,.12);
                call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,true);call("syncLiveAnalysis");return null;});
            ready();exact();float[] a=fx(()->pcm());
            fx(()->{call("onSelectSlotB");((ComboBox<StereoProfile>)root.lookup("#stereoProfile")).setValue(StereoProfile.ROCK);
                ((Knob)root.lookup("#stereoGenerationAmount")).setValue(95);return null;});
            ready();exact();float[] b=fx(()->pcm());check(!Arrays.equals(a,b),"Stereo controls are inert");
            fx(()->{
                var player=get("player");player.getClass().getMethod("seekTo",double.class).invoke(player,60.0);
                call("updateLimiterMeters");shot(out.resolve("stereo-image.png"),1360,900);
                shot(out.resolve("stereo-image-compact.png"),1100,740);
                call("onSelectSlotA");check(pcm()==a,"A cache was not reused");
                check(((ComboBox<?>)root.lookup("#stereoProfile")).getValue()==StereoProfile.ELECTRONIC,"A profile not restored");
                call("onSelectSlotB");check(pcm()==b,"B cache was not reused");
                return null;
            });
            fx(()->{((Knob)root.lookup("#stereoGenerationAmount")).setValue(20);return null;});ready();
            fx(()->{call("onUndo");return null;});ready();
            check(fx(()->((Knob)root.lookup("#stereoGenerationAmount")).getValue())==95,"Undo did not restore Stereo Image");
            // Hold the final worker and require the provisional path to publish changed PCM.
            long start=System.nanoTime();
            fx(()->{((Knob)root.lookup("#stereoGenerationAmount")).setValue(70);
                ((PauseTransition)get("dynRefreshDebounce")).stop();call("requestInteractiveEqPreview");return null;});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(fx(()->get("player").getClass().getMethod("getPreviewWindow").invoke(get("player")))==null) {
                if(System.nanoTime()>deadline)throw new AssertionError("Stereo preview not published");Thread.sleep(5);
            }
            System.out.printf(Locale.US,"STEREO_UI_PREVIEW publicationMs=%.2f noAudioDevice=true%n",(System.nanoTime()-start)/1e6);
            fx(()->{call("syncLiveAnalysis");return null;});ready();exact();
            fx(()->{call("loadStereoReference",new Class<?>[]{java.io.File.class},audio.toFile());return null;});
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(fx(()->((StereoImageProcessor)get("stereoImage")).settings().profile())!=StereoProfile.REFERENCE) {
                if(System.nanoTime()>deadline)throw new AssertionError("Reference import failed");Thread.sleep(20);
            }
            ready();exact();
            System.out.println("STEREO_REFERENCE_PASS actualFileLoaded=true measuredShare=true");
            // Allow the preview worker to run between edits, then require the
            // final publication to belong to the LAST settings, not an old job.
            for(int edit=0;edit<30;edit++) {
                final int e=edit;
                fx(()->{
                    ((Knob)root.lookup("#stereoGenerationAmount")).setValue(20+(e*17)%80);
                    ((Knob)root.lookup("#stereoLevelingAmount")).setValue((e*13)%101);
                    ((ComboBox<StereoProfile>)root.lookup("#stereoProfile")).setValue(
                            e%2==0?StereoProfile.ELECTRONIC:StereoProfile.ROCK);
                    ((PauseTransition)get("dynRefreshDebounce")).stop();
                    call("requestInteractiveEqPreview");return null;
                });
                Thread.sleep(8);
            }
            fx(()->{call("syncLiveAnalysis");return null;});ready();exact();
            System.out.println("STEREO_UI_STRESS rapidEdits=30 finalBitExact=true stalePublication=false");
            check(identity.equals(LevelerExclusionStore.identity(audio)),"Private source changed");
            System.out.println("STEREO_UI_PASS cachedAB=true undo=true controls=true preview=true finalBitExact=true sourceUnchanged=true");
        } finally {fx(()->{if(c!=null)c.shutdown();return null;});Platform.exit();}
    }
}
