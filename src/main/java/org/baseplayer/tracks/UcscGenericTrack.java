package org.baseplayer.tracks;

import java.util.concurrent.CompletableFuture;

import org.baseplayer.io.APIs.UcscApiClient;
import org.baseplayer.io.APIs.UcscApiClient.ConservationData;
import org.baseplayer.ui.InfoPopup;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Generic track for displaying any UCSC bigWig or bigBed track.
 * Supports click handling to show data values at any position.
 */
public class UcscGenericTrack extends AbstractUcscTrack {
  
  private final String trackName;  // UCSC internal track name
  
  // Click handling popup
  private final InfoPopup popup = new InfoPopup();
  
  public UcscGenericTrack(String trackName, String displayName, String genome) {
    super(displayName, "UCSC: " + trackName);
    this.trackName = trackName;
    this.preferredHeight = 50;
  }
  
  @Override
  protected CompletableFuture<ConservationData> fetchDataFromApi(
      String chromosome, long start, long end, int bins) {
    // TODO: Create a proper generic UCSC track fetcher
    // For now, use conservation API as a placeholder
    return UcscApiClient.fetchConservation(chromosome, start, end, bins);
  }
  
  @Override
  protected void drawTrackData(GraphicsContext gc, double x, double y, 
                                double width, double height, double viewStart, double viewEnd) {
    drawDataTrack(gc, x, y, width, height, currentData, viewStart, viewEnd);
  }
  
  private void drawDataTrack(GraphicsContext gc, double x, double y, double width, double height,
                             ConservationData data, double viewStart, double viewEnd) {
    double[] scores = data.scores();
    if (scores == null || scores.length == 0) return;
    
    double dataStart = (double) data.start();
    double dataEnd = (double) data.end();
    int bins = scores.length;
    
    double dataBinSize = (dataEnd - dataStart) / bins;
    
    // Get scale range
    double dataMin = data.minScore();
    double dataMax = data.maxScore();
    if (minValue != null) dataMin = minValue;
    if (maxValue != null) dataMax = maxValue;
    double range = dataMax - dataMin;
    if (range <= 0) range = 1;
    
    // Map data to screen
    double viewLength = viewEnd - viewStart;
    boolean isBaseLevelData = data.isBaseLevelData();
    
    // Draw as histogram with proper genomic coordinate mapping
    for (int i = 0; i < bins; i++) {
      double value = scores[i];
      if (value == 0) continue; // Skip zero values
      
      // Calculate genomic position for this bin
      double binGenomicStart = dataStart + (i * dataBinSize);
      double binGenomicEnd = isBaseLevelData ? (binGenomicStart + 1.0) : (dataStart + ((i + 1) * dataBinSize));
      
      // Skip if outside view
      if (binGenomicEnd < viewStart || binGenomicStart > viewEnd) continue;
      
      // Map to screen coordinates
      double screenX1 = x + ((binGenomicStart - viewStart) / viewLength) * width;
      double screenX2 = x + ((binGenomicEnd - viewStart) / viewLength) * width;
      double binWidth = Math.max(1, screenX2 - screenX1);
      
      // Normalize to 0-1
      double normalized = (value - dataMin) / range;
      double barHeight = normalized * height;
      
      // Color gradient based on value
      Color barColor = getColorForValue(normalized);
      gc.setFill(barColor);
      
      double barY = y + height - barHeight;
      gc.fillRect(screenX1, barY, binWidth, barHeight);
    }
    
    // Draw baseline
    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(x, y + height, x + width, y + height);
  }
  
  private Color getColorForValue(double normalized) {
    // Clamp to valid range [0, 1] to prevent invalid RGB values
    normalized = Math.max(0.0, Math.min(1.0, normalized));
    
    // Blue to yellow to red gradient
    if (normalized < 0.5) {
      // Blue to yellow
      double t = normalized * 2;
      return Color.rgb(
          (int)(50 + t * 200),
          (int)(50 + t * 200),
          (int)(200 - t * 200)
      );
    } else {
      // Yellow to red
      double t = (normalized - 0.5) * 2;
      return Color.rgb(
          (int)(250),
          (int)(250 - t * 200),
          (int)(50 - t * 50)
      );
    }
  }
  
  @Override
  public boolean supportsClick() {
    return currentData != null && currentData.hasData();
  }
  
  @Override
  public boolean handleClick(double clickX, double clickY, double trackWidth, double trackHeight,
                             String chromosome, double viewStart, double viewEnd,
                             Window owner, double screenX, double screenY) {
    
    if (currentData == null || !currentData.hasData()) {
      return false;
    }
    
    // Calculate genomic position from click
    double viewLength = viewEnd - viewStart;
    double genomicPos = viewStart + (clickX / trackWidth) * viewLength;
    long clickedPosition = Math.round(genomicPos);
    
    // Find the value at this position in our data
    double[] scores = currentData.scores();
    long dataStart = currentData.start();
    long dataEnd = currentData.end();
    int bins = scores.length;
    
    // Calculate which bin this position falls into
    double dataBinSize = (double)(dataEnd - dataStart) / bins;
    int binIndex = (int)((clickedPosition - dataStart) / dataBinSize);
    
    // Check if position is within our data range
    if (binIndex < 0 || binIndex >= bins) {
      System.out.println("Clicked position " + clickedPosition + " is outside data range");
      return false;
    }
    
    // Get the score at this position
    double scoreValue = scores[binIndex];
    
    // Get scale info for display (must be final for lambda)
    final Double minVal;
    final Double maxVal;
    if (minValue != null) {
      minVal = minValue;
    } else {
      minVal = currentData.minScore();
    }
    if (maxValue != null) {
      maxVal = maxValue;
    } else {
      maxVal = currentData.maxScore();
    }
    
    // Show popup with the data
    Platform.runLater(() -> {
      popup.show(
          UcscTrackDataPopup.buildContent(
              getName(),              // Track display name
              trackName,              // UCSC track name
              chromosome,
              clickedPosition,
              scoreValue,
              minVal,
              maxVal
          ),
          owner, screenX, screenY
      );
    });
    
    return true;
  }
  
  @Override
  public void hidePopup() {
    popup.hide();
  }
  
  @Override
  public void dispose() {
    super.dispose();
    popup.hide();
  }
}
