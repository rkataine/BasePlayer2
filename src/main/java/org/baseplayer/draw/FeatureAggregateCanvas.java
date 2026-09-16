package org.baseplayer.draw;

import org.baseplayer.services.ServiceRegistry;

import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;

public class FeatureAggregateCanvas extends AggregateBandCanvas {

  public FeatureAggregateCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(
        reactiveCanvas,
        parent,
        drawStack,
        ServiceRegistry.getInstance()
            .getFeatureTrackViewportRegistry()
            .masterBandHeightProperty());
  }

  @Override
  protected void drawBand() {
    // Stub: no aggregate content yet. Do not draw a bottom separator —
    // {@link GenomicCanvas#drawTrackRowTopDivider} skips the body/aggregate join.
  }
}
