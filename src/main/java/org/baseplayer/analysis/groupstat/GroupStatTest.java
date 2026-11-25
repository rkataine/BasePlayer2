package org.baseplayer.analysis.groupstat;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;

/**
 * Selectable hypothesis test for group comparison (all multiomics modes).
 * {@link #AUTO} picks from matrix shape. With three or more groups the engine
 * runs all pairwise 2-group tests of the resolved concrete test.
 */
public enum GroupStatTest {
  AUTO,
  /** Limma-style moderated t (pairwise when N&gt;2). */
  MODERATED,
  WELCH,
  /** Mann–Whitney (pairwise when N&gt;2). */
  RANK,
  /** 2×2 Fisher on signal vs no-signal (pairwise when N&gt;2). */
  FISHER;

  public String displayName() {
    return switch (this) {
      case AUTO -> "Auto (Fisher / Moderated / pairwise)";
      case MODERATED -> "Moderated t-test";
      case WELCH -> "Welch t-test";
      case RANK -> "Mann–Whitney";
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
              + "With three or more groups it runs the same 2-group test on every pair "
              + "and reports the best (lowest-p) contrast; Hits/Diff list every group "
              + "involved in a pair with p≤0.05. "
              + "Use Auto unless you know the data type and want to force a specific test.";
      case MODERATED ->
          "Limma-inspired moderated two-sample t-test for continuous / expression-like "
              + "values. It borrows strength across features to stabilize variance estimates, "
              + "which helps when each group has few samples. "
              + "Prefer this for numeric signal matrices. With three or more groups the "
              + "engine applies it pairwise. "
              + "Not intended for binary hit/no-hit tables.";
      case WELCH ->
          "Classic two-sided Welch t-test (unequal variances allowed) on continuous values. "
              + "Choose this when you want a standard t-test without moderated variance "
              + "shrinkage, or when group variances clearly differ. "
              + "Usually Moderated t is a better default for multiomics feature screens. "
              + "With three or more groups it is applied pairwise.";
      case RANK ->
          "Non-parametric Mann–Whitney (Wilcoxon rank-sum) test. "
              + "Use for skewed, ordinal, or non-normal continuous values. "
              + "With three or more groups it is applied pairwise.";
      case FISHER ->
          "Two-sided 2×2 Fisher exact test on signal vs no-signal (hit) counts. "
              + "Best for sparse binary or carrier-like matrices where presence/absence "
              + "matters more than intensity. Do not use on continuous intensities — "
              + "prefer Moderated t, Welch, or rank tests instead. "
              + "With three or more groups it is applied pairwise.";
    };
  }

  /** Shared preamble shown above per-test sections in the help modal. */
  public static String helpIntro() {
    return "Compare sample groups on the current feature matrix. All tests report "
        + "Benjamini–Hochberg FDR (q-values) across features. With three or more groups, "
        + "every pair is tested with the chosen 2-group method; each feature keeps its "
        + "best (lowest-p) pair for ranking, and the table shows all groups that appear "
        + "in any pair with raw p≤0.05.";
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
   * Resolve Auto to a concrete 2-group test for this matrix.
   * Sparse missingDefault=0 matrices prefer Fisher; otherwise Moderated.
   * Multi-group runs use this concrete test pairwise (see {@link GroupStatEngine}).
   */
  public GroupStatTest resolve(FeatureValueMatrix matrix, int groupCount) {
    return resolve((ValueGrid) matrix, groupCount);
  }

  public GroupStatTest resolve(ValueGrid matrix, int groupCount) {
    if (this != AUTO) {
      return this;
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
    GroupStatTest concrete = resolve(matrix, Math.max(2, groupCount));
    String base = switch (concrete) {
      case MODERATED -> "Moderated t-test";
      case WELCH -> "Welch t-test";
      case RANK -> "Mann-Whitney";
      case FISHER -> "Fisher exact";
      case AUTO -> "Auto";
    };
    if (groupCount > 2) {
      return "Pairwise " + base;
    }
    return base;
  }
}
