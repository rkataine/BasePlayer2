package org.baseplayer.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranked group-comparison results for one analysis run.
 */
public final class GroupStatResult {

  /**
   * Per-group support and distribution summaries (universal across multiomics modes).
   * {@link #n()} is an alias for {@link #nTotal()}.
   */
  public record GroupSummary(
      int groupId,
      String groupName,
      int nTotal,
      int nObserved,
      int nSignal,
      int nPos,
      int nNeg,
      double mean,
      double median,
      double meanSignal,
      double min,
      double max,
      double sd,
      double p10,
      double p90,
      double p95,
      double meanTop10Pct,
      double meanTop5Pct,
      double meanBottom10Pct,
      double meanBottom5Pct) {

    /** Group roster size (alias for {@link #nTotal()}). */
    public int n() {
      return nTotal;
    }

    public double pctSignal() {
      return nTotal > 0 ? 100.0 * nSignal / nTotal : Double.NaN;
    }

    /** Compact constructor for tests / simple binary summaries. */
    public static GroupSummary basic(
        int groupId, String groupName, int n, double mean, double median) {
      return new GroupSummary(
          groupId,
          groupName,
          n,
          n,
          n,
          0,
          0,
          mean,
          median,
          mean,
          mean,
          mean,
          0,
          mean,
          mean,
          mean,
          mean,
          mean,
          mean,
          mean);
    }
  }

  /** Highest / lowest group means for a comparison row (NaN means skipped). */
  public record MeanExtremes(GroupSummary highest, GroupSummary lowest) {
    public boolean distinct() {
      return highest != null
          && lowest != null
          && highest.groupId() != lowest.groupId();
    }
  }

  public record Row(
      String unitId,
      String geneName,
      String chromosome,
      long start1Based,
      long end1Based,
      List<GroupSummary> groups,
      Double effectVsBaseline,
      Double pValue,
      Double qValue,
      String testName,
      String direction) {}

  /** Highest / lowest group means; null when fewer than two usable means. */
  public static MeanExtremes meanExtremes(List<GroupSummary> groups) {
    if (groups == null || groups.isEmpty()) {
      return null;
    }
    GroupSummary highest = null;
    GroupSummary lowest = null;
    for (GroupSummary g : groups) {
      if (g == null || g.nTotal() <= 0 || Double.isNaN(g.mean())) {
        continue;
      }
      if (highest == null || g.mean() > highest.mean()) {
        highest = g;
      }
      if (lowest == null || g.mean() < lowest.mean()) {
        lowest = g;
      }
    }
    if (highest == null || lowest == null) {
      return null;
    }
    return new MeanExtremes(highest, lowest);
  }

  private final AnalysisMode mode;
  private final List<Integer> groupIds;
  private final Integer baselineGroupId;
  private final List<Row> rows;
  private final String summary;
  private final long createdAtMs;
  private final String methodStrip;

  public GroupStatResult(
      AnalysisMode mode,
      List<Integer> groupIds,
      Integer baselineGroupId,
      List<Row> rows,
      String summary) {
    this(mode, groupIds, baselineGroupId, rows, summary, null);
  }

  public GroupStatResult(
      AnalysisMode mode,
      List<Integer> groupIds,
      Integer baselineGroupId,
      List<Row> rows,
      String summary,
      String methodStrip) {
    this.mode = mode;
    this.groupIds = groupIds != null ? List.copyOf(groupIds) : List.of();
    this.baselineGroupId = baselineGroupId;
    this.rows = rows != null ? List.copyOf(rows) : List.of();
    this.summary = summary != null ? summary : "";
    this.methodStrip = methodStrip != null ? methodStrip : "";
    this.createdAtMs = System.currentTimeMillis();
  }

  public AnalysisMode mode() {
    return mode;
  }

  public List<Integer> groupIds() {
    return groupIds;
  }

  public Integer baselineGroupId() {
    return baselineGroupId;
  }

  public List<Row> rows() {
    return rows;
  }

  public String summary() {
    return summary;
  }

  /** One-line method description for UI / Excel meta. */
  public String methodStrip() {
    return methodStrip;
  }

  public long createdAtMs() {
    return createdAtMs;
  }

  /** Map unitId → row for canvas lookups. */
  public Map<String, Row> indexByUnit() {
    Map<String, Row> map = new LinkedHashMap<>();
    for (Row row : rows) {
      map.put(row.unitId(), row);
    }
    return Collections.unmodifiableMap(map);
  }
}
