package org.baseplayer.draw;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.annotation.Cytoband;
import org.baseplayer.annotation.GeneLocation;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseUtils;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

/**
 * Canvas component for drawing chromosome cytobands with interactive navigation.
 * Supports dragging the view indicator and selection to zoom.
 */
public class DrawCytoband extends Canvas {
  
  public static final double CYTO_PADDING_X = 5;
  public static final double CYTO_PADDING_Y = 5;
  public static final double CYTO_HEIGHT = 18;
  public static final double PREFERRED_HEIGHT = CYTO_PADDING_Y * 2 + CYTO_HEIGHT;
  
  private static final double CYTO_ROUND = 8;
  
  // Flag to prevent sequence fetching during drag
  public static boolean isDragging = false;
  
  private final GraphicsContext gc;
  private final DrawStack drawStack;
  
  private Tooltip bandTooltip;
  private String currentHoveredBand = null;
  
  // Selection dragging (zoom to selection)
  private boolean selectDragging = false;
  private double selectStartX = 0;
  private double selectEndX = 0;
  
  // Indicator dragging (move view)
  private boolean indicatorDragging = false;
  private double indicatorDragStartX = 0;
  private double indicatorViewStartPos = 0;
  
  public DrawCytoband(DrawStack drawStack) {
    this.drawStack = drawStack;
    this.gc = getGraphicsContext2D();
    
    setHeight(PREFERRED_HEIGHT);
    
    if (!AnnotationData.isCytobandsLoaded()) {
      AnnotationLoader.loadCytobands();
    }
    
    setupInteraction();
    
    // Redraw when update flag changes
    DrawFunctions.update.addListener((obs, oldVal, newVal) -> draw());
    
    // Redraw on width change
    widthProperty().addListener((obs, oldVal, newVal) -> draw());
  }
  
  private void setupInteraction() {
    setOnMousePressed(event -> {
      if (isIndicatorHit(event.getX())) {
        indicatorDragging = true;
        isDragging = true;
        indicatorDragStartX = event.getX();
        indicatorViewStartPos = drawStack.start;
      } else {
        selectDragging = true;
        selectStartX = event.getX();
        selectEndX = event.getX();
      }
      draw();
      event.consume();
    });
    
    setOnMouseDragged(event -> {
      if (indicatorDragging) {
        double cytoWidth = getWidth() - 2 * CYTO_PADDING_X;
        double dragDelta = event.getX() - indicatorDragStartX;
        double genomeDelta = (dragDelta / cytoWidth) * drawStack.chromSize;
        double newStart = indicatorViewStartPos + genomeDelta;
        drawStack.drawCanvas.setStart(newStart);
        event.consume();
      } else if (selectDragging) {
        selectEndX = event.getX();
        draw();
        event.consume();
      }
    });
    
    setOnMouseReleased(event -> {
      if (indicatorDragging) {
        isDragging = false;
        indicatorDragging = false;
        DrawFunctions.update.set(!DrawFunctions.update.get());
        event.consume();
      } else if (selectDragging) {
        double cytoWidth = getWidth() - 2 * CYTO_PADDING_X;
        double minX = Math.max(CYTO_PADDING_X, Math.min(selectStartX, selectEndX));
        double maxX = Math.min(getWidth() - CYTO_PADDING_X, Math.max(selectStartX, selectEndX));
        
        double startRatio = (minX - CYTO_PADDING_X) / cytoWidth;
        double endRatio = (maxX - CYTO_PADDING_X) / cytoWidth;
        
        double newStart = startRatio * drawStack.chromSize;
        double newEnd = endRatio * drawStack.chromSize;
        
        if (newEnd - newStart < 100) {
          double center = (newStart + newEnd) / 2;
          newStart = center - 50;
          newEnd = center + 50;
        }
        
        drawStack.drawCanvas.zoomAnimation(newStart, newEnd);
        
        selectDragging = false;
        draw();
        event.consume();
      }
    });
    
    setOnMouseMoved(event -> {
      if (isIndicatorHit(event.getX())) {
        setCursor(javafx.scene.Cursor.H_RESIZE);
        if (bandTooltip != null) bandTooltip.hide();
        currentHoveredBand = null;
      } else {
        setCursor(javafx.scene.Cursor.TEXT);
        showBandTooltip(event.getX(), event.getY());
      }
    });
    
    setOnMouseExited(event -> {
      if (bandTooltip != null) bandTooltip.hide();
      currentHoveredBand = null;
    });
  }
  
  private boolean isIndicatorHit(double mouseX) {
    // Don't allow indicator dragging when fully zoomed out
    boolean isZoomedOut = drawStack.viewLength >= drawStack.chromSize * 0.95;
    if (isZoomedOut) return false;
    
    double cytoWidth = getWidth() - 2 * CYTO_PADDING_X;
    double indicatorX = CYTO_PADDING_X + (drawStack.start / drawStack.chromSize) * cytoWidth;
    double indicatorWidth = Math.max(20, (drawStack.viewLength / drawStack.chromSize) * cytoWidth);
    return mouseX >= indicatorX && mouseX <= indicatorX + indicatorWidth;
  }
  
  private void showBandTooltip(double mouseX, double mouseY) {
    String currentChrom = drawStack.chromosome;
    if (!AnnotationData.isCytobandsLoaded() || currentChrom == null) return;
    
    double cytoWidth = getWidth() - 2 * CYTO_PADDING_X;
    
    for (Cytoband band : AnnotationData.getCytobands()) {
      if (!band.chrom().equals(currentChrom)) continue;
      
      double xStart = CYTO_PADDING_X + (band.start() / (double)drawStack.chromSize) * cytoWidth;
      double xEnd = CYTO_PADDING_X + (band.end() / (double)drawStack.chromSize) * cytoWidth;
      
      if (mouseX >= xStart && mouseX <= xEnd) {
        if (band.name().equals(currentHoveredBand)) return;
        
        currentHoveredBand = band.name();
        
        if (bandTooltip == null) {
          bandTooltip = new Tooltip();
          bandTooltip.setShowDelay(Duration.millis(100));
        }
        bandTooltip.setText(band.name() + " (" + BaseUtils.formatNumber((int)band.start()) + " - " + BaseUtils.formatNumber((int)band.end()) + ")");
        
        var screenPos = localToScreen(mouseX, mouseY + 20);
        if (screenPos != null) {
          bandTooltip.show(this, screenPos.getX(), screenPos.getY());
        }
        return;
      }
    }
    
    if (bandTooltip != null) bandTooltip.hide();
  }
  
  public void draw() {
    gc.setFill(DrawFunctions.backgroundColor);
    gc.fillRect(0, 0, getWidth(), getHeight());
    
    String currentChrom = drawStack.chromosome;
    double cytoWidth = getWidth() - 2 * CYTO_PADDING_X;
    
    if (!AnnotationData.isCytobandsLoaded() || currentChrom == null) return;
    
    // Find centromere positions
    double centroStart = -1, centroEnd = -1;
    for (Cytoband band : AnnotationData.getCytobands()) {
      if (!band.chrom().equals(currentChrom)) continue;
      if (band.stain().equals("acen")) {
        if (centroStart < 0) centroStart = band.start();
        centroEnd = band.end();
      }
    }
    
    gc.save();
    gc.setFont(AppFonts.getUIFont(AppFonts.SIZE_SMALL));
    gc.setTextAlign(TextAlignment.CENTER);
    
    // Draw each band
    for (Cytoband band : AnnotationData.getCytobands()) {
      if (!band.chrom().equals(currentChrom)) continue;
      
      double xStart = CYTO_PADDING_X + (band.start() / (double)drawStack.chromSize) * cytoWidth;
      double xEnd = CYTO_PADDING_X + (band.end() / (double)drawStack.chromSize) * cytoWidth;
      double bandWidth = Math.max(1, xEnd - xStart);
      
      Color baseColor = getCytobandColor(band.stain());
      gc.setFill(getCytobandGradient(baseColor, CYTO_PADDING_Y, CYTO_HEIGHT));
      
      boolean isFirstBand = band.start() == 0;
      boolean isLastBand = band.end() >= drawStack.chromSize - 1;
      boolean isCentromere = band.stain().equals("acen");
      
      if (isCentromere) {
        boolean isLeftCentro = band.name().contains("p") || band.start() == centroStart;
        double midY = CYTO_PADDING_Y + CYTO_HEIGHT / 2;
        
        gc.beginPath();
        if (isLeftCentro) {
          gc.moveTo(xStart, CYTO_PADDING_Y);
          gc.lineTo(xEnd, midY);
          gc.lineTo(xStart, CYTO_PADDING_Y + CYTO_HEIGHT);
        } else {
          gc.moveTo(xEnd, CYTO_PADDING_Y);
          gc.lineTo(xStart, midY);
          gc.lineTo(xEnd, CYTO_PADDING_Y + CYTO_HEIGHT);
        }
        gc.closePath();
        gc.fill();
      } else if (isFirstBand) {
        gc.fillRoundRect(xStart, CYTO_PADDING_Y, bandWidth + CYTO_ROUND, CYTO_HEIGHT, CYTO_ROUND * 2, CYTO_ROUND * 2);
        gc.fillRect(xStart + CYTO_ROUND, CYTO_PADDING_Y, bandWidth - CYTO_ROUND, CYTO_HEIGHT);
      } else if (isLastBand) {
        gc.fillRect(xStart, CYTO_PADDING_Y, bandWidth - CYTO_ROUND, CYTO_HEIGHT);
        gc.fillRoundRect(xEnd - CYTO_ROUND * 2, CYTO_PADDING_Y, CYTO_ROUND * 2, CYTO_HEIGHT, CYTO_ROUND * 2, CYTO_ROUND * 2);
      } else {
        gc.fillRect(xStart, CYTO_PADDING_Y, bandWidth, CYTO_HEIGHT);
      }
      
      // Draw band label if enough space
      if (bandWidth > 25 && !isCentromere) {
        double textBrightness = baseColor.getBrightness() < 0.5 ? 1.0 : 0.0;
        gc.setFill(Color.gray(textBrightness));
        gc.fillText(band.name(), xStart + bandWidth / 2, CYTO_PADDING_Y + CYTO_HEIGHT / 2 + 3);
      }
    }
    
    // Draw outline
   /*  gc.setStroke(Color.gray(0.4));
    gc.setLineWidth(1);
    gc.strokeRoundRect(CYTO_PADDING_X, CYTO_PADDING_Y, cytoWidth, CYTO_HEIGHT, CYTO_ROUND * 2, CYTO_ROUND * 2);
    // Use pending position if dragging, otherwise use actual position
    double displayStart = indicatorDragging && pendingViewStart >= 0 ? pendingViewStart : drawStack.start;
    double xpos = CYTO_PADDING_X + (displayS
    gc.restore(); */
    
    // Draw current view indicator (only if zoomed in)
    boolean isZoomedOut = drawStack.viewLength >= drawStack.chromSize * 0.95;
    if (!isZoomedOut) {
      gc.setStroke(Color.DODGERBLUE);
      gc.setLineWidth(2);
      double xpos = CYTO_PADDING_X + (drawStack.start / drawStack.chromSize) * cytoWidth;
      double width = Math.max(20, (drawStack.viewLength / drawStack.chromSize) * cytoWidth);
      
      Color indicatorColor = Color.rgb(30, 144, 255, 0.5);
      LinearGradient indicatorGradient = getCytobandGradient(indicatorColor, CYTO_PADDING_Y, CYTO_HEIGHT);
      gc.setFill(indicatorGradient);
      gc.fillRoundRect(xpos, CYTO_PADDING_Y, width, CYTO_HEIGHT, 10, 10);
      gc.strokeRoundRect(xpos, CYTO_PADDING_Y, width, CYTO_HEIGHT, 10, 10);
    }
    
    // Draw selection rectangle when dragging
    if (selectDragging) {
      double selectMinX = Math.max(CYTO_PADDING_X, Math.min(selectStartX, selectEndX));
      double selectMaxX = Math.min(getWidth() - CYTO_PADDING_X, Math.max(selectStartX, selectEndX));
      double selectWidth = Math.max(2, selectMaxX - selectMinX);
      
      gc.setStroke(Color.ORANGE);
      gc.setLineWidth(2);
      Color selectColor = Color.rgb(255, 165, 0, 0.5);
      LinearGradient selectGradient = getCytobandGradient(selectColor, CYTO_PADDING_Y, CYTO_HEIGHT);
      gc.setFill(selectGradient);
      gc.fillRoundRect(selectMinX, CYTO_PADDING_Y, selectWidth, CYTO_HEIGHT, 10, 10);
      gc.strokeRoundRect(selectMinX, CYTO_PADDING_Y, selectWidth, CYTO_HEIGHT, 10, 10);
    }
    
    // Draw highlighted gene location from search
    GeneLocation highlightedGeneLocation = AnnotationData.getHighlightedGeneLocation();
    if (highlightedGeneLocation != null && 
        highlightedGeneLocation.chrom().equals(drawStack.chromosome)) {
      double geneStartX = CYTO_PADDING_X + (highlightedGeneLocation.start() / drawStack.chromSize) * cytoWidth;
      double geneEndX = CYTO_PADDING_X + (highlightedGeneLocation.end() / drawStack.chromSize) * cytoWidth;
      double geneWidth = Math.max(2, geneEndX - geneStartX);
      
      gc.setStroke(Color.YELLOW);
      gc.setLineWidth(2);
      gc.setFill(Color.rgb(255, 255, 0, 0.5));
      gc.fillRect(geneStartX, CYTO_PADDING_Y, geneWidth, CYTO_HEIGHT);
      gc.strokeRect(geneStartX, CYTO_PADDING_Y, geneWidth, CYTO_HEIGHT);
    }
  }
  
  private Color getCytobandColor(String stain) {
    return switch (stain) {
      case "gneg" -> Color.gray(0.95);
      case "gpos25" -> Color.gray(0.75);
      case "gpos50" -> Color.gray(0.55);
      case "gpos75" -> Color.gray(0.35);
      case "gpos100" -> Color.gray(0.15);
      case "acen" -> Color.web("#B87333");
      case "gvar" -> Color.web("#A0A0B0");
      default -> Color.LIGHTGRAY;
    };
  }
  
  private LinearGradient getCytobandGradient(Color baseColor, double y, double height) {
    Color lighter = baseColor.interpolate(Color.WHITE, 0.4);
    Color darker = baseColor.interpolate(Color.BLACK, 0.2);
    return new LinearGradient(0, y, 0, y + height, false, CycleMethod.NO_CYCLE,
      new Stop(0, lighter),
      new Stop(0.3, baseColor),
      new Stop(0.7, baseColor),
      new Stop(1, darker)
    );
  }
}
