package org.baseplayer.genome.draw;

import org.baseplayer.draw.DrawStack;

import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseUtils;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Utility class for drawing position indicators.
 * Can be called from any canvas that needs to display genomic position markers.
 */
public class PositionIndicator {
  
  private static final int BASE_DISPLAY_THRESHOLD = 100000;
  
  /**
   * Draw position indicators at the bottom of the canvas.
   * @param gc GraphicsContext to draw on
   * @param drawStack DrawStack containing view parameters
   * @param width Canvas width
   * @param height Canvas height
   */
  public static void draw(GraphicsContext gc, DrawStack drawStack, double width, double height) {
    gc.setFill(Color.rgb(30, 30, 30));
    gc.fillRect(0, height - 25, width, 25);
    
    gc.setFill(Color.GREY);
    gc.setStroke(Color.GREY);
    gc.setLineWidth(1);
    gc.setFont(AppFonts.getMonoFont(10));
    
    ReferenceGenomeService refService = ServiceRegistry.getInstance().getReferenceGenomeService();
    boolean showingBases = drawStack.getViewLength() <= BASE_DISPLAY_THRESHOLD && refService.hasGenome();
    int lineHeight = showingBases ? 20 : 4;
    
    if (drawStack.getViewLength() >= 40000000) {
      drawIndicatorLines(gc, drawStack, width, height, 20000000, "M", lineHeight, false);
    } else if (drawStack.getViewLength() > 2000000) {
      drawIndicatorLines(gc, drawStack, width, height, 2000000, "M", lineHeight, false);
    } else if (drawStack.getViewLength() > 60000) {
      drawIndicatorLines(gc, drawStack, width, height, 100000, null, lineHeight, false);
    } else if (drawStack.getViewLength() > 10000) {
      drawIndicatorLines(gc, drawStack, width, height, 10000, null, lineHeight, false);
    } else if (drawStack.getViewLength() > 1000) {
      drawIndicatorLines(gc, drawStack, width, height, 1000, null, lineHeight, false);
    } else { 
      drawIndicatorLines(gc, drawStack, width, height, 100, null, lineHeight, false);
      if (drawStack.getViewLength() < 100) {
        drawIndicatorLines(gc, drawStack, width, height, 10, null, lineHeight, false);
        drawIndicatorLines(gc, drawStack, width, height, 1, null, lineHeight, true);
      }
    } 
    
    if (drawStack.getViewLength() < 200) {
      double lineStart = width / 2 - drawStack.getPixelSize() / 2;
      String middlePosText = BaseUtils.formatNumber((int) drawStack.middlePos());
      gc.fillText(middlePosText, lineStart, height - lineHeight - 5);
    }
  }
  
  private static void drawIndicatorLines(GraphicsContext gc, DrawStack drawStack, 
      double width, double height, int scale, String postfix, int lineHeight, boolean skip) {
    
    int startValue = (int) Math.round(drawStack.getViewStart() / scale) * scale;
    
    for (int i = startValue; i < drawStack.chromSize; i += scale) {
      if (i < drawStack.getViewStart()) continue;
      if (i > drawStack.getViewEnd()) break;
      
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
    return ((chromPos - drawStack.getViewStart()) / drawStack.getViewLength()) * width;
  }
}
