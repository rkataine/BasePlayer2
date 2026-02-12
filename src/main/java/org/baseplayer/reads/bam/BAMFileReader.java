package org.baseplayer.reads.bam;

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

import org.baseplayer.SharedModel;

/**
 * Custom BAM file reader.
 * Opens a BAM file and its .bai index, reads the header for reference info,
 * and queries reads overlapping a genomic region.
 */
public class BAMFileReader implements AlignmentReader {

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

    // For records without MD tag, compute mismatches by comparing SEQ against reference
    resolveSeqMismatches(records, chrom, start, end);

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
          
          // Resolve SEQ-based mismatches before passing to consumer
          if (record.seq != null && record.mismatches == null) {
            resolveSeqMismatchesSingle(record, chrom);
          }

          if (!consumer.test(record)) break outer;
        }
      }
    }
  }

  // BAM 4-bit base encoding: =ACMGRSVTWYHKDBN → index 0-15
  private static final char[] BAM_BASE = {'=','A','C','M','G','R','S','V','T','W','Y','H','K','D','B','N'};

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
    rec.pos = bgzf.readInt() + 1; // BAM POS is 0-based; convert to 1-based
    
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
    
    // Variable-length data: seq + qual + tags
    int fixedAndName = 32 + lReadName + (nCigarOp * 4);
    int variableBytes = blockSize - fixedAndName;

    // Read SEQ: ceil(readLength/2) bytes, 4-bit encoded
    int seqBytes = (rec.readLength + 1) / 2;
    char[] seq = null;
    if (seqBytes > 0 && variableBytes >= seqBytes) {
      byte[] seqData = new byte[seqBytes];
      bgzf.readFully(seqData);
      seq = decodeSeq(seqData, rec.readLength);
      variableBytes -= seqBytes;
    }

    // Skip QUAL (readLength bytes)
    int qualBytes = rec.readLength;
    if (qualBytes > 0 && variableBytes >= qualBytes) {
      bgzf.skip(qualBytes);
      variableBytes -= qualBytes;
    }

    // Parse tags to find MD:Z
    String mdTag = null;
    if (variableBytes > 0) {
      byte[] tagData = new byte[variableBytes];
      bgzf.readFully(tagData);
      mdTag = findMDTag(tagData);
    }

    // Build mismatches from SEQ + MD tag, or keep seq for reference-based fallback
    if (seq != null && mdTag != null) {
      rec.mismatches = parseMDMismatches(mdTag, seq, rec.pos, rec.cigarOps);
    } else if (seq != null) {
      // No MD tag — keep seq for later reference-based mismatch computation
      rec.seq = seq;
    }
    
    return rec;
  }

  /**
   * Decode BAM 4-bit encoded sequence into char array.
   */
  private static char[] decodeSeq(byte[] data, int readLen) {
    char[] seq = new char[readLen];
    for (int i = 0; i < readLen; i++) {
      int code = (i % 2 == 0)
          ? (data[i / 2] >> 4) & 0xF
          : data[i / 2] & 0xF;
      seq[i] = BAM_BASE[code];
    }
    return seq;
  }

  /**
   * Find the MD:Z tag in BAM auxiliary data.
   * Tags are: tag[0], tag[1], type, value...
   */
  private static String findMDTag(byte[] data) {
    int pos = 0;
    while (pos + 2 < data.length) {
      char t1 = (char) data[pos++];
      char t2 = (char) data[pos++];
      char type = (char) data[pos++];
      if (t1 == 'M' && t2 == 'D' && type == 'Z') {
        // Read null-terminated string
        int start = pos;
        while (pos < data.length && data[pos] != 0) pos++;
        return new String(data, start, pos - start);
      }
      // Skip value based on type
      pos = skipTagValue(data, pos, type);
      if (pos < 0) break; // parse error
    }
    return null;
  }

  /**
   * Skip a BAM tag value based on its type code.
   * Returns new position, or -1 on error.
   */
  private static int skipTagValue(byte[] data, int pos, char type) {
    switch (type) {
      case 'A': case 'c': case 'C': return pos + 1;
      case 's': case 'S': return pos + 2;
      case 'i': case 'I': case 'f': return pos + 4;
      case 'd': return pos + 8;
      case 'Z': case 'H':
        while (pos < data.length && data[pos] != 0) pos++;
        return pos + 1; // skip null terminator
      case 'B':
        if (pos + 4 >= data.length) return -1;
        char elemType = (char) data[pos++];
        int count = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8)
                  | ((data[pos+2] & 0xFF) << 16) | ((data[pos+3] & 0xFF) << 24);
        pos += 4;
        int elemSize = switch (elemType) {
          case 'c', 'C' -> 1;
          case 's', 'S' -> 2;
          case 'i', 'I', 'f' -> 4;
          case 'd' -> 8;
          default -> 0;
        };
        return pos + count * elemSize;
      default: return -1;
    }
  }

  /**
   * Parse MD tag + read sequence to produce mismatch positions.
   * MD format: numbers (matching), letters (reference base = mismatch),
   * ^letters (deletion from reference). We need to walk the CIGAR
   * to map read positions to reference positions.
   *
   * Returns packed int array [pos0, base0, pos1, base1, ...] or null.
   */
  private static int[] parseMDMismatches(String md, char[] seq, int alignStart, int[] cigarOps) {
    if (md.isEmpty() || seq.length == 0) return null;

    // Build read-to-ref mapping: for each read position, what is the ref offset?
    // We walk CIGAR to know which read positions align to which ref positions.
    // We only care about M/=/X (alignment match) positions for MD parsing.

    // First, build a list of aligned (read, ref) position pairs
    int readPos = 0;
    int refPos = alignStart; // 1-based

    java.util.List<int[]> mismatches = new java.util.ArrayList<>();

    // Walk MD string using CIGAR-aware read position tracking
    // MD operates on the aligned portion (M/=/X ops), skipping insertions and soft clips
    // Build an array mapping "alignment position" → read position
    int alignLen = 0;
    for (int op : cigarOps) {
      int opLen = op >>> 4;
      int opCode = op & 0xF;
      if (opCode == BAMRecord.CIGAR_M || opCode == BAMRecord.CIGAR_EQ || opCode == BAMRecord.CIGAR_X) {
        alignLen += opLen;
      }
    }

    // alignPos[i] = read position for the i-th aligned base
    // refOff[i]   = reference offset (from alignStart) for the i-th aligned base
    int[] alignToRead = new int[alignLen];
    int[] alignToRef = new int[alignLen];
    readPos = 0;
    refPos = 0;
    int ai = 0;
    for (int op : cigarOps) {
      int opLen = op >>> 4;
      int opCode = op & 0xF;
      switch (opCode) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X -> {
          for (int j = 0; j < opLen; j++) {
            if (ai < alignLen) {
              alignToRead[ai] = readPos;
              alignToRef[ai] = refPos;
              ai++;
            }
            readPos++;
            refPos++;
          }
        }
        case BAMRecord.CIGAR_I, BAMRecord.CIGAR_S -> readPos += opLen;
        case BAMRecord.CIGAR_D, BAMRecord.CIGAR_N -> refPos += opLen;
        case BAMRecord.CIGAR_H, BAMRecord.CIGAR_P -> {} // no movement
      }
    }

    // Now walk the MD string
    int mdIdx = 0;
    int alignIdx = 0; // position in the aligned bases
    while (mdIdx < md.length() && alignIdx < alignLen) {
      char c = md.charAt(mdIdx);
      if (Character.isDigit(c)) {
        // Parse number = matching bases
        int num = 0;
        while (mdIdx < md.length() && Character.isDigit(md.charAt(mdIdx))) {
          num = num * 10 + (md.charAt(mdIdx) - '0');
          mdIdx++;
        }
        alignIdx += num;
      } else if (c == '^') {
        // Deletion from reference: ^ACG...
        mdIdx++; // skip '^'
        while (mdIdx < md.length() && Character.isLetter(md.charAt(mdIdx))) {
          mdIdx++;
          // Deletions don't consume read bases, but they are already
          // accounted for by CIGAR D ops, so we don't advance alignIdx
        }
      } else if (Character.isLetter(c)) {
        // Mismatch: the letter is the reference base; read base comes from SEQ
        if (alignIdx < alignLen) {
          int rp = alignToRead[alignIdx];
          int genomicPos = alignStart + alignToRef[alignIdx];
          if (rp >= 0 && rp < seq.length) {
            mismatches.add(new int[]{genomicPos, seq[rp]});
          }
          alignIdx++;
        }
        mdIdx++;
      } else {
        mdIdx++; // skip unknown
      }
    }

    if (mismatches.isEmpty()) return null;
    int[] result = new int[mismatches.size() * 2];
    for (int i = 0; i < mismatches.size(); i++) {
      result[i * 2] = mismatches.get(i)[0];
      result[i * 2 + 1] = mismatches.get(i)[1];
    }
    return result;
  }

  @Override public String getSampleName() { return sampleName; }
  @Override public String[] getRefNames() { return refNames; }
  @Override public int[] getRefLengths() { return refLengths; }
  @Override public Path getPath() { return bamPath; }

  /**
   * Read a BAM record minimally — only refID, pos, flag, and readLength.
   * Skips all variable-length data (name, CIGAR, SEQ, QUAL, tags) for maximum speed.
   * Returns null on EOF. Sets refID, pos, flag on the shared scratch record.
   */
  private int[] readRecordMinimal() throws IOException {
    int blockSize;
    try {
      blockSize = bgzf.readInt();
    } catch (IOException e) {
      return null;
    }
    if (blockSize <= 0) return null;

    int refID = bgzf.readInt();
    int pos = bgzf.readInt() + 1; // 0-based -> 1-based
    long binMqNl = bgzf.readUInt();
    long flagNc = bgzf.readUInt();
    int flag = (int) ((flagNc >> 16) & 0xFFFF);
    int readLength = bgzf.readInt();

    // Skip remaining fixed fields (mate info: 12 bytes) + all variable data
    int remaining = blockSize - 32 + 12; // 32 = core fixed, but we read 20 so far (4+4+4+4+4), skip rest
    // We read: refID(4) + pos(4) + binMqNl(4) + flagNc(4) + readLength(4) = 20 bytes of the 32 core
    // Remaining in block: blockSize - 20
    bgzf.skip(blockSize - 20);

    return new int[] { refID, pos, flag, readLength };
  }

  /**
   * Sampled coverage in a single pass. Collects BAI chunks for all sample windows,
   * merges them, and streams through once counting reads per window.
   */
  @Override
  public void querySampledCounts(String chrom, int[] positions, int window, int[] counts, Runnable onChunkDone) throws IOException {
    Integer refId = refNameToId.get(chrom);
    if (refId == null) refId = refNameToId.get("chr" + chrom);
    if (refId == null && chrom.startsWith("chr")) refId = refNameToId.get(chrom.substring(3));
    if (refId == null) return;

    // Collect chunks for all windows and merge
    List<BAIIndex.Chunk> allChunks = new ArrayList<>();
    for (int i = 0; i < positions.length; i++) {
      allChunks.addAll(index.getChunks(refId, positions[i], positions[i] + window));
    }
    if (allChunks.isEmpty()) return;

    // Sort and merge
    allChunks.sort((a, b) -> Long.compare(a.start, b.start));
    List<BAIIndex.Chunk> merged = new ArrayList<>();
    BAIIndex.Chunk cur = allChunks.get(0);
    for (int i = 1; i < allChunks.size(); i++) {
      BAIIndex.Chunk next = allChunks.get(i);
      if (next.start <= cur.end) {
        cur = new BAIIndex.Chunk(cur.start, Math.max(cur.end, next.end));
      } else {
        merged.add(cur);
        cur = next;
      }
    }
    merged.add(cur);

    // Single pass through merged chunks
    Set<Long> seenOffsets = new HashSet<>();
    synchronized (bgzf) {
      for (BAIIndex.Chunk chunk : merged) {
        if (Thread.currentThread().isInterrupted()) return;
        bgzf.seek(chunk.start);
        while (bgzf.getVirtualOffset() < chunk.end) {
          if (Thread.currentThread().isInterrupted()) return;
          long offset = bgzf.getVirtualOffset();
          int[] rec = readRecordMinimal();
          if (rec == null) break;

          if (!seenOffsets.add(offset)) continue;
          int recRefID = rec[0], recPos = rec[1], recFlag = rec[2], recReadLen = rec[3];

          // Skip unmapped/secondary/supplementary
          if ((recFlag & 0x4) != 0 || (recFlag & 0x100) != 0 || (recFlag & 0x800) != 0) continue;
          if (recRefID != refId) break;

          // Bin this read into matching sample windows
          for (int i = 0; i < positions.length; i++) {
            if (recPos < positions[i] + window && recPos + recReadLen > positions[i]) {
              counts[i]++;
            }
          }
        }
        // Notify after each chunk so UI can update progressively
        if (onChunkDone != null) onChunkDone.run();
      }
    }
  }

  /**
   * Batch-resolve mismatches by comparing SEQ against reference for records without MD tag.
   * Fetches the reference once for the query region, then compares each read.
   */
  private static void resolveSeqMismatches(List<BAMRecord> records, String chrom, int start, int end) {
    if (SharedModel.referenceGenome == null) return;

    // Find the span of records that need reference comparison
    boolean needRef = false;
    int minPos = Integer.MAX_VALUE;
    int maxEnd = 0;
    for (BAMRecord rec : records) {
      if (rec.seq != null && rec.mismatches == null) {
        needRef = true;
        if (rec.pos < minPos) minPos = rec.pos;
        if (rec.end > maxEnd) maxEnd = rec.end;
      }
    }
    if (!needRef) return;

    // Fetch reference bases (getBases uses 1-based coords; pos is now 1-based)
    String refBases = SharedModel.referenceGenome.getBases(chrom, minPos, maxEnd);
    if (refBases.isEmpty()) return;

    for (BAMRecord rec : records) {
      if (rec.seq != null && rec.mismatches == null) {
        rec.mismatches = computeSeqMismatches(rec.seq, rec.cigarOps, rec.pos, refBases, minPos);
        rec.seq = null; // free memory
      }
    }
  }

  /**
   * Resolve mismatches for a single record by comparing SEQ against reference.
   * Used in the streaming path where we can't batch.
   */
  private static void resolveSeqMismatchesSingle(BAMRecord rec, String chrom) {
    if (SharedModel.referenceGenome == null) return;
    String refBases = SharedModel.referenceGenome.getBases(chrom, rec.pos, rec.end);
    if (!refBases.isEmpty()) {
      rec.mismatches = computeSeqMismatches(rec.seq, rec.cigarOps, rec.pos, refBases, rec.pos);
    }
    rec.seq = null;
  }

  /**
   * Compare read sequence against reference using CIGAR alignment.
   * Walks CIGAR ops: for M/EQ/X, compares each read base against the reference base.
   * Any mismatch is recorded as [genomicPos, readBase].
   *
   * @param seq         decoded read sequence
   * @param cigarOps    CIGAR operations (each is opLen<<4 | opCode)
   * @param alignStart  1-based alignment start position
   * @param refBases    reference bases (uppercase)
   * @param refStart    1-based genomic position of refBases[0]
   */
  static int[] computeSeqMismatches(char[] seq, int[] cigarOps, int alignStart,
                                    String refBases, int refStart) {
    if (seq == null || cigarOps == null || refBases.isEmpty()) return null;

    java.util.List<int[]> mismatches = new java.util.ArrayList<>();
    int readPos = 0;
    int refPos = alignStart;

    for (int op : cigarOps) {
      int opLen = op >>> 4;
      int opCode = op & 0xF;
      switch (opCode) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X -> {
          for (int j = 0; j < opLen; j++) {
            int ri = refPos - refStart;
            if (readPos < seq.length && ri >= 0 && ri < refBases.length()) {
              char readBase = Character.toUpperCase(seq[readPos]);
              char refBase = Character.toUpperCase(refBases.charAt(ri));
              if (readBase != refBase && readBase != 'N' && refBase != 'N') {
                mismatches.add(new int[]{refPos, readBase});
              }
            }
            readPos++;
            refPos++;
          }
        }
        case BAMRecord.CIGAR_I -> readPos += opLen;
        case BAMRecord.CIGAR_S -> readPos += opLen;
        case BAMRecord.CIGAR_D, BAMRecord.CIGAR_N -> refPos += opLen;
        case BAMRecord.CIGAR_H, BAMRecord.CIGAR_P -> {} // no movement
      }
    }

    if (mismatches.isEmpty()) return null;
    int[] result = new int[mismatches.size() * 2];
    for (int i = 0; i < mismatches.size(); i++) {
      result[i * 2] = mismatches.get(i)[0];
      result[i * 2 + 1] = mismatches.get(i)[1];
    }
    return result;
  }

  @Override
  public void close() throws IOException {
    bgzf.close();
  }
}
