package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.baseplayer.analysis.groupstat.GroupStatResult;

/**
 * Filters {@link GroupStatResult} rows by effect size, FDR/p, per-group n, direction,
 * and optional top-N by difference magnitude.
 */
public final class ThresholdFilter {

  private ThresholdFilter() {}

  public static GroupStatResult apply(GroupStatResult result, DifferenceThresholds thresholds) {
    if (result == null) {
      return null;
    }
    if (thresholds == null) {
      return result;
    }
    if (!thresholds.isEnabled()) {
      return new GroupStatResult(
          result.mode(),
          result.groupIds(),
          result.baselineGroupId(),
          result.rows(),
          String.format(
              Locale.ROOT,
              "%s — %d rows (filter off)",
              result.summary(),
              result.rows().size()),
          result.methodStrip());
    }

    List<GroupStatResult.Row> kept = new ArrayList<>();
    for (GroupStatResult.Row row : result.rows()) {
      if (passes(row, thresholds)) {
        kept.add(row);
      }
    }

    int beforeTopN = kept.size();
    if (thresholds.getMaxRows() > 0 && kept.size() > thresholds.getMaxRows()) {
      kept.sort(Comparator
          .comparingDouble((GroupStatResult.Row r) -> differenceMagnitude(r)).reversed()
          .thenComparing(r -> r.qValue() != null ? r.qValue() : Double.POSITIVE_INFINITY)
          .thenComparing(r -> r.pValue() != null ? r.pValue() : Double.POSITIVE_INFINITY));
      kept = new ArrayList<>(kept.subList(0, thresholds.getMaxRows()));
    }

    String summary = String.format(
        Locale.ROOT,
        "%s — %d / %d rows (|Δ|≥%.2f, q≤%.3g, n≥%d%s)",
        result.summary(),
        kept.size(),
        result.rows().size(),
        thresholds.getMinAbsLog2Fc(),
        thresholds.getMaxQ(),
        thresholds.getMinNPerGroup(),
        thresholds.getMaxRows() > 0
            ? String.format(Locale.ROOT, ", top %d of %d", kept.size(), beforeTopN)
            : "");
    return new GroupStatResult(
        result.mode(),
        result.groupIds(),
        result.baselineGroupId(),
        kept,
        summary,
        result.methodStrip());
  }

  public static boolean passes(GroupStatResult.Row row, DifferenceThresholds thresholds) {
    if (row == null) {
      return false;
    }
    if (thresholds == null || !thresholds.isEnabled()) {
      // Filter off → show every computed row; toggle on to hide by Δ / q / direction.
      return true;
    }
    if (!passesDirection(row, thresholds)) {
      return false;
    }
    if (row.groups() != null) {
      for (GroupStatResult.GroupSummary g : row.groups()) {
        // Prefer observed samples (continuous DE / patterns); fall back to non-zero hits.
        int n = g.nObserved() > 0 ? g.nObserved() : Math.max(g.nSignal(), 0);
        if (n < thresholds.getMinNPerGroup()) {
          return false;
        }
      }
    }
    if (differenceMagnitude(row) < thresholds.getMinAbsLog2Fc()) {
      return false;
    }
    Double sig = row.qValue() != null ? row.qValue() : row.pValue();
    if (sig == null || Double.isNaN(sig)) {
      return false;
    }
    return sig <= thresholds.getMaxQ();
  }

  public static boolean passesDirection(GroupStatResult.Row row, DifferenceThresholds thresholds) {
    if (row == null || thresholds == null) {
      return true;
    }
    String dir = row.direction() != null ? row.direction() : "";
    return switch (dir) {
      case "up" -> thresholds.isShowUp();
      case "down" -> thresholds.isShowDown();
      case "different" -> thresholds.isShowUp() || thresholds.isShowDown();
      case "similar" -> thresholds.isShowSimilar();
      default -> thresholds.isShowUp() || thresholds.isShowDown() || thresholds.isShowSimilar();
    };
  }

  /**
   * Absolute effect vs baseline (log2 fold or mean Δ, depending on the run).
   * Falls back to |max−min| group means when effect is missing.
   */
  public static double differenceMagnitude(GroupStatResult.Row row) {
    if (row == null) {
      return 0;
    }
    if (row.effectVsBaseline() != null && !Double.isNaN(row.effectVsBaseline())) {
      return Math.abs(row.effectVsBaseline());
    }
    GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(row.groups());
    if (extremes != null && extremes.distinct()) {
      double spread = Math.abs(extremes.highest().mean() - extremes.lowest().mean());
      if (!Double.isNaN(spread)) {
        return spread;
      }
    }
    return 0;
  }
}
