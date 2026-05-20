package org.baseplayer.controllers;

import java.io.File;
import java.util.List;

import org.baseplayer.components.GeneSearchComponent;
import org.baseplayer.controllers.commands.FileCommands;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.controllers.commands.ViewCommands;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.samples.alignment.FetchManager;
import org.baseplayer.samples.alignment.draw.AlignmentCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ViewportState;
import org.baseplayer.utils.BaseUtils;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;

public class MenuBarController {
  @FXML private TextField positionField;
  @FXML private TextField geneSearchField;
  @FXML private Label chromosomeLabel;
  @FXML private Label viewLengthLabel;
  @FXML private MenuBar menuBar;
  @FXML private Menu recentFilesMenu;
  @FXML private Pane memoryBar;
  @FXML private Button zoomInButton;
  @FXML private Button zoomOutButton;
  @FXML private Button copyPositionButton;
  
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
  private Popup snackbarPopup;
  private Label snackbarLabel;
  private PauseTransition snackbarHideTimer;

  private record ParsedPosition(String chromosome, int start, Integer end) {
    boolean isRange() { return end != null; }
  }
  
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
    refreshRecentFilesMenu();
    
    AlignmentCanvas.update.addListener((observable, oldValue, newValue) -> {
      DrawStack hoverStack = stackManager.getHoverStack();
      if(hoverStack == null) return;
      String chrom = hoverStack.chromosome != null ? hoverStack.chromosome : "1";
      String chromDisplay = chrom.startsWith("chr") || !chrom.matches("^(\\d{1,2}|X|Y|MT?)$") ? chrom : "chr" + chrom;
      chromosomeLabel.setText(chromDisplay + ":");
      if (!isEditingPositionField()) {
        syncPositionFieldFromHoverStack();
      }
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
      var refGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();
      java.util.List<String> chroms = refGenomeService.hasGenome()
          ? new java.util.ArrayList<>(refGenomeService.getCurrentGenome().getStandardChromosomeNames())
          : new java.util.ArrayList<>();
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
        String label = chrom.startsWith("chr") || !chrom.matches("^(\\d{1,2}|X|Y|MT?)$") ? chrom : "chr" + chrom;
        MenuItem item = new MenuItem(label);
        item.setOnAction(event -> {
          onChromosomeSelected(chrom);
        });
        chromosomeLabelMenu.getItems().add(item);
      }
      
      chromosomeLabelMenu.show(chromosomeLabel, Side.BOTTOM, 0, 0);
    });
    
    chromosomeLabel.setStyle("-fx-cursor: hand;");
    
    // Setup position field for editing
    positionField.setOnAction(e -> navigateFromPositionField());
    positionField.focusedProperty().addListener((obs, oldFocused, focused) -> {
      if (!focused) {
        // After editing ends, restore canonical start-end text from current hover stack.
        syncPositionFieldFromHoverStack();
      }
    });

    if (copyPositionButton != null) {
      copyPositionButton.setText("\u2398");
      copyPositionButton.setTooltip(new Tooltip("Copy current locus (chr:start-end)"));
      copyPositionButton.setFocusTraversable(false);
    }
  }

  private boolean isEditingPositionField() {
    return positionField != null && positionField.isFocused();
  }

  private void syncPositionFieldFromHoverStack() {
    DrawStack hoverStack = stackManager.getHoverStack();
    if (hoverStack == null || positionField == null) return;
    positionField.setText((int) hoverStack.start + "-" + (int) (hoverStack.end - 1));
  }

  private void navigateFromPositionField() {
    ParsedPosition parsed = parsePositionInput(positionField.getText());
    if (parsed == null) return;

    if (parsed.chromosome != null && !parsed.chromosome.isBlank()) {
      onChromosomeSelected(parsed.chromosome);
    }

    if (parsed.isRange()) {
      NavigationCommands.navigateToPosition(parsed.start, parsed.end);
    } else {
      NavigationCommands.navigateToPosition(parsed.start);
    }
  }

  private ParsedPosition parsePositionInput(String input) {
    if (input == null) return null;

    String text = input.trim();
    if (text.isEmpty()) return null;

    String chromToken = null;
    String coordToken = text;

    int colon = text.indexOf(':');
    if (colon >= 0) {
      chromToken = text.substring(0, colon).trim();
      coordToken = text.substring(colon + 1).trim();
    }

    coordToken = coordToken.replace(",", "").replace("_", "").replace(" ", "");
    if (coordToken.isEmpty()) return null;

    String resolvedChrom = resolveChromosomeToken(chromToken);

    try {
      int dash = coordToken.indexOf('-');
      if (dash >= 0) {
        String s1 = coordToken.substring(0, dash).trim();
        String s2 = coordToken.substring(dash + 1).trim();
        if (s1.isEmpty() || s2.isEmpty()) return null;
        int start = Math.max(1, Integer.parseInt(s1));
        int end = Math.max(1, Integer.parseInt(s2));
        if (end < start) {
          int tmp = start;
          start = end;
          end = tmp;
        }
        return new ParsedPosition(resolvedChrom, start, end);
      }

      int pos = Math.max(1, Integer.parseInt(coordToken));
      return new ParsedPosition(resolvedChrom, pos, null);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String resolveChromosomeToken(String chromToken) {
    if (chromToken == null || chromToken.isBlank()) return null;

    String requested = normalizeChromToken(chromToken);
    var refGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (!refGenomeService.hasGenome()) {
      return requested;
    }

    for (String chrom : refGenomeService.getCurrentGenome().getChromosomeNames()) {
      if (normalizeChromToken(chrom).equalsIgnoreCase(requested)) {
        return chrom;
      }
    }
    return requested;
  }

  private String normalizeChromToken(String chrom) {
    String c = chrom.trim();
    return c.regionMatches(true, 0, "chr", 0, 3) ? c.substring(3) : c;
  }

  @FXML
  private void copyCurrentPosition() {
    DrawStack hoverStack = stackManager.getHoverStack();
    if (hoverStack == null) return;

    String chrom = hoverStack.chromosome != null ? hoverStack.chromosome : "1";
    String withChr = chrom.regionMatches(true, 0, "chr", 0, 3) ? chrom : "chr" + chrom;
    String locus = withChr + ":" + (int) hoverStack.start + "-" + (int) (hoverStack.end - 1);

    ClipboardContent content = new ClipboardContent();
    content.putString(locus);
    Clipboard.getSystemClipboard().setContent(content);
    showSnackbar("Copied: " + locus);
  }

  private void showSnackbar(String message) {
    if (menuBar == null || menuBar.getScene() == null) return;
    Window owner = menuBar.getScene().getWindow();
    if (owner == null) return;

    if (snackbarPopup == null) {
      snackbarPopup = new Popup();
      snackbarPopup.setAutoHide(false);
      snackbarPopup.setHideOnEscape(false);

      snackbarLabel = new Label();
      snackbarLabel.setStyle(
          "-fx-text-fill: #f0f0f0;"
              + "-fx-font-size: 12;"
              + "-fx-background-color: rgba(45, 45, 45, 0.96);"
              + "-fx-background-radius: 6;"
              + "-fx-border-color: rgba(170, 170, 170, 0.35);"
              + "-fx-border-radius: 6;"
              + "-fx-padding: 8 12 8 12;");

      HBox root = new HBox(snackbarLabel);
      root.setMouseTransparent(true);
      snackbarPopup.getContent().add(root);

      snackbarHideTimer = new PauseTransition(Duration.millis(1400));
      snackbarHideTimer.setOnFinished(e -> snackbarPopup.hide());
    }

    snackbarLabel.setText(message);

    // Rough width estimate for centering near the copy button.
    double estimatedW = Math.max(120, message.length() * 7.0 + 30.0);

    double x = owner.getX() + 12.0;
    double y = owner.getY() + 40.0;

    Bounds menuBounds = menuBar.localToScreen(menuBar.getBoundsInLocal());
    if (menuBounds != null) {
      y = menuBounds.getMaxY() + 8.0;
    }

    if (copyPositionButton != null) {
      Bounds copyBounds = copyPositionButton.localToScreen(copyPositionButton.getBoundsInLocal());
      if (copyBounds != null) {
        x = copyBounds.getMinX() + (copyBounds.getWidth() - estimatedW) / 2.0;
        y = copyBounds.getMaxY() + 8.0;
      }
    }

    // Clamp into the window bounds to avoid clipping.
    double minX = owner.getX() + 8.0;
    double maxX = owner.getX() + owner.getWidth() - estimatedW - 8.0;
    if (maxX < minX) maxX = minX;
    x = Math.max(minX, Math.min(maxX, x));

    double minY = owner.getY() + 8.0;
    double maxY = owner.getY() + owner.getHeight() - 40.0;
    if (maxY < minY) maxY = minY;
    y = Math.max(minY, Math.min(maxY, y));

    if (!snackbarPopup.isShowing()) {
      snackbarPopup.show(owner, x, y);
    } else {
      snackbarPopup.setX(x);
      snackbarPopup.setY(y);
    }

    snackbarHideTimer.playFromStart();
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

  @FXML
  public void populateRecentFilesMenu(Event event) {
    refreshRecentFilesMenu();
  }

  private void refreshRecentFilesMenu() {
    if (recentFilesMenu == null) return;

    recentFilesMenu.getItems().clear();
    List<UserPreferences.RecentFile> recentFiles = UserPreferences.getRecentFiles();
    if (recentFiles.isEmpty()) {
      MenuItem emptyItem = new MenuItem("No recent files");
      emptyItem.setDisable(true);
      recentFilesMenu.getItems().add(emptyItem);
      return;
    }

    int shown = 0;
    for (UserPreferences.RecentFile rf : recentFiles) {
      if (rf == null || rf.path() == null || rf.path().isBlank()) continue;
      shown++;
      File file = new File(rf.path());
      String type = rf.fileType() != null ? rf.fileType() : "FILE";
      String itemText = shown + ". " + file.getName() + " [" + type + "]";
      MenuItem item = new MenuItem(itemText);
      item.setOnAction(e -> {
        if (!file.exists() || !file.isFile()) {
          UserPreferences.removeRecentFile(rf.path());
          refreshRecentFilesMenu();
          System.err.println("Recent file not found: " + rf.path());
          return;
        }
        FileCommands.openRecentFile(type, rf.path());
      });
      recentFilesMenu.getItems().add(item);
    }

    if (shown == 0) {
      MenuItem emptyItem = new MenuItem("No recent files");
      emptyItem.setDisable(true);
      recentFilesMenu.getItems().add(emptyItem);
      return;
    }

    recentFilesMenu.getItems().add(new SeparatorMenuItem());
    MenuItem clear = new MenuItem("Clear Recent Files");
    clear.setOnAction(e -> {
      UserPreferences.clearRecentFiles();
      refreshRecentFilesMenu();
    });
    recentFilesMenu.getItems().add(clear);
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
  private void minimizeWindow() {
    ViewCommands.minimizeWindow();
  }

  @FXML
  private void maximizeWindow() {
    ViewCommands.maximizeWindow();
  }

  @FXML
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
