package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.analysis.grid.CompactTableGrid;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.FilteredValueGrid;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.groupstat.GroupStatResult;

/**
 * Filters Signal annotation / group-result rows by Sample Comparison rules
 * (shared-sample range, tag PRESENT/ABSENT, gene-level vs flanking clusters).
 * <p>
 * Presence = finite gene-level value that passes the current value thresholds
 * (out-of-threshold ⇒ absent). Multi-channel: present if any channel passes.
 */
public final class SignalComparisonFilter {

  private SignalComparisonFilter() {}

  /** Legacy entry: presence from annotation row groups (no matrix thresholds). */
  public static GroupStatResult apply(
      GroupStatResult result,
      SignalComparisonSettings settings,
      List<SampleTrack> roster) {
    return apply(result, settings, roster, null, null, null, null);
  }

  /**
   * @param matrix single-channel collected values (optional when {@code bundle} set)
   * @param bundle multi-channel values (preferred when channels present)
   * @param channels per-channel thresholds; when non-empty, presence uses any-channel pass
   * @param thresholds single-channel thresholds when {@code channels} is empty
   */
  public static GroupStatResult apply(
      GroupStatResult result,
      SignalComparisonSettings settings,
      List<SampleTrack> roster,
      FeatureValueMatrix matrix,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      SignalValueThresholds thresholds) {
    return apply(result, settings, roster, matrix, bundle, channels, thresholds, matrix, null);
  }

  public static GroupStatResult apply(
      GroupStatResult result,
      SignalComparisonSettings settings,
      List<SampleTrack> roster,
      FeatureValueMatrix matrix,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      SignalValueThresholds thresholds,
      ValueGrid grid) {
    return apply(result, settings, roster, matrix, bundle, channels, thresholds, grid, null);
  }

  public static GroupStatResult apply(
      GroupStatResult result,
      SignalComparisonSettings settings,
      List<SampleTrack> roster,
      FeatureValueMatrix matrix,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      SignalValueThresholds thresholds,
      ValueGrid grid,
      Collection<SignalEventDirection> allowedDirections) {
    if (result == null || result.rows().isEmpty()) {
      return result;
    }
    if (settings == null || !settings.hasActiveConstraints()) {
      return result;
    }
    Set<SignalEventDirection> allowed = normalizeAllowed(allowedDirections, channels);
    List<SampleTrack> tracks = roster != null ? roster : List.of();
    Map<SampleTag, List<SampleTrack>> byTag = indexByTag(tracks);
    List<GroupStatResult.Row> rows = new ArrayList<>(result.rows());
    Function<GroupStatResult.Row, Set<SampleTrack>> presentOf = row -> {
      if (allowed != null && bundle != null && row != null) {
        String locusId = alleleCnLocusId(row);
        Set<SignalEventDirection> rowAllowed = allowedForRow(row, allowed);
        return presentAlleleCn(locusId, tracks, bundle, channels, rowAllowed);
      }
      if (grid != null && row != null && row.unitId() != null) {
        // Compact SSOT: finite cell (already masked). Sparse matrix: apply thr when set.
        return presentFromGrid(grid, row.unitId(), tracks, thresholds);
      }
      return presentForAnnotationRow(row, tracks, matrix, bundle, channels, thresholds);
    };

    boolean[] keep;
    if (!settings.isGeneLevel() && settings.getComparisonWindowBp() > 0) {
      keep = evaluateFlankingRowClusters(rows, settings, byTag, presentOf);
    } else {
      keep = new boolean[rows.size()];
      for (int i = 0; i < rows.size(); i++) {
        keep[i] = passesUnit(presentOf.apply(rows.get(i)), settings, byTag);
      }
    }

    List<GroupStatResult.Row> filtered = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      if (keep[i]) {
        filtered.add(rows.get(i));
      }
    }
    String summary = String.format(
        Locale.ROOT,
        "%s · Sample comparison: %d / %d units",
        result.summary() != null ? result.summary() : "Signal",
        filtered.size(),
        rows.size());
    return new GroupStatResult(
        result.mode(),
        result.groupIds(),
        result.baselineGroupId(),
        filtered,
        summary,
        result.methodStrip(),
        result.signalChannels(),
        result.channelValuesByUnit());
  }

  /** Legacy: presence = any finite value (no thresholds). */
  public static FeatureValueMatrix filterMatrixUnits(
      FeatureValueMatrix matrix,
      SignalComparisonSettings settings,
      List<SampleTrack> roster) {
    return filterMatrixUnits(matrix, settings, roster, null, null, null);
  }

  /**
   * Drop matrix units that fail Sample Comparison (for group Run input).
   * Presence = finite value passing thresholds (or any channel when multi-channel).
   */
  public static FeatureValueMatrix filterMatrixUnits(
      FeatureValueMatrix matrix,
      SignalComparisonSettings settings,
      List<SampleTrack> roster,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      SignalValueThresholds thresholds) {
    return filterMatrixUnits(matrix, settings, roster, bundle, channels, thresholds, null);
  }

  public static FeatureValueMatrix filterMatrixUnits(
      FeatureValueMatrix matrix,
      SignalComparisonSettings settings,
      List<SampleTrack> roster,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      SignalValueThresholds thresholds,
      Collection<SignalEventDirection> allowedDirections) {
    if (matrix == null || matrix.isEmpty() || settings == null || !settings.hasActiveConstraints()) {
      return matrix;
    }
    List<SampleTrack> tracks = roster != null && !roster.isEmpty()
        ? roster
        : new ArrayList<>(matrix.tracks());
    Map<SampleTag, List<SampleTrack>> byTag = indexByTag(tracks);
    Set<SignalEventDirection> allowed = normalizeAllowed(allowedDirections, channels);

    List<String> unitIds = new ArrayList<>(matrix.unitIds());
    unitIds.sort(Comparator
        .comparing((String id) -> chromKey(matrix.locusOf(id)), String.CASE_INSENSITIVE_ORDER)
        .thenComparingLong(id -> locusStart(matrix.locusOf(id))));

    Function<String, Set<SampleTrack>> presentOf = unitId -> {
      if (allowed != null && bundle != null) {
        return presentAlleleCn(unitId, tracks, bundle, channels, allowed);
      }
      return presentForUnit(unitId, tracks, matrix, bundle, channels, thresholds);
    };

    boolean[] keep;
    if (!settings.isGeneLevel() && settings.getComparisonWindowBp() > 0) {
      keep = evaluateFlankingMatrixClusters(matrix, unitIds, settings, byTag, presentOf);
    } else {
      keep = new boolean[unitIds.size()];
      for (int i = 0; i < unitIds.size(); i++) {
        keep[i] = passesUnit(presentOf.apply(unitIds.get(i)), settings, byTag);
      }
    }

    FeatureValueMatrix out = new FeatureValueMatrix();
    Double missing = matrix.getMissingDefault();
    if (missing != null) {
      out.setMissingDefault(missing);
    }
    int kept = 0;
    for (int i = 0; i < unitIds.size(); i++) {
      if (!keep[i]) {
        continue;
      }
      kept++;
      String unitId = unitIds.get(i);
      FeatureValueMatrix.Locus locus = matrix.locusOf(unitId);
      if (locus != null) {
        out.putLocus(unitId, locus.chromosome(), locus.start1Based(), locus.end1Based());
      }
      for (SampleTrack track : tracks) {
        if (matrix.hasValue(unitId, track)) {
          Double v = matrix.get(unitId, track);
          if (v != null && Double.isFinite(v)) {
            out.put(unitId, track, v);
          }
        }
      }
    }
    return kept == unitIds.size() ? matrix : out;
  }

  static boolean passesUnit(
      Set<SampleTrack> present,
      SignalComparisonSettings settings,
      Map<SampleTag, List<SampleTrack>> byTag) {
    int shared = present != null ? present.size() : 0;
    if (shared < settings.getMinSharedSamples()) {
      return false;
    }
    if (shared > settings.getMaxSharedSamples()) {
      return false;
    }
    return passesTagRoles(present, settings, byTag);
  }

  private static boolean passesTagRoles(
      Set<SampleTrack> present,
      SignalComparisonSettings settings,
      Map<SampleTag, List<SampleTrack>> byTag) {
    Map<SampleTag, VariantFilter.GroupRole> roles = settings.getTagRoles();
    if (roles == null || roles.isEmpty()) {
      return true;
    }
    if (!hasSignalTagComparison(roles)) {
      return true;
    }

    boolean anyPresentMatched = false;
    boolean anyPresentConfigured = false;
    VariantFilter.PresentMatchMode mode = settings.getPresentMatchMode();

    for (Map.Entry<SampleTag, VariantFilter.GroupRole> entry : roles.entrySet()) {
      VariantFilter.GroupRole role = entry.getValue();
      if (role == null || role == VariantFilter.GroupRole.IGNORE) {
        continue;
      }
      List<SampleTrack> cohort = byTag.getOrDefault(entry.getKey(), List.of());
      if (cohort.isEmpty()) {
        continue;
      }
      if (role == VariantFilter.GroupRole.ABSENT) {
        for (SampleTrack t : cohort) {
          if (present.contains(t)) {
            return false;
          }
        }
        continue;
      }
      // PRESENT / HET / HOM → presence of a Signal event (no genotype on signal).
      anyPresentConfigured = true;
      boolean matched = false;
      for (SampleTrack t : cohort) {
        if (present.contains(t)) {
          matched = true;
          break;
        }
      }
      if (mode == VariantFilter.PresentMatchMode.ALL) {
        if (!matched) {
          return false;
        }
      } else if (matched) {
        anyPresentMatched = true;
      }
    }
    if (anyPresentConfigured && mode == VariantFilter.PresentMatchMode.ANY) {
      return anyPresentMatched;
    }
    return true;
  }

  /**
   * Signal: any non-IGNORE role is site-level (including a solitary PRESENT).
   * Point/SV keeps the stricter solitary-PRESENT rule elsewhere.
   */
  private static boolean hasSignalTagComparison(Map<SampleTag, VariantFilter.GroupRole> roles) {
    for (VariantFilter.GroupRole role : roles.values()) {
      if (role != null && role != VariantFilter.GroupRole.IGNORE) {
        return true;
      }
    }
    return false;
  }

  private static Set<SampleTrack> presentForAnnotationRow(
      GroupStatResult.Row row,
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      SignalValueThresholds thresholds) {
    String unitId = row != null ? row.unitId() : null;
    if (unitId != null && (matrix != null || bundle != null)) {
      return presentForUnit(unitId, tracks, matrix, bundle, channels, thresholds);
    }
    return presentTracksFromRowGroups(row, tracks);
  }

  /**
   * Drop units that fail Sample Comparison on any {@link ValueGrid} (compact SSOT or sparse).
   * Presence = finite cell (thresholds already applied via compact mask when applicable).
   */
  public static ValueGrid filterGridUnits(
      ValueGrid grid,
      SignalComparisonSettings settings,
      List<SampleTrack> roster) {
    if (grid == null || grid.isEmpty() || settings == null || !settings.hasActiveConstraints()) {
      return grid;
    }
    List<SampleTrack> tracks = roster != null && !roster.isEmpty()
        ? roster
        : new ArrayList<>(grid.tracks());
    Map<SampleTag, List<SampleTrack>> byTag = indexByTag(tracks);
    Set<String> keep = new LinkedHashSet<>();
    for (String unitId : grid.unitIds()) {
      Set<SampleTrack> present = presentFromGrid(grid, unitId, tracks, null);
      if (passesUnit(present, settings, byTag)) {
        keep.add(unitId);
      }
    }
    if (keep.size() == grid.size()) {
      return grid;
    }
    return new FilteredValueGrid(grid, keep);
  }

  /**
   * Present = finite value that passes thresholds when provided; otherwise finite cell.
   * Multi-channel: any channel passes ⇒ present.
   */
  static Set<SampleTrack> presentForUnit(
      String unitId,
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      SignalValueThresholds thresholds) {
    Set<SampleTrack> present = new LinkedHashSet<>();
    if (unitId == null || tracks == null) {
      return present;
    }
    if (bundle != null && channels != null && !channels.isEmpty()) {
      for (SampleTrack track : tracks) {
        if (samplePassesAnyChannel(bundle, channels, unitId, track)) {
          present.add(track);
        }
      }
      return present;
    }
    FeatureValueMatrix source = matrix;
    if (source == null && bundle != null) {
      source = bundle.primaryMatrix();
    }
    if (source == null) {
      return present;
    }
    SignalValueThresholds thr = thresholds != null ? thresholds : new SignalValueThresholds();
    for (SampleTrack track : tracks) {
      Double v = source.get(unitId, track);
      if (v != null && Double.isFinite(v) && thr.passes(v)) {
        present.add(track);
      }
    }
    return present;
  }

  private static Set<SampleTrack> presentFromGrid(
      ValueGrid grid, String unitId, List<SampleTrack> tracks, SignalValueThresholds thresholds) {
    Set<SampleTrack> present = new LinkedHashSet<>();
    if (grid == null || unitId == null || tracks == null) {
      return present;
    }
    // Compact SSOT is already NaN-masked — ignore thr. Sparse collect still uses thr.
    // Require hasValue so missingDefault fills (e.g. LOH matrix 0.0) are not “present”.
    boolean applyThr = thresholds != null && thresholds.isEnabled()
        && !(grid instanceof CompactTableGrid);
    for (SampleTrack track : tracks) {
      if (track == null || !grid.hasValue(unitId, track)) {
        continue;
      }
      Double v = grid.get(unitId, track);
      if (v == null || !Double.isFinite(v)) {
        continue;
      }
      if (applyThr && !thresholds.passes(v)) {
        continue;
      }
      // LOH binary grids may store explicit 0 for non-LOH; that is not “present”.
      if (v == 0.0 && thresholds != null && thresholds.isLohOnly()) {
        continue;
      }
      present.add(track);
    }
    return present;
  }

  private static boolean samplePassesAnyChannel(
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      String unitId,
      SampleTrack track) {
    if (bundle == null || track == null || unitId == null || channels == null) {
      return false;
    }
    for (SignalChannel ch : channels) {
      if (ch == null) {
        continue;
      }
      FeatureValueMatrix m = bundle.matrix(ch.id());
      if (m == null) {
        continue;
      }
      Double v = m.get(unitId, track);
      if (v != null && Double.isFinite(v) && ch.thresholds().passes(v)) {
        return true;
      }
    }
    return false;
  }

  /**
   * When channels are allele-CN only and allowed directions are set, return that set;
   * otherwise null (caller uses threshold/finite presence).
   */
  private static Set<SignalEventDirection> normalizeAllowed(
      Collection<SignalEventDirection> allowedDirections,
      List<SignalChannel> channels) {
    if (!SignalChannels.isAlleleCnOnly(channels)) {
      return null;
    }
    if (allowedDirections == null) {
      return SignalEventDirection.defaultAllowed();
    }
    EnumSet<SignalEventDirection> out = EnumSet.noneOf(SignalEventDirection.class);
    for (SignalEventDirection d : allowedDirections) {
      if (d != null && d != SignalEventDirection.NONE) {
        out.add(d);
      }
    }
    return out;
  }

  /** Present when Minor+Major classify to an allowed event type. */
  private static Set<SampleTrack> presentAlleleCn(
      String unitId,
      List<SampleTrack> tracks,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      Set<SignalEventDirection> allowed) {
    Set<SampleTrack> present = new LinkedHashSet<>();
    if (unitId == null || tracks == null || bundle == null || allowed == null) {
      return present;
    }
    for (SampleTrack track : tracks) {
      SignalEventDirection dir =
          SignalEventDirection.fromBundleSample(bundle, channels, unitId, track);
      if (allowed.contains(dir)) {
        present.add(track);
      }
    }
    return present;
  }

  /** Gene locus for allele-CN rows ({@code TP53} from {@code TP53::LOH}). */
  private static String alleleCnLocusId(GroupStatResult.Row row) {
    if (row == null) {
      return null;
    }
    if (row.geneName() != null && !row.geneName().isBlank()) {
      return row.geneName();
    }
    return SignalEventDirection.locusUnitId(row.unitId());
  }

  /**
   * Gene×event rows count only that event type (intersected with the UI type filter).
   */
  private static Set<SignalEventDirection> allowedForRow(
      GroupStatResult.Row row, Set<SignalEventDirection> allowed) {
    SignalEventDirection tagged = SignalEventDirection.parseRowDirection(
        row != null ? row.direction() : null);
    if (tagged == SignalEventDirection.NONE) {
      return allowed;
    }
    if (allowed == null || !allowed.contains(tagged)) {
      return EnumSet.noneOf(SignalEventDirection.class);
    }
    return EnumSet.of(tagged);
  }

  private static Set<SampleTrack> presentTracksFromRowGroups(
      GroupStatResult.Row row, List<SampleTrack> roster) {
    Set<SampleTrack> present = new LinkedHashSet<>();
    if (row == null || row.groups() == null) {
      return present;
    }
    Map<String, SampleTrack> byName = new java.util.HashMap<>();
    for (SampleTrack t : roster) {
      if (t != null && t.getDisplayName() != null) {
        byName.put(t.getDisplayName(), t);
      }
    }
    for (GroupStatResult.GroupSummary g : row.groups()) {
      if (g == null || g.groupName() == null) {
        continue;
      }
      SampleTrack track = byName.get(g.groupName());
      if (track != null) {
        present.add(track);
      }
    }
    return present;
  }

  private static boolean[] evaluateFlankingRowClusters(
      List<GroupStatResult.Row> rows,
      SignalComparisonSettings settings,
      Map<SampleTag, List<SampleTrack>> byTag,
      Function<GroupStatResult.Row, Set<SampleTrack>> presentOf) {
    boolean[] keep = new boolean[rows.size()];
    List<Integer> order = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator
        .comparing((Integer i) -> chromKey(rows.get(i)), String.CASE_INSENSITIVE_ORDER)
        .thenComparingLong(i -> Math.max(0L, rows.get(i).start1Based())));

    int window = settings.getComparisonWindowBp();
    int clusterStart = 0;
    while (clusterStart < order.size()) {
      int clusterEnd = clusterStart;
      GroupStatResult.Row first = rows.get(order.get(clusterStart));
      long clusterEndPos = Math.max(first.end1Based(), first.start1Based());
      String chrom = chromKey(first);
      while (clusterEnd + 1 < order.size()) {
        GroupStatResult.Row next = rows.get(order.get(clusterEnd + 1));
        if (!chrom.equalsIgnoreCase(chromKey(next))) {
          break;
        }
        long nextStart = Math.max(0L, next.start1Based());
        if (nextStart - clusterEndPos > window) {
          break;
        }
        clusterEnd++;
        clusterEndPos = Math.max(clusterEndPos, Math.max(next.end1Based(), nextStart));
      }
      Set<SampleTrack> union = new LinkedHashSet<>();
      for (int c = clusterStart; c <= clusterEnd; c++) {
        union.addAll(presentOf.apply(rows.get(order.get(c))));
      }
      boolean pass = passesUnit(union, settings, byTag);
      for (int c = clusterStart; c <= clusterEnd; c++) {
        keep[order.get(c)] = pass;
      }
      clusterStart = clusterEnd + 1;
    }
    return keep;
  }

  private static boolean[] evaluateFlankingMatrixClusters(
      FeatureValueMatrix matrix,
      List<String> unitIds,
      SignalComparisonSettings settings,
      Map<SampleTag, List<SampleTrack>> byTag,
      Function<String, Set<SampleTrack>> presentOf) {
    boolean[] keep = new boolean[unitIds.size()];
    int window = settings.getComparisonWindowBp();
    int clusterStart = 0;
    while (clusterStart < unitIds.size()) {
      int clusterEnd = clusterStart;
      FeatureValueMatrix.Locus first = matrix.locusOf(unitIds.get(clusterStart));
      long clusterEndPos = locusEnd(first);
      String chrom = chromKey(first);
      while (clusterEnd + 1 < unitIds.size()) {
        FeatureValueMatrix.Locus next = matrix.locusOf(unitIds.get(clusterEnd + 1));
        if (!chrom.equalsIgnoreCase(chromKey(next))) {
          break;
        }
        long nextStart = locusStart(next);
        if (nextStart - clusterEndPos > window) {
          break;
        }
        clusterEnd++;
        clusterEndPos = Math.max(clusterEndPos, locusEnd(next));
      }
      Set<SampleTrack> union = new LinkedHashSet<>();
      for (int c = clusterStart; c <= clusterEnd; c++) {
        union.addAll(presentOf.apply(unitIds.get(c)));
      }
      boolean pass = passesUnit(union, settings, byTag);
      for (int c = clusterStart; c <= clusterEnd; c++) {
        keep[c] = pass;
      }
      clusterStart = clusterEnd + 1;
    }
    return keep;
  }

  private static Map<SampleTag, List<SampleTrack>> indexByTag(List<SampleTrack> tracks) {
    Map<SampleTag, List<SampleTrack>> byTag = new EnumMap<>(SampleTag.class);
    for (SampleTrack track : tracks) {
      if (track == null || track.getTags() == null) {
        continue;
      }
      for (SampleTag tag : track.getTags()) {
        if (tag == null) {
          continue;
        }
        byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(track);
      }
    }
    return byTag;
  }

  private static String chromKey(GroupStatResult.Row row) {
    return row != null && row.chromosome() != null ? row.chromosome() : "";
  }

  private static String chromKey(FeatureValueMatrix.Locus locus) {
    return locus != null && locus.chromosome() != null ? locus.chromosome() : "";
  }

  private static long locusStart(FeatureValueMatrix.Locus locus) {
    return locus != null ? Math.max(0L, locus.start1Based()) : 0L;
  }

  private static long locusEnd(FeatureValueMatrix.Locus locus) {
    if (locus == null) {
      return 0L;
    }
    return Math.max(locus.end1Based(), locus.start1Based());
  }
}
