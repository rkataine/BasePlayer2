package org.baseplayer.components.sidebars;

import java.util.Optional;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Asks whether to place selected sample tracks in a colored sidebar group,
 * or remove them from their current group(s).
 */
public final class SampleGroupDialog {

  public sealed interface Outcome {
    record Add(String name, Color color) implements Outcome {}
    record Remove() implements Outcome {}
  }

  private SampleGroupDialog() {}

  public static Optional<Outcome> show(
      Window owner, int sampleCount, String suggestedName, Color initialColor) {
    return show(owner, sampleCount, suggestedName, initialColor, false, 0);
  }

  public static Optional<Outcome> show(
      Window owner,
      int sampleCount,
      String suggestedName,
      Color initialColor,
      boolean offerRemove,
      int groupedCount) {
    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.setTitle("Sample group");
    dialog.setResizable(false);

    Label title = new Label(
        sampleCount <= 1
            ? "Add this sample to a group?"
            : "Add " + sampleCount + " samples to a group?");
    title.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 13px; -fx-font-weight: bold;");

    Label hint = new Label("Pick a sidebar color for the group.");
    hint.setStyle("-fx-text-fill: #999999; -fx-font-size: 11px;");
    hint.setWrapText(true);

    TextField nameField = new TextField();
    String placeholder = (suggestedName != null && !suggestedName.isBlank())
        ? suggestedName.trim()
        : "Group name";
    nameField.setPromptText(placeholder);
    nameField.setStyle(
        "-fx-background-color: #333; -fx-text-fill: #ddd; -fx-border-color: #555; -fx-font-size: 12px;"
            + "-fx-prompt-text-fill: #777777;");

    ColorPicker colorPicker = new ColorPicker(
        initialColor != null ? initialColor : Color.web("#4db8ff"));
    colorPicker.setPrefWidth(140);

    Label colorLabel = new Label("Color");
    colorLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
    HBox colorRow = new HBox(10, colorLabel, colorPicker);
    colorRow.setAlignment(Pos.CENTER_LEFT);

    Label nameLabel = new Label("Name");
    nameLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
    VBox nameBox = new VBox(4, nameLabel, nameField);

    Button cancel = new Button("Cancel");
    cancel.setStyle(
        "-fx-background-color: #3c3c3c; -fx-text-fill: #bbbbbb; -fx-font-size: 12px;"
            + "-fx-padding: 6 14 6 14; -fx-border-color: #666; -fx-cursor: hand;");
    cancel.setCancelButton(true);

    Button add = new Button("Add to group");
    add.setStyle(
        "-fx-background-color: #2a4a6a; -fx-text-fill: #d8e8ff; -fx-font-size: 12px;"
            + "-fx-padding: 6 14 6 14; -fx-border-color: #4db8ff; -fx-cursor: hand;");
    add.setDefaultButton(true);

    final Outcome[] chosen = new Outcome[1];
    cancel.setOnAction(e -> dialog.close());
    add.setOnAction(e -> {
      String name = nameField.getText() == null ? "" : nameField.getText().trim();
      Color color = colorPicker.getValue() != null ? colorPicker.getValue() : Color.web("#4db8ff");
      chosen[0] = new Outcome.Add(name, color);
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    if (offerRemove && groupedCount > 0) {
      Button remove = new Button(
          groupedCount == 1
              ? "Remove from group"
              : "Remove " + groupedCount + " from group");
      remove.setStyle(
          "-fx-background-color: #4a3030; -fx-text-fill: #ffd0d0; -fx-font-size: 12px;"
              + "-fx-padding: 6 14 6 14; -fx-border-color: #aa6666; -fx-cursor: hand;");
      remove.setOnAction(e -> {
        chosen[0] = new Outcome.Remove();
        dialog.close();
      });
      buttons.getChildren().add(remove);
    }

    buttons.getChildren().addAll(cancel, add);

    VBox root = new VBox(12, title, hint, nameBox, colorRow, buttons);
    root.setPadding(new Insets(16));
    root.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    dialog.setScene(new Scene(root, offerRemove ? 420 : 340, 220));
    dialog.setOnShown(e -> {
      nameField.requestFocus();
      nameField.deselect();
      nameField.positionCaret(0);
    });
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]);
  }
}
