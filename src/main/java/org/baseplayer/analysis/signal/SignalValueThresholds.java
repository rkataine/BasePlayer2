package org.baseplayer.analysis.signal;
import org.baseplayer.analysis.DifferenceThresholds;

/**
 * Absolute value cutoffs for signal annotation (single-sample / cohort listing).
 * Distinct from {@link DifferenceThresholds}, which filter group differentials.
 */
public final class SignalValueThresholds {

  private boolean enabled = false;
  /** Inclusive lower bound when non-null. */
  private Double minValue = null;
  /** Inclusive upper bound when non-null. */
  private Double maxValue = null;
  /**
   * Baseline / “middle” for near-baseline exclusion (e.g. 0 for LOH fraction,
   * 2 for diploid total CN). Used with {@link #minAbsFromMiddle}.
   */
  private double middle = 0.0;
  /**
   * When {@code > 0} and filter is enabled, keep only values with
   * {@code |value − middle| ≥ minAbsFromMiddle} (drops near-diploid / near-baseline).
   */
  private double minAbsFromMiddle = 0.0;
  /**
   * Keep at most this many rows after filtering, ranked by |value − middle| descending
   * when middle exclusion is active, otherwise by |value|.
   * {@code 0} = unlimited.
   */
  private int maxRows = 0;
  /**
   * Allele-CN LOH mode: keep only units with value {@code > 0} (any LOH).
   * Independent of {@link #enabled} min/max cutoffs.
   */
  private boolean lohOnly = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Double getMinValue() {
    return minValue;
  }

  public void setMinValue(Double minValue) {
    this.minValue = minValue != null && Double.isFinite(minValue) ? minValue : null;
  }

  public Double getMaxValue() {
    return maxValue;
  }

  public void setMaxValue(Double maxValue) {
    this.maxValue = maxValue != null && Double.isFinite(maxValue) ? maxValue : null;
  }

  public double getMiddle() {
    return middle;
  }

  public void setMiddle(double middle) {
    this.middle = Double.isFinite(middle) ? middle : 0.0;
  }

  public double getMinAbsFromMiddle() {
    return minAbsFromMiddle;
  }

  public void setMinAbsFromMiddle(double minAbsFromMiddle) {
    this.minAbsFromMiddle = Double.isFinite(minAbsFromMiddle)
        ? Math.max(0.0, minAbsFromMiddle)
        : 0.0;
  }

  public boolean usesMiddleExclusion() {
    return enabled && minAbsFromMiddle > 0.0;
  }

  public int getMaxRows() {
    return maxRows;
  }

  public void setMaxRows(int maxRows) {
    this.maxRows = Math.max(0, maxRows);
  }

  public boolean isLohOnly() {
    return lohOnly;
  }

  public void setLohOnly(boolean lohOnly) {
    this.lohOnly = lohOnly;
  }

  public boolean passes(double value) {
    if (!Double.isFinite(value)) {
      return false;
    }
    if (lohOnly && !(value > 0.0)) {
      return false;
    }
    if (!enabled) {
      return true;
    }
    if (minValue != null && value < minValue) {
      return false;
    }
    if (maxValue != null && value > maxValue) {
      return false;
    }
    if (minAbsFromMiddle > 0.0 && Math.abs(value - middle) < minAbsFromMiddle) {
      return false;
    }
    return true;
  }

  /** Ranking magnitude for Top-N: distance from middle when that filter is on. */
  public double rankMagnitude(double value) {
    if (!Double.isFinite(value)) {
      return 0.0;
    }
    if (usesMiddleExclusion()) {
      return Math.abs(value - middle);
    }
    return Math.abs(value);
  }

  public SignalValueThresholds copy() {
    SignalValueThresholds t = new SignalValueThresholds();
    t.enabled = enabled;
    t.minValue = minValue;
    t.maxValue = maxValue;
    t.middle = middle;
    t.minAbsFromMiddle = minAbsFromMiddle;
    t.maxRows = maxRows;
    t.lohOnly = lohOnly;
    return t;
  }

  /** Legacy kind defaults (single-channel path). */
  public static SignalValueThresholds defaultsFor(SignalValueKind kind) {
    SignalValueThresholds t = new SignalValueThresholds();
    if (kind == SignalValueKind.TABLE_LOH) {
      t.lohOnly = true;
      t.middle = 0.0;
    } else if (kind == SignalValueKind.TABLE_VALUES) {
      t.middle = 2.0;
    }
    return t;
  }

  /**
   * Per-channel suggested baselines by {@link SignalValueType}.
   * Filtering stays <strong>off</strong> until the user enables it (Min/Max / |Δ|).
   * Allele CN suggests Middle=1 so diploid drop is one click away when Filter is on.
   */
  public static SignalValueThresholds defaultsFor(SignalValueType type) {
    SignalValueThresholds t = new SignalValueThresholds();
    if (type == null) {
      return t;
    }
    switch (type) {
      case ALLELE_CN -> t.middle = 1.0;
      case EXPRESSION -> t.middle = 0.0;
      case METHYLATION, RAW -> {
        // No bounds stored — UI may suggest 0–1 for methylation spinners only.
      }
    }
    return t;
  }
}
