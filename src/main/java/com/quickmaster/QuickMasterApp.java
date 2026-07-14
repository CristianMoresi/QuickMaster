package com.quickmaster;

import com.quickmaster.config.AppLogger;
import com.quickmaster.ui.WindowsAspectRatioHook;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Locale;

/** JavaFX entry point for QuickMaster. */
public class QuickMasterApp extends Application
{
    public static final String APP_TITLE = "QuickMaster";

    public static final double DEFAULT_WIDTH  = 1180;
    public static final double DEFAULT_HEIGHT = 820;

    /** The interface is laid out at this fixed size, then scaled as one unit. */
    public static final double DESIGN_WIDTH  = 1360;
    public static final double DESIGN_HEIGHT = 830;

    private static final double SCALE_MIN = 0.6;
    private static final double SCALE_MAX = 1.7;

    private static final String FXML_MAIN = "/com/quickmaster/ui/main-view.fxml";
    private static final String CSS_MAIN  = "/com/quickmaster/ui/app.css";
    private static final Color BACKGROUND = Color.web("#14141a");

    private ProportionalWindowResizer proportionalWindowResizer;

    public static void main(String[] args)
    {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws IOException
    {
        AppLogger.info("Application starting (JavaFX).");

        URL fxmlUrl = getClass().getResource(FXML_MAIN);
        URL cssUrl  = getClass().getResource(CSS_MAIN);
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();

        Scene scene;
        Region design = null;
        Group scaledInterface = null;
        ImageView resizePreview = null;
        if (fxmlUrl != null)
        {
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            javafx.scene.Parent ui = loader.load();

            design = (Region) ui;
            design.setMinSize(DESIGN_WIDTH, DESIGN_HEIGHT);
            design.setPrefSize(DESIGN_WIDTH, DESIGN_HEIGHT);
            design.setMaxSize(DESIGN_WIDTH, DESIGN_HEIGHT);

            scaledInterface = new Group(design);
            resizePreview = new ImageView();
            resizePreview.setMouseTransparent(true);
            resizePreview.setSmooth(true);
            resizePreview.setPreserveRatio(false);
            resizePreview.setVisible(false);

            StackPane viewport = new StackPane(scaledInterface, resizePreview);
            viewport.setStyle("-fx-background-color: #14141a;");
            resizePreview.fitWidthProperty().bind(viewport.widthProperty());
            resizePreview.fitHeightProperty().bind(viewport.heightProperty());

            double openScale = Math.min(1.0, Math.min(
                    visualBounds.getWidth() * 0.92 / DESIGN_WIDTH,
                    visualBounds.getHeight() * 0.92 / DESIGN_HEIGHT));
            scene = new Scene(viewport,
                    DESIGN_WIDTH * openScale, DESIGN_HEIGHT * openScale);
            scene.setFill(BACKGROUND);

            javafx.beans.binding.NumberBinding zoom =
                    javafx.beans.binding.Bindings.min(
                            scene.widthProperty().divide(DESIGN_WIDTH),
                            scene.heightProperty().divide(DESIGN_HEIGHT));
            scaledInterface.scaleXProperty().bind(zoom);
            scaledInterface.scaleYProperty().bind(zoom);
        }
        else
        {
            AppLogger.warn("FXML not found at " + FXML_MAIN
                    + ": showing placeholder scene.");
            scene = new Scene(new StackPane(new Label(
                    "QuickMaster: FXML not yet provided.")),
                    DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }

        if (cssUrl != null)
            scene.getStylesheets().add(cssUrl.toExternalForm());

        stage.setTitle(APP_TITLE);
        stage.setScene(scene);

        for (int size : new int[]{ 16, 32, 48, 64, 128, 256 })
        {
            java.io.InputStream in = getClass().getResourceAsStream(
                    "/icon-" + size + ".png");
            if (in != null)
                stage.getIcons().add(new javafx.scene.image.Image(in));
        }

        stage.setResizable(true);
        stage.show();
        stage.centerOnScreen();

        proportionalWindowResizer = new ProportionalWindowResizer(
                stage, scene, design, scaledInterface, resizePreview,
                visualBounds, Screen.getPrimary().getOutputScaleX(),
                Screen.getPrimary().getOutputScaleY());
        proportionalWindowResizer.install();
    }

    /** Installs the platform constraint that keeps the content ratio fixed. */
    private static final class ProportionalWindowResizer implements AutoCloseable
    {
        private final Stage stage;
        private final Scene scene;
        private final Region design;
        private final Group scaledInterface;
        private final ImageView resizePreview;
        private final Rectangle2D screenBounds;
        private final double outputScaleX;
        private final double outputScaleY;
        private WindowsAspectRatioHook windowsHook;

        private ProportionalWindowResizer(Stage stage, Scene scene,
                Region design, Group scaledInterface, ImageView resizePreview,
                Rectangle2D screenBounds, double outputScaleX,
                double outputScaleY)
        {
            this.stage = stage;
            this.scene = scene;
            this.design = design;
            this.scaledInterface = scaledInterface;
            this.resizePreview = resizePreview;
            this.screenBounds = screenBounds;
            this.outputScaleX = outputScaleX;
            this.outputScaleY = outputScaleY;
        }

        private void install()
        {
            double decorationWidth = Math.max(0.0,
                    stage.getWidth() - scene.getWidth());
            double decorationHeight = Math.max(0.0,
                    stage.getHeight() - scene.getHeight());

            double screenScale = Math.min(
                    Math.max(1.0, screenBounds.getWidth() - decorationWidth)
                            / DESIGN_WIDTH,
                    Math.max(1.0, screenBounds.getHeight() - decorationHeight)
                            / DESIGN_HEIGHT);
            double maximumScale = Math.max(0.01,
                    Math.min(SCALE_MAX, screenScale));
            double minimumScale = Math.min(SCALE_MIN, maximumScale);
            double initialScale = Math.max(minimumScale,
                    Math.min(maximumScale, Math.min(
                            scene.getWidth() / DESIGN_WIDTH,
                            scene.getHeight() / DESIGN_HEIGHT)));

            stage.setMinWidth(DESIGN_WIDTH * minimumScale + decorationWidth);
            stage.setMinHeight(DESIGN_HEIGHT * minimumScale + decorationHeight);
            stage.setMaxWidth(DESIGN_WIDTH * maximumScale + decorationWidth);
            stage.setMaxHeight(DESIGN_HEIGHT * maximumScale + decorationHeight);

            if (!isWindows())
            {
                stage.setResizable(false);
                AppLogger.warn("Proportional native resizing is currently "
                        + "available only on Windows.");
                return;
            }

            try
            {
                windowsHook = WindowsAspectRatioHook.install(APP_TITLE,
                        DESIGN_WIDTH, DESIGN_HEIGHT,
                        minimumScale, maximumScale, initialScale,
                        outputScaleX, outputScaleY,
                        this::prepareLiveResize,
                        this::beginLiveResize, this::endLiveResize);
                AppLogger.info("Native WM_SIZING aspect-ratio constraint enabled.");
            }
            catch (RuntimeException | LinkageError error)
            {
                stage.setResizable(false);
                AppLogger.error("Native proportional resizing could not be "
                        + "installed; free resizing was disabled.", error);
            }
        }

        /** Captures before Windows starts moving or sizing the window. */
        private void prepareLiveResize()
        {
            if (design == null || resizePreview == null
                    || !Platform.isFxApplicationThread())
                return;

            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(BACKGROUND);
            parameters.setTransform(new Scale(outputScaleX, outputScaleY));
            WritableImage image = new WritableImage(
                    (int) Math.ceil(DESIGN_WIDTH * outputScaleX),
                    (int) Math.ceil(DESIGN_HEIGHT * outputScaleY));
            resizePreview.setImage(design.snapshot(parameters, image));
        }

        private void beginLiveResize()
        {
            if (scaledInterface == null || resizePreview == null
                    || resizePreview.getImage() == null
                    || !Platform.isFxApplicationThread())
                return;
            resizePreview.setVisible(true);
            scaledInterface.setVisible(false);
        }

        private void endLiveResize()
        {
            if (scaledInterface == null || resizePreview == null
                    || !Platform.isFxApplicationThread())
                return;
            scaledInterface.setVisible(true);
            resizePreview.setVisible(false);
            resizePreview.setImage(null);
        }

        private static boolean isWindows()
        {
            return System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).startsWith("windows");
        }

        @Override
        public void close()
        {
            endLiveResize();
            if (windowsHook != null)
            {
                windowsHook.close();
                windowsHook = null;
            }
        }
    }

    @Override
    public void stop()
    {
        if (proportionalWindowResizer != null)
            proportionalWindowResizer.close();
        AppLogger.info("Application exited normally.");
    }
}
