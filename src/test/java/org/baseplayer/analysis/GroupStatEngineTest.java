package org.baseplayer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class GroupStatEngineTest {

  @Test
  void welchDetectsMeanDifference() {
    List<Double> a = List.of(1.0, 1.1, 0.9, 1.05, 0.95);
    List<Double> b = List.of(5.0, 5.2, 4.8, 5.1, 4.9);
    Double p = GroupStatEngine.welchP(a, b);
    assertNotNull(p);
    assertTrue(p < 0.01, "expected small p, got " + p);
  }

  @Test
  void bhMonotonic() {
    List<GroupStatResult.Row> rows = new ArrayList<>();
    rows.add(row(0.001));
    rows.add(row(0.04));
    rows.add(row(0.2));
    GroupStatEngine.applyBenjaminiHochberg(rows);
    assertNotNull(rows.get(0).qValue());
    assertTrue(rows.get(0).qValue() <= rows.get(1).qValue() + 1e-12);
    assertTrue(rows.get(1).qValue() <= rows.get(2).qValue() + 1e-12);
  }

  @Test
  void meanAndMedian() {
    assertEquals(2.0, GroupStatEngine.mean(List.of(1.0, 2.0, 3.0)), 1e-9);
    assertEquals(2.0, GroupStatEngine.median(List.of(1.0, 2.0, 3.0)), 1e-9);
    assertEquals(2.5, GroupStatEngine.median(List.of(1.0, 2.0, 3.0, 4.0)), 1e-9);
  }

  private static GroupStatResult.Row row(double p) {
    return new GroupStatResult.Row(
        "g", "g", "1", 1, 10, List.of(), null, p, null, "t", "up");
  }
}
