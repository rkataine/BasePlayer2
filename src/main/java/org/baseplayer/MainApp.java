package org.baseplayer;

import java.io.IOException;
import java.net.URL;

import org.baseplayer.draw.DrawFunctions;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class MainApp extends Application {
    public static Stage stage;
    public static Stage splashStage; 
    static Scene scene;
    public static boolean darkMode = false;
    public static Image icon;
    Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(0.1), e -> stage.setOpacity(1)));
    @Override
    public void start(Stage primaryStage) throws Exception {
        icon = new Image(getResource("BasePlayer_icon.png").toString());
        showShplashScreen();
        PauseTransition delay = new PauseTransition(Duration.seconds(1));
        delay.setOnFinished(event -> { showMainStage(primaryStage); });
        delay.play();
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
   
    void showShplashScreen() {
        splashStage = new Stage();
        splashStage.setOpacity(0.85);
        splashStage.initStyle(StageStyle.UNDECORATED);
        splashStage.getIcons().add(icon);
        ImageView imageView = new ImageView(icon);
        Rectangle shine = new Rectangle(200, 200);
        shine.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.TRANSPARENT),
            new Stop(0.5, Color.WHITE),
            new Stop(1, Color.TRANSPARENT)));
        shine.setBlendMode(BlendMode.OVERLAY);

        // Create a TranslateTransition for the shine effect
        TranslateTransition tt = new TranslateTransition(Duration.seconds(1), shine);
        tt.setFromX(-200);
        tt.setToX(200);
        tt.setCycleCount(1);
        tt.play();
        StackPane splashRoot = new StackPane(imageView, shine);
        Scene splashScene = new Scene(splashRoot, 200, 200);
        splashStage.setScene(splashScene);
        splashStage.show();
    }
    void showMainStage(Stage primaryStage) {
        primaryStage.setWidth(1);
        primaryStage.setHeight(1); 
        new Thread(() -> {
            try {

                Parent root = loadFXML("Main");
                stage = primaryStage;
                scene = new Scene(root);
                scene.setFill(Color.BLACK);
                scene.getStylesheets().add(getResource("styles.css").toExternalForm());
                setDarkMode();       
                stage.initStyle(StageStyle.DECORATED);
                stage.getIcons().add(icon);
                stage.setTitle("BasePlayer 2");
                
                Platform.runLater(() -> {
                  
                    stage.setScene(scene);
                    FadeTransition ft = new FadeTransition(Duration.seconds(1), stage.getScene().getRoot());
                    ft.setFromValue(0);
                    ft.setToValue(1);
                    stage.show(); 
                    splashStage.close();
                    Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
                    stage.setX(screenBounds.getWidth()/2 - 600);
                    stage.setY(50);
                    stage.setWidth(1200);
                    stage.setHeight(800);
                    ft.play();
                });               
               
            } catch (IOException e) {
                System.err.println("Error loading FXML: " + e.getMessage());
            }
        }).start();
        
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
    static URL getResource(String string) { return MainApp.class.getResource(string); }
    public static void main(String[] args) { launch(args); }
}
