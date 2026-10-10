package org.baseplayer.features;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.components.AppDialog;
import org.baseplayer.ui.theme.AppTheme;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Pick BED tracks for Union / Intersect / Subtract. Creates no tracks itself —
 * returns the ordered selection for {@link BedSetOperations#apply}.
 */
public final class BedSetOperationDialog {

  private BedSetOperationDialog() {}

  /**
   * @param candidates fully materialized BED tracks (order preserved)
   * @param preselected tracks to check initially (may be empty)
   * @return ordered selected tracks, or empty if cancelled
   */
  public static Optional<List<BedTrack>> show(
      Window owner,
      BedSetOperations.Op op,
      List<BedTrack> candidates,
      Set<BedTrack> preselected) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    Set<BedTrack> initial = preselected != null ? preselected : Set.of();

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.setTitle(op.label());
    dialog.setResizable(true);

    Label title = AppDialog.titleLabel(op.label() + " tracks");
    String hintText = switch (op) {
      case UNION ->
          "Select at least two BED tracks. Uses currently loaded features "
              + "(full file if in memory, otherwise the loaded viewport).";
      case INTERSECT ->
          "Select at least two BED tracks. Result is overlap of currently loaded features.";
      case SUBTRACT ->
          "Select at least two tracks. First checked is the base (A − B − C…). "
              + "Uses currently loaded features only.";
    };
    Label hint = AppDialog.hintLabel(hintText);

    VBox list = new VBox(4);
    list.setPadding(new Insets(0, 4, 4, 2));
    List<CheckBox> boxes = new ArrayList<>(candidates.size());
    for (BedTrack bed : candidates) {
      CheckBox box = new CheckBox(bed.getName());
      box.setSelected(initial.contains(bed));
      box.setStyle("-fx-text-fill: " + AppTheme.chrome().textHex() + "; -fx-font-size: 11px;");
      box.setUserData(bed);
      box.setCursor(Cursor.HAND);
      boxes.add(box);
      list.getChildren().add(box);
    }

    // For subtract, put preselected tracks first in display order if present.
    if (op == BedSetOperations.Op.SUBTRACT && !initial.isEmpty()) {
      list.getChildren().clear();
      List<CheckBox> ordered = new ArrayList<>();
      for (BedTrack bed : candidates) {
        if (initial.contains(bed)) {
          for (CheckBox box : boxes) {
            if (box.getUserData() == bed) {
              ordered.add(box);
              break;
            }
          }
        }
      }
      for (CheckBox box : boxes) {
        if (!ordered.contains(box)) {
          ordered.add(box);
        }
      }
      boxes.clear();
      boxes.addAll(ordered);
      list.getChildren().addAll(boxes);
    }

    Button cancel = AppDialog.secondaryButton("Cancel");
    cancel.setCancelButton(true);
    Button run = AppDialog.primaryButton(op.label());
    run.setDefaultButton(true);

    Runnable sync = () -> {
      int n = 0;
      for (CheckBox box : boxes) {
        if (box.isSelected()) {
          n++;
        }
      }
      run.setDisable(n < 2);
    };
    for (CheckBox box : boxes) {
      box.selectedProperty().addListener((obs, o, n) -> sync.run());
    }
    sync.run();

    ScrollPane scroll = new ScrollPane(list);
    scroll.setFitToWidth(true);
    scroll.setPrefHeight(Math.min(320, 28 + candidates.size() * 26));
    scroll.setStyle(
        "-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
    VBox.setVgrow(scroll, Priority.ALWAYS);

    @SuppressWarnings("unchecked")
    final List<BedTrack>[] chosen = new List[1];
    cancel.setOnAction(e -> dialog.close());
    run.setOnAction(e -> {
      List<BedTrack> selected = new ArrayList<>();
      for (CheckBox box : boxes) {
        if (box.isSelected() && box.getUserData() instanceof BedTrack bed) {
          selected.add(bed);
        }
      }
      if (selected.size() < 2) {
        return;
      }
      chosen[0] = selected;
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, run);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, title, hint, scroll, buttons);
    root.setPadding(new Insets(16));
    root.setStyle(AppDialog.PANEL_STYLE);
    dialog.setScene(new Scene(root, 420, Math.min(480, 160 + candidates.size() * 26)));
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]).map(List::copyOf);
  }

  /** Collect all BED tracks from a list (displayed order). */
  public static List<BedTrack> bedTracks(Iterable<? extends Track> tracks) {
    List<BedTrack> out = new ArrayList<>();
    if (tracks == null) {
      return out;
    }
    Set<BedTrack> seen = new LinkedHashSet<>();
    for (Track track : tracks) {
      if (track instanceof BedTrack bed && seen.add(bed)) {
        out.add(bed);
      }
    }
    return out;
  }
}
