package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SomaticSampleNameDetectTest {

  @Test
  void hasTokenMatchesNkTkWithBoundaries() {
    assertTrue(VariantLoader.hasToken("fam_c390_1_7484nk_t", "nk"));
    assertTrue(VariantLoader.hasToken("fam_c390_1_7485tk_t", "tk"));
    assertFalse(VariantLoader.hasToken("fam_c390_1_7485tk_t", "nk"));
    assertFalse(VariantLoader.hasToken("unknown", "nk"));
    assertFalse(VariantLoader.hasToken("thinking", "nk"));
  }
}
