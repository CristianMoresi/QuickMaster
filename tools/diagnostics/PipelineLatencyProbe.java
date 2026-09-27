import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.FadeProcessor;
import com.quickmaster.processing.analysis.OutputAnalysis;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.dynamics.AnalysisDynamicsProcessor;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Read-only packaged-chain stage timings. Does not export audio or use an audio device. */
public final class PipelineLatencyProbe {
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static Object call(Object owner, String name) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(owner);
    }
    private static final class Timed implements AudioProcessor, com.quickmaster.processing.OfflineBlockSizing {
        final AudioProcessor delegate;
        long analysisNanos, processNanos, prepareNanos;
        Timed(AudioProcessor p) { delegate = p; }
        public void prepare(int sr, long count) {
            long start = System.nanoTime(); delegate.prepare(sr, count); prepareNanos += System.nanoTime() - start;
        }
        public boolean usesAnalysis() { return delegate.usesAnalysis(); }
        public boolean analyzeWhenBypassed() {
            try { return (boolean)AudioProcessor.class.getMethod("analyzeWhenBypassed").invoke(delegate); }
            catch (NoSuchMethodException oldContract) { return true; }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
        public void analyze(float[] pcm, int ch) {
            long start = System.nanoTime(); delegate.analyze(pcm, ch); analysisNanos += System.nanoTime() - start;
        }
        public float[] process(float[] pcm, int ch) {
            long start = System.nanoTime(); float[] out = delegate.process(pcm, ch); processNanos += System.nanoTime() - start; return out;
        }
        public int getLatencyFrames() { return delegate.getLatencyFrames(); }
        public int preferredOfflineBlockFrames() {
            return delegate instanceof com.quickmaster.processing.OfflineBlockSizing sizing
                    ? sizing.preferredOfflineBlockFrames() : ProcessingPipeline.OFFLINE_BLOCK_FRAMES;
        }
        public void setPlaybackPosition(long frame) { delegate.setPlaybackPosition(frame); }
        public boolean isEnabled() { return delegate.isEnabled(); }
        public void setEnabled(boolean enabled) { delegate.setEnabled(enabled); }
    }
    private static String sha(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] b = new byte[65536]; int n;
            while ((n = stream.read(b)) > 0) md.update(b, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static String pcmSha(float[] pcm) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        var bytes = java.nio.ByteBuffer.allocate(65536).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float sample : pcm) {
            if (!Float.isFinite(sample)) throw new AssertionError("Non-finite output");
            if (!bytes.hasRemaining()) { md.update(bytes.array()); bytes.clear(); }
            bytes.putInt(Float.floatToRawIntBits(sample));
        }
        md.update(bytes.array(), 0, bytes.position());
        return HexFormat.of().formatHex(md.digest());
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path input = Path.of(args[0]); String sourceHash = sha(input);
        Path jar = Path.of(ProcessingPipeline.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        System.out.println("JAR " + jar + " sha256=" + sha(jar));
        WavFile song = new WavFile(input.toString()); song.load();
        System.out.printf("SOURCE %s frames=%d sampleRate=%d channels=%d sha256=%s%n", input.getFileName(),
                song.getSamples().length / song.getChannels(), song.getSampleRate(), song.getChannels(), sourceHash);
        TrackAnalysis track = new TrackAnalysis();
        long trackStart = System.nanoTime(); track.analyze(song.getSamples(), song.getChannels(), song.getSampleRate());
        System.out.printf("SOURCE_ANALYSIS seconds=%.6f%n", (System.nanoTime() - trackStart) / 1e9);
        CompletableFuture<MainController> init = new CompletableFuture<>();
        Platform.startup(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml")); loader.load();
                MainController controller = loader.getController();
                Field f = MainController.class.getDeclaredField("trackAnalysis"); f.setAccessible(true); f.set(controller, track);
                if (Arrays.asList(args).contains("--check-fades")) {
                    FadeProcessor liveFade = (FadeProcessor)field(controller, "fade");
                    var originalType = liveFade.getFadeType();
                    try {
                        for (var type : originalType.getDeclaringClass().getEnumConstants()) {
                            liveFade.setFadeType(type);
                            Object snapshot = call(controller, "buildSnapshot");
                            var chain = (ProcessingPipeline)field(snapshot, "pipeline");
                            FadeProcessor copy = (FadeProcessor)chain.getProcessors().stream()
                                    .filter(p -> p instanceof FadeProcessor).findFirst().orElseThrow();
                            if (copy.getFadeType() != type) throw new AssertionError("Snapshot lost fade type " + type);
                        }
                        System.out.println("FADE_SNAPSHOT_PASS allCurvesPreserved=true");
                    } finally { liveFade.setFadeType(originalType); }
                }
                init.complete(controller);
            } catch (Throwable ex) { init.completeExceptionally(ex); }
        });
        int exit = 0;
        try {
            MainController controller = init.get(30, TimeUnit.SECONDS);
            for (String mode : List.of("bypassed", "leveler", "leveler-warm", "all-active")) {
                CompletableFuture<Object> snapshot = new CompletableFuture<>();
                Platform.runLater(() -> {
                    try {
                        ProcessingPipeline live = (ProcessingPipeline)field(controller, "pipeline");
                        AudioProcessor leveler = (AudioProcessor)field(controller, "leveler");
                        for (AudioProcessor p : live.getProcessors())
                            p.setEnabled(mode.equals("all-active") || (!mode.equals("bypassed") && p == leveler));
                        snapshot.complete(call(controller, "buildSnapshot"));
                    } catch (Throwable ex) { snapshot.completeExceptionally(ex); }
                });
                Object s = snapshot.get(30, TimeUnit.SECONDS);
                ProcessingPipeline original = (ProcessingPipeline)field(s, "pipeline");
                ProcessingPipeline measured = new ProcessingPipeline();
                List<Timed> stages = new ArrayList<>();
                for (AudioProcessor p : original.getProcessors()) { Timed t = new Timed(p); stages.add(t); measured.addProcessor(t); }
                long start = System.nanoTime();
                measured.prepare(song.getSampleRate(), song.getSamples().length);
                float[] render = measured.analyzeAndRender(song.getSamples(), song.getChannels(), 0, null, null, null);
                long rendered = System.nanoTime();
                OutputAnalysis.Result result = OutputAnalysis.measure(render, song.getChannels(), song.getSampleRate());
                long ended = System.nanoTime();
                System.out.printf("MODE %s chainSec=%.6f metersSec=%.6f totalSec=%.6f lufs=%.6f%n", mode,
                        (rendered - start)/1e9, (ended - rendered)/1e9, (ended - start)/1e9, result.integratedLufs());
                System.out.printf("PCM %s samples=%d sha256=%s%n", mode, render.length, pcmSha(render));
                for (Timed t : stages) System.out.printf("STAGE %s enabled=%s analysisSec=%.6f renderSec=%.6f prepareSec=%.6f%n",
                        t.delegate.getClass().getSimpleName(), t.isEnabled(), t.analysisNanos/1e9, t.processNanos/1e9, t.prepareNanos/1e9);
                AnalysisDynamicsProcessor live = (AnalysisDynamicsProcessor)field(controller, "leveler");
                for (Timed t : stages) if (t.delegate.getClass() == live.getClass())
                    live.adoptEnvelope((AnalysisDynamicsProcessor)t.delegate);
                System.out.printf("LEVELER_DIAGNOSTIC %s %s%n",mode,call(live,"getAnalysisDiagnostic"));
            }
            if (!sourceHash.equals(sha(input))) throw new AssertionError("Source changed");
            System.out.println("SOURCE_UNCHANGED=true");
        } catch (Throwable ex) { ex.printStackTrace(); exit = 1; }
        finally { Platform.exit(); }
        System.exit(exit);
    }
}
