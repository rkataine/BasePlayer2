package org.baseplayer.draw;

import java.util.ArrayList;

import org.baseplayer.SharedModel;
import org.baseplayer.controllers.MainController;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public class TrackInfo {
  SideBarStack sidebar;
  GraphicsContext gc;
  GraphicsContext reactivegc;
  ArrayList<String> tracks;

  public TrackInfo(SideBarStack sidebar) {
    this.sidebar = sidebar;
    this.gc = sidebar.sideCanvas.getGraphicsContext2D();
    this.reactivegc = sidebar.reactiveCanvas.getGraphicsContext2D();
    this.tracks = SharedModel.sampleList;
    sidebar.sideCanvas.setOnMouseMoved((event) -> {
      SharedModel.hoverSample.set((int)(event.getY() / (sidebar.sideCanvas.getHeight() / tracks.size())));
    });
    sidebar.sideCanvas.setOnScroll((event) -> { 
      SharedModel.scrollBarPosition -= event.getDeltaY();
      
      if (SharedModel.scrollBarPosition < 0) SharedModel.scrollBarPosition = 0;
      if (SharedModel.scrollBarPosition > (SharedModel.sampleList.size() - 1) * SharedModel.sampleHeight) SharedModel.scrollBarPosition = (SharedModel.sampleList.size() - 1) * SharedModel.sampleHeight;
      SharedModel.firstVisibleSample = Math.max(0, (int)(SharedModel.scrollBarPosition / SharedModel.sampleHeight));
      SharedModel.lastVisibleSample = Math.min(tracks.size() -1,(int)((SharedModel.scrollBarPosition + sidebar.sideCanvas.getHeight()) / SharedModel.sampleHeight));
      DrawFunctions.update.set(!DrawFunctions.update.get());
    });
    sidebar.sideCanvas.setOnMouseClicked(event -> {
      if (event.getClickCount() == 2) {
        if (SharedModel.firstVisibleSample == SharedModel.lastVisibleSample) {
          SharedModel.firstVisibleSample = 0;
          SharedModel.lastVisibleSample = tracks.size() - 1;
        } else {
          SharedModel.firstVisibleSample = SharedModel.hoverSample.get();
          SharedModel.lastVisibleSample = SharedModel.hoverSample.get();
        }
        SharedModel.sampleHeight = sidebar.sideCanvas.getHeight() / SharedModel.visibleSamples().getAsInt();
        SharedModel.scrollBarPosition = SharedModel.firstVisibleSample * SharedModel.sampleHeight;
        DrawFunctions.update.set(!DrawFunctions.update.get());
      }
    });
    SharedModel.hoverSample.addListener((obs, oldVal, newVal) -> { if (oldVal != newVal) draw(); });
  }

  public void draw() {
    gc.clearRect(0, 0, sidebar.sideCanvas.getWidth(), sidebar.sideCanvas.getHeight());
    gc.setFill(Color.WHITE);
    gc.setStroke(Color.GRAY);
    if (tracks.isEmpty()) return;
    for (int i = SharedModel.firstVisibleSample; i < SharedModel.lastVisibleSample + 1; i++) {
      gc.fillText(tracks.get(i), 10, gc.getFont().getSize() + i * SharedModel.sampleHeight - SharedModel.scrollBarPosition);
      gc.strokeLine(0, i * SharedModel.sampleHeight, sidebar.sideCanvas.getWidth(), i * SharedModel.sampleHeight);
      for(DrawStack stack : MainController.drawStacks) {
        stack.drawCanvas.getGraphicsContext2D().setStroke(SharedModel.hoverSample.get() == i ? Color.WHITE : Color.GRAY);
        stack.drawCanvas.getGraphicsContext2D().strokeLine(0, i * SharedModel.sampleHeight, stack.drawCanvas.getWidth(), i * SharedModel.sampleHeight);
      }
    }
  }
}
