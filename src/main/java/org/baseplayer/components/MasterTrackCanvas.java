package org.baseplayer.components;

import org.baseplayer.components.sidebars.SidebarBase;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Rendering-only canvas for the master-track header area.
 */
public class MasterTrackCanvas extends GenomicCanvas {

  public interface ControlRenderHandler {
    ExpandedControlsRenderResult renderExpandedControls(
        GraphicsContext gc,
        double w,
        double h,
        double headerBarH,
        int trackCount,
        int firstVisible,
        int lastVisible,
        String focusedGene,
        boolean hasActiveSampleFilterQuery);

    void drawHeaderHover(
        GraphicsContext reactiveGc,
        double w,
        double h,
        double headerBarH,
        double headerBtnSize,
        double headerBtnLeftX,
        boolean settingsHovered,
        boolean reloadHovered,
        boolean addHovered,
        boolean canReload,
        boolean highlightRangeLabel,
        HitBox rangeLabelHit);

    void onExpandedControlsRendered(ExpandedControlsRenderResult result);
  }

  public static record HitBox(double x, double y, double w, double h) {
    public boolean contains(double px, double py) {
      return px >= x && px <= x + w && py >= y && py <= y + h;
    }
  }

  public record ExpandedControlsRenderResult(HitBox rangeStartHandleHit, HitBox rangeEndHandleHit, HitBox rangeLabelHit) {
  }

  public record RenderState(
      int trackCount,
      boolean controlsExpanded,
      boolean canReload,
      int firstVisible,
      int lastVisible,
      String focusedGene,
      boolean hasActiveSampleFilterQuery,
      boolean settingsHovered,
      boolean reloadHovered,
      boolean addHovered,
      boolean highlightRangeLabel) {
    public static RenderState empty() {
      return new RenderState(0, false, false, 0, 0, null, false, false, false, false, false);
    }
  }

  private static final double HEADER_BTN_SIZE = 18;
  private static final double HEADER_BTN_LEFT_X = 4;

  private ControlRenderHandler controlRenderHandler;
  private RenderState renderState = RenderState.empty();

  public final LoadRegionButton loadRegionButton;

  public MasterTrackCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack initialDrawStack) {
    super(reactiveCanvas, parent, initialDrawStack);

    loadRegionButton = new LoadRegionButton();

    // Keep visuals anchored correctly on resize.
    widthProperty().addListener((obs, oldVal, newVal) -> draw());
    heightProperty().addListener((obs, oldVal, newVal) -> draw());
    reactiveCanvas.widthProperty().addListener((obs, oldVal, newVal) -> draw());
    reactiveCanvas.heightProperty().addListener((obs, oldVal, newVal) -> draw());
  }

  public void initializeLoadRegionButton() {
    Platform.runLater(loadRegionButton::attachRegionListener);
  }

  public DrawStack getStack() {
    return drawStack;
  }

  public void setControlRenderHandler(ControlRenderHandler controlRenderHandler) {
    this.controlRenderHandler = controlRenderHandler;
  }

  public void setRenderState(RenderState renderState) {
    this.renderState = renderState != null ? renderState : RenderState.empty();
  }

  @Override
  public void draw() {
    double w = getWidth();
    double h = getHeight();
    if (w <= 0 || h <= 0) return;

    int trackCount = Math.max(0, renderState.trackCount());

    GraphicsContext gc = getGraphicsContext2D();
    double headerBarH = Math.min(org.baseplayer.services.SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT, h);
    SidebarBase.drawStandardHeader(gc, w, headerBarH, "Tracks", trackCount);
    double sy = (headerBarH - HEADER_BTN_SIZE) / 2;

    if (renderState.canReload()) {
      double reloadX = reloadBtnX(w);
      gc.setFont(Font.font("Segoe UI Symbol", 14));
      gc.setFill(Color.web("#ff9944"));
      gc.fillText("\u21ba", reloadX + 1, sy + HEADER_BTN_SIZE - 3);
    }

    ExpandedControlsRenderResult expandedResult = new ExpandedControlsRenderResult(null, null, null);
    if (renderState.controlsExpanded() && controlRenderHandler != null) {
      expandedResult = controlRenderHandler.renderExpandedControls(
          gc,
          w,
          h,
          headerBarH,
          trackCount,
          renderState.firstVisible(),
          renderState.lastVisible(),
          renderState.focusedGene(),
          renderState.hasActiveSampleFilterQuery());
    }

    if (controlRenderHandler != null) {
      controlRenderHandler.onExpandedControlsRendered(expandedResult);
    }

    drawHeaderHover(expandedResult.rangeLabelHit());
  }

  private void drawHeaderHover(HitBox rangeLabelHit) {
    if (reactiveGc == null) return;
    double w = getReactiveCanvas().getWidth();
    double h = getReactiveCanvas().getHeight();
    reactiveGc.clearRect(0, 0, w, h);

    if (controlRenderHandler != null) {
      double headerBarH = Math.min(org.baseplayer.services.SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT, getHeight());
      controlRenderHandler.drawHeaderHover(
          reactiveGc,
          w,
          h,
          headerBarH,
          HEADER_BTN_SIZE,
          HEADER_BTN_LEFT_X,
          renderState.settingsHovered(),
          renderState.reloadHovered(),
          renderState.addHovered(),
          renderState.canReload(),
          renderState.highlightRangeLabel(),
          rangeLabelHit);
    }
  }

  public static double headerBtnSize() {
    return HEADER_BTN_SIZE;
  }

  public static double headerBtnLeftX() {
    return HEADER_BTN_LEFT_X;
  }

  public static double reloadBtnX(double canvasWidth) {
    return canvasWidth - 2 * (HEADER_BTN_SIZE + 4);
  }

  public void zoomAt(double zoomDirection, double targetX) {
    zoom(zoomDirection, targetX);
  }

  public void setNavigating(boolean navigating) {
    drawStack.nav.navigating = navigating;
  }

  public void panByGenomeDelta(double genomeDelta) {
    setStart(drawStack.getViewStart() - genomeDelta);
  }

  public static boolean inHeaderBtn(double mx, double my, double bx, double by) {
    return mx >= bx && mx <= bx + HEADER_BTN_SIZE && my >= by && my <= by + HEADER_BTN_SIZE;
  }
}
