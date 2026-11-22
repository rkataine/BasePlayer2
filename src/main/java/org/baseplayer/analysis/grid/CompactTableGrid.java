package org.baseplayer.analysis.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.analysis.source.MappedTableValueSource;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.signal.SignalScopeHelper;

/**
 * Zero-copy {@link ValueGrid} over gene-matrix {@link MappedTableData} columns
 * that share one {@link SharedMatrixLoci} (same object as the sample canvas).
 */
public final class CompactTableGrid implements ValueGrid {

  private final SharedMatrixLoci loci;
  private final List<SampleTrack> trackOrder;
  private final MappedTableData[] tables;
  private final Set<String> unitIds;
  private final Set<SampleTrack> trackSet;

  private CompactTableGrid(
      SharedMatrixLoci loci,
      List<SampleTrack> trackOrder,
      MappedTableData[] tables,
      Set<String> unitIds) {
    this.loci = loci;
    this.trackOrder = List.copyOf(trackOrder);
    this.tables = tables;
    this.unitIds = Collections.unmodifiableSet(unitIds);
    Set<SampleTrack> ts = Collections.newSetFromMap(new IdentityHashMap<>());
    ts.addAll(this.trackOrder);
    this.trackSet = Collections.unmodifiableSet(ts);
  }

  /**
   * Build a grid when every usable track has a gene-matrix table on the
   * <em>same</em> {@link SharedMatrixLoci} instance. Returns {@code null} otherwise
   * (caller falls back to collect → {@link FeatureValueMatrix}).
   */
  public static CompactTableGrid tryCreate(List<SampleTrack> tracks, GenomicScope scope) {
    if (tracks == null || tracks.isEmpty()) {
      return null;
    }
    SharedMatrixLoci shared = null;
    List<SampleTrack> used = new ArrayList<>();
    List<MappedTableData> tableList = new ArrayList<>();
    for (SampleTrack track : tracks) {
      MappedTableData table = MappedTableValueSource.firstValueTable(track);
      if (table == null || !table.hasUnitIds() || table.sharedLoci() == null
          || table.compactValues() == null) {
        return null;
      }
      if (shared == null) {
        shared = table.sharedLoci();
      } else if (shared != table.sharedLoci()) {
        return null;
      }
      used.add(track);
      tableList.add(table);
    }
    if (shared == null || used.isEmpty()) {
      return null;
    }
    Set<String> units = new LinkedHashSet<>();
    for (int i = 0; i < shared.size(); i++) {
      String unit = shared.unitId(i);
      if (unit == null || unit.isBlank()) {
        continue;
      }
      if (!inScope(shared.chrom(i), shared.start1Based(i), shared.end1Based(i), scope)) {
        continue;
      }
      units.add(unit);
    }
    if (units.isEmpty()) {
      return null;
    }
    return new CompactTableGrid(
        shared, used, tableList.toArray(MappedTableData[]::new), units);
  }

  /**
   * Same as {@link #tryCreate} but only units on {@code chromosome}
   * (name match via {@link org.baseplayer.utils.ChromosomeNames#key}).
   */
  public static CompactTableGrid tryCreateForChrom(List<SampleTrack> tracks, String chromosome) {
    if (chromosome == null || chromosome.isBlank()) {
      return null;
    }
    CompactTableGrid all = tryCreate(tracks, GenomicScope.ALL_CHROMS);
    if (all == null || all.isEmpty()) {
      return null;
    }
    String key = org.baseplayer.utils.ChromosomeNames.key(chromosome);
    Set<String> units = new LinkedHashSet<>();
    SharedMatrixLoci shared = all.sharedLoci();
    int[] idxs = shared.indicesOnChromosome(chromosome);
    for (int idx : idxs) {
      String unit = shared.unitId(idx);
      if (unit != null && !unit.isBlank() && all.unitIds().contains(unit)) {
        units.add(unit);
      }
    }
    // Fallback: scan when indices empty but chrom key might differ in unit set.
    if (units.isEmpty()) {
      for (String unit : all.unitIds()) {
        FeatureValueMatrix.Locus loc = all.locusOf(unit);
        if (loc == null || loc.chromosome() == null) {
          continue;
        }
        if (key.equals(org.baseplayer.utils.ChromosomeNames.key(loc.chromosome()))) {
          units.add(unit);
        }
      }
    }
    if (units.isEmpty()) {
      return null;
    }
    return new CompactTableGrid(shared, all.trackOrder, all.tables, units);
  }

  public SharedMatrixLoci sharedLoci() {
    return loci;
  }

  public List<SampleTrack> trackOrder() {
    return trackOrder;
  }

  public MappedTableData tableFor(SampleTrack track) {
    if (track == null) {
      return null;
    }
    for (int i = 0; i < trackOrder.size(); i++) {
      if (trackOrder.get(i) == track) {
        return tables[i];
      }
    }
    return null;
  }

  /** All compact tables in this grid (for mask / restore). */
  public List<MappedTableData> tables() {
    return List.of(tables);
  }

  @Override
  public Set<String> unitIds() {
    return unitIds;
  }

  @Override
  public FeatureValueMatrix.Locus locusOf(String unitId) {
    int idx = loci.indexOfUnitId(unitId);
    if (idx < 0) {
      return null;
    }
    return new FeatureValueMatrix.Locus(
        loci.chrom(idx), loci.start1Based(idx), loci.end1Based(idx));
  }

  @Override
  public Double get(String unitId, SampleTrack track) {
    float v = raw(unitId, track);
    return Float.isFinite(v) ? (double) v : null;
  }

  @Override
  public boolean hasValue(String unitId, SampleTrack track) {
    return Float.isFinite(raw(unitId, track));
  }

  @Override
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

  @Override
  public List<Double> valuesFor(String unitId, List<SampleTrack> sampleTracks) {
    List<Double> out = new ArrayList<>();
    if (sampleTracks == null) {
      return out;
    }
    for (SampleTrack track : sampleTracks) {
      Double v = get(unitId, track);
      if (v != null) {
        out.add(v);
      }
    }
    return out;
  }

  @Override
  public Set<SampleTrack> tracks() {
    return trackSet;
  }

  @Override
  public int size() {
    return unitIds.size();
  }

  @Override
  public boolean isEmpty() {
    return unitIds.isEmpty();
  }

  private float raw(String unitId, SampleTrack track) {
    if (unitId == null || track == null) {
      return Float.NaN;
    }
    int idx = loci.indexOfUnitId(unitId);
    if (idx < 0) {
      return Float.NaN;
    }
    MappedTableData table = tableFor(track);
    return table != null ? table.compactValue(idx) : Float.NaN;
  }

  private static boolean inScope(
      String chrom, long start1Based, long end1Based, GenomicScope scope) {
    if (scope == null || scope == GenomicScope.ALL_CHROMS) {
      return true;
    }
    SignalScopeHelper.Region view = SignalScopeHelper.currentView();
    if (view == null) {
      return scope != GenomicScope.CURRENT_VIEW;
    }
    if (chrom == null) {
      return false;
    }
    if (!chrom.equals(view.chrom())
        && !org.baseplayer.utils.ChromosomeNames.key(chrom)
            .equals(org.baseplayer.utils.ChromosomeNames.key(view.chrom()))) {
      return false;
    }
    if (scope == GenomicScope.CURRENT_CHROM) {
      return true;
    }
    return end1Based >= view.start1Based() && start1Based <= view.end1Based();
  }
}
