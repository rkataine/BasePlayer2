package org.baseplayer.controllers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.baseplayer.SharedModel;
import org.baseplayer.draw.DrawFunctions;
import org.baseplayer.draw.DrawSampleData;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.SideBarStack;
import org.baseplayer.io.ReferenceGenome;
import org.baseplayer.tracks.FeatureTracksSidebar;
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
  @FXML private SplitPane drawCanvas;
  @FXML private SplitPane chromCanvas;
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
  public static AnchorPane staticDraw;
  Runtime instance = Runtime.getRuntime();
  IntegerProperty memoryUsage = new SimpleIntegerProperty(0);
  public static DrawStack hoverStack;
  public static ArrayList<DrawStack> drawStacks = new ArrayList<>();
  SideBarStack sideBarStack;
  
  public static boolean showOnlyCancerGenes = false;
  
  // Unified sidebar controller for all horizontal split panes
  private final SidebarController sidebarController = new SidebarController();
  
  public void initialize() {
      chromSplitPane = chromCanvas;
      drawPane = drawCanvas;
      featureTracksContentPane = featureTracksContentSplit;
      sideBarStack = new SideBarStack(drawSideBarStackPane);
      
      // Setup unified sidebar controller for all horizontal split panes
      sidebarController.addPane(chromSplit);
      sidebarController.addPane(featureTracksSplit);
      sidebarController.addPane(drawSplit);
      
      addStack(true);  // Create stack first
      
      // Setup feature tracks sidebar
      setupFeatureTracksSidebar();
      
      loadAvailableGenomes(); // Then load genomes which will update stack sizes
      
      addMemUpdateListener();
      addUpdateListener();
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
    Path genomesDir = Path.of("genomes");
    if (!Files.exists(genomesDir)) return;
    
    try (Stream<Path> dirs = Files.list(genomesDir)) {
      dirs.filter(Files::isDirectory).forEach(dir -> {
        try (Stream<Path> files = Files.list(dir)) {
          files.filter(f -> f.toString().endsWith(".fa") || f.toString().endsWith(".fasta"))
               .filter(f -> Files.exists(Path.of(f.toString() + ".fai")))
               .findFirst()
               .ifPresent(fastaPath -> {
                 try {
                   ReferenceGenome genome = new ReferenceGenome(fastaPath);
                   referenceComboBox.getItems().add(genome);
                 } catch (IOException e) {
                   System.err.println("Failed to load genome: " + fastaPath + " - " + e.getMessage());
                 }
               });
        } catch (IOException e) {
          System.err.println("Error scanning genome directory: " + dir);
        }
      });
    } catch (IOException e) {
      System.err.println("Error scanning genomes folder: " + e.getMessage());
    }
    
    referenceComboBox.setOnAction(e -> onReferenceGenomeSelected());
    
    // Load available annotations
    loadAvailableAnnotations();
    
    if (!referenceComboBox.getItems().isEmpty()) {
      referenceComboBox.getSelectionModel().selectFirst();
      onReferenceGenomeSelected();
    }
  }
  
  private void loadAvailableAnnotations() {
    Path annotationDir = Path.of("genomes/GRCh38/annotation");
    if (!Files.exists(annotationDir)) return;
    
    try (Stream<Path> files = Files.list(annotationDir)) {
      files.filter(f -> f.toString().endsWith(".gff3.gz") || f.toString().endsWith(".gff3") || f.toString().endsWith(".gtf.gz"))
           .forEach(gff3Path -> {
             String filename = gff3Path.getFileName().toString();
             annotationComboBox.getItems().add(filename);
           });
    } catch (IOException e) {
      System.err.println("Error scanning annotations folder: " + e.getMessage());
    }
    
    // Select default annotation if available
    if (!annotationComboBox.getItems().isEmpty()) {
      // Try to find the default Ensembl annotation
      String defaultAnnotation = annotationComboBox.getItems().stream()
          .filter(name -> name.contains("Homo_sapiens.GRCh38"))
          .findFirst()
          .orElse(annotationComboBox.getItems().get(0));
      annotationComboBox.getSelectionModel().select(defaultAnnotation);
    }
  }
  
  private void onReferenceGenomeSelected() {
    ReferenceGenome genome = referenceComboBox.getValue();
    if (genome == null) return;
    
    SharedModel.referenceGenome = genome;
    
    java.util.List<String> chromNames = genome.getStandardChromosomeNames();
    MenuBarController.setChromosomes(chromNames);
    
    // Update all stacks with chromosome list
    for (var stack : MainController.drawStacks) {
      stack.setChromosomeList(chromNames);
    }
  }
  
  public static void zoomout() {
    hoverStack.drawCanvas.zoomAnimation(1, hoverStack.chromSize);
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
      if (SharedModel.referenceGenome != null) {
        drawStack.setChromosomeList(SharedModel.referenceGenome.getStandardChromosomeNames());
      }
      
      // Load simulated variants if generated
      drawStack.loadSimulatedVariants();
      
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

  /**
   * Add a new split panel and navigate it to a specific chromosome and position.
   * Used for "go to mate" from the read info popup.
   */
  public static void addStackAtPosition(String chrom, int position) {
    // Strip "chr" prefix if present to match internal naming
    if (chrom.startsWith("chr")) chrom = chrom.substring(3);
    final String finalChrom = chrom;

    // Create the new stack at the target chromosome
    DrawStack drawStack = new DrawStack(finalChrom);
    if (SharedModel.referenceGenome != null) {
      drawStack.setChromosomeList(SharedModel.referenceGenome.getStandardChromosomeNames());
    }
    drawStack.chromosomeDropdown.setValue(finalChrom);
    drawStack.loadSimulatedVariants();

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
      drawStack.drawCanvas.zoomAnimation(start, end);
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
  void addUpdateListener() {
    DrawSampleData.update.addListener((observable, oldValue, newValue) -> {
       
      // Always draw all stacks so that data updates (e.g. BAM fetch completion)
      // are reflected everywhere, not just on the hover stack
      for (DrawStack pane : drawStacks) {
        pane.cytobandCanvas.draw();
        pane.chromCanvas.draw();
        pane.drawCanvas.draw();
      }
      
      // Update feature tracks when region changes
      for (DrawStack stack : drawStacks) {
        if (stack.featureTracksCanvas != null) {
          stack.featureTracksCanvas.draw();
        }
      }
      if (featureTracksSidebar != null) {
        featureTracksSidebar.draw();
      }

      sideBarStack.trackInfo.draw();
      memoryUsage.set(BaseUtils.toMegabytes.apply(instance.totalMemory() - instance.freeMemory()));
    });
  }
  
  void setWindowSizeListener() {
    mainSplit.setOnMouseEntered((MouseEvent event) -> {
        isActive = true;
        DrawFunctions.update.set(!DrawFunctions.update.get());
    });
    mainSplit.setOnMouseExited((MouseEvent event) -> {  
        isActive = false;
    });
    drawCanvas.setOnMouseExited((MouseEvent event) -> {  
        takeSnapshot();
    });
    drawCanvas.setOnMouseEntered((MouseEvent event) -> {  
        DrawFunctions.update.set(!DrawFunctions.update.get());
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
    drawPane.getDividers().forEach(divider -> {
      divider.positionProperty().addListener((obs, oldVal, newVal) -> {
          int index = drawPane.getDividers().indexOf(divider);
          if (index < chromSplitPane.getDividers().size()) {
            chromSplitPane.getDividers().get(index).setPosition(newVal.doubleValue());
          }
          if (index < featureTracksContentPane.getDividers().size()) {
            featureTracksContentPane.getDividers().get(index).setPosition(newVal.doubleValue());
          }
      });
    });
  
    chromSplitPane.getDividers().forEach(divider -> {
        divider.positionProperty().addListener((obs, oldVal, newVal) -> {
            int index = chromSplitPane.getDividers().indexOf(divider);
            if (index < drawPane.getDividers().size()) {
              drawPane.getDividers().get(index).setPosition(newVal.doubleValue());
            }
            if (index < featureTracksContentPane.getDividers().size()) {
              featureTracksContentPane.getDividers().get(index).setPosition(newVal.doubleValue());
            }
        });
    });
    
    featureTracksContentPane.getDividers().forEach(divider -> {
        divider.positionProperty().addListener((obs, oldVal, newVal) -> {
            int index = featureTracksContentPane.getDividers().indexOf(divider);
            if (index < drawPane.getDividers().size()) {
              drawPane.getDividers().get(index).setPosition(newVal.doubleValue());
            }
            if (index < chromSplitPane.getDividers().size()) {
              chromSplitPane.getDividers().get(index).setPosition(newVal.doubleValue());
            }
        });
    });
  }
  void takeSnapshot() {
    for (DrawStack pane : drawStacks)
      pane.drawCanvas.snapshot = pane.drawCanvas.snapshot(null, null);    
  }
  
  @FXML
  @SuppressWarnings("unused")
  private void showAnnotationOptions() {
    javafx.stage.Stage dialog = new javafx.stage.Stage();
    dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
    dialog.initOwner(annotationOptionsButton.getScene().getWindow());
    dialog.setTitle("Gene Display Options");
    dialog.setResizable(false);
    
    // Create content
    javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(15);
    content.setPadding(new javafx.geometry.Insets(20));
    content.setStyle("-fx-background-color: #2b2b2b;");
    
    // Title
    javafx.scene.control.Label title = new javafx.scene.control.Label("Gene Display Settings");
    title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white;");
    
    // Cancer genes checkbox
    javafx.scene.control.CheckBox cancerGenesCheckBox = new javafx.scene.control.CheckBox("Show only cancer genes (COSMIC)");
    cancerGenesCheckBox.setSelected(showOnlyCancerGenes);
    cancerGenesCheckBox.setStyle("-fx-text-fill: white;");
    
    // MANE transcripts checkbox
    javafx.scene.control.CheckBox maneCheckBox = new javafx.scene.control.CheckBox("Show only MANE transcripts");
    if (!drawStacks.isEmpty() && drawStacks.get(0).chromCanvas != null) {
      maneCheckBox.setSelected(drawStacks.get(0).chromCanvas.isShowManeOnly());
    }
    maneCheckBox.setStyle("-fx-text-fill: white;");
    
    // Info label
    javafx.scene.control.Label infoLabel = new javafx.scene.control.Label(
      """
      Cancer genes are from the COSMIC Cancer Gene Census.
      MANE transcripts are the authoritative reference transcripts.""");
    infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #999999; -fx-wrap-text: true;");
    infoLabel.setMaxWidth(300);
    
    // Buttons
    javafx.scene.layout.HBox buttonBox = new javafx.scene.layout.HBox(10);
    buttonBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
    
    javafx.scene.control.Button applyButton = new javafx.scene.control.Button("Apply");
    applyButton.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-cursor: hand;");
    applyButton.setOnAction(e -> {
      showOnlyCancerGenes = cancerGenesCheckBox.isSelected();
      boolean maneOnly = maneCheckBox.isSelected();
      for (DrawStack stack : drawStacks) {
        if (stack.chromCanvas != null) {
          stack.chromCanvas.setShowManeOnly(maneOnly);
        }
      }
      org.baseplayer.draw.DrawFunctions.update.set(!org.baseplayer.draw.DrawFunctions.update.get());
      dialog.close();
    });
    
    javafx.scene.control.Button cancelButton = new javafx.scene.control.Button("Cancel");
    cancelButton.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: white; -fx-cursor: hand;");
    cancelButton.setOnAction(e -> dialog.close());
    
    buttonBox.getChildren().addAll(cancelButton, applyButton);
    
    content.getChildren().addAll(title, cancerGenesCheckBox, maneCheckBox, infoLabel, buttonBox);
    
    javafx.scene.Scene scene = new javafx.scene.Scene(content);
    dialog.setScene(scene);
    dialog.show();
  }
  
  /**
   * Get the feature tracks canvas (from the first draw stack).
   * Used by SampleDataManager to add BED/BigWig tracks.
   */
  public static org.baseplayer.tracks.FeatureTracksCanvas getFeatureTracksCanvas() {
    if (drawStacks.isEmpty()) return null;
    return drawStacks.get(0).featureTracksCanvas;
  }
}
