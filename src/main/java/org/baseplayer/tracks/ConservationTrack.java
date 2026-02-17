package org.baseplayer.tracks;

import java.util.concurrent.CompletableFuture;

import org.baseplayer.io.UcscApiClient;
import org.baseplayer.io.UcscApiClient.ConservationData;
import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Track displaying PhyloP conservation scores from UCSC API.
 * Shows conservation (positive scores) and fast-evolving regions (negative scores).
 */
public class ConservationTrack extends AbstractUcscTrack {
  
  public ConservationTrack() {
    super("PhyloP Conservation", "UCSC API");
    this.preferredHeight = 35;
    this.color = Color.rgb(100, 180, 100);
  }
  
  @Override
  protected CompletableFuture<ConservationData> fetchDataFromApi(
      String chromosome, long start, long end, int bins) {
    // Use PhyloP conservation track from UCSC
    return UcscApiClient.fetchConservation(chromosome, start, end, bins);
  }
  
  @Override
  protected String getZoomMessage() {
    return "Zoom to < 100kb to see conservation";
  }
  
  @Override
  protected void drawTrackData(GraphicsContext gc, double x, double y, 
                                double width, double height, double viewStart, double viewEnd) {
    double barCenter = y + height / 2;
    
    // Draw zero line
    gc.setStroke(Color.rgb(60, 60, 60));
    gc.setLineWidth(0.5);
    gc.strokeLine(x, barCenter, x + width, barCenter);
    
    // Draw bars
    drawBars(gc, x, y, barCenter, width, height, viewStart, viewEnd);
  }
  
  private void drawBars(GraphicsContext gc, double x, double barTop, double barCenter, 
                        double width, double barHeight, double viewStart, double viewEnd) {
    
    double[] scores = currentData.scores();
    double dataStart = (double) currentData.start();
    double dataEnd = (double) currentData.end();
    int bins = scores.length;
    
    double dataBinSize = (dataEnd - dataStart) / bins;
    double halfHeight = barHeight / 2;
    
    // Scale factor for scores - use track settings if provided, otherwise auto-scale
    double displayMin = minValue != null ? minValue : currentData.minScore();
    double displayMax = maxValue != null ? maxValue : currentData.maxScore();
    double maxAbsScore = Math.max(Math.abs(displayMin), Math.abs(displayMax));
    if (maxAbsScore < 1) maxAbsScore = 1;
    
    // Map data to screen
    double viewLength = viewEnd - viewStart;
    
    // For per-base data at high zoom, use optimized drawing
    boolean isBaseLevelData = currentData.isBaseLevelData();
    
    for (int i = 0; i < bins; i++) {
      double score = scores[i];
      if (score == 0) continue;
      
      // Calculate genomic position for this bin/base - keep as double for smooth scrolling
      double binGenomicStart = dataStart + (i * dataBinSize);
      double binGenomicEnd = isBaseLevelData ? (binGenomicStart + 1.0) : (dataStart + ((i + 1) * dataBinSize));
      
      // Skip if outside view
      if (binGenomicEnd < viewStart || binGenomicStart > viewEnd) continue;
      
      // Map to screen coordinates
      double screenX1 = x + ((binGenomicStart - viewStart) / viewLength) * width;
      double screenX2 = x + ((binGenomicEnd - viewStart) / viewLength) * width;
      double binWidth = Math.max(1, screenX2 - screenX1);
      
      double barDisplayHeight = Math.abs(score) / maxAbsScore * halfHeight;
      barDisplayHeight = Math.min(barDisplayHeight, halfHeight);
      
      if (score > 0) {
        // Conserved - draw upward in green
        gc.setFill(getConservedColor(score / maxAbsScore));
        gc.fillRect(screenX1, barCenter - barDisplayHeight, binWidth, barDisplayHeight);
      } else {
        // Fast-evolving - draw downward in red
        gc.setFill(getFastEvolvingColor(Math.abs(score) / maxAbsScore));
        gc.fillRect(screenX1, barCenter, binWidth, barDisplayHeight);
      }
    }
    
    // Scale labels (show display range)
    gc.setFill(Color.rgb(80, 80, 80));
    gc.setFont(AppFonts.getUIFont(8));
    gc.fillText(String.format("+%.1f", displayMax), x + 4, barTop + 8);
    gc.fillText(String.format("%.1f", displayMin), x + 4, barTop + barHeight - 2);
    
    // Indicator for per-base data
    if (isBaseLevelData) {
      gc.setFill(Color.rgb(100, 100, 150));
      gc.setFont(AppFonts.getUIFont(8));
      gc.fillText("(bp)", x + width - 25, barTop + 8);
    }
  }
  
  private Color getConservedColor(double intensity) {
    intensity = Math.min(1.0, Math.max(0.0, intensity));
    int green = (int)(100 + 155 * intensity);
    return Color.rgb(30, green, 60);
  }
  
  private Color getFastEvolvingColor(double intensity) {
    intensity = Math.min(1.0, Math.max(0.0, intensity));
    int red = (int)(100 + 155 * intensity);
    return Color.rgb(red, 40, 40);
  }
}
