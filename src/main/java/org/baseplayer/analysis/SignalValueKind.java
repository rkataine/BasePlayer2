package org.baseplayer.analysis;

/** Numeric value source kinds available for Signal comparison. */
public enum SignalValueKind {
  BED_SCORES("BED with scores"),
  BED_ANY("BED features"),
  BIGWIG("BigWig"),
  BAM_METHYL("BAM methylation (current view)"),
  /** Opened tables with a Value/Score column. */
  TABLE_VALUES("Table values"),
  /** Opened tables with minor+major allele CN → LOH scores. */
  TABLE_LOH("Table allele-CN LOH");

  private final String label;

  SignalValueKind(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
