package org.baseplayer.io.table;

/** Semantic columns for user-mapped genomic tables (any tool/format). */
public enum MappedTableColumnRole {
  CHROM("Chromosome"),
  START("Start"),
  END("End"),
  VALUE("Value"),
  MINOR_CN("Minor allele CN"),
  MAJOR_CN("Major allele CN"),
  SAMPLE("Sample"),
  TOTAL_CN("Total CN"),
  SCORE("Score");

  private final String label;

  MappedTableColumnRole(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** Chromosome + interval are always required. */
  public boolean required() {
    return this == CHROM || this == START || this == END;
  }
}
