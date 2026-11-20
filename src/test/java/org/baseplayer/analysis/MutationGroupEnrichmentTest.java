package org.baseplayer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MutationGroupEnrichmentTest {

  @Test
  void cnvBurdenCountsSignedEventsNotMagnitudeMean() {
    MutationGroupEnrichment.CnvBurden burden = new MutationGroupEnrichment.CnvBurden();
    burden.addEvent(-0.12);
    burden.addEvent(-0.01);
    burden.addEvent(0.05);
    burden.addEvent(0.0); // ignored
    assertEquals(1, burden.gains());
    assertEquals(2, burden.losses());
    // Net events — not mean(-0.12, -0.01, 0.05)
    assertEquals(-1.0, burden.signedEventScore(), 1e-9);
  }

  @Test
  void cnvBurdenStableWhenExtraSameSignEventsAdded() {
    MutationGroupEnrichment.CnvBurden one = new MutationGroupEnrichment.CnvBurden();
    one.addEvent(-0.12);
    assertEquals(-1.0, one.signedEventScore(), 1e-9);

    MutationGroupEnrichment.CnvBurden many = new MutationGroupEnrichment.CnvBurden();
    many.addEvent(-0.12);
    many.addEvent(-0.04);
    many.addEvent(-0.01);
    // More loss events increase |burden|; they do not dilute toward 0 like a mean.
    assertEquals(-3.0, many.signedEventScore(), 1e-9);
  }

  @Test
  void directionalCoherenceRequiresDominantSign() {
    assertTrue(MutationGroupEnrichment.isDirectionallyCoherent(List.of(0.0, 0.0, 0.0)));
    assertTrue(MutationGroupEnrichment.isDirectionallyCoherent(
        List.of(-1.0, -2.0, -1.0, 0.0, 1.0))); // 3/4 losses ≥ 70%
    assertFalse(MutationGroupEnrichment.isDirectionallyCoherent(
        List.of(-1.0, -1.0, 1.0, 1.0))); // 50/50
  }
}
