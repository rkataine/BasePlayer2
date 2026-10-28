package org.baseplayer.components.sidebars;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.io.dragen.DragenAnalysisKind;
import org.baseplayer.io.dragen.DragenFolderScan;
import org.baseplayer.ui.theme.AppTheme;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Settings dialog for opening DRAGEN output folder(s): choose which analysis
 * products to load onto sample track(s), plus optional secondary BigWigs.
 */
public final class OpenDragenDialog {

  /**
   * Selected analyses and optional CNV BigWig basenames (matched per folder
   * when applying the same choice to a batch).
   */
  public record Result(Set<DragenAnalysisKind> kinds, List<File> optionalBigWigs) {
    public Result {
      kinds = kinds != null && !kinds.isEmpty()
          ? EnumSet.copyOf(kinds)
          : EnumSet.noneOf(DragenAnalysisKind.class);
      optionalBigWigs = optionalBigWigs != null ? List.copyOf(optionalBigWigs) : List.of();
    }
  }

  private OpenDragenDialog() {}

  /**
   * Single-folder picker using that scan's available analyses and optionals.
   */
  public static Optional<Result> show(Window owner, DragenFolderScan scan) {
    if (scan == null || scan.available().isEmpty()) {
      return Optional.empty();
    }
    return show(
        owner,
        scan.stem() != null && !scan.stem().isBlank()
            ? scan.stem()
            : scan.directory().getName(),
        1,
        scan.available(),
        scan.previewByKind(),
        scan.optionalCnvBigWigs());
  }

  /**
   * Batch-aware picker. {@code available} / {@code optionalFiles} should be the
   * union across folders; previews may come from the first scan.
   *
   * @param folderCount number of DRAGEN folders the result will apply to
   */
  public static Optional<Result> show(
      Window owner,
      String displayName,
      int folderCount,
      Set<DragenAnalysisKind> available,
      Map<DragenAnalysisKind, String> previews,
      List<File> optionalFiles) {
    if (available == null || available.isEmpty()) {
      return Optional.empty();
    }

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    Window resolved = owner != null ? owner : MainApp.stage;
    if (resolved != null) {
      dialog.initOwner(resolved);
    }
    dialog.setTitle("Open DRAGEN");
    dialog.setResizable(false);

    Label title = new Label("Open DRAGEN");
    title.getStyleClass().add("panel-title");

    String name = displayName != null && !displayName.isBlank() ? displayName : "DRAGEN";
    String hintText = folderCount > 1
        ? "Settings apply to " + folderCount + " selected DRAGEN folders (first: \"" + name
            + "\"). Select analyses to load onto each sample track."
        : "Sample \"" + name + "\". Select analyses to load onto one sample track.";
    Label hint = new Label(hintText);
    hint.getStyleClass().add("value-label");
    hint.setWrapText(true);
    hint.setMaxWidth(420);

    Label analysesHeader = new Label("Analyses");
    analysesHeader.getStyleClass().add("section-header");

    Map<DragenAnalysisKind, String> previewMap =
        previews != null ? previews : Map.of();

    Map<DragenAnalysisKind, CheckBox> boxes = new LinkedHashMap<>();
    VBox analysesBox = new VBox(8);
    for (DragenAnalysisKind kind : DragenAnalysisKind.values()) {
      if (!available.contains(kind)) {
        continue;
      }
      String preview = previewMap.getOrDefault(kind, "");
      String boxText = preview == null || preview.isBlank()
          ? kind.label()
          : kind.label() + " — " + preview;
      CheckBox box = new CheckBox(boxText);
      box.setSelected(false);
      box.setWrapText(true);
      box.setMaxWidth(400);
      box.getStyleClass().add("filter-checkbox");
      boxes.put(kind, box);
      analysesBox.getChildren().add(box);
    }

    // Dedupe optionals by basename so a batch union lists each track once.
    Map<String, File> optionalByName = new LinkedHashMap<>();
    if (optionalFiles != null) {
      for (File file : optionalFiles) {
        if (file != null) {
          optionalByName.putIfAbsent(file.getName(), file);
        }
      }
    }

    List<CheckBox> optionalBoxes = new ArrayList<>();
    VBox optionalBox = new VBox(8);
    Label optionalHeader = null;
    if (!optionalByName.isEmpty()) {
      optionalHeader = new Label("Optional CNV tracks");
      optionalHeader.getStyleClass().add("section-header");
      Label optionalHint = new Label(
          folderCount > 1
              ? "Unchecked by default. Matching files are loaded in each folder when present."
              : "Unchecked by default. Add tn / target counts / extras if needed.");
      optionalHint.getStyleClass().add("value-label");
      optionalHint.setWrapText(true);
      optionalHint.setMaxWidth(420);
      optionalBox.getChildren().add(optionalHint);
      for (Map.Entry<String, File> entry : optionalByName.entrySet()) {
        CheckBox box = new CheckBox(entry.getKey());
        box.setSelected(false);
        box.setWrapText(true);
        box.setMaxWidth(400);
        box.getStyleClass().add("filter-checkbox");
        box.setUserData(entry.getValue());
        CheckBox cnvBox = boxes.get(DragenAnalysisKind.CNV);
        if (cnvBox != null) {
          box.disableProperty().bind(cnvBox.selectedProperty().not());
        }
        optionalBoxes.add(box);
        optionalBox.getChildren().add(box);
      }
    }

    final var chosen = new Object() {
      Result value;
    };
    Button cancel = secondaryButton("Cancel");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> dialog.close());
    Button open = secondaryButton("Open");
    open.setDefaultButton(true);
    open.setOnAction(e -> {
      EnumSet<DragenAnalysisKind> selected = EnumSet.noneOf(DragenAnalysisKind.class);
      for (Map.Entry<DragenAnalysisKind, CheckBox> entry : boxes.entrySet()) {
        if (entry.getValue().isSelected()) {
          selected.add(entry.getKey());
        }
      }
      if (selected.isEmpty()) {
        return;
      }
      List<File> opted = new ArrayList<>();
      if (selected.contains(DragenAnalysisKind.CNV)) {
        for (CheckBox box : optionalBoxes) {
          if (box.isSelected() && box.getUserData() instanceof File file) {
            opted.add(file);
          }
        }
      }
      chosen.value = new Result(selected, opted);
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, open);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12);
    root.getChildren().addAll(title, hint, analysesHeader, analysesBox);
    if (optionalHeader != null) {
      root.getChildren().addAll(optionalHeader, optionalBox);
    }
    root.getChildren().add(buttons);
    root.setPadding(new Insets(16));
    root.getStyleClass().addAll("filter-panel", "dialog-surface");

    int height = Math.min(640, 180 + boxes.size() * 48 + optionalBoxes.size() * 28
        + (optionalHeader != null ? 60 : 0)
        + (folderCount > 1 ? 24 : 0));
    Scene scene = new Scene(root, 460, height);
    applyTheme(scene);
    scene.setFill(AppTheme.chrome().panel());
    dialog.setScene(scene);
    dialog.showAndWait();
    return Optional.ofNullable(chosen.value);
  }

  private static Button secondaryButton(String text) {
    Button button = new Button(text);
    button.getStyleClass().add("secondary-button");
    return button;
  }

  private static void applyTheme(Scene scene) {
    AppTheme.setDark(MainApp.darkMode);
    scene.getStylesheets().clear();
    if (MainApp.darkMode) {
      scene.getStylesheets().add(MainApp.getResource("theme-dark.css").toExternalForm());
    } else {
      scene.getStylesheets().add(MainApp.getResource("theme-light.css").toExternalForm());
    }
    scene.getStylesheets().add(MainApp.getResource("application.css").toExternalForm());
  }
}
