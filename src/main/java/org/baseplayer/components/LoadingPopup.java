package org.baseplayer.components;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * A lightweight popup that shows loading progress with a cancel button.
 *
 * <p>Designed to be shown during background file loading operations. The message
 * can be updated after construction (for future extension with per-item status or
 * a time indicator).
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * LoadingPopup popup = new LoadingPopup();
 * popup.show("Loading sample.bam…", ownerWindow, () -> {
 *     future.cancel(true);
 * });
 * // Later, from any thread:
 * popup.hide();
 * }</pre>
 */
public class LoadingPopup {

    /** Approximate popup dimensions used for immediate centering. */
    private static final double POPUP_W = 310;
    private static final double POPUP_H = 200;  // Increased for progress bar and debug controls

    private static final String POPUP_STYLE =
            "-fx-background-color: rgba(30, 30, 30, 0.98);" +
            "-fx-background-radius: 8;"                      +
            "-fx-border-color: #555555;"                     +
            "-fx-border-radius: 8;"                          +
            "-fx-border-width: 1;";

    private final Popup popup;
    private final Label messageLabel;
    private final ProgressBar progressBar;
    private Runnable onCancel;
    private static final double PROGRESS_EPSILON = 1e-9;

    public LoadingPopup() {
        popup = new Popup();
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);

        VBox root = new VBox(8);
        root.setPadding(new Insets(16, 24, 16, 24));
        root.setAlignment(Pos.CENTER);
        root.setStyle(POPUP_STYLE);
        root.setPrefWidth(POPUP_W);
        root.setPrefHeight(POPUP_H);

        // ── Spinner + message ──────────────────────────────────────────────
        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(22, 22);
        spinner.setStyle("-fx-progress-color: #5a9fd4;");

        messageLabel = new Label("Loading…");
        messageLabel.setStyle("-fx-text-fill: #d3d3d3; -fx-font-size: 13;");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(210);

        HBox msgRow = new HBox(10, spinner, messageLabel);
        msgRow.setAlignment(Pos.CENTER_LEFT);

        // ── Progress bar ───────────────────────────────────────────────────
        progressBar = new ProgressBar(0.0);
        progressBar.setPrefHeight(20);
        progressBar.setMinHeight(20);
        progressBar.setMaxHeight(20);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        
        // Style the track/background
        progressBar.setStyle(
            "-fx-pref-height: 20px;" +
            "-fx-background-color: linear-gradient(to bottom, #2a2a2a, #1a1a1a);" +
            "-fx-background-radius: 3px;" +
            "-fx-border-color: #555555;" +
            "-fx-border-width: 1px;" +
            "-fx-border-radius: 3px;"
        );
        // Note: The .bar child styling happens in show() after scene construction

        // ── Cancel button ──────────────────────────────────────────────────
        Button cancelBtn = new Button("Cancel");
        String cancelNormal =
                "-fx-background-color: #3c3c3c;" +
                "-fx-text-fill: #d3d3d3;"        +
                "-fx-font-size: 12;"             +
                "-fx-padding: 5 18 5 18;"        +
                "-fx-background-radius: 4;"      +
                "-fx-cursor: hand;";
        String cancelHover =
                "-fx-background-color: #c0392b;" +
                "-fx-text-fill: white;"          +
                "-fx-font-size: 12;"             +
                "-fx-padding: 5 18 5 18;"        +
                "-fx-background-radius: 4;"      +
                "-fx-cursor: hand;";
        cancelBtn.setStyle(cancelNormal);
        cancelBtn.setFocusTraversable(false);
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle(cancelHover));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle(cancelNormal));
        cancelBtn.setOnAction(e -> cancel());

        root.getChildren().addAll(msgRow, progressBar, cancelBtn);
        
        VBox.setVgrow(msgRow, Priority.NEVER);
        VBox.setVgrow(progressBar, Priority.NEVER);  // Fixed: Don't expand vertically
        VBox.setVgrow(cancelBtn, Priority.NEVER);
        popup.getContent().add(root);
    }

    /**
     * Show the popup centred on the owner window.
     *
     * <p>Must be called on the JavaFX application thread.
     *
     * @param message  text to display; may be updated later via {@link #setMessage}
     * @param owner    the window to centre over
     * @param onCancel called on the JavaFX thread when the user clicks Cancel
     */
    public void show(String message, Window owner, Runnable onCancel) {
        this.onCancel = onCancel;
        messageLabel.setText(message);
        progressBar.setProgress(0.0);  // Start at 0% in determinate mode
        // Centre immediately using fixed dimensions so the popup never flashes at (0,0)
        double x = owner.getX() + (owner.getWidth()  - POPUP_W) / 2;
        double y = owner.getY() + (owner.getHeight() - POPUP_H) / 2;
        popup.show(owner, x, y);
        
        // Style the internal bar node after the scene is constructed
        Platform.runLater(() -> {
            Node bar = progressBar.lookup(".bar");
            if (bar != null) {
                bar.setStyle(
                    "-fx-background-color: #2196F3;" +
                    "-fx-background-radius: 2px;" +
                    "-fx-background-insets: 2px;"
                );
            }
        });
    }

    /**
     * Replace the cancel callback after {@link #show} has been called.
     * Useful when the future reference is only available after showing.
     * Must be called on the JavaFX application thread.
     */
    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /**
     * Update the displayed message. Safe to call from any thread.
     */
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
     * @param current current progress value (0-current)
     * @param total   total progress value
     */
    public void setProgress(int current, int total) {
        if (total <= 0) {
            setProgress(-1.0);
            return;
        }
        double progress = Math.min(1.0, (double) current / total);
        setProgress(progress);
    }

    /**
     * Update the progress bar directly. Safe to call from any thread.
     * 
     * @param progress value between 0.0 and 1.0, or negative for indeterminate
     */
    public void setProgress(double progress) {
        if (Platform.isFxApplicationThread()) {
            if (Math.abs(progressBar.getProgress() - progress) < PROGRESS_EPSILON) {
                return;
            }
            progressBar.setProgress(progress);
        } else {
            Platform.runLater(() -> {
                if (Math.abs(progressBar.getProgress() - progress) < PROGRESS_EPSILON) {
                    return;
                }
                progressBar.setProgress(progress);
            });
        }
    }

    /** Hide the popup immediately. Safe to call from any thread. */
    public void hide() {
        if (Platform.isFxApplicationThread()) {
            popup.hide();
        } else {
            Platform.runLater(popup::hide);
        }
    }

    /** @return {@code true} if the popup is currently visible. */
    public boolean isShowing() {
        return popup.isShowing();
    }

    // ── Private ────────────────────────────────────────────────────────────

    private void cancel() {
        popup.hide();
        if (onCancel != null) {
            onCancel.run();
        }
    }
}
