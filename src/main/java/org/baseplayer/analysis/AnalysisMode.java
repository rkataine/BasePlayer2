package org.baseplayer.analysis;

/** Top-level Analysis Manager modes. */
public enum AnalysisMode {
  MUTATIONS_POINT,
  MUTATIONS_SV,
  /** Continuous numeric signal comparison (any comparable values). */
  SIGNAL;

  /**
   * Map legacy persisted / in-memory names to current modes.
   * {@code EXPRESSION} and {@code METHYLATION} become {@link #SIGNAL}.
   */
  public static AnalysisMode fromPersisted(String name) {
    if (name == null || name.isBlank()) {
      return SIGNAL;
    }
    String key = name.trim().toUpperCase();
    if ("EXPRESSION".equals(key) || "METHYLATION".equals(key) || "SIGNAL".equals(key)) {
      return SIGNAL;
    }
    try {
      return AnalysisMode.valueOf(key);
    } catch (IllegalArgumentException ex) {
      return SIGNAL;
    }
  }
}
