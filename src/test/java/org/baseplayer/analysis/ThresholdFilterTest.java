package org.baseplayer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class ThresholdFilterTest {

  @Test
  void filtersByEffectAndQ() {
    DifferenceThresholds thr = new DifferenceThresholds();
    thr.setEnabled(true);
    thr.setMinAbsLog2Fc(0.5);
    thr.setMaxQ(0.05);
    thr.setMinNPerGroup(2);

    GroupStatResult.Row pass = row("A", 1.0, 0.01, 3, "up", 0.0, 1.0);
    GroupStatResult.Row failFc = row("B", 0.1, 0.01, 3, "up", 0.0, 0.1);
    GroupStatResult.Row failQ = row("C", 1.0, 0.2, 3, "up", 0.0, 1.0);
    GroupStatResult.Row failN = row("D", 1.0, 0.01, 1, "up", 0.0, 1.0);

    assertTrue(ThresholdFilter.passes(pass, thr));
    assertFalse(ThresholdFilter.passes(failFc, thr));
    assertFalse(ThresholdFilter.passes(failQ, thr));
    assertFalse(ThresholdFilter.passes(failN, thr));

    GroupStatResult result = new GroupStatResult(
        AnalysisMode.SIGNAL,
        List.of(1, 2),
        1,
        List.of(pass, failFc, failQ, failN),
        "test");
    GroupStatResult filtered = ThresholdFilter.apply(result, thr);
    assertEquals(1, filtered.rows().size());
    assertEquals("A", filtered.rows().get(0).unitId());
  }

  @Test
  void minDeltaUsesEffectVsBaseline() {
    DifferenceThresholds thr = new DifferenceThresholds();
    thr.setEnabled(true);
    thr.setMinAbsLog2Fc(0.5);
    thr.setMaxQ(1.0);
    thr.setMinNPerGroup(2);

    // Large effect passes even if group-mean spread is tiny.
    GroupStatResult.Row largeEffect = row("large", 2.0, 0.01, 3, "different", 0.00, 0.01);
    assertTrue(ThresholdFilter.passes(largeEffect, thr));
    assertEquals(2.0, ThresholdFilter.differenceMagnitude(largeEffect), 1e-12);

    GroupStatResult.Row smallEffect = row("small", 0.2, 0.01, 3, "different", 0.0, 0.8);
    assertFalse(ThresholdFilter.passes(smallEffect, thr));
    assertEquals(0.2, ThresholdFilter.differenceMagnitude(smallEffect), 1e-12);
  }

  @Test
  void keepsTopNByDifferenceMagnitudeWhenFilterEnabled() {
    DifferenceThresholds thr = new DifferenceThresholds();
    thr.setEnabled(true);
    thr.setMinAbsLog2Fc(0);
    thr.setMaxQ(1.0);
    thr.setMinNPerGroup(1);
    thr.setShowUp(true);
    thr.setShowDown(true);
    thr.setShowSimilar(true);
    thr.setMaxRows(2);

    GroupStatResult.Row strong = row("A", 3.0, 0.2, 3, "up", 0.0, 3.0);
    GroupStatResult.Row mid = row("B", 1.0, 0.01, 3, "up", 0.0, 1.0);
    GroupStatResult.Row weak = row("C", 0.2, 0.001, 3, "up", 0.0, 0.2);
    GroupStatResult result = new GroupStatResult(
        AnalysisMode.SIGNAL,
        List.of(1, 2),
        1,
        List.of(weak, mid, strong),
        "test");
    GroupStatResult filtered = ThresholdFilter.apply(result, thr);
    assertEquals(2, filtered.rows().size());
    assertEquals("A", filtered.rows().get(0).unitId());
    assertEquals("B", filtered.rows().get(1).unitId());
  }

  @Test
  void filterOffShowsAllRows() {
    DifferenceThresholds thr = new DifferenceThresholds();
    thr.setEnabled(false);
    thr.setShowUp(true);
    thr.setShowDown(false);
    thr.setShowSimilar(false);
    thr.setMaxRows(1);

    GroupStatResult.Row up = row("A", 1.0, 0.01, 3, "up");
    GroupStatResult.Row down = row("B", -1.0, 0.01, 3, "down");
    GroupStatResult result = new GroupStatResult(
        AnalysisMode.SIGNAL,
        List.of(1, 2),
        1,
        List.of(up, down),
        "test");
    GroupStatResult filtered = ThresholdFilter.apply(result, thr);
    assertEquals(2, filtered.rows().size());
  }

  private static GroupStatResult.Row row(String id, double effect, double q, int n, String direction) {
    return row(id, effect, q, n, direction, 1.0, 2.0);
  }

  private static GroupStatResult.Row row(
      String id,
      double effect,
      double q,
      int n,
      String direction,
      double meanA,
      double meanB) {
    return new GroupStatResult.Row(
        id,
        id,
        "1",
        1,
        100,
        List.of(
            GroupStatResult.GroupSummary.basic(1, "A", n, meanA, meanA),
            GroupStatResult.GroupSummary.basic(2, "B", n, meanB, meanB)),
        effect,
        q,
        q,
        "t",
        direction);
  }
}
