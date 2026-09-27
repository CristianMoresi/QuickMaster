import com.quickmaster.audio.*;
import com.quickmaster.processing.*;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.animation.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real FXML/Task source replacement and close races, with generated test audio only. */
public class SourceAnalysisRaceProbe {
    static final CompletableFuture<Void> done=new CompletableFuture<>();
    static MainController controller;
    static long deadline;
    static Path missingFile;
    static Object field(String name) throws Exception {
        Field f=MainController.class.getDeclaredField(name); f.setAccessible(true); return f.get(controller);
    }
    static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    static void invoke(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=MainController.class.getDeclaredMethod(name,types); m.setAccessible(true); m.invoke(controller,args);
    }
    static void later(Runnable action) {
        PauseTransition delay=new PauseTransition(Duration.millis(25));
        delay.setOnFinished(e->{try{action.run();}catch(Throwable ex){done.completeExceptionally(ex);}}); delay.play();
    }
    static void waitReady(Runnable action) {
        try {
            if(System.nanoTime()>deadline) throw new AssertionError("Source race timed out");
            long current=(long)field("outputAnalysisGeneration");
            if((long)field("levelerFailedGeneration")==current) throw new AssertionError("Current output failed");
            if(field("loadedFile")==null || (boolean)field("sourceAnalysisPending") || (int)field("analyzeJobs")!=0
                    || !field("levelerReadyGeneration").equals(current)) {
                later(()->waitReady(action)); return;
            }
            action.run();
        } catch(Throwable ex){done.completeExceptionally(ex);}
    }
    static Path fixture(Path directory,String name,int rate,int seconds,double bpm) throws Exception {
        float[] pcm=new float[rate*seconds*2];
        for(int f=0;f<rate*seconds;f++) {
            double phase=(f/(double)rate)%(60/bpm);
            double env=Math.exp(-phase*40);
            float v=(float)(.45*env*Math.sin(2*Math.PI*91*f/rate));
            pcm[2*f]=v; pcm[2*f+1]=v*.8f;
        }
        Path path=directory.resolve(name);
        new WavFile(path.toString(),rate,2,pcm,24,false).save(path.toString());
        return path;
    }
    static void editAndRetarget() {
        try {
            AudioFile file=(AudioFile)field("loadedFile");
            TrackAnalysis before=(TrackAnalysis)field("trackAnalysis");
            // Two source generations on the FX thread. No callback from the first
            // may publish shared state or grant readiness for the second.
            file.setSamples(Arrays.copyOf(file.getSamples(),file.getSamples().length/2));
            invoke("recomputeTrackAnalysis",new Class[]{Runnable.class},(Runnable)()->{});
            float[] latest=Arrays.copyOf(file.getSamples(),file.getSamples().length-480);
            file.setSamples(latest);
            // Mirror afterAudioEdit(): the player must own the new source identity.
            ((com.quickmaster.playback.AudioPlayer)field("player")).prepare(file);
            invoke("recomputeTrackAnalysis",new Class[]{Runnable.class},(Runnable)()->{
                try {invoke("syncLiveAnalysis",new Class[0]);}catch(Exception ex){done.completeExceptionally(ex);}
            });
            before.setManualBpm(143);
            invoke("onPlayPause",new Class[0]);
            if(!(boolean)field("playWhenReady")) throw new AssertionError("Play must wait for source analysis");
            invoke("onStop",new Class[0]);
            if((boolean)field("playWhenReady")) throw new AssertionError("Stop must cancel queued play");
            waitReady(()->{
                try {
                    TrackAnalysis current=(TrackAnalysis)field("trackAnalysis");
                    if(current==before || !current.isManualBpm() || current.getBpm()!=143) throw new AssertionError("Source result/manual tempo publication failed");
                    if(((AudioFile)field("loadedFile")).getSamples()!=latest) throw new AssertionError("Older source won");
                    if(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("QuickMaster-Playback")))
                        throw new AssertionError("Cancelled play unexpectedly opened the playback thread");
                    System.out.println("SOURCE_EDIT_PASS latestArray=true isolatedTrackAnalysis=true latestManualBpm=143 queuedPlayCancelled=true");
                    if (missingFile != null) failedReplacement(); else closeRace();
                }catch(Throwable ex){done.completeExceptionally(ex);}
            });
        }catch(Throwable ex){done.completeExceptionally(ex);}
    }
    static void closeRace() {
        try {
            // Begin more work, then close. No later generation may become ready.
            invoke("recomputeTrackAnalysis",new Class[]{Runnable.class},(Runnable)()->{});
            controller.shutdown();
            long ready=(long)field("levelerReadyGeneration");
            PauseTransition finish=new PauseTransition(Duration.seconds(2));
            finish.setOnFinished(e->{try {
                if(!field("levelerReadyGeneration").equals(ready)) throw new AssertionError("Publication after shutdown");
                System.out.println("SOURCE_CLOSE_PASS noPublicationAfterClose=true"); done.complete(null);
            } catch(Throwable ex){done.completeExceptionally(ex);}}); finish.play();
        }catch(Throwable ex){done.completeExceptionally(ex);}
    }
    static void failedReplacement() {
        try {
            AudioFile retained=(AudioFile)field("loadedFile");
            TrackAnalysis before=(TrackAnalysis)field("trackAnalysis");
            retained.setSamples(Arrays.copyOf(retained.getSamples(),retained.getSamples().length/2));
            float[] latest=retained.getSamples();
            ((com.quickmaster.playback.AudioPlayer)field("player")).prepare(retained);
            invoke("recomputeTrackAnalysis",new Class[]{Runnable.class},(Runnable)()->{});
            invoke("loadAudioFile",new Class[]{java.io.File.class},missingFile.toFile());
            Timeline dismiss=new Timeline();
            dismiss.getKeyFrames().add(new KeyFrame(Duration.millis(25),event->{
                for(var window:new ArrayList<>(javafx.stage.Window.getWindows())) {
                    if(window.getScene()!=null && window.getScene().getRoot() instanceof DialogPane dialog) {
                        ((Button)dialog.lookupButton(ButtonType.OK)).fire();
                        dismiss.stop();
                        deadline=System.nanoTime()+10_000_000_000L;
                        later(()->waitReady(()->{
                            try {
                                TrackAnalysis current=(TrackAnalysis)field("trackAnalysis");
                                if(field("loadedFile")!=retained || retained.getSamples()!=latest || current==before)
                                    throw new AssertionError("Failed replacement lost current source analysis");
                                if(!current.isManualBpm() || current.getBpm()!=143)throw new AssertionError("Failure lost manual tempo");
                                System.out.println("SOURCE_FAILURE_RECOVERY_PASS retainedFile=true latestEditedPcm=true freshAnalysis=true");
                                closeRace();
                            }catch(Throwable ex){done.completeExceptionally(ex);}
                        }));
                    }
                }
            }));
            dismiss.setCycleCount(Animation.INDEFINITE);dismiss.play();
        }catch(Throwable ex){done.completeExceptionally(ex);}
    }
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory(Path.of(args[0]).toAbsolutePath(),"source-race-");
        if(Arrays.asList(args).contains("--failure"))missingFile=directory.resolve("deliberately-missing.wav");
        Path first=fixture(directory,"first.wav",48000,40,90), latest=fixture(directory,"latest.wav",44100,12,120);
        deadline=System.nanoTime()+120_000_000_000L;
        Platform.startup(()->{
            try {
                Platform.setImplicitExit(false);
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                Region root=loader.load(); controller=loader.getController();
                new Scene(root,1360,830); root.applyCss(); root.resize(1360,830); root.layout();
                for(Object module:(List<?>)field("chainModules")) ((CheckBox)field(module,"enableBox")).setSelected(false);
                for(var processor:((ProcessingPipeline)field("pipeline")).getProcessors()) processor.setEnabled(false);
                invoke("loadAudioFile",new Class[]{java.io.File.class},first.toFile());
                invoke("loadAudioFile",new Class[]{java.io.File.class},latest.toFile());
                waitReady(()->{
                    try {
                        AudioFile file=(AudioFile)field("loadedFile");
                        if(!file.getFilePath().equals(latest.toString()) || file.getSampleRate()!=44100 || file.getSamples().length!=12*44100*2)
                            throw new AssertionError("Wrong file/format adopted: "+file.getFilePath()+", "+file.getSampleRate()+", "+file.getSamples().length);
                        System.out.println("SOURCE_LOAD_PASS latestFile=true sampleRate=44100 duration=12s");
                        editAndRetarget();
                    }catch(Throwable ex){done.completeExceptionally(ex);}
                });
            }catch(Throwable ex){done.completeExceptionally(ex);}
        });
        int exit=0;
        try{done.get(140,TimeUnit.SECONDS);}catch(Throwable ex){ex.printStackTrace();exit=1;}
        finally{Platform.exit();} System.exit(exit);
    }
}
