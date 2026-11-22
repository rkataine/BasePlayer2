package org.baseplayer.analysis.groupstat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.baseplayer.analysis.AnalysisMode;

/**
 * Plain-text Hits / Diff labels for mutation·SV (polarity + rate) vs Signal (mean extremes).
 */
public final class GroupComparisonDiffFormat {

  private GroupComparisonDiffFormat() {}

  public static String formatHitsPlain(AnalysisMode mode, GroupStatResult.Row row) {
    if (row == null || row.groups() == null || row.groups().isEmpty()) {
      return "—";
    }
    boolean polarity = GroupStatResult.usesPolarityRateDiff(mode);
    StringBuilder sb = new StringBuilder();
    for (GroupStatResult.GroupSummary g : row.groups()) {
      if (g == null) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(" · ");
      }
      sb.append(formatHitsChip(g, polarity));
    }
    return sb.length() == 0 ? "—" : sb.toString();
  }

  public static String formatHitsPlain(GroupStatResult.Row row) {
    return formatHitsPlain(AnalysisMode.MUTATIONS_SV, row);
  }

  /** Collapsed chip: {@code −80%} or {@code 80%} (percent only). */
  public static String formatHitsChip(GroupStatResult.GroupSummary g, boolean withPolarity) {
    if (g == null) {
      return "—";
    }
    String pct = Double.isNaN(g.pctSignal())
        ? "—"
        : String.format(Locale.ROOT, "%.0f%%", g.pctSignal());
    if (!withPolarity) {
      return pct;
    }
    GroupStatResult.Polarity pol = g.dominantPolarity();
    if (pol == GroupStatResult.Polarity.NONE) {
      return pct;
    }
    return pol.glyph() + pct;
  }

  public static String formatHitsTooltip(GroupStatResult.GroupSummary g) {
    if (g == null) {
      return "";
    }
    return g.groupName()
        + " "
        + g.dominantPolarity().word()
        + " — "
        + g.nSignal()
        + "/"
        + g.nTotal()
        + " hits ("
        + (Double.isNaN(g.pctSignal())
            ? "—"
            : String.format(Locale.ROOT, "%.0f%%", g.pctSignal()))
        + "), +"
        + g.nPos()
        + " / −"
        + g.nNeg();
  }

  public static String formatDiffPlain(AnalysisMode mode, GroupStatResult.Row row) {
    if (row == null || row.groups() == null || row.groups().isEmpty()) {
      return "similar";
    }
    if (isSimilar(row)) {
      return "similar";
    }
    if (GroupStatResult.usesPolarityRateDiff(mode)) {
      return formatPolarityRateDiff(row.groups());
    }
    return formatMeanDiff(row.groups());
  }

  /** Backward-compatible: mean extremes (Signal-style). */
  public static String formatDiffPlain(GroupStatResult.Row row) {
    return formatDiffPlain(AnalysisMode.SIGNAL, row);
  }

  /**
   * Mutation/SV Diff: direction split, or the two hit-rate extremes with rates
   * (e.g. {@code group3 −80% · group1 −10%}), plus mixed-group flags.
   */
  private static String formatPolarityRateDiff(List<GroupStatResult.GroupSummary> groups) {
    boolean split = GroupStatResult.hasPolaritySplit(groups);
    StringBuilder sb = new StringBuilder();

    if (split) {
      boolean first = true;
      for (GroupStatResult.GroupSummary g : groups) {
        if (g == null) {
          continue;
        }
        GroupStatResult.Polarity pol = g.dominantPolarity();
        if (pol != GroupStatResult.Polarity.GAIN && pol != GroupStatResult.Polarity.LOSS) {
          continue;
        }
        if (!first) {
          sb.append(" · ");
        }
        first = false;
        sb.append(g.groupName()).append(' ').append(pol.word());
      }
    } else {
      GroupStatResult.HitRateExtremes rates = GroupStatResult.hitRateExtremes(groups);
      if (rates != null && rates.distinct()) {
        sb.append(formatExtremeChip(rates.most()));
        sb.append(" · ");
        sb.append(formatExtremeChip(rates.least()));
      } else {
        // No clear rate contrast — list groups with polarity glyph.
        boolean first = true;
        for (GroupStatResult.GroupSummary g : groups) {
          if (g == null) {
            continue;
          }
          if (!first) {
            sb.append(" · ");
          }
          first = false;
          sb.append(g.groupName());
          GroupStatResult.Polarity pol = g.dominantPolarity();
          if (pol != GroupStatResult.Polarity.NONE) {
            sb.append(' ').append(pol.glyph());
          }
        }
      }
    }

    String mixed = formatMixedFlag(groups);
    if (!mixed.isEmpty()) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(mixed);
    }
    return sb.length() == 0 ? "similar" : sb.toString();
  }

  /** {@code group3 −80%} for Diff extremes. */
  public static String formatExtremeChip(GroupStatResult.GroupSummary g) {
    if (g == null) {
      return "—";
    }
    String pct = Double.isNaN(g.pctSignal())
        ? "—"
        : String.format(Locale.ROOT, "%.0f%%", g.pctSignal());
    GroupStatResult.Polarity pol = g.dominantPolarity();
    if (pol == GroupStatResult.Polarity.NONE) {
      return g.groupName() + " " + pct;
    }
    return g.groupName() + " " + pol.glyph() + pct;
  }

  /** e.g. {@code · mixed in group2} when any group has both signs. */
  public static String formatMixedFlag(List<GroupStatResult.GroupSummary> groups) {
    List<String> mixed = mixedGroupNames(groups);
    if (mixed.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("· mixed in ");
    for (int i = 0; i < mixed.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(mixed.get(i));
    }
    return sb.toString();
  }

  public static List<String> mixedGroupNames(List<GroupStatResult.GroupSummary> groups) {
    List<String> out = new ArrayList<>();
    if (groups == null) {
      return out;
    }
    for (GroupStatResult.GroupSummary g : groups) {
      if (g != null && g.dominantPolarity() == GroupStatResult.Polarity.MIXED) {
        out.add(g.groupName());
      }
    }
    return out;
  }

  private static String formatMeanDiff(List<GroupStatResult.GroupSummary> groups) {
    GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(groups);
    StringBuilder sb = new StringBuilder();
    for (GroupStatResult.GroupSummary g : groups) {
      if (g == null) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(" · ");
      }
      if (extremes != null && extremes.distinct()) {
        if (g.groupId() == extremes.highest().groupId()) {
          sb.append('↑').append(' ');
        } else if (g.groupId() == extremes.lowest().groupId()) {
          sb.append('↓').append(' ');
        }
      }
      sb.append(g.groupName());
    }
    return sb.length() == 0 ? "similar" : sb.toString();
  }

  public static boolean isSimilar(GroupStatResult.Row row) {
    if (row == null) {
      return true;
    }
    String dir = row.direction() != null ? row.direction() : "";
    return "similar".equals(dir) || dir.isBlank();
  }

  public static final String DELTA_TOOLTIP =
      "Effect vs baseline (log2 fold or mean Δ, depending on Effect mode). "
          + "Expand row for per-group means and hit counts.";
}
