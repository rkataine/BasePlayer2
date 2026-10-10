package org.baseplayer.features;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.components.AppDialog;
import org.baseplayer.io.readers.BedFeatureNameCatalog;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.GeneColors;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
 * Pre-load filter for BED feature names, styled like the gene-types sidebar
 * (color swatches + select/deselect-all checkbox).
 */
public final class BedFeatureFilterDialog {

  /**
   * @param selectedKeys selected entry keys (feature names and/or
   *        {@link BedFeatureNameCatalog#OTHER_NAME})
   * @param listedNames names shown as individual rows (excludes Other)
   * @param loadAll true when every row including Other (if present) is selected
   */
  public record Outcome(Set<String> selectedKeys, Set<String> listedNames, boolean loadAll) {
  }

  private BedFeatureFilterDialog() {}

  public static Optional<Outcome> show(
      Window owner, String trackTitle, BedFeatureNameCatalog.Catalog catalog) {
    return show(owner, trackTitle, catalog, null, "Load");
  }

  /**
   * @param initiallySelected {@code null} selects every catalog entry (default for
   *        first open). Otherwise restores a previous filter selection.
   * @param confirmLabel primary button text ({@code "Load"} or {@code "Apply"})
   */
  public static Optional<Outcome> show(
      Window owner,
      String trackTitle,
      BedFeatureNameCatalog.Catalog catalog,
      Set<String> initiallySelected,
      String confirmLabel) {
    if (catalog == null || catalog.entries().isEmpty()) {
      return Optional.of(new Outcome(null, Set.of(), true));
    }
    List<BedFeatureNameCatalog.Entry> entries = catalog.entries();

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.setTitle("Select features");
    dialog.setResizable(true);

    Label title = AppDialog.titleLabel(
        trackTitle == null || trackTitle.isBlank()
            ? "Which features to load?"
            : "Features in " + trackTitle);
    String hintText = catalog.hasOtherBucket()
        ? "Types from the first "
            + catalog.sampleRows()
            + " rows (file start). % is share of that sample. "
            + "“Other types” covers names that appear later."
        : "Deselect types you do not want. % is share of the scanned sample.";
    Label hint = AppDialog.hintLabel(hintText);

    Set<String> selected = new LinkedHashSet<>();
    if (initiallySelected == null) {
      for (BedFeatureNameCatalog.Entry entry : entries) {
        selected.add(entry.name());
      }
    } else {
      for (BedFeatureNameCatalog.Entry entry : entries) {
        if (initiallySelected.contains(entry.name())) {
          selected.add(entry.name());
        }
      }
      if (selected.isEmpty()) {
        for (BedFeatureNameCatalog.Entry entry : entries) {
          selected.add(entry.name());
        }
      }
    }

    Region selectAllBox = new Region();
    Polyline selectAllCheck = new Polyline();
    StackPane selectAllPane = new StackPane();
    Label selectAllLabel = new Label("All feature types");
    selectAllLabel.setStyle("-fx-font-size: 11px;");
    selectAllLabel.setMouseTransparent(true);

    List<SwatchRow> rows = new ArrayList<>(entries.size());
    VBox list = new VBox(4);
    list.setPadding(new Insets(0, 4, 4, 2));

    Button cancel = AppDialog.secondaryButton("Cancel");
    cancel.setCancelButton(true);
    String primaryLabel =
        confirmLabel == null || confirmLabel.isBlank() ? "Load" : confirmLabel;
    Button load = AppDialog.primaryButton(primaryLabel);
    load.setDefaultButton(true);

    Runnable syncUi = () -> {
      boolean allOn = selected.size() == entries.size();
      applySelectAllCheckboxStyle(selectAllBox, selectAllCheck, allOn);
      selectAllLabel.setTextFill(
          allOn ? AppTheme.canvas().axisInk() : AppTheme.chrome().muted());
      for (SwatchRow row : rows) {
        applySwatchStyle(row, selected.contains(row.key));
      }
      load.setDisable(selected.isEmpty());
    };

    buildSelectAllChrome(selectAllBox, selectAllCheck, selectAllPane);
    selectAllPane.setOnMouseClicked(e -> {
      if (selected.size() == entries.size()) {
        selected.clear();
      } else {
        selected.clear();
        for (BedFeatureNameCatalog.Entry entry : entries) {
          selected.add(entry.name());
        }
      }
      syncUi.run();
    });

    int sampleRows = Math.max(1, catalog.sampleRows());
    for (BedFeatureNameCatalog.Entry entry : entries) {
      SwatchRow row = buildSwatchRow(entry, sampleRows);
      row.row.setOnMouseClicked(e -> {
        if (!selected.add(entry.name())) {
          selected.remove(entry.name());
        }
        syncUi.run();
        e.consume();
      });
      rows.add(row);
      list.getChildren().add(row.row);
    }
    syncUi.run();

    ScrollPane scroll = new ScrollPane(list);
    scroll.setFitToWidth(true);
    scroll.setPrefHeight(Math.min(360, 28 + entries.size() * 22));
    scroll.setStyle(
        "-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
    VBox.setVgrow(scroll, Priority.ALWAYS);

    final Outcome[] chosen = new Outcome[1];
    cancel.setOnAction(e -> dialog.close());
    load.setOnAction(e -> {
      if (selected.isEmpty()) {
        return;
      }
      boolean loadAll = selected.size() == entries.size();
      chosen[0] = new Outcome(
          loadAll ? null : Set.copyOf(selected),
          catalog.listedNames(),
          loadAll);
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, load);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    HBox header = new HBox(6, selectAllPane, selectAllLabel);
    header.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(12, title, hint, header, scroll, buttons);
    root.setPadding(new Insets(16));
    root.setStyle(AppDialog.PANEL_STYLE);
    dialog.setScene(new Scene(root, 440, Math.min(540, 190 + entries.size() * 22)));
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]);
  }

  private static void buildSelectAllChrome(Region box, Polyline check, StackPane pane) {
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
    Tooltip.install(pane, new Tooltip("Select / deselect all"));
  }

  private static SwatchRow buildSwatchRow(BedFeatureNameCatalog.Entry entry, int sampleRows) {
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

    String labelText = entry.displayLabel();
    if (!entry.otherBucket() && entry.count() > 0 && sampleRows > 0) {
      labelText = labelText + "  (" + formatSamplePercent(entry.count(), sampleRows) + ")";
    }
    Label label = new Label(labelText);
    label.setStyle("-fx-font-size: 11px;");
    label.setMaxWidth(Double.MAX_VALUE);
    label.setMouseTransparent(true);
    if (entry.otherBucket()) {
      Tooltip.install(label, new Tooltip(
          "Feature names that did not appear in the first "
              + BedFeatureNameCatalog.SAMPLE_DATA_LINES
              + " data rows"));
    }

    HBox row = new HBox(5, swatchPane, label);
    row.setAlignment(Pos.CENTER_LEFT);
    row.setMaxWidth(Double.MAX_VALUE);
    row.setPadding(new Insets(2, 4, 2, 2));
    row.setCursor(Cursor.HAND);
    row.setOnMouseEntered(e -> applyRowHover(row, true));
    row.setOnMouseExited(e -> applyRowHover(row, false));
    HBox.setHgrow(label, Priority.ALWAYS);

    return new SwatchRow(entry.name(), entry.color(), row, swatch, strike, label);
  }

  /** Share of the scanned sample rows (e.g. of the first 1000). */
  private static String formatSamplePercent(long count, int sampleRows) {
    double pct = 100.0 * count / sampleRows;
    if (pct >= 10) {
      return String.format("%.0f%%", pct);
    }
    if (pct >= 1) {
      return String.format("%.1f%%", pct);
    }
    if (pct > 0) {
      return String.format("%.2f%%", pct);
    }
    return "0%";
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

  private static void applySelectAllCheckboxStyle(Region box, Polyline check, boolean checked) {
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

  private static void applySwatchStyle(SwatchRow row, boolean enabled) {
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

  private static final class SwatchRow {
    final String key;
    final Color color;
    final HBox row;
    final Region swatch;
    final Line strike;
    final Label label;

    SwatchRow(String key, Color color, HBox row, Region swatch, Line strike, Label label) {
      this.key = key;
      this.color = color;
      this.row = row;
      this.swatch = swatch;
      this.strike = strike;
      this.label = label;
    }
  }
}
