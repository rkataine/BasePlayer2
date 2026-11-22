package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.io.table.MappedTableColumnMapping;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.io.table.MappedTableData;

/**
 * Builds {@link SignalChannel} lists from opened mapped tables.
 */
public final class SignalChannels {

  private SignalChannels() {}

  /**
   * Derive channels from the first usable mapped table.
   * Minor+Major → two {@link SignalValueType#ALLELE_CN} channels;
   * VALUE/SCORE → one {@link SignalValueType#RAW} channel.
   */
  public static List<SignalChannel> fromTable(MappedTableData table) {
    if (table == null || table.getMapping() == null) {
      return List.of();
    }
    // Gene/region matrices: one value per sample track — use MappedTableValueSource
    // with unit ids (not MultiChannelMappedTableSource / getRows materialization).
    if (table.isIntervalBars()) {
      return List.of();
    }
    MappedTableColumnMapping mapping = table.getMapping();
    String[] headers = table.getHeaders();
    List<SignalChannel> out = new ArrayList<>();
    if (mapping.hasAlleleCn()) {
      out.add(channel(
          "minor",
          headerOr(headers, mapping, MappedTableColumnRole.MINOR_CN, "Minor"),
          SignalValueType.ALLELE_CN,
          MappedTableColumnRole.MINOR_CN));
      out.add(channel(
          "major",
          headerOr(headers, mapping, MappedTableColumnRole.MAJOR_CN, "Major"),
          SignalValueType.ALLELE_CN,
          MappedTableColumnRole.MAJOR_CN));
      return List.copyOf(out);
    }
    if (mapping.has(MappedTableColumnRole.VALUE)) {
      out.add(channel(
          "value",
          headerOr(headers, mapping, MappedTableColumnRole.VALUE, "Value"),
          SignalValueType.RAW,
          MappedTableColumnRole.VALUE));
    } else if (mapping.has(MappedTableColumnRole.SCORE)) {
      out.add(channel(
          "score",
          headerOr(headers, mapping, MappedTableColumnRole.SCORE, "Score"),
          SignalValueType.RAW,
          MappedTableColumnRole.SCORE));
    }
    return List.copyOf(out);
  }

  private static SignalChannel channel(
      String id,
      String label,
      SignalValueType type,
      MappedTableColumnRole role) {
    return new SignalChannel(id, label, type, role, SignalValueThresholds.defaultsFor(type));
  }

  private static String headerOr(
      String[] headers,
      MappedTableColumnMapping mapping,
      MappedTableColumnRole role,
      String fallback) {
    Integer idx = mapping.indexOf(role);
    if (headers != null && idx != null && idx >= 0 && idx < headers.length
        && headers[idx] != null && !headers[idx].isBlank()) {
      return headers[idx].trim();
    }
    return fallback;
  }
}
