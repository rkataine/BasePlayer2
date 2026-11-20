package org.baseplayer.analysis;

/** How continuous signal values are aggregated into comparison rows. */
public enum ComparisonUnit {
  GENE("Genes"),
  WINDOW("Windows"),
  NATIVE_FEATURE("Features");

  private final String label;

  ComparisonUnit(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
