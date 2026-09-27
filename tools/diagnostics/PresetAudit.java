import com.google.gson.Gson;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Invalid presets must be rejected without changing any live control. No audio device. */
public class PresetAudit {
    public static void main(String[] args) throws Exception {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        Platform.startup(() -> {
            int failures = 0;
            Gson gson = new Gson();
            for (int test = 0; test < 7; test++) {
                MainController controller = null;
                try {
                    FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                    loader.load(); controller = loader.getController();
                    Method capture = MainController.class.getDeclaredMethod("capturePreset");
                    capture.setAccessible(true);
                    ChainPreset original = (ChainPreset) capture.invoke(controller);
                    String before = gson.toJson(original);
                    ChainPreset invalid = gson.fromJson(before, ChainPreset.class);
                    invalid.autoEqOn = !original.autoEqOn;
                    switch (test) {
                        case 0 -> invalid.mbPushDb = null;
                        case 1 -> invalid.bands.add(null);
                        case 2 -> invalid.normalizerTargetDbtp = Double.NaN;
                        case 3 -> invalid.version = 999;
                        case 4 -> invalid.dynamicsOrder = java.util.List.of("peak", "beat", "leveler", "bogus");
                        case 5, 6 -> {
                            for (int i = 0; i < (test == 5 ? 1 : 16); i++) {
                                var band = new com.dspark.effects.MasterEqualizer.Band();
                                band.gainDb = 3; band.dynamic = test == 6;
                                invalid.bands.add(gson.fromJson(gson.toJson(band), ChainPreset.BandPreset.class));
                            }
                        }
                    }
                    Method apply = MainController.class.getDeclaredMethod("applyPreset", ChainPreset.class);
                    apply.setAccessible(true);
                    Throwable rejection = null;
                    try { apply.invoke(controller, invalid); }
                    catch (InvocationTargetException e) { rejection = e.getCause(); }
                    String after;
                    try { after = gson.toJson(capture.invoke(controller)); }
                    catch (RuntimeException e) { after = "CORRUPTED: " + e; }
                    boolean unchanged = before.equals(after);
                    Field updating = MainController.class.getDeclaredField("updatingEqEditor");
                    updating.setAccessible(true);
                    boolean flagStuck = updating.getBoolean(controller);
                    System.out.println("PRESET_AUDIT case=" + test + " rejected=" + rejection
                            + " unchanged=" + unchanged + " editorFlagStuck=" + flagStuck);
                    if (test < 5) {
                        if (!unchanged || flagStuck || !(rejection instanceof IllegalArgumentException)) failures++;
                    } else if (rejection != null || flagStuck || !gson.toJson(invalid).equals(after)) {
                        System.out.println("VALID_PRESET_MISMATCH expected=" + gson.toJson(invalid) + " actual=" + after);
                        failures++;
                    }
                } catch (Throwable e) { e.printStackTrace(); failures++; }
                finally { if (controller != null) controller.shutdown(); }
            }
            done.complete(failures);
        });
        int failures = done.get(60, TimeUnit.SECONDS);
        Platform.exit();
        System.out.println("PRESET_AUDIT failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
