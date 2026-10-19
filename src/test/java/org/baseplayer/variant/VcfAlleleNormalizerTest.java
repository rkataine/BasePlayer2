package org.baseplayer.variant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VcfAlleleNormalizerTest {

  @Test
  void deletionStripsPaddingBase() {
    var n = VcfAlleleNormalizer.normalize(100, "CT", "C");
    assertEquals(101, n.position());
    assertEquals("T", n.ref());
    assertEquals("-", n.alt());
  }

  @Test
  void multiBaseDeletion() {
    var n = VcfAlleleNormalizer.normalize(50, "ACGT", "A");
    assertEquals(51, n.position());
    assertEquals("CGT", n.ref());
    assertEquals("-", n.alt());
  }

  @Test
  void insertionKeepsAnchorPosition() {
    var n = VcfAlleleNormalizer.normalize(100, "C", "CT");
    // Stay on the VCF anchor so Position matches codon/HGVS (not POS+1).
    assertEquals(100, n.position());
    assertEquals("-", n.ref());
    assertEquals("T", n.alt());
  }

  @Test
  void snvUnchanged() {
    var n = VcfAlleleNormalizer.normalize(200, "A", "G");
    assertEquals(200, n.position());
    assertEquals("A", n.ref());
    assertEquals("G", n.alt());
  }
}
