package org.baseplayer.samples.alignment.draw;

import java.util.function.Function;

import org.baseplayer.utils.DrawColors;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Shared renderer for drawing mismatch markers on aligned reads.
 * Used by both BAM and CRAM read display.
 *
 * Mismatches are stored in BAMRecord.mismatches as int triplets: [pos0, readBase0, refBase0, pos1, readBase1, refBase1, ...]
 * where pos is 1-based genomic position, readBase is ASCII char code, refBase is ASCII char code of reference (0 if unknown).
 */
public class MismatchRenderer {

  // Font for base letters
  private static final Font BASE_FONT_LARGE = Font.font("Monospaced", FontWeight.BOLD, 11);
  private static final Font BASE_FONT_SMALL = Font.font("Monospaced", FontWeight.BOLD, 9);

  /**
   * Draw mismatch markers for a single read, with base letters when zoomed in enough.
   * Supports optional methylation filtering (suppresses C→T and G→A bisulfite conversions).
   */
  public static void drawMismatches(GraphicsContext gc, int[] mismatches,
                                    double y, double h, double canvasWidth,
                                    Function<Double, Double> chromPosToScreen,
                                    boolean isMethylData, boolean isReverseStrand) {
    if (mismatches == null) return;
    
    for (int m = 0; m + 2 < mismatches.length; m += 3) {
      int mmPos = mismatches[m];
      int mmBase = mismatches[m + 1];
      int mmRefBase = mismatches[m + 2];
      
      // Skip bisulfite conversion mismatches: both C→T and G→A on both strands
      // (bisulfite affects cytosines on both DNA strands)
      if (isMethylData && mmRefBase > 0) {
        char readB = Character.toUpperCase((char) mmBase);
        char refB = Character.toUpperCase((char) mmRefBase);
        // Skip C→T conversions (unmethylated C)
        if (refB == 'C' && readB == 'T') continue;
        // Skip G→A conversions (unmethylated C on opposite strand)
        if (refB == 'G' && readB == 'A') continue;
      }
      
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
   * Draw mismatch markers without methylation filtering (backward-compatible).
   */
  public static void drawMismatches(GraphicsContext gc, int[] mismatches,
                                    double y, double h, double canvasWidth,
                                    Function<Double, Double> chromPosToScreen) {
    drawMismatches(gc, mismatches, y, h, canvasWidth, chromPosToScreen, false, false);
  }

  /**
   * Get the display color for a given base character.
   */
  public static Color baseColor(int base) {
    return switch (Character.toUpperCase((char) base)) {
      case 'A' -> DrawColors.MISMATCH_A;
      case 'C' -> DrawColors.MISMATCH_C;
      case 'G' -> DrawColors.MISMATCH_G;
      case 'T' -> DrawColors.MISMATCH_T;
      default -> DrawColors.MISMATCH_OTHER;
    };
  }
}
