package org.baseplayer.draw;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.baseplayer.SharedModel;
import org.baseplayer.controllers.MainController;
import org.baseplayer.tracks.ConservationTrack;
import org.baseplayer.tracks.FeatureTracksCanvas;
import org.baseplayer.tracks.GnomadTrack;
import org.baseplayer.variant.Variant;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

public class DrawStack {
  public String chromosome = "1";
  public double chromSize;
  public double start;
  public double end;
  public double viewLength;
  public double pixelSize = 0;
  public double scale = 0;
  public VBox chromContainer = new VBox();  // Container for cytoband + chrom stack
  public StackPane chromStack = new StackPane(); 
  public StackPane drawStack = new StackPane();
  public StackPane featureTracksStack = new StackPane();  // Container for feature tracks
  public DrawCytoband cytobandCanvas;
  public DrawChromData chromCanvas;
  public DrawSampleData drawCanvas;
  public FeatureTracksCanvas featureTracksCanvas;
  public ComboBox<String> chromosomeDropdown;
  public Label closeButton;

  public Variant[] variants;
  public double middlePos() { return start + (end - start) / 2; }

  // Simulated variants per chromosome
  private static final Map<String, Variant[]> simulatedVariants = new HashMap<>();
  private static boolean variantsGenerated = false;
  
  // Standard chromosome sizes (GRCh38)
  public static final Map<String, Long> CHROMOSOME_SIZES = new HashMap<>();
  static {
    CHROMOSOME_SIZES.put("1", 249250621L);
    CHROMOSOME_SIZES.put("2", 243199373L);
    CHROMOSOME_SIZES.put("3", 198022430L);
    CHROMOSOME_SIZES.put("4", 191154276L);
    CHROMOSOME_SIZES.put("5", 180915260L);
    CHROMOSOME_SIZES.put("6", 171115067L);
    CHROMOSOME_SIZES.put("7", 159138663L);
    CHROMOSOME_SIZES.put("8", 146364022L);
    CHROMOSOME_SIZES.put("9", 141213431L);
    CHROMOSOME_SIZES.put("10", 135534747L);
    CHROMOSOME_SIZES.put("11", 135006516L);
    CHROMOSOME_SIZES.put("12", 133851895L);
    CHROMOSOME_SIZES.put("13", 115169878L);
    CHROMOSOME_SIZES.put("14", 107349540L);
    CHROMOSOME_SIZES.put("15", 102531392L);
    CHROMOSOME_SIZES.put("16", 90354753L);
    CHROMOSOME_SIZES.put("17", 81195210L);
    CHROMOSOME_SIZES.put("18", 78077248L);
    CHROMOSOME_SIZES.put("19", 59128983L);
    CHROMOSOME_SIZES.put("20", 63025520L);
    CHROMOSOME_SIZES.put("21", 48129895L);
    CHROMOSOME_SIZES.put("22", 51304566L);
    CHROMOSOME_SIZES.put("X", 155270560L);
    CHROMOSOME_SIZES.put("Y", 59373566L);
    CHROMOSOME_SIZES.put("MT", 16569L);
  }

  public DrawStack() {
    this("1");
  }
  
  public DrawStack(String chrom) {
    this.chromosome = chrom;
    
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
    cytobandCanvas = new DrawCytoband(this);
    Pane cytoWrapper = new Pane(cytobandCanvas);
    cytoWrapper.setMinHeight(DrawCytoband.PREFERRED_HEIGHT);
    cytoWrapper.setMaxHeight(DrawCytoband.PREFERRED_HEIGHT);
    cytoWrapper.setPrefHeight(DrawCytoband.PREFERRED_HEIGHT);
    cytobandCanvas.widthProperty().bind(cytoWrapper.widthProperty());
    cytobandCanvas.setHeight(DrawCytoband.PREFERRED_HEIGHT);
    
    // Create gene/reference canvas (fills remaining space)
    chromCanvas = new DrawChromData(new Canvas(), chromStack, this);
    chromStack.getChildren().addAll(chromCanvas, chromCanvas.getReactiveCanvas(), chromosomeDropdown, closeButton);
    VBox.setVgrow(chromStack, Priority.ALWAYS);
    
    // Assemble the container: cytoband at top, chromStack below
    chromContainer.getChildren().addAll(cytoWrapper, chromStack);
    
    drawCanvas = new DrawSampleData(new Canvas(), drawStack, this);
    drawStack.getChildren().addAll(drawCanvas, drawCanvas.getReactiveCanvas());
    
    // Create feature tracks canvas with default conservation and gnomAD tracks
    featureTracksStack.setMinSize(0, 0);
    featureTracksCanvas = new FeatureTracksCanvas(new Canvas(), featureTracksStack, this);
    featureTracksStack.getChildren().addAll(featureTracksCanvas, featureTracksCanvas.getReactiveCanvas());
    
    // Add default tracks
    ConservationTrack conservationTrack = new ConservationTrack();
    conservationTrack.setVisible(true);
    featureTracksCanvas.addTrack(conservationTrack);
    
    GnomadTrack gnomadTrack = new GnomadTrack();
    gnomadTrack.setVisible(true);
    featureTracksCanvas.addTrack(gnomadTrack);
    
    featureTracksCanvas.setCollapsed(false);
    
    // Show close button on hover
    chromContainer.setOnMouseEntered(e -> updateControlsVisibility());
    chromContainer.setOnMouseExited(e -> closeButton.setVisible(false));
    drawStack.setOnMouseEntered(e -> updateControlsVisibility());
    drawStack.setOnMouseExited(e -> closeButton.setVisible(false));
  }
  
  public void updateControlsVisibility() {
    boolean hasMultipleStacks = MainController.drawStacks.size() > 1;
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
  }
  
  private void onChromosomeSelected() {
    String selected = chromosomeDropdown.getValue();
    if (selected == null || selected.equals(chromosome)) return;
    
    chromosome = selected;
    updateChromosomeSize();
    
    // Load variants for this chromosome
    if (variantsGenerated) {
      variants = simulatedVariants.get(chromosome);
    }
    
    // Properly update coordinates and derived values
    drawCanvas.setStartEnd(1.0, chromSize + 1);
    chromCanvas.setStartEnd(1.0, chromSize + 1);
  }
  
  private void updateChromosomeSize() {
    // Use reference genome size if available, otherwise use default
    if (SharedModel.referenceGenome != null) {
      chromSize = SharedModel.referenceGenome.getChromosomeLength(chromosome);
    } else {
      chromSize = CHROMOSOME_SIZES.getOrDefault(chromosome, CHROMOSOME_SIZES.get("1"));
    }
    // Initialize start, end, viewLength based on chromosome size
    start = 1;
    end = chromSize + 1;
    viewLength = chromSize;
  }
  
  public static long getChromosomeSize(String chrom) {
    return CHROMOSOME_SIZES.getOrDefault(chrom, CHROMOSOME_SIZES.get("1"));
  }
  
  /**
   * Generate simulated variant data for all chromosomes.
   * Creates variants spread across each chromosome based on its size.
   */
  public static void generateSimulatedVariants(int samples, int variantsPerChrom) {
    if (variantsGenerated) return;
    
    // Initialize samples
    SharedModel.sampleList.clear();
    for (int i = 1; i <= samples; i++) {
      SharedModel.sampleList.add("Sample " + i);
    }
    SharedModel.lastVisibleSample = samples - 1;
    
    // Generate variants for each chromosome
    for (Map.Entry<String, Long> entry : CHROMOSOME_SIZES.entrySet()) {
      String chrom = entry.getKey();
      long size = entry.getValue();
      
      // Scale variant count based on chromosome size
      int numVariants = (int) (variantsPerChrom * (size / 248956422.0)); // Scale relative to chr1
      numVariants = Math.max(100, numVariants); // At least 100 variants
      
      Variant[] chromVariants = new Variant[numVariants];
      for (int v = 0; v < numVariants; v++) {
        int pos = (int) (Math.random() * size) + 1;
        int sampleIdx = (int) (Math.random() * samples) + 1;
        double height = Math.random();
        chromVariants[v] = new Variant(sampleIdx, new Line(pos, 0, pos, height));
      }
      
      // Sort variants by position
      Arrays.sort(chromVariants, Comparator.comparing(variant -> variant.line.getStartX()));
      simulatedVariants.put(chrom, chromVariants);
    }
    
    variantsGenerated = true;
  }
  
  public static boolean isVariantsGenerated() {
    return variantsGenerated;
  }
  
  public static Variant[] getSimulatedVariants(String chrom) {
    return simulatedVariants.get(chrom);
  }
  
  public void loadSimulatedVariants() {
    if (variantsGenerated) {
      variants = simulatedVariants.get(chromosome);
    }
  }
}
