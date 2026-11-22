package org.baseplayer.io.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.utils.ChromosomeNames;

/**
 * Shared gene/region loci for a wide matrix file (one copy for all sample columns).
 * Per-sample values live as aligned {@code float[]} on each {@link MappedTableData}.
 */
public final class SharedMatrixLoci {

  private final String[] chrom;
  private final long[] start1Based;
  private final long[] end1Based;
  private final String[] unitId;
  /** chrom key → indices into arrays, sorted by start. */
  private final Map<String, int[]> indicesByChrom;
  /** unitId → first locus index (lazy). */
  private Map<String, Integer> indexByUnitId;

  public SharedMatrixLoci(
      String[] chrom,
      long[] start1Based,
      long[] end1Based,
      String[] unitId) {
    if (chrom == null || start1Based == null || end1Based == null) {
      throw new IllegalArgumentException("chrom/start/end required");
    }
    int n = chrom.length;
    if (start1Based.length != n || end1Based.length != n
        || (unitId != null && unitId.length != n)) {
      throw new IllegalArgumentException("locus array length mismatch");
    }
    this.chrom = chrom;
    this.start1Based = start1Based;
    this.end1Based = end1Based;
    this.unitId = unitId;
    this.indicesByChrom = indexByChrom(chrom, start1Based, end1Based);
  }

  public int size() {
    return chrom.length;
  }

  public String chrom(int i) {
    return chrom[i];
  }

  public long start1Based(int i) {
    return start1Based[i];
  }

  public long end1Based(int i) {
    return end1Based[i];
  }

  public String unitId(int i) {
    if (unitId == null || i < 0 || i >= unitId.length) {
      return null;
    }
    return unitId[i];
  }

  public boolean hasUnitIds() {
    return unitId != null;
  }

  /** First locus index for {@code unitId}, or {@code -1} if unknown. */
  public int indexOfUnitId(String id) {
    if (id == null || id.isBlank() || unitId == null) {
      return -1;
    }
    Map<String, Integer> map = indexByUnitId;
    if (map == null) {
      map = new HashMap<>();
      for (int i = 0; i < unitId.length; i++) {
        String u = unitId[i];
        if (u != null && !u.isBlank()) {
          map.putIfAbsent(u, i);
        }
      }
      indexByUnitId = Map.copyOf(map);
      map = indexByUnitId;
    }
    Integer idx = map.get(id);
    return idx != null ? idx : -1;
  }

  /** Chromosome keys present in this matrix (stripped form used by {@link ChromosomeNames#key}). */
  public Set<String> chromosomeKeys() {
    return indicesByChrom.keySet();
  }

  /** Locus indices on {@code chromosome}, sorted by start (empty if unknown). */
  public int[] indicesOnChromosome(String chromosome) {
    if (chromosome == null || chromosome.isBlank()) {
      return new int[0];
    }
    int[] all = indicesByChrom.get(ChromosomeNames.key(chromosome));
    return all != null ? all : new int[0];
  }

  /** Indices on {@code chrom} overlapping {@code [viewStart, viewEnd]} (inclusive). */
  public int[] overlappingIndices(String chromosome, long viewStart, long viewEnd) {
    if (chromosome == null || viewEnd < viewStart) {
      return new int[0];
    }
    int[] all = indicesByChrom.get(ChromosomeNames.key(chromosome));
    if (all == null || all.length == 0) {
      return new int[0];
    }
    List<Integer> hit = new ArrayList<>();
    for (int idx : all) {
      if (end1Based[idx] < viewStart) {
        continue;
      }
      if (start1Based[idx] > viewEnd) {
        break;
      }
      hit.add(idx);
    }
    int[] out = new int[hit.size()];
    for (int i = 0; i < hit.size(); i++) {
      out[i] = hit.get(i);
    }
    return out;
  }

  private static Map<String, int[]> indexByChrom(
      String[] chrom, long[] start, long[] end) {
    Map<String, List<Integer>> tmp = new HashMap<>();
    for (int i = 0; i < chrom.length; i++) {
      if (chrom[i] == null || chrom[i].isBlank()) {
        continue;
      }
      tmp.computeIfAbsent(ChromosomeNames.key(chrom[i]), k -> new ArrayList<>()).add(i);
    }
    Map<String, int[]> out = new HashMap<>(tmp.size());
    for (Map.Entry<String, List<Integer>> e : tmp.entrySet()) {
      List<Integer> list = e.getValue();
      list.sort(Comparator
          .comparingLong((Integer i) -> start[i])
          .thenComparingLong(i -> end[i]));
      int[] arr = new int[list.size()];
      for (int i = 0; i < list.size(); i++) {
        arr[i] = list.get(i);
      }
      out.put(e.getKey(), arr);
    }
    return Collections.unmodifiableMap(out);
  }
}
