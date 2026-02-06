package org.baseplayer.annotation;

import java.util.List;

/**
 * Represents a gene with its genomic coordinates, strand, biotype, description, and transcripts.
 * By default only MANE transcripts are loaded; other transcripts can be loaded on demand.
 */
public record Gene(
    String chrom, 
    long start, 
    long end, 
    String name, 
    String id, 
    String strand, 
    String biotype,
    String description,
    List<Transcript> transcripts,
    List<long[]> exons  // Merged exons from all transcripts for simple display
) {
  /** Check if gene has any MANE_Select transcript */
  public boolean hasManeSelect() {
    return transcripts != null && transcripts.stream().anyMatch(Transcript::isManeSelect);
  }
  
  /** Get the MANE_Select transcript if available */
  public Transcript getManeSelectTranscript() {
    if (transcripts == null) return null;
    return transcripts.stream().filter(Transcript::isManeSelect).findFirst().orElse(null);
  }
  
  /** Get exons for display - uses MANE transcript if available, otherwise first transcript */
  public List<long[]> getDisplayExons(boolean maneOnly) {
    // Try to use MANE transcript first
    if (maneOnly && hasManeSelect()) {
      Transcript mane = getManeSelectTranscript();
      return mane != null ? mane.exons() : exons;
    }
    
    // For genes without MANE (e.g., lncRNAs), use first transcript if available
    if (maneOnly && transcripts != null && !transcripts.isEmpty()) {
      return transcripts.get(0).exons();
    }
    
    // Fallback to merged exons
    return exons;
  }
}
