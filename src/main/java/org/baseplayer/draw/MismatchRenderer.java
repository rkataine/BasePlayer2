package org.baseplayer.draw;

import java.util.function.Function;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Shared renderer for drawing mismatch markers on aligned reads.
 * Used by both BAM and CRAM read display.
 *
 * Mismatches are stored in BAMRecord.mismatches as int pairs: [pos0, base0, pos1, base1, ...]
 * where pos is 0-based genomic position and base is ASCII char code (A/C/G/T/N).
 */
public class MismatchRenderer {

  // IGV-like mismatch base colors
  public static final Color MISMATCH_A = Color.rgb(100, 200, 100);
  public static final Color MISMATCH_C = Color.rgb(100, 100, 220);
  public static final Color MISMATCH_G = Color.rgb(210, 180, 60);
  public static final Color MISMATCH_T = Color.rgb(220, 80, 80);
  public static final Color MISMATCH_OTHER = Color.rgb(150, 150, 150);

  // Font for base letters
  private static final Font BASE_FONT_LARGE = Font.font("Monospaced", FontWeight.BOLD, 11);
  private static final Font BASE_FONT_SMALL = Font.font("Monospaced", FontWeight.BOLD, 9);

  /**
   * Draw mismatch markers for a single read, with base letters when zoomed in enough.
   */
  public static void drawMismatches(GraphicsContext gc, int[] mismatches,
                                    double y, double h, double canvasWidth,
                                    Function<Double, Double> chromPosToScreen) {
    if (mismatches == null) return;
    for (int m = 0; m < mismatches.length; m += 2) {
      int mmPos = mismatches[m];
      int mmBase = mismatches[m + 1];
      double mx1 = chromPosToScreen.apply((double) mmPos);
      double mx2 = chromPosToScreen.apply((double) (mmPos + 1));
      double mw = Math.max(1, mx2 - mx1);

      if (mx1 + mw < 0 || mx1 > canvasWidth) continue;

      gc.setFill(baseColor(mmBase));
      gc.fillRect(mx1, y, mw, h);

      // Draw base letter when there's enough space
      if (mw >= 6 && h >= 8) {
        gc.setFill(Color.WHITE);
        gc.setFont(mw >= 10 ? BASE_FONT_LARGE : BASE_FONT_SMALL);
        String letter = String.valueOf((char) Character.toUpperCase(mmBase));
        double textX = mx1 + (mw - (mw >= 10 ? 7 : 5.5)) / 2;
        double textY = y + h - (h - (mw >= 10 ? 10 : 8)) / 2;
        gc.fillText(letter, textX, textY);
      }
    }
  }

  /**
   * Get the display color for a given base character.
   */
  public static Color baseColor(int base) {
    return switch (Character.toUpperCase((char) base)) {
      case 'A' -> MISMATCH_A;
      case 'C' -> MISMATCH_C;
      case 'G' -> MISMATCH_G;
      case 'T' -> MISMATCH_T;
      default -> MISMATCH_OTHER;
    };
  }
}
