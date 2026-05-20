package org.baseplayer.components.sidebars;

import java.util.List;

import org.baseplayer.components.AnnotationOptionsDialog;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.io.Settings;
import org.baseplayer.services.InitializationService;

import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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

  private final ComboBox<ReferenceGenome> referenceComboBox  = new ComboBox<>();
  private final ComboBox<String>          annotationComboBox = new ComboBox<>();

  private final InitializationService initializationService;

  public GenomeSidebar(StackPane parent, InitializationService initializationService) {
    super(parent, DEFAULT_HEADER_HEIGHT);
    this.initializationService = initializationService;

    buildContent();
    loadAvailableGenomes();
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
    Label annotationLabel = new Label("Gene annotation");
    annotationLabel.getStyleClass().add("sidebar-label");

    annotationComboBox.setPrefHeight(22);
    annotationComboBox.setMinWidth(0);
    annotationComboBox.setMaxWidth(Double.MAX_VALUE);
    annotationComboBox.getStyleClass().add("minimal-combo-box");
    annotationComboBox.setPromptText("Annotations");
    annotationComboBox.setOnAction(e -> {
      String sel = annotationComboBox.getValue();
      if (sel != null) Settings.get().setLastAnnotation(sel);
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
    layout.getChildren().addAll(annotationLabel, annotationComboBox, spacer, referenceLabel, referenceComboBox);

    contentPane.getChildren().add(layout);
  }

  // ── Data loading ──────────────────────────────────────────────────────────

  private void loadAvailableGenomes() {
    List<ReferenceGenome> genomes = initializationService.loadAvailableGenomes();
    referenceComboBox.getItems().addAll(genomes);
    referenceComboBox.setOnAction(e -> onReferenceGenomeSelected());

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
    onReferenceGenomeSelected();
  }

  private void loadAvailableAnnotations() {
    ReferenceGenome genome = referenceComboBox.getValue();
    String genomeName = genome != null ? genome.getName() : "GRCh38";

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
    // Save whatever is now selected
    Settings.get().setLastAnnotation(annotationComboBox.getValue());
  }

  private void onReferenceGenomeSelected() {
    ReferenceGenome genome = referenceComboBox.getValue();
    if (genome == null) return;
    Settings.get().setLastGenome(genome.getName());
    initializationService.selectReferenceGenome(genome);
    loadAvailableAnnotations();
  }
}
