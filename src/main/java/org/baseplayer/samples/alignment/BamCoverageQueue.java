package org.baseplayer.samples.alignment;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.samples.Sample;

import javafx.application.Platform;

/**
 * Sequential coverage filler for coverage-only / aggregate samples.
 * Uses an ephemeral BAM open so it never steals or closes interactive
 * read-pile handles in {@link BamHandleCache}.
 */
public final class BamCoverageQueue {

  private static final BamCoverageQueue INSTANCE = new BamCoverageQueue();

  private final Object lock = new Object();
  private final LinkedHashSet<Job> pending = new LinkedHashSet<>();
  private boolean running;
  private String activeViewKey;

  private BamCoverageQueue() {}

  public static BamCoverageQueue get() {
    return INSTANCE;
  }

  public void enqueue(Sample sample, String chrom, int start, int end,
                      DrawStack stack, int numColumns) {
    if (sample == null || sample.getDataType() != Sample.DataType.BAM) {
      return;
    }
    if (chrom == null || end <= start || stack == null || numColumns <= 0) {
      return;
    }
    if (covers(sample.getParkedCoverageCache(), chrom, start, end)) {
      return;
    }

    String viewKey = chrom + ":" + start + ":" + end;
    Job job = new Job(sample, chrom, start, end, stack, Math.max(1, numColumns), viewKey);
    synchronized (lock) {
      if (!viewKey.equals(activeViewKey)) {
        pending.clear();
        activeViewKey = viewKey;
      }
      pending.add(job);
      if (running) {
        return;
      }
      running = true;
    }
    AlignmentFetchPool.get().submit(this::drain);
  }

  /** One sample per pool turn so read fetches can interleave on the shared pool. */
  private void drain() {
    Job job;
    synchronized (lock) {
      if (pending.isEmpty()) {
        running = false;
        return;
      }
      Iterator<Job> it = pending.iterator();
      job = it.next();
      it.remove();
    }
    try {
      loadOne(job);
    } catch (Exception ex) {
      System.err.println("Coverage queue failed for " + job.sample.getName() + ": " + ex.getMessage());
    }
    Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
    synchronized (lock) {
      if (pending.isEmpty()) {
        running = false;
      } else {
        AlignmentFetchPool.get().submit(this::drain);
      }
    }
  }

  private static void loadOne(Job job) {
    if (covers(job.sample.getParkedCoverageCache(), job.chrom, job.start, job.end)) {
      return;
    }
    // Prefer the interactive handle if this sample already has one open.
    AlignmentFile existing = job.sample.getBamFile();
    if (existing != null) {
      AlignmentFile.CoverageCache cache = existing.computeCoverageNow(
          job.chrom, job.start, job.end, job.stack,
          existing.isMethylationData(), job.numColumns);
      if (cache != null) {
        job.sample.parkCoverageCache(cache);
      }
      return;
    }

    // Ephemeral open: does not register in BamHandleCache / Sample.bamFile.
    AlignmentFile ephemeral = job.sample.openEphemeral();
    if (ephemeral == null) {
      return;
    }
    try {
      AlignmentFile.CoverageCache cache = ephemeral.computeCoverageNow(
          job.chrom, job.start, job.end, job.stack,
          ephemeral.isMethylationData(), job.numColumns);
      if (cache != null) {
        job.sample.parkCoverageCache(cache);
      }
      job.sample.noteDetectionFrom(ephemeral);
    } finally {
      try {
        ephemeral.close();
      } catch (Exception ignored) {
        // best-effort
      }
    }
  }

  private static boolean covers(AlignmentFile.CoverageCache cache,
                                String chrom, int start, int end) {
    if (cache == null || cache.chrom == null || !cache.chrom.equals(chrom)) {
      return false;
    }
    if (cache.numBins <= 0 || cache.binSize <= 0) {
      return false;
    }
    double cacheEnd = cache.genomicStart + cache.numBins * cache.binSize;
    return start >= cache.genomicStart && end <= cacheEnd;
  }

  private record Job(Sample sample, String chrom, int start, int end,
                     DrawStack stack, int numColumns, String viewKey) {
    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Job other)) {
        return false;
      }
      return sample == other.sample && Objects.equals(viewKey, other.viewKey);
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(sample) * 31 + Objects.hashCode(viewKey);
    }
  }
}
