package org.baseplayer;

import java.io.IOException;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
       //Parent root = FXMLLoader.load(getClass().getResource("scene.fxml"));
      // System.out.println(getClass().getResource("styles.css"));
        Scene scene = new Scene(loadFXML("Main"));
        //Scene scene = new Scene(root);
        scene.getStylesheets().add(MainApp.class.getResource("styles.css").toExternalForm());
        Image icon = new Image(MainApp.class.getResource("BasePlayer_icon.png").toString());
        stage.getIcons().add(icon);
        stage.setTitle("BasePlayer 2");
        stage.setScene(scene);
        stage.setFullScreen(false);
        stage.show();
    }
    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(MainApp.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }
    public static void main(String[] args) {
        launch(args);
    }
}