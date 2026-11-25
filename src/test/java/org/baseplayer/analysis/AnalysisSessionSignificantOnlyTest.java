package org.baseplayer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.groupstat.GroupStatResult;

class AnalysisSessionSignificantOnlyTest {

  @BeforeEach
  void clear() {
    AnalysisSession.get().clear();
  }

  @Test
  void putResultWithFilterOnKeepsSingleSignificantCopy() {
    GroupStatResult.Row pass = new GroupStatResult.Row(
        "A", "A", "1", 1, 2,
        List.of(GroupStatResult.GroupSummary.basic(1, "g1", 5, 2.0, 2.0)),
        1.5, 0.001, 0.01, "t", "up");
    GroupStatResult.Row fail = new GroupStatResult.Row(
        "B", "B", "1", 3, 4,
        List.of(GroupStatResult.GroupSummary.basic(1, "g1", 5, 0.1, 0.1)),
        0.1, 0.5, 0.8, "t", "up");
    GroupStatResult raw = new GroupStatResult(
        AnalysisMode.SIGNAL, List.of(1), null, List.of(pass, fail), "raw");

    DifferenceThresholds thr = new DifferenceThresholds();
    thr.setEnabled(true);
    thr.setShowSimilar(false);
    thr.setShowUp(true);
    thr.setShowDown(true);
    thr.setUseMaxQ(true);
    thr.setUseMinAbsLog2Fc(true);
    thr.setMaxQ(0.05);
    thr.setMinAbsLog2Fc(0.5);

    AnalysisSession.get().putResult(AnalysisMode.SIGNAL, raw, null, thr);

    GroupStatResult stored = AnalysisSession.get().getResult(AnalysisMode.SIGNAL);
    GroupStatResult filtered = AnalysisSession.get().getFilteredResult(AnalysisMode.SIGNAL);
    assertSame(stored, filtered);
    assertEquals(1, stored.rows().size());
    assertEquals("A", stored.rows().get(0).unitId());
  }
}
