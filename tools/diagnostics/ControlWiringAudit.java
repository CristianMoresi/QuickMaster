import com.quickmaster.audio.WavFile;
import com.quickmaster.ui.MainController;
import com.quickmaster.ui.Knob;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckBox;
import javafx.animation.PauseTransition;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Actual control listeners with generated source PCM; no audio line is opened. */
public class ControlWiringAudit {
    static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    static void call(Object owner, String name) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(owner);
    }
    public static void main(String[] args) throws Exception {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        Platform.startup(() -> {
            int failures = 0;
            for (String control : new String[]{"multiband", "broadband", "limitEnabled", "levelingUndo"}) {
                MainController controller = null;
                try {
                    FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                    loader.load(); controller = loader.getController();
                    Field loaded = MainController.class.getDeclaredField("loadedFile"); loaded.setAccessible(true);
                    loaded.set(controller, new WavFile("generated", 48000, 1, new float[48000], 32, true));
                    Method capture = MainController.class.getDeclaredMethod("capturePreset"); capture.setAccessible(true);
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    String original = gson.toJson(capture.invoke(controller));
                    long before = (long) field(controller, "outputAnalysisGeneration");
                    Knob knob = null; double old = 0;
                    switch (control) {
                        case "multiband" -> knob = ((Knob[])field(controller, "mbPushKnobs"))[0];
                        case "broadband" -> knob = (Knob) field(controller, "bbPushKnob");
                        case "levelingUndo" -> knob = (Knob) field(controller, "levelingKnob");
                        default -> { CheckBox box = (CheckBox) field(controller, "limEnabled"); box.setSelected(!box.isSelected()); }
                    }
                    if (knob != null) { old = knob.getValue(); knob.setValue(old == 0 ? .5 : 0); }
                    long after = (long) field(controller, "outputAnalysisGeneration");
                    if (field(controller, "dynRefreshDebounce") instanceof PauseTransition pause) pause.stop();
                    boolean undo = true;
                    if (control.equals("levelingUndo")) {
                        call(controller, "commitParamGesture"); call(controller, "onUndo");
                        undo = knob.getValue() == old && original.equals(gson.toJson(capture.invoke(controller)));
                    }
                    System.out.println("CONTROL_AUDIT " + control + " generation=" + before + "->" + after + " undoRestored=" + undo);
                    if (after <= before || !undo) failures++;
                } catch (Throwable e) { e.printStackTrace(); failures++; }
                finally { if (controller != null) controller.shutdown(); }
            }
            done.complete(failures);
        });
        int failures = done.get(30, TimeUnit.SECONDS); Platform.exit();
        System.out.println("CONTROL_AUDIT failures=" + failures); System.exit(failures == 0 ? 0 : 1);
    }
}
