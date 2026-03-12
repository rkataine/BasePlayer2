package org.baseplayer.draw;

import org.baseplayer.controllers.MainController;
import org.baseplayer.features.FeatureTrack;
import org.baseplayer.features.FeatureTracksCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.draw.CytobandCanvas;
import org.baseplayer.genome.gene.draw.ChromosomeCanvas;
import org.baseplayer.io.APIs.UcscApiClient;
import org.baseplayer.io.GnomadDataParser;
import org.baseplayer.samples.alignment.FetchManager;
import org.baseplayer.samples.alignment.draw.AlignmentCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.NavigationState;
import org.baseplayer.services.ServiceRegistry;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class DrawStack {
  public String chromosome = "1";
  public double chromSize;
  public double start;
  public double end;
  public double viewLength;
  public double pixelSize = 0;
  public double scale = 0;

  /** Per-stack navigation/rendering state. Mutated only by the owning canvas. */
  public final NavigationState nav = new NavigationState();
  
  // Services
  private final ReferenceGenomeService referenceGenomeService;
  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
  
  public VBox chromContainer = new VBox();  // Container for cytoband + chrom stack
  public StackPane chromStack = new StackPane(); 
  public ScrollPane chromScrollPane;  // Scroll pane for gene canvas vertical scrolling
  public StackPane drawStack = new StackPane();
  public StackPane featureTracksStack = new StackPane();  // Container for feature tracks
  public CytobandCanvas cytobandCanvas;
  public ChromosomeCanvas chromosomeCanvas;
  public AlignmentCanvas alignmentCanvas;
  public FeatureTracksCanvas featureTracksCanvas;
  public ComboBox<String> chromosomeDropdown;
  public Label closeButton;

  public double middlePos() { return start + (end - start) / 2; }

  public DrawStack() {
    this("1");
  }
  
  public DrawStack(String chrom) {
    this.chromosome = chrom;
    
    // Initialize services
    ServiceRegistry services = ServiceRegistry.getInstance();
    this.referenceGenomeService = services.getReferenceGenomeService();
    
    // Initialize chromosome size first, before creating any canvas objects
    updateChromosomeSize();
    
    chromContainer.setMinSize(0, 0);
    chromStack.setMinSize(0, 0);
    drawStack.setMinSize(0, 0);

    // Create chromosome dropdown
    chromosomeDropdown = new ComboBox<>();
    chromosomeDropdown.getStyleClass().add("minimal-combo-box");
    chromosomeDropdown.setStyle("-fx-background-color: rgba(30, 30, 30, 0.95); -fx-background-radius: 3;");
    chromosomeDropdown.setPrefWidth(60);
    chromosomeDropdown.setMaxWidth(60);
    chromosomeDropdown.setOnAction(e -> onChromosomeSelected());
    StackPane.setAlignment(chromosomeDropdown, Pos.TOP_LEFT);
    StackPane.setMargin(chromosomeDropdown, new Insets(3, 0, 0, 5));
    
    // Create close button (hidden by default)
    closeButton = new Label("✕");
    closeButton.setStyle("-fx-background-color: rgba(30, 30, 30, 0.9); -fx-background-radius: 3; -fx-text-fill: #aaaaaa; -fx-padding: 2 6 2 6; -fx-cursor: hand;");
    closeButton.setVisible(false);
    closeButton.setOnMouseEntered(e -> closeButton.setStyle("-fx-background-color: rgba(200, 50, 50, 0.9); -fx-background-radius: 3; -fx-text-fill: white; -fx-padding: 2 6 2 6; -fx-cursor: hand;"));
    closeButton.setOnMouseExited(e -> closeButton.setStyle("-fx-background-color: rgba(30, 30, 30, 0.9); -fx-background-radius: 3; -fx-text-fill: #aaaaaa; -fx-padding: 2 6 2 6; -fx-cursor: hand;"));
    closeButton.setOnMouseClicked(e -> MainController.removeStack(this));
    StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
    StackPane.setMargin(closeButton, new Insets(3, 5, 0, 0));

    // Create cytoband canvas (fixed height at top, wrapped in Pane for sizing)
    cytobandCanvas = new CytobandCanvas(this);
    Pane cytoWrapper = new Pane(cytobandCanvas);
    cytoWrapper.setMinHeight(CytobandCanvas.PREFERRED_HEIGHT);
    cytoWrapper.setMaxHeight(CytobandCanvas.PREFERRED_HEIGHT);
    cytoWrapper.setPrefHeight(CytobandCanvas.PREFERRED_HEIGHT);
    cytobandCanvas.widthProperty().bind(cytoWrapper.widthProperty());
    cytobandCanvas.setHeight(CytobandCanvas.PREFERRED_HEIGHT);
    
    // Create gene/reference canvas (fills remaining space, with vertical scrolling)
    StackPane canvasPane = new StackPane();
    canvasPane.setMinSize(0, 0);
    chromosomeCanvas = new ChromosomeCanvas(new Canvas(), canvasPane, this);
    canvasPane.getChildren().addAll(chromosomeCanvas, chromosomeCanvas.getReactiveCanvas());
    
    // Wrap canvas in ScrollPane for vertical scrolling when many gene rows
    chromScrollPane = new ScrollPane(canvasPane);
    chromScrollPane.setFitToWidth(true);
    chromScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    chromScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    chromScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
    chromScrollPane.setPannable(false);
    
    // Redraw when scroll position changes so position indicators stay at bottom
    chromScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> chromosomeCanvas.draw());
    
    // Overlay dropdown and close button on top of ScrollPane
    chromStack.getChildren().addAll(chromScrollPane, chromosomeDropdown, closeButton);
    VBox.setVgrow(chromStack, Priority.ALWAYS);
    
    // Assemble the container: cytoband at top, chromStack below
    chromContainer.getChildren().addAll(cytoWrapper, chromStack);
    
    alignmentCanvas = new AlignmentCanvas(new Canvas(), drawStack, this);
    drawStack.getChildren().addAll(alignmentCanvas, alignmentCanvas.getReactiveCanvas());
    
    // Create feature tracks canvas with default conservation and gnomAD tracks
    featureTracksStack.setMinSize(0, 0);
    featureTracksCanvas = new FeatureTracksCanvas(new Canvas(), featureTracksStack, this);
    featureTracksStack.getChildren().addAll(featureTracksCanvas, featureTracksCanvas.getReactiveCanvas());
    
    // Add default tracks
    FeatureTrack conservationTrack = new FeatureTrack(
        "PhyloP Conservation", "UCSC API", UcscApiClient::fetchConservation);
    conservationTrack.setCoordinateBase(0); // UCSC is 0-based
    conservationTrack.setVisible(false);
    featureTracksCanvas.addTrack(conservationTrack);

    GnomadDataParser gnomadParser = new GnomadDataParser();
    FeatureTrack gnomadTrack = new FeatureTrack(
        "gnomAD Variants", "gnomAD v4", gnomadParser::fetch);
    gnomadTrack.setCoordinateBase(1); // gnomAD uses 1-based VCF coordinates
    gnomadTrack.setPopupContentBuilder(gnomadParser::buildPopupContent);
    gnomadTrack.setVisible(false);
    featureTracksCanvas.addTrack(gnomadTrack);
    
    featureTracksCanvas.setCollapsed(false);
    
    // Show close button on hover
    chromContainer.setOnMouseEntered(e -> updateControlsVisibility());
    chromContainer.setOnMouseExited(e -> closeButton.setVisible(false));
    drawStack.setOnMouseEntered(e -> updateControlsVisibility());
    drawStack.setOnMouseExited(e -> closeButton.setVisible(false));
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
      chromosomeDropdown.setValue(chromosome);
    } else if (!chromosomes.isEmpty()) {
      chromosome = chromosomes.get(0);
      chromosomeDropdown.setValue(chromosome);
    }
    // Genome just became available (or changed) — re-read the actual chromosome size.
    updateChromosomeSize();
    alignmentCanvas.setStartEnd(1.0, chromSize + 1);
    chromosomeCanvas.setStartEnd(1.0, chromSize + 1);
  }
  
  private void onChromosomeSelected() {
    String selected = chromosomeDropdown.getValue();
    if (selected == null || selected.equals(chromosome)) return;
    
    // Cancel all in-flight fetches before switching chromosome
    FetchManager.get().cancelAll();
    
    chromosome = selected;
    updateChromosomeSize();
    alignmentCanvas.setStartEnd(1.0, chromSize + 1);
    chromosomeCanvas.setStartEnd(1.0, chromSize + 1);
  }

  /**
   * Switch this stack to the given chromosome, updating all coordinates.
   * Uses the loaded reference genome for the chromosome size.
   * Does nothing if {@code chrom} is already the current chromosome.
   */
  public void switchToChromosome(String chrom) {
    if (chrom == null || chrom.equals(chromosome)) return;
    FetchManager.get().cancelAll();
    chromosome = chrom;
    updateChromosomeSize();
    chromosomeDropdown.setValue(chrom);
    alignmentCanvas.setStartEnd(1.0, chromSize + 1);
    chromosomeCanvas.setStartEnd(1.0, chromSize + 1);
  }
  
  private void updateChromosomeSize() {
    if (referenceGenomeService.hasGenome()) {
      chromSize = referenceGenomeService.getCurrentGenome().getChromosomeLength(chromosome);
    } else {
      // No reference genome loaded yet; use a large generic fallback.
      chromSize = 1_000_000_000L;
    }
    start = 1;
    end = chromSize + 1;
    viewLength = chromSize;
  }
  

}
