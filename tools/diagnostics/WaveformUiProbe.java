import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.FadeProcessor;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.ui.MainController;
import com.quickmaster.ui.waveform.WaveformViewport;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;

/** Separate-process JavaFX interaction probe; uses synthetic PCM and isolated APPDATA. */
public final class WaveformUiProbe {
    private static final class TransportSentinel extends AudioPlayer {
        TransportSentinel() { super(new ProcessingPipeline()); }
        @Override public long getPositionSamples() { return 56_000; }
        @Override public State getState() { return State.PLAYING; }
        @Override public boolean isPlaying() { return true; }
        @Override public synchronized void seekTo(double seconds) { throw new AssertionError("Wheel sought playback"); }
        @Override public synchronized void play() { throw new AssertionError("Wheel started playback"); }
        @Override public synchronized void pause() { throw new AssertionError("Wheel paused playback"); }
        @Override public synchronized void stop() { throw new AssertionError("Wheel stopped playback"); }
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
    private static void call(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }
    private static void snapshot(Node node, Path path) throws Exception {
        WritableImage image = node.snapshot(null, null);
        BufferedImage png = new BufferedImage((int)image.getWidth(), (int)image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var pixels = image.getPixelReader();
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, pixels.getArgb(x, y));
        if (!ImageIO.write(png, "png", path.toFile())) throw new AssertionError("PNG writer unavailable");
    }
    private static void scroll(Canvas canvas, double localX, double delta, boolean shortcut) {
        scroll(canvas, localX, canvas.getHeight() * 0.5, 0, delta, shortcut, false);
    }
    private static void scroll(Canvas canvas, double localX, double localY, double dx, double dy, boolean shortcut, boolean alt) {
        Point2D scene = canvas.localToScene(localX, localY);
        ScrollEvent event = new ScrollEvent(ScrollEvent.SCROLL, scene.getX(), scene.getY(), 0, 0,
                false, shortcut, alt, false, false, false, dx, dy, dx, dy,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0,
                new PickResult(canvas, scene.getX(), scene.getY()));
        canvas.fireEvent(event);
    }
    private static void probe(Path output) throws Exception {
        Files.createDirectories(output);
        FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
        Region root = loader.load();
        MainController controller = loader.getController();
        root.setPrefSize(1360, 830);
        Scene scene = new Scene(root, 1360, 830);
        scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
        root.applyCss();
        root.resize(1360, 830);
        root.layout();
        TrackAnalysis tempo = (TrackAnalysis)field(controller, "trackAnalysis");
        set(tempo, "bpm", 82.25);
        set(tempo, "detectedBpm", 82.25);
        set(tempo, "confidence", 0.65);
        call(controller, "updateBpmUi");
        Label tempoLabel = (Label)field(controller, "beatBpmLabel");
        if (!tempoLabel.getText().contains("auto?") || !tempoLabel.getTooltip().getText().contains("Uncertain"))
            throw new AssertionError("Ambiguous tempo not exposed by the UI");
        var modules = (java.util.List<?>)field(controller, "chainModules");
        Method select = MainController.class.getDeclaredMethod("selectModule", modules.get(0).getClass());
        select.setAccessible(true);
        select.invoke(controller, modules.get(1));
        root.applyCss(); root.layout();
        snapshot(root, output.resolve("ui-dynamics.png"));
        select.invoke(controller, modules.get(0));
        root.applyCss(); root.layout();
        tempo.setManualBpm(97);
        call(controller, "updateBpmUi");
        if (!tempoLabel.getText().contains("97 BPM") || !tempoLabel.getText().contains("manual"))
            throw new AssertionError("Manual tempo not exposed by the UI");
        tempo.clearManualBpm();
        tempo.analyze(new float[16000], 1, 8000);
        call(controller, "updateBpmUi");
        if (!tempoLabel.getText().equals("no tempo") || !tempoLabel.getTooltip().getText().contains("250 ms"))
            throw new AssertionError("Unknown tempo fallback not exposed by the UI");
        System.out.println("TEMPO_UI_PASS ambiguous=auto? manual=97 unknown=250ms");
        int rate = 8000;
        float[] pcm = new float[16 * rate];
        for (int i = 0; i < pcm.length; i++) {
            double time = i / (double)rate;
            double amplitude = 0.08 + 0.62 * (0.5 + 0.5 * Math.sin(time * 1.3));
            pcm[i] = (float)(amplitude * Math.sin(time * 2 * Math.PI * 173));
        }
        set(controller, "loadedFile", new WavFile("synthetic-ui.wav", rate, 1, pcm, 16, false));
        set(controller, "player", new TransportSentinel());
        set(controller, "selStartSec", 6.0);
        set(controller, "selEndSec", 8.0);
        FadeProcessor fade = (FadeProcessor)field(controller, "fade");
        fade.setFadeInSec(4.0);
        fade.setFadeOutSec(3.0);
        var fadeType = fade.getFadeType();
        call(controller, "resetWaveformViewport");
        call(controller, "downsampleForDisplay");
        call(controller, "drawWaveform");
        Canvas canvas = (Canvas)field(controller, "waveformCanvas");
        if (canvas.getWidth() < 500 || canvas.getHeight() < 30) throw new AssertionError("Canvas was not laid out");
        snapshot(root, output.resolve("ui-full.png"));
        snapshot(canvas, output.resolve("waveform-full.png"));
        double pointer = canvas.getWidth() * 0.25;
        WaveformViewport before = (WaveformViewport)field(controller, "waveformViewport");
        double anchor = before.timeAtX(pointer, canvas.getWidth()).orElseThrow();
        scroll(canvas, pointer, 120, true);
        scroll(canvas, pointer, 120, true);
        WaveformViewport zoomed = (WaveformViewport)field(controller, "waveformViewport");
        if (!(zoomed.visibleSec() < before.visibleSec())) throw new AssertionError("Shortcut wheel did not zoom");
        if (Math.abs(zoomed.timeAtX(pointer, canvas.getWidth()).orElseThrow() - anchor) > 1e-10) throw new AssertionError("Pointer anchor drift");
        if (fadeType != fade.getFadeType()) throw new AssertionError("Shortcut wheel changed fade type");
        float[] columns = (float[])field(controller, "waveformDownsampled");
        if (columns.length != (int)canvas.getWidth()) throw new AssertionError("Peak columns do not match the viewport width");
        snapshot(canvas, output.resolve("waveform-zoomed.png"));
        snapshot(root, output.resolve("ui-zoomed.png"));
        scroll(canvas, canvas.getWidth() * 0.6, 120, false);
        WaveformViewport panned = (WaveformViewport)field(controller, "waveformViewport");
        if (!(panned.startSec() < zoomed.startSec())) throw new AssertionError("Unmodified wheel did not pan toward track start");
        if (panned.visibleSec() != zoomed.visibleSec()) throw new AssertionError("Panning changed zoom");
        if ((double)field(controller, "selStartSec") != 6.0 || (double)field(controller, "selEndSec") != 8.0)
            throw new AssertionError("Panning changed the selection");
        if (fadeType != fade.getFadeType()) throw new AssertionError("Panning changed fade type");
        snapshot(canvas, output.resolve("waveform-panned.png"));
        scroll(canvas, canvas.getWidth() * 0.6, -120, false);
        WaveformViewport returned = (WaveformViewport)field(controller, "waveformViewport");
        if (Math.abs(returned.startSec() - zoomed.startSec()) > 1e-10) throw new AssertionError("Reverse wheel did not restore viewport");
        double handleX = returned.xAtTime(fade.getFadeInSec(), canvas.getWidth()).orElseThrow();
        scroll(canvas, handleX, 9, 0, 40, false, false);
        WaveformViewport overHandle = (WaveformViewport)field(controller, "waveformViewport");
        if (!(overHandle.startSec() < returned.startSec()) || fadeType != fade.getFadeType())
            throw new AssertionError("Plain wheel over a fade handle did not exclusively pan");
        double shiftedHandleX = overHandle.xAtTime(fade.getFadeInSec(), canvas.getWidth()).orElseThrow();
        scroll(canvas, shiftedHandleX, 9, 0, 40, false, true);
        if (fadeType == fade.getFadeType() || !overHandle.equals(field(controller, "waveformViewport")))
            throw new AssertionError("Alt-wheel did not preserve the dedicated fade gesture");
        fade.setFadeType(fadeType);
        scroll(canvas, canvas.getWidth() * 0.6, 70, -40, 0, false, false);
        WaveformViewport horizontal = (WaveformViewport)field(controller, "waveformViewport");
        if (Math.abs(horizontal.startSec() - returned.startSec()) > 1e-10)
            throw new AssertionError("Horizontal trackpad event did not pan");
        for (int i = 0; i < 50; i++) scroll(canvas, pointer, 120, false);
        WaveformViewport leftEdge = (WaveformViewport)field(controller, "waveformViewport");
        if (leftEdge.startSec() != 0 || leftEdge.visibleSec() != zoomed.visibleSec())
            throw new AssertionError("Panning crossed the left edge or changed zoom");
        for (int i = 0; i < 50; i++) scroll(canvas, pointer, -120, false);
        WaveformViewport rightEdge = (WaveformViewport)field(controller, "waveformViewport");
        if (rightEdge.startSec() != rightEdge.durationSec() - rightEdge.visibleSec())
            throw new AssertionError("Panning crossed the right edge");
        for (int i = 0; i < 30; i++) scroll(canvas, pointer, -120, true);
        if (!before.equals(field(controller, "waveformViewport"))) throw new AssertionError("Zoom-out did not return to full track");
        snapshot(canvas, output.resolve("waveform-restored.png"));
        System.out.println("WAVEFORM_UI_PASS full=" + before + " zoomed=" + zoomed + " anchorSec=" + anchor
                + " canvas=" + canvas.getWidth() + "x" + canvas.getHeight() + " screenshots=" + output.toAbsolutePath());
        System.out.println("WAVEFORM_PAN_PASS start=" + zoomed.startSec() + " -> " + panned.startSec()
                + " visibleSec=" + panned.visibleSec() + " playbackUnchanged=true selectionUnchanged=true");
    }
    public static void main(String[] args) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.startup(() -> {
            try { probe(Path.of(args[0])); done.complete(null); }
            catch (Throwable error) { done.completeExceptionally(error); }
        });
        int exit = 0;
        try { done.get(40, TimeUnit.SECONDS); }
        catch (Exception error) { error.printStackTrace(); exit = 1; }
        finally { Platform.exit(); }
        System.exit(exit);
    }
}
