package org.baseplayer.draw;

import java.util.List;

import org.baseplayer.SharedModel;
import org.baseplayer.io.Settings;
import org.baseplayer.reads.bam.BAMRecord;
import org.baseplayer.reads.bam.SampleFile;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.variant.Variant;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

public class DrawSampleData extends DrawFunctions {
  
  public Image snapshot;
  private final GraphicsContext gc;

  /** Unified coverage data computation and drawing. */
  private final CoverageDrawer coverageDrawer = new CoverageDrawer();

  // Coverage view constants
  private static final double MIN_COVERAGE_HEIGHT = 30;
  private static final double MAX_COVERAGE_HEIGHT = 60;

  // Don't query BAM when view is wider than this (too many reads to draw individually)
  // Show coverage-only view up to the coverage limit; beyond that show sampled coverage

  public DrawSampleData(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    widthProperty().addListener((obs, oldVal, newVal) -> { resizing = true; setStartEnd(drawStack.start, drawStack.end); resizing = false; });
    gc = getGraphicsContext2D();
    gc.setLineWidth(1);

    Platform.runLater(() -> { draw(); });
  }
  void drawSnapShot() { if (snapshot != null) gc.drawImage(snapshot, 0, 0, getWidth(), getHeight()); }
  @Override
  public void draw() {
    gc.setFill(DrawColors.BACKGROUND);
    gc.fillRect(0, 0, getWidth()+1, getHeight()+1);
    
    // Always calculate sampleHeight before drawing variants (needed for correct Y positioning)
    double canvasHeight = getHeight();
    double masterOffset = SharedModel.masterTrackHeight;
    double availableHeight = canvasHeight - masterOffset;
    SharedModel.sampleHeight = availableHeight / Math.max(1, SharedModel.visibleSamples().getAsInt());
    
    // Draw BAM reads/coverage first, then variants on top so they're visible
    drawBamReads();
    if (drawStack.variants != null) drawVariants();
    super.draw();
  }
  void drawVariants() {    
    for (Variant variant : drawStack.variants) {
      if (variant.line.getEndX() < drawStack.start-1) continue;
      if (variant.line.getStartX() > drawStack.end) break;

      drawLine(variant, DrawColors.lineColor, gc);    
    }
  }
  void drawLine(Variant variant, Color color, GraphicsContext gc) {
    if (variant.index < SharedModel.firstVisibleSample || variant.index > SharedModel.lastVisibleSample + 1) return;
   
    gc.setFill(color);
    double screenPos = chromPosToScreenPos.apply(variant.line.getStartX());
    double ypos = SharedModel.masterTrackHeight + SharedModel.sampleHeight * variant.index - SharedModel.scrollBarPosition;
    double height = heightToScreen.apply(variant.line.getEndY());
    
    // Always use fillRect — strokeLine at sub-pixel positions gets anti-aliased
    // across two pixels, making the line invisible on top of opaque reads
    double w = Math.max(1, drawStack.pixelSize);
    gc.fillRect(Math.floor(screenPos), ypos - height, w, height);
  }

  /**
   * Draw BAM reads for all loaded sample files.
   * Each sample occupies a horizontal strip (sampleHeight pixels tall).
   * Reads are packed into rows within that strip.
   */
  void drawBamReads() {
    if (SharedModel.bamFiles.isEmpty()) return;
    
    double canvasHeight = getHeight();
    int numSamples = SharedModel.sampleList.size();
    if (numSamples == 0) return;
    
    double masterOffset = SharedModel.masterTrackHeight;
    double sampleH = SharedModel.sampleHeight; // Use the value already calculated in draw()
    
    // Don't query when zoomed out beyond coverage range — use sampled coverage (if enabled)
    if (drawStack.viewLength > Settings.get().getMaxCoverageViewLength()) {
      // Only draw sampled coverage if the setting is enabled (useful to disable for exome data)
      if (!Settings.get().isEnableSampledCoverage()) {
        return; // Skip sampled coverage - no data at this zoom level
      }
      String chrom = drawStack.chromosome;
      int start = Math.max(0, (int) drawStack.start);
      int end = (int) drawStack.end;
      try {
        for (int sampleIdx = 0; sampleIdx < SharedModel.bamFiles.size(); sampleIdx++) {
          if (sampleIdx < SharedModel.firstVisibleSample || sampleIdx > SharedModel.lastVisibleSample) continue;
          SampleFile sample = SharedModel.bamFiles.get(sampleIdx);
          if (!sample.visible) continue;
          double sampleY = masterOffset + sampleIdx * sampleH - SharedModel.scrollBarPosition;
          drawSampledCoverage(sample, chrom, start, end, sampleY, sampleH);
          for (SampleFile sub : sample.getOverlays()) {
            if (!sub.visible) continue;
            drawSampledCoverage(sub, chrom, start, end, sampleY, sampleH);
          }
        }
      } catch (Exception e) {
        System.err.println("Error drawing sampled coverage: " + e.getMessage());
      }
      return;
    }
    
    boolean coverageOnly = drawStack.viewLength > Settings.get().getMaxReadViewLength();
    
    String chrom = drawStack.chromosome;
    int start = Math.max(0, (int) drawStack.start);
    int end = (int) drawStack.end;
    
    try {
      // Compute the coverage matrix for all visible samples in one pass
      coverageDrawer.compute(drawStack, chromPosToScreenPos, (int) getWidth());

      // Coverage-only mode: CoverageDrawer renders everything (coverage + methylation + master track)
      double coverageFractionH = Math.max(MIN_COVERAGE_HEIGHT, Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));
      coverageDrawer.render(gc, getWidth(), masterOffset, sampleH, SharedModel.scrollBarPosition,
          coverageOnly, coverageFractionH);

      // In read view mode, also draw reads below the coverage area
      if (!coverageOnly) {
        for (int sampleIdx = 0; sampleIdx < SharedModel.bamFiles.size(); sampleIdx++) {
          if (sampleIdx < SharedModel.firstVisibleSample || sampleIdx > SharedModel.lastVisibleSample) continue;
          SampleFile sample = SharedModel.bamFiles.get(sampleIdx);
          if (!sample.visible) continue;
          double sampleY = masterOffset + sampleIdx * sampleH - SharedModel.scrollBarPosition;
          drawSampleFileReads(sample, chrom, start, end, sampleY, sampleH, canvasHeight, coverageFractionH);
          for (SampleFile sub : sample.getOverlays()) {
            if (!sub.visible) continue;
            drawSampleFileReads(sub, chrom, start, end, sampleY, sampleH, canvasHeight, coverageFractionH);
          }
        }
      }
    } catch (Exception e) {
      System.err.println("Error drawing BAM reads: " + e.getMessage());
    }
  }

  // Minimum read height in pixels — prevents reads from becoming invisible
  // Vertical spacing between read rows

  /**
   * Draw one SampleFile's reads (without coverage) in the given track strip.
   * Coverage and methylation are now handled by CoverageDrawer.
   * The strip is split: coverage area on top (already drawn), reads below.
   */
  private void drawSampleFileReads(SampleFile sf, String chrom, int start, int end,
                               double sampleY, double sampleH, double canvasHeight, double coverageH) {
    // Show loading indicator if this file is currently fetching for this stack
    boolean isHoverStack = (drawStack == org.baseplayer.controllers.MainController.hoverStack);
    boolean shouldShowLoading = sf.isLoading(drawStack) && (isHoverStack || !DrawFunctions.navigating);
    
    if (shouldShowLoading) {
      drawLoadingIndicator(sampleY, sampleH);
    }
    
    // Show status message in the track if available
    String status = sf.getStatusMessage();
    if (status != null && !status.isEmpty()) {
      gc.setFill(Color.rgb(200, 200, 200, 0.9));
      gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.NORMAL, 11));
      gc.fillText(status, 10, sampleY + 15);
    }

    // Reads are already cached by CoverageDrawer.compute(); just get them
    List<BAMRecord> reads = sf.getCachedReads(drawStack);
    if (reads == null || reads.isEmpty()) {
      // Fallback: try getReads (might not be cached yet)
      reads = sf.getReads(chrom, start, end, drawStack, isHoverStack);
    }
    if (reads.isEmpty()) return;

    double readsY = sampleY + coverageH;
    double readsH = sampleH - coverageH;
    boolean isMethyl = sf.isMethylationData();

    // Draw separator line between coverage and reads
    gc.setStroke(DrawColors.COVERAGE_SEPARATOR);
    gc.strokeLine(0, readsY, getWidth(), readsY);

    int maxRow = sf.getMaxRow(drawStack) + 1;
    double gap = Settings.get().getReadGap();
    int hp2Start = sf.getHP2StartRow(drawStack);
    
    // Apply read scroll offset
    double scrollOffset = sf.readScrollOffset;
    
    if (hp2Start >= 0 && sf.isHaplotypeData()) {
      // ── Allele butterfly view: HP1 grows up from middle, HP2 grows down ──
      int hp1Rows = hp2Start;
      int hp2Rows = maxRow - hp2Start;
      int maxPerHalf = Math.max(hp1Rows, hp2Rows);
      double readHeight = Math.max(Settings.get().getMinReadHeight(),
          Math.min(8, (readsH / 2 - 4) / Math.max(1, maxPerHalf)));
      double middleY = readsY + readsH / 2;

      // Draw allele middle separator line
      gc.setStroke(DrawColors.ALLELE_SEPARATOR);
      gc.setLineWidth(1.5);
      gc.strokeLine(0, middleY, getWidth(), middleY);
      gc.setLineWidth(1.0);
      // Labels
      gc.setFill(DrawColors.ALLELE_HP1_LABEL);
      gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 9));
      gc.fillText("HP1", 4, middleY - 4);
      gc.setFill(DrawColors.ALLELE_HP2_LABEL);
      gc.fillText("HP2", 4, middleY + 11);

      // Draw reads with allele-aware y positioning
      if (sf.overlay) {
        drawReadListAllele(reads, middleY, readHeight, gap, canvasHeight, hp2Start,
            DrawColors.OVERLAY_FORWARD, DrawColors.OVERLAY_REVERSE, DrawColors.OVERLAY_FORWARD_STROKE, DrawColors.OVERLAY_REVERSE_STROKE, isMethyl, sampleY + coverageH, sampleY + sampleH);
      } else {
        drawReadListAllele(reads, middleY, readHeight, gap, canvasHeight, hp2Start,
            DrawColors.READ_FORWARD, DrawColors.READ_REVERSE, DrawColors.READ_FORWARD_STROKE, DrawColors.READ_REVERSE_STROKE, isMethyl, sampleY + coverageH, sampleY + sampleH);
      }
    } else {
      // ── Normal view ──
      double readHeight = Math.max(Settings.get().getMinReadHeight(), Math.min(8, (readsH - 2) / Math.max(1, maxRow)));
    
      if (sf.overlay) {
        drawReadList(reads, readsY - scrollOffset, readHeight, gap, canvasHeight, DrawColors.OVERLAY_FORWARD, DrawColors.OVERLAY_REVERSE, DrawColors.OVERLAY_FORWARD_STROKE, DrawColors.OVERLAY_REVERSE_STROKE, isMethyl, sampleY + coverageH, sampleY + sampleH);
      } else {
        drawReadList(reads, readsY - scrollOffset, readHeight, gap, canvasHeight, DrawColors.READ_FORWARD, DrawColors.READ_REVERSE, DrawColors.READ_FORWARD_STROKE, DrawColors.READ_REVERSE_STROKE, isMethyl, sampleY + coverageH, sampleY + sampleH);
      }
    
      // Draw read group separator lines and labels
      java.util.Map<String, Integer> rgStartRows = sf.getReadGroupStartRows(drawStack);
      if (rgStartRows.size() > 1) {
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : rgStartRows.entrySet()) {
          if (first) { first = false; continue; } // skip first group (no separator above it)
          int rgRow = entry.getValue();
          double sepY = readsY + rgRow * (readHeight + gap) - gap - 1;
          if (sepY > readsY && sepY < sampleY + sampleH) {
            gc.setStroke(Color.rgb(100, 160, 200, 0.6));
            gc.setLineDashes(6, 3);
            gc.strokeLine(0, sepY, getWidth(), sepY);
            gc.setLineDashes();
            String label = entry.getKey().equals("__none__") ? "(no RG)" : entry.getKey();
            gc.setFill(Color.rgb(150, 200, 240, 0.9));
            gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 9));
            gc.fillText(label, 4, sepY + 10);
          }
        }
        // Label the first group too
        java.util.Map.Entry<String, Integer> firstEntry = rgStartRows.entrySet().iterator().next();
        String firstLabel = firstEntry.getKey().equals("__none__") ? "(no RG)" : firstEntry.getKey();
        double firstLabelY = readsY + firstEntry.getValue() * (readHeight + gap) + 10;
        gc.setFill(Color.rgb(150, 200, 240, 0.9));
        gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 9));
        gc.fillText(firstLabel, 4, firstLabelY);
      }
    }
  }

  /**
   * Draw chromosome-level sampled coverage for a sample file.
   * Used when zoomed out beyond MAX_COVERAGE_VIEW_LENGTH (>2M).
   * Requests sparse sampling from SampleFile and draws the result as a coverage profile.
   */
  private void drawSampledCoverage(SampleFile sf, String chrom, int start, int end,
                                    double sampleY, double sampleH) {
    double cw = getWidth();
    // Fixed sample points — configurable via Settings
    int numSamples = Settings.get().getSampledCoveragePoints();

    SampleFile.SampledCoverage sampled = sf.requestSampledCoverage(chrom, start, end, numSamples, drawStack);
    if (sampled == null) {
      // Show loading indicator while sampling hasn't started
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
      gc.fillText("Sampling coverage...", 10, sampleY + sampleH / 2 + 4);
      return;
    }
    
    // Show progress if still fetching (samplesCompleted == 0 but chunks being processed)
    if (sampled.samplesCompleted == 0) {
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
      if (sampled.chunksProcessed > 0) {
        gc.fillText("Sampling coverage... (" + sampled.chunksProcessed + " chunks processed)", 10, sampleY + sampleH / 2 + 4);
      } else {
        gc.fillText("Sampling coverage...", 10, sampleY + sampleH / 2 + 4);
      }
      return;
    }

    // Use smoothed depths if available, raw otherwise (progressive)
    int count = sampled.samplesCompleted;
    double maxDepth = sampled.maxDepth;
    if (maxDepth <= 0) return;

    double[] vals = sampled.smoothed != null ? sampled.smoothed : sampled.depths;
    double yBottom = sampleY + sampleH - 1;
    double scale = (sampleH - 14) / maxDepth;
    // Minimum visible height for any non-zero bin (ensures peaks in small files are always visible)
    double minVisibleHeight = Math.max(4, (sampleH - 14) * 0.08);

    // Merge smoothed and raw: use smoothed for shape but guarantee raw peaks are visible
    // If a raw depth is non-zero but smoothed is near-zero, use raw value instead
    double[] merged = new double[count];
    for (int i = 0; i < count; i++) {
      merged[i] = vals[i];
      if (sampled.depths[i] > 0 && merged[i] < sampled.depths[i] * 0.1) {
        merged[i] = sampled.depths[i]; // restore peaks that smoothing killed
      }
    }

    // Draw smooth filled polygon by linearly interpolating between sample points
    // Build screen-space x/y arrays, extending edge values to fill the view
    double[] sx = new double[count];
    double[] sy = new double[count];
    for (int i = 0; i < count; i++) {
      sx[i] = chromPosToScreenPos.apply((double) sampled.positions[i]);
      double h = merged[i] * scale;
      // Enforce minimum visible height for any non-zero depth
      if (sampled.depths[i] > 0 && h < minVisibleHeight) h = minVisibleHeight;
      sy[i] = h;
    }
    double strideX = count >= 2
        ? sx[1] - sx[0]
        : cw / count;

    // Render as filled polygon with sub-pixel interpolation
    // First pass: ensure all non-zero samples draw at least 1 pixel
    gc.setFill(DrawColors.COVERAGE_FILL);
    for (int i = 0; i < count; i++) {
      if (sampled.depths[i] > 0 && sy[i] >= 0.5) {
        double px = Math.floor(sx[i]);
        if (px >= 0 && px < cw) {
          gc.fillRect(px, yBottom - sy[i], 1, sy[i]);
        }
      }
    }

    // Second pass: smooth interpolation between samples
    double drawStart = Math.max(0, sx[0] - strideX * 0.5);
    double drawEnd = Math.min(cw, sx[count - 1] + strideX * 0.5);
    if (drawEnd > drawStart) {
      int seg = 0; // current segment index
      for (double px = drawStart; px < drawEnd; px += 1.0) {
        // Advance segment so sx[seg] <= px < sx[seg+1]
        while (seg < count - 2 && sx[seg + 1] < px) seg++;

        double h;
        if (px < sx[0]) {
          h = sy[0]; // extend first value flat to the left
        } else if (seg >= count - 1) {
          h = sy[count - 1]; // extend last value flat to the right
        } else {
          double segW = sx[seg + 1] - sx[seg];
          if (segW < 0.001) {
            h = sy[seg];
          } else {
            double t = (px - sx[seg]) / segW;
            h = sy[seg] + t * (sy[seg + 1] - sy[seg]);
          }
        }
        if (h < 0.5) continue;
        gc.fillRect(px, yBottom - h, 1, h);
      }
    }

    // Progress indicator while still loading
    if (!sampled.complete) {
      int pct = (int)(100.0 * count / sampled.numSamples);
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
      gc.fillText("Sampling " + pct + "%", 3, sampleY + sampleH - 4);
    }

    // Max depth label
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
    gc.fillText(String.valueOf((int) maxDepth), 3, sampleY + 10);
  }

  /** Animated loading dots indicator */
  private long loadingFrame = 0;

  private void drawLoadingIndicator(double sampleY, double sampleH) {
    loadingFrame++;
    int dots = (int)(loadingFrame % 4) + 1;
    String text = "Loading" + ".".repeat(dots);
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
    gc.fillText(text, 10, sampleY + sampleH / 2 + 4);
  }

  /**
   * Draw a list of BAM reads with the given colors.
   * clipTop/clipBottom define the visible region — reads outside are skipped.
   */
  private void drawReadList(List<BAMRecord> reads, double sampleY, double readHeight, double gap,
                            double canvasHeight, Color fwdFill, Color revFill, Color fwdStroke, Color revStroke,
                            boolean isMethylData, double clipTop, double clipBottom) {
    double canvasWidth = getWidth();
    for (BAMRecord read : reads) {
      double x1 = chromPosToScreenPos.apply((double) read.pos);
      double x2 = chromPosToScreenPos.apply((double) read.end);
      double width = Math.max(1, x2 - x1);
      double y = sampleY + read.row * (readHeight + gap) + 1;
      double h = readHeight >= 3 ? readHeight - gap : readHeight;

      if (y + h < clipTop || y > clipBottom) continue;
      if (x1 + width < 0 || x1 > canvasWidth) continue;

      // Check for discordant reads first
      int discordantType = read.getDiscordantType();
      Color fillColor, strokeColor;
      
      if (discordantType > 0) {
        // Use discordant colors
        switch (discordantType) {
          case 1 -> { // Inter-chromosomal — color by mate chromosome
            int ci = Math.abs(read.mateRefID) % DrawColors.INTERCHROM_FILLS.length;
            fillColor = DrawColors.INTERCHROM_FILLS[ci];
            strokeColor = DrawColors.INTERCHROM_STROKES[ci];
          }
          case 2 -> { // Deletion
            fillColor = DrawColors.DISCORDANT_DELETION;
            strokeColor = DrawColors.DISCORDANT_DELETION_STROKE;
          }
          case 3 -> { // Inversion
            fillColor = DrawColors.DISCORDANT_INVERSION;
            strokeColor = DrawColors.DISCORDANT_INVERSION_STROKE;
          }
          case 4 -> { // Duplication
            fillColor = DrawColors.DISCORDANT_DUPLICATION;
            strokeColor = DrawColors.DISCORDANT_DUPLICATION_STROKE;
          }
          default -> { // Normal (fallback)
            if (read.isReverseStrand()) {
              fillColor = revFill;
              strokeColor = revStroke;
            } else {
              fillColor = fwdFill;
              strokeColor = fwdStroke;
            }
          }
        }
      } else {
        // Use normal forward/reverse colors
        if (read.isReverseStrand()) {
          fillColor = revFill;
          strokeColor = revStroke;
        } else {
          fillColor = fwdFill;
          strokeColor = fwdStroke;
        }
      }

      gc.setFill(fillColor);
      gc.setStroke(strokeColor);

      gc.fillRect(x1, y, width, h);
      if (readHeight >= 3) {
        gc.strokeRect(x1, y, width, h);
      }

      // Draw mismatches on top of the read
      MismatchRenderer.drawMismatches(gc, read.mismatches, y, h, canvasWidth, chromPosToScreenPos,
          isMethylData, read.isReverseStrand());
    }
  }

  /**
   * Draw reads in allele butterfly layout.
   * HP1 reads (row < hp2StartRow) grow upward from middleY.
   * HP2+unphased reads (row >= hp2StartRow) grow downward from middleY.
   */
  private void drawReadListAllele(List<BAMRecord> reads, double middleY, double readHeight, double gap,
                                  double canvasHeight, int hp2StartRow,
                                  Color fwdFill, Color revFill, Color fwdStroke, Color revStroke,
                                  boolean isMethylData, double clipTop, double clipBottom) {
    double canvasWidth = getWidth();
    for (BAMRecord read : reads) {
      double x1 = chromPosToScreenPos.apply((double) read.pos);
      double x2 = chromPosToScreenPos.apply((double) read.end);
      double width = Math.max(1, x2 - x1);
      double h = readHeight >= 3 ? readHeight - gap : readHeight;

      // Butterfly y positioning
      double y;
      if (read.row < hp2StartRow) {
        // HP1: grow upward from middle line
        y = middleY - (read.row + 1) * (readHeight + gap);
      } else {
        // HP2/unphased: grow downward from middle line
        y = middleY + (read.row - hp2StartRow) * (readHeight + gap) + 1;
      }

      if (y + h < clipTop || y > clipBottom) continue;
      if (x1 + width < 0 || x1 > canvasWidth) continue;
      int discordantType = read.getDiscordantType();
      Color fillColor, strokeColor;
      if (discordantType > 0) {
        switch (discordantType) {
          case 1 -> { int ci = Math.abs(read.mateRefID) % DrawColors.INTERCHROM_FILLS.length; fillColor = DrawColors.INTERCHROM_FILLS[ci]; strokeColor = DrawColors.INTERCHROM_STROKES[ci]; }
          case 2 -> { fillColor = DrawColors.DISCORDANT_DELETION; strokeColor = DrawColors.DISCORDANT_DELETION_STROKE; }
          case 3 -> { fillColor = DrawColors.DISCORDANT_INVERSION; strokeColor = DrawColors.DISCORDANT_INVERSION_STROKE; }
          case 4 -> { fillColor = DrawColors.DISCORDANT_DUPLICATION; strokeColor = DrawColors.DISCORDANT_DUPLICATION_STROKE; }
          default -> {
            if (read.isReverseStrand()) { fillColor = revFill; strokeColor = revStroke; }
            else { fillColor = fwdFill; strokeColor = fwdStroke; }
          }
        }
      } else {
        if (read.isReverseStrand()) { fillColor = revFill; strokeColor = revStroke; }
        else { fillColor = fwdFill; strokeColor = fwdStroke; }
      }

      gc.setFill(fillColor);
      gc.setStroke(strokeColor);
      gc.fillRect(x1, y, width, h);
      if (readHeight >= 3) {
        gc.strokeRect(x1, y, width, h);
      }
      MismatchRenderer.drawMismatches(gc, read.mismatches, y, h, canvasWidth, chromPosToScreenPos,
          isMethylData, read.isReverseStrand());
    }
  }

}
