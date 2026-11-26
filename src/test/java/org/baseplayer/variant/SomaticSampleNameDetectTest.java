package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  @Test
  void customTumorPatternUsesSampleFilterWildcards() {
    assertEquals(
        "sample_tk",
        VariantLoader.tumorFromCustomPattern("sample_nk", "sample_tk", "*tk*"));
    assertEquals(
        "PATIENT_T",
        VariantLoader.tumorFromCustomPattern("PATIENT_N", "PATIENT_T", "*_T"));
    assertNull(VariantLoader.tumorFromCustomPattern("a_tk", "b_tk", "*tk*"));
    assertNull(VariantLoader.tumorFromCustomPattern("a", "b", "*zz*"));
  }

  @Test
  void guessTumorFromNamesPrefersCustomPatternOverBuiltins() {
    assertEquals(
        "weird_X",
        VariantLoader.guessTumorFromNames("weird_X", "tumor_sample", "*_X"));
    assertEquals(
        "sample_tumor",
        VariantLoader.guessTumorFromNames("sample_normal", "sample_tumor", ""));
  }
}
