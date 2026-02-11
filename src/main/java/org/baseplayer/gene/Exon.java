package org.baseplayer.gene;

/**
 * Represents an exon with its genomic coordinates and coding information.
 * An exon can be fully coding (CDS), fully non-coding (UTR), or split.
 */
public record Exon(
    String id,
    long start,
    long end,
    int rank,           // Exon number (1-based, accounting for strand)
    long cdsStart,      // CDS start within this exon (0 if no CDS)
    long cdsEnd,        // CDS end within this exon (0 if no CDS)
    int phase           // Reading frame offset (0, 1, or 2) for CDS start, -1 if non-coding
) {
  
  /**
   * Create a non-coding exon (UTR or non-coding gene).
   */
  public static Exon nonCoding(String id, long start, long end, int rank) {
    return new Exon(id, start, end, rank, 0, 0, -1);
  }
  
  /**
   * Create a fully coding exon.
   */
  public static Exon coding(String id, long start, long end, int rank, int phase) {
    return new Exon(id, start, end, rank, start, end, phase);
  }
  
  /**
   * Check if this exon has any coding sequence.
   */
  public boolean hasCDS() {
    return cdsStart > 0 && cdsEnd > 0 && cdsStart <= cdsEnd;
  }
  
  /**
   * Check if this exon is fully coding (no UTR regions).
   */
  public boolean isFullyCoding() {
    return hasCDS() && cdsStart == start && cdsEnd == end;
  }
  
  /**
   * Check if this exon has a 5' UTR region (before CDS).
   * For forward strand, this is at the start of the exon.
   */
  public boolean has5UTR(boolean isForwardStrand) {
    if (!hasCDS()) return true;  // Entire exon is UTR
    if (isForwardStrand) {
      return cdsStart > start;
    } else {
      return cdsEnd < end;
    }
  }
  
  /**
   * Check if this exon has a 3' UTR region (after CDS).
   * For forward strand, this is at the end of the exon.
   */
  public boolean has3UTR(boolean isForwardStrand) {
    if (!hasCDS()) return true;  // Entire exon is UTR
    if (isForwardStrand) {
      return cdsEnd < end;
    } else {
      return cdsStart > start;
    }
  }
  
  /**
   * Get the length of this exon in bases.
   */
  public long length() {
    return end - start + 1;
  }
  
  /**
   * Get the length of the CDS portion.
   */
  public long cdsLength() {
    if (!hasCDS()) return 0;
    return cdsEnd - cdsStart + 1;
  }
  
  /**
   * Simple representation as start-end array for backward compatibility.
   */
  public long[] toArray() {
    return new long[]{start, end};
  }
}
