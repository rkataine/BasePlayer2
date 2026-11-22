package org.baseplayer.analysis;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.signal.SignalValueType;

/**
 * How {@link GroupStatEngine} summarizes the contrast vs baseline.
 * {@link #AUTO} follows {@link SignalValueType}: methylation / allele CN → mean Δ;
 * otherwise log2 fold of group means.
 */
public enum EffectMode {
  AUTO,
  /** {@code log2((other+ε)/(baseline+ε))}. */
  LOG2_FOLD,
  /** {@code otherMean − baselineMean} (betas, CN, already-log values). */
  MEAN_DELTA,
  /** Same numeric as {@link #MEAN_DELTA}; labels as already log-scale. */
  ALREADY_LOG;

  public String displayName() {
    return switch (this) {
      case AUTO -> "Auto (by value type)";
      case LOG2_FOLD -> "log2 fold";
      case MEAN_DELTA -> "Mean difference";
      case ALREADY_LOG -> "Already log-scale (mean Δ)";
    };
  }

  public static EffectMode fromPersisted(String name) {
    if (name == null || name.isBlank()) {
      return AUTO;
    }
    try {
      return EffectMode.valueOf(name.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return AUTO;
    }
  }

  /** Concrete effect used for a run (ALREADY_LOG collapses to MEAN_DELTA). */
  public EffectMode resolve(SignalValueType type) {
    if (this == ALREADY_LOG || this == MEAN_DELTA) {
      return MEAN_DELTA;
    }
    if (this == LOG2_FOLD) {
      return LOG2_FOLD;
    }
    SignalValueType t = type != null ? type : SignalValueType.RAW;
    return switch (t) {
      case METHYLATION, ALLELE_CN -> MEAN_DELTA;
      case EXPRESSION, RAW -> LOG2_FOLD;
    };
  }

  public String resolvedLabel(SignalValueType type) {
    if (this == ALREADY_LOG) {
      return "mean Δ (log-scale values)";
    }
    return switch (resolve(type)) {
      case LOG2_FOLD -> "log2 fold";
      case MEAN_DELTA, ALREADY_LOG -> "mean Δ";
      case AUTO -> "auto";
    };
  }
}
