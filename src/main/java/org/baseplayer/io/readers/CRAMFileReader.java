package org.baseplayer.io.readers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.services.ServiceRegistry;

/**
 * Custom CRAM file reader.
 * Parses CRAM 3.0 files: file definition, SAM header, container/slice/block
 * structure, compression header, and record decoding using rANS 4x8, gzip, and
 * raw block compression. No external library (htsjdk) is used.
 */
public class CRAMFileReader implements AlignmentReader {

  private final Path cramPath;
  private final RandomAccessFile raf;
  private final CRAIIndex index;
  private final String sampleName;
  private final String[] refNames;
  private final int[] refLengths;
  private final Map<String, Integer> refNameToId;
  private final CRAMDecoder decoder;

  // ── public constructor ──────────────────────────────────────────

  public CRAMFileReader(Path cramPath) throws IOException {
    this.cramPath = cramPath;
    this.raf = new RandomAccessFile(cramPath.toFile(), "r");
    ReferenceGenomeService referenceGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();

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
    int containerLength = readContainerLength();
    byte[] containerData = new byte[containerLength];
    raf.readFully(containerData);

    // The first block in the header container is the FILE_HEADER block
    CRAMDecoder.ByteStream bs = new CRAMDecoder.ByteStream(containerData);
    CRAMDecoder.BlockHeader bh = readBlockHeader(bs);
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
    this.refNames = names.toArray(String[]::new);
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
    
    // 4) Create decoder
    this.decoder = new CRAMDecoder(referenceGenomeService);
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
        int containerLength = readContainerLength();
        byte[] containerData = new byte[containerLength];
        raf.readFully(containerData);

        List<BAMRecord> containerRecords = decoder.decodeContainer(containerData, chrom);
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
        int containerLength = readContainerLength();
        byte[] containerData = new byte[containerLength];
        raf.readFully(containerData);

        // Decode with full span of our sample windows
        List<BAMRecord> recs = decoder.decodeContainer(containerData, chrom);
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

  /** Reads the container header, skips all metadata, and returns the container data length. */
  private int readContainerLength() throws IOException {
    byte[] buf4 = new byte[4];
    raf.readFully(buf4);
    int length = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    // Skip: refSeqId, startPos, alignSpan, nRecords
    readITF8FromRAF(); readITF8FromRAF(); readITF8FromRAF(); readITF8FromRAF();
    // Skip: recordCounter, bases
    readLTF8FromRAF(); readLTF8FromRAF();
    // Skip: nBlocks
    readITF8FromRAF();
    // Skip landmarks array
    int numLandmarks = readITF8FromRAF();
    for (int i = 0; i < numLandmarks; i++) readITF8FromRAF();
    // Skip CRC32
    raf.skipBytes(4);
    return length;
  }

  // Helper methods for reading SAM header block
  private static CRAMDecoder.BlockHeader readBlockHeader(CRAMDecoder.ByteStream bs) {
    CRAMDecoder.BlockHeader bh = new CRAMDecoder.BlockHeader();
    bh.method = bs.readByte();
    bh.contentType = bs.readByte();
    bh.contentId = bs.readITF8();
    bh.compressedSize = bs.readITF8();
    bh.uncompressedSize = bs.readITF8();
    return bh;
  }

  private static byte[] readBlockData(CRAMDecoder.ByteStream bs, CRAMDecoder.BlockHeader bh) throws IOException {
    byte[] compressed = bs.readBytes(bh.compressedSize);
    // CRC32 (4 bytes)
    bs.skip(4);
    return decompress(compressed, bh.method, bh.uncompressedSize);
  }

  private static byte[] decompress(byte[] data, int method, int uncompressedSize) throws IOException {
    return switch (method) {
      case 0 -> data; // raw
      case 1 -> decompressGzip(data, uncompressedSize);
      case 4 -> RANSDecompressor.decompress(data);
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

  // ── ITF8 reading from RandomAccessFile ─────────────────────────

  private int readITF8FromRAF() throws IOException {
    int b = raf.read();
    if ((b & 0x80) == 0) return b;
    if ((b & 0xC0) == 0x80) return ((b & 0x3F) << 8) | raf.read();
    if ((b & 0xE0) == 0xC0) return ((b & 0x1F) << 16) | (raf.read() << 8) | raf.read();
    if ((b & 0xF0) == 0xE0) return ((b & 0x0F) << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    return ((b & 0x0F) << 28) | (raf.read() << 20) | (raf.read() << 12) | (raf.read() << 4) | (raf.read() & 0x0F);
  }

  /**
   * Read LTF8-encoded long from RandomAccessFile.
   * LTF8 is the long variant of ITF8, supporting 64-bit values.
   */
  private long readLTF8FromRAF() throws IOException {
    int b = raf.read();
    if ((b & 0x80) == 0) return b;
    if ((b & 0xC0) == 0x80) return ((long)(b & 0x3F) << 8) | raf.read();
    if ((b & 0xE0) == 0xC0) return ((long)(b & 0x1F) << 16) | ((long)raf.read() << 8) | raf.read();
    if ((b & 0xF0) == 0xE0) return ((long)(b & 0x0F) << 24) | ((long)raf.read() << 16) | ((long)raf.read() << 8) | raf.read();
    if ((b & 0xF8) == 0xF0) return ((long)(b & 0x07) << 32) | ((long)raf.read() << 24) | ((long)raf.read() << 16) | ((long)raf.read() << 8) | raf.read();
    if ((b & 0xFC) == 0xF8) return ((long)(b & 0x03) << 40) | ((long)raf.read() << 32) | ((long)raf.read() << 24) | ((long)raf.read() << 16) | ((long)raf.read() << 8) | raf.read();
    if ((b & 0xFE) == 0xFC) return ((long)(b & 0x01) << 48) | ((long)raf.read() << 40) | ((long)raf.read() << 32) | ((long)raf.read() << 24) | ((long)raf.read() << 16) | ((long)raf.read() << 8) | raf.read();
    if ((b & 0xFF) == 0xFE) return ((long)raf.read() << 48) | ((long)raf.read() << 40) | ((long)raf.read() << 32) | ((long)raf.read() << 24) | ((long)raf.read() << 16) | ((long)raf.read() << 8) | raf.read();
    return ((long)raf.read() << 56) | ((long)raf.read() << 48) | ((long)raf.read() << 40) | ((long)raf.read() << 32) | ((long)raf.read() << 24) | ((long)raf.read() << 16) | ((long)raf.read() << 8) | raf.read();
  }


  // ── Utility ────────────────────────────────────────────────────

  private Integer resolveRefId(String chrom) {
    Integer refId = refNameToId.get(chrom);
    if (refId == null) refId = refNameToId.get("chr" + chrom);
    if (refId == null && chrom.startsWith("chr")) refId = refNameToId.get(chrom.substring(3));
    // Handle M <-> MT mapping for mitochondrial chromosome
    if (refId == null && chrom.equals("MT")) refId = refNameToId.get("M");
    if (refId == null && chrom.equals("M")) refId = refNameToId.get("MT");
    if (refId == null && chrom.equals("chrMT")) refId = refNameToId.get("chrM");
    if (refId == null && chrom.equals("chrM")) refId = refNameToId.get("chrMT");
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
}
