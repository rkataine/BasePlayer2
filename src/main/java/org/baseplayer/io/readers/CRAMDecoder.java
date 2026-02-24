package org.baseplayer.io.readers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.genome.ReferenceGenomeService;

/**
 * Decodes CRAM 3.0 container data into BAM records.
 * Handles compression headers, slices, blocks, and record decoding with
 * substitution matrices, Huffman coding, and various data encodings.
 * 
 * Extracted from CRAMFileReader to separate container decoding from file I/O.
 */
public class CRAMDecoder {

  private final ReferenceGenomeService referenceGenomeService;

  public CRAMDecoder(ReferenceGenomeService referenceGenomeService) {
    this.referenceGenomeService = referenceGenomeService;
  }

  /**
   * Decode a CRAM container's byte data into BAM records.
   * @param data container data (after container header)
   * @param chrom chromosome name for reference lookup
   * @return list of decoded BAM records
   */
  public List<BAMRecord> decodeContainer(byte[] data, String chrom) throws IOException {
    ByteStream bs = new ByteStream(data);

    // First block is the compression header
    BlockHeader compHdr = readBlockHeader(bs);
    byte[] compHdrData = readBlockData(bs, compHdr);
    CompressionHeader ch = parseCompressionHeader(compHdrData);

    List<BAMRecord> allRecords = new ArrayList<>();

    // Remaining blocks are slices
    while (bs.remaining() > 0) {
      try {
        List<BAMRecord> sliceRecords = decodeSlice(bs, ch, chrom);
        allRecords.addAll(sliceRecords);
      } catch (IOException e) {
        System.err.println("CRAM slice decode error: " + e.getMessage());
        break;
      }
    }
    return allRecords;
  }

  // ── Block reading ──────────────────────────────────────────────

  static class BlockHeader {
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

  static class EncodingDescriptor {
    int codecId;
    byte[] params;
  }

  private static CompressionHeader parseCompressionHeader(byte[] data) throws IOException {
    ByteStream bs = new ByteStream(data);
    CompressionHeader ch = new CompressionHeader();

    // Preservation map — first ITF8 is byte count; second ITF8 inside is entry count
    ch.readNamesIncluded = true;
    ch.apDelta = true;
    ch.substitutionMatrix = new byte[5];
    {
      int pmSizeBytes = bs.readITF8();
      ByteStream pmBs = new ByteStream(bs.readBytes(pmSizeBytes));
      int pmCount = pmBs.readITF8(); // entry count
      for (int i = 0; i < pmCount; i++) {
        int k1 = pmBs.readByte();
        int k2 = pmBs.readByte();
        String key = "" + (char) k1 + (char) k2;
        switch (key) {
          case "RN" -> ch.readNamesIncluded = pmBs.readByte() != 0;
          case "AP" -> ch.apDelta = pmBs.readByte() != 0;
          case "RR" -> pmBs.readByte(); // reference required—skip
          case "SM" -> {
            for (int j = 0; j < 5; j++) ch.substitutionMatrix[j] = (byte) pmBs.readByte();
            ch.subLookup = buildSubstitutionLookup(ch.substitutionMatrix);
          }
          case "TD" -> {
            int tdSize = pmBs.readITF8();
            byte[] td = pmBs.readBytes(tdSize);
            ch.tagDictionary = parseTagDictionary(td);
          }
          default -> {
            // Unknown key — no safe way to skip, stop parsing
            break;
          }
        }
      }
    }

    // Data series encoding map — byte count + entry count
    {
      int dsSizeBytes = bs.readITF8();
      ByteStream dsBs = new ByteStream(bs.readBytes(dsSizeBytes));
      int dsCount = dsBs.readITF8(); // entry count
      for (int i = 0; i < dsCount; i++) {
        int c1 = dsBs.readByte();
        int c2 = dsBs.readByte();
        String key = "" + (char) c1 + (char) c2;
        EncodingDescriptor ed = readEncodingDescriptor(dsBs);
        ch.dataSeriesEncodings.put(key, ed);
      }
    }

    // Tag encoding map — byte count + entry count
    {
      int teSizeBytes = bs.readITF8();
      ByteStream teBs = new ByteStream(bs.readBytes(teSizeBytes));
      int teCount = teBs.readITF8(); // entry count
      for (int i = 0; i < teCount; i++) {
        int tagKey = teBs.readITF8();
        EncodingDescriptor ed = readEncodingDescriptor(teBs);
        ch.tagEncodings.put(tagKey, ed);
      }
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
      List<Character> remaining = new ArrayList<>(4);
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
    return entries.toArray(byte[][]::new);
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
                                      String chrom) throws IOException {
    // Slice header block
    BlockHeader sliceHdrBlock = readBlockHeader(containerBs);
    byte[] sliceHdrData = readBlockData(containerBs, sliceHdrBlock);
    ByteStream shBs = new ByteStream(sliceHdrData);

    int sliceRefSeqId = shBs.readITF8();
    int sliceAlignStart = shBs.readITF8(); // 1-based
    int sliceAlignSpan = shBs.readITF8();
    int sliceNumRecords = shBs.readITF8();
    shBs.readLTF8();  // record counter (CRAM 3.0 LTF8 field) — skip
    int sliceNumBlocks = shBs.readITF8();
    // Block content IDs: one ITF8 per block
    for (int b = 0; b < sliceNumBlocks; b++) shBs.readITF8();
    shBs.readITF8();  // embedded reference bases block content ID — skip
    shBs.skip(16);    // reference MD5

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
                         coreData, externalBlocks, multiRef, chrom);
  }

  // ── Record decoding ────────────────────────────────────────────

  private List<BAMRecord> decodeRecords(CompressionHeader ch, int numRecords,
      int sliceRefId, int sliceAlignStart, int sliceAlignSpan,
      byte[] coreData, Map<Integer, byte[]> externalBlocks,
      boolean multiRef, String chrom) throws IOException {

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
    if (chrom != null && referenceGenomeService.hasGenome() && sliceAlignStart > 0) {
      // sliceAlignStart is 1-based; fetch a wider region to cover all reads in this slice
      int fetchStart = sliceAlignStart; // 1-based for getBases
      int fetchEnd = sliceAlignStart + sliceAlignSpan + 1000; // 1-based, with margin
      refBases = referenceGenomeService.getBases(chrom, fetchStart, fetchEnd);
      refBasesStart = fetchStart; // 1-based, matches genomicPos coordinate system
    }

    for (int i = 0; i < numRecords; i++) {
      int bamFlags = bfDec.decode();
      int cramFlags = cfDec.decode();

      int recRefId = (multiRef && riDec != null) ? riDec.decode() : sliceRefId;
      int readLen = rlDec.decode();

      int ap = apDec.decode();
      int alignStart;
      if (ch.apDelta) {
        alignStart = prevAlignStart + ap;
        prevAlignStart = alignStart;
      } else {
        alignStart = ap;
      }
      if (rgDec != null) rgDec.decode(); // read group

      // Read name
      String readName = null;
      if (ch.readNamesIncluded && rnDec != null) {
        byte[] rn = rnDec.decode();
        if (rn != null) readName = new String(rn);
      }

      // Mate info
      boolean detached = (cramFlags & 0x02) != 0;
      boolean hasMateDownstream = (cramFlags & 0x04) != 0;
      int mateRefIDCRAM = -1;
      int matePosValue = -1;
      int templateSize = 0;
      if (detached) {
        if (mfDec != null) mfDec.decode(); // mate bit flags
        if (!ch.readNamesIncluded && rnDec != null) {
          byte[] rn = rnDec.decode();
          if (rn != null && readName == null) readName = new String(rn);
        }
        if (nsDec != null) mateRefIDCRAM = nsDec.decode(); // mate ref seq id
        if (npDec != null) matePosValue = npDec.decode(); // mate pos
        if (tsDec != null) templateSize = tsDec.decode(); // template size
      } else if (hasMateDownstream) {
        if (nfDec != null) nfDec.decode(); // distance to next fragment
      }

      // Tags
      boolean recHasMethylTag = false;
      int recHaplotype = 0;
      String recMethylString = null;
      if (tlDec != null) {
        int tlIdx = tlDec.decode();
        if (ch.tagDictionary != null && tlIdx >= 0 && tlIdx < ch.tagDictionary.length) {
          byte[] tagList = ch.tagDictionary[tlIdx];
          // Each tag is 3 bytes: tag[0], tag[1], type
          for (int t = 0; t + 2 < tagList.length; t += 3) {
            int tagKey = ((tagList[t] & 0xFF) << 16) | ((tagList[t + 1] & 0xFF) << 8) | (tagList[t + 2] & 0xFF);
            ByteArrayDecoder td = tagDecoders.get(tagKey);
            if (td != null) {
              byte[] tagValue = td.decode();
              // Detect methylation tags: MM, Mm, ML, Ml, XM
              char c1 = (char)(tagList[t] & 0xFF);
              char c2 = (char)(tagList[t + 1] & 0xFF);
              char tagType = (char)(tagList[t + 2] & 0xFF);
              if ((c1 == 'M' && (c2 == 'M' || c2 == 'm' || c2 == 'L' || c2 == 'l'))
                  || (c1 == 'X' && c2 == 'M')) {
                recHasMethylTag = true;
                // Extract XM:Z string for bisulfite detection
                if (c1 == 'X' && c2 == 'M' && tagType == 'Z' && tagValue != null) {
                  // String tags are null-terminated
                  int len = tagValue.length;
                  if (len > 0 && tagValue[len - 1] == 0) len--;
                  recMethylString = new String(tagValue, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
                }
              }
              // Detect HP:i haplotype tag
              if (c1 == 'H' && c2 == 'P' && tagValue != null && tagValue.length >= 4) {
                recHaplotype = (tagValue[0] & 0xFF) | ((tagValue[1] & 0xFF) << 8)
                    | ((tagValue[2] & 0xFF) << 16) | ((tagValue[3] & 0xFF) << 24);
              }
            }
          }
        }
      }

      // Read features → compute reference span, collect mismatches, and build CIGAR
      boolean unmappedSeq = (cramFlags & 0x08) != 0;
      int refSpan = readLen;
      List<int[]> mmList = null; // collected as [genomicPos, readBase, refBase]
      // CIGAR reconstruction: collect events as [readPos (1-based), cigarOp, length]
      List<int[]> cigarEvents = null;

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
              int genomicPos = alignStart + (featurePos - 1) + refOffset;
              int refBaseChar = 0;
              if (refBases != null) {
                int refIdx = genomicPos - refBasesStart;
                if (refIdx >= 0 && refIdx < refBases.length()) {
                  refBaseChar = Character.toUpperCase(refBases.charAt(refIdx));
                }
              }
              if (mmList == null) mmList = new ArrayList<>();
              mmList.add(new int[]{genomicPos, base, refBaseChar});
            }
            case 'X' -> { // substitution
              int bsCode = bsDec != null ? bsDec.decode() : 0;
              int genomicPos = alignStart + (featurePos - 1) + refOffset;
              char readBase = '?';
              int refBaseX = 0;
              if (ch.subLookup != null && refBases != null) {
                int refIdx = genomicPos - refBasesStart;
                if (refIdx >= 0 && refIdx < refBases.length()) {
                  char refBase = refBases.charAt(refIdx);
                  refBaseX = Character.toUpperCase(refBase);
                  int ri = switch (Character.toUpperCase(refBase)) {
                    case 'A' -> 0; case 'C' -> 1; case 'G' -> 2; case 'T' -> 3; default -> 4;
                  };
                  if (bsCode >= 0 && bsCode < 4) readBase = ch.subLookup[ri][bsCode];
                }
              }
              if (mmList == null) mmList = new ArrayList<>();
              mmList.add(new int[]{genomicPos, readBase, refBaseX});
            }
            case 'I' -> { // insertion
              byte[] ins = inDec != null ? inDec.decode() : null;
              if (ins != null) {
                refSpan -= ins.length; refOffset -= ins.length;
                if (cigarEvents == null) cigarEvents = new ArrayList<>();
                cigarEvents.add(new int[]{featurePos, BAMRecord.CIGAR_I, ins.length});
              }
            }
            case 'i' -> { // single base insertion
              if (baDec != null) baDec.decode();
              refSpan -= 1;
              refOffset -= 1;
              if (cigarEvents == null) cigarEvents = new ArrayList<>();
              cigarEvents.add(new int[]{featurePos, BAMRecord.CIGAR_I, 1});
            }
            case 'D' -> { // deletion
              int dl = dlDec != null ? dlDec.decode() : 0;
              refSpan += dl;
              refOffset += dl;
              if (dl > 0) {
                if (cigarEvents == null) cigarEvents = new ArrayList<>();
                cigarEvents.add(new int[]{featurePos, BAMRecord.CIGAR_D, dl});
              }
            }
            case 'N' -> { // ref skip (splice junction)
              int rs = rsDec != null ? rsDec.decode() : 0;
              refSpan += rs;
              refOffset += rs;
              if (rs > 0) {
                if (cigarEvents == null) cigarEvents = new ArrayList<>();
                cigarEvents.add(new int[]{featurePos, BAMRecord.CIGAR_N, rs});
              }
            }
            case 'S' -> { // soft clip
              byte[] sc = scDec != null ? scDec.decode() : null;
              if (sc != null) {
                refSpan -= sc.length; refOffset -= sc.length;
                if (cigarEvents == null) cigarEvents = new ArrayList<>();
                cigarEvents.add(new int[]{featurePos, BAMRecord.CIGAR_S, sc.length});
              }
            }
            case 'H' -> { // hard clip
              int hcLen = hcDec != null ? hcDec.decode() : 0;
              if (hcLen > 0) {
                if (cigarEvents == null) cigarEvents = new ArrayList<>();
                cigarEvents.add(new int[]{featurePos, BAMRecord.CIGAR_H, hcLen});
              }
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
              // Unknown feature code—skip
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
      
      // Build cigarOps from collected CIGAR events for splice junction rendering
      if (cigarEvents != null && !cigarEvents.isEmpty()) {
        // Sort by read position, then build CIGAR ops
        cigarEvents.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> cigarList = new ArrayList<>();
        int lastEnd = 1; // 1-based read position
        for (int[] evt : cigarEvents) {
          int readPos = evt[0];
          int op = evt[1];
          int len = evt[2];
          // Add M op for the gap before this event
          if (readPos > lastEnd) {
            cigarList.add(new int[]{(readPos - lastEnd) << 4});
          }
          cigarList.add(new int[]{len << 4 | op});
            // Advance lastEnd based on op type
            lastEnd = switch (op) {
                case BAMRecord.CIGAR_I, BAMRecord.CIGAR_S -> readPos + len;
                case BAMRecord.CIGAR_D, BAMRecord.CIGAR_N -> readPos;
                case BAMRecord.CIGAR_H -> readPos;
                default -> readPos + len;
            }; // consumes read bases
            // doesn't consume read bases
            // hard clip doesn't consume read bases
        }
        // Trailing M for remaining read bases
        if (lastEnd <= readLen) {
          cigarList.add(new int[]{(readLen - lastEnd + 1) << 4});
        }
        rec.cigarOps = new int[cigarList.size()];
        for (int c = 0; c < cigarList.size(); c++) rec.cigarOps[c] = cigarList.get(c)[0];
      } else if (!unmappedSeq) {
        // Simple M-only alignment
        rec.cigarOps = new int[]{readLen << 4};
      }
      
      // Set mate information
      rec.mateRefID = mateRefIDCRAM;
      rec.matePos = matePosValue;
      rec.insertSize = templateSize;
      rec.hasMethylTag = recHasMethylTag;
      rec.methylString = recMethylString;
      rec.haplotype = recHaplotype;

      // Pack mismatches: [pos0, readBase0, refBase0, pos1, readBase1, refBase1, ...]
      if (mmList != null && !mmList.isEmpty()) {
        rec.mismatches = new int[mmList.size() * 3];
        for (int m = 0; m < mmList.size(); m++) {
          rec.mismatches[m * 3] = mmList.get(m)[0];
          rec.mismatches[m * 3 + 1] = mmList.get(m)[1];
          rec.mismatches[m * 3 + 2] = mmList.get(m).length > 2 ? mmList.get(m)[2] : 0;
        }
      }

      records.add(rec);
    }
    return records;
  }

  // ── Decoder builders ───────────────────────────────────────────

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
      case 1 -> { // EXTERNAL—byte data series reads single bytes
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
        ByteArrayDecoder valDecoder = buildByteArrayDecoderInner(readEncodingDescriptor(paramBs), extStreams);
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

  private ByteArrayDecoder buildByteArrayDecoderInner(EncodingDescriptor ed,
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

    long readLTF8() {
      int b = readByte();
      if ((b & 0x80) == 0) return b;
      if ((b & 0xC0) == 0x80) return ((long)(b & 0x3F) << 8) | readByte();
      if ((b & 0xE0) == 0xC0) return ((long)(b & 0x1F) << 16) | ((long)readByte() << 8) | readByte();
      if ((b & 0xF0) == 0xE0) return ((long)(b & 0x0F) << 24) | ((long)readByte() << 16) | ((long)readByte() << 8) | readByte();
      if ((b & 0xF8) == 0xF0) return ((long)(b & 0x07) << 32) | ((long)readByte() << 24) | ((long)readByte() << 16) | ((long)readByte() << 8) | readByte();
      if ((b & 0xFC) == 0xF8) return ((long)(b & 0x03) << 40) | ((long)readByte() << 32) | ((long)readByte() << 24) | ((long)readByte() << 16) | ((long)readByte() << 8) | readByte();
      if ((b & 0xFE) == 0xFC) return ((long)(b & 0x01) << 48) | ((long)readByte() << 40) | ((long)readByte() << 32) | ((long)readByte() << 24) | ((long)readByte() << 16) | ((long)readByte() << 8) | readByte();
      if ((b & 0xFF) == 0xFE) return ((long)readByte() << 48) | ((long)readByte() << 40) | ((long)readByte() << 32) | ((long)readByte() << 24) | ((long)readByte() << 16) | ((long)readByte() << 8) | readByte();
      return ((long)readByte() << 56) | ((long)readByte() << 48) | ((long)readByte() << 40) | ((long)readByte() << 32) | ((long)readByte() << 24) | ((long)readByte() << 16) | ((long)readByte() << 8) | readByte();
    }

    byte[] readBytes(int n) {
      int actual = Math.min(n, data.length - pos);
      byte[] result = new byte[actual];
      System.arraycopy(data, pos, result, 0, actual);
      pos += actual;
      return result;
    }

    void skip(int n) { pos = Math.min(pos + n, data.length); }

    int readITF8() {
      int b = readByte();
      if ((b & 0x80) == 0) return b;
      if ((b & 0xC0) == 0x80) return ((b & 0x3F) << 8) | readByte();
      if ((b & 0xE0) == 0xC0) return ((b & 0x1F) << 16) | (readByte() << 8) | readByte();
      if ((b & 0xF0) == 0xE0) return ((b & 0x0F) << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
      return ((b & 0x0F) << 28) | (readByte() << 20) | (readByte() << 12) | (readByte() << 4) | (readByte() & 0x0F);
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
}
