package org.baseplayer.components;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.ZoomController;
import org.baseplayer.features.Track;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.draw.TrackBodyCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
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
 *   <li>Short sample/feature rows — glass-pane overlay on the track canvas</li>
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

  private enum OverlayTarget {
    SAMPLE,
    FEATURE
  }

  private static boolean installed;
  private static Canvas overlayCanvas;
  private static final List<Hit> overlayHits = new ArrayList<>();
  private static int activeTrackIndex = -1;
  private static OverlayTarget activeTarget = OverlayTarget.SAMPLE;
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
    return width(showReload, true, false, buttonSize);
  }

  public static double width(boolean showReload, boolean showClose, double buttonSize) {
    return width(showReload, showClose, false, buttonSize);
  }

  public static double width(
      boolean showReload, boolean showClose, boolean showPlayStop, double buttonSize) {
    int count = 2; // add + settings
    if (showClose) {
      count++;
    }
    if (showPlayStop) {
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
      if (activeTarget == OverlayTarget.FEATURE) {
        FeatureTrackViewportRegistry features =
            ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
        features.handleTrackIconAction(
            hit.id(), activeTrackIndex, e.getScreenX(), e.getScreenY());
      } else {
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        registry.handleTrackIconAction(
            hit.id(), activeTrackIndex, e.getScreenX(), e.getScreenY());
      }
      refreshGlassOverlay();
    });

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    registry.hoverSampleProperty().addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));
    registry.listPointerInsideProperty().addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    features.hoveredTrackIndexProperty().addListener((obs, o, n) -> Platform.runLater(
        SampleTrackControls::refreshGlassOverlay));
    features.listPointerInsideProperty().addListener((obs, o, n) -> Platform.runLater(
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
    SampleRegistry samples = ServiceRegistry.getInstance().getSampleRegistry();
    FeatureTrackViewportRegistry features =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();

    double sampleH = samples.getSampleHeight();
    int sampleHover = samples.getHoverSample();
    boolean sampleWantsOverlay = samples.isListPointerInside()
        && !fitsInSidebar(sampleH)
        && sampleHover >= 0
        && sampleHover < samples.getSampleTracks().size();

    if (sampleWantsOverlay) {
      cancelHideDelay();
      activeTarget = OverlayTarget.SAMPLE;
      activeTrackIndex = sampleHover;
      showGlassOverlayForSample(activeTrackIndex);
      return;
    }

    double featureH = features.getTrackRowHeightPixels();
    int featureHover = features.getHoveredTrackIndex();
    boolean featureWantsOverlay = features.isListPointerInside()
        && !fitsInSidebar(featureH)
        && featureHover >= 0
        && featureHover < features.getFeatureTracks().size();

    if (featureWantsOverlay) {
      cancelHideDelay();
      activeTarget = OverlayTarget.FEATURE;
      activeTrackIndex = featureHover;
      showGlassOverlayForFeature(activeTrackIndex);
      return;
    }

    if (pointerOverOverlay && activeTrackIndex >= 0) {
      if (activeTarget == OverlayTarget.FEATURE
          && activeTrackIndex < features.getFeatureTracks().size()
          && !fitsInSidebar(features.getTrackRowHeightPixels())) {
        cancelHideDelay();
        showGlassOverlayForFeature(activeTrackIndex);
        return;
      }
      if (activeTarget == OverlayTarget.SAMPLE
          && activeTrackIndex < samples.getSampleTracks().size()
          && !fitsInSidebar(sampleH)) {
        cancelHideDelay();
        showGlassOverlayForSample(activeTrackIndex);
        return;
      }
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
    activeTarget = OverlayTarget.SAMPLE;
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

  private static void showGlassOverlayForSample(int trackIndex) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (trackIndex < 0 || trackIndex >= registry.getSampleTracks().size()) {
      hideGlassOverlay();
      return;
    }
    TrackBodyCanvas body = resolveSampleBodyCanvas();
    int slot = registry.getDisplayedSlotForTrackIndex(trackIndex);
    if (slot < 0) {
      hideGlassOverlay();
      return;
    }
    SampleTrack track = registry.getSampleTracks().get(trackIndex);
    boolean showReload = track.getSamples().stream().anyMatch(Sample::isSuspended);
    placeOverlayOnBody(
        body,
        slot,
        registry.getSampleHeight(),
        registry.getScrollBarPosition(),
        track.isVisible(),
        showReload,
        false);
  }

  private static void showGlassOverlayForFeature(int trackIndex) {
    FeatureTrackViewportRegistry registry =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    if (trackIndex < 0 || trackIndex >= registry.getFeatureTracks().size()) {
      hideGlassOverlay();
      return;
    }
    TrackBodyCanvas body = resolveFeatureBodyCanvas();
    int slot = registry.getDisplayedSlotForTrackIndex(trackIndex);
    if (slot < 0) {
      hideGlassOverlay();
      return;
    }
    Track track = registry.getFeatureTrackAtBackingIndex(trackIndex);
    boolean visible = track != null && track.isVisible() && !track.isAggregateDisabled();
    boolean showPlayStop = track instanceof org.baseplayer.features.BedTrack bed
        && bed.getVariantAnnotationMode().filtersVisibility();
    placeOverlayOnBody(
        body,
        slot,
        registry.getTrackRowHeightPixels(),
        registry.getVerticalScrollOffsetPixels(),
        visible,
        false,
        showPlayStop);
  }

  private static void placeOverlayOnBody(
      TrackBodyCanvas body,
      int slot,
      double rowHeight,
      double scrollOffset,
      boolean trackVisible,
      boolean showReload,
      boolean showPlayStop) {
    StackPane host = ZoomController.getGlassHost();
    if (body == null || host == null || body.getScene() == null || host.getScene() == null) {
      hideGlassOverlay();
      return;
    }

    double rowY = slot * rowHeight - scrollOffset;
    if (rowY + rowHeight < 0 || rowY > body.getHeight()) {
      hideGlassOverlay();
      return;
    }

    double buttonSize = OVERLAY_BUTTON_SIZE;
    double stripH = stripHeight(buttonSize);
    double totalW = width(showReload, true, showPlayStop, buttonSize);
    double canvasW = totalW + 2 * OVERLAY_PAD;
    double canvasH = stripH + 2 * OVERLAY_PAD;

    double topY = rowY + Math.max(4, (rowHeight - stripH) * 0.5);
    if (topY + stripH > rowY + rowHeight - 2) {
      topY = rowY + rowHeight - stripH - 2;
    }
    if (topY < rowY + 2) {
      topY = rowY + 2;
    }
    double leftX = OVERLAY_LEFT_MARGIN;

    Point2D sceneOrigin = body.localToScene(leftX - OVERLAY_PAD, topY - OVERLAY_PAD);
    Point2D hostOrigin = host.sceneToLocal(sceneOrigin);

    overlayCanvas.setLayoutX(hostOrigin.getX());
    overlayCanvas.setLayoutY(hostOrigin.getY());
    overlayCanvas.setWidth(canvasW);
    overlayCanvas.setHeight(canvasH);
    overlayCanvas.setUserData(new boolean[] { showReload, showPlayStop });
    overlayCanvas.setVisible(true);
    paintOverlayCanvas(trackVisible, showReload, showPlayStop);
  }

  private static void paintOverlayCanvas() {
    if (overlayCanvas == null || !overlayCanvas.isVisible() || activeTrackIndex < 0) {
      return;
    }
    boolean showReload = false;
    boolean showPlayStop = false;
    if (overlayCanvas.getUserData() instanceof boolean[] flags && flags.length >= 2) {
      showReload = flags[0];
      showPlayStop = flags[1];
    }
    boolean visible = true;
    if (activeTarget == OverlayTarget.FEATURE) {
      FeatureTrackViewportRegistry features =
          ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
      Track track = features.getFeatureTrackAtBackingIndex(activeTrackIndex);
      visible = track != null && track.isVisible() && !track.isAggregateDisabled();
      showPlayStop = track instanceof org.baseplayer.features.BedTrack bed
          && bed.getVariantAnnotationMode().filtersVisibility();
    } else {
      SampleRegistry samples = ServiceRegistry.getInstance().getSampleRegistry();
      if (activeTrackIndex < samples.getSampleTracks().size()) {
        SampleTrack track = samples.getSampleTracks().get(activeTrackIndex);
        visible = track.isVisible();
        showReload = track.getSamples().stream().anyMatch(Sample::isSuspended);
      }
      showPlayStop = false;
    }
    paintOverlayCanvas(visible, showReload, showPlayStop);
  }

  private static void paintOverlayCanvas(
      boolean trackVisible, boolean showReload, boolean showPlayStop) {
    if (overlayCanvas == null || !overlayCanvas.isVisible()) {
      return;
    }
    GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
    gc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
    overlayHits.clear();
    overlayHits.addAll(drawOverlay(
        gc,
        OVERLAY_PAD,
        OVERLAY_PAD,
        trackVisible,
        showReload,
        showPlayStop,
        org.baseplayer.features.BedVariantAnnotation.isFilterRunning(),
        org.baseplayer.features.BedVariantAnnotation.isFilterBusy(),
        org.baseplayer.features.BedVariantAnnotation.busyAngleDeg(),
        hoveredOverlayId));
  }

  private static TrackBodyCanvas resolveSampleBodyCanvas() {
    DrawStackManager manager = ServiceRegistry.getInstance().getDrawStackManager();
    List<DrawStack> stacks = manager.getStacks();
    if (stacks == null || stacks.isEmpty()) {
      return null;
    }
    return stacks.get(0).sampleTrackCanvas;
  }

  private static TrackBodyCanvas resolveFeatureBodyCanvas() {
    DrawStackManager manager = ServiceRegistry.getInstance().getDrawStackManager();
    List<DrawStack> stacks = manager.getStacks();
    if (stacks == null || stacks.isEmpty()) {
      return null;
    }
    return stacks.get(0).featureTrackCanvas;
  }

  /** Sidebar: under the name, right-aligned in the track row. */
  public static List<Hit> drawSidebar(
      GraphicsContext gc,
      double contentRight,
      double topY,
      boolean trackVisible,
      boolean showReload,
      String hoveredId) {
    return drawSidebar(
        gc, contentRight, topY, trackVisible, showReload, false, false, false, 0, hoveredId);
  }

  /** Sidebar strip with optional play/stop after close (BED INTERSECT/SUBTRACT). */
  public static List<Hit> drawSidebar(
      GraphicsContext gc,
      double contentRight,
      double topY,
      boolean trackVisible,
      boolean showReload,
      boolean showPlayStop,
      boolean filterRunning,
      boolean filterBusy,
      double busyAngleDeg,
      String hoveredId) {
    double buttonSize = SIDEBAR_BUTTON_SIZE;
    double leftX = contentRight - SIDEBAR_RIGHT_MARGIN
        - width(showReload, true, showPlayStop, buttonSize);
    return draw(
        gc, leftX, topY, buttonSize, trackVisible, showReload, true,
        showPlayStop, filterRunning, filterBusy, busyAngleDeg, hoveredId, false);
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
    double leftX = contentRight - MASTER_RIGHT_MARGIN - width(showReload, false, false, buttonSize);
    return draw(
        gc, leftX, topY, buttonSize, true, showReload, false,
        false, false, false, 0, hoveredId, false);
  }

  /**
   * Geometric play / stop control for BED INTERSECT/SUBTRACT filters.
   * When {@code busy}, draws a spinner instead of the glyph.
   */
  public static Hit drawPlayStopButton(
      GraphicsContext gc,
      double x,
      double y,
      double buttonSize,
      boolean filterRunning,
      boolean busy,
      double busyAngleDeg,
      String hoveredId) {
    boolean dark = org.baseplayer.ui.theme.AppTheme.isDark();
    boolean hovered = "bed-filter-toggle".equals(hoveredId);
    Color bg = filterRunning
        ? (dark ? Color.web("#3a3030") : Color.web("#ffebee"))
        : (dark ? Color.web("#2a3a2a") : Color.web("#e8f5e9"));
    Color fg = filterRunning
        ? (dark ? Color.web("#ef9a9a") : Color.web("#c62828"))
        : (dark ? Color.web("#a5d6a7") : Color.web("#2e7d32"));

    gc.setFill(bg);
    gc.fillRoundRect(x, y, buttonSize, buttonSize, 4, 4);
    if (hovered && !busy) {
      gc.setStroke(dark ? Color.rgb(180, 220, 255, 0.9) : Color.rgb(0, 90, 158, 0.85));
      gc.setLineWidth(1.4);
      gc.strokeRoundRect(x - 0.5, y - 0.5, buttonSize + 1, buttonSize + 1, 4, 4);
    } else {
      gc.setStroke(dark ? Color.rgb(255, 255, 255, 0.16) : Color.rgb(0, 0, 0, 0.18));
      gc.setLineWidth(1);
      gc.strokeRoundRect(x + 0.5, y + 0.5, buttonSize - 1, buttonSize - 1, 4, 4);
    }

    if (busy) {
      drawSpinner(gc, x + buttonSize * 0.5, y + buttonSize * 0.5, buttonSize * 0.32, busyAngleDeg, fg);
    } else if (filterRunning) {
      // Stop: solid rounded square (avoids broken pause-glyph fonts).
      double s = buttonSize * 0.38;
      double sx = x + (buttonSize - s) * 0.5;
      double sy = y + (buttonSize - s) * 0.5;
      gc.setFill(fg);
      gc.fillRoundRect(sx, sy, s, s, 2.5, 2.5);
    } else {
      // Play: filled triangle.
      double cx = x + buttonSize * 0.42;
      double cy = y + buttonSize * 0.5;
      double h = buttonSize * 0.42;
      double w = buttonSize * 0.36;
      gc.setFill(fg);
      gc.fillPolygon(
          new double[] { cx - w * 0.15, cx - w * 0.15, cx + w * 0.7 },
          new double[] { cy - h * 0.5, cy + h * 0.5, cy },
          3);
    }
    return new Hit("bed-filter-toggle", x, y, buttonSize, buttonSize);
  }

  /** Arc spinner centered at ({@code cx}, {@code cy}). */
  public static void drawSpinner(
      GraphicsContext gc, double cx, double cy, double radius, double angleDeg, Color color) {
    gc.save();
    gc.setStroke(color);
    gc.setLineWidth(Math.max(1.5, radius * 0.35));
    gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
    double start = angleDeg;
    gc.strokeArc(cx - radius, cy - radius, radius * 2, radius * 2, start, 270, javafx.scene.shape.ArcType.OPEN);
    gc.restore();
  }

  /** Glass / overlay strip painting. */
  public static List<Hit> drawOverlay(
      GraphicsContext gc,
      double leftX,
      double topY,
      boolean trackVisible,
      boolean showReload,
      String hoveredId) {
    return drawOverlay(
        gc, leftX, topY, trackVisible, showReload, false, false, false, 0, hoveredId);
  }

  public static List<Hit> drawOverlay(
      GraphicsContext gc,
      double leftX,
      double topY,
      boolean trackVisible,
      boolean showReload,
      boolean showPlayStop,
      boolean filterRunning,
      boolean filterBusy,
      double busyAngleDeg,
      String hoveredId) {
    return draw(
        gc, leftX, topY, OVERLAY_BUTTON_SIZE, trackVisible, showReload, true,
        showPlayStop, filterRunning, filterBusy, busyAngleDeg, hoveredId, true);
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
    return draw(
        gc, leftX, topY, buttonSize, trackVisible, showReload, showClose,
        false, false, false, 0, hoveredId, withPlate);
  }

  public static List<Hit> draw(
      GraphicsContext gc,
      double leftX,
      double topY,
      double buttonSize,
      boolean trackVisible,
      boolean showReload,
      boolean showClose,
      boolean showPlayStop,
      boolean filterRunning,
      boolean filterBusy,
      double busyAngleDeg,
      String hoveredId,
      boolean withPlate) {
    List<Hit> hits = new ArrayList<>(5);
    double stripH = stripHeight(buttonSize);
    double y = topY + VERTICAL_PADDING;
    double totalW = width(showReload, showClose, showPlayStop, buttonSize);

    if (withPlate) {
      var chrome = org.baseplayer.ui.theme.AppTheme.chrome();
      boolean dark = org.baseplayer.ui.theme.AppTheme.isDark();
      gc.setFill(dark
          ? Color.rgb(18, 20, 24, 0.72)
          : Color.color(chrome.panel().getRed(), chrome.panel().getGreen(), chrome.panel().getBlue(), 0.92));
      gc.fillRoundRect(leftX - 6, topY - 3, totalW + 12, stripH + 6, 8, 8);
      gc.setStroke(dark
          ? Color.rgb(120, 170, 220, 0.45)
          : Color.color(chrome.accent().getRed(), chrome.accent().getGreen(), chrome.accent().getBlue(), 0.55));
      gc.setLineWidth(1);
      gc.strokeRoundRect(leftX - 6, topY - 3, totalW + 12, stripH + 6, 8, 8);
    }

    double x = leftX;
    Font iconFont = Font.font("Segoe UI Symbol", FontWeight.BOLD,
        buttonSize >= 20 ? 14 : 12);

    boolean dark = org.baseplayer.ui.theme.AppTheme.isDark();
    String addBg = dark ? "#2d4a2d" : "#c8e6c9";
    String addFg = dark ? "#9dcc9d" : "#1b5e20";
    String settingsBg = dark ? "#2f343a" : "#d5d5d5";
    String settingsFg = trackVisible
        ? (dark ? "#dde3ea" : "#0a0a0a")
        : (dark ? "#777777" : "#777777");
    String closeBg = dark ? "#4a2a2a" : "#ffcdd2";
    String closeFg = dark ? "#e08888" : "#a31515";
    String reloadBg = dark ? "#4a3520" : "#ffe0b2";
    String reloadFg = dark ? "#ff9944" : "#c43e00";

    x = paintButton(gc, hits, x, y, buttonSize, iconFont, "+",
        addBg, addFg, "add", hoveredId);
    x = paintButton(gc, hits, x, y, buttonSize, iconFont, "⚙",
        settingsBg, settingsFg, "settings", hoveredId);
    if (showClose) {
      x = paintButton(gc, hits, x, y, buttonSize, iconFont, "✕",
          closeBg, closeFg, "close", hoveredId);
    }
    if (showPlayStop) {
      Hit playStop = drawPlayStopButton(
          gc, x, y, buttonSize, filterRunning, filterBusy, busyAngleDeg, hoveredId);
      hits.add(playStop);
      x = playStop.x() + playStop.width() + BUTTON_GAP;
    }
    if (showReload) {
      paintButton(gc, hits, x, y, buttonSize, iconFont, "\u21ba",
          reloadBg, reloadFg, "reload", hoveredId);
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
    return draw(
        gc, leftX, topY, buttonSize, trackVisible, showReload, true, hoveredId, withPlate);
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
    boolean dark = org.baseplayer.ui.theme.AppTheme.isDark();
    if (hovered) {
      gc.setStroke(dark ? Color.rgb(180, 220, 255, 0.9) : Color.rgb(0, 90, 158, 0.85));
      gc.setLineWidth(1.4);
      gc.strokeRoundRect(x - 0.5, y - 0.5, buttonSize + 1, buttonSize + 1, 4, 4);
    } else {
      gc.setStroke(dark ? Color.rgb(255, 255, 255, 0.16) : Color.rgb(0, 0, 0, 0.18));
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
