package org.baseplayer.components.sidebars;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.components.AppDialog;
import org.baseplayer.samples.SampleTag;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Quick dialog to place selected sample tracks in a colored group and/or
 * assign fixed tags (mother/father/child/parental/marker).
 */
public final class SampleGroupDialog {

  public sealed interface Outcome {
    record Add(String name, Color color, Set<SampleTag> tags) implements Outcome {}
    record Remove() implements Outcome {}
  }

  private SampleGroupDialog() {}

  public static Optional<Outcome> show(
      Window owner, int sampleCount, String suggestedName, Color initialColor) {
    return show(owner, sampleCount, suggestedName, initialColor, false, 0, Set.of());
  }

  public static Optional<Outcome> show(
      Window owner,
      int sampleCount,
      String suggestedName,
      Color initialColor,
      boolean offerRemove,
      int groupedCount) {
    return show(owner, sampleCount, suggestedName, initialColor, offerRemove, groupedCount, Set.of());
  }

  public static Optional<Outcome> show(
      Window owner,
      int sampleCount,
      String suggestedName,
      Color initialColor,
      boolean offerRemove,
      int groupedCount,
      Set<SampleTag> initialTags) {
    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.setTitle("New sample group");
    dialog.setResizable(false);

    Label title = AppDialog.titleLabel(
        sampleCount <= 1
            ? "Group / tag this sample"
            : "Group / tag " + sampleCount + " samples");

    Label hint = AppDialog.hintLabel(
        "Name a group for the selected samples and optionally set tags "
            + "(Mother, Father, Child, Parental, Marker).");
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

    Label nameLabel = new Label("Group name");
    nameLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
    VBox nameBox = new VBox(4, nameLabel, nameField);

    Label tagsLabel = new Label("Tags");
    tagsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
    EnumSet<SampleTag> selectedTags = EnumSet.noneOf(SampleTag.class);
    if (initialTags != null) {
      selectedTags.addAll(initialTags);
    }
    VBox tagRows = new VBox(6);
    for (SampleTag tag : SampleTag.values()) {
      tagRows.getChildren().add(tagRoleRow(tag, selectedTags));
    }
    VBox tagsBox = new VBox(6, tagsLabel, tagRows);

    Button cancel = AppDialog.secondaryButton("Cancel");
    cancel.setCancelButton(true);

    Button add = AppDialog.primaryButton("Apply");
    add.setDefaultButton(true);

    final Outcome[] chosen = new Outcome[1];
    cancel.setOnAction(e -> dialog.close());
    add.setOnAction(e -> {
      String name = nameField.getText() == null ? "" : nameField.getText().trim();
      Color color = colorPicker.getValue() != null ? colorPicker.getValue() : Color.web("#4db8ff");
      chosen[0] = new Outcome.Add(name, color, EnumSet.copyOf(selectedTags));
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

    VBox root = new VBox(12, title, hint, nameBox, colorRow, tagsBox, buttons);
    root.setPadding(new Insets(16));
    root.setStyle(AppDialog.PANEL_STYLE);
    dialog.setScene(new Scene(root, 480, 520));
    dialog.setOnShown(e -> {
      nameField.requestFocus();
      nameField.deselect();
      nameField.positionCaret(0);
    });
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]);
  }

  private static HBox tagRoleRow(SampleTag tag, EnumSet<SampleTag> selectedTags) {
    CheckBox box = new CheckBox();
    box.setSelected(selectedTags.contains(tag));
    box.setStyle("-fx-text-fill: #ddd;");
    box.selectedProperty().addListener((obs, o, on) -> {
      if (Boolean.TRUE.equals(on)) {
        selectedTags.add(tag);
      } else {
        selectedTags.remove(tag);
      }
    });

    Circle swatch = new Circle(5, tag.color());
    Label name = new Label(tag.displayName());
    name.setStyle("-fx-text-fill: #eeeeee; -fx-font-size: 12px; -fx-font-weight: bold;");
    Label desc = new Label(tag.description());
    desc.setWrapText(true);
    desc.setMaxWidth(360);
    desc.setStyle("-fx-text-fill: #999999; -fx-font-size: 11px;");
    VBox text = new VBox(1, name, desc);
    HBox.setHgrow(text, Priority.ALWAYS);

    HBox row = new HBox(10, box, swatch, text);
    row.setAlignment(Pos.TOP_LEFT);
    row.setPadding(new Insets(4, 6, 4, 6));
    row.setStyle(
        "-fx-background-color: #2a2a2a; -fx-background-radius: 6; -fx-cursor: hand;");
    row.setOnMouseClicked(e -> {
      if (e.getTarget() != box) {
        box.setSelected(!box.isSelected());
      }
    });
    return row;
  }
}
