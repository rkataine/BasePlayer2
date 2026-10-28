package org.baseplayer.components.sidebars;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.baseplayer.MainApp;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.ui.theme.AppTheme;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Themed directory browser laid out like a native file chooser: clickable
 * breadcrumb path (Ikonli icons), quick places on the left, Open bottom-right.
 *
 * <p>JavaFX has no multi-select {@code DirectoryChooser}; this dialog is the
 * app substitute. Ctrl/Shift select folders in the current view, then Open.
 * Double-click enters a folder.
 */
public final class SelectSampleDirectoriesDialog {

  private static final double PLACES_WIDTH = 168;
  private static final int ICON_SIZE = 13;
  private static final int CRUMB_ICON_SIZE = 12;
  /** Same gold as {@code MenuIcons.COLOR_DIRECTORY}. */
  private static final Color FOLDER_ICON_COLOR = Color.web("#e2b04a");

  private SelectSampleDirectoriesDialog() {}

  /** Sample directories picker; remembers last path under preference key {@code DIR}. */
  public static Optional<List<File>> show(Window owner) {
    return show(owner, "Open directories", "DIR");
  }

  /**
   * @param title window title
   * @param preferenceKey {@link UserPreferences} last-directory key (e.g. {@code DIR}, {@code DRAGEN})
   */
  public static Optional<List<File>> show(Window owner, String title, String preferenceKey) {
    Window resolved = owner != null ? owner : MainApp.stage;
    String prefKey = preferenceKey == null || preferenceKey.isBlank() ? "DIR" : preferenceKey;
    String windowTitle = title == null || title.isBlank() ? "Open directories" : title;

    File start = UserPreferences.getLastDirectory(prefKey);
    if (start == null || !start.isDirectory()) {
      start = UserPreferences.getLastDirectory("DIR");
    }
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
    dialog.setTitle(windowTitle);
    dialog.setMinWidth(720);
    dialog.setMinHeight(480);

    ObservableList<File> folderRows = FXCollections.observableArrayList();
    final File[] currentDir = {start};

    Button up = iconButton(FontAwesomeSolid.CHEVRON_LEFT, "Go to parent folder");
    up.getStyleClass().add("directory-chooser-up");

    HBox crumbs = new HBox(2);
    crumbs.setAlignment(Pos.CENTER_LEFT);
    crumbs.getStyleClass().add("directory-chooser-crumbs");

    ScrollPane crumbScroll = new ScrollPane(crumbs);
    crumbScroll.setFitToHeight(true);
    crumbScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    crumbScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    crumbScroll.setFocusTraversable(false);
    crumbScroll.getStyleClass().add("directory-chooser-crumb-scroll");
    HBox.setHgrow(crumbScroll, Priority.ALWAYS);

    HBox pathBar = new HBox(6, up, crumbScroll);
    pathBar.setAlignment(Pos.CENTER_LEFT);
    pathBar.getStyleClass().add("directory-chooser-path-bar");
    pathBar.setPadding(new Insets(8, 10, 8, 10));

    ListView<Place> placesList = new ListView<>();
    placesList.getStyleClass().add("directory-chooser-places");
    placesList.setItems(FXCollections.observableArrayList(buildPlaces(prefKey)));
    placesList.setPrefWidth(PLACES_WIDTH);
    placesList.setMinWidth(140);
    placesList.setMaxWidth(220);
    placesList.setCellFactory(lv -> new ListCell<>() {
      @Override
      protected void updateItem(Place item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
          setGraphic(null);
          setTooltip(null);
          return;
        }
        Label name = new Label(item.label);
        name.getStyleClass().add("directory-chooser-place-name");
        HBox box = new HBox(10, faIcon(item.ikon, ICON_SIZE), name);
        box.setAlignment(Pos.CENTER_LEFT);
        setTooltip(new Tooltip(item.dir.getAbsolutePath()));
        setText(null);
        setGraphic(box);
      }
    });

    ListView<File> folderList = new ListView<>(folderRows);
    folderList.getStyleClass().add("directory-chooser-folders");
    folderList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    folderList.setCellFactory(lv -> new ListCell<>() {
      @Override
      protected void updateItem(File item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
          setGraphic(null);
          setTooltip(null);
          return;
        }
        String label = item.getName();
        Label name = new Label(label == null || label.isEmpty() ? item.getAbsolutePath() : label);
        name.getStyleClass().add("directory-chooser-folder-name");
        HBox box = new HBox(8, faIcon(FontAwesomeSolid.FOLDER, ICON_SIZE, FOLDER_ICON_COLOR), name);
        box.setAlignment(Pos.CENTER_LEFT);
        setTooltip(new Tooltip(item.getAbsolutePath()));
        setText(null);
        setGraphic(box);
      }
    });

    final List<List<File>> resultHolder = new ArrayList<>(1);
    Button cancel = chooserButton("Cancel", false);
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> dialog.close());
    Button open = chooserButton("Open", true);
    open.setDefaultButton(true);
    open.setDisable(true);

    Runnable syncOpen = () ->
        open.setDisable(folderList.getSelectionModel().getSelectedItems().isEmpty());
    folderList.getSelectionModel().getSelectedItems()
        .addListener((ListChangeListener<File>) c -> syncOpen.run());

    Runnable confirmOpen = () -> {
      List<File> selected = new ArrayList<>();
      for (File dir : folderList.getSelectionModel().getSelectedItems()) {
        if (dir != null && dir.isDirectory()) {
          selected.add(dir);
        }
      }
      if (selected.isEmpty()) {
        return;
      }
      selected.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      UserPreferences.setLastDirectory(prefKey, currentDir[0]);
      resultHolder.clear();
      resultHolder.add(selected);
      dialog.close();
    };
    open.setOnAction(e -> confirmOpen.run());

    final Consumer<File> navigate = new Consumer<>() {
      @Override
      public void accept(File target) {
        navigateTo(target, currentDir, crumbs, folderRows, folderList, up, syncOpen, this);
      }
    };

    up.setOnAction(e -> {
      File parent = currentDir[0].getParentFile();
      if (parent != null && parent.isDirectory()) {
        navigate.accept(parent);
      }
    });

    placesList.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY) {
        return;
      }
      Place place = placesList.getSelectionModel().getSelectedItem();
      if (place != null && place.dir.isDirectory()) {
        navigate.accept(place.dir);
      }
    });

    folderList.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2) {
        return;
      }
      File dir = folderList.getSelectionModel().getSelectedItem();
      if (dir != null && dir.isDirectory()) {
        navigate.accept(dir);
      }
    });

    folderList.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ENTER) {
        e.consume();
        if (!open.isDisabled()) {
          confirmOpen.run();
        }
      }
    });

    VBox mainColumn = new VBox(pathBar, folderList);
    VBox.setVgrow(folderList, Priority.ALWAYS);
    mainColumn.getStyleClass().add("directory-chooser-main");

    HBox browser = new HBox(placesList, mainColumn);
    HBox.setHgrow(mainColumn, Priority.ALWAYS);
    browser.getStyleClass().add("directory-chooser-body");
    VBox.setVgrow(browser, Priority.ALWAYS);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, open);
    buttons.setAlignment(Pos.CENTER_RIGHT);
    buttons.setPadding(new Insets(10));
    buttons.getStyleClass().add("directory-chooser-buttons");

    BorderPane root = new BorderPane();
    root.setCenter(browser);
    root.setBottom(buttons);
    root.getStyleClass().addAll("dialog-surface", "directory-chooser");

    Scene scene = new Scene(root, 780, 520);
    applyTheme(scene);
    scene.setFill(AppTheme.chrome().panel());
    dialog.setScene(scene);

    navigate.accept(start);

    dialog.showAndWait();
    return resultHolder.isEmpty() ? Optional.empty() : Optional.of(resultHolder.get(0));
  }

  private static Color iconColor() {
    return AppTheme.chrome().text();
  }

  private static Color mutedIconColor() {
    return AppTheme.chrome().muted();
  }

  private static FontIcon faIcon(Ikon ikon, int size) {
    return faIcon(ikon, size, iconColor());
  }

  private static FontIcon faIcon(Ikon ikon, int size, Color color) {
    FontIcon icon = new FontIcon(ikon);
    icon.setIconSize(size);
    icon.setIconColor(color);
    return icon;
  }

  private static Button iconButton(Ikon ikon, String tooltip) {
    Button button = new Button();
    button.setGraphic(faIcon(ikon, ICON_SIZE));
    button.getStyleClass().add("secondary-button");
    if (tooltip != null) {
      button.setTooltip(new Tooltip(tooltip));
    }
    return button;
  }

  private static List<Place> buildPlaces(String preferenceKey) {
    Map<String, Place> places = new LinkedHashMap<>();
    File home = new File(System.getProperty("user.home", "."));
    addPlace(places, "Home", FontAwesomeSolid.HOME, home);
    addPlace(places, "Desktop", FontAwesomeSolid.DESKTOP, new File(home, "Desktop"));
    addPlace(places, "Documents", FontAwesomeSolid.FILE_ALT, new File(home, "Documents"));
    addPlace(places, "Downloads", FontAwesomeSolid.DOWNLOAD, new File(home, "Downloads"));
    addPlace(places, "Music", FontAwesomeSolid.MUSIC, new File(home, "Music"));
    addPlace(places, "Pictures", FontAwesomeSolid.IMAGE, new File(home, "Pictures"));
    addPlace(places, "Videos", FontAwesomeSolid.VIDEO, new File(home, "Videos"));

    File lastPref = UserPreferences.getLastDirectory(preferenceKey);
    if (lastPref != null && lastPref.isDirectory()) {
      addPlace(places, "Last opened", FontAwesomeSolid.FOLDER_OPEN, lastPref);
    }
    if (!"DIR".equals(preferenceKey)) {
      File lastDir = UserPreferences.getLastDirectory("DIR");
      if (lastDir != null && lastDir.isDirectory()
          && (lastPref == null || !samePath(lastDir, lastPref))) {
        addPlace(places, "Last sample folder", FontAwesomeSolid.FOLDER, lastDir);
      }
    }
    File lastBam = UserPreferences.getLastDirectory("BAM");
    if (lastBam != null && lastBam.isDirectory()
        && (lastPref == null || !samePath(lastBam, lastPref))) {
      addPlace(places, "Last BAM folder", FontAwesomeSolid.FOLDER, lastBam);
    }
    return new ArrayList<>(places.values());
  }

  private static void addPlace(Map<String, Place> places, String label, Ikon ikon, File dir) {
    if (dir == null || !dir.isDirectory()) {
      return;
    }
    places.putIfAbsent(dir.getAbsolutePath(), new Place(label, ikon, dir));
  }

  private static boolean samePath(File a, File b) {
    return a.getAbsolutePath().equals(b.getAbsolutePath());
  }

  private static boolean isAncestorOrSame(File ancestor, File child) {
    if (ancestor == null || child == null) {
      return false;
    }
    File cur = child;
    while (cur != null) {
      if (samePath(cur, ancestor)) {
        return true;
      }
      cur = cur.getParentFile();
    }
    return false;
  }

  private static void navigateTo(
      File dir,
      File[] currentDir,
      HBox crumbs,
      ObservableList<File> folderRows,
      ListView<File> folderList,
      Button up,
      Runnable syncOpen,
      Consumer<File> navigate) {
    if (dir == null || !dir.isDirectory()) {
      return;
    }
    currentDir[0] = dir;
    rebuildBreadcrumbs(dir, crumbs, navigate);
    folderRows.setAll(listChildDirectories(dir));
    folderList.getSelectionModel().clearSelection();
    File parent = dir.getParentFile();
    up.setDisable(parent == null || !parent.isDirectory());
    if (syncOpen != null) {
      syncOpen.run();
    }
  }

  /**
   * GNOME-style crumbs: parent chevron is separate; path shows Home icon when
   * under {@code user.home}, then clickable segment labels with › separators.
   */
  private static void rebuildBreadcrumbs(File dir, HBox crumbs, Consumer<File> navigate) {
    crumbs.getChildren().clear();
    File home = new File(System.getProperty("user.home", "."));
    List<File> chain = new ArrayList<>();
    File walk = dir;
    while (walk != null) {
      chain.add(0, walk);
      if (samePath(walk, home)) {
        break;
      }
      File parent = walk.getParentFile();
      if (parent == null) {
        break;
      }
      walk = parent;
    }

    boolean underHome = !chain.isEmpty() && samePath(chain.get(0), home);
    if (!underHome && !isAncestorOrSame(home, dir)) {
      // Absolute path from filesystem root.
      File root = chain.isEmpty() ? dir : chain.get(0);
      crumbs.getChildren().add(crumbButton(
          "/", faIcon(FontAwesomeSolid.HDD, CRUMB_ICON_SIZE), root, navigate));
      for (int i = 1; i < chain.size(); i++) {
        crumbs.getChildren().add(separator());
        File segment = chain.get(i);
        crumbs.getChildren().add(crumbButton(segmentName(segment), null, segment, navigate));
      }
      return;
    }

    // Under home: home icon + segments after home (incl. home username as first text).
    crumbs.getChildren().add(crumbButton(
        null, faIcon(FontAwesomeSolid.HOME, CRUMB_ICON_SIZE), home, navigate));
    for (int i = 1; i < chain.size(); i++) {
      crumbs.getChildren().add(separator());
      File segment = chain.get(i);
      crumbs.getChildren().add(crumbButton(segmentName(segment), null, segment, navigate));
    }
  }

  private static String segmentName(File segment) {
    String name = segment.getName();
    if (name == null || name.isEmpty()) {
      return segment.getAbsolutePath();
    }
    return name;
  }

  private static Node separator() {
    FontIcon chevron = faIcon(FontAwesomeSolid.CHEVRON_RIGHT, 10, mutedIconColor());
    Label sep = new Label();
    sep.setGraphic(chevron);
    sep.getStyleClass().add("directory-chooser-crumb-sep");
    return sep;
  }

  private static Button crumbButton(String text, Node graphic, File target, Consumer<File> navigate) {
    Button crumb = new Button(text == null ? "" : text);
    if (graphic != null) {
      crumb.setGraphic(graphic);
      if (text == null || text.isEmpty()) {
        crumb.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
      } else {
        crumb.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
        crumb.setGraphicTextGap(4);
      }
    }
    crumb.getStyleClass().add("directory-chooser-crumb");
    crumb.setTooltip(new Tooltip(target.getAbsolutePath()));
    crumb.setOnAction(e -> navigate.accept(target));
    crumb.setFocusTraversable(false);
    return crumb;
  }

  /** Child directories only — never files. */
  private static List<File> listChildDirectories(File parent) {
    List<File> result = new ArrayList<>();
    File[] children = parent.listFiles(File::isDirectory);
    if (children == null) {
      return result;
    }
    for (File child : children) {
      if (child != null && child.isDirectory() && !child.isHidden()) {
        result.add(child);
      }
    }
    result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    return result;
  }

  private static Button chooserButton(String text, boolean primary) {
    Button button = new Button(text);
    button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
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

  private static final class Place {
    final String label;
    final Ikon ikon;
    final File dir;

    Place(String label, Ikon ikon, File dir) {
      this.label = label;
      this.ikon = ikon;
      this.dir = dir;
    }
  }
}
