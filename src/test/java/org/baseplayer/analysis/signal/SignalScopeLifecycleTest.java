package org.baseplayer.analysis.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.baseplayer.analysis.GenomicScope;

class SignalScopeLifecycleTest {

  @Test
  void signalSourceSpecDefaultsToCurrentChromosome() {
    SignalSourceSpec spec = new SignalSourceSpec();
    assertEquals(GenomicScope.CURRENT_CHROM, spec.getScope());
  }

  @Test
  void annotateAllOverrideIsNotClobberedByDefaultScope() {
    // Mimic workspace buildSpecForScope: resolve defaults CURRENT, then set requested scope.
    SignalSourceSpec resolved = new SignalSourceSpec();
    assertEquals(GenomicScope.CURRENT_CHROM, resolved.getScope());

    resolved.setScope(GenomicScope.ALL_CHROMS);
    assertEquals(GenomicScope.ALL_CHROMS, resolved.getScope());

    SignalSourceSpec currentOnly = new SignalSourceSpec();
    currentOnly.setScope(GenomicScope.CURRENT_CHROM);
    assertEquals(GenomicScope.CURRENT_CHROM, currentOnly.getScope());
  }
}
