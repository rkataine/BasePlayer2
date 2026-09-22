package org.baseplayer.components;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.ZoomController;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.draw.TrackBodyCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * Shared track action strip: add / settings / remove (/ reload).
 *
 * <ul>
 *   <li>Tall sidebar rows — under the track name, right-aligned</li>
 *   <li>Short rows — glass-pane overlay (not tied to TrackBodyCanvas)</li>
 *   <li>Sample / feature master headers — right-aligned in the header bar</li>
 * </ul>
 */
public final class SampleTrackControls {

  public static final double SIDEBAR_BUTTON_SIZE = 16;
  public static final double OVERLAY_BUTTON_SIZE = 22;
  public static final double MASTER_BUTTON_SIZE = 18;
  public static final double BUTTON_GAP = 4;
  public static final double VERTICAL_PADDING = 2;
  /** Extra space between the sample name baseline area and the control strip. */
  public static final double NAME_TO_CONTROLS_GAP = 10;
  public static final double SIDEBAR_RIGHT_MARGIN = 6;
  public static final double OVERLAY_LEFT_MARGIN = 8;
  public static final double MASTER_RIGHT_MARGIN = 6;
  public static final double MIN_ROW_HEIGHT_FOR_SIDEBAR = 52;
  private static final double OVERLAY_PAD = 6;
  private static final Duration HIDE_DELAY = Duration.millis(180);

  private static boolean installed;
  private static Canvas overlayCanvas;
  private static final List<Hit> overlayHits = new ArrayList<>();
  private static int activeTrackIndex = -1;
  private static boolean pointerOverOverlay;
  private static String hoveredOverlayId;
  private static PauseTransition hideDelay;

  private SampleTrackControls() {}

  public static boolean fitsInSidebar(double rowHeight) {
    return rowHeight >= MIN_ROW_HEIGHT_FOR_SIDEBAR;
  }

  public static double stripHeight(double buttonSize) {
    return buttonSize + 2 * VERTICAL_PADDING;
  }

  public static double width(boolean showReload, double buttonSize) {
    return width(showReload, true, buttonSize);
  }

  public static double width(boolean showReload, boolean showClose, double buttonSize) {
    int count = 2; // add + settings
    if (showClose) {
      count++;
    }
    if (showReload) {
      count++;
    }
    return count * buttonSize + (count - 1) * BUTTON_GAP;
  }

  public record Hit(String id, double x, double y, double width, double height) {
    public boolean contains(double mx, double my) {
      return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }
  }

  public static Hit findHit(List<Hit> hits, double mx, double my) {
    if (hits == null) {
      return null;
    }
    for (Hit hit : hits) {
      if (hit.contains(mx, my)) {
        return hit;
      }
    }
    return null;
  }

  /**
   * Install an interactive short-row control strip on the main glass host.
   * Call once after {@link ZoomController#installGlass}.
   */
  public static void installGlassOverlay() {
    if (installed) {
      return;
    }
    StackPane host = ZoomController.getGlassHost();
    if (host == null) {
      return;
    }
    installed = true;

    overlayCanvas = new Canvas();
    overlayCanvas.setManaged(false);
    overlayCanvas.setVisible(false);
    overlayCanvas.setMouseTransparent(false);
    host.getChildren().add(overlayCanvas);

    hideDelay = new PauseTransition(HIDE_DELAY);
    hideDelay.setOnFinished(e -> {
      if (!pointerOverOverlay) {
        hideGlassOverlay();
      }
    });

    overlayCanvas.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
      pointerOverOverlay = true;
      cancelHideDelay();
    });
    overlayCanvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
      pointerOverOverlay = false;
      hoveredOverlayId = null;
      paintOverlayCanvas();
      scheduleHideIfNeeded();
    });
    overlayCanvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
      Hit hit = findHit(overlayHits, e.getX(), e.getY());
      String next = hit != null ? hit.id() : null;
      overlayCanvas.setCursor(hit != null ? Cursor.HAND : Cursor.DEFAULT);
      if (!java.util.Objects.equals(next, hoveredOverlayId)) {
        hoveredOverlayId = next;
        paintOverlayCanvas();
      }
    });
    overlayCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1) {
        return;
      }
      Hit hit = findHit(overlayHits, e.getX(), e.getY());
      if (hit == null || activeTrackIndex < 0) {
        return;
      }
      e.consume();
      SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
      registry.handleTrackIconAction(
          hit.id(), activeTrackIndex, e.getScreenX(), e.getScreenY());
      refreshGlassOverlay();
    });

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.hoverSampleProperty().addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));
    registry.listPointerInsideProperty().addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));
    org.baseplayer.draw.GenomicCanvas.update.addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));

    host.widthProperty().addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));
    host.heightProperty().addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));
  }

  public static void refreshGlassOverlay() {
    if (!installed || overlayCanvas == null) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    double sampleH = registry.getSampleHeight();
    int hover = registry.getHoverSample();

    boolean sidebarWantsOverlay = registry.isListPointerInside()
        && !fitsInSidebar(sampleH)
        && hover >= 0
        && hover < registry.getSampleTracks().size();

    if (sidebarWantsOverlay) {
      cancelHideDelay();
      activeTrackIndex = hover;
      showGlassOverlayForTrack(activeTrackIndex);
      return;
    }

    if (pointerOverOverlay && activeTrackIndex >= 0
        && activeTrackIndex < registry.getSampleTracks().size()
        && !fitsInSidebar(sampleH)) {
      cancelHideDelay();
      showGlassOverlayForTrack(activeTrackIndex);
      return;
    }

    if (overlayCanvas.isVisible()) {
      scheduleHideIfNeeded();
    }
  }

  private static void scheduleHideIfNeeded() {
    if (pointerOverOverlay) {
      return;
    }
    if (hideDelay != null) {
      hideDelay.playFromStart();
    } else {
      hideGlassOverlay();
    }
  }

  private static void cancelHideDelay() {
    if (hideDelay != null) {
      hideDelay.stop();
    }
  }

  private static void hideGlassOverlay() {
    activeTrackIndex = -1;
    hoveredOverlayId = null;
    overlayHits.clear();
    pointerOverOverlay = false;
    if (overlayCanvas != null) {
      overlayCanvas.setVisible(false);
      overlayCanvas.setWidth(0);
      overlayCanvas.setHeight(0);
      GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
      gc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
    }
  }

  private static void showGlassOverlayForTrack(int trackIndex) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (trackIndex < 0 || trackIndex >= registry.getSampleTracks().size()) {
      hideGlassOverlay();
      return;
    }
    TrackBodyCanvas body = resolveSampleBodyCanvas();
    StackPane host = ZoomController.getGlassHost();
    if (body == null || host == null || body.getScene() == null || host.getScene() == null) {
      hideGlassOverlay();
      return;
    }

    int slot = registry.getDisplayedSlotForTrackIndex(trackIndex);
    if (slot < 0) {
      hideGlassOverlay();
      return;
    }

    double sampleH = registry.getSampleHeight();
    double sampleY = slot * sampleH - registry.getScrollBarPosition();
    if (sampleY + sampleH < 0 || sampleY > body.getHeight()) {
      hideGlassOverlay();
      return;
    }

    SampleTrack track = registry.getSampleTracks().get(trackIndex);
    boolean showReload = track.getSamples().stream().anyMatch(Sample::isSuspended);
    double buttonSize = OVERLAY_BUTTON_SIZE;
    double stripH = stripHeight(buttonSize);
    double totalW = width(showReload, true, buttonSize);
    double canvasW = totalW + 2 * OVERLAY_PAD;
    double canvasH = stripH + 2 * OVERLAY_PAD;

    double topY = sampleY + Math.max(4, (sampleH - stripH) * 0.5);
    if (topY + stripH > sampleY + sampleH - 2) {
      topY = sampleY + sampleH - stripH - 2;
    }
    if (topY < sampleY + 2) {
      topY = sampleY + 2;
    }
    double leftX = OVERLAY_LEFT_MARGIN;

    Point2D sceneOrigin = body.localToScene(leftX - OVERLAY_PAD, topY - OVERLAY_PAD);
    Point2D hostOrigin = host.sceneToLocal(sceneOrigin);

    overlayCanvas.setLayoutX(hostOrigin.getX());
    overlayCanvas.setLayoutY(hostOrigin.getY());
    overlayCanvas.setWidth(canvasW);
    overlayCanvas.setHeight(canvasH);
    overlayCanvas.setVisible(true);
    paintOverlayCanvas();
  }

  private static void paintOverlayCanvas() {
    if (overlayCanvas == null || !overlayCanvas.isVisible() || activeTrackIndex < 0) {
      return;
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (activeTrackIndex >= registry.getSampleTracks().size()) {
      return;
    }
    SampleTrack track = registry.getSampleTracks().get(activeTrackIndex);
    boolean showReload = track.getSamples().stream().anyMatch(Sample::isSuspended);
    GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
    gc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
    overlayHits.clear();
    overlayHits.addAll(drawOverlay(
        gc, OVERLAY_PAD, OVERLAY_PAD, track.isVisible(), showReload, hoveredOverlayId));
  }

  private static TrackBodyCanvas resolveSampleBodyCanvas() {
    DrawStackManager manager = ServiceRegistry.getInstance().getDrawStackManager();
    List<DrawStack> stacks = manager.getStacks();
    if (stacks == null || stacks.isEmpty()) {
      return null;
    }
    return stacks.get(0).sampleTrackCanvas;
  }

  /** Sidebar: under the name, right-aligned in the track row. */
  public static List<Hit> drawSidebar(
      GraphicsContext gc,
      double contentRight,
      double topY,
      boolean trackVisible,
      boolean showReload,
      String hoveredId) {
    double buttonSize = SIDEBAR_BUTTON_SIZE;
    double leftX = contentRight - SIDEBAR_RIGHT_MARGIN - width(showReload, buttonSize);
    return draw(gc, leftX, topY, buttonSize, trackVisible, showReload, true, hoveredId, false);
  }

  /**
   * Sample / feature master header: right-aligned strip (add, settings, optional reload).
   * No close button — column-level chrome only.
   */
  public static List<Hit> drawMaster(
      GraphicsContext gc,
      double contentRight,
      double headerHeight,
      boolean showReload,
      String hoveredId) {
    double buttonSize = MASTER_BUTTON_SIZE;
    double stripH = stripHeight(buttonSize);
    double topY = (headerHeight - stripH) / 2.0;
    double leftX = contentRight - MASTER_RIGHT_MARGIN - width(showReload, false, buttonSize);
    return draw(gc, leftX, topY, buttonSize, true, showReload, false, hoveredId, false);
  }

  /** Glass / overlay strip painting. */
  public static List<Hit> drawOverlay(
      GraphicsContext gc,
      double leftX,
      double topY,
      boolean trackVisible,
      boolean showReload,
      String hoveredId) {
    return draw(
        gc, leftX, topY, OVERLAY_BUTTON_SIZE, trackVisible, showReload, true, hoveredId, true);
  }

  public static List<Hit> draw(
      GraphicsContext gc,
      double leftX,
      double topY,
      double buttonSize,
      boolean trackVisible,
      boolean showReload,
      boolean showClose,
      String hoveredId,
      boolean withPlate) {
    List<Hit> hits = new ArrayList<>(4);
    double stripH = stripHeight(buttonSize);
    double y = topY + VERTICAL_PADDING;
    double totalW = width(showReload, showClose, buttonSize);

    if (withPlate) {
      gc.setFill(Color.rgb(18, 20, 24, 0.72));
      gc.fillRoundRect(leftX - 6, topY - 3, totalW + 12, stripH + 6, 8, 8);
      gc.setStroke(Color.rgb(120, 170, 220, 0.45));
      gc.setLineWidth(1);
      gc.strokeRoundRect(leftX - 6, topY - 3, totalW + 12, stripH + 6, 8, 8);
    }

    double x = leftX;
    Font iconFont = Font.font("Segoe UI Symbol", FontWeight.BOLD,
        buttonSize >= 20 ? 14 : 12);

    x = paintButton(gc, hits, x, y, buttonSize, iconFont, "+",
        "#2d4a2d", "#9dcc9d", "add", hoveredId);
    x = paintButton(gc, hits, x, y, buttonSize, iconFont, "⚙",
        "#2f343a", trackVisible ? "#dde3ea" : "#777777", "settings", hoveredId);
    if (showClose) {
      x = paintButton(gc, hits, x, y, buttonSize, iconFont, "✕",
          "#4a2a2a", "#e08888", "close", hoveredId);
    }
    if (showReload) {
      paintButton(gc, hits, x, y, buttonSize, iconFont, "\u21ba",
          "#4a3520", "#ff9944", "reload", hoveredId);
    }
    return hits;
  }

  /** Backward-compatible overload (always includes close). */
  public static List<Hit> draw(
      GraphicsContext gc,
      double leftX,
      double topY,
      double buttonSize,
      boolean trackVisible,
      boolean showReload,
      String hoveredId,
      boolean withPlate) {
    return draw(gc, leftX, topY, buttonSize, trackVisible, showReload, true, hoveredId, withPlate);
  }

  private static double paintButton(
      GraphicsContext gc,
      List<Hit> hits,
      double x,
      double y,
      double buttonSize,
      Font iconFont,
      String glyph,
      String bgHex,
      String fgHex,
      String id,
      String hoveredId) {
    boolean hovered = id.equals(hoveredId);
    gc.setFill(Color.web(bgHex));
    gc.fillRoundRect(x, y, buttonSize, buttonSize, 4, 4);
    if (hovered) {
      gc.setStroke(Color.rgb(180, 220, 255, 0.9));
      gc.setLineWidth(1.4);
      gc.strokeRoundRect(x - 0.5, y - 0.5, buttonSize + 1, buttonSize + 1, 4, 4);
    } else {
      gc.setStroke(Color.rgb(255, 255, 255, 0.16));
      gc.setLineWidth(1);
      gc.strokeRoundRect(x + 0.5, y + 0.5, buttonSize - 1, buttonSize - 1, 4, 4);
    }
    gc.setFill(Color.web(fgHex));
    gc.setFont(iconFont);
    double textX = x + buttonSize * 0.22;
    double textY = y + buttonSize - buttonSize * 0.28;
    if ("+".equals(glyph)) {
      textX = x + buttonSize * 0.28;
      textY = y + buttonSize - buttonSize * 0.22;
    } else if ("⚙".equals(glyph)) {
      textX = x + buttonSize * 0.16;
    } else if ("✕".equals(glyph)) {
      textX = x + buttonSize * 0.2;
    }
    gc.fillText(glyph, textX, textY);
    hits.add(new Hit(id, x, y, buttonSize, buttonSize));
    return x + buttonSize + BUTTON_GAP;
  }
}
