package org.baseplayer.io.table;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.utils.ChromosomeNames;

/** Loaded mapped table bound to a sample file. */
public final class MappedTableData {

  private final Path path;
  private final String name;
  private final MappedTableColumnMapping mapping;
  private final String[] headers;
  private final List<MappedTableRow> rows;
  /** Rows per stripped chrom key, sorted by start. */
  private final Map<String, List<MappedTableRow>> rowsByChrom;

  public MappedTableData(
      Path path,
      String name,
      MappedTableColumnMapping mapping,
      String[] headers,
      List<MappedTableRow> rows) {
    this.path = path;
    this.name = name != null ? name : (path != null && path.getFileName() != null
        ? path.getFileName().toString()
        : "table");
    this.mapping = mapping;
    this.headers = headers != null ? headers.clone() : new String[0];
    this.rows = rows != null ? List.copyOf(rows) : List.of();
    this.rowsByChrom = indexByChrom(this.rows);
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

  public List<MappedTableRow> getRows() {
    return rows;
  }

  public boolean hasValueColumn() {
    return mapping != null && mapping.hasValueColumn();
  }

  public boolean hasAlleleCn() {
    return mapping != null && mapping.hasAlleleCn();
  }

  /**
   * Rows overlapping {@code [viewStart, viewEnd]} on {@code chrom} (chr-prefix agnostic).
   * Coordinates are inclusive 1-based.
   */
  public List<MappedTableRow> rowsOverlapping(String chrom, long viewStart, long viewEnd) {
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
