package org.baseplayer.analysis.grid;

import java.util.List;
import java.util.Set;

import org.baseplayer.samples.SampleTrack;

/**
 * Read-only gene/unit × sample numeric grid for Signal annotate / compare / group stats.
 * Gene matrices use {@link CompactTableGrid} over {@code MappedTableData} (SSOT with canvas).
 * Derived collects (BigWig, BED collapse, …) use {@link FeatureValueMatrix}.
 */
public interface ValueGrid {

  Set<String> unitIds();

  FeatureValueMatrix.Locus locusOf(String unitId);

  /** Explicit finite value, or {@code null} when missing / masked. */
  Double get(String unitId, SampleTrack track);

  boolean hasValue(String unitId, SampleTrack track);

  int observedCount(String unitId, List<SampleTrack> sampleTracks);

  List<Double> valuesFor(String unitId, List<SampleTrack> sampleTracks);

  Set<SampleTrack> tracks();

  int size();

  boolean isEmpty();
}
