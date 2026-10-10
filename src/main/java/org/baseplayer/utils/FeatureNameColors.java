package org.baseplayer.utils;

import javafx.scene.paint.Color;

/**
 * Stable feature-name → color mapping for BED types / legend swatches.
 * Same name always yields the same color across sessions.
 */
public final class FeatureNameColors {

  private static final Color EMPTY_NAME_FALLBACK = Color.rgb(120, 120, 130);

  private FeatureNameColors() {}

  /**
   * Color for a BED feature name. Empty/blank names use {@code fallback}
   * (or a neutral gray when fallback is null).
   */
  public static Color colorForName(String name, Color fallback) {
    if (name == null || name.isBlank()) {
      return fallback != null ? fallback : EMPTY_NAME_FALLBACK;
    }
    int hash = name.trim().hashCode();
    // Spread hue; keep sat/brightness high enough to read on dark/light canvases.
    double hue = Integer.remainderUnsigned(hash, 360);
    double saturation = 0.55 + (Integer.remainderUnsigned(hash >>> 8, 35) / 100.0);
    double brightness = 0.72 + (Integer.remainderUnsigned(hash >>> 16, 20) / 100.0);
    return Color.hsb(hue, Math.min(0.90, saturation), Math.min(0.92, brightness));
  }

  public static Color colorForName(String name) {
    return colorForName(name, null);
  }
}
