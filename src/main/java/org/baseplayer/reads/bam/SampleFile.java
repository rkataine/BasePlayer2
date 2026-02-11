package org.baseplayer.reads.bam;

import java.io.Closeable;
import java.io.IOException;
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

import javafx.application.Platform;

/**
 * Represents a loaded BAM sample file.
 * Wraps a BAMFileReader and caches reads per DrawStack viewport.
 * Fetches are done asynchronously so navigation stays responsive.
 */
public class SampleFile implements Closeable {

  public final String name;
  public final Path path;
  private final BAMFileReader reader;
  
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
  /** Maximum view length for BAM queries (matches DrawSampleData) */
  private static final int MAX_BAM_VIEW_LENGTH = 500_000;
  /** Minimum pixel gap between reads for packing (ensures consistent visual spacing at all zoom levels) */
  private static final int MIN_PIXEL_GAP = 3;

  // Background fetch
  private static final ExecutorService fetchPool = Executors.newFixedThreadPool(
      Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
      r -> { Thread t = new Thread(r, "BAM-fetch"); t.setDaemon(true); return t; }
  );

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
  }

  public SampleFile(Path bamPath) throws IOException {
    this.path = bamPath;
    this.reader = new BAMFileReader(bamPath);
    this.name = reader.getSampleName();
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
   * @param stack the DrawStack requesting reads (each stack caches independently)
   * @param blockDuringNavigation if false, allows fetches even during navigation
   */
  public List<BAMRecord> getReads(String chrom, int start, int end, DrawStack stack, boolean blockDuringNavigation) {
    StackCache sc = getCache(stack);
    int viewLength = end - start;
    
    // Clear cache if view is too wide (no BAM queries at this zoom level)
    if (viewLength > MAX_BAM_VIEW_LENGTH) {
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
      // Check if view has changed significantly - if so, repack for optimal display
      boolean viewChanged = false;
      if (sc.lastViewStart != -1) {
        int lastViewLength = sc.lastViewEnd - sc.lastViewStart;
        viewChanged = Math.abs(start - sc.lastViewStart) > viewLength / 2 ||
                      Math.abs(lastViewLength - viewLength) > Math.max(lastViewLength, viewLength) / 2;
      }
      
      if ((sc.lastViewStart == -1 || viewChanged) && !sc.cachedReads.get().isEmpty()) {
        sc.lastViewStart = start;
        sc.lastViewEnd = end;
        // Repack cached reads for current view to optimize row usage
        repackReads(stack);
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

      sc.pendingFetch = fetchPool.submit(() -> {
        try {
          List<BAMRecord> reads = new ArrayList<>();
          List<Integer> rowEnds = new ArrayList<>();
          int[] maxRowLocal = {0};
          // Pixel-based gap: MIN_PIXEL_GAP pixels converted to genomic coordinates
          int gap = Math.max(1, (int)(MIN_PIXEL_GAP * stack.scale));
          long[] lastUpdate = {System.nanoTime()};

          reader.queryStreaming(chrom, fetchStart, fetchEnd, record -> {
            if (Thread.currentThread().isInterrupted()) {
              return false;
            }
            
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
            consecutiveErrors = 0;
            System.out.println("Fetched BAM (" + name + "): " + reads.size() + " reads");
            
            // Repack to optimize row usage now that all reads are loaded
            repackReads(stack);
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

  public BAMFileReader getReader() { return reader; }

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
    stackCaches.clear();
    reader.close();
    for (SampleFile overlay : overlays) {
      overlay.close();
    }
    overlays.clear();
  }
}
