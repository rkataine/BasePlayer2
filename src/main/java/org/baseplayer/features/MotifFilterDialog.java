package org.baseplayer.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.components.AppDialog;
import org.baseplayer.features.motif.MotifMatrix;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.FeatureNameColors;
import org.baseplayer.utils.GeneColors;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Motif picker for JASPAR/PFM tracks. Searchable because CORE sets are large.
 */
public final class MotifFilterDialog {

  public record Outcome(Set<String> selectedIds) {}

  private MotifFilterDialog() {}

  public static Optional<Outcome> show(
      Window owner, String trackTitle, List<MotifMatrix> matrices) {
    return show(owner, trackTitle, matrices, null, "Load");
  }

  public static Optional<Outcome> show(
      Window owner,
      String trackTitle,
      List<MotifMatrix> matrices,
      Set<String> initiallySelected,
      String confirmLabel) {
    if (matrices == null || matrices.isEmpty()) {
      return Optional.empty();
    }

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.setTitle("Select motifs");
    dialog.setResizable(true);

    Label title = AppDialog.titleLabel(
        trackTitle == null || trackTitle.isBlank()
            ? "Which motifs to scan?"
            : "Motifs in " + trackTitle);
    Label hint = AppDialog.hintLabel(
        matrices.size()
            + " matrices. Search and select a subset — scanning hundreds at once is slow.");

    Set<String> selected = new LinkedHashSet<>();
    if (initiallySelected != null) {
      for (MotifMatrix m : matrices) {
        if (initiallySelected.contains(m.id())) {
          selected.add(m.id());
        }
      }
    }

    TextField search = new TextField();
    search.setPromptText("Filter by ID or name…");
    search.setStyle("-fx-font-size: 11px;");

    Region selectVisibleBox = new Region();
    Polyline selectVisibleCheck = new Polyline();
    StackPane selectVisiblePane = new StackPane();
    Label selectVisibleLabel = new Label("All matching filter");
    selectVisibleLabel.setStyle("-fx-font-size: 11px;");
    selectVisibleLabel.setMouseTransparent(true);

    Button representatives = AppDialog.secondaryButton("Representatives only");
    Tooltip.install(
        representatives,
        new Tooltip("Select one motif per name (longest matrix; ties keep higher JASPAR version)"));

    Label selectedCount = new Label();
    selectedCount.setStyle("-fx-font-size: 11px;");

    VBox list = new VBox(3);
    list.setPadding(new Insets(0, 4, 4, 2));
    List<Row> rows = new ArrayList<>(matrices.size());
    Map<String, MotifMatrix> byId = new HashMap<>();
    for (MotifMatrix matrix : matrices) {
      byId.put(matrix.id(), matrix);
    }

    Button cancel = AppDialog.secondaryButton("Cancel");
    cancel.setCancelButton(true);
    String primaryLabel =
        confirmLabel == null || confirmLabel.isBlank() ? "Load" : confirmLabel;
    Button load = AppDialog.primaryButton(primaryLabel);
    load.setDefaultButton(true);

    Runnable syncUi = () -> {
      String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
      int visible = 0;
      int visibleSelected = 0;
      for (Row row : rows) {
        boolean match = q.isEmpty()
            || row.id.toLowerCase(Locale.ROOT).contains(q)
            || row.name.toLowerCase(Locale.ROOT).contains(q);
        row.row.setVisible(match);
        row.row.setManaged(match);
        if (match) {
          visible++;
          if (selected.contains(row.id)) {
            visibleSelected++;
          }
        }
        applySwatchStyle(row, selected.contains(row.id));
      }
      boolean allVisibleOn = visible > 0 && visibleSelected == visible;
      applyCheckboxStyle(selectVisibleBox, selectVisibleCheck, allVisibleOn);
      selectVisibleLabel.setTextFill(
          allVisibleOn ? AppTheme.canvas().axisInk() : AppTheme.chrome().muted());
      selectedCount.setText(selected.size() + " selected");
      selectedCount.setTextFill(AppTheme.chrome().muted());
      load.setDisable(selected.isEmpty());
    };

    buildCheckboxChrome(selectVisibleBox, selectVisibleCheck, selectVisiblePane);
    selectVisiblePane.setOnMouseClicked(e -> {
      String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
      boolean allOn = true;
      List<String> visibleIds = new ArrayList<>();
      for (Row row : rows) {
        boolean match = q.isEmpty()
            || row.id.toLowerCase(Locale.ROOT).contains(q)
            || row.name.toLowerCase(Locale.ROOT).contains(q);
        if (match) {
          visibleIds.add(row.id);
          if (!selected.contains(row.id)) {
            allOn = false;
          }
        }
      }
      if (allOn) {
        selected.removeAll(visibleIds);
      } else {
        selected.addAll(visibleIds);
      }
      syncUi.run();
    });

    representatives.setOnAction(e -> {
      String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
      List<MotifMatrix> candidates = new ArrayList<>();
      List<String> visibleIds = new ArrayList<>();
      for (Row row : rows) {
        boolean match = q.isEmpty()
            || row.id.toLowerCase(Locale.ROOT).contains(q)
            || row.name.toLowerCase(Locale.ROOT).contains(q);
        if (!match) {
          continue;
        }
        visibleIds.add(row.id);
        MotifMatrix matrix = byId.get(row.id);
        if (matrix != null) {
          candidates.add(matrix);
        }
      }
      Set<String> reps = representativeIds(candidates);
      selected.removeAll(visibleIds);
      selected.addAll(reps);
      syncUi.run();
    });

    for (MotifMatrix matrix : matrices) {
      Row row = buildRow(matrix);
      row.row.setOnMouseClicked(e -> {
        if (!selected.add(matrix.id())) {
          selected.remove(matrix.id());
        }
        syncUi.run();
        e.consume();
      });
      rows.add(row);
      list.getChildren().add(row.row);
    }

    search.textProperty().addListener((obs, o, n) -> syncUi.run());
    syncUi.run();

    ScrollPane scroll = new ScrollPane(list);
    scroll.setFitToWidth(true);
    scroll.setPrefHeight(360);
    scroll.setStyle(
        "-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
    VBox.setVgrow(scroll, Priority.ALWAYS);

    final Outcome[] chosen = new Outcome[1];
    cancel.setOnAction(e -> dialog.close());
    load.setOnAction(e -> {
      if (selected.isEmpty()) {
        return;
      }
      chosen[0] = new Outcome(Set.copyOf(selected));
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, selectedCount, spacer, cancel, load);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    Region headerSpacer = new Region();
    HBox.setHgrow(headerSpacer, Priority.ALWAYS);
    HBox header = new HBox(6, selectVisiblePane, selectVisibleLabel, headerSpacer, representatives);
    header.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(10, title, hint, search, header, scroll, buttons);
    root.setPadding(new Insets(16));
    root.setStyle(AppDialog.PANEL_STYLE);
    dialog.setScene(new Scene(root, 480, 560));
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]);
  }

  /**
   * One motif per TF name (case-insensitive): longest matrix wins; ties prefer
   * higher JASPAR version suffix ({@code MA0004.2} over {@code MA0004.1}).
   */
  static Set<String> representativeIds(List<MotifMatrix> matrices) {
    Map<String, MotifMatrix> bestByName = new HashMap<>();
    for (MotifMatrix matrix : matrices) {
      if (matrix == null) {
        continue;
      }
      String key = matrix.name().trim().toLowerCase(Locale.ROOT);
      MotifMatrix prev = bestByName.get(key);
      if (prev == null || isBetterRepresentative(matrix, prev)) {
        bestByName.put(key, matrix);
      }
    }
    Set<String> ids = new LinkedHashSet<>();
    for (MotifMatrix matrix : matrices) {
      if (matrix == null) {
        continue;
      }
      MotifMatrix best = bestByName.get(matrix.name().trim().toLowerCase(Locale.ROOT));
      if (best != null && best.id().equals(matrix.id())) {
        ids.add(matrix.id());
      }
    }
    return ids;
  }

  private static boolean isBetterRepresentative(MotifMatrix candidate, MotifMatrix incumbent) {
    int lenCmp = Integer.compare(candidate.length(), incumbent.length());
    if (lenCmp != 0) {
      return lenCmp > 0;
    }
    return jasparVersion(candidate.id()) > jasparVersion(incumbent.id());
  }

  /** Parses trailing {@code .N} from JASPAR IDs; {@code 0} when absent. */
  private static int jasparVersion(String id) {
    if (id == null) {
      return 0;
    }
    int dot = id.lastIndexOf('.');
    if (dot < 0 || dot + 1 >= id.length()) {
      return 0;
    }
    try {
      return Integer.parseInt(id.substring(dot + 1));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static void buildCheckboxChrome(Region box, Polyline check, StackPane pane) {
    box.setMinSize(12, 10);
    box.setPrefSize(12, 10);
    box.setMaxSize(12, 10);
    check.getPoints().setAll(2.4, 5.5, 5.0, 7.8, 9.8, 2.2);
    check.setStrokeWidth(1.6);
    check.setFill(null);
    check.setMouseTransparent(true);
    pane.getChildren().setAll(box, check);
    pane.setMinSize(12, 10);
    pane.setPrefSize(12, 10);
    pane.setMaxSize(12, 10);
    pane.setCursor(Cursor.HAND);
    pane.setAlignment(Pos.CENTER);
    Tooltip.install(pane, new Tooltip("Select / deselect all matching the filter"));
  }

  private static Row buildRow(MotifMatrix matrix) {
    Color color = FeatureNameColors.colorForName(matrix.name());
    Region swatch = new Region();
    swatch.setMinSize(12, 10);
    swatch.setPrefSize(12, 10);
    swatch.setMaxSize(12, 10);
    swatch.setMouseTransparent(true);

    Line strike = new Line(1, 9, 11, 1);
    strike.setStrokeWidth(1.2);
    strike.setMouseTransparent(true);
    strike.setVisible(false);

    StackPane swatchPane = new StackPane(swatch, strike);
    swatchPane.setMinSize(12, 10);
    swatchPane.setPrefSize(12, 10);
    swatchPane.setMaxSize(12, 10);
    swatchPane.setMouseTransparent(true);

    Label label = new Label(matrix.displayLabel() + "  (" + matrix.length() + " bp)");
    label.setStyle("-fx-font-size: 11px;");
    label.setMaxWidth(Double.MAX_VALUE);
    label.setMouseTransparent(true);

    HBox row = new HBox(5, swatchPane, label);
    row.setAlignment(Pos.CENTER_LEFT);
    row.setMaxWidth(Double.MAX_VALUE);
    row.setPadding(new Insets(2, 4, 2, 2));
    row.setCursor(Cursor.HAND);
    row.setOnMouseEntered(e -> applyRowHover(row, true));
    row.setOnMouseExited(e -> applyRowHover(row, false));
    HBox.setHgrow(label, Priority.ALWAYS);

    return new Row(matrix.id(), matrix.name(), color, row, swatch, strike, label);
  }

  private static void applyRowHover(HBox row, boolean hovered) {
    if (hovered) {
      Color hover = AppTheme.chrome().elevated();
      row.setStyle(
          "-fx-background-color: rgba("
              + (int) (hover.getRed() * 255) + ","
              + (int) (hover.getGreen() * 255) + ","
              + (int) (hover.getBlue() * 255) + ",0.85);"
              + "-fx-background-radius: 3;");
    } else {
      row.setStyle("");
    }
  }

  private static void applyCheckboxStyle(Region box, Polyline check, boolean checked) {
    var chrome = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    Color stroke = checked ? canvas.axisInk() : chrome.muted();
    box.setStyle(
        "-fx-background-color: transparent;"
            + "-fx-border-color: " + GeneColors.toHexString(stroke) + ";"
            + "-fx-border-width: 1.2;"
            + "-fx-border-radius: 2;"
            + "-fx-background-radius: 2;");
    check.setStroke(canvas.axisInk());
    check.setVisible(checked);
  }

  private static void applySwatchStyle(Row row, boolean enabled) {
    var chrome = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    Color color = row.color != null ? row.color : Color.GRAY;
    double alpha = enabled ? 0.95 : 0.28;
    String fill = String.format(
        "rgba(%d,%d,%d,%.2f)",
        (int) (color.getRed() * 255),
        (int) (color.getGreen() * 255),
        (int) (color.getBlue() * 255),
        alpha);
    row.swatch.setStyle(
        "-fx-background-color: " + fill + ";"
            + "-fx-background-radius: 2;");
    row.strike.setVisible(!enabled);
    row.strike.setStroke(chrome.muted());
    row.label.setTextFill(enabled ? canvas.axisInk() : chrome.muted());
  }

  private static final class Row {
    final String id;
    final String name;
    final Color color;
    final HBox row;
    final Region swatch;
    final Line strike;
    final Label label;

    Row(
        String id,
        String name,
        Color color,
        HBox row,
        Region swatch,
        Line strike,
        Label label) {
      this.id = id;
      this.name = name;
      this.color = color;
      this.row = row;
      this.swatch = swatch;
      this.strike = strike;
      this.label = label;
    }
  }
}
