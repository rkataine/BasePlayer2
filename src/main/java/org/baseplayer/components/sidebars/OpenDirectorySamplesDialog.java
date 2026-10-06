package org.baseplayer.components.sidebars;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.io.SampleFileKind;
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
 * Compact settings dialog for opening a directory (or batch of subdirectories)
 * of sample files. Types are taken from the first directory only.
 */
public final class OpenDirectorySamplesDialog {

  public record Result(
      Set<SampleFileKind> types,
      boolean separateTracks,
      boolean addToGroup,
      boolean applyToAll) {}

  private OpenDirectorySamplesDialog() {}

  /**
   * @param firstDirName name shown in the hint
   * @param directoryCount total directories in the batch
   * @param availableTypes types present in the first directory
   */
  public static Optional<Result> show(
      Window owner,
      String firstDirName,
      int directoryCount,
      Set<SampleFileKind> availableTypes) {
    if (availableTypes == null || availableTypes.isEmpty()) {
      return Optional.empty();
    }

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    Window resolved = owner != null ? owner : MainApp.stage;
    if (resolved != null) {
      dialog.initOwner(resolved);
    }
    dialog.setTitle("Open directory");
    dialog.setResizable(false);

    Label title = new Label("Open directory");
    title.getStyleClass().add("panel-title");

    String hintText = directoryCount > 1
        ? "Settings for \"" + firstDirName + "\". Optionally apply the same settings to the other "
            + (directoryCount - 1) + " selected directory(ies)."
        : "Settings for \"" + firstDirName + "\".";
    Label hint = new Label(hintText);
    hint.getStyleClass().add("value-label");
    hint.setWrapText(true);
    hint.setMaxWidth(360);

    Label typesHeader = new Label("Sample types");
    typesHeader.getStyleClass().add("section-header");

    Map<SampleFileKind, CheckBox> typeBoxes = new LinkedHashMap<>();
    VBox typesBox = new VBox(6);
    for (SampleFileKind kind : SampleFileKind.values()) {
      if (!availableTypes.contains(kind)) {
        continue;
      }
      CheckBox box = new CheckBox(kind.label());
      box.setSelected(true);
      box.getStyleClass().add("filter-checkbox");
      typeBoxes.put(kind, box);
      typesBox.getChildren().add(box);
    }

    CheckBox separateTracks = new CheckBox("Open as separate tracks");
    separateTracks.setSelected(true);
    separateTracks.getStyleClass().add("filter-checkbox");

    CheckBox addToGroup = new CheckBox("Add to group (directory name)");
    addToGroup.setSelected(true);
    addToGroup.getStyleClass().add("filter-checkbox");

    CheckBox applyToAll = new CheckBox("Apply settings to other directories");
    applyToAll.setSelected(true);
    applyToAll.getStyleClass().add("filter-checkbox");
    applyToAll.setVisible(directoryCount > 1);
    applyToAll.setManaged(directoryCount > 1);

    final Result[] chosen = new Result[1];
    Button cancel = secondaryButton("Cancel");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> dialog.close());
    Button open = secondaryButton("Open");
    open.setDefaultButton(true);
    open.setOnAction(e -> {
      EnumSet<SampleFileKind> selected = EnumSet.noneOf(SampleFileKind.class);
      for (Map.Entry<SampleFileKind, CheckBox> entry : typeBoxes.entrySet()) {
        if (entry.getValue().isSelected()) {
          selected.add(entry.getKey());
        }
      }
      if (selected.isEmpty()) {
        return;
      }
      chosen[0] = new Result(
          selected,
          separateTracks.isSelected(),
          addToGroup.isSelected(),
          directoryCount <= 1 || applyToAll.isSelected());
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, open);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12,
        title,
        hint,
        typesHeader,
        typesBox,
        separateTracks,
        addToGroup,
        applyToAll,
        buttons);
    root.setPadding(new Insets(16));
    root.getStyleClass().add("filter-panel");

    Scene scene = new Scene(root, 400, directoryCount > 1 ? 340 : 300);
    applyTheme(scene);
    dialog.setScene(scene);
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]);
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
