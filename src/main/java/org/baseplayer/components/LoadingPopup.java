package org.baseplayer.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Owned modal loading dialog with progress, ETA, and cancel.
 *
 * <p>Uses a {@link Stage} (not a transparent {@link javafx.stage.Popup}) so Linux
 * compositors do not flash flipped canvas textures into the loading surface.
 * Message and progress can be updated from any thread.
 */
public class LoadingPopup {

  private static final double POPUP_W = 360;
  private static final double POPUP_H = 220;

  private static final String PANEL_STYLE = AppDialog.PANEL_STYLE;

  private static final double PROGRESS_EPSILON = 1e-9;

  private final Stage stage;
  private final VBox root;
  private final Label messageLabel;
  private final Label detailLabel;
  private final ProgressBar progressBar;
  private final ProgressBar detailProgressBar;
  private final Label timeEstimateLabel;

  private Runnable onCancel;
  private Window ownerHint;
  private volatile long startTime;
  private volatile double lastProgress;
  private boolean sessionActive;
  private Timeline elapsedTicker;

  /**
   * While &gt; 0, keep the stage hidden so nested {@link AppDialog}s can receive
   * input. Pair {@link #beginModalSuppress()} / {@link #endModalSuppress()}.
   */
  private int modalSuppressDepth;

  private final ChangeListener<Number> ownerBoundsListener =
      (obs, was, value) -> {
        if (sessionActive && modalSuppressDepth == 0) {
          centerOnOwner();
        }
      };

  private final ChangeListener<Boolean> ownerShowingListener =
      (obs, was, is) -> syncForegroundVisibility();

  public LoadingPopup() {
    stage = new Stage(StageStyle.UTILITY);
    stage.initModality(Modality.WINDOW_MODAL);
    stage.setResizable(false);
    stage.setTitle("Loading");
    stage.setOnCloseRequest(e -> {
      e.consume();
      cancel();
    });

    root = new VBox(8);
    root.setPadding(new Insets(16, 24, 16, 24));
    root.setAlignment(Pos.CENTER);
    root.setStyle(PANEL_STYLE);
    root.setPrefWidth(POPUP_W);
    root.setMinWidth(POPUP_W);
    root.setMaxWidth(POPUP_W);
    root.setMinHeight(Region.USE_PREF_SIZE);
    root.setPrefHeight(Region.USE_COMPUTED_SIZE);
    root.setMaxHeight(420);

    ProgressIndicator spinner = new ProgressIndicator(-1);
    spinner.setPrefSize(22, 22);
    spinner.setStyle("-fx-progress-color: #5a9fd4;");

    double textWidth = POPUP_W - 48 - 32;
    messageLabel = new Label("Loading…");
    messageLabel.setStyle("-fx-text-fill: #d3d3d3; -fx-font-size: 13;");
    messageLabel.setWrapText(true);
    messageLabel.setTextOverrun(OverrunStyle.CLIP);
    messageLabel.setMinWidth(0);
    messageLabel.setPrefWidth(textWidth);
    messageLabel.setMaxWidth(textWidth);

    HBox msgRow = new HBox(10, spinner, messageLabel);
    msgRow.setAlignment(Pos.TOP_LEFT);
    msgRow.setMaxWidth(POPUP_W - 48);
    HBox.setHgrow(messageLabel, Priority.ALWAYS);

    progressBar = createProgressBar(20);

    detailLabel = new Label("");
    detailLabel.setStyle("-fx-text-fill: #a8a8a8; -fx-font-size: 11;");
    detailLabel.setWrapText(true);
    detailLabel.setTextOverrun(OverrunStyle.CLIP);
    detailLabel.setMaxWidth(POPUP_W - 48);
    detailLabel.setVisible(false);
    detailLabel.setManaged(false);

    detailProgressBar = createProgressBar(12);
    detailProgressBar.setVisible(false);
    detailProgressBar.setManaged(false);

    timeEstimateLabel = new Label("Elapsed: — | ETA: —");
    timeEstimateLabel.setStyle("-fx-text-fill: #999999; -fx-font-size: 11;");
    timeEstimateLabel.setWrapText(true);
    timeEstimateLabel.setTextOverrun(OverrunStyle.CLIP);
    timeEstimateLabel.setMaxWidth(POPUP_W - 48);

    Button cancelBtn = new Button("Cancel");
    String cancelNormal =
        "-fx-background-color: #3c3c3c;"
            + "-fx-text-fill: #d3d3d3;"
            + "-fx-font-size: 12;"
            + "-fx-padding: 5 18 5 18;"
            + "-fx-background-radius: 4;"
            + "-fx-cursor: hand;";
    String cancelHover =
        "-fx-background-color: #c0392b;"
            + "-fx-text-fill: white;"
            + "-fx-font-size: 12;"
            + "-fx-padding: 5 18 5 18;"
            + "-fx-background-radius: 4;"
            + "-fx-cursor: hand;";
    cancelBtn.setStyle(cancelNormal);
    cancelBtn.setFocusTraversable(false);
    cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle(cancelHover));
    cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle(cancelNormal));
    cancelBtn.setOnAction(e -> cancel());
    cancelBtn.setCancelButton(true);

    root.getChildren().addAll(
        msgRow, progressBar, detailLabel, detailProgressBar, timeEstimateLabel, cancelBtn);

    Scene scene = new Scene(root);
    scene.setFill(javafx.scene.paint.Color.web("#1e1e1e"));
    stage.setScene(scene);
  }

  private static ProgressBar createProgressBar(double height) {
    ProgressBar bar = new ProgressBar(0.0);
    bar.setPrefHeight(height);
    bar.setMinHeight(height);
    bar.setMaxHeight(height);
    bar.setMaxWidth(Double.MAX_VALUE);
    bar.setStyle(
        "-fx-pref-height: " + height + "px;"
            + "-fx-background-color: linear-gradient(to bottom, #2a2a2a, #1a1a1a);"
            + "-fx-background-radius: 3px;"
            + "-fx-border-color: #555555;"
            + "-fx-border-width: 1px;"
            + "-fx-border-radius: 3px;");
    return bar;
  }

  /**
   * Show the dialog centred on the owner window.
   * Must be called on the JavaFX application thread.
   */
  public void show(String message, Window owner, Runnable onCancel) {
    this.onCancel = onCancel;
    this.ownerHint = owner;
    messageLabel.setText(message);
    clearDetail();
    progressBar.setProgress(0.0);
    lastProgress = 0.0;
    timeEstimateLabel.setText("Elapsed: 0s | ETA: —");
    startTime = System.currentTimeMillis();
    sessionActive = true;
    startElapsedTicker();
    bindOwner(owner);
    syncForegroundVisibility();

    Platform.runLater(() -> {
      styleBarFill(progressBar);
      styleBarFill(detailProgressBar);
    });
  }

  private static void styleBarFill(ProgressBar bar) {
    Node fill = bar.lookup(".bar");
    if (fill != null) {
      fill.setStyle(
          "-fx-background-color: " + org.baseplayer.ui.theme.AppTheme.CHROME_DARK.focusHex() + ";"
              + "-fx-background-radius: 2px;"
              + "-fx-background-insets: 2px;");
    }
  }

  /** Replace the cancel callback after {@link #show} has been called. */
  public void setOnCancel(Runnable onCancel) {
    this.onCancel = onCancel;
  }

  /** Update the displayed message (overall task). Safe to call from any thread. */
  public void setMessage(String message) {
    if (Platform.isFxApplicationThread()) {
      messageLabel.setText(message != null ? message : "");
      sizeToSceneIfShowing();
    } else {
      Platform.runLater(() -> {
        messageLabel.setText(message != null ? message : "");
        sizeToSceneIfShowing();
      });
    }
  }

  /**
   * Secondary status under the overall bar (e.g. ClinVar windows within a chromosome).
   * Empty/null hides the detail row. Safe from any thread.
   */
  public void setDetailMessage(String detail) {
    if (Platform.isFxApplicationThread()) {
      applyDetailMessage(detail);
    } else {
      Platform.runLater(() -> applyDetailMessage(detail));
    }
  }

  private void applyDetailMessage(String detail) {
    String text = detail != null ? detail.trim() : "";
    boolean show = !text.isEmpty();
    detailLabel.setText(text);
    detailLabel.setVisible(show);
    detailLabel.setManaged(show);
    sizeToSceneIfShowing();
  }

  /**
   * Fine-grained progress under the detail message. Negative hides the detail bar.
   * Does not move the overall progress bar or ETA. Safe from any thread.
   */
  public void setDetailProgress(int current, int total) {
    if (total <= 0) {
      setDetailProgressRatio(-1.0);
      return;
    }
    setDetailProgressRatio(Math.min(1.0, (double) current / total));
  }

  public void setDetailProgress(double progress) {
    setDetailProgressRatio(progress);
  }

  public void clearDetail() {
    if (Platform.isFxApplicationThread()) {
      applyDetailMessage("");
      applyDetailProgress(-1.0);
    } else {
      Platform.runLater(() -> {
        applyDetailMessage("");
        applyDetailProgress(-1.0);
      });
    }
  }

  private void setDetailProgressRatio(double progress) {
    if (Platform.isFxApplicationThread()) {
      applyDetailProgress(progress);
    } else {
      Platform.runLater(() -> applyDetailProgress(progress));
    }
  }

  private void applyDetailProgress(double progress) {
    boolean show = progress >= 0.0;
    detailProgressBar.setVisible(show);
    detailProgressBar.setManaged(show);
    if (show) {
      if (Math.abs(detailProgressBar.getProgress() - progress) >= PROGRESS_EPSILON) {
        detailProgressBar.setProgress(progress);
      }
      styleBarFill(detailProgressBar);
    } else {
      detailProgressBar.setProgress(0.0);
    }
    sizeToSceneIfShowing();
  }

  /**
   * Update the overall progress bar. Safe to call from any thread.
   */
  public void setProgress(int current, int total) {
    if (total <= 0) {
      setProgressRatio(-1.0);
      return;
    }
    setProgressRatio(Math.min(1.0, (double) current / total));
  }

  /**
   * Update the overall progress bar directly. Safe to call from any thread.
   *
   * @param progress value between 0.0 and 1.0, or negative for indeterminate
   */
  public void setProgress(double progress) {
    setProgressRatio(progress);
  }

  private void setProgressRatio(double progress) {
    if (Platform.isFxApplicationThread()) {
      applyProgress(progress);
    } else {
      Platform.runLater(() -> applyProgress(progress));
    }
  }

  private void applyProgress(double progress) {
    if (Math.abs(progressBar.getProgress() - progress) >= PROGRESS_EPSILON) {
      progressBar.setProgress(progress);
    }
    lastProgress = progress;
    updateTimeEstimate(progress);
    if (sessionActive && !stage.isShowing() && modalSuppressDepth == 0) {
      syncForegroundVisibility();
    }
  }

  private void sizeToSceneIfShowing() {
    if (sessionActive && stage.isShowing()) {
      stage.sizeToScene();
      centerOnOwner();
    }
  }

  /**
   * Update owner hint and force a foreground sync (e.g. after Variant Manager hide).
   * Must run on the FX thread.
   */
  public void ensureVisibleOver(Window owner) {
    if (owner != null) {
      this.ownerHint = owner;
      bindOwner(owner);
    }
    if (sessionActive) {
      syncForegroundVisibility();
    }
  }

  /**
   * Elapsed always updates while a session is active; ETA needs progress in (0, 1).
   */
  private void updateTimeEstimate(double progress) {
    if (startTime == 0) {
      timeEstimateLabel.setText("Elapsed: — | ETA: —");
      return;
    }

    long elapsedMs = System.currentTimeMillis() - startTime;
    long elapsedSeconds = Math.max(0, elapsedMs / 1000);
    String elapsedStr = formatTime(elapsedSeconds);

    String etaStr = "—";
    if (progress > 0.0 && progress < 1.0) {
      long estimatedTotalMs = (long) (elapsedMs / progress);
      long estimatedRemainingMs = Math.max(0, estimatedTotalMs - elapsedMs);
      etaStr = formatTime(estimatedRemainingMs / 1000);
    } else if (progress >= 1.0) {
      etaStr = "0s";
    }
    timeEstimateLabel.setText("Elapsed: " + elapsedStr + " | ETA: " + etaStr);
  }

  private void startElapsedTicker() {
    stopElapsedTicker();
    elapsedTicker = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
      if (sessionActive) {
        updateTimeEstimate(lastProgress);
      }
    }));
    elapsedTicker.setCycleCount(Timeline.INDEFINITE);
    elapsedTicker.play();
  }

  private void stopElapsedTicker() {
    if (elapsedTicker != null) {
      elapsedTicker.stop();
      elapsedTicker = null;
    }
  }

  private static String formatTime(long seconds) {
    if (seconds < 0) {
      return "—";
    }
    if (seconds < 60) {
      return seconds + "s";
    }
    long minutes = seconds / 60;
    long secs = seconds % 60;
    return minutes + "m " + secs + "s";
  }

  /** Hide the dialog immediately. Safe to call from any thread. */
  public void hide() {
    if (Platform.isFxApplicationThread()) {
      sessionActive = false;
      modalSuppressDepth = 0;
      stopElapsedTicker();
      unbindOwner();
      ownerHint = null;
      if (stage.isShowing()) {
        stage.hide();
      }
    } else {
      Platform.runLater(this::hide);
    }
  }

  /**
   * Temporarily hide without ending the loading session so a modal dialog can
   * receive input. Pair with {@link #endModalSuppress()}.
   * Must be called on the JavaFX thread.
   */
  public void beginModalSuppress() {
    modalSuppressDepth++;
    if (stage.isShowing()) {
      stage.hide();
    }
  }

  /**
   * Undo one {@link #beginModalSuppress()}; restores the dialog when nesting
   * depth returns to zero and a session is still active.
   * Must be called on the JavaFX thread.
   */
  public void endModalSuppress() {
    if (modalSuppressDepth > 0) {
      modalSuppressDepth--;
    }
    if (modalSuppressDepth == 0 && sessionActive) {
      syncForegroundVisibility();
    }
  }

  /**
   * @return {@code true} if a loading session is in progress
   *         (may be hidden while suppressed or owner is unavailable)
   */
  public boolean isShowing() {
    return sessionActive;
  }

  public void syncForegroundVisibility() {
    if (!sessionActive || modalSuppressDepth > 0) {
      if (stage.isShowing()) {
        stage.hide();
      }
      return;
    }

    Window owner = usableOwner(ownerHint);
    if (owner == null) {
      owner = findAnyUsableStage();
    }
    if (owner == null) {
      if (stage.isShowing()) {
        stage.hide();
      }
      return;
    }

    ensureStageOwner(owner);
    if (!stage.isShowing()) {
      stage.sizeToScene();
      centerOnOwner();
      stage.show();
    } else {
      stage.sizeToScene();
      centerOnOwner();
    }
  }

  /** Current {@link Stage#getOwner()}, or null before first show. */
  public Window stageOwner() {
    return stage.getOwner();
  }

  /**
   * Tear down this popup so {@link org.baseplayer.services.LoadingManager} can
   * create a new one owned by a different window (JavaFX forbids rebinding
   * {@link Stage#initOwner}).
   */
  public void dispose() {
    sessionActive = false;
    modalSuppressDepth = 0;
    stopElapsedTicker();
    unbindOwner();
    ownerHint = null;
    onCancel = null;
    if (stage.isShowing()) {
      stage.hide();
    }
    stage.setOnCloseRequest(null);
    stage.close();
  }

  /** {@link Stage#initOwner} may be called only once; prefer the first usable owner. */
  private void ensureStageOwner(Window owner) {
    if (stage.getOwner() != null || owner == null) {
      return;
    }
    stage.initOwner(owner);
  }

  private void centerOnOwner() {
    Window anchor = usableOwner(ownerHint);
    if (anchor == null) {
      anchor = stage.getOwner();
    }
    if (anchor == null) {
      return;
    }
    double w = stage.getWidth() > 0 ? stage.getWidth() : POPUP_W;
    double h = stage.getHeight() > 0 ? stage.getHeight() : POPUP_H;
    stage.setX(anchor.getX() + (anchor.getWidth() - w) / 2);
    stage.setY(anchor.getY() + (anchor.getHeight() - h) / 2);
  }

  private void cancel() {
    sessionActive = false;
    stopElapsedTicker();
    unbindOwner();
    ownerHint = null;
    if (stage.isShowing()) {
      stage.hide();
    }
    if (onCancel != null) {
      onCancel.run();
    }
  }

  private void bindOwner(Window owner) {
    unbindOwner();
    ownerHint = owner;
    if (owner == null) {
      return;
    }
    owner.xProperty().addListener(ownerBoundsListener);
    owner.yProperty().addListener(ownerBoundsListener);
    owner.widthProperty().addListener(ownerBoundsListener);
    owner.heightProperty().addListener(ownerBoundsListener);
    if (owner instanceof Stage ownerStage) {
      ownerStage.showingProperty().addListener(ownerShowingListener);
      ownerStage.iconifiedProperty().addListener(ownerShowingListener);
    }
  }

  private void unbindOwner() {
    Window owner = ownerHint;
    if (owner == null) {
      return;
    }
    owner.xProperty().removeListener(ownerBoundsListener);
    owner.yProperty().removeListener(ownerBoundsListener);
    owner.widthProperty().removeListener(ownerBoundsListener);
    owner.heightProperty().removeListener(ownerBoundsListener);
    if (owner instanceof Stage ownerStage) {
      ownerStage.showingProperty().removeListener(ownerShowingListener);
      ownerStage.iconifiedProperty().removeListener(ownerShowingListener);
    }
  }

  private Window findAnyUsableStage() {
    for (Window window : Window.getWindows()) {
      Stage s = asUsableStage(window);
      if (s != null) {
        return s;
      }
    }
    return null;
  }

  private Window usableOwner(Window owner) {
    return asUsableStage(owner);
  }

  private Stage asUsableStage(Window window) {
    if (!(window instanceof Stage)) {
      return null;
    }
    Stage s = (Stage) window;
    if (s == stage) {
      return null;
    }
    if (!s.isShowing() || s.isIconified()) {
      return null;
    }
    if (s.getStyle() == StageStyle.TRANSPARENT) {
      return null;
    }
    return s;
  }
}
