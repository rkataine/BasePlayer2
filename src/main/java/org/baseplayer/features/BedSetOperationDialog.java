package org.baseplayer.features;

import java.util.Optional;

import org.baseplayer.components.AppDialog;
import org.baseplayer.features.BedSetOperations.AttributeSource;
import org.baseplayer.features.BedSetOperations.Op;
import org.baseplayer.features.BedSetOperations.OverlayChoice;
import org.baseplayer.ui.theme.AppTheme;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Choose Union / Intersect / Subtract and which track’s attributes to keep
 * when overlaying one BED onto another.
 */
public final class BedSetOperationDialog {

  private BedSetOperationDialog() {}

  /**
   * @param baseName existing track display name
   * @param overlayName added file / second track display name
   * @param initial optional previous choice when changing operation
   * @return choice, or empty if cancelled
   */
  public static Optional<OverlayChoice> show(
      Window owner,
      String baseName,
      String overlayName,
      OverlayChoice initial) {
    String a = displayName(baseName, "This track");
    String b = displayName(overlayName, "Added BED");
    Op initialOp = initial != null && initial.op() != null ? initial.op() : Op.INTERSECT;
    AttributeSource initialAttrs =
        initial != null && initial.attributeSource() != null
            ? initial.attributeSource()
            : AttributeSource.FIRST;

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.setTitle("BED overlay");
    dialog.setResizable(false);

    Label title = AppDialog.titleLabel("Overlay BED tracks");
    Label hint = AppDialog.hintLabel(
        "Uses currently loaded features (full file if in memory, otherwise the loaded viewport). "
            + "Subtract is always " + a + " − " + b + ".");

    Label opLabel = sectionLabel("Operation");
    ToggleGroup opGroup = new ToggleGroup();
    RadioButton union = radio("Union", opGroup);
    RadioButton intersect = radio("Intersect", opGroup);
    RadioButton subtract = radio("Subtract", opGroup);
    switch (initialOp) {
      case UNION -> union.setSelected(true);
      case SUBTRACT -> subtract.setSelected(true);
      default -> intersect.setSelected(true);
    }
    VBox opBox = new VBox(4, union, intersect, subtract);

    Label attrLabel = sectionLabel("Annotation attributes (name / strand / color)");
    ToggleGroup attrGroup = new ToggleGroup();
    RadioButton fromFirst = radio("From: " + a, attrGroup);
    RadioButton fromSecond = radio("From: " + b, attrGroup);
    if (initialAttrs == AttributeSource.SECOND) {
      fromSecond.setSelected(true);
    } else {
      fromFirst.setSelected(true);
    }
    VBox attrBox = new VBox(4, fromFirst, fromSecond);

    Runnable syncAttrEnabled = () -> {
      boolean subtractSelected = subtract.isSelected();
      fromFirst.setDisable(subtractSelected);
      fromSecond.setDisable(subtractSelected);
      if (subtractSelected) {
        fromFirst.setSelected(true);
      }
    };
    opGroup.selectedToggleProperty().addListener((obs, o, n) -> syncAttrEnabled.run());
    syncAttrEnabled.run();

    Button cancel = AppDialog.secondaryButton("Cancel");
    cancel.setCancelButton(true);
    Button run = AppDialog.primaryButton("Apply");
    run.setDefaultButton(true);

    final OverlayChoice[] chosen = new OverlayChoice[1];
    cancel.setOnAction(e -> dialog.close());
    run.setOnAction(e -> {
      Op op;
      if (union.isSelected()) {
        op = Op.UNION;
      } else if (subtract.isSelected()) {
        op = Op.SUBTRACT;
      } else {
        op = Op.INTERSECT;
      }
      AttributeSource attrs =
          fromSecond.isSelected() ? AttributeSource.SECOND : AttributeSource.FIRST;
      chosen[0] = new OverlayChoice(op, attrs);
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, run);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, title, hint, opLabel, opBox, attrLabel, attrBox, buttons);
    root.setPadding(new Insets(16));
    root.setStyle(AppDialog.PANEL_STYLE);
    dialog.setScene(new Scene(root, 420, 360));
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]);
  }

  private static String displayName(String name, String fallback) {
    if (name == null || name.isBlank()) {
      return fallback;
    }
    return name.length() > 40 ? name.substring(0, 38) + "…" : name;
  }

  private static Label sectionLabel(String text) {
    Label label = new Label(text);
    label.setStyle(
        "-fx-text-fill: " + AppTheme.chrome().textHex()
            + "; -fx-font-size: 12px; -fx-font-weight: bold;");
    return label;
  }

  private static RadioButton radio(String text, ToggleGroup group) {
    RadioButton button = new RadioButton(text);
    button.setToggleGroup(group);
    button.setStyle("-fx-text-fill: " + AppTheme.chrome().textHex() + "; -fx-font-size: 11px;");
    button.setCursor(Cursor.HAND);
    return button;
  }
}
