package org.baseplayer.draw;

import org.baseplayer.components.MasterTrackPainter;
import org.baseplayer.samples.alignment.draw.CoverageDrawer;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantList;

import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;

/**
 * Sample-column aggregate band for cohort-level summaries (variant density,
 * comparative methylation, and future non-alignment aggregates).
 * Stacked above {@link org.baseplayer.samples.alignment.draw.TrackBodyCanvas}; shares genomic X with other canvases.
 */
public class SampleAggregateCanvas extends AggregateBandCanvas {

  private final MasterTrackPainter painter;
  private final CoverageDrawer coverageDrawer;

  public SampleAggregateCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack,
                               CoverageDrawer coverageDrawer) {
    super(reactiveCanvas, parent, drawStack,
        ServiceRegistry.getInstance().getSampleRegistry().masterTrackHeightProperty());
    this.coverageDrawer = coverageDrawer;
    this.painter = new MasterTrackPainter(coverageDrawer, () ->
        GenomicCanvas.update.set(!GenomicCanvas.update.get()));
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
