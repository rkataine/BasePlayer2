package org.baseplayer.analysis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GroupStatFormatTest {

  @Test
  void formatPUnitIntervalAndClarity() {
    assertEquals("—", GroupStatResultTable.formatP(null));
    assertEquals("—", GroupStatResultTable.formatP(Double.NaN));
    // Numerical CDF noise: clamp into [0,1] instead of blanking the cell.
    assertEquals("0", GroupStatResultTable.formatP(-0.01));
    assertEquals("1.0000", GroupStatResultTable.formatP(1.5));
    assertEquals("0", GroupStatResultTable.formatP(0.0));
    assertEquals("0.1234", GroupStatResultTable.formatP(0.1234));
    assertEquals("0.0001", GroupStatResultTable.formatP(1e-4));

    String tiny = GroupStatResultTable.formatP(1e-5);
    assertTrue(tiny.contains("×10^"), tiny);
    assertFalse(tiny.startsWith("-"), "must not look like a negative q: " + tiny);
    assertEquals("1.0×10^-5", tiny);
  }
}
