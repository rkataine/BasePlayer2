package org.baseplayer.controllers.commands;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.baseplayer.MainApp;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.ProjectSerializer;
import org.baseplayer.project.ProjectService;
import org.baseplayer.project.ProjectSessionState;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Handles file operations: loading BAM, VCF, BED, BigWig files and project open/save.
 */
public class FileCommands {


  /**
   * Open a file dialog for loading files.
   *
   * @param fileType Type of file (VCF, BAM, BED, etc.)
   */
  public static void openFile(String fileType) {
    openFile(null, fileType);
  }

  /**
   * @param action LOAD or SAVE (from menu item id prefix); may be null
   * @param fileType BAM, VCF, SES, etc.
   */
  public static void openFile(String action, String fileType) {
    if (fileType == null) return;

    switch (fileType.toUpperCase()) {
      case "BAM" -> SampleDataManager.addBamFiles();
      case "BED" -> SampleDataManager.addBedSampleFile();
      case "BIGWIG" -> SampleDataManager.addBigWigFile();
      case "VCF" -> SampleDataManager.addVcfFile();
      case "SES" -> handleSessionAction(action);
      case "CTRL" -> System.out.println("File menu action not implemented yet: " + fileType);
      default -> System.out.println("Unknown file menu action: " + fileType);
    }
    if (!"SES".equalsIgnoreCase(fileType)) {
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }
  }

  private static void handleSessionAction(String action) {
    String act = action == null ? "LOAD" : action.toUpperCase();
    switch (act) {
      case "SAVE" -> saveSession(false);
      case "SAVEAS", "SAVE_AS" -> saveSession(true);
      default -> openSession();
    }
  }

  public static void openSession() {
    openSessionFromChooser();
  }

  /**
   * @return true if a session was chosen and load started
   */
  public static boolean openSessionFromChooser() {
    if (!confirmDiscardIfDirty()) return false;

    FileChooser chooser = sessionChooser("Open Project");
    File lastDir = UserPreferences.getLastDirectory("JSON");
    if (lastDir != null) {
      try {
        chooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException ignored) { /* default */ }
    }
    File file = chooser.showOpenDialog(MainApp.stage);
    if (file == null) return false;
    UserPreferences.setLastDirectory("JSON", file.getParentFile());
    return loadSessionFile(file.toPath());
  }

  /**
   * Open a project file. Does not show a chooser.
   *
   * @return true if load started successfully
   */
  public static boolean openSession(Path path) {
    if (path == null) return false;
    if (!confirmDiscardIfDirty()) return false;
    return loadSessionFile(path);
  }

  private static boolean loadSessionFile(Path path) {
    File file = path.toFile();
    if (!file.exists() || !file.isFile()) {
      showError("Could not open project", "File not found:\n" + path);
      return false;
    }

    try {
      ProjectDocument doc = ProjectSerializer.read(path);
      ProjectService.loadAsync(path, doc, null);
      UserPreferences.addRecentProject(file);
      UserPreferences.setLastDirectory("JSON", file.getParentFile());
      return true;
    } catch (Exception e) {
      showError("Could not open project", e.getMessage());
      return false;
    }
  }

  public static void saveSession(boolean forceSaveAs) {
    ProjectSessionState session = ProjectSessionState.get();
    Path existing = session.getFile();
    if (!forceSaveAs && existing != null) {
      ProjectService.saveAsync(
          existing,
          null,
          e -> showError("Could not save project", e != null ? e.getMessage() : null));
      return;
    }

    FileChooser chooser = sessionChooser("Save Project");
    File lastDir = UserPreferences.getLastDirectory("JSON");
    if (lastDir != null) {
      try {
        chooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException ignored) { /* default */ }
    }
    if (existing != null) {
      chooser.setInitialFileName(existing.getFileName().toString());
    } else if (session.getName() != null && !"Untitled".equals(session.getName())) {
      chooser.setInitialFileName(session.getName() + ".bpproj");
    } else {
      chooser.setInitialFileName("project.bpproj");
    }

    File file = chooser.showSaveDialog(MainApp.stage);
    if (file == null) return;
    if (!file.getName().contains(".")) {
      file = new File(file.getParentFile(), file.getName() + ".bpproj");
    }
    UserPreferences.setLastDirectory("JSON", file.getParentFile());
    ProjectService.saveAsync(
        file.toPath(),
        null,
        e -> showError("Could not save project", e != null ? e.getMessage() : null));
  }

  /**
   * @return true if caller may proceed (saved, discarded, or not dirty)
   */
  public static boolean confirmDiscardIfDirty() {
    ProjectSessionState session = ProjectSessionState.get();
    if (!session.isDirty()) return true;

    AtomicReference<String> choice = new AtomicReference<>("cancel");

    Stage dialog = new Stage();
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initOwner(MainApp.stage);
    dialog.setTitle("Unsaved project");
    dialog.setResizable(false);

    VBox root = new VBox(12);
    root.setPadding(new Insets(20));
    root.setStyle("-fx-background-color: #2b2b2b;");

    Label title = new Label("Save changes to \"" + session.getName() + "\"?");
    title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");
    title.setWrapText(true);
    title.setMaxWidth(360);

    Label detail = new Label("Your project has unsaved changes.");
    detail.setStyle("-fx-font-size: 12px; -fx-text-fill: #cccccc;");
    detail.setWrapText(true);
    detail.setMaxWidth(360);

    Button saveBtn = new Button("Save");
    saveBtn.setDefaultButton(true);
    saveBtn.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-cursor: hand;");
    saveBtn.setOnAction(e -> {
      choice.set("save");
      dialog.close();
    });

    Button discardBtn = new Button("Don't save");
    discardBtn.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #cccccc; -fx-cursor: hand;");
    discardBtn.setOnAction(e -> {
      choice.set("discard");
      dialog.close();
    });

    Button cancelBtn = new Button("Cancel");
    cancelBtn.setCancelButton(true);
    cancelBtn.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #cccccc; -fx-cursor: hand;");
    cancelBtn.setOnAction(e -> {
      choice.set("cancel");
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(10, spacer, cancelBtn, discardBtn, saveBtn);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    root.getChildren().addAll(title, detail, buttons);

    Scene scene = new Scene(root);
    if (MainApp.stage != null && MainApp.stage.getScene() != null) {
      scene.getStylesheets().addAll(MainApp.stage.getScene().getStylesheets());
    }
    dialog.setScene(scene);
    dialog.sizeToScene();
    dialog.showAndWait();

    return switch (choice.get()) {
      case "save" -> saveSessionAndWait();
      case "discard" -> true;
      default -> false;
    };
  }

  /** Resolve path (chooser if needed) and save with loading UI; wait until finished. */
  private static boolean saveSessionAndWait() {
    ProjectSessionState session = ProjectSessionState.get();
    Path target = session.getFile();
    if (target == null) {
      FileChooser chooser = sessionChooser("Save Project");
      File lastDir = UserPreferences.getLastDirectory("JSON");
      if (lastDir != null) {
        try {
          chooser.setInitialDirectory(lastDir);
        } catch (IllegalArgumentException ignored) { /* default */ }
      }
      if (session.getName() != null && !"Untitled".equals(session.getName())) {
        chooser.setInitialFileName(session.getName() + ".bpproj");
      } else {
        chooser.setInitialFileName("project.bpproj");
      }
      File file = chooser.showSaveDialog(MainApp.stage);
      if (file == null) return false;
      if (!file.getName().contains(".")) {
        file = new File(file.getParentFile(), file.getName() + ".bpproj");
      }
      UserPreferences.setLastDirectory("JSON", file.getParentFile());
      target = file.toPath();
    }

    AtomicReference<Exception> error = new AtomicReference<>();
    AtomicReference<Boolean> finished = new AtomicReference<>(false);

    Stage wait = new Stage();
    wait.initModality(Modality.APPLICATION_MODAL);
    wait.initOwner(MainApp.stage);
    wait.setTitle("Saving");
    wait.setResizable(false);
    Label waitLabel = new Label("Saving project…");
    waitLabel.setStyle("-fx-text-fill: #cccccc;");
    VBox waitRoot = new VBox(waitLabel);
    waitRoot.setPadding(new Insets(24));
    waitRoot.setStyle("-fx-background-color: #2b2b2b;");
    wait.setScene(new Scene(waitRoot));

    Path savePath = target;
    wait.setOnShown(e -> ProjectService.saveAsync(
        savePath,
        () -> {
          finished.set(true);
          wait.close();
        },
        ex -> {
          error.set(ex);
          finished.set(true);
          wait.close();
        }));

    wait.showAndWait();
    if (error.get() != null) {
      showError("Could not save project", error.get().getMessage());
      return false;
    }
    return Boolean.TRUE.equals(finished.get()) && !session.isDirty();
  }

  /** Clear loaded samples/VCFs and detach from the current project file (untitled). */
  public static void newProject() {
    if (!confirmDiscardIfDirty()) return;
    SampleDataManager.clearAllData();
    ProjectSessionState.get().clearSession();
  }

  /** @deprecated use {@link #newProject()} */
  @Deprecated
  public static void clearAllData() {
    newProject();
  }

  /**
   * Open a file directly from the recent-files menu.
   */
  public static void openRecentFile(String fileType, String path) {
    if (path == null || path.isBlank()) return;
    File file = new File(path);
    if (!file.exists() || !file.isFile()) {
      System.err.println("Recent file missing: " + path);
      return;
    }

    String resolvedType = fileType;
    if (resolvedType == null || resolvedType.isBlank()) {
      String lower = file.getName().toLowerCase();
      if (lower.endsWith(".bam") || lower.endsWith(".cram")) resolvedType = "BAM";
      else if (lower.endsWith(".bed") || lower.endsWith(".bed.gz")) resolvedType = "BED";
      else if (lower.endsWith(".bw") || lower.endsWith(".bigwig")) resolvedType = "BIGWIG";
      else if (lower.endsWith(".bpproj") || lower.endsWith(".json")) resolvedType = "SES";
      else resolvedType = "";
    }

    switch (resolvedType.toUpperCase()) {
      case "BAM" -> SampleDataManager.addBamFile(file);
      case "BED" -> SampleDataManager.addBedSampleFile(file);
      case "BIGWIG" -> SampleDataManager.addBigWigFile(file);
      case "SES", "JSON" -> {
        openSession(file.toPath());
      }
      default -> System.out.println("Unsupported recent file type: " + resolvedType + " (" + path + ")");
    }
    if (!"SES".equalsIgnoreCase(resolvedType) && !"JSON".equalsIgnoreCase(resolvedType)) {
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }
  }

  private static FileChooser sessionChooser(String title) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title);
    chooser.getExtensionFilters().addAll(
        new ExtensionFilter("BasePlayer project", "*.bpproj", "*.json"),
        new ExtensionFilter("All files", "*.*")
    );
    return chooser;
  }

  private static void showError(String header, String detail) {
    Stage dialog = new Stage();
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initOwner(MainApp.stage);
    dialog.setTitle("Session");
    dialog.setResizable(false);

    VBox root = new VBox(12);
    root.setPadding(new Insets(20));
    root.setStyle("-fx-background-color: #2b2b2b;");

    Label title = new Label(header != null ? header : "Error");
    title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");
    title.setWrapText(true);
    title.setMaxWidth(360);

    Label body = new Label(detail != null ? detail : "");
    body.setStyle("-fx-font-size: 12px; -fx-text-fill: #cccccc;");
    body.setWrapText(true);
    body.setMaxWidth(360);

    Button ok = new Button("OK");
    ok.setDefaultButton(true);
    ok.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-cursor: hand;");
    ok.setOnAction(e -> dialog.close());

    HBox buttons = new HBox(ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    root.getChildren().addAll(title, body, buttons);
    Scene scene = new Scene(root);
    if (MainApp.stage != null && MainApp.stage.getScene() != null) {
      scene.getStylesheets().addAll(MainApp.stage.getScene().getStylesheets());
    }
    dialog.setScene(scene);
    dialog.sizeToScene();
    dialog.showAndWait();
  }
}
