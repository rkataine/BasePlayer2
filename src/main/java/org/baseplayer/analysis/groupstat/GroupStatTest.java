package org.baseplayer.analysis.groupstat;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;

/**
 * Selectable hypothesis test for group comparison (all multiomics modes).
 * {@link #AUTO} picks from matrix shape and group count.
 */
public enum GroupStatTest {
  AUTO,
  /** Limma-style moderated t (2 groups) or Kruskal–Wallis (N&gt;2). */
  MODERATED,
  WELCH,
  /** Mann–Whitney (2 groups) or Kruskal–Wallis (N&gt;2). */
  RANK,
  /** 2×2 Fisher on signal vs no-signal; falls back to RANK when N&gt;2. */
  FISHER;

  public String displayName() {
    return switch (this) {
      case AUTO -> "Auto (Fisher / Moderated / K–W)";
      case MODERATED -> "Moderated t-test";
      case WELCH -> "Welch t-test";
      case RANK -> "Mann–Whitney / Kruskal–Wallis";
      case FISHER -> "Fisher exact (hits)";
    };
  }

  /** Short section heading for the help modal (usually {@link #displayName()}). */
  public String helpTitle() {
    return displayName();
  }

  /**
   * Explanation of what this test does and when to choose it.
   * Shared FDR / N&gt;2 notes live in {@link #helpIntro()}.
   */
  public String helpBody() {
    return switch (this) {
      case AUTO ->
          "Default choice for most runs. With two groups, Auto uses Fisher exact on "
              + "sparse 0/1 (carrier-like) matrices and Moderated t on continuous values. "
              + "With three or more groups it uses Kruskal–Wallis. "
              + "Use Auto unless you know the data type and want to force a specific test.";
      case MODERATED ->
          "Limma-inspired moderated two-sample t-test for continuous / expression-like "
              + "values. It borrows strength across features to stabilize variance estimates, "
              + "which helps when each group has few samples. "
              + "Prefer this for numeric signal matrices with two groups. "
              + "Not intended for binary hit/no-hit tables.";
      case WELCH ->
          "Classic two-sided Welch t-test (unequal variances allowed) on continuous values. "
              + "Choose this when you want a standard t-test without moderated variance "
              + "shrinkage, or when group variances clearly differ. "
              + "Usually Moderated t is a better default for multiomics feature screens.";
      case RANK ->
          "Non-parametric tests: Mann–Whitney (two groups) or Kruskal–Wallis (three or more). "
              + "Use for skewed, ordinal, or non-normal continuous values, or whenever you "
              + "want a rank-based comparison. This is also the automatic path when N>2 "
              + "for tests that only support two groups.";
      case FISHER ->
          "Two-sided 2×2 Fisher exact test on signal vs no-signal (hit) counts. "
              + "Best for sparse binary or carrier-like matrices where presence/absence "
              + "matters more than intensity. Do not use on continuous intensities — "
              + "prefer Moderated t, Welch, or rank tests instead.";
    };
  }

  /** Shared preamble shown above per-test sections in the help modal. */
  public static String helpIntro() {
    return "Compare sample groups on the current feature matrix. All tests report "
        + "Benjamini–Hochberg FDR (q-values). Moderated t, Welch, and Fisher only "
        + "apply to two groups; with three or more groups they fall back to "
        + "Kruskal–Wallis (rank).";
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
   * otherwise Moderated (2) or Kruskal–Wallis (N&gt;2).
   */
  public GroupStatTest resolve(FeatureValueMatrix matrix, int groupCount) {
    return resolve((ValueGrid) matrix, groupCount);
  }

  public GroupStatTest resolve(ValueGrid matrix, int groupCount) {
    if (this != AUTO) {
      if (this == FISHER && groupCount > 2) {
        return RANK;
      }
      if ((this == WELCH || this == MODERATED) && groupCount > 2) {
        return RANK;
      }
      return this;
    }
    if (groupCount > 2) {
      return RANK;
    }
    if (matrix instanceof FeatureValueMatrix fvm && fvm.getMissingDefault() != null) {
      return FISHER;
    }
    return MODERATED;
  }

  public String resolvedLabel(FeatureValueMatrix matrix, int groupCount) {
    return resolvedLabel((ValueGrid) matrix, groupCount);
  }

  public String resolvedLabel(ValueGrid matrix, int groupCount) {
    GroupStatTest concrete = resolve(matrix, groupCount);
    return switch (concrete) {
      case MODERATED -> "Moderated t-test";
      case WELCH -> "Welch t-test";
      case RANK -> groupCount > 2 ? "Kruskal-Wallis" : "Mann-Whitney";
      case FISHER -> "Fisher exact";
      case AUTO -> "Auto";
    };
  }
}
