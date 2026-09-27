import com.quickmaster.audio.AudioFile;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.dynamics.MacroLevelerProcessor;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Diagnostic, not musical acceptance: actual player/listeners/animation with a simulated device. */
public class LevelerPlaybackDiagnostic {
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void call(Object owner, String name) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(owner);
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(work.call()); }
            catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(30, TimeUnit.SECONDS);
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Platform.startup(() -> {});
        MainController controller = null;
        try {
            double amount = args.length > 1 ? Double.parseDouble(args[1]) : 1;
            AtomicLong devicePosition = new AtomicLong(), writes = new AtomicLong();
            SourceDataLine device = (SourceDataLine)Proxy.newProxyInstance(
                    LevelerPlaybackDiagnostic.class.getClassLoader(), new Class<?>[]{SourceDataLine.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "write":
                                Thread.sleep(20);
                                int bytes = (int)arguments[2];
                                devicePosition.addAndGet(bytes / 4); writes.incrementAndGet(); return bytes;
                            case "getLongFramePosition": return devicePosition.get();
                            default:
                                if (method.getReturnType() == boolean.class) return false;
                                if (method.getReturnType() == int.class) return 0;
                                if (method.getReturnType() == long.class) return 0L;
                                return null;
                        }
                    });
            controller = fx(() -> {
                FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                Region root = loader.load(); MainController c = loader.getController();
                new Scene(root, 1360, 830);
                root.applyCss(); root.resize(1360, 830); root.layout();
                AudioPlayer player = (AudioPlayer)field(c, "player");
                // Keep the original player, including its real playing-property
                // listeners and AnimationTimer. Only the hardware factory is replaced.
                Class<?> factory = Class.forName("com.quickmaster.playback.AudioPlayer$LineFactory");
                Object replacement = Proxy.newProxyInstance(factory.getClassLoader(), new Class<?>[]{factory},
                        (p, m, a) -> device);
                Field factoryField = AudioPlayer.class.getDeclaredField("lineFactory");
                factoryField.setAccessible(true); factoryField.set(player, replacement);
                for (Object module : (List<?>)field(c, "chainModules"))
                    ((CheckBox)field(module, "enableBox")).setSelected(false);
                for (var stage : ((ProcessingPipeline)field(c, "pipeline")).getProcessors()) stage.setEnabled(false);
                for (Object card : (List<?>)field(c, "dynCards"))
                    ((CheckBox)field(card, "on")).setSelected(false);
                ((CheckBox)field(c, "dynMasterEnabled")).setSelected(true);
                for (Object card : (List<?>)field(c, "dynCards"))
                    if (field(card, "name").equals("Leveler")) ((CheckBox)field(card, "on")).setSelected(true);
                Object knob = field(c, "levelingKnob");
                knob.getClass().getMethod("setValue", double.class).invoke(knob, amount);
                if (field(c, "dynRefreshDebounce") instanceof PauseTransition pause) pause.stop();
                Method load = MainController.class.getDeclaredMethod("loadAudioFile", java.io.File.class);
                load.setAccessible(true); load.invoke(c, Path.of(args[0]).toFile());
                return c;
            });
            MainController c = controller;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            boolean ready = false;
            while (!ready && System.nanoTime() < deadline) {
                Thread.sleep(100);
                ready = fx(() -> field(c, "loadedFile") != null
                        && field(c, "outputAnalysisGeneration").equals(field(c, "levelerReadyGeneration"))
                        && (int)field(c, "analyzeJobs") == 0);
            }
            if (!ready) throw new AssertionError("Current analysis not ready");
            AudioPlayer player = fx(() -> (AudioPlayer)field(c, "player"));
            AudioFile song = fx(() -> (AudioFile)field(c, "loadedFile"));
            if (song.getChannels() != 2) throw new AssertionError("This device fixture expects stereo");
            float[] source = song.getSamples(), rendered = (float[])field(player, "publishedRender");
            System.out.println("JAR " + MainController.class.getProtectionDomain().getCodeSource().getLocation());
            System.out.println("DIAGNOSTIC amount=" + amount + " status="
                    + fx(() -> ((MacroLevelerProcessor)field(c, "leveler")).getAnalysisDiagnostic()));
            int positiveWindows = 0, negativeWindows = 0;
            for (double second : new double[]{10, 20, 40, 80, 130, 180, 200, 240, 270}) {
                int from = (int)(second * song.getSampleRate()) * 2, to = from + song.getSampleRate() * 2;
                double originalPower = 0, outputPower = 0;
                long changed = 0;
                for (int i = from; i < Math.min(to, source.length); i++) {
                    originalPower += (double)source[i] * source[i]; outputPower += (double)rendered[i] * rendered[i];
                    if (Float.floatToRawIntBits(source[i]) != Float.floatToRawIntBits(rendered[i])) changed++;
                }
                fx(() -> { player.seekTo(second); if (!player.isPlaying()) call(c, "onPlayPause"); return null; });
                long before = writes.get();
                Thread.sleep(250);
                String meter = fx(() -> {
                    for (Object card : (List<?>)field(c, "dynCards"))
                        if (field(card, "name").equals("Leveler")) return ((Label)field(card, "grLabel")).getText();
                    throw new AssertionError("No Leveler card");
                });
                if (!player.isPlaying() || writes.get() <= before) throw new AssertionError("No playback at " + second);
                double pcmGain = 10 * Math.log10(outputPower / originalPower);
                if (pcmGain > 1) positiveWindows++;
                if (pcmGain < -.00001) negativeWindows++;
                double expectedMeter = fx(() -> ((MacroLevelerProcessor)field(c, "leveler"))
                        .getGainDbAtPosition(player.getPositionSamples()));
                double displayed = Double.parseDouble(meter.replace("dB", "").strip());
                if (Math.abs(displayed - expectedMeter) > .75)
                    throw new AssertionError("Meter does not follow playback gain: " + meter + " vs " + expectedMeter);
                System.out.printf("WINDOW second=%.1f pcmGainDb=%.6f changed=%d animatedMeter=%s playerSecond=%.3f%n",
                        second, 10 * Math.log10(outputPower / originalPower), changed, meter,
                        player.getPositionSamples() / (double)song.getSampleRate());
            }
            if (args.length > 2 && args[2].equals("--assert")) {
                if (positiveWindows < 3 || negativeWindows != 0)
                    throw new AssertionError("Expected several audible boosts and no cuts");
                System.out.println("MACRO_PLAYBACK_PASS positiveWindows=" + positiveWindows
                        + " negativeWindows=" + negativeWindows + " hardwareSimulated=true");
            }
            System.out.println("DIAGNOSTIC_COMPLETE hardwareSimulated=true musicalAcceptance=false");
        } finally {
            if (controller != null) { MainController c = controller; fx(() -> { c.shutdown(); return null; }); }
            Platform.exit();
        }
    }
}
