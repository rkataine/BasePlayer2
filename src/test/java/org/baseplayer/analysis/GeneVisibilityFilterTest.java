package org.baseplayer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.baseplayer.utils.GeneBiotypeVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class GeneVisibilityFilterTest {

  @BeforeEach
  @AfterEach
  void resetVisibility() {
    GeneBiotypeVisibility.get().setAllVisible(true);
  }

  @Test
  void looksLikeLocusId() {
    assertTrue(GeneVisibilityFilter.looksLikeLocusId("chr1:100-200"));
    assertTrue(GeneVisibilityFilter.looksLikeLocusId("1:10-20"));
    assertFalse(GeneVisibilityFilter.looksLikeLocusId("TP53"));
    assertFalse(GeneVisibilityFilter.looksLikeLocusId("chr1"));
  }

  @Test
  void applyKeepsLocusRowsWhenGeneTypesHidden() {
    GeneBiotypeVisibility vis = GeneBiotypeVisibility.get();
    for (GeneBiotypeVisibility.Category c : GeneBiotypeVisibility.Category.values()) {
      vis.setVisible(c, false);
    }

    GroupStatResult.Row locus = row("chr1:100-200", "chr1:100-200");
    GroupStatResult.Row blankGene = row("feature-1", "");
    // Blank geneName falls back to unitId — locus-shaped unitIds stay visible.
    GroupStatResult.Row locusUnitOnly = row("2:50-60", "");
    GroupStatResult input = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(1), null, List.of(locus, blankGene, locusUnitOnly), "in");

    GroupStatResult out = GeneVisibilityFilter.apply(input);
    // blankGene → unitId "feature-1" classified as OTHER → hidden when all off
    assertEquals(2, out.rows().size());
    assertEquals("chr1:100-200", out.rows().get(0).unitId());
    assertEquals("2:50-60", out.rows().get(1).unitId());
  }

  @Test
  void applyNoOpWhenAllVisible() {
    GroupStatResult.Row a = row("TP53", "TP53");
    GroupStatResult input = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(1), null, List.of(a), "in");
    assertSame(input, GeneVisibilityFilter.apply(input));
  }

  private static GroupStatResult.Row row(String unitId, String geneName) {
    return new GroupStatResult.Row(
        unitId,
        geneName,
        "1",
        1,
        10,
        List.of(GroupStatResult.GroupSummary.basic(1, "g1", 5, 1.0, 1.0)),
        1.0,
        0.01,
        0.02,
        "t",
        "up");
  }
}
