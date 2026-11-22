package org.baseplayer.io.table;

/** How leading columns identify each matrix row. */
public enum MatrixKeyMode {
  /** Single gene id / symbol column (ENSG or HGNC), resolved via annotation. */
  GENE_ID("Gene ID (ENSG / symbol)", 1),
  /** Chromosome, start, end as three columns. */
  CHROM_START_END("Chrom / Start / End", 3),
  /** Single column {@code chr:start-end}. */
  REGION("Region (chr:start-end)", 1);

  private final String label;
  private final int keyColumnCount;

  MatrixKeyMode(String label, int keyColumnCount) {
    this.label = label;
    this.keyColumnCount = keyColumnCount;
  }

  public String label() {
    return label;
  }

  /** Number of leading key columns this mode consumes. */
  public int keyColumnCount() {
    return keyColumnCount;
  }

  @Override
  public String toString() {
    return label;
  }
}
