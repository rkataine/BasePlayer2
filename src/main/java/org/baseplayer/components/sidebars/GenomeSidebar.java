package org.baseplayer.components.sidebars;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.MainApp;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.components.AnnotationOptionsDialog;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.io.Settings;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.services.InitializationService;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.GeneBiotypeVisibility;
import org.baseplayer.utils.GeneColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;

/**
 * Sidebar for genome configuration — extends {@link SidebarBase}.
 *
 * <p>Header shows "Genome" with ⚙ (annotation options) and + buttons.
 * Content area holds combo-boxes for reference genome and gene annotation
 * selection.</p>
 */
public class GenomeSidebar extends SidebarBase {

  private static GenomeSidebar instance;

  private final StackPane hostPane;
  private final ComboBox<ReferenceGenome> referenceComboBox  = new ComboBox<>();
  private final ComboBox<String>          annotationComboBox = new ComboBox<>();
  private final Label projectNameLabel = new Label("Untitled");
  private final Region biotypeSelectAllBox = new Region();
  private final Polyline biotypeSelectAllCheck = new Polyline();
  private final StackPane biotypeSelectAllPane = new StackPane();
  private final Map<GeneBiotypeVisibility.Category, BiotypeSwatchRow> biotypeRows =
      new EnumMap<>(GeneBiotypeVisibility.Category.class);

  private VBox sessionHeader;
  private VBox genomeSettings;
  private HBox biotypeHeader;

  private final InitializationService initializationService;
  private boolean suppressAnnotationReload;
  private boolean suppressBiotypeSync;

  private record BiotypeSwatchRow(HBox row, Region swatch, Line strike, Label label) {
  }

  public GenomeSidebar(StackPane parent, InitializationService initializationService) {
    super(parent, DEFAULT_HEADER_HEIGHT);
    this.hostPane = parent;
    this.initializationService = initializationService;
    instance = this;

    buildContent();
    installFixedSidebarClip();
    setupProjectNameLabel();
    installBiotypeVisibilityListener();
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
    Runnable redraw = () -> {
      sidebar.drawHeader();
      sidebar.syncBiotypeLegend();
    };
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

    // Below Genome header bar, above settings — never overlaps the settings/add buttons.
    sessionHeader = new VBox(2, projectNameLabel, projectSeparator);
    sessionHeader.setPadding(new Insets(6, 5, 4, 5));
    sessionHeader.setMaxWidth(Double.MAX_VALUE);
    sessionHeader.setMinHeight(Region.USE_PREF_SIZE);
    int headerIndex = rootLayout.getChildren().indexOf(headerPane);
    rootLayout.getChildren().add(headerIndex >= 0 ? headerIndex + 1 : 0, sessionHeader);

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

    genomeSettings = new VBox(4,
        annotationLabel, annotationComboBox,
        referenceLabel, referenceComboBox);
    genomeSettings.setMinHeight(Region.USE_PREF_SIZE);
    genomeSettings.setMaxHeight(Region.USE_PREF_SIZE);
    genomeSettings.setMaxWidth(Double.MAX_VALUE);

    biotypeHeader = buildBiotypeHeader();
    biotypeHeader.setMinHeight(Region.USE_PREF_SIZE);
    biotypeHeader.setMaxHeight(Region.USE_PREF_SIZE);

    VBox biotypeLegendRows = buildBiotypeLegendRows();
    biotypeLegendRows.setMinHeight(Region.USE_PREF_SIZE);
    biotypeLegendRows.setMaxHeight(Region.USE_PREF_SIZE);

    VBox layout = new VBox(4);
    layout.setPadding(new Insets(5));
    layout.setMinWidth(0);
    layout.setMinHeight(Region.USE_PREF_SIZE);
    layout.setMaxHeight(Region.USE_PREF_SIZE);
    layout.setMaxWidth(Double.MAX_VALUE);
    layout.getChildren().addAll(genomeSettings, biotypeHeader, biotypeLegendRows);

    // Fixed-size content: do not grow/shrink with the chrom strip.
    VBox.setVgrow(contentPane, Priority.NEVER);
    contentPane.setMinHeight(Region.USE_PREF_SIZE);
    contentPane.setMaxHeight(Region.USE_PREF_SIZE);
    contentPane.getChildren().add(layout);
    syncBiotypeLegend();
  }

  /**
   * Sidebar keeps a constant preferred height. Shrinking the gene canvas only
   * clips the host — it does not reflow or resize sidebar controls.
   */
  private void installFixedSidebarClip() {
    rootLayout.prefHeightProperty().unbind();
    rootLayout.maxHeightProperty().unbind();
    rootLayout.setMinWidth(0);
    rootLayout.setMinHeight(Region.USE_PREF_SIZE);
    rootLayout.setPrefHeight(Region.USE_COMPUTED_SIZE);
    rootLayout.setMaxHeight(Region.USE_PREF_SIZE);

    hostPane.setMinHeight(0);
    hostPane.setAlignment(Pos.TOP_LEFT);

    Rectangle hostClip = new Rectangle();
    hostClip.widthProperty().bind(hostPane.widthProperty());
    hostClip.heightProperty().bind(hostPane.heightProperty());
    hostPane.setClip(hostClip);

    // Clear any chrom-pane floor left from earlier min-height locking.
    javafx.scene.Parent chromSplit = hostPane.getParent();
    if (chromSplit != null && chromSplit.getParent() instanceof Region chromPane) {
      chromPane.setMinHeight(0);
    }
  }

  private HBox buildBiotypeHeader() {
    // Match aggregate CanvasColorLegend “All” checkbox (rounded box + checkmark).
    biotypeSelectAllBox.setMinSize(12, 10);
    biotypeSelectAllBox.setPrefSize(12, 10);
    biotypeSelectAllBox.setMaxSize(12, 10);

    biotypeSelectAllCheck.getPoints().setAll(
        2.4, 5.5,
        5.0, 7.8,
        9.8, 2.2);
    biotypeSelectAllCheck.setStrokeWidth(1.6);
    biotypeSelectAllCheck.setFill(null);
    biotypeSelectAllCheck.setMouseTransparent(true);
    biotypeSelectAllCheck.setVisible(false);

    biotypeSelectAllPane.getChildren().setAll(biotypeSelectAllBox, biotypeSelectAllCheck);
    biotypeSelectAllPane.setMinSize(12, 10);
    biotypeSelectAllPane.setPrefSize(12, 10);
    biotypeSelectAllPane.setMaxSize(12, 10);
    biotypeSelectAllPane.setCursor(Cursor.HAND);
    biotypeSelectAllPane.setAlignment(Pos.CENTER);
    Tooltip.install(biotypeSelectAllPane, new Tooltip("Select / deselect all gene types"));
    biotypeSelectAllPane.setOnMouseClicked(e -> {
      if (suppressBiotypeSync) {
        return;
      }
      GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
      visibility.setAllVisible(!visibility.areAllVisible());
    });
    applySelectAllCheckboxStyle(true);

    Label biotypeLabel = new Label("Gene types");
    biotypeLabel.getStyleClass().add("sidebar-label");

    HBox header = new HBox(6, biotypeSelectAllPane, biotypeLabel);
    header.setAlignment(Pos.CENTER_LEFT);
    header.setMaxWidth(Double.MAX_VALUE);
    return header;
  }

  private VBox buildBiotypeLegendRows() {
    VBox rows = new VBox(4);
    rows.setMaxWidth(Double.MAX_VALUE);
    rows.setPadding(new Insets(0, 0, 4, 0));

    for (GeneBiotypeVisibility.Category category : GeneBiotypeVisibility.Category.values()) {
      Region swatch = new Region();
      swatch.setMinSize(12, 10);
      swatch.setPrefSize(12, 10);
      swatch.setMaxSize(12, 10);
      swatch.setMouseTransparent(true);

      Line strike = new Line(1, 9, 11, 1);
      strike.setStrokeWidth(1.2);
      strike.setMouseTransparent(true);
      strike.setVisible(false);

      StackPane swatchPane = new StackPane(swatch, strike);
      swatchPane.setMinSize(12, 10);
      swatchPane.setPrefSize(12, 10);
      swatchPane.setMaxSize(12, 10);
      swatchPane.setMouseTransparent(true);

      Label label = new Label(category.label());
      label.setStyle("-fx-font-size: 11px;");
      label.setMaxWidth(Double.MAX_VALUE);
      // Clicks bubble to the row once (avoid double-toggle from child + parent handlers).
      label.setMouseTransparent(true);

      HBox row = new HBox(5, swatchPane, label);
      row.setAlignment(Pos.CENTER_LEFT);
      row.setMaxWidth(Double.MAX_VALUE);
      row.setPadding(new Insets(2, 4, 2, 2));
      row.setCursor(Cursor.HAND);
      row.setOnMouseClicked(e -> {
        if (suppressBiotypeSync) {
          return;
        }
        GeneBiotypeVisibility.get().toggle(category);
        e.consume();
      });
      row.setOnMouseEntered(e -> applyBiotypeRowHover(row, true));
      row.setOnMouseExited(e -> applyBiotypeRowHover(row, false));
      HBox.setHgrow(label, Priority.ALWAYS);

      biotypeRows.put(category, new BiotypeSwatchRow(row, swatch, strike, label));
      applyBiotypeSwatchStyle(category, true);
      rows.getChildren().add(row);
    }
    return rows;
  }

  private void applyBiotypeRowHover(HBox row, boolean hovered) {
    if (row == null) {
      return;
    }
    if (hovered) {
      Color hover = AppTheme.chrome().elevated();
      row.setStyle(
          "-fx-background-color: rgba("
              + (int) (hover.getRed() * 255) + ","
              + (int) (hover.getGreen() * 255) + ","
              + (int) (hover.getBlue() * 255) + ",0.85);"
              + "-fx-background-radius: 3;");
    } else {
      row.setStyle("");
    }
  }

  private void applySelectAllCheckboxStyle(boolean checked) {
    var chrome = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    Color stroke = checked ? canvas.axisInk() : chrome.muted();
    biotypeSelectAllBox.setStyle(
        "-fx-background-color: transparent;"
            + "-fx-border-color: " + GeneColors.toHexString(stroke) + ";"
            + "-fx-border-width: 1.2;"
            + "-fx-border-radius: 2;"
            + "-fx-background-radius: 2;");
    biotypeSelectAllCheck.setStroke(canvas.axisInk());
    biotypeSelectAllCheck.setVisible(checked);
  }

  private void applyBiotypeSwatchStyle(GeneBiotypeVisibility.Category category, boolean enabled) {
    BiotypeSwatchRow row = biotypeRows.get(category);
    if (row == null) {
      return;
    }
    var chrome = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    Color color = category.color();
    double alpha = enabled ? 0.95 : 0.28;
    String fill = String.format(
        "rgba(%d,%d,%d,%.2f)",
        (int) (color.getRed() * 255),
        (int) (color.getGreen() * 255),
        (int) (color.getBlue() * 255),
        alpha);
    row.swatch().setStyle(
        "-fx-background-color: " + fill + ";"
            + "-fx-background-radius: 2;");
    row.strike().setVisible(!enabled);
    row.strike().setStroke(chrome.muted());
    // Match aggregate legend label ink (not swatch color).
    row.label().setTextFill(enabled ? canvas.axisInk() : chrome.muted());
  }

  private void installBiotypeVisibilityListener() {
    GeneBiotypeVisibility.get().addListener(() -> {
      if (Platform.isFxApplicationThread()) {
        syncBiotypeLegend();
      } else {
        Platform.runLater(this::syncBiotypeLegend);
      }
    });
  }

  private void syncBiotypeLegend() {
    suppressBiotypeSync = true;
    try {
      GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
      for (GeneBiotypeVisibility.Category category : GeneBiotypeVisibility.Category.values()) {
        applyBiotypeSwatchStyle(category, visibility.isVisible(category));
      }
      applySelectAllCheckboxStyle(visibility.areAllVisible());
    } finally {
      suppressBiotypeSync = false;
    }
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
        ReferenceGenome currentGenome = referenceComboBox.getValue();
        org.baseplayer.project.SessionDocumentSync.writeGenome(
            currentGenome != null ? currentGenome.getName() : Settings.get().getLastGenome(),
            annotationComboBox.getValue());
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
    org.baseplayer.project.SessionDocumentSync.writeGenome(
        genome.getName(), Settings.get().getLastAnnotation());
    org.baseplayer.project.ProjectSessionState.get().markDirty();
  }
}
