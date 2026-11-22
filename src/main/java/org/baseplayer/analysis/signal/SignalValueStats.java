package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.groupstat.GroupStatEngine;

/**
 * Observed distribution of per-unit annotation values (before threshold filtering).
 */
public final class SignalValueStats {

  private final int unitCount;
  private final double min;
  private final double max;
  private final double p5;
  private final double p50;
  private final double p95;

  private SignalValueStats(int unitCount, double min, double max, double p5, double p50, double p95) {
    this.unitCount = unitCount;
    this.min = min;
    this.max = max;
    this.p5 = p5;
    this.p50 = p50;
    this.p95 = p95;
  }

  public int unitCount() {
    return unitCount;
  }

  public double min() {
    return min;
  }

  public double max() {
    return max;
  }

  public double p5() {
    return p5;
  }

  public double p50() {
    return p50;
  }

  public double p95() {
    return p95;
  }

  public boolean isEmpty() {
    return unitCount <= 0 || !Double.isFinite(min) || !Double.isFinite(max);
  }

  public String formatSummary() {
    if (isEmpty()) {
      return "No values yet";
    }
    return String.format(
        Locale.ROOT,
        "%d units · min %s · p5 %s · median %s · p95 %s · max %s",
        unitCount,
        formatNum(min),
        formatNum(p5),
        formatNum(p50),
        formatNum(p95),
        formatNum(max));
  }

  /**
   * Per-unit means across {@code tracks}, matching {@link SignalAnnotator} inputs.
   */
  public static SignalValueStats fromMatrix(FeatureValueMatrix matrix, List<SampleTrack> tracks) {
    return fromGrid(matrix, tracks);
  }

  public static SignalValueStats fromGrid(ValueGrid matrix, List<SampleTrack> tracks) {
    if (matrix == null || matrix.isEmpty()) {
      return empty();
    }
    List<SampleTrack> sampleTracks = tracks != null ? tracks : List.of();
    List<Double> means = new ArrayList<>();
    for (String unitId : matrix.unitIds()) {
      List<Double> vals = matrix.valuesFor(unitId, sampleTracks);
      if (vals.isEmpty() && sampleTracks.isEmpty()) {
        vals = matrix.valuesFor(unitId, new ArrayList<>(matrix.tracks()));
      }
      double mean = GroupStatEngine.mean(vals);
      if (Double.isFinite(mean)) {
        means.add(mean);
      }
    }
    return fromValues(means);
  }

  public static SignalValueStats fromValues(List<Double> values) {
    if (values == null || values.isEmpty()) {
      return empty();
    }
    List<Double> sorted = new ArrayList<>();
    for (Double v : values) {
      if (v != null && Double.isFinite(v)) {
        sorted.add(v);
      }
    }
    if (sorted.isEmpty()) {
      return empty();
    }
    Collections.sort(sorted);
    int n = sorted.size();
    return new SignalValueStats(
        n,
        sorted.get(0),
        sorted.get(n - 1),
        percentile(sorted, 0.05),
        percentile(sorted, 0.50),
        percentile(sorted, 0.95));
  }

  public static SignalValueStats empty() {
    return new SignalValueStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
  }

  private static double percentile(List<Double> sorted, double p) {
    if (sorted.isEmpty()) {
      return Double.NaN;
    }
    if (sorted.size() == 1) {
      return sorted.get(0);
    }
    double idx = p * (sorted.size() - 1);
    int lo = (int) Math.floor(idx);
    int hi = (int) Math.ceil(idx);
    if (lo == hi) {
      return sorted.get(lo);
    }
    double w = idx - lo;
    return sorted.get(lo) * (1.0 - w) + sorted.get(hi) * w;
  }

  private static String formatNum(double v) {
    if (!Double.isFinite(v)) {
      return "—";
    }
    if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e9) {
      return String.valueOf((long) Math.rint(v));
    }
    return String.format(Locale.ROOT, "%.4g", v);
  }
}
