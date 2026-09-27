import com.quickmaster.audio.AudioFile;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Actual async file-load/controller/control/adoption/render route; no playback, export or native chooser. */
public class LevelerUiAcceptance {
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void call(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]).toAbsolutePath();
        Path image = Path.of(args[1]);
        Files.createDirectories(image.getParent());
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.startup(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                Region root = loader.load();
                MainController controller = loader.getController();
                Scene scene = new Scene(root, 1360, 830);
                scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.setPrefSize(1360, 830);
                root.applyCss(); root.resize(1360, 830); root.layout();
                var modules = (List<?>)field(controller, "chainModules");
                for (Object module : modules) ((CheckBox)field(module, "enableBox")).setSelected(false);
                var pipeline = (ProcessingPipeline)field(controller, "pipeline");
                for (var processor : pipeline.getProcessors()) processor.setEnabled(false);
                var cards = (List<?>)field(controller, "dynCards");
                for (Object card : cards) ((CheckBox)field(card, "on")).setSelected(false);
                ((CheckBox)field(controller, "dynMasterEnabled")).setSelected(true);
                for (Object card : cards) if (field(card, "name").equals("Leveler"))
                    ((CheckBox)field(card, "on")).setSelected(true);
                Object knob = field(controller, "levelingKnob");
                knob.getClass().getMethod("setValue", double.class).invoke(knob, 1.0);
                var debounce = (PauseTransition)field(controller, "dynRefreshDebounce");
                if (debounce != null) debounce.stop();
                Method select = MainController.class.getDeclaredMethod("selectModule", modules.get(0).getClass());
                select.setAccessible(true);
                select.invoke(controller, modules.get(1));
                Method load = MainController.class.getDeclaredMethod("loadAudioFile", java.io.File.class);
                load.setAccessible(true);
                Runnable[] verify = new Runnable[1];
                verify[0] = () -> {
                    try {
                        // Automatic Peak/Beat range updates can legitimately queue
                        // a second generation; observe its completion, never forge readiness.
                        if (field(controller, "loadedFile") == null || (long)field(controller, "levelerReadyGeneration") < 0
                                || !field(controller, "outputAnalysisGeneration").equals(field(controller, "levelerReadyGeneration"))) {
                            PauseTransition retry = new PauseTransition(javafx.util.Duration.millis(100));
                            retry.setOnFinished(event -> verify[0].run());
                            retry.play();
                            return;
                        }
                        LevelerProcessor leveler = (LevelerProcessor)field(controller, "leveler");
                        String diagnostic = ((Label)field(controller, "levelerDiagnosticLabel")).getText();
                        System.out.println("UI_STATE enabled=" + leveler.isEnabled() + " amount=" + leveler.getLeveling()
                                + " speed=" + leveler.getSpeed() + " analyzed=" + leveler.isAnalyzed()
                                + " processor=" + leveler.getAnalysisDiagnostic()
                                + " current=" + field(controller, "outputAnalysisGeneration")
                                + " ready=" + field(controller, "levelerReadyGeneration")
                                + " started=" + field(controller, "levelerStartedGeneration"));
                        if (!leveler.isEnabled() || !diagnostic.equals("Leveling · comparable sections"))
                            throw new AssertionError("UI did not adopt an active current plan: " + diagnostic);
                        AudioFile song = (AudioFile)field(controller, "loadedFile");
                        if (!((Label)field(controller, "fileNameLabel")).getText().equals(source.getFileName().toString()))
                            throw new AssertionError("File-load UI did not identify the source");
                        for (var processor : pipeline.getProcessors())
                            if (processor.isEnabled() && processor != leveler)
                                throw new AssertionError("Unexpected active processor: " + processor.getClass().getName());
                        pipeline.prepare(song.getSampleRate(), song.getSamples().length);
                        float[] rendered = pipeline.executeBlocks(song.getSamples().clone(), song.getChannels(), 4096);
                        long changed = 0;
                        for (int i = 0; i < rendered.length; i++)
                            if (Float.floatToRawIntBits(rendered[i]) != Float.floatToRawIntBits(song.getSamples()[i])) changed++;
                        if (changed == 0) throw new AssertionError("Live pipeline is unchanged after UI analysis");
                        root.applyCss(); root.layout();
                        WritableImage shot = root.snapshot(null, null);
                        BufferedImage png = new BufferedImage((int)shot.getWidth(), (int)shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
                            png.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
                        ImageIO.write(png, "png", image.toFile());
                        System.out.println("UI_PASS actualAsyncFileLoad=true currentDiagnostic=" + diagnostic + " livePipelineChanged=" + changed
                                + " generation=" + field(controller, "levelerReadyGeneration") + " screenshot=" + image);
                        knob.getClass().getMethod("setValue", double.class).invoke(knob, 0.0);
                        call(controller, "updateLevelerDiagnostic");
                        String zero = ((Label)field(controller, "levelerDiagnosticLabel")).getText();
                        if (!zero.equals("Leveling off (0%)")) throw new AssertionError("Zero-control diagnostic: " + zero);
                        System.out.println("UI_ZERO_PASS " + zero);
                        done.complete(null);
                    } catch (Throwable error) { done.completeExceptionally(error); }
                };
                load.invoke(controller, source.toFile());
                verify[0].run();
            } catch (Throwable error) { done.completeExceptionally(error); }
        });
        int exit = 0;
        try { done.get(180, TimeUnit.SECONDS); }
        catch (Throwable error) { error.printStackTrace(); exit = 1; }
        finally { Platform.exit(); }
        System.exit(exit);
    }
}
