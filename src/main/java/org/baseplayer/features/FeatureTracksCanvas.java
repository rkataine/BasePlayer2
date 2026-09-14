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
  
  /** Panel header height in pixels — shared with {@link org.baseplayer.components.sidebars.FeatureTracksSidebar}. */
  public static final double HEADER_HEIGHT = 20;
  /** Padding between tracks in pixels — shared with {@link org.baseplayer.components.sidebars.FeatureTracksSidebar}. */
  public static final double TRACK_PADDING = 2;
  
  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
  private final GraphicsContext gc;
  private final List<Track> tracks = new ArrayList<>();
  private boolean collapsed = true;
  private ContextMenu contextMenu;
  private Runnable onCollapsedChanged;
  
  /** Cached region parameters to avoid redundant notifyRegionChanged calls every frame. */
  private String lastNotifiedChrom = "";
  private long lastNotifiedStart = -1;
  private long lastNotifiedEnd = -1;
  
  public FeatureTracksCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    
    gc = getGraphicsContext2D();
    gc.setFont(AppFonts.getUIFont());
    
    // Set up mouse entered listener to set hover stack
    reactiveCanvas.setOnMouseEntered(event -> {
      stackManager.setHoverStack(drawStack);
      update.set(!update.get());
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

  private void notifyPreferredHeightChanged() {
    if (onCollapsedChanged != null) {
      onCollapsedChanged.run();
    }
  }
  
  @Override
  public void draw() {
    double width = getWidth();
    double height = getHeight();
    
    if (width <= 0 || height <= 0) return;
    
    // Clear background with header color for uniform appearance
    gc.setFill(Color.rgb(35, 35, 40));
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
    String chrom = drawStack.getChromosome();
    double start = drawStack.getViewStart();  // Keep as double for smooth scrolling
    double end = drawStack.getViewEnd();
    
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
    gc.setFill(Color.rgb(150, 150, 150));
    gc.setFont(AppFonts.getUIFont(11));
    
    String headerText = collapsed ? "▶" : "▼";
    if (!tracks.isEmpty()) {
      headerText += " (" + tracks.size() + ")";
    }
    gc.fillText(headerText, 8, HEADER_HEIGHT / 2 + 4);
  }
  
  public void notifyRegionChanged() {
    if (drawStack == null) return;
    
    String chrom = drawStack.getChromosome();
    long start = (long) drawStack.getViewStart();
    long end = (long) drawStack.getViewEnd();
    
    if (chrom != null && chrom.equals(lastNotifiedChrom) && start == lastNotifiedStart && end == lastNotifiedEnd) {
      return;
    }
    lastNotifiedChrom = chrom;
    lastNotifiedStart = start;
    lastNotifiedEnd = end;
    
    for (Track track : tracks) {
      if (track.isVisible()) {
        track.onRegionChanged(chrom, start, end, drawStack);
      }
    }
  }
  
  public void addTrack(Track track) {
    tracks.add(track);
    
    if (track instanceof AbstractUcscTrack ucscTrack) {
      ucscTrack.setOnDataLoaded(() -> update.set(!update.get()));
    }
    
    notifyRegionChanged();
    notifyPreferredHeightChanged();
    update.set(!update.get());
  }
  
  public void removeTrack(Track track) {
    track.dispose();
    tracks.remove(track);
    notifyPreferredHeightChanged();
    update.set(!update.get());
  }
  
  public List<Track> getTracks() {
    return new ArrayList<>(tracks);
  }
  
  public boolean isCollapsed() {
    return collapsed;
  }
  
  public void setCollapsed(boolean collapsed) {
    this.collapsed = collapsed;
    notifyPreferredHeightChanged();
    update.set(!update.get());
  }
  
  public double getPreferredHeight() {
    if (collapsed || tracks.isEmpty()) {
      return HEADER_HEIGHT;
    }
    
    double totalHeight = HEADER_HEIGHT;
    for (Track track : tracks) {
      totalHeight += track.getPreferredHeight() + TRACK_PADDING;
    }
    return totalHeight;
  }
  
  private void setupContextMenu() {
    contextMenu = new ContextMenu();
    
    MenuItem toggleItem = new MenuItem("Collapse/Expand");
    toggleItem.setOnAction(e -> setCollapsed(!collapsed));
    
    MenuItem addBedFile = new MenuItem("Add BED file...");
    addBedFile.setOnAction(e -> showAddFileDialog("BED", "*.bed", "*.bed.gz"));
    
    MenuItem addBigWigFile = new MenuItem("Add BigWig file...");
    addBigWigFile.setOnAction(e -> showAddFileDialog("BigWig", "*.bw", "*.bigwig", "*.bigWig"));
    
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
    
    getReactiveCanvas().setOnContextMenuRequested(e -> {
      contextMenu.show(getReactiveCanvas(), e.getScreenX(), e.getScreenY());
    });
  }
  
  private void setupMouseHandlers() {
    getReactiveCanvas().setOnMouseClicked(e -> {
      if (e.isConsumed() || isDragging()) return;
      
      if (e.getY() < HEADER_HEIGHT && e.getClickCount() == 1) {
        setCollapsed(!collapsed);
        e.consume();
        return;
      }
      
      if (!collapsed && e.getClickCount() == 1 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
        Track clickedTrack = getTrackAtY(e.getY());
        if (clickedTrack != null && clickedTrack.isVisible() && clickedTrack.supportsClick()) {
          double trackY = getTrackY(clickedTrack);
          double clickRelativeY = e.getY() - trackY;
          double clickRelativeX = e.getX();
          
          boolean handled = clickedTrack.handleClick(
              clickRelativeX, clickRelativeY,
              getWidth(), calculateTrackHeight(clickedTrack),
              drawStack.getChromosome(), drawStack.getViewStart(), drawStack.getViewEnd(),
              getScene().getWindow(), e.getScreenX(), e.getScreenY()
          );
          
          if (handled) {
            e.consume();
          }
        }
      }
    });
  }
  
  private double calculateTrackHeight(Track track) {
    if (tracks.isEmpty()) return 0;
    
    double availableHeight = getHeight() - HEADER_HEIGHT;
    double totalPadding = TRACK_PADDING * (tracks.size() - 1);
    double trackAreaHeight = availableHeight - totalPadding;
    
    double totalPreferredHeight = 0;
    for (Track t : tracks) {
      totalPreferredHeight += t.getPreferredHeight();
    }
    
    if (totalPreferredHeight > 0) {
      double heightRatio = track.getPreferredHeight() / totalPreferredHeight;
      return trackAreaHeight * heightRatio;
    } else {
      return trackAreaHeight / tracks.size();
    }
  }
  
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
    
    String fileType = type.toUpperCase();
    if (type.equals("BigWig")) {
      fileType = "BIGWIG";
    }
    
    java.io.File lastDir = UserPreferences.getLastDirectory(fileType);
    if (lastDir != null) {
      try {
        chooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException e) {
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
