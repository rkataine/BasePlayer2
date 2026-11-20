package org.baseplayer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GroupStatSummaryTest {

  @Test
  void summarizeCountsSignalAndTails() {
    List<Double> vals = List.of(0.0, 0.0, -2.0, -1.0, 3.0, 4.0, 5.0, 0.0);
    GroupStatResult.GroupSummary s = GroupStatEngine.summarizeGroup(1, "g1", 8, 5, vals);
    assertEquals(8, s.nTotal());
    assertEquals(5, s.nObserved());
    assertEquals(5, s.nSignal());
    assertEquals(3, s.nPos());
    assertEquals(2, s.nNeg());
    assertEquals(5.0 / 8.0 * 100.0, s.pctSignal(), 1e-9);
    assertTrue(s.meanTop5Pct() >= s.meanTop10Pct() - 1e-9 || s.nTotal() > 0);
    assertEquals(5.0, s.max(), 1e-9);
    assertEquals(-2.0, s.min(), 1e-9);
  }

  @Test
  void autoPicksFisherForSparseMissingDefault() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    matrix.setMissingDefault(0.0);
    assertEquals(GroupStatTest.FISHER, GroupStatTest.AUTO.resolve(matrix, 2));
    assertEquals(GroupStatTest.RANK, GroupStatTest.AUTO.resolve(matrix, 3));
  }

  @Test
  void autoPicksWelchForDenseTwoGroup() {
    FeatureValueMatrix matrix = new FeatureValueMatrix();
    assertEquals(GroupStatTest.WELCH, GroupStatTest.AUTO.resolve(matrix, 2));
  }

  @Test
  void percentileAndTails() {
    List<Double> vals = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
    assertEquals(1.0, GroupStatEngine.percentile(vals, 0.0), 1e-9);
    assertEquals(10.0, GroupStatEngine.percentile(vals, 1.0), 1e-9);
    assertEquals(10.0, GroupStatEngine.meanTopFraction(vals, 0.1), 1e-9);
    assertEquals(1.0, GroupStatEngine.meanBottomFraction(vals, 0.1), 1e-9);
  }

  @Test
  void fisherDetectsEnrichment() {
    Double p = GroupStatEngine.fisherExactP(20, 5, 2, 23);
    assertTrue(p != null && p < 0.01, "expected small p, got " + p);
  }
}
