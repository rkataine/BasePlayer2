package org.baseplayer.tracks;

import javafx.scene.paint.Color;

/**
 * Popup displaying data from a UCSC track at a specific genomic position.
 * Shows the score value, position, and track metadata.
 */
public class UcscTrackDataPopup extends TrackDataPopup {
  
  private String trackName;
  private String trackType;
  private String chromosome;
  private long position;
  private double score;
  private Double minValue;
  private Double maxValue;
  
  public UcscTrackDataPopup() {
    super();
  }
  
  /**
   * Set the data to display.
   */
  public void setData(String trackName, String trackType, String chromosome, long position, 
                      double score, Double minValue, Double maxValue) {
    this.trackName = trackName;
    this.trackType = trackType;
    this.chromosome = chromosome;
    this.position = position;
    this.score = score;
    this.minValue = minValue;
    this.maxValue = maxValue;
  }
  
  @Override
  protected void buildContent() {
    if (trackName == null) return;
    
    // Header with track name
    addHeader(trackName, Color.web("#88ccff"));
    
    // Track type
    if (trackType != null) {
      addInfoRow("Track Type", trackType, Color.web("#888888"));
    }
    
    addSeparator();
    
    // Position
    addSectionTitle("Genomic Position");
    addInfoRow("Chromosome", chromosome);
    addInfoRow("Position", String.format("%,d", position));
    
    addSeparator();
    addSectionTitle("Value");
    
    // Score with color based on value
    Color scoreColor = getScoreColor(score, minValue, maxValue);
    addInfoRow("Score", formatScore(score), scoreColor);
    
    // Show scale if available
    if (minValue != null && maxValue != null) {
      String scaleInfo = String.format("Scale: %.2f to %.2f", minValue, maxValue);
      addInfoRow("", scaleInfo, Color.web("#666666"));
    } else if (minValue != null || maxValue != null) {
      double min = 0.0;
      double max = 0.0;
      if (minValue != null) min = minValue;
      if (maxValue != null) max = maxValue;
      String scaleInfo = String.format("Scale: %.2f to %.2f", min, max);
      addInfoRow("", scaleInfo, Color.web("#666666"));
    }
  }
  
  /**
   * Format score value for display.
   */
  private String formatScore(double score) {
    if (Math.abs(score) < 0.01 && score != 0) {
      return String.format("%.2e", score);
    } else if (Math.abs(score) >= 1000) {
      return String.format("%,.0f", score);
    } else {
      return String.format("%.3f", score);
    }
  }
  
  /**
   * Get color for score based on value range.
   */
  private Color getScoreColor(double score, Double min, Double max) {
    // If we have range info, color based on position in range
    if (min != null && max != null && max > min) {
      double normalized = (score - min) / (max - min);
      normalized = Math.max(0.0, Math.min(1.0, normalized));
      
      // Blue to yellow to red gradient
      if (normalized < 0.5) {
        double t = normalized * 2;
        return Color.rgb(
            (int)(80 + t * 170),
            (int)(140 + t * 110),
            (int)(200 - t * 150)
        );
      } else {
        double t = (normalized - 0.5) * 2;
        return Color.rgb(
            (int)(250),
            (int)(250 - t * 200),
            (int)(50)
        );
      }
    }
    
    // Default color based on value magnitude
    if (score > 0) {
      return Color.web("#88ccff");
    } else if (score < 0) {
      return Color.web("#ff8888");
    } else {
      return Color.web("#888888");
    }
  }
}
