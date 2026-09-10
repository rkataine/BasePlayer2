package org.baseplayer.variant.ui;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Objects;

public class MinimizedVariantManagerWindow {
    private static final double MINIMIZED_WIDTH = 280;
    private static final double MINIMIZED_HEIGHT = 78;

    private static MinimizedVariantManagerWindow instance;
    private static Stage managedMainStage;

    private Stage minimizedStage;

    private MinimizedVariantManagerWindow(Stage mainStage) {
        managedMainStage = mainStage;
    }

    public static void handleMinimize(Stage mainStage) {
        if (mainStage == null) {
            return;
        }

        if (instance == null || !Objects.equals(MinimizedVariantManagerWindow.managedMainStage, mainStage)) {
            instance = new MinimizedVariantManagerWindow(mainStage);
        }

        instance.minimize();
    }

    public static void handleExpand() {
        if (instance != null) {
            instance.expand();
        }
    }

    public static void handleCleanup() {
        if (instance != null) {
            instance.cleanup();
            instance = null;
            managedMainStage = null;
        }
    }

    private void minimize() {
        if (minimizedStage != null && minimizedStage.isShowing()) {
            minimizedStage.toFront();
            minimizedStage.requestFocus();
            return;
        }

        createAndShowWindow();
        managedMainStage.hide();
    }

    private void expand() {
        if (!managedMainStage.isShowing()) {
            managedMainStage.show();
        }
        managedMainStage.toFront();
        managedMainStage.requestFocus();

        if (minimizedStage != null) {
            minimizedStage.close();
            minimizedStage = null;
        }
    }

    private void cleanup() {
        if (minimizedStage != null) {
            minimizedStage.close();
            minimizedStage = null;
        }
    }

    private void createAndShowWindow() {
        minimizedStage = new Stage();
        minimizedStage.initStyle(StageStyle.TRANSPARENT);
        minimizedStage.setResizable(false);
        minimizedStage.setAlwaysOnTop(true);

        FontIcon expandIcon = new FontIcon(FontAwesomeSolid.EXPAND);
        expandIcon.setIconSize(18);
        expandIcon.setIconColor(Color.web("#aaaaaa"));

        Button maximizeButton = new Button();
        maximizeButton.setGraphic(expandIcon);
        maximizeButton.setStyle("-fx-padding: 0 4 0 4; -fx-background-color: transparent; -fx-border: none; -fx-cursor: hand;");
        maximizeButton.setOnMouseEntered(e -> expandIcon.setIconColor(Color.web("#ffffff")));
        maximizeButton.setOnMouseExited(e -> expandIcon.setIconColor(Color.web("#aaaaaa")));
        maximizeButton.setOnAction(e -> handleExpand());

        Label titleLabel = new Label("Variant Manager");
        titleLabel.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 12px; -fx-font-weight: bold;");
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        HBox titleBar = new HBox(6);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setStyle("-fx-background-color: #1f1f1f; -fx-background-radius: 10 10 0 0; -fx-border-color: #555555; -fx-border-width: 1 1 0 1; -fx-border-radius: 10 10 0 0; -fx-padding: 6 8 6 10;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleBar.getChildren().add(titleLabel);

        Label minimizedLabel = new Label("Minimized");
        minimizedLabel.setStyle("-fx-text-fill: #d8d8d8; -fx-font-size: 14px; -fx-font-weight: bold;");

        HBox minimizedContent = new HBox(8);
        minimizedContent.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        minimizedContent.getChildren().addAll(minimizedLabel, spacer, maximizeButton);

        VBox body = new VBox();
        body.setAlignment(Pos.CENTER_LEFT);
        body.setStyle("-fx-background-color: #2d2d2d; -fx-background-radius: 0 0 10 10; -fx-border-color: #555555; -fx-border-width: 0 1 1 1; -fx-border-radius: 0 0 10 10; -fx-padding: 10 10 10 10;");
        body.getChildren().add(minimizedContent);

        VBox mainContainer = new VBox(0);
        mainContainer.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        mainContainer.getChildren().addAll(titleBar, body);

        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(e -> {
            dragOffset[0] = e.getScreenX() - minimizedStage.getX();
            dragOffset[1] = e.getScreenY() - minimizedStage.getY();
        });
        titleBar.setOnMouseDragged(e -> {
            minimizedStage.setX(e.getScreenX() - dragOffset[0]);
            minimizedStage.setY(e.getScreenY() - dragOffset[1]);
        });

        Scene minimizedScene = new Scene(mainContainer, MINIMIZED_WIDTH, MINIMIZED_HEIGHT);
        minimizedScene.setFill(Color.TRANSPARENT);
        minimizedStage.setScene(minimizedScene);
        minimizedStage.sizeToScene();

        double mainX = managedMainStage.getX();
        double mainY = managedMainStage.getY();

        minimizedStage.setX(mainX + 12);
        minimizedStage.setY(mainY + 60);

        minimizedStage.setOnCloseRequest(e -> {
            e.consume();
            handleExpand();
        });

        minimizedStage.show();
    }
}
