import com.quickmaster.config.ChainPreset;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the picture of the interface the README shows: the real FXML and
 * controller with a track loaded and processed, every module of the chain on,
 * the EQ tab with a gentle dynamic tilt band and the Auto EQ at half its
 * amount. Drawn off screen at the design size (1360 x 830) times a scale:
 * no window, no audio device, the source file read only. On a display scaled
 * to 200 % the canvases are drawn at twice the size too, so a scale of 2 is
 * sharp throughout.
 * <p>
 * Arguments: the WAV, the PNG to write, the scale (2 when left out) and the
 * playhead in seconds (a third of the way in when left out).
 */
public final class ReadmeScreenshot extends StereoUiAudit
{
    public static void main(String[] args) throws Exception
    {
        Path audio = Path.of(args[0]), out = Path.of(args[1]).toAbsolutePath();
        double scale = args.length > 2 ? Double.parseDouble(args[2]) : 2.0;
        double playhead = args.length > 3 ? Double.parseDouble(args[3]) : Double.NaN;
        final int width = 1360, height = 830;
        Files.createDirectories(out.getParent());
        System.out.println("APPLICATION_CLASSES " + MainController.class.getProtectionDomain().getCodeSource().getLocation());
        Platform.startup(() -> { });
        try
        {
            fx(() ->
            {
                var loader = new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                root = loader.load();
                c = loader.getController();
                var scene = new Scene(root, width, height);
                scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                for (Object module : (List<?>) get("chainModules"))
                    if (get(module, "name").equals("EQ")) call("selectModule", new Class<?>[] { module.getClass() }, module);
                call("loadAudioFile", new Class<?>[] { java.io.File.class }, audio.toFile());
                return null;
            });
            ready();
            fx(() ->
            {
                var p = (ChainPreset) call("capturePreset");
                p.eqOn = true;
                p.eqAutoGain = true;
                p.autoEqOn = true;
                p.autoEqAmount = 0.5;
                p.autoEqCutBoost = 0.0;
                var band = new ChainPreset.BandPreset();
                band.type = "TILT";
                band.channel = "STEREO";
                band.phase = "MINIMUM";
                band.frequency = 706.0;
                band.gainDb = 2.0;
                band.q = 0.93;
                band.dynamic = true;
                band.threshold = -20.0;
                band.aboveRatio = 2.0;
                band.aboveAttackMs = 5.0;
                band.aboveReleaseMs = 80.0;
                band.aboveRangeDb = 6.0;
                band.belowRatio = 1.0;
                band.belowAttackMs = 10.0;
                band.belowReleaseMs = 120.0;
                band.belowRangeDb = 6.0;
                p.bands = new ArrayList<>(List.of(band));
                p.stereoOn = p.dynamicsOn = p.clipOn = p.limitOn = true;
                p.normalizerOn = true;
                p.normalizerTargetDbtp = -0.1;
                p.osOn = false;
                call("applyPreset", new Class<?>[] { ChainPreset.class, boolean.class }, p, true);
                call("syncLiveAnalysis");
                return null;
            });
            ready();
            fx(() ->
            {
                call("selectBand", new Class<?>[] { int.class }, 0);
                var player = get("player");
                player.getClass().getMethod("seekTo", double.class).invoke(player, Double.isNaN(playhead) ? duration() / 3.0 : playhead);
                root.setMinSize(width, height);
                root.setPrefSize(width, height);
                root.setMaxSize(width, height);
                root.resize(width, height);
                root.applyCss();
                root.layout();
                call("updatePositionLabel");
                call("drawEqCurve");
                call("drawWaveform");
                var parameters = new SnapshotParameters();
                parameters.setTransform(Transform.scale(scale, scale));
                parameters.setViewport(new Rectangle2D(0, 0, width * scale, height * scale));
                var image = root.snapshot(parameters, null);
                var png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < png.getHeight(); y++)
                    for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                ImageIO.write(png, "png", out.toFile());
                System.out.printf(Locale.US, "README_SCREENSHOT %s %dx%d scale=%.1f%n", out, png.getWidth(), png.getHeight(), scale);
                return null;
            });
        }
        finally
        {
            fx(() ->
            {
                if (c != null) c.shutdown();
                return null;
            });
            Platform.exit();
        }
    }

    /** The length of the loaded track, in seconds. */
    private static double duration() throws Exception
    {
        Object file = get("loadedFile");
        return (double) file.getClass().getMethod("getDuration").invoke(file);
    }
}
