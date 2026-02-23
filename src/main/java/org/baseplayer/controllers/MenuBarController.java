package org.baseplayer.controllers;

import org.baseplayer.alignment.FetchManager;
import org.baseplayer.alignment.draw.AlignmentCanvas;
import org.baseplayer.controllers.commands.FileCommands;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.controllers.commands.SearchCommands;
import org.baseplayer.controllers.commands.ViewCommands;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ViewportState;
import org.baseplayer.utils.BaseUtils;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
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
  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
  private ContextMenu chromosomeLabelMenu;
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
    new GeneSearchComponent(geneSearchField, viewportState);
    setupPositionField();
    setupZoomButtons();
    
    AlignmentCanvas.update.addListener((observable, oldValue, newValue) -> {
      DrawStack hoverStack = stackManager.getHoverStack();
      if(hoverStack == null) return;
      String chrom = hoverStack.chromosome != null ? hoverStack.chromosome : "1";
      chromosomeLabel.setText("chr" + chrom + ":");
      positionField.setText((int)hoverStack.start + "-" + (int)(hoverStack.end - 1));
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
        Integer n1 = BaseUtils.tryParseInt(c1);
        Integer n2 = BaseUtils.tryParseInt(c2);
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
    if (stackManager.getHoverStack() == null || zoomInIcon == null || zoomOutIcon == null) return;
    
    var stack = stackManager.getHoverStack();
    
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
    if (stackManager.getHoverStack() == null || viewLengthLabel == null) return;
    
    long viewLength = (long) stackManager.getHoverStack().viewLength;
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
  
  public void openFileMenu(ActionEvent event) {
      MenuItem menuItem = (MenuItem) event.getSource();
      String[] types = menuItem.getId().split("_");
      String filtertype = types[1];

      FileCommands.openFile(filtertype);
      //boolean multiSelect = !filtertype.equals("SES"); // TODO later: when opening bam or vcf for a track, refactor to work for that too
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
  

  
  private static int getChromOrder(String name) {
    return switch (name) {
      case "X" -> 0;
      case "Y" -> 1;
      case "MT", "M" -> 2;
      default -> 3;
    };
  }
}
