package org.baseplayer;

import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.effect.BlendMode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class SplashScreen {
    private Stage splashStage;
    
    public void show() {
        splashStage = new Stage();
        splashStage.setOpacity(0.85);
        splashStage.initStyle(StageStyle.TRANSPARENT);
        
        // Create stylized "BP" text
        Text bpText = new Text("BasePlayer 2");
        bpText.setFont(Font.font("Arial", FontWeight.BOLD, 80));
        bpText.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.rgb(0, 120, 130)),
            new Stop(1, Color.rgb(0, 80, 90))));
        bpText.setStroke(Color.rgb(0, 0, 0));
        bpText.setStrokeWidth(1);
        
        // Create secondary title
        Text fundingText = new Text("Funded by the Cancer Foundation Finland");
        fundingText.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        fundingText.setFill(Color.rgb(255, 255, 255));
        fundingText.setStroke(Color.rgb(0, 0, 0));
        fundingText.setStrokeWidth(0.5);
        
        // Get text bounds for dynamic sizing
        double textWidth = bpText.getLayoutBounds().getWidth();
        double textHeight = bpText.getLayoutBounds().getHeight();
        double fundingHeight = fundingText.getLayoutBounds().getHeight();
        double sceneWidth = textWidth + 40; // Add padding
        double sceneHeight = textHeight + fundingHeight + 60; // Add space for secondary title
        
        // Create shine text that will overlay with the effect
        Text shineText = new Text("BASEPLAYER 2");
        shineText.setFont(Font.font("Arial", FontWeight.BOLD, 80));
        shineText.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.TRANSPARENT),
            new Stop(0.4, Color.TRANSPARENT),
            new Stop(0.5, Color.rgb(255, 255, 255, 0.8)),
            new Stop(0.6, Color.TRANSPARENT),
            new Stop(1, Color.TRANSPARENT)));
        shineText.setStroke(Color.TRANSPARENT);
        shineText.setBlendMode(BlendMode.ADD);

        // Create a TranslateTransition for the shine effect
        TranslateTransition tt = new TranslateTransition(Duration.seconds(1.5), shineText);
        tt.setFromX(-sceneWidth);
        tt.setToX(sceneWidth);
        tt.setCycleCount(1);
        tt.play();
        
        // Stack the main text and shine text
        StackPane titleStack = new StackPane(bpText, shineText);
        
        // Create VBox to hold title and secondary text
        VBox contentBox = new VBox(10);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.getChildren().addAll(titleStack, fundingText);
        
        // Create root pane
        StackPane splashRoot = new StackPane(contentBox);
        splashRoot.setStyle("-fx-background-color: transparent;");
        Scene splashScene = new Scene(splashRoot, sceneWidth, sceneHeight);
        splashScene.setFill(Color.TRANSPARENT);
        splashStage.setScene(splashScene);
        splashStage.show();
    }
    
    public void close() {
        if (splashStage != null) {
            splashStage.close();
        }
    }
}
