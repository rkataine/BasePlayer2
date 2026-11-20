package org.baseplayer.analysis;

/**
 * Selectable hypothesis test for group comparison (all multiomics modes).
 * {@link #AUTO} picks from matrix shape and group count.
 */
public enum GroupStatTest {
  AUTO,
  WELCH,
  /** Mann–Whitney (2 groups) or Kruskal–Wallis (N&gt;2). */
  RANK,
  /** 2×2 Fisher on signal vs no-signal; falls back to RANK when N&gt;2. */
  FISHER;

  public String displayName() {
    return switch (this) {
      case AUTO -> "Auto";
      case WELCH -> "Welch t-test";
      case RANK -> "Mann–Whitney / Kruskal–Wallis";
      case FISHER -> "Fisher exact (hits)";
    };
  }

  public static GroupStatTest fromPersisted(String name) {
    if (name == null || name.isBlank()) {
      return AUTO;
    }
    try {
      return GroupStatTest.valueOf(name.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return AUTO;
    }
  }

  /**
   * Resolve Auto to a concrete test for this run.
   * Sparse missingDefault=0 matrices prefer Fisher when 2 groups (carrier-like);
   * otherwise Welch (2) or Kruskal–Wallis (N&gt;2).
   */
  public GroupStatTest resolve(FeatureValueMatrix matrix, int groupCount) {
    if (this != AUTO) {
      if (this == FISHER && groupCount > 2) {
        return RANK;
      }
      if (this == WELCH && groupCount > 2) {
        return RANK;
      }
      return this;
    }
    if (groupCount > 2) {
      return RANK;
    }
    if (matrix != null && matrix.getMissingDefault() != null) {
      return FISHER;
    }
    return WELCH;
  }

  public String resolvedLabel(FeatureValueMatrix matrix, int groupCount) {
    GroupStatTest concrete = resolve(matrix, groupCount);
    return switch (concrete) {
      case WELCH -> "Welch t-test";
      case RANK -> groupCount > 2 ? "Kruskal-Wallis" : "Mann-Whitney";
      case FISHER -> "Fisher exact";
      case AUTO -> "Auto";
    };
  }
}
