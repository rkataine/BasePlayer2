package org.baseplayer.reads.bam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and queries BAI (BAM Index) files.
 * Parses the binning index and linear index for each reference sequence,
 * then returns the BGZF chunks that overlap a given genomic region.
 */
public class BAIIndex {

  /**
   * A chunk of BAM data defined by virtual file offset range.
   */
  public static class Chunk {
    public final long start; // virtual offset
    public final long end;   // virtual offset

    public Chunk(long start, long end) {
      this.start = start;
      this.end = end;
    }
  }

  /**
   * Index data for a single reference sequence.
   */
  private static class RefIndex {
    // bin number -> list of chunks
    final Map<Integer, List<Chunk>> bins;
    // linear index: one virtual offset per 16384bp window
    final long[] linearIndex;

    RefIndex(Map<Integer, List<Chunk>> bins, long[] linearIndex) {
      this.bins = bins;
      this.linearIndex = linearIndex;
    }
  }

  private final RefIndex[] references;

  public BAIIndex(Path baiPath) throws IOException {
    try (BGZFRawReader reader = new BGZFRawReader(baiPath)) {
      // Magic: "BAI\1"
      byte[] magic = new byte[4];
      reader.readFully(magic);
      if (magic[0] != 'B' || magic[1] != 'A' || magic[2] != 'I' || magic[3] != 1) {
        throw new IOException("Not a valid BAI file: " + baiPath);
      }

      int nRef = reader.readInt();
      references = new RefIndex[nRef];

      for (int i = 0; i < nRef; i++) {
        // Read bins
        int nBin = reader.readInt();
        Map<Integer, List<Chunk>> bins = new HashMap<>();
        for (int b = 0; b < nBin; b++) {
          int binNumber = reader.readInt(); // uint32 but bins fit in int
          int nChunk = reader.readInt();
          List<Chunk> chunks = new ArrayList<>(nChunk);
          for (int c = 0; c < nChunk; c++) {
            long chunkStart = reader.readLong();
            long chunkEnd = reader.readLong();
            chunks.add(new Chunk(chunkStart, chunkEnd));
          }
          bins.put(binNumber, chunks);
        }

        // Read linear index
        int nIntv = reader.readInt();
        long[] linearIdx = new long[nIntv];
        for (int li = 0; li < nIntv; li++) {
          linearIdx[li] = reader.readLong();
        }

        references[i] = new RefIndex(bins, linearIdx);
      }
    }
  }

  /**
   * Get all BGZF chunks that may contain reads overlapping [start, end) for a reference.
   * Coordinates are 0-based.
   */
  public List<Chunk> getChunks(int refId, int start, int end) {
    if (refId < 0 || refId >= references.length) return Collections.emptyList();
    
    RefIndex ref = references[refId];
    if (ref == null) return Collections.emptyList();

    // Get all bins that overlap the query region
    int[] bins = reg2bins(start, end);

    // Collect all chunks from overlapping bins
    List<Chunk> chunks = new ArrayList<>();
    for (int bin : bins) {
      List<Chunk> binChunks = ref.bins.get(bin);
      if (binChunks != null) {
        chunks.addAll(binChunks);
      }
    }

    if (chunks.isEmpty()) return chunks;

    // Use linear index to set a minimum offset
    // The linear index has one entry per 16384bp window
    long minOffset = 0;
    int linearIndexPos = start / 16384;
    if (ref.linearIndex.length > 0 && linearIndexPos < ref.linearIndex.length) {
      minOffset = ref.linearIndex[linearIndexPos];
    }

    // Filter chunks by minimum offset and sort
    List<Chunk> filtered = new ArrayList<>();
    for (Chunk chunk : chunks) {
      if (chunk.end > minOffset) {
        long adjustedStart = Math.max(chunk.start, minOffset);
        filtered.add(new Chunk(adjustedStart, chunk.end));
      }
    }

    // Sort by start offset
    filtered.sort((a, b) -> Long.compare(a.start, b.start));

    // Merge overlapping chunks
    if (filtered.size() <= 1) return filtered;
    
    List<Chunk> merged = new ArrayList<>();
    Chunk current = filtered.get(0);
    for (int i = 1; i < filtered.size(); i++) {
      Chunk next = filtered.get(i);
      if (next.start <= current.end) {
        // Overlapping - merge
        current = new Chunk(current.start, Math.max(current.end, next.end));
      } else {
        merged.add(current);
        current = next;
      }
    }
    merged.add(current);
    return merged;
  }

  /**
   * Return all BAI bins that overlap with region [beg, end).
   * Uses the standard binning scheme from the SAM specification.
   */
  static int[] reg2bins(int beg, int end) {
    // Max possible bins for a query
    List<Integer> bins = new ArrayList<>();
    int e = end - 1;
    
    bins.add(0); // Level 0
    for (int k = 1 + (beg >> 26); k <= 1 + (e >> 26); k++) bins.add(k);       // Level 1
    for (int k = 9 + (beg >> 23); k <= 9 + (e >> 23); k++) bins.add(k);       // Level 2
    for (int k = 73 + (beg >> 20); k <= 73 + (e >> 20); k++) bins.add(k);     // Level 3
    for (int k = 585 + (beg >> 17); k <= 585 + (e >> 17); k++) bins.add(k);   // Level 4
    for (int k = 4681 + (beg >> 14); k <= 4681 + (e >> 14); k++) bins.add(k); // Level 5
    
    return bins.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Simple little-endian reader for BAI files (uncompressed).
   */
  private static class BGZFRawReader implements AutoCloseable {
    private final java.io.RandomAccessFile raf;
    private final byte[] buf4 = new byte[4];
    private final byte[] buf8 = new byte[8];

    BGZFRawReader(Path path) throws IOException {
      this.raf = new java.io.RandomAccessFile(path.toFile(), "r");
    }

    void readFully(byte[] buf) throws IOException {
      raf.readFully(buf);
    }

    int readInt() throws IOException {
      raf.readFully(buf4);
      return ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    long readLong() throws IOException {
      raf.readFully(buf8);
      return ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    @Override
    public void close() throws IOException {
      raf.close();
    }
  }
}
