package org.baseplayer.samples.alignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.draw.DrawStack;

/**
 * Handles row packing algorithm for BAM reads.
 * Assigns each read a display row to minimize vertical space while
 * preventing overlaps at the current zoom level.
 *
 * <p>CIGAR-aware: for spliced RNA-seq reads (containing CIGAR N operations),
 * only exonic segments are considered when checking row occupancy, allowing
 * other reads to be packed into the intronic gaps.
 */
public class ReadPacker {
  
  /**
   * Pack reads in genomic order while preferring to place paired mates on the same row
   * when they do not overlap existing segments.
   */
  private static void packByMateSideBySide(List<BAMRecord> reads, int gap, PackingResult result) {
    reads.sort(Comparator.comparingInt(r -> r.pos));

    List<List<int[]>> rowSegments = new ArrayList<>();
    List<List<String>> rowOwners = new ArrayList<>();
    // readName → {row, maxEnd}: tracks where the first part of a split/mate pair landed
    Map<String, int[]> pendingByName = new LinkedHashMap<>();

    for (BAMRecord record : reads) {
      int[][] exons = getExonSegments(record);
      int r = -1;
      int bridgeFrom = -1; // if ≥ 0, add a bridge segment [bridgeFrom, record.pos] before exons

      int[] pendingPlacement = findPendingPlacementAllowOwnOverlap(
          record, pendingByName, rowSegments, rowOwners, exons, gap);
      if (pendingPlacement != null) {
        r = pendingPlacement[0];
        bridgeFrom = calcBridgeFrom(record, pendingPlacement[1]);
      }

      if (r < 0) {
        r = findFittingRow(rowSegments, exons, gap);
      }

      record.row = placeRecordInRow(rowSegments, rowOwners, record, r, bridgeFrom, exons);

      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }

      updatePendingPlacement(pendingByName, record);
    }
  }
  /** Minimum pixel gap between reads for packing (ensures consistent visual spacing at all zoom levels) */
  private static final int MIN_PIXEL_GAP = 3;
  
  /**
   * Result of packing operation containing row assignments and metadata.
   */
  public static class PackingResult {
    public int maxRow = 0;
    public int discordantStartRow = -1;
    public int normalStartRow = -1;
    public int hp2StartRow = -1;
    public int strandSplitStartRow = -1;
    public int discordantSplitStartRow = -1;
    public Map<String, Integer> readGroupStartRows = new LinkedHashMap<>();
  }
  
  /**
   * Pack reads into rows for display, considering current zoom level.
   * Supports multiple modes: normal, haplotype-split, read-group-split.
   * 
   * @param reads List of reads to pack (modified in place)
   * @param stack DrawStack providing scale information
   * @param splitByReadGroup Whether to separate reads by read group
   * @param haplotypeDetected Whether haplotype data is present
  * @param stackMatesSideBySide Whether to prefer placing mates on the same row
   * @param detectedReadGroups List of detected read group names
   * @return PackingResult with row assignments and metadata
   */
  public static PackingResult packReads(List<BAMRecord> reads, DrawStack stack, 
                                       boolean splitByReadGroup, boolean haplotypeDetected,
                          boolean splitByStrand, boolean splitByDiscordant,
                          boolean stackMatesSideBySide,
                                       List<String> detectedReadGroups) {
    if (reads.isEmpty()) {
      return new PackingResult();
    }
    
    PackingResult result = new PackingResult();
    int gap = Math.max(1, (int)(MIN_PIXEL_GAP * stack.scale));
    
    if (splitByReadGroup && detectedReadGroups.size() > 1) {
      packByReadGroup(reads, gap, detectedReadGroups, result);
    } else if (splitByDiscordant) {
      packByDiscordant(reads, gap, result);
    } else if (splitByStrand) {
      packByStrand(reads, gap, result);
    } else if (stackMatesSideBySide) {
      packByMateSideBySide(reads, gap, result);
    } else if (haplotypeDetected) {
      packByHaplotype(reads, gap, result);
    } else {
      packNormal(reads, gap, result);
    }
    
    return result;
  }
  
  /**
   * Pack reads separated by read group.
   * Each read group gets its own section with a separator row.
   */
  private static void packByReadGroup(List<BAMRecord> reads, int gap, 
                                     List<String> detectedReadGroups, PackingResult result) {
    // Group reads by read group
    Map<String, List<BAMRecord>> rgGroups = new LinkedHashMap<>();
    for (String rg : detectedReadGroups) {
      rgGroups.put(rg, new ArrayList<>());
    }
    rgGroups.put("__none__", new ArrayList<>()); // for reads without RG
    
    for (BAMRecord record : reads) {
      String rg = record.readGroup != null ? record.readGroup : "__none__";
      List<BAMRecord> group = rgGroups.get(rg);
      if (group == null) {
        // RG not seen in initial detection — put in first group
        group = rgGroups.values().iterator().next();
      }
      group.add(record);
    }
    
    // Pack each read group separately
    int currentStartRow = 0;
    
    for (Map.Entry<String, List<BAMRecord>> entry : rgGroups.entrySet()) {
      List<BAMRecord> rgReads = entry.getValue();
      if (rgReads.isEmpty()) continue;
      
      result.readGroupStartRows.put(entry.getKey(), currentStartRow);
      rgReads.sort(Comparator.comparingInt(r -> r.pos));
      
      List<List<int[]>> rowSegments = new ArrayList<>();
      for (BAMRecord record : rgReads) {
        int[][] exons = getExonSegments(record);
        int r = findFittingRow(rowSegments, exons, gap);
        if (r >= 0) {
          record.row = currentStartRow + r;
          addSegments(rowSegments.get(r), exons);
        } else {
          record.row = currentStartRow + rowSegments.size();
          List<int[]> segs = new ArrayList<>();
          addSegments(segs, exons);
          rowSegments.add(segs);
        }
        if (record.row > result.maxRow) {
          result.maxRow = record.row;
        }
      }
      
      currentStartRow = result.maxRow + 2; // leave 1 empty row as separator
    }
  }
  
  /**
   * Pack reads separated by strand.
   * Forward-strand on top (rendered upward), reverse-strand on bottom.
   */
  private static void packByStrand(List<BAMRecord> reads, int gap, PackingResult result) {
    List<BAMRecord> fwdReads = new ArrayList<>();
    List<BAMRecord> revReads = new ArrayList<>();

    for (BAMRecord record : reads) {
      if (record.isReverseStrand()) {
        revReads.add(record);
      } else {
        fwdReads.add(record);
      }
    }

    fwdReads.sort(Comparator.comparingInt(r -> r.pos));
    revReads.sort(Comparator.comparingInt(r -> r.pos));

    // Pack forward reads: rows 0, 1, 2, ...
    List<List<int[]>> rowSegments = new ArrayList<>();
    for (BAMRecord record : fwdReads) {
      int[][] exons = getExonSegments(record);
      int r = findFittingRow(rowSegments, exons, gap);
      if (r >= 0) {
        record.row = r;
        addSegments(rowSegments.get(r), exons);
      } else {
        record.row = rowSegments.size();
        List<int[]> segs = new ArrayList<>();
        addSegments(segs, exons);
        rowSegments.add(segs);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }

    // Reverse strand starts on fresh rows after forward
    int revStart = rowSegments.isEmpty() ? 0 : rowSegments.size();
    result.strandSplitStartRow = revStart;

    List<List<int[]>> revRowSegments = new ArrayList<>();
    for (BAMRecord record : revReads) {
      int[][] exons = getExonSegments(record);
      int r = findFittingRow(revRowSegments, exons, gap);
      if (r >= 0) {
        record.row = revStart + r;
        addSegments(revRowSegments.get(r), exons);
      } else {
        record.row = revStart + revRowSegments.size();
        List<int[]> segs = new ArrayList<>();
        addSegments(segs, exons);
        revRowSegments.add(segs);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }
  }

  /**
   * Pack reads separated by discordant/split status.
   * Discordant reads or reads with SA (supplementary) tag on top (rendered upward),
   * all other reads on bottom. Mirrors packByStrand exactly.
   */
  private static void packByDiscordant(List<BAMRecord> reads, int gap, PackingResult result) {
    List<BAMRecord> discReads = new ArrayList<>();
    List<BAMRecord> normReads = new ArrayList<>();

    for (BAMRecord record : reads) {
      if (record.getDiscordantType() > 0 || record.saTag != null) {
        discReads.add(record);
      } else {
        normReads.add(record);
      }
    }

    discReads.sort(Comparator.comparingInt(r -> r.pos));
    normReads.sort(Comparator.comparingInt(r -> r.pos));

    // Pack discordant/split reads: rows 0, 1, 2, ...
    List<List<int[]>> rowSegments = new ArrayList<>();
    for (BAMRecord record : discReads) {
      int[][] exons = getExonSegments(record);
      int r = findFittingRow(rowSegments, exons, gap);
      if (r >= 0) {
        record.row = r;
        addSegments(rowSegments.get(r), exons);
      } else {
        record.row = rowSegments.size();
        List<int[]> segs = new ArrayList<>();
        addSegments(segs, exons);
        rowSegments.add(segs);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }

    // Normal reads start on fresh rows after discordant/split
    int normStart = rowSegments.isEmpty() ? 0 : rowSegments.size();
    result.discordantSplitStartRow = normStart;

    List<List<int[]>> normRowSegments = new ArrayList<>();
    for (BAMRecord record : normReads) {
      int[][] exons = getExonSegments(record);
      int r = findFittingRow(normRowSegments, exons, gap);
      if (r >= 0) {
        record.row = normStart + r;
        addSegments(normRowSegments.get(r), exons);
      } else {
        record.row = normStart + normRowSegments.size();
        List<int[]> segs = new ArrayList<>();
        addSegments(segs, exons);
        normRowSegments.add(segs);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }
  }

  /**
   * Pack reads separated by haplotype.
   * HP1 on top (rendered upward), HP2+unphased on bottom.
   */
  private static void packByHaplotype(List<BAMRecord> reads, int gap, PackingResult result) {
    List<BAMRecord> hp1Reads = new ArrayList<>();
    List<BAMRecord> hp2Reads = new ArrayList<>();
    
    for (BAMRecord record : reads) {
      if (record.haplotype == 1) {
        hp1Reads.add(record);
      } else {
        hp2Reads.add(record); // HP2 + unphased
      }
    }
    
    hp1Reads.sort(Comparator.comparingInt(r -> r.pos));
    hp2Reads.sort(Comparator.comparingInt(r -> r.pos));
    
    // Pack HP1 reads: rows 0, 1, 2, ...
    List<List<int[]>> rowSegments = new ArrayList<>();
    for (BAMRecord record : hp1Reads) {
      int[][] exons = getExonSegments(record);
      int r = findFittingRow(rowSegments, exons, gap);
      if (r >= 0) {
        record.row = r;
        addSegments(rowSegments.get(r), exons);
      } else {
        record.row = rowSegments.size();
        List<int[]> segs = new ArrayList<>();
        addSegments(segs, exons);
        rowSegments.add(segs);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }
    
    // HP2 starts on fresh rows after HP1
    int hp2Start = rowSegments.isEmpty() ? 0 : rowSegments.size();
    result.hp2StartRow = hp2Start;
    
    List<List<int[]>> hp2RowSegments = new ArrayList<>();
    for (BAMRecord record : hp2Reads) {
      int[][] exons = getExonSegments(record);
      int r = findFittingRow(hp2RowSegments, exons, gap);
      if (r >= 0) {
        record.row = hp2Start + r;
        addSegments(hp2RowSegments.get(r), exons);
      } else {
        record.row = hp2Start + hp2RowSegments.size();
        List<int[]> segs = new ArrayList<>();
        addSegments(segs, exons);
        hp2RowSegments.add(segs);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }
  }
  
  /**
   * Pack all reads together by position (normal mode).
   */
  private static void packNormal(List<BAMRecord> reads, int gap, PackingResult result) {
    reads.sort(Comparator.comparingInt(r -> r.pos));
    
    List<List<int[]>> rowSegments = new ArrayList<>();
    for (BAMRecord record : reads) {
      int[][] exons = getExonSegments(record);
      int r = findFittingRow(rowSegments, exons, gap);
      if (r >= 0) {
        record.row = r;
        addSegments(rowSegments.get(r), exons);
      } else {
        record.row = rowSegments.size();
        List<int[]> segs = new ArrayList<>();
        addSegments(segs, exons);
        rowSegments.add(segs);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }
  }

  // ── CIGAR-aware helpers ───────────────────────────────────────────────

  /**
   * Extract exon (reference-consuming, non-intron) segments from a read's CIGAR.
   * For reads without CIGAR_N, returns a single [pos, end] segment.
   */
  static int[][] getExonSegments(BAMRecord record) {
    if (record.cigarOps == null) {
      return new int[][] {{ record.pos, record.end }};
    }
    // Quick check for any splice junctions
    boolean hasSplice = false;
    for (int cigarOp : record.cigarOps) {
      if ((cigarOp & 0xF) == BAMRecord.CIGAR_N) { hasSplice = true; break; }
    }
    if (!hasSplice) {
      return new int[][] {{ record.pos, record.end }};
    }
    // Walk CIGAR and collect exon segments
    List<int[]> segments = new ArrayList<>();
    int refPos = record.pos;
    int segStart = refPos;
    for (int cigarOp : record.cigarOps) {
      int op  = cigarOp & 0xF;
      int len = cigarOp >>> 4;
      switch (op) {
        case BAMRecord.CIGAR_M, BAMRecord.CIGAR_EQ, BAMRecord.CIGAR_X, BAMRecord.CIGAR_D -> {
          refPos += len;
        }
        case BAMRecord.CIGAR_N -> {
          // End current exon segment, start new one after intron
          if (refPos > segStart) {
            segments.add(new int[] { segStart, refPos });
          }
          refPos += len;
          segStart = refPos;
        }
        default -> {} // I, S, H, P: no reference consumption
      }
    }
    // Add final exon segment
    if (refPos > segStart) {
      segments.add(new int[] { segStart, refPos });
    }
    return segments.toArray(int[][]::new);
  }

  /**
   * Find the first row where all exon segments fit without overlapping
   * existing segments (respecting the gap).
   * Returns row index, or -1 if no row fits.
   */
  static int findFittingRow(List<List<int[]>> rowSegments, int[][] exons, int gap) {
    for (int r = 0; r < rowSegments.size(); r++) {
      if (fitsInRow(rowSegments.get(r), exons, gap)) {
        return r;
      }
    }
    return -1;
  }

  /**
   * Check if all exon segments fit in a row without overlapping existing segments.
   * Both lists are sorted by start position.
   */
  static boolean fitsInRow(List<int[]> existing, int[][] exons, int gap) {
    // Full check: verify no exon overlaps any existing segment
    for (int[] exon : exons) {
      for (int[] seg : existing) {
        if (exon[0] < seg[1] + gap && exon[1] + gap > seg[0]) {
          return false; // overlap
        }
      }
    }
    return true;
  }

  /**
   * Add exon segments to a row's segment list, maintaining sorted order.
   */
  static void addSegments(List<int[]> rowSegs, int[][] exons) {
    rowSegs.addAll(Arrays.asList(exons));
  }

  static void addSegmentOwners(List<String> owners, int count, String readName) {
    for (int i = 0; i < count; i++) {
      owners.add(readName);
    }
  }

  static boolean fitsInRowAllowOwnOverlap(List<int[]> existing, List<String> owners,
                                          int[][] exons, int gap, String readName) {
    for (int[] exon : exons) {
      for (int i = 0; i < existing.size(); i++) {
        int[] seg = existing.get(i);
        if (exon[0] < seg[1] + gap && exon[1] + gap > seg[0]) {
          String owner = i < owners.size() ? owners.get(i) : null;
          if (!readName.equals(owner)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  static int[] findPendingPlacementAllowOwnOverlap(BAMRecord record,
                                                    Map<String, int[]> pendingByName,
                                                    List<List<int[]>> rowSegments,
                                                    List<List<String>> rowOwners,
                                                    int[][] exons,
                                                    int gap) {
    if (record.readName == null) return null;
    int[] pending = pendingByName.get(record.readName);
    if (pending == null) return null;
    int pendingRow = pending[0];
    int pendingEnd = pending[1];
    if (pendingRow < 0 || pendingRow >= rowSegments.size() || pendingRow >= rowOwners.size()) {
      return null;
    }
    if (!fitsInRowAllowOwnOverlap(rowSegments.get(pendingRow), rowOwners.get(pendingRow), exons, gap, record.readName)) {
      return null;
    }
    return new int[]{ pendingRow, pendingEnd };
  }

  static int calcBridgeFrom(BAMRecord record, int pendingEnd) {
    // Bridge only for non-supplementary mates to avoid reserving huge genomic ranges.
    if (!record.isSupplementary() && record.pos > pendingEnd) {
      return pendingEnd;
    }
    return -1;
  }

  static void addBridgeSegment(List<int[]> rowSegs, List<String> owners, int bridgeFrom, BAMRecord record) {
    if (bridgeFrom >= 0 && record.pos > bridgeFrom) {
      rowSegs.add(new int[]{ bridgeFrom, record.pos });
      if (owners != null) owners.add(record.readName);
    }
  }

  static void addSegmentsWithOwner(List<int[]> rowSegs, List<String> owners,
                                   int[][] exons, String readName) {
    addSegments(rowSegs, exons);
    if (owners != null) {
      addSegmentOwners(owners, exons.length, readName);
    }
  }

  static void updatePendingPlacement(Map<String, int[]> pendingByName, BAMRecord record) {
    if (record.readName == null) return;
    int[] pending = pendingByName.get(record.readName);
    if (pending != null) {
      if (record.end > pending[1]) pending[1] = record.end;
      pending[0] = record.row;
    } else {
      pendingByName.put(record.readName, new int[]{ record.row, record.end });
    }
  }

  static int placeRecordInRow(List<List<int[]>> rowSegments,
                              List<List<String>> rowOwners,
                              BAMRecord record,
                              int targetRow,
                              int bridgeFrom,
                              int[][] exons) {
    if (targetRow >= 0 && targetRow < rowSegments.size() && targetRow < rowOwners.size()) {
      addBridgeSegment(rowSegments.get(targetRow), rowOwners.get(targetRow), bridgeFrom, record);
      addSegmentsWithOwner(rowSegments.get(targetRow), rowOwners.get(targetRow), exons, record.readName);
      return targetRow;
    }

    int newRow = rowSegments.size();
    List<int[]> segs = new ArrayList<>();
    List<String> owners = new ArrayList<>();
    addSegmentsWithOwner(segs, owners, exons, record.readName);
    rowSegments.add(segs);
    rowOwners.add(owners);
    return newRow;
  }
}
