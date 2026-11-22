package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.List;
import org.baseplayer.analysis.groupstat.GroupStatResult;

/**
 * CNV-style direction for Allele CN annotation rows (table readability).
 * Thresholds match aggregate Allele-CN recurrence (gain ≥ 2.5 total CN).
 */
public enum SignalEventDirection {
  NONE("—"),
  GAIN("↑ Gain"),
  LOSS("↓ Loss"),
  LOH("LOH");

  /** Total CN at/above this counts as gain. */
  public static final double GAIN_TOTAL_CN = 2.5;
  /** Total CN at/below this counts as loss (when not LOH). */
  public static final double LOSS_TOTAL_CN = 1.5;

  private final String label;

  SignalEventDirection(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /**
   * Classify from minor/major allele copy numbers.
   * LOH: minor ≈ 0 and major ≈ 2 (copy-neutral); else gain/loss by total CN.
   * Hemizygous 0/1 is Loss, not LOH.
   */
  public static SignalEventDirection fromAlleleCn(Double minor, Double major) {
    double mi = minor != null && Double.isFinite(minor) ? minor : Double.NaN;
    double ma = major != null && Double.isFinite(major) ? major : Double.NaN;
    if (!Double.isFinite(mi) && !Double.isFinite(ma)) {
      return NONE;
    }
    if (!Double.isFinite(mi)) {
      mi = 0.0;
    }
    if (!Double.isFinite(ma)) {
      ma = 0.0;
    }
    // Copy-neutral LOH (e.g. 0/2); 0/1 is hemizygous loss below.
    if (mi <= 0.25 && ma >= 1.75) {
      return LOH;
    }
    double total = mi + ma;
    if (total >= GAIN_TOTAL_CN) {
      return GAIN;
    }
    if (total <= LOSS_TOTAL_CN) {
      return LOSS;
    }
    return NONE;
  }

  /** Resolve minor/major from a multi-channel annotation result row. */
  public static SignalEventDirection fromResultRow(GroupStatResult result, GroupStatResult.Row row) {
    if (result == null || row == null || !result.hasSignalChannels()) {
      return NONE;
    }
    Double minor = null;
    Double major = null;
    for (SignalChannel ch : result.signalChannels()) {
      if (ch == null || ch.type() != SignalValueType.ALLELE_CN) {
        continue;
      }
      Double v = result.channelValue(row.unitId(), ch.id());
      if (ch.sourceRole() == org.baseplayer.io.table.MappedTableColumnRole.MINOR_CN
          || "minor".equalsIgnoreCase(ch.id())
          || (ch.label() != null && ch.label().toLowerCase().contains("minor"))) {
        minor = v;
      } else if (ch.sourceRole() == org.baseplayer.io.table.MappedTableColumnRole.MAJOR_CN
          || "major".equalsIgnoreCase(ch.id())
          || (ch.label() != null && ch.label().toLowerCase().contains("major"))) {
        major = v;
      }
    }
    if (minor == null && major == null) {
      // Fall back: first two ALLELE_CN channels as minor then major.
      int i = 0;
      for (SignalChannel ch : result.signalChannels()) {
        if (ch == null || ch.type() != SignalValueType.ALLELE_CN) {
          continue;
        }
        Double v = result.channelValue(row.unitId(), ch.id());
        if (i == 0) {
          minor = v;
        } else if (i == 1) {
          major = v;
        }
        i++;
      }
    }
    return fromAlleleCn(minor, major);
  }

  public static boolean resultHasAlleleCn(GroupStatResult result) {
    if (result == null || !result.hasSignalChannels()) {
      return false;
    }
    for (SignalChannel ch : result.signalChannels()) {
      if (ch != null && ch.type() == SignalValueType.ALLELE_CN) {
        return true;
      }
    }
    return false;
  }

  /**
   * Chrom-local color scale: p5–p95 when ≥8 points, else min–max.
   * Returns {@code [lo, hi]} or null if no finite values.
   */
  public static double[] chromValueScale(List<Double> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    List<Double> sorted = new ArrayList<>(values.size());
    for (Double v : values) {
      if (v != null && Double.isFinite(v)) {
        sorted.add(v);
      }
    }
    if (sorted.isEmpty()) {
      return null;
    }
    sorted.sort(Double::compareTo);
    double lo;
    double hi;
    if (sorted.size() >= 8) {
      lo = percentile(sorted, 0.05);
      hi = percentile(sorted, 0.95);
    } else {
      lo = sorted.get(0);
      hi = sorted.get(sorted.size() - 1);
    }
    if (hi <= lo) {
      hi = lo + 1e-9;
    }
    return new double[] { lo, hi };
  }

  private static double percentile(List<Double> sortedAsc, double p) {
    if (sortedAsc.isEmpty()) {
      return Double.NaN;
    }
    if (sortedAsc.size() == 1) {
      return sortedAsc.get(0);
    }
    double idx = p * (sortedAsc.size() - 1);
    int i = (int) Math.floor(idx);
    int j = Math.min(sortedAsc.size() - 1, i + 1);
    double f = idx - i;
    return sortedAsc.get(i) * (1.0 - f) + sortedAsc.get(j) * f;
  }
}
