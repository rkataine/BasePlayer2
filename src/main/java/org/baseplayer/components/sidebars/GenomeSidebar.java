package org.baseplayer.components.sidebars;

import java.util.List;

import org.baseplayer.MainApp;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.components.AnnotationOptionsDialog;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.io.Settings;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.services.InitializationService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Sidebar for genome configuration — extends {@link SidebarBase}.
 *
 * <p>Header shows "Genome" with ⚙ (annotation options) and + buttons.
 * Content area holds combo-boxes for reference genome and gene annotation
 * selection.</p>
 */
public class GenomeSidebar extends SidebarBase {

  private static GenomeSidebar instance;

  private final ComboBox<ReferenceGenome> referenceComboBox  = new ComboBox<>();
  private final ComboBox<String>          annotationComboBox = new ComboBox<>();
  private final Label projectNameLabel = new Label("Untitled");

  private final InitializationService initializationService;
  private boolean suppressAnnotationReload;

  public GenomeSidebar(StackPane parent, InitializationService initializationService) {
    super(parent, DEFAULT_HEADER_HEIGHT);
    this.initializationService = initializationService;
    instance = this;

    buildContent();
    setupProjectNameLabel();
    loadAvailableGenomes();
  }

  /**
   * Sync combo boxes and reload cytobands/genes from {@link Settings}
   * (used after project open restores {@code genome.id} / {@code genome.annotation}).
   *
   * @return true if the sidebar was available and sync was scheduled/ran
   */
  public static boolean syncFromSettings() {
    GenomeSidebar sidebar = instance;
    if (sidebar == null) {
      return false;
    }
    if (Platform.isFxApplicationThread()) {
      sidebar.applySettingsToUi();
    } else {
      Platform.runLater(sidebar::applySettingsToUi);
    }
    return true;
  }

  /** Redraw header chrome after theme toggle. */
  public static void redrawThemeChrome() {
    GenomeSidebar sidebar = instance;
    if (sidebar == null) {
      return;
    }
    Runnable redraw = sidebar::drawHeader;
    if (Platform.isFxApplicationThread()) {
      redraw.run();
    } else {
      Platform.runLater(redraw);
    }
  }

  // ── SidebarBase contract ──────────────────────────────────────────────────

  @Override protected String getTitle() { return "Genome"; }
  @Override protected int getItemCount() { return 0; }

  @Override protected void onSettingsClicked(double screenX, double screenY) {
    AnnotationOptionsDialog.show(headerPane.getScene().getWindow());
  }

  @Override protected void onAddClicked(double screenX, double screenY) {
    // No-op for now — could open a genome-browser / genome-download dialog
  }

  @Override protected void drawContent() {
    // Content is JavaFX controls — no canvas drawing needed
  }

  // ── Content layout ────────────────────────────────────────────────────────

  private void buildContent() {
    projectNameLabel.getStyleClass().add("project-name-label");
    projectNameLabel.setMaxWidth(Double.MAX_VALUE);
    projectNameLabel.setWrapText(true);
    Tooltip.install(projectNameLabel, new Tooltip("Current project"));

    Separator projectSeparator = new Separator();
    projectSeparator.setMaxWidth(Double.MAX_VALUE);

    VBox sessionHeader = new VBox(2, projectNameLabel, projectSeparator);
    sessionHeader.setPadding(new Insets(6, 5, 4, 5));
    sessionHeader.setMaxWidth(Double.MAX_VALUE);
    rootLayout.getChildren().add(0, sessionHeader);

    Label annotationLabel = new Label("Gene annotation");
    annotationLabel.getStyleClass().add("sidebar-label");

    annotationComboBox.setPrefHeight(22);
    annotationComboBox.setMinWidth(0);
    annotationComboBox.setMaxWidth(Double.MAX_VALUE);
    annotationComboBox.getStyleClass().add("minimal-combo-box");
    annotationComboBox.setPromptText("Annotations");
    annotationComboBox.setOnAction(e -> {
      if (suppressAnnotationReload) {
        return;
      }
      String sel = annotationComboBox.getValue();
      if (sel != null) {
        Settings.get().setLastAnnotation(sel);
        AnnotationLoader.loadGenesBackground();
      }
    });

    Label referenceLabel = new Label("Reference genome");
    referenceLabel.getStyleClass().add("sidebar-label");

    referenceComboBox.setPrefHeight(22);
    referenceComboBox.setMinWidth(0);
    referenceComboBox.setMaxWidth(Double.MAX_VALUE);
    referenceComboBox.getStyleClass().add("minimal-combo-box");
    referenceComboBox.setPromptText("Homo Sapiens GRCh38");

    // Spacer pushes reference section to bottom
    VBox spacer = new VBox();
    VBox.setVgrow(spacer, Priority.ALWAYS);

    VBox layout = new VBox(4);
    layout.setPadding(new Insets(5));
    layout.setMinWidth(0);
    layout.setMaxWidth(Double.MAX_VALUE);
    layout.setMaxHeight(Double.MAX_VALUE);
    layout.getChildren().addAll(
        annotationLabel, annotationComboBox,
        spacer,
        referenceLabel, referenceComboBox);

    contentPane.getChildren().add(layout);
  }

  private void setupProjectNameLabel() {
    ProjectSessionState session = ProjectSessionState.get();
    Runnable refresh = () -> {
      projectNameLabel.setText(session.getDisplayLabel());
      if (MainApp.stage != null) {
        MainApp.stage.setTitle("BasePlayer — " + session.getDisplayLabel());
      }
    };
    refresh.run();
    session.addListener(s -> {
      if (Platform.isFxApplicationThread()) {
        refresh.run();
      } else {
        Platform.runLater(refresh);
      }
    });
  }

  // ── Data loading ──────────────────────────────────────────────────────────

  private void loadAvailableGenomes() {
    List<ReferenceGenome> genomes = initializationService.loadAvailableGenomes();
    referenceComboBox.getItems().addAll(genomes);

    String lastGenome = Settings.get().getLastGenome();
    ReferenceGenome selected = null;
    if (lastGenome != null) {
      selected = genomes.stream()
          .filter(g -> g.getName().equals(lastGenome))
          .findFirst().orElse(null);
    }
    if (selected != null) {
      referenceComboBox.getSelectionModel().select(selected);
    } else if (!genomes.isEmpty()) {
      referenceComboBox.getSelectionModel().selectFirst();
    }
    // Wire action after initial select so programmatic select does not double-load genes.
    referenceComboBox.setOnAction(e -> onReferenceGenomeSelected());
    onReferenceGenomeSelected();
  }

  /**
   * Apply Settings genome/annotation to the combo boxes and reload annotation data.
   * Does not overwrite Settings when the saved annotation is missing from disk.
   */
  private void applySettingsToUi() {
    String lastGenome = Settings.get().getLastGenome();
    String lastAnnotation = Settings.get().getLastAnnotation();

    ReferenceGenome match = null;
    if (lastGenome != null && !lastGenome.isBlank()) {
      match = referenceComboBox.getItems().stream()
          .filter(g -> lastGenome.equals(g.getName()))
          .findFirst()
          .orElse(null);
    }

    referenceComboBox.setOnAction(null);
    try {
      if (match != null) {
        referenceComboBox.getSelectionModel().select(match);
        initializationService.selectReferenceGenome(match);
      }

      loadAvailableAnnotations(false);

      if (lastAnnotation != null && !lastAnnotation.isBlank()) {
        suppressAnnotationReload = true;
        try {
          if (annotationComboBox.getItems().contains(lastAnnotation)) {
            annotationComboBox.getSelectionModel().select(lastAnnotation);
          }
        } finally {
          suppressAnnotationReload = false;
        }
        // Keep project annotation even if the file is temporarily missing from the combo.
        Settings.get().setLastAnnotation(lastAnnotation);
      }
      if (lastGenome != null && !lastGenome.isBlank()) {
        Settings.get().setLastGenome(lastGenome);
      }

      AnnotationLoader.loadCytobands();
      AnnotationLoader.loadGenesBackground();
    } finally {
      referenceComboBox.setOnAction(e -> onReferenceGenomeSelected());
    }
  }

  private void loadAvailableAnnotations() {
    loadAvailableAnnotations(true);
  }

  private void loadAvailableAnnotations(boolean persistSelection) {
    ReferenceGenome genome = referenceComboBox.getValue();
    String genomeName = genome != null ? genome.getName() : "GRCh38";

    suppressAnnotationReload = true;
    try {
      annotationComboBox.getItems().clear();
      List<String> annotations = initializationService.loadAvailableAnnotations(genomeName);
      annotationComboBox.getItems().addAll(annotations);

      if (!annotations.isEmpty()) {
        // Restore last used annotation if it belongs to this genome
        String lastAnnotation = Settings.get().getLastAnnotation();
        if (lastAnnotation != null && annotations.contains(lastAnnotation)) {
          annotationComboBox.getSelectionModel().select(lastAnnotation);
        } else {
          String defaultAnnotation = initializationService.findDefaultAnnotation(annotations);
          if (defaultAnnotation != null) {
            annotationComboBox.getSelectionModel().select(defaultAnnotation);
          }
        }
      }
      if (persistSelection) {
        Settings.get().setLastAnnotation(annotationComboBox.getValue());
      }
    } finally {
      suppressAnnotationReload = false;
    }
  }

  private void onReferenceGenomeSelected() {
    ReferenceGenome genome = referenceComboBox.getValue();
    if (genome == null) return;
    Settings.get().setLastGenome(genome.getName());
    initializationService.selectReferenceGenome(genome);
    loadAvailableAnnotations();
    AnnotationLoader.loadCytobands();
    AnnotationLoader.loadGenesBackground();
  }
}
