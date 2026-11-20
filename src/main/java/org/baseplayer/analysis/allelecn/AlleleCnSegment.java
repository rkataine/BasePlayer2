package org.baseplayer.analysis.allelecn;

/**
 * Neutral allele-specific copy-number segment (tool-agnostic).
 * Coordinates are inclusive 1-based genomic positions after mapping.
 */
public record AlleleCnSegment(
    String chromosome,
    long start1Based,
    long end1Based,
    String sampleId,
    double minorCn,
    double majorCn,
    Double totalCn,
    Double score) {

  public AlleleCnSegment {
    if (chromosome == null || chromosome.isBlank()) {
      throw new IllegalArgumentException("chromosome required");
    }
    if (end1Based < start1Based) {
      long tmp = start1Based;
      start1Based = end1Based;
      end1Based = tmp;
    }
  }

  public double totalAlleleCn() {
    return minorCn + majorCn;
  }
}
