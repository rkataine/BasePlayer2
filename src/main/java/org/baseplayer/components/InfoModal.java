package org.baseplayer.components;

import java.util.List;
import java.util.Objects;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Scrollable multi-section informational dialog built on {@link AppDialog}.
 *
 * <p>Use for longer help / “?” explanations. Prefer {@link AppDialog#info} for
 * short one-line alerts.
 */
public final class InfoModal extends AppDialog {

  /** One titled block of help text. */
  public record Section(String title, String body) {
    public Section {
      title = title != null ? title : "";
      body = body != null ? body : "";
    }
  }

  private InfoModal(Window owner, String windowTitle) {
    super(owner, windowTitle);
    root.setPrefWidth(520);
    root.setMinWidth(420);
    root.setMaxWidth(640);
    stage.setResizable(true);
  }

  /**
   * Show a modal with an optional heading and arbitrary content node.
   */
  public static void show(Window owner, String windowTitle, String heading, Node content) {
    InfoModal dialog = new InfoModal(owner, windowTitle);
    if (heading != null && !heading.isBlank()) {
      dialog.addTitle(heading);
    }
    dialog.addScrollableContent(content);
    dialog.addPrimaryButton("OK");
    dialog.showAndWaitConfirmed();
  }

  /**
   * Show a modal with an optional heading and a list of titled sections.
   */
  public static void show(
      Window owner, String windowTitle, String heading, List<Section> sections) {
    Objects.requireNonNull(sections, "sections");
    VBox stack = new VBox(12);
    stack.setFillWidth(true);
    for (Section section : sections) {
      if (section == null) {
        continue;
      }
      VBox block = new VBox(4);
      block.setFillWidth(true);
      if (!section.title().isBlank()) {
        block.getChildren().add(titleLabel(section.title()));
      }
      if (!section.body().isBlank()) {
        block.getChildren().add(messageLabel(section.body()));
      }
      stack.getChildren().add(block);
    }
    show(owner, windowTitle, heading, stack);
  }

  private void addScrollableContent(Node content) {
    if (content == null) {
      return;
    }
    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.setPadding(new Insets(0));
    scroll.setStyle(
        "-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
    scroll.setPrefViewportHeight(360);
    scroll.setMaxHeight(480);
    VBox.setVgrow(scroll, Priority.ALWAYS);
    addContent(scroll);
  }
}
