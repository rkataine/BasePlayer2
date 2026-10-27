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
 * Settings dialog for opening a DRAGEN output folder: choose which analysis
 * products to load onto a single sample track, plus optional secondary BigWigs.
 */
public final class OpenDragenDialog {

  /** Selected analyses and any optional CNV BigWigs the user opted into. */
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
   * @return selection result, or empty if cancelled / nothing available
   */
  public static Optional<Result> show(Window owner, DragenFolderScan scan) {
    if (scan == null || scan.available().isEmpty()) {
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

    String stem = scan.stem() != null && !scan.stem().isBlank() ? scan.stem() : scan.directory().getName();
    Label hint = new Label(
        "Sample \"" + stem + "\". Select analyses to load onto one sample track.");
    hint.getStyleClass().add("value-label");
    hint.setWrapText(true);
    hint.setMaxWidth(420);

    Label analysesHeader = new Label("Analyses");
    analysesHeader.getStyleClass().add("section-header");

    Map<DragenAnalysisKind, CheckBox> boxes = new LinkedHashMap<>();
    VBox analysesBox = new VBox(8);
    for (DragenAnalysisKind kind : DragenAnalysisKind.values()) {
      if (!scan.available().contains(kind)) {
        continue;
      }
      String preview = scan.preview(kind);
      String boxText = preview == null || preview.isBlank()
          ? kind.label()
          : kind.label() + " — " + preview;
      CheckBox box = new CheckBox(boxText);
      box.setSelected(true);
      box.setWrapText(true);
      box.setMaxWidth(400);
      box.getStyleClass().add("filter-checkbox");
      boxes.put(kind, box);
      analysesBox.getChildren().add(box);
    }

    List<CheckBox> optionalBoxes = new ArrayList<>();
    List<File> optionalFiles = scan.optionalCnvBigWigs();
    VBox optionalBox = new VBox(8);
    Label optionalHeader = null;
    if (!optionalFiles.isEmpty()) {
      optionalHeader = new Label("Optional CNV tracks");
      optionalHeader.getStyleClass().add("section-header");
      Label optionalHint = new Label("Unchecked by default. Add tn / target counts / extras if needed.");
      optionalHint.getStyleClass().add("value-label");
      optionalHint.setWrapText(true);
      optionalHint.setMaxWidth(420);
      optionalBox.getChildren().add(optionalHint);
      for (File file : optionalFiles) {
        CheckBox box = new CheckBox(file.getName());
        box.setSelected(false);
        box.setWrapText(true);
        box.setMaxWidth(400);
        box.getStyleClass().add("filter-checkbox");
        box.setUserData(file);
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
        + (optionalHeader != null ? 60 : 0));
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
