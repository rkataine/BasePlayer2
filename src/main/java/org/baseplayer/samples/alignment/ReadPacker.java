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
      
      List<Integer> rowEnds = new ArrayList<>();
      for (BAMRecord record : rgReads) {
        boolean placed = false;
        for (int r = 0; r < rowEnds.size(); r++) {
          if (record.pos >= rowEnds.get(r) + gap) {
            record.row = currentStartRow + r;
            rowEnds.set(r, record.end);
            placed = true;
            break;
          }
        }
        if (!placed) {
          record.row = currentStartRow + rowEnds.size();
          rowEnds.add(record.end);
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
    List<Integer> rowEnds = new ArrayList<>();
    for (BAMRecord record : hp1Reads) {
      boolean placed = false;
      for (int r = 0; r < rowEnds.size(); r++) {
        if (record.pos >= rowEnds.get(r) + gap) {
          record.row = r;
          rowEnds.set(r, record.end);
          placed = true;
          break;
        }
      }
      if (!placed) {
        record.row = rowEnds.size();
        rowEnds.add(record.end);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }
    
    // HP2 starts on fresh rows after HP1
    int hp2Start = rowEnds.isEmpty() ? 0 : rowEnds.size();
    result.hp2StartRow = hp2Start;
    
    List<Integer> hp2RowEnds = new ArrayList<>();
    for (BAMRecord record : hp2Reads) {
      boolean placed = false;
      for (int r = 0; r < hp2RowEnds.size(); r++) {
        if (record.pos >= hp2RowEnds.get(r) + gap) {
          record.row = hp2Start + r;
          hp2RowEnds.set(r, record.end);
          placed = true;
          break;
        }
      }
      if (!placed) {
        record.row = hp2Start + hp2RowEnds.size();
        hp2RowEnds.add(record.end);
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
    
    List<Integer> rowEnds = new ArrayList<>();
    for (BAMRecord record : reads) {
      boolean placed = false;
      for (int r = 0; r < rowEnds.size(); r++) {
        if (record.pos >= rowEnds.get(r) + gap) {
          record.row = r;
          rowEnds.set(r, record.end);
          placed = true;
          break;
        }
      }
      if (!placed) {
        record.row = rowEnds.size();
        rowEnds.add(record.end);
      }
      if (record.row > result.maxRow) {
        result.maxRow = record.row;
      }
    }
  }
}
