package org.baseplayer;

import java.io.IOException;
import java.net.URL;

import org.baseplayer.draw.DrawFunctions;

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
    public static boolean darkMode = false;
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
        
        // Start loading immediately in background
        long startTime = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Parent root = loadFXML("Main");
                stage = primaryStage;
                scene = new Scene(root);
                scene.setFill(Color.BLACK);
                scene.getStylesheets().add(getResource("styles.css").toExternalForm());
                setDarkMode();       
                stage.initStyle(StageStyle.UNDECORATED);
                stage.getIcons().add(icon);
                stage.setTitle("BasePlayer 2");
                
                // Ensure minimum splash screen display time (1.5 seconds)
                long elapsed = System.currentTimeMillis() - startTime;
                long minDisplayTime = 1500;
                if (elapsed < minDisplayTime) {
                    Thread.sleep(minDisplayTime - elapsed);
                }
                
                Platform.runLater(() -> {
                    showMainStage(primaryStage);
                });
            } catch (Exception e) {
                System.err.println("Error loading application: " + e.getMessage());
                e.printStackTrace();
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
        stage.setScene(scene);
        FadeTransition ft = new FadeTransition(Duration.seconds(1), stage.getScene().getRoot());
        ft.setFromValue(0);
        ft.setToValue(1);
        stage.setMaximized(true);
        stage.show(); 
        splashScreen.close();
        ft.play();
    }
    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }
    public static void setDarkMode() {        
        if (darkMode) scene.getStylesheets().remove(getResource("darkmode.css").toExternalForm());
        else scene.getStylesheets().add(getResource("darkmode.css").toExternalForm());
        DrawFunctions.lineColor = darkMode ? DrawFunctions.lineColor = new Color(0.3, 0.6, 0.6, 0.5) : new Color(0.5, 0.8, 0.8, 0.5);
        darkMode = !darkMode;
        DrawFunctions.update.set(!DrawFunctions.update.get());
    }
    static URL getResource(String string) {
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
