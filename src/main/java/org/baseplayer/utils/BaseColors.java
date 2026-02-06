package org.baseplayer.utils;

import javafx.scene.paint.Color;

/**
 * Reference base colors for DNA sequence display.
 */
public final class BaseColors {
  
  private BaseColors() {} // Utility class
  
  public static final Color COLOR_A = Color.web("#4CAF50"); // Green
  public static final Color COLOR_T = Color.web("#F44336"); // Red
  public static final Color COLOR_G = Color.web("#FF9800"); // Orange
  public static final Color COLOR_C = Color.web("#2196F3"); // Blue
  public static final Color COLOR_N = Color.GRAY;
  
  /**
   * Get the color for a DNA base.
   */
  public static Color getBaseColor(char base) {
    return switch (Character.toUpperCase(base)) {
      case 'A' -> COLOR_A;
      case 'T' -> COLOR_T;
      case 'G' -> COLOR_G;
      case 'C' -> COLOR_C;
      default -> COLOR_N;
    };
  }
}
