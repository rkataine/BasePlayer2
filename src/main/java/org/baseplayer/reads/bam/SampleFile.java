package org.baseplayer.reads.bam;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.baseplayer.draw.DrawCytoband;
import org.baseplayer.draw.DrawFunctions;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.Settings;

import javafx.application.Platform;

/**
 * Represents a loaded BAM sample file.
 * Wraps a BAMFileReader and caches reads per DrawStack viewport.
 * Fetches are done asynchronously so navigation stays responsive.
 */
public class SampleFile implements Closeable {

  public final String name;
  public final Path path;
  private final AlignmentReader reader;
  
  // Visibility toggle
  public boolean visible = true;
  
  // When true, renders with transparent overlay colors instead of opaque
  public boolean overlay = false;
  
  // Additional data files drawn on the same track
  private final List<SampleFile> overlays = new ArrayList<>();

  // Per-stack caching — each DrawStack has its own cached region and fetch state
  private final ConcurrentHashMap<DrawStack, StackCache> stackCaches = new ConcurrentHashMap<>();

  // Error tracking — suppress repeated messages
  private volatile int consecutiveErrors = 0;
  private static final int MAX_ERROR_LOG = 3;
  /** Fetch this fraction of view-length extra on each side to avoid refetching on tiny movements. */
  private static final double FETCH_BUFFER_FRACTION = 0.3;
  /** Maximum view length for BAM queries — now read from Settings. */
  private static int getMaxBamViewLength() { return Settings.get().getMaxCoverageViewLength(); }
  /** Minimum pixel gap between reads for packing (ensures consistent visual spacing at all zoom levels) */
  private static final int MIN_PIXEL_GAP = 3;

  // Per-file fetch executor — each file gets its own thread so BAM and CRAM don't block each other
  private final ExecutorService fetchPool;
  
  // Status message shown in the track (e.g., "No reads found", "Loaded 123 reads")
  private volatile String statusMessage = null;
  
  // Data type detection (auto-detected from first batch of reads)
  private volatile boolean methylationDetected = false;
  private volatile boolean haplotypeDetected = false;
  /** User can override: true = suppress methyl mismatches, null = auto (use methylationDetected). */
  private volatile Boolean suppressMethylMismatches = null;
  
  // Read group support
  /** Detected read group names (populated from first batch). */
  private volatile List<String> detectedReadGroups = new ArrayList<>();
  /** When true, reads are separated into sections by read group. */
  private volatile boolean splitByReadGroup = false;
  
  /** Vertical scroll offset for reads within this sample track (in pixels). */
  public volatile double readScrollOffset = 0;

  // Per-file coverage cache (keyed by DrawStack), so each file owns all its cached data
  public static class CoverageCache {
    public double[] smoothedCov;
    public double[] rawMmA, rawMmC, rawMmG, rawMmT;
    public double[] methylRatio;  // per-bin methylation ratio (0-1, or -1 = no data)
    public double[] smoothedMethylRatio; // smoothed version for stable display
    public int genomicStart;
    public double binSize;
    public int numBins;
    public double scaleMax;
    public List<BAMRecord> sourceReads; // identity check for invalidation
  }
  private final ConcurrentHashMap<DrawStack, CoverageCache> coverageCaches = new ConcurrentHashMap<>();

  /** Get the coverage cache for a given DrawStack, or null. */
  public CoverageCache getCoverageCache(DrawStack stack) {
    return coverageCaches.get(stack);
  }

  /** Get currently cached reads for a DrawStack without triggering a new fetch. */
  public List<BAMRecord> getCachedReads(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.cachedReads.get() : Collections.emptyList();
  }

  /** Set the coverage cache for a given DrawStack. */
  public void setCoverageCache(DrawStack stack, CoverageCache cache) {
    coverageCaches.put(stack, cache);
  }

  // --- Chromosome-level sampled coverage ---
  /** Sparse coverage sampled at regular intervals for chromosome-level view. */
  public static class SampledCoverage {
    public String chrom;
    public int viewStart, viewEnd;
    public int numSamples;
    public int stride;
    public int window;
    public int[] positions;    // genomic position of each sample
    public double[] depths;    // raw depth at each sample
    public double[] smoothed;  // smoothed depths for rendering
    public double maxDepth;
    public volatile int samplesCompleted; // progress counter
    public volatile boolean complete;
    public volatile int chunksProcessed;  // for progress indication
    public volatile int totalChunks;
  }
  private final ConcurrentHashMap<DrawStack, SampledCoverage> sampledCoverages = new ConcurrentHashMap<>();
  private volatile Future<?> samplingFuture;

  /** Get cached sampled coverage, or null if not yet computed. */
  public SampledCoverage getSampledCoverage(DrawStack stack) {
    return sampledCoverages.get(stack);
  }

  /** Clear all cached sampled coverage (e.g., when sample points setting changes). */
  public void clearSampledCoverageCache() {
    sampledCoverages.clear();
  }
  
  /** Get current status message for display in the track. */
  public String getStatusMessage() {
    return statusMessage;
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
   * Samples coverage at regular intervals by doing many small targeted queries
   * using the index file for fast random access. Returns cached result or
   * triggers async sampling and returns partial/null.
   */
  public SampledCoverage requestSampledCoverage(String chrom, int start, int end,
                                                 int numSamples, DrawStack stack) {
    SampledCoverage cached = sampledCoverages.get(stack);

    // Check if cache is valid for this view (same chrom, covers the region, similar resolution)
    if (cached != null && cached.complete && cached.chrom.equals(chrom)
        && start >= cached.viewStart && end <= cached.viewEnd) {
      // Re-sample if zoomed significantly closer (stride changed by >2x)
      int newStride = Math.max(1, (end - start) / numSamples);
      if (newStride >= cached.stride / 3) {
        return cached; // resolution is close enough, reuse cache
      }
      // else: zoomed in enough that we need finer sampling — fall through to re-fetch
    }

    // Don't start new fetch during active navigation
    if (DrawFunctions.navigating || DrawCytoband.isDragging || DrawFunctions.animationRunning) {
      return cached; // return stale or null
    }

    // Check if we're already fetching for this exact region
    if (cached != null && !cached.complete && cached.chrom.equals(chrom)
        && cached.viewStart == start && cached.viewEnd == end) {
      return cached; // still loading, return partial
    }

    // Cancel any running sampling task
    Future<?> prev = samplingFuture;
    if (prev != null && !prev.isDone()) {
      prev.cancel(true);
    }

    // Set up new sampled coverage
    int stride = Math.max(1, (end - start) / numSamples);
    // Fixed 1000bp window — fast approximation, not representative of whole bin
    int window = Math.min(1000, stride);

    SampledCoverage sc = new SampledCoverage();
    sc.chrom = chrom;
    sc.viewStart = start;
    sc.viewEnd = end;
    sc.numSamples = numSamples;
    sc.stride = stride;
    sc.window = window;
    sc.positions = new int[numSamples];
    sc.depths = new double[numSamples];
    sc.maxDepth = 0;
    sc.samplesCompleted = 0;
    sc.complete = false;
    sampledCoverages.put(stack, sc);

    System.out.println("Sampling coverage (" + name + "): " + chrom + ":" + start + "-" + end
        + " stride=" + stride + " window=" + window + " samples=" + numSamples);

    // Check FetchManager before submitting (memory only — SAMPLED_COVERAGE allows large regions)
    final FetchManager fm = FetchManager.get();
    if (!fm.canFetch(FetchManager.FetchType.SAMPLED_COVERAGE, end - start)) {
      System.err.println("Sampled coverage fetch blocked by FetchManager for " + name);
      return cached;
    }

    samplingFuture = fetchPool.submit(() -> {
      FetchManager.FetchTicket ticket = fm.acquire(
          FetchManager.FetchType.SAMPLED_COVERAGE, SampleFile.this, stack, chrom, start, end);
      try {
        // Build position array — sample from the CENTER of each bin
        int halfStride = stride / 2;
        int halfWindow = sc.window / 2;
        for (int i = 0; i < numSamples; i++) {
          sc.positions[i] = start + i * stride + halfStride; // bin center for rendering
        }

        int w = sc.window;
        double maxDepth = 0;

        // Query each position INDIVIDUALLY — avoids merging BAI chunks across
        // all positions (which at chromosome level creates 100+ merged chunks
        // covering most of the file, making it extremely slow).
        // Each individual 1kb query hits only a few small index chunks → fast.
        for (int i = 0; i < numSamples; i++) {
          if (Thread.currentThread().isInterrupted() || ticket.isCancelled()) return;

          int queryPos = Math.max(0, sc.positions[i] - halfWindow);
          int[] singlePos = { queryPos };
          int[] singleCount = { 0 };
          reader.querySampledCounts(chrom, singlePos, w, singleCount, null);

          double depth = (double) singleCount[0] * 1000.0 / w;
          sc.depths[i] = depth;
          if (depth > maxDepth) maxDepth = depth;
          sc.maxDepth = maxDepth;
          sc.samplesCompleted = i + 1;

          // Update UI after each sample so coverage appears progressively
          Platform.runLater(() -> DrawFunctions.update.set(!DrawFunctions.update.get()));
        }

        if (Thread.currentThread().isInterrupted() || ticket.isCancelled()) return;

        // Smooth the sampled depths (3-pass box blur) — optional via settings
        if (Settings.get().isSmoothSmallFiles()) {
          double[] smoothed = new double[numSamples];
          System.arraycopy(sc.depths, 0, smoothed, 0, numSamples);
          int radius = Math.max(1, Math.min(6, numSamples / 30));
          double[] tmp = new double[numSamples];
          for (int pass = 0; pass < 3; pass++) {
            double sum = 0;
            // Seed window: elements [0, radius) — one short of full window
            for (int k = 0; k < radius && k < numSamples; k++) sum += smoothed[k];
            for (int k = 0; k < numSamples; k++) {
              int right = k + radius;
              int left = k - radius - 1;
              if (right < numSamples) sum += smoothed[right];
              if (left >= 0) sum -= smoothed[left];
              int cnt = Math.min(right, numSamples - 1) - Math.max(left + 1, 0) + 1;
              tmp[k] = sum / cnt;
            }
            System.arraycopy(tmp, 0, smoothed, 0, numSamples);
          }
          double maxSmoothed = 0;
          for (double v : smoothed) if (v > maxSmoothed) maxSmoothed = v;
          sc.smoothed = smoothed;
          sc.maxDepth = Math.max(maxSmoothed, maxDepth);
        } else {
          // No smoothing — use raw depths directly
          sc.smoothed = null;
          sc.maxDepth = maxDepth;
        }
        sc.complete = true;
        System.out.println("Sampling complete (" + name + "): maxDepth=" + (int) sc.maxDepth);
        Platform.runLater(() -> DrawFunctions.update.set(!DrawFunctions.update.get()));
      } catch (IOException e) {
        if (!(e instanceof java.io.IOException && Thread.currentThread().isInterrupted())) {
          System.err.println("Error sampling coverage (" + name + "): " + e.getMessage());
        }
      } finally {
        fm.release(ticket);
      }
    });

    return sc;
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
    volatile boolean cachedCoverageOnly = false;
    volatile double cachedScale = -1;
    /** The first row where discordant reads are placed (0 if discordant reads exist and are shown at top, -1 = no discordant reads). */
    volatile int discordantStartRow = -1;
    /** The first row where normal/concordant reads are placed (-1 if no separation, or no normal reads). */
    volatile int normalStartRow = -1;
    /** The first row where HP2/unphased reads are placed in allele view (-1 = not in allele view). */
    volatile int hp2StartRow = -1;
    /** Read group boundary rows: maps RG name → first row in that section. */
    volatile java.util.Map<String, Integer> readGroupStartRows = new java.util.LinkedHashMap<>();
  }

  private long fileSize = -1;

  /** Returns true if this is a small file (< 100MB) that should use full coverage instead of sampling. */
  public boolean isSmallFile() {
    return fileSize > 0 && fileSize < 100_000_000L;
  }

  public SampleFile(Path filePath) throws IOException {
    this.path = filePath;
    this.fileSize = Files.size(filePath);
    String fileName = filePath.getFileName().toString().toLowerCase();
    if (fileName.endsWith(".cram")) {
      this.reader = new CRAMFileReader(filePath);
    } else {
      this.reader = new BAMFileReader(filePath);
    }
    this.name = reader.getSampleName();
    this.fetchPool = Executors.newSingleThreadExecutor(
        r -> { Thread t = new Thread(r, "fetch-" + this.name); t.setDaemon(true); return t; }
    );
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
    return getReads(chrom, start, end, stack, true, false);
  }
  
  /**
   * Get reads with optional navigation blocking.
   */
  public List<BAMRecord> getReads(String chrom, int start, int end, DrawStack stack, boolean blockDuringNavigation) {
    return getReads(chrom, start, end, stack, blockDuringNavigation, false);
  }

  /**
   * Get reads with optional navigation blocking and coverage-only mode.
   * @param stack the DrawStack requesting reads (each stack caches independently)
   * @param blockDuringNavigation if false, allows fetches even during navigation
   * @param coverageOnly if true, skip row packing for faster loading
   */
  public List<BAMRecord> getReads(String chrom, int start, int end, DrawStack stack, boolean blockDuringNavigation, boolean coverageOnly) {
    StackCache sc = getCache(stack);
    int viewLength = end - start;
    
    // Block all fetches during line zoom - just return cached data
    if (org.baseplayer.draw.DrawFunctions.lineZoomerActive) {
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
    
    // Invalidate cache if switching from coverageOnly to read mode (need packed reads)
    if (!coverageOnly && sc.cachedCoverageOnly && !sc.cachedReads.get().isEmpty()) {
      sc.cachedReads.set(Collections.emptyList());
      sc.cachedChrom = "";
      sc.cachedStart = -1;
      sc.cachedEnd = -1;
      sc.cachedCoverageOnly = false;
      // Also clear fetching state to allow new fetch
      sc.fetchingChrom = "";
      sc.fetchingStart = -1;
      sc.fetchingEnd = -1;
      hasOverlap = false;
    }
    
    // If view has moved too far from cache (no overlap), clear it
    if (!sc.cachedChrom.isEmpty() && !chrom.equals(sc.cachedChrom)) {
      sc.cachedReads.set(Collections.emptyList());
      sc.cachedChrom = "";
      sc.cachedStart = -1;
      sc.cachedEnd = -1;
      hasOverlap = false;
    } else if (!hasOverlap && !sc.cachedChrom.isEmpty()) {
      sc.cachedReads.set(Collections.emptyList());
      sc.cachedChrom = "";
      sc.cachedStart = -1;
      sc.cachedEnd = -1;
    }
    
    // Fast path: reuse cache if requested region is within cached region
    if (chrom.equals(sc.cachedChrom) && start >= sc.cachedStart && end <= sc.cachedEnd) {
      // Repack if scale changed significantly (zoomed in/out)
      if (!coverageOnly && sc.cachedScale > 0 && !sc.cachedReads.get().isEmpty()) {
        double scaleRatio = stack.scale / sc.cachedScale;
        if (scaleRatio < 0.5 || scaleRatio > 2.0) {
          repackReads(stack);
          sc.cachedScale = stack.scale;
        }
      }
      return sc.cachedReads.get();
    }

    // Block all new fetches during zoom animation or cytoband dragging
    if (DrawFunctions.animationRunning || DrawCytoband.isDragging) {
      return sc.cachedReads.get(); // return stale or empty, no new fetches
    }
    
    // During regular scroll/pan navigation, allow fetches to proceed with directional buffering
    // This keeps UI responsive during scroll momentum
    if (blockDuringNavigation && DrawFunctions.navigating) {
      // If we have cached data, return it (but continue below to potentially start prefetch)
      if (hasOverlap) {
        // Check if there's already a fetch in progress that will cover us
        if (chrom.equals(sc.fetchingChrom) && start >= sc.fetchingStart && end <= sc.fetchingEnd) {
          return sc.cachedReads.get(); // Fetch in progress, return cached
        }
        // Fall through to trigger prefetch in scroll direction
      } else {
        // No cached data at all - allow immediate fetch even during navigation
        // Fall through
      }
    }

    // Synchronize per-cache to prevent duplicate submits
    synchronized (sc) {
      // Re-check after acquiring lock
      if (chrom.equals(sc.cachedChrom) && start >= sc.cachedStart && end <= sc.cachedEnd) {
        return sc.cachedReads.get();
      }
      if (chrom.equals(sc.fetchingChrom) && start >= sc.fetchingStart && end <= sc.fetchingEnd) {
        return sc.cachedReads.get();
      }

      // Cancel any in-flight chromosome-level sampling (free the executor for read-level fetch)
      Future<?> sf = samplingFuture;
      if (sf != null && !sf.isDone()) {
        sf.cancel(true);
      }

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
      // This allows fetches to complete during navigation if they're still useful
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

      // Reset read scroll when fetching new region
      readScrollOffset = 0;

      System.out.println("Fetching BAM (" + name + "): " + chrom + ":" + fetchStart + "-" + fetchEnd);

      // Check FetchManager before submitting
      final FetchManager fm = FetchManager.get();
      if (!fm.canFetch(fetchEnd - fetchStart)) {
        System.err.println("Fetch blocked by FetchManager for " + name);
        sc.loading = false;
        sc.fetchingChrom = "";
        sc.fetchingStart = -1;
        sc.fetchingEnd = -1;
        return sc.cachedReads.get();
      }

      final boolean skipPacking = coverageOnly;
      sc.pendingFetch = fetchPool.submit(() -> {
        FetchManager.FetchTicket ticket = fm.acquire(FetchManager.FetchType.READS, SampleFile.this, stack, chrom, fetchStart, fetchEnd);
        try {
          List<BAMRecord> reads = new ArrayList<>();
          List<Integer> rowEnds = new ArrayList<>();
          int[] maxRowLocal = {0};
          // Pixel-based gap: MIN_PIXEL_GAP pixels converted to genomic coordinates
          int gap = skipPacking ? 0 : Math.max(1, (int)(MIN_PIXEL_GAP * stack.scale));
          long[] lastUpdate = {System.nanoTime()};
          boolean[] detectionDone = {methylationDetected || haplotypeDetected || !detectedReadGroups.isEmpty()};

          reader.queryStreaming(chrom, fetchStart, fetchEnd, record -> {
            if (Thread.currentThread().isInterrupted() || ticket.isCancelled()) {
              return false;
            }
            if (!fm.recordRead(ticket)) {
              System.err.println("Fetch aborted by FetchManager for " + name
                  + " after " + ticket.itemCount.get() + " reads");
              return false;
            }
            
            if (!skipPacking) {
              // Incremental packing
              boolean placed = false;
              for (int r = 0; r < rowEnds.size(); r++) {
                if (record.pos >= rowEnds.get(r) + gap) {
                  record.row = r;
                  rowEnds.set(r, record.end);
                  placed = true;
                  break;
                }
              }
              if (!placed) {
                record.row = rowEnds.size();
                rowEnds.add(record.end);
              }
              if (record.row > maxRowLocal[0]) maxRowLocal[0] = record.row;
            }
            reads.add(record);
            
            // Auto-detect on first 200 reads BEFORE any UI updates
            if (!detectionDone[0] && reads.size() == 200) {
              runAutoDetection(reads);
              detectionDone[0] = true;
            }

            // Progressive display every ~100 ms (but only after detection on first batch)
            long now = System.nanoTime();
            if (now - lastUpdate[0] > 100_000_000L && (detectionDone[0] || reads.size() > 200)) {
              sc.maxRow = maxRowLocal[0];
              sc.cachedReads.set(new ArrayList<>(reads));
              Platform.runLater(() -> DrawFunctions.update.set(!DrawFunctions.update.get()));
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
            sc.cachedCoverageOnly = skipPacking;
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
                Platform.runLater(() -> DrawFunctions.update.set(!DrawFunctions.update.get()));
              }
            }, 5000);
            
            // Repack to optimize row usage now that all reads are loaded
            if (!skipPacking) {
              repackReads(stack);
              sc.cachedScale = stack.scale;
            }
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
        } finally {
          fm.release(ticket);
          sc.loading = false;
          Platform.runLater(() -> DrawFunctions.update.set(!DrawFunctions.update.get()));
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
  
  /** Get detected read groups. */
  public List<String> getDetectedReadGroups() { return detectedReadGroups; }
  
  /** Whether read group splitting is enabled. */
  public boolean isSplitByReadGroup() { return splitByReadGroup; }
  
  /** Enable/disable read group splitting. Repacks all stacks. */
  public void setSplitByReadGroup(boolean split) { 
    this.splitByReadGroup = split;
    repackAllStacks();
  }
  
  /** Get the read group boundary rows for a given stack. */
  public java.util.Map<String, Integer> getReadGroupStartRows(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    return sc != null ? sc.readGroupStartRows : java.util.Collections.emptyMap();
  }

  /**
   * Auto-detect methylation, haplotype, and read group data from a sample of reads.
   * Called once on the first 200 reads, before any UI updates.
   */
  private void runAutoDetection(List<BAMRecord> reads) {
    int methylTagCount = 0, hpCount = 0;
    int totalMismatches = 0, bisulfiteMismatches = 0;
    int xmBisulfiteCount = 0; // Count XM:Z bisulfite signatures
    java.util.Set<String> rgSet = new java.util.LinkedHashSet<>();
    int sampleSize = Math.min(reads.size(), 200);
    
    for (int i = 0; i < sampleSize; i++) {
      BAMRecord r = reads.get(i);
      
      // Check for methylation tags
      if (r.hasMethylTag) methylTagCount++;
      
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
  }

  /**
   * Repack cached reads for the given stack using pixel-based spacing.
   * Supports three modes: RG-split, allele-split (haplotype), or normal with discordant grouping.
   */
  public void repackReads(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    if (sc == null || sc.cachedReads.get().isEmpty()) return;

    List<BAMRecord> reads = new ArrayList<>(sc.cachedReads.get());
    int gap = Math.max(1, (int)(MIN_PIXEL_GAP * stack.scale));
    int maxRowLocal = 0;

    if (splitByReadGroup && detectedReadGroups.size() > 1) {
      // ── Read group split mode ──
      java.util.Map<String, List<BAMRecord>> rgGroups = new java.util.LinkedHashMap<>();
      for (String rg : detectedReadGroups) rgGroups.put(rg, new ArrayList<>());
      rgGroups.put("__none__", new ArrayList<>()); // for reads without RG
      
      for (BAMRecord record : reads) {
        String rg = record.readGroup != null ? record.readGroup : "__none__";
        List<BAMRecord> group = rgGroups.get(rg);
        if (group == null) {
          // RG not seen in initial detection — put in first group
          group = rgGroups.values().iterator().next();
        }
        group.add(record);
      }
      
      java.util.Map<String, Integer> rgStartRows = new java.util.LinkedHashMap<>();
      int currentStartRow = 0;
      
      for (java.util.Map.Entry<String, List<BAMRecord>> entry : rgGroups.entrySet()) {
        List<BAMRecord> rgReads = entry.getValue();
        if (rgReads.isEmpty()) continue;
        
        rgStartRows.put(entry.getKey(), currentStartRow);
        rgReads.sort(java.util.Comparator.comparingInt(r -> r.pos));
        
        List<Integer> rowEnds = new ArrayList<>();
        for (BAMRecord record : rgReads) {
          boolean placed = false;
          for (int r = 0; r < rowEnds.size(); r++) {
            if (record.pos >= rowEnds.get(r) + gap) {
              record.row = currentStartRow + r;
              rowEnds.set(r, record.end);
              placed = true;
              break;
            }
          }
          if (!placed) {
            record.row = currentStartRow + rowEnds.size();
            rowEnds.add(record.end);
          }
          if (record.row > maxRowLocal) maxRowLocal = record.row;
        }
        
        currentStartRow = maxRowLocal + 2; // leave 1 empty row as separator
      }
      
      sc.readGroupStartRows = rgStartRows;
      sc.discordantStartRow = -1;
      sc.normalStartRow = -1;
      sc.hp2StartRow = -1;
      sc.maxRow = maxRowLocal;
      sc.cachedReads.set(reads);

    } else if (haplotypeDetected) {
      // ── Allele-split mode: HP1 on top (rendered upward), HP2+unphased on bottom ──
      List<BAMRecord> hp1Reads = new ArrayList<>();
      List<BAMRecord> hp2Reads = new ArrayList<>();
      for (BAMRecord record : reads) {
        if (record.haplotype == 1) {
          hp1Reads.add(record);
        } else {
          hp2Reads.add(record); // HP2 + unphased
        }
      }
      hp1Reads.sort(java.util.Comparator.comparingInt(r -> r.pos));
      hp2Reads.sort(java.util.Comparator.comparingInt(r -> r.pos));

      // Pack HP1 reads: rows 0, 1, 2, ...
      List<Integer> rowEnds = new ArrayList<>();
      for (BAMRecord record : hp1Reads) {
        boolean placed = false;
        for (int r = 0; r < rowEnds.size(); r++) {
          if (record.pos >= rowEnds.get(r) + gap) {
            record.row = r;
            rowEnds.set(r, record.end);
            placed = true;
            break;
          }
        }
        if (!placed) {
          record.row = rowEnds.size();
          rowEnds.add(record.end);
        }
        if (record.row > maxRowLocal) maxRowLocal = record.row;
      }

      // HP2 starts on fresh rows after HP1
      int hp2Start = rowEnds.isEmpty() ? 0 : rowEnds.size();
      List<Integer> hp2RowEnds = new ArrayList<>();
      for (BAMRecord record : hp2Reads) {
        boolean placed = false;
        for (int r = 0; r < hp2RowEnds.size(); r++) {
          if (record.pos >= hp2RowEnds.get(r) + gap) {
            record.row = hp2Start + r;
            hp2RowEnds.set(r, record.end);
            placed = true;
            break;
          }
        }
        if (!placed) {
          record.row = hp2Start + hp2RowEnds.size();
          hp2RowEnds.add(record.end);
        }
        if (record.row > maxRowLocal) maxRowLocal = record.row;
      }

      sc.hp2StartRow = hp2Start;
      sc.discordantStartRow = -1;
      sc.normalStartRow = -1;
      sc.readGroupStartRows = new java.util.LinkedHashMap<>();
      sc.maxRow = maxRowLocal;
      sc.cachedReads.set(reads);

    } else {
      // ── Normal mode: pack all reads by position ──
      reads.sort(java.util.Comparator.comparingInt(r -> r.pos));

      List<Integer> rowEnds = new ArrayList<>();
      for (BAMRecord record : reads) {
        boolean placed = false;
        for (int r = 0; r < rowEnds.size(); r++) {
          if (record.pos >= rowEnds.get(r) + gap) {
            record.row = r;
            rowEnds.set(r, record.end);
            placed = true;
            break;
          }
        }
        if (!placed) {
          record.row = rowEnds.size();
          rowEnds.add(record.end);
        }
        if (record.row > maxRowLocal) maxRowLocal = record.row;
      }

      sc.discordantStartRow = -1;
      sc.normalStartRow = -1;
      sc.hp2StartRow = -1;
      sc.readGroupStartRows = new java.util.LinkedHashMap<>();
      sc.maxRow = maxRowLocal;
      sc.cachedReads.set(reads);
    }
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

  /** Add an additional data file to this track. */
  public void addOverlay(SampleFile file) {
    file.overlay = true;
    overlays.add(file);
  }

  /** Remove and close an overlay by index. */
  public void removeOverlay(int index) {
    if (index < 0 || index >= overlays.size()) return;
    try {
      overlays.get(index).close();
    } catch (IOException e) {
      System.err.println("Error closing overlay: " + e.getMessage());
    }
    overlays.remove(index);
  }

  /** Get all additional data files for this track. */
  public List<SampleFile> getOverlays() {
    return overlays;
  }

  @Override
  public void close() throws IOException {
      try (reader) {
          for (StackCache sc : stackCaches.values()) {
              Future<?> f = sc.pendingFetch;
              if (f != null) f.cancel(true);
          }
          Future<?> sf = samplingFuture;
          if (sf != null) sf.cancel(true);
          stackCaches.clear();
          coverageCaches.clear();
          sampledCoverages.clear();
          fetchPool.shutdownNow();
      }
    for (SampleFile overlayFile : overlays) {
      overlayFile.close();
    }
    overlays.clear();
  }
}
