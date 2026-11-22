package org.baseplayer.components;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.baseplayer.ui.theme.AppTheme;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;

/**
 * Compact “?” control that opens an {@link InfoModal}.
 *
 * <p>Reuse wherever inline help is needed: pass window title, heading, and
 * sections (or a supplier that builds them lazily).
 */
public final class HelpInfoButton {

  private HelpInfoButton() {}

  /**
   * Question-mark button that opens an info modal with the given sections.
   */
  public static Button create(String windowTitle, String heading, List<InfoModal.Section> sections) {
    Objects.requireNonNull(sections, "sections");
    return create(windowTitle, heading, () -> sections);
  }

  /**
   * Question-mark button that builds sections lazily when clicked.
   */
  public static Button create(
      String windowTitle, String heading, Supplier<List<InfoModal.Section>> sectionsSupplier) {
    Objects.requireNonNull(sectionsSupplier, "sectionsSupplier");

    FontIcon icon = new FontIcon(FontAwesomeSolid.QUESTION_CIRCLE);
    icon.setIconSize(14);
    icon.setIconColor(javafx.scene.paint.Color.web(AppTheme.CHROME_DARK.mutedHex()));

    Button button = new Button();
    button.setGraphic(icon);
    button.setFocusTraversable(false);
    button.setStyle(
        "-fx-background-color: transparent;"
            + "-fx-background-insets: 0;"
            + "-fx-background-radius: 0;"
            + "-fx-padding: 2 4 2 4;"
            + "-fx-cursor: hand;");
    button.setTooltip(new Tooltip("About this…"));
    button.setOnAction(e -> {
      Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
      List<InfoModal.Section> sections = sectionsSupplier.get();
      InfoModal.show(
          owner,
          windowTitle != null ? windowTitle : "Help",
          heading,
          sections != null ? sections : List.of());
    });
    return button;
  }
}
