package org.baseplayer.features;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

/**
 * Canvas for drawing feature tracks, extends GenomicCanvas for navigation.
 */
public class FeatureTracksCanvas extends GenomicCanvas {
  
  /** Panel header height in pixels — shared with {@link FeatureTracksSidebar}. */
  static final double HEADER_HEIGHT = 18;
  /** Padding between tracks in pixels — shared with {@link FeatureTracksSidebar}. */
  static final double TRACK_PADDING = 2;
  
  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
  private final GraphicsContext gc;
  private final List<Track> tracks = new ArrayList<>();
  private boolean collapsed = false;
  private ContextMenu contextMenu;
  private Runnable onCollapsedChanged;
  
  public FeatureTracksCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    
    gc = getGraphicsContext2D();
    gc.setFont(AppFonts.getUIFont());
    
    // Set up mouse entered listener to set hover stack
    reactiveCanvas.setOnMouseEntered(event -> {
      stackManager.setHoverStack(drawStack);
      resizing = true;
      update.set(!update.get());
      resizing = false;
    });
    
    setupContextMenu();
    setupMouseHandlers();
  }
  
  /**
   * Set callback for when collapsed state changes.
   */
  public void setOnCollapsedChanged(Runnable callback) {
    this.onCollapsedChanged = callback;
  }
  
  @Override
  public void draw() {
    double width = getWidth();
    double height = getHeight();
    
    if (width <= 0 || height <= 0) return;
    
    // Clear background
    gc.setFill(Color.rgb(26, 26, 30));
    gc.fillRect(0, 0, width, height);
    
    // Draw header
    drawHeader(width);
    
    if (collapsed || tracks.isEmpty()) {
      super.draw();
      return;
    }
    
    // Notify tracks of region (trigger data fetch if needed)
    notifyRegionChanged();
    
    // Draw all tracks (visible tracks get data drawn, invisible show placeholder)
    // Heights are calculated dynamically based on available space and preferred height ratios
    double y = HEADER_HEIGHT;
    String chrom = drawStack.chromosome;
    double start = drawStack.start;  // Keep as double for smooth scrolling
    double end = drawStack.end;
    
    for (Track track : tracks) {
      // Calculate dynamic height based on available space
      double trackHeight = calculateTrackHeight(track);
      
      if (!track.isVisible()) {
        // Draw dimmed placeholder for invisible tracks
        gc.setFill(Color.rgb(20, 20, 25, 0.6));
        gc.fillRect(0, y, width, trackHeight);
        
        // Draw track name (dimmed)
        gc.setFill(Color.rgb(80, 80, 80));
        gc.setFont(AppFonts.getUIFont(10));
        gc.fillText(track.getName() + " (click eye icon to enable)", 10, y + trackHeight / 2 + 4);
      } else {
        // Draw actual track data
        track.draw(gc, 0, y, width, trackHeight, chrom, start, end);
      }
      
      y += trackHeight + TRACK_PADDING;
    }
    
    super.draw();
  }
  
  private void drawHeader(double width) {
    // Header background
    gc.setFill(Color.rgb(35, 35, 40));
    gc.fillRect(0, 0, width, HEADER_HEIGHT);
    
    // Header text
    gc.setFill(Color.rgb(150, 150, 150));
    gc.setFont(AppFonts.getUIFont(10));
    
    String headerText = collapsed ? "▶ Feature Tracks" : "▼ Feature Tracks";
    if (!tracks.isEmpty()) {
      headerText += " (" + tracks.size() + ")";
    }
    gc.fillText(headerText, 6, 13);
    
    // Add track hint
    gc.setFill(Color.rgb(100, 100, 100));
    gc.fillText("Right-click to add tracks", width - 140, 13);
  }
  
  /**
   * Notify all tracks of region change to trigger data fetching.
   */
  public void notifyRegionChanged() {
    if (drawStack == null) return;
    
    String chrom = drawStack.chromosome;
    long start = (long) drawStack.start;
    long end = (long) drawStack.end;
    
    for (Track track : tracks) {
      if (track.isVisible()) {
        track.onRegionChanged(chrom, start, end);
      }
    }
  }
  
  /**
   * Add a track.
   */
  public void addTrack(Track track) {
    tracks.add(track);
    
    // Set up callback for async tracks to trigger redraw
    if (track instanceof ConservationTrack ct) {
      ct.setOnDataLoaded(() -> update.set(!update.get()));
    }
    if (track instanceof GnomadTrack gt) {
      gt.setOnDataLoaded(() -> update.set(!update.get()));
    }
    
    notifyRegionChanged();
    update.set(!update.get());
  }
  
  /**
   * Remove a track.
   */
  public void removeTrack(Track track) {
    track.dispose();
    tracks.remove(track);
    update.set(!update.get());
  }
  
  /**
   * Get all tracks.
   */
  public List<Track> getTracks() {
    return new ArrayList<>(tracks);
  }
  
  public boolean isCollapsed() {
    return collapsed;
  }
  
  public void setCollapsed(boolean collapsed) {
    this.collapsed = collapsed;
    if (onCollapsedChanged != null) {
      onCollapsedChanged.run();
    }
    update.set(!update.get());
  }
  
  /**
   * Calculate preferred height based on visible tracks.
   */
  public double getPreferredHeight() {
    if (collapsed || tracks.isEmpty()) {
      return HEADER_HEIGHT;
    }
    
    double totalHeight = HEADER_HEIGHT;
    for (Track track : tracks) {
      // Show all tracks (both visible and invisible) with eye icons
      totalHeight += track.getPreferredHeight() + TRACK_PADDING;
    }
    return totalHeight;
  }
  
  private void setupContextMenu() {
    contextMenu = new ContextMenu();
    
    // Toggle collapse
    MenuItem toggleItem = new MenuItem("Collapse/Expand");
    toggleItem.setOnAction(e -> setCollapsed(!collapsed));
    
    // Add from file
    MenuItem addBedFile = new MenuItem("Add BED file...");
    addBedFile.setOnAction(e -> showAddFileDialog("BED", "*.bed", "*.bed.gz"));
    
    MenuItem addBigWigFile = new MenuItem("Add BigWig file...");
    addBigWigFile.setOnAction(e -> showAddFileDialog("BigWig", "*.bw", "*.bigwig", "*.bigWig"));
    
    // Remove tracks
    MenuItem removeAll = new MenuItem("Remove all tracks");
    removeAll.setOnAction(e -> new ArrayList<>(tracks).forEach(this::removeTrack));
    
    contextMenu.getItems().addAll(
        toggleItem,
        new SeparatorMenuItem(),
        addBedFile,
        addBigWigFile,
        new SeparatorMenuItem(),
        removeAll
    );
    
    // Show context menu on right-click
    getReactiveCanvas().setOnContextMenuRequested(e -> {
      contextMenu.show(getReactiveCanvas(), e.getScreenX(), e.getScreenY());
    });
  }
  
  private void setupMouseHandlers() {
    getReactiveCanvas().setOnMouseClicked(e -> {
      if (e.isConsumed() || isDragging()) return;
      
      // Handle header click (toggle collapse)
      if (e.getY() < HEADER_HEIGHT && e.getClickCount() == 1) {
        setCollapsed(!collapsed);
        e.consume();
        return;
      }
      
      // Handle track clicks
      if (!collapsed && e.getClickCount() == 1 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
        // Find which track was clicked
        Track clickedTrack = getTrackAtY(e.getY());
        if (clickedTrack != null && clickedTrack.isVisible() && clickedTrack.supportsClick()) {
          // Calculate click position relative to track
          double trackY = getTrackY(clickedTrack);
          double clickRelativeY = e.getY() - trackY;
          double clickRelativeX = e.getX();
          
          // Let the track handle the click (use dynamic height)
          boolean handled = clickedTrack.handleClick(
              clickRelativeX, clickRelativeY,
              getWidth(), calculateTrackHeight(clickedTrack),
              drawStack.chromosome, drawStack.start, drawStack.end,
              getScene().getWindow(), e.getScreenX(), e.getScreenY()
          );
          
          if (handled) {
            e.consume();
          }
        }
      }
    });
  }
  
  /**
   * Calculate dynamic height for a track based on available space and preferred height ratios.
   */
  private double calculateTrackHeight(Track track) {
    if (tracks.isEmpty()) return 0;
    
    double availableHeight = getHeight() - HEADER_HEIGHT;
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
  
  /**
   * Get the track at the given Y coordinate.
   */
  private Track getTrackAtY(double y) {
    if (y < HEADER_HEIGHT) return null;
    
    double trackY = HEADER_HEIGHT;
    for (Track track : tracks) {
      double trackHeight = calculateTrackHeight(track);
      if (y >= trackY && y < trackY + trackHeight) {
        return track;
      }
      trackY += trackHeight + TRACK_PADDING;
    }
    return null;
  }
  
  /**
   * Get the Y position of a track.
   */
  private double getTrackY(Track track) {
    double y = HEADER_HEIGHT;
    for (Track t : tracks) {
      if (t == track) {
        return y;
      }
      y += calculateTrackHeight(t) + TRACK_PADDING;
    }
    return y;
  }
  
  private void showAddFileDialog(String type, String... extensions) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Add " + type + " Track");
    
    // Use appropriate file type for directory preference
    String fileType = type.toUpperCase();
    if (type.equals("BigWig")) {
      fileType = "BIGWIG";
    }
    
    java.io.File lastDir = UserPreferences.getLastDirectory(fileType);
    if (lastDir != null) {
      try {
        chooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
        // Directory became inaccessible, FileChooser will use system default
        System.err.println("Last directory not accessible: " + lastDir + ". Using default.");
      }
    }
    
    chooser.getExtensionFilters().add(
        new FileChooser.ExtensionFilter(type + " files", extensions)
    );
    
    java.io.File file = chooser.showOpenDialog(getScene().getWindow());
    if (file != null) {
      UserPreferences.setLastDirectory(fileType, file.getParentFile());
      try {
        Track track;
          switch (type) {
              case "BED" -> track = new BedTrack(file.toPath());
              case "BigWig" -> track = new BigWigTrack(file.toPath());
              default -> {
                  return;
              }
          }
        addTrack(track);
      } catch (IOException ex) {
        System.err.println("Failed to load track file: " + ex.getMessage());
      }
    }
  }
}
