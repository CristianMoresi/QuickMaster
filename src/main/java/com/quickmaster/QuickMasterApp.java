package com.quickmaster;

import com.quickmaster.config.AppLogger;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * JavaFX entry point for QuickMaster.
 * <p>
 * Bootstraps the JavaFX runtime, loads the main scene from FXML
 * ({@code main-view.fxml}), applies the application stylesheet,
 * and shows the primary window.
 */
public class QuickMasterApp extends Application
{
    public static final String APP_TITLE = "QuickMaster";

    public static final double DEFAULT_WIDTH  = 1180;
    public static final double DEFAULT_HEIGHT = 820;

    private static final String FXML_MAIN = "/com/quickmaster/ui/main-view.fxml";
    private static final String CSS_MAIN  = "/com/quickmaster/ui/app.css";

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

        Scene scene;
        if (fxmlUrl != null)
        {
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            scene = new Scene(loader.load(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }
        else
        {
            AppLogger.warn("FXML not found at " + FXML_MAIN
                    + ": showing placeholder scene.");
            StackPane root = new StackPane(new Label(
                    "QuickMaster: FXML not yet provided."));
            scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }

        if (cssUrl != null)
        {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle(APP_TITLE);
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(720);

        // Load the icon at several resolutions so the window, taskbar and switcher
        // each pick a crisp size instead of downscaling one large image.
        for (int size : new int[]{ 16, 32, 48, 64, 128, 256 })
        {
            java.io.InputStream in = getClass().getResourceAsStream("/icon-" + size + ".png");
            if (in != null) stage.getIcons().add(new javafx.scene.image.Image(in));
        }

        stage.show();
    }

    @Override
    public void stop()
    {
        AppLogger.info("Application exited normally.");
    }
}