package org.baseplayer.alignment;

/**
 * Lightweight BAM alignment record.
 * Only stores the fields needed for display: position, end, flags, mapping quality.
 */
public class BAMRecord {

  // CIGAR operation codes
  public static final int CIGAR_M  = 0; // alignment match (can be seq match or mismatch)
  public static final int CIGAR_I  = 1; // insertion to reference
  public static final int CIGAR_D  = 2; // deletion from reference
  public static final int CIGAR_N  = 3; // skipped region from reference
  public static final int CIGAR_S  = 4; // soft clipping
  public static final int CIGAR_H  = 5; // hard clipping
  public static final int CIGAR_P  = 6; // padding
  public static final int CIGAR_EQ = 7; // sequence match
  public static final int CIGAR_X  = 8; // sequence mismatch

  // SAM flags
  public static final int FLAG_PAIRED         = 0x1;
  public static final int FLAG_PROPER_PAIR    = 0x2;
  public static final int FLAG_UNMAPPED       = 0x4;
  public static final int FLAG_REVERSE        = 0x10;
  public static final int FLAG_SECONDARY      = 0x100;
  public static final int FLAG_DUPLICATE      = 0x400;
  public static final int FLAG_SUPPLEMENTARY  = 0x800;

  public int refID;
  public int pos;           // 0-based leftmost position
  public int end;           // 0-based rightmost position (exclusive), computed from cigar
  public int mapq;
  public int flag;
  public int readLength;    // number of bases in the read
  public int[] cigarOps;    // raw cigar: each is (opLen << 4 | op)
  public String readName;
  
  // Mate information for pair analysis
  public int mateRefID = -1;     // mate's reference sequence ID
  public int matePos = -1;       // mate's position (0-based)
  public int insertSize = 0;     // signed observed insert size
  
  // Data type tags
  public boolean hasMethylTag = false;  // MM/ML/XM tags detected (methylation data)
  public String methylString = null;    // XM:Z tag value (bisulfite methylation call string)
  public int haplotype = 0;             // HP:i tag value (0=unphased, 1=HP1, 2=HP2)
  public int phaseSet = 0;              // PS:i tag value (phase set identifier)
  public String readGroup = null;       // RG:Z tag value (read group identifier)

  /**
   * Mismatches relative to the reference.
   * Packed as triplets: [refPos0, readBase0, refBase0, refPos1, readBase1, refBase1, ...]
   * where refPos is the 1-based genomic position, readBase is the ASCII code of the read base,
   * and refBase is the ASCII code of the reference base (0 if unknown).
   */
  public int[] mismatches;

  /** Temporary read sequence for reference-based mismatch computation when no MD tag. Nulled after use. */
  public char[] seq;

  // Display: row assigned during packing
  public int row = -1;

  /**
   * Compute end position from cigar operations.
   * Only M, D, N, EQ, X consume reference bases.
   */
  public void computeEnd() {
    int refLen = 0;
    if (cigarOps != null) {
      for (int cigarOp : cigarOps) {
        int op = cigarOp & 0xF;
        int len = cigarOp >>> 4;
        switch (op) {
          case CIGAR_M, CIGAR_D, CIGAR_N, CIGAR_EQ, CIGAR_X -> refLen += len;
          default -> {
              }
        }
      }
    }
    end = pos + refLen;
  }

  public boolean isReverseStrand()  { return (flag & FLAG_REVERSE) != 0; }
  public boolean isPaired()         { return (flag & FLAG_PAIRED) != 0; }
  public boolean isProperPair()     { return (flag & FLAG_PROPER_PAIR) != 0; }
  public boolean isUnmapped()       { return (flag & FLAG_UNMAPPED) != 0; }
  public boolean isDuplicate()      { return (flag & FLAG_DUPLICATE) != 0; }
  public boolean isSecondary()      { return (flag & FLAG_SECONDARY) != 0; }
  public boolean isSupplementary()  { return (flag & FLAG_SUPPLEMENTARY) != 0; }
  
  /**
   * Detect discordant read type for color coding.
   * @return 0=normal, 1=inter-chromosomal, 2=deletion, 3=inversion, 4=duplication
   */
  public int getDiscordantType() {
    // Not paired or proper pair = normal
    if (!isPaired() || isProperPair()) return 0;
    
    // Inter-chromosomal (different chromosomes)
    if (mateRefID != refID && mateRefID >= 0) return 1;
    
    // Same chromosome discordance
    if (mateRefID == refID && matePos >= 0) {
      int absInsert = Math.abs(insertSize);
      
      // Deletion (larger insert size, typical threshold ~1kb)
      if (absInsert > 1000) return 2;
      
      // Duplication/tandem repeat (smaller insert or wrong orientation)
      if (absInsert < 100) return 4;
      
      // Inversion (both reads same orientation)
      boolean mateReverse = (flag & 0x20) != 0; // mate reverse strand flag
      if (isReverseStrand() == mateReverse) return 3;
    }
    
    return 0; // normal
  }
  
  /**
   * Get the stacking rank for grouping reads in rows.
   * Lower rank = packed first = displayed at the top.
   * <ul>
   *   <li>0 = normal proper-pair / unpaired / single-end reads</li>
   *   <li>10 = inter-chromosomal discordant</li>
   *   <li>11 = deletion (large insert)</li>
   *   <li>12 = inversion (same orientation)</li>
   *   <li>13 = duplication (small insert / wrong orientation)</li>
   * </ul>
   * Future ranks: e.g. 20+ for reads matching a specific mismatch pattern.
   */
  public int getStackRank() {
    int dt = getDiscordantType();
    if (dt == 0) return 0;       // normal
    return 9 + dt;               // 10-13 for discordant types
  }
  
  /**
   * Debug helper: print BAM record fields and mismatches.
   */
  public void debugPrint() {
    System.out.println("BAMRecord: " + readName);
    System.out.println("  pos=" + pos + " end=" + end + " mapq=" + mapq + " flag=" + flag);
    System.out.println("  reverse=" + isReverseStrand() + " paired=" + isPaired());
    System.out.println("  hasMethylTag=" + hasMethylTag + " haplotype=" + haplotype + " phaseSet=" + phaseSet + " readGroup=" + readGroup);
    
    if (mismatches != null && mismatches.length > 0) {
      System.out.print("  mismatches (" + (mismatches.length / 3) + "): ");
      for (int i = 0; i + 2 < mismatches.length; i += 3) {
        int refPos = mismatches[i];
        char readBase = (char) mismatches[i + 1];
        char refBase = (mismatches[i + 2] > 0) ? (char) mismatches[i + 2] : '?';
        System.out.print(refPos + ":" + refBase + "→" + readBase + " ");
      }
      System.out.println();
    } else {
      System.out.println("  mismatches: none");
    }
  }
}
