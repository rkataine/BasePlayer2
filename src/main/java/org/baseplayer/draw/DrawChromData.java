package org.baseplayer.draw;

import org.baseplayer.controllers.MainController;
import org.baseplayer.utils.BaseUtils;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class DrawChromData extends DrawFunctions {
  private final GraphicsContext gc;

  public DrawChromData(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    reactiveCanvas.setOnMouseEntered(event -> { MainController.hoverStack = drawStack; resizing = true; update.set(!update.get()); resizing = false; });
  
    gc = getGraphicsContext2D();
    gc.setFont(new Font("Segoe UI Regular", 8));
  }

  @Override
  public void draw() {
    
    gc.setFill(backgroundColor);
    gc.fillRect(0, 0, getWidth(), getHeight());
   
    drawCyto();
    drawIndicators();
    super.draw();
  }
  void drawCyto() {
    gc.setFill(zoomColor);
    gc.setStroke(Color.INDIANRED);
    double xpos = drawStack.start / drawStack.chromSize * getWidth() + 3;
    double width = Math.max(20, drawStack.viewLength / drawStack.chromSize * getWidth() - 6);
    gc.fillRoundRect(xpos, 3, width, 20, 20, 20);
    gc.strokeRoundRect(xpos, 3, width, 20, 20, 20);
    
  }
  void drawIndicators() {
    gc.setFill(Color.LIGHTGREY);
    gc.setStroke(Color.GREY);
    gc.setLineWidth(1);
   
    String text = BaseUtils.formatNumber((int)drawStack.viewLength) + " bp";
    int textWidth = text.length() * 2;
    
    gc.strokeLine(0, getHeight()-22, getWidth()/2 - textWidth - 10, getHeight()-22);
    gc.strokeLine(getWidth()/2 + textWidth + 10, getHeight()-22, getWidth(), getHeight()-22);
    gc.fillText(text, getWidth()/2 - textWidth, getHeight()-20);

    if (drawStack.viewLength >= 40000000) drawIndicatorLines(10000000, "0M", 4, false);
    else if (drawStack.viewLength > 2000000) drawIndicatorLines(1000000, "M", 4, false);
    else if (drawStack.viewLength > 60000) drawIndicatorLines(100000, null, 4, false);
    else if (drawStack.viewLength > 10000) drawIndicatorLines(10000, null, 4, false);
    else if (drawStack.viewLength > 1000) drawIndicatorLines(1000, null, 4, false);
    else { 
      drawIndicatorLines(100, null, 4, false);
      if(drawStack.viewLength < 100) {
        drawIndicatorLines(10, null, 4, false);
        drawIndicatorLines(1, null, 4, true);
      }
    } 
    if (drawStack.viewLength < 100) {
      gc.setLineDashes(2, 4);
      int middlePos = (int)screenPosToChromPos.apply(getWidth()/2);
      gc.strokeLine(getWidth()/2 - drawStack.pixelSize/2, 0, getWidth()/2 - drawStack.pixelSize/2, getHeight());
      gc.strokeLine(getWidth()/2 + drawStack.pixelSize/2, 0, getWidth()/2 + drawStack.pixelSize/2, getHeight());
      gc.fillText(""+BaseUtils.formatNumber(middlePos), getWidth()/2 - 19, getHeight()-11);
    }
    gc.setLineDashes(0);
  }
  void drawIndicatorLines(int scale, String postfix, int lineheight, boolean skip) {
    gc.setStroke(Color.GRAY);
    gc.setFill(Color.WHITESMOKE);
    
    int startvalue = (int)Math.round(drawStack.start / scale) * scale;
   
    for (int i = (int)startvalue; i < drawStack.chromSize; i += scale) {
      if (i < drawStack.start) continue;
      if (i > drawStack.end) break;
     
      double linepos = chromPosToScreenPos.apply(i * 1.0);
      String text = BaseUtils.formatNumber(i);
      if (postfix != null) 
        text = ""+(int)(i/scale) + postfix;
      int textWidth = (int)gc.getFont().getSize() * text.length();
      if (!skip) gc.fillText(text, linepos - textWidth/3 , getHeight()-lineheight);
     
      gc.strokeLine(linepos, getHeight()-lineheight, linepos, getHeight());
    }
  }
}
