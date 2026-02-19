package org.baseplayer.features;

import java.util.concurrent.CompletableFuture;

import org.baseplayer.io.APIs.UcscApiClient;
import org.baseplayer.io.APIs.UcscApiClient.ConservationData;
import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Track displaying AlphaMissense pathogenicity predictions from UCSC API.
 * Shows predicted pathogenicity scores for variants.
 * 
 * This is an example demonstrating how easy it is to create new UCSC-based tracks
 * by extending AbstractUcscTrack.
 */
public class AlphaMissenseTrack extends AbstractUcscTrack {
  
  public AlphaMissenseTrack() {
    super("AlphaMissense", "UCSC API");
    this.preferredHeight = 40;
    this.color = Color.rgb(180, 100, 180);
  }
  
  @Override
  protected CompletableFuture<ConservationData> fetchDataFromApi(
      String chromosome, long start, long end, int bins) {
    // TODO: Implement AlphaMissense-specific API call
    // For now, use conservation API as a placeholder
    // In a real implementation, you would:
    // return UcscApiClient.fetchAlphaMissense(chromosome, start, end, bins);
    return UcscApiClient.fetchConservation(chromosome, start, end, bins);
  }
  
  @Override
  protected String getZoomMessage() {
    return "Zoom to < 100kb to see AlphaMissense predictions";
  }
  
  @Override
  protected void drawTrackData(GraphicsContext gc, double x, double y, 
                                double width, double height, double viewStart, double viewEnd) {
    drawPathogenicityBars(gc, x, y, width, height, viewStart, viewEnd);
  }
  
  private void drawPathogenicityBars(GraphicsContext gc, double x, double y, 
                                      double width, double height, double viewStart, double viewEnd) {
    
    double[] scores = currentData.scores();
    double dataStart = (double) currentData.start();
    double dataEnd = (double) currentData.end();
    int bins = scores.length;
    
    double dataBinSize = (dataEnd - dataStart) / bins;
    
    // Scale factor for scores (AlphaMissense typically 0-1)
    double displayMin = minValue != null ? minValue : 0.0;
    double displayMax = maxValue != null ? maxValue : 1.0;
    double range = displayMax - displayMin;
    if (range <= 0) range = 1;
    
    // Map data to screen
    double viewLength = viewEnd - viewStart;
    boolean isBaseLevelData = currentData.isBaseLevelData();
    
    for (int i = 0; i < bins; i++) {
      double score = scores[i];
      if (score == 0) continue;
      
      // Calculate genomic position
      double binGenomicStart = dataStart + (i * dataBinSize);
      double binGenomicEnd = isBaseLevelData ? (binGenomicStart + 1.0) : (dataStart + ((i + 1) * dataBinSize));
      
      // Skip if outside view
      if (binGenomicEnd < viewStart || binGenomicStart > viewEnd) continue;
      
      // Map to screen coordinates
      double screenX1 = x + ((binGenomicStart - viewStart) / viewLength) * width;
      double screenX2 = x + ((binGenomicEnd - viewStart) / viewLength) * width;
      double binWidth = Math.max(1, screenX2 - screenX1);
      
      // Normalize score
      double normalized = (score - displayMin) / range;
      double barHeight = normalized * height;
      
      // Color based on pathogenicity: benign (green) to pathogenic (red)
      Color color = getPathogenicityColor(normalized);
      gc.setFill(color);
      
      double barY = y + height - barHeight;
      gc.fillRect(screenX1, barY, binWidth, barHeight);
    }
    
    // Draw baseline
    gc.setStroke(Color.rgb(60, 60, 60));
    gc.setLineWidth(0.5);
    gc.strokeLine(x, y + height, x + width, y + height);
    
    // Scale labels
    gc.setFill(Color.rgb(80, 80, 80));
    gc.setFont(AppFonts.getUIFont(8));
    gc.fillText(String.format("%.2f", displayMax), x + 4, y + 8);
    gc.fillText(String.format("%.2f", displayMin), x + 4, y + height - 2);
    
    // Indicator for per-base data
    if (isBaseLevelData) {
      gc.setFill(Color.rgb(100, 100, 150));
      gc.setFont(AppFonts.getUIFont(8));
      gc.fillText("(bp)", x + width - 25, y + 8);
    }
  }
  
  private Color getPathogenicityColor(double normalized) {
    // Green (benign) to yellow to red (pathogenic)
    normalized = Math.min(1.0, Math.max(0.0, normalized));
    
    if (normalized < 0.5) {
      // Green to yellow
      double t = normalized * 2;
      return Color.rgb(
          (int)(50 + t * 200),
          (int)(150 + t * 100),
          50
      );
    } else {
      // Yellow to red
      double t = (normalized - 0.5) * 2;
      return Color.rgb(
          (int)(250),
          (int)(250 - t * 200),
          50
      );
    }
  }
}
