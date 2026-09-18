package org.baseplayer.draw;

import org.baseplayer.controllers.MainController;
import org.baseplayer.features.DefaultFeatureTracks;
import org.baseplayer.genome.GenomicRegion;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.draw.CytobandCanvas;
import org.baseplayer.genome.gene.draw.ChromosomeCanvas;
import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.alignment.FetchManager;
import org.baseplayer.samples.alignment.draw.TrackBodyCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.NavigationState;
import org.baseplayer.services.ServiceRegistry;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

public class DrawStack {
  private static final double CHROM_DROPDOWN_MIN_WIDTH = 70;
  private static final double CHROM_DROPDOWN_MAX_WIDTH = 320;
  private static final double CHROM_DROPDOWN_CHROME_PADDING = 36;

  String chromosome = "1";
  public double chromSize;
  double start;
  double end;
  double viewLength;
  double pixelSize = 0;
  public double scale = 0;

  private final ObjectProperty<GenomicRegion> regionProperty = new SimpleObjectProperty<>();

  /** Per-stack navigation/rendering state. Mutated only by the owning canvas. */
  public final NavigationState nav = new NavigationState();
  
  // Services
  private final ReferenceGenomeService referenceGenomeService;
  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
  
  public VBox chromContainer = new VBox();  // Container for cytoband + chrom stack
  public StackPane chromStack = new StackPane(); 
  public ScrollPane chromScrollPane;  // Scroll pane for gene canvas vertical scrolling
  /** Sample column: master aggregate band above alignment body. */
  public VBox sampleColumn = new VBox();
  public StackPane masterStack = new StackPane();
  /** Sample body only (TrackBodyCanvas); no longer includes the master band. */
  public StackPane drawStack = new StackPane();
  public VBox featureColumn = new VBox();
  public StackPane featureMasterStack = new StackPane();
  public StackPane featureBodyStack = new StackPane();
  public CytobandCanvas cytobandCanvas;
  public ChromosomeCanvas chromosomeCanvas;
  public TrackBodyCanvas sampleTrackCanvas;
  public SampleAggregateCanvas sampleAggregateCanvas;
  public FeatureAggregateCanvas featureAggregateCanvas;
  public TrackBodyCanvas featureTrackCanvas;
  public ComboBox<String> chromosomeDropdown;
  public Label closeButton;

  public double middlePos() { return start + (end - start) / 2; }

  public ObjectProperty<GenomicRegion> regionProperty() { return regionProperty; }

  public GenomicRegion getRegion() { return regionProperty.get(); }

  public void setRegion(String chrom, long start, long end) {
    regionProperty.set(new GenomicRegion(chrom, start, end));
  }

  public String getChromosome() { return chromosome; }

  public double getViewStart() { return start; }

  public double getViewEnd() { return end; }

  public double getViewLength() { return viewLength; }

  public double getPixelSize() { return pixelSize; }

  public DrawStack() {
    this("1");
  }
  
  public DrawStack(String chrom) {
    this.chromosome = chrom;
    
    ServiceRegistry services = ServiceRegistry.getInstance();
    this.referenceGenomeService = services.getReferenceGenomeService();
    
    updateChromosomeSize();
    
    chromContainer.setMinSize(0, 0);
    chromStack.setMinSize(0, 0);
    sampleColumn.setMinSize(0, 0);
    drawStack.setMinSize(0, 0);

    chromosomeDropdown = new ComboBox<>();
    chromosomeDropdown.getStyleClass().add("minimal-combo-box");
    chromosomeDropdown.setStyle("-fx-background-color: rgba(30, 30, 30, 0.95); -fx-background-radius: 3;");
    chromosomeDropdown.setMinWidth(CHROM_DROPDOWN_MIN_WIDTH);
    chromosomeDropdown.setPrefWidth(120);
    chromosomeDropdown.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    
    chromosomeDropdown.setOnAction(e -> {
      String selected = chromosomeDropdown.getValue();
      if (selected != null) {
        onUserSelectedChromosome(selected);
      }
    });
    
    chromosomeDropdown.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
      if (event.getButton() == MouseButton.PRIMARY
          && !chromosomeDropdown.isShowing()
          && !chromosomeDropdown.getItems().isEmpty()) {
        chromosomeDropdown.show();
      }
    });
    StackPane.setAlignment(chromosomeDropdown, Pos.TOP_LEFT);
    StackPane.setMargin(chromosomeDropdown, new Insets(3, 0, 0, 5));
    
    closeButton = new Label("✕");
    closeButton.setStyle("-fx-background-color: rgba(30, 30, 30, 0.9); -fx-background-radius: 3; -fx-text-fill: #aaaaaa; -fx-padding: 2 6 2 6; -fx-cursor: hand;");
    closeButton.setVisible(false);
    closeButton.setOnMouseEntered(e -> closeButton.setStyle("-fx-background-color: rgba(200, 50, 50, 0.9); -fx-background-radius: 3; -fx-text-fill: white; -fx-padding: 2 6 2 6; -fx-cursor: hand;"));
    closeButton.setOnMouseExited(e -> closeButton.setStyle("-fx-background-color: rgba(30, 30, 30, 0.9); -fx-background-radius: 3; -fx-text-fill: #aaaaaa; -fx-padding: 2 6 2 6; -fx-cursor: hand;"));
    closeButton.setOnMouseClicked(e -> MainController.removeStack(this));
    StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
    StackPane.setMargin(closeButton, new Insets(3, 5, 0, 0));

    cytobandCanvas = new CytobandCanvas(this);
    Pane cytoWrapper = new Pane(cytobandCanvas);
    cytoWrapper.setMinHeight(CytobandCanvas.PREFERRED_HEIGHT);
    cytoWrapper.setMaxHeight(CytobandCanvas.PREFERRED_HEIGHT);
    cytoWrapper.setPrefHeight(CytobandCanvas.PREFERRED_HEIGHT);
    cytobandCanvas.widthProperty().bind(cytoWrapper.widthProperty());
    cytobandCanvas.setHeight(CytobandCanvas.PREFERRED_HEIGHT);
    
    StackPane canvasPane = new StackPane();
    canvasPane.setMinSize(0, 0);
    chromosomeCanvas = new ChromosomeCanvas(new Canvas(), canvasPane, this);
    canvasPane.getChildren().addAll(chromosomeCanvas, chromosomeCanvas.getReactiveCanvas());
    
    chromScrollPane = new ScrollPane(canvasPane);
    chromScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    chromScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    chromScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
    chromScrollPane.setPannable(false);
    canvasPane.prefWidthProperty().bind(chromScrollPane.widthProperty());
    canvasPane.minWidthProperty().bind(chromScrollPane.widthProperty());
    canvasPane.maxWidthProperty().bind(chromScrollPane.widthProperty());
    
    chromScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> chromosomeCanvas.draw());
    chromScrollPane.heightProperty().addListener((obs, oldVal, newVal) -> chromosomeCanvas.draw());
    
    chromStack.getChildren().addAll(chromScrollPane, chromosomeDropdown, closeButton);
    VBox.setVgrow(chromStack, Priority.ALWAYS);
    
    chromContainer.getChildren().addAll(cytoWrapper, chromStack);
    
    sampleColumn.setMinSize(0, 0);
    masterStack.setMinSize(0, 0);
    drawStack.setMinSize(0, 0);

    var sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    masterStack.minHeightProperty().bind(sampleRegistry.masterTrackHeightProperty());
    masterStack.maxHeightProperty().bind(sampleRegistry.masterTrackHeightProperty());
    masterStack.prefHeightProperty().bind(sampleRegistry.masterTrackHeightProperty());

    sampleTrackCanvas = new TrackBodyCanvas(new Canvas(), drawStack, this, sampleRegistry);
    drawStack.getChildren().addAll(sampleTrackCanvas, sampleTrackCanvas.getReactiveCanvas());

    sampleAggregateCanvas = new SampleAggregateCanvas(
        new Canvas(), masterStack, this, sampleTrackCanvas.getCoverageDrawer());
    masterStack.getChildren().addAll(
        sampleAggregateCanvas, sampleAggregateCanvas.getReactiveCanvas());

    VBox.setVgrow(drawStack, Priority.ALWAYS);
    sampleColumn.getChildren().addAll(masterStack, drawStack);

    featureColumn.setMinSize(0, 0);
    featureMasterStack.setMinSize(0, 0);
    featureBodyStack.setMinSize(0, 0);
    var featureViewportRegistry =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    featureMasterStack.minHeightProperty().bind(
        featureViewportRegistry.masterBandHeightProperty());
    featureMasterStack.maxHeightProperty().bind(
        featureViewportRegistry.masterBandHeightProperty());
    featureMasterStack.prefHeightProperty().bind(
        featureViewportRegistry.masterBandHeightProperty());

    featureAggregateCanvas =
        new FeatureAggregateCanvas(new Canvas(), featureMasterStack, this);
    featureMasterStack.getChildren().addAll(
        featureAggregateCanvas, featureAggregateCanvas.getReactiveCanvas());

    featureTrackCanvas = new TrackBodyCanvas(new Canvas(), featureBodyStack, this, featureViewportRegistry);
    featureBodyStack.getChildren().addAll(featureTrackCanvas, featureTrackCanvas.getReactiveCanvas());
    VBox.setVgrow(featureBodyStack, Priority.ALWAYS);
    featureColumn.getChildren().addAll(featureMasterStack, featureBodyStack);

    // Seed defaults only for a fresh untitled session. An open project file owns
    // its feature-track list — absence there must not be refilled by a new stack.
    if (featureTrackCanvas.getTracks().isEmpty()
        && ProjectSessionState.get().getFile() == null) {
      featureTrackCanvas.addTrack(DefaultFeatureTracks.createPhyloP());
      featureTrackCanvas.addTrack(DefaultFeatureTracks.createGnomad());
    }

    chromContainer.setOnMouseEntered(e -> updateControlsVisibility());
    chromContainer.setOnMouseExited(e -> closeButton.setVisible(false));
    sampleColumn.setOnMouseEntered(e -> updateControlsVisibility());
    sampleColumn.setOnMouseExited(e -> closeButton.setVisible(false));
    featureColumn.setOnMouseEntered(e -> updateControlsVisibility());
    featureColumn.setOnMouseExited(e -> closeButton.setVisible(false));
  }
  
  public void updateControlsVisibility() {
    boolean hasMultipleStacks = stackManager.getStacks().size() > 1;
    chromosomeDropdown.setVisible(hasMultipleStacks);
    closeButton.setVisible(hasMultipleStacks);
  }
  
  public void setChromosomeList(java.util.List<String> chromosomes) {
    chromosomeDropdown.getItems().clear();
    chromosomeDropdown.getItems().addAll(chromosomes);
    if (chromosomes.contains(chromosome)) {
      setChromosomeDropdownValueSilently(chromosome);
    } else if (!chromosomes.isEmpty()) {
      chromosome = chromosomes.get(0);
      setChromosomeDropdownValueSilently(chromosome);
    }
    updateChromosomeDropdownWidthByLongestContig();
    updateChromosomeSize();
    sampleTrackCanvas.setStartEnd(1.0, chromSize + 1);
    if (sampleAggregateCanvas != null) {
      sampleAggregateCanvas.setStartEnd(1.0, chromSize + 1);
    }
    if (featureAggregateCanvas != null) {
      featureAggregateCanvas.setStartEnd(1.0, chromSize + 1);
    }
    if (featureTrackCanvas != null) {
      featureTrackCanvas.setStartEnd(1.0, chromSize + 1);
    }
    chromosomeCanvas.setStartEnd(1.0, chromSize + 1);
  }
  
  private void onUserSelectedChromosome(String selected) {
    if (selected == null || selected.isBlank()) {
      return;
    }
    long chromLen = referenceGenomeService.hasGenome() 
      ? referenceGenomeService.getCurrentGenome().getChromosomeLength(selected)
      : 1_000_000_000L;
    navigateTo(selected, 1, chromLen + 1);
    VcfManager vcfManager = org.baseplayer.io.VcfManager.getInstance();
    vcfManager.loadRegionVariants(selected, 1, chromLen + 1, true);
  }

  public void navigateTo(String chrom, double start, double end) {
    if (!chrom.equals(chromosome)) {
      FetchManager.get().cancelAll();
      chromosome = chrom;
      updateChromosomeSize();
      setChromosomeDropdownValueSilently(chrom);
      // Drop previous chromosome's variants immediately; VcfManager reattaches after load.
      if (sampleTrackCanvas != null) {
        sampleTrackCanvas.clearVariantList();
      }
      if (sampleAggregateCanvas != null) {
        sampleAggregateCanvas.clearVariantList();
      }
    }
    sampleTrackCanvas.setStartEnd(start, end);
    if (sampleAggregateCanvas != null) {
      sampleAggregateCanvas.setStartEnd(start, end);
    }
    if (featureAggregateCanvas != null) {
      featureAggregateCanvas.setStartEnd(start, end);
    }
    if (featureTrackCanvas != null) {
      featureTrackCanvas.setStartEnd(start, end);
    }
    chromosomeCanvas.setStartEnd(start, end);
    
    setRegion(chrom, (long) start, (long) end);
    sampleTrackCanvas.zoomAnimation(start, end);
    org.baseplayer.project.ProjectSessionState.get().markDirty();
  }

  public void switchToChromosome(String chrom) {
    if (chrom == null) return;
    long chromLen = referenceGenomeService.hasGenome() 
      ? referenceGenomeService.getCurrentGenome().getChromosomeLength(chrom)
      : 1_000_000_000L;
    navigateTo(chrom, 1, chromLen + 1);
    VcfManager.getInstance().loadRegionVariants(chrom, 1, chromLen + 1, true);
  }

  private void setChromosomeDropdownValueSilently(String value) {
    var originalHandler = chromosomeDropdown.getOnAction();
    chromosomeDropdown.setOnAction(null);
    chromosomeDropdown.setValue(value);
    chromosomeDropdown.setOnAction(originalHandler);
  }

  private void updateChromosomeDropdownWidthByLongestContig() {
    double widestTextWidth = measureTextWidth(chromosome != null ? chromosome : "1");

    for (String contig : chromosomeDropdown.getItems()) {
      if (contig == null || contig.isBlank()) continue;
      double width = measureTextWidth(contig);
      if (width > widestTextWidth) {
        widestTextWidth = width;
      }
    }

    double targetWidth = widestTextWidth + CHROM_DROPDOWN_CHROME_PADDING;
    targetWidth = Math.max(CHROM_DROPDOWN_MIN_WIDTH, Math.min(CHROM_DROPDOWN_MAX_WIDTH, targetWidth));
    chromosomeDropdown.setPrefWidth(targetWidth);
    chromosomeDropdown.setMaxWidth(targetWidth);
  }

  private double measureTextWidth(String value) {
    Text text = new Text(value);
    Font font = Font.getDefault();
    if (chromosomeDropdown.getButtonCell() != null && chromosomeDropdown.getButtonCell().getFont() != null) {
      font = chromosomeDropdown.getButtonCell().getFont();
    }
    text.setFont(font);
    return Math.ceil(text.getLayoutBounds().getWidth());
  }
  
  private void updateChromosomeSize() {
		chromSize = 1_000_000;
    if (referenceGenomeService.hasGenome()) {
      chromSize = referenceGenomeService.getCurrentGenome().getChromosomeLength(chromosome);
    }
    start = 1;
    end = chromSize + 1;
    viewLength = chromSize;
  }
}
