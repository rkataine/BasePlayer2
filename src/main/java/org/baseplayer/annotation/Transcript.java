package org.baseplayer.annotation;

import java.util.List;

/**
 * Represents a transcript with its exons, CDS bounds, and MANE status.
 */
public record Transcript(
    String id,
    String name,
    long start,
    long end,
    String biotype,
    boolean isManeSelect,
    boolean isManeClinic,
    List<long[]> exons,
    long cdsStart,   // Start of coding sequence (0 if non-coding)
    long cdsEnd      // End of coding sequence (0 if non-coding)
) {
  /** Backward compatible constructor without CDS bounds */
  public Transcript(String id, String name, long start, long end, String biotype,
                    boolean isManeSelect, boolean isManeClinic, List<long[]> exons) {
    this(id, name, start, end, biotype, isManeSelect, isManeClinic, exons, 0, 0);
  }
  
  /** Check if this is any MANE transcript */
  public boolean isMane() {
    return isManeSelect || isManeClinic;
  }
  
  /** Check if this transcript has a coding sequence */
  public boolean hasCDS() {
    return cdsStart > 0 && cdsEnd > 0 && cdsStart <= cdsEnd;
  }
  
  /** Check if an exon region overlaps with the CDS */
  public boolean isExonCoding(long exonStart, long exonEnd) {
    if (!hasCDS()) return false;
    return exonStart <= cdsEnd && exonEnd >= cdsStart;
  }
  
  /** Get the CDS portion of an exon (or null if no overlap) */
  public long[] getExonCDS(long exonStart, long exonEnd) {
    if (!isExonCoding(exonStart, exonEnd)) return null;
    return new long[] {
      Math.max(exonStart, cdsStart),
      Math.min(exonEnd, cdsEnd)
    };
  }
}
