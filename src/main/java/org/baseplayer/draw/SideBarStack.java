package org.baseplayer.draw;

import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;

public class SideBarStack {
  public Canvas sideCanvas;
  public Canvas reactiveCanvas;
  public TrackInfo trackInfo;

  public SideBarStack(StackPane drawSideBarStackPane) {
    sideCanvas = new Canvas();
    reactiveCanvas = new Canvas();
    sideCanvas.heightProperty().bind(drawSideBarStackPane.heightProperty());
    sideCanvas.widthProperty().bind(drawSideBarStackPane.widthProperty());
    reactiveCanvas.setMouseTransparent(true); // Allow clicks to pass through
    drawSideBarStackPane.getChildren().addAll(sideCanvas, reactiveCanvas);
    trackInfo = new TrackInfo(this);
  }
}
