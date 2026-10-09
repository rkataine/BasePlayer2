package org.baseplayer;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.controllers.commands.FileCommands;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.UserPreferences.RecentFile;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Cubase-style start hub: brand + tips on the left, recent projects and
 * primary actions on the right. Overlay on the main stage after splash.
 */
public final class StartHub {

  private static final double PANEL_WIDTH = 920;
  private static final double PANEL_HEIGHT = 560;
  private static final String BACKDROP_STYLE = "start-hub-backdrop";

  private static StackPane activeOverlay;
  private static VBox activeRecentList;
  /**
   * When true, {@link #show} is a no-op. Set by {@link #dismiss()} so project load /
   * splash finish cannot put the hub back up. Cleared only by {@link #allowShow()}.
   */
  private static volatile boolean suppressed;

  private StartHub() {}

  public static boolean isShowing() {
    return activeOverlay != null && activeOverlay.getParent() != null;
  }

  public static boolean isSuppressed() {
    return suppressed;
  }

  /** Allow the hub to be shown again (File → Open Project). */
  public static void allowShow() {
    suppressed = false;
  }

  public static void show(Stage stage) {
    if (suppressed) {
      return;
    }
    if (stage == null || stage.getScene() == null) {
      return;
    }
    Runnable show = () -> showOnFxThread(stage);
    if (Platform.isFxApplicationThread()) {
      show.run();
    } else {
      Platform.runLater(show);
    }
  }

  private static void showOnFxThread(Stage stage) {
    if (suppressed || stage == null || stage.getScene() == null) {
      return;
    }
    // Drop any orphan backdrops before deciding whether to refresh or build.
    removeAllHubOverlays(stage.getScene().getRoot());

    if (activeOverlay != null && activeOverlay.getParent() != null) {
      if (activeRecentList != null) {
        populateRecentList(activeRecentList);
      }
      activeOverlay.toFront();
      return;
    }
    activeOverlay = null;
    activeRecentList = null;

    Parent sceneRoot = stage.getScene().getRoot();
    if (!(sceneRoot instanceof AnchorPane rootPane)) {
      System.err.println("StartHub: expected AnchorPane scene root, got "
          + (sceneRoot == null ? "null" : sceneRoot.getClass().getName()));
      return;
    }

    StackPane backdrop = new StackPane();
    backdrop.getStyleClass().add(BACKDROP_STYLE);
    backdrop.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    AnchorPane.setTopAnchor(backdrop, 0.0);
    AnchorPane.setRightAnchor(backdrop, 0.0);
    AnchorPane.setBottomAnchor(backdrop, 0.0);
    AnchorPane.setLeftAnchor(backdrop, 0.0);

    BorderPane panel = buildPanel(backdrop);
    panel.setMaxSize(PANEL_WIDTH, PANEL_HEIGHT);
    panel.setPrefSize(PANEL_WIDTH, PANEL_HEIGHT);
    panel.getStyleClass().add("start-hub-panel");
    StackPane.setAlignment(panel, Pos.CENTER);

    backdrop.getChildren().add(panel);
    rootPane.getChildren().add(backdrop);
    activeOverlay = backdrop;

    if (!stage.isMaximized()) {
      stage.setMaximized(true);
    }

    backdrop.setOpacity(0);
    panel.setScaleX(0.96);
    panel.setScaleY(0.96);

    FadeTransition fadeIn = new FadeTransition(Duration.millis(280), backdrop);
    fadeIn.setToValue(1);
    ScaleTransition scale = new ScaleTransition(Duration.millis(280), panel);
    scale.setToX(1);
    scale.setToY(1);
    scale.setInterpolator(Interpolator.EASE_OUT);
    ParallelTransition intro = new ParallelTransition(fadeIn, scale);
    // If dismiss raced the intro, do not leave a half-faded blocker.
    intro.setOnFinished(e -> {
      if (suppressed || activeOverlay != backdrop) {
        forceRemoveNode(backdrop);
      }
    });
    intro.play();

    Platform.runLater(panel::requestFocus);
  }

  /**
   * Remove the hub immediately so it can never stay up and block the UI.
   * Suppresses further {@link #show} until {@link #allowShow()}. Safe from any thread.
   */
  public static void dismiss() {
    suppressed = true;
    Runnable hide = StartHub::dismissOnFxThread;
    if (Platform.isFxApplicationThread()) {
      hide.run();
    } else {
      Platform.runLater(hide);
    }
  }

  private static void dismissOnFxThread() {
    StackPane overlay = activeOverlay;
    activeOverlay = null;
    activeRecentList = null;

    if (overlay != null) {
      forceRemoveNode(overlay);
    }

    Stage stage = MainApp.stage;
    if (stage != null && stage.getScene() != null) {
      removeAllHubOverlays(stage.getScene().getRoot());
    }
  }

  private static void forceRemoveNode(Node overlay) {
    if (overlay == null) {
      return;
    }
    overlay.setMouseTransparent(true);
    overlay.setVisible(false);
    Parent parent = overlay.getParent();
    if (parent instanceof Pane pane) {
      pane.getChildren().remove(overlay);
    }
  }

  /** Remove every hub backdrop under {@code root} (handles orphans after races). */
  private static void removeAllHubOverlays(Parent root) {
    if (!(root instanceof Pane pane)) {
      return;
    }
    List<Node> toRemove = new ArrayList<>();
    for (Node child : pane.getChildren()) {
      if (child.getStyleClass().contains(BACKDROP_STYLE)) {
        toRemove.add(child);
      }
    }
    if (!toRemove.isEmpty()) {
      for (Node n : toRemove) {
        n.setMouseTransparent(true);
        n.setVisible(false);
      }
      pane.getChildren().removeAll(toRemove);
    }
    if (activeOverlay != null && activeOverlay.getParent() == null) {
      activeOverlay = null;
      activeRecentList = null;
    }
  }

  private static BorderPane buildPanel(StackPane backdrop) {
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
    HBox.setHgrow(right, Priority.ALWAYS);

    HBox topBar = new HBox();
    topBar.setAlignment(Pos.CENTER_RIGHT);
    topBar.setPadding(new Insets(8, 10, 0, 10));
    topBar.getStyleClass().add("start-hub-top-bar");
    Button close = new Button("✕");
    close.getStyleClass().add("start-hub-close");
    close.setFocusTraversable(false);
    close.setOnAction(e -> {
      dismiss();
      allowShow(); // user closed manually — File → Open Project may show again
    });
    topBar.getChildren().add(close);

    VBox chrome = new VBox(body);
    panel.setTop(topBar);
    panel.setCenter(chrome);

    panel.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        dismiss();
        allowShow();
        e.consume();
      }
    });

    backdrop.setOnMouseClicked(e -> {
      if (e.getTarget() == backdrop) {
        dismiss();
        allowShow();
        e.consume();
      }
    });

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

    VBox recentList = new VBox(4);
    recentList.getStyleClass().add("start-hub-recent-list");
    activeRecentList = recentList;
    populateRecentList(recentList);

    ScrollPane scroll = new ScrollPane(recentList);
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
      // Hide immediately so chooser / load never leave a blocker behind.
      dismiss();
      boolean started = FileCommands.openSessionFromChooser();
      if (!started) {
        allowShow();
        show(MainApp.stage);
      }
    });

    Button createEmpty = new Button("New Project");
    createEmpty.getStyleClass().addAll("start-hub-button", "start-hub-button-primary");
    createEmpty.setDefaultButton(true);
    createEmpty.setOnAction(e -> {
      dismiss();
      // Same path as File → New Project (clear tracks / session), not overlay-only.
      if (!FileCommands.newProject()) {
        allowShow();
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

  private static void populateRecentList(VBox recentList) {
    recentList.getChildren().clear();
    List<RecentFile> projects = UserPreferences.getRecentProjects();
    if (projects.isEmpty()) {
      Label empty = new Label("No recent projects yet.\nSave a project (File → Save Project) to see it here.");
      empty.getStyleClass().add("start-hub-empty");
      empty.setWrapText(true);
      recentList.getChildren().add(empty);
      return;
    }

    for (RecentFile rf : projects) {
      recentList.getChildren().add(recentRow(rf, recentList));
    }
  }

  private static HBox recentRow(RecentFile rf, VBox recentList) {
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
        populateRecentList(recentList);
        return;
      }
      // Dismiss first so dirty-confirm / load / paint errors never leave a blocker.
      dismiss();
      boolean started = FileCommands.openSession(path);
      if (!started) {
        allowShow();
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
