package org.baseplayer.analysis.signal;

import java.util.Locale;

/**
 * Semantic type for a mapped Signal value channel. Drives filter defaults, labels,
 * and display precision (Allele CN → integers; continuous → compact float).
 */
public enum SignalValueType {
  RAW("Raw"),
  EXPRESSION("Expression"),
  METHYLATION("Methylation"),
  ALLELE_CN("Allele CN");

  private final String label;

  SignalValueType(String label) {
    this.label = label;
  }

  /** Round continuous collects to whole copy numbers when type is {@link #ALLELE_CN}. */
  public double normalize(double value) {
    if (!Double.isFinite(value)) {
      return value;
    }
    return this == ALLELE_CN ? Math.rint(value) : value;
  }

  /** Display string for a channel cell. */
  public String format(double value) {
    if (!Double.isFinite(value)) {
      return "—";
    }
    return switch (this) {
      case ALLELE_CN -> Long.toString(Math.round(value));
      default -> String.format(Locale.ROOT, "%.4g", value);
    };
  }

  public static String format(SignalValueType type, Double value) {
    if (value == null || !Double.isFinite(value)) {
      return "—";
    }
    return (type != null ? type : RAW).format(value.doubleValue());
  }

  @Override
  public String toString() {
    return label;
  }
}
