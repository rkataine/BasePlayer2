package org.baseplayer.reads.bam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

import org.baseplayer.SharedModel;

/**
 * Custom CRAM file reader.
 * Parses CRAM 3.0 files: file definition, SAM header, container/slice/block
 * structure, compression header, and record decoding using rANS 4x8, gzip, and
 * raw block compression. No external library (htsjdk) is used.
 */
public class CRAMFileReader implements AlignmentReader, Closeable {

  private final Path cramPath;
  private final RandomAccessFile raf;
  private final CRAIIndex index;
  private final String sampleName;
  private final String[] refNames;
  private final int[] refLengths;
  private final Map<String, Integer> refNameToId;

  // ── public constructor ──────────────────────────────────────────

  public CRAMFileReader(Path cramPath) throws IOException {
    this.cramPath = cramPath;
    this.raf = new RandomAccessFile(cramPath.toFile(), "r");

    // 1) File definition: 26 bytes
    byte[] magic = new byte[4];
    raf.readFully(magic);
    if (magic[0] != 'C' || magic[1] != 'R' || magic[2] != 'A' || magic[3] != 'M')
      throw new IOException("Not a valid CRAM file: " + cramPath);
    int major = raf.read();
    int minor = raf.read();
    if (major < 3) throw new IOException("Unsupported CRAM version " + major + "." + minor);
    raf.skipBytes(20); // file-id

    // 2) SAM header container
    long headerContainerOffset = raf.getFilePointer();
    ContainerHeader ch = readContainerHeader();
    byte[] containerData = new byte[ch.length];
    raf.readFully(containerData);

    // The first block in the header container is the FILE_HEADER block
    ByteStream bs = new ByteStream(containerData);
    BlockHeader bh = readBlockHeader(bs);
    byte[] blockData = readBlockData(bs, bh);
    // blockData is the SAM header text (possibly gzip/raw)
    String headerText = new String(blockData);
    this.sampleName = parseSampleName(headerText, cramPath);

    // Parse reference sequences from @SQ lines
    List<String> names = new ArrayList<>();
    List<Integer> lengths = new ArrayList<>();
    for (String line : headerText.split("\n")) {
      if (line.startsWith("@SQ")) {
        String n = null; int len = 0;
        for (String f : line.split("\t")) {
          if (f.startsWith("SN:")) n = f.substring(3);
          else if (f.startsWith("LN:")) len = Integer.parseInt(f.substring(3));
        }
        if (n != null) { names.add(n); lengths.add(len); }
      }
    }
    this.refNames = names.toArray(new String[0]);
    this.refLengths = new int[lengths.size()];
    this.refNameToId = new HashMap<>();
    for (int i = 0; i < lengths.size(); i++) {
      refLengths[i] = lengths.get(i);
      refNameToId.put(refNames[i], i);
    }

    // 3) Load CRAI index
    Path craiPath = Path.of(cramPath + ".crai");
    if (!Files.exists(craiPath)) {
      String s = cramPath.toString();
      if (s.endsWith(".cram"))
        craiPath = Path.of(s.substring(0, s.length() - 5) + ".crai");
    }
    if (!Files.exists(craiPath))
      throw new IOException("CRAI index not found for: " + cramPath);
    this.index = new CRAIIndex(craiPath);
  }

  // ── AlignmentReader implementation ──────────────────────────────

  @Override
  public List<BAMRecord> query(String chrom, int start, int end) throws IOException {
    List<BAMRecord> records = new ArrayList<>();
    queryStreaming(chrom, start, end, r -> { records.add(r); return true; });
    return records;
  }

  @Override
  public void queryStreaming(String chrom, int start, int end,
                             Predicate<BAMRecord> consumer) throws IOException {
    Integer refId = resolveRefId(chrom);
    if (refId == null) return;

    // CRAI uses 1-based coordinates
    List<CRAIIndex.Entry> entries = index.getEntries(refId, start + 1, end + 1);
    if (entries.isEmpty()) return;

    Set<Long> seenOffsets = new HashSet<>();
    synchronized (raf) {
      for (CRAIIndex.Entry entry : entries) {
        if (!seenOffsets.add(entry.containerOffset)) continue;

        raf.seek(entry.containerOffset);
        ContainerHeader ch = readContainerHeader();
        byte[] containerData = new byte[ch.length];
        raf.readFully(containerData);

        List<BAMRecord> containerRecords = decodeContainer(containerData, refId, start, end, chrom);
        for (BAMRecord rec : containerRecords) {
          if (rec.isUnmapped() || rec.isSecondary() || rec.isSupplementary()) continue;
          if (rec.end <= start || rec.pos >= end) continue;
          if (!consumer.test(rec)) return;
        }
      }
    }
  }

  @Override public String getSampleName() { return sampleName; }
  @Override public String[] getRefNames() { return refNames; }
  @Override public int[] getRefLengths() { return refLengths; }
  @Override public Path getPath() { return cramPath; }

  @Override
  public void querySampledCounts(String chrom, int[] positions, int window, int[] counts, Runnable onChunkDone) throws IOException {
    Integer refId = resolveRefId(chrom);
    if (refId == null) return;

    // Collect all CRAI entries covering any of our sample windows
    int minPos = Integer.MAX_VALUE, maxPos = 0;
    for (int p : positions) {
      if (p < minPos) minPos = p;
      if (p + window > maxPos) maxPos = p + window;
    }
    // CRAI is 1-based
    List<CRAIIndex.Entry> entries = index.getEntries(refId, minPos + 1, maxPos + 1);
    if (entries.isEmpty()) return;

    // Deduplicate by container offset and decode each container once
    Set<Long> seenOffsets = new HashSet<>();
    synchronized (raf) {
      for (CRAIIndex.Entry entry : entries) {
        if (Thread.currentThread().isInterrupted()) return;
        if (!seenOffsets.add(entry.containerOffset)) continue;

        raf.seek(entry.containerOffset);
        ContainerHeader ch = readContainerHeader();
        byte[] containerData = new byte[ch.length];
        raf.readFully(containerData);

        // Decode with full span of our sample windows
        List<BAMRecord> recs = decodeContainer(containerData, refId, minPos, maxPos, chrom);
        for (BAMRecord rec : recs) {
          if (rec.isUnmapped() || rec.isSecondary() || rec.isSupplementary()) continue;
          // Bin into matching sample windows
          for (int i = 0; i < positions.length; i++) {
            if (rec.pos < positions[i] + window && rec.end > positions[i]) {
              counts[i]++;
            }
          }
        }
        // Notify after each container so UI can update progressively
        if (onChunkDone != null) onChunkDone.run();
      }
    }
  }

  @Override
  public void close() throws IOException { raf.close(); }

  // ── Container / Block parsing ──────────────────────────────────

  private static class ContainerHeader {
    int length;       // size of remaining container data
    int refSeqId;
    int refPos;       // 1-based
    int alignSpan;
    int numRecords;
    long recordCounter;
    long numBases;
    int numBlocks;
    int[] landmarks;
  }

  private ContainerHeader readContainerHeader() throws IOException {
    ContainerHeader h = new ContainerHeader();
    byte[] buf4 = new byte[4];
    raf.readFully(buf4);
    h.length = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    h.refSeqId = readITF8FromRAF();
    h.refPos = readITF8FromRAF();
    h.alignSpan = readITF8FromRAF();
    h.numRecords = readITF8FromRAF();
    h.recordCounter = readLTF8FromRAF();
    h.numBases = readLTF8FromRAF();
    h.numBlocks = readITF8FromRAF();
    int numLandmarks = readITF8FromRAF();
    h.landmarks = new int[numLandmarks];
    for (int i = 0; i < numLandmarks; i++) h.landmarks[i] = readITF8FromRAF();
    // CRC32 (4 bytes) — skip
    raf.skipBytes(4);
    return h;
  }

  private static class BlockHeader {
    int method;         // 0=raw, 1=gzip, 4=rANS
    int contentType;    // 0=FILE_HEADER, 1=COMPRESSION_HEADER, 2=SLICE_HEADER, 4=EXTERNAL, 5=CORE
    int contentId;
    int compressedSize;
    int uncompressedSize;
  }

  private static BlockHeader readBlockHeader(ByteStream bs) {
    BlockHeader bh = new BlockHeader();
    bh.method = bs.readByte();
    bh.contentType = bs.readByte();
    bh.contentId = bs.readITF8();
    bh.compressedSize = bs.readITF8();
    bh.uncompressedSize = bs.readITF8();
    System.err.println("Block: method=" + bh.method + " type=" + bh.contentType
                     + " id=" + bh.contentId + " comp=" + bh.compressedSize
                     + " uncomp=" + bh.uncompressedSize);
    return bh;
  }

  private static byte[] readBlockData(ByteStream bs, BlockHeader bh) throws IOException {
    byte[] compressed = bs.readBytes(bh.compressedSize);
    // CRC32 (4 bytes)
    bs.skip(4);
    return decompress(compressed, bh.method, bh.uncompressedSize);
  }

  // ── Decompression ──────────────────────────────────────────────

  private static byte[] decompress(byte[] data, int method, int uncompressedSize) throws IOException {
    return switch (method) {
      case 0 -> data; // raw
      case 1 -> decompressGzip(data, uncompressedSize);
      case 4 -> decompressRANS(data, uncompressedSize);
      default -> throw new IOException("Unsupported CRAM block compression method: " + method);
    };
  }

  private static byte[] decompressGzip(byte[] data, int expectedSize) throws IOException {
    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream(expectedSize);
      byte[] buf = new byte[8192];
      int n;
      while ((n = gis.read(buf)) >= 0) bos.write(buf, 0, n);
      return bos.toByteArray();
    }
  }

  /**
   * rANS 4x8 decompression (CRAM 3.0 method 4).
   * Format: order(1) + compSize(4LE) + uncompSize(4LE) + freqTable + 4×states + data
   */
  private static byte[] decompressRANS(byte[] data, int expectedSize) throws IOException {
    if (data.length < 9) throw new IOException("rANS data too short");
    int order = data[0] & 0xFF;
    int compSize = readInt32LE(data, 1);
    int uncompSize = readInt32LE(data, 5);
    System.err.println("rANS: order=" + order + " compSize=" + compSize
                     + " uncompSize=" + uncompSize + " data.length=" + data.length);
    if (uncompSize <= 0) return new byte[0];

    int ptr = 9; // past the 9-byte prefix
    if (order == 0) {
      return ransDecodeOrder0(data, ptr, uncompSize);
    } else {
      return ransDecodeOrder1(data, ptr, uncompSize);
    }
  }

  // ── rANS order-0 ───────────────────────────────────────────────

  private static byte[] ransDecodeOrder0(byte[] data, int ptr, int outSize) throws IOException {
    int[] freq = new int[256];
    int[] cumFreq = new int[257];
    int[] ptrArr = {ptr};

    readFreqTable0(data, ptrArr, freq);
    ptr = ptrArr[0];

    // Build cumulative frequencies
    cumFreq[0] = 0;
    for (int i = 0; i < 256; i++) cumFreq[i + 1] = cumFreq[i] + freq[i];
    int totalFreq = cumFreq[256];

    // Normalize to 4096 if needed
    if (totalFreq != 4096) {
      normalizeFreqs(freq, cumFreq, 4096);
      totalFreq = 4096;
    }

    // Build reverse lookup
    byte[] lookup = new byte[4096];
    for (int sym = 0; sym < 256; sym++) {
      for (int j = cumFreq[sym]; j < cumFreq[sym + 1]; j++) {
        lookup[j] = (byte) sym;
      }
    }

    // Init 4 rANS states
    long[] R = new long[4];
    for (int i = 0; i < 4; i++) {
      R[i] = readUInt32LE(data, ptr);
      ptr += 4;
    }

    System.err.println("rANS O0: ptr after states=" + ptr + " data.length=" + data.length + " outSize=" + outSize);
    byte[] output = new byte[outSize];
    for (int i = 0; i < outSize; i++) {
      int idx = i & 3; // i % 4
      int f = (int) (R[idx] & 0xFFF); // cumulative freq
      int sym = lookup[f] & 0xFF;
      output[i] = (byte) sym;

      // Advance step
      R[idx] = freq[sym] * (R[idx] >> 12) + (R[idx] & 0xFFF) - cumFreq[sym];

      // Renormalize
      while (R[idx] < (1L << 23)) {
        if (ptr >= data.length) {
          System.err.println("rANS O0: out of data at i=" + i + "/" + outSize + " ptr=" + ptr
                           + " state=" + R[idx] + " sym=" + sym);
          return output;
        }
        R[idx] = (R[idx] << 8) | (data[ptr++] & 0xFF);
      }
    }
    return output;
  }

  // ── rANS order-1 ───────────────────────────────────────────────

  private static byte[] ransDecodeOrder1(byte[] data, int ptr, int outSize) throws IOException {
    int[][] freq = new int[256][256];
    int[][] cumFreq = new int[256][257];
    byte[][] lookup = new byte[256][4096];
    int[] ptrArr = {ptr};

    // Read frequency tables: same structure as order-0 RLE but nested per context
    readFreqTable1(data, ptrArr, freq, cumFreq, lookup);
    ptr = ptrArr[0];

    // Init 4 rANS states + last-symbol trackers
    long[] R = new long[4];
    int[] L = new int[4]; // last symbol for each state
    for (int i = 0; i < 4; i++) {
      R[i] = readUInt32LE(data, ptr);
      ptr += 4;
    }

    System.err.println("rANS O1: ptr after states=" + ptr + " data.length=" + data.length + " outSize=" + outSize);
    byte[] output = new byte[outSize];
    int outSize4 = outSize / 4;

    for (int i = 0; i < outSize4; i++) {
      for (int j = 0; j < 4; j++) {
        int ctx = L[j];
        int f = (int) (R[j] & 0xFFF);
        int sym = lookup[ctx][f] & 0xFF;
        output[i + j * outSize4] = (byte) sym;

        R[j] = freq[ctx][sym] * (R[j] >> 12) + (R[j] & 0xFFF) - cumFreq[ctx][sym];
        while (R[j] < (1L << 23)) {
          if (ptr >= data.length) {
            System.err.println("rANS O1: out of data at i=" + i + " j=" + j + " outSize4=" + outSize4
                             + " ptr=" + ptr + " state=" + R[j] + " sym=" + sym + " ctx=" + ctx);
            return output;
          }
          R[j] = (R[j] << 8) | (data[ptr++] & 0xFF);
        }
        L[j] = sym;
      }
    }
    // Remainder using state 3
    for (int i = outSize4 * 4; i < outSize; i++) {
      int ctx = L[3];
      int f = (int) (R[3] & 0xFFF);
      int sym = lookup[ctx][f] & 0xFF;
      output[i] = (byte) sym;

      R[3] = freq[ctx][sym] * (R[3] >> 12) + (R[3] & 0xFFF) - cumFreq[ctx][sym];
      while (R[3] < (1L << 23)) {
        if (ptr >= data.length) {
          System.err.println("rANS O1 remainder: out of data at i=" + i + " ptr=" + ptr);
          return output;
        }
        R[3] = (R[3] << 8) | (data[ptr++] & 0xFF);
      }
      L[3] = sym;
    }
    return output;
  }

  // ── Freq table reading (order-0 RLE format) ────────────────────

  private static void readFreqTable0(byte[] data, int[] ptrArr, int[] freq) {
    int ptr = ptrArr[0];
    for (int i = 0; i < 256; i++) freq[i] = 0;

    int sym = data[ptr++] & 0xFF;
    int lastSym = sym;
    int rle = 0;

    while (true) {
      int f = data[ptr++] & 0xFF;
      if (f >= 128) f = ((f & 0x7F) << 8) | (data[ptr++] & 0xFF);
      freq[sym] = f;

      if (rle > 0) {
        rle--;
        sym++;
      } else {
        sym = data[ptr++] & 0xFF;
        if (sym == 0) break;
        if (sym == ((lastSym + 1) & 0xFF)) {
          rle = data[ptr++] & 0xFF;
        }
      }
      lastSym = sym;
    }
    ptrArr[0] = ptr;
  }

  private static void readFreqTable1(byte[] data, int[] ptrArr, int[][] freq,
                                     int[][] cumFreq, byte[][] lookup) {
    int sym = data[ptrArr[0]++] & 0xFF;
    int lastSym = sym;
    int rle = 0;

    while (true) {
      int[] f0 = new int[256];
      readFreqTable0(data, ptrArr, f0);
      freq[sym] = f0;

      // Build cumulative + lookup for this context
      int total = 0;
      for (int i = 0; i < 256; i++) total += f0[i];
      if (total != 4096) normalizeFreqs(f0, null, 4096);
      cumFreq[sym][0] = 0;
      for (int i = 0; i < 256; i++) cumFreq[sym][i + 1] = cumFreq[sym][i] + f0[i];
      for (int s = 0; s < 256; s++) {
        for (int j = cumFreq[sym][s]; j < cumFreq[sym][s + 1]; j++) {
          lookup[sym][j] = (byte) s;
        }
      }
      freq[sym] = f0;

      if (rle > 0) {
        rle--;
        sym++;
      } else {
        sym = data[ptrArr[0]++] & 0xFF;
        if (sym == 0) break;
        if (sym == ((lastSym + 1) & 0xFF)) {
          rle = data[ptrArr[0]++] & 0xFF;
        }
      }
      lastSym = sym;
    }
  }

  private static void normalizeFreqs(int[] freq, int[] cumFreq, int target) {
    int total = 0;
    for (int f : freq) total += f;
    if (total == 0 || total == target) return;

    int maxIdx = 0;
    for (int i = 1; i < 256; i++) if (freq[i] > freq[maxIdx]) maxIdx = i;

    int newTotal = 0;
    for (int i = 0; i < 256; i++) {
      if (freq[i] == 0) continue;
      freq[i] = Math.max(1, (int) ((long) freq[i] * target / total));
      newTotal += freq[i];
    }
    freq[maxIdx] += target - newTotal;

    if (cumFreq != null) {
      cumFreq[0] = 0;
      for (int i = 0; i < 256; i++) cumFreq[i + 1] = cumFreq[i] + freq[i];
    }
  }

  // ── Container decoding ─────────────────────────────────────────

  private List<BAMRecord> decodeContainer(byte[] data, int queryRefId,
                                          int queryStart, int queryEnd, String chrom) throws IOException {
    ByteStream bs = new ByteStream(data);

    // First block is the compression header
    BlockHeader compHdr = readBlockHeader(bs);
    byte[] compHdrData = readBlockData(bs, compHdr);
    CompressionHeader ch = parseCompressionHeader(compHdrData);

    List<BAMRecord> allRecords = new ArrayList<>();

    // Remaining blocks are slices
    while (bs.remaining() > 0) {
      try {
        List<BAMRecord> sliceRecords = decodeSlice(bs, ch, queryRefId, chrom);
        allRecords.addAll(sliceRecords);
      } catch (Exception e) {
        System.err.println("CRAM slice decode error: " + e.getMessage());
        break;
      }
    }
    return allRecords;
  }

  // ── Compression header ─────────────────────────────────────────

  private static class CompressionHeader {
    boolean readNamesIncluded;
    boolean apDelta;
    byte[] substitutionMatrix;
    /**
     * Decoded substitution lookup: subLookup[refBaseIndex][bsCode] = read base char.
     * refBaseIndex: A=0, C=1, G=2, T=3, N=4.
     */
    char[][] subLookup;
    byte[][] tagDictionary; // each entry is a list of tag-type triples
    Map<String, EncodingDescriptor> dataSeriesEncodings = new HashMap<>();
    Map<Integer, EncodingDescriptor> tagEncodings = new HashMap<>();
  }

  private static class EncodingDescriptor {
    int codecId;
    byte[] params;
  }

  private static CompressionHeader parseCompressionHeader(byte[] data) throws IOException {
    ByteStream bs = new ByteStream(data);
    CompressionHeader ch = new CompressionHeader();

    // Preservation map
    int pmSize = bs.readITF8(); // total byte size
    int pmCount = bs.readITF8();
    ch.readNamesIncluded = true;
    ch.apDelta = true;
    ch.substitutionMatrix = new byte[5];

    for (int i = 0; i < pmCount; i++) {
      int k1 = bs.readByte();
      int k2 = bs.readByte();
      String key = "" + (char) k1 + (char) k2;
      switch (key) {
        case "RN" -> ch.readNamesIncluded = bs.readByte() != 0;
        case "AP" -> ch.apDelta = bs.readByte() != 0;
        case "RR" -> bs.readByte(); // reference required — skip
        case "SM" -> {
          for (int j = 0; j < 5; j++) ch.substitutionMatrix[j] = (byte) bs.readByte();
          ch.subLookup = buildSubstitutionLookup(ch.substitutionMatrix);
        }
        case "TD" -> {
          int tdSize = bs.readITF8();
          byte[] td = bs.readBytes(tdSize);
          ch.tagDictionary = parseTagDictionary(td);
        }
        default -> {
          // Unknown preservation key — try to skip safely
        }
      }
    }

    // Data series encoding map
    int dsSize = bs.readITF8();
    int dsCount = bs.readITF8();
    for (int i = 0; i < dsCount; i++) {
      int c1 = bs.readByte();
      int c2 = bs.readByte();
      String key = "" + (char) c1 + (char) c2;
      EncodingDescriptor ed = readEncodingDescriptor(bs);
      ch.dataSeriesEncodings.put(key, ed);
    }

    // Tag encoding map
    int teSize = bs.readITF8();
    int teCount = bs.readITF8();
    for (int i = 0; i < teCount; i++) {
      int tagKey = bs.readITF8();
      EncodingDescriptor ed = readEncodingDescriptor(bs);
      ch.tagEncodings.put(tagKey, ed);
    }

    return ch;
  }

  /**
   * Build substitution lookup table from the 5-byte SM matrix.
   * Each byte encodes a permutation of 4 alternative bases using Lehmer code (2-bit fields).
   * subLookup[refBaseIdx][bsCode] = substituted base character.
   */
  private static char[][] buildSubstitutionLookup(byte[] sm) {
    char[] bases = {'A', 'C', 'G', 'T', 'N'};
    char[][] lookup = new char[5][4];
    for (int r = 0; r < 5; r++) {
      // Build list of 4 alternative bases (all except bases[r])
      char[] alts = new char[4];
      int ai = 0;
      for (int j = 0; j < 5; j++) {
        if (j != r) alts[ai++] = bases[j];
      }
      int b = sm[r] & 0xFF;
      // Lehmer decode: 4 x 2-bit fields from MSB to LSB
      java.util.List<Character> remaining = new java.util.ArrayList<>(4);
      for (char c : alts) remaining.add(c);
      for (int code = 0; code < 4; code++) {
        int shift = (3 - code) * 2;
        int idx = (b >> shift) & 0x3;
        if (idx >= remaining.size()) idx = remaining.size() - 1;
        lookup[r][code] = remaining.remove(idx);
      }
    }
    return lookup;
  }

  private static byte[][] parseTagDictionary(byte[] td) {
    List<byte[]> entries = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= td.length; i++) {
      if (i == td.length || td[i] == 0) {
        if (i > start) {
          byte[] entry = new byte[i - start];
          System.arraycopy(td, start, entry, 0, i - start);
          entries.add(entry);
        } else {
          entries.add(new byte[0]);
        }
        start = i + 1;
      }
    }
    return entries.toArray(new byte[0][]);
  }

  private static EncodingDescriptor readEncodingDescriptor(ByteStream bs) {
    EncodingDescriptor ed = new EncodingDescriptor();
    ed.codecId = bs.readITF8();
    int paramLen = bs.readITF8();
    ed.params = bs.readBytes(paramLen);
    return ed;
  }

  // ── Slice decoding ─────────────────────────────────────────────

  private List<BAMRecord> decodeSlice(ByteStream containerBs, CompressionHeader ch,
                                      int queryRefId, String chrom) throws IOException {
    // Slice header block
    BlockHeader sliceHdrBlock = readBlockHeader(containerBs);
    byte[] sliceHdrData = readBlockData(containerBs, sliceHdrBlock);
    ByteStream shBs = new ByteStream(sliceHdrData);

    int sliceRefSeqId = shBs.readITF8();
    int sliceAlignStart = shBs.readITF8(); // 1-based
    int sliceAlignSpan = shBs.readITF8();
    int sliceNumRecords = shBs.readITF8();
    long sliceRecordCounter = shBs.readLTF8();
    int sliceNumBlocks = shBs.readITF8();
    int numContentIds = shBs.readITF8();
    int[] contentIds = new int[numContentIds];
    for (int i = 0; i < numContentIds; i++) contentIds[i] = shBs.readITF8();
    int embeddedRefBlockId = shBs.readITF8();
    shBs.skip(16); // reference MD5

    // Read core data block and external blocks
    byte[] coreData = null;
    Map<Integer, byte[]> externalBlocks = new HashMap<>();

    for (int i = 0; i < sliceNumBlocks; i++) {
      BlockHeader bh = readBlockHeader(containerBs);
      byte[] blockData = readBlockData(containerBs, bh);
      if (bh.contentType == 5) { // CORE
        coreData = blockData;
      } else if (bh.contentType == 4) { // EXTERNAL
        externalBlocks.put(bh.contentId, blockData);
      }
    }

    if (coreData == null) coreData = new byte[0];

    // Decode records
    boolean multiRef = (sliceRefSeqId == -2);
    return decodeRecords(ch, sliceNumRecords, sliceRefSeqId, sliceAlignStart, sliceAlignSpan,
                         coreData, externalBlocks, multiRef, queryRefId, chrom);
  }

  // ── Record decoding ────────────────────────────────────────────

  private List<BAMRecord> decodeRecords(CompressionHeader ch, int numRecords,
      int sliceRefId, int sliceAlignStart, int sliceAlignSpan,
      byte[] coreData, Map<Integer, byte[]> externalBlocks,
      boolean multiRef, int queryRefId, String chrom) throws IOException {

    BitStream coreBits = new BitStream(coreData);
    Map<Integer, ByteStream> extStreams = new HashMap<>();
    for (var entry : externalBlocks.entrySet()) {
      extStreams.put(entry.getKey(), new ByteStream(entry.getValue()));
    }

    // Build decoders for each data series
    IntDecoder bfDec = buildIntDecoder(ch.dataSeriesEncodings.get("BF"), coreBits, extStreams);
    IntDecoder cfDec = buildIntDecoder(ch.dataSeriesEncodings.get("CF"), coreBits, extStreams);
    IntDecoder riDec = multiRef ? buildIntDecoder(ch.dataSeriesEncodings.get("RI"), coreBits, extStreams) : null;
    IntDecoder rlDec = buildIntDecoder(ch.dataSeriesEncodings.get("RL"), coreBits, extStreams);
    IntDecoder apDec = buildIntDecoder(ch.dataSeriesEncodings.get("AP"), coreBits, extStreams);
    IntDecoder rgDec = buildIntDecoder(ch.dataSeriesEncodings.get("RG"), coreBits, extStreams);
    IntDecoder mqDec = buildIntDecoder(ch.dataSeriesEncodings.get("MQ"), coreBits, extStreams);
    IntDecoder fnDec = buildIntDecoder(ch.dataSeriesEncodings.get("FN"), coreBits, extStreams);
    IntDecoder fpDec = buildIntDecoder(ch.dataSeriesEncodings.get("FP"), coreBits, extStreams);
    IntDecoder dlDec = buildIntDecoder(ch.dataSeriesEncodings.get("DL"), coreBits, extStreams);
    IntDecoder rsDec = buildIntDecoder(ch.dataSeriesEncodings.get("RS"), coreBits, extStreams);
    IntDecoder hcDec = buildIntDecoder(ch.dataSeriesEncodings.get("HC"), coreBits, extStreams);
    IntDecoder pdDec = buildIntDecoder(ch.dataSeriesEncodings.get("PD"), coreBits, extStreams);
    IntDecoder tlDec = buildIntDecoder(ch.dataSeriesEncodings.get("TL"), coreBits, extStreams);
    IntDecoder nfDec = buildIntDecoder(ch.dataSeriesEncodings.get("NF"), coreBits, extStreams);
    IntDecoder nsDec = buildIntDecoder(ch.dataSeriesEncodings.get("NS"), coreBits, extStreams);
    IntDecoder npDec = buildIntDecoder(ch.dataSeriesEncodings.get("NP"), coreBits, extStreams);
    IntDecoder tsDec = buildIntDecoder(ch.dataSeriesEncodings.get("TS"), coreBits, extStreams);

    ByteDecoder fcDec = buildByteDecoder(ch.dataSeriesEncodings.get("FC"), coreBits, extStreams);
    ByteDecoder baDec = buildByteDecoder(ch.dataSeriesEncodings.get("BA"), coreBits, extStreams);
    ByteDecoder bsDec = buildByteDecoder(ch.dataSeriesEncodings.get("BS"), coreBits, extStreams);
    ByteDecoder qsDec = buildByteDecoder(ch.dataSeriesEncodings.get("QS"), coreBits, extStreams);
    ByteDecoder mfDec = buildByteDecoder(ch.dataSeriesEncodings.get("MF"), coreBits, extStreams);

    ByteArrayDecoder rnDec = buildByteArrayDecoder(ch.dataSeriesEncodings.get("RN"), coreBits, extStreams);
    ByteArrayDecoder inDec = buildByteArrayDecoder(ch.dataSeriesEncodings.get("IN"), coreBits, extStreams);
    ByteArrayDecoder scDec = buildByteArrayDecoder(ch.dataSeriesEncodings.get("SC"), coreBits, extStreams);
    ByteArrayDecoder bbDec = buildByteArrayDecoder(ch.dataSeriesEncodings.get("BB"), coreBits, extStreams);
    ByteArrayDecoder qqDec = buildByteArrayDecoder(ch.dataSeriesEncodings.get("QQ"), coreBits, extStreams);

    // Build tag decoders
    Map<Integer, ByteArrayDecoder> tagDecoders = new HashMap<>();
    for (var e : ch.tagEncodings.entrySet()) {
      tagDecoders.put(e.getKey(), buildByteArrayDecoder(e.getValue(), coreBits, extStreams));
    }

    List<BAMRecord> records = new ArrayList<>(numRecords);
    int prevAlignStart = sliceAlignStart; // 1-based

    // Fetch reference bases for the slice region (for substitution decoding)
    String refBases = null;
    int refBasesStart = 0; // 0-based genomic start of refBases string
    if (chrom != null && SharedModel.referenceGenome != null && sliceAlignStart > 0) {
      // sliceAlignStart is 1-based; fetch a wider region to cover all reads in this slice
      int fetchStart = sliceAlignStart; // 1-based for getBases
      int fetchEnd = sliceAlignStart + sliceAlignSpan + 1000; // 1-based, with margin
      refBases = SharedModel.referenceGenome.getBases(chrom, fetchStart, fetchEnd);
      refBasesStart = fetchStart; // 1-based, matches genomicPos coordinate system
    }

    for (int i = 0; i < numRecords; i++) {
      int bamFlags = bfDec.decode();
      int cramFlags = cfDec.decode();

      int recRefId = multiRef ? riDec.decode() : sliceRefId;
      int readLen = rlDec.decode();

      int ap = apDec.decode();
      int alignStart;
      if (ch.apDelta) {
        alignStart = prevAlignStart + ap;
        prevAlignStart = alignStart;
      } else {
        alignStart = ap;
      }

      int readGroup = rgDec.decode();

      // Read name
      String readName = null;
      if (ch.readNamesIncluded && rnDec != null) {
        byte[] rn = rnDec.decode();
        if (rn != null) readName = new String(rn);
      }

      // Mate info
      boolean detached = (cramFlags & 0x02) != 0;
      boolean hasMateDownstream = (cramFlags & 0x04) != 0;
      if (detached) {
        if (mfDec != null) mfDec.decode(); // mate bit flags
        if (!ch.readNamesIncluded && rnDec != null) {
          byte[] rn = rnDec.decode();
          if (rn != null && readName == null) readName = new String(rn);
        }
        if (nsDec != null) nsDec.decode(); // mate ref seq id
        if (npDec != null) npDec.decode(); // mate pos
        if (tsDec != null) tsDec.decode(); // template size
      } else if (hasMateDownstream) {
        if (nfDec != null) nfDec.decode(); // distance to next fragment
      }

      // Tags
      if (tlDec != null) {
        int tlIdx = tlDec.decode();
        if (ch.tagDictionary != null && tlIdx >= 0 && tlIdx < ch.tagDictionary.length) {
          byte[] tagList = ch.tagDictionary[tlIdx];
          // Each tag is 3 bytes: tag[0], tag[1], type
          for (int t = 0; t + 2 < tagList.length; t += 3) {
            int tagKey = ((tagList[t] & 0xFF) << 16) | ((tagList[t + 1] & 0xFF) << 8) | (tagList[t + 2] & 0xFF);
            ByteArrayDecoder td = tagDecoders.get(tagKey);
            if (td != null) td.decode(); // read and discard
          }
        }
      }

      // Read features → compute reference span and collect mismatches
      boolean unmappedSeq = (cramFlags & 0x08) != 0;
      int refSpan = readLen;
      List<int[]> mmList = null; // collected as [genomicPos0based, baseChar]

      if (!unmappedSeq && fnDec != null) {
        int numFeatures = fnDec.decode();
        int prevFeaturePos = 0;
        int refOffset = 0; // tracks ref offset adjustments from indels/clips
        for (int f = 0; f < numFeatures; f++) {
          int fc = fcDec != null ? fcDec.decode() : 0;
          int fp = fpDec != null ? fpDec.decode() : 0;
          int featurePos = prevFeaturePos + fp;
          prevFeaturePos = featurePos;

          switch ((char) fc) {
            case 'B' -> { // read base (explicit mismatch)
              int base = baDec != null ? baDec.decode() : 'N';
              if (qsDec != null) qsDec.decode();
              // featurePos is 1-based in read; genomic pos = alignStart + featurePos-1 + refOffset (1-based)
              int genomicPos = alignStart + (featurePos - 1) + refOffset;
              if (mmList == null) mmList = new ArrayList<>();
              mmList.add(new int[]{genomicPos, base});
            }
            case 'X' -> { // substitution
              int bsCode = bsDec != null ? bsDec.decode() : 0;
              // Resolve read base from substitution matrix + reference
              int genomicPos = alignStart + (featurePos - 1) + refOffset; // 1-based
              char readBase = '?';
              if (ch.subLookup != null && refBases != null) {
                int refIdx = genomicPos - refBasesStart;
                if (refIdx >= 0 && refIdx < refBases.length()) {
                  char refBase = refBases.charAt(refIdx);
                  int ri = switch (Character.toUpperCase(refBase)) {
                    case 'A' -> 0; case 'C' -> 1; case 'G' -> 2; case 'T' -> 3; default -> 4;
                  };
                  if (bsCode >= 0 && bsCode < 4) readBase = ch.subLookup[ri][bsCode];
                }
              }
              if (mmList == null) mmList = new ArrayList<>();
              mmList.add(new int[]{genomicPos, readBase});
            }
            case 'I' -> { // insertion
              byte[] ins = inDec != null ? inDec.decode() : null;
              if (ins != null) { refSpan -= ins.length; refOffset -= ins.length; }
            }
            case 'i' -> { // single base insertion
              if (baDec != null) baDec.decode();
              refSpan -= 1;
              refOffset -= 1;
            }
            case 'D' -> { // deletion
              int dl = dlDec != null ? dlDec.decode() : 0;
              refSpan += dl;
              refOffset += dl;
            }
            case 'N' -> { // ref skip
              int rs = rsDec != null ? rsDec.decode() : 0;
              refSpan += rs;
              refOffset += rs;
            }
            case 'S' -> { // soft clip
              byte[] sc = scDec != null ? scDec.decode() : null;
              if (sc != null) { refSpan -= sc.length; refOffset -= sc.length; }
            }
            case 'H' -> { // hard clip
              if (hcDec != null) hcDec.decode();
            }
            case 'P' -> { // padding
              if (pdDec != null) pdDec.decode();
            }
            case 'Q' -> { // single quality score
              if (qsDec != null) qsDec.decode();
            }
            case 'q' -> { // quality score stretch
              if (qqDec != null) qqDec.decode();
            }
            case 'b' -> { // bases + qualities
              if (bbDec != null) bbDec.decode();
              if (qqDec != null) qqDec.decode();
            }
            default -> {
              // Unknown feature code — skip
            }
          }
        }
      }

      // Mapping quality
      int mapq = mqDec != null ? mqDec.decode() : 0;

      // Quality scores (if preserved as array)
      if ((cramFlags & 0x01) != 0 && qsDec != null) {
        for (int q = 0; q < readLen; q++) qsDec.decode();
      }

      // Build BAMRecord
      BAMRecord rec = new BAMRecord();
      rec.refID = recRefId;
      rec.pos = alignStart; // Keep 1-based (matches drawStack coordinate system)
      rec.end = rec.pos + Math.max(1, refSpan);
      rec.flag = bamFlags;
      rec.mapq = mapq;
      rec.readLength = readLen;
      rec.readName = readName;
      rec.cigarOps = null; // Not needed for drawing

      // Pack mismatches: [pos0, base0, pos1, base1, ...]
      if (mmList != null && !mmList.isEmpty()) {
        rec.mismatches = new int[mmList.size() * 2];
        for (int m = 0; m < mmList.size(); m++) {
          rec.mismatches[m * 2] = mmList.get(m)[0];
          rec.mismatches[m * 2 + 1] = mmList.get(m)[1];
        }
      }

      records.add(rec);
    }
    return records;
  }

  // ── Codec builders ─────────────────────────────────────────────

  @FunctionalInterface
  private interface IntDecoder { int decode() throws IOException; }

  @FunctionalInterface
  private interface ByteDecoder { int decode() throws IOException; }

  @FunctionalInterface
  private interface ByteArrayDecoder { byte[] decode() throws IOException; }

  private IntDecoder buildIntDecoder(EncodingDescriptor ed, BitStream coreBits,
                                     Map<Integer, ByteStream> extStreams) {
    if (ed == null) return () -> 0;
    ByteStream paramBs = new ByteStream(ed.params);
    return switch (ed.codecId) {
      case 0 -> () -> 0; // NULL
      case 1 -> { // EXTERNAL
        int blockId = paramBs.readITF8();
        ByteStream ext = extStreams.get(blockId);
        yield ext != null ? () -> ext.readITF8() : () -> 0;
      }
      case 3 -> { // HUFFMAN_INT
        int numSymbols = paramBs.readITF8();
        int[] symbols = new int[numSymbols];
        for (int i = 0; i < numSymbols; i++) symbols[i] = paramBs.readITF8();
        int numLens = paramBs.readITF8();
        int[] bitLens = new int[numLens];
        for (int i = 0; i < numLens; i++) bitLens[i] = paramBs.readITF8();

        if (numSymbols == 1) {
          int val = symbols[0];
          yield () -> val;
        } else {
          // Build Huffman decode tree
          HuffmanDecoder hd = new HuffmanDecoder(symbols, bitLens);
          yield () -> hd.decode(coreBits);
        }
      }
      case 6 -> { // BETA
        int offset = paramBs.readITF8();
        int numBits = paramBs.readITF8();
        yield () -> coreBits.readBits(numBits) - offset;
      }
      case 7 -> { // SUBEXP
        int offset = paramBs.readITF8();
        int k = paramBs.readITF8();
        yield () -> decodeSubexp(coreBits, k) - offset;
      }
      case 8 -> { // GOLOMB_RICE
        int offset = paramBs.readITF8();
        int log2m = paramBs.readITF8();
        yield () -> decodeGolombRice(coreBits, log2m) - offset;
      }
      case 9 -> { // GAMMA
        int offset = paramBs.readITF8();
        yield () -> decodeGamma(coreBits) - offset;
      }
      default -> () -> 0;
    };
  }

  private ByteDecoder buildByteDecoder(EncodingDescriptor ed, BitStream coreBits,
                                       Map<Integer, ByteStream> extStreams) {
    if (ed == null) return () -> 0;
    ByteStream paramBs = new ByteStream(ed.params);
    return switch (ed.codecId) {
      case 0 -> () -> 0; // NULL
      case 1 -> { // EXTERNAL — byte data series reads single bytes
        int blockId = paramBs.readITF8();
        ByteStream ext = extStreams.get(blockId);
        yield ext != null ? () -> ext.readByte() : () -> 0;
      }
      case 3 -> { // HUFFMAN_INT (used for byte data too)
        int numSymbols = paramBs.readITF8();
        int[] symbols = new int[numSymbols];
        for (int i = 0; i < numSymbols; i++) symbols[i] = paramBs.readITF8();
        int numLens = paramBs.readITF8();
        int[] bitLens = new int[numLens];
        for (int i = 0; i < numLens; i++) bitLens[i] = paramBs.readITF8();

        if (numSymbols == 1) {
          int val = symbols[0];
          yield () -> val;
        } else {
          HuffmanDecoder hd = new HuffmanDecoder(symbols, bitLens);
          yield () -> hd.decode(coreBits);
        }
      }
      default -> () -> 0;
    };
  }

  private ByteArrayDecoder buildByteArrayDecoder(EncodingDescriptor ed, BitStream coreBits,
                                                 Map<Integer, ByteStream> extStreams) {
    if (ed == null) return () -> new byte[0];
    ByteStream paramBs = new ByteStream(ed.params);
    return switch (ed.codecId) {
      case 0 -> () -> new byte[0]; // NULL
      case 4 -> { // BYTE_ARRAY_LEN
        IntDecoder lenDecoder = buildIntDecoder(readEncodingDescriptor(paramBs), coreBits, extStreams);
        ByteArrayDecoder valDecoder = buildByteArrayDecoderInner(readEncodingDescriptor(paramBs), coreBits, extStreams);
        yield () -> {
          int len = lenDecoder.decode();
          // Read len bytes from the value codec's external block
          return readBytesFromCodec(valDecoder, len, ed, extStreams);
        };
      }
      case 5 -> { // BYTE_ARRAY_STOP
        int stopByte = paramBs.readByte();
        int blockId = paramBs.readITF8();
        ByteStream ext = extStreams.get(blockId);
        yield ext != null ? () -> ext.readUntil(stopByte) : () -> new byte[0];
      }
      default -> () -> new byte[0];
    };
  }

  private ByteArrayDecoder buildByteArrayDecoderInner(EncodingDescriptor ed, BitStream coreBits,
                                                      Map<Integer, ByteStream> extStreams) {
    if (ed == null) return () -> new byte[0];
    ByteStream paramBs = new ByteStream(ed.params);
    if (ed.codecId == 1) { // EXTERNAL
      int blockId = paramBs.readITF8();
      ByteStream ext = extStreams.get(blockId);
      return ext != null ? () -> new byte[]{(byte) ext.readByte()} : () -> new byte[0];
    }
    return () -> new byte[0];
  }

  private byte[] readBytesFromCodec(ByteArrayDecoder valDecoder, int len,
                                    EncodingDescriptor outerEd,
                                    Map<Integer, ByteStream> extStreams) throws IOException {
    // For BYTE_ARRAY_LEN with EXTERNAL value codec, read len bytes from the external block
    ByteStream paramBs = new ByteStream(outerEd.params);
    // Skip the length encoding descriptor
    readEncodingDescriptor(paramBs);
    // Read the value encoding descriptor
    EncodingDescriptor valEd = readEncodingDescriptor(paramBs);
    if (valEd.codecId == 1) { // EXTERNAL
      ByteStream vp = new ByteStream(valEd.params);
      int blockId = vp.readITF8();
      ByteStream ext = extStreams.get(blockId);
      if (ext != null) return ext.readBytes(len);
    }
    // Fallback: read byte by byte using the decoder
    byte[] result = new byte[len];
    for (int i = 0; i < len; i++) {
      byte[] b = valDecoder.decode();
      if (b.length > 0) result[i] = b[0];
    }
    return result;
  }

  // ── Huffman decoder ────────────────────────────────────────────

  private static class HuffmanDecoder {
    private final int[] symbols;
    private final int[] bitLens;
    private final int[] codes; // canonical codes

    HuffmanDecoder(int[] symbols, int[] bitLens) {
      this.symbols = symbols;
      this.bitLens = bitLens;
      this.codes = buildCanonicalCodes(symbols, bitLens);
    }

    int decode(BitStream bits) throws IOException {
      int code = 0;
      for (int len = 1; len <= 32; len++) {
        code = (code << 1) | bits.readBit();
        for (int i = 0; i < symbols.length; i++) {
          if (bitLens[i] == len && codes[i] == code) {
            return symbols[i];
          }
        }
      }
      throw new IOException("Huffman decode failed");
    }

    private static int[] buildCanonicalCodes(int[] symbols, int[] bitLens) {
      int n = symbols.length;
      int[] codes = new int[n];
      // Sort by bit length, then by symbol value (for canonical ordering)
      Integer[] indices = new Integer[n];
      for (int i = 0; i < n; i++) indices[i] = i;
      java.util.Arrays.sort(indices, (a, b) -> {
        int cmp = Integer.compare(bitLens[a], bitLens[b]);
        return cmp != 0 ? cmp : Integer.compare(symbols[a], symbols[b]);
      });
      int code = 0;
      int prevLen = bitLens[indices[0]];
      codes[indices[0]] = 0;
      for (int i = 1; i < n; i++) {
        code++;
        int shift = bitLens[indices[i]] - prevLen;
        code <<= shift;
        prevLen = bitLens[indices[i]];
        codes[indices[i]] = code;
      }
      return codes;
    }
  }

  // ── Variable-length coding helpers ─────────────────────────────

  private static int decodeSubexp(BitStream bits, int k) throws IOException {
    int n = 0;
    while (bits.readBit() == 1) n++;
    if (n == 0) return bits.readBits(k);
    return (1 << (n + k - 1)) + bits.readBits(n + k - 1);
  }

  private static int decodeGolombRice(BitStream bits, int log2m) throws IOException {
    int q = 0;
    while (bits.readBit() == 1) q++;
    int r = bits.readBits(log2m);
    return (q << log2m) | r;
  }

  private static int decodeGamma(BitStream bits) throws IOException {
    int n = 0;
    while (bits.readBit() == 0) n++;
    return (1 << n) + (n > 0 ? bits.readBits(n) : 0) - 1;
  }

  // ── ByteStream: sequential byte-level reader ───────────────────

  static class ByteStream {
    private final byte[] data;
    private int pos;

    ByteStream(byte[] data) { this.data = data; this.pos = 0; }

    int remaining() { return data.length - pos; }

    int readByte() {
      return pos < data.length ? data[pos++] & 0xFF : 0;
    }

    byte[] readBytes(int n) {
      int actual = Math.min(n, data.length - pos);
      byte[] result = new byte[actual];
      System.arraycopy(data, pos, result, 0, actual);
      pos += actual;
      return result;
    }

    void skip(int n) { pos = Math.min(pos + n, data.length); }

    int readInt32LE() {
      int b0 = readByte(), b1 = readByte(), b2 = readByte(), b3 = readByte();
      return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    int readITF8() {
      int b = readByte();
      if ((b & 0x80) == 0) return b;
      if ((b & 0xC0) == 0x80) return ((b & 0x3F) << 8) | readByte();
      if ((b & 0xE0) == 0xC0) return ((b & 0x1F) << 16) | (readByte() << 8) | readByte();
      if ((b & 0xF0) == 0xE0) return ((b & 0x0F) << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
      return ((b & 0x0F) << 28) | (readByte() << 20) | (readByte() << 12) | (readByte() << 4) | (readByte() & 0x0F);
    }

    long readLTF8() {
      int b = readByte();
      if ((b & 0x80) == 0) return b;
      if ((b & 0xC0) == 0x80) return ((long)(b & 0x3F) << 8) | readByte();
      if ((b & 0xE0) == 0xC0) return ((long)(b & 0x1F) << 16) | (readByte() << 8) | readByte();
      if ((b & 0xF0) == 0xE0) return ((long)(b & 0x0F) << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
      if ((b & 0xF8) == 0xF0) return ((long)(b & 0x07) << 32) | ((long)readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
      if ((b & 0xFC) == 0xF8) return ((long)(b & 0x03) << 40) | ((long)readByte() << 32) | ((long)readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
      if ((b & 0xFE) == 0xFC) return ((long)(b & 0x01) << 48) | ((long)readByte() << 40) | ((long)readByte() << 32) | ((long)readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
      return ((long)readByte() << 48) | ((long)readByte() << 40) | ((long)readByte() << 32) | ((long)readByte() << 24) | ((long)readByte() << 16) | ((long)readByte() << 8) | readByte();
    }

    byte[] readUntil(int stopByte) {
      int start = pos;
      while (pos < data.length && (data[pos] & 0xFF) != stopByte) pos++;
      byte[] result = new byte[pos - start];
      System.arraycopy(data, start, result, 0, result.length);
      if (pos < data.length) pos++; // skip stop byte
      return result;
    }
  }

  // ── BitStream: bit-level reader (MSB first) ────────────────────

  private static class BitStream {
    private final byte[] data;
    private int bytePos;
    private int bitPos; // 7 = MSB, 0 = LSB

    BitStream(byte[] data) { this.data = data; this.bytePos = 0; this.bitPos = 7; }

    int readBit() throws IOException {
      if (bytePos >= data.length) return 0;
      int bit = (data[bytePos] >> bitPos) & 1;
      bitPos--;
      if (bitPos < 0) { bitPos = 7; bytePos++; }
      return bit;
    }

    int readBits(int n) throws IOException {
      int value = 0;
      for (int i = 0; i < n; i++) {
        value = (value << 1) | readBit();
      }
      return value;
    }
  }

  // ── ITF8 reading from RandomAccessFile ─────────────────────────

  private int readITF8FromRAF() throws IOException {
    int b = raf.read();
    if ((b & 0x80) == 0) return b;
    if ((b & 0xC0) == 0x80) return ((b & 0x3F) << 8) | raf.read();
    if ((b & 0xE0) == 0xC0) return ((b & 0x1F) << 16) | (raf.read() << 8) | raf.read();
    if ((b & 0xF0) == 0xE0) return ((b & 0x0F) << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    return ((b & 0x0F) << 28) | (raf.read() << 20) | (raf.read() << 12) | (raf.read() << 4) | (raf.read() & 0x0F);
  }

  private long readLTF8FromRAF() throws IOException {
    int b = raf.read();
    if ((b & 0x80) == 0) return b;
    if ((b & 0xC0) == 0x80) return ((long)(b & 0x3F) << 8) | raf.read();
    if ((b & 0xE0) == 0xC0) return ((long)(b & 0x1F) << 16) | (raf.read() << 8) | raf.read();
    if ((b & 0xF0) == 0xE0) return ((long)(b & 0x0F) << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    if ((b & 0xF8) == 0xF0) return ((long)(b & 0x07) << 32) | ((long)raf.read() << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    if ((b & 0xFC) == 0xF8) return ((long)(b & 0x03) << 40) | ((long)raf.read() << 32) | ((long)raf.read() << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    if ((b & 0xFE) == 0xFC) return ((long)(b & 0x01) << 48) | ((long)raf.read() << 40) | ((long)raf.read() << 32) | ((long)raf.read() << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    return ((long)raf.read() << 48) | ((long)raf.read() << 40) | ((long)raf.read() << 32) | ((long)raf.read() << 24) | ((long)raf.read() << 16) | ((long)raf.read() << 8) | raf.read();
  }

  // ── Utility ────────────────────────────────────────────────────

  private Integer resolveRefId(String chrom) {
    Integer refId = refNameToId.get(chrom);
    if (refId == null) refId = refNameToId.get("chr" + chrom);
    if (refId == null && chrom.startsWith("chr")) refId = refNameToId.get(chrom.substring(3));
    return refId;
  }

  private static String parseSampleName(String headerText, Path path) {
    for (String line : headerText.split("\n")) {
      if (line.startsWith("@RG")) {
        for (String field : line.split("\t")) {
          if (field.startsWith("SM:")) return field.substring(3).trim();
        }
      }
    }
    String name = path.getFileName().toString();
    if (name.endsWith(".cram")) name = name.substring(0, name.length() - 5);
    return name;
  }

  private static int readInt32LE(byte[] data, int offset) {
    return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
         | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
  }

  private static long readUInt32LE(byte[] data, int offset) {
    return ((long)(data[offset] & 0xFF)) | ((long)(data[offset + 1] & 0xFF) << 8)
         | ((long)(data[offset + 2] & 0xFF) << 16) | ((long)(data[offset + 3] & 0xFF) << 24);
  }
}
