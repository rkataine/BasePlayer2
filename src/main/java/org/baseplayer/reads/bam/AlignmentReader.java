package org.baseplayer.reads.bam;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Common interface for alignment file readers (BAM, CRAM).
 */
public interface AlignmentReader extends Closeable {

  /**
   * Query reads overlapping [start, end) on the given chromosome.
   * Coordinates are 0-based half-open.
   */
  List<BAMRecord> query(String chrom, int start, int end) throws IOException;

  /**
   * Stream reads overlapping [start, end) on the given chromosome.
   * Each valid read is passed to the consumer as it is decoded.
   * The consumer returns true to continue, false to stop.
   */
  void queryStreaming(String chrom, int start, int end,
                      Predicate<BAMRecord> consumer) throws IOException;

  /**
   * Sampled coverage: query multiple small windows in a single pass.
   * Returns read counts for each window position. Implementations should
   * merge index chunks and minimize I/O.
   * @param positions  start of each sampling window (0-based)
   * @param window     width of each sampling window
   * @param counts     output array — counts[i] = number of reads overlapping positions[i]..positions[i]+window
   * @param onChunkDone called after each index chunk is processed (for progressive UI updates), may be null
   */
  void querySampledCounts(String chrom, int[] positions, int window, int[] counts, Runnable onChunkDone) throws IOException;

  /** Get the sample name from the file header. */
  String getSampleName();

  /** Get reference sequence names from the header. */
  String[] getRefNames();

  /** Get reference sequence lengths from the header. */
  int[] getRefLengths();

  /** Get the path to the alignment file. */
  Path getPath();
}
