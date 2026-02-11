package org.baseplayer.reads.bam;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Custom BAM file reader.
 * Opens a BAM file and its .bai index, reads the header for reference info,
 * and queries reads overlapping a genomic region.
 */
public class BAMFileReader implements Closeable {

  private final BGZFInputStream bgzf;
  private final BAIIndex index;
  private final String[] refNames;
  private final int[] refLengths;
  private final Map<String, Integer> refNameToId;
  private final String sampleName;
  private final Path bamPath;

  public BAMFileReader(Path bamPath) throws IOException {
    this.bamPath = bamPath;
    this.bgzf = new BGZFInputStream(bamPath);
    
    // Read BAM header
    bgzf.seek(0);
    
    // Magic: "BAM\1"
    byte[] magic = new byte[4];
    bgzf.readFully(magic);
    if (magic[0] != 'B' || magic[1] != 'A' || magic[2] != 'M' || magic[3] != 1) {
      throw new IOException("Not a valid BAM file: " + bamPath);
    }

    // Header text
    int headerLen = bgzf.readInt();
    byte[] headerText = new byte[headerLen];
    bgzf.readFully(headerText);
    
    // Parse sample name from header @RG SM: tag
    sampleName = parseSampleName(new String(headerText), bamPath);

    // Reference sequences
    int nRef = bgzf.readInt();
    refNames = new String[nRef];
    refLengths = new int[nRef];
    refNameToId = new HashMap<>();

    for (int i = 0; i < nRef; i++) {
      int nameLen = bgzf.readInt();
      byte[] name = new byte[nameLen];
      bgzf.readFully(name);
      // Remove null terminator
      refNames[i] = new String(name, 0, nameLen - 1);
      refLengths[i] = bgzf.readInt();
      refNameToId.put(refNames[i], i);
    }

    // Load BAI index — try .bam.bai first, then .bai
    Path baiPath = Path.of(bamPath.toString() + ".bai");
    if (!Files.exists(baiPath)) {
      // Try replacing .bam with .bai
      String bamStr = bamPath.toString();
      if (bamStr.endsWith(".bam")) {
        baiPath = Path.of(bamStr.substring(0, bamStr.length() - 4) + ".bai");
      }
    }
    if (!Files.exists(baiPath)) {
      throw new IOException("BAI index not found for: " + bamPath);
    }
    this.index = new BAIIndex(baiPath);
  }

  /**
   * Extract sample name from BAM header text (from @RG SM: tag).
   * Falls back to filename.
   */
  private static String parseSampleName(String headerText, Path bamPath) {
    for (String line : headerText.split("\n")) {
      if (line.startsWith("@RG")) {
        String[] fields = line.split("\t");
        for (String field : fields) {
          if (field.startsWith("SM:")) {
            return field.substring(3).trim();
          }
        }
      }
    }
    // Fallback: use filename without extension
    String name = bamPath.getFileName().toString();
    if (name.endsWith(".bam")) name = name.substring(0, name.length() - 4);
    return name;
  }

  /**
   * Query reads overlapping [start, end) on the given chromosome.
   * Coordinates are 0-based half-open.
   */
  public List<BAMRecord> query(String chrom, int start, int end) throws IOException {
    // Try with and without "chr" prefix
    Integer refId = refNameToId.get(chrom);
    if (refId == null) refId = refNameToId.get("chr" + chrom);
    if (refId == null && chrom.startsWith("chr")) refId = refNameToId.get(chrom.substring(3));
    if (refId == null) return new ArrayList<>();

    List<BAIIndex.Chunk> chunks = index.getChunks(refId, start, end);
    List<BAMRecord> records = new ArrayList<>();
    Set<Long> seenOffsets = new HashSet<>();

    // Synchronize on bgzf to prevent concurrent RandomAccessFile access
    synchronized (bgzf) {
      for (BAIIndex.Chunk chunk : chunks) {
        bgzf.seek(chunk.start);
        
        while (bgzf.getVirtualOffset() < chunk.end) {
          long recordOffset = bgzf.getVirtualOffset();
          BAMRecord record = readRecord();
          if (record == null) break;
          
          // Deduplicate: skip if we already saw a record at this offset
          if (!seenOffsets.add(recordOffset)) continue;
          
          // Skip unmapped, secondary, supplementary reads
          if (record.isUnmapped() || record.isSecondary() || record.isSupplementary()) continue;
          
          // Check if read is on the right reference
          if (record.refID != refId) break;
          
          // Past query region — skip rest of this chunk
          if (record.pos >= end) break;
          
          // Before query region — skip
          if (record.end <= start) continue;
          
          records.add(record);
        }
      }
    }

    return records;
  }

  /**
   * Stream reads overlapping [start, end) on the given chromosome.
   * Each valid read is passed to the consumer as it is decoded — no intermediate list.
   * The consumer returns true to continue, false to stop.
   */
  public void queryStreaming(String chrom, int start, int end,
                             Predicate<BAMRecord> consumer) throws IOException {
    Integer refId = refNameToId.get(chrom);
    if (refId == null) refId = refNameToId.get("chr" + chrom);
    if (refId == null && chrom.startsWith("chr")) refId = refNameToId.get(chrom.substring(3));
    if (refId == null) return;

    List<BAIIndex.Chunk> chunks = index.getChunks(refId, start, end);
    Set<Long> seenOffsets = new HashSet<>();

    synchronized (bgzf) {
      outer:
      for (BAIIndex.Chunk chunk : chunks) {
        bgzf.seek(chunk.start);
        while (bgzf.getVirtualOffset() < chunk.end) {
          long recordOffset = bgzf.getVirtualOffset();
          BAMRecord record = readRecord();
          if (record == null) break;

          if (!seenOffsets.add(recordOffset)) continue;
          if (record.isUnmapped() || record.isSecondary() || record.isSupplementary()) continue;
          
          // Different chromosome - skip rest of this chunk
          if (record.refID != refId) break;
          
          // Past query end - skip rest of this chunk but check next chunks
          if (record.pos >= end) break;
          
          // Before query start - skip this read
          if (record.end <= start) continue;
          
          if (!consumer.test(record)) break outer;
        }
      }
    }
  }

  /**
   * Read a single BAM record from the current position.
   */
  private BAMRecord readRecord() throws IOException {
    int blockSize;
    try {
      blockSize = bgzf.readInt();
    } catch (IOException e) {
      return null; // EOF
    }
    if (blockSize <= 0) return null;

    BAMRecord rec = new BAMRecord();
    
    // Core fixed-length fields (32 bytes)
    rec.refID = bgzf.readInt();
    rec.pos = bgzf.readInt();
    
    // bin_mq_nl: bin << 16 | mapq << 8 | l_read_name
    long binMqNl = bgzf.readUInt();
    int lReadName = (int) (binMqNl & 0xFF);
    rec.mapq = (int) ((binMqNl >> 8) & 0xFF);
    
    // flag_nc: flag << 16 | n_cigar_op
    long flagNc = bgzf.readUInt();
    int nCigarOp = (int) (flagNc & 0xFFFF);
    rec.flag = (int) ((flagNc >> 16) & 0xFFFF);
    
    rec.readLength = bgzf.readInt();
    
    // Skip mate info: next_refID (4) + next_pos (4) + tlen (4)
    bgzf.skip(12);
    
    // Read name
    byte[] nameBytes = new byte[lReadName];
    bgzf.readFully(nameBytes);
    rec.readName = new String(nameBytes, 0, lReadName - 1); // remove null terminator
    
    // CIGAR
    rec.cigarOps = new int[nCigarOp];
    for (int i = 0; i < nCigarOp; i++) {
      rec.cigarOps[i] = bgzf.readInt();
    }
    rec.computeEnd();
    
    // Skip remaining variable-length data: seq + qual + tags
    int variableBytes = blockSize - 32 - lReadName - (nCigarOp * 4);
    if (variableBytes > 0) {
      bgzf.skip(variableBytes);
    }
    
    return rec;
  }

  public String getSampleName() { return sampleName; }
  public String[] getRefNames() { return refNames; }
  public int[] getRefLengths() { return refLengths; }
  public Path getPath() { return bamPath; }

  @Override
  public void close() throws IOException {
    bgzf.close();
  }
}
