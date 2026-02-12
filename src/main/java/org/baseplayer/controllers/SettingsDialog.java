package org.baseplayer.controllers;

import org.baseplayer.MainApp;
import org.baseplayer.draw.DrawFunctions;
import org.baseplayer.io.Settings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Settings dialog — lets the user tune display thresholds and rendering parameters.
 * Built programmatically (no FXML) for a compact, dark-themed VS Code look.
 */
public class SettingsDialog {

  private final Stage dialog;
  private final Settings settings = Settings.get();

  // Spinners for numeric values
  private final Spinner<Integer> maxReadViewSpinner;
  private final Spinner<Integer> maxCoverageViewSpinner;
  private final Spinner<Integer> sampledPointsSpinner;
  private final Spinner<Double>  coverageFractionSpinner;
  private final Spinner<Double>  readGapSpinner;
  private final Spinner<Double>  minReadHeightSpinner;
  private final CheckBox         smoothSmallFilesCheck;

  public SettingsDialog() {
    dialog = new Stage(StageStyle.DECORATED);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initOwner(MainApp.stage);
    dialog.setTitle("Settings");
    dialog.setResizable(true);

    // ── Build spinners with current values ──────────────────────────────

    maxReadViewSpinner        = intSpinner(10_000, 500_000, settings.getMaxReadViewLength(), 10_000);
    maxCoverageViewSpinner    = intSpinner(100_000, 50_000_000, settings.getMaxCoverageViewLength(), 100_000);
    sampledPointsSpinner      = intSpinner(5, 500, settings.getSampledCoveragePoints(), 5);
    coverageFractionSpinner   = dblSpinner(0.05, 1.0, settings.getCoverageFraction(), 0.05);
    readGapSpinner            = dblSpinner(0.0, 10.0, settings.getReadGap(), 0.5);
    minReadHeightSpinner      = dblSpinner(1.0, 20.0, settings.getMinReadHeight(), 0.5);
    smoothSmallFilesCheck     = new CheckBox("Apply smoothing to sampled coverage");
    smoothSmallFilesCheck.setSelected(settings.isSmoothSmallFiles());

    // ── Layout ──────────────────────────────────────────────────────────

    VBox root = new VBox(12);
    root.setPadding(new Insets(16));
    root.getStyleClass().add("settings-dialog");

    // Section: Zoom thresholds
    root.getChildren().add(sectionLabel("Zoom Thresholds"));
    GridPane zoomGrid = createGrid();
    int row = 0;
    addRow(zoomGrid, row++, "Read view max (bp):", maxReadViewSpinner,
        "Max view length for showing individual reads");
    addRow(zoomGrid, row++, "Coverage view max (bp):", maxCoverageViewSpinner,
        "Above this, sampled coverage is used");
    root.getChildren().add(zoomGrid);

    root.getChildren().add(new Separator());

    // Section: Sampled coverage
    root.getChildren().add(sectionLabel("Sampled Coverage"));
    GridPane sampledGrid = createGrid();
    row = 0;
    addRow(sampledGrid, row++, "Sample points:", sampledPointsSpinner,
        "Number of sparse sample positions across the chromosome");
    sampledGrid.add(smoothSmallFilesCheck, 0, row, 2, 1);
    GridPane.setMargin(smoothSmallFilesCheck, new Insets(4, 0, 0, 0));
    root.getChildren().add(sampledGrid);

    root.getChildren().add(new Separator());

    // Section: Read rendering
    root.getChildren().add(sectionLabel("Read Rendering"));
    GridPane readGrid = createGrid();
    row = 0;
    addRow(readGrid, row++, "Coverage fraction:", coverageFractionSpinner,
        "Fraction of track height used for coverage area");
    addRow(readGrid, row++, "Read gap (px):", readGapSpinner,
        "Vertical spacing between read rows");
    addRow(readGrid, row++, "Min read height (px):", minReadHeightSpinner,
        "Minimum height for individual reads");
    root.getChildren().add(readGrid);

    root.getChildren().add(new Separator());

    // Buttons
    HBox buttons = new HBox(10);
    buttons.setAlignment(Pos.CENTER_RIGHT);
    Button applyBtn  = new Button("Apply");
    Button resetBtn  = new Button("Reset Defaults");
    Button cancelBtn = new Button("Cancel");
    applyBtn.setDefaultButton(true);
    applyBtn.setOnAction(e -> applySettings());
    resetBtn.setOnAction(e -> resetToDefaults());
    cancelBtn.setOnAction(e -> dialog.close());
    buttons.getChildren().addAll(resetBtn, cancelBtn, applyBtn);
    HBox.setHgrow(resetBtn, Priority.NEVER);
    root.getChildren().add(buttons);

    Scene scene = new Scene(root);
    // Apply dark theme if active
    if (MainApp.stage != null && MainApp.stage.getScene() != null) {
      scene.getStylesheets().addAll(MainApp.stage.getScene().getStylesheets());
    }
    dialog.setScene(scene);
    dialog.sizeToScene();
  }

  public void show() {
    // Center on main stage
    if (MainApp.stage != null) {
      dialog.setX(MainApp.stage.getX() + (MainApp.stage.getWidth() - 420) / 2);
      dialog.setY(MainApp.stage.getY() + (MainApp.stage.getHeight() - 500) / 2);
    }
    dialog.setAlwaysOnTop(true);
    dialog.toFront();
    dialog.showAndWait();
  }

  // ── Apply & Reset ───────────────────────────────────────────────────────

  private void applySettings() {
    settings.setMaxReadViewLength(maxReadViewSpinner.getValue());
    settings.setMaxCoverageViewLength(maxCoverageViewSpinner.getValue());
    settings.setSampledCoveragePoints(sampledPointsSpinner.getValue());
    settings.setCoverageFraction(coverageFractionSpinner.getValue());
    settings.setReadGap(readGapSpinner.getValue());
    settings.setMinReadHeight(minReadHeightSpinner.getValue());
    settings.setSmoothSmallFiles(smoothSmallFilesCheck.isSelected());

    // Trigger redraw so changes are visible immediately
    DrawFunctions.update.set(!DrawFunctions.update.get());
    dialog.close();
  }

  private void resetToDefaults() {
    maxReadViewSpinner.getValueFactory().setValue(Settings.DEF_MAX_READ_VIEW_LENGTH);
    maxCoverageViewSpinner.getValueFactory().setValue(Settings.DEF_MAX_COVERAGE_VIEW_LENGTH);
    sampledPointsSpinner.getValueFactory().setValue(Settings.DEF_SAMPLED_COVERAGE_POINTS);
    coverageFractionSpinner.getValueFactory().setValue(Settings.DEF_COVERAGE_FRACTION);
    readGapSpinner.getValueFactory().setValue(Settings.DEF_READ_GAP);
    minReadHeightSpinner.getValueFactory().setValue(Settings.DEF_MIN_READ_HEIGHT);
    smoothSmallFilesCheck.setSelected(Settings.DEF_SMOOTH_SMALL_FILES);
  }

  // ── Helper methods ────────────────────────────────────────────────────

  private static Label sectionLabel(String text) {
    Label l = new Label(text);
    l.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
    return l;
  }

  private static GridPane createGrid() {
    GridPane g = new GridPane();
    g.setHgap(12);
    g.setVgap(8);
    return g;
  }

  private static void addRow(GridPane grid, int row, String labelText,
                              javafx.scene.control.Control control, String tooltip) {
    Label label = new Label(labelText);
    label.setMinWidth(180);
    control.setPrefWidth(150);
    if (tooltip != null && !tooltip.isEmpty()) {
      javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(tooltip);
      label.setTooltip(tip);
      control.setTooltip(tip);
    }
    grid.add(label, 0, row);
    grid.add(control, 1, row);
  }

  private static Spinner<Integer> intSpinner(int min, int max, int value, int step) {
    Spinner<Integer> s = new Spinner<>(
        new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value, step));
    s.setEditable(true);
    s.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
      if (!isFocused) commitSpinnerValue(s);
    });
    return s;
  }

  private static Spinner<Double> dblSpinner(double min, double max, double value, double step) {
    Spinner<Double> s = new Spinner<>(
        new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step));
    s.setEditable(true);
    s.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
      if (!isFocused) commitSpinnerValue(s);
    });
    return s;
  }

  /** Force-commit typed text in an editable spinner. */
  @SuppressWarnings("unchecked")
  private static <T> void commitSpinnerValue(Spinner<T> spinner) {
    try {
      spinner.getValueFactory().setValue(
          spinner.getValueFactory().getConverter().fromString(spinner.getEditor().getText()));
    } catch (Exception ignored) {
      // revert to current value on parse error
      spinner.getEditor().setText(spinner.getValueFactory().getConverter().toString(spinner.getValue()));
    }
  }
}
