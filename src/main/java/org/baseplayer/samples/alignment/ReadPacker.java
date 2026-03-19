package org.baseplayer.samples.alignment;

import java.util.ArrayList;
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
   * @param detectedReadGroups List of detected read group names
   * @return PackingResult with row assignments and metadata
   */
  public static PackingResult packReads(List<BAMRecord> reads, DrawStack stack, 
                                       boolean splitByReadGroup, boolean haplotypeDetected,
                                       List<String> detectedReadGroups) {
    if (reads.isEmpty()) {
      return new PackingResult();
    }
    
    PackingResult result = new PackingResult();
    int gap = Math.max(1, (int)(MIN_PIXEL_GAP * stack.scale));
    
    if (splitByReadGroup && detectedReadGroups.size() > 1) {
      packByReadGroup(reads, gap, detectedReadGroups, result);
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
    return segments.toArray(new int[0][]);
  }

  /**
   * Find the first row where all exon segments fit without overlapping
   * existing segments (respecting the gap).
   * Returns row index, or -1 if no row fits.
   */
  private static int findFittingRow(List<List<int[]>> rowSegments, int[][] exons, int gap) {
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
  private static boolean fitsInRow(List<int[]> existing, int[][] exons, int gap) {
    // Fast path: check against the last existing segment's end
    // (covers 99% of cases since reads are sorted by pos)
    if (!existing.isEmpty()) {
      int[] last = existing.get(existing.isEmpty() ? 0 : existing.size() - 1);
      if (exons[0][0] >= last[1] + gap) {
        return true; // all exons start after the last existing segment
      }
    }
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
  private static void addSegments(List<int[]> rowSegs, int[][] exons) {
    for (int[] exon : exons) {
      rowSegs.add(exon);
    }
  }
}
