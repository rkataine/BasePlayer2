package org.baseplayer.tracks;

import javafx.scene.paint.Color;

/**
 * Abstract base implementation of Track with common functionality.
 */
public abstract class AbstractTrack implements Track {
  
  protected final String name;
  protected final String type;
  protected double preferredHeight = 30;
  protected boolean visible = false;  // Disabled by default - user must click eye icon
  protected Color color = Color.GRAY;
  
  // Display scaling settings
  protected Double minValue = null;  // null = auto-scale
  protected Double maxValue = null;  // null = auto-scale
  
  // Cached region info
  protected String lastChromosome = "";
  protected long lastStart = 0;
  protected long lastEnd = 0;
  
  protected AbstractTrack(String name, String type) {
    this.name = name;
    this.type = type;
  }
  
  @Override
  public String getName() {
    return name;
  }
  
  @Override
  public String getType() {
    return type;
  }
  
  @Override
  public double getPreferredHeight() {
    return preferredHeight;
  }
  
  public void setPreferredHeight(double height) {
    this.preferredHeight = height;
  }
  
  @Override
  public boolean isVisible() {
    return visible;
  }
  
  @Override
  public void setVisible(boolean visible) {
    this.visible = visible;
  }
  
  @Override
  public Color getColor() {
    return color;
  }
  
  public void setColor(Color color) {
    this.color = color;
  }
  
  /**
   * Check if the region has changed significantly enough to warrant data reload.
   */
  protected boolean regionChanged(String chromosome, long start, long end) {
    return !chromosome.equals(lastChromosome) 
        || start < lastStart 
        || end > lastEnd;
  }
  
  /**
   * Update the cached region info.
   */
  protected void updateCachedRegion(String chromosome, long start, long end) {
    this.lastChromosome = chromosome;
    this.lastStart = start;
    this.lastEnd = end;
  }
  
  @Override
  public Double getMinValue() {
    return minValue;
  }
  
  @Override
  public Double getMaxValue() {
    return maxValue;
  }
  
  @Override
  public void setMinValue(Double value) {
    this.minValue = value;
  }
  
  @Override
  public void setMaxValue(Double value) {
    this.maxValue = value;
  }
}
