package org.baseplayer.samples.alignment.draw;

import java.util.List;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.ArrowShape;
import org.baseplayer.utils.DrawColors;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

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
  private final DrawStack drawStack;

  /** Animated loading dots. Incremented each time {@link #drawLoadingIndicator} is called. */
  private long loadingFrame = 0;

  DrawReads(GraphicsContext gc, DrawStack drawStack) {
    this.gc = gc;
    this.drawStack = drawStack;
  }

  /** Primitive coordinate conversion — avoids Double autoboxing in hot loops. */
  private double toScreenX(double chromPos) {
    return (chromPos - drawStack.start) * drawStack.pixelSize;
  }

  // ── Status messages ──────────────────────────────────────────────────────────

  void drawLoadingIndicator(double sampleY, double sampleH) {
    loadingFrame++;
    int dots = (int) (loadingFrame % 4) + 1;
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(AppFonts.getFont("Segoe UI", 11));
    gc.fillText("Loading" + ".".repeat(dots), 10, sampleY + sampleH / 2 + 4);
  }

  static void drawZoomMessage(GraphicsContext gc, double sampleY, double sampleH, String message) {
    gc.setFill(Color.web("#888888"));
    gc.setFont(AppFonts.getFont("Segoe UI", 11));
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
            double scrollOffsetTop, double scrollOffsetBottom,
            Color fwdFill, Color revFill, Color fwdStroke, Color revStroke,
        ReadColorMode colorMode, boolean isMethylData, BAMRecord selectedRead,
            double clipTop, double clipBottom, double canvasWidth) {

    double middleY = readsY + readsH / 2;
    boolean signalMode = colorMode == ReadColorMode.UC_TAG || colorMode == ReadColorMode.UD_TAG || colorMode == ReadColorMode.UL_TAG;

    // Compute dynamic color scale from visible reads' signal values.
    // Center on the mean, use 2*stddev as half-range so bulk of values fill the color space.
    // Sample up to ~50,000 values across reads for fast statistics.
    double signalCenter = 0;
    double signalHalfRange = UC_SCALE; // fallback
    // UL tag uses a fixed absolute color scale — no dynamic stats needed.
    if (signalMode && colorMode != ReadColorMode.UL_TAG) {
      long sum = 0;
      long sumSq = 0;
      int count = 0;
      int totalValues = 0;
      for (BAMRecord read : reads) {
        if (read.signalTag != null) totalValues += read.signalTag.length;
      }
      // Sample stride: process every Nth value to cap work at ~50k values
      int stride = Math.max(1, totalValues / 50_000);
      for (BAMRecord read : reads) {
        if (read.signalTag == null) continue;
        for (int i = 0; i < read.signalTag.length; i += stride) {
          short v = read.signalTag[i];
          sum += v;
          sumSq += (long) v * v;
          count++;
        }
      }
      if (count > 0) {
        signalCenter = (double) sum / count;
        double variance = (double) sumSq / count - signalCenter * signalCenter;
        double stddev = Math.sqrt(Math.max(0, variance));
        signalHalfRange = Math.max(1, 2 * stddev);
      }
      ensureColorLut(signalCenter, signalHalfRange);
    }

    for (BAMRecord read : reads) {
      double x1    = toScreenX(read.pos);
      double x2    = toScreenX(read.end);
      double width = Math.max(1, x2 - x1);
      double h     = readHeight >= 3 ? readHeight - gap : readHeight;
      double y     = calcY(read, butterflyLayout, hp2StartRow, readsY, readHeight, gap, middleY, scrollOffset, scrollOffsetTop, scrollOffsetBottom);

      if (y + h < clipTop || y > clipBottom) continue;
      if (x1 + width < 0 || x1 > canvasWidth) continue;

      // Clamp the read's vertical extent to [clipTop, clipBottom] so reads that
      // straddle a butterfly-half boundary are cut cleanly at the midline without
      // needing the expensive gc.clip().
      double drawY = y;
      double drawH = h;
      if (drawY < clipTop) {
        drawH -= (clipTop - drawY);
        drawY = clipTop;
      }
      if (drawY + drawH > clipBottom) {
        drawH = clipBottom - drawY;
      }
      if (drawH <= 0) continue;

      // UC/UD/UL tag coloring: draw per-base colors from signal values
      if (signalMode && read.signalTag != null) {
        drawUcTagRead(gc, read, read.signalTag, drawY, drawH, readHeight, canvasWidth, signalCenter, signalHalfRange, colorMode == ReadColorMode.UL_TAG);
      } else if (colorMode == ReadColorMode.BASE_QUALITY && read.qualities != null && read.qualities.length > 0) {
        drawBaseQualityRead(gc, read, read.qualities, drawY, drawH, readHeight, canvasWidth);
      } else {
        Color[] colors = pickColors(read, fwdFill, revFill, fwdStroke, revStroke, drawStack.chromosome);
        gc.setFill(colors[0]);
        gc.setStroke(colors[1]);

        // Check if read has splice junctions (CIGAR N ops)
        if (hasSpliceJunctions(read)) {
          drawSplicedRead(gc, read, drawY, drawH, colors[0], colors[1], readHeight, canvasWidth);
        } else {
          ArrowShape.fillArrow(gc, x1, drawY, width, drawH, read.isReverseStrand(), colors[0]);
          if (readHeight >= 3) ArrowShape.strokeArrow(gc, x1, drawY, width, drawH, read.isReverseStrand(), colors[1]);
        }
      }

      MismatchRenderer.drawMismatches(gc, read.mismatches, drawY, drawH, canvasWidth,
          drawStack.start, drawStack.pixelSize, isMethylData, read.isReverseStrand(),
          signalMode && read.signalTag != null, colorMode == ReadColorMode.BASE_QUALITY,
          colorMode == ReadColorMode.BASE_QUALITY);

      // Selected-read outline (and its visible mate) drawn in the main pass so
      // they stay locked to read geometry during panning/scrolling.
      boolean isSelected = selectedRead != null && read == selectedRead;
      boolean isSelectedMate = selectedRead != null
          && selectedRead.readName != null
          && read != selectedRead
          && selectedRead.readName.equals(read.readName);
      if (isSelected || isSelectedMate) {
        Color sel = Color.color(1.0, 0.85, 0.0);
        gc.setStroke(sel);
        gc.setLineWidth(1.5);
        if (hasSpliceJunctions(read)) {
          drawSplicedHighlight(gc, read, drawY, drawH, sel, canvasWidth);
        } else {
          ArrowShape.strokeArrow(gc, x1 - 0.5, drawY - 0.5, width + 1, drawH + 1,
              read.isReverseStrand(), sel);
        }
        gc.setLineWidth(1.0);
      }
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

    boolean isReverse = read.isReverseStrand();

    for (int cigarOp : read.cigarOps) {
      int op = cigarOp & 0xF;
      int len = cigarOp >>> 4;

      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X -> {
          // Draw exon segment as filled rectangle (or arrow if it's the
          // strand-specific outer segment)
          double sx1 = toScreenX(refPos);
          double sx2 = toScreenX((refPos + len));
          double sw = Math.max(1, sx2 - sx1);
          if (sx1 + sw >= 0 && sx1 <= canvasWidth) {
            boolean isFirstSeg = refPos == read.pos;
            boolean isLastSeg  = refPos + len == read.end;
            boolean useArrow   = (isReverse && isFirstSeg) || (!isReverse && isLastSeg);
            if (useArrow) {
              ArrowShape.fillArrow(gc, sx1, y, sw, h, isReverse, fill);
              if (readHeight >= 3) ArrowShape.strokeArrow(gc, sx1, y, sw, h, isReverse, stroke);
            } else {
              gc.setFill(fill);
              gc.fillRect(sx1, y, sw, h);
              if (readHeight >= 3) {
                gc.setStroke(stroke);
                gc.strokeRect(sx1, y, sw, h);
              }
            }
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_N -> {
          // Skip intron — sashimi plot shows junctions instead
          refPos += len;
        }
        case BAMRecord.CIGAR_D -> {
          // Deletion: thin line at read level
          double dx1 = toScreenX(refPos);
          double dx2 = toScreenX((refPos + len));
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
                   boolean butterflyLayout, int hp2StartRow, double scrollOffset,
                   double scrollOffsetTop, double scrollOffsetBottom) {
    double middleY = readsY + readsH / 2;
    double h = readHeight >= 3 ? readHeight - gap : readHeight;

    for (BAMRecord read : reads) {
      double y = calcY(read, butterflyLayout, hp2StartRow, readsY, readHeight, gap, middleY,
          scrollOffset, scrollOffsetTop, scrollOffsetBottom);

      // In butterfly mode, each half is clipped to its own region during rendering.
      // Constrain the hittable Y range to the same clip so that reads scrolled past
      // the midline (invisible) don't shadow reads that are actually visible.
      double yHit, yHitBottom;
      if (butterflyLayout) {
        double halfTop    = (read.row < hp2StartRow) ? readsY  : middleY;
        double halfBottom = (read.row < hp2StartRow) ? middleY : readsY + readsH;
        if (y + h <= halfTop || y >= halfBottom) continue; // entirely outside visible clip
        yHit       = Math.max(y, halfTop);
        yHitBottom = Math.min(y + h, halfBottom);
      } else {
        yHit       = y;
        yHitBottom = y + h;
      }

      if (my < yHit || my > yHitBottom) continue;

      // For spliced reads (CIGAR N ops present), only match if the mouse falls on an
      // exonic or deletion segment — not inside the rendered intron gap.  This fixes
      // the incorrect wide-box hit on RNA-seq reads, and allows shorter reads packed
      // into intronic gaps (CIGAR-aware packing) to be correctly detected.
      if (hasSpliceJunctions(read)) {
        if (hitTestSplicedX(read, mx)) return read;
      } else {
        double x1 = toScreenX(read.pos);
        double x2 = toScreenX(read.end);
        if (mx >= x1 && mx <= Math.max(x1 + 1, x2)) return read;
      }
    }
    return null;
  }

  /**
   * Returns true if {@code mx} falls within any rendered (exonic/deletion) segment of
   * a spliced read.  Mirrors the CIGAR traversal used by {@link #drawSplicedRead}.
   */
  private boolean hitTestSplicedX(BAMRecord read, double mx) {
    if (read.cigarOps == null) {
      // Fallback: treat as unspliced
      double x1 = toScreenX(read.pos);
      double x2 = toScreenX(read.end);
      return mx >= x1 && mx <= Math.max(x1 + 1, x2);
    }
    int refPos = read.pos;
    for (int cigarOp : read.cigarOps) {
      int op  = cigarOp & 0xF;
      int len = cigarOp >>> 4;
      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X,
             BAMRecord.CIGAR_D -> {
          double sx1 = toScreenX(refPos);
          double sx2 = toScreenX(refPos + len);
          if (mx >= sx1 && mx <= Math.max(sx1 + 1, sx2)) return true;
          refPos += len;
        }
        case BAMRecord.CIGAR_N -> refPos += len;  // intron gap — not hittable
        default -> { /* I/S/H/P: do not advance refPos */ }
      }
    }
    return false;
  }

  // ── Reactive highlight ───────────────────────────────────────────────────────

  /**
   * Draws a white-border highlight around {@code read} on the reactive canvas {@code rgc}.
   */
  void drawHighlight(GraphicsContext rgc, BAMRecord read,
                     double readsY, double readsH, double readHeight, double gap,
                     boolean butterflyLayout, int hp2StartRow, double scrollOffset,
                     double scrollOffsetTop, double scrollOffsetBottom,
                     double clipTop, double clipBottom, double canvasWidth) {
    drawHighlightColored(rgc, read, readsY, readsH, readHeight, gap, butterflyLayout,
        hp2StartRow, scrollOffset, scrollOffsetTop, scrollOffsetBottom,
        clipTop, clipBottom, canvasWidth, Color.WHITE);
  }

  /** Draws a highlight outline in {@code strokeColor} — used for mate/split-read highlighting. */
  void drawHighlightColored(GraphicsContext rgc, BAMRecord read,
                            double readsY, double readsH, double readHeight, double gap,
                            boolean butterflyLayout, int hp2StartRow, double scrollOffset,
                            double scrollOffsetTop, double scrollOffsetBottom,
                            double clipTop, double clipBottom, double canvasWidth,
                            Color strokeColor) {
    double middleY = readsY + readsH / 2;
    double x1 = toScreenX(read.pos);
    double x2 = toScreenX(read.end);
    double w  = Math.max(1, x2 - x1);
    double h  = readHeight >= 3 ? readHeight - gap : readHeight;
    double y  = calcY(read, butterflyLayout, hp2StartRow, readsY, readHeight, gap, middleY, scrollOffset, scrollOffsetTop, scrollOffsetBottom);

    if (y + h < clipTop || y > clipBottom) return;
    if (x1 + w < 0 || x1 > canvasWidth) return;

    double drawY = y;
    double drawH = h;
    if (drawY < clipTop) {
      drawH -= (clipTop - drawY);
      drawY = clipTop;
    }
    if (drawY + drawH > clipBottom) {
      drawH = clipBottom - drawY;
    }
    if (drawH <= 0) return;

    rgc.setStroke(strokeColor);
    rgc.setLineWidth(1.5);

    if (hasSpliceJunctions(read)) {
      drawSplicedHighlight(rgc, read, drawY, drawH, strokeColor, canvasWidth);
    } else {
      ArrowShape.strokeArrow(rgc, x1 - 0.5, drawY - 0.5, w + 1, drawH + 1, read.isReverseStrand(), strokeColor);
    }

    rgc.setLineWidth(1.0);
  }

  /** Draw a splice-aware highlight outline that mirrors the rendered read geometry. */
  private void drawSplicedHighlight(GraphicsContext rgc, BAMRecord read,
                                    double y, double h,
                                    Color stroke,
                                    double canvasWidth) {
    int refPos = read.pos;
    boolean isReverse = read.isReverseStrand();

    for (int cigarOp : read.cigarOps) {
      int op = cigarOp & 0xF;
      int len = cigarOp >>> 4;

      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X -> {
          double sx1 = toScreenX(refPos);
          double sx2 = toScreenX((refPos + len));
          double sw = Math.max(1, sx2 - sx1);
          if (sx1 + sw >= 0 && sx1 <= canvasWidth) {
            boolean isFirstSeg = refPos == read.pos;
            boolean isLastSeg  = refPos + len == read.end;
            boolean useArrow   = (isReverse && isFirstSeg) || (!isReverse && isLastSeg);
            if (useArrow) {
              ArrowShape.strokeArrow(rgc, sx1 - 0.5, y - 0.5, sw + 1, h + 1, isReverse, stroke);
            } else {
              rgc.strokeRect(sx1 - 0.5, y - 0.5, sw + 1, h + 1);
            }
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_N -> {
          refPos += len;
        }
        case BAMRecord.CIGAR_D -> {
          double dx1 = toScreenX(refPos);
          double dx2 = toScreenX((refPos + len));
          if (dx1 + (dx2 - dx1) >= 0 && dx1 <= canvasWidth) {
            rgc.strokeRect(dx1 - 0.5, y - 0.5, Math.max(1, dx2 - dx1) + 1, h + 1);
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_I, BAMRecord.CIGAR_S, BAMRecord.CIGAR_H, BAMRecord.CIGAR_P -> {
          // no reference span to draw
        }
        default -> {
          // unknown op
        }
      }
    }
  }

  // ── UC tag rendering ────────────────────────────────────────────────────────

  /** Max absolute UC value for color scaling. Values beyond this clip to full blue/red. */
  private static final double UC_SCALE = 15000.0;
  /** Typical Phred cap used for display scaling (values above clamp to max color). */
  private static final int MAX_PHRED_FOR_COLOR = 45;

  /**
   * Number of discrete steps in the cached color lookup table.
   * 512 steps gives smooth visual gradients while keeping memory at ~4 KB.
   */
  private static final int COLOR_LUT_SIZE = 512;

  /** Cached diverging-color lookup table, rebuilt when center/range change. */
  private Color[] colorLut;
  private double colorLutCenter = Double.NaN;
  private double colorLutHalfRange = Double.NaN;

  /** Cached quality-color LUT for Phred-only rendering. */
  private static final Color[] QUALITY_COLOR_LUT = buildQualityColorLut();

  private static Color[] buildQualityColorLut() {
    Color[] lut = new Color[128];
    for (int q = 0; q < lut.length; q++) {
      double t = Math.max(0.0, Math.min(1.0, q / (double) MAX_PHRED_FOR_COLOR));
      // Low quality -> warm red/orange, high quality -> cool blue/cyan.
      double hue = 8 + 210 * t;
      double sat = 0.88;
      double bri = 0.46 + 0.44 * t;
      lut[q] = Color.hsb(hue, sat, bri);
    }
    return lut;
  }

  private static Color lookupQualityColor(int phred) {
    int idx = Math.max(0, Math.min(QUALITY_COLOR_LUT.length - 1, phred));
    return QUALITY_COLOR_LUT[idx];
  }

  /**
   * Ensure the cached color LUT matches the current center and half-range.
   * Rebuilds only when the parameters actually change.
   */
  private void ensureColorLut(double center, double halfRange) {
    if (colorLut != null && center == colorLutCenter && halfRange == colorLutHalfRange) return;
    colorLut = new Color[COLOR_LUT_SIZE];
    for (int i = 0; i < COLOR_LUT_SIZE; i++) {
      // Map index to [-1, 1]
      double t = 2.0 * i / (COLOR_LUT_SIZE - 1) - 1.0;
      if (t < 0) {
        colorLut[i] = Color.color(1 + t, 1 + t, 1);
      } else {
        colorLut[i] = Color.color(1, 1 - t, 1 - t);
      }
    }
    colorLutCenter = center;
    colorLutHalfRange = halfRange;
  }

  /**
   * Fast lookup of a cached color for a signal value.
   */
  private Color lookupUcColor(short value, double center, double halfRange) {
    double t = Math.max(-1, Math.min(1, (value - center) / halfRange));
    int idx = (int) ((t + 1.0) * 0.5 * (COLOR_LUT_SIZE - 1) + 0.5);
    if (idx < 0) idx = 0;
    if (idx >= COLOR_LUT_SIZE) idx = COLOR_LUT_SIZE - 1;
    return colorLut[idx];
  }

  /**
   * Fixed color mapping for UL tag values:
   * 0 = dark blue, 9 = white, 18 = red, &gt;18 = progressively darker red.
   */
  private static Color lookupUlColor(short value) {
    if (value <= 0) {
      return Color.color(0.0, 0.0, 0.6);
    }
    if (value <= 9) {
      double t = value / 9.0;
      // dark blue (0, 0, 0.6) → white (1, 1, 1)
      return Color.color(t, t, 0.6 + 0.4 * t);
    }
    if (value <= 18) {
      double t = (value - 9.0) / 9.0;
      // white (1, 1, 1) → red (1, 0, 0)
      return Color.color(1.0, 1.0 - t, 1.0 - t);
    }
    // >18: red → dark red (saturates at value ~36)
    double t = Math.min(1.0, (value - 18.0) / 18.0);
    return Color.color(1.0 - 0.5 * t, 0.0, 0.0);
  }

  /**
   * Draw a read colored by UC tag values. Walks the CIGAR to map each aligned
   * base to its UC tag index, then paints base-sized rectangles with a
   * blue (negative) → white (zero) → red (positive) diverging color scale.
   *
   * <p>Performance optimizations:
   * <ul>
   *   <li>When zoomed out ({@code pixelSize < 1}), steps by
   *       {@code ceil(1/pixelSize)} bases so at most one rect is drawn per
   *       screen pixel (avoids millions of redundant {@code fillRect} calls
   *       that would overflow the JavaFX Canvas command buffer).</li>
   *   <li>Merges consecutive bases with the same LUT color into a single
   *       {@code fillRect} call (large runs are common in signal data).</li>
   * </ul>
   */
  private void drawUcTagRead(GraphicsContext gc, BAMRecord read, short[] uc,
                              double y, double h, double readHeight, double canvasWidth,
                              double center, double halfRange, boolean isUlMode) {
    int refPos = read.pos;
    // When ur tag is present, uc values are indexed by reference position relative to ur.
    boolean useUrOffset = read.urTag != null && read.urTag.length >= 2;
    int urStart = useUrOffset ? read.urTag[0] : 0;
    int urEnd = useUrOffset ? read.urTag[1] : 0;
    boolean reverse = read.isReverseStrand();
    int ucIdx = 0; // used only when ur tag is absent

    // Pixel step: at zoom levels where each base is sub-pixel, skip forward so
    // we draw ~1 sample per pixel instead of per base.
    double pixelSize = drawStack.pixelSize;
    int baseStep = pixelSize >= 1.0 ? 1 : (int) Math.max(1, Math.ceil(1.0 / pixelSize));

    // Run-merging state (accumulated same-color fillRect).
    Color runColor = null;
    double runX = 0;
    double runEndX = 0;
    double readX1 = toScreenX(read.pos);
    double readX2 = toScreenX(read.end);

    for (int cigarOp : read.cigarOps) {
      int op = cigarOp & 0xF;
      int len = cigarOp >>> 4;

      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X -> {
          // Quick off-screen check for the whole segment
          double segStartX = toScreenX(refPos);
          double segEndX = toScreenX((refPos + len));
          if (segEndX < 0 || segStartX > canvasWidth) {
            refPos += len;
            ucIdx += len;
            break;
          }

          for (int j = 0; j < len; j += baseStep) {
            int step = Math.min(baseStep, len - j);
            double bx = toScreenX(refPos + j);
            double bx2 = toScreenX(refPos + j + step);
            if (bx > canvasWidth) break;
            if (bx2 < 0) continue;
            double bw = Math.max(1, bx2 - bx);

            int idx;
            if (useUrOffset) {
              idx = reverse ? (urEnd - refPos - j) : (refPos + j - urStart);
            } else {
              idx = ucIdx + j;
            }
            Color c = (idx >= 0 && idx < uc.length)
                ? (isUlMode ? lookupUlColor(uc[idx]) : lookupUcColor(uc[idx], center, halfRange))
                : Color.GRAY;

            // Extend current run if same color and contiguous; else flush and start new run.
            if (c == runColor && bx <= runEndX + 0.01) {
              if (bx + bw > runEndX) runEndX = bx + bw;
            } else {
              if (runColor != null) {
                fillMergedRunWithArrowCap(gc, runColor, runX, runEndX, y, h,
                    read.isReverseStrand(), readX1, readX2);
              }
              runColor = c;
              runX = bx;
              runEndX = bx + bw;
            }
          }
          refPos += len;
          ucIdx += len;
        }
        case BAMRecord.CIGAR_I -> {
          ucIdx += len;
        }
        case BAMRecord.CIGAR_D, BAMRecord.CIGAR_N -> {
          // Flush current color run before drawing deletion line.
          if (runColor != null) {
            fillMergedRunWithArrowCap(gc, runColor, runX, runEndX, y, h,
                read.isReverseStrand(), readX1, readX2);
            runColor = null;
          }
          if (op == BAMRecord.CIGAR_D) {
            double dx1 = toScreenX(refPos);
            double dx2 = toScreenX((refPos + len));
            if (dx1 + (dx2 - dx1) >= 0 && dx1 <= canvasWidth) {
              gc.setFill(Color.GRAY);
              gc.fillRect(dx1, y + h / 2 - 0.5, Math.max(1, dx2 - dx1), 1);
            }
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_S -> {
          ucIdx += len;
        }
        case BAMRecord.CIGAR_H, BAMRecord.CIGAR_P -> {
          // consumes neither
        }
        default -> {}
      }
    }

    // Flush trailing run.
    if (runColor != null) {
      fillMergedRunWithArrowCap(gc, runColor, runX, runEndX, y, h,
          read.isReverseStrand(), readX1, readX2);
    }

    // Stroke outline
    if (readHeight >= 3) {
      double x1 = toScreenX(read.pos);
      double x2 = toScreenX(read.end);
      gc.setStroke(Color.gray(0.3));
      ArrowShape.strokeArrow(gc, x1, y, Math.max(1, x2 - x1), h, read.isReverseStrand(), Color.gray(0.3));
    }
  }

  /**
   * Draw a read with per-base quality coloring (Phred-scaled).
   */
  private void drawBaseQualityRead(GraphicsContext gc, BAMRecord read, byte[] qualities,
                                   double y, double h, double readHeight, double canvasWidth) {
    if (read.cigarOps == null) return;

    int refPos = read.pos;
    int readIdx = 0;

    double pixelSize = drawStack.pixelSize;
    int baseStep = pixelSize >= 1.0 ? 1 : (int) Math.max(1, Math.ceil(1.0 / pixelSize));

    Color runColor = null;
    double runX = 0;
    double runEndX = 0;
    double readX1 = toScreenX(read.pos);
    double readX2 = toScreenX(read.end);

    for (int cigarOp : read.cigarOps) {
      int op = cigarOp & 0xF;
      int len = cigarOp >>> 4;

      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X -> {
          double segStartX = toScreenX(refPos);
          double segEndX = toScreenX((refPos + len));
          if (segEndX < 0 || segStartX > canvasWidth) {
            refPos += len;
            readIdx += len;
            break;
          }

          for (int j = 0; j < len; j += baseStep) {
            int step = Math.min(baseStep, len - j);
            double bx = toScreenX(refPos + j);
            double bx2 = toScreenX(refPos + j + step);
            if (bx > canvasWidth) break;
            if (bx2 < 0) continue;
            double bw = Math.max(1, bx2 - bx);

            int qIdx = readIdx + j;
            int q = qIdx < qualities.length ? (qualities[qIdx] & 0xFF) : 0;
            Color c = lookupQualityColor(q);

            if (c == runColor && bx <= runEndX + 0.01) {
              if (bx + bw > runEndX) runEndX = bx + bw;
            } else {
              if (runColor != null) {
                fillMergedRunWithArrowCap(gc, runColor, runX, runEndX, y, h,
                    read.isReverseStrand(), readX1, readX2);
              }
              runColor = c;
              runX = bx;
              runEndX = bx + bw;
            }
          }
          refPos += len;
          readIdx += len;
        }
        case BAMRecord.CIGAR_I, BAMRecord.CIGAR_S -> {
          readIdx += len;
        }
        case BAMRecord.CIGAR_D, BAMRecord.CIGAR_N -> {
          if (runColor != null) {
            fillMergedRunWithArrowCap(gc, runColor, runX, runEndX, y, h,
                read.isReverseStrand(), readX1, readX2);
            runColor = null;
          }
          if (op == BAMRecord.CIGAR_D) {
            double dx1 = toScreenX(refPos);
            double dx2 = toScreenX((refPos + len));
            if (dx1 + (dx2 - dx1) >= 0 && dx1 <= canvasWidth) {
              gc.setFill(Color.GRAY);
              gc.fillRect(dx1, y + h / 2 - 0.5, Math.max(1, dx2 - dx1), 1);
            }
          }
          refPos += len;
        }
        case BAMRecord.CIGAR_H, BAMRecord.CIGAR_P -> {
          // consumes neither
        }
        default -> {}
      }
    }

    if (runColor != null) {
      fillMergedRunWithArrowCap(gc, runColor, runX, runEndX, y, h,
          read.isReverseStrand(), readX1, readX2);
    }

    if (readHeight >= 3) {
      double x1 = toScreenX(read.pos);
      double x2 = toScreenX(read.end);
      gc.setStroke(Color.gray(0.3));
      ArrowShape.strokeArrow(gc, x1, y, Math.max(1, x2 - x1), h, read.isReverseStrand(), Color.gray(0.3));
    }
  }

  private static void fillMergedRunWithArrowCap(GraphicsContext gc, Color fill,
                                                 double runX, double runEndX,
                                                 double y, double h,
                                                 boolean reverse,
                                                 double readX1, double readX2) {
    double width = runEndX - runX;
    if (width <= 0) return;

    boolean touchesArrowEnd = reverse
        ? (runX <= readX1 + 1.0)
        : (runEndX >= readX2 - 1.0);

    if (touchesArrowEnd) {
      ArrowShape.fillArrow(gc, runX, y, width, h, reverse, fill);
    } else {
      gc.setFill(fill);
      gc.fillRect(runX, y, width, h);
    }
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  /** Public wrapper so callers (e.g. AlignmentCanvas) can get the screen Y of a read. */
  public double calcReadScreenY(BAMRecord read, double readsY, double readsH,
                                double readHeight, double gap, boolean butterfly, int hp2StartRow,
                                double scrollOffset, double scrollOffsetTop, double scrollOffsetBottom) {
    double middleY = readsY + readsH / 2;
    return calcY(read, butterfly, hp2StartRow, readsY, readHeight, gap, middleY, scrollOffset, scrollOffsetTop, scrollOffsetBottom);
  }

  /**
   * Returns the screen X of the midpoint of each exon segment for a spliced read.
   * For a read with N introns the returned array has N+1 entries.
   * For unspliced reads (no N ops) returns a single-element array with the overall midpoint.
   */
  public double[] getExonMidXs(BAMRecord read) {
    if (!hasSpliceJunctions(read) || read.cigarOps == null) {
      return new double[]{ toScreenX((read.pos + read.end) * 0.5) };
    }
    java.util.List<Double> midXs = new java.util.ArrayList<>();
    int refPos = read.pos;
    int segStart = refPos;
    for (int op : read.cigarOps) {
      int type = op & 0xF;
      int len  = op >>> 4;
      switch (type) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X, BAMRecord.CIGAR_D -> refPos += len;
        case BAMRecord.CIGAR_N -> {
          midXs.add(toScreenX((segStart + refPos) * 0.5));
          refPos += len;
          segStart = refPos;
        }
        default -> {}
      }
    }
    midXs.add(toScreenX((segStart + refPos) * 0.5)); // last exon
    return midXs.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /**
   * Returns the screen X coordinate of the visual midpoint of {@code read}.
   * <p>For spliced reads (CIGAR N ops), the genomic midpoint falls inside an intron,
   * so we instead find the midpoint by exon-length: sum the exon lengths, then locate
   * the genomic position that corresponds to the 50 % mark through the exon body.
   * For non-spliced reads this is identical to {@code toScreenX((read.pos + read.end) / 2)}.
   */
  public double calcReadArcAnchorX(BAMRecord read) {
    if (!hasSpliceJunctions(read) || read.cigarOps == null) {
      return toScreenX((read.pos + read.end) * 0.5);
    }
    // Pass 1: total exon length
    int totalExon = 0;
    int refPos = read.pos;
    for (int op : read.cigarOps) {
      int type = op & 0xF;
      int len  = op >>> 4;
      if (type == BAMRecord.CIGAR_M || type == BAMRecord.CIGAR_EQ || type == BAMRecord.CIGAR_X) {
        totalExon += len;
        refPos += len;
      } else if (type == BAMRecord.CIGAR_N || type == BAMRecord.CIGAR_D) {
        refPos += len;
      }
    }
    if (totalExon == 0) return toScreenX((read.pos + read.end) * 0.5);

    // Pass 2: find the genomic position at the 50 % exon mark
    int half = totalExon / 2;
    int accumulated = 0;
    refPos = read.pos;
    for (int op : read.cigarOps) {
      int type = op & 0xF;
      int len  = op >>> 4;
      if (type == BAMRecord.CIGAR_M || type == BAMRecord.CIGAR_EQ || type == BAMRecord.CIGAR_X) {
        if (accumulated + len >= half) {
          int offset = half - accumulated;
          return toScreenX(refPos + offset);
        }
        accumulated += len;
        refPos += len;
      } else if (type == BAMRecord.CIGAR_N || type == BAMRecord.CIGAR_D) {
        refPos += len;
      }
    }
    return toScreenX(refPos); // fallback: end of last exon
  }

  private static double calcY(BAMRecord read, boolean butterfly, int hp2StartRow,
                               double readsY, double readHeight, double gap,
                               double middleY, double scrollOffset,
                               double scrollOffsetTop, double scrollOffsetBottom) {
    if (butterfly) {
      if (read.row < hp2StartRow) {
        // Top half (forward / HP1): offset moves reads down (toward middle) to reveal upper rows
        return middleY - (read.row + 1) * (readHeight + gap) + scrollOffsetTop;
      } else {
        // Bottom half (reverse / HP2): offset moves reads up (toward middle) to reveal lower rows
        return middleY + (read.row - hp2StartRow) * (readHeight + gap) + 1 - scrollOffsetBottom;
      }
    }
    return (readsY - scrollOffset) + read.row * (readHeight + gap) + 1;
  }

  private static Color[] pickColors(BAMRecord read,
                                     Color fwdFill, Color revFill,
                                     Color fwdStroke, Color revStroke,
                                     String currentChrom) {
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

    // SA tag: if any split alignment maps to a different chromosome, color by
    // the first such chromosome using the same inter-chrom palette.
    if (read.saTag != null && currentChrom != null) {
      String saChrom = parseSaChrom(read.saTag, currentChrom);
      if (saChrom != null) {
        int ci = Math.abs(saChrom.hashCode()) % DrawColors.INTERCHROM_FILLS.length;
        return new Color[]{ DrawColors.INTERCHROM_FILLS[ci], DrawColors.INTERCHROM_STROKES[ci] };
      }
    }

    return read.isReverseStrand()
        ? new Color[]{ revFill, revStroke }
        : new Color[]{ fwdFill, fwdStroke };
  }

  /**
   * Returns the first SA chromosome that differs from {@code currentChrom},
   * normalizing both by stripping a leading "chr" prefix before comparing.
   * Returns {@code null} if all SA entries map to the same chromosome.
   */
  private static String parseSaChrom(String saTag, String currentChrom) {
    String normCurrent = stripChr(currentChrom);
    int start = 0;
    int len = saTag.length();
    while (start < len) {
      int semi = saTag.indexOf(';', start);
      if (semi < 0) semi = len;
      int comma = saTag.indexOf(',', start);
      if (comma > 0 && comma < semi) {
        String saChrom = saTag.substring(start, comma);
        if (!stripChr(saChrom).equals(normCurrent)) return saChrom;
      }
      start = semi + 1;
    }
    return null;
  }

  private static String stripChr(String s) {
    return (s != null && s.startsWith("chr")) ? s.substring(3) : s;
  }
}
