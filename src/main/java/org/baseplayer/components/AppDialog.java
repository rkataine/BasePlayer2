package org.baseplayer.components;

import java.util.Optional;

import org.baseplayer.MainApp;
import org.baseplayer.ui.theme.AppTheme;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Base class for dark-themed modal info / confirm dialogs.
 *
 * <p>Uses the same panel chrome as {@link InfoPopup} and {@link LoadingPopup}
 * so every confirmation and message dialog matches the app UI.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * boolean ok = AppDialog.confirm(
 *     owner,
 *     "Reload required",
 *     "Load INS variants?",
 *     "These types are not in the current cache…");
 * }</pre>
 *
 * <p>Subclass for richer dialogs, or use {@link #confirm} / {@link #info}.
 */
public class AppDialog {

  /** Shared panel style (matches InfoPopup / LoadingPopup). */
  public static final String PANEL_STYLE =
      "-fx-background-color: " + AppTheme.CHROME_DARK.surfaceHex() + ";"
          + "-fx-background-radius: 8;"
          + "-fx-border-color: " + AppTheme.CHROME_DARK.strokeHex() + ";"
          + "-fx-border-radius: 8;"
          + "-fx-border-width: 1;";

  public static final String TITLE_STYLE =
      "-fx-text-fill: #e8e8e8; -fx-font-size: 14px; -fx-font-weight: bold;";

  public static final String MESSAGE_STYLE =
      "-fx-text-fill: #b0b0b0; -fx-font-size: 12px; -fx-wrap-text: true;";

  public static final String HINT_STYLE =
      "-fx-text-fill: " + AppTheme.CHROME_DARK.mutedHex() + "; -fx-font-size: 11px; -fx-wrap-text: true;";

  private static final String PRIMARY_STYLE =
      "-fx-background-color: #2a4a6a;"
          + "-fx-text-fill: #d8e8ff;"
          + "-fx-font-size: 12px;"
          + "-fx-padding: 6 16 6 16;"
          + "-fx-background-radius: 4;"
          + "-fx-border-color: #4db8ff;"
          + "-fx-border-radius: 4;"
          + "-fx-cursor: hand;";

  private static final String SECONDARY_STYLE =
      "-fx-background-color: " + AppTheme.CHROME_DARK.controlHex() + ";"
          + "-fx-text-fill: " + AppTheme.CHROME_DARK.textMutedHex() + ";"
          + "-fx-font-size: 12px;"
          + "-fx-padding: 6 16 6 16;"
          + "-fx-background-radius: 4;"
          + "-fx-border-color: #666666;"
          + "-fx-border-radius: 4;"
          + "-fx-cursor: hand;";

  protected final Stage stage;
  protected final VBox root;
  protected final VBox body;
  protected final HBox buttonBar;

  private boolean confirmed;

  protected AppDialog(Window owner, String windowTitle) {
    stage = new Stage(StageStyle.UTILITY);
    stage.initModality(Modality.WINDOW_MODAL);
    Window resolvedOwner = owner != null ? owner : MainApp.stage;
    if (resolvedOwner != null) {
      stage.initOwner(resolvedOwner);
    }
    stage.setTitle(windowTitle != null ? windowTitle : "");
    stage.setResizable(false);

    body = new VBox(10);
    body.setFillWidth(true);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    buttonBar = new HBox(8, spacer);
    buttonBar.setAlignment(Pos.CENTER_RIGHT);

    root = new VBox(14, body, buttonBar);
    root.setPadding(new Insets(18, 20, 16, 20));
    root.setStyle(PANEL_STYLE);
    root.setPrefWidth(420);
    root.setMinWidth(360);
    root.setMaxWidth(520);

    Scene scene = new Scene(root);
    scene.setFill(javafx.scene.paint.Color.web("#1e1e1e"));
    stage.setScene(scene);
  }

  /** Add a bold heading under the window title. */
  protected Label addTitle(String text) {
    Label label = titleLabel(text);
    body.getChildren().add(label);
    return label;
  }

  /** Add wrapped body / explanation text. */
  protected Label addMessage(String text) {
    Label label = messageLabel(text);
    body.getChildren().add(label);
    return label;
  }

  /** Add smaller secondary hint text. */
  protected Label addHint(String text) {
    Label label = hintLabel(text);
    body.getChildren().add(label);
    return label;
  }

  /** Insert arbitrary content above the button bar. */
  protected void addContent(Node node) {
    if (node != null) {
      body.getChildren().add(node);
    }
  }

  /**
   * Primary action button (confirm / OK / Apply). Closing with this sets
   * {@link #isConfirmed()} to true.
   */
  protected Button addPrimaryButton(String text) {
    Button button = primaryButton(text);
    button.setDefaultButton(true);
    button.setOnAction(e -> {
      confirmed = true;
      stage.close();
    });
    buttonBar.getChildren().add(button);
    return button;
  }

  /** Secondary / cancel button. Closes without confirming. */
  protected Button addSecondaryButton(String text) {
    Button button = secondaryButton(text);
    button.setCancelButton(true);
    button.setOnAction(e -> {
      confirmed = false;
      stage.close();
    });
    buttonBar.getChildren().add(button);
    return button;
  }

  /** Show modally and wait; returns whether the primary button was used. */
  protected boolean showAndWaitConfirmed() {
    confirmed = false;
    stage.sizeToScene();
    stage.showAndWait();
    return confirmed;
  }

  protected boolean isConfirmed() {
    return confirmed;
  }

  protected void close() {
    stage.close();
  }

  // ── Shared widget factories ───────────────────────────────────────────────

  public static Label titleLabel(String text) {
    Label label = new Label(text);
    label.setStyle(TITLE_STYLE);
    label.setWrapText(true);
    label.setMaxWidth(Double.MAX_VALUE);
    return label;
  }

  public static Label messageLabel(String text) {
    Label label = new Label(text);
    label.setStyle(MESSAGE_STYLE);
    label.setWrapText(true);
    label.setMaxWidth(Double.MAX_VALUE);
    return label;
  }

  public static Label hintLabel(String text) {
    Label label = new Label(text);
    label.setStyle(HINT_STYLE);
    label.setWrapText(true);
    label.setMaxWidth(Double.MAX_VALUE);
    return label;
  }

  public static Button primaryButton(String text) {
    Button button = new Button(text);
    button.setStyle(PRIMARY_STYLE);
    return button;
  }

  public static Button secondaryButton(String text) {
    Button button = new Button(text);
    button.setStyle(SECONDARY_STYLE);
    return button;
  }

  // ── Convenience entry points ──────────────────────────────────────────────

  /**
   * Confirmation dialog. Returns {@code true} if the user chose the confirm action.
   */
  public static boolean confirm(
      Window owner, String windowTitle, String heading, String message) {
    return confirm(owner, windowTitle, heading, message, "OK", "Cancel");
  }

  /**
   * Confirmation dialog with custom button labels.
   */
  public static boolean confirm(
      Window owner,
      String windowTitle,
      String heading,
      String message,
      String confirmLabel,
      String cancelLabel) {
    AppDialog dialog = new AppDialog(owner, windowTitle);
    if (heading != null && !heading.isBlank()) {
      dialog.addTitle(heading);
    }
    if (message != null && !message.isBlank()) {
      dialog.addMessage(message);
    }
    dialog.addSecondaryButton(cancelLabel != null ? cancelLabel : "Cancel");
    dialog.addPrimaryButton(confirmLabel != null ? confirmLabel : "OK");
    return dialog.showAndWaitConfirmed();
  }

  /** Informational dialog with a single dismiss button. */
  public static void info(Window owner, String windowTitle, String heading, String message) {
    AppDialog dialog = new AppDialog(owner, windowTitle);
    if (heading != null && !heading.isBlank()) {
      dialog.addTitle(heading);
    }
    if (message != null && !message.isBlank()) {
      dialog.addMessage(message);
    }
    dialog.addPrimaryButton("OK");
    dialog.showAndWaitConfirmed();
  }

  /** Same as {@link #confirm} but returned as {@link Optional} of the confirm choice. */
  public static Optional<Boolean> confirmOptional(
      Window owner, String windowTitle, String heading, String message) {
    return Optional.of(confirm(owner, windowTitle, heading, message));
  }
}
