package org.baseplayer.samples.alignment;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.draw.CytobandCanvas;
import org.baseplayer.io.Settings;
import org.baseplayer.io.readers.AlignmentReader;
import org.baseplayer.io.readers.BAMFileReader;
import org.baseplayer.io.readers.CRAMFileReader;
import org.baseplayer.samples.alignment.draw.ReadColorMode;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;

import javafx.application.Platform;

/**
 * Represents a loaded BAM/CRAM alignment file.
 * Wraps an AlignmentReader and caches reads per DrawStack viewport.
 * Fetches are done asynchronously so navigation stays responsive.
 * <p>
 * Generic sample metadata (name, visibility, overlays) lives in
 * {@link org.baseplayer.samples.Sample}; this class handles BAM-specific
 * read fetching, caching, packing, and coverage sampling.
 */
public class AlignmentFile implements Closeable {

  /** Exclusive read stacking modes exposed in track/master settings. */
  public enum ReadStackingMode {
    AUTO("Default"),
    READ_GROUP("Read group split"),
    STRAND_SPLIT("Strand split"),
    DISCORDANT_SPLIT("Discordant/split split"),
    MATE_SIDE_BY_SIDE("Mate side-by-side");

    private final String label;

    ReadStackingMode(String label) { this.label = label; }

    @Override public String toString() { return label; }
  }

  public final String name;
  public final Path path;
  private final AlignmentReader reader;

  // Per-stack caching — each DrawStack has its own cached region and fetch state
  private final ConcurrentHashMap<DrawStack, StackCache> stackCaches = new ConcurrentHashMap<>();

  // Error tracking — suppress repeated messages
  private volatile int consecutiveErrors = 0;
  private static final int MAX_ERROR_LOG = 3;
  /** Fetch this fraction of view-length extra on each side to avoid refetching on tiny movements. */
  private static final double FETCH_BUFFER_FRACTION = 0.3;
  /** How often to publish partial coverage cache updates while streaming. */
  private static final long COVERAGE_PROGRESS_INTERVAL_NS = 60_000_000L;
  /** Maximum view length for BAM queries — now read from Settings. */
  private static int getMaxBamViewLength() { return Settings.get().getMaxCoverageViewLength(); }

  // Per-file fetch executor — each file gets its own thread so BAM and CRAM don't block each other
  private final ExecutorService fetchPool;
  
  // Status message shown in the track (e.g., "No reads found", "Loaded 123 reads")
  private volatile String statusMessage = null;

  // Callback fired once on the JavaFX thread after the
  // first read-fetch attempt completes (whether successful, empty, or error).
  private volatile Runnable onFirstLoadComplete;
  private final AtomicBoolean firstLoadFired = new AtomicBoolean(false);

  /** When true, {@link #getReads} returns empty immediately and no new fetches are started. */
  private volatile boolean suspended = false;

  /**
   * Fired once (then cleared) on the JavaFX thread the moment the first actual fetch
   * is submitted to {@link #fetchPool}. Zoomed-out draw calls that return early never
   * trigger this, so callers can use it to lazily register a "loading reads" task only
   * when a real fetch is about to happen.
   */
  private volatile Runnable onFirstFetchStarted;
  private final AtomicBoolean firstFetchStartedFired = new AtomicBoolean(false);

  // Data type detection (auto-detected from first batch of reads)
  private volatile boolean methylationDetected = false;
  private volatile boolean haplotypeDetected = false;
  private volatile boolean baseModificationsDetected = false; // MM/ML tags present
  private volatile boolean ucTagDetected = false;  // uc:B:s signal tag present
  private volatile boolean udTagDetected = false;  // ud:B:s signal tag present
  private volatile boolean ulTagDetected = false;  // ul:B:s signal tag present
  /** User can override: true = suppress methyl mismatches, null = auto (use methylationDetected). */
  private volatile Boolean suppressMethylMismatches = null;
  
  // Read group support
  /** Detected read group names (populated from first batch). */
  private volatile List<String> detectedReadGroups = new ArrayList<>();
  /** When true, reads are separated into sections by read group. */
  private volatile boolean splitByReadGroup = false;
  /** When true, reads are separated by strand (butterfly layout). */
  private volatile boolean splitByStrand = false;
  /** When true, reads are separated by discordant/SA-tag status (butterfly layout). */
  private volatile boolean splitByDiscordant = false;
  /** When true, paired-end mates are preferentially packed on the same row. */
  private volatile boolean stackMatesSideBySide = false;
  /** Per-file read coloring mode (overrides global default for this track/file). */
  private volatile ReadColorMode readColorMode;
  
  /** Vertical scroll offset for reads within this sample track (in pixels). */
  public volatile double readScrollOffset = 0;
  /** Vertical scroll offset for top half in butterfly layout (forward / HP1). */
  public volatile double readScrollOffsetTop = 0;
  /** Vertical scroll offset for bottom half in butterfly layout (reverse / HP2). */
  public volatile double readScrollOffsetBottom = 0;

  // Per-file coverage cache (keyed by DrawStack), so each file owns all its cached data
  public static class CoverageCache {
    public double[] smoothedCov;
    public double[] rawMmA, rawMmC, rawMmG, rawMmT;
    public double[] methylRatio;  // per-bin methylation ratio (0-1, or -1 = no data)
    public double[] smoothedMethylRatio; // smoothed version for stable display
    public String chrom;
    public boolean methyl;
    public int genomicStart;
    public double binSize;
    public int numBins;
    public double scaleMax;
  }
  private final ConcurrentHashMap<DrawStack, CoverageCache> coverageCaches = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DrawStack, Future<?>> coverageFetches = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DrawStack, CoverageRequest> coverageRequests = new ConcurrentHashMap<>();
  private final ReferenceGenomeService referenceGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();

  private static class CoverageRequest {
    final String chrom;
    final int viewStart;
    final int viewEnd;
    final int fetchStart;
    final int fetchEnd;
    final double binSize;
    final int numBins;
    final boolean methyl;

    CoverageRequest(String chrom, int viewStart, int viewEnd,
                    int fetchStart, int fetchEnd,
                    double binSize, int numBins, boolean methyl) {
      this.chrom = chrom;
      this.viewStart = viewStart;
      this.viewEnd = viewEnd;
      this.fetchStart = fetchStart;
      this.fetchEnd = fetchEnd;
      this.binSize = binSize;
      this.numBins = numBins;
      this.methyl = methyl;
    }

    boolean covers(String c, int start, int end, double requestedBinSize, boolean requestedMethyl) {
      if (!chrom.equals(c)) return false;
      if (requestedMethyl && !methyl) return false;
      if (start < viewStart || end > viewEnd) return false;
      return relativeDifference(binSize, requestedBinSize) < 0.01;
    }
  }

  /** Get currently cached reads for a DrawStack without triggering a new fetch. */
  public List<BAMRecord> getCachedReads(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.cachedReads.get() : Collections.emptyList();
  }

  /**
   * Request a coverage-only matrix cache for the current viewport.
   * <p>
   * This path never stores per-read lists: reads are streamed and reduced
   * directly into bin arrays used for coverage rendering.
   */
  public CoverageCache requestCoverageCache(String chrom, int start, int end,
                                            DrawStack stack, boolean blockDuringNavigation,
                                            boolean isMethyl, int numColumns) {
    if (suspended || numColumns <= 0 || end <= start) return null;

    int viewLength = end - start;
    if (viewLength > getMaxBamViewLength()) {
      clearCoverageCache(stack);
      return null;
    }

    double binSize = (double) viewLength / Math.max(1, numColumns);
    int fetchStart = Math.max(0, start);
    int fetchEnd = end;
    int numBins = Math.max(1, (int) ((fetchEnd - fetchStart) / binSize) + 1);

    CoverageCache cache = coverageCaches.get(stack);
    if (isCoverageCacheValid(cache, chrom, start, end, binSize, isMethyl)) {
      return cache;
    }

    if (stack.nav.lineZoomerActive || stack.nav.animationRunning || CytobandCanvas.isDragging) {
      return null;
    }
    if (blockDuringNavigation && stack.nav.navigating) {
      return null;
    }

    StackCache sc = getCache(stack);
    CoverageRequest req = new CoverageRequest(chrom, start, end, fetchStart, fetchEnd, binSize, numBins, isMethyl);

    synchronized (sc) {
      cache = coverageCaches.get(stack);
      if (isCoverageCacheValid(cache, chrom, start, end, binSize, isMethyl)) {
        return cache;
      }

      CoverageRequest inFlightReq = coverageRequests.get(stack);
      Future<?> inFlight = coverageFetches.get(stack);
      if (inFlight != null && !inFlight.isDone() && inFlightReq != null
          && inFlightReq.covers(chrom, start, end, binSize, isMethyl)) {
        return null;
      }

      if (inFlight != null && !inFlight.isDone()) {
        inFlight.cancel(true);
      }

      FetchManager fm = FetchManager.get();
      if (!fm.canFetch(FetchManager.FetchType.READS, this, stack, chrom, fetchStart, fetchEnd)) {
        return null;
      }

      // Coverage-only mode should not retain row-packed reads.
      if (!sc.cachedReads.get().isEmpty()) {
        sc.cachedReads.set(Collections.emptyList());
      }
      sc.cachedChrom = "";
      sc.cachedStart = -1;
      sc.cachedEnd = -1;
      sc.loading = true;

      coverageRequests.put(stack, req);
      Future<?> fetch = fetchPool.submit(() -> computeCoverageCacheAsync(stack, req));
      coverageFetches.put(stack, fetch);
    }

    return null;
  }

  private boolean isCoverageCacheValid(CoverageCache cache, String chrom, int start, int end,
                                       double binSize, boolean isMethyl) {
    if (cache == null || cache.chrom == null) return false;
    if (!cache.chrom.equals(chrom)) return false;
    if (isMethyl && cache.methylRatio == null) return false;
    if (relativeDifference(cache.binSize, binSize) >= 0.01) return false;
    double cacheEnd = cache.genomicStart + cache.numBins * cache.binSize;
    return start >= cache.genomicStart && end <= cacheEnd;
  }

  private void cancelCoverageFetch(DrawStack stack) {
    Future<?> fetch = coverageFetches.remove(stack);
    if (fetch != null && !fetch.isDone()) {
      fetch.cancel(true);
    }
    coverageRequests.remove(stack);
  }

  // --- Chromosome-level sampled coverage ---
  /** Handles chromosome-level coverage sampling. */
  private final CoverageCalculator coverageCalculator;

  /** Get cached sampled coverage, or null if not yet computed. */
  public CoverageCalculator.SampledCoverage getSampledCoverage(DrawStack stack) {
    return coverageCalculator.getSampledCoverage(stack);
  }

  /** Clear all cached sampled coverage (e.g., when sample points setting changes). */
  public void clearSampledCoverageCache() {
    coverageCalculator.clearSampledCoverageCache();
  }
  
  /** Clear read cache for a specific DrawStack (frees memory when zoomed out or changed location). */
  public void clearReadCache(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    if (sc != null) {
      // Cancel any pending fetch
      Future<?> prev = sc.pendingFetch;
      if (prev != null && !prev.isDone()) {
        prev.cancel(true);
      }
      // Clear cached data
      sc.cachedReads.set(Collections.emptyList());
      sc.cachedChrom = "";
      sc.cachedStart = -1;
      sc.cachedEnd = -1;
      sc.fetchingChrom = "";
      sc.fetchingStart = -1;
      sc.fetchingEnd = -1;
      sc.loading = false;
      sc.readScrollOffset = 0;
      sc.readScrollOffsetTop = 0;
      sc.readScrollOffsetBottom = 0;
    }
  }
  
  /** Clear coverage cache for a specific DrawStack. */
  public void clearCoverageCache(DrawStack stack) {
    cancelCoverageFetch(stack);
    coverageCaches.remove(stack);
  }
  
  /** Clear all caches for a specific DrawStack (reads, coverage, sampled coverage). */
  public void clearAllCaches(DrawStack stack) {
    clearReadCache(stack);
    clearCoverageCache(stack);
    coverageCalculator.removeSampledCoverage(stack);
  }
  
  /** Clear all caches for all stacks (e.g., when file is closed or chromosome changes). */
  public void clearAllCaches() {
    // Cancel all pending fetches
    for (StackCache sc : stackCaches.values()) {
      Future<?> prev = sc.pendingFetch;
      if (prev != null && !prev.isDone()) {
        prev.cancel(true);
      }
    }
    for (Future<?> fetch : coverageFetches.values()) {
      if (fetch != null && !fetch.isDone()) {
        fetch.cancel(true);
      }
    }
    coverageFetches.clear();
    coverageRequests.clear();
    stackCaches.clear();
    coverageCaches.clear();
    coverageCalculator.clearSampledCoverageCache();
  }
  
  /** Get current status message for display in the track. */
  public String getStatusMessage() {
    return statusMessage;
  }

  /**
   * Register a callback that fires exactly once on the JavaFX thread after the
   * first read-fetch attempt completes (whether successful, empty, or error).
   * Safe to call from any thread before or after the first fetch starts.
   */
  public void setOnFirstLoadComplete(Runnable callback) {
    // If the first fetch already finished before we registered, fire immediately.
    if (firstLoadFired.get()) {
      Platform.runLater(callback);
    } else {
      onFirstLoadComplete = callback;
      // Guard against a race where the fetch finished between the check and the assignment.
      if (firstLoadFired.get() && onFirstLoadComplete != null) {
        Runnable cb = onFirstLoadComplete;
        onFirstLoadComplete = null;
        Platform.runLater(cb);
      }
    }
  }

  /**
   * Register a callback fired once on the JavaFX thread right before the first actual
   * fetch is submitted to the thread pool. Never fires for zoomed-out draw calls that
   * return early. Safe to call from any thread.
   */
  public void setOnFirstFetchStarted(Runnable callback) {
    if (firstFetchStartedFired.get()) {
      Platform.runLater(callback);
    } else {
      onFirstFetchStarted = callback;
      if (firstFetchStartedFired.get() && onFirstFetchStarted != null) {
        Runnable cb = onFirstFetchStarted;
        onFirstFetchStarted = null;
        Platform.runLater(cb);
      }
    }
  }

  /**
   * Cancel all in-flight read fetches and suspend future fetches.
   * The draw loop will receive empty read lists until {@link #resume()} is called.
   * Resets the first-load callback so it can be re-armed on reload.
   */
  public void cancelAndSuspend() {
    suspended = true;
    for (StackCache sc : stackCaches.values()) {
      Future<?> f = sc.pendingFetch;
      if (f != null) f.cancel(true);
      sc.loading = false;
      sc.fetchingChrom = "";
      sc.fetchingStart = -1;
      sc.fetchingEnd = -1;
    }
    for (Future<?> f : coverageFetches.values()) {
      if (f != null) f.cancel(true);
    }
    coverageFetches.clear();
    coverageRequests.clear();
    coverageCalculator.cancelSampling();
    // Reset so the callbacks can be re-armed when the user reloads
    firstLoadFired.set(false);
    firstFetchStartedFired.set(false);
  }

  /** Clear the suspension flag so the draw loop can trigger new read fetches. */
  public void resume() {
    suspended = false;
  }

  /** Whether read fetching is currently suspended for this file. */
  public boolean isSuspended() {
    return suspended;
  }

  /** Whether this file contains methylation data (auto-detected or user-overridden). */
  public boolean isMethylationData() {
    Boolean override = suppressMethylMismatches;
    return (override != null) ? override : methylationDetected;
  }
  
  /** Whether this file contains haplotype/phasing data (HP tags detected). */
  public boolean isHaplotypeData() {
    return haplotypeDetected;
  }
  
  /** Set user override for methylation mismatch suppression. Null = auto-detect. */
  public void setSuppressMethylMismatches(Boolean value) {
    this.suppressMethylMismatches = value;
  }

  /**
   * Request chromosome-level sampled coverage for the given region.
   * Delegates to CoverageCalculator.
   */
  public CoverageCalculator.SampledCoverage requestSampledCoverage(String chrom, int start, int end,
                                                 int numSamples, DrawStack stack) {
    return coverageCalculator.requestSampledCoverage(chrom, start, end, numSamples, stack);
  }

  /** Per-stack cache state. */
  private static class StackCache {
    final AtomicReference<List<BAMRecord>> cachedReads = new AtomicReference<>(Collections.emptyList());
    volatile String cachedChrom = "";
    volatile int cachedStart = -1;
    volatile int cachedEnd = -1;
    volatile String fetchingChrom = "";
    volatile int fetchingStart = -1;
    volatile int fetchingEnd = -1;
    volatile int maxRow = 0;
    volatile boolean loading = false;
    volatile Future<?> pendingFetch;
    volatile int lastViewStart = -1;
    volatile int lastViewEnd = -1;
    /** Which signal tag was active when reads were cached: 'c', 'd', or '\0'. */
    volatile char cachedSignalTag = '\0';
    /** The first row where discordant reads are placed (0 if discordant reads exist and are shown at top, -1 = no discordant reads). */
    volatile int discordantStartRow = -1;
    /** The first row where normal/concordant reads are placed (-1 if no separation, or no normal reads). */
    volatile int normalStartRow = -1;
    /** The first row where HP2/unphased reads are placed in allele view (-1 = not in allele view). */
    volatile int hp2StartRow = -1;
    /** The first row where reverse-strand reads are placed in strand-split view (-1 = not in strand view). */
    volatile int strandSplitStartRow = -1;
    /** The first row where concordant reads are placed in discordant-split view (-1 = not in discordant view). */
    volatile int discordantSplitStartRow = -1;
    /** Read group boundary rows: maps RG name → first row in that section. */
    volatile java.util.Map<String, Integer> readGroupStartRows = new java.util.LinkedHashMap<>();
    /** Vertical scroll offset for reads within this stack panel (in pixels). */
    volatile double readScrollOffset = 0;
    /** Vertical scroll offset for top half in butterfly layout (forward / HP1). */
    volatile double readScrollOffsetTop = 0;
    /** Vertical scroll offset for bottom half in butterfly layout (reverse / HP2). */
    volatile double readScrollOffsetBottom = 0;
  }

  private long fileSize = -1;

  /** Returns true if this is a small file (< 100MB) that should use full coverage instead of sampling. */
  public boolean isSmallFile() {
    return fileSize > 0 && fileSize < 100_000_000L;
  }

  /** Get the sample name (from BAM header). */
  public String getName() { return name; }

  /**
   * Create a AlignmentFile from a BAM/CRAM alignment file.
   */
  public AlignmentFile(Path filePath) throws IOException {
    this.path = filePath;
    this.fileSize = Files.size(filePath);
    
    String fileName = filePath.getFileName().toString().toLowerCase();
    if (fileName.endsWith(".cram")) {
      this.reader = new CRAMFileReader(filePath);
    } else {
      this.reader = new BAMFileReader(filePath);
    }
    this.name = reader.getSampleName();
    this.readColorMode = Settings.get().getReadColorMode();
    this.fetchPool = Executors.newSingleThreadExecutor(
        r -> { Thread t = new Thread(r, "fetch-" + this.name); t.setDaemon(true); return t; }
    );
    this.coverageCalculator = new CoverageCalculator(reader, name, fetchPool, this);
  }

  private StackCache getCache(DrawStack stack) {
    return stackCaches.computeIfAbsent(stack, k -> new StackCache());
  }

  /**
   * Get reads for the given region and stack. Returns cached reads immediately.
   * If the region changed, kicks off an async fetch and returns stale data
   * (or empty) until the fetch completes, at which point a redraw is triggered.
   */
  public List<BAMRecord> getReads(String chrom, int start, int end, DrawStack stack) {
    return getReads(chrom, start, end, stack, true);
  }
  
  /**
   * Get reads with optional navigation blocking.
   */
  public List<BAMRecord> getReads(String chrom, int start, int end, DrawStack stack, boolean blockDuringNavigation) {
    // If reads are suspended (cancelled by user), return nothing until resumed
    if (suspended) return Collections.emptyList();

    StackCache sc = getCache(stack);
    cancelCoverageFetch(stack);
    int viewLength = end - start;
    
    // Block all fetches during line zoom - just return cached data
    if (stack.nav.lineZoomerActive) {
      return sc.cachedReads.get();
    }
    
    // Clear cache and cancel in-flight fetches if view is too wide
    if (viewLength > getMaxBamViewLength()) {
      Future<?> prev = sc.pendingFetch;
      if (prev != null && !prev.isDone()) {
        prev.cancel(true);
      }
      if (!sc.cachedReads.get().isEmpty() || sc.fetchingStart >= 0) {
        sc.cachedReads.set(Collections.emptyList());
        sc.cachedChrom = "";
        sc.cachedStart = -1;
        sc.cachedEnd = -1;
        sc.fetchingChrom = "";
        sc.fetchingStart = -1;
        sc.fetchingEnd = -1;
      }
      sc.loading = false;
      return Collections.emptyList();
    }
    
    // Check if requested view overlaps with cached region
    boolean hasOverlap = !sc.cachedChrom.isEmpty() && chrom.equals(sc.cachedChrom) && !(end <= sc.cachedStart || start >= sc.cachedEnd);

    // Invalidate cache if signal tag mode changed (cached reads lack the needed tag data)
    {
      ReadColorMode cm = getReadColorMode();
      char neededSignalTag = cm == ReadColorMode.UC_TAG ? 'c'
                           : cm == ReadColorMode.UD_TAG ? 'd'
                           : cm == ReadColorMode.UL_TAG ? 'l' : '\0';
      if (neededSignalTag != sc.cachedSignalTag && !sc.cachedReads.get().isEmpty()) {
        sc.cachedReads.set(Collections.emptyList());
        sc.cachedChrom = "";
        sc.cachedStart = -1;
        sc.cachedEnd = -1;
        sc.fetchingChrom = "";
        sc.fetchingStart = -1;
        sc.fetchingEnd = -1;
        hasOverlap = false;
      }
    }
    
    // If view has moved too far from cache (no overlap), clear it
    if (!sc.cachedChrom.isEmpty() && !chrom.equals(sc.cachedChrom)) {
      sc.cachedReads.set(Collections.emptyList());
      sc.cachedChrom = "";
      sc.cachedStart = -1;
      sc.cachedEnd = -1;
    } else if (!hasOverlap && !sc.cachedChrom.isEmpty()) {
      sc.cachedReads.set(Collections.emptyList());
      sc.cachedChrom = "";
      sc.cachedStart = -1;
      sc.cachedEnd = -1;
    }
    
    // Fast path: reuse cache if requested region is within cached region.
    // We deliberately do NOT repack here on zoom even if the scale changed
    // significantly — repacking only when new reads are fetched keeps the
    // visible stacking stable while the user zooms or pans within the cached
    // region. (The post-fetch repack below handles the case where new reads
    // come in.)
    if (chrom.equals(sc.cachedChrom) && start >= sc.cachedStart && end <= sc.cachedEnd) {
      return sc.cachedReads.get();
    }

    // Block all new fetches during zoom animation or cytoband dragging
    if (stack.nav.animationRunning || CytobandCanvas.isDragging) {
      return sc.cachedReads.get(); // return stale or empty, no new fetches
    }
    
    // During active navigation, callers that request blocking should reuse cached
    // data only and defer any new fetch start until navigation settles.
    if (blockDuringNavigation && stack.nav.navigating) {
      return sc.cachedReads.get();
    }

    // Synchronize per-cache to prevent duplicate submits
    synchronized (sc) {
      // Re-check after acquiring lock
      if (chrom.equals(sc.cachedChrom) && start >= sc.cachedStart && end <= sc.cachedEnd) {
        return sc.cachedReads.get();
      }
      // If in-flight fetch covers our region, wait for it
      if (chrom.equals(sc.fetchingChrom) && start >= sc.fetchingStart && end <= sc.fetchingEnd) {
        return sc.cachedReads.get();
      }

      // Cancel any in-flight chromosome-level sampling (free the executor for read-level fetch)
      coverageCalculator.cancelSampling();

      // Detect scroll direction and apply directional buffering
      int baseBuffer = Math.max(1000, (int)(viewLength * FETCH_BUFFER_FRACTION));
      int leftBuffer = baseBuffer;
      int rightBuffer = baseBuffer;
      
      // If we have previous view position, detect direction and apply asymmetric buffer
      if (sc.lastViewEnd > 0) {
        boolean movingRight = end > sc.lastViewEnd;
        boolean movingLeft = start < sc.lastViewStart;
        
        if (movingRight && !movingLeft) {
          // Scrolling right: apply 3x buffer to the right, 0.5x to the left
          rightBuffer = baseBuffer * 3;
          leftBuffer = baseBuffer / 2;
        } else if (movingLeft && !movingRight) {
          // Scrolling left: apply 3x buffer to the left, 0.5x to the right
          leftBuffer = baseBuffer * 3;
          rightBuffer = baseBuffer / 2;
        }
        // If both or neither are true (zooming or first fetch), use symmetric buffer
      }
      
      int fetchStart0 = Math.max(0, start - leftBuffer);
      int fetchEnd0 = end + rightBuffer;

      // Cap total fetch region to prevent excessive memory usage on zoom-out
      int maxFetch = getMaxBamViewLength();
      if (fetchEnd0 - fetchStart0 > maxFetch) {
        int excess = (fetchEnd0 - fetchStart0) - maxFetch;
        fetchStart0 += excess / 2;
        fetchEnd0 -= excess / 2;
      }
      final int fetchStart = fetchStart0;
      final int fetchEnd = fetchEnd0;

      // Only cancel in-flight fetch if it won't cover our new buffered region
      Future<?> prev = sc.pendingFetch;
      if (prev != null && !prev.isDone()) {
        boolean fetchCoversNewRegion = chrom.equals(sc.fetchingChrom) 
            && fetchStart >= sc.fetchingStart 
            && fetchEnd <= sc.fetchingEnd;
        
        if (!fetchCoversNewRegion) {
          // Stale fetch - cancel it and start new one
          prev.cancel(true);
          sc.fetchingChrom = "";
          sc.fetchingStart = -1;
          sc.fetchingEnd = -1;
        } else {
          // In-flight fetch will cover us - keep it running
          return sc.cachedReads.get();
        }
      }

      sc.fetchingChrom = chrom;
      sc.fetchingStart = fetchStart;
      sc.fetchingEnd = fetchEnd;
      sc.loading = true;

      // Reset read scroll for this stack panel when fetching a new region
      resetReadScrollOffsets(stack);

      // Check FetchManager before submitting
      final FetchManager fm = FetchManager.get();
      if (!fm.canFetch(FetchManager.FetchType.READS, AlignmentFile.this, stack, chrom, fetchStart, fetchEnd)) {
        System.err.println("Fetch blocked by FetchManager for " + name);
        sc.loading = false;
        sc.fetchingChrom = "";
        sc.fetchingStart = -1;
        sc.fetchingEnd = -1;
        return sc.cachedReads.get();
      }

      System.out.println("Fetching BAM (" + name + "): " + chrom + ":" + fetchStart + "-" + fetchEnd);

      // Notify that a real fetch is starting — fired once, lazily creates the loading task.
      if (firstFetchStartedFired.compareAndSet(false, true)) {
        Runnable cb = onFirstFetchStarted;
        onFirstFetchStarted = null;
        if (cb != null) Platform.runLater(cb);
      }

      sc.pendingFetch = fetchPool.submit(() -> {
        FetchManager.FetchTicket ticket = fm.acquire(FetchManager.FetchType.READS, AlignmentFile.this, stack, chrom, fetchStart, fetchEnd);
        try {
          // Enable signal tag parsing only when UC/UD color mode is active
          ReadColorMode colorMode = getReadColorMode();
          char signalTag = colorMode == ReadColorMode.UC_TAG ? 'c'
                         : colorMode == ReadColorMode.UD_TAG ? 'd'
                         : colorMode == ReadColorMode.UL_TAG ? 'l' : '\0';
          reader.setActiveSignalTag(signalTag);

          List<BAMRecord> reads = new ArrayList<>();
          List<List<int[]>> rowSegments = new ArrayList<>();
          List<List<String>> rowOwners = new ArrayList<>();
          // In mate-side-by-side mode: track where first part of each readName landed so
          // subsequent split parts / mates land on the same row with a bridge gap reserved.
          final boolean sideBySide = stackMatesSideBySide;
          Map<String, int[]> splitMateRow = sideBySide ? new LinkedHashMap<>() : null;
          int[] maxRowLocal = {0};
          // Pixel-based gap: 3 pixels converted to genomic coordinates (matches ReadPacker.MIN_PIXEL_GAP)
          int gap = Math.max(1, (int)(3 * stack.scale));
          long[] lastUpdate = {System.nanoTime()};
          boolean[] detectionDone = {methylationDetected || haplotypeDetected || !detectedReadGroups.isEmpty()};
          final int maxReadCoverage = Settings.get().getMaxReadCoverage();

          reader.queryStreaming(chrom, fetchStart, fetchEnd, record -> {
            if (Thread.currentThread().isInterrupted() || ticket.isCancelled()) {
              return false;
            }
            if (!fm.recordRead(ticket)) {
              System.err.println("Fetch aborted by FetchManager for " + name
                  + " after " + ticket.itemCount.get() + " reads");
              return false;
            }
            // Stop when packing depth (actual coverage) exceeds the limit
            if (maxRowLocal[0] >= maxReadCoverage) {
              return false;
            }
            
            // Incremental CIGAR-aware packing
            int[][] exons = ReadPacker.getExonSegments(record);
            int r = -1;
            int bridgeFrom = -1;

            // In mate-side-by-side mode: prefer same row as the paired mate/split part (all reads).
            // Bridge segments are only added for non-supplementary mates (to avoid huge range reservation).
            // Overlap is allowed only with segments that belong to the same readName.
            if (sideBySide && record.readName != null && splitMateRow != null) {
              int[] pendingPlacement = ReadPacker.findPendingPlacementAllowOwnOverlap(
                  record, splitMateRow, rowSegments, rowOwners, exons, gap);
              if (pendingPlacement != null) {
                r = pendingPlacement[0];
                bridgeFrom = ReadPacker.calcBridgeFrom(record, pendingPlacement[1]);
              }
            }

            if (r < 0) {
              r = ReadPacker.findFittingRow(rowSegments, exons, gap);
            }

            record.row = ReadPacker.placeRecordInRow(rowSegments, rowOwners, record, r, bridgeFrom, exons);
            if (record.row > maxRowLocal[0]) maxRowLocal[0] = record.row;

            if (sideBySide && record.readName != null && splitMateRow != null) {
              ReadPacker.updatePendingPlacement(splitMateRow, record);
            }
            reads.add(record);
            
            // Auto-detect on first 200 reads BEFORE any UI updates
            if (!detectionDone[0] && reads.size() == 200) {
              runAutoDetection(reads);
              detectionDone[0] = true;
            }

            // Progressive display every ~100 ms (but only after detection on first batch)
            long now = System.nanoTime();
            if (now - lastUpdate[0] > 100_000_000L
                && (detectionDone[0] || reads.size() > 200)) {
              sc.maxRow = maxRowLocal[0];
              sc.cachedReads.set(new ArrayList<>(reads));
              sc.cachedSignalTag = signalTag;
              Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
              lastUpdate[0] = now;
            }

            return true;
          });

          // Final state - only update if not cancelled
          if (!Thread.currentThread().isInterrupted() && !ticket.isCancelled()) {
            sc.cachedReads.set(reads);
            sc.cachedChrom = chrom;
            sc.cachedStart = fetchStart;
            sc.cachedEnd = fetchEnd;
            sc.maxRow = maxRowLocal[0];
            sc.lastViewStart = start;
            sc.lastViewEnd = end;
            sc.cachedSignalTag = signalTag;
            consecutiveErrors = 0;
            System.out.println("Fetched BAM (" + name + "): " + reads.size() + " reads");
            
            // Run auto-detection if not done yet (e.g., if fewer than 200 reads total)
            if (!methylationDetected && !haplotypeDetected && detectedReadGroups.isEmpty() && !reads.isEmpty()) {
              runAutoDetection(reads);
            }
            
            // Show status message in the track
            if (reads.isEmpty()) {
              String location = chrom + ":" + fetchStart + "-" + fetchEnd;
              statusMessage = "No reads found at " + location;
            } else {
              StringBuilder msg = new StringBuilder();
              msg.append("Loaded ").append(reads.size()).append(" reads");
              if (methylationDetected) msg.append(" \u2022 Methylation data");
              if (haplotypeDetected) msg.append(" \u2022 Phased (HP)");
              statusMessage = msg.toString();
            }
            
            // Clear status after 5 seconds
            new Timer(true).schedule(new TimerTask() {
              @Override
              public void run() {
                statusMessage = null;
                Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
              }
            }, 5000);
            
            // Repack to optimize row usage now that all reads are loaded
            repackReads(stack);
          }
        } catch (IOException e) {
          if (consecutiveErrors < MAX_ERROR_LOG) {
            System.err.println("Error querying BAM (" + name + "): " + e.getMessage());
          } else if (consecutiveErrors == MAX_ERROR_LOG) {
            System.err.println("Suppressing further BAM errors for " + name);
          }
          consecutiveErrors++;
          sc.fetchingChrom = "";
          sc.fetchingStart = -1;
          sc.fetchingEnd = -1;
        } catch (RuntimeException e) {
          System.err.println("Runtime error in BAM fetch (" + name + "): " + e.getMessage());
          sc.fetchingChrom = "";
          sc.fetchingStart = -1;
          sc.fetchingEnd = -1;
        } finally {
          fm.release(ticket);
          sc.loading = false;
          // Fire the first-load callback once — but not if we are suspended (cancelled),
          // to prevent a racing cancelled fetch from prematurely completing a reload task.
          if (!suspended && firstLoadFired.compareAndSet(false, true)) {
            Runnable cb = onFirstLoadComplete;
            onFirstLoadComplete = null;
            if (cb != null) Platform.runLater(cb);
          }
          Platform.runLater(() -> {
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
          });
        }
      });
    }

    // Return stale cached reads (or empty) so the draw call doesn't block
    return sc.cachedReads.get();
  }

  /**
   * Whether this sample is currently loading reads for the given stack.
   */
  public boolean isLoading(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null && sc.loading;
  }

  /**
   * Maximum row used in the current viewport for the given stack.
   */
  public int getMaxRow(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.maxRow : 0;
  }

  /** Get normal-layout read scroll offset for the given stack panel. */
  public double getReadScrollOffset(DrawStack stack) {
    return getCache(stack).readScrollOffset;
  }

  /** Get top-half butterfly read scroll offset for the given stack panel. */
  public double getReadScrollOffsetTop(DrawStack stack) {
    return getCache(stack).readScrollOffsetTop;
  }

  /** Get bottom-half butterfly read scroll offset for the given stack panel. */
  public double getReadScrollOffsetBottom(DrawStack stack) {
    return getCache(stack).readScrollOffsetBottom;
  }

  /** Set normal-layout read scroll offset for the given stack panel (clamped to >= 0). */
  public void setReadScrollOffset(DrawStack stack, double value) {
    getCache(stack).readScrollOffset = Math.max(0, value);
  }

  /** Set top-half butterfly read scroll offset for the given stack panel (clamped to >= 0). */
  public void setReadScrollOffsetTop(DrawStack stack, double value) {
    getCache(stack).readScrollOffsetTop = Math.max(0, value);
  }

  /** Set bottom-half butterfly read scroll offset for the given stack panel (clamped to >= 0). */
  public void setReadScrollOffsetBottom(DrawStack stack, double value) {
    getCache(stack).readScrollOffsetBottom = Math.max(0, value);
  }

  private static double relativeDifference(double a, double b) {
    double denom = Math.max(1e-9, Math.max(Math.abs(a), Math.abs(b)));
    return Math.abs(a - b) / denom;
  }

  private void computeCoverageCacheAsync(DrawStack stack, CoverageRequest req) {
    FetchManager fm = FetchManager.get();
    FetchManager.FetchTicket ticket = fm.acquire(
        FetchManager.FetchType.READS, AlignmentFile.this, stack, req.chrom, req.fetchStart, req.fetchEnd);
    ThreadRunner.RunnerTask runnerTask = ThreadRunner.get().track(
        "Loading coverage: " + name, () -> ticket.cancelled = true);

    try {
      double[] binCov = new double[req.numBins];
      double[] mmA = new double[req.numBins];
      double[] mmC = new double[req.numBins];
      double[] mmG = new double[req.numBins];
      double[] mmT = new double[req.numBins];
      double[] bisulfiteBin = req.methyl ? new double[req.numBins] : null;
      int[] cgCount = req.methyl ? computeCGBinCounts(req) : null;

      @SuppressWarnings("unchecked")
      java.util.HashMap<Integer, int[]>[] posMM = new java.util.HashMap[req.numBins];

      List<BAMRecord> detectionReads = new ArrayList<>();
      boolean[] detectionDone = {methylationDetected || haplotypeDetected || !detectedReadGroups.isEmpty()};
      long[] lastProgressPublishNs = {System.nanoTime()};

      reader.setActiveSignalTag('\0');
      reader.queryStreaming(req.chrom, req.fetchStart, req.fetchEnd, record -> {
        if (Thread.currentThread().isInterrupted() || ticket.isCancelled()) return false;
        if (!fm.recordRead(ticket)) return false;

        addBinCoverageEvents(record, binCov, req.numBins, req.fetchStart, req.binSize);

        if (!detectionDone[0] && detectionReads.size() < 200) {
          detectionReads.add(record);
          if (detectionReads.size() == 200) {
            runAutoDetection(detectionReads);
            detectionDone[0] = true;
          }
        }

        if (record.mismatches != null) {
          for (int m = 0; m + 2 < record.mismatches.length; m += 3) {
            int pos = record.mismatches[m];
            int bin = (int) ((pos - req.fetchStart) / req.binSize);
            if (bin < 0 || bin >= req.numBins) continue;

            if (req.methyl && bisulfiteBin != null && record.mismatches[m + 2] > 0) {
              char readB = Character.toUpperCase((char) record.mismatches[m + 1]);
              char refB = Character.toUpperCase((char) record.mismatches[m + 2]);
              if ((refB == 'C' && readB == 'T') || (refB == 'G' && readB == 'A')) {
                bisulfiteBin[bin]++;
                continue;
              }
            }

            if (posMM[bin] == null) posMM[bin] = new java.util.HashMap<>();
            int[] c = posMM[bin].computeIfAbsent(pos, k -> new int[4]);
            switch (Character.toUpperCase((char) record.mismatches[m + 1])) {
              case 'A' -> {
                c[0]++;
                if (c[0] > mmA[bin]) mmA[bin] = c[0];
              }
              case 'C' -> {
                c[1]++;
                if (c[1] > mmC[bin]) mmC[bin] = c[1];
              }
              case 'G' -> {
                c[2]++;
                if (c[2] > mmG[bin]) mmG[bin] = c[2];
              }
              case 'T' -> {
                c[3]++;
                if (c[3] > mmT[bin]) mmT[bin] = c[3];
              }
              default -> {
              }
            }
          }
        }

        long now = System.nanoTime();
        if (now - lastProgressPublishNs[0] >= COVERAGE_PROGRESS_INTERVAL_NS) {
          MethylSnapshot partialMethyl = buildMethylSnapshot(req, binCov, bisulfiteBin, cgCount);
          publishCoverageSnapshot(stack, req, binCov, mmA, mmC, mmG, mmT,
              partialMethyl.methylRatio(), partialMethyl.smoothedMethylRatio(), false);
          lastProgressPublishNs[0] = now;
        }
        return true;
      });

      if (Thread.currentThread().isInterrupted() || ticket.isCancelled()) return;

      if (!detectionDone[0] && !detectionReads.isEmpty()) {
        runAutoDetection(detectionReads);
      }

      // Publish an immediate near-final snapshot before methylation post-processing.
      MethylSnapshot partialMethyl = buildMethylSnapshot(req, binCov, bisulfiteBin, cgCount);
      publishCoverageSnapshot(stack, req, binCov, mmA, mmC, mmG, mmT,
          partialMethyl.methylRatio(), partialMethyl.smoothedMethylRatio(), false);

      for (int b = 0; b < req.numBins; b++) {
        if (posMM[b] == null) continue;
        for (int[] c : posMM[b].values()) {
          if (c[0] > mmA[b]) mmA[b] = c[0];
          if (c[1] > mmC[b]) mmC[b] = c[1];
          if (c[2] > mmG[b]) mmG[b] = c[2];
          if (c[3] > mmT[b]) mmT[b] = c[3];
        }
        posMM[b] = null;
      }

      MethylSnapshot finalMethyl = buildMethylSnapshot(req, binCov, bisulfiteBin, cgCount);
      publishCoverageSnapshot(stack, req, binCov, mmA, mmC, mmG, mmT,
          finalMethyl.methylRatio(), finalMethyl.smoothedMethylRatio(), true);
    } catch (IOException e) {
      if (!(e instanceof java.io.IOException && Thread.currentThread().isInterrupted())) {
        System.err.println("Error computing coverage matrix (" + name + "): " + e.getMessage());
      }
    } finally {
      fm.release(ticket);
      runnerTask.complete();
      StackCache sc = stackCaches.get(stack);
      CoverageRequest currentReq = coverageRequests.get(stack);
      if (currentReq == req) {
        coverageRequests.remove(stack);
        coverageFetches.remove(stack);
        if (sc != null) {
          sc.loading = false;
        }
      }
    }
  }

  private int[] computeCGBinCounts(CoverageRequest req) {
    if (!req.methyl || !referenceGenomeService.hasGenome()) return null;

    String covRefBases = referenceGenomeService.getBases(req.chrom, Math.max(1, req.viewStart), req.viewEnd);
    if (covRefBases == null || covRefBases.isEmpty()) return null;

    int[] cgCount = new int[req.numBins];
    for (int i = 0; i < covRefBases.length(); i++) {
      char base = Character.toUpperCase(covRefBases.charAt(i));
      if (base != 'C' && base != 'G') continue;

      int gpos = req.viewStart + i;
      int bin = (int) ((gpos - req.fetchStart) / req.binSize);
      if (bin >= 0 && bin < req.numBins) cgCount[bin]++;
    }
    return cgCount;
  }

  private MethylSnapshot buildMethylSnapshot(CoverageRequest req, double[] binCov,
                                             double[] bisulfiteBin, int[] cgCount) {
    if (!req.methyl) return MethylSnapshot.EMPTY;

    double[] methylRatio = new double[req.numBins];
    if (bisulfiteBin == null || cgCount == null) {
      java.util.Arrays.fill(methylRatio, -1);
      return new MethylSnapshot(methylRatio, methylRatio.clone());
    }

    for (int b = 0; b < req.numBins; b++) {
      if (cgCount[b] > 0 && binCov[b] >= 3) {
        double expectedCov = cgCount[b] * binCov[b];
        double ratio = 1.0 - bisulfiteBin[b] / expectedCov;
        methylRatio[b] = Math.max(0, Math.min(1, ratio));
      } else {
        methylRatio[b] = -1;
      }
    }

    double[] smoothed = methylRatio.clone();
    smoothMethylRatio(smoothed);
    return new MethylSnapshot(methylRatio, smoothed);
  }

  private record MethylSnapshot(double[] methylRatio, double[] smoothedMethylRatio) {
    private static final MethylSnapshot EMPTY = new MethylSnapshot(null, null);
  }

  private void publishCoverageSnapshot(DrawStack stack, CoverageRequest req,
                                       double[] binCov,
                                       double[] mmA, double[] mmC, double[] mmG, double[] mmT,
                                       double[] methylRatio, double[] smoothedMethylRatio,
                                       boolean finalSnapshot) {
    CoverageRequest currentReq = coverageRequests.get(stack);
    if (currentReq != req) return;

    double maxRaw = 0;
    for (double c : binCov) if (c > maxRaw) maxRaw = c;

    double[] covDisplay = binCov.clone();
    if (finalSnapshot) {
      smoothBins(covDisplay);
    }

    double maxDisplay = 0;
    for (double c : covDisplay) if (c > maxDisplay) maxDisplay = c;

    CoverageCache cache = new CoverageCache();
    cache.smoothedCov = covDisplay;
    cache.rawMmA = mmA.clone();
    cache.rawMmC = mmC.clone();
    cache.rawMmG = mmG.clone();
    cache.rawMmT = mmT.clone();
    cache.methylRatio = methylRatio != null ? methylRatio.clone() : null;
    cache.smoothedMethylRatio = smoothedMethylRatio != null ? smoothedMethylRatio.clone() : null;
    cache.chrom = req.chrom;
    cache.methyl = req.methyl;
    cache.genomicStart = req.fetchStart;
    cache.binSize = req.binSize;
    cache.numBins = req.numBins;
    cache.scaleMax = Math.max(maxDisplay, maxRaw);

    coverageCaches.put(stack, cache);
    Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
  }

  /** In-place Gaussian-like smoothing (3-pass box blur). */
  private static void smoothBins(double[] bins) {
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
  private static void smoothMethylRatio(double[] ratios) {
    int n = ratios.length;
    if (n < 5) return;

    int maxGap = Math.max(5, Math.min(30, n / 30));
    int lastValid = -1;
    for (int i = 0; i < n; i++) {
      if (ratios[i] >= 0) {
        if (lastValid >= 0 && i - lastValid <= maxGap) {
          double v0 = ratios[lastValid], v1 = ratios[i];
          for (int j = lastValid + 1; j < i; j++) {
            double t = (double) (j - lastValid) / (i - lastValid);
            ratios[j] = v0 + t * (v1 - v0);
          }
        }
        lastValid = i;
      }
    }

    int radius = Math.max(2, Math.min(15, n / 40));
    double[] tmp = new double[n];
    for (int pass = 0; pass < 3; pass++) {
      for (int i = 0; i < n; i++) {
        if (ratios[i] < 0) {
          tmp[i] = -1;
          continue;
        }
        double sum = 0;
        int cnt = 0;
        for (int k = Math.max(0, i - radius); k <= Math.min(n - 1, i + radius); k++) {
          if (ratios[k] >= 0) {
            sum += ratios[k];
            cnt++;
          }
        }
        tmp[i] = cnt > 0 ? sum / cnt : -1;
      }
      System.arraycopy(tmp, 0, ratios, 0, n);
    }
  }

  /** Add bin-level coverage for exonic segments (coverage-only / zoomed-out mode). */
  private static void addBinCoverageEvents(BAMRecord read, double[] binCov, int numBins,
                                           int binStart, double binSize) {
    if (read.cigarOps == null) {
      addBinEvent(binCov, numBins, binStart, binSize, read.pos, read.end);
      return;
    }
    boolean hasSplice = false;
    for (int cigarOp : read.cigarOps) {
      if ((cigarOp & 0xF) == BAMRecord.CIGAR_N) {
        hasSplice = true;
        break;
      }
    }
    if (!hasSplice) {
      addBinEvent(binCov, numBins, binStart, binSize, read.pos, read.end);
      return;
    }
    int refPos = read.pos;
    for (int cigarOp : read.cigarOps) {
      int op = cigarOp & 0xF;
      int len = cigarOp >>> 4;
      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X, BAMRecord.CIGAR_D -> {
          addBinEvent(binCov, numBins, binStart, binSize, refPos, refPos + len);
          refPos += len;
        }
        case BAMRecord.CIGAR_N -> refPos += len;
        default -> {
        }
      }
    }
  }

  private static void addBinEvent(double[] binCov, int numBins, int binStart, double binSize,
                                  int segStart, int segEnd) {
    int b1 = Math.max(0, (int) ((segStart - binStart) / binSize));
    int b2 = Math.min(numBins - 1, (int) ((segEnd - binStart) / binSize));
    for (int b = b1; b <= b2; b++) binCov[b]++;
  }

  /** Reset all read scroll offsets for the given stack panel. */
  public void resetReadScrollOffsets(DrawStack stack) {
    StackCache sc = getCache(stack);
    sc.readScrollOffset = 0;
    sc.readScrollOffsetTop = 0;
    sc.readScrollOffsetBottom = 0;
  }
  
  /**
   * Get the first row where discordant reads are placed for the given stack.
   * Returns 0 if discordant reads exist (they're shown at the top), or -1 if there are no discordant reads.
   */
  public int getDiscordantStartRow(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.discordantStartRow : -1;
  }

  /**
   * Get the first row where normal/concordant reads are placed for the given stack.
   * Returns -1 if no separation exists (i.e., no discordant reads), or row number if normal reads start after discordant section.
   */
  public int getNormalStartRow(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.normalStartRow : -1;
  }

  /**
   * Get the first row where HP2/unphased reads are placed in allele view.
   * Returns -1 if not in allele view mode.
   */
  public int getHP2StartRow(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.hp2StartRow : -1;
  }

  /**
   * Get the first row where reverse-strand reads are placed in strand-split view.
   * Returns -1 if not in strand-split mode.
   */
  public int getStrandSplitStartRow(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.strandSplitStartRow : -1;
  }

  /**
   * Get the first row where concordant reads are placed in discordant-split view.
   * Returns -1 if not in discordant-split mode.
   */
  public int getDiscordantSplitStartRow(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.discordantSplitStartRow : -1;
  }
  
  /** Get detected read groups. */
  public List<String> getDetectedReadGroups() { return detectedReadGroups; }
  
  /** Whether read group splitting is enabled. */
  public boolean isSplitByReadGroup() { return splitByReadGroup; }
  
  /** Enable/disable read group splitting. Repacks all stacks. */
  public void setSplitByReadGroup(boolean split) { 
    if (split) {
      splitByStrand = false;
      splitByDiscordant = false;
      stackMatesSideBySide = false;
    }
    this.splitByReadGroup = split;
    repackAllStacks();
  }

  /** Whether strand splitting is enabled. */
  public boolean isSplitByStrand() { return splitByStrand; }

  /** Enable/disable strand splitting. Repacks all stacks. */
  public void setSplitByStrand(boolean split) {
    if (split) {
      splitByReadGroup = false;
      splitByDiscordant = false;
      stackMatesSideBySide = false;
    }
    this.splitByStrand = split;
    repackAllStacks();
  }

  /** Whether discordant/split-read splitting is enabled. */
  public boolean isSplitByDiscordant() { return splitByDiscordant; }

  /** Enable/disable discordant/split-read splitting. Repacks all stacks. */
  public void setSplitByDiscordant(boolean split) {
    if (split) {
      splitByReadGroup = false;
      splitByStrand = false;
      stackMatesSideBySide = false;
    }
    this.splitByDiscordant = split;
    repackAllStacks();
  }

  /** Whether mate-side-by-side packing is enabled. */
  public boolean isStackMatesSideBySide() { return stackMatesSideBySide; }

  /** Enable/disable mate-side-by-side packing. Repacks all stacks. */
  public void setStackMatesSideBySide(boolean stackSideBySide) {
    if (stackSideBySide) {
      splitByReadGroup = false;
      splitByStrand = false;
      splitByDiscordant = false;
    }
    this.stackMatesSideBySide = stackSideBySide;
    repackAllStacks();
  }

  /** Get current exclusive read stacking mode. */
  public ReadStackingMode getReadStackingMode() {
    if (splitByReadGroup) return ReadStackingMode.READ_GROUP;
    if (splitByStrand) return ReadStackingMode.STRAND_SPLIT;
    if (splitByDiscordant) return ReadStackingMode.DISCORDANT_SPLIT;
    if (stackMatesSideBySide) return ReadStackingMode.MATE_SIDE_BY_SIDE;
    return ReadStackingMode.AUTO;
  }

  /** Set exclusive read stacking mode. */
  public void setReadStackingMode(ReadStackingMode mode) {
    ReadStackingMode effective = mode != null ? mode : ReadStackingMode.AUTO;
    splitByReadGroup = false;
    splitByStrand = false;
    splitByDiscordant = false;
    stackMatesSideBySide = false;
    switch (effective) {
      case READ_GROUP -> splitByReadGroup = true;
      case STRAND_SPLIT -> splitByStrand = true;
      case DISCORDANT_SPLIT -> splitByDiscordant = true;
      case MATE_SIDE_BY_SIDE -> stackMatesSideBySide = true;
      case AUTO -> {
        // keep all disabled
      }
    }
    repackAllStacks();
  }

  /** Get this file's read color mode. */
  public ReadColorMode getReadColorMode() {
    return readColorMode != null ? readColorMode : Settings.get().getReadColorMode();
  }

  /** Set this file's read color mode. */
  public void setReadColorMode(ReadColorMode mode) {
    this.readColorMode = mode != null ? mode : Settings.get().getReadColorMode();
  }

  /**
   * Get available color modes for this file based on detected data types.
   * Filters out modes that require tags not present in the data.
   * 
   * @return list of ReadColorMode values that can be used with this file
   */
  public java.util.List<ReadColorMode> getAvailableColorModes() {
    java.util.List<ReadColorMode> modes = new java.util.ArrayList<>();
    
    // Always available modes
    modes.add(ReadColorMode.STRAND);
    modes.add(ReadColorMode.BASE_QUALITY);
    
    // Signal tag modes - only show if detected in data
    if (ucTagDetected) modes.add(ReadColorMode.UC_TAG);
    if (udTagDetected) modes.add(ReadColorMode.UD_TAG);
    if (ulTagDetected) modes.add(ReadColorMode.UL_TAG);
    
    // Base modification mode - only show if MM/ML tags were detected
    if (baseModificationsDetected) {
      modes.add(ReadColorMode.BASE_MODIFICATION);
    }
    
    return modes;
  }

  /** Get the read group boundary rows for a given stack. */
  public java.util.Map<String, Integer> getReadGroupStartRows(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.readGroupStartRows : java.util.Collections.emptyMap();
  }

  /**
   * Auto-detect methylation, haplotype, base modifications, and read group data from a sample of reads.
   * Called once on the first 200 reads, before any UI updates.
   */
  private void runAutoDetection(List<BAMRecord> reads) {
    int methylTagCount = 0, hpCount = 0;
    int baseModCount = 0;
    int totalMismatches = 0, bisulfiteMismatches = 0;
    int xmBisulfiteCount = 0; // Count XM:Z bisulfite signatures
    java.util.Set<String> rgSet = new java.util.LinkedHashSet<>();
    int sampleSize = Math.min(reads.size(), 200);
    
    for (int i = 0; i < sampleSize; i++) {
      BAMRecord r = reads.get(i);
      
      // Check for methylation tags
      if (r.hasMethylTag) methylTagCount++;
      
      // Check for base modification tags (MM/ML)
      if (r.baseModifications != null && !r.baseModifications.isEmpty()) baseModCount++;
      
      // Check XM:Z string for bisulfite signature (faster than mismatch analysis)
      // XM:Z format: '.'=match, 'x'=mismatch, 'h'/'z'/'Z'=methylation calls
      if (r.methylString != null && r.methylString.length() > 10) {
        int methyCalls = 0;
        for (char c : r.methylString.toCharArray()) {
          if (c == 'h' || c == 'z' || c == 'Z') methyCalls++;
        }
        if (methyCalls > r.methylString.length() * 0.05) { // >5% methylation calls
          xmBisulfiteCount++;
        }
      }
      
      // Check for haplotype tags
      if (r.haplotype > 0) hpCount++;
      
      // Collect read groups
      if (r.readGroup != null) rgSet.add(r.readGroup);
      
      // Analyze mismatch patterns for bisulfite signature (fallback if no XM:Z)
      if (r.mismatches != null && r.mismatches.length >= 3) {
        for (int m = 0; m + 2 < r.mismatches.length; m += 3) {
          int readBase = r.mismatches[m + 1];
          int refBase = r.mismatches[m + 2];
          if (refBase > 0) {
            totalMismatches++;
            char rB = Character.toUpperCase((char) readBase);
            char refB = Character.toUpperCase((char) refBase);
            // Bisulfite signature: C→T and G→A (both strands affected)
            if ((refB == 'C' && rB == 'T') || (refB == 'G' && rB == 'A')) {
              bisulfiteMismatches++;
            }
          }
        }
      }
    }
    
    // Detect methylation: tags (>20%), XM:Z bisulfite pattern (>50%), or mismatch pattern (>80%)
    if (methylTagCount > sampleSize * 0.2) {
      methylationDetected = true;
      System.out.println("  → Methylation detected via tags (" + methylTagCount + "/" + sampleSize + " reads with MM/ML/XM)");
    } else if (xmBisulfiteCount > sampleSize * 0.5) {
      methylationDetected = true;
      System.out.println("  → Methylation detected via XM:Z bisulfite pattern (" + xmBisulfiteCount + "/" + sampleSize + " reads)");
    } else if (totalMismatches > 50 && bisulfiteMismatches > totalMismatches * 0.8) {
      methylationDetected = true;
      System.out.println("  → Methylation detected via mismatch pattern (" + 
                         bisulfiteMismatches + "/" + totalMismatches + 
                         " = " + (100 * bisulfiteMismatches / totalMismatches) + "% are C→T/G→A bisulfite conversions)");
    }
    
    // Detect base modifications (MM/ML tags) - distinct from bisulfite methylation
    if (baseModCount > sampleSize * 0.1) {
      baseModificationsDetected = true;
      System.out.println("  → Base modifications detected (MM/ML tags: " + baseModCount + "/" + sampleSize + " reads)");
    }
    
    if (hpCount > 0) {
      haplotypeDetected = true;
      System.out.println("  → Haplotype data detected (" + hpCount + "/" + sampleSize + " reads with HP tags)");
    }
    
    // Store detected read groups
    if (rgSet.size() > 1) {
      detectedReadGroups = new ArrayList<>(rgSet);
      System.out.println("  → Read groups detected (" + rgSet.size() + "): " + rgSet);
    } else if (rgSet.size() == 1) {
      detectedReadGroups = new ArrayList<>(rgSet);
      System.out.println("  → Single read group: " + rgSet.iterator().next());
    }
    
    // Detect signal tags (UC, UD, UL) by checking a small sample
    detectSignalTags();
  }

  /**
   * Detect which signal tags (uc, ud, ul) are present in the data.
   * This requires fetching a few reads with each signal tag type enabled.
   */
  private void detectSignalTags() {
    if (reader == null) return;
    
    // Get first chromosome name
    String[] refNames = reader.getRefNames();
    if (refNames == null || refNames.length == 0) return;
    String testChrom = refNames[0];
    
    // Test each signal tag type on a small sample
    char[] tagsToTest = {'c', 'd', 'l'}; // uc, ud, ul
    
    for (char tag : tagsToTest) {
      try {
        // Enable this signal tag
        reader.setActiveSignalTag(tag);
        
        // Try to fetch a few reads from the beginning of the first chromosome
        boolean[] found = {false};
        reader.queryStreaming(testChrom, 0, 100000, record -> {
          if (record.signalTag != null && record.signalTag.length > 0) {
            found[0] = true;
            return false; // Stop after finding first read with signal tag
          }
          return found[0] == false; // Continue only if not found yet
        });
        
        if (found[0]) {
          switch (tag) {
            case 'c' -> {
              ucTagDetected = true;
              System.out.println("  → UC signal tag detected");
            }
            case 'd' -> {
              udTagDetected = true;
              System.out.println("  → UD signal tag detected");
            }
            case 'l' -> {
              ulTagDetected = true;
              System.out.println("  → UL signal tag detected");
            }
          }
        }
      } catch (Exception e) {
        // Silently ignore errors during signal tag detection
      }
    }
    
    // Reset signal tag to none after detection
    reader.setActiveSignalTag('\0');
  }

  /**
   * Repack cached reads for the given stack using pixel-based spacing.
   * Supports three modes: RG-split, allele-split (haplotype), or normal with discordant grouping.
   */
  public void repackReads(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    if (sc == null || sc.cachedReads.get().isEmpty()) return;

    List<BAMRecord> reads = new ArrayList<>(sc.cachedReads.get());
    
    // Use ReadPacker to assign rows
    ReadPacker.PackingResult result = ReadPacker.packReads(
      reads, stack, splitByReadGroup, haplotypeDetected, splitByStrand, splitByDiscordant,
      stackMatesSideBySide, detectedReadGroups);
    
    // Update cache with packing results
    sc.maxRow = result.maxRow;
    sc.discordantStartRow = result.discordantStartRow;
    sc.normalStartRow = result.normalStartRow;
    sc.hp2StartRow = result.hp2StartRow;
    sc.strandSplitStartRow = result.strandSplitStartRow;
    sc.discordantSplitStartRow = result.discordantSplitStartRow;
    sc.readGroupStartRows = result.readGroupStartRows;
    sc.cachedReads.set(reads);
  }

  /**
   * Repack reads for all stacks.
   */
  public void repackAllStacks() {
    for (DrawStack stack : stackCaches.keySet()) {
      repackReads(stack);
    }
  }

  public AlignmentReader getReader() { return reader; }

  @Override
  public void close() throws IOException {
    if (reader != null) {
      try (reader) {
          for (StackCache sc : stackCaches.values()) {
              Future<?> f = sc.pendingFetch;
              if (f != null) f.cancel(true);
          }
            for (Future<?> f : coverageFetches.values()) {
              if (f != null) f.cancel(true);
            }
            coverageFetches.clear();
            coverageRequests.clear();
          coverageCalculator.cancelSampling();
          stackCaches.clear();
          coverageCaches.clear();
          coverageCalculator.clearSampledCoverageCache();
          if (fetchPool != null) {
            fetchPool.shutdownNow();
          }
      }
    }
  }
}
