package org.baseplayer.io.table;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import org.baseplayer.utils.ChromosomeNames;

/** Loaded mapped table bound to a sample file. */
public final class MappedTableData {

  private final Path path;
  private final String name;
  private final MappedTableColumnMapping mapping;
  private final String[] headers;
  private final List<MappedTableRow> rows;
  /** Rows per stripped chrom key, sorted by start (classic row list only). */
  private final Map<String, List<MappedTableRow>> rowsByChrom;
  /**
   * When true, paint as discrete interval bars (gene matrix), not CNV step lines.
   * Unit ids on shared loci are used directly for Signal collect (no re-annotate).
   */
  private final boolean intervalBars;
  private final SharedMatrixLoci sharedLoci;
  /** Parallel to {@link #sharedLoci}; {@link Float#NaN} = missing / masked. */
  private final float[] compactValues;
  /** Clone of loaded values before threshold masking; null until first mask. */
  private float[] rawBackup;

  public MappedTableData(
      Path path,
      String name,
      MappedTableColumnMapping mapping,
      String[] headers,
      List<MappedTableRow> rows) {
    this(path, name, mapping, headers, rows, false, null, null);
  }

  private MappedTableData(
      Path path,
      String name,
      MappedTableColumnMapping mapping,
      String[] headers,
      List<MappedTableRow> rows,
      boolean intervalBars,
      SharedMatrixLoci sharedLoci,
      float[] compactValues) {
    this.path = path;
    this.name = name != null ? name : (path != null && path.getFileName() != null
        ? path.getFileName().toString()
        : "table");
    this.mapping = mapping;
    this.headers = headers != null ? headers.clone() : new String[0];
    this.intervalBars = intervalBars;
    this.sharedLoci = sharedLoci;
    if (sharedLoci != null) {
      if (compactValues == null || compactValues.length != sharedLoci.size()) {
        throw new IllegalArgumentException("compact values length must match shared loci");
      }
      this.compactValues = compactValues;
      this.rows = List.of();
      this.rowsByChrom = Map.of();
    } else {
      this.compactValues = null;
      this.rows = rows != null ? List.copyOf(rows) : List.of();
      this.rowsByChrom = indexByChrom(this.rows);
    }
  }

  /**
   * Compact gene/region matrix sample: shared loci + one float value per locus.
   * Drawn as interval bars; Signal uses unit ids without re-annotation.
   */
  public static MappedTableData geneMatrixSample(
      Path path,
      String sampleName,
      MappedTableColumnMapping mapping,
      String[] headers,
      SharedMatrixLoci loci,
      float[] values) {
    return new MappedTableData(
        path, sampleName, mapping, headers, List.of(), true, loci, values);
  }

  public Path getPath() {
    return path;
  }

  public String getName() {
    return name;
  }

  public MappedTableColumnMapping getMapping() {
    return mapping;
  }

  public String[] getHeaders() {
    return headers.clone();
  }

  public boolean hasValueColumn() {
    return mapping != null && mapping.hasValueColumn();
  }

  public boolean hasAlleleCn() {
    return mapping != null && mapping.hasAlleleCn();
  }

  /** Discrete bars at gene/region intervals (matrix open). */
  public boolean isIntervalBars() {
    return intervalBars;
  }

  public SharedMatrixLoci sharedLoci() {
    return sharedLoci;
  }

  public float[] compactValues() {
    return compactValues;
  }

  /** True when each locus already carries a gene/feature unit id. */
  public boolean hasUnitIds() {
    return sharedLoci != null && sharedLoci.hasUnitIds();
  }

  /**
   * All rows. For compact matrices this materializes every finite value — prefer
   * {@link #forEachOverlapping} / unit-id collect for large matrices.
   */
  public List<MappedTableRow> getRows() {
    if (sharedLoci == null) {
      return rows;
    }
    List<MappedTableRow> out = new ArrayList<>();
    for (int i = 0; i < sharedLoci.size(); i++) {
      float v = compactValues[i];
      if (!Float.isFinite(v)) {
        continue;
      }
      out.add(toRow(i, v));
    }
    return out;
  }

  /**
   * Rows overlapping {@code [viewStart, viewEnd]} on {@code chrom} (chr-prefix agnostic).
   * Coordinates are inclusive 1-based.
   */
  public List<MappedTableRow> rowsOverlapping(String chrom, long viewStart, long viewEnd) {
    if (sharedLoci != null) {
      int[] idx = sharedLoci.overlappingIndices(chrom, viewStart, viewEnd);
      if (idx.length == 0) {
        return List.of();
      }
      List<MappedTableRow> out = new ArrayList<>(idx.length);
      for (int i : idx) {
        float v = compactValues[i];
        if (!Float.isFinite(v)) {
          continue;
        }
        out.add(toRow(i, v));
      }
      return out;
    }
    if (chrom == null || viewEnd < viewStart) {
      return List.of();
    }
    List<MappedTableRow> chromRows = rowsByChrom.get(ChromosomeNames.key(chrom));
    if (chromRows == null || chromRows.isEmpty()) {
      return List.of();
    }
    List<MappedTableRow> out = new ArrayList<>();
    for (MappedTableRow row : chromRows) {
      if (row.end1Based() < viewStart) {
        continue;
      }
      if (row.start1Based() > viewEnd) {
        break;
      }
      out.add(row);
    }
    return out;
  }

  /** Invoke {@code action} with locus index for each overlapping finite value. */
  public void forEachOverlapping(
      String chrom, long viewStart, long viewEnd, IntConsumer action) {
    if (action == null) {
      return;
    }
    if (sharedLoci != null) {
      for (int i : sharedLoci.overlappingIndices(chrom, viewStart, viewEnd)) {
        if (Float.isFinite(compactValues[i])) {
          action.accept(i);
        }
      }
      return;
    }
    // Classic path: no compact indices — callers should use rowsOverlapping.
  }

  public float compactValue(int locusIndex) {
    if (compactValues == null || locusIndex < 0 || locusIndex >= compactValues.length) {
      return Float.NaN;
    }
    return compactValues[locusIndex];
  }

  /** Snapshot loaded values once so threshold Apply can restore then re-mask. */
  public void ensureRawBackup() {
    if (compactValues == null || rawBackup != null) {
      return;
    }
    rawBackup = compactValues.clone();
  }

  /** Restore working values from the pre-mask backup (no-op if never masked). */
  public void restoreFromRawBackup() {
    if (compactValues == null || rawBackup == null) {
      return;
    }
    System.arraycopy(rawBackup, 0, compactValues, 0, compactValues.length);
  }

  public boolean hasRawBackup() {
    return rawBackup != null;
  }

  /**
   * Restore from raw (if any), then set working cells to {@link Float#NaN} where
   * {@code fail} is true. Finite cells that pass stay at their raw values.
   */
  public void applyValueMask(java.util.function.DoublePredicate fail) {
    if (compactValues == null || fail == null) {
      return;
    }
    ensureRawBackup();
    float[] src = rawBackup != null ? rawBackup : compactValues;
    for (int i = 0; i < compactValues.length; i++) {
      float v = src[i];
      if (Float.isFinite(v) && fail.test(v)) {
        compactValues[i] = Float.NaN;
      } else {
        compactValues[i] = v;
      }
    }
  }

  private MappedTableRow toRow(int i, float v) {
    return new MappedTableRow(
        sharedLoci.chrom(i),
        sharedLoci.start1Based(i),
        sharedLoci.end1Based(i),
        name,
        (double) v,
        null,
        null,
        null,
        null);
  }

  private static Map<String, List<MappedTableRow>> indexByChrom(List<MappedTableRow> all) {
    if (all.isEmpty()) {
      return Map.of();
    }
    Map<String, List<MappedTableRow>> map = new HashMap<>();
    for (MappedTableRow row : all) {
      map.computeIfAbsent(ChromosomeNames.key(row.chromosome()), k -> new ArrayList<>()).add(row);
    }
    for (List<MappedTableRow> list : map.values()) {
      list.sort(Comparator.comparingLong(MappedTableRow::start1Based)
          .thenComparingLong(MappedTableRow::end1Based));
    }
    Map<String, List<MappedTableRow>> frozen = new HashMap<>(map.size());
    for (Map.Entry<String, List<MappedTableRow>> e : map.entrySet()) {
      frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
    }
    return Map.copyOf(frozen);
  }
}
