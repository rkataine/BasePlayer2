package org.baseplayer.analysis.groupstat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.AnalysisMode;

class GroupComparisonDiffFormatTest {

  @Test
  void allLossDifferentRatesShowsExplicitExtremesNotMostLeast() {
    // Sparse-loss group has higher mean (−0.1 vs −0.8) — old Diff would ↑ that group.
    GroupStatResult.GroupSummary g3 =
        GroupStatResult.GroupSummary.withPolarity(3, "group3", 10, 0, 8, -0.8);
    GroupStatResult.GroupSummary g2 =
        GroupStatResult.GroupSummary.withPolarity(2, "group2", 10, 0, 4, -0.4);
    GroupStatResult.GroupSummary g1 =
        GroupStatResult.GroupSummary.withPolarity(1, "group1", 10, 0, 1, -0.1);

    GroupStatResult.Row row = row("GENE", List.of(g3, g2, g1), "different");
    String diff = GroupComparisonDiffFormat.formatDiffPlain(AnalysisMode.MUTATIONS_SV, row);

    assertFalse(diff.contains("↑"), diff);
    assertFalse(diff.contains("↓"), diff);
    assertFalse(diff.contains("most"), diff);
    assertFalse(diff.contains("least"), diff);
    assertTrue(diff.contains("group3 −80%"), diff);
    assertTrue(diff.contains("group1 −10%"), diff);
    assertTrue(diff.indexOf("group3") < diff.indexOf("group1"), diff);
    assertFalse(diff.contains("group2"), "middle group omitted from Diff extremes: " + diff);
  }

  @Test
  void polaritySplitHeadlinesGainVsLoss() {
    GroupStatResult.GroupSummary losses =
        GroupStatResult.GroupSummary.withPolarity(3, "group3", 10, 0, 7, -0.7);
    GroupStatResult.GroupSummary gains =
        GroupStatResult.GroupSummary.withPolarity(1, "group1", 10, 6, 0, 0.6);

    GroupStatResult.Row row = row("GENE", List.of(losses, gains), "different");
    String diff = GroupComparisonDiffFormat.formatDiffPlain(AnalysisMode.MUTATIONS_SV, row);

    assertTrue(GroupStatResult.hasPolaritySplit(row.groups()));
    assertTrue(diff.contains("loss"), diff);
    assertTrue(diff.contains("gain"), diff);
    assertFalse(diff.contains("most"), diff);
    assertFalse(diff.contains("↑"), diff);
  }

  @Test
  void hitsChipIsPolarityAndPercentOnly() {
    GroupStatResult.GroupSummary g =
        GroupStatResult.GroupSummary.withPolarity(3, "group3", 10, 0, 8, -0.8);
    assertEquals("−80%", GroupComparisonDiffFormat.formatHitsChip(g, true));
    assertEquals("80%", GroupComparisonDiffFormat.formatHitsChip(g, false));
  }

  @Test
  void mixedWithinGroupIsFlaggedOnDiff() {
    GroupStatResult.GroupSummary mixed =
        GroupStatResult.GroupSummary.withPolarity(2, "group2", 10, 3, 3, 0.0);
    GroupStatResult.GroupSummary losses =
        GroupStatResult.GroupSummary.withPolarity(3, "group3", 10, 0, 8, -0.8);
    GroupStatResult.Row row = row("GENE", List.of(losses, mixed), "different");

    assertEquals(GroupStatResult.Polarity.MIXED, mixed.dominantPolarity());
    String diff = GroupComparisonDiffFormat.formatDiffPlain(AnalysisMode.MUTATIONS_SV, row);
    assertTrue(diff.contains("mixed in group2"), diff);
    assertEquals("±60%", GroupComparisonDiffFormat.formatHitsChip(mixed, true));
  }

  @Test
  void signalModeKeepsMeanArrows() {
    GroupStatResult.GroupSummary high =
        GroupStatResult.GroupSummary.withPolarity(1, "hi", 10, 5, 0, 2.0);
    GroupStatResult.GroupSummary low =
        GroupStatResult.GroupSummary.withPolarity(2, "lo", 10, 0, 5, -2.0);
    GroupStatResult.Row row = row("W", List.of(high, low), "different");

    String diff = GroupComparisonDiffFormat.formatDiffPlain(AnalysisMode.SIGNAL, row);
    assertTrue(diff.contains("↑"), diff);
    assertTrue(diff.contains("↓"), diff);
    assertTrue(diff.contains("hi") && diff.contains("lo"), diff);
  }

  @Test
  void similarDirectionStaysSimilar() {
    GroupStatResult.GroupSummary g1 =
        GroupStatResult.GroupSummary.withPolarity(1, "a", 10, 0, 5, -0.5);
    GroupStatResult.GroupSummary g2 =
        GroupStatResult.GroupSummary.withPolarity(2, "b", 10, 0, 8, -0.8);
    GroupStatResult.Row row = row("GENE", List.of(g1, g2), "similar");
    assertEquals(
        "similar",
        GroupComparisonDiffFormat.formatDiffPlain(AnalysisMode.MUTATIONS_SV, row));
  }

  private static GroupStatResult.Row row(
      String gene, List<GroupStatResult.GroupSummary> groups, String direction) {
    return new GroupStatResult.Row(
        gene, gene, "1", 1, 100, groups, 0.5, 0.01, 0.02, "Welch", direction);
  }
}
