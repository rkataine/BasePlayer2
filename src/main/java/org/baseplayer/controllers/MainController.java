package org.baseplayer.controllers;
import java.util.List;

import org.baseplayer.components.AnnotationOptionsDialog;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.FeatureTracksSidebar;
import org.baseplayer.features.draw.SidebarPanel;
import org.baseplayer.services.EventCoordinator;
import org.baseplayer.services.InitializationService;
import org.baseplayer.genome.ReferenceGenome;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.BaseUtils;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.SplitPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;

public class MainController {
  @FXML private SplitPane alignmentSplitPane;
  @FXML private SplitPane chromosomeSplitPane;
  @FXML private SplitPane chromSplit;
  @FXML private SplitPane drawSplit;
  @FXML private StackPane drawSideBarStackPane;
  @FXML private SplitPane mainSplit;
  @FXML private AnchorPane chromSideBar;
  @FXML private SplitPane drawSideBar;
  @FXML private AnchorPane chromPane;
  @FXML private AnchorPane featureTracksPane;
  @FXML private SplitPane featureTracksSplit;
  @FXML private StackPane featureTracksSideBarPane;
  @FXML private SplitPane featureTracksContentSplit;
  @FXML private ComboBox<ReferenceGenome> referenceComboBox;
  @FXML private ComboBox<String> annotationComboBox;
  @FXML private Button annotationOptionsButton;
  public Canvas drawSideBarCanvas; 
  public static SplitPane chromSplitPane;
  public static SplitPane drawPane;
  public static SplitPane featureTracksContentPane;
  private FeatureTracksSidebar featureTracksSidebar;
 
  public static boolean dividerHovered;
  public static boolean isActive = false;
  Runtime instance = Runtime.getRuntime();
  IntegerProperty memoryUsage = new SimpleIntegerProperty(0);
  SidebarPanel sidebarPanel;
  
  public static boolean showOnlyCancerGenes = false;
  
  // Services
  private final InitializationService initializationService;
  private final EventCoordinator eventCoordinator;
  private static final org.baseplayer.services.DrawStackManager stackManager =
      ServiceRegistry.getInstance().getDrawStackManager();
  
  // Internal list alias — external callers use DrawStackManager
  private static final List<DrawStack> drawStacks = stackManager.getStacksMutable();
  
  // Unified sidebar controller for all horizontal split panes
  private final SidebarController sidebarController = new SidebarController();
  
  public MainController() {
    // Initialize services from registry
    this.initializationService = new InitializationService();
    this.eventCoordinator = new EventCoordinator(drawStacks);
  }
  
  public void initialize() {
      chromSplitPane = chromosomeSplitPane;
      drawPane = alignmentSplitPane;
      featureTracksContentPane = featureTracksContentSplit;
      sidebarPanel = new SidebarPanel(drawSideBarStackPane);
      eventCoordinator.setSidebarPanel(sidebarPanel);
      
      // Setup unified sidebar controller for all horizontal split panes
      sidebarController.addPane(chromSplit);
      sidebarController.addPane(featureTracksSplit);
      sidebarController.addPane(drawSplit);
      
      addStack(true);  // Create stack first
      
      // Setup feature tracks sidebar
      setupFeatureTracksSidebar();
      
      loadAvailableGenomes(); // Then load genomes which will update stack sizes
      
      addMemUpdateListener();
      eventCoordinator.setupDrawUpdateListener(memoryUsage);
      setWindowSizeListener();
      
      // Lock sidebar and chrom pane sizes after window is shown
      mainSplit.sceneProperty().addListener((obs, oldScene, newScene) -> {
        if (newScene != null) {
          newScene.windowProperty().addListener((obs2, oldWindow, newWindow) -> {
            if (newWindow != null) {
              newWindow.setOnShown(e -> {
                javafx.application.Platform.runLater(() -> {
                  sidebarController.setActive(true);
                  sidebarController.lockConstraints();
                  lockSidebarConstraints();
                });
              });
            }
          });
        }
      });
  }

  private void setupFeatureTracksSidebar() {
    // Create the sidebar - it will use the first stack's canvas initially
    featureTracksSidebar = new FeatureTracksSidebar(featureTracksSideBarPane);
    eventCoordinator.setFeatureTracksSidebar(featureTracksSidebar);
    
    if (!drawStacks.isEmpty()) {
      featureTracksSidebar.setFeatureTracksCanvas(drawStacks.get(0).featureTracksCanvas);
      
      // Update pane height when collapsed state changes
      drawStacks.get(0).featureTracksCanvas.setOnCollapsedChanged(() -> {
        updateFeatureTracksPaneHeight();
        featureTracksSidebar.draw();
      });
    }
    updateFeatureTracksPaneHeight();
  }
  
  private void updateFeatureTracksPaneHeight() {
    if (drawStacks.isEmpty()) return;
    double height = drawStacks.get(0).featureTracksCanvas.getPreferredHeight();
    featureTracksPane.setPrefHeight(height);
    featureTracksPane.setMinHeight(height);
    featureTracksSidebar.draw();
  }
  
  private void loadAvailableGenomes() {
    List<ReferenceGenome> genomes = initializationService.loadAvailableGenomes();
    referenceComboBox.getItems().addAll(genomes);
    
    referenceComboBox.setOnAction(e -> onReferenceGenomeSelected());
    
    // Load available annotations
    loadAvailableAnnotations();
    
    if (!referenceComboBox.getItems().isEmpty()) {
      referenceComboBox.getSelectionModel().selectFirst();
      onReferenceGenomeSelected();
    }
  }
  
  private void loadAvailableAnnotations() {
    List<String> annotations = initializationService.loadAvailableAnnotations("GRCh38");
    annotationComboBox.getItems().addAll(annotations);
    
    // Select default annotation if available
    if (!annotationComboBox.getItems().isEmpty()) {
      String defaultAnnotation = initializationService.findDefaultAnnotation(annotations);
      if (defaultAnnotation != null) {
        annotationComboBox.getSelectionModel().select(defaultAnnotation);
      }
    }
  }
  
  private void onReferenceGenomeSelected() {
    ReferenceGenome genome = referenceComboBox.getValue();
    initializationService.selectReferenceGenome(genome);
  }
  
  public static void zoomout() {
    DrawStack hover = stackManager.getHoverStack();
    if (hover == null) return;
    hover.alignmentCanvas.zoomAnimation(1, hover.chromSize);
  }
  
  void addMemUpdateListener() {
    memoryUsage.addListener((observable, oldValue, newValue) -> {
      int maxMem = BaseUtils.toMegabytes.apply(instance.maxMemory());
      MenuBarController.updateMemoryBar(newValue.intValue(), maxMem);
    });
  }
  public static void addStack(boolean add) {
    if (add) {
      // Determine chromosome for new stack
      String[] defaultChroms = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "X", "Y"};
      String chrom = defaultChroms[Math.min(drawStacks.size(), defaultChroms.length - 1)];
      
      DrawStack drawStack = new DrawStack(chrom);
      
      // Set chromosome list if reference genome is loaded
      ReferenceGenomeService refService = ServiceRegistry.getInstance().getReferenceGenomeService();
      if (refService.hasGenome()) {
        drawStack.setChromosomeList(refService.getCurrentGenome().getStandardChromosomeNames());
      }
      
      drawStacks.add(drawStack);
      chromSplitPane.getItems().add(drawStack.chromContainer);
      featureTracksContentPane.getItems().add(drawStack.featureTracksStack);
      drawPane.getItems().add(drawStack.drawStack);
      
      // Update visibility of controls on all stacks
      for (DrawStack stack : drawStacks) {
        stack.updateControlsVisibility();
      }
    } else {
      if (drawStacks.size() < 2) return;
      drawStacks.removeLast();
      drawPane.getItems().removeLast();
      featureTracksContentPane.getItems().removeLast();
      chromSplitPane.getItems().removeLast();
      
      // Update visibility of controls on all stacks
      for (DrawStack stack : drawStacks) {
        stack.updateControlsVisibility();
      }
    }
    setDividerListeners();
    double[] drawPositions = new double[drawPane.getItems().size() - 1];
    for (int i = 0; i < drawStacks.size() - 1; i++) {
      drawPositions[i] = (i + 1) / (double)drawStacks.size();
    }
    drawPane.setDividerPositions(drawPositions);
    featureTracksContentPane.setDividerPositions(drawPositions);
    chromSplitPane.setDividerPositions(drawPositions);
  
  }
  public static void addStackAtPosition(String chrom, int position) {
    // Strip "chr" prefix if present to match internal naming
    if (chrom.startsWith("chr")) chrom = chrom.substring(3);
    final String finalChrom = chrom;

    // Create the new stack at the target chromosome
    DrawStack drawStack = new DrawStack(finalChrom);
    ReferenceGenomeService refService = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (refService.hasGenome()) {
      drawStack.setChromosomeList(refService.getCurrentGenome().getStandardChromosomeNames());
    }
    drawStack.chromosomeDropdown.setValue(finalChrom);

    drawStacks.add(drawStack);
    chromSplitPane.getItems().add(drawStack.chromContainer);
    featureTracksContentPane.getItems().add(drawStack.featureTracksStack);
    drawPane.getItems().add(drawStack.drawStack);

    for (DrawStack stack : drawStacks) {
      stack.updateControlsVisibility();
    }
    setDividerListeners();
    double[] drawPositions = new double[drawPane.getItems().size() - 1];
    for (int i = 0; i < drawStacks.size() - 1; i++) {
      drawPositions[i] = (i + 1) / (double) drawStacks.size();
    }
    drawPane.setDividerPositions(drawPositions);
    featureTracksContentPane.setDividerPositions(drawPositions);
    chromSplitPane.setDividerPositions(drawPositions);

    // Zoom to mate position after layout settles
    Platform.runLater(() -> {
      double viewSize = 1000; // ~1kb window around mate
      double start = Math.max(1, position - viewSize / 2);
      double end = start + viewSize;
      drawStack.alignmentCanvas.zoomAnimation(start, end);
    });
  }
  
  public static void removeStack(DrawStack stackToRemove) {
    if (drawStacks.size() < 2) return;
    
    int index = drawStacks.indexOf(stackToRemove);
    if (index < 0) return;
    
    drawStacks.remove(index);
    chromSplitPane.getItems().remove(index);
    featureTracksContentPane.getItems().remove(index);
    drawPane.getItems().remove(index);
    
    // Update visibility of controls on all stacks
    for (DrawStack stack : drawStacks) {
      stack.updateControlsVisibility();
    }
    
    setDividerListeners();
    double[] drawPositions = new double[drawPane.getItems().size() - 1];
    for (int i = 0; i < drawStacks.size() - 1; i++) {
      drawPositions[i] = (i + 1) / (double)drawStacks.size();
    }
    drawPane.setDividerPositions(drawPositions);
    featureTracksContentPane.setDividerPositions(drawPositions);
    chromSplitPane.setDividerPositions(drawPositions);
  }
  
  void setWindowSizeListener() {
    mainSplit.setOnMouseEntered((MouseEvent event) -> {
        isActive = true;
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
    });
    mainSplit.setOnMouseExited((MouseEvent event) -> {  
        isActive = false;
    });
    alignmentSplitPane.setOnMouseExited((MouseEvent event) -> {  
        takeSnapshot();
    });
    alignmentSplitPane.setOnMouseEntered((MouseEvent event) -> {  
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
    });
  }
  
  void lockSidebarConstraints() {
    // Prevent sidebars and chrom pane from resizing when parent resizes,
    // but still allow manual divider adjustment
    javafx.scene.control.SplitPane.setResizableWithParent(chromSideBar, false);
    javafx.scene.control.SplitPane.setResizableWithParent(drawSideBar, false);
    javafx.scene.control.SplitPane.setResizableWithParent(featureTracksSideBarPane, false);
    javafx.scene.control.SplitPane.setResizableWithParent(chromPane, false);
  }
  static void setDividerListeners() {
    EventCoordinator.synchronizeDividers(drawPane, chromSplitPane, featureTracksContentPane);
  }
  void takeSnapshot() {
    eventCoordinator.takeCanvasSnapshots();
  }
  
  @FXML
  @SuppressWarnings("unused")
  private void showAnnotationOptions() {
    AnnotationOptionsDialog.show(annotationOptionsButton.getScene().getWindow());
  }
  
  /**
   * Get the feature tracks canvas (from the first draw stack).
   * Used by SampleDataManager to add BED/BigWig tracks.
   */
  public static org.baseplayer.features.FeatureTracksCanvas getFeatureTracksCanvas() {
    if (drawStacks.isEmpty()) return null;
    return drawStacks.get(0).featureTracksCanvas;
  }
}
