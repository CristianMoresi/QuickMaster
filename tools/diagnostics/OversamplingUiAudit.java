import com.quickmaster.audio.*;
import com.quickmaster.processing.*;
import com.quickmaster.ui.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.animation.PauseTransition;
import javafx.scene.control.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual asynchronous file/control/render/meter route at 32 kHz with oversampling. */
public class OversamplingUiAudit {
    static final CompletableFuture<Void> done = new CompletableFuture<>();
    static MainController controller;
    static int factor = 8;
    static long deadline;
    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static Object field(String name) throws Exception { return field(controller, name); }
    static void verifyWhenReady() {
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("UI analysis timed out");
            if (field("loadedFile") == null || !field("outputAnalysisGeneration").equals(field("levelerReadyGeneration"))
                    || (int)field("analyzeJobs") != 0) {
                PauseTransition pause = new PauseTransition(javafx.util.Duration.millis(25));
                pause.setOnFinished(e -> verifyWhenReady()); pause.play(); return;
            }
            AudioFile audio = (AudioFile)field("loadedFile");
            Method build = MainController.class.getDeclaredMethod("buildSnapshot"); build.setAccessible(true);
            Object snapshot = build.invoke(controller);
            ProcessingPipeline expectedPipeline = (ProcessingPipeline)field(snapshot, "pipeline");
            WavFile expected = new WavFile("generated", audio.getSampleRate(), audio.getChannels(), audio.getSamples(), 32, true);
            expectedPipeline.processOversampled(expected, factor, null);
            double tp = 20 * Math.log10(com.dspark.analysis.TruePeak.measureMax(expected.getSamples(), audio.getChannels()));
            double expectedGain = ((PeakNormalizer)field(snapshot, "normalizer")).getGain();
            double appliedGain = ((PeakNormalizer)field("normalizer")).getGain();
            String text = ((Label)field("meterPeak")).getText();
            if (Math.abs(tp + 1) > .001 || !text.equals("-1.0 dBTP") || Math.abs(expectedGain - appliedGain) > 1e-7)
                throw new AssertionError("Meter/render/adoption differ: " + text + " actualTP=" + tp + " expectedGain=" + expectedGain + " adopted=" + appliedGain);
            System.out.printf(Locale.ROOT, "OS_UI_PASS factor=%d sourceRate=%d peak=%.9f meter=%s gain=%.9f generation=%s%n",
                    factor, audio.getSampleRate(), tp, text, appliedGain, field("outputAnalysisGeneration"));
            if (factor == 8) {
                long before = (long)field("outputAnalysisGeneration"); factor = 2;
                ((ComboBox<String>)field("osCombo")).setValue("2x");
                if ((long)field("outputAnalysisGeneration") <= before) throw new AssertionError("Factor control did not invalidate analysis");
                verifyWhenReady();
            } else { controller.shutdown(); done.complete(null); }
        } catch (Throwable e) { done.completeExceptionally(e); }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of(args[0]), "os-ui-");
        Path source = directory.resolve("synthetic-32k.wav");
        float[] pcm = new float[32001 * 2];
        for (int f = 0; f < 32001; f++) { pcm[f * 2] = (float)(.1 * Math.sin(f * .4) + (f % 173 == 0 ? .7 : 0)); pcm[f * 2 + 1] = -pcm[f * 2]; }
        new WavFile("generated", 32000, 2, pcm, 32, true).save(source.toString());
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        Platform.startup(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml")); loader.load(); controller = loader.getController();
                for (Object module : (List<?>)field("chainModules")) ((CheckBox)field(module, "enableBox")).setSelected(false);
                for (var processor : ((ProcessingPipeline)field("pipeline")).getProcessors()) processor.setEnabled(false);
                ((CheckBox)field("clipEnabled")).setSelected(true);
                for (Object card : (List<?>)field("clipCardList")) ((CheckBox)field(card, "on")).setSelected(false);
                ((CheckBox)field(((List<?>)field("clipCardList")).get(0), "on")).setSelected(true);
                ((Knob)field("satKnob")).setValue(6);
                ((CheckBox)field("peakEnabled")).setSelected(true);
                ((Slider)field("peakTarget")).setValue(-1);
                ((ComboBox<String>)field("osCombo")).setValue("8x");
                ((ToggleButton)field("osToggle")).setSelected(true);
                Method load = MainController.class.getDeclaredMethod("loadAudioFile", java.io.File.class); load.setAccessible(true); load.invoke(controller, source.toFile());
                verifyWhenReady();
            } catch (Throwable e) { done.completeExceptionally(e); }
        });
        int exit = 0;
        try { done.get(95, TimeUnit.SECONDS); }
        catch (Throwable e) { e.printStackTrace(); exit = 1; }
        finally { Platform.exit(); Files.deleteIfExists(source); Files.deleteIfExists(directory); }
        System.exit(exit);
    }
}
