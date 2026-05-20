package org.baseplayer.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;

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
    private static final double POPUP_H = 115;

    /** Popup is guaranteed to stay visible for at least this many milliseconds. */
    private static final long MIN_SHOW_MS = 400;

    private static final String POPUP_STYLE =
            "-fx-background-color: rgba(30, 30, 30, 0.98);" +
            "-fx-background-radius: 8;"                      +
            "-fx-border-color: #555555;"                     +
            "-fx-border-radius: 8;"                          +
            "-fx-border-width: 1;";

    private final Popup popup;
    private final Label messageLabel;
    private Runnable onCancel;
    private long showTimeMs;

    public LoadingPopup() {
        popup = new Popup();
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);

        VBox root = new VBox(14);
        root.setPadding(new Insets(18, 24, 18, 24));
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

        root.getChildren().addAll(msgRow, cancelBtn);
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
        showTimeMs = System.currentTimeMillis();
        // Centre immediately using fixed dimensions so the popup never flashes at (0,0)
        double x = owner.getX() + (owner.getWidth()  - POPUP_W) / 2;
        double y = owner.getY() + (owner.getHeight() - POPUP_H) / 2;
        popup.show(owner, x, y);
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
     * Hide the popup, respecting the minimum display time.
     * Safe to call from any thread.
     */
    public void hide() {
        if (Platform.isFxApplicationThread()) {
            hideAfterMinTime();
        } else {
            Platform.runLater(this::hideAfterMinTime);
        }
    }

    private void hideAfterMinTime() {
        long remaining = MIN_SHOW_MS - (System.currentTimeMillis() - showTimeMs);
        if (remaining > 0) {
            new Timeline(new KeyFrame(Duration.millis(remaining), e -> popup.hide())).play();
        } else {
            popup.hide();
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
