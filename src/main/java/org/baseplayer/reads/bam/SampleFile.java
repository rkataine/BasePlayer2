package org.baseplayer.reads.bam;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
  private ExecutorService fetchPool;

  // Per-file coverage cache (keyed by DrawStack), so each file owns all its cached data
  public static class CoverageCache {
    public double[] smoothedCov;
    public double[] rawMmA, rawMmC, rawMmG, rawMmT;
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
  }
  private final ConcurrentHashMap<DrawStack, SampledCoverage> sampledCoverages = new ConcurrentHashMap<>();
  private volatile Future<?> samplingFuture;

  /** Get cached sampled coverage, or null if not yet computed. */
  public SampledCoverage getSampledCoverage(DrawStack stack) {
    return sampledCoverages.get(stack);
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
    int window = Math.min(1000, Math.max(100, stride / 4));

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

    samplingFuture = fetchPool.submit(() -> {
      try {
        // Build position array for all sample windows
        int[] positions = new int[numSamples];
        for (int i = 0; i < numSamples; i++) {
          positions[i] = start + i * stride;
          sc.positions[i] = positions[i];
        }

        int[] counts = new int[numSamples];

        // Always use sparse index-based sampling to avoid false peaks from fixed-bin artifacts
        int w = sc.window;
        reader.querySampledCounts(chrom, positions, w, counts, () -> {
          double mx = 0;
          for (int i = 0; i < numSamples; i++) {
            double d = (double) counts[i] * 1000.0 / w;
            sc.depths[i] = d;
            if (d > mx) mx = d;
          }
          sc.samplesCompleted = numSamples;
          sc.maxDepth = mx;
          Platform.runLater(() -> DrawFunctions.update.set(!DrawFunctions.update.get()));
        });

        if (Thread.currentThread().isInterrupted()) return;

        // Final conversion of counts to depth values
        int effectiveWindow = sc.window;
        double maxDepth = 0;
        for (int i = 0; i < numSamples; i++) {
          double depth = (double) counts[i] * 1000.0 / effectiveWindow;
          sc.depths[i] = depth;
          if (depth > maxDepth) maxDepth = depth;
        }
        sc.samplesCompleted = numSamples;
        sc.maxDepth = maxDepth;

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
      } catch (Exception e) {
        if (!(e instanceof java.io.IOException && Thread.currentThread().isInterrupted())) {
          System.err.println("Error sampling coverage (" + name + "): " + e.getMessage());
        }
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
    
    // Clear cache if view is too wide (no BAM queries at this zoom level)
    if (viewLength > getMaxBamViewLength()) {
      if (!sc.cachedReads.get().isEmpty()) {
        sc.cachedReads.set(Collections.emptyList());
        sc.cachedChrom = "";
        sc.cachedStart = -1;
        sc.cachedEnd = -1;
        sc.fetchingChrom = "";
        sc.fetchingStart = -1;
        sc.fetchingEnd = -1;
      }
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

    // Defer new fetches while the user is actively navigating
    if (blockDuringNavigation && (DrawFunctions.navigating || DrawCytoband.isDragging || DrawFunctions.animationRunning)) {
      if (hasOverlap) {
        return sc.cachedReads.get();
      } else {
        return Collections.emptyList();
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

      // Cancel any in-flight fetch for a stale region
      Future<?> prev = sc.pendingFetch;
      if (prev != null && !prev.isDone()) {
        prev.cancel(true);
        sc.fetchingChrom = "";
        sc.fetchingStart = -1;
        sc.fetchingEnd = -1;
      }

      // Add buffer around the requested region
      int buffer = Math.max(1000, (int)(viewLength * FETCH_BUFFER_FRACTION));
      int fetchStart = Math.max(0, start - buffer);
      int fetchEnd = end + buffer;

      sc.fetchingChrom = chrom;
      sc.fetchingStart = fetchStart;
      sc.fetchingEnd = fetchEnd;
      sc.loading = true;

      System.out.println("Fetching BAM (" + name + "): " + chrom + ":" + fetchStart + "-" + fetchEnd);

      final boolean skipPacking = coverageOnly;
      sc.pendingFetch = fetchPool.submit(() -> {
        try {
          List<BAMRecord> reads = new ArrayList<>();
          List<Integer> rowEnds = skipPacking ? null : new ArrayList<>();
          int[] maxRowLocal = {0};
          // Pixel-based gap: MIN_PIXEL_GAP pixels converted to genomic coordinates
          int gap = skipPacking ? 0 : Math.max(1, (int)(MIN_PIXEL_GAP * stack.scale));
          long[] lastUpdate = {System.nanoTime()};

          reader.queryStreaming(chrom, fetchStart, fetchEnd, record -> {
            if (Thread.currentThread().isInterrupted()) {
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

            // Progressive display every ~100 ms
            long now = System.nanoTime();
            if (now - lastUpdate[0] > 100_000_000L) {
              sc.maxRow = maxRowLocal[0];
              sc.cachedReads.set(new ArrayList<>(reads));
              Platform.runLater(() -> DrawFunctions.update.set(!DrawFunctions.update.get()));
              lastUpdate[0] = now;
            }

            return true;
          });

          // Final state - only update if not cancelled
          if (!Thread.currentThread().isInterrupted()) {
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
            
            // Repack to optimize row usage now that all reads are loaded
            if (!skipPacking) {
              repackReads(stack);
              sc.cachedScale = stack.scale;
            }
          }
        } catch (Exception e) {
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
   * Repack cached reads for the given stack using pixel-based spacing.
   * Useful after zoom operations to optimize row usage.
   */
  public void repackReads(DrawStack stack) {
    StackCache sc = stackCaches.get(stack);
    if (sc == null || sc.cachedReads.get().isEmpty()) return;

    List<BAMRecord> reads = new ArrayList<>(sc.cachedReads.get());
    List<Integer> rowEnds = new ArrayList<>();
    // Pixel-based gap: MIN_PIXEL_GAP pixels converted to genomic coordinates
    int gap = Math.max(1, (int)(MIN_PIXEL_GAP * stack.scale));
    int maxRowLocal = 0;

    // Repack all reads
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

    sc.maxRow = maxRowLocal;
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
    reader.close();
    for (SampleFile overlay : overlays) {
      overlay.close();
    }
    overlays.clear();
  }
}
