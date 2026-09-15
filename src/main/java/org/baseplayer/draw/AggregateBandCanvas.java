package org.baseplayer.draw;

import javafx.beans.property.DoubleProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;

import org.baseplayer.utils.DrawColors;

/**
 * Genomic aggregate band: shares pan/zoom with other {@link GenomicCanvas}
 * views but does not drive per-sample vertical read scrolling.
 *
 * <p>Used for the sample master band today. Feature tracks can later add a
 * similar band (intersect / subtract / annotate) by subclassing this class
 * and stacking it above {@link org.baseplayer.features.FeatureTracksCanvas}.
 */
public abstract class AggregateBandCanvas extends GenomicCanvas {

  private final DoubleProperty bandHeight;

  protected AggregateBandCanvas(Canvas reactiveCanvas, StackPane parent,
                                DrawStack drawStack, DoubleProperty bandHeight) {
    super(reactiveCanvas, parent, drawStack);
    this.bandHeight = bandHeight;
    // Override default parent-height binding: band height is owned by bandHeight.
    heightProperty().unbind();
    reactiveCanvas.heightProperty().unbind();
    heightProperty().bind(bandHeight);
    reactiveCanvas.heightProperty().bind(bandHeight);
  }

  public DoubleProperty bandHeightProperty() {
    return bandHeight;
  }

  /** Aggregate bands never scroll BAM reads under the cursor. */
  @Override
  protected boolean handlesSampleVerticalScroll() {
    return false;
  }

  @Override
  protected void handleScroll(ScrollEvent event) {
    if (event.isControlDown()) {
      super.handleScroll(event);
      return;
    }
    // Horizontal pan only (no sample-read vertical scroll).
    event.consume();
    double scrollDelta = event.getDeltaX();
    if (scrollDelta == 0) {
      return;
    }
    double genomeDelta = scrollDelta * 0.3 * drawStack.scale;
    setStart(drawStack.start - genomeDelta);
  }

  @Override
  public void draw() {
    getGraphicsContext2D().setFill(DrawColors.BACKGROUND);
    getGraphicsContext2D().fillRect(0, 0, getWidth() + 1, getHeight() + 1);
    drawBand();
    super.draw();
  }

  /** Paint band-specific content into this canvas (full height = band height). */
  protected abstract void drawBand();
}
