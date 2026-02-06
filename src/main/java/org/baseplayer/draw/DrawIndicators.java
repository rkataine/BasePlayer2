package org.baseplayer.draw;

import org.baseplayer.SharedModel;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseUtils;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Utility class for drawing position indicators.
 * Can be called from any canvas that needs to display genomic position markers.
 */
public class DrawIndicators {
  
  private static final int BASE_DISPLAY_THRESHOLD = 100000;
  
  /**
   * Draw position indicators at the bottom of the canvas.
   * @param gc GraphicsContext to draw on
   * @param drawStack DrawStack containing view parameters
   * @param width Canvas width
   * @param height Canvas height
   */
  public static void draw(GraphicsContext gc, DrawStack drawStack, double width, double height) {
    // Draw dark background bar for indicators
    gc.setFill(Color.rgb(30, 30, 30));
    gc.fillRect(0, height - 25, width, 25);
    
    gc.setFill(Color.GREY);
    gc.setStroke(Color.GREY);
    gc.setLineWidth(1);
    gc.setFont(AppFonts.getMonoFont(10));
    
    boolean showingBases = drawStack.viewLength <= BASE_DISPLAY_THRESHOLD && SharedModel.referenceGenome != null;
    int lineHeight = showingBases ? 20 : 4;
    
    // Draw indicator lines based on zoom level
    if (drawStack.viewLength >= 40000000) {
      drawIndicatorLines(gc, drawStack, width, height, 20000000, "M", lineHeight, false);
    } else if (drawStack.viewLength > 2000000) {
      drawIndicatorLines(gc, drawStack, width, height, 2000000, "M", lineHeight, false);
    } else if (drawStack.viewLength > 60000) {
      drawIndicatorLines(gc, drawStack, width, height, 100000, null, lineHeight, false);
    } else if (drawStack.viewLength > 10000) {
      drawIndicatorLines(gc, drawStack, width, height, 10000, null, lineHeight, false);
    } else if (drawStack.viewLength > 1000) {
      drawIndicatorLines(gc, drawStack, width, height, 1000, null, lineHeight, false);
    } else { 
      drawIndicatorLines(gc, drawStack, width, height, 100, null, lineHeight, false);
      if (drawStack.viewLength < 100) {
        drawIndicatorLines(gc, drawStack, width, height, 10, null, lineHeight, false);
        drawIndicatorLines(gc, drawStack, width, height, 1, null, lineHeight, true);
      }
    } 
    
    // Show center position at high zoom
    if (drawStack.viewLength < 200) {
      double lineStart = width / 2 - drawStack.pixelSize / 2;
      String middlePosText = BaseUtils.formatNumber((int) drawStack.middlePos());
      gc.fillText(middlePosText, lineStart, height - lineHeight - 5);
    }
  }
  
  private static void drawIndicatorLines(GraphicsContext gc, DrawStack drawStack, 
      double width, double height, int scale, String postfix, int lineHeight, boolean skip) {
    
    int startValue = (int) Math.round(drawStack.start / scale) * scale;
    
    for (int i = startValue; i < drawStack.chromSize; i += scale) {
      if (i < drawStack.start) continue;
      if (i > drawStack.end) break;
      
      double linePos = chromPosToScreenPos(i, drawStack, width);
      
      String text;
      if (postfix != null && postfix.equals("M")) {
        text = (i / 1000000) + "M";
      } else if (postfix != null) {
        text = (i / scale) + postfix;
      } else {
        text = BaseUtils.formatNumber(i);
      }
      
      if (!skip) {
        gc.fillText(text, linePos, height - lineHeight - 5);
      }
      gc.strokeLine(linePos, height - lineHeight, linePos, height);
    }
  }
  
  private static double chromPosToScreenPos(double chromPos, DrawStack drawStack, double width) {
    return ((chromPos - drawStack.start) / drawStack.viewLength) * width;
  }
}
