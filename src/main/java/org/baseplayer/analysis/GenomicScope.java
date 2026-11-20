package org.baseplayer.analysis;

/** Genomic extent for signal collection. */
public enum GenomicScope {
  ALL_CHROMS("All chromosomes"),
  CURRENT_CHROM("Current chromosome"),
  CURRENT_VIEW("Current view");

  private final String label;

  GenomicScope(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
