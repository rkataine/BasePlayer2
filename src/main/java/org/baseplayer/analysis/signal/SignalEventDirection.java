package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.analysis.allelecn.LohFromAlleleCn;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.samples.SampleTrack;

/**
 * CNV-style direction for Allele CN annotation rows (table readability).
 * LOH matches {@link LohFromAlleleCn#binary()} (same as aggregate band);
 * gain threshold matches aggregate Allele-CN recurrence (total ≥ 2.5).
 */
public enum SignalEventDirection {
  NONE("—"),
  GAIN("↑ Gain"),
  LOSS("↓ Loss"),
  LOH("LOH");

  /** Total CN at/above this counts as gain. */
  public static final double GAIN_TOTAL_CN = 2.5;
  /** Total CN at/below this counts as loss (when not LOH), e.g. homozygous deletion 0/0. */
  public static final double LOSS_TOTAL_CN = 1.5;
  /** Same epsilon as {@link LohFromAlleleCn#binary()}. */
  private static final double LOH_EPSILON = 0.0;

  private final String label;

  SignalEventDirection(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** Short label for filter toggles (Gain / Loss / LOH). */
  public String filterLabel() {
    return switch (this) {
      case GAIN -> "Gain";
      case LOSS -> "Loss";
      case LOH -> "LOH";
      case NONE -> "—";
    };
  }

  /** RGB for filter swatches / row tint (matches annotation Dir colors). */
  public int[] rgb() {
    return switch (this) {
      case GAIN -> new int[] {45, 95, 210};
      case LOSS -> new int[] {180, 90, 40};
      case LOH -> new int[] {210, 55, 55};
      case NONE -> new int[] {128, 128, 128};
    };
  }

  /** Default type filter: Gain + Loss + LOH. */
  public static EnumSet<SignalEventDirection> defaultAllowed() {
    return EnumSet.of(GAIN, LOSS, LOH);
  }

  /** Ordered event types for UI toggles. */
  public static List<SignalEventDirection> filterTypes() {
    return List.of(GAIN, LOSS, LOH);
  }

  /** Annotation row unitId for a gene×event split ({@code TP53::LOH}). */
  public static String rowUnitId(String geneUnitId, SignalEventDirection dir) {
    if (geneUnitId == null || geneUnitId.isBlank() || dir == null || dir == NONE) {
      return geneUnitId;
    }
    return geneUnitId + "::" + dir.name();
  }

  /** Gene / locus id from an annotation unitId (strips {@code ::GAIN|LOSS|LOH}). */
  public static String locusUnitId(String unitId) {
    if (unitId == null) {
      return null;
    }
    int sep = unitId.lastIndexOf("::");
    if (sep <= 0) {
      return unitId;
    }
    String suffix = unitId.substring(sep + 2);
    try {
      SignalEventDirection d = valueOf(suffix);
      if (d != NONE) {
        return unitId.substring(0, sep);
      }
    } catch (IllegalArgumentException ignored) {
      // not an event suffix
    }
    return unitId;
  }

  /** Parse {@link GroupStatResult.Row#direction()} when it stores an event type name. */
  public static SignalEventDirection parseRowDirection(String direction) {
    if (direction == null || direction.isBlank()) {
      return NONE;
    }
    try {
      SignalEventDirection d = valueOf(direction.trim());
      return d != NONE ? d : NONE;
    } catch (IllegalArgumentException ignored) {
      return NONE;
    }
  }

  /**
   * Classify from minor/major allele copy numbers.
   * <ul>
   *   <li>LOH — minor lost and major remains (0/1, 0/2, …); same as {@link LohFromAlleleCn}</li>
   *   <li>Gain — total CN ≥ {@link #GAIN_TOTAL_CN}</li>
   *   <li>Loss — low total without LOH (e.g. homozygous deletion 0/0)</li>
   * </ul>
   */
  public static SignalEventDirection fromAlleleCn(Double minor, Double major) {
    double mi = minor != null && Double.isFinite(minor) ? minor : Double.NaN;
    double ma = major != null && Double.isFinite(major) ? major : Double.NaN;
    if (!Double.isFinite(mi) && !Double.isFinite(ma)) {
      return NONE;
    }
    if (!Double.isFinite(mi)) {
      mi = 0.0;
    }
    if (!Double.isFinite(ma)) {
      ma = 0.0;
    }
    // LohFromAlleleCn.binary(): minor lost, major remains (0/1 and 0/2 are both LOH).
    if (mi <= LOH_EPSILON && ma > LOH_EPSILON) {
      return LOH;
    }
    double total = mi + ma;
    if (total >= GAIN_TOTAL_CN) {
      return GAIN;
    }
    if (total <= LOSS_TOTAL_CN) {
      return LOSS;
    }
    return NONE;
  }

  /** Resolve minor/major from a multi-channel annotation result row. */
  public static SignalEventDirection fromResultRow(GroupStatResult result, GroupStatResult.Row row) {
    if (row == null) {
      return NONE;
    }
    // Gene×event split rows store the type in direction (avoids diluted means).
    SignalEventDirection tagged = parseRowDirection(row.direction());
    if (tagged != NONE) {
      return tagged;
    }
    if (result == null || !result.hasSignalChannels()) {
      return NONE;
    }
    Double minor = null;
    Double major = null;
    for (SignalChannel ch : result.signalChannels()) {
      if (ch == null || ch.type() != SignalValueType.ALLELE_CN) {
        continue;
      }
      Double v = result.channelValue(row.unitId(), ch.id());
      if (ch.sourceRole() == org.baseplayer.io.table.MappedTableColumnRole.MINOR_CN
          || "minor".equalsIgnoreCase(ch.id())
          || (ch.label() != null && ch.label().toLowerCase().contains("minor"))) {
        minor = v;
      } else if (ch.sourceRole() == org.baseplayer.io.table.MappedTableColumnRole.MAJOR_CN
          || "major".equalsIgnoreCase(ch.id())
          || (ch.label() != null && ch.label().toLowerCase().contains("major"))) {
        major = v;
      }
    }
    if (minor == null && major == null) {
      // Fall back: first two ALLELE_CN channels as minor then major.
      int i = 0;
      for (SignalChannel ch : result.signalChannels()) {
        if (ch == null || ch.type() != SignalValueType.ALLELE_CN) {
          continue;
        }
        Double v = result.channelValue(row.unitId(), ch.id());
        if (i == 0) {
          minor = v;
        } else if (i == 1) {
          major = v;
        }
        i++;
      }
    }
    return fromAlleleCn(minor, major);
  }

  public static boolean resultHasAlleleCn(GroupStatResult result) {
    if (result == null || !result.hasSignalChannels()) {
      return false;
    }
    for (SignalChannel ch : result.signalChannels()) {
      if (ch != null && ch.type() == SignalValueType.ALLELE_CN) {
        return true;
      }
    }
    return false;
  }

  /**
   * Classify one sample×gene from a Minor+Major bundle.
   * Missing / non-allele-CN channels → {@link #NONE}.
   */
  public static SignalEventDirection fromBundleSample(
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      String unitId,
      SampleTrack track) {
    if (bundle == null || unitId == null || track == null
        || channels == null || !SignalChannels.isAlleleCnOnly(channels)) {
      return NONE;
    }
    Double minor = null;
    Double major = null;
    for (SignalChannel ch : channels) {
      if (ch == null) {
        continue;
      }
      FeatureValueMatrix m = bundle.matrix(ch.id());
      if (m == null || !m.hasValue(unitId, track)) {
        continue;
      }
      Double v = m.get(unitId, track);
      if (ch.sourceRole() == MappedTableColumnRole.MINOR_CN
          || "minor".equalsIgnoreCase(ch.id())) {
        minor = v;
      } else if (ch.sourceRole() == MappedTableColumnRole.MAJOR_CN
          || "major".equalsIgnoreCase(ch.id())) {
        major = v;
      }
    }
    if (minor == null && major == null) {
      int i = 0;
      for (SignalChannel ch : channels) {
        if (ch == null) {
          continue;
        }
        FeatureValueMatrix m = bundle.matrix(ch.id());
        if (m == null || !m.hasValue(unitId, track)) {
          continue;
        }
        Double v = m.get(unitId, track);
        if (i == 0) {
          minor = v;
        } else if (i == 1) {
          major = v;
        }
        i++;
      }
    }
    return fromAlleleCn(minor, major);
  }

  /**
   * Keep annotation rows whose consensus Dir is in {@code allowed}.
   * Non-allele-CN results are returned unchanged. Empty allowed → no rows.
   */
  public static GroupStatResult filterResult(
      GroupStatResult result, Collection<SignalEventDirection> allowed) {
    if (result == null || !resultHasAlleleCn(result)) {
      return result;
    }
    Set<SignalEventDirection> keep = allowed != null && !allowed.isEmpty()
        ? EnumSet.copyOf(allowed)
        : EnumSet.noneOf(SignalEventDirection.class);
    keep.remove(NONE);
    List<GroupStatResult.Row> rows = new ArrayList<>();
    for (GroupStatResult.Row row : result.rows()) {
      SignalEventDirection dir = fromResultRow(result, row);
      if (keep.contains(dir)) {
        rows.add(row);
      }
    }
    if (rows.size() == result.rows().size()) {
      return result;
    }
    return new GroupStatResult(
        result.mode(),
        result.groupIds(),
        result.baselineGroupId(),
        rows,
        result.summary(),
        result.methodStrip(),
        result.signalChannels(),
        result.channelValuesByUnit());
  }
}
