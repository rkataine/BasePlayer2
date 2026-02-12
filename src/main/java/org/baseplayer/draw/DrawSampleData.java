package org.baseplayer.draw;

import java.util.List;

import org.baseplayer.SharedModel;
import org.baseplayer.io.Settings;
import org.baseplayer.reads.bam.BAMRecord;
import org.baseplayer.reads.bam.SampleFile;
import org.baseplayer.variant.Variant;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

public class DrawSampleData extends DrawFunctions {
  
  public Image snapshot;
  private GraphicsContext gc;

  // Colors for BAM read rendering
  private static final Color READ_FORWARD = Color.rgb(120, 160, 200, 0.85);
  private static final Color READ_REVERSE = Color.rgb(200, 120, 130, 0.85);
  private static final Color READ_FORWARD_STROKE = Color.rgb(90, 130, 170);
  private static final Color READ_REVERSE_STROKE = Color.rgb(170, 90, 100);

  // Overlay colors (slightly different tint)
  private static final Color OVERLAY_FORWARD = Color.rgb(160, 200, 140, 0.7);
  private static final Color OVERLAY_REVERSE = Color.rgb(200, 180, 100, 0.7);
  private static final Color OVERLAY_FORWARD_STROKE = Color.rgb(130, 170, 110);
  private static final Color OVERLAY_REVERSE_STROKE = Color.rgb(170, 150, 70);

  // Coverage view constants
  private static final double MIN_COVERAGE_HEIGHT = 30;
  private static final double MAX_COVERAGE_HEIGHT = 60;
  private static final Color COVERAGE_FILL = Color.rgb(100, 140, 180, 0.50);
  private static final Color COVERAGE_SEPARATOR = Color.rgb(80, 80, 80, 0.60);

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
    gc.setFill(backgroundColor);
    gc.fillRect(0, 0, getWidth()+1, getHeight()+1);
    if (drawStack.variants != null) drawVariants();
    drawBamReads();
    super.draw();
  }
  void drawVariants() {    
    for (Variant variant : drawStack.variants) {
      if (variant.line.getEndX() < drawStack.start-1) continue;
      if (variant.line.getStartX() > drawStack.end) break;

      drawLine(variant, lineColor, gc);    
    }
  }
  void drawLine(Variant variant, Color color, GraphicsContext gc) {
    if (variant.index < SharedModel.firstVisibleSample || variant.index > SharedModel.lastVisibleSample + 1) return;
   
    gc.setStroke(color);
    gc.setFill(color);
    double screenPos = chromPosToScreenPos.apply(variant.line.getStartX());
    double ypos = SharedModel.MASTER_TRACK_HEIGHT + SharedModel.sampleHeight * variant.index - SharedModel.scrollBarPosition;
    double height = heightToScreen.apply(variant.line.getEndY());
    
    if (drawStack.pixelSize > 1) 
         gc.fillRect(screenPos, ypos - height, drawStack.pixelSize, height);
    else gc.strokeLine(screenPos, ypos, screenPos, ypos-height);
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
    
    double masterOffset = SharedModel.MASTER_TRACK_HEIGHT;
    double availableHeight = canvasHeight - masterOffset;
    double sampleH = availableHeight / Math.max(1, SharedModel.visibleSamples().getAsInt());
    SharedModel.sampleHeight = sampleH;
    
    // Don't query when zoomed out beyond coverage range — use sampled coverage
    if (drawStack.viewLength > Settings.get().getMaxCoverageViewLength()) {
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
      for (int sampleIdx = 0; sampleIdx < SharedModel.bamFiles.size(); sampleIdx++) {
        if (sampleIdx < SharedModel.firstVisibleSample || sampleIdx > SharedModel.lastVisibleSample) continue;
        
        SampleFile sample = SharedModel.bamFiles.get(sampleIdx);
        if (!sample.visible) continue;
        
        double sampleY = masterOffset + sampleIdx * sampleH - SharedModel.scrollBarPosition;
        
        if (coverageOnly) {
          // Between read view and coverage view limit: coverage-only, full height
          drawCoverageOnly(sample, chrom, start, end, sampleY, sampleH);
        } else {
          // Draw main sample reads (with split coverage + reads)
          drawSampleFile(sample, chrom, start, end, sampleY, sampleH, canvasHeight);
        }
        
        // Draw additional data files on the same track
        for (SampleFile sub : sample.getOverlays()) {
          if (!sub.visible) continue;
          if (coverageOnly) {
            drawCoverageOnly(sub, chrom, start, end, sampleY, sampleH);
          } else {
            drawSampleFile(sub, chrom, start, end, sampleY, sampleH, canvasHeight);
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
   * Draw one SampleFile's reads in the given track strip, choosing colors based on its overlay flag.
   * The strip is split: coverage on top, reads below.
   */
  private void drawSampleFile(SampleFile sf, String chrom, int start, int end,
                               double sampleY, double sampleH, double canvasHeight) {
    // Show loading indicator if this file is currently fetching for this stack
    boolean isHoverStack = (drawStack == org.baseplayer.controllers.MainController.hoverStack);
    boolean shouldShowLoading = sf.isLoading(drawStack) && (isHoverStack || !DrawFunctions.navigating);
    
    if (shouldShowLoading) {
      drawLoadingIndicator(sampleY, sampleH);
    }

    // Pass drawStack so SampleFile caches per-stack; allow non-hover stacks to fetch during navigation
    List<BAMRecord> reads = sf.getReads(chrom, start, end, drawStack, isHoverStack);
    if (reads.isEmpty()) return;

    // Split area: coverage on top, reads below
    double coverageH = Math.max(MIN_COVERAGE_HEIGHT, Math.min(MAX_COVERAGE_HEIGHT, sampleH * Settings.get().getCoverageFraction()));
    double readsY = sampleY + coverageH;
    double readsH = sampleH - coverageH;

    // Draw coverage track with mismatches
    drawCoverage(reads, start, end, sampleY, coverageH);

    // Draw separator line between coverage and reads
    gc.setStroke(COVERAGE_SEPARATOR);
    gc.strokeLine(0, readsY, getWidth(), readsY);

    int maxRow = sf.getMaxRow(drawStack) + 1;
    double readHeight = Math.max(Settings.get().getMinReadHeight(), Math.min(8, (readsH - 2) / Math.max(1, maxRow)));
    
    if (sf.overlay) {
      drawReadList(reads, readsY, readHeight, Settings.get().getReadGap(), canvasHeight, OVERLAY_FORWARD, OVERLAY_REVERSE, OVERLAY_FORWARD_STROKE, OVERLAY_REVERSE_STROKE);
    } else {
      drawReadList(reads, readsY, readHeight, Settings.get().getReadGap(), canvasHeight, READ_FORWARD, READ_REVERSE, READ_FORWARD_STROKE, READ_REVERSE_STROKE);
    }
  }

  /**
   * Draw coverage histogram with mismatch base colors for a set of reads.
   * Uses sweep-line for O(reads + positions) coverage computation and bins
   * positions by pixel when zoomed out for efficient rendering.
   */
  private void drawCoverage(List<BAMRecord> reads, int start, int end,
                            double coverageY, double coverageH) {
    int regionLen = end - start + 2;
    if (regionLen <= 0 || regionLen > 200_000) return;

    // Sweep-line events for O(reads) coverage accumulation
    int[] events = new int[regionLen + 2];
    // Per-position mismatch base counts
    int[] mmA = new int[regionLen];
    int[] mmC = new int[regionLen];
    int[] mmG = new int[regionLen];
    int[] mmT = new int[regionLen];

    for (BAMRecord read : reads) {
      int r1 = Math.max(0, read.pos - start);
      int r2 = Math.min(regionLen - 1, read.end - start);
      if (r2 < 0 || r1 >= regionLen) continue;
      events[r1]++;
      if (r2 + 1 <= regionLen) events[r2 + 1]--;

      if (read.mismatches != null) {
        for (int m = 0; m < read.mismatches.length; m += 2) {
          int idx = read.mismatches[m] - start;
          if (idx < 0 || idx >= regionLen) continue;
          switch (Character.toUpperCase((char) read.mismatches[m + 1])) {
            case 'A' -> mmA[idx]++;
            case 'C' -> mmC[idx]++;
            case 'G' -> mmG[idx]++;
            case 'T' -> mmT[idx]++;
          }
        }
      }
    }

    // Build coverage array from events
    int[] coverage = new int[regionLen];
    int maxCov = 0, running = 0;
    for (int i = 0; i < regionLen; i++) {
      running += events[i];
      coverage[i] = Math.max(0, running);
      if (coverage[i] > maxCov) maxCov = coverage[i];
    }
    if (maxCov == 0) return;

    double yBottom = coverageY + coverageH - 1;
    double scale = (coverageH - 14) / maxCov; // margin for label at top
    double cw = getWidth();

    // When zoomed out, bin multiple positions per pixel for efficient drawing
    int step = Math.max(1, (int) (1.0 / drawStack.pixelSize));

    // Draw coverage fill
    gc.setFill(COVERAGE_FILL);
    for (int i = 0; i < regionLen; i += step) {
      int cov = coverage[i];
      for (int j = 1; j < step && i + j < regionLen; j++) {
        cov = Math.max(cov, coverage[i + j]);
      }
      if (cov == 0) continue;

      double x1 = chromPosToScreenPos.apply((double) (start + i));
      double x2 = chromPosToScreenPos.apply((double) (start + i + step));
      if (x1 > cw) break;
      double w = Math.max(1, x2 - x1);
      double h = cov * scale;
      gc.fillRect(x1, yBottom - h, w, h);
    }

    // Draw mismatch base colors as stacked bars from bottom of coverage
    double barW = Math.max(1, drawStack.pixelSize);
    for (int i = 0; i < regionLen; i++) {
      int totalMM = mmA[i] + mmC[i] + mmG[i] + mmT[i];
      if (totalMM == 0) continue;

      double x = chromPosToScreenPos.apply((double) (start + i));
      if (x + barW < 0) continue;
      if (x > cw) break;

      double baseY = yBottom;
      if (mmT[i] > 0) {
        double h = mmT[i] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_T);
        gc.fillRect(x, baseY - h, barW, h);
        baseY -= h;
      }
      if (mmG[i] > 0) {
        double h = mmG[i] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_G);
        gc.fillRect(x, baseY - h, barW, h);
        baseY -= h;
      }
      if (mmC[i] > 0) {
        double h = mmC[i] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_C);
        gc.fillRect(x, baseY - h, barW, h);
        baseY -= h;
      }
      if (mmA[i] > 0) {
        double h = mmA[i] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_A);
        gc.fillRect(x, baseY - h, barW, h);
      }
    }

    // Draw max coverage label
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
    gc.fillText(String.valueOf(maxCov), 3, coverageY + 10);
  }

  /**
   * Draw coverage-only view for a sample file, expanded to fill the full track height.
   * Used when zoomed out beyond individual-read range but within coverage range.
   * Bins are computed in genomic coordinates and cached per-file so that horizontal
   * panning just re-renders from cache without recomputing smoothing.
   */
  private void drawCoverageOnly(SampleFile sf, String chrom, int start, int end,
                                double sampleY, double sampleH) {
    boolean isHoverStack = (drawStack == org.baseplayer.controllers.MainController.hoverStack);
    boolean shouldShowLoading = sf.isLoading(drawStack) && (isHoverStack || !DrawFunctions.navigating);
    if (shouldShowLoading) {
      drawLoadingIndicator(sampleY, sampleH);
    }

    List<BAMRecord> reads = sf.getReads(chrom, start, end, drawStack, isHoverStack, true);
    if (reads.isEmpty()) return;

    double cw = getWidth();
    int viewLen = end - start;
    if (viewLen <= 0) return;
    double binSize = (double) viewLen / cw;

    // Check if cached data is still valid (cache is per-file, per-DrawStack)
    SampleFile.CoverageCache cache = sf.getCoverageCache(drawStack);
    int cacheGenomicEnd = cache != null ? (int)(cache.genomicStart + cache.numBins * cache.binSize) : 0;
    boolean cacheValid = cache != null
        && cache.sourceReads == reads
        && Math.abs(cache.binSize - binSize) / binSize < 0.01
        && start >= cache.genomicStart
        && end <= cacheGenomicEnd;

    if (!cacheValid) {
      // Compute bins in genomic coordinates with buffer for panning headroom
      int buffer = viewLen / 2;
      int binStart = Math.max(0, start - buffer);
      int binEnd = end + buffer;
      int numBins = Math.max(1, (int)((binEnd - binStart) / binSize) + 1);

      double[] binCov = new double[numBins];
      double[] mmA = new double[numBins];
      double[] mmC = new double[numBins];
      double[] mmG = new double[numBins];
      double[] mmT = new double[numBins];

      for (BAMRecord read : reads) {
        int b1 = Math.max(0, (int)((read.pos - binStart) / binSize));
        int b2 = Math.min(numBins - 1, (int)((read.end - binStart) / binSize));
        for (int b = b1; b <= b2; b++) binCov[b]++;
        if (read.mismatches != null) {
          for (int m = 0; m < read.mismatches.length; m += 2) {
            int bin = (int)((read.mismatches[m] - binStart) / binSize);
            if (bin < 0 || bin >= numBins) continue;
            switch (Character.toUpperCase((char) read.mismatches[m + 1])) {
              case 'A' -> mmA[bin]++;
              case 'C' -> mmC[bin]++;
              case 'G' -> mmG[bin]++;
              case 'T' -> mmT[bin]++;
            }
          }
        }
      }

      // Find raw max before smoothing
      double maxRaw = 0;
      for (double c : binCov) if (c > maxRaw) maxRaw = c;

      smoothBins(binCov);

      double maxSmoothed = 0;
      for (double c : binCov) if (c > maxSmoothed) maxSmoothed = c;

      cache = new SampleFile.CoverageCache();
      cache.smoothedCov = binCov;
      cache.rawMmA = mmA;
      cache.rawMmC = mmC;
      cache.rawMmG = mmG;
      cache.rawMmT = mmT;
      cache.genomicStart = binStart;
      cache.binSize = binSize;
      cache.numBins = numBins;
      cache.scaleMax = Math.max(maxSmoothed, maxRaw);
      cache.sourceReads = reads;
      sf.setCoverageCache(drawStack, cache);
    }

    if (cache.scaleMax == 0) return;

    // Render from cache — map genomic bins to screen coordinates
    double yBottom = sampleY + sampleH - 1;
    double scale = (sampleH - 14) / cache.scaleMax;

    // Only iterate bins visible on screen
    int firstBin = Math.max(0, (int)((start - cache.genomicStart) / cache.binSize) - 1);
    int lastBin = Math.min(cache.numBins - 1, (int)((end - cache.genomicStart) / cache.binSize) + 1);

    // Draw smoothed coverage
    gc.setFill(COVERAGE_FILL);
    for (int b = firstBin; b <= lastBin; b++) {
      if (cache.smoothedCov[b] < 0.5) continue;
      double gpos = cache.genomicStart + b * cache.binSize;
      double x = chromPosToScreenPos.apply(gpos);
      double x2 = chromPosToScreenPos.apply(gpos + cache.binSize);
      if (x2 < 0) continue;
      if (x > cw) break;
      double w = Math.max(1, x2 - x);
      double h = cache.smoothedCov[b] * scale;
      gc.fillRect(x, yBottom - h, w, h);
    }

    // Draw raw mismatch bars at correct allelic fraction
    for (int b = firstBin; b <= lastBin; b++) {
      double totalMM = cache.rawMmA[b] + cache.rawMmC[b] + cache.rawMmG[b] + cache.rawMmT[b];
      if (totalMM < 0.5) continue;
      double gpos = cache.genomicStart + b * cache.binSize;
      double x = chromPosToScreenPos.apply(gpos);
      double x2 = chromPosToScreenPos.apply(gpos + cache.binSize);
      if (x2 < 0) continue;
      if (x > cw) break;
      double w = Math.max(1, x2 - x);

      double baseY = yBottom;
      if (cache.rawMmT[b] >= 0.5) {
        double h = cache.rawMmT[b] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_T);
        gc.fillRect(x, baseY - h, w, h);
        baseY -= h;
      }
      if (cache.rawMmG[b] >= 0.5) {
        double h = cache.rawMmG[b] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_G);
        gc.fillRect(x, baseY - h, w, h);
        baseY -= h;
      }
      if (cache.rawMmC[b] >= 0.5) {
        double h = cache.rawMmC[b] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_C);
        gc.fillRect(x, baseY - h, w, h);
        baseY -= h;
      }
      if (cache.rawMmA[b] >= 0.5) {
        double h = cache.rawMmA[b] * scale;
        gc.setFill(MismatchRenderer.MISMATCH_A);
        gc.fillRect(x, baseY - h, w, h);
      }
    }

    // Max coverage label
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
    gc.fillText(String.valueOf((int) cache.scaleMax), 3, sampleY + 10);
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
    if (sampled == null || sampled.samplesCompleted == 0) {
      // Show loading indicator while sampling hasn't started
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
      gc.fillText("Sampling coverage...", 10, sampleY + sampleH / 2 + 4);
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
    gc.setFill(COVERAGE_FILL);
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
   */
  private void drawReadList(List<BAMRecord> reads, double sampleY, double readHeight, double gap,
                            double canvasHeight, Color fwdFill, Color revFill, Color fwdStroke, Color revStroke) {
    double canvasWidth = getWidth();
    for (BAMRecord read : reads) {
      double x1 = chromPosToScreenPos.apply((double) read.pos);
      double x2 = chromPosToScreenPos.apply((double) read.end);
      double width = Math.max(1, x2 - x1);
      double y = sampleY + read.row * (readHeight + gap) + 1;
      double h = readHeight >= 3 ? readHeight - gap : readHeight;

      if (y + readHeight < 0 || y > canvasHeight) continue;
      if (x1 + width < 0 || x1 > canvasWidth) continue;

      if (read.isReverseStrand()) {
        gc.setFill(revFill);
        gc.setStroke(revStroke);
      } else {
        gc.setFill(fwdFill);
        gc.setStroke(fwdStroke);
      }

      gc.fillRect(x1, y, width, h);
      if (readHeight >= 3) {
        gc.strokeRect(x1, y, width, h);
      }

      // Draw mismatches on top of the read
      MismatchRenderer.drawMismatches(gc, read.mismatches, y, h, canvasWidth, chromPosToScreenPos);
    }
  }

  /**
   * In-place Gaussian-like smoothing (3-pass box blur for approximation).
   * Radius adapts to array length for consistent visual smoothness.
   */
  private static void smoothBins(double[] bins) {
    int n = bins.length;
    if (n < 5) return;
    int radius = Math.max(1, Math.min(8, n / 80));
    double[] tmp = new double[n];
    for (int pass = 0; pass < 3; pass++) {
      int window = 2 * radius + 1;
      double sum = 0;
      // Seed with left edge
      for (int i = 0; i <= radius && i < n; i++) sum += bins[i];
      for (int i = 0; i < n; i++) {
        int right = i + radius;
        int left = i - radius - 1;
        if (right < n) sum += bins[right];
        if (left >= 0) sum -= bins[left];
        int count = Math.min(right, n - 1) - Math.max(left + 1, 0) + 1;
        tmp[i] = sum / count;
      }
      System.arraycopy(tmp, 0, bins, 0, n);
    }
  }

}
