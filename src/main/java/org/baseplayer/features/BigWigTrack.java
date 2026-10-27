package org.baseplayer.features;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.bbreader.BBFileReader;
import org.baseplayer.io.bbreader.BBZoomLevelHeader;
import org.baseplayer.io.bbreader.BBZoomLevels;
import org.baseplayer.io.bbreader.BigWigIterator;
import org.baseplayer.io.bbreader.WigItem;
import org.baseplayer.io.bbreader.ZoomDataRecord;
import org.baseplayer.io.bbreader.ZoomLevelIterator;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.ChromosomeNames;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Feature track for local BigWig files, using the Broad/IGV BBReader ported
 * from classic BasePlayer ({@link org.baseplayer.io.bbreader.BBFileReader}).
 */
@SuppressWarnings("this-escape")
public class BigWigTrack extends AbstractTrack {

  /** Pixel width at which BAF intervals switch from dots to region bars. */
  private static final double BAF_BAR_MIN_WIDTH_PX = 3.0;
  private static final double BAF_DOT_SIZE = 3.0;
  private static final double BAF_BAR_THICKNESS = 3.0;
  /**
   * Below this bases/pixel, BAF always uses full-resolution values (thinned per
   * screen column). Above it we may use zoom records, but never their mean —
   * only min/max, which preserves the 0 / 1 bands.
   */
  private static final double BAF_FULL_RES_MAX_BASES_PER_PIXEL = 500.0;
  /** Cap how many BAF dots we keep after column thinning. */
  private static final int BAF_MAX_DRAW_POINTS = 50_000;
  /** Floor for log₂(ratio) so zeros / tiny ratios don't explode. */
  private static final double COPY_RATIO_LOG_EPS = 1e-3;
  private static final double COPY_RATIO_SEG_THICKNESS = 2.5;

  /** BAF mid (het ~0.5): greenish. Extremes (0 / 1) blend toward orange. */
  private static final Color COLOR_BAF_MID = Color.rgb(46, 170, 90);
  private static final Color COLOR_BAF_EXTREME = Color.rgb(230, 125, 35);
  /** Sidebar / chrome fallback for BAF tracks. */
  private static final Color COLOR_BAF = COLOR_BAF_MID;
  /** Copy-ratio gain (above diploid / log₂ 0). */
  private static final Color COLOR_COPY_GAIN = Color.rgb(200, 55, 55);
  /** Copy-ratio loss (below diploid / log₂ 0). */
  private static final Color COLOR_COPY_LOSS = Color.rgb(55, 95, 200);
  /** Neutral midline / track chrome for seg. */
  private static final Color COLOR_COPY_NEUTRAL = Color.rgb(120, 120, 120);
  /** Target-normalized coverage ({@code *.tn.bw}). */
  private static final Color COLOR_TN = Color.rgb(210, 140, 40);
  /** Target counts / improper-pair coverage histograms. */
  private static final Color COLOR_COUNTS = Color.rgb(90, 150, 130);
  private static final Color COLOR_DEFAULT = Color.rgb(100, 149, 237);

  public record WigBin(int start, int end, double value) {}

  private final Path filePath;
  private final BBFileReader reader;
  private final AtomicInteger loadGeneration = new AtomicInteger();

  private volatile List<WigBin> bins = List.of();
  private volatile String loadedChrom = "";
  private volatile long loadedStart = 0;
  private volatile long loadedEnd = 0;
  private volatile boolean loading = false;
  private volatile String loadError = null;

  private double dataMin = 0;
  private double dataMax = 0;
  private double scale = 1;
  private SignalDisplayMode displayMode = SignalDisplayMode.HISTOGRAM;
  /** When {@link SignalDisplayMode#COPY_RATIO}, plot log₂(ratio) (default for seg.bw). */
  private boolean copyRatioLog2 = true;

  private Runnable onDataLoaded;

  public BigWigTrack(Path filePath) throws IOException {
    super(filePath.getFileName().toString(), "BigWig");
    this.filePath = filePath.toAbsolutePath().normalize();
    this.preferredHeight = 40;
    this.color = defaultColorFor(this.filePath);
    // Signal tracks are not useful in the feature-name aggregate density band.
    setAggregateDisabled(true);

    if (looksLikeBafFile(this.filePath)) {
      setDisplayMode(SignalDisplayMode.BAF);
    } else if (looksLikeSegFile(this.filePath)) {
      setDisplayMode(SignalDisplayMode.COPY_RATIO);
      setCopyRatioLog2(true);
    }

    System.err.println("[BigWig] Opening via BBFileReader: " + this.filePath
        + " mode=" + displayMode
        + (displayMode == SignalDisplayMode.COPY_RATIO
            ? (copyRatioLog2 ? " log2" : " linear")
            : ""));
    this.reader = new BBFileReader(this.filePath.toString());
    if (!reader.isBigWigFile()) {
      reader.close();
      throw new IOException("Not a BigWig file: " + this.filePath);
    }

    List<String> chroms = reader.getChromosomeNames();
    if (chroms != null && !chroms.isEmpty()) {
      setChromPrefix(ChromosomeNames.detectPrefix(chroms));
      int show = Math.min(chroms.size(), 12);
      System.err.println("[BigWig] chroms=" + chroms.size()
          + " prefix='" + getChromPrefix() + "'"
          + " first=" + chroms.subList(0, show));
    }
    BBZoomLevels zooms = reader.getZoomLevels();
    System.err.println("[BigWig] zoomLevels=" + reader.getZoomLevelCount()
        + (zooms != null ? (" headers=" + zooms.getZoomHeaderCount()) : ""));
  }

  /** Heuristic: DRAGEN / Illumina {@code *.baf*.bw} companions. */
  public static boolean looksLikeBafFile(Path path) {
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.contains(".baf.") || name.endsWith(".baf.bw") || name.contains("_baf.");
  }

  /** Track chrome color from DRAGEN / signal file naming conventions. */
  public static Color defaultColorFor(Path path) {
    if (path == null || path.getFileName() == null) {
      return COLOR_DEFAULT;
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (looksLikeBafFile(path)) {
      return COLOR_BAF;
    }
    if (looksLikeSegFile(path)) {
      return COLOR_COPY_NEUTRAL;
    }
    if (name.endsWith(".tn.bw") || name.contains(".tn.")) {
      return COLOR_TN;
    }
    if (name.contains("target.counts") || name.contains("improper.pairs") || name.contains("improper_pairs")) {
      return COLOR_COUNTS;
    }
    return COLOR_DEFAULT;
  }

  /**
   * DRAGEN CNV segmentation BigWig ({@code *.seg.bw}), not {@code *.baf.seg.bw}.
   */
  public static boolean looksLikeSegFile(Path path) {
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".seg.bw") && !name.contains(".baf.seg");
  }

  public SignalDisplayMode getDisplayMode() {
    return displayMode;
  }

  public boolean isCopyRatioLog2() {
    return copyRatioLog2;
  }

  public void setCopyRatioLog2(boolean log2) {
    if (this.copyRatioLog2 == log2) {
      return;
    }
    this.copyRatioLog2 = log2;
    applyCopyRatioScaleDefaults();
    // Redraw only — stored bins stay linear; transform is at paint time.
  }

  public void setDisplayMode(SignalDisplayMode mode) {
    SignalDisplayMode next = mode != null ? mode : SignalDisplayMode.HISTOGRAM;
    boolean changed = next != this.displayMode;
    this.displayMode = next;
    if (this.displayMode == SignalDisplayMode.BAF) {
      this.minValue = 0.0;
      this.maxValue = 1.0;
      this.preferredHeight = Math.max(this.preferredHeight, 48);
    } else if (this.displayMode == SignalDisplayMode.COPY_RATIO) {
      this.preferredHeight = Math.max(this.preferredHeight, 48);
      applyCopyRatioScaleDefaults();
    }
    if (changed) {
      // Force refetch — zoom-mean vs min/max/full are not interchangeable.
      loadedChrom = "";
      bins = List.of();
    }
  }

  private void applyCopyRatioScaleDefaults() {
    if (copyRatioLog2) {
      // ±2 covers ~0.25× … 4× linear copy ratio.
      this.minValue = -2.0;
      this.maxValue = 2.0;
    } else {
      this.minValue = 0.0;
      this.maxValue = 2.0;
    }
  }

  @Override
  public Path getSourcePath() {
    return filePath;
  }

  public void setOnDataLoaded(Runnable callback) {
    this.onDataLoaded = callback;
  }

  @Override
  public boolean isLoading() {
    return loading;
  }

  @Override
  public void onRegionChanged(String chromosome, long start, long end, DrawStack drawStack) {
    if (!visible || reader == null) {
      return;
    }
    if (drawStack != null && drawStack.nav != null && drawStack.nav.animationRunning) {
      return;
    }
    if (chromosome == null || chromosome.isBlank() || end <= start) {
      return;
    }

    // Skip reload if we already cover this region at a suitable zoom.
    if (!loading
        && chromosome.equals(loadedChrom)
        && start >= loadedStart
        && end <= loadedEnd
        && (loadedEnd - loadedStart) <= Math.max(1, (end - start) * 3)) {
      return;
    }

    final int gen = loadGeneration.incrementAndGet();
    final String chrom = chromosome;
    final long viewStart = Math.max(0, start);
    final long viewEnd = Math.max(viewStart + 1, end);
    final double pixelSize = drawStack != null ? Math.max(1e-9, drawStack.getPixelSize()) : 1.0;
    final double basesPerPixel = 1.0 / pixelSize;
    // Approximate paint width for per-column thinning (fallback if canvas unknown).
    final int approxPixels = Math.max(100, (int) Math.round((viewEnd - viewStart) * pixelSize));

    // Pad fetch window slightly so pan stays covered.
    long pad = Math.max(1, (viewEnd - viewStart) / 4);
    final long fetchStart = Math.max(0, viewStart - pad);
    final long fetchEnd = viewEnd + pad;
    final boolean baf = displayMode == SignalDisplayMode.BAF;
    final boolean copyRatio = displayMode == SignalDisplayMode.COPY_RATIO;
    final int zoomLevel = chooseZoomLevel(basesPerPixel, baf, copyRatio);

    loading = true;
    loadError = null;
    notifyLoaded();

    Thread t = new Thread(() -> {
      try {
        List<WigBin> fetched = fetchBins(
            chrom, fetchStart, fetchEnd, zoomLevel, baf, basesPerPixel, approxPixels);
        if (gen != loadGeneration.get()) {
          return;
        }
        double min = 0;
        double max = 0;
        boolean any = false;
        for (WigBin bin : fetched) {
          if (Double.isNaN(bin.value())) {
            continue;
          }
          if (!any) {
            min = max = bin.value();
            any = true;
          } else {
            min = Math.min(min, bin.value());
            max = Math.max(max, bin.value());
          }
        }
        final List<WigBin> result = List.copyOf(fetched);
        final double fMin = any ? min : 0;
        final double fMax = any ? max : 0;
        final double fScale = Math.max(1e-9, Math.max(Math.abs(fMin), Math.abs(fMax)));
        Platform.runLater(() -> {
          if (gen != loadGeneration.get()) {
            return;
          }
          bins = result;
          if (displayMode == SignalDisplayMode.BAF) {
            dataMin = 0;
            dataMax = 1;
            scale = 1;
          } else if (displayMode == SignalDisplayMode.COPY_RATIO) {
            // Display range is in transformed space; defaults set by applyCopyRatioScaleDefaults.
            if (minValue != null && maxValue != null) {
              dataMin = minValue;
              dataMax = maxValue;
            } else if (copyRatioLog2) {
              dataMin = -2;
              dataMax = 2;
            } else {
              dataMin = 0;
              dataMax = 2;
            }
            scale = Math.max(1e-9, Math.max(Math.abs(dataMin), Math.abs(dataMax)));
          } else {
            dataMin = minValue != null ? minValue : fMin;
            dataMax = maxValue != null ? maxValue : fMax;
            scale = maxValue != null
                ? Math.max(1e-9, Math.abs(maxValue))
                : fScale;
          }
          loadedChrom = chrom;
          loadedStart = fetchStart;
          loadedEnd = fetchEnd;
          loading = false;
          System.err.println("[BigWig] loaded " + result.size() + " bins"
              + " chrom=" + chrom
              + " dataChrom=" + toDataChrom(chrom)
              + " " + fetchStart + "-" + fetchEnd
              + " zoom=" + zoomLevel
              + (baf ? " (BAF min/max or full)" : "")
              + " scale=" + scale);
          notifyLoaded();
        });
      } catch (Exception e) {
        if (gen != loadGeneration.get()) {
          return;
        }
        System.err.println("[BigWig] fetch failed: " + e.getMessage());
        e.printStackTrace(System.err);
        Platform.runLater(() -> {
          if (gen != loadGeneration.get()) {
            return;
          }
          loadError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
          bins = List.of();
          loading = false;
          notifyLoaded();
        });
      }
    }, "BigWig-load-" + filePath.getFileName());
    t.setDaemon(true);
    t.start();
  }

  private int chooseZoomLevel(double basesPerPixel, boolean bafMode, boolean copyRatioMode) {
    // Segments are few long intervals — always prefer full resolution.
    if (copyRatioMode) {
      return 1;
    }
    // Mean-aggregated zoom levels collapse BAF clouds to ~0.5 — stay on full-res
    // until the view is very coarse, then use zoom min/max instead of mean.
    if (bafMode && basesPerPixel <= BAF_FULL_RES_MAX_BASES_PER_PIXEL) {
      return 1;
    }
    BBZoomLevels zooms = reader.getZoomLevels();
    if (zooms == null || zooms.getZoomHeaderCount() < 2) {
      return 1;
    }
    int level = 1;
    int count = zooms.getZoomHeaderCount();
    for (int i = 2; i <= count; i++) {
      BBZoomLevelHeader header = zooms.getZoomLevelHeader(i);
      if (header == null) {
        break;
      }
      if (header.getReductionLevel() < basesPerPixel) {
        level = i;
      } else {
        break;
      }
    }
    return level;
  }

  private List<WigBin> fetchBins(
      String chromosome,
      long start,
      long end,
      int zoomLevel,
      boolean bafMode,
      double basesPerPixel,
      int approxPixels) {
    String dataChrom = toDataChrom(chromosome);

    if (zoomLevel <= 1) {
      List<WigBin> full = fetchFullResolution(dataChrom, start, end);
      if (full.isEmpty()) {
        String alt = alternateChrom(dataChrom);
        if (alt != null) {
          List<WigBin> altFull = fetchFullResolution(alt, start, end);
          if (!altFull.isEmpty()) {
            applyChromPrefixFromName(alt);
            full = altFull;
          }
        }
      }
      if (bafMode) {
        return thinBafForDisplay(full, start, end, approxPixels);
      }
      return full;
    }

    List<WigBin> zoomBins = fetchZoomBins(dataChrom, start, end, zoomLevel, bafMode);
    if (zoomBins.isEmpty()) {
      String alt = alternateChrom(dataChrom);
      if (alt != null) {
        zoomBins = fetchZoomBins(alt, start, end, zoomLevel, bafMode);
        if (!zoomBins.isEmpty()) {
          applyChromPrefixFromName(alt);
        }
      }
    }
    if (zoomBins.isEmpty()) {
      // Fall back to full-res (possibly thinned) rather than empty.
      return fetchBins(chromosome, start, end, 1, bafMode, basesPerPixel, approxPixels);
    }
    return zoomBins;
  }

  private List<WigBin> fetchFullResolution(String dataChrom, long start, long end) {
    List<WigBin> out = new ArrayList<>();
    BigWigIterator it = reader.getBigWigIterator(
        dataChrom, (int) start, dataChrom, (int) end, false);
    if (it == null) {
      return out;
    }
    while (it.hasNext()) {
      WigItem item = it.next();
      if (item == null) {
        continue;
      }
      if (item.getStartBase() > end) {
        break;
      }
      if (item.getEndBase() < start) {
        continue;
      }
      out.add(new WigBin(item.getStartBase(), item.getEndBase(), item.getWigValue()));
    }
    return out;
  }

  private List<WigBin> fetchZoomBins(
      String dataChrom, long start, long end, int zoomLevel, boolean bafMode) {
    List<WigBin> out = new ArrayList<>();
    ZoomLevelIterator zit;
    try {
      zit = reader.getZoomLevelIterator(
          zoomLevel, dataChrom, (int) start, dataChrom, (int) end, false);
    } catch (RuntimeException e) {
      System.err.println("[BigWig] zoom iterator failed: " + e.getMessage());
      return out;
    }
    if (zit == null) {
      return out;
    }
    while (zit.hasNext()) {
      ZoomDataRecord rec = zit.next();
      if (rec == null) {
        continue;
      }
      if (rec.getChromStart() > end) {
        break;
      }
      if (rec.getChromEnd() < start) {
        continue;
      }
      if (bafMode) {
        // Keep envelope (min/max) plus mean so the 0.5 band stays visible —
        // mean alone would hide 0/1; min/max alone hide the het cloud.
        float min = rec.getMinVal();
        float max = rec.getMaxVal();
        float mean = rec.getMeanVal();
        int mid = rec.getChromStart() + Math.max(0, (rec.getChromEnd() - rec.getChromStart()) / 2);
        out.add(new WigBin(mid, mid + 1, min));
        if (Float.isFinite(max) && Math.abs(max - min) > 1e-4f) {
          out.add(new WigBin(mid, mid + 1, max));
        }
        if (Float.isFinite(mean)
            && Math.abs(mean - min) > 1e-3f
            && Math.abs(mean - max) > 1e-3f) {
          out.add(new WigBin(mid, mid + 1, mean));
        }
      } else {
        out.add(new WigBin(rec.getChromStart(), rec.getChromEnd(), rec.getMeanVal()));
      }
    }
    return out;
  }

  /**
   * Keep BAF scatter readable when zoomed out: per screen column retain min and
   * max (and a mid sample when present) instead of averaging to 0.5.
   */
  private static List<WigBin> thinBafForDisplay(
      List<WigBin> full, long viewStart, long viewEnd, int approxPixels) {
    if (full.isEmpty()) {
      return full;
    }
    int columns = Math.max(64, Math.min(approxPixels, 4000));
    if (full.size() <= columns * 3 && full.size() <= BAF_MAX_DRAW_POINTS) {
      return full;
    }
    double viewLen = Math.max(1, viewEnd - viewStart);
    float[] colMin = new float[columns];
    float[] colMax = new float[columns];
    float[] colMid = new float[columns];
    boolean[] has = new boolean[columns];
    boolean[] hasMid = new boolean[columns];
    java.util.Arrays.fill(colMin, Float.POSITIVE_INFINITY);
    java.util.Arrays.fill(colMax, Float.NEGATIVE_INFINITY);

    for (WigBin bin : full) {
      double center = (bin.start() + bin.end()) * 0.5;
      int col = (int) ((center - viewStart) / viewLen * columns);
      if (col < 0 || col >= columns) {
        continue;
      }
      float v = (float) bin.value();
      if (!Float.isFinite(v)) {
        continue;
      }
      has[col] = true;
      colMin[col] = Math.min(colMin[col], v);
      colMax[col] = Math.max(colMax[col], v);
      // Prefer a value near 0.5 when present (het band).
      if (!hasMid[col] || Math.abs(v - 0.5f) < Math.abs(colMid[col] - 0.5f)) {
        colMid[col] = v;
        hasMid[col] = true;
      }
    }

    List<WigBin> out = new ArrayList<>(columns * 3);
    for (int c = 0; c < columns; c++) {
      if (!has[c]) {
        continue;
      }
      int pos = (int) (viewStart + (c + 0.5) * viewLen / columns);
      // Always emit min / max / near-0.5 when they land on different Y pixels.
      addDistinctBafSample(out, pos, colMin[c]);
      addDistinctBafSample(out, pos, colMax[c]);
      if (hasMid[c]) {
        addDistinctBafSample(out, pos, colMid[c]);
      }
    }
    return out;
  }

  /** Add a BAF sample unless it maps to the same display value as the last one at pos. */
  private static void addDistinctBafSample(List<WigBin> out, int pos, float value) {
    if (!Float.isFinite(value)) {
      return;
    }
    // ~1/256 of the 0–1 range ≈ one Y pixel on a ~50px track.
    final float eps = 1f / 256f;
    for (int i = out.size() - 1; i >= 0; i--) {
      WigBin prev = out.get(i);
      if (prev.start() != pos) {
        break;
      }
      if (Math.abs(prev.value() - value) < eps) {
        return;
      }
    }
    out.add(new WigBin(pos, pos + 1, value));
  }

  private static String alternateChrom(String dataChrom) {
    if (dataChrom == null || dataChrom.isBlank()) {
      return null;
    }
    String lower = dataChrom.toLowerCase(Locale.ROOT);
    if (lower.startsWith("chr")) {
      return dataChrom.substring(3);
    }
    return "chr" + dataChrom;
  }

  private void applyChromPrefixFromName(String dataChrom) {
    if (dataChrom != null && dataChrom.toLowerCase(Locale.ROOT).startsWith("chr")) {
      setChromPrefix(ChromosomeNames.CHR_PREFIX);
    } else {
      setChromPrefix(ChromosomeNames.NONE);
    }
  }

  private void notifyLoaded() {
    if (onDataLoaded != null) {
      Platform.runLater(onDataLoaded);
    }
  }

  @Override
  public void draw(GraphicsContext gc, double x, double y, double width, double height,
                   String chromosome, double start, double end) {
    draw(gc, x, y, width, height, chromosome, start, end, true);
  }

  /**
   * @param clearBackground when false (sample-row overlay), skip the opaque fill so
   *     BAM/coverage behind the signal remains visible
   */
  public void draw(GraphicsContext gc, double x, double y, double width, double height,
                   String chromosome, double start, double end, boolean clearBackground) {
    if (clearBackground) {
      gc.setFill(Color.rgb(25, 25, 30));
      gc.fillRect(x, y, width, height);
    }

    if (loadError != null) {
      gc.setFill(Color.rgb(180, 80, 80));
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      gc.fillText(loadError, x + width / 2, y + height / 2 + 4);
      gc.setTextAlign(TextAlignment.LEFT);
      return;
    }

    List<WigBin> localBins = bins;
    if (loading && localBins.isEmpty()) {
      gc.setFill(Color.rgb(120, 120, 120));
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      gc.fillText("Loading BigWig…", x + width / 2, y + height / 2 + 4);
      gc.setTextAlign(TextAlignment.LEFT);
      return;
    }

    if (localBins.isEmpty()) {
      if (clearBackground) {
        gc.setFill(Color.rgb(100, 100, 100));
        gc.setFont(AppFonts.getUIFont(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("No BigWig data in view", x + width / 2, y + height / 2 + 4);
        gc.setTextAlign(TextAlignment.LEFT);
      }
      return;
    }

    if (displayMode == SignalDisplayMode.BAF) {
      drawBaf(gc, x, y, width, height, start, end, localBins);
    } else if (displayMode == SignalDisplayMode.COPY_RATIO) {
      drawCopyRatioSegments(gc, x, y, width, height, start, end, localBins);
    } else {
      drawHistogram(gc, x, y, width, height, start, end, localBins);
    }

    if (loading) {
      gc.setFill(Color.rgb(180, 180, 180, 0.7));
      gc.setFont(AppFonts.getUIFont(9));
      gc.setTextAlign(TextAlignment.LEFT);
      gc.fillText("updating…", x + 4, y + 12);
    }
  }

  /**
   * BAF: fixed 0 (bottom) – 0.5 (mid) – 1 (top). Zoomed-out → dots;
   * zoomed-in (wide intervals) → thin horizontal bars for regions.
   */
  private void drawBaf(
      GraphicsContext gc, double x, double y, double width, double height,
      double viewStart, double viewEnd, List<WigBin> localBins) {
    double viewLen = Math.max(1e-9, viewEnd - viewStart);
    double top = y + 2;
    double usable = Math.max(4, height - 4);
    double midY = top + usable * 0.5;

    gc.setStroke(Color.rgb(90, 90, 90));
    gc.setLineWidth(1);
    gc.setLineDashes(3, 3);
    gc.strokeLine(x, midY, x + width, midY);
    gc.setLineDashes();

    // Soft guide ticks at 0 / 1
    gc.setStroke(Color.rgb(55, 55, 55));
    gc.strokeLine(x, top, x + width, top);
    gc.strokeLine(x, top + usable, x + width, top + usable);

    // Suppress overdraw only when the same screen pixel (x AND y) was already inked —
    // same-X different-Y (0 vs 0.5 vs 1) must remain visible.
    java.util.HashSet<Long> drawnPx = new java.util.HashSet<>();
    for (WigBin bin : localBins) {
      if (bin.end() <= viewStart || bin.start() >= viewEnd) {
        continue;
      }
      double value = bin.value();
      if (Double.isNaN(value)) {
        continue;
      }
      double v = Math.max(0, Math.min(1, value));
      // 0 at bottom, 1 at top
      double valueY = top + usable * (1.0 - v);
      gc.setFill(bafColorForValue(v));

      double x1 = x + ((bin.start() - viewStart) / viewLen) * width;
      double x2 = x + ((bin.end() - viewStart) / viewLen) * width;
      double barW = Math.max(1, x2 - x1);

      if (barW >= BAF_BAR_MIN_WIDTH_PX) {
        double barTop = valueY - BAF_BAR_THICKNESS * 0.5;
        int xPix = (int) x1;
        int yPix = (int) Math.round(valueY);
        long key = (((long) xPix) << 32) | (yPix & 0xffffffffL);
        if (!drawnPx.add(key)) {
          continue;
        }
        gc.fillRect(x1, barTop, barW, BAF_BAR_THICKNESS);
      } else {
        double cx = (x1 + x2) * 0.5;
        int xPix = (int) Math.round(cx);
        int yPix = (int) Math.round(valueY);
        long key = (((long) xPix) << 32) | (yPix & 0xffffffffL);
        if (!drawnPx.add(key)) {
          continue;
        }
        gc.fillOval(cx - BAF_DOT_SIZE * 0.5, valueY - BAF_DOT_SIZE * 0.5,
            BAF_DOT_SIZE, BAF_DOT_SIZE);
      }
    }
  }

  /**
   * Green at het (~0.5); blends to orange toward homozygous 0 and 1.
   */
  static Color bafColorForValue(double value01) {
    double v = Math.max(0, Math.min(1, value01));
    double towardExtreme = Math.abs(v - 0.5) * 2.0;
    return COLOR_BAF_MID.interpolate(COLOR_BAF_EXTREME, towardExtreme);
  }

  /**
   * DRAGEN {@code *.seg.bw}: horizontal steps at linear or log₂ copy ratio.
   * Midline = 1.0 (linear) or 0 (log₂).
   */
  private void drawCopyRatioSegments(
      GraphicsContext gc, double x, double y, double width, double height,
      double viewStart, double viewEnd, List<WigBin> localBins) {
    double viewLen = Math.max(1e-9, viewEnd - viewStart);
    double top = y + 2;
    double usable = Math.max(4, height - 4);

    double dispMin = minValue != null ? minValue : dataMin;
    double dispMax = maxValue != null ? maxValue : dataMax;
    if (!(dispMax > dispMin)) {
      dispMin = copyRatioLog2 ? -2 : 0;
      dispMax = copyRatioLog2 ? 2 : 2;
    }
    double midDisplay = copyRatioLog2 ? 0.0 : 1.0;
    double midY = top + usable * (1.0 - ((midDisplay - dispMin) / (dispMax - dispMin)));
    midY = Math.max(top, Math.min(top + usable, midY));

    gc.setStroke(COLOR_COPY_NEUTRAL);
    gc.setLineWidth(1);
    gc.setLineDashes(3, 3);
    gc.strokeLine(x, midY, x + width, midY);
    gc.setLineDashes();

    gc.setLineWidth(COPY_RATIO_SEG_THICKNESS);
    Double prevEndX = null;
    Double prevValueY = null;
    Color prevStroke = null;

    for (WigBin bin : localBins) {
      if (bin.end() <= viewStart || bin.start() >= viewEnd) {
        continue;
      }
      double raw = bin.value();
      if (Double.isNaN(raw)) {
        continue;
      }
      double disp = toCopyRatioDisplay(raw);
      double frac = (disp - dispMin) / (dispMax - dispMin);
      frac = Math.max(0, Math.min(1, frac));
      double valueY = top + usable * (1.0 - frac);
      Color stroke = copyRatioStroke(disp, midDisplay);

      double x1 = x + ((Math.max(bin.start(), viewStart) - viewStart) / viewLen) * width;
      double x2 = x + ((Math.min(bin.end(), viewEnd) - viewStart) / viewLen) * width;
      if (x2 - x1 < 1) {
        x2 = x1 + 1;
      }

      // Optional vertical connector for a step plot when segments abut.
      if (prevEndX != null && prevValueY != null && Math.abs(prevEndX - x1) <= 1.5) {
        gc.setStroke(prevStroke != null ? prevStroke : stroke);
        gc.strokeLine(x1, prevValueY, x1, valueY);
      }
      gc.setStroke(stroke);
      gc.strokeLine(x1, valueY, x2, valueY);
      prevEndX = x2;
      prevValueY = valueY;
      prevStroke = stroke;
    }

    // Small legend hint
    gc.setFill(Color.rgb(160, 160, 160));
    gc.setFont(AppFonts.getUIFont(9));
    gc.setTextAlign(TextAlignment.LEFT);
    gc.fillText(copyRatioLog2 ? "log₂ ratio" : "copy ratio", x + 4, y + 11);
  }

  /** Linear Segment_Mean → display value (identity or log₂). */
  private double toCopyRatioDisplay(double linearRatio) {
    if (copyRatioLog2) {
      return Math.log(Math.max(COPY_RATIO_LOG_EPS, linearRatio)) / Math.log(2.0);
    }
    return linearRatio;
  }

  /** IGV-style red = gain, blue = loss relative to diploid midline. */
  private static Color copyRatioStroke(double displayValue, double midDisplay) {
    if (displayValue > midDisplay + 1e-6) {
      return COLOR_COPY_GAIN;
    }
    if (displayValue < midDisplay - 1e-6) {
      return COLOR_COPY_LOSS;
    }
    return COLOR_COPY_NEUTRAL;
  }

  private void drawHistogram(
      GraphicsContext gc, double x, double y, double width, double height,
      double viewStart, double viewEnd, List<WigBin> localBins) {
    double viewLen = Math.max(1e-9, viewEnd - viewStart);
    double midY = y + height / 2.0;
    double usable = Math.max(4, height - 4);
    double half = usable / 2.0;
    boolean bipolar = dataMin < 0 && dataMax > 0;

    if (bipolar) {
      gc.setStroke(Color.rgb(70, 70, 70));
      gc.setLineWidth(1);
      gc.strokeLine(x, midY, x + width, midY);
    }

    gc.setFill(color);
    for (WigBin bin : localBins) {
      if (bin.end() <= viewStart || bin.start() >= viewEnd) {
        continue;
      }
      double x1 = x + ((bin.start() - viewStart) / viewLen) * width;
      double x2 = x + ((bin.end() - viewStart) / viewLen) * width;
      double barW = Math.max(1, x2 - x1);
      double value = bin.value();
      if (Double.isNaN(value)) {
        continue;
      }

      if (bipolar) {
        double barH = Math.min(half, (Math.abs(value) / scale) * half);
        if (value >= 0) {
          gc.fillRect(x1, midY - barH, barW, barH);
        } else {
          gc.fillRect(x1, midY, barW, barH);
        }
      } else {
        double barH = Math.min(usable, (Math.abs(value) / scale) * usable);
        gc.fillRect(x1, y + height - 2 - barH, barW, barH);
      }
    }
  }

  @Override
  public void dispose() {
    loadGeneration.incrementAndGet();
    if (reader != null) {
      reader.close();
    }
  }
}
