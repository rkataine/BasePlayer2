package org.baseplayer.draw;

import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;

public class SidebarPanel {
  public Canvas sideCanvas;
  public Canvas reactiveCanvas;
  public TrackLabelPanel trackInfo;

  public SidebarPanel(StackPane drawSideBarStackPane) {
    sideCanvas = new Canvas();
    reactiveCanvas = new Canvas();
    sideCanvas.heightProperty().bind(drawSideBarStackPane.heightProperty());
    sideCanvas.widthProperty().bind(drawSideBarStackPane.widthProperty());
    reactiveCanvas.setMouseTransparent(true); // Allow clicks to pass through
    drawSideBarStackPane.getChildren().addAll(sideCanvas, reactiveCanvas);
    trackInfo = new TrackLabelPanel(this);
  }
}
