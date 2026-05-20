package org.baseplayer.components.sidebars;

import java.util.List;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.FeatureTracksCanvas;
import static org.baseplayer.features.FeatureTracksCanvas.TRACK_PADDING;
import org.baseplayer.features.Track;
import org.baseplayer.features.TrackSettingsPopup;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;

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
public class FeatureTracksPanel extends SidebarContentPanel {

  // ── Layout constants ──────────────────────────────────────────────────────

  private static final double ICON_SIZE    = 14;
  private static final double ICON_PADDING = 4;

  // ── State ─────────────────────────────────────────────────────────────────

  private FeatureTracksCanvas featureTracksCanvas;
  private final TrackSettingsPopup settingsPopup = new TrackSettingsPopup();

  // ── Construction ──────────────────────────────────────────────────────────

  public FeatureTracksPanel(StackPane parent) {
    super(parent);
    setupHoverHandlers();
    setupClickHandler();
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

  @Override
  public void draw() {
    double w = canvas.getWidth();
    double h = canvas.getHeight();
    if (w <= 0 || h <= 0) return;

    gc.setFill(DrawColors.SIDEBAR);
    gc.fillRect(0, 0, w, h);

    if (featureTracksCanvas == null || featureTracksCanvas.isCollapsed()) return;

    clearIconRegions();

    List<Track> tracks = featureTracksCanvas.getTracks();
    double currentY = 0;

    for (int i = 0; i < tracks.size(); i++) {
      Track track = tracks.get(i);
      double trackHeight = calculateTrackHeight(track);
      boolean isVisible = track.isVisible();

      // Eye icon (left)
      double eyeX = ICON_PADDING;
      double eyeY = currentY + (trackHeight - ICON_SIZE) / 2;
      drawEyeIcon(eyeX, eyeY, isVisible);
      addIconRegion(i, "eye", eyeX, eyeY, ICON_SIZE, ICON_SIZE);

      // Settings cogwheel icon (next to eye)
      double cogX = eyeX + ICON_SIZE + ICON_PADDING;
      double cogY = eyeY;
      drawCogwheelIcon(cogX, cogY, isVisible);
      addIconRegion(i, "settings", cogX, cogY, ICON_SIZE, ICON_SIZE);

      // Track name
      double textX = cogX + ICON_SIZE + ICON_PADDING + 2;
      gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
      gc.setFont(AppFonts.getUIFont(9));
      gc.fillText(track.getName(), textX, currentY + 12);

      // Track type
      gc.setFill(isVisible ? Color.web("#888888") : Color.web("#555555"));
      gc.setFont(AppFonts.getUIFont(8));
      gc.fillText(track.getType(), textX, currentY + 22);

      // Remove icon (right)
      double removeX = Math.max(w - ICON_SIZE - ICON_PADDING, textX + 20);
      double removeY = currentY + (trackHeight - ICON_SIZE) / 2;
      gc.setFill(Color.web("#3c3c3c"));
      gc.fillRoundRect(removeX - 1, removeY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
      gc.setFill(Color.web("#cc6666"));
      gc.setFont(AppFonts.getUIFont(10));
      gc.fillText("✕", removeX + 3, removeY + ICON_SIZE - 3);
      addIconRegion(i, "remove", removeX - 1, removeY - 1, ICON_SIZE + 2, ICON_SIZE + 2);

      // Separator
      gc.setStroke(DrawColors.BORDER);
      gc.strokeLine(0, currentY + trackHeight, w, currentY + trackHeight);

      currentY += trackHeight + TRACK_PADDING;
    }
  }

  // ── Reactive overlay ──────────────────────────────────────────────────────

  @Override
  protected void drawReactive() {
    double w = reactiveCanvas.getWidth();
    double h = reactiveCanvas.getHeight();
    reactiveGc.clearRect(0, 0, w, h);

    if (featureTracksCanvas == null || featureTracksCanvas.isCollapsed()) return;

    List<Track> tracks = featureTracksCanvas.getTracks();

    // Icon hover glow
    if (hoveredIcon != null && hoverIndex >= 0 && hoverIndex < tracks.size()) {
      IconRegion region = findIconRegion(hoveredIcon, hoverIndex);
      if (region != null) {
        reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
        reactiveGc.fillRoundRect(region.x() - 2, region.y() - 2,
            region.width() + 4, region.height() + 4, 4, 4);
      }
    }

    if (hoverIndex < 0 || hoverIndex >= tracks.size()) return;

    // Track row highlight
    double currentY = 0;
    for (int i = 0; i < tracks.size(); i++) {
      double trackHeight = calculateTrackHeight(tracks.get(i));
      if (i == hoverIndex) {
        reactiveGc.setFill(Color.rgb(255, 255, 255, 0.05));
        reactiveGc.fillRect(0, currentY, w, trackHeight);

        double textX = ICON_PADDING + ICON_SIZE + ICON_PADDING + ICON_SIZE + ICON_PADDING + 2;
        reactiveGc.setFill(Color.WHITE);
        reactiveGc.setFont(AppFonts.getUIFont(9));
        reactiveGc.fillText(tracks.get(i).getName(), textX, currentY + 12);
        break;
      }
      currentY += trackHeight + TRACK_PADDING;
    }
  }

  // ── Mouse handlers ────────────────────────────────────────────────────────

  @Override
  protected int findRowAt(double y) {
    if (featureTracksCanvas == null || featureTracksCanvas.isCollapsed()) return -1;
    List<Track> tracks = featureTracksCanvas.getTracks();
    double currentY = 0;
    for (int i = 0; i < tracks.size(); i++) {
      double trackHeight = calculateTrackHeight(tracks.get(i));
      if (y >= currentY && y < currentY + trackHeight) return i;
      currentY += trackHeight + TRACK_PADDING;
    }
    return -1;
  }

  @Override
  protected String findIconAt(double x, double y, int rowIdx) {
    return findIconFromRegions(x, y, rowIdx);
  }

  private void setupClickHandler() {
    reactiveCanvas.setOnMouseClicked(event -> {
      if (event.getButton() == MouseButton.PRIMARY) {
        int rowIdx = findRowAt(event.getY());
        if (rowIdx >= 0 && featureTracksCanvas != null) {
          List<Track> tracks = featureTracksCanvas.getTracks();
          if (rowIdx < tracks.size()) {
            String icon = findIconAt(event.getX(), event.getY(), rowIdx);
            Track track = tracks.get(rowIdx);
            if ("eye".equals(icon)) {
              track.setVisible(!track.isVisible());
              featureTracksCanvas.notifyRegionChanged();
              GenomicCanvas.update.set(!GenomicCanvas.update.get());
              draw();
              drawReactive();
            }
            if ("settings".equals(icon)) {
              showSettingsPopup(track, event.getScreenX(), event.getScreenY());
            }
            if ("remove".equals(icon)) {
              featureTracksCanvas.removeTrack(track);
              GenomicCanvas.update.set(!GenomicCanvas.update.get());
              draw();
              drawReactive();
            }
          }
        }
      }
    });
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
}
