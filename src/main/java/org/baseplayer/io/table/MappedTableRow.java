package org.baseplayer.io.table;

/**
 * One genomic interval row from a user-mapped table.
 * Coordinates are inclusive 1-based after mapping.
 */
public record MappedTableRow(
    String chromosome,
    long start1Based,
    long end1Based,
    String sampleId,
    Double value,
    Double minorCn,
    Double majorCn,
    Double totalCn,
    Double score) {

  public MappedTableRow {
    if (chromosome == null || chromosome.isBlank()) {
      throw new IllegalArgumentException("chromosome required");
    }
    if (end1Based < start1Based) {
      long tmp = start1Based;
      start1Based = end1Based;
      end1Based = tmp;
    }
  }

  public double analysisValue() {
    if (value != null && Double.isFinite(value)) {
      return value;
    }
    if (score != null && Double.isFinite(score)) {
      return score;
    }
    return Double.NaN;
  }

  public boolean hasAlleleCn() {
    return minorCn != null && majorCn != null
        && Double.isFinite(minorCn) && Double.isFinite(majorCn);
  }
}
