package org.baseplayer.samples.alignment.draw;

import java.util.List;
import java.util.function.Function;

import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.utils.DrawColors;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Unified renderer for BAM read rows inside a single sample track strip.
 *
 * <p>Handles both the flat (normal) layout and the butterfly (allele/haplotype)
 * layout in a single {@link #draw} method, eliminating the previous duplication
 * between {@code drawReadList} and {@code drawReadListAllele}.
 *
 * <p>Also provides read hit-testing ({@link #findAt}) and reactive-canvas
 * highlight drawing ({@link #drawHighlight}).
 */
class DrawReads {

  private final GraphicsContext gc;
  private final Function<Double, Double> chromPosToScreenPos;

  /** Animated loading dots. Incremented each time {@link #drawLoadingIndicator} is called. */
  private long loadingFrame = 0;

  DrawReads(GraphicsContext gc, Function<Double, Double> chromPosToScreenPos) {
    this.gc = gc;
    this.chromPosToScreenPos = chromPosToScreenPos;
  }

  // ── Status messages ──────────────────────────────────────────────────────────

  void drawLoadingIndicator(double sampleY, double sampleH) {
    loadingFrame++;
    int dots = (int) (loadingFrame % 4) + 1;
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(Font.font("Segoe UI", 11));
    gc.fillText("Loading" + ".".repeat(dots), 10, sampleY + sampleH / 2 + 4);
  }

  static void drawZoomMessage(GraphicsContext gc, double sampleY, double sampleH, String message) {
    gc.setFill(Color.web("#888888"));
    gc.setFont(Font.font("Segoe UI", 11));
    gc.fillText(message, 10, sampleY + sampleH / 2 + 4);
  }

  // ── Read drawing ─────────────────────────────────────────────────────────────

  /**
   * Draws all reads for one sample strip.
   *
   * <p>Set {@code butterflyLayout = true} for haplotype data: HP1 rows grow
   * upward from the horizontal midpoint of the reads area, HP2/unphased rows
   * grow downward.
   *
   * @param reads           the reads to render
   * @param readsY          top Y of the reads area (bottom of the coverage strip)
   * @param readsH          height of the reads area in pixels
   * @param readHeight      pixel height per read row
   * @param gap             vertical gap between read rows
   * @param butterflyLayout {@code true} = allele butterfly; {@code false} = normal flat
   * @param hp2StartRow     first row index belonging to HP2 (butterfly only)
   * @param scrollOffset    vertical scroll applied in normal layout
   * @param fwdFill         fill colour for forward-strand reads
   * @param revFill         fill colour for reverse-strand reads
   * @param fwdStroke       stroke colour for forward-strand reads
   * @param revStroke       stroke colour for reverse-strand reads
   * @param isMethylData    {@code true} → mismatches rendered as methylation marks
   * @param clipTop         reads whose bottom edge is above this y are skipped
   * @param clipBottom      reads whose top edge is below this y are skipped
   * @param canvasWidth     pixel width of the canvas (for x-clipping)
   */
  void draw(List<BAMRecord> reads,
            double readsY, double readsH, double readHeight, double gap,
            boolean butterflyLayout, int hp2StartRow, double scrollOffset,
            Color fwdFill, Color revFill, Color fwdStroke, Color revStroke,
            boolean isMethylData,
            double clipTop, double clipBottom, double canvasWidth) {

    double middleY = readsY + readsH / 2;

    for (BAMRecord read : reads) {
      double x1    = chromPosToScreenPos.apply((double) read.pos);
      double x2    = chromPosToScreenPos.apply((double) read.end);
      double width = Math.max(1, x2 - x1);
      double h     = readHeight >= 3 ? readHeight - gap : readHeight;
      double y     = calcY(read, butterflyLayout, hp2StartRow, readsY, readHeight, gap, middleY, scrollOffset);

      if (y + h < clipTop || y > clipBottom) continue;
      if (x1 + width < 0 || x1 > canvasWidth) continue;

      Color[] colors = pickColors(read, fwdFill, revFill, fwdStroke, revStroke);
      gc.setFill(colors[0]);
      gc.setStroke(colors[1]);

      // Check if read has splice junctions (CIGAR N ops)
      if (hasSpliceJunctions(read)) {
        drawSplicedRead(gc, read, y, h, colors[0], colors[1], readHeight, canvasWidth);
      } else {
        gc.fillRect(x1, y, width, h);
        if (readHeight >= 3) gc.strokeRect(x1, y, width, h);
      }

      MismatchRenderer.drawMismatches(gc, read.mismatches, y, h, canvasWidth,
          chromPosToScreenPos, isMethylData, read.isReverseStrand());
    }
  }

  /**
   * Check if a read has splice junctions (CIGAR N operations).
   */
  private static boolean hasSpliceJunctions(BAMRecord read) {
    if (read.cigarOps == null) return false;
    for (int cigarOp : read.cigarOps) {
      if ((cigarOp & 0xF) == BAMRecord.CIGAR_N) return true;
    }
    return false;
  }

  /**
   * Draw a read with splice junctions: exonic segments as filled rectangles,
   * intronic gaps as a thin connecting line at the middle of the read height.
   */
  private void drawSplicedRead(GraphicsContext gc, BAMRecord read,
                                double y, double h,
                                Color fill, Color stroke,
                                double readHeight, double canvasWidth) {
    int refPos = read.pos;
    double midY = y + h / 2;

    for (int cigarOp : read.cigarOps) {
      int op = cigarOp & 0xF;
      int len = cigarOp >>> 4;

      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X -> {
          // Draw exon segment as filled rectangle
          double sx1 = chromPosToScreenPos.apply((double) refPos);
          double sx2 = chromPosToScreenPos.apply((double) (refPos + len));
          double sw = Math.max(1, sx2 - sx1);
          if (sx1 + sw >= 0 && sx1 <= canvasWidth) {
            gc.setFill(fill);
            gc.fillRect(sx1, y, sw, h);
            if (readHeight >= 3) {
              gc.setStroke(stroke);
              gc.strokeRect(sx1, y, sw, h);
            }
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_N -> {
          // Draw intron as a thin connecting line
          double gapX1 = chromPosToScreenPos.apply((double) refPos);
          double gapX2 = chromPosToScreenPos.apply((double) (refPos + len));
          if (gapX1 <= canvasWidth && gapX2 >= 0) {
            gc.setStroke(Color.rgb(140, 140, 140, 0.7));
            gc.setLineWidth(1.0);
            gc.strokeLine(gapX1, midY, gapX2, midY);
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_D -> {
          // Deletion: thin line at read level
          double dx1 = chromPosToScreenPos.apply((double) refPos);
          double dx2 = chromPosToScreenPos.apply((double) (refPos + len));
          if (dx1 + (dx2 - dx1) >= 0 && dx1 <= canvasWidth) {
            gc.setFill(fill);
            gc.fillRect(dx1, y, Math.max(1, dx2 - dx1), h);
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_I, BAMRecord.CIGAR_S -> {
          // Insertion/soft clip: does not consume reference
        }
        case BAMRecord.CIGAR_H, BAMRecord.CIGAR_P -> {
          // Hard clip/padding: no effect on display
        }
        default -> {
          // Unknown op: advance ref by len if it consumes reference
        }
      }
    }
  }

  // ── Hit testing ──────────────────────────────────────────────────────────────

  /**
   * Returns the read at screen position ({@code mx}, {@code my}), or {@code null}.
   * Uses the same y-calculation as {@link #draw} so results are always consistent.
   */
  BAMRecord findAt(List<BAMRecord> reads, double mx, double my,
                   double readsY, double readsH, double readHeight, double gap,
                   boolean butterflyLayout, int hp2StartRow, double scrollOffset) {
    double middleY = readsY + readsH / 2;
    double h = readHeight >= 3 ? readHeight - gap : readHeight;

    for (BAMRecord read : reads) {
      double x1 = chromPosToScreenPos.apply((double) read.pos);
      double x2 = chromPosToScreenPos.apply((double) read.end);
      double w  = Math.max(1, x2 - x1);
      double y  = calcY(read, butterflyLayout, hp2StartRow, readsY, readHeight, gap, middleY, scrollOffset);
      if (mx >= x1 && mx <= x1 + w && my >= y && my <= y + h) return read;
    }
    return null;
  }

  // ── Reactive highlight ───────────────────────────────────────────────────────

  /**
   * Draws a white-border highlight around {@code read} on the reactive canvas {@code rgc}.
   */
  void drawHighlight(GraphicsContext rgc, BAMRecord read,
                     double readsY, double readsH, double readHeight, double gap,
                     boolean butterflyLayout, int hp2StartRow, double scrollOffset) {
    double middleY = readsY + readsH / 2;
    double x1 = chromPosToScreenPos.apply((double) read.pos);
    double x2 = chromPosToScreenPos.apply((double) read.end);
    double w  = Math.max(1, x2 - x1);
    double h  = readHeight >= 3 ? readHeight - gap : readHeight;
    double y  = calcY(read, butterflyLayout, hp2StartRow, readsY, readHeight, gap, middleY, scrollOffset);

    rgc.setStroke(Color.WHITE);
    rgc.setLineWidth(1.5);
    rgc.strokeRect(x1 - 0.5, y - 0.5, w + 1, h + 1);
    rgc.setLineWidth(1.0);
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  private static double calcY(BAMRecord read, boolean butterfly, int hp2StartRow,
                               double readsY, double readHeight, double gap,
                               double middleY, double scrollOffset) {
    if (butterfly) {
      return (read.row < hp2StartRow)
          ? middleY - (read.row + 1) * (readHeight + gap)
          : middleY + (read.row - hp2StartRow) * (readHeight + gap) + 1;
    }
    return (readsY - scrollOffset) + read.row * (readHeight + gap) + 1;
  }

  private static Color[] pickColors(BAMRecord read,
                                     Color fwdFill, Color revFill,
                                     Color fwdStroke, Color revStroke) {
    int dt = read.getDiscordantType();
    if (dt > 0) {
      return switch (dt) {
        case 1 -> {
          int ci = Math.abs(read.mateRefID) % DrawColors.INTERCHROM_FILLS.length;
          yield new Color[]{ DrawColors.INTERCHROM_FILLS[ci], DrawColors.INTERCHROM_STROKES[ci] };
        }
        case 2 -> new Color[]{ DrawColors.DISCORDANT_DELETION,     DrawColors.DISCORDANT_DELETION_STROKE };
        case 3 -> new Color[]{ DrawColors.DISCORDANT_INVERSION,    DrawColors.DISCORDANT_INVERSION_STROKE };
        case 4 -> new Color[]{ DrawColors.DISCORDANT_DUPLICATION,  DrawColors.DISCORDANT_DUPLICATION_STROKE };
        default -> read.isReverseStrand()
            ? new Color[]{ revFill, revStroke }
            : new Color[]{ fwdFill, fwdStroke };
      };
    }
    return read.isReverseStrand()
        ? new Color[]{ revFill, revStroke }
        : new Color[]{ fwdFill, fwdStroke };
  }
}
