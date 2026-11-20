package org.baseplayer;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.baseplayer.controllers.commands.FileCommands;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.UserPreferences.RecentFile;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Start hub: brand + tips on the left, recent projects and actions on the right.
 * Owned modal {@link Stage} — {@link #show} / {@link #dismiss} only.
 */
public final class StartHub {

  private static final double PANEL_WIDTH = 920;
  private static final double PANEL_HEIGHT = 560;

  private static Stage hubStage;
  private static VBox recentList;

  private StartHub() {}

  public static boolean isShowing() {
    return hubStage != null && hubStage.isShowing();
  }

  /** Show the hub (or refresh recent projects if already open). */
  public static void show(Window owner) {
    Runnable run = () -> showOnFxThread(owner);
    if (Platform.isFxApplicationThread()) {
      run.run();
    } else {
      Platform.runLater(run);
    }
  }

  /** Hide the hub if open. Safe from any thread. */
  public static void dismiss() {
    Runnable run = StartHub::dismissOnFxThread;
    if (Platform.isFxApplicationThread()) {
      run.run();
    } else {
      Platform.runLater(run);
    }
  }

  private static void showOnFxThread(Window owner) {
    Window resolved = owner != null ? owner : MainApp.stage;
    if (hubStage != null && hubStage.isShowing()) {
      if (recentList != null) {
        populateRecentList(recentList);
      }
      hubStage.toFront();
      hubStage.requestFocus();
      return;
    }

    if (hubStage != null) {
      hubStage.close();
      hubStage = null;
      recentList = null;
    }

    Stage stage = new Stage(StageStyle.UTILITY);
    stage.initModality(Modality.WINDOW_MODAL);
    if (resolved != null) {
      stage.initOwner(resolved);
    }
    stage.setTitle("BasePlayer 2");
    stage.setResizable(false);

    BorderPane panel = buildPanel();
    panel.setPrefSize(PANEL_WIDTH, PANEL_HEIGHT);
    panel.setMinSize(PANEL_WIDTH, PANEL_HEIGHT);
    panel.getStyleClass().add("start-hub-panel");

    Scene scene = new Scene(panel, PANEL_WIDTH, PANEL_HEIGHT);
    scene.setFill(Color.web("#1a1d21"));
    if (resolved instanceof Stage ownerStage
        && ownerStage.getScene() != null
        && !ownerStage.getScene().getStylesheets().isEmpty()) {
      scene.getStylesheets().addAll(ownerStage.getScene().getStylesheets());
    } else if (MainApp.stage != null && MainApp.stage.getScene() != null) {
      scene.getStylesheets().addAll(MainApp.stage.getScene().getStylesheets());
    }

    stage.setScene(scene);
    stage.setOnCloseRequest(e -> dismissOnFxThread());
    scene.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        dismissOnFxThread();
        e.consume();
      }
    });

    hubStage = stage;
    stage.show();
    stage.centerOnScreen();
    Platform.runLater(panel::requestFocus);
  }

  private static void dismissOnFxThread() {
    Stage stage = hubStage;
    hubStage = null;
    recentList = null;
    if (stage != null) {
      stage.hide();
      stage.close();
    }
  }

  private static BorderPane buildPanel() {
    BorderPane panel = new BorderPane();
    panel.setFocusTraversable(true);

    VBox left = buildLeftColumn();
    left.getStyleClass().add("start-hub-left");
    left.setPrefWidth(PANEL_WIDTH * 0.40);
    left.setMinWidth(PANEL_WIDTH * 0.38);
    left.setMaxWidth(PANEL_WIDTH * 0.42);
    left.setMaxHeight(Double.MAX_VALUE);

    VBox right = buildRightColumn();
    right.getStyleClass().add("start-hub-right");
    right.setMaxHeight(Double.MAX_VALUE);
    HBox.setHgrow(right, Priority.ALWAYS);

    HBox body = new HBox(0, left, right);
    body.setFillHeight(true);

    HBox topBar = new HBox();
    topBar.setAlignment(Pos.CENTER_RIGHT);
    topBar.setPadding(new Insets(8, 10, 0, 10));
    topBar.getStyleClass().add("start-hub-top-bar");
    Button close = new Button("✕");
    close.getStyleClass().add("start-hub-close");
    close.setFocusTraversable(false);
    close.setOnAction(e -> dismiss());
    topBar.getChildren().add(close);

    panel.setTop(topBar);
    panel.setCenter(new VBox(body));
    return panel;
  }

  private static VBox buildLeftColumn() {
    VBox left = new VBox(18);
    left.setPadding(new Insets(36, 28, 28, 32));
    left.setAlignment(Pos.TOP_LEFT);

    HBox brandRow = new HBox(14);
    brandRow.setAlignment(Pos.CENTER_LEFT);

    ImageView logo = new ImageView();
    try {
      Image icon = MainApp.icon != null
          ? MainApp.icon
          : new Image(MainApp.getResource("BasePlayer_icon.png").toExternalForm());
      logo.setImage(icon);
      logo.setFitWidth(56);
      logo.setFitHeight(56);
      logo.setPreserveRatio(true);
      logo.setSmooth(true);
    } catch (Exception ignored) {
      // Brand text still shows without icon
    }

    Text wordmark = new Text("BasePlayer 2");
    wordmark.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
    wordmark.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
        new Stop(0, Color.web("#5ec8d6")),
        new Stop(1, Color.web("#2a8a96"))));

    Text shine = new Text("BasePlayer 2");
    shine.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
    shine.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
        new Stop(0, Color.TRANSPARENT),
        new Stop(0.42, Color.TRANSPARENT),
        new Stop(0.5, Color.rgb(255, 255, 255, 0.55)),
        new Stop(0.58, Color.TRANSPARENT),
        new Stop(1, Color.TRANSPARENT)));
    shine.setBlendMode(BlendMode.ADD);

    StackPane wordmarkStack = new StackPane(wordmark, shine);
    wordmarkStack.setAlignment(Pos.CENTER_LEFT);
    Rectangle clip = new Rectangle();
    clip.widthProperty().bind(wordmarkStack.widthProperty());
    clip.heightProperty().bind(wordmarkStack.heightProperty());
    wordmarkStack.setClip(clip);

    TranslateTransition shimmer = new TranslateTransition(Duration.seconds(1.6), shine);
    shimmer.setFromX(-180);
    shimmer.setToX(220);
    shimmer.setCycleCount(1);
    shimmer.setInterpolator(Interpolator.EASE_BOTH);
    shimmer.setDelay(Duration.millis(300));
    shimmer.play();

    VBox titleBlock = new VBox(4);
    Label tagline = new Label("Explore genomes, alignments, and variants");
    tagline.getStyleClass().add("start-hub-tagline");
    titleBlock.getChildren().addAll(wordmarkStack, tagline);

    brandRow.getChildren().addAll(logo, titleBlock);

    Label tipsHeader = new Label("Getting started");
    tipsHeader.getStyleClass().add("start-hub-section-title");

    VBox tips = new VBox(10);
    tips.getChildren().addAll(
        tipRow("Open a project", "Jump back into a saved project with tracks and variants restored."),
        tipRow("Load BAM & VCF", "Add alignments and variant calls from the File menu anytime."),
        tipRow("Annotate variants", "Run annotate-all to fill the variant table across chromosomes."),
        tipRow("Save your work", "Sessions keep view, filters, and the variant cache together."));

    Region spacer = new Region();
    VBox.setVgrow(spacer, Priority.ALWAYS);

    Label funding = new Label("Funded by the Cancer Foundation Finland");
    funding.getStyleClass().add("start-hub-funding");

    left.getChildren().addAll(brandRow, tipsHeader, tips, spacer, funding);
    return left;
  }

  private static HBox tipRow(String title, String body) {
    HBox row = new HBox(12);
    row.setAlignment(Pos.TOP_LEFT);
    row.getStyleClass().add("start-hub-tip-row");

    Label accent = new Label("▸");
    accent.getStyleClass().add("start-hub-tip-accent");

    VBox text = new VBox(2);
    Label titleLabel = new Label(title);
    titleLabel.getStyleClass().add("start-hub-tip-title");
    Label bodyLabel = new Label(body);
    bodyLabel.getStyleClass().add("start-hub-tip-body");
    bodyLabel.setWrapText(true);
    bodyLabel.setMaxWidth(280);
    text.getChildren().addAll(titleLabel, bodyLabel);

    row.getChildren().addAll(accent, text);
    return row;
  }

  private static VBox buildRightColumn() {
    VBox right = new VBox(14);
    right.setPadding(new Insets(32, 28, 24, 24));
    right.setAlignment(Pos.TOP_LEFT);

    Label projectsHeader = new Label("Projects");
    projectsHeader.getStyleClass().add("start-hub-section-title");

    VBox list = new VBox(4);
    list.getStyleClass().add("start-hub-recent-list");
    recentList = list;
    populateRecentList(list);

    ScrollPane scroll = new ScrollPane(list);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.getStyleClass().add("start-hub-scroll");
    scroll.setMinHeight(200);
    VBox.setVgrow(scroll, Priority.ALWAYS);

    HBox actions = new HBox(10);
    actions.setAlignment(Pos.CENTER_RIGHT);
    actions.getStyleClass().add("start-hub-actions");

    Button openOther = new Button("Open other…");
    openOther.getStyleClass().addAll("start-hub-button", "start-hub-button-secondary");
    openOther.setOnAction(e -> {
      dismiss();
      boolean started = FileCommands.openSessionFromChooser();
      if (!started) {
        show(MainApp.stage);
      }
    });

    Button createEmpty = new Button("New Project");
    createEmpty.getStyleClass().addAll("start-hub-button", "start-hub-button-primary");
    createEmpty.setDefaultButton(true);
    createEmpty.setOnAction(e -> {
      dismiss();
      if (!FileCommands.newProject()) {
        show(MainApp.stage);
      }
    });

    Button quit = new Button("Quit BasePlayer");
    quit.getStyleClass().addAll("start-hub-button", "start-hub-button-secondary");
    quit.setOnAction(e -> {
      if (FileCommands.confirmDiscardIfDirty()) {
        dismiss();
        Platform.exit();
      }
    });

    actions.getChildren().addAll(openOther, createEmpty, quit);
    right.getChildren().addAll(projectsHeader, scroll, actions);
    return right;
  }

  private static void populateRecentList(VBox list) {
    list.getChildren().clear();
    List<RecentFile> projects = UserPreferences.getRecentProjects();
    if (projects.isEmpty()) {
      Label empty = new Label(
          "No recent projects yet.\nSave a project (File → Save Project) to see it here.");
      empty.getStyleClass().add("start-hub-empty");
      empty.setWrapText(true);
      list.getChildren().add(empty);
      return;
    }

    for (RecentFile rf : projects) {
      list.getChildren().add(recentRow(rf, list));
    }
  }

  private static HBox recentRow(RecentFile rf, VBox list) {
    Path path = Path.of(rf.path());
    String name = path.getFileName() != null ? path.getFileName().toString() : rf.path();
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      name = name.substring(0, dot);
    }

    VBox labels = new VBox(2);
    Label nameLabel = new Label(name);
    nameLabel.getStyleClass().add("start-hub-recent-name");
    Label pathLabel = new Label(rf.path());
    pathLabel.getStyleClass().add("start-hub-recent-path");
    labels.getChildren().addAll(nameLabel, pathLabel);
    HBox.setHgrow(labels, Priority.ALWAYS);

    HBox row = new HBox(labels);
    row.setAlignment(Pos.CENTER_LEFT);
    row.getStyleClass().add("start-hub-recent-row");
    row.setPadding(new Insets(10, 12, 10, 12));
    row.setMaxWidth(Double.MAX_VALUE);

    Runnable open = () -> {
      File file = path.toFile();
      if (!file.exists() || !file.isFile()) {
        UserPreferences.removeRecentProject(rf.path());
        populateRecentList(list);
        return;
      }
      dismiss();
      boolean started = FileCommands.openSession(path);
      if (!started) {
        show(MainApp.stage);
      }
    };

    row.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() >= 1) {
        open.run();
        e.consume();
      }
    });
    row.setOnMouseEntered(e -> row.getStyleClass().add("start-hub-recent-row-hover"));
    row.setOnMouseExited(e -> row.getStyleClass().remove("start-hub-recent-row-hover"));

    return row;
  }
}
