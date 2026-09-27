import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.audio.AudioFile;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.animation.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual load and knob listeners; timings end at current-plan adoption, not hardware playback. */
public final class InteractionLatencyProbe {
    private static boolean fullChain;
    private static int repeatedEdits;
    private static boolean heapCheckpoints;
    private static MainController controller;
    private static Timeline pulse;
    private static long lastPulse, maxPulseDelay, lastEdit, start;
    private static long audioReadyAt;
    private static int maxJobs, maxWorkers;
    private static final CompletableFuture<Void> done = new CompletableFuture<>();
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void after(int millis, Runnable runnable) {
        PauseTransition pause = new PauseTransition(Duration.millis(millis));
        pause.setOnFinished(e -> { try { runnable.run(); } catch (Throwable ex) { done.completeExceptionally(ex); } });
        pause.play();
    }
    private static void observe() {
        try {
            long now = System.nanoTime();
            if (lastPulse != 0) maxPulseDelay = Math.max(maxPulseDelay, now - lastPulse - 50_000_000L);
            lastPulse = now;
            if (audioReadyAt == 0 && field(controller,"loadedFile") != null
                    && field(controller,"outputAnalysisGeneration").equals(field(controller,"levelerReadyGeneration")))
                audioReadyAt = now;
            maxJobs = Math.max(maxJobs, (int)field(controller, "analyzeJobs"));
            int workers = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive()
                    && (t.getName().equals("QuickMaster-Task") || t.getName().equals("QuickMaster-Analysis"))) workers++;
            maxWorkers = Math.max(maxWorkers, workers);
        } catch (Throwable ex) { done.completeExceptionally(ex); }
    }
    private static void begin() {
        start = lastEdit = System.nanoTime(); maxJobs = maxWorkers = 0; maxPulseDelay = 0; lastPulse = 0;
        audioReadyAt = 0;
    }
    private static void awaitReady(String scenario, Runnable next) {
        try {
            if ((System.nanoTime() - start) > 180_000_000_000L) throw new AssertionError("Timed out: " + scenario);
            long generation = (long)field(controller, "outputAnalysisGeneration");
            if ((long)field(controller, "levelerFailedGeneration") == generation
                    || (long)field(controller, "levelerCancelledGeneration") == generation) {
                System.out.printf(Locale.ROOT,
                        "INTERACTION_FAILED %s elapsedSec=%.6f maxJobs=%d maxWorkers=%d maxFxPulseDelayMs=%.3f generation=%d%n",
                        scenario, (System.nanoTime()-start)/1e9, maxJobs, maxWorkers, maxPulseDelay/1e6, generation);
                throw new AssertionError("Current analysis failed or was cancelled: " + scenario);
            }
            boolean current = field(controller, "loadedFile") != null
                    && field(controller, "outputAnalysisGeneration").equals(field(controller, "levelerReadyGeneration"))
                    && (int)field(controller, "analyzeJobs") == 0;
            if (!current) { after(50, () -> awaitReady(scenario, next)); return; }
            long now = System.nanoTime();
            System.out.printf(Locale.ROOT, "INTERACTION %s totalSec=%.6f lastEditToSettledSec=%.6f maxJobs=%d maxWorkers=%d maxFxPulseDelayMs=%.3f generation=%s%n",
                    scenario, (now-start)/1e9, (now-lastEdit)/1e9, maxJobs, maxWorkers, maxPulseDelay/1e6,
                    field(controller, "levelerReadyGeneration"));
            System.out.printf(Locale.ROOT, "AUDIO_READY %s lastEditSec=%.6f beforeMetersSec=%.6f%n",
                    scenario, ((audioReadyAt == 0 ? now : audioReadyAt)-lastEdit)/1e9,
                    audioReadyAt == 0 ? 0 : (now-audioReadyAt)/1e9);
            if (heapCheckpoints) {
                // A separate memory-sweep mode, not an isolated latency benchmark.
                // No production GC request is added to the application.
                System.gc();
                System.out.printf(Locale.ROOT, "HEAP_CHECKPOINT %s MiB=%.3f%n", scenario,
                        java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576.0);
            }
            after(300, next);
        } catch (Throwable ex) { done.completeExceptionally(ex); }
    }
    private static void knob(double value) {
        try {
            Object knob = field(controller, "levelingKnob");
            lastEdit = System.nanoTime();
            audioReadyAt = 0;
            knob.getClass().getMethod("setValue", double.class).invoke(knob, value);
        } catch (Throwable ex) { done.completeExceptionally(ex); }
    }
    private static void burst(int i) {
        knob((i % 2 == 0) ? .30 : .80);
        if (i < 5) after(350, () -> burst(i + 1));
        else after(300, () -> awaitReady("six-edits-350ms-apart", () -> done.complete(null)));
    }
    private static void repeatedEdit(int index) {
        if (index >= repeatedEdits) { begin(); burst(0); return; }
        begin(); knob((index % 2 == 0) ? .40 : .70);
        after(300, () -> awaitReady("repeat-edit-" + (index + 1), () -> repeatedEdit(index + 1)));
    }
    private record Applied(float[] source, float[] rendered, int rate, int channels, double speed) {}
    private record FullApplied(float[] source, float[] rendered, ProcessingPipeline cold, int rate, int channels) {}
    private static final class AlreadyAnalyzed implements com.quickmaster.processing.AudioProcessor, com.quickmaster.processing.OfflineBlockSizing {
        final com.quickmaster.processing.AudioProcessor stage;
        AlreadyAnalyzed(com.quickmaster.processing.AudioProcessor stage) { this.stage=stage; }
        public void prepare(int rate,long samples){stage.prepare(rate,samples);}
        public float[] process(float[] block,int channels){return stage.process(block,channels);}
        public boolean isEnabled(){return stage.isEnabled();}
        public void setEnabled(boolean enabled){throw new UnsupportedOperationException();}
        public int getLatencyFrames(){return stage.getLatencyFrames();}
        public int preferredOfflineBlockFrames(){return stage instanceof com.quickmaster.processing.OfflineBlockSizing sizing
                ? sizing.preferredOfflineBlockFrames():1024;}
    }
    private static void verifyFullChainAudio() throws Exception {
        CompletableFuture<FullApplied> captured=new CompletableFuture<>();
        Platform.runLater(()->{
            try {
                pulse.stop();
                if(((LevelerProcessor)field(controller,"leveler")).getLeveling()!=.8) throw new AssertionError("Wrong latest amount");
                AudioFile song=(AudioFile)field(controller,"loadedFile");
                ProcessingPipeline applied=new ProcessingPipeline();
                for(var stage:((ProcessingPipeline)field(controller,"pipeline")).getProcessors()) applied.addProcessor(new AlreadyAnalyzed(stage));
                applied.prepare(song.getSampleRate(),song.getSamples().length);
                float[] actual=applied.analyzeAndRender(song.getSamples(),song.getChannels(),0,null,null,null);
                Method capture=MainController.class.getDeclaredMethod("capturePreset"); capture.setAccessible(true);
                Object preset=capture.invoke(controller);
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml")); loader.load();
                MainController fresh=loader.getController();
                Field analysis=MainController.class.getDeclaredField("trackAnalysis"); analysis.setAccessible(true);
                analysis.set(fresh,analysis.get(controller));
                Method apply=MainController.class.getDeclaredMethod("applyPreset",preset.getClass(),boolean.class); apply.setAccessible(true);
                apply.invoke(fresh,preset,false);
                Method snapshot=MainController.class.getDeclaredMethod("buildSnapshot"); snapshot.setAccessible(true);
                ProcessingPipeline cold=(ProcessingPipeline)field(snapshot.invoke(fresh),"pipeline");
                var actualStages=((ProcessingPipeline)field(controller,"pipeline")).getProcessors();
                for(int i=0;i<actualStages.size();i++) {
                    if(actualStages.get(i).isEnabled()!=cold.getProcessors().get(i).isEnabled())
                        throw new AssertionError("Harness preset differs from actual stage toggle: "+actualStages.get(i).getClass().getSimpleName());
                }
                String requested=new com.google.gson.Gson().toJson(preset), restored=new com.google.gson.Gson().toJson(capture.invoke(fresh));
                if(!requested.equals(restored)) throw new AssertionError("Harness preset roundtrip differs: "+requested+" vs "+restored);
                fresh.shutdown();
                captured.complete(new FullApplied(song.getSamples(),actual,cold,song.getSampleRate(),song.getChannels()));
            }catch(Throwable ex){captured.completeExceptionally(ex);}
        });
        FullApplied actual=captured.get(45,TimeUnit.SECONDS);
        actual.cold().prepare(actual.rate(),actual.source().length);
        float[] expected=actual.cold().analyzeAndRender(actual.source(),actual.channels(),0,null,null,null);
        double maxError=0; long changed=0, rawBitDifferences=0;
        for(int i=0;i<expected.length;i++) {
            maxError=Math.max(maxError,Math.abs(expected[i]-actual.rendered()[i]));
            if(!Float.isFinite(actual.rendered()[i]) || maxError>2e-6) throw new AssertionError("Live full chain differs from cold current preset at "+i+", error="+maxError);
            if(Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(actual.rendered()[i])) rawBitDifferences++;
            if(Float.floatToRawIntBits(actual.rendered()[i])!=Float.floatToRawIntBits(actual.source()[i])) changed++;
        }
        if(changed==0) throw new AssertionError("Full chain unexpectedly bypassed");
        if(rawBitDifferences!=0) throw new AssertionError("Full chain raw-bit differences, including signed zeros: "+rawBitDifferences);
        System.out.printf(Locale.ROOT,"LATEST_FULL_AUDIO_PASS coldFreshController=true bitExact=true rawBitDifferences=%d maxAbsError=%.9g changedSamples=%d%n",rawBitDifferences,maxError,changed);
    }
    private static void verifyLatestAudio() throws Exception {
        if(fullChain) { verifyFullChainAudio(); return; }
        CompletableFuture<Applied> captured = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                pulse.stop();
                LevelerProcessor live = (LevelerProcessor)field(controller, "leveler");
                if (live.getLeveling() != .80 || !live.isEnabled()) throw new AssertionError("Not the latest requested control");
                AudioFile song = (AudioFile)field(controller, "loadedFile");
                ProcessingPipeline pipeline = (ProcessingPipeline)field(controller, "pipeline");
                for (var processor : pipeline.getProcessors())
                    if (processor.isEnabled() && processor != live) throw new AssertionError("Unexpected active processor");
                pipeline.prepare(song.getSampleRate(), song.getSamples().length);
                float[] output = pipeline.executeBlocks(song.getSamples().clone(), song.getChannels(), 4096);
                captured.complete(new Applied(song.getSamples(), output, song.getSampleRate(), song.getChannels(), live.getSpeed()));
            } catch (Throwable ex) { captured.completeExceptionally(ex); }
        });
        Applied actual = captured.get(30, TimeUnit.SECONDS);
        LevelerProcessor reference = new LevelerProcessor();
        reference.setEnabled(true); reference.setLeveling(.80); reference.setSpeed(actual.speed());
        reference.prepare(actual.rate(), actual.source().length);
        reference.analyze(actual.source(), actual.channels());
        float[] expected = reference.process(actual.source().clone(), actual.channels());
        long changed = 0;
        for (int i = 0; i < expected.length; i++) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual.rendered()[i]))
                throw new AssertionError("Latest async audio differs from isolated current-control reference at " + i);
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual.source()[i])) changed++;
        }
        if (changed == 0) throw new AssertionError("Latest adopted audio unexpectedly bypassed");
        System.out.printf("LATEST_AUDIO_PASS amount=0.8 bitExactToColdReference=true changedSamples=%d%n", changed);
    }
    public static void main(String[] args) throws Exception {
        fullChain=Arrays.asList(args).contains("--full-chain");
        heapCheckpoints=Arrays.asList(args).contains("--heap-checkpoints");
        for (String arg : args) if (arg.startsWith("--repeats="))
            repeatedEdits = Integer.parseInt(arg.substring("--repeats=".length()));
        if (repeatedEdits < 0 || repeatedEdits > 100) throw new IllegalArgumentException("repeats must be 0..100");
        System.out.println("CONFIGURATION fullChain="+fullChain+" heapCheckpoints="+heapCheckpoints);
        System.out.println("JAR " + MainController.class.getProtectionDomain().getCodeSource().getLocation());
        Platform.startup(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                Region root = loader.load(); controller = loader.getController();
                Scene scene = new Scene(root, 1360, 830);
                scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.setPrefSize(1360, 830); root.applyCss(); root.resize(1360,830); root.layout();
                for (Object module : (List<?>)field(controller,"chainModules"))
                    ((CheckBox)field(module,"enableBox")).setSelected(fullChain);
                for (var p : ((ProcessingPipeline)field(controller,"pipeline")).getProcessors()) p.setEnabled(fullChain);
                for (Object card : (List<?>)field(controller,"dynCards"))
                    ((CheckBox)field(card,"on")).setSelected(fullChain);
                for (Object card : (List<?>)field(controller,"clipCardList"))
                    ((CheckBox)field(card,"on")).setSelected(fullChain);
                ((CheckBox)field(controller,"peakEnabled")).setSelected(fullChain);
                ((CheckBox)field(controller,"dynMasterEnabled")).setSelected(true);
                for (Object card : (List<?>)field(controller,"dynCards"))
                    if (field(card,"name").equals("Leveler")) ((CheckBox)field(card,"on")).setSelected(true);
                pulse = new Timeline(new KeyFrame(Duration.millis(50), e -> observe()));
                pulse.setCycleCount(Animation.INDEFINITE); pulse.play();
                Method load = MainController.class.getDeclaredMethod("loadAudioFile", java.io.File.class); load.setAccessible(true);
                begin(); load.invoke(controller, Path.of(args[0]).toFile());
                awaitReady(fullChain ? "load-full-chain" : "load-leveler-only", () -> {
                    begin(); knob(.65);
                    after(300, () -> awaitReady("single-leveler-edit", () -> repeatedEdit(0)));
                });
            } catch (Throwable ex) { done.completeExceptionally(ex); }
        });
        int exit = 0;
        try {
            done.get(360 + repeatedEdits * 30L, TimeUnit.SECONDS);
            // Outside all latency intervals and before the extra cold oracle:
            // distinguish retained application heap from young-GC high-water marks.
            System.gc();
            System.out.printf(Locale.ROOT, "APP_RETAINED_HEAP_AFTER_GC MiB=%.3f%n",
                    java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576.0);
            verifyLatestAudio();
        }
        catch (Throwable ex) { ex.printStackTrace(); exit = 1; }
        finally { Platform.exit(); }
        System.exit(exit);
    }
}
