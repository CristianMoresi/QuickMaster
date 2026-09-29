import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.*;
import com.quickmaster.processing.analysis.SpectrumAnalysis;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import java.nio.file.*;
import java.util.*;

/** Installed FXML + actual file import + display-only tilt. No audio device. */
public final class SpectrumTiltUiAudit extends StereoUiAudit {
    public static void main(String[] args) throws Exception {
        Path audio = Path.of(args[0]), out = Path.of(args[1]); Files.createDirectories(out);
        String identity = LevelerExclusionStore.identity(audio);
        System.out.println("APPLICATION_CLASSES " + MainController.class.getProtectionDomain().getCodeSource().getLocation());
        Platform.startup(() -> {});
        try {
            fx(() -> {
                var loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                root = loader.load(); c = loader.getController();
                var scene = new Scene(root, 1360, 900);
                scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                var preset = (ChainPreset) call("capturePreset");
                preset.autoEqOn = preset.eqOn = preset.dynamicsOn = preset.clipOn = preset.limitOn = false;
                preset.normalizerOn = preset.osOn = preset.stereoOn = false;
                preset.fadeInSec = preset.fadeOutSec = 0;
                call("applyPreset", new Class<?>[]{ChainPreset.class, boolean.class}, preset, false);
                for (Object module : (List<?>) get("chainModules")) if (get(module, "name").equals("EQ"))
                    call("selectModule", new Class<?>[]{module.getClass()}, module);
                call("loadAudioFile", new Class<?>[]{java.io.File.class}, audio.toFile());
                return null;
            });
            ready();
            fx(() -> {
                AudioFile loaded = (AudioFile) get("loadedFile");
                check(loaded.getFilePath().equals(audio.toString()), "Wrong file imported");
                float[] original = loaded.getSamples().clone(), render = pcm().clone();
                String preset = new com.google.gson.Gson().toJson(call("capturePreset"));
                long generation = (long) get("outputAnalysisGeneration");
                SpectrumAnalysis spectrum = (SpectrumAnalysis) get("spectrumAnalysis");
                double measured = spectrum.levelDbAt(1000);
                Object display = get("spectrumDisplay");
                var slope = display.getClass().getDeclaredField("TILT_DB_PER_OCTAVE"); slope.setAccessible(true);
                check(slope.getDouble(null) == 4.5, "Wrong default analyzer slope");
                call("drawEqCurve"); shot(out.resolve("eq-analyzer-45.png"), 1360, 900);
                call("drawEqCurve"); shot(out.resolve("eq-analyzer-45-compact.png"), 1100, 760);
                check(Arrays.equals(original, loaded.getSamples()), "Drawing altered imported PCM");
                check(Arrays.equals(render, pcm()), "Drawing altered master PCM");
                check(spectrum.levelDbAt(1000) == measured, "Drawing altered spectrum measurements");
                check(preset.equals(new com.google.gson.Gson().toJson(call("capturePreset"))), "Drawing altered DSP preset");
                check(generation == (long) get("outputAnalysisGeneration") && (int) get("analyzeJobs") == 0,
                        "Drawing scheduled processing");
                return null;
            });
            check(identity.equals(LevelerExclusionStore.identity(audio)), "Source file changed");
            System.out.println("SPECTRUM_UI_PASS slope=4.5 pivotHz=1000 sourceUnchanged=true renderUnchanged=true noAnalysis=true noAudioDevice=true");
        } finally { fx(() -> { if (c != null) c.shutdown(); return null; }); Platform.exit(); }
    }
}
