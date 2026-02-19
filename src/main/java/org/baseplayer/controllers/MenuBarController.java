package org.baseplayer.controllers;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.GeneLocation;
import org.baseplayer.controllers.commands.FileCommands;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.controllers.commands.SearchCommands;
import org.baseplayer.controllers.commands.ViewCommands;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.alignment.draw.AlignmentCanvas;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.alignment.FetchManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ViewportState;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.GeneColors;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public class MenuBarController {
  @FXML private TextField positionField;
  @FXML private TextField geneSearchField;
  @FXML private Label chromosomeLabel;
  @FXML private Label viewLengthLabel;
  @FXML private MenuBar menuBar;
  @FXML private Pane memoryBar;
  @FXML private Button zoomInButton;
  @FXML private Button zoomOutButton;
  @FXML@SuppressWarnings("unused")
 private Button minimizeButton;
  @FXML@SuppressWarnings("unused")
 private Button maximizeButton;
  @FXML@SuppressWarnings("unused")
 private Button closeButton;
  
  // Zoom button icons for state updates
  private FontIcon zoomInIcon;
  private FontIcon zoomOutIcon;
  private static final Color ZOOM_IN_ACTIVE = Color.web("#709076");  // Slight green
  private static final Color ZOOM_OUT_ACTIVE = Color.web("#b68454"); // Slight orange
  private static final Color ZOOM_DISABLED = Color.web("#555555");   // Gray
  
  private static MenuBarController instance;
  private ContextMenu geneAutoComplete;
  private ContextMenu chromosomeLabelMenu;
  private List<String> currentSuggestions = new ArrayList<>();
  private final List<HBox> suggestionContainers = new ArrayList<>();
  private int selectedSuggestionIndex = -1;
  private Rectangle memoryFill;
  private Tooltip memoryTooltip;
  
  // Services
  private SampleRegistry sampleRegistry;
  private ViewportState viewportState;

  public void initialize() {
    instance = this;
    
    // Initialize services
    ServiceRegistry services = ServiceRegistry.getInstance();
    this.sampleRegistry = services.getSampleRegistry();
    this.viewportState = services.getViewportState();
    
    setupMemoryBar();
    setupGeneSearch();
    setupPositionField();
    setupZoomButtons();
    
    AlignmentCanvas.update.addListener((observable, oldValue, newValue) -> {
      if(MainController.hoverStack == null) return;
      String chrom = MainController.hoverStack.chromosome != null ? MainController.hoverStack.chromosome : "1";
      chromosomeLabel.setText("chr" + chrom + ":");
      positionField.setText((int)MainController.hoverStack.start + "-" + (int)(MainController.hoverStack.end - 1));
      updateViewLengthLabel();
      updateZoomButtonStates();
    });
    menuBar.widthProperty().addListener((observable, oldValue, newValue) -> {
      menuBar.setMinWidth(newValue.doubleValue());
      menuBar.setMaxWidth(newValue.doubleValue());
    });
  }
  
  private void setupMemoryBar() {
    // Create the fill rectangle that shows used memory (green from the left)
    memoryFill = new Rectangle(0, 0, 0, 10);
    memoryFill.setArcWidth(2);
    memoryFill.setArcHeight(2);
    memoryFill.setMouseTransparent(true);
    memoryBar.getChildren().add(memoryFill);
    
    // Create tooltip for hover
    memoryTooltip = new Tooltip();
    memoryTooltip.setShowDelay(Duration.millis(100));
    Tooltip.install(memoryBar, memoryTooltip);
    memoryTooltip.setText("Memory: calculating...");
    
    // Double-click to run garbage collector
    memoryBar.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2) {
        System.gc();
      }
    });
  }
  
  private void setupPositionField() {
    // Setup chromosome label click to show dropdown
    chromosomeLabel.setOnMouseClicked(e -> {
      if (chromosomeLabelMenu == null) {
        chromosomeLabelMenu = new ContextMenu();
        chromosomeLabelMenu.setAutoHide(true);
      }
      
      chromosomeLabelMenu.getItems().clear();
      // Sort chromosomes properly (1-22, X, Y, MT)
      java.util.List<String> chroms = new java.util.ArrayList<>(DrawStack.CHROMOSOME_SIZES.keySet());
      chroms.sort((c1, c2) -> {
        Integer n1 = tryParseInt(c1);
        Integer n2 = tryParseInt(c2);
        if (n1 != null && n2 != null) return n1.compareTo(n2);
        if (n1 != null) return -1;
        if (n2 != null) return 1;
        int order1 = getChromOrder(c1);
        int order2 = getChromOrder(c2);
        return Integer.compare(order1, order2);
      });
      
      for (String chrom : chroms) {
        MenuItem item = new MenuItem("chr" + chrom);
        item.setOnAction(event -> {
          onChromosomeSelected(chrom);
        });
        chromosomeLabelMenu.getItems().add(item);
      }
      
      chromosomeLabelMenu.show(chromosomeLabel, Side.BOTTOM, 0, 0);
    });
    
    chromosomeLabel.setStyle("-fx-cursor: hand;");
    
    // Setup position field for editing
    positionField.setOnAction(e -> {
      String text = positionField.getText();
      SearchCommands.navigateToPositionString(text);
    });
  }
  
  private void setupZoomButtons() {
    // Set magnifying glass icons for zoom buttons
    zoomInIcon = new FontIcon(FontAwesomeSolid.SEARCH_PLUS);
    zoomInIcon.setIconSize(14);
    zoomInIcon.setIconColor(ZOOM_IN_ACTIVE);
    zoomInButton.setText("");
    zoomInButton.setGraphic(zoomInIcon);
    
    zoomOutIcon = new FontIcon(FontAwesomeSolid.SEARCH_MINUS);
    zoomOutIcon.setIconSize(14);
    zoomOutIcon.setIconColor(ZOOM_OUT_ACTIVE);
    zoomOutButton.setText("");
    zoomOutButton.setGraphic(zoomOutIcon);
  }
  
  private void updateZoomButtonStates() {
    if (MainController.hoverStack == null || zoomInIcon == null || zoomOutIcon == null) return;
    
    var stack = MainController.hoverStack;
    
    // Check if can zoom in (not already at minimum zoom)
    boolean canZoomIn = stack.viewLength > GenomicCanvas.minZoom * 1.1;
    zoomInIcon.setIconColor(canZoomIn ? ZOOM_IN_ACTIVE : ZOOM_DISABLED);
    zoomInButton.setDisable(!canZoomIn);
    
    // Check if can zoom out (not already showing full chromosome)
    boolean canZoomOut = stack.viewLength < stack.chromSize * 0.99;
    zoomOutIcon.setIconColor(canZoomOut ? ZOOM_OUT_ACTIVE : ZOOM_DISABLED);
    zoomOutButton.setDisable(!canZoomOut);
  }
  
  public static void updateMemoryBar(int usedMem, int maxMem) {
    if (instance == null || instance.memoryBar == null || instance.memoryFill == null) return;
    
    double proportion = (double) usedMem / maxMem;
    double barWidth = instance.memoryBar.getWidth();
    double barHeight = instance.memoryBar.getHeight();
    
    // Green fill from left showing used memory
    double usedWidth = Math.max(1, barWidth * proportion); // At least 1 pixel
    instance.memoryFill.setX(0);
    instance.memoryFill.setY(1);
    instance.memoryFill.setWidth(usedWidth);
    instance.memoryFill.setHeight(barHeight - 2);
    
    // Color based on proportion: green -> yellow -> red
    Color fillColor;
    if (proportion < 0.5) {
      fillColor = Color.web("#4CAF50"); // Green
    } else if (proportion < 0.8) {
      fillColor = Color.web("#FFC107"); // Yellow
    } else {
      fillColor = Color.web("#F44336"); // Red
    }
    instance.memoryFill.setFill(fillColor);
    
    // Update tooltip text - use original format from sidebar
    int percent = (int) (Math.round(proportion * 100));
    instance.memoryTooltip.setText("Memory usage:\n" + BaseUtils.formatNumber(usedMem) + " / " + BaseUtils.formatNumber(maxMem) + "MB ( " + percent + "% )");
  }
  
  private void onChromosomeSelected(String chromosome) {
    if (chromosome == null) return;
    
    // Cancel all in-flight fetches before switching chromosome
    FetchManager.get().cancelAll();
    
    // Clear all cached read and coverage data when changing chromosomes
    for (var track : sampleRegistry.getSampleTracks()) {
      for (var sample : track.getSamples()) {
        if (sample.getBamFile() != null) {
          sample.getBamFile().clearAllCaches();
        }
      }
    }
    
    viewportState.setCurrentChromosome(chromosome);
    
    // Delegate to NavigationCommands for global chromosome switch
    NavigationCommands.switchChromosome(chromosome);
  }
  
  private void updateViewLengthLabel() {
    if (MainController.hoverStack == null || viewLengthLabel == null) return;
    
    long viewLength = (long) MainController.hoverStack.viewLength;
    viewLengthLabel.setText(formatViewLength(viewLength));
  }
  
  private String formatViewLength(long length) {
    if (length >= 1_000_000) {
      return (length / 1_000_000) + "Mb";
    } else if (length >= 1_000) {
      return (length / 1_000) + "Kb";
    } else {
      return length + "bp";
    }
  }
  
  private void setupGeneSearch() {
    geneAutoComplete = new ContextMenu();
    geneAutoComplete.setAutoHide(true);
    
    // Add event filter directly on ContextMenu to catch Enter before it's consumed
    geneAutoComplete.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ke -> {
      if (null != ke.getCode()) switch (ke.getCode()) {
            case ENTER -> {
                if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < currentSuggestions.size()) {
                    System.out.println("ContextMenu: Navigating to highlighted: " + currentSuggestions.get(selectedSuggestionIndex));
                    String geneToNavigate = currentSuggestions.get(selectedSuggestionIndex);
                    geneAutoComplete.hide();
                    selectedSuggestionIndex = -1;
                    navigateToGene(geneToNavigate);
                    ke.consume();
                } else {
                    // No selection, use text field value
                    String text = geneSearchField.getText();
                    if (text != null && !text.isEmpty()) {
                        geneAutoComplete.hide();
                        List<String> suggestions = AnnotationData.searchGenes(text);
                        if (!suggestions.isEmpty()) {
                            String exactMatch = suggestions.stream()
                                    .filter(s -> s.equalsIgnoreCase(text))
                                    .findFirst()
                                    .orElse(suggestions.get(0));
                            navigateToGene(exactMatch);
                        } else {
                            navigateToGene(text);
                        }
                    }
                    ke.consume();
                }
          }
            case DOWN, TAB -> {
                if (!currentSuggestions.isEmpty()) {
                    if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
                        suggestionContainers.get(selectedSuggestionIndex).setStyle("-fx-background-color: transparent;");
                    }
                    
                    if (ke.getCode() == javafx.scene.input.KeyCode.TAB && ke.isShiftDown()) {
                        selectedSuggestionIndex--;
                        if (selectedSuggestionIndex < 0) {
                            selectedSuggestionIndex = currentSuggestions.size() - 1;
                        }
                    } else {
                        selectedSuggestionIndex++;
                        if (selectedSuggestionIndex >= currentSuggestions.size()) {
                            selectedSuggestionIndex = 0;
                        }
                    }
                    
                    if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
                        suggestionContainers.get(selectedSuggestionIndex).setStyle("-fx-background-color: #444444;");
                        
                        String selectedGene = currentSuggestions.get(selectedSuggestionIndex);
                        GeneLocation loc = AnnotationData.getGeneLocation(selectedGene);
                        if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
                            AnnotationData.setHighlightedGene(loc);
                        }
                    }
                }       ke.consume();
          }
            case UP -> {
                if (!currentSuggestions.isEmpty()) {
                    if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
                        suggestionContainers.get(selectedSuggestionIndex).setStyle("-fx-background-color: transparent;");
                    }
                    
                    selectedSuggestionIndex--;
                    if (selectedSuggestionIndex < 0) {
                        selectedSuggestionIndex = currentSuggestions.size() - 1;
                    }
                    
                    if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
                        suggestionContainers.get(selectedSuggestionIndex).setStyle("-fx-background-color: #444444;");
                        
                        String selectedGene = currentSuggestions.get(selectedSuggestionIndex);
                        GeneLocation loc = AnnotationData.getGeneLocation(selectedGene);
                        if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
                            AnnotationData.setHighlightedGene(loc);
                        }
                    }
                }       ke.consume();
          }
            case ESCAPE -> {
                geneAutoComplete.hide();
                AnnotationData.clearHighlightedGene();
                selectedSuggestionIndex = -1;
                geneSearchField.requestFocus();
                ke.consume();
          }
            default -> {
          }
        }
    });
    
    geneSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
      if (newVal == null || newVal.length() < 2) {
        geneAutoComplete.hide();
        AnnotationData.clearHighlightedGene();
        currentSuggestions.clear();
        suggestionContainers.clear();
        selectedSuggestionIndex = -1;
        return;
      }
      
      List<String> suggestions = AnnotationData.searchGenes(newVal);
      if (suggestions.isEmpty()) {
        geneAutoComplete.hide();
        AnnotationData.clearHighlightedGene();
        currentSuggestions.clear();
        suggestionContainers.clear();
        selectedSuggestionIndex = -1;
        return;
      }
      
      currentSuggestions = new ArrayList<>(suggestions);
      suggestionContainers.clear();
      selectedSuggestionIndex = -1;
      
      // Check if typed text matches a gene exactly (case insensitive) and highlight it
      String exactMatch = suggestions.stream()
          .filter(s -> s.equalsIgnoreCase(newVal))
          .findFirst()
          .orElse(null);
      if (exactMatch != null) {
        GeneLocation loc = AnnotationData.getGeneLocation(exactMatch);
        if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
          AnnotationData.setHighlightedGene(loc);
        }
      } else {
        AnnotationData.clearHighlightedGene();
      }
      
      geneAutoComplete.getItems().clear();
      for (String geneName : suggestions) {
        GeneLocation loc = AnnotationData.getGeneLocation(geneName);
        
        Label nameLabel = new Label(geneName);
        // Color gene name based on COSMIC status and biotype (use setStyle to override CSS)
        Color geneColor = GeneColors.getGeneColor(geneName, AnnotationData.getGeneBiotype(geneName));
        nameLabel.setStyle("-fx-text-fill: " + GeneColors.toHexString(geneColor) + ";");
        HBox container = new HBox(5);
        container.setCursor(javafx.scene.Cursor.HAND);
        container.setPrefWidth(300);
        container.setStyle("-fx-background-color: transparent;");
        suggestionContainers.add(container);        
        if (loc != null) {
          String locText = String.format("%s:%,d-%,d", loc.chrom(), loc.start(), loc.end());
          Label locLabel = new Label(locText);
          locLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
          
          Region spacer = new Region();
          HBox.setHgrow(spacer, Priority.ALWAYS);
          
          container.getChildren().addAll(nameLabel, spacer, locLabel);
        } else {
          container.getChildren().add(nameLabel);
        }
        
        // Show gene location highlight on hover
        container.setOnMouseEntered(e -> {
          if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
            AnnotationData.setHighlightedGene(loc);
          }
        });
        container.setOnMouseExited(e -> {
          // Restore exact match highlight if exists
          String currentText = geneSearchField.getText();
          if (currentText != null) {
            GeneLocation exactLoc = AnnotationData.getGeneLocation(currentText);
            if (exactLoc != null && exactLoc.chrom().equals(viewportState.getCurrentChromosome())) {
              AnnotationData.setHighlightedGene(exactLoc);
              return;
            }
          }
          AnnotationData.clearHighlightedGene();
        });
        
        CustomMenuItem item = new CustomMenuItem(container);
        item.setHideOnClick(true);
        item.setOnAction(e -> navigateToGene(geneName));
        
        geneAutoComplete.getItems().add(item);
      }
      
      if (!geneAutoComplete.isShowing()) {
        geneAutoComplete.show(geneSearchField, Side.BOTTOM, 0, 0);
      }
    });
    
    // Hide autocomplete and clear highlight when focus is lost
    geneSearchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
      if (!isFocused) {
        geneAutoComplete.hide();
        AnnotationData.clearHighlightedGene();
        selectedSuggestionIndex = -1;
      } else {
        // Reset selection when focus is gained
        selectedSuggestionIndex = -1;
      }
    });
    
    // Enter key is handled by the scene event filter when autocomplete is showing
    // When autocomplete is NOT showing, use setOnAction for direct navigation
    geneSearchField.setOnAction(e -> {
      // Only navigate if autocomplete is NOT showing (handled by event filter otherwise)
      if (!geneAutoComplete.isShowing()) {
        String text = geneSearchField.getText();
        if (text != null && text.length() >= 2) {
          List<String> suggestions = AnnotationData.searchGenes(text);
          if (!suggestions.isEmpty()) {
            String exactMatch = suggestions.stream()
                .filter(s -> s.equalsIgnoreCase(text))
                .findFirst()
                .orElse(suggestions.get(0));
            navigateToGene(exactMatch);
          }
        }
      }
    });
  }
  
  private void navigateToGene(String geneName) {
    geneAutoComplete.hide();
    SearchCommands.clearGeneHighlight();
    
    // Delegate to NavigationCommands for the actual navigation
    NavigationCommands.navigateToGene(geneName);
    
    // UI updates
    geneSearchField.setText(geneName);
    geneSearchField.selectAll();
    geneSearchField.getParent().requestFocus();
  }
  
  public static void setChromosomes(java.util.List<String> chromosomes) {
    // Chromosome selection is now handled via the position field chromosome label dropdown
    // No longer needed with the removed chromosomeDropdown ComboBox
  }
  public void openFileMenu(ActionEvent event) {
      MenuItem menuItem = (MenuItem) event.getSource();
      String[] types = menuItem.getId().split("_");
      String filtertype = types[1];

      FileCommands.openFile(filtertype);
      //boolean multiSelect = !filtertype.equals("SES"); // TODO myöhemmin kun avataan bam tai vcf trackille, refactoroi toimimaan myös sille
      //new FileDialog(menuItem.getText(), types[1], types[0], multiSelect);
  }
  public void addStack(ActionEvent event) { ViewCommands.addStack(); }
  public void removeStack(ActionEvent event) { ViewCommands.removeStack(); }
  public void setDarkMode(ActionEvent event) { ViewCommands.toggleDarkMode(); }
  public void cleanMemory(ActionEvent event) { ViewCommands.cleanMemory(); }

  @FXML
  public void openSettings(ActionEvent event) {
    ViewCommands.openSettings();
  }
  
  private long lastZoomInClick = 0;
  private long lastZoomOutClick = 0;
  
  @FXML
  public void zoomIn(ActionEvent event) {
    long now = System.currentTimeMillis();
    boolean isDoubleClick = (now - lastZoomInClick) < 300;
    lastZoomInClick = now;
    
    NavigationCommands.zoomIn(isDoubleClick);
  }
  
  @FXML
  public void zoomOut(ActionEvent event) {
    long now = System.currentTimeMillis();
    boolean isDoubleClick = (now - lastZoomOutClick) < 300;
    lastZoomOutClick = now;
    
    NavigationCommands.zoomOut(isDoubleClick);
  }

  @FXML
  @SuppressWarnings("unused")
  private void minimizeWindow() {
    ViewCommands.minimizeWindow();
  }

  @FXML
  @SuppressWarnings("unused")
  private void maximizeWindow() {
    ViewCommands.maximizeWindow();
  }

  @FXML
  @SuppressWarnings("unused")
  private void closeWindow() {
    ViewCommands.closeWindow();
  }
  
  private static Integer tryParseInt(String s) {
    try {
      return Integer.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
  
  private static int getChromOrder(String name) {
    return switch (name) {
      case "X" -> 0;
      case "Y" -> 1;
      case "MT", "M" -> 2;
      default -> 3;
    };
  }
}
