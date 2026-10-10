package org.baseplayer.features;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.Settings;
import org.baseplayer.io.readers.BedFeatureNameCatalog;
import org.baseplayer.io.readers.BedFileReader;
import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.io.readers.IndexedBedReader;
import org.baseplayer.samples.alignment.FetchManager;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.utils.FeatureNameColors;
import org.baseplayer.utils.StackingAlgorithm;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

/**
 * BED feature track. Small / unindexed files are fully loaded into memory.
 * bgzip + {@code .tbi} files use tabix region queries and only keep the
 * current viewport cache in RAM.
 */
public class BedTrack extends AbstractTrack {

  public static final double DEFAULT_BAR_HEIGHT_PX = 10;
  private static final double MIN_BAR_HEIGHT_PX = 2;
  private static final double MAX_BAR_HEIGHT_PX = 48;
  private static final double ROW_TOP_PAD = 2;
  private static final double NAME_SLOT_H = 11;
  /** Show name labels once a feature is at least this wide (px). */
  private static final double MIN_LABEL_WIDTH_PX = 18;
  private static final double LABEL_CHAR_WIDTH = 8.0;
  private static final double LABEL_PAD_PX = 12.0;
  private static final double LANE_GAP = 3;
  private static final int MAX_STACK_ROWS = 40;
  /** Click / hover only when zoomed in enough that the feature spans this many px. */
  public static final double MIN_INTERACTIVE_WIDTH_PX = 6;
  /** Extra bases fetched around the view to reduce refetch while panning. */
  private static final long QUERY_PADDING_BP = 25_000L;

  private static final StackingAlgorithm<BedFeature> FEATURE_STACKER =
      StackingAlgorithm.createWithVisual(
          // Inclusive genomic ends for packing (BED end is half-open).
          f -> f.start() + 1,
          f -> Math.max(f.start() + 1, f.end()),
          BedTrack::visualEndIncludingName,
          MAX_STACK_ROWS,
          0.0);

  private static double visualEndIncludingName(
      BedFeature feature, double viewStart, double viewLength, double canvasWidth) {
    double x1 = ((feature.start() + 1 - viewStart) / viewLength) * canvasWidth;
    double x2 = ((feature.end() - viewStart) / viewLength) * canvasWidth;
    double bodyW = Math.max(1, x2 - x1);
    String name = feature.name();
    if (name != null && !name.isEmpty() && bodyW >= MIN_LABEL_WIDTH_PX) {
      double labelEnd = Math.max(0, x1) + 2 + name.length() * LABEL_CHAR_WIDTH + LABEL_PAD_PX;
      return Math.max(x2, labelEnd);
    }
    return x2 + 2;
  }
  /** Single-thread pool: tabix readers are not safe for concurrent queries. */
  private static final ExecutorService QUERY_POOL = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "bed-tabix-query");
    t.setDaemon(true);
    return t;
  });

  private final Path sourcePath;
  private final long sourceFileBytes;
  private final Map<String, List<BedFeature>> featuresByChrom;
  private final IndexedBedReader indexedReader;
  private final List<BedTrack> derivedSources;
  private final boolean fullyMaterialized;

  private volatile List<BedFeature> regionCache = List.of();
  private volatile String cacheChrom = "";
  private volatile long cacheStart = -1;
  private volatile long cacheEnd = -1;
  private volatile boolean loading;
  private final AtomicInteger fetchGeneration = new AtomicInteger();
  private Runnable onDataLoaded;
  private final InfoPopup featurePopup = new InfoPopup(300, 260, false);
  /**
   * {@code null} = all names. Otherwise selected keys from the filter dialog
   * (feature names and optionally {@link BedFeatureNameCatalog#OTHER_NAME}).
   */
  private Set<String> selectedNameKeys;
  /** Names that appeared as individual rows in the filter dialog (excludes Other). */
  private Set<String> listedDialogNames;

  /** Fixed interval-bar height (px); adjustable in track settings. */
  private double barHeightPixels = DEFAULT_BAR_HEIGHT_PX;
  /** When true and scores exist, paint score histograms instead of flat bars. */
  private boolean showScores;
  /** Lazily detected: any feature with a non-zero score. */
  private Boolean hasScores;
  /**
   * When not {@link BedVariantAnnotation.Mode#OFF}, this track filters sample
   * variants by genomic overlap (see {@link BedVariantAnnotation}).
   */
  private BedVariantAnnotation.Mode variantAnnotationMode = BedVariantAnnotation.Mode.OFF;

  /** Last stacked layout for hit-testing (track-local Y). */
  private List<BedHit> lastStackedHits = List.of();
  private double lastStackTrackHeight;
  private boolean lastStackScoresMode;

  /** Hit result for hover/click on a painted BED interval. */
  public record BedHit(
      BedFeature feature, double x1, double y1, double width, double height) {
  }

  public BedTrack(Path filePath) throws IOException {
    super(filePath.getFileName().toString(), "BED");
    this.sourcePath = filePath.toAbsolutePath().normalize();
    this.sourceFileBytes = Files.size(this.sourcePath);
    this.preferredHeight = 25;
    this.color = Color.rgb(70, 130, 180);
    this.derivedSources = List.of();

    if (IndexedBedReader.hasTabixIndex(this.sourcePath)) {
      this.indexedReader = new IndexedBedReader(this.sourcePath);
      this.featuresByChrom = Map.of();
      this.chromPrefix = indexedReader.getChromPrefix();
      this.fullyMaterialized = false;
      this.hasScores = probeFileHasScores(this.sourcePath);
    } else {
      if (sourceFileBytes > BedFileReader.FULL_LOAD_MAX_BYTES) {
        System.err.println(
            "BED file is large (" + (sourceFileBytes / (1024 * 1024)) + " MB) and has no .tbi index; "
                + "loading fully into memory. Prefer bgzip + tabix for files like RepeatMasker.");
      }
      this.indexedReader = null;
      BedFileReader.BedLoad load = BedFileReader.readLoad(this.sourcePath, this.color);
      this.featuresByChrom = load.featuresByChrom();
      this.chromPrefix = load.chromPrefix();
      this.fullyMaterialized = true;
      this.hasScores = detectScores(this.featuresByChrom);
    }
  }

  private BedTrack(
      String name,
      String type,
      Map<String, List<BedFeature>> featuresByChrom,
      String chromPrefix,
      List<BedTrack> derivedSources) {
    super(name, type);
    this.sourcePath = null;
    this.sourceFileBytes = 0;
    this.indexedReader = null;
    this.preferredHeight = 25;
    this.color = Color.rgb(70, 130, 180);
    this.featuresByChrom = featuresByChrom != null ? featuresByChrom : Map.of();
    this.chromPrefix = chromPrefix != null ? chromPrefix : "";
    this.derivedSources = derivedSources != null
        ? List.copyOf(derivedSources)
        : List.of();
    this.fullyMaterialized = true;
    this.visible = true;
    this.hasScores = detectScores(this.featuresByChrom);
  }

  public double getBarHeightPixels() {
    return barHeightPixels;
  }

  public void setBarHeightPixels(double heightPx) {
    this.barHeightPixels = Math.max(MIN_BAR_HEIGHT_PX, Math.min(MAX_BAR_HEIGHT_PX, heightPx));
  }

  public boolean isShowScores() {
    return showScores;
  }

  public void setShowScores(boolean showScores) {
    this.showScores = showScores && hasScores();
  }

  /** True if any loaded features carry a non-zero BED score (column 5). */
  public boolean hasScores() {
    if (hasScores != null) {
      return hasScores;
    }
    if (!regionCache.isEmpty()) {
      hasScores = detectScoresInList(regionCache);
      if (Boolean.TRUE.equals(hasScores)) {
        return true;
      }
    }
    return false;
  }

  /** In-memory BED track produced by a set operation. */
  public static BedTrack derived(
      String name,
      String type,
      Map<String, List<BedFeature>> featuresByChrom,
      String chromPrefix,
      List<BedTrack> sources) {
    return new BedTrack(name, type, featuresByChrom, chromPrefix, sources);
  }

  public boolean isDerived() {
    return sourcePath == null && indexedReader == null && fullyMaterialized;
  }

  /** True when all chromosomes are held in memory (set-ops safe). */
  public boolean isFullyMaterialized() {
    return fullyMaterialized;
  }

  public boolean isIndexed() {
    return indexedReader != null;
  }

  public BedVariantAnnotation.Mode getVariantAnnotationMode() {
    return variantAnnotationMode != null ? variantAnnotationMode : BedVariantAnnotation.Mode.OFF;
  }

  /**
   * Direct mode setter (no exclusivity / VCF refresh). Prefer
   * {@link BedVariantAnnotation#setMode} from UI; session restore may call this.
   */
  public void setVariantAnnotationMode(BedVariantAnnotation.Mode mode) {
    this.variantAnnotationMode = mode != null ? mode : BedVariantAnnotation.Mode.OFF;
  }

  /**
   * Features for variant annotation filtering on {@code chromosome} (name filter
   * applied). Materialized tracks return the full chromosome; tabix tracks query
   * the whole contig up to {@code maxFeatures}.
   */
  public List<BedFeature> getAnnotationFeatures(String chromosome, int maxFeatures) {
    if (chromosome == null) {
      return List.of();
    }
    String bare = ChromosomeNames.strip(chromosome);
    if (indexedReader != null) {
      try {
        return filterByIncludedNames(
            indexedReader.query(bare, 0L, Integer.MAX_VALUE, Math.max(1, maxFeatures)));
      } catch (IOException ex) {
        System.err.println("BED annotation query failed for " + getName() + ": " + ex.getMessage());
        return List.of();
      }
    }
    return filterByIncludedNames(featuresByChrom.getOrDefault(bare, List.of()));
  }

  public List<BedTrack> getDerivedSources() {
    return derivedSources;
  }

  public void setOnDataLoaded(Runnable callback) {
    this.onDataLoaded = callback;
  }

  /** Clear name filter (accept all feature names). */
  public void setIncludedFeatureNames(Set<String> names) {
    if (names == null) {
      setNameFilter(null, null);
    } else {
      setNameFilter(names, names);
    }
  }

  /**
   * Apply filter-dialog outcome. {@code selectedKeys == null} means load all.
   * When {@link BedFeatureNameCatalog#OTHER_NAME} is selected, names that were
   * not listed in the dialog are also accepted.
   */
  public void setNameFilter(Set<String> selectedKeys, Set<String> listedNames) {
    if (selectedKeys == null) {
      this.selectedNameKeys = null;
      this.listedDialogNames = null;
    } else {
      this.selectedNameKeys = Set.copyOf(selectedKeys);
      this.listedDialogNames = listedNames != null ? Set.copyOf(listedNames) : Set.of();
    }
    clearRegionCache();
  }

  /** {@code null} means all feature names are accepted. */
  public Set<String> getSelectedNameKeys() {
    return selectedNameKeys;
  }

  /** Whether this track can reopen the file-based feature-type dialog. */
  public boolean supportsFeatureNameFilterDialog() {
    return sourcePath != null;
  }

  public boolean acceptsFeatureName(String name) {
    if (selectedNameKeys == null) {
      return true;
    }
    String key = name == null ? "" : name;
    if (selectedNameKeys.contains(key)) {
      return true;
    }
    boolean otherOn = selectedNameKeys.contains(BedFeatureNameCatalog.OTHER_NAME);
    if (otherOn && listedDialogNames != null && !listedDialogNames.contains(key)) {
      return true;
    }
    return false;
  }

  private void clearRegionCache() {
    fetchGeneration.incrementAndGet();
    cacheChrom = "";
    cacheStart = -1;
    cacheEnd = -1;
    regionCache = List.of();
    loading = false;
  }

  /**
   * Zoom limit applies only to indexed BED files whose on-disk size is at or
   * above Settings → Large BED size (default 200 MB).
   */
  public boolean requiresZoomLimit() {
    if (indexedReader == null) {
      return false;
    }
    return sourceFileBytes >= Settings.get().getLargeBedZoomLimitBytes();
  }

  public boolean isViewTooLargeForIndexed(long viewStart, long viewEnd) {
    if (!requiresZoomLimit()) {
      return false;
    }
    return viewEnd - viewStart > Settings.get().getLargeBedMaxViewLength();
  }

  public long getMaxIndexedViewBp() {
    return Settings.get().getLargeBedMaxViewLength();
  }

  @Override
  public Path getSourcePath() {
    return sourcePath;
  }

  @Override
  public boolean isLoading() {
    return loading;
  }

  @Override
  public void dispose() {
    featurePopup.hide();
    fetchGeneration.incrementAndGet();
    if (indexedReader != null) {
      indexedReader.close();
    }
    regionCache = List.of();
  }

  @Override
  public void onRegionChanged(String chromosome, long start, long end, DrawStack drawStack) {
    if (!visible || indexedReader == null) {
      return;
    }
    if (drawStack != null && drawStack.nav != null && drawStack.nav.animationRunning) {
      return;
    }
    requestRegion(chromosome, start, end, true);
  }

  /**
   * Ensure features for {@code [start,end]} (view coords, same as drawStack) are
   * available. For indexed tracks this may kick an async tabix fetch.
   */
  public void prepareRegion(String chromosome, long start, long end) {
    if (indexedReader == null) {
      return;
    }
    requestRegion(chromosome, start, end, true);
  }

  private void requestRegion(String chromosome, long start, long end, boolean async) {
    if (indexedReader == null || chromosome == null || end <= start) {
      return;
    }
    String bare = ChromosomeNames.strip(chromosome);
    if (isViewTooLargeForIndexed(start, end)) {
      // Drop any prior huge cache so zoom-out cannot retain millions of features.
      if (!regionCache.isEmpty() || cacheStart >= 0) {
        clearRegionCache();
        Platform.runLater(() -> {
          if (onDataLoaded != null) {
            onDataLoaded.run();
          } else {
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
          }
        });
      }
      return;
    }
    if (cacheCovers(bare, start, end)) {
      return;
    }
    if (loading) {
      // Coalesce: an in-flight fetch is already running; next draw will refresh.
      return;
    }
    long paddedStart = Math.max(0, start - QUERY_PADDING_BP);
    long paddedEnd = end + QUERY_PADDING_BP;
    if (requiresZoomLimit()) {
      long maxView = getMaxIndexedViewBp();
      if (paddedEnd - paddedStart > maxView + 2 * QUERY_PADDING_BP) {
        paddedStart = start;
        paddedEnd = end;
      }
    }
    final long padStart = paddedStart;
    final long padEnd = paddedEnd;
    int regionBp = (int) Math.min(Integer.MAX_VALUE, Math.max(0, padEnd - padStart));
    FetchManager fm = FetchManager.get();
    if (!fm.canFetch(FetchManager.FetchType.FEATURE_TRACK, regionBp)) {
      return;
    }
    int generation = fetchGeneration.incrementAndGet();
    loading = true;

    Runnable fetch = () -> {
      FetchManager.FetchTicket ticket = fm.acquire(
          FetchManager.FetchType.FEATURE_TRACK, this, null, bare, (int) padStart, (int) padEnd);
      try {
        // View coords are 1-based-ish; BED storage is 0-based half-open.
        long start0 = Math.max(0, padStart - 1);
        long end0 = Math.max(start0 + 1, padEnd);
        // Cap only for large-BED zoom-limited tracks; otherwise return the full
        // window (whole-chromosome views were truncating to the first 25k hits).
        int maxFeatures = requiresZoomLimit()
            ? IndexedBedReader.DEFAULT_MAX_FEATURES
            : Integer.MAX_VALUE;
        List<BedFeature> features = filterByIncludedNames(
            indexedReader.query(bare, start0, end0, maxFeatures));
        if (generation != fetchGeneration.get() || ticket.isCancelled()) {
          return;
        }
        regionCache = features;
        cacheChrom = bare;
        cacheStart = padStart;
        cacheEnd = padEnd;
      } catch (IOException ex) {
        if (generation == fetchGeneration.get()) {
          System.err.println("BED tabix query failed: " + ex.getMessage());
          regionCache = List.of();
        }
      } finally {
        fm.release(ticket);
        if (generation == fetchGeneration.get()) {
          loading = false;
          Platform.runLater(() -> {
            if (onDataLoaded != null) {
              onDataLoaded.run();
            } else {
              GenomicCanvas.update.set(!GenomicCanvas.update.get());
            }
          });
        }
      }
    };

    if (async) {
      QUERY_POOL.execute(fetch);
    } else {
      fetch.run();
    }
  }

  private boolean cacheCovers(String bareChrom, long start, long end) {
    return bareChrom != null
        && bareChrom.equals(cacheChrom)
        && start >= cacheStart
        && end <= cacheEnd
        && cacheStart >= 0;
  }

  @Override
  public void draw(GraphicsContext gc, double x, double y, double width, double height,
                   String chromosome, double start, double end) {
    gc.setFill(Color.rgb(28, 28, 32));
    gc.fillRect(x, y, width, height);

    if (isIndexed() && isViewTooLargeForIndexed((long) start, (long) end)) {
      gc.setFill(Color.rgb(140, 140, 150));
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.LEFT);
      double maxMb = getMaxIndexedViewBp() / 1_000_000.0;
      gc.fillText(
          "Zoom to < " + String.format("%.1f", maxMb) + " Mb to load features",
          x + 4, y + height / 2 + 4);
      return;
    }

    List<BedFeature> features = getFeaturesOverlapping(chromosome, (long) start, (long) end);
    if (features.isEmpty()) {
      if (isIndexed() && loading) {
        gc.setFill(Color.rgb(120, 120, 120));
        gc.setFont(AppFonts.getUIFont(8));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("Loading…", x + 4, y + height / 2 + 3);
      } else if (isIndexed() && !loading) {
        gc.setFill(Color.rgb(120, 120, 120));
        gc.setFont(AppFonts.getUIFont(8));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("No features in view", x + 4, y + height - 4);
      }
      return;
    }

    noteScores(features);
    FeatureLayout layout = layoutForRow(y, height);
    double viewLength = end - start;
    if (viewLength <= 0) {
      return;
    }

    boolean scoresMode = showScores && hasScores();
    if (scoresMode) {
      drawScoresMode(gc, x, y, width, height, start, end, viewLength, features, layout);
      return;
    }

    // Stack only when zoomed in enough that name labels are shown.
    if (anyBedLabelVisible(features, start, end, viewLength, width)) {
      drawStackedBars(gc, x, y, width, height, start, end, viewLength, features);
    } else {
      drawSingleLaneBars(gc, x, y, width, height, start, end, viewLength, features, layout);
    }
    gc.setTextAlign(TextAlignment.LEFT);
  }

  private static boolean anyBedLabelVisible(
      List<BedFeature> features, double start, double end, double viewLength, double width) {
    int from = findFirstOverlappingIndex(features, start, end);
    for (int i = from; i < features.size(); i++) {
      BedFeature feature = features.get(i);
      if (feature.start() + 1 > end) {
        break;
      }
      if (feature.end() < start) {
        continue;
      }
      double featureWidth =
          Math.max(1, ((feature.end() - feature.start()) / viewLength) * width);
      if (featureWidth >= MIN_LABEL_WIDTH_PX && !feature.name().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private void drawSingleLaneBars(
      GraphicsContext gc, double x, double y, double width, double height,
      double start, double end, double viewLength, List<BedFeature> features,
      FeatureLayout layout) {
    lastStackedHits = List.of();
    lastStackScoresMode = false;
    lastStackTrackHeight = height;
    int lastDrawnPixelX = Integer.MIN_VALUE;
    int from = findFirstOverlappingIndex(features, start, end);
    List<BedHit> painted = new ArrayList<>();
    for (int i = from; i < features.size(); i++) {
      BedFeature feature = features.get(i);
      if (feature.start() + 1 > end) {
        break;
      }
      if (feature.end() < start) {
        continue;
      }
      double featureX1 = Math.max(x, x + ((feature.start() + 1 - start) / viewLength) * width);
      double featureX2 = Math.min(x + width, x + ((feature.end() - start) / viewLength) * width);
      double featureWidth = Math.max(1, featureX2 - featureX1);
      int xPixel = (int) featureX1;
      boolean singlePixel = (int) featureX2 <= xPixel;
      if (singlePixel && xPixel == lastDrawnPixelX) {
        continue;
      }
      double barY = layout.featureY();
      double barH = layout.featureHeight();
      gc.setFill(colorForFeature(feature));
      gc.fillRect(featureX1, barY, featureWidth, barH);
      if (singlePixel) {
        lastDrawnPixelX = xPixel;
      }
      painted.add(new BedHit(feature, featureX1 - x, barY - y, featureWidth, barH));
    }
    lastStackedHits = painted;
  }

  private void drawScoresMode(
      GraphicsContext gc, double x, double y, double width, double height,
      double start, double end, double viewLength, List<BedFeature> features,
      FeatureLayout layout) {
    double[] range = scoreRange(features);
    double scoreMin = minValue != null ? minValue : range[0];
    double scoreMax = maxValue != null ? maxValue : range[1];
    if (scoreMax <= scoreMin) {
      scoreMax = scoreMin + 1;
    }
    lastStackedHits = List.of();
    lastStackScoresMode = true;
    lastStackTrackHeight = height;

    int lastDrawnPixelX = Integer.MIN_VALUE;
    int from = findFirstOverlappingIndex(features, start, end);
    for (int i = from; i < features.size(); i++) {
      BedFeature feature = features.get(i);
      if (feature.start() + 1 > end) {
        break;
      }
      if (feature.end() < start) {
        continue;
      }
      double featureX1 = Math.max(x, x + ((feature.start() + 1 - start) / viewLength) * width);
      double featureX2 = Math.min(x + width, x + ((feature.end() - start) / viewLength) * width);
      double featureWidth = Math.max(1, featureX2 - featureX1);
      int xPixel = (int) featureX1;
      boolean singlePixel = (int) featureX2 <= xPixel;
      if (singlePixel && xPixel == lastDrawnPixelX) {
        continue;
      }
      double score = Double.isNaN(feature.score()) ? 0 : feature.score();
      double t = (score - scoreMin) / (scoreMax - scoreMin);
      t = Math.max(0, Math.min(1, t));
      double barH = Math.max(1, layout.featureHeight() * t);
      double barY = layout.featureY() + layout.featureHeight() - barH;
      gc.setFill(colorForFeature(feature));
      gc.fillRect(featureX1, barY, featureWidth, barH);
      if (singlePixel) {
        lastDrawnPixelX = xPixel;
      }
      if (featureWidth >= MIN_LABEL_WIDTH_PX && !feature.name().isEmpty()) {
        drawFeatureNameLabel(gc, feature.name(), featureX1 + 2, barY - 2);
      }
    }
  }

  private void drawStackedBars(
      GraphicsContext gc, double x, double y, double width, double height,
      double start, double end, double viewLength, List<BedFeature> features) {
    double barH = Math.max(MIN_BAR_HEIGHT_PX, Math.min(MAX_BAR_HEIGHT_PX, barHeightPixels));
    double idealLanePitch = NAME_SLOT_H + barH + LANE_GAP;

    StackingAlgorithm.StackResult<BedFeature> stacked =
        FEATURE_STACKER.stack(features, start, end, width);
    int usedRows = 0;
    for (int row = 0; row < stacked.getRowCount(); row++) {
      if (!stacked.getRow(row).isEmpty()) {
        usedRows = row + 1;
      }
    }
    if (usedRows == 0) {
      lastStackedHits = List.of();
      lastStackScoresMode = false;
      lastStackTrackHeight = height;
      return;
    }

    double available = Math.max(idealLanePitch, height - ROW_TOP_PAD - 2);
    double lanePitch = idealLanePitch;
    double drawBarH = barH;
    if (usedRows * idealLanePitch > available) {
      lanePitch = available / usedRows;
      drawBarH = Math.max(MIN_BAR_HEIGHT_PX, lanePitch - NAME_SLOT_H - LANE_GAP);
    }

    setPreferredHeight(ROW_TOP_PAD + usedRows * idealLanePitch + 2);

    List<BedHit> stackedHits = new ArrayList<>();
    for (int row = 0; row < usedRows; row++) {
      double laneTop = y + ROW_TOP_PAD + row * lanePitch;
      double barY = laneTop + NAME_SLOT_H;
      for (BedFeature feature : stacked.getRow(row)) {
        double featureX1 = Math.max(x, x + ((feature.start() + 1 - start) / viewLength) * width);
        double featureX2 = Math.min(x + width, x + ((feature.end() - start) / viewLength) * width);
        double featureWidth = Math.max(1, featureX2 - featureX1);
        gc.setFill(colorForFeature(feature));
        gc.fillRect(featureX1, barY, featureWidth, drawBarH);
        if (featureWidth >= MIN_LABEL_WIDTH_PX && !feature.name().isEmpty()) {
          drawFeatureNameLabel(gc, feature.name(), featureX1 + 2, barY - 2);
        }
        stackedHits.add(new BedHit(
            feature, featureX1 - x, barY - y, featureWidth, drawBarH));
      }
    }
    lastStackedHits = stackedHits;
    lastStackScoresMode = false;
    lastStackTrackHeight = height;
  }

  /**
   * Prefer BED itemRgb when present; otherwise stable name-hash (or a neutral
   * fallback for unnamed features without a color).
   */
  public static Color colorForFeature(BedFeature feature) {
    if (feature == null) {
      return FeatureNameColors.colorForName("");
    }
    if (feature.color() != null) {
      return feature.color();
    }
    return FeatureNameColors.colorForName(feature.name());
  }

  /** Plain white name label; {@code textY} is the baseline just above the bar. */
  public static void drawFeatureNameLabel(
      GraphicsContext gc, String name, double textX, double textY) {
    if (name == null || name.isEmpty()) {
      return;
    }
    gc.setFont(AppFonts.getUIFont(9));
    gc.setFill(Color.WHITE);
    gc.fillText(name, textX, textY);
  }

  private record FeatureLayout(double featureY, double featureHeight, boolean scorePlot) {}

  private FeatureLayout layoutForRow(double rowY, double rowHeight) {
    if (showScores && hasScores()) {
      double plotTop = rowY + ROW_TOP_PAD;
      double plotH = Math.max(barHeightPixels, rowHeight - ROW_TOP_PAD - 2);
      return new FeatureLayout(plotTop, plotH, true);
    }
    double barH = Math.max(MIN_BAR_HEIGHT_PX, Math.min(MAX_BAR_HEIGHT_PX, barHeightPixels));
    double blockH = NAME_SLOT_H + barH;
    double available = Math.max(blockH, rowHeight - ROW_TOP_PAD - 2);
    double blockTop = rowY + ROW_TOP_PAD + Math.max(0, (available - blockH) / 2);
    return new FeatureLayout(blockTop + NAME_SLOT_H, barH, false);
  }

  /**
   * Hit-test a point in track-local coordinates. Only returns a hit when the
   * feature is wide enough on screen (zoomed in).
   */
  public BedHit hitTest(
      double clickX, double clickY, double trackWidth, double trackHeight,
      String chromosome, double viewStart, double viewEnd) {
    if (trackWidth <= 0 || viewEnd <= viewStart) {
      return null;
    }
    // Stacked interval mode: use last draw layout.
    if (!lastStackScoresMode && !lastStackedHits.isEmpty()
        && Math.abs(trackHeight - lastStackTrackHeight) <= 1) {
      for (int i = lastStackedHits.size() - 1; i >= 0; i--) {
        BedHit sh = lastStackedHits.get(i);
        if (sh.width() < MIN_INTERACTIVE_WIDTH_PX) {
          continue;
        }
        if (clickX >= sh.x1() && clickX <= sh.x1() + sh.width()
            && clickY >= sh.y1() - NAME_SLOT_H && clickY <= sh.y1() + sh.height() + 2) {
          return sh;
        }
      }
      return null;
    }

    FeatureLayout layout = layoutForRow(0, trackHeight);
    double hitTop = layout.featureY() - (layout.scorePlot() ? 0 : NAME_SLOT_H);
    double hitBottom = layout.featureY() + layout.featureHeight();
    if (clickY < hitTop - 2 || clickY > hitBottom + 2) {
      return null;
    }
    List<BedFeature> features =
        getFeaturesOverlapping(chromosome, (long) viewStart, (long) viewEnd);
    if (features.isEmpty()) {
      return null;
    }
    noteScores(features);
    boolean scoresMode = showScores && hasScores();
    if (!scoresMode) {
      return null;
    }
    double[] range = scoreRange(features);
    double scoreMin = minValue != null ? minValue : range[0];
    double scoreMax = maxValue != null ? maxValue : range[1];
    if (scoreMax <= scoreMin) {
      scoreMax = scoreMin + 1;
    }
    double viewLength = viewEnd - viewStart;
    int from = findFirstOverlappingIndex(features, viewStart, viewEnd);
    BedHit hit = null;
    for (int i = from; i < features.size(); i++) {
      BedFeature feature = features.get(i);
      if (feature.start() + 1 > viewEnd) {
        break;
      }
      if (feature.end() < viewStart) {
        continue;
      }
      double x1 = Math.max(0, ((feature.start() + 1 - viewStart) / viewLength) * trackWidth);
      double x2 = Math.min(trackWidth, ((feature.end() - viewStart) / viewLength) * trackWidth);
      double w = Math.max(1, x2 - x1);
      if (w < MIN_INTERACTIVE_WIDTH_PX) {
        continue;
      }
      if (clickX < x1 || clickX > x1 + w) {
        continue;
      }
      double score = Double.isNaN(feature.score()) ? 0 : feature.score();
      double t = (score - scoreMin) / (scoreMax - scoreMin);
      t = Math.max(0, Math.min(1, t));
      double barH = Math.max(1, layout.featureHeight() * t);
      double barY = layout.featureY() + layout.featureHeight() - barH;
      if (clickY >= barY - NAME_SLOT_H && clickY <= barY + barH + 2) {
        hit = new BedHit(feature, x1, barY, w, barH);
      }
    }
    return hit;
  }

  private void noteScores(List<BedFeature> features) {
    if (Boolean.TRUE.equals(hasScores) || features == null || features.isEmpty()) {
      return;
    }
    if (detectScoresInList(features)) {
      hasScores = true;
    }
  }

  private static boolean detectScores(Map<String, List<BedFeature>> byChrom) {
    if (byChrom == null) {
      return false;
    }
    for (List<BedFeature> list : byChrom.values()) {
      if (detectScoresInList(list)) {
        return true;
      }
    }
    return false;
  }

  /** Sample the start of a BED file for a non-zero score column (for tabix tracks). */
  private static boolean probeFileHasScores(Path path) {
    if (path == null) {
      return false;
    }
    boolean gzipped = path.toString().endsWith(".gz");
    try (var in = gzipped
            ? new java.util.zip.GZIPInputStream(Files.newInputStream(path))
            : Files.newInputStream(path);
         var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
      String line;
      int checked = 0;
      while ((line = reader.readLine()) != null && checked < 200) {
        if (line.isEmpty()
            || line.charAt(0) == '#'
            || line.startsWith("track")
            || line.startsWith("browser")) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 5) {
          continue;
        }
        checked++;
        try {
          double score = Double.parseDouble(parts[4].trim());
          if (score != 0 && !Double.isNaN(score)) {
            return true;
          }
        } catch (NumberFormatException ignored) {
        }
      }
    } catch (IOException ignored) {
    }
    return false;
  }

  private static boolean detectScoresInList(List<BedFeature> features) {
    if (features == null) {
      return false;
    }
    for (BedFeature f : features) {
      if (f != null && !Double.isNaN(f.score()) && f.score() != 0) {
        return true;
      }
    }
    return false;
  }

  private static double[] scoreRange(List<BedFeature> features) {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (BedFeature f : features) {
      if (f == null || Double.isNaN(f.score())) {
        continue;
      }
      min = Math.min(min, f.score());
      max = Math.max(max, f.score());
    }
    if (!Double.isFinite(min) || !Double.isFinite(max)) {
      return new double[] {0, 1};
    }
    return new double[] {min, max};
  }

  @Override
  public boolean supportsClick() {
    return true;
  }

  @Override
  public boolean handleClick(
      double clickX, double clickY, double trackWidth, double trackHeight,
      String chromosome, double viewStart, double viewEnd,
      Window owner, double screenX, double screenY) {
    BedHit hit = hitTest(clickX, clickY, trackWidth, trackHeight, chromosome, viewStart, viewEnd);
    if (hit == null) {
      return false;
    }
    BedFeature f = hit.feature();
    Color color = colorForFeature(f);
    String title = f.name() == null || f.name().isEmpty() ? "(unnamed)" : f.name();
    PopupContent content = new PopupContent()
        .title(title, color)
        .separator()
        .row("Track", getName())
        .row("Chromosome", f.chrom() != null ? f.chrom() : chromosome)
        .row("Start", String.format("%,d", f.start() + 1))
        .row("End", String.format("%,d", f.end()))
        .row("Length", String.format("%,d bp", Math.max(0, f.end() - f.start())));
    if (f.strand() != null && !f.strand().isEmpty() && !".".equals(f.strand())) {
      content.row("Strand", f.strand());
    }
    if (!Double.isNaN(f.score()) && f.score() != 0) {
      content.row("Score", String.format("%.3g", f.score()));
    }
    featurePopup.show(content, owner, screenX + 12, screenY + 8);
    return true;
  }

  @Override
  public void hidePopup() {
    featurePopup.hide();
  }

  /**
   * Index of the first feature that may overlap [{@code viewStart}, {@code viewEnd}]
   * in a list sorted by {@code start} (then {@code end}). Returns {@code features.size()}
   * if none. Caller should scan forward and stop when {@code start + 1 > viewEnd}.
   */
  public static int findFirstOverlappingIndex(
      List<BedFeature> features, double viewStart, double viewEnd) {
    if (features == null || features.isEmpty()) {
      return 0;
    }
    int lo = 0;
    int hi = features.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (features.get(mid).start() + 1 <= viewStart) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    int i = lo;
    while (i > 0 && features.get(i - 1).end() >= viewStart) {
      i--;
    }
    return i;
  }

  /**
   * Features overlapping the view. Indexed tracks return the viewport cache
   * (and schedule a fetch if needed); materialized tracks binary-search the
   * full chromosome list.
   */
  public List<BedFeature> getFeaturesOverlapping(String chromosome, long viewStart, long viewEnd) {
    if (chromosome == null) {
      return List.of();
    }
    String bare = ChromosomeNames.strip(chromosome);
    if (indexedReader != null) {
      if (isViewTooLargeForIndexed(viewStart, viewEnd)) {
        return List.of();
      }
      if (!cacheCovers(bare, viewStart, viewEnd)) {
        requestRegion(bare, viewStart, viewEnd, true);
      }
      if (!bare.equals(cacheChrom)) {
        return List.of();
      }
      return regionCache;
    }
    List<BedFeature> all = featuresByChrom.getOrDefault(bare, List.of());
    if (all.isEmpty()) {
      return List.of();
    }
    int from = findFirstOverlappingIndex(all, viewStart, viewEnd);
    if (from >= all.size()) {
      return List.of();
    }
    int to = from;
    while (to < all.size() && all.get(to).start() + 1 <= viewEnd) {
      to++;
    }
    return filterByIncludedNames(all.subList(from, to));
  }

  private List<BedFeature> filterByIncludedNames(List<BedFeature> features) {
    if (features == null || features.isEmpty() || selectedNameKeys == null) {
      return features == null ? List.of() : features;
    }
    List<BedFeature> out = new ArrayList<>(Math.min(features.size(), 64));
    for (BedFeature feature : features) {
      if (acceptsFeatureName(feature.name())) {
        out.add(feature);
      }
    }
    return out;
  }

  /**
   * Whole-chromosome features for fully materialized tracks. Indexed tracks
   * return only the current region cache for that chromosome (may be empty).
   */
  public List<BedFeature> getFeatures(String chromosome) {
    return getLoadedFeatures(chromosome);
  }

  /**
   * Features currently held in memory for {@code chromosome}, after the active
   * name filter. Tabix tracks: viewport cache only; materialized: full chrom.
   */
  public List<BedFeature> getLoadedFeatures(String chromosome) {
    if (chromosome == null) {
      return List.of();
    }
    String bare = ChromosomeNames.strip(chromosome);
    if (indexedReader != null) {
      return bare.equals(cacheChrom) ? regionCache : List.of();
    }
    return filterByIncludedNames(featuresByChrom.getOrDefault(bare, List.of()));
  }

  /**
   * Chromosomes that currently have loaded features (for set-ops on whatever
   * is in memory, including tabix viewport caches).
   */
  public Set<String> getLoadedChromosomes() {
    if (indexedReader != null) {
      if (cacheChrom != null && !cacheChrom.isEmpty() && !regionCache.isEmpty()) {
        return Set.of(cacheChrom);
      }
      return Set.of();
    }
    Set<String> chroms = new LinkedHashSet<>();
    for (Map.Entry<String, List<BedFeature>> e : featuresByChrom.entrySet()) {
      List<BedFeature> filtered = filterByIncludedNames(e.getValue());
      if (filtered != null && !filtered.isEmpty()) {
        chroms.add(e.getKey());
      }
    }
    return chroms;
  }

  public Set<String> getChromosomes() {
    if (indexedReader != null) {
      Set<String> bare = new LinkedHashSet<>();
      for (String seq : indexedReader.getSequenceNames()) {
        bare.add(ChromosomeNames.strip(seq));
      }
      return Collections.unmodifiableSet(bare);
    }
    return Collections.unmodifiableSet(featuresByChrom.keySet());
  }
}
