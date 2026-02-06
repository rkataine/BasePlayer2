package org.baseplayer.tracks;

import javafx.scene.canvas.GraphicsContext;
import javafx.stage.Window;

/**
 * Base interface for all genomic feature tracks.
 * Tracks display data aligned to genomic coordinates.
 */
public interface Track {
  
  /**
   * Get the display name of this track.
   */
  String getName();
  
  /**
   * Get the track type description.
   */
  String getType();
  
  /**
   * Get the preferred height for this track in pixels.
   */
  double getPreferredHeight();
  
  /**
   * Check if the track is currently visible.
   */
  boolean isVisible();
  
  /**
   * Set track visibility.
   */
  void setVisible(boolean visible);
  
  /**
   * Draw the track.
   * 
   * @param gc Graphics context to draw on
   * @param x Starting x position
   * @param y Starting y position
   * @param width Available width
   * @param height Available height
   * @param chromosome Current chromosome
   * @param start View start position (can be fractional for smooth scrolling)
   * @param end View end position (can be fractional for smooth scrolling)
   */
  void draw(GraphicsContext gc, double x, double y, double width, double height,
            String chromosome, double start, double end);
  
  /**
   * Called when the view region changes.
   * Used to trigger async data loading.
   */
  void onRegionChanged(String chromosome, long start, long end);
  
  /**
   * Clean up resources when track is removed.
   */
  default void dispose() {}
  
  /**
   * Get track color (for legend/identification).
   */
  default javafx.scene.paint.Color getColor() {
    return javafx.scene.paint.Color.GRAY;
  }
  
  /**
   * Check if track has data for the current region.
   */
  default boolean hasDataForRegion(String chromosome, long start, long end) {
    return true;
  }
  
  /**
   * Check if track is currently loading data.
   */
  default boolean isLoading() {
    return false;
  }
  
  /**
   * Get minimum display value (for scaling). null means auto-scale.
   */
  default Double getMinValue() {
    return null;
  }
  
  /**
   * Get maximum display value (for scaling). null means auto-scale.
   */
  default Double getMaxValue() {
    return null;
  }
  
  /**
   * Set minimum display value. null for auto-scale.
   */
  default void setMinValue(Double value) {}
  
  /**
   * Set maximum display value. null for auto-scale.
   */
  default void setMaxValue(Double value) {}
  
  /**
   * Check if track uses auto-scaling.
   */
  default boolean isAutoScale() {
    return getMinValue() == null && getMaxValue() == null;
  }
  
  // ============= Click handling methods =============
  
  /**
   * Check if this track supports clicking on data items.
   */
  default boolean supportsClick() {
    return false;
  }
  
  /**
   * Handle a click at the given position. Returns true if an item was found and handled.
   * 
   * @param clickX The x coordinate of the click relative to track start
   * @param clickY The y coordinate of the click relative to track start
   * @param trackWidth The width of the track area
   * @param trackHeight The height of the track area
   * @param chromosome Current chromosome
   * @param viewStart View start position
   * @param viewEnd View end position
   * @param owner Window owner for popup
   * @param screenX Screen X coordinate for popup position
   * @param screenY Screen Y coordinate for popup position
   * @return true if a click was handled, false otherwise
   */
  default boolean handleClick(double clickX, double clickY, double trackWidth, double trackHeight,
                              String chromosome, double viewStart, double viewEnd,
                              Window owner, double screenX, double screenY) {
    return false;
  }
  
  /**
   * Hide any popup shown by this track.
   */
  default void hidePopup() {}
}
