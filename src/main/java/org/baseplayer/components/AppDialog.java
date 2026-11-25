package org.baseplayer.components;

import java.util.Optional;

import org.baseplayer.MainApp;
import org.baseplayer.ui.theme.AppTheme;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

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
    this(owner, windowTitle, Modality.WINDOW_MODAL);
  }

  protected AppDialog(Window owner, String windowTitle, Modality modality) {
    stage = new Stage(StageStyle.UTILITY);
    stage.initModality(modality != null ? modality : Modality.WINDOW_MODAL);
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

  /**
   * Ensure a modal Stage receives OS keyboard focus, then focus {@code input}.
   * On Linux, {@code Node.requestFocus()} alone often paints a caret while key
   * events still go to the owner window until the user clicks the dialog.
   *
   * <p>Uses {@link Stage#addEventHandler} so existing {@code setOnShown} handlers
   * are preserved.
   */
  public static void focusWhenShown(Stage stage, Node input) {
    focusWhenShown(stage, input, false);
  }

  /** Like {@link #focusWhenShown(Stage, Node)} and optionally select-all text. */
  public static void focusWhenShown(Stage stage, Node input, boolean selectAll) {
    if (stage == null) {
      return;
    }
    stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> scheduleInputFocus(stage, input, selectAll));
  }

  /**
   * Take window focus then input focus (double {@link Platform#runLater} so the
   * window manager has finished activating the stage).
   */
  public static void scheduleInputFocus(Window window, Node input, boolean selectAll) {
    Platform.runLater(() -> {
      if (window instanceof Stage stage) {
        stage.toFront();
        stage.requestFocus();
      } else if (window != null) {
        window.requestFocus();
      }
      Platform.runLater(() -> {
        applyInputFocus(window, input, selectAll);
        // Third pulse: some WMs steal focus once after show.
        Platform.runLater(() -> {
          if (input != null && input.getScene() != null && !input.isFocused()) {
            applyInputFocus(window, input, selectAll);
          }
        });
      });
    });
  }

  private static void applyInputFocus(Window window, Node input, boolean selectAll) {
    if (window instanceof Stage stage) {
      stage.toFront();
      stage.requestFocus();
    } else if (window != null) {
      window.requestFocus();
    }
    if (input == null) {
      return;
    }
    input.requestFocus();
    if (selectAll && input instanceof TextInputControl field) {
      field.selectAll();
    }
  }

  /** Show modally and wait; returns whether the primary button was used. */
  protected boolean showAndWaitConfirmed() {
    confirmed = false;
    stage.sizeToScene();
    focusWhenShown(stage, null, false);
    Window owner = stage.getOwner();
    stage.showAndWait();
    // Linux/GTK: after a WINDOW_MODAL child closes, restore focus to the owner
    // synchronously so a following native FileChooser parents to the frontmost
    // Stage (runLater races and can leave MainApp covering Analysis).
    if (owner instanceof Stage ownerStage && ownerStage.isShowing()) {
      ownerStage.toFront();
      ownerStage.requestFocus();
    } else if (MainApp.stage != null && MainApp.stage.isShowing()) {
      MainApp.stage.toFront();
      MainApp.stage.requestFocus();
    }
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

  /**
   * Text prompt dialog. Returns the trimmed name when confirmed, empty on cancel.
   * Empty field falls back to {@code suggested} when that is non-blank.
   */
  public static Optional<String> prompt(
      Window owner, String windowTitle, String heading, String message, String suggested) {
    AppDialog dialog = new AppDialog(owner, windowTitle != null ? windowTitle : "Name this data");
    if (heading != null && !heading.isBlank()) {
      dialog.addTitle(heading);
    }
    if (message != null && !message.isBlank()) {
      dialog.addMessage(message);
    }
    javafx.scene.control.TextField field = new javafx.scene.control.TextField();
    String placeholder = suggested != null && !suggested.isBlank() ? suggested.trim() : "Dataset name";
    field.setPromptText(placeholder);
    if (suggested != null && !suggested.isBlank()) {
      field.setText(suggested.trim());
    }
    field.setStyle(
        "-fx-background-color: #333; -fx-text-fill: #ddd; -fx-border-color: #555; -fx-font-size: 12px;"
            + "-fx-prompt-text-fill: #777777;");
    dialog.addContent(field);
    dialog.addSecondaryButton("Cancel");
    dialog.addPrimaryButton("OK");
    focusWhenShown(dialog.stage, field, true);
    if (!dialog.showAndWaitConfirmed()) {
      return Optional.empty();
    }
    String name = field.getText() == null ? "" : field.getText().trim();
    if (name.isEmpty() && suggested != null && !suggested.isBlank()) {
      name = suggested.trim();
    }
    if (name.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(name);
  }

  /** Same as {@link #confirm} but returned as {@link Optional} of the confirm choice. */
  public static Optional<Boolean> confirmOptional(
      Window owner, String windowTitle, String heading, String message) {
    return Optional.of(confirm(owner, windowTitle, heading, message));
  }
}
