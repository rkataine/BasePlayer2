package org.baseplayer.samples.alignment.draw;

/**
 * Type of coverage data needed for a read color mode.
 */
enum CoverageDataType {
  /** No special coverage data needed (just mismatches). */
  NONE,
  /** Numeric signal tag data (UC, UD, UL, etc.). */
  SIGNAL,
  /** Base modification data (MM/ML tags). */
  MODIFICATION,
  /** Base quality scores. */
  QUALITY
}

/**
 * Determines how reads are colored in the alignment view.
 */
public enum ReadColorMode {
  /** Color by strand (forward/reverse) — the default. */
  STRAND("Strand", CoverageDataType.NONE),
  /** Color by average base quality (low=warm, high=cool). */
  BASE_QUALITY("Base quality", CoverageDataType.QUALITY),
  /** Color by Uncalled uc:B:s tag values — blue (negative) to red (positive). */
  UC_TAG("UC tag", CoverageDataType.SIGNAL),
  /** Color by Uncalled ud:B:s tag values — blue (negative) to red (positive). */
  UD_TAG("UD tag", CoverageDataType.SIGNAL),
  UL_TAG("UL tag", CoverageDataType.SIGNAL),
  /** Color by base modification probability from MM/ML tags — blue (low) to red (high). */
  BASE_MODIFICATION("Base modifications", CoverageDataType.MODIFICATION);

  private final String label;
  private final CoverageDataType dataType;

  ReadColorMode(String label, CoverageDataType dataType) {
    this.label = label;
    this.dataType = dataType;
  }

  public String getLabel() { return label; }
  public CoverageDataType getDataType() { return dataType; }

  @Override public String toString() { return label; }
}
