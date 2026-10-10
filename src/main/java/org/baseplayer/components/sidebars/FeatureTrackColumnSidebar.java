package org.baseplayer.components.sidebars;

import java.io.File;

import org.baseplayer.components.AppDialog;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.BedSetOperationDialog;
import org.baseplayer.features.BedSetOperations;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BedTrackOpen;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.MotifTrackOpen;
import org.baseplayer.features.Track;
import org.baseplayer.features.UcscTracksBrowser;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.samples.alignment.draw.TrackBodyCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.theme.AppTheme;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.util.List;

public class FeatureTrackColumnSidebar extends TrackColumnSidebar {

  private final FeatureTrackListPanel trackList;
  private final FeatureTrackViewportRegistry featureTrackViewportRegistry =
      ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
  private final DrawStackManager drawStackManager =
      ServiceRegistry.getInstance().getDrawStackManager();
  private final ReferenceGenomeService referenceGenomeService =
      ServiceRegistry.getInstance().getReferenceGenomeService();
  private TrackBodyCanvas featureTrackCanvas;

  private final TextField featureSearchField = new TextField();
  private final TextField featureReplaceField = new TextField();
  private final Button featureReplaceButton = new Button("Replace");
  private final Button featureReplaceToggleButton = new Button("▾");
  private final Label featureSearchStatus = new Label();
  private VBox featureSearchBar;
  private Region masterChromeResizeGrip;
  private HBox featureReplaceRow;
  private boolean featureReplaceExpanded;
  private boolean suppressSearchFieldSync;

  public FeatureTrackColumnSidebar(StackPane parent) {
    super(parent, ServiceRegistry.getInstance().getFeatureTrackViewportRegistry());
    trackList = new FeatureTrackListPanel(contentPane);
    installFeatureSearchBar();
  }

  public void setFeatureTrackCanvas(TrackBodyCanvas canvas) {
    featureTrackCanvas = canvas;
  }

  @Override
  protected TrackListPanel getTrackListPanel() {
    return trackList;
  }

  @Override
  protected void redrawAfterVisibleTrackRangeChange() {
    for (org.baseplayer.draw.DrawStack stack : drawStackManager.getStacks()) {
      if (stack.featureTrackCanvas != null) {
        stack.featureTrackCanvas.draw();
      }
      if (stack.featureAggregateCanvas != null) {
        stack.featureAggregateCanvas.draw();
      }
    }
    draw();
  }

  @Override
  protected String getTitle() {
    return "Feature Tracks";
  }

  @Override
  protected int getItemCount() {
    return featureTrackViewportRegistry.getDisplayedTrackCount();
  }

  @Override
  protected double estimateTrackBodyViewportHeightPixels() {
    if (!drawStackManager.isEmpty() && drawStackManager.getFirst().featureTrackCanvas != null) {
      double fromCanvas = drawStackManager.getFirst().featureTrackCanvas.getHeight();
      if (fromCanvas > 0) {
        return fromCanvas;
      }
    }
    double derived = featureTrackViewportRegistry.getTrackRowHeightPixels()
        * Math.max(1, featureTrackViewportRegistry.getVisibleTrackSlotCount());
    return Math.max(0, derived);
  }

  @Override
  protected String getVisibleTracksRangeLabelTitle() {
    return "Visible tracks";
  }

  @Override
  protected boolean shouldAutoExpandMasterHeader(int displayedTrackCount) {
    // Visible-range slider is pointless for a single track.
    return displayedTrackCount > 1;
  }

  @Override
  protected boolean usesMasterCanvasEdgeResize() {
    return false;
  }

  @Override
  protected ContextMenu buildAddTrackMenu() {
    ContextMenu menu = new ContextMenu();
    MenuItem bedItem = new MenuItem("BED file...");
    bedItem.setOnAction(e -> addBedTrack());
    MenuItem bigWigItem = new MenuItem("BigWig file...");
    bigWigItem.setOnAction(e -> addBigWigTrack());
    MenuItem jasparItem = new MenuItem("JASPAR / PFM file...");
    jasparItem.setOnAction(e -> addMotifTrack());
    MenuItem ucscBrowserItem = new MenuItem("Browse UCSC Tracks...");
    ucscBrowserItem.setOnAction(e -> showUcscTracksBrowser());
    menu.getItems().addAll(
        bedItem, bigWigItem, jasparItem, new SeparatorMenuItem(), ucscBrowserItem);
    return menu;
  }

  @Override
  protected ContextMenu buildSidebarSettingsMenu() {
    ContextMenu menu = new ContextMenu();
    menu.setStyle("-fx-background-color: #2B2B2B; -fx-border-color: #555; -fx-border-width: 1;");

    MenuItem unionItem = new MenuItem("Union…");
    unionItem.setOnAction(e -> runBedSetOperation(BedSetOperations.Op.UNION));
    MenuItem intersectItem = new MenuItem("Intersect…");
    intersectItem.setOnAction(e -> runBedSetOperation(BedSetOperations.Op.INTERSECT));
    MenuItem subtractItem = new MenuItem("Subtract…");
    subtractItem.setOnAction(e -> runBedSetOperation(BedSetOperations.Op.SUBTRACT));

    MenuItem removeAll = new MenuItem("Remove all feature tracks");
    removeAll.setOnAction(e -> {
      featureTrackViewportRegistry.clearFeatureTracks();
      draw();
      redrawAfterVisibleTrackRangeChange();
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    });
    MenuItem clearFilter = new MenuItem("Clear name filter");
    clearFilter.setOnAction(e -> {
      featureSearchField.clear();
      applyFeatureSearchFilter();
    });
    menu.getItems().addAll(
        unionItem,
        intersectItem,
        subtractItem,
        new SeparatorMenuItem(),
        removeAll,
        new SeparatorMenuItem(),
        clearFilter);
    return menu;
  }

  private void runBedSetOperation(BedSetOperations.Op op) {
    Window owner = headerPane.getScene() != null ? headerPane.getScene().getWindow() : null;
    List<BedTrack> candidates = BedSetOperationDialog.bedTracks(
        featureTrackViewportRegistry.getFeatureTracks());
    if (candidates.size() < 2) {
      AppDialog.info(
          owner,
          op.label(),
          "Need at least two BED tracks.",
          "Open another BED track, then run " + op.label() + " again.");
      return;
    }
    java.util.Set<BedTrack> preselected = new java.util.LinkedHashSet<>();
    for (Track track : featureTrackViewportRegistry.getSelectedTracks()) {
      if (track instanceof BedTrack bed) {
        preselected.add(bed);
      }
    }
    BedSetOperationDialog.show(owner, op, candidates, preselected).ifPresent(selected -> {
      try {
        BedTrack result = BedSetOperations.apply(op, selected);
        result.setVisible(true);
        for (BedTrack source : selected) {
          featureTrackViewportRegistry.setAggregateDisabled(source, true);
        }
        if (featureTrackCanvas != null) {
          featureTrackCanvas.addTrack(result);
        } else {
          featureTrackViewportRegistry.addFeatureTrack(result);
          featureTrackViewportRegistry.includeNewTracksAtEndAndResetRowHeight();
        }
        int newIndex = featureTrackViewportRegistry.getFeatureTrackIndex(result);
        if (newIndex >= 0) {
          featureTrackViewportRegistry.setSelectedTrackIndices(java.util.Set.of(newIndex));
        }
        draw();
        redrawAfterVisibleTrackRangeChange();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      } catch (RuntimeException ex) {
        AppDialog.info(owner, op.label() + " failed", ex.getMessage(), null);
      }
    });
  }

  private void installFeatureSearchBar() {
    featureSearchBar = buildFeatureSearchBar();

    headerPane.minHeightProperty().unbind();
    headerPane.maxHeightProperty().unbind();
    masterHeaderCanvas.widthProperty().unbind();
    masterHeaderCanvas.heightProperty().unbind();
    masterHeaderReactiveCanvas.widthProperty().unbind();
    masterHeaderReactiveCanvas.heightProperty().unbind();
    headerPane.getChildren().clear();

    StackPane masterCanvasHost = new StackPane(masterHeaderCanvas, masterHeaderReactiveCanvas);
    masterHeaderCanvas.widthProperty().bind(masterCanvasHost.widthProperty());
    masterHeaderCanvas.heightProperty().bind(
        featureTrackViewportRegistry.masterTrackHeightProperty());
    masterHeaderReactiveCanvas.widthProperty().bind(masterCanvasHost.widthProperty());
    masterHeaderReactiveCanvas.heightProperty().bind(
        featureTrackViewportRegistry.masterTrackHeightProperty());
    masterCanvasHost.minHeightProperty().bind(
        featureTrackViewportRegistry.masterTrackHeightProperty());
    masterCanvasHost.maxHeightProperty().bind(
        featureTrackViewportRegistry.masterTrackHeightProperty());
    masterCanvasHost.prefHeightProperty().bind(
        featureTrackViewportRegistry.masterTrackHeightProperty());
    masterCanvasHost.setMaxWidth(Double.MAX_VALUE);
    masterCanvasHost.setMinWidth(0);

    masterChromeResizeGrip = buildMasterChromeResizeGrip();
    VBox masterChrome = new VBox(masterCanvasHost, featureSearchBar, masterChromeResizeGrip);
    masterChrome.setFillWidth(true);
    masterChrome.setMaxWidth(Double.MAX_VALUE);
    headerPane.getChildren().add(masterChrome);
    headerPane.setMinHeight(Region.USE_PREF_SIZE);
    headerPane.setPrefHeight(Region.USE_COMPUTED_SIZE);
    headerPane.setMaxHeight(Region.USE_PREF_SIZE);

    featureTrackViewportRegistry.getFeatureTracks().addListener(
        (ListChangeListener<org.baseplayer.features.Track>) change ->
            Platform.runLater(this::updateFeatureSearchBarVisibility));
    featureSearchBar.heightProperty().addListener(
        (obs, o, n) -> syncFilterStripHeightToRegistry());
    masterChromeResizeGrip.heightProperty().addListener(
        (obs, o, n) -> syncFilterStripHeightToRegistry());
    updateFeatureSearchBarVisibility();
  }

  private Region buildMasterChromeResizeGrip() {
    Region grip = new Region();
    grip.setMinHeight(6);
    grip.setPrefHeight(6);
    grip.setMaxHeight(6);
    grip.setMaxWidth(Double.MAX_VALUE);
    grip.setCursor(Cursor.V_RESIZE);
    grip.setStyle(
        "-fx-background-color: transparent; -fx-border-color: transparent transparent "
            + AppTheme.chrome().borderHex() + " transparent; -fx-border-width: 0 0 1 0;");
    grip.setOnMousePressed(event -> {
      if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
        return;
      }
      beginMasterBandResize(event.getScreenY());
      event.consume();
    });
    grip.setOnMouseDragged(event -> {
      if (!isMasterBandResizing()) {
        return;
      }
      updateMasterBandResize(event.getScreenY());
      event.consume();
    });
    grip.setOnMouseReleased(event -> {
      if (isMasterBandResizing()) {
        endMasterBandResize();
        event.consume();
      }
    });
    return grip;
  }

  private void updateFeatureSearchBarVisibility() {
    if (featureSearchBar == null) {
      return;
    }
    boolean show = !featureTrackViewportRegistry.getFeatureTracks().isEmpty();
    featureSearchBar.setVisible(show);
    featureSearchBar.setManaged(show);
    if (!show) {
      setFeatureReplaceExpanded(false);
    }
    syncFilterStripHeightToRegistry();
  }

  private void syncFilterStripHeightToRegistry() {
    double h = 0;
    if (featureSearchBar != null && featureSearchBar.isManaged() && featureSearchBar.isVisible()) {
      h += featureSearchBar.getHeight();
    }
    if (masterChromeResizeGrip != null) {
      h += masterChromeResizeGrip.getHeight();
    }
    featureTrackViewportRegistry.setFilterStripHeightPixels(h);
  }

  private VBox buildFeatureSearchBar() {
    featureSearchField.setPromptText("Filter tracks…");
    featureSearchField.getStyleClass().add("filter-field");
    featureSearchField.setTooltip(new Tooltip(
        "Filter tracks by name. Use * as a wildcard (e.g. prefix_*). Expand ▾ to replace."));
    HBox.setHgrow(featureSearchField, Priority.ALWAYS);

    featureReplaceToggleButton.setTooltip(new Tooltip("Show replace"));
    styleCompactButton(featureReplaceToggleButton);
    featureReplaceToggleButton.setMinWidth(26);
    featureReplaceToggleButton.setPrefWidth(26);
    featureReplaceToggleButton.setOnAction(
        event -> setFeatureReplaceExpanded(!featureReplaceExpanded));

    Button clearButton = new Button("×");
    clearButton.setTooltip(new Tooltip("Clear filter"));
    styleCompactButton(clearButton);
    clearButton.setOnAction(event -> {
      featureSearchField.clear();
      applyFeatureSearchFilter();
    });

    featureReplaceField.setPromptText("Replace with…");
    featureReplaceField.getStyleClass().add("filter-field");
    HBox.setHgrow(featureReplaceField, Priority.ALWAYS);
    featureReplaceField.setTooltip(new Tooltip(
        "Replacement for the search match (empty = remove). With *, use $1, $2 for each wildcard."));

    featureReplaceButton.setTooltip(new Tooltip(
        "Replace the search match in currently listed track names"));
    styleCompactButton(featureReplaceButton);
    featureReplaceButton.setOnAction(event -> applyFeatureNameReplace());
    featureReplaceField.setOnAction(event -> applyFeatureNameReplace());

    featureSearchStatus.setStyle(
        "-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 10;");

    featureSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
      if (suppressSearchFieldSync) {
        return;
      }
      applyFeatureSearchFilter();
      updateReplaceUiVisibility();
    });
    featureSearchField.setOnAction(event -> applyFeatureSearchFilter());

    HBox filterRow = new HBox(4, featureSearchField, featureReplaceToggleButton, clearButton);
    filterRow.setAlignment(Pos.CENTER_LEFT);

    featureReplaceRow = new HBox(4, featureReplaceField, featureReplaceButton, featureSearchStatus);
    featureReplaceRow.setAlignment(Pos.CENTER_LEFT);
    featureReplaceRow.setManaged(false);
    featureReplaceRow.setVisible(false);

    VBox bar = new VBox(3, filterRow, featureReplaceRow);
    bar.setPadding(new Insets(4, 6, 6, 6));
    bar.setStyle("-fx-background-color: " + AppTheme.canvas().trackBackgroundHex()
        + "; -fx-border-color: " + AppTheme.chrome().borderHex()
        + "; -fx-border-width: 1 0 1 0;");
    bar.getStyleClass().add("feature-search-bar");
    return bar;
  }

  private static void styleCompactButton(Button button) {
    button.setStyle(
        "-fx-background-color: " + AppTheme.chrome().elevatedHex()
            + "; -fx-text-fill: " + AppTheme.chrome().textHex()
            + "; -fx-font-size: 11; -fx-padding: 2 8 2 8; -fx-border-color: "
            + AppTheme.chrome().strokeHex() + "; -fx-cursor: hand;");
  }

  private boolean hasSearchText() {
    String text = featureSearchField.getText();
    return text != null && !text.trim().isEmpty();
  }

  private void setFeatureReplaceExpanded(boolean expanded) {
    featureReplaceExpanded = expanded && hasSearchText();
    updateReplaceUiVisibility();
    if (featureReplaceExpanded) {
      Platform.runLater(featureReplaceField::requestFocus);
    }
  }

  private void updateReplaceUiVisibility() {
    boolean hasQuery = hasSearchText();
    featureReplaceToggleButton.setVisible(hasQuery);
    featureReplaceToggleButton.setManaged(hasQuery);
    if (!hasQuery) {
      featureReplaceExpanded = false;
    }
    featureReplaceToggleButton.setText(featureReplaceExpanded ? "▴" : "▾");
    featureReplaceToggleButton.setTooltip(new Tooltip(
        featureReplaceExpanded ? "Hide replace" : "Show replace"));
    if (featureReplaceRow != null) {
      featureReplaceRow.setVisible(featureReplaceExpanded);
      featureReplaceRow.setManaged(featureReplaceExpanded);
    }
  }

  private void applyFeatureSearchFilter() {
    String query = featureSearchField.getText() == null
        ? ""
        : featureSearchField.getText().trim();
    if (!query.isEmpty()) {
      featureTrackViewportRegistry.applyTextSubsetQuery(query);
      featureTrackViewportRegistry.showDefaultHeightWindowFromStart(
          estimateTrackBodyViewportHeightPixels());
      redrawAfterVisibleTrackRangeChange();
    } else if (featureTrackViewportRegistry.hasActiveFilterQuery()) {
      featureTrackViewportRegistry.clearTextFilter();
      featureTrackViewportRegistry.showDefaultHeightWindowFromStart(
          estimateTrackBodyViewportHeightPixels());
      redrawAfterVisibleTrackRangeChange();
    }
    updateSearchStatusLabel();
  }

  private void applyFeatureNameReplace() {
    String find = featureSearchField.getText() == null
        ? ""
        : featureSearchField.getText().trim();
    if (find.isEmpty()) {
      featureSearchStatus.setText("Enter text to find");
      return;
    }
    String replacement =
        featureReplaceField.getText() == null ? "" : featureReplaceField.getText();
    boolean scoped = featureTrackViewportRegistry.hasActiveFilterQuery();
    int renamed = featureTrackViewportRegistry.replaceInTrackNames(find, replacement, scoped);
    if (renamed > 0) {
      featureTrackViewportRegistry.applyTextSubsetQuery(find);
      featureTrackViewportRegistry.showDefaultHeightWindowFromStart(
          estimateTrackBodyViewportHeightPixels());
      redrawAfterVisibleTrackRangeChange();
      featureSearchStatus.setText(renamed + " renamed");
    } else {
      featureSearchStatus.setText("No matches");
    }
    updateReplaceUiVisibility();
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private void updateSearchStatusLabel() {
    if (!featureReplaceExpanded) {
      return;
    }
    if (featureTrackViewportRegistry.hasActiveFilterQuery()) {
      int shown = featureTrackViewportRegistry.getDisplayedTrackCount();
      int total = featureTrackViewportRegistry.getFeatureTracks().size();
      featureSearchStatus.setText(shown + "/" + total);
    }
  }

  private void addBedTrack() {
    if (featureTrackCanvas == null) {
      return;
    }
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BED File");
    File lastDirectory = UserPreferences.getLastDirectory("BED");
    if (lastDirectory != null) {
      try {
        fileChooser.setInitialDirectory(lastDirectory);
      } catch (IllegalArgumentException ignored) {
      }
    }
    fileChooser.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("BED files", "*.bed", "*.bed.gz"),
        new FileChooser.ExtensionFilter("All files", "*.*"));
    File file = fileChooser.showOpenDialog(headerPane.getScene().getWindow());
    if (file == null) {
      return;
    }
    UserPreferences.setLastDirectory("BED", file.getParentFile());
    try {
      BedTrackOpen.openInteractive(
              file.toPath(),
              headerPane.getScene() != null ? headerPane.getScene().getWindow() : null)
          .ifPresent(bedTrack -> {
            bedTrack.setVisible(true);
            featureTrackCanvas.addTrack(bedTrack);
            draw();
          });
    } catch (java.io.IOException ex) {
      System.err.println("Failed to load BED file: " + ex.getMessage());
    }
  }

  private void addBigWigTrack() {
    if (featureTrackCanvas == null) {
      return;
    }
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BigWig File");
    File lastDirectory = UserPreferences.getLastDirectory("BIGWIG");
    if (lastDirectory != null) {
      try {
        fileChooser.setInitialDirectory(lastDirectory);
      } catch (IllegalArgumentException ignored) {
      }
    }
    fileChooser.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("BigWig files", "*.bw", "*.bigwig", "*.bigWig"),
        new FileChooser.ExtensionFilter("All files", "*.*"));
    File file = fileChooser.showOpenDialog(headerPane.getScene().getWindow());
    if (file == null) {
      return;
    }
    UserPreferences.setLastDirectory("BIGWIG", file.getParentFile());
    try {
      BigWigTrack bigWigTrack = new BigWigTrack(file.toPath());
      bigWigTrack.setVisible(true);
      featureTrackCanvas.addTrack(bigWigTrack);
      draw();
    } catch (java.io.IOException ex) {
      System.err.println("Failed to load BigWig file: " + ex.getMessage());
    }
  }

  private void addMotifTrack() {
    if (featureTrackCanvas == null) {
      return;
    }
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open JASPAR / PFM File");
    File lastDirectory = UserPreferences.getLastDirectory("JASPAR");
    if (lastDirectory != null) {
      try {
        fileChooser.setInitialDirectory(lastDirectory);
      } catch (IllegalArgumentException ignored) {
      }
    }
    fileChooser.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter(
            "JASPAR / PFM files", "*.txt", "*.pfm", "*.jaspar"),
        new FileChooser.ExtensionFilter("All files", "*.*"));
    File file = fileChooser.showOpenDialog(headerPane.getScene().getWindow());
    if (file == null) {
      return;
    }
    UserPreferences.setLastDirectory("JASPAR", file.getParentFile());
    try {
      MotifTrackOpen.openInteractive(
              file.toPath(),
              headerPane.getScene() != null ? headerPane.getScene().getWindow() : null)
          .ifPresent(motifTrack -> {
            motifTrack.setVisible(true);
            featureTrackCanvas.addTrack(motifTrack);
            draw();
          });
    } catch (java.io.IOException ex) {
      System.err.println("Failed to load JASPAR/PFM file: " + ex.getMessage());
    }
  }

  private void showUcscTracksBrowser() {
    if (featureTrackCanvas == null
        || headerPane.getScene() == null
        || headerPane.getScene().getWindow() == null) {
      return;
    }
    String genome = "hg38";
    if (referenceGenomeService.hasGenome()) {
      String name = referenceGenomeService.getCurrentGenome().getName();
      genome = switch (name.toLowerCase()) {
        case "grch38" -> "hg38";
        case "grch37" -> "hg19";
        case "grcm38" -> "mm10";
        case "grcm39" -> "mm39";
        default -> name.toLowerCase();
      };
    }
    new UcscTracksBrowser(
        (javafx.stage.Stage) headerPane.getScene().getWindow(),
        featureTrackCanvas,
        genome).show();
  }
}
