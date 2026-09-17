package org.baseplayer;

import java.io.IOException;
import java.net.URL;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.io.VcfManager;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class MainApp extends Application {
    public static Stage stage;
    static Scene scene;
    public static boolean darkMode = true; // Start in dark mode by default
    public static Image icon;
    private static javafx.application.HostServices hostServices;
    private SplashScreen splashScreen;
    Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(0.1), e -> stage.setOpacity(1)));
    @Override
    public void start(Stage primaryStage) throws Exception {
        hostServices = getHostServices();
        icon = new Image(getResource("BasePlayer_icon.png").toString());
        splashScreen = new SplashScreen();
        splashScreen.show();
        
        long startTime = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Parent root = loadFXML("Main");
                stage = primaryStage;
                scene = new Scene(root);
                scene.setFill(Color.BLACK);
                // Load theme first, then application styles
                applyTheme();
                
                // Ensure minimum splash screen display time (1.5 seconds)
                long elapsed = System.currentTimeMillis() - startTime;
                long minDisplayTime = 1500;
                if (elapsed < minDisplayTime) {
                    Thread.sleep(minDisplayTime - elapsed);
                }
                
                Platform.runLater(() -> {
                    showMainStage(primaryStage);
                });
            } catch (IOException | InterruptedException e) {
                System.err.println("Error loading application: " + e.getMessage());
            }
        }).start();
        
        primaryStage.xProperty().addListener((obs, oldVal, newVal) -> {
             if (timeline.getStatus() == Animation.Status.RUNNING)
                timeline.stop();
            
            stage.setOpacity(0.6);
            // Start the Timeline
            timeline.playFromStart();
        });
        
        primaryStage.yProperty().addListener((obs, oldVal, newVal) -> {
            if (timeline.getStatus() == Animation.Status.RUNNING)
                timeline.stop();
                
            
            stage.setOpacity(0.6);
            // Start the Timeline
            timeline.playFromStart();
        });
    }
   
    void showMainStage(Stage primaryStage) {
        // Stage chrome must be configured on the FX thread before show().
        if (primaryStage.getStyle() != StageStyle.UNDECORATED) {
            primaryStage.initStyle(StageStyle.UNDECORATED);
        }
        applyStageIcons(primaryStage);
        primaryStage.setTitle("BasePlayer 2");

        stage.setScene(scene);
        FadeTransition ft = new FadeTransition(Duration.seconds(1), stage.getScene().getRoot());
        ft.setFromValue(0);
        ft.setToValue(1);
        stage.setMaximized(true);
        stage.setOnCloseRequest(event -> {
            if (!org.baseplayer.controllers.commands.FileCommands.confirmDiscardIfDirty()) {
                event.consume();
                return;
            }
            org.baseplayer.variant.ui.MinimizedVariantManagerWindow.handleCleanup();
        });
        stage.show();
        stage.setMaximized(true);
        splashScreen.close();
        ft.setOnFinished(e -> StartHub.show(primaryStage));
        ft.play();
        
        // Auto-open Variant Manager if VCFs are already loaded
        if (VcfManager.getInstance().hasLoadedVcf()) {
            org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
                MainApp.stage, VcfManager.getInstance(), null);
        }
    }

    @Override
    public void stop() {
        org.baseplayer.variant.ui.MinimizedVariantManagerWindow.handleCleanup();
    }

    /** Taskbar / window icons: several sizes help Linux desktop environments. */
    private static void applyStageIcons(Stage target) {
        if (target == null) return;
        String url = getResource("BasePlayer_icon.png").toExternalForm();
        target.getIcons().setAll(
            new Image(url, 16, 16, true, true),
            new Image(url, 32, 32, true, true),
            new Image(url, 48, 48, true, true),
            new Image(url, 64, 64, true, true),
            new Image(url, 128, 128, true, true),
            new Image(url, 256, 256, true, true)
        );
        if (icon == null) {
            icon = new Image(url);
        }
    }
    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }
    /**
     * Apply the current theme and application styles to the scene.
     */
    private static void applyTheme() {
        scene.getStylesheets().clear();
        // Load theme first (defines CSS variables)
        if (darkMode) {
            scene.getStylesheets().add(getResource("theme-dark.css").toExternalForm());
        } else {
            scene.getStylesheets().add(getResource("theme-light.css").toExternalForm());
        }
        // Then load application styles that use those variables
        scene.getStylesheets().add(getResource("application.css").toExternalForm());
    }
    
    /**
     * Toggle between dark and light mode.
     */
    public static void setDarkMode() {
        darkMode = !darkMode;
        applyTheme();
        // Update draw colors based on theme
        DrawColors.lineColor = darkMode 
            ? new Color(0.3, 0.6, 0.6, 0.5) 
            : new Color(0.5, 0.8, 0.8, 0.5);
        org.baseplayer.project.ProjectSessionState.get().markDirty();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }
    public static URL getResource(String string) {
        URL url = MainApp.class.getResource(string);
        if (url == null) {
            // Try absolute path anchored at the package
            url = MainApp.class.getResource("/org/baseplayer/" + string);
        }
        if (url == null) {
            String cp = System.getProperty("java.class.path");
            throw new RuntimeException("Resource '" + string + "' not found. Searched '" + string + "' and '/org/baseplayer/" + string + "'.\n" +
                    "Ensure build/resources/main is on the runtime classpath and that you rebuilt the project.\n" +
                    "Current classpath: " + cp);
        }
        return url;
    }
    
    public static javafx.application.HostServices getHostServicesInstance() {
        return hostServices;
    }
    
    public static void main(String[] args) { launch(args); }
}
