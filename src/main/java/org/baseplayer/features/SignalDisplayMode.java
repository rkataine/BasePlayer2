package org.baseplayer.features;

/**
 * How continuous / allele-frequency signal is drawn on a track.
 */
public enum SignalDisplayMode {
  /** Classic coverage-style histogram (auto-scaled). */
  HISTOGRAM("Histogram"),
  /**
   * B-allele frequency style: fixed 0–1 scale, 0.5 midline,
   * values as dots (zoomed out) or short bars (zoomed in).
   */
  BAF("BAF (0–1)"),
  /**
   * DRAGEN-style CNV segments ({@code *.seg.bw}): horizontal steps at copy-ratio
   * (linear or log₂). Neutral at 1.0 (linear) or 0 (log₂).
   */
  COPY_RATIO("Copy-ratio segments");

  private final String label;

  SignalDisplayMode(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
