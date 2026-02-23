package org.baseplayer.alignment.draw;

import java.util.List;
import java.util.function.Function;

import org.baseplayer.alignment.AlignmentFile;
import org.baseplayer.alignment.BAMRecord;
import org.baseplayer.alignment.CoverageCalculator;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.Settings;
import org.baseplayer.sample.Sample;
import org.baseplayer.sample.SampleTrack;
import org.baseplayer.services.ReferenceGenomeService;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.DrawColors;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Unified coverage data computation and drawing.
 * <p>
 * Builds a per-sample matrix of coverage values (coverage, mismatches, methylation)
 * in a single left-to-right pass through the reads. Then a single rendering pass
 * draws both individual sample tracks and the master track methylation lines,
 * iterating the matrix only once from left to right.
 */
public class CoverageDrawer {

  // ── Per-sample data row in the matrix ──
  /**
   * Holds all computed coverage data for one sample at screen-pixel resolution.
   * Values are indexed by pixel column (0 .. numColumns-1).
   */
  public static class SampleRow {
    public final Sample sample;
    public final int sampleIndex;
    /** Total coverage per pixel column */
    public final double[] coverage;
    /** Mismatch counts per base per column */
    public final double[] mmA, mmC, mmG, mmT;
    /** Methylation ratio per column (0-1, or -1 = no data) */
    public final double[] methylRatio;
    /** Smoothed methylation ratio for line drawing */
    public final double[] smoothedMethylRatio;
    /** Max coverage value (for scale) */
    public double maxCoverage;
    /** Color index for master track */
    public int methylColorIndex;
    /** True if this row is only for the master track (sample not in visible range) */
    public boolean masterTrackOnly;

    SampleRow(Sample sample, int sampleIndex, int numColumns) {
      this.sample = sample;
      this.sampleIndex = sampleIndex;
      this.coverage = new double[numColumns];
      this.mmA = new double[numColumns];
      this.mmC = new double[numColumns];
      this.mmG = new double[numColumns];
      this.mmT = new double[numColumns];
      this.methylRatio = new double[numColumns];
      this.smoothedMethylRatio = new double[numColumns];
      java.util.Arrays.fill(methylRatio, -1);
      java.util.Arrays.fill(smoothedMethylRatio, -1);
    }
  }

  // ── The matrix: one row per sample ──
  private SampleRow[] rows;
  private int numColumns; // = screen width in pixels

  // ── Context for coordinate mapping ──
  private DrawStack drawStack;
  private Function<Double, Double> chromPosToScreenPos;
  
  // ── Services ──
  private final SampleRegistry sampleRegistry;
  private final ReferenceGenomeService referenceGenomeService;

  // ── Sub-drawers ──
  private final SashimiDrawer sashimiDrawer = new SashimiDrawer();
  
  public CoverageDrawer() {
    ServiceRegistry services = ServiceRegistry.getInstance();
    this.sampleRegistry = services.getSampleRegistry();
    this.referenceGenomeService = services.getReferenceGenomeService();
  }

  /**
   * Populate the coverage matrix from reads.
   * Handles both base-level (from BAMRecord lists) and binned (coverage-only) modes.
   *
   * @param drawStack        current view parameters
   * @param chromPosToScreenPos coordinate mapping function
   * @param screenWidth      canvas width in pixels
   */
  public void compute(DrawStack drawStack, Function<Double, Double> chromPosToScreenPos,
                      int screenWidth) {
    this.drawStack = drawStack;
    this.chromPosToScreenPos = chromPosToScreenPos;
    this.numColumns = Math.max(1, screenWidth);

    if (sampleRegistry.getSampleTracks().isEmpty()) { rows = new SampleRow[0]; return; }

    String chrom = drawStack.chromosome;
    int start = Math.max(0, (int) drawStack.start);
    int end = (int) drawStack.end;
    boolean coverageOnly = drawStack.viewLength > Settings.get().getMaxReadViewLength();
    boolean isHoverStack = (drawStack == ServiceRegistry.getInstance().getDrawStackManager().getHoverStack());

    // Count visible methylation samples for color assignment
    int methylColorIdx = 0;

    // Build one SampleRow per visible BAM file
    java.util.List<SampleRow> rowList = new java.util.ArrayList<>();
    java.util.Set<Sample> processedFiles = new java.util.HashSet<>();
    for (int sIdx = 0; sIdx < sampleRegistry.getSampleTracks().size(); sIdx++) {
      if (sIdx < sampleRegistry.getFirstVisibleSample() || sIdx > sampleRegistry.getLastVisibleSample()) continue;
      SampleTrack track = sampleRegistry.getSampleTracks().get(sIdx);

      for (Sample sf : track.getSamples()) {
        if (!sf.visible) continue;
        processedFiles.add(sf);

        SampleRow row = buildRow(sf, sIdx, chrom, start, end, coverageOnly, isHoverStack);
        if (row != null) {
          if (sf.isMethylationData()) {
            row.methylColorIndex = methylColorIdx++;
          }
          rowList.add(row);
        }
      }
    }

    // Also build rows for non-visible methylation samples (master track only)
    for (int sIdx = 0; sIdx < sampleRegistry.getSampleTracks().size(); sIdx++) {
      if (sIdx >= sampleRegistry.getFirstVisibleSample() && sIdx <= sampleRegistry.getLastVisibleSample()) continue;
      SampleTrack track = sampleRegistry.getSampleTracks().get(sIdx);

      for (Sample sf : track.getSamples()) {
        if (!sf.visible || !sf.isMethylationData() || processedFiles.contains(sf)) continue;

        SampleRow row = buildRow(sf, sIdx, chrom, start, end, coverageOnly, isHoverStack);
        if (row != null) {
          row.methylColorIndex = methylColorIdx++;
          row.masterTrackOnly = true;
          rowList.add(row);
        }
      }
    }
    rows = rowList.toArray(SampleRow[]::new);
  }

  /**
   * Build one SampleRow by computing coverage, mismatches, and methylation
   * from the sample's reads in a single pass.
   */
  private SampleRow buildRow(Sample sample, int sampleIndex, String chrom,
                             int start, int end, boolean coverageOnly, boolean isHoverStack) {
    boolean isMethyl = sample.isMethylationData();

    if (coverageOnly) {
      return buildRowCoverageOnly(sample, sampleIndex, chrom, start, end, isMethyl, isHoverStack);
    } else {
      return buildRowFromReads(sample, sampleIndex, chrom, start, end, isMethyl, isHoverStack);
    }
  }

  /**
   * Build a SampleRow from individual reads (zoomed-in, base-level resolution).
   * <p>
   * Coverage and mismatches are accumulated directly at pixel resolution using a
   * pixel-level sweep-line — no intermediate regionLen-sized arrays.  For methylation
   * samples, a small base-level sweep-line and bisConv array are kept so per-CpG
   * ratios remain accurate.
   */
  private SampleRow buildRowFromReads(Sample sample, int sampleIndex, String chrom,
                                      int start, int end, boolean isMethyl, boolean isHoverStack) {
    AlignmentFile bamFile = sample.getBamFile();
    if (bamFile == null) return null;
    List<BAMRecord> reads = bamFile.getReads(chrom, start, end, drawStack, isHoverStack);
    if (reads.isEmpty()) return null;

    int regionLen = end - start + 2;
    if (regionLen <= 0 || regionLen > 200_000) return null;

    SampleRow row = new SampleRow(sample, sampleIndex, numColumns);

    // ── Pixel-level coverage sweep-line (replaces regionLen-sized events[]) ──
    int[] pixEvents = new int[numColumns + 2];

    // For methylation only: per-position data needed for accurate per-CpG ratios
    int[] baseEvents = isMethyl ? new int[regionLen + 2] : null;
    int[] bisConv    = isMethyl ? new int[regionLen]      : null;

    // Single pass through reads
    for (BAMRecord read : reads) {
      // Map read span to pixel columns for coverage
      int px1 = Math.max(0, (int)(double) chromPosToScreenPos.apply((double) read.pos));
      int px2 = Math.min(numColumns, (int)(double) chromPosToScreenPos.apply((double) read.end));
      if (px1 < numColumns && px2 >= 0) {
        pixEvents[px1]++;
        if (px2 + 1 <= numColumns) pixEvents[px2 + 1]--;
      }

      // Base-level events for methylation
      if (isMethyl) {
        int r1 = Math.max(0, read.pos - start);
        int r2 = Math.min(regionLen - 1, read.end - start);
        if (r2 >= 0 && r1 < regionLen) {
          baseEvents[r1]++;
          if (r2 + 1 <= regionLen) baseEvents[r2 + 1]--;
        }
      }

      // Mismatches: map directly to pixel columns (no intermediate arrays)
      if (read.mismatches != null) {
        for (int m = 0; m + 2 < read.mismatches.length; m += 3) {
          int mPos = read.mismatches[m];

          // Bisulfite conversions tracked per position for methylation
          if (isMethyl && read.mismatches[m + 2] > 0) {
            char readB = Character.toUpperCase((char) read.mismatches[m + 1]);
            char refB  = Character.toUpperCase((char) read.mismatches[m + 2]);
            if ((refB == 'C' && readB == 'T') || (refB == 'G' && readB == 'A')) {
              int idx = mPos - start;
              if (idx >= 0 && idx < regionLen) bisConv[idx]++;
              continue;
            }
          }

          int px = (int)(double) chromPosToScreenPos.apply((double) mPos);
          if (px < 0 || px >= numColumns) continue;
          switch (Character.toUpperCase((char) read.mismatches[m + 1])) {
            case 'A' -> row.mmA[px]++;
            case 'C' -> row.mmC[px]++;
            case 'G' -> row.mmG[px]++;
            case 'T' -> row.mmT[px]++;
          }
        }
      }
    }

    // Convert pixel events → running coverage
    double maxCov = 0;
    int running = 0;
    for (int px = 0; px < numColumns; px++) {
      running += pixEvents[px];
      row.coverage[px] = Math.max(0, running);
      if (row.coverage[px] > maxCov) maxCov = row.coverage[px];
    }
    // Don't create row if coverage is effectively zero (below rendering threshold)
    if (maxCov < 0.5) return null;
    row.maxCoverage = maxCov;

    // ── Methylation: per-position ratios binned to pixels ──
    if (isMethyl) {
      String refBases = null;
      if (referenceGenomeService.hasGenome()) {
        refBases = referenceGenomeService.getBases(chrom, Math.max(1, start), start + regionLen - 1);
        if (refBases != null && refBases.isEmpty()) refBases = null;
      }

      // Build per-base coverage from base events
      int[] baseCov = new int[regionLen];
      int run = 0;
      for (int i = 0; i < regionLen; i++) {
        run += baseEvents[i];
        baseCov[i] = Math.max(0, run);
      }

      // Bin methylation ratios to pixels
      double[] methylSum = new double[numColumns];
      int[] mCnt = new int[numColumns];
      for (int i = 0; i < regionLen; i++) {
        if (baseCov[i] < 3) continue;
        if (refBases != null && i < refBases.length()) {
          char base = Character.toUpperCase(refBases.charAt(i));
          if (base == 'A' || base == 'T') continue;
        }
        double ratio = 1.0 - (double) bisConv[i] / baseCov[i];
        ratio = Math.max(0, Math.min(1, ratio));
        int px = (int)(double) chromPosToScreenPos.apply((double) (start + i));
        if (px >= 0 && px < numColumns) {
          methylSum[px] += ratio;
          mCnt[px]++;
        }
      }
      for (int p = 0; p < numColumns; p++) {
        row.methylRatio[p] = mCnt[p] > 0 ? methylSum[p] / mCnt[p] : -1;
      }

      // At close zoom (high pixelSize), fill gaps between mapped positions
      if (drawStack.pixelSize > 1.5) {
        for (int p = 0; p < numColumns - 1; p++) {
          if (row.methylRatio[p] >= 0) {
            int nextValid = -1;
            for (int j = p + 1; j < Math.min(p + (int)(drawStack.pixelSize * 2), numColumns); j++) {
              if (row.methylRatio[j] >= 0) { nextValid = j; break; }
            }
            if (nextValid > p + 1) {
              for (int j = p + 1; j < nextValid; j++) {
                row.methylRatio[j] = row.methylRatio[p];
              }
            }
          }
        }
      }

      // Smooth for line drawing
      System.arraycopy(row.methylRatio, 0, row.smoothedMethylRatio, 0, numColumns);
      smoothMethylRatio(row.smoothedMethylRatio);
    }

    return row;
  }

  /**
   * Build a SampleRow for coverage-only mode (zoomed out, binned).
   * Uses cached bin data when available, or computes from reads.
   */
  private SampleRow buildRowCoverageOnly(Sample sample, int sampleIndex, String chrom,
                                         int start, int end, boolean isMethyl, boolean isHoverStack) {
    AlignmentFile bamFile = sample.getBamFile();
    if (bamFile == null) return null;

    double cw = numColumns;
    int viewLen = end - start;
    if (viewLen <= 0) return null;
    double binSize = (double) viewLen / cw;

    List<BAMRecord> reads = bamFile.getReads(chrom, start, end, drawStack, isHoverStack, true);
    if (reads.isEmpty()) return null;

    // Check if cached data is still valid
    AlignmentFile.CoverageCache cache = bamFile.getCoverageCache(drawStack);
    int cacheGenomicEnd = cache != null ? (int)(cache.genomicStart + cache.numBins * cache.binSize) : 0;
    boolean cacheValid = cache != null
        && cache.sourceReads == reads
        && Math.abs(cache.binSize - binSize) / binSize < 0.01
        && start >= cache.genomicStart
        && end <= cacheGenomicEnd;

    if (!cacheValid) {
      cache = computeCoverageCache(reads, start, end, viewLen, binSize, isMethyl, chrom);
      bamFile.setCoverageCache(drawStack, cache);
    }

    // Don't create row if coverage is effectively zero (below rendering threshold)
    if (cache == null || cache.scaleMax < 0.5) return null;

    // Map cached bins to SampleRow at screen-pixel resolution
    SampleRow row = new SampleRow(sample, sampleIndex, numColumns);
    row.maxCoverage = cache.scaleMax;

    int firstBin = Math.max(0, (int)((start - cache.genomicStart) / cache.binSize) - 1);
    int lastBin = Math.min(cache.numBins - 1, (int)((end - cache.genomicStart) / cache.binSize) + 1);

    for (int b = firstBin; b <= lastBin; b++) {
      double gpos = cache.genomicStart + b * cache.binSize;
      double x = chromPosToScreenPos.apply(gpos);
      int px = (int) x;
      if (px < 0) continue;
      if (px >= numColumns) break;

      row.coverage[px] = Math.max(row.coverage[px], cache.smoothedCov[b]);
      row.mmA[px] += cache.rawMmA[b];
      row.mmC[px] += cache.rawMmC[b];
      row.mmG[px] += cache.rawMmG[b];
      row.mmT[px] += cache.rawMmT[b];
    }

    // Map methylation from cache
    if (isMethyl && cache.smoothedMethylRatio != null) {
      for (int b = firstBin; b <= lastBin; b++) {
        double gpos = cache.genomicStart + b * cache.binSize + cache.binSize / 2.0;
        double x = chromPosToScreenPos.apply(gpos);
        int px = (int) x;
        if (px >= 0 && px < numColumns) {
          row.methylRatio[px] = cache.methylRatio[b];
          row.smoothedMethylRatio[px] = cache.smoothedMethylRatio[b];
        }
      }
      
      // Fill gaps between mapped bins if needed
      if (drawStack.pixelSize > 1.5) {
        for (int p = 0; p < numColumns - 1; p++) {
          if (row.methylRatio[p] >= 0) {
            int nextValid = -1;
            for (int j = p + 1; j < Math.min(p + (int)(drawStack.pixelSize * 2), numColumns); j++) {
              if (row.methylRatio[j] >= 0) {
                nextValid = j;
                break;
              }
            }
            if (nextValid > p + 1) {
              for (int j = p + 1; j < nextValid; j++) {
                row.methylRatio[j] = row.methylRatio[p];
                row.smoothedMethylRatio[j] = row.smoothedMethylRatio[p];
              }
            }
          }
        }
      }
    }

    return row;
  }

  /**
   * Compute the CoverageCache for binned viewing (coverage-only mode).
   * <p>
   * All arrays are bin-sized (≈ screen width).  Methylation ratios are computed
   * at bin level using the reference genome and per-bin bisulfite counts, avoiding
   * the previous regionLen-sized intermediate arrays (up to 48 MB).
   */
  private AlignmentFile.CoverageCache computeCoverageCache(List<BAMRecord> reads,
      int start, int end, int viewLen, double binSize, boolean isMethyl, String chrom) {

    int buffer = viewLen / 2;
    int binStart = Math.max(0, start - buffer);
    int binEnd = end + buffer;
    int numBins = Math.max(1, (int)((binEnd - binStart) / binSize) + 1);

    double[] binCov = new double[numBins];
    double[] mmA = new double[numBins];
    double[] mmC = new double[numBins];
    double[] mmG = new double[numBins];
    double[] mmT = new double[numBins];
    double[] bisulfiteBin = new double[numBins];

    // Single pass through reads — all accumulation at bin level
    for (BAMRecord read : reads) {
      int b1 = Math.max(0, (int)((read.pos - binStart) / binSize));
      int b2 = Math.min(numBins - 1, (int)((read.end - binStart) / binSize));
      for (int b = b1; b <= b2; b++) binCov[b]++;

      if (read.mismatches != null) {
        for (int m = 0; m + 2 < read.mismatches.length; m += 3) {
          int bin = (int)((read.mismatches[m] - binStart) / binSize);
          if (bin < 0 || bin >= numBins) continue;

          if (isMethyl && read.mismatches[m + 2] > 0) {
            char readB = Character.toUpperCase((char) read.mismatches[m + 1]);
            char refB = Character.toUpperCase((char) read.mismatches[m + 2]);
            if ((refB == 'C' && readB == 'T') || (refB == 'G' && readB == 'A')) {
              bisulfiteBin[bin]++;
              continue;
            }
          }

          switch (Character.toUpperCase((char) read.mismatches[m + 1])) {
            case 'A' -> mmA[bin]++;
            case 'C' -> mmC[bin]++;
            case 'G' -> mmG[bin]++;
            case 'T' -> mmT[bin]++;
          }
        }
      }
    }

    double maxRaw = 0;
    for (double c : binCov) if (c > maxRaw) maxRaw = c;

    // ── Bin-level methylation ratio (no per-position arrays) ──
    double[] methylRatio = null;
    if (isMethyl) {
      // Fetch reference for the visible region to count C/G sites per bin
      String covRefBases = null;
      if (referenceGenomeService.hasGenome()) {
        covRefBases = referenceGenomeService.getBases(chrom, Math.max(1, start), end);
        if (covRefBases != null && covRefBases.isEmpty()) covRefBases = null;
      }

      methylRatio = new double[numBins];
      if (covRefBases != null) {
        // Count C/G reference sites per bin
        int[] cgCount = new int[numBins];
        for (int i = 0; i < covRefBases.length(); i++) {
          char base = Character.toUpperCase(covRefBases.charAt(i));
          if (base != 'C' && base != 'G') continue;
          int gpos = start + i;
          int bin = (int)((gpos - binStart) / binSize);
          if (bin >= 0 && bin < numBins) cgCount[bin]++;
        }
        // ratio = 1 - bisConversions / (cgSites × binCoverage)
        for (int b = 0; b < numBins; b++) {
          if (cgCount[b] > 0 && binCov[b] >= 3) {
            double expectedCov = cgCount[b] * binCov[b];
            methylRatio[b] = 1.0 - bisulfiteBin[b] / expectedCov;
            methylRatio[b] = Math.max(0, Math.min(1, methylRatio[b]));
          } else {
            methylRatio[b] = -1;
          }
        }
      } else {
        java.util.Arrays.fill(methylRatio, -1);
      }
    }

    smoothBins(binCov);

    double maxSmoothed = 0;
    for (double c : binCov) if (c > maxSmoothed) maxSmoothed = c;

    double[] smoothedMethylRatio = null;
    if (methylRatio != null) {
      smoothedMethylRatio = methylRatio.clone();
      smoothMethylRatio(smoothedMethylRatio);
    }

    AlignmentFile.CoverageCache cache = new AlignmentFile.CoverageCache();
    cache.smoothedCov = binCov;
    cache.rawMmA = mmA;
    cache.rawMmC = mmC;
    cache.rawMmG = mmG;
    cache.rawMmT = mmT;
    cache.methylRatio = methylRatio;
    cache.smoothedMethylRatio = smoothedMethylRatio;
    cache.genomicStart = binStart;
    cache.binSize = binSize;
    cache.numBins = numBins;
    cache.scaleMax = Math.max(maxSmoothed, maxRaw);
    cache.sourceReads = reads;
    return cache;
  }

  // ── Drawing methods ──

  /**
   * Single-pass rendering: iterates columns left-to-right, drawing both individual
   * sample coverage tracks and master track methylation lines simultaneously.
   *
   * @param gc               graphics context to draw on
   * @param canvasWidth      canvas width
   * @param masterTrackHeight height of the master track area
   * @param sampleHeight     height per sample track
   * @param scrollBarPosition vertical scroll offset
   * @param coverageOnly     true if in coverage-only mode (full track height)
   * @param coverageFractionH height of coverage area when in read+coverage mode
   */
  public void render(GraphicsContext gc, double canvasWidth, double masterTrackHeight,
                     double sampleHeight, double scrollBarPosition,
                     boolean coverageOnly, double coverageFractionH) {
    if (rows == null || rows.length == 0 || numColumns == 0) return;

    double mmMinFrac = Settings.get().getMismatchMinFraction();
    int mmMinCount = Settings.get().getMismatchMinCount();

    // Determine which samples have methylation data for master track
    java.util.List<SampleRow> methylRows = new java.util.ArrayList<>();
    for (SampleRow row : rows) {
      if (row.sample.isMethylationData() && row.smoothedMethylRatio != null) {
        methylRows.add(row);
      }
    }
    boolean hasMasterMethyl = !methylRows.isEmpty();

    // Draw master track background if needed
    if (hasMasterMethyl && masterTrackHeight >= 10) {
      gc.setFill(Color.rgb(25, 28, 35, 0.95));
      gc.fillRect(0, 0, canvasWidth, masterTrackHeight);

      gc.setStroke(Color.rgb(80, 80, 80, 0.4));
      gc.setLineWidth(0.5);
      double y50 = 2 + (masterTrackHeight - 4) * 0.5;
      gc.strokeLine(0, y50, canvasWidth, y50);
      gc.setLineWidth(1.0);
    }

    // ── Single left-to-right pass: draw per-sample coverage + master methylation ──

    // First draw per-sample coverage bars and mismatches
    for (SampleRow row : rows) {
      if (row.masterTrackOnly) continue; // Skip rows only needed for master track
      double sampleY = masterTrackHeight + row.sampleIndex * sampleHeight - scrollBarPosition;
      double covH = coverageOnly ? sampleHeight : coverageFractionH;
      double yBottom = sampleY + covH - 1;
      double scale = row.maxCoverage > 0 ? (covH - 14) / row.maxCoverage : 0;
      double barW = Math.max(1, drawStack.pixelSize);

      // Draw coverage fill — always 1px wide to prevent overdraw at close zoom
      gc.setFill(DrawColors.COVERAGE_FILL);
      for (int px = 0; px < numColumns; px++) {
        if (row.coverage[px] < 0.5) continue;
        double h = row.coverage[px] * scale;
        gc.fillRect(px, yBottom - h, 1, h);
      }

      // Draw mismatches with thresholds
      int lastMmPx = -1;
      for (int px = 0; px < numColumns; px++) {
        double totalMM = row.mmA[px] + row.mmC[px] + row.mmG[px] + row.mmT[px];
        if (totalMM < 0.5) continue;
        if (px == lastMmPx) continue;
        lastMmPx = px;

        double cov = row.coverage[px];
        double baseY = yBottom;
        if (row.mmT[px] >= mmMinCount && (cov == 0 || row.mmT[px] / cov >= mmMinFrac)) {
          double h = row.mmT[px] * scale;
          gc.setFill(DrawColors.MISMATCH_T);
          gc.fillRect(px, baseY - h, barW, h);
          baseY -= h;
        }
        if (row.mmG[px] >= mmMinCount && (cov == 0 || row.mmG[px] / cov >= mmMinFrac)) {
          double h = row.mmG[px] * scale;
          gc.setFill(DrawColors.MISMATCH_G);
          gc.fillRect(px, baseY - h, barW, h);
          baseY -= h;
        }
        if (row.mmC[px] >= mmMinCount && (cov == 0 || row.mmC[px] / cov >= mmMinFrac)) {
          double h = row.mmC[px] * scale;
          gc.setFill(DrawColors.MISMATCH_C);
          gc.fillRect(px, baseY - h, barW, h);
          baseY -= h;
        }
        if (row.mmA[px] >= mmMinCount && (cov == 0 || row.mmA[px] / cov >= mmMinFrac)) {
          double h = row.mmA[px] * scale;
          gc.setFill(DrawColors.MISMATCH_A);
          gc.fillRect(px, baseY - h, barW, h);
        }
      }

      // Per-sample methylation line
      if (row.sample.isMethylationData()) {
        double methylTop = sampleY + 2;
        double methylBottom = sampleY + covH - 2;
        double methylH = methylBottom - methylTop;
        double baseOffset = coverageOnly ? 0 : drawStack.pixelSize / 2.0;

        gc.setStroke(DrawColors.METHYL_LINE);
        gc.setLineWidth(1.5);
        gc.beginPath();
        boolean started = false;
        for (int px = 0; px < numColumns; px++) {
          double val = row.smoothedMethylRatio[px];
          if (val < 0) { started = false; continue; }
          double x = px + baseOffset;
          double y = methylBottom - val * methylH;
          if (!started) { gc.moveTo(x, y); started = true; }
          else { gc.lineTo(x, y); }
        }
        gc.stroke();
        gc.setLineWidth(1.0);
      }

      // Max coverage label
      gc.setFill(Color.web("#aaaaaa"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
      gc.fillText(String.valueOf((int) row.maxCoverage), 3, sampleY + 10);

      // Sashimi arches (splice junction arches) for RNAseq data
      sashimiDrawer.draw(gc, row.sample, sampleY, covH, canvasWidth, drawStack, chromPosToScreenPos);
    }

    // ── Master track methylation lines (same data, just different y-mapping) ──
    if (hasMasterMethyl && masterTrackHeight >= 10) {
      double margin = 2;
      double plotH = masterTrackHeight - 2 * margin;

      for (SampleRow row : methylRows) {
        Color color = DrawColors.SAMPLE_METHYL_COLORS[row.methylColorIndex % DrawColors.SAMPLE_METHYL_COLORS.length];
        gc.setStroke(color);
        gc.setLineWidth(1.5);
        gc.beginPath();
        boolean started = false;

        for (int px = 0; px < numColumns; px++) {
          double val = row.smoothedMethylRatio[px];
          if (val < 0) { started = false; continue; }
          double y = margin + plotH * (1.0 - val);
          if (!started) { gc.moveTo(px, y); started = true; }
          else { gc.lineTo(px, y); }
        }
        gc.stroke();
      }
      gc.setLineWidth(1.0);

      // Sample name legend
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 8));
      double legendX = 4;
      for (SampleRow row : methylRows) {
        Color color = DrawColors.SAMPLE_METHYL_COLORS[row.methylColorIndex % DrawColors.SAMPLE_METHYL_COLORS.length];
        String label = row.sample.getName();
        if (label.length() > 20) label = label.substring(0, 18) + "..";
        gc.setFill(color);
        gc.fillRect(legendX, masterTrackHeight - 10, 8, 6);
        gc.setFill(Color.rgb(200, 200, 200, 0.9));
        gc.fillText(label, legendX + 10, masterTrackHeight - 4);
        legendX += 14 + label.length() * 5;
      }

      // Separator at bottom of master track
      gc.setStroke(Color.rgb(100, 100, 100, 0.6));
      gc.strokeLine(0, masterTrackHeight, canvasWidth, masterTrackHeight);
    }
  }

  /** Get the SampleRow for a given sample, or null. */
  public SampleRow getRow(Sample sample) {
    if (rows == null) return null;
    for (SampleRow row : rows) {
      if (row.sample == sample) return row;
    }
    return null;
  }

  /** Get all sample rows. */
  public SampleRow[] getRows() {
    return rows;
  }

  /** Check if matrix has any data. */
  public boolean hasData() {
    return rows != null && rows.length > 0;
  }

  // ── Static smoothing utilities ──

  /** In-place Gaussian-like smoothing (3-pass box blur). */
  static void smoothBins(double[] bins) {
    int n = bins.length;
    if (n < 5) return;
    int radius = Math.max(1, Math.min(8, n / 80));
    double[] tmp = new double[n];
    for (int pass = 0; pass < 3; pass++) {
      double sum = 0;
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

  /** In-place smoothing for methylation ratio arrays, handling -1 (no data) gaps. */
  /**
   * Draws chromosome-level sampled coverage for one sample.
   * Used when zoomed out beyond the full-coverage view length.
   * Requests sparse sampling from {@link AlignmentFile} and draws the result
   * as a smooth filled coverage profile.
   */
  public void drawSampled(GraphicsContext gc, Sample sample,
                           String chrom, int start, int end,
                           double sampleY, double sampleH, double canvasWidth,
                           Function<Double, Double> chromPosToScreenPos,
                           DrawStack drawStack) {
    AlignmentFile bamFile = sample.getBamFile();
    if (bamFile == null) return;

    int numPoints = Settings.get().getSampledCoveragePoints();
    CoverageCalculator.SampledCoverage sampled =
        bamFile.requestSampledCoverage(chrom, start, end, numPoints, drawStack);

    if (sampled == null) {
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
      gc.fillText("Sampling coverage...", 10, sampleY + sampleH / 2 + 4);
      return;
    }
    if (sampled.samplesCompleted == 0) {
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
      String msg = sampled.chunksProcessed > 0
          ? "Sampling coverage... (" + sampled.chunksProcessed + " chunks processed)"
          : "Sampling coverage...";
      gc.fillText(msg, 10, sampleY + sampleH / 2 + 4);
      return;
    }

    int    count    = sampled.samplesCompleted;
    double maxDepth = sampled.maxDepth;
    if (maxDepth <= 0) return;

    double[] vals = sampled.smoothed != null ? sampled.smoothed : sampled.depths;
    double yBottom        = sampleY + sampleH - 1;
    double scale          = (sampleH - 14) / maxDepth;
    double minVisibleH    = Math.max(4, (sampleH - 14) * 0.08);

    // Merge: restore peaks that smoothing suppressed
    double[] merged = new double[count];
    for (int i = 0; i < count; i++) {
      merged[i] = vals[i];
      if (sampled.depths[i] > 0 && merged[i] < sampled.depths[i] * 0.1)
        merged[i] = sampled.depths[i];
    }

    double[] sx = new double[count];
    double[] sy = new double[count];
    for (int i = 0; i < count; i++) {
      sx[i] = chromPosToScreenPos.apply((double) sampled.positions[i]);
      double h = merged[i] * scale;
      if (sampled.depths[i] > 0 && h < minVisibleH) h = minVisibleH;
      sy[i] = h;
    }
    double strideX = count >= 2 ? sx[1] - sx[0] : canvasWidth / count;

    gc.setFill(DrawColors.COVERAGE_FILL);
    // First pass: guarantee a 1-px column for every non-zero sample
    for (int i = 0; i < count; i++) {
      if (sampled.depths[i] > 0 && sy[i] >= 0.5) {
        double px = Math.floor(sx[i]);
        if (px >= 0 && px < canvasWidth)
          gc.fillRect(px, yBottom - sy[i], 1, sy[i]);
      }
    }
    // Second pass: smooth interpolation between samples
    double drawStart = Math.max(0, sx[0] - strideX * 0.5);
    double drawEnd   = Math.min(canvasWidth, sx[count - 1] + strideX * 0.5);
    if (drawEnd > drawStart) {
      int seg = 0;
      for (double px = drawStart; px < drawEnd; px += 1.0) {
        while (seg < count - 2 && sx[seg + 1] < px) seg++;
        double h;
        if      (px < sx[0])    { h = sy[0]; }
        else if (seg >= count-1) { h = sy[count - 1]; }
        else {
          double segW = sx[seg + 1] - sx[seg];
          h = segW < 0.001 ? sy[seg]
              : sy[seg] + (px - sx[seg]) / segW * (sy[seg + 1] - sy[seg]);
        }
        if (h >= 0.5) gc.fillRect(px, yBottom - h, 1, h);
      }
    }

    if (!sampled.complete) {
      int pct = (int)(100.0 * count / sampled.numSamples);
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
      gc.fillText("Sampling " + pct + "%", 3, sampleY + sampleH - 4);
    }
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
    gc.fillText(String.valueOf((int) maxDepth), 3, sampleY + 10);
  }

  static void smoothMethylRatio(double[] ratios) {
    int n = ratios.length;
    if (n < 5) return;

    // Interpolate across small gaps
    int maxGap = Math.max(5, Math.min(30, n / 30));
    int lastValid = -1;
    for (int i = 0; i < n; i++) {
      if (ratios[i] >= 0) {
        if (lastValid >= 0 && i - lastValid <= maxGap) {
          double v0 = ratios[lastValid], v1 = ratios[i];
          for (int j = lastValid + 1; j < i; j++) {
            double t = (double)(j - lastValid) / (i - lastValid);
            ratios[j] = v0 + t * (v1 - v0);
          }
        }
        lastValid = i;
      }
    }

    // 3-pass box blur (only over valid values)
    int radius = Math.max(2, Math.min(15, n / 40));
    double[] tmp = new double[n];
    for (int pass = 0; pass < 3; pass++) {
      for (int i = 0; i < n; i++) {
        if (ratios[i] < 0) { tmp[i] = -1; continue; }
        double sum = 0; int cnt = 0;
        for (int k = Math.max(0, i - radius); k <= Math.min(n - 1, i + radius); k++) {
          if (ratios[k] >= 0) { sum += ratios[k]; cnt++; }
        }
        tmp[i] = cnt > 0 ? sum / cnt : -1;
      }
      System.arraycopy(tmp, 0, ratios, 0, n);
    }
  }
}
