package org.baseplayer.analysis.signal;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.samples.SampleTrack;

/**
 * Builds a gene×event presence matrix (0/1) from an allele-CN Minor+Major bundle.
 * Units match annotation split rows ({@code TP53::LOH}); {@code missingDefault=0}
 * so {@link org.baseplayer.analysis.groupstat.GroupStatTest#AUTO} selects Fisher.
 */
public final class AlleleCnEventMatrix {

  private AlleleCnEventMatrix() {}

  /**
   * @param allowedDirections Gain/Loss/LOH to emit; null/empty → all three
   * @return matrix, or null when inputs are not allele-CN
   */
  public static FeatureValueMatrix build(
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      List<SampleTrack> roster,
      Collection<SignalEventDirection> allowedDirections) {
    if (bundle == null || bundle.isEmpty() || !SignalChannels.isAlleleCnOnly(channels)) {
      return null;
    }
    List<SampleTrack> tracks = roster != null && !roster.isEmpty()
        ? roster
        : List.copyOf(bundle.tracks());
    if (tracks.isEmpty()) {
      return null;
    }
    Set<SignalEventDirection> allowed = normalizeAllowed(allowedDirections);

    FeatureValueMatrix out = new FeatureValueMatrix();
    out.setMissingDefault(0.0);

    for (String geneId : bundle.unitIds()) {
      if (geneId == null || geneId.isBlank()) {
        continue;
      }
      FeatureValueMatrix.Locus locus = locusOf(bundle, channels, geneId);
      for (SignalEventDirection dir : SignalEventDirection.filterTypes()) {
        if (!allowed.contains(dir)) {
          continue;
        }
        String rowId = SignalEventDirection.rowUnitId(geneId, dir);
        boolean anyHit = false;
        for (SampleTrack track : tracks) {
          SignalEventDirection sampleDir =
              SignalEventDirection.fromBundleSample(bundle, channels, geneId, track);
          if (sampleDir == dir) {
            out.put(rowId, track, 1.0);
            anyHit = true;
          }
        }
        if (!anyHit) {
          continue;
        }
        out.touch(rowId);
        if (locus != null) {
          out.putLocus(rowId, locus.chromosome(), locus.start1Based(), locus.end1Based());
        }
      }
    }
    return out.isEmpty() ? null : out;
  }

  /** True when this matrix is an allele-CN 0/1 event grid. */
  public static boolean isEventMatrix(FeatureValueMatrix matrix) {
    if (matrix == null || matrix.getMissingDefault() == null) {
      return false;
    }
    if (matrix.getMissingDefault() != 0.0) {
      return false;
    }
    for (String unitId : matrix.unitIds()) {
      if (!unitId.equals(SignalEventDirection.locusUnitId(unitId))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Rewrite group-stat rows so geneName is the gene and direction is GAIN/LOSS/LOH
   * (engine otherwise stores unitId as geneName and up/down as direction).
   */
  public static GroupStatResult tagEventRows(GroupStatResult result) {
    if (result == null || result.rows().isEmpty()) {
      return result;
    }
    boolean any = false;
    for (GroupStatResult.Row row : result.rows()) {
      if (row != null && !row.unitId().equals(SignalEventDirection.locusUnitId(row.unitId()))) {
        any = true;
        break;
      }
    }
    if (!any) {
      return result;
    }
    java.util.ArrayList<GroupStatResult.Row> rows = new java.util.ArrayList<>(result.rows().size());
    for (GroupStatResult.Row row : result.rows()) {
      if (row == null) {
        continue;
      }
      String gene = SignalEventDirection.locusUnitId(row.unitId());
      SignalEventDirection dir = SignalEventDirection.NONE;
      int sep = row.unitId() != null ? row.unitId().lastIndexOf("::") : -1;
      if (sep > 0) {
        dir = SignalEventDirection.parseRowDirection(row.unitId().substring(sep + 2));
      }
      if (dir == SignalEventDirection.NONE) {
        dir = SignalEventDirection.parseRowDirection(row.direction());
      }
      rows.add(new GroupStatResult.Row(
          row.unitId(),
          gene != null ? gene : row.geneName(),
          row.chromosome(),
          row.start1Based(),
          row.end1Based(),
          row.groups(),
          row.effectVsBaseline(),
          row.pValue(),
          row.qValue(),
          row.testName(),
          dir != SignalEventDirection.NONE ? dir.name() : row.direction()));
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

  private static Set<SignalEventDirection> normalizeAllowed(
      Collection<SignalEventDirection> allowedDirections) {
    if (allowedDirections == null || allowedDirections.isEmpty()) {
      return SignalEventDirection.defaultAllowed();
    }
    EnumSet<SignalEventDirection> out = EnumSet.noneOf(SignalEventDirection.class);
    for (SignalEventDirection d : allowedDirections) {
      if (d != null && d != SignalEventDirection.NONE) {
        out.add(d);
      }
    }
    return out.isEmpty() ? SignalEventDirection.defaultAllowed() : out;
  }

  private static FeatureValueMatrix.Locus locusOf(
      FeatureValueBundle bundle, List<SignalChannel> channels, String geneId) {
    if (bundle == null || geneId == null) {
      return null;
    }
    if (channels != null) {
      for (SignalChannel ch : channels) {
        if (ch == null) {
          continue;
        }
        FeatureValueMatrix m = bundle.matrix(ch.id());
        if (m != null) {
          FeatureValueMatrix.Locus locus = m.locusOf(geneId);
          if (locus != null) {
            return locus;
          }
        }
      }
    }
    FeatureValueMatrix primary = bundle.primaryMatrix();
    return primary != null ? primary.locusOf(geneId) : null;
  }
}
