package org.baseplayer.components.sidebars;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.ui.theme.AppTheme;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Themed directory browser that lists only folders (never files) and lets the
 * user check multiple directories to open.
 *
 * <p>JavaFX has no multi-select {@code DirectoryChooser}; this dialog is the
 * app substitute. Use Shift+click to check a range, or checkboxes / Check all.
 */
public final class SelectSampleDirectoriesDialog {

  private SelectSampleDirectoriesDialog() {}

  public static Optional<List<File>> show(Window owner) {
    Window resolved = owner != null ? owner : MainApp.stage;

    File start = UserPreferences.getLastDirectory("DIR");
    if (start == null || !start.isDirectory()) {
      start = UserPreferences.getLastDirectory("BAM");
    }
    if (start == null || !start.isDirectory()) {
      start = new File(System.getProperty("user.home", "."));
    }

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (resolved != null) {
      dialog.initOwner(resolved);
    }
    dialog.setTitle("Open directories");
    dialog.setMinWidth(560);
    dialog.setMinHeight(420);

    Label title = new Label("Open directories");
    title.getStyleClass().add("panel-title");
    Label hint = new Label(
        "Folders only. Check folders to open, or click then Shift+click for a range. "
            + "Double-click enters a folder.");
    hint.getStyleClass().add("value-label");
    hint.setWrapText(true);
    hint.setMaxWidth(540);

    Label pathLabel = new Label();
    pathLabel.getStyleClass().add("section-header");
    pathLabel.setWrapText(true);
    pathLabel.setMaxWidth(540);

    Set<String> checkedPaths = new LinkedHashSet<>();
    ObservableList<DirRow> rows = FXCollections.observableArrayList();
    final File[] currentDir = {start};
    final Runnable[] refreshOpen = {() -> {}};
    final int[] anchorIndex = {-1};

    ListView<DirRow> list = new ListView<>(rows);
    list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    list.setCellFactory(lv -> new ListCell<>() {
      private final CheckBox check = new CheckBox();
      private final Label name = new Label();
      private final HBox box = new HBox(10, check, name);
      private DirRow bound;

      {
        box.setAlignment(Pos.CENTER_LEFT);
        name.getStyleClass().add("value-label");
        check.getStyleClass().add("filter-checkbox");
        check.setOnAction(ev -> {
          if (bound == null) {
            return;
          }
          setChecked(bound, check.isSelected(), checkedPaths);
          int idx = rows.indexOf(bound);
          if (idx >= 0) {
            anchorIndex[0] = idx;
            list.getSelectionModel().clearAndSelect(idx);
          }
          refreshOpen[0].run();
        });
      }

      @Override
      protected void updateItem(DirRow item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          bound = null;
          setText(null);
          setGraphic(null);
          return;
        }
        bound = item;
        String label = item.dir.getName();
        name.setText(label == null || label.isEmpty() ? item.dir.getAbsolutePath() : label);
        check.setSelected(checkedPaths.contains(item.dir.getAbsolutePath()));
        item.checked.set(check.isSelected());
        setText(null);
        setGraphic(box);
      }
    });

    list.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY) {
        return;
      }
      // Checkbox handles its own toggle.
      if (isInsideCheckBox(e.getTarget())) {
        return;
      }

      int index = indexAtMouse(list, e.getY());
      if (index < 0 || index >= rows.size()) {
        return;
      }

      if (e.getClickCount() == 2) {
        navigateTo(rows.get(index).dir, currentDir, pathLabel, rows, checkedPaths, list);
        anchorIndex[0] = -1;
        return;
      }

      if (e.isShiftDown() && anchorIndex[0] >= 0) {
        int from = Math.min(anchorIndex[0], index);
        int to = Math.max(anchorIndex[0], index);
        list.getSelectionModel().clearSelection();
        list.getSelectionModel().selectRange(from, to + 1);
        for (int i = from; i <= to; i++) {
          setChecked(rows.get(i), true, checkedPaths);
        }
        list.refresh();
        refreshOpen[0].run();
        return;
      }

      DirRow row = rows.get(index);
      boolean next = !checkedPaths.contains(row.dir.getAbsolutePath());
      setChecked(row, next, checkedPaths);
      list.getSelectionModel().clearAndSelect(index);
      anchorIndex[0] = index;
      list.refresh();
      refreshOpen[0].run();
    });

    Button up = secondaryButton("Up");
    up.setOnAction(e -> {
      File parent = currentDir[0].getParentFile();
      if (parent != null && parent.isDirectory()) {
        navigateTo(parent, currentDir, pathLabel, rows, checkedPaths, list);
        anchorIndex[0] = -1;
      }
    });

    Button selectVisible = secondaryButton("Check all visible");
    selectVisible.setOnAction(e -> {
      for (DirRow row : rows) {
        setChecked(row, true, checkedPaths);
      }
      list.getSelectionModel().selectAll();
      if (!rows.isEmpty()) {
        anchorIndex[0] = 0;
      }
      list.refresh();
      refreshOpen[0].run();
    });

    Button clearChecks = secondaryButton("Clear checks");
    clearChecks.setOnAction(e -> {
      checkedPaths.clear();
      for (DirRow row : rows) {
        row.checked.set(false);
      }
      list.getSelectionModel().clearSelection();
      anchorIndex[0] = -1;
      list.refresh();
      refreshOpen[0].run();
    });

    final List<List<File>> resultHolder = new ArrayList<>(1);
    Button cancel = secondaryButton("Cancel");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> dialog.close());
    Button open = secondaryButton("Open");
    open.setDefaultButton(true);
    open.setDisable(true);
    open.setOnAction(e -> {
      if (checkedPaths.isEmpty()) {
        return;
      }
      List<File> selected = new ArrayList<>();
      for (String path : checkedPaths) {
        File dir = new File(path);
        if (dir.isDirectory()) {
          selected.add(dir);
        }
      }
      if (selected.isEmpty()) {
        return;
      }
      selected.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      UserPreferences.setLastDirectory("DIR", currentDir[0]);
      resultHolder.clear();
      resultHolder.add(selected);
      dialog.close();
    });
    refreshOpen[0] = () -> open.setDisable(checkedPaths.isEmpty());

    HBox navRow = new HBox(8, up, selectVisible, clearChecks);
    navRow.setAlignment(Pos.CENTER_LEFT);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, open);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(10, title, hint, pathLabel, list, navRow, buttons);
    VBox.setVgrow(list, Priority.ALWAYS);
    root.setPadding(new Insets(14));
    root.getStyleClass().add("filter-panel");

    Scene scene = new Scene(root, 580, 460);
    applyTheme(scene);
    dialog.setScene(scene);

    navigateTo(start, currentDir, pathLabel, rows, checkedPaths, list);
    refreshOpen[0].run();

    dialog.showAndWait();
    return resultHolder.isEmpty() ? Optional.empty() : Optional.of(resultHolder.get(0));
  }

  private static boolean isInsideCheckBox(Object target) {
    if (!(target instanceof Node node)) {
      return false;
    }
    Node cur = node;
    while (cur != null) {
      if (cur instanceof CheckBox) {
        return true;
      }
      cur = cur.getParent();
    }
    return false;
  }

  private static int indexAtMouse(ListView<?> list, double yInList) {
    // VirtualFlow cell height varies; use selection after click when possible.
    int selected = list.getSelectionModel().getSelectedIndex();
    if (selected >= 0) {
      return selected;
    }
    return -1;
  }

  private static void setChecked(DirRow row, boolean checked, Set<String> checkedPaths) {
    row.checked.set(checked);
    String path = row.dir.getAbsolutePath();
    if (checked) {
      checkedPaths.add(path);
    } else {
      checkedPaths.remove(path);
    }
  }

  private static void navigateTo(
      File dir,
      File[] currentDir,
      Label pathLabel,
      ObservableList<DirRow> rows,
      Set<String> checkedPaths,
      ListView<DirRow> list) {
    if (dir == null || !dir.isDirectory()) {
      return;
    }
    currentDir[0] = dir;
    pathLabel.setText(dir.getAbsolutePath());
    rows.setAll(listChildDirectories(dir, checkedPaths));
    list.getSelectionModel().clearSelection();
  }

  /** Child directories only — never files. */
  private static List<DirRow> listChildDirectories(File parent, Set<String> checkedPaths) {
    List<DirRow> result = new ArrayList<>();
    File[] children = parent.listFiles(File::isDirectory);
    if (children == null) {
      return result;
    }
    List<File> dirs = new ArrayList<>();
    for (File child : children) {
      if (child != null && child.isDirectory() && !child.isHidden()) {
        dirs.add(child);
      }
    }
    dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    for (File dir : dirs) {
      result.add(new DirRow(dir, checkedPaths.contains(dir.getAbsolutePath())));
    }
    return result;
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

  private static final class DirRow {
    final File dir;
    final SimpleBooleanProperty checked;

    DirRow(File dir, boolean checked) {
      this.dir = dir;
      this.checked = new SimpleBooleanProperty(checked);
    }
  }
}
