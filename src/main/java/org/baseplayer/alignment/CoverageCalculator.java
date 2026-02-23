package org.baseplayer.alignment;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.baseplayer.chromosome.draw.CytobandCanvas;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.Settings;
import org.baseplayer.io.readers.AlignmentReader;

import javafx.application.Platform;

/**
 * Handles chromosome-level coverage sampling for BAM/CRAM files.
 * Samples coverage at regular intervals using indexed random access
 * for fast chromosome-wide visualization.
 * 
 * Extracted from AlignmentFile to separate coverage calculation concerns.
 */
public class CoverageCalculator {

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
  private final ExecutorService fetchPool;
  private final AlignmentReader reader;
  private final String sampleName;
  private final AlignmentFile sampleFile; // for FetchManager registration

  public CoverageCalculator(AlignmentReader reader, String sampleName, 
                           ExecutorService fetchPool, AlignmentFile sampleFile) {
    this.reader = reader;
    this.sampleName = sampleName;
    this.fetchPool = fetchPool;
    this.sampleFile = sampleFile;
  }

  /** Get cached sampled coverage, or null if not yet computed. */
  public SampledCoverage getSampledCoverage(DrawStack stack) {
    return sampledCoverages.get(stack);
  }

  /** Clear all cached sampled coverage (e.g., when sample points setting changes). */
  public void clearSampledCoverageCache() {
    sampledCoverages.clear();
  }
  
  /** Remove sampled coverage for a specific DrawStack. */
  public void removeSampledCoverage(DrawStack stack) {
    sampledCoverages.remove(stack);
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
    if (stack.nav.navigating || CytobandCanvas.isDragging || stack.nav.animationRunning) {
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

    System.out.println("Sampling coverage (" + sampleName + "): " + chrom + ":" + start + "-" + end
        + " stride=" + stride + " window=" + window + " samples=" + numSamples);

    // Check FetchManager before submitting (memory only — SAMPLED_COVERAGE allows large regions)
    final FetchManager fm = FetchManager.get();
    if (!fm.canFetch(FetchManager.FetchType.SAMPLED_COVERAGE, end - start)) {
      System.err.println("Sampled coverage fetch blocked by FetchManager for " + sampleName);
      return cached;
    }

    samplingFuture = fetchPool.submit(() -> {
      FetchManager.FetchTicket ticket = fm.acquire(
          FetchManager.FetchType.SAMPLED_COVERAGE, sampleFile, stack, chrom, start, end);
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
          Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
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
        System.out.println("Sampling complete (" + sampleName + "): maxDepth=" + (int) sc.maxDepth);
        Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
      } catch (IOException e) {
        if (!(e instanceof java.io.IOException && Thread.currentThread().isInterrupted())) {
          System.err.println("Error sampling coverage (" + sampleName + "): " + e.getMessage());
        }
      } finally {
        fm.release(ticket);
      }
    });

    return sc;
  }

  /** Cancel any pending sampling operation. */
  public void cancelSampling() {
    Future<?> prev = samplingFuture;
    if (prev != null && !prev.isDone()) {
      prev.cancel(true);
    }
  }
}
