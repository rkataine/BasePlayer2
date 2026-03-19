package org.baseplayer.samples.alignment.draw;

/**
 * Determines how reads are colored in the alignment view.
 */
public enum ReadColorMode {
  /** Color by strand (forward/reverse) — the default. */
  STRAND("Strand"),
  /** Color by Uncalled uc:B:s tag values — blue (negative) to red (positive). */
  UC_TAG("UC tag"),
  /** Color by Uncalled ud:B:s tag values — blue (negative) to red (positive). */
  UD_TAG("UD tag");

  private final String label;

  ReadColorMode(String label) { this.label = label; }

  public String getLabel() { return label; }

  @Override public String toString() { return label; }
}
