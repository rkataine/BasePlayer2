package org.baseplayer.analysis.signal;

import java.util.Locale;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.ValueGrid;

/**
 * Coarse value-scale detection from sampled numeric cells.
 * Distinguishes methylation-like, counts-like, log-like, and continuous expression-like
 * (TPM/FPKM/CPM look the same — all map to {@link SignalValueType#EXPRESSION}).
 */
public final class SignalScaleInfer {

  public record Suggestion(
      SignalValueType type,
      EffectMode effectMode,
      String summary) {}

  private static final int DEFAULT_CAP = 50_000;

  private SignalScaleInfer() {}

  public static Suggestion inferFromGrid(ValueGrid grid) {
    return inferFromGrid(grid, DEFAULT_CAP);
  }

  public static Suggestion inferFromGrid(ValueGrid grid, int maxSamples) {
    if (grid == null || grid.isEmpty()) {
      return new Suggestion(SignalValueType.RAW, EffectMode.AUTO, "No values to detect");
    }
    int cap = Math.max(100, maxSamples);
    double[] buf = new double[cap];
    int n = 0;
    outer:
    for (String unitId : grid.unitIds()) {
      for (SampleTrack track : grid.tracks()) {
        Double v = grid.get(unitId, track);
        if (v == null || !Double.isFinite(v)) {
          continue;
        }
        buf[n++] = v;
        if (n >= cap) {
          break outer;
        }
      }
    }
    return infer(buf, n);
  }

  /** Package-visible for tests. */
  public static Suggestion infer(double[] values, int n) {
    if (values == null || n <= 0) {
      return new Suggestion(SignalValueType.RAW, EffectMode.AUTO, "No values to detect");
    }
    int finite = 0;
    int negatives = 0;
    int inUnit = 0;
    int inPercent = 0;
    int nearInt = 0;
    int fractional = 0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < n; i++) {
      double v = values[i];
      if (!Double.isFinite(v)) {
        continue;
      }
      finite++;
      min = Math.min(min, v);
      max = Math.max(max, v);
      if (v < 0) {
        negatives++;
      }
      if (v >= 0 && v <= 1.0) {
        inUnit++;
      }
      if (v >= 0 && v <= 100.0) {
        inPercent++;
      }
      if (Math.abs(v - Math.rint(v)) < 1e-6) {
        nearInt++;
      } else {
        fractional++;
      }
    }
    if (finite == 0) {
      return new Suggestion(SignalValueType.RAW, EffectMode.AUTO, "No finite values");
    }
    double negFrac = negatives / (double) finite;
    double unitFrac = inUnit / (double) finite;
    double pctFrac = inPercent / (double) finite;
    double intFrac = nearInt / (double) finite;

    if (negFrac >= 0.05) {
      return new Suggestion(
          SignalValueType.EXPRESSION,
          EffectMode.ALREADY_LOG,
          String.format(Locale.ROOT,
              "Detected: Expression, already log-scale (%.0f%% negative)",
              100 * negFrac));
    }
    if (unitFrac >= 0.90 && max <= 1.0 + 1e-6 && min >= -1e-9) {
      return new Suggestion(
          SignalValueType.METHYLATION,
          EffectMode.MEAN_DELTA,
          "Detected: Methylation (values in [0, 1])");
    }
    if (pctFrac >= 0.90 && max <= 100.0 + 1e-6 && min >= -1e-9 && max > 1.5 && intFrac < 0.85) {
      return new Suggestion(
          SignalValueType.METHYLATION,
          EffectMode.MEAN_DELTA,
          "Detected: Methylation-like (values in [0, 100])");
    }
    if (intFrac >= 0.95 && min >= -1e-9 && max <= 12.0) {
      return new Suggestion(
          SignalValueType.ALLELE_CN,
          EffectMode.MEAN_DELTA,
          String.format(Locale.ROOT,
              "Detected: Allele CN-like (integers ≤ %.0f)",
              max));
    }
    if (intFrac >= 0.92 && min >= -1e-9 && max > 12.0) {
      return new Suggestion(
          SignalValueType.EXPRESSION,
          EffectMode.LOG2_FOLD,
          "Detected: Expression / counts-like integers (exploratory log2 fold)");
    }
    if (fractional > 0 && min >= -1e-9) {
      return new Suggestion(
          SignalValueType.EXPRESSION,
          EffectMode.LOG2_FOLD,
          "Detected: Expression-like continuous (TPM/FPKM/CPM-compatible)");
    }
    return new Suggestion(
        SignalValueType.RAW,
        EffectMode.LOG2_FOLD,
        "Detected: Raw continuous signal");
  }
}
