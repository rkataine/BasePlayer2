package org.baseplayer.components.sidebars;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.FeatureTracksCanvas;
import static org.baseplayer.features.FeatureTracksCanvas.TRACK_PADDING;
import org.baseplayer.features.Track;
import org.baseplayer.features.TrackSettingsPopup;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * Canvas panel that renders individual feature track rows in the feature-tracks
 * sidebar.
 *
 * <p>Each row shows an eye (visibility toggle), a settings (⚙) icon, the track
 * name/type, and a separator line.  On hover, the track row is highlighted and
 * the name becomes white.</p>
 *
 * <p>This is the feature-track equivalent of {@link SampleListPanel} for
 * alignment samples.</p>
 */
public class FeatureTracksPanel {

  // ── Layout constants ──────────────────────────────────────────────────────

  private static final double ICON_SIZE    = 14;
  private static final double ICON_PADDING = 4;

  // ── Icon click regions ────────────────────────────────────────────────────

  private record IconRegion(Track track, String iconType, double x, double y, double size) {
    boolean contains(double mx, double my) {
      return mx >= x && mx <= x + size && my >= y && my <= y + size;
    }
  }

  private final List<IconRegion> iconRegions = new ArrayList<>();

  // ── State ─────────────────────────────────────────────────────────────────

  private final Canvas canvas;
  private final Canvas reactiveCanvas;
  private final GraphicsContext gc;
  private final GraphicsContext reactiveGc;

  private FeatureTracksCanvas featureTracksCanvas;
  private Track   hoveredTrack = null;
  private String  hoveredIcon  = null;   // "eye" | "settings"
  private final TrackSettingsPopup settingsPopup = new TrackSettingsPopup();
  private ContextMenu trackContextMenu;

  // ── Construction ──────────────────────────────────────────────────────────

  public FeatureTracksPanel(StackPane parent) {
    this.canvas = new Canvas();
    this.reactiveCanvas = new Canvas();

    canvas.widthProperty().bind(parent.widthProperty());
    canvas.heightProperty().bind(parent.heightProperty());
    reactiveCanvas.widthProperty().bind(parent.widthProperty());
    reactiveCanvas.heightProperty().bind(parent.heightProperty());

    gc = canvas.getGraphicsContext2D();
    reactiveGc = reactiveCanvas.getGraphicsContext2D();

    parent.getChildren().addAll(canvas, reactiveCanvas);

    setupContextMenu();
    setupMouseHandlers();
  }

  // ── Public API ────────────────────────────────────────────────────────────

  public void setFeatureTracksCanvas(FeatureTracksCanvas ftCanvas) {
    this.featureTracksCanvas = ftCanvas;
  }

  /** @return current {@link FeatureTracksCanvas}, or {@code null}. */
  public FeatureTracksCanvas getFeatureTracksCanvas() {
    return featureTracksCanvas;
  }

  // ── Drawing ───────────────────────────────────────────────────────────────

  public void draw() {
    double w = canvas.getWidth();
    double h = canvas.getHeight();
    if (w <= 0 || h <= 0) return;

    gc.setFill(DrawColors.SIDEBAR);
    gc.fillRect(0, 0, w, h);

    if (featureTracksCanvas == null || featureTracksCanvas.isCollapsed()) return;

    iconRegions.clear();

    List<Track> tracks = featureTracksCanvas.getTracks();
    double currentY = 0;

    for (Track track : tracks) {
      double trackHeight = calculateTrackHeight(track);
      boolean isVisible = track.isVisible();

      // Eye icon (left)
      double eyeX = ICON_PADDING;
      double eyeY = currentY + (trackHeight - ICON_SIZE) / 2;
      drawEyeIcon(eyeX, eyeY, isVisible);
      iconRegions.add(new IconRegion(track, "eye", eyeX, eyeY, ICON_SIZE));

      // Settings cogwheel icon (next to eye)
      double cogX = eyeX + ICON_SIZE + ICON_PADDING;
      double cogY = eyeY;
      drawCogwheelIcon(cogX, cogY, isVisible);
      iconRegions.add(new IconRegion(track, "settings", cogX, cogY, ICON_SIZE));

      // Track name
      double textX = cogX + ICON_SIZE + ICON_PADDING + 2;
      gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
      gc.setFont(AppFonts.getUIFont(9));
      gc.fillText(track.getName(), textX, currentY + 12);

      // Track type
      gc.setFill(isVisible ? Color.web("#888888") : Color.web("#555555"));
      gc.setFont(AppFonts.getUIFont(8));
      gc.fillText(track.getType(), textX, currentY + 22);

      // Separator
      gc.setStroke(DrawColors.BORDER);
      gc.strokeLine(0, currentY + trackHeight, w, currentY + trackHeight);

      currentY += trackHeight + TRACK_PADDING;
    }
  }

  // ── Reactive overlay ──────────────────────────────────────────────────────

  private void drawReactive() {
    double w = reactiveCanvas.getWidth();
    double h = reactiveCanvas.getHeight();
    reactiveGc.clearRect(0, 0, w, h);

    if (featureTracksCanvas == null || featureTracksCanvas.isCollapsed()) return;

    // Icon hover glow
    if (hoveredIcon != null && hoveredTrack != null) {
      for (IconRegion region : iconRegions) {
        if (region.iconType().equals(hoveredIcon) && region.track() == hoveredTrack) {
          reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
          reactiveGc.fillRoundRect(region.x() - 2, region.y() - 2,
              region.size() + 4, region.size() + 4, 4, 4);
        }
      }
    }

    if (hoveredTrack == null) return;

    // Track row highlight
    List<Track> tracks = featureTracksCanvas.getTracks();
    double currentY = 0;
    for (Track track : tracks) {
      double trackHeight = calculateTrackHeight(track);
      if (track == hoveredTrack) {
        reactiveGc.setFill(Color.rgb(255, 255, 255, 0.05));
        reactiveGc.fillRect(0, currentY, w, trackHeight);

        double textX = ICON_PADDING + ICON_SIZE + ICON_PADDING + ICON_SIZE + ICON_PADDING + 2;
        reactiveGc.setFill(Color.WHITE);
        reactiveGc.setFont(AppFonts.getUIFont(9));
        reactiveGc.fillText(track.getName(), textX, currentY + 12);
        break;
      }
      currentY += trackHeight + TRACK_PADDING;
    }
  }

  // ── Mouse handlers ────────────────────────────────────────────────────────

  private void setupMouseHandlers() {
    reactiveCanvas.setOnMouseMoved(event -> {
      Track track = findTrackAt(event.getY());
      String icon = findIconAt(event.getX(), event.getY());
      if (track != hoveredTrack || !Objects.equals(icon, hoveredIcon)) {
        hoveredTrack = track;
        hoveredIcon = icon;
        drawReactive();
      }
    });

    reactiveCanvas.setOnMouseExited(event -> {
      if (hoveredTrack != null || hoveredIcon != null) {
        hoveredTrack = null;
        hoveredIcon = null;
        drawReactive();
      }
    });

    reactiveCanvas.setOnMouseClicked(event -> {
      if (event.getButton() == MouseButton.PRIMARY) {
        // Icon clicks
        for (IconRegion region : iconRegions) {
          if (region.contains(event.getX(), event.getY())) {
            if ("eye".equals(region.iconType())) {
              region.track().setVisible(!region.track().isVisible());
              if (featureTracksCanvas != null) {
                featureTracksCanvas.notifyRegionChanged();
                GenomicCanvas.update.set(!GenomicCanvas.update.get());
              }
              draw();
            } else if ("settings".equals(region.iconType())) {
              showSettingsPopup(region.track(), event.getScreenX(), event.getScreenY());
            }
            return;
          }
        }
      } else if (event.getButton() == MouseButton.SECONDARY) {
        Track trackAtMouse = findTrackAt(event.getY());
        updateContextMenuForTrack(trackAtMouse);
        trackContextMenu.show(reactiveCanvas, event.getScreenX(), event.getScreenY());
      }
    });
  }

  // ── Track lookup ──────────────────────────────────────────────────────────

  private Track findTrackAt(double y) {
    if (featureTracksCanvas == null || featureTracksCanvas.isCollapsed()) return null;

    List<Track> tracks = featureTracksCanvas.getTracks();
    double currentY = 0;
    for (Track track : tracks) {
      double trackHeight = calculateTrackHeight(track);
      if (y >= currentY && y < currentY + trackHeight) return track;
      currentY += trackHeight + TRACK_PADDING;
    }
    return null;
  }

  private String findIconAt(double x, double y) {
    for (IconRegion region : iconRegions) {
      if (region.contains(x, y)) return region.iconType();
    }
    return null;
  }

  // ── Track height calculation ──────────────────────────────────────────────

  private double calculateTrackHeight(Track track) {
    if (featureTracksCanvas == null) return 0;

    List<Track> tracks = featureTracksCanvas.getTracks();
    if (tracks.isEmpty()) return 0;

    double availableHeight = canvas.getHeight();
    double totalPadding = TRACK_PADDING * (tracks.size() - 1);
    double trackAreaHeight = availableHeight - totalPadding;

    double totalPreferred = 0;
    for (Track t : tracks) totalPreferred += t.getPreferredHeight();

    if (totalPreferred > 0) {
      return trackAreaHeight * (track.getPreferredHeight() / totalPreferred);
    }
    return trackAreaHeight / tracks.size();
  }

  // ── Icon drawing ──────────────────────────────────────────────────────────

  private void drawEyeIcon(double x, double y, boolean visible) {
    double cx = x + ICON_SIZE / 2;
    double cy = y + ICON_SIZE / 2;

    if (visible) {
      gc.setFill(Color.rgb(100, 160, 220));
      gc.setStroke(Color.rgb(100, 160, 220));
      gc.setLineWidth(1.2);
      gc.strokeOval(cx - 5, cy - 2.5, 10, 5);
      gc.fillOval(cx - 2, cy - 2, 4, 4);
    } else {
      gc.setStroke(Color.rgb(100, 100, 100));
      gc.setLineWidth(1.2);
      gc.strokeOval(cx - 5, cy - 2.5, 10, 5);
      gc.strokeLine(x + 2, y + ICON_SIZE - 2, x + ICON_SIZE - 2, y + 2);
    }
    gc.setLineWidth(1);
  }

  private void drawCogwheelIcon(double x, double y, boolean trackVisible) {
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 12));
    gc.setFill(trackVisible ? Color.rgb(140, 140, 140) : Color.rgb(80, 80, 80));
    gc.fillText("⚙", x + 1, y + ICON_SIZE - 2);
  }

  // ── Popups / Menus ────────────────────────────────────────────────────────

  private void showSettingsPopup(Track track, double screenX, double screenY) {
    if (settingsPopup.isShowing()) settingsPopup.hide();
    settingsPopup.show(track, () -> {
      if (featureTracksCanvas != null) {
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    }, canvas.getScene().getWindow(), screenX, screenY);
  }

  private void setupContextMenu() {
    trackContextMenu = new ContextMenu();
  }

  private void updateContextMenuForTrack(Track track) {
    trackContextMenu.getItems().clear();

    if (track == null) return;

    MenuItem toggleVisibility = new MenuItem(track.isVisible() ? "Hide Track" : "Show Track");
    toggleVisibility.setOnAction(e -> {
      track.setVisible(!track.isVisible());
      if (featureTracksCanvas != null) {
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
      draw();
    });

    MenuItem removeItem = new MenuItem("Remove Track");
    removeItem.setOnAction(e -> {
      if (featureTracksCanvas != null) {
        featureTracksCanvas.removeTrack(track);
        draw();
      }
    });

    trackContextMenu.getItems().addAll(toggleVisibility, new SeparatorMenuItem(), removeItem);
  }
}
