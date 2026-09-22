package org.baseplayer.controllers;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.components.SampleTrackControls;
import org.baseplayer.components.sidebars.FeatureTrackColumnSidebar;
import org.baseplayer.components.sidebars.GenomeSidebar;
import org.baseplayer.components.sidebars.SampleTrackColumnSidebar;
import org.baseplayer.components.sidebars.SidebarController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.draw.ZoomController;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.services.EventCoordinator;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.InitializationService;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.TrackViewportRegistry;
import org.baseplayer.utils.BaseUtils;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.SplitPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

public class MainController {
  private static MainController instance;

  @FXML private SplitPane alignmentSplitPane;
  @FXML private SplitPane chromosomeSplitPane;
  @FXML private SplitPane chromSplit;
  @FXML private SplitPane drawSplit;
  @FXML private StackPane drawSideBarStackPane;
  @FXML private StackPane alignmentOverlayPane;
  @FXML private SplitPane mainSplit;
  @FXML private StackPane genomeSideBarPane;
  @FXML private SplitPane drawSideBar;
  @FXML private AnchorPane chromPane;
  @FXML private AnchorPane featureTracksPane;
  @FXML private SplitPane featureTracksSplit;
  @FXML private StackPane featureTracksSideBarPane;
  @FXML private SplitPane featureTracksContentSplit;
  public Canvas drawSideBarCanvas; 
  public static SplitPane chromSplitPane;
  public static SplitPane drawPane;
  public static SplitPane featureTracksContentPane;
  private FeatureTrackColumnSidebar featureTrackColumnSidebar;
  static SampleTrackColumnSidebar sidebarPanel;

  public static boolean dividerHovered;
  public static boolean isActive = false;
  Runtime instanceRuntime = Runtime.getRuntime();
  IntegerProperty memoryUsage = new SimpleIntegerProperty(0);
  
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
  private boolean updatingVerticalDividers = false;
  private boolean applyingDividers = false;
  private static final double FEATURE_MIN_HEIGHT_PADDING_PX = 12;

  // Shared glasspane over the alignment split area for cross-stack connector arcs.
  private Canvas crossStackOverlayCanvas;
  private GraphicsContext crossStackOverlayGc;
  private static Canvas sharedCrossStackOverlayCanvas;
  private static GraphicsContext sharedCrossStackOverlayGc;
  private static Object crossStackOverlayOwner;

  public MainController() {
    // Initialize services from registry
    this.initializationService = new InitializationService();
    this.eventCoordinator = new EventCoordinator(drawStacks);
  }

  public static MainController get() {
    return instance;
  }
  
  public void initialize() {
      instance = this;
      chromSplitPane = chromosomeSplitPane;
      drawPane = alignmentSplitPane;
      featureTracksContentPane = featureTracksContentSplit;
      
      // Set the main viewport StackPane in DrawStackManager EARLY, before creating sidebar
      // This makes it available to overlay components like LoadRegionButton
      stackManager.setAlignmentOverlayPane(alignmentOverlayPane);
      
      // Setup unified sidebar controller for all horizontal split panes
      sidebarController.addPane(chromSplit);
      sidebarController.addPane(featureTracksSplit);
      sidebarController.addPane(drawSplit);

      setupCrossStackOverlay();
      ZoomController.installGlass(mainSplit);
      SampleTrackControls.installGlassOverlay();
      
      addStack(true);  // Real DrawStack before sidebars that resolve stacks from manager

      new GenomeSidebar(genomeSideBarPane, initializationService);
      sidebarPanel = new SampleTrackColumnSidebar(drawSideBarStackPane);
      eventCoordinator.setSidebarPanel(sidebarPanel);
      sidebarPanel.draw();
      
      setupFeatureTrackColumnSidebar();
      
      addMemUpdateListener();
      eventCoordinator.setupDrawUpdateListener(memoryUsage);
      setupVerticalDividerBehavior();
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

  private void setupFeatureTrackColumnSidebar() {
    featureTrackColumnSidebar = new FeatureTrackColumnSidebar(featureTracksSideBarPane);
    eventCoordinator.setFeatureTrackColumnSidebar(featureTrackColumnSidebar);

    if (!drawStacks.isEmpty()) {
      featureTrackColumnSidebar.setFeatureTrackCanvas(drawStacks.get(0).featureTrackCanvas);
    }

    FeatureTrackViewportRegistry featureRegistry =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    int trackCount = featureRegistry.getDisplayedTrackCount();
    double bodyHeight = TrackViewportRegistry.DEFAULT_TRACK_ROW_HEIGHT_PIXELS
        * Math.max(1, trackCount);
    if (trackCount > 0) {
      featureRegistry.setVisibleTrackRange(0, trackCount - 1, bodyHeight);
    }

    if (featureTracksPane != null) {
      featureTracksPane.setMinHeight(0);
      featureTracksPane.setPrefHeight(82 + bodyHeight);
    }

    featureRegistry.getFeatureTracks().addListener((ListChangeListener<? super org.baseplayer.features.Track>) change ->
        Platform.runLater(this::enforceVerticalDividerBounds));

    featureTrackColumnSidebar.draw();
    enforceVerticalDividerBounds();
  }

  private void setupCrossStackOverlay() {
    if (alignmentOverlayPane == null) return;

    crossStackOverlayCanvas = new Canvas();
    crossStackOverlayCanvas.setManaged(false);
    crossStackOverlayCanvas.setMouseTransparent(true);
    crossStackOverlayCanvas.widthProperty().bind(alignmentOverlayPane.widthProperty());
    crossStackOverlayCanvas.heightProperty().bind(alignmentOverlayPane.heightProperty());
    crossStackOverlayGc = crossStackOverlayCanvas.getGraphicsContext2D();

    alignmentOverlayPane.getChildren().add(crossStackOverlayCanvas);

    sharedCrossStackOverlayCanvas = crossStackOverlayCanvas;
    sharedCrossStackOverlayGc = crossStackOverlayGc;
    crossStackOverlayOwner = null;

    alignmentOverlayPane.widthProperty().addListener((obs, o, n) -> clearCrossStackOverlay());
    alignmentOverlayPane.heightProperty().addListener((obs, o, n) -> clearCrossStackOverlay());
  }

  private static void clearCrossStackOverlay() {
    if (sharedCrossStackOverlayCanvas == null || sharedCrossStackOverlayGc == null) return;
    sharedCrossStackOverlayGc.clearRect(0, 0,
        sharedCrossStackOverlayCanvas.getWidth(),
        sharedCrossStackOverlayCanvas.getHeight());
  }

  public static void releaseCrossStackMateArc(Object owner) {
    if (owner != null && owner == crossStackOverlayOwner) {
      crossStackOverlayOwner = null;
      clearCrossStackOverlay();
    }
  }

  /** Force-clear any cross-stack connector regardless of current owner. */
  public static void clearCrossStackMateArc() {
    crossStackOverlayOwner = null;
    clearCrossStackOverlay();
  }

  public static void addLoadRegionButtonToViewport() {
    StackPane overlayPane = stackManager.getAlignmentOverlayPane();
    if (overlayPane == null) {
      return;
    }
    
    if (sidebarPanel == null || sidebarPanel.loadRegionButton == null) {
      return;
    }
    
    org.baseplayer.components.LoadRegionButton button = sidebarPanel.loadRegionButton;
    // Remove if already added
    if (overlayPane.getChildren().contains(button)) {
      return;
    }
    
    // Add to viewport overlay - position below legends on the left
    overlayPane.getChildren().add(button);
    StackPane.setAlignment(button, Pos.TOP_LEFT);
    StackPane.setMargin(button, new Insets(90, 0, 0, 4));  // 90px from top to place below legends
  }

  public static boolean drawCrossStackMateArc(Object owner,
                                              Node sourceNode, double sourceX, double sourceY,
                                              Node targetNode, double targetX, double targetY) {
    return drawCrossStackMateArc(owner, sourceNode, sourceX, sourceY, targetNode, targetX, targetY,
        Color.rgb(255, 180, 80, 0.94));
  }

  public static boolean drawCrossStackMateArc(Object owner,
                                              Node sourceNode, double sourceX, double sourceY,
                                              Node targetNode, double targetX, double targetY,
                                              Color arcColor) {
    if (sharedCrossStackOverlayCanvas == null || sharedCrossStackOverlayGc == null) return false;
    if (sourceNode == null || targetNode == null) {
      if (owner == crossStackOverlayOwner) clearCrossStackMateArc();
      return false;
    }

    Point2D sourceScene = sourceNode.localToScene(sourceX, sourceY);
    Point2D targetScene = targetNode.localToScene(targetX, targetY);
    if (sourceScene == null || targetScene == null) {
      if (owner == crossStackOverlayOwner) clearCrossStackMateArc();
      return false;
    }

    Point2D source = sharedCrossStackOverlayCanvas.sceneToLocal(sourceScene);
    Point2D target = sharedCrossStackOverlayCanvas.sceneToLocal(targetScene);
    if (source == null || target == null) {
      if (owner == crossStackOverlayOwner) clearCrossStackMateArc();
      return false;
    }
    if (!Double.isFinite(source.getX()) || !Double.isFinite(source.getY())
        || !Double.isFinite(target.getX()) || !Double.isFinite(target.getY())) {
      if (owner == crossStackOverlayOwner) clearCrossStackMateArc();
      return false;
    }

    if (owner != crossStackOverlayOwner) {
      crossStackOverlayOwner = owner;
    }

    // Repaint this frame's connector only; prevents trail artifacts while moving.
    clearCrossStackOverlay();

    GraphicsContext gc = sharedCrossStackOverlayGc;
    double x1 = source.getX();
    double y1 = source.getY();
    double x2 = target.getX();
    double y2 = target.getY();
    double ctrlX = (x1 + x2) * 0.5;
    double archHeight = Math.max(24.0, Math.abs(x2 - x1) * 0.16);
    double ctrlY = Math.min(y1, y2) - archHeight;
    Color strokeColor = arcColor != null ? arcColor : Color.rgb(255, 180, 80, 0.94);

    gc.setStroke(strokeColor);
    gc.setLineWidth(1.3);
    gc.setLineDashes(7, 4);
    gc.beginPath();
    gc.moveTo(x1, y1);
    gc.quadraticCurveTo(ctrlX, ctrlY, x2, y2);
    gc.stroke();
    gc.setLineDashes(0);

    // Arrow head at the target end, aligned to the curve tangent.
    double tx = x2 - ctrlX;
    double ty = y2 - ctrlY;
    double len = Math.hypot(tx, ty);
    if (len > 0.001) {
      double ux = tx / len;
      double uy = ty / len;
      double arrowLen = 8.0;
      double wing = 4.5;
      double bx = x2 - ux * arrowLen;
      double by = y2 - uy * arrowLen;
      double px = -uy;
      double py = ux;

        gc.setFill(strokeColor.deriveColor(0, 1, 1, Math.min(1.0, strokeColor.getOpacity() + 0.02)));
      gc.fillPolygon(
          new double[]{x2, bx + px * wing, bx - px * wing},
          new double[]{y2, by + py * wing, by - py * wing},
          3);
    }
    return true;
  }
  
  private void setupVerticalDividerBehavior() {
    if (mainSplit == null || mainSplit.getDividers().size() < 2) return;

    SplitPane.Divider divider0 = mainSplit.getDividers().get(0);
    SplitPane.Divider divider1 = mainSplit.getDividers().get(1);

    divider0.positionProperty().addListener((obs, oldVal, newVal) -> {
      if (updatingVerticalDividers) return;

      double oldPos0 = oldVal.doubleValue();
      double requestedPos0 = newVal.doubleValue();
      double currentPos1 = divider1.getPosition();

      double delta = requestedPos0 - oldPos0;
      if (Math.abs(delta) < 1e-9) return;

      // Keep feature pane height unchanged while dragging divider 0.
      double featureSpan = Math.max(0, currentPos1 - oldPos0);
      double minGeneNorm = toNorm(chromPane != null ? chromPane.getMinHeight() : 0);
      double minSampleNorm = toNorm(getSamplePaneMinHeight());

      double minPos0 = Math.max(0, minGeneNorm);
      double maxPos0 = Math.min(1, 1.0 - minSampleNorm - featureSpan);
      if (maxPos0 < minPos0) maxPos0 = minPos0;

      double clampedPos0 = clamp(requestedPos0, minPos0, maxPos0);
      double clampedPos1 = clamp(clampedPos0 + featureSpan, clampedPos0, 1);

      if (Math.abs(clampedPos0 - requestedPos0) > 1e-9
          || Math.abs(clampedPos1 - currentPos1) > 1e-9) {
        setVerticalDividerPositions(clampedPos0, clampedPos1);
      }
      if (!applyingDividers) {
        ProjectSessionState.get().markDirty();
      }
    });

    divider1.positionProperty().addListener((obs, oldVal, newVal) -> {
      if (updatingVerticalDividers) return;

      double pos0 = divider0.getPosition();
      double requestedPos1 = newVal.doubleValue();

      double minFeatureNorm = toNorm(getFeatureTracksFloorHeight());
      double minSampleNorm = toNorm(getSamplePaneMinHeight());

      double minPos1 = Math.max(pos0 + minFeatureNorm, pos0);
      double maxPos1 = Math.min(1, 1.0 - minSampleNorm);
      if (maxPos1 < minPos1) maxPos1 = minPos1;

      double clampedPos1 = clamp(requestedPos1, minPos1, maxPos1);
      if (Math.abs(clampedPos1 - requestedPos1) > 1e-9) {
        setVerticalDividerPositions(pos0, clampedPos1);
      }
      if (!applyingDividers) {
        ProjectSessionState.get().markDirty();
      }
    });

    mainSplit.heightProperty().addListener((obs, oldVal, newVal) -> enforceVerticalDividerBounds());
    Platform.runLater(this::enforceVerticalDividerBounds);
  }

  private void enforceVerticalDividerBounds() {
    if (mainSplit == null || mainSplit.getDividers().size() < 2) return;

    double pos0 = mainSplit.getDividers().get(0).getPosition();
    double pos1 = mainSplit.getDividers().get(1).getPosition();

    double minGeneNorm = toNorm(chromPane != null ? chromPane.getMinHeight() : 0);
    double minFeatureNorm = toNorm(getFeatureTracksFloorHeight());
    double minSampleNorm = toNorm(getSamplePaneMinHeight());

    double clampedPos0 = clamp(pos0, Math.max(0, minGeneNorm), 1);
    double clampedPos1 = clamp(pos1, clampedPos0, 1);

    clampedPos1 = Math.max(clampedPos1, clampedPos0 + minFeatureNorm);
    clampedPos1 = Math.min(clampedPos1, 1.0 - minSampleNorm);
    clampedPos1 = clamp(clampedPos1, clampedPos0, 1);

    // Re-apply gene minimum if enforcing feature/sample constraints pushed divider 0 too far.
    clampedPos0 = clamp(clampedPos0, Math.max(0, minGeneNorm), Math.min(clampedPos1, 1));

    if (Math.abs(clampedPos0 - pos0) > 1e-9 || Math.abs(clampedPos1 - pos1) > 1e-9) {
      setVerticalDividerPositions(clampedPos0, clampedPos1);
    }
  }

  private void setVerticalDividerPositions(double pos0, double pos1) {
    if (mainSplit == null || mainSplit.getDividers().size() < 2) return;
    updatingVerticalDividers = true;
    try {
      mainSplit.setDividerPositions(pos0, pos1);
    } finally {
      updatingVerticalDividers = false;
    }
  }

  private double toNorm(double px) {
    double height = mainSplit != null ? mainSplit.getHeight() : 0;
    if (height <= 1 || px <= 0) return 0;
    return px / height;
  }

  private double getFeatureTracksFloorHeight() {
    var featureRegistry = ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    // No tracks: allow the feature pane to collapse fully.
    if (featureRegistry.getDisplayedTrackCount() <= 0) {
      return 0;
    }
    double masterHeight = Math.max(
        TrackViewportRegistry.DEFAULT_MASTER_BAND_HEIGHT_PIXELS,
        featureRegistry.getMasterBandHeightPixels());
    int bodySlots = featureRegistry.getVisibleTrackSlotCount();
    if (bodySlots <= 0) {
      bodySlots = 1;
    }
    double bodyHeight = bodySlots * TrackViewportRegistry.DEFAULT_TRACK_ROW_HEIGHT_PIXELS;
    double floor = masterHeight + bodyHeight;
    if (featureTracksPane != null) {
      floor = Math.max(floor, featureTracksPane.getMinHeight());
    }
    return Math.max(0, floor + FEATURE_MIN_HEIGHT_PADDING_PX);
  }

  private double getSamplePaneMinHeight() {
    if (mainSplit == null || mainSplit.getItems().size() < 3) return 0;
    Node samplePane = mainSplit.getItems().get(2);
    if (samplePane instanceof javafx.scene.layout.Region region) {
      return Math.max(0, region.getMinHeight());
    }
    return 0;
  }

  private static double clamp(double value, double min, double max) {
    if (max < min) return min;
    return Math.max(min, Math.min(max, value));
  }
  
  
  public static void zoomout() {
    DrawStack hover = stackManager.getHoverStack();
    if (hover == null) return;
    hover.sampleTrackCanvas.zoomAnimation(1, hover.chromSize);
  }
  
  void addMemUpdateListener() {
    memoryUsage.addListener((observable, oldValue, newValue) -> {
      int maxMem = BaseUtils.toMegabytes.apply(instanceRuntime.maxMemory());
      MenuBarController.updateMemoryBar(newValue.intValue(), maxMem);
    });
  }
  public static void addStack(boolean add) {
    clearCrossStackMateArc();
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
      featureTracksContentPane.getItems().add(drawStack.featureColumn);
      drawPane.getItems().add(drawStack.sampleColumn);
      VcfManager.getInstance().loadVariantsForStack(drawStack);
      
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
    double viewSize = 1000;
    double start = Math.max(1, position - viewSize / 2);
    double end = start + viewSize;
    addStackAtRegion(chrom, start, end);
  }

  public static DrawStack addStackAtRegion(String chrom, double start, double end) {
    clearCrossStackMateArc();
    chrom = org.baseplayer.utils.ChromosomeNames.strip(chrom);
    final String finalChrom = chrom;

    DrawStack drawStack = new DrawStack(finalChrom);
    ReferenceGenomeService refService = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (refService.hasGenome()) {
      drawStack.setChromosomeList(refService.getCurrentGenome().getStandardChromosomeNames());
    }

    drawStack.navigateTo(finalChrom, start, end);

    drawStacks.add(drawStack);
    chromSplitPane.getItems().add(drawStack.chromContainer);
    featureTracksContentPane.getItems().add(drawStack.featureColumn);
    drawPane.getItems().add(drawStack.sampleColumn);
    VcfManager.getInstance().loadVariantsForStack(drawStack);

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

    Platform.runLater(() -> drawStack.sampleTrackCanvas.zoomAnimation(start, end));
    return drawStack;
  }
  
  public static void removeStack(DrawStack stackToRemove) {
    clearCrossStackMateArc();
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
    javafx.scene.control.SplitPane.setResizableWithParent(genomeSideBarPane, false);
    javafx.scene.control.SplitPane.setResizableWithParent(drawSideBar, false);
    javafx.scene.control.SplitPane.setResizableWithParent(featureTracksSideBarPane, false);
    javafx.scene.control.SplitPane.setResizableWithParent(chromPane, false);
    javafx.scene.control.SplitPane.setResizableWithParent(featureTracksPane, false);
  }
  static void setDividerListeners() {
    EventCoordinator.synchronizeDividers(drawPane, chromSplitPane, featureTracksContentPane);
    if (drawPane != null) {
      for (SplitPane.Divider divider : drawPane.getDividers()) {
        divider.positionProperty().addListener((obs, oldVal, newVal) -> {
          MainController ctrl = instance;
          if (ctrl == null || ctrl.applyingDividers) return;
          ProjectSessionState.get().markDirty();
        });
      }
    }
  }

  /**
   * Snapshot vertical + horizontal SplitPane divider positions into {@code ui}.
   */
  public void captureDividerPositions(ProjectDocument.UiSpec ui) {
    if (ui == null) return;
    if (mainSplit != null && !mainSplit.getDividers().isEmpty()) {
      ui.mainSplitDividers = toDividerList(mainSplit.getDividerPositions());
    }
    ui.sidebarDivider = sidebarController.getDividerPosition();
    if (drawPane != null && drawPane.getDividers().size() > 0) {
      ui.columnDividers = toDividerList(drawPane.getDividerPositions());
    }
  }

  /**
   * Restore divider positions from a session. Call after tracks/stacks are restored;
   * applies twice via {@code runLater} so SplitPane layout accepts the values.
   */
  public void applyDividerPositions(ProjectDocument.UiSpec ui) {
    if (ui == null) return;
    applyingDividers = true;
    sidebarController.setSuppressSessionDirty(true);
    try {
      applyDividerPositionsNow(ui);
    } catch (RuntimeException e) {
      applyingDividers = false;
      sidebarController.setSuppressSessionDirty(false);
      throw e;
    }
    Platform.runLater(() -> {
      try {
        applyDividerPositionsNow(ui);
        enforceVerticalDividerBounds();
      } finally {
        applyingDividers = false;
        sidebarController.setSuppressSessionDirty(false);
      }
    });
  }

  private void applyDividerPositionsNow(ProjectDocument.UiSpec ui) {
    if (ui.mainSplitDividers != null && ui.mainSplitDividers.size() >= 2 && mainSplit != null) {
      double pos0 = ui.mainSplitDividers.get(0);
      double pos1 = ui.mainSplitDividers.get(1);
      setVerticalDividerPositions(pos0, pos1);
    }
    if (ui.sidebarDivider != null) {
      sidebarController.setDividerPosition(ui.sidebarDivider);
    }
    if (ui.columnDividers != null && !ui.columnDividers.isEmpty()
        && drawPane != null && chromSplitPane != null && featureTracksContentPane != null) {
      double[] positions = ui.columnDividers.stream().mapToDouble(Double::doubleValue).toArray();
      if (positions.length == drawPane.getDividers().size()) {
        drawPane.setDividerPositions(positions);
        featureTracksContentPane.setDividerPositions(positions);
        chromSplitPane.setDividerPositions(positions);
      }
    }
  }

  private static List<Double> toDividerList(double[] positions) {
    List<Double> list = new ArrayList<>(positions.length);
    for (double p : positions) {
      list.add(p);
    }
    return list;
  }

  void takeSnapshot() {
    eventCoordinator.takeCanvasSnapshots();
  }

  /**
   * Get the feature tracks body canvas (from the first draw stack).
   * Used by SampleDataManager to add BED/BigWig tracks.
   */
  public static org.baseplayer.samples.alignment.draw.TrackBodyCanvas getFeatureTrackCanvas() {
    if (drawStacks.isEmpty()) return null;
    return drawStacks.get(0).featureTrackCanvas;
  }

  public static void initializeLoadRegionButton() {
    if (sidebarPanel != null) {
      sidebarPanel.initializeLoadRegionButton();
    }
  }
}
