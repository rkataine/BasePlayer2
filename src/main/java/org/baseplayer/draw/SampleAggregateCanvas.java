package org.baseplayer.draw;

import org.baseplayer.components.MasterTrackPainter;
import org.baseplayer.samples.alignment.draw.CoverageDrawer;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantList;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

/**
 * Sample-column aggregate band for cohort-level summaries (variant density,
 * comparative methylation, and future non-alignment aggregates).
 * Stacked above {@link org.baseplayer.samples.alignment.draw.TrackBodyCanvas}; shares genomic X with other canvases.
 */
public class SampleAggregateCanvas extends AggregateBandCanvas {

  private static final double RESIZE_EDGE_PX = 6;

  private final MasterTrackPainter painter;
  private final CoverageDrawer coverageDrawer;
  private final SampleRegistry sampleRegistry =
      ServiceRegistry.getInstance().getSampleRegistry();

  private boolean resizingBand;
  private double resizeStartScreenY;
  private double resizeStartMasterHeight;

  public SampleAggregateCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack,
                               CoverageDrawer coverageDrawer) {
    super(reactiveCanvas, parent, drawStack,
        ServiceRegistry.getInstance().getSampleRegistry().aggregateBandHeightProperty());
    this.coverageDrawer = coverageDrawer;
    // Density completion only repaints this band — not the full canvas stack.
    this.painter = new MasterTrackPainter(coverageDrawer, this::draw);

    Canvas reactive = getReactiveCanvas();
    // Filters run before GenomicCanvas pan/zoom handlers.
    reactive.addEventFilter(MouseEvent.MOUSE_MOVED, this::onBandMouseMoved);
    reactive.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onBandMousePressed);
    reactive.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onBandMouseDragged);
    reactive.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onBandMouseReleased);
    reactive.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onLegendClick);
  }

  private boolean inResizeEdge(double y) {
    return y >= getHeight() - RESIZE_EDGE_PX;
  }

  private void onBandMouseMoved(MouseEvent event) {
    if (resizingBand || inResizeEdge(event.getY())) {
      applyCursor(Cursor.V_RESIZE);
      event.consume();
      return;
    }
    Cursor cursor = painter.isOverLegend(event.getX(), event.getY())
        ? Cursor.HAND
        : Cursor.DEFAULT;
    applyCursor(cursor);
  }

  private void onBandMousePressed(MouseEvent event) {
    if (event.getButton() != MouseButton.PRIMARY || !inResizeEdge(event.getY())) {
      return;
    }
    resizingBand = true;
    resizeStartScreenY = event.getScreenY();
    resizeStartMasterHeight = sampleRegistry.getMasterTrackHeight();
    applyCursor(Cursor.V_RESIZE);
    event.consume();
  }

  private void onBandMouseDragged(MouseEvent event) {
    if (!resizingBand) {
      return;
    }
    double delta = event.getScreenY() - resizeStartScreenY;
    sampleRegistry.setMasterTrackHeight(
        Math.max(20, Math.min(200, resizeStartMasterHeight + delta)));
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
    event.consume();
  }

  private void onBandMouseReleased(MouseEvent event) {
    if (!resizingBand) {
      return;
    }
    resizingBand = false;
    applyCursor(Cursor.DEFAULT);
    event.consume();
  }

  private void applyCursor(Cursor cursor) {
    getReactiveCanvas().setCursor(cursor);
    setCursor(cursor);
  }

  private void onLegendClick(MouseEvent event) {
    if (mouseDragged || resizingBand) {
      return;
    }
    if (painter.handleLegendClick(event.getX(), event.getY())) {
      event.consume();
    }
  }

  public CoverageDrawer getCoverageDrawer() {
    return coverageDrawer;
  }

  public void setVariantList(VariantList variantList) {
    painter.setVariantList(variantList);
  }

  public void clearVariantList() {
    painter.clearVariantList();
  }

  public VariantList getVariantList() {
    return painter.getVariantList();
  }

  /** Refresh legend types after shared VariantList contents change (e.g. sample removed). */
  public void refreshPresentTypesFromList() {
    painter.refreshPresentTypesFromList();
  }

  public void forceCalculateDensity() {
    painter.forceCalculateDensity(drawStack);
  }

  @Override
  protected void drawBand() {
    double h = getHeight();
    if (h <= 0 || getWidth() <= 0) {
      return;
    }
    painter.drawMasterAggregates(getGraphicsContext2D(), drawStack, getWidth(), h);
  }
}
