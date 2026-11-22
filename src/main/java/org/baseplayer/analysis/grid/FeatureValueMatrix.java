package org.baseplayer.analysis.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.analysis.ValueAggregation;

/**
 * Sparse gene/site × sample matrix of numeric values (expression, methylation, …).
 * Optional {@link #setMissingDefault(Double)} fills absent sample cells on read
 * without densifying storage (important for CNV/mutation group scans).
 */
public final class FeatureValueMatrix implements ValueGrid {

  /** Optional locus metadata for a matrix row (for navigation / canvas). */
  public record Locus(String chromosome, long start1Based, long end1Based) {}

  private final Map<String, Map<SampleTrack, Double>> values = new LinkedHashMap<>();
  private final Map<String, Locus> loci = new LinkedHashMap<>();
  private final Set<SampleTrack> tracks = Collections.newSetFromMap(new IdentityHashMap<>());
  /** When non-null, {@link #valuesFor} returns this for missing sample cells. */
  private Double missingDefault;

  public void setMissingDefault(Double missingDefault) {
    this.missingDefault = missingDefault;
  }

  public Double getMissingDefault() {
    return missingDefault;
  }

  public void put(String unitId, SampleTrack track, double value) {
    if (unitId == null || unitId.isBlank() || track == null || Double.isNaN(value)) {
      return;
    }
    // Skip storing the missing-default itself — keeps the matrix sparse.
    if (missingDefault != null && value == missingDefault.doubleValue()) {
      values.computeIfAbsent(unitId, k -> new IdentityHashMap<>());
      tracks.add(track);
      return;
    }
    values.computeIfAbsent(unitId, k -> new IdentityHashMap<>()).put(track, value);
    tracks.add(track);
  }

  /** Ensure a unit exists even when every sample is the missing default. */
  public void touch(String unitId) {
    if (unitId == null || unitId.isBlank()) {
      return;
    }
    values.computeIfAbsent(unitId, k -> new IdentityHashMap<>());
  }

  public void putLocus(String unitId, String chromosome, long start1Based, long end1Based) {
    if (unitId == null || unitId.isBlank() || chromosome == null) {
      return;
    }
    loci.putIfAbsent(unitId, new Locus(chromosome, start1Based, end1Based));
  }

  public Double get(String unitId, SampleTrack track) {
    Map<SampleTrack, Double> row = values.get(unitId);
    if (row == null) {
      return null;
    }
    Double v = row.get(track);
    if (v != null) {
      return v;
    }
    return missingDefault;
  }

  /** True when an explicit (non-filled) value was stored for this unit/track. */
  public boolean hasValue(String unitId, SampleTrack track) {
    if (unitId == null || track == null) {
      return false;
    }
    Map<SampleTrack, Double> row = values.get(unitId);
    return row != null && row.containsKey(track);
  }

  /** Count of explicit values for {@code unitId} among {@code sampleTracks}. */
  public int observedCount(String unitId, List<SampleTrack> sampleTracks) {
    if (sampleTracks == null || sampleTracks.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (SampleTrack track : sampleTracks) {
      if (hasValue(unitId, track)) {
        n++;
      }
    }
    return n;
  }

  public Locus locusOf(String unitId) {
    return loci.get(unitId);
  }

  public Set<String> unitIds() {
    return Collections.unmodifiableSet(values.keySet());
  }

  public Set<SampleTrack> tracks() {
    return Collections.unmodifiableSet(tracks);
  }

  public List<Double> valuesFor(String unitId, List<SampleTrack> sampleTracks) {
    List<Double> out = new ArrayList<>();
    if (sampleTracks == null) {
      return out;
    }
    Map<SampleTrack, Double> row = values.get(unitId);
    if (row == null) {
      return out;
    }
    for (SampleTrack track : sampleTracks) {
      Double v = row.get(track);
      if (v != null && !Double.isNaN(v)) {
        out.add(v);
      } else if (missingDefault != null) {
        out.add(missingDefault);
      }
    }
    return out;
  }

  public int size() {
    return values.size();
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }

  /** Merge multiple observations for the same unit/track using {@code agg}. */
  public static double collapse(List<Double> observations, ValueAggregation agg) {
    if (observations == null || observations.isEmpty()) {
      return Double.NaN;
    }
    ValueAggregation mode = agg != null ? agg : ValueAggregation.MEAN;
    return switch (mode) {
      case MAX -> observations.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
      case MEDIAN -> {
        List<Double> sorted = new ArrayList<>(observations);
        Collections.sort(sorted);
        int n = sorted.size();
        if ((n & 1) == 1) {
          yield sorted.get(n / 2);
        }
        yield 0.5 * (sorted.get(n / 2 - 1) + sorted.get(n / 2));
      }
      case MEAN -> observations.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    };
  }
}
