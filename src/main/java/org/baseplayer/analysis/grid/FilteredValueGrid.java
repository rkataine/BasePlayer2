package org.baseplayer.analysis.grid;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.baseplayer.samples.SampleTrack;

/** Unit-id subset view over a {@link ValueGrid} (Sample Comparison keep-set). */
public final class FilteredValueGrid implements ValueGrid {

  private final ValueGrid inner;
  private final Set<String> unitIds;

  public FilteredValueGrid(ValueGrid inner, Set<String> unitIds) {
    this.inner = inner;
    this.unitIds = Collections.unmodifiableSet(unitIds);
  }

  @Override
  public Set<String> unitIds() {
    return unitIds;
  }

  @Override
  public FeatureValueMatrix.Locus locusOf(String unitId) {
    return unitIds.contains(unitId) ? inner.locusOf(unitId) : null;
  }

  @Override
  public Double get(String unitId, SampleTrack track) {
    return unitIds.contains(unitId) ? inner.get(unitId, track) : null;
  }

  @Override
  public boolean hasValue(String unitId, SampleTrack track) {
    return unitIds.contains(unitId) && inner.hasValue(unitId, track);
  }

  @Override
  public int observedCount(String unitId, List<SampleTrack> sampleTracks) {
    return unitIds.contains(unitId) ? inner.observedCount(unitId, sampleTracks) : 0;
  }

  @Override
  public List<Double> valuesFor(String unitId, List<SampleTrack> sampleTracks) {
    return unitIds.contains(unitId) ? inner.valuesFor(unitId, sampleTracks) : List.of();
  }

  @Override
  public Set<SampleTrack> tracks() {
    return inner.tracks();
  }

  @Override
  public int size() {
    return unitIds.size();
  }

  @Override
  public boolean isEmpty() {
    return unitIds.isEmpty();
  }
}
