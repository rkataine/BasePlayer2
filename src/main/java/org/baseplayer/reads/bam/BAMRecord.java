package org.baseplayer.reads.bam;

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
          case CIGAR_M:
          case CIGAR_D:
          case CIGAR_N:
          case CIGAR_EQ:
          case CIGAR_X:
            refLen += len;
            break;
          default:
            break;
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
}
