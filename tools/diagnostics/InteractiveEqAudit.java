import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.playback.PreviewWindow;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Real controller + device consumption; optional private excerpts are temporary and deleted. */
class InteractiveEqAudit {
    static MainController c; static Region root; static javafx.stage.Stage stage;
    static Object get(Object o,String n)throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception{return get(c,n);}
    static Object call(String n,Class<?>[] types,Object...args)throws Exception {var m=MainController.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(c,args);}
    static Object call(String n)throws Exception{return call(n,new Class<?>[0]);}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static <T>T fx(Callable<T> action)throws Exception {var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable t){f.completeExceptionally(t);}});return f.get(60,TimeUnit.SECONDS);}
    static void ready()throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(System.nanoTime()<end){if(fx(()->get("loadedFile")!=null&&!(boolean)get("fileLoadPending")&&get("outputAnalysisGeneration").equals(get("levelerReadyGeneration"))&&(int)get("analyzeJobs")==0))return;Thread.sleep(20);}
        throw new AssertionError("Full render timed out");
    }
    static void shot(Path file)throws Exception {
        root.applyCss();root.layout();call("drawWaveform");var img=root.snapshot(null,null);
        var png=new BufferedImage((int)img.getWidth(),(int)img.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,img.getPixelReader().getArgb(x,y));
        ImageIO.write(png,"png",file.toFile());
    }
    public static void main(String[] args)throws Exception {
        Path audio=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
        boolean full=Arrays.asList(args).contains("--full-chain");int factor=1;
        for(String arg:args)if(arg.startsWith("--os="))factor=Integer.parseInt(arg.substring(5));final int os=factor;
        String identity=com.quickmaster.config.LevelerExclusionStore.identity(audio);
        Path temporary=null;
        if(Arrays.asList(args).contains("--excerpt")) {
            var original=new com.quickmaster.audio.WavFile(audio.toString());original.load();
            int rate=original.getSampleRate(),ch=original.getChannels();
            int start=Math.min(rate*60,Math.max(0,original.getSamples().length/ch-rate*30));
            int end=Math.min(original.getSamples().length/ch,start+rate*30);
            temporary=Files.createTempFile("quickmaster-private-preview-", ".wav");
            new com.quickmaster.audio.WavFile(temporary.toString(),rate,ch,Arrays.copyOfRange(original.getSamples(),start*ch,end*ch),32,true).save(temporary.toString());
            System.out.println("EQ_INTERACTIVE_EXCERPT seconds="+((end-start)/(double)rate)+" originalReadOnly=true temporaryWillBeDeleted=true");
        }
        final Path input=temporary==null?audio:temporary;
        Platform.startup(()->{});
        try {
            fx(()->{
                var loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                var scene=new Scene(root,1360,900);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                stage=new javafx.stage.Stage();stage.setTitle("QuickMaster — interactive EQ verification");stage.setScene(scene);stage.show();
                var p=(ChainPreset)call("capturePreset");p.autoEqOn=false;p.dynamicsOn=p.clipOn=p.limitOn=full;p.normalizerOn=false;
                p.eqOn=p.eqAutoGain=true;p.osOn=os>1;p.osFactor=os;p.fadeInSec=p.fadeOutSec=0;
                if(full) {p.peakCompOn=p.beatCompOn=p.levelerOn=p.punchOn=p.softClipOn=p.hardClipOn=true;p.leveling=.5;}
                var b=new ChainPreset.BandPreset();b.type="BELL";b.channel="STEREO";b.phase="LINEAR";b.frequency=857;b.gainDb=0;b.q=.71;
                b.aboveRatio=b.belowRatio=1;b.aboveAttackMs=b.belowAttackMs=10;b.aboveReleaseMs=b.belowReleaseMs=100;p.bands=List.of(b);
                call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
                call("loadAudioFile",new Class<?>[]{java.io.File.class},input.toFile());return null;
            });
            ready();AudioPlayer player=fx(()->(AudioPlayer)get("player"));
            AudioFile file=fx(()->(AudioFile)get("loadedFile"));float[] oldMaster=(float[])get(player,"publishedRender");
            if(full)check(fx(()->((com.quickmaster.processing.dynamics.MacroLevelerProcessor)get("leveler")).getAnalysisReport()!=null),"Full-chain Leveler is not analyzed");
            if(Arrays.asList(args).contains("--eligibility-only")) {
                fx(()->{
                    var mask=com.quickmaster.processing.dynamics.macro.LevelerExclusions.EMPTY.add(0,2L*file.getSampleRate());
                    call("commitLevelerExclusions",new Class<?>[]{mask.getClass()},mask);
                    Object knob=get("kGain");knob.getClass().getMethod("setValue",double.class).invoke(knob,6.0);
                    ((PauseTransition)get("dynRefreshDebounce")).stop();call("requestInteractiveEqPreview");
                    check(get(get("interactivePreview"),"current")==null,"Preview reused an obsolete exclusion context");
                    call("syncLiveAnalysis");return null;
                });ready();
                fx(()->{
                    Object knob=get("kGain");knob.getClass().getMethod("setValue",double.class).invoke(knob,9.0);
                    ((PauseTransition)get("dynRefreshDebounce")).stop();call("requestInteractiveEqPreview");
                    check(get(get("interactivePreview"),"current")!=null,"Completed context did not re-enable EQ preview");
                    ((javafx.scene.control.TextField)get("beatBpmField")).setText("123");call("commitManualBpm");
                    ((PauseTransition)get("dynRefreshDebounce")).stop();call("requestInteractiveEqPreview");
                    check(get(get("interactivePreview"),"current")==null,"Preview reused an obsolete tempo context");
                    return null;
                });
                check(identity.equals(com.quickmaster.config.LevelerExclusionStore.identity(audio)),"Private source modified");
                System.out.println("EQ_PREVIEW_CONTEXT_PASS exclusions=true tempo=true resumesAfterFinal=true sourceUnchanged=true");
                return;
            }
            fx(()->{player.seekTo(Math.min(60,Math.max(0,file.getDuration()-10)));call("requestPlayback");return null;});
            Thread.sleep(300);
            List<Double> timings=new ArrayList<>();
            for(double gain:new double[]{6,12,-6,18.8,3,-3}) {
                long previous=player.getConsumedPreviewGeneration();long start=System.nanoTime();
                fx(()->{
                    Object knob=get("kGain");knob.getClass().getMethod("setValue",double.class).invoke(knob,gain);
                    // Deliberately hold the final render: only interactive PCM can respond.
                    ((PauseTransition)get("dynRefreshDebounce")).stop();return null;
                });
                long deadline=start+TimeUnit.SECONDS.toNanos(4);PreviewWindow window;
                while((window=player.getPreviewWindow())==null||window.generation()<=previous) {
                    if(System.nanoTime()>deadline)throw new AssertionError("No preview publication");Thread.sleep(2);
                }
                double publishMs=(System.nanoTime()-start)/1e6;
                while(player.getConsumedPreviewGeneration()<=previous) {
                    if(System.nanoTime()>deadline)throw new AssertionError("Preview never reached audio device");Thread.sleep(2);
                }
                double consumeMs=(System.nanoTime()-start)/1e6;timings.add(consumeMs);
                check((float[])get(player,"publishedRender")==oldMaster,"Whole-track render unexpectedly changed");
                check(fx(()->!get("outputAnalysisGeneration").equals(get("levelerReadyGeneration"))),"Preview falsely marked final");
                double peak=com.dspark.analysis.TruePeak.measureMax(window.pcm(),file.getChannels());
                check(peak<1,"Preview overload");long changed=0;
                for(int i=0;i<window.pcm().length;i++)if(window.pcm()[i]!=oldMaster[(int)window.startFrame()*file.getChannels()+i])changed++;
                check(changed>window.pcm().length/2,"Preview did not change the audible signal");
                System.out.printf(Locale.ROOT,"EQ_INTERACTIVE gain=%.1f os=%d full=%s publishMs=%.3f consumedMs=%.3f dbtp=%.3f changed=%d finalStillPending=true%n",gain,os,full,publishMs,consumeMs,20*Math.log10(peak),changed);
                if(gain==18.8)fx(()->{shot(out.resolve("preview.png"));return null;});
                Thread.sleep(110);
            }
            // Seek, loop and paused playback must not require a whole-song rebuild.
            Set<Long> heard=new HashSet<>();
            for(int i=0;i<24;i++) {
                final double gain=2+(i%11);
                fx(()->{Object knob=get("kGain");knob.getClass().getMethod("setValue",double.class).invoke(knob,gain);((PauseTransition)get("dynRefreshDebounce")).stop();return null;});
                heard.add(player.getConsumedPreviewGeneration());Thread.sleep(25);
            }
            check(heard.size()>=3,"Sustained drag starved preview publication");
            System.out.println("EQ_INTERACTIVE_DRAG heardGenerations="+heard.size()+" noStarvation=true");
            double loop=Math.min(70,Math.max(0,file.getDuration()-3));
            fx(()->{player.setLoopRegion((long)(loop*file.getSampleRate()),(long)((loop+1)*file.getSampleRate()));player.seekTo(loop);return null;});
            Thread.sleep(1300);check(player.isPlaying(),"Preview stopped transport");player.clearLoopRegion();
            // Also exercise the NORMAL UI path, with the whole-track worker
            // allowed to start after its debounce and compete with preview.
            long beforeBackground=player.getConsumedPreviewGeneration(),backgroundStart=System.nanoTime();
            fx(()->{Object knob=get("kGain");knob.getClass().getMethod("setValue",double.class).invoke(knob,9.0);return null;});
            while(player.getConsumedPreviewGeneration()<=beforeBackground) {
                if(System.nanoTime()-backgroundStart>TimeUnit.SECONDS.toNanos(4))throw new AssertionError("Background render blocked preview");
                Thread.sleep(2);
            }
            double backgroundMs=(System.nanoTime()-backgroundStart)/1e6;
            check(fx(()->!get("outputAnalysisGeneration").equals(get("levelerReadyGeneration"))),"Full render beat interactive audition");
            Thread.sleep(800);check(player.isPlaying(),"Background render interrupted playback");
            System.out.printf(Locale.ROOT,"EQ_INTERACTIVE_BACKGROUND consumedMs=%.3f normalDebounce=true finalStillPendingAtFirstAudio=true transportContinued=true%n",backgroundMs);
            fx(()->{player.pause();return null;});ready();
            check(player.getPreviewWindow()==null,"Final render retained provisional window");
            float[] finalPcm=(float[])get(player,"publishedRender");
            Object snap=fx(()->call("buildSnapshot"));var pipeline=(ProcessingPipeline)get(snap,"pipeline");
            var reference=new com.quickmaster.audio.WavFile("read-only",file.getSampleRate(),file.getChannels(),file.getSamples(),32,true);
            pipeline.processOversampled(reference,os,null);check(Arrays.equals(reference.getSamples(),finalPcm),"Final PCM differs from exact cold render");
            fx(()->{shot(out.resolve("final.png"));return null;});
            check(identity.equals(com.quickmaster.config.LevelerExclusionStore.identity(audio)),"Private source modified");
            double median=timings.stream().skip(1).sorted().toList().get(2);
            check(median<(os==1?150:750),"Interactive response budget exceeded: "+median);
            System.out.printf(Locale.ROOT,"EQ_INTERACTIVE_PASS medianWarmConsumedMs=%.3f actualPlayback=true noFullRenderBarrier=true finalBitExact=true sourceUnchanged=true%n",median);
        } finally {fx(()->{if(c!=null)c.shutdown();if(stage!=null)stage.close();return null;});Platform.exit();if(temporary!=null)Files.deleteIfExists(temporary);}
    }
}
