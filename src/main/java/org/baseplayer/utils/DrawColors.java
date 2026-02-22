package org.baseplayer.utils;

import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

/**
 * Centralized color constants for all drawing operations.
 */
public final class DrawColors {

  private DrawColors() {} // Utility class

  // ── UI theme colors ──
  public static Color lineColor = new Color(0.5, 0.8, 0.8, 0.5);  // Mutable: changed by dark mode toggle
  public static final Color BACKGROUND = Color.web("#1e1e1e");       // Editor background
  public static final Color SIDEBAR = Color.web("#252526");          // Sidebar background
  public static final Color BORDER = Color.web("#3c3c3c");           // Border color
  public static final LinearGradient ZOOM_GRADIENT = new LinearGradient(
    0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
    new Stop(0, Color.rgb(30, 144, 255, 0.3)),
    new Stop(1, Color.rgb(100, 180, 255, 0.3))
  );

  // ── BAM read colors ──
  public static final Color READ_FORWARD = Color.rgb(120, 160, 200, 0.85);
  public static final Color READ_REVERSE = Color.rgb(200, 120, 130, 0.85);
  public static final Color READ_FORWARD_STROKE = Color.rgb(90, 130, 170);
  public static final Color READ_REVERSE_STROKE = Color.rgb(170, 90, 100);

  // ── Overlay read colors ──
  public static final Color OVERLAY_FORWARD = Color.rgb(160, 200, 140, 0.7);
  public static final Color OVERLAY_REVERSE = Color.rgb(200, 180, 100, 0.7);
  public static final Color OVERLAY_FORWARD_STROKE = Color.rgb(130, 170, 110);
  public static final Color OVERLAY_REVERSE_STROKE = Color.rgb(170, 150, 70);

  // ── Discordant read colors (structural variants) ──
  public static final Color DISCORDANT_DELETION = Color.rgb(100, 180, 100, 0.85);
  public static final Color DISCORDANT_DELETION_STROKE = Color.rgb(70, 140, 70);
  public static final Color DISCORDANT_INVERSION = Color.rgb(100, 140, 200, 0.85);
  public static final Color DISCORDANT_INVERSION_STROKE = Color.rgb(70, 110, 170);
  public static final Color DISCORDANT_DUPLICATION = Color.rgb(200, 140, 80, 0.85);
  public static final Color DISCORDANT_DUPLICATION_STROKE = Color.rgb(170, 110, 50);

  // ── Inter-chromosomal discordant: distinct colors per mate chromosome ──
  public static final Color[] INTERCHROM_FILLS = {
    Color.rgb(180, 100, 180, 0.85),  // Purple
    Color.rgb(220, 100, 100, 0.85),  // Red
    Color.rgb(100, 180, 220, 0.85),  // Cyan
    Color.rgb(220, 180, 60, 0.85),   // Yellow
    Color.rgb(180, 100, 120, 0.85),  // Magenta/rose
    Color.rgb(100, 200, 160, 0.85),  // Teal
    Color.rgb(200, 120, 200, 0.85),  // Pink
    Color.rgb(160, 200, 100, 0.85),  // Lime
    Color.rgb(120, 120, 200, 0.85),  // Indigo
    Color.rgb(200, 160, 120, 0.85),  // Tan
    Color.rgb(140, 200, 200, 0.85),  // Light teal
    Color.rgb(200, 100, 160, 0.85),  // Hot pink
    Color.rgb(160, 140, 100, 0.85),  // Olive
    Color.rgb(100, 160, 180, 0.85),  // Steel
    Color.rgb(200, 140, 160, 0.85),  // Salmon
    Color.rgb(140, 180, 120, 0.85),  // Sage
    Color.rgb(180, 160, 200, 0.85),  // Lavender
    Color.rgb(200, 200, 100, 0.85),  // Gold
    Color.rgb(160, 100, 160, 0.85),  // Plum
    Color.rgb(100, 180, 140, 0.85),  // Mint
    Color.rgb(180, 120, 100, 0.85),  // Rust
    Color.rgb(120, 160, 180, 0.85),  // Slate
    Color.rgb(200, 160, 180, 0.85),  // Blush
    Color.rgb(140, 200, 160, 0.85),  // Sea green
  };
  public static final Color[] INTERCHROM_STROKES = new Color[INTERCHROM_FILLS.length];
  static {
    for (int i = 0; i < INTERCHROM_FILLS.length; i++) {
      INTERCHROM_STROKES[i] = INTERCHROM_FILLS[i].darker();
    }
  }

  // ── Allele-split (butterfly) view ──
  public static final Color ALLELE_SEPARATOR = Color.rgb(140, 180, 220, 0.7);
  public static final Color ALLELE_HP1_LABEL = Color.rgb(160, 200, 240, 0.8);
  public static final Color ALLELE_HP2_LABEL = Color.rgb(240, 180, 140, 0.8);

  // ── Coverage ──
  public static final Color COVERAGE_FILL = Color.rgb(100, 140, 180, 0.50);
  public static final Color COVERAGE_SEPARATOR = Color.rgb(80, 80, 80, 0.60);

  // ── Sashimi plot (splice junction arches) ──
  public static final Color SASHIMI_ARC = Color.rgb(200, 100, 50, 0.75);
  public static final Color SASHIMI_ARC_STROKE = Color.rgb(180, 80, 40, 0.9);
  public static final Color SASHIMI_LABEL = Color.rgb(230, 180, 140, 0.95);

  // ── Methylation ──
  public static final Color METHYL_LINE = Color.rgb(0, 180, 180, 0.9);
  public static final Color[] SAMPLE_METHYL_COLORS = {
    Color.rgb(220, 60, 60),    // Red
    Color.rgb(60, 120, 220),   // Blue
    Color.rgb(60, 180, 60),    // Green
    Color.rgb(200, 140, 40),   // Orange
    Color.rgb(160, 60, 200),   // Purple
    Color.rgb(40, 180, 180),   // Teal
    Color.rgb(200, 60, 140),   // Pink
    Color.rgb(120, 160, 40),   // Olive
  };

  // ── Mismatch base colors ──
  public static final Color MISMATCH_A = Color.rgb(100, 200, 100);
  public static final Color MISMATCH_C = Color.rgb(100, 100, 220);
  public static final Color MISMATCH_G = Color.rgb(210, 180, 60);
  public static final Color MISMATCH_T = Color.rgb(220, 80, 80);
  public static final Color MISMATCH_OTHER = Color.rgb(150, 150, 150);

  // ── gnomAD variant consequence colors ──
  public static final Color GNOMAD_LOF        = Color.rgb(200, 50, 50);   // Red   - loss of function
  public static final Color GNOMAD_MISSENSE   = Color.rgb(220, 140, 40);  // Orange - missense
  public static final Color GNOMAD_SYNONYMOUS = Color.rgb(80, 140, 200);  // Blue  - synonymous
  public static final Color GNOMAD_OTHER      = Color.rgb(120, 120, 120); // Gray  - other
}
