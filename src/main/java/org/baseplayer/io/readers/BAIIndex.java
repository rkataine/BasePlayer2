package org.baseplayer.io.readers;

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
 * BAM index (.bai) accessed by path. Does <b>not</b> keep the whole genome index
 * in heap: on open it only records per-reference byte offsets, then loads one
 * reference's bins/linear index on demand for each query.
 */
public class BAIIndex {

  public static class Chunk {
    public final long start;
    public final long end;

    public Chunk(long start, long end) {
      this.start = start;
      this.end = end;
    }
  }

  private static class RefIndex {
    final Map<Integer, List<Chunk>> bins;
    final long[] linearIndex;

    RefIndex(Map<Integer, List<Chunk>> bins, long[] linearIndex) {
      this.bins = bins;
      this.linearIndex = linearIndex;
    }
  }

  private final Path baiPath;
  /** Byte offset in the BAI file where each reference's index block starts. */
  private final long[] refOffsets;
  /** Only the last-queried reference is kept parsed in memory. */
  private int loadedRefId = -1;
  private RefIndex loadedRef;

  public BAIIndex(Path baiPath) throws IOException {
    this.baiPath = baiPath;
    try (BGZFRawReader reader = new BGZFRawReader(baiPath)) {
      byte[] magic = new byte[4];
      reader.readFully(magic);
      if (magic[0] != 'B' || magic[1] != 'A' || magic[2] != 'I' || magic[3] != 1) {
        throw new IOException("Not a valid BAI file: " + baiPath);
      }

      int nRef = reader.readInt();
      refOffsets = new long[nRef];
      for (int i = 0; i < nRef; i++) {
        refOffsets[i] = reader.position();
        skipRefBlock(reader);
      }
    }
  }

  /** Drop the cached reference block (index path is kept). */
  public synchronized void unload() {
    loadedRefId = -1;
    loadedRef = null;
  }

  public int getReferenceCount() {
    return refOffsets.length;
  }

  /**
   * Get all BGZF chunks that may contain reads overlapping [start, end) for a reference.
   * Coordinates are 0-based.
   */
  public synchronized List<Chunk> getChunks(int refId, int start, int end) throws IOException {
    RefIndex ref = loadRef(refId);
    if (ref == null) {
      return Collections.emptyList();
    }

    int[] bins = reg2bins(start, end);
    List<Chunk> chunks = new ArrayList<>();
    for (int bin : bins) {
      List<Chunk> binChunks = ref.bins.get(bin);
      if (binChunks != null) {
        chunks.addAll(binChunks);
      }
    }
    if (chunks.isEmpty()) {
      return chunks;
    }

    long minOffset = 0;
    int linearIndexPos = start / 16384;
    if (ref.linearIndex.length > 0 && linearIndexPos < ref.linearIndex.length) {
      minOffset = ref.linearIndex[linearIndexPos];
    }

    List<Chunk> filtered = new ArrayList<>();
    for (Chunk chunk : chunks) {
      if (chunk.end > minOffset) {
        filtered.add(new Chunk(Math.max(chunk.start, minOffset), chunk.end));
      }
    }
    if (filtered.size() <= 1) {
      return filtered;
    }

    filtered.sort((a, b) -> Long.compare(a.start, b.start));
    List<Chunk> merged = new ArrayList<>();
    Chunk current = filtered.get(0);
    for (int i = 1; i < filtered.size(); i++) {
      Chunk next = filtered.get(i);
      if (next.start <= current.end) {
        current = new Chunk(current.start, Math.max(current.end, next.end));
      } else {
        merged.add(current);
        current = next;
      }
    }
    merged.add(current);
    return merged;
  }

  private RefIndex loadRef(int refId) throws IOException {
    if (refId < 0 || refId >= refOffsets.length) {
      return null;
    }
    if (refId == loadedRefId && loadedRef != null) {
      return loadedRef;
    }
    try (BGZFRawReader reader = new BGZFRawReader(baiPath)) {
      reader.seek(refOffsets[refId]);
      loadedRef = readRefBlock(reader);
      loadedRefId = refId;
      return loadedRef;
    }
  }

  private static void skipRefBlock(BGZFRawReader reader) throws IOException {
    int nBin = reader.readInt();
    for (int b = 0; b < nBin; b++) {
      reader.readInt(); // bin number
      int nChunk = reader.readInt();
      reader.skipBytes((long) nChunk * 16L); // 2 × uint64 per chunk
    }
    int nIntv = reader.readInt();
    reader.skipBytes((long) nIntv * 8L);
  }

  private static RefIndex readRefBlock(BGZFRawReader reader) throws IOException {
    int nBin = reader.readInt();
    Map<Integer, List<Chunk>> bins = new HashMap<>();
    for (int b = 0; b < nBin; b++) {
      int binNumber = reader.readInt();
      int nChunk = reader.readInt();
      List<Chunk> chunks = new ArrayList<>(nChunk);
      for (int c = 0; c < nChunk; c++) {
        chunks.add(new Chunk(reader.readLong(), reader.readLong()));
      }
      bins.put(binNumber, chunks);
    }
    int nIntv = reader.readInt();
    long[] linearIdx = new long[nIntv];
    for (int li = 0; li < nIntv; li++) {
      linearIdx[li] = reader.readLong();
    }
    return new RefIndex(bins, linearIdx);
  }

  static int[] reg2bins(int beg, int end) {
    List<Integer> bins = new ArrayList<>();
    int e = end - 1;
    bins.add(0);
    for (int k = 1 + (beg >> 26); k <= 1 + (e >> 26); k++) bins.add(k);
    for (int k = 9 + (beg >> 23); k <= 9 + (e >> 23); k++) bins.add(k);
    for (int k = 73 + (beg >> 20); k <= 73 + (e >> 20); k++) bins.add(k);
    for (int k = 585 + (beg >> 17); k <= 585 + (e >> 17); k++) bins.add(k);
    for (int k = 4681 + (beg >> 14); k <= 4681 + (e >> 14); k++) bins.add(k);
    return bins.stream().mapToInt(Integer::intValue).toArray();
  }

  private static class BGZFRawReader implements AutoCloseable {
    private final java.io.RandomAccessFile raf;
    private final byte[] buf4 = new byte[4];
    private final byte[] buf8 = new byte[8];

    BGZFRawReader(Path path) throws IOException {
      this.raf = new java.io.RandomAccessFile(path.toFile(), "r");
    }

    long position() throws IOException {
      return raf.getFilePointer();
    }

    void seek(long pos) throws IOException {
      raf.seek(pos);
    }

    void skipBytes(long n) throws IOException {
      if (n <= 0) {
        return;
      }
      long pos = raf.getFilePointer() + n;
      if (pos > raf.length()) {
        throw new IOException("BAI skip past EOF");
      }
      raf.seek(pos);
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
