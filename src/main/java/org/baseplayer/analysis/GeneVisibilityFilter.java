package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.baseplayer.utils.GeneBiotypeVisibility;
import org.baseplayer.analysis.groupstat.GroupStatResult;

/**
 * Filters {@link GroupStatResult} rows by {@link GeneBiotypeVisibility}
 * (Genome sidebar gene-type legend, including COSMIC-only).
 */
public final class GeneVisibilityFilter {

  private GeneVisibilityFilter() {}

  public static GroupStatResult apply(GroupStatResult result) {
    if (result == null) {
      return null;
    }
    GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
    List<GroupStatResult.Row> kept = new ArrayList<>();
    int hidden = 0;
    for (GroupStatResult.Row row : result.rows()) {
      if (passes(row, visibility)) {
        kept.add(row);
      } else {
        hidden++;
      }
    }
    if (hidden == 0) {
      return result;
    }
    String summary = String.format(
        Locale.ROOT,
        "%s — %d / %d rows (gene types)",
        result.summary(),
        kept.size(),
        result.rows().size());
    return new GroupStatResult(
        result.mode(),
        result.groupIds(),
        result.baselineGroupId(),
        kept,
        summary,
        result.methodStrip());
  }

  static boolean passes(GroupStatResult.Row row, GeneBiotypeVisibility visibility) {
    if (row == null) {
      return false;
    }
    if (visibility == null) {
      return true;
    }
    String name = row.geneName();
    if (name == null || name.isBlank()) {
      // Prefer unit id for gene-unit rows that only populated unitId.
      name = row.unitId();
    }
    if (name == null || name.isBlank()) {
      return true;
    }
    // Window / region units are not gene-typed — keep them.
    if (looksLikeLocusId(name)) {
      return true;
    }
    return visibility.isVisibleGeneName(name);
  }

  /** Heuristic for {@code chr:start-end} style unit ids. */
  static boolean looksLikeLocusId(String id) {
    if (id == null) {
      return false;
    }
    int colon = id.indexOf(':');
    if (colon <= 0) {
      return false;
    }
    int dash = id.indexOf('-', colon + 1);
    return dash > colon + 1 && dash + 1 < id.length();
  }
}
