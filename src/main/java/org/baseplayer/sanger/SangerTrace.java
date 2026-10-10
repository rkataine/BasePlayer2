package org.baseplayer.sanger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One loaded AB1 chromatogram with optional genomic alignment state.
 */
public final class SangerTrace {

  private final Path path;
  private final String name;
  private final Ab1Reader reader;

  private volatile String chromosome;
  private volatile int offset = -1;
  private volatile boolean reversed;
  private volatile double[] genomicPositions;
  private volatile boolean aligning;
  /** Chromosome of the last alignment attempt (success or failure). */
  private volatile String lastAlignAttemptChrom;
  /** View-start (bp) of the last alignment attempt, used to throttle retries. */
  private volatile long lastAlignAttemptViewStart = Long.MIN_VALUE;

  public SangerTrace(Path path) throws IOException {
    this.path = Objects.requireNonNull(path, "path");
    this.reader = new Ab1Reader(path);
    Path fileName = path.getFileName();
    this.name = fileName != null ? fileName.toString() : path.toString();
  }

  public Path getPath() {
    return path;
  }

  public String getName() {
    return name;
  }

  public Ab1Reader getReader() {
    return reader;
  }

  public String getChromosome() {
    return chromosome;
  }

  public int getOffset() {
    return offset;
  }

  public boolean isReversed() {
    return reversed;
  }

  public double[] getGenomicPositions() {
    return genomicPositions;
  }

  public boolean isAligned() {
    return offset >= 0 && genomicPositions != null && chromosome != null;
  }

  public boolean isAligning() {
    return aligning;
  }

  public void setAligning(boolean aligning) {
    this.aligning = aligning;
  }

  /**
   * Whether another alignment attempt should be scheduled for this view.
   * Retries only when the chromosome changes or the view moves by ≥10 kb.
   */
  public boolean shouldRetryAlign(String chrom, long viewStart) {
    if (aligning) {
      return false;
    }
    if (isAligned() && chrom != null && chrom.equals(chromosome)) {
      return false;
    }
    if (chrom != null && chrom.equals(lastAlignAttemptChrom)
        && Math.abs(viewStart - lastAlignAttemptViewStart) < 10_000L) {
      return false;
    }
    return true;
  }

  public void markAlignAttempt(String chrom, long viewStart) {
    this.lastAlignAttemptChrom = chrom;
    this.lastAlignAttemptViewStart = viewStart;
  }

  public void setAlignment(String chromosome, int offset, boolean reversed, double[] genomicPositions) {
    this.chromosome = chromosome;
    this.offset = offset;
    this.reversed = reversed;
    this.genomicPositions = genomicPositions;
  }

  public void clearAlignment() {
    this.chromosome = null;
    this.offset = -1;
    this.reversed = false;
    this.genomicPositions = null;
  }

  public double getGenomicStart() {
    double[] positions = genomicPositions;
    if (positions == null || positions.length == 0) {
      return -1;
    }
    double min = Double.MAX_VALUE;
    for (double pos : positions) {
      if (pos > 0 && pos < min) {
        min = pos;
      }
    }
    return min == Double.MAX_VALUE ? -1 : min;
  }

  public double getGenomicEnd() {
    double[] positions = genomicPositions;
    if (positions == null || positions.length == 0) {
      return -1;
    }
    double max = -1;
    for (double pos : positions) {
      if (pos > max) {
        max = pos;
      }
    }
    return max;
  }
}
