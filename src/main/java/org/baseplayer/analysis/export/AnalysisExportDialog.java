package org.baseplayer.analysis.export;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.baseplayer.components.AppDialog;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Multi-select dialog for {@link AnalysisExportKind}. Disabled kinds have no data.
 */
public final class AnalysisExportDialog extends AppDialog {

  private final EnumMap<AnalysisExportKind, CheckBox> boxes = new EnumMap<>(AnalysisExportKind.class);
  private EnumSet<AnalysisExportKind> selection = EnumSet.noneOf(AnalysisExportKind.class);

  private AnalysisExportDialog(Window owner, EnumSet<AnalysisExportKind> available) {
    super(owner, "Export analysis");
    addTitle("Choose output kinds");
    addHint("Select one or more formats. Disabled options have no data in the current session.");

    EnumSet<AnalysisExportKind> avail =
        available != null ? available : EnumSet.noneOf(AnalysisExportKind.class);
    VBox list = new VBox(10);
    list.setPadding(new Insets(4, 0, 0, 0));
    for (AnalysisExportKind kind : AnalysisExportKind.values()) {
      CheckBox box = new CheckBox(kind.label());
      box.setStyle("-fx-text-fill: #e8e8e8; -fx-font-size: 12px;");
      boolean enabled = avail.contains(kind);
      box.setDisable(!enabled);
      box.setSelected(enabled);
      box.setAllowIndeterminate(false);
      javafx.scene.control.Tooltip.install(
          box,
          new javafx.scene.control.Tooltip(
              enabled ? kind.description() : kind.description() + " (no data)"));
      boxes.put(kind, box);
      list.getChildren().add(box);
    }
    addContent(list);

    addSecondaryButton("Cancel");
    Button exportBtn = addPrimaryButton("Export…");
    Runnable syncExportEnabled = () -> exportBtn.setDisable(!anySelected());
    for (CheckBox box : boxes.values()) {
      box.selectedProperty().addListener((obs, o, n) -> syncExportEnabled.run());
    }
    syncExportEnabled.run();
  }

  private boolean anySelected() {
    for (CheckBox box : boxes.values()) {
      if (box != null && !box.isDisable() && box.isSelected()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Show the dialog. Returns selected kinds, or empty if cancelled / nothing selected.
   */
  public static EnumSet<AnalysisExportKind> show(Window owner) {
    EnumSet<AnalysisExportKind> available = AnalysisExportService.availableKinds();
    if (available.isEmpty()) {
      AppDialog.info(
          owner,
          "Export analysis",
          "Nothing to export",
          "Load signal data or run a group comparison first.");
      return EnumSet.noneOf(AnalysisExportKind.class);
    }
    // Heatmaps stay on top of JavaFX chrome — hide for the kind picker. On confirm,
    // leave them hidden so the following native FileChooser is not covered; the
    // export caller restores them after the save dialog.
    org.baseplayer.analysis.ui.SignalPatternHeatmap.prepareForNativeDialog();
    AnalysisExportDialog dialog = new AnalysisExportDialog(owner, available);
    boolean confirmed = dialog.showAndWaitConfirmed();
    if (!confirmed) {
      org.baseplayer.analysis.ui.SignalPatternHeatmap.restoreAfterNativeDialog(owner);
      return EnumSet.noneOf(AnalysisExportKind.class);
    }
    EnumSet<AnalysisExportKind> selected = EnumSet.noneOf(AnalysisExportKind.class);
    for (Map.Entry<AnalysisExportKind, CheckBox> e : dialog.boxes.entrySet()) {
      CheckBox box = e.getValue();
      if (box != null && !box.isDisable() && box.isSelected()) {
        selected.add(e.getKey());
      }
    }
    dialog.selection = selected;
    return selected;
  }

  /** Last confirmed selection (tests / callers that keep the instance). */
  EnumSet<AnalysisExportKind> selection() {
    return selection;
  }
}
