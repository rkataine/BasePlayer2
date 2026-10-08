package org.baseplayer.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Lightweight popup that shows loading progress with a cancel button.
 *
 * <p>Designed for background file loading. Message and progress can be updated
 * after construction from any thread.
 */
public class LoadingPopup {

    /** Approximate popup dimensions used for immediate centering. */
    private static final double POPUP_W = 310;
    private static final double POPUP_H = 230;

    private static final String POPUP_STYLE = AppDialog.PANEL_STYLE;

    private static final double PROGRESS_EPSILON = 1e-9;

    private final Popup popup;
    private final Label messageLabel;
    private final ProgressBar progressBar;
    private final Label timeEstimateLabel;

    private Runnable onCancel;
    private Window ownerHint;
    private volatile long startTime;
    private volatile double lastProgress;
    private boolean sessionActive;
    private Timeline elapsedTicker;
    /**
     * After native dialogs (FileChooser) Linux often leaves no focused Stage.
     * Defer hide so we do not treat that as "app backgrounded" and skip the popup.
     */
    private Timeline unfocusedHideDelay;

    private final ChangeListener<Boolean> focusListener =
            (obs, was, is) -> syncForegroundVisibility();
    private final ChangeListener<Number> boundsListener =
            (obs, was, value) -> {
                if (sessionActive) {
                    syncForegroundVisibility();
                }
            };

    public LoadingPopup() {
        popup = new Popup();
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);

        VBox root = new VBox(8);
        root.setPadding(new Insets(16, 24, 16, 24));
        root.setAlignment(Pos.CENTER);
        root.setStyle(POPUP_STYLE);
        root.setPrefWidth(POPUP_W);
        root.setMinWidth(POPUP_W);
        root.setMaxWidth(POPUP_W);
        root.setPrefHeight(POPUP_H);
        root.setMinHeight(POPUP_H);
        root.setMaxHeight(POPUP_H);

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(22, 22);
        spinner.setStyle("-fx-progress-color: #5a9fd4;");

        messageLabel = new Label("Loading…");
        messageLabel.setStyle("-fx-text-fill: #d3d3d3; -fx-font-size: 13;");
        messageLabel.setWrapText(false);
        messageLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        messageLabel.setEllipsisString("…");
        messageLabel.setMinWidth(0);
        messageLabel.setPrefWidth(210);
        messageLabel.setMaxWidth(210);

        HBox msgRow = new HBox(10, spinner, messageLabel);
        msgRow.setAlignment(Pos.CENTER_LEFT);
        msgRow.setMaxWidth(POPUP_W - 48);
        HBox.setHgrow(messageLabel, Priority.ALWAYS);

        timeEstimateLabel = new Label("Elapsed: — | ETA: —");
        timeEstimateLabel.setStyle("-fx-text-fill: #999999; -fx-font-size: 11;");
        timeEstimateLabel.setWrapText(false);
        timeEstimateLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        timeEstimateLabel.setMaxWidth(POPUP_W - 48);

        progressBar = new ProgressBar(0.0);
        progressBar.setPrefHeight(20);
        progressBar.setMinHeight(20);
        progressBar.setMaxHeight(20);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setStyle(
                "-fx-pref-height: 20px;"
                + "-fx-background-color: linear-gradient(to bottom, #2a2a2a, #1a1a1a);"
                + "-fx-background-radius: 3px;"
                + "-fx-border-color: #555555;"
                + "-fx-border-width: 1px;"
                + "-fx-border-radius: 3px;");

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

        root.getChildren().addAll(msgRow, progressBar, timeEstimateLabel, cancelBtn);
        VBox.setVgrow(msgRow, Priority.NEVER);
        VBox.setVgrow(progressBar, Priority.NEVER);
        VBox.setVgrow(timeEstimateLabel, Priority.NEVER);
        VBox.setVgrow(cancelBtn, Priority.NEVER);
        popup.getContent().add(root);
    }

    /**
     * Show the popup centred on the owner window.
     * Must be called on the JavaFX application thread.
     *
     * @param message  text to display; may be updated later via {@link #setMessage}
     * @param owner    the window to centre over
     * @param onCancel called on the JavaFX thread when the user clicks Cancel
     */
    public void show(String message, Window owner, Runnable onCancel) {
        this.onCancel = onCancel;
        this.ownerHint = owner;
        messageLabel.setText(message);
        progressBar.setProgress(0.0);
        lastProgress = 0.0;
        timeEstimateLabel.setText("Elapsed: 0s | ETA: —");
        startTime = System.currentTimeMillis();
        sessionActive = true;
        startElapsedTicker();
        watchWindows();
        syncForegroundVisibility();

        Platform.runLater(() -> {
            Node bar = progressBar.lookup(".bar");
            if (bar != null) {
                bar.setStyle(
                        "-fx-background-color: " + org.baseplayer.ui.theme.AppTheme.CHROME_DARK.focusHex() + ";"
                        + "-fx-background-radius: 2px;"
                        + "-fx-background-insets: 2px;");
            }
        });
    }

    /** Replace the cancel callback after {@link #show} has been called. */
    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** Update the displayed message. Safe to call from any thread. */
    public void setMessage(String message) {
        if (Platform.isFxApplicationThread()) {
            messageLabel.setText(message);
        } else {
            Platform.runLater(() -> messageLabel.setText(message));
        }
    }

    /**
     * Update the progress bar. Safe to call from any thread.
     *
     * @param current current progress value
     * @param total   total progress value
     */
    public void setProgress(int current, int total) {
        if (total <= 0) {
            setProgressRatio(-1.0);
            return;
        }
        setProgressRatio(Math.min(1.0, (double) current / total));
    }

    /**
     * Update the progress bar directly. Safe to call from any thread.
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

    /** Hide the popup immediately. Safe to call from any thread. */
    public void hide() {
        if (Platform.isFxApplicationThread()) {
            sessionActive = false;
            stopElapsedTicker();
            cancelUnfocusedHide();
            ownerHint = null;
            popup.hide();
        } else {
            Platform.runLater(this::hide);
        }
    }

    /**
     * @return {@code true} if a loading session is in progress
     *         (may be hidden while the app is in the background)
     */
    public boolean isShowing() {
        return sessionActive;
    }

    public void syncForegroundVisibility() {
        if (!sessionActive) {
            cancelUnfocusedHide();
            popup.hide();
            return;
        }
        watchWindows();

        // Hide when the user alt-tabs away — but not on a brief empty-focus gap
        // (common on Linux right after FileChooser), or Excel export never shows.
        if (isAppInForeground()) {
            cancelUnfocusedHide();
        } else {
            scheduleUnfocusedHide();
        }

        Window top = findTopFocusedStage();
        if (top == null) {
            top = usableOwner(ownerHint);
        }
        if (top == null) {
            top = findAnyUsableStage();
        }
        if (top == null) {
            popup.hide();
            return;
        }
        double x = top.getX() + (top.getWidth() - POPUP_W) / 2;
        double y = top.getY() + (top.getHeight() - POPUP_H) / 2;
        if (popup.isShowing() && popup.getOwnerWindow() == top) {
            popup.setX(x);
            popup.setY(y);
            return;
        }
        if (popup.isShowing()) {
            popup.hide();
        }
        popup.show(top, x, y);
        watchWindow(popup);
    }

    private void scheduleUnfocusedHide() {
        if (unfocusedHideDelay != null) {
            return;
        }
        unfocusedHideDelay = new Timeline(new KeyFrame(Duration.millis(750), e -> {
            unfocusedHideDelay = null;
            if (sessionActive && !isAppInForeground()) {
                popup.hide();
            }
        }));
        unfocusedHideDelay.play();
    }

    private void cancelUnfocusedHide() {
        if (unfocusedHideDelay != null) {
            unfocusedHideDelay.stop();
            unfocusedHideDelay = null;
        }
    }

    private void cancel() {
        sessionActive = false;
        stopElapsedTicker();
        cancelUnfocusedHide();
        ownerHint = null;
        popup.hide();
        if (onCancel != null) {
            onCancel.run();
        }
    }

    private void watchWindows() {
        for (Window window : Window.getWindows()) {
            watchWindow(window);
        }
    }

    private void watchWindow(Window window) {
        if (window == null) {
            return;
        }
        window.focusedProperty().removeListener(focusListener);
        window.focusedProperty().addListener(focusListener);
        if (window instanceof Stage) {
            Stage stage = (Stage) window;
            stage.iconifiedProperty().removeListener(focusListener);
            stage.iconifiedProperty().addListener(focusListener);
            stage.xProperty().removeListener(boundsListener);
            stage.yProperty().removeListener(boundsListener);
            stage.widthProperty().removeListener(boundsListener);
            stage.heightProperty().removeListener(boundsListener);
            stage.xProperty().addListener(boundsListener);
            stage.yProperty().addListener(boundsListener);
            stage.widthProperty().addListener(boundsListener);
            stage.heightProperty().addListener(boundsListener);
        }
    }

    /**
     * True when any BasePlayer window (stage or this loading popup) holds focus.
     * False when another application is in front.
     */
    private boolean isAppInForeground() {
        for (Window window : Window.getWindows()) {
            if (!window.isFocused()) {
                continue;
            }
            if (window == popup) {
                return true;
            }
            if (asUsableStage(window) != null) {
                return true;
            }
            if (window instanceof javafx.stage.PopupWindow pw) {
                Window owner = pw.getOwnerWindow();
                if (owner == popup || asUsableStage(owner) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Window findTopFocusedStage() {
        for (Window window : Window.getWindows()) {
            Stage stage = asUsableStage(window);
            if (stage != null && stage.isFocused()) {
                return stage;
            }
        }
        return null;
    }

    private static Window findAnyUsableStage() {
        for (Window window : Window.getWindows()) {
            Stage stage = asUsableStage(window);
            if (stage != null) {
                return stage;
            }
        }
        return null;
    }

    private static Window usableOwner(Window owner) {
        return asUsableStage(owner);
    }

    private static Stage asUsableStage(Window window) {
        if (!(window instanceof Stage)) {
            return null;
        }
        Stage stage = (Stage) window;
        if (!stage.isShowing() || stage.isIconified()) {
            return null;
        }
        if (stage.getStyle() == StageStyle.TRANSPARENT) {
            return null;
        }
        return stage;
    }
}
