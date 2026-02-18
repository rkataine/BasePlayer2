package org.baseplayer.tracks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.services.ReferenceGenomeService;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Popup;

/**
 * Sidebar for feature tracks displaying track names and options.
 */
public class FeatureTracksSidebar {
  
  private static final double HEADER_HEIGHT = 18;
  private static final double TRACK_PADDING = 2;
  private static final double ICON_SIZE = 14;
  private static final double ICON_PADDING = 4;
  
  // Click regions for icons
  private record IconRegion(Track track, String iconType, double x, double y, double size) {
    boolean contains(double mx, double my) {
      return mx >= x && mx <= x + size && my >= y && my <= y + size;
    }
  }
  private final List<IconRegion> iconRegions = new ArrayList<>();
  
  private final Canvas canvas;
  private final Canvas reactiveCanvas;
  private final GraphicsContext gc;
  private final GraphicsContext reactiveGc;
  private FeatureTracksCanvas featureTracksCanvas;
  private Track hoveredTrack = null;
  private String hoveredIcon = null;  // "eye" or "settings"
  private ContextMenu contextMenu;
  private ContextMenu addTracksMenu;
  private ContextMenu masterSettingsMenu;
  private Popup settingsPopup;
  private boolean plusButtonHovered = false;
  private boolean settingsButtonHovered = false;
  
  // Services
  private final ReferenceGenomeService referenceGenomeService;
  
  public FeatureTracksSidebar(StackPane parent) {
    this.canvas = new Canvas();
    this.reactiveCanvas = new Canvas();
    this.referenceGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();
    
    canvas.heightProperty().bind(parent.heightProperty());
    canvas.widthProperty().bind(parent.widthProperty());
    reactiveCanvas.heightProperty().bind(parent.heightProperty());
    reactiveCanvas.widthProperty().bind(parent.widthProperty());
    
    parent.getChildren().addAll(canvas, reactiveCanvas);
    
    gc = canvas.getGraphicsContext2D();
    reactiveGc = reactiveCanvas.getGraphicsContext2D();
    
    setupContextMenu();
    setupAddTracksMenu();
    setupMasterSettingsMenu();
    setupMouseHandlers();
  }
  
  /**
   * Set the feature tracks canvas this sidebar is connected to.
   */
  public void setFeatureTracksCanvas(FeatureTracksCanvas canvas) {
    this.featureTracksCanvas = canvas;
  }
  
  private void setupContextMenu() {
    contextMenu = new ContextMenu();
    
    // Add track submenu
    Menu addTrackMenu = new Menu("Add Track");
    
    MenuItem addBedItem = new MenuItem("BED file...");
    addBedItem.setOnAction(e -> addBedTrack());
    
    MenuItem addBigWigItem = new MenuItem("BigWig file...");
    addBigWigItem.setOnAction(e -> addBigWigTrack());
    
    MenuItem browseUcscItem = new MenuItem("Browse UCSC Tracks...");
    browseUcscItem.setOnAction(e -> showUcscTracksBrowser());
    
    addTrackMenu.getItems().addAll(addBedItem, addBigWigItem, new SeparatorMenuItem(), 
        browseUcscItem);
    
    contextMenu.getItems().add(addTrackMenu);
  }
  
  private void setupAddTracksMenu() {
    addTracksMenu = new ContextMenu();
    
    // File-based tracks
    MenuItem bedItem = new MenuItem("BED file...");
    bedItem.setOnAction(e -> addBedTrack());
    
    MenuItem bigWigItem = new MenuItem("BigWig file...");
    bigWigItem.setOnAction(e -> addBigWigTrack());
    
    // UCSC tracks browser
    MenuItem ucscBrowserItem = new MenuItem("Browse UCSC Tracks...");
    ucscBrowserItem.setOnAction(e -> showUcscTracksBrowser());
    
    addTracksMenu.getItems().addAll(
        bedItem,
        bigWigItem,
        new SeparatorMenuItem(),
        ucscBrowserItem
    );
  }
  
  private void setupMasterSettingsMenu() {
    masterSettingsMenu = new ContextMenu();
    masterSettingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");
    
    // Future: Add global feature track settings here
    // For now, just placeholder
    MenuItem placeholderItem = new MenuItem("Track Analysis (coming soon)");
    placeholderItem.setDisable(true);
    
    masterSettingsMenu.getItems().add(placeholderItem);
  }
  
  private void setupMouseHandlers() {
    reactiveCanvas.setOnMouseMoved(event -> {
      double x = event.getX();
      double y = event.getY();
      
      // Check if hovering over buttons in master track header
      if (y < HEADER_HEIGHT) {
        double width = reactiveCanvas.getWidth();
        
        // Plus button
        double plusX = width - 20;
        double plusY = (HEADER_HEIGHT - 14) / 2;
        boolean wasPlusHovered = plusButtonHovered;
        plusButtonHovered = (x >= plusX && x <= plusX + 14 && y >= plusY && y <= plusY + 14);
        
        // Settings button
        double settingsX = 4;
        double settingsY = (HEADER_HEIGHT - 14) / 2;
        boolean wasSettingsHovered = settingsButtonHovered;
        settingsButtonHovered = (x >= settingsX && x <= settingsX + 14 && y >= settingsY && y <= settingsY + 14);
        
        if (wasPlusHovered != plusButtonHovered || wasSettingsHovered != settingsButtonHovered) {
          drawReactive();
        }
        if (!plusButtonHovered && !settingsButtonHovered) {
          hoveredTrack = null;
          hoveredIcon = null;
        }
        return;
      }
      plusButtonHovered = false;
      settingsButtonHovered = false;
      
      Track track = findTrackAt(y);
      String icon = findIconAt(x, y);
      if (track != hoveredTrack || !java.util.Objects.equals(icon, hoveredIcon)) {
        hoveredTrack = track;
        hoveredIcon = icon;
        drawReactive();
      }
    });
    
    reactiveCanvas.setOnMouseExited(event -> {
      if (hoveredTrack != null || hoveredIcon != null || plusButtonHovered || settingsButtonHovered) {
        hoveredTrack = null;
        hoveredIcon = null;
        plusButtonHovered = false;
        settingsButtonHovered = false;
        drawReactive();
      }
    });
    
    reactiveCanvas.setOnMouseClicked(event -> {
      if (event.getButton() == MouseButton.PRIMARY) {
        // Check if clicking buttons in master track header
        double x = event.getX();
        double y = event.getY();
        if (y < HEADER_HEIGHT) {
          double width = reactiveCanvas.getWidth();
          
          // Plus button
          double plusX = width - 20;
          double plusY = (HEADER_HEIGHT - 14) / 2;
          if (x >= plusX && x <= plusX + 14 && y >= plusY && y <= plusY + 14) {
            addTracksMenu.show(reactiveCanvas, event.getScreenX(), event.getScreenY());
            return;
          }
          
          // Settings button
          double settingsX = 4;
          double settingsY = (HEADER_HEIGHT - 14) / 2;
          if (x >= settingsX && x <= settingsX + 14 && y >= settingsY && y <= settingsY + 14) {
            masterSettingsMenu.show(reactiveCanvas, event.getScreenX(), event.getScreenY());
            return;
          }
        }
        
        // Check for icon clicks
        for (IconRegion region : iconRegions) {
          if (region.contains(event.getX(), event.getY())) {
            if ("eye".equals(region.iconType())) {
              // Toggle visibility
              region.track().setVisible(!region.track().isVisible());
              if (featureTracksCanvas != null) {
                featureTracksCanvas.notifyRegionChanged();
                GenomicCanvas.update.set(!GenomicCanvas.update.get());
              }
              draw();
            } else if ("settings".equals(region.iconType())) {
              // Show settings popup
              showSettingsPopup(region.track(), event.getScreenX(), event.getScreenY());
            }
            return;
          }
        }
      } else if (event.getButton() == MouseButton.SECONDARY) {
        Track trackAtMouse = findTrackAt(event.getY());
        updateContextMenuForTrack(trackAtMouse);
        contextMenu.show(reactiveCanvas, event.getScreenX(), event.getScreenY());
      }
    });
  }
  
  private String findIconAt(double x, double y) {
    for (IconRegion region : iconRegions) {
      if (region.contains(x, y)) {
        return region.iconType();
      }
    }
    return null;
  }
  
  private void updateContextMenuForTrack(Track track) {
    // Remove old track-specific items (keep Add Track menu)
    while (contextMenu.getItems().size() > 1) {
      contextMenu.getItems().remove(1);
    }
    
    if (track != null) {
      contextMenu.getItems().add(new SeparatorMenuItem());
      
      // Toggle visibility
      MenuItem toggleVisibility = new MenuItem(track.isVisible() ? "Hide Track" : "Show Track");
      toggleVisibility.setOnAction(e -> {
        track.setVisible(!track.isVisible());
        if (featureTracksCanvas != null) {
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
        }
        draw();
      });
      contextMenu.getItems().add(toggleVisibility);
      
      // Remove track
      MenuItem removeItem = new MenuItem("Remove Track");
      removeItem.setOnAction(e -> {
        if (featureTracksCanvas != null) {
          featureTracksCanvas.removeTrack(track);
          draw();
        }
      });
      contextMenu.getItems().add(removeItem);
    }
  }
  
  /**
   * Calculate dynamic height for a track based on available space.
   */
  private double calculateTrackHeight(Track track) {
    if (featureTracksCanvas == null) return 0;
    
    List<Track> tracks = featureTracksCanvas.getTracks();
    if (tracks.isEmpty()) return 0;
    
    double availableHeight = canvas.getHeight() - HEADER_HEIGHT;
    double totalPadding = TRACK_PADDING * (tracks.size() - 1);
    double trackAreaHeight = availableHeight - totalPadding;
    
    // Calculate total preferred height weight
    double totalPreferredHeight = 0;
    for (Track t : tracks) {
      totalPreferredHeight += t.getPreferredHeight();
    }
    
    // Calculate dynamic height based on track's preferred height ratio
    if (totalPreferredHeight > 0) {
      double heightRatio = track.getPreferredHeight() / totalPreferredHeight;
      return trackAreaHeight * heightRatio;
    } else {
      return trackAreaHeight / tracks.size();
    }
  }
  
  private Track findTrackAt(double y) {
    if (featureTracksCanvas == null) return null;
    if (featureTracksCanvas.isCollapsed()) return null;
    
    List<Track> tracks = featureTracksCanvas.getTracks();
    if (tracks.isEmpty()) return null;
    
    double currentY = HEADER_HEIGHT;
    for (Track track : tracks) {
      // Show all tracks (visible and invisible)
      double trackHeight = calculateTrackHeight(track);
      if (y >= currentY && y < currentY + trackHeight) {
        return track;
      }
      currentY += trackHeight + TRACK_PADDING;
    }
    return null;
  }
  
  private void addBedTrack() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BED File");
    File lastDir = UserPreferences.getLastDirectory("BED");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        // Directory became inaccessible, FileChooser will use system default
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new FileChooser.ExtensionFilter("BED files", "*.bed", "*.bed.gz"),
      new FileChooser.ExtensionFilter("All files", "*.*")
    );
    
    File file = fileChooser.showOpenDialog(canvas.getScene().getWindow());
    if (file != null && featureTracksCanvas != null) {
      UserPreferences.setLastDirectory("BED", file.getParentFile());
      try {
        BedTrack track = new BedTrack(file.toPath());
        featureTracksCanvas.addTrack(track);
        featureTracksCanvas.setCollapsed(false);
        draw();
      } catch (java.io.IOException e) {
        System.err.println("Failed to load BED file: " + e.getMessage());
      }
    }
  }
  
  private void addBigWigTrack() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BigWig File");
    File lastDir = UserPreferences.getLastDirectory("BIGWIG");
    if (lastDir != null) {
      try {
        fileChooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        // Directory became inaccessible, FileChooser will use system default
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    fileChooser.getExtensionFilters().addAll(
      new FileChooser.ExtensionFilter("BigWig files", "*.bw", "*.bigwig", "*.bigWig"),
      new FileChooser.ExtensionFilter("All files", "*.*")
    );
    
    File file = fileChooser.showOpenDialog(canvas.getScene().getWindow());
    if (file != null && featureTracksCanvas != null) {
      UserPreferences.setLastDirectory("BIGWIG", file.getParentFile());
      try {
        BigWigTrack track = new BigWigTrack(file.toPath());
        featureTracksCanvas.addTrack(track);
        featureTracksCanvas.setCollapsed(false);
        draw();
      } catch (java.io.IOException e) {
        System.err.println("Failed to load BigWig file: " + e.getMessage());
      }
    }
  }
  
  private void showUcscTracksBrowser() {
    if (canvas.getScene() == null || canvas.getScene().getWindow() == null) return;
    
    // Get current genome from referenceGenomeService
    String genome = "hg38"; // Default
    if (referenceGenomeService.hasGenome()) {
      String genomeName = referenceGenomeService.getCurrentGenome().getName();
      // Map common genome names to UCSC assembly names
      genome = switch (genomeName.toLowerCase()) {
        case "grch38" -> "hg38";
        case "grch37" -> "hg19";
        case "grcm38" -> "mm10";
        case "grcm39" -> "mm39";
        default -> genomeName.toLowerCase();
      };
    }
    
    UcscTracksBrowser browser = new UcscTracksBrowser(
        (javafx.stage.Stage) canvas.getScene().getWindow(),
        featureTracksCanvas,
        genome
    );
    browser.show();
  }
  
  /**
   * Draw the sidebar.
   */
  public void draw() {
    double width = canvas.getWidth();
    double height = canvas.getHeight();
    
    if (width <= 0 || height <= 0) return;
    
    // Fill background
    gc.setFill(DrawColors.SIDEBAR);
    gc.fillRect(0, 0, width, height);
    
    // Draw master track header
    drawMasterTrackHeader(width);
    
    if (featureTracksCanvas == null) return;
    if (featureTracksCanvas.isCollapsed()) return;
    
    // Clear icon regions
    iconRegions.clear();
    
    // Draw track names with icons
    List<Track> tracks = featureTracksCanvas.getTracks();
    double currentY = HEADER_HEIGHT;
    
    for (Track track : tracks) {
      // Show all tracks (visible and invisible)
      double trackHeight = calculateTrackHeight(track);
      boolean isVisible = track.isVisible();
      
      // Draw eye icon (left side)
      double eyeX = ICON_PADDING;
      double eyeY = currentY + (trackHeight - ICON_SIZE) / 2;
      drawEyeIcon(eyeX, eyeY, isVisible);
      iconRegions.add(new IconRegion(track, "eye", eyeX, eyeY, ICON_SIZE));
      
      // Draw settings cogwheel icon (next to eye)
      double cogX = eyeX + ICON_SIZE + ICON_PADDING;
      double cogY = eyeY;
      drawCogwheelIcon(cogX, cogY, isVisible);
      iconRegions.add(new IconRegion(track, "settings", cogX, cogY, ICON_SIZE));
      
      // Track name (after icons)
      double textX = cogX + ICON_SIZE + ICON_PADDING + 2;
      gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
      gc.setFont(AppFonts.getUIFont(9));
      gc.fillText(track.getName(), textX, currentY + 12);
      
      // Track type (smaller, dimmed)
      gc.setFill(isVisible ? Color.web("#888888") : Color.web("#555555"));
      gc.setFont(AppFonts.getUIFont(8));
      gc.fillText(track.getType(), textX, currentY + 22);
      
      // Draw separator
      gc.setStroke(DrawColors.BORDER);
      gc.strokeLine(0, currentY + trackHeight, width, currentY + trackHeight);
      
      currentY += trackHeight + TRACK_PADDING;
    }
  }
  
  /**
   * Draw eye icon for visibility toggle.
   */
  private void drawEyeIcon(double x, double y, boolean visible) {
    double centerX = x + ICON_SIZE / 2;
    double centerY = y + ICON_SIZE / 2;
    
    if (visible) {
      // Open eye - blue
      gc.setFill(Color.rgb(100, 160, 220));
      gc.setStroke(Color.rgb(100, 160, 220));
      gc.setLineWidth(1.2);
      // Eye outline
      gc.strokeOval(centerX - 5, centerY - 2.5, 10, 5);
      // Pupil
      gc.fillOval(centerX - 2, centerY - 2, 4, 4);
    } else {
      // Closed eye with slash - gray
      gc.setStroke(Color.rgb(100, 100, 100));
      gc.setLineWidth(1.2);
      // Eye outline
      gc.strokeOval(centerX - 5, centerY - 2.5, 10, 5);
      // Slash through
      gc.strokeLine(x + 2, y + ICON_SIZE - 2, x + ICON_SIZE - 2, y + 2);
    }
    gc.setLineWidth(1);
  }
  
  /**
   * Draw the master track header (similar to sample tracks).
   * Contains settings button (⚙) and add button (+).
   */
  private void drawMasterTrackHeader(double width) {
    // Background — slightly different shade
    gc.setFill(Color.web("#2b2d30"));
    gc.fillRect(0, 0, width, HEADER_HEIGHT);
    
    // Settings button (⚙) on left
    double settingsX = 4;
    double settingsY = (HEADER_HEIGHT - 14) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(settingsX, settingsY, 14, 14, 3, 3);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(settingsX, settingsY, 14, 14, 3, 3);
    gc.setFont(AppFonts.getUIFont(10));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("⚙", settingsX + 2, settingsY + 11);
    
    // Label with track count
    gc.setFont(AppFonts.getUIFont(10));
    gc.setFill(Color.web("#999999"));
    int trackCount = featureTracksCanvas != null ? featureTracksCanvas.getTracks().size() : 0;
    String label = trackCount == 0 ? "Feature Tracks" : "Feature Tracks (" + trackCount + ")";
    gc.fillText(label, 24, 13);
    
    // "+" button on right
    double plusX = width - 20;
    double plusY = (HEADER_HEIGHT - 14) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(plusX, plusY, 14, 14, 3, 3);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(plusX, plusY, 14, 14, 3, 3);
    gc.setFont(AppFonts.getUIFont(12));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("+", plusX + 3, plusY + 11);
    
    // Bottom border
    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(0, HEADER_HEIGHT, width, HEADER_HEIGHT);
  }
  
  /**
   * Draw cogwheel/settings icon (Unicode gear character).
   */
  private void drawCogwheelIcon(double x, double y, boolean trackVisible) {
    Color color = trackVisible ? Color.rgb(140, 140, 140) : Color.rgb(80, 80, 80);
    
    // Draw the gear using Unicode character ⚙
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 12));
    gc.setFill(color);
    gc.fillText("⚙", x + 1, y + ICON_SIZE - 2);
  }
  
  /**
   * Show settings popup for a track.
   */
  private void showSettingsPopup(Track track, double screenX, double screenY) {
    if (settingsPopup != null) {
      settingsPopup.hide();
    }
    
    settingsPopup = new Popup();
    settingsPopup.setAutoHide(true);
    
    VBox content = new VBox(8);
    content.setPadding(new Insets(12));
    content.setStyle("-fx-background-color: #2a2a2e; -fx-border-color: #555; -fx-border-radius: 4; -fx-background-radius: 4;");
    
    // Title
    Label title = new Label(track.getName() + " Settings");
    title.setStyle("-fx-text-fill: #ddd; -fx-font-weight: bold;");
    content.getChildren().add(title);
    
    // Auto-scale checkbox with visible green checkmark
    CheckBox autoScale = new CheckBox("Auto-scale");
    autoScale.setSelected(track.isAutoScale());
    autoScale.setStyle("-fx-text-fill: #ccc;");
    
    // Create a custom checkmark graphic that replaces the native one
    StackPane customBox = new StackPane();
    customBox.setPrefSize(16, 16);
    customBox.setStyle("-fx-border-color: #66cc66; -fx-border-width: 1.5; -fx-background-color: #333;");
    
    SVGPath checkMark = new SVGPath();
    checkMark.setContent("M 2 8 L 6 12 L 14 2");
    checkMark.setStroke(Color.web("#66cc66"));
    checkMark.setStrokeWidth(2.5);
    checkMark.setFill(null);
    checkMark.visibleProperty().bind(autoScale.selectedProperty());
    
    customBox.getChildren().add(checkMark);
    
    // Create HBox with custom checkbox and label
    HBox autoRow = new HBox(6);
    autoRow.setAlignment(Pos.CENTER_LEFT);
    
    // Make customBox clickable to toggle checkbox
    customBox.setOnMouseClicked(e -> autoScale.setSelected(!autoScale.isSelected()));
    
    Label autoLabel = new Label("Auto-scale");
    autoLabel.setStyle("-fx-text-fill: #ccc;");
    autoLabel.setOnMouseClicked(e -> autoScale.setSelected(!autoScale.isSelected()));
    
    autoRow.getChildren().addAll(customBox, autoLabel);
    
    // Min/Max value fields
    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(6);
    
    Label minLabel = new Label("Min:");
    minLabel.setStyle("-fx-text-fill: #aaa;");
    TextField minField = new TextField();
    minField.setPrefWidth(80);
    minField.setStyle("-fx-background-color: #333; -fx-text-fill: #ddd; -fx-border-color: #555;");
    if (track.getMinValue() != null) {
      minField.setText(String.format("%.2f", track.getMinValue()));
    }
    
    Label maxLabel = new Label("Max:");
    maxLabel.setStyle("-fx-text-fill: #aaa;");
    TextField maxField = new TextField();
    maxField.setPrefWidth(80);
    maxField.setStyle("-fx-background-color: #333; -fx-text-fill: #ddd; -fx-border-color: #555;");
    if (track.getMaxValue() != null) {
      maxField.setText(String.format("%.2f", track.getMaxValue()));
    }
    
    grid.add(minLabel, 0, 0);
    grid.add(minField, 1, 0);
    grid.add(maxLabel, 0, 1);
    grid.add(maxField, 1, 1);
    
    // Enable/disable fields based on auto-scale
    minField.setDisable(autoScale.isSelected());
    maxField.setDisable(autoScale.isSelected());
    autoScale.selectedProperty().addListener((obs, old, newVal) -> {
      minField.setDisable(newVal);
      maxField.setDisable(newVal);
      if (newVal) {
        minField.clear();
        maxField.clear();
      }
    });
    
    // Buttons
    HBox buttons = new HBox(8);
    buttons.setAlignment(Pos.CENTER_RIGHT);
    
    Button applyBtn = new Button("Apply");
    applyBtn.setStyle("-fx-background-color: #3a6ea5; -fx-text-fill: white;");
    applyBtn.setOnAction(e -> {
      if (autoScale.isSelected()) {
        track.setMinValue(null);
        track.setMaxValue(null);
      } else {
        try {
          String minText = minField.getText().trim();
          String maxText = maxField.getText().trim();
          track.setMinValue(minText.isEmpty() ? null : Double.valueOf(minText));
          track.setMaxValue(maxText.isEmpty() ? null : Double.valueOf(maxText));
        } catch (NumberFormatException ex) {
          // Ignore invalid input
        }
      }
      if (featureTracksCanvas != null) {
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
      settingsPopup.hide();
    });
    
    Button cancelBtn = new Button("Cancel");
    cancelBtn.setStyle("-fx-background-color: #555; -fx-text-fill: #ccc;");
    cancelBtn.setOnAction(e -> settingsPopup.hide());
    
    buttons.getChildren().addAll(cancelBtn, applyBtn);
    
    content.getChildren().addAll(autoRow, grid, buttons);
    
    settingsPopup.getContent().add(content);
    settingsPopup.show(canvas.getScene().getWindow(), screenX, screenY);
  }
  
  /**
   * Draw reactive overlay (hover effects).
   */
  private void drawReactive() {
    double width = reactiveCanvas.getWidth();
    double height = reactiveCanvas.getHeight();
    
    reactiveGc.clearRect(0, 0, width, height);
    
    // Draw master track button hover effects
    if (plusButtonHovered) {
      double plusX = width - 20;
      double plusY = (HEADER_HEIGHT - 14) / 2;
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
      reactiveGc.fillRoundRect(plusX, plusY, 14, 14, 3, 3);
    }
    
    if (settingsButtonHovered) {
      double settingsX = 4;
      double settingsY = (HEADER_HEIGHT - 14) / 2;
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
      reactiveGc.fillRoundRect(settingsX, settingsY, 14, 14, 3, 3);
    }
    
    if (featureTracksCanvas == null) return;
    if (featureTracksCanvas.isCollapsed()) return;
    
    // Highlight hovered icon
    if (hoveredIcon != null) {
      for (IconRegion region : iconRegions) {
        if (region.contains(reactiveCanvas.getScene().getWindow().getX(), 
                            reactiveCanvas.getScene().getWindow().getY())) {
          // Skip - we'll draw below
        }
      }
      // Draw hover effect on the icon
      for (IconRegion region : iconRegions) {
        if (region.iconType().equals(hoveredIcon) && region.track() == hoveredTrack) {
          reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
          reactiveGc.fillRoundRect(region.x() - 2, region.y() - 2, 
                                   region.size() + 4, region.size() + 4, 4, 4);
        }
      }
    }
    
    if (hoveredTrack == null) return;
    
    // Find track position and highlight it
    List<Track> tracks = featureTracksCanvas.getTracks();
    double currentY = HEADER_HEIGHT;
    
    for (Track track : tracks) {
      double trackHeight = calculateTrackHeight(track);
      
      if (track == hoveredTrack) {
        // Subtle highlight on track row
        reactiveGc.setFill(Color.rgb(255, 255, 255, 0.05));
        reactiveGc.fillRect(0, currentY, width, trackHeight);
        
        // Highlight text
        double textX = ICON_PADDING + ICON_SIZE + ICON_PADDING + ICON_SIZE + ICON_PADDING + 2;
        reactiveGc.setFill(Color.WHITE);
        reactiveGc.setFont(AppFonts.getUIFont(9));
        reactiveGc.fillText(track.getName(), textX, currentY + 12);
        break;
      }
      
      currentY += trackHeight + TRACK_PADDING;
    }
  }
}
