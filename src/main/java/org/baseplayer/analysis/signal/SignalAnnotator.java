package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.utils.GeneBiotypeVisibility;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.grid.CompactTableGrid;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.groupstat.GroupStatResult;

/**
 * Builds a position-sorted gene/feature listing from a collected
 * {@link FeatureValueMatrix} without requiring sample groups.
 */
public final class SignalAnnotator {

  /**
   * When many samples pass, keep one cohort summary plus at most this many
   * extreme per-sample rows for table expand (avoids units×samples heap blow-up).
   */
  static final int MAX_EXPAND_SAMPLES = 20;

  private SignalAnnotator() {}

  /**
   * Multi-channel annotate: keep gene if any <strong>sample × channel</strong> passes.
   * Expand lists only those deviant samples (diploid / threshold-fail omitted).
   */
  public static GroupStatResult annotateBundle(
      AnalysisMode mode,
      FeatureValueBundle bundle,
      List<SampleTrack> tracks,
      String scoreLabel) {
    return annotateBundle(mode, bundle, null, tracks, scoreLabel);
  }

  /**
   * @param channelThresholds when non-null, used for pass/rank (refilter); data still from {@code bundle}
   */
  public static GroupStatResult annotateBundle(
      AnalysisMode mode,
      FeatureValueBundle bundle,
      List<SignalChannel> channelThresholds,
      List<SampleTrack> tracks,
      String scoreLabel) {
    if (bundle == null || bundle.isEmpty()) {
      return new GroupStatResult(
          mode != null ? mode : AnalysisMode.SIGNAL,
          List.of(),
          null,
          List.of(),
          "No signal values collected");
    }
    List<SignalChannel> channels = channelThresholds != null && !channelThresholds.isEmpty()
        ? channelThresholds
        : bundle.channels();
    if (channels.isEmpty()) {
      return new GroupStatResult(
          mode != null ? mode : AnalysisMode.SIGNAL,
          List.of(),
          null,
          List.of(),
          "No signal channels");
    }
    List<SampleTrack> sampleTracks = tracks != null ? tracks : List.of();
    if (sampleTracks.isEmpty()) {
      sampleTracks = bundle.tracks();
    }
    List<GroupStatResult.Row> rows = new ArrayList<>();
    Map<String, Map<String, Double>> channelValuesByUnit = new LinkedHashMap<>();
    int maxRows = 0;
    for (SignalChannel ch : channels) {
      maxRows = Math.max(maxRows, ch.thresholds().getMaxRows());
    }

    boolean alleleCnSplit = SignalChannels.isAlleleCnOnly(channels);
    GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
    for (String unitId : bundle.unitIds()) {
      if (!visibility.isVisibleGeneName(unitId)) {
        continue;
      }
      List<SampleTrack> roster = sampleTracks;
      boolean anyPass = false;
      for (SampleTrack track : roster) {
        if (samplePassesAnyChannel(bundle, channels, unitId, track)) {
          anyPass = true;
          break;
        }
      }
      if (!anyPass) {
        // Sparse collect may leave only non-roster tracks; fall back to matrix tracks.
        roster = new ArrayList<>(bundle.tracks());
        for (SampleTrack track : roster) {
          if (samplePassesAnyChannel(bundle, channels, unitId, track)) {
            anyPass = true;
            break;
          }
        }
      }
      if (!anyPass) {
        continue;
      }

      FeatureValueMatrix.Locus locus = null;
      for (SignalChannel ch : channels) {
        FeatureValueMatrix matrix = bundle.matrix(ch.id());
        if (matrix != null) {
          locus = matrix.locusOf(unitId);
          if (locus != null) {
            break;
          }
        }
      }

      if (alleleCnSplit) {
        appendAlleleCnEventRows(
            rows, channelValuesByUnit, bundle, channels, unitId, locus, roster);
        continue;
      }

      List<SampleTrack> deviantTracks = new ArrayList<>();
      for (SampleTrack track : roster) {
        if (samplePassesAnyChannel(bundle, channels, unitId, track)) {
          deviantTracks.add(track);
        }
      }
      if (deviantTracks.isEmpty()) {
        continue;
      }

      Map<String, Double> channelMeans = channelMeansForTracks(bundle, channels, unitId, deviantTracks);
      if (channelMeans.isEmpty()) {
        continue;
      }
      Double primaryMean = channelMeans.values().iterator().next();
      List<GroupStatResult.GroupSummary> summaries =
          sampleSummaries(bundle.primaryMatrix(), unitId, deviantTracks);
      channelValuesByUnit.put(unitId, Map.copyOf(channelMeans));
      rows.add(new GroupStatResult.Row(
          unitId,
          unitId,
          locus != null ? locus.chromosome() : null,
          locus != null ? locus.start1Based() : 0L,
          locus != null ? locus.end1Based() : 0L,
          summaries,
          primaryMean,
          null,
          null,
          "annotation",
          "channels"));
    }

    rows.sort(Comparator
        .comparing((GroupStatResult.Row r) -> chromSortKey(r.chromosome()), String.CASE_INSENSITIVE_ORDER)
        .thenComparingLong(r -> Math.max(0L, r.start1Based()))
        .thenComparing(r -> r.geneName() != null ? r.geneName() : "", String.CASE_INSENSITIVE_ORDER)
        .thenComparingInt(r -> dirSortKey(SignalEventDirection.parseRowDirection(r.direction()))));

    if (maxRows > 0 && rows.size() > maxRows) {
      List<GroupStatResult.Row> byMag = new ArrayList<>(rows);
      byMag.sort(Comparator.comparingDouble((GroupStatResult.Row r) ->
          rankAcrossChannels(r.unitId(), channels, channelValuesByUnit)).reversed());
      rows = new ArrayList<>(byMag.subList(0, maxRows));
      rows.sort(Comparator
          .comparing((GroupStatResult.Row r) -> chromSortKey(r.chromosome()), String.CASE_INSENSITIVE_ORDER)
          .thenComparingLong(r -> Math.max(0L, r.start1Based())));
    }

    String score = scoreLabel != null && !scoreLabel.isBlank() ? scoreLabel : "Signal channels";
    String methodStrip = String.format(
        Locale.ROOT,
        alleleCnSplit
            ? "Annotation: %s · %d channels · one row per gene×event · Sorted by position"
            : "Annotation: %s · %d channels · deviant samples only · Sorted by position",
        score,
        channels.size());
    String summary = String.format(
        Locale.ROOT,
        "Annotated %d units from %d sample%s (%d channels)",
        rows.size(),
        Math.max(1, sampleTracks.size()),
        sampleTracks.size() == 1 ? "" : "s",
        channels.size());
    return new GroupStatResult(
        mode != null ? mode : AnalysisMode.SIGNAL,
        List.of(),
        null,
        rows,
        summary,
        methodStrip,
        channels,
        channelValuesByUnit);
  }

  /**
   * Allele-CN: one annotation row per event type present at the gene
   * (e.g. TP53 LOH n=5, TP53 Loss n=6, TP53 Gain n=1).
   */
  private static void appendAlleleCnEventRows(
      List<GroupStatResult.Row> rows,
      Map<String, Map<String, Double>> channelValuesByUnit,
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      String geneUnitId,
      FeatureValueMatrix.Locus locus,
      List<SampleTrack> roster) {
    Map<SignalEventDirection, List<SampleTrack>> byDir = new EnumMap<>(SignalEventDirection.class);
    for (SampleTrack track : roster) {
      if (!samplePassesAnyChannel(bundle, channels, geneUnitId, track)) {
        continue;
      }
      SignalEventDirection dir =
          SignalEventDirection.fromBundleSample(bundle, channels, geneUnitId, track);
      if (dir == SignalEventDirection.NONE) {
        continue;
      }
      byDir.computeIfAbsent(dir, d -> new ArrayList<>()).add(track);
    }
    for (SignalEventDirection dir : SignalEventDirection.filterTypes()) {
      List<SampleTrack> typeTracks = byDir.get(dir);
      if (typeTracks == null || typeTracks.isEmpty()) {
        continue;
      }
      Map<String, Double> channelMeans =
          channelMeansForTracks(bundle, channels, geneUnitId, typeTracks);
      if (channelMeans.isEmpty()) {
        continue;
      }
      Double primaryMean = channelMeans.values().iterator().next();
      List<GroupStatResult.GroupSummary> summaries =
          sampleSummaries(bundle.primaryMatrix(), geneUnitId, typeTracks);
      String rowUnitId = SignalEventDirection.rowUnitId(geneUnitId, dir);
      channelValuesByUnit.put(rowUnitId, Map.copyOf(channelMeans));
      rows.add(new GroupStatResult.Row(
          rowUnitId,
          geneUnitId,
          locus != null ? locus.chromosome() : null,
          locus != null ? locus.start1Based() : 0L,
          locus != null ? locus.end1Based() : 0L,
          summaries,
          primaryMean,
          null,
          null,
          "annotation",
          dir.name()));
    }
  }

  private static Map<String, Double> channelMeansForTracks(
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      String unitId,
      List<SampleTrack> tracks) {
    Map<String, Double> channelMeans = new LinkedHashMap<>();
    for (SignalChannel ch : channels) {
      FeatureValueMatrix matrix = bundle.matrix(ch.id());
      if (matrix == null) {
        continue;
      }
      List<Double> vals = matrix.valuesFor(unitId, tracks);
      double mean = GroupStatEngine.mean(vals);
      if (Double.isFinite(mean)) {
        channelMeans.put(ch.id(), mean);
      }
    }
    return channelMeans;
  }

  private static List<GroupStatResult.GroupSummary> sampleSummaries(
      FeatureValueMatrix primary, String unitId, List<SampleTrack> tracks) {
    List<GroupStatResult.GroupSummary> summaries = new ArrayList<>();
    if (primary == null || tracks == null || tracks.isEmpty()) {
      return summaries;
    }
    if (tracks.size() == 1) {
      SampleTrack only = tracks.get(0);
      String name = only.getDisplayName() != null ? only.getDisplayName() : "Value";
      List<Double> one = primary.valuesFor(unitId, List.of(only));
      int nObs = primary.observedCount(unitId, List.of(only));
      summaries.add(GroupStatEngine.summarizeGroup(-1, name, Math.max(1, nObs), nObs, one));
      return summaries;
    }
    for (int i = 0; i < tracks.size(); i++) {
      SampleTrack track = tracks.get(i);
      List<Double> one = primary.valuesFor(unitId, List.of(track));
      int nObs = primary.observedCount(unitId, List.of(track));
      String name = track.getDisplayName() != null ? track.getDisplayName() : "Sample " + (i + 1);
      summaries.add(GroupStatEngine.summarizeGroup(i, name, 1, nObs, one));
    }
    return summaries;
  }

  private static int dirSortKey(SignalEventDirection dir) {
    if (dir == null) {
      return 99;
    }
    return switch (dir) {
      case GAIN -> 0;
      case LOSS -> 1;
      case LOH -> 2;
      case NONE -> 99;
    };
  }

  /**
   * Samples that currently pass multi-channel annotation thresholds for {@code unitId}
   * (same rules as {@link #annotateBundle}).
   */
  public static List<SampleTrack> deviantTracksForUnit(
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      List<SampleTrack> tracks,
      String unitId) {
    if (bundle == null || unitId == null || unitId.isBlank()) {
      return List.of();
    }
    List<SignalChannel> chans = channels != null && !channels.isEmpty()
        ? channels
        : bundle.channels();
    if (chans.isEmpty()) {
      return List.of();
    }
    List<SampleTrack> roster = tracks != null && !tracks.isEmpty()
        ? tracks
        : new ArrayList<>(bundle.tracks());
    List<SampleTrack> out = new ArrayList<>();
    for (SampleTrack track : roster) {
      if (samplePassesAnyChannel(bundle, chans, unitId, track)) {
        out.add(track);
      }
    }
    if (out.isEmpty() && tracks != null && !tracks.isEmpty()) {
      for (SampleTrack track : bundle.tracks()) {
        if (samplePassesAnyChannel(bundle, chans, unitId, track)) {
          out.add(track);
        }
      }
    }
    return out;
  }

  /**
   * Samples that currently pass single-grid annotation thresholds for {@code unitId}
   * (same rules as {@link #annotate}).
   */
  public static List<SampleTrack> deviantTracksForUnit(
      ValueGrid matrix,
      List<SampleTrack> tracks,
      SignalValueThresholds thresholds,
      String unitId) {
    if (matrix == null || matrix.isEmpty() || unitId == null || unitId.isBlank()) {
      return List.of();
    }
    SignalValueThresholds thr = thresholds != null ? thresholds : new SignalValueThresholds();
    List<SampleTrack> roster = tracks != null && !tracks.isEmpty()
        ? tracks
        : new ArrayList<>(matrix.tracks());
    List<SampleTrack> out = new ArrayList<>();
    for (SampleTrack track : roster) {
      Double v = matrix.get(unitId, track);
      if (v != null && thr.passes(v)) {
        out.add(track);
      }
    }
    return out;
  }

  /** True when any channel has a finite value that passes its thresholds for this sample. */
  private static boolean samplePassesAnyChannel(
      FeatureValueBundle bundle,
      List<SignalChannel> channels,
      String unitId,
      SampleTrack track) {
    if (bundle == null || track == null || unitId == null || channels == null) {
      return false;
    }
    for (SignalChannel ch : channels) {
      FeatureValueMatrix matrix = bundle.matrix(ch.id());
      if (matrix == null) {
        continue;
      }
      Double v = matrix.get(unitId, track);
      if (v != null && Double.isFinite(v) && ch.thresholds().passes(v)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param tracks sample order for per-sample summaries (may be empty)
   * @param scoreLabel short label for the method strip
   */
  public static GroupStatResult annotate(
      AnalysisMode mode,
      FeatureValueMatrix matrix,
      List<SampleTrack> tracks,
      SignalValueThresholds thresholds,
      String scoreLabel) {
    return annotate(mode, (ValueGrid) matrix, tracks, thresholds, scoreLabel);
  }

  /**
   * Annotate from any {@link ValueGrid} (compact gene-matrix SSOT or sparse collect matrix).
   * When values are already masked in the SSOT, pass thresholds with filter off — presence
   * is simply a finite cell.
   */
  public static GroupStatResult annotate(
      AnalysisMode mode,
      ValueGrid matrix,
      List<SampleTrack> tracks,
      SignalValueThresholds thresholds,
      String scoreLabel) {
    if (matrix == null || matrix.isEmpty()) {
      return new GroupStatResult(
          mode != null ? mode : AnalysisMode.SIGNAL,
          List.of(),
          null,
          List.of(),
          "No signal values collected");
    }
    SignalValueThresholds thr = thresholds != null ? thresholds : new SignalValueThresholds();
    List<SampleTrack> sampleTracks = tracks != null ? tracks : List.of();
    List<GroupStatResult.Row> rows = new ArrayList<>();
    GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();

    for (String unitId : matrix.unitIds()) {
      if (!visibility.isVisibleGeneName(unitId)) {
        continue;
      }
      List<SampleTrack> roster = !sampleTracks.isEmpty()
          ? sampleTracks
          : new ArrayList<>(matrix.tracks());
      List<SampleTrack> deviantTracks = new ArrayList<>();
      for (SampleTrack track : roster) {
        Double v = matrix.get(unitId, track);
        if (v != null && thr.passes(v)) {
          deviantTracks.add(track);
        }
      }
      if (deviantTracks.isEmpty()) {
        continue;
      }
      List<Double> vals = matrix.valuesFor(unitId, deviantTracks);
      double mean = GroupStatEngine.mean(vals);
      List<GroupStatResult.GroupSummary> summaries = buildAnnotationSummaries(
          matrix, unitId, deviantTracks, vals);
      FeatureValueMatrix.Locus locus = matrix.locusOf(unitId);
      rows.add(new GroupStatResult.Row(
          unitId,
          unitId,
          locus != null ? locus.chromosome() : null,
          locus != null ? locus.start1Based() : 0L,
          locus != null ? locus.end1Based() : 0L,
          summaries,
          Double.isFinite(mean) ? mean : null,
          null,
          null,
          "annotation",
          "value"));
    }

    rows.sort(Comparator
        .comparing((GroupStatResult.Row r) -> chromSortKey(r.chromosome()), String.CASE_INSENSITIVE_ORDER)
        .thenComparingLong(r -> Math.max(0L, r.start1Based()))
        .thenComparing(r -> r.geneName() != null ? r.geneName() : "", String.CASE_INSENSITIVE_ORDER));

    if (thr.getMaxRows() > 0 && rows.size() > thr.getMaxRows()) {
      List<GroupStatResult.Row> byMag = new ArrayList<>(rows);
      byMag.sort(Comparator.comparingDouble((GroupStatResult.Row r) -> {
        Double v = annotationValue(r);
        return v != null && Double.isFinite(v) ? thr.rankMagnitude(v) : 0.0;
      }).reversed());
      rows = new ArrayList<>(byMag.subList(0, thr.getMaxRows()));
      rows.sort(Comparator
          .comparing((GroupStatResult.Row r) -> chromSortKey(r.chromosome()), String.CASE_INSENSITIVE_ORDER)
          .thenComparingLong(r -> Math.max(0L, r.start1Based())));
    }

    String score = scoreLabel != null && !scoreLabel.isBlank() ? scoreLabel : "Signal";
    String thrPart = formatThr(thr);
    String methodStrip = String.format(
        Locale.ROOT,
        "Annotation: %s · Sorted by position · Thresholds: %s",
        score,
        thrPart);
    String summary = String.format(
        Locale.ROOT,
        "Annotated %d units from %d sample%s",
        rows.size(),
        Math.max(1, sampleTracks.size()),
        sampleTracks.size() == 1 ? "" : "s");
    return new GroupStatResult(
        mode != null ? mode : AnalysisMode.SIGNAL,
        List.of(),
        null,
        rows,
        summary,
        methodStrip);
  }

  /**
   * One sample → one summary. Few passers → per-sample expand.
   * Many passers (typical gene-matrix) → cohort summary + top-|value| samples only.
   */
  static List<GroupStatResult.GroupSummary> buildAnnotationSummaries(
      ValueGrid matrix,
      String unitId,
      List<SampleTrack> deviantTracks,
      List<Double> cohortVals) {
    List<GroupStatResult.GroupSummary> summaries = new ArrayList<>();
    if (deviantTracks == null || deviantTracks.isEmpty()) {
      return summaries;
    }
    if (deviantTracks.size() == 1) {
      SampleTrack only = deviantTracks.get(0);
      String name = only.getDisplayName() != null ? only.getDisplayName() : "Value";
      List<Double> one = matrix.valuesFor(unitId, List.of(only));
      int nObs = matrix.observedCount(unitId, List.of(only));
      summaries.add(GroupStatEngine.summarizeGroup(-1, name, Math.max(1, nObs), nObs, one));
      return summaries;
    }
    boolean slim = matrix instanceof CompactTableGrid
        || deviantTracks.size() > MAX_EXPAND_SAMPLES;
    if (slim) {
      int nObs = cohortVals != null ? cohortVals.size() : 0;
      summaries.add(GroupStatEngine.summarizeGroup(
          -1,
          "Cohort (" + deviantTracks.size() + " samples)",
          deviantTracks.size(),
          nObs,
          cohortVals != null ? cohortVals : List.of()));
      List<SampleTrack> top = topExtremeTracks(matrix, unitId, deviantTracks, MAX_EXPAND_SAMPLES);
      for (int i = 0; i < top.size(); i++) {
        SampleTrack track = top.get(i);
        List<Double> one = matrix.valuesFor(unitId, List.of(track));
        int obs = matrix.observedCount(unitId, List.of(track));
        String name = track.getDisplayName() != null ? track.getDisplayName() : "Sample " + (i + 1);
        summaries.add(GroupStatEngine.summarizeGroup(i, name, 1, obs, one));
      }
      return summaries;
    }
    for (int i = 0; i < deviantTracks.size(); i++) {
      SampleTrack track = deviantTracks.get(i);
      List<Double> one = matrix.valuesFor(unitId, List.of(track));
      int nObs = matrix.observedCount(unitId, List.of(track));
      String name = track.getDisplayName() != null ? track.getDisplayName() : "Sample " + (i + 1);
      summaries.add(GroupStatEngine.summarizeGroup(i, name, 1, nObs, one));
    }
    return summaries;
  }

  private static List<SampleTrack> topExtremeTracks(
      ValueGrid matrix,
      String unitId,
      List<SampleTrack> tracks,
      int limit) {
    if (tracks == null || tracks.isEmpty() || limit <= 0) {
      return List.of();
    }
    record Hit(SampleTrack track, double mag) {}
    List<Hit> hits = new ArrayList<>(tracks.size());
    for (SampleTrack track : tracks) {
      Double v = matrix.get(unitId, track);
      if (v == null || !Double.isFinite(v)) {
        continue;
      }
      hits.add(new Hit(track, Math.abs(v)));
    }
    hits.sort(Comparator.comparingDouble(Hit::mag).reversed());
    int n = Math.min(limit, hits.size());
    List<SampleTrack> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(hits.get(i).track());
    }
    return out;
  }

  /** True when this result came from {@link #annotate} (no group ids). */
  public static boolean isAnnotationResult(GroupStatResult result) {
    return result != null && result.groupIds().isEmpty();
  }

  /** True when annotation used allele-CN LOH scores ({@link SignalValueKind#TABLE_LOH}). */
  public static boolean isLohAnnotation(GroupStatResult result) {
    if (result == null) {
      return false;
    }
    String strip = result.methodStrip();
    return strip != null && strip.contains(SignalValueKind.TABLE_LOH.name());
  }

  /** Display value for an annotation row (effect field holds the mean). */
  public static Double annotationValue(GroupStatResult.Row row) {
    if (row == null) {
      return null;
    }
    if (row.effectVsBaseline() != null && Double.isFinite(row.effectVsBaseline())) {
      return row.effectVsBaseline();
    }
    if (row.groups() == null || row.groups().isEmpty()) {
      return null;
    }
    double sum = 0;
    int n = 0;
    for (GroupStatResult.GroupSummary g : row.groups()) {
      if (g != null && Double.isFinite(g.mean())) {
        sum += g.mean();
        n++;
      }
    }
    return n > 0 ? sum / n : null;
  }

  /**
   * Human-readable Value cell: {@code LOH} / {@code k/n LOH} for LOH annotation,
   * otherwise a compact decimal.
   */
  public static String formatAnnotationValue(GroupStatResult.Row row, boolean loh) {
    if (!loh) {
      Double v = annotationValue(row);
      if (v == null || !Double.isFinite(v)) {
        return "—";
      }
      return String.format(Locale.ROOT, "%.4g", v);
    }
    return formatLohHits(row);
  }

  /** Per-sample / expanded-row LOH label. */
  public static String formatLohSampleValue(double mean) {
    if (!Double.isFinite(mean)) {
      return "—";
    }
    return mean > 0.0 ? "LOH" : "—";
  }

  /**
   * Single-sample → {@code LOH}; multi-sample → {@code k/n LOH}
   * (samples with LOH / samples).
   */
  public static String formatLohHits(GroupStatResult.Row row) {
    if (row == null || row.groups() == null || row.groups().isEmpty()) {
      Double v = annotationValue(row);
      return v != null && Double.isFinite(v) && v > 0.0 ? "LOH" : "—";
    }
    int k = 0;
    int n = 0;
    for (GroupStatResult.GroupSummary g : row.groups()) {
      if (g == null) {
        continue;
      }
      n++;
      if (Double.isFinite(g.mean()) && g.mean() > 0.0) {
        k++;
      }
    }
    if (n <= 0) {
      return "—";
    }
    if (n == 1) {
      return k > 0 ? "LOH" : "—";
    }
    return String.format(Locale.ROOT, "%d/%d LOH", k, n);
  }

  private static double rankAcrossChannels(
      String unitId,
      List<SignalChannel> channels,
      Map<String, Map<String, Double>> channelValuesByUnit) {
    Map<String, Double> vals = channelValuesByUnit.get(unitId);
    if (vals == null || channels == null) {
      return 0.0;
    }
    double best = 0.0;
    for (SignalChannel ch : channels) {
      Double v = vals.get(ch.id());
      if (v != null && Double.isFinite(v)) {
        best = Math.max(best, ch.thresholds().rankMagnitude(v));
      }
    }
    return best;
  }

  private static String chromSortKey(String chrom) {
    if (chrom == null || chrom.isBlank()) {
      return "";
    }
    return ChromosomeNames.forDisplay(chrom);
  }

  private static String formatThr(SignalValueThresholds thr) {
    StringBuilder sb = new StringBuilder();
    if (thr.isLohOnly()) {
      sb.append("LOH-only");
    }
    if (thr.isEnabled()) {
      if (thr.getMinValue() != null) {
        if (sb.length() > 0) {
          sb.append(' ');
        }
        sb.append("≥").append(formatNum(thr.getMinValue()));
      }
      if (thr.getMaxValue() != null) {
        if (sb.length() > 0) {
          sb.append(' ');
        }
        sb.append("≤").append(formatNum(thr.getMaxValue()));
      }
      if (thr.getMinAbsFromMiddle() > 0.0) {
        if (sb.length() > 0) {
          sb.append(' ');
        }
        sb.append("|v−").append(formatNum(thr.getMiddle()))
            .append("|≥").append(formatNum(thr.getMinAbsFromMiddle()));
      }
      if (sb.length() == 0 || "LOH-only".contentEquals(sb)) {
        if (sb.length() > 0) {
          sb.append(' ');
        }
        sb.append("on");
      }
    } else if (sb.length() == 0) {
      sb.append("off");
    }
    return sb.toString();
  }

  private static String formatNum(double v) {
    if (Math.abs(v - Math.rint(v)) < 1e-9) {
      return String.valueOf((long) Math.rint(v));
    }
    return String.format(Locale.ROOT, "%.3g", v);
  }
}
