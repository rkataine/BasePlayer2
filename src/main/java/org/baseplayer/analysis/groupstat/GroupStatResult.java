package org.baseplayer.analysis.groupstat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.signal.SignalChannel;

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

    /**
     * Dominant sign among non-zero samples: gain (+) vs loss (−).
     * Mixed whenever both signs are present (even if one side is larger);
     * none when no signal.
     */
    public Polarity dominantPolarity() {
      if (nPos <= 0 && nNeg <= 0) {
        return Polarity.NONE;
      }
      if (nPos > 0 && nNeg > 0) {
        return Polarity.MIXED;
      }
      return nPos > 0 ? Polarity.GAIN : Polarity.LOSS;
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

    /** Test helper with explicit gain/loss hit counts. */
    public static GroupSummary withPolarity(
        int groupId,
        String groupName,
        int nTotal,
        int nPos,
        int nNeg,
        double mean) {
      int nSignal = Math.max(0, nPos) + Math.max(0, nNeg);
      return new GroupSummary(
          groupId,
          groupName,
          nTotal,
          nSignal,
          nSignal,
          Math.max(0, nPos),
          Math.max(0, nNeg),
          mean,
          mean,
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

  /** Gain / loss / mixed / none among non-zero samples in a group. */
  public enum Polarity {
    GAIN,
    LOSS,
    MIXED,
    NONE;

    public String glyph() {
      return switch (this) {
        case GAIN -> "+";
        case LOSS -> "−";
        case MIXED -> "±";
        case NONE -> "·";
      };
    }

    public String word() {
      return switch (this) {
        case GAIN -> "gain";
        case LOSS -> "loss";
        case MIXED -> "mixed";
        case NONE -> "none";
      };
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

  /** Highest / lowest hit-rate groups (pctSignal). */
  public record HitRateExtremes(GroupSummary most, GroupSummary least) {
    public boolean distinct() {
      if (most == null || least == null || most.groupId() == least.groupId()) {
        return false;
      }
      double a = most.pctSignal();
      double b = least.pctSignal();
      if (Double.isNaN(a) || Double.isNaN(b)) {
        return false;
      }
      return Math.abs(a - b) > 0.5; // > 0.5 percentage points
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

  /** Highest / lowest hit-rate groups; null when fewer than two usable rates. */
  public static HitRateExtremes hitRateExtremes(List<GroupSummary> groups) {
    if (groups == null || groups.isEmpty()) {
      return null;
    }
    GroupSummary most = null;
    GroupSummary least = null;
    for (GroupSummary g : groups) {
      if (g == null || g.nTotal() <= 0 || Double.isNaN(g.pctSignal())) {
        continue;
      }
      if (most == null || g.pctSignal() > most.pctSignal()) {
        most = g;
      }
      if (least == null || g.pctSignal() < least.pctSignal()) {
        least = g;
      }
    }
    if (most == null || least == null) {
      return null;
    }
    return new HitRateExtremes(most, least);
  }

  /**
   * True when at least two groups have opposing dominant polarities (gain vs loss).
   * Mixed/none groups are ignored for the split check.
   */
  public static boolean hasPolaritySplit(List<GroupSummary> groups) {
    if (groups == null || groups.isEmpty()) {
      return false;
    }
    Polarity seen = null;
    for (GroupSummary g : groups) {
      if (g == null) {
        continue;
      }
      Polarity p = g.dominantPolarity();
      if (p == Polarity.NONE || p == Polarity.MIXED) {
        continue;
      }
      if (seen == null) {
        seen = p;
      } else if (seen != p) {
        return true;
      }
    }
    return false;
  }

  /** Mutation / SV modes use polarity + hit-rate Diff chrome (not mean ↑/↓). */
  public static boolean usesPolarityRateDiff(AnalysisMode mode) {
    return mode == AnalysisMode.MUTATIONS_POINT || mode == AnalysisMode.MUTATIONS_SV;
  }

  private final AnalysisMode mode;
  private final List<Integer> groupIds;
  private final Integer baselineGroupId;
  private final List<Row> rows;
  private final String summary;
  private final long createdAtMs;
  private final String methodStrip;
  /** Signal multi-channel annotation metadata (empty for other modes). */
  private final List<SignalChannel> signalChannels;
  /** unitId → channelId → cohort/sample mean for annotation columns. */
  private final Map<String, Map<String, Double>> channelValuesByUnit;

  public GroupStatResult(
      AnalysisMode mode,
      List<Integer> groupIds,
      Integer baselineGroupId,
      List<Row> rows,
      String summary) {
    this(mode, groupIds, baselineGroupId, rows, summary, null, List.of(), Map.of());
  }

  public GroupStatResult(
      AnalysisMode mode,
      List<Integer> groupIds,
      Integer baselineGroupId,
      List<Row> rows,
      String summary,
      String methodStrip) {
    this(mode, groupIds, baselineGroupId, rows, summary, methodStrip, List.of(), Map.of());
  }

  public GroupStatResult(
      AnalysisMode mode,
      List<Integer> groupIds,
      Integer baselineGroupId,
      List<Row> rows,
      String summary,
      String methodStrip,
      List<SignalChannel> signalChannels,
      Map<String, Map<String, Double>> channelValuesByUnit) {
    this.mode = mode;
    this.groupIds = groupIds != null ? List.copyOf(groupIds) : List.of();
    this.baselineGroupId = baselineGroupId;
    this.rows = rows != null ? List.copyOf(rows) : List.of();
    this.summary = summary != null ? summary : "";
    this.methodStrip = methodStrip != null ? methodStrip : "";
    this.signalChannels = signalChannels != null ? List.copyOf(signalChannels) : List.of();
    this.channelValuesByUnit = channelValuesByUnit != null
        ? Map.copyOf(channelValuesByUnit)
        : Map.of();
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

  public List<SignalChannel> signalChannels() {
    return signalChannels;
  }

  public boolean hasSignalChannels() {
    return !signalChannels.isEmpty();
  }

  public Map<String, Map<String, Double>> channelValuesByUnit() {
    return channelValuesByUnit;
  }

  public Double channelValue(String unitId, String channelId) {
    if (unitId == null || channelId == null) {
      return null;
    }
    Map<String, Double> row = channelValuesByUnit.get(unitId);
    if (row == null) {
      return null;
    }
    Double v = row.get(channelId);
    return v != null && Double.isFinite(v) ? v : null;
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
