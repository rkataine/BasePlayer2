package org.baseplayer.analysis.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.signal.SignalAnnotator;
import org.baseplayer.analysis.signal.SignalChannel;
import org.baseplayer.samples.SampleTrack;

/**
 * Streaming TSV writers for MOFA-style signal long tables and comparison effect tables.
 */
public final class AnalysisLongTsvExport {

  private AnalysisLongTsvExport() {}

  /** Headers for {@link #writeSignalMofaLong}. */
  public static final List<String> SIGNAL_HEADERS = List.of(
      "sample", "feature", "gene_name", "view", "value", "chrom", "start", "end");

  /** Headers for {@link #writeSignalAnnotation}. */
  public static final List<String> ANNOTATION_HEADERS = List.of(
      "feature",
      "gene_name",
      "chrom",
      "start",
      "end",
      "value",
      "n_signal",
      "samples",
      "direction");

  /** Headers for {@link #writeComparisonEffects}. */
  public static final List<String> COMPARISON_HEADERS = List.of(
      "feature",
      "gene_name",
      "chrom",
      "start",
      "end",
      "effect",
      "p",
      "q",
      "direction",
      "test",
      "mode");

  public static void writeSignalMofaLong(Path path) throws IOException {
    writeSignalMofaLong(path, AnalysisSession.get());
  }

  public static void writeSignalMofaLong(Path path, AnalysisSession session) throws IOException {
    if (path == null || session == null) {
      throw new IllegalArgumentException("path/session required");
    }
    try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writeSignalMofaLong(w, session);
    }
  }

  /** Package-visible for tests. */
  static void writeSignalMofaLong(Writer w, AnalysisSession session) throws IOException {
    writeHeader(w, SIGNAL_HEADERS);
    Map<String, ValueGrid> views = signalViews(session);
    if (views.isEmpty()) {
      return;
    }
    for (Map.Entry<String, ValueGrid> entry : views.entrySet()) {
      String view = entry.getKey();
      ValueGrid grid = entry.getValue();
      if (grid == null || grid.isEmpty()) {
        continue;
      }
      List<SampleTrack> tracks = orderedTracks(grid);
      for (String unitId : grid.unitIds()) {
        if (unitId == null || unitId.isBlank()) {
          continue;
        }
        FeatureValueMatrix.Locus locus = grid.locusOf(unitId);
        String chrom = locus != null && locus.chromosome() != null ? locus.chromosome() : "";
        String start = locus != null ? Long.toString(locus.start1Based()) : "";
        String end = locus != null ? Long.toString(locus.end1Based()) : "";
        for (SampleTrack track : tracks) {
          if (track == null || !grid.hasValue(unitId, track)) {
            continue;
          }
          Double v = grid.get(unitId, track);
          if (v == null || !Double.isFinite(v)) {
            continue;
          }
          writeRow(
              w,
              List.of(
                  tsv(track.getDisplayName()),
                  tsv(unitId),
                  tsv(unitId),
                  tsv(view),
                  fmt(v),
                  tsv(chrom),
                  start,
                  end));
        }
      }
    }
  }

  public static void writeSignalAnnotation(Path path) throws IOException {
    writeSignalAnnotation(path, AnalysisSession.get());
  }

  public static void writeSignalAnnotation(Path path, AnalysisSession session) throws IOException {
    if (path == null || session == null) {
      throw new IllegalArgumentException("path/session required");
    }
    try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writeSignalAnnotation(w, session);
    }
  }

  static void writeSignalAnnotation(Writer w, AnalysisSession session) throws IOException {
    writeHeader(w, ANNOTATION_HEADERS);
    GroupStatResult result = session.getAnnotationResult();
    if (result == null || result.rows() == null || result.rows().isEmpty()) {
      return;
    }
    boolean loh = SignalAnnotator.isLohAnnotation(result);
    for (GroupStatResult.Row row : result.rows()) {
      if (row == null) {
        continue;
      }
      String feature = row.unitId() != null ? row.unitId() : "";
      String gene = row.geneName() != null ? row.geneName() : feature;
      String value = SignalAnnotator.formatAnnotationValue(row, loh);
      int nSignal = 0;
      StringBuilder samples = new StringBuilder();
      if (row.groups() != null) {
        for (GroupStatResult.GroupSummary g : row.groups()) {
          if (g == null || g.groupName() == null) {
            continue;
          }
          // Annotation rows: one GroupSummary per deviant sample (name = sample).
          if (g.groupName().startsWith("Cohort")) {
            continue;
          }
          if (loh && !(Double.isFinite(g.mean()) && g.mean() > 0.0)) {
            continue;
          }
          nSignal++;
          if (samples.length() > 0) {
            samples.append(';');
          }
          samples.append(g.groupName());
        }
      }
      writeRow(
          w,
          List.of(
              tsv(feature),
              tsv(gene),
              tsv(row.chromosome()),
              Long.toString(row.start1Based()),
              Long.toString(row.end1Based()),
              tsv(value),
              Integer.toString(nSignal),
              tsv(samples.toString()),
              tsv(row.direction())));
    }
  }

  public static void writeComparisonEffects(Path path) throws IOException {
    writeComparisonEffects(path, AnalysisSession.get());
  }

  public static void writeComparisonEffects(Path path, AnalysisSession session) throws IOException {
    if (path == null || session == null) {
      throw new IllegalArgumentException("path/session required");
    }
    try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writeComparisonEffects(w, session);
    }
  }

  static void writeComparisonEffects(Writer w, AnalysisSession session) throws IOException {
    writeHeader(w, COMPARISON_HEADERS);
    for (GroupStatResult result : comparisonResults(session)) {
      if (result.rows() == null) {
        continue;
      }
      String mode = result.mode() != null ? result.mode().name() : "";
      for (GroupStatResult.Row row : result.rows()) {
        if (row == null) {
          continue;
        }
        String feature = row.unitId() != null ? row.unitId() : "";
        String gene = row.geneName() != null ? row.geneName() : feature;
        writeRow(
            w,
            List.of(
                tsv(feature),
                tsv(gene),
                tsv(row.chromosome()),
                Long.toString(row.start1Based()),
                Long.toString(row.end1Based()),
                fmt(row.effectVsBaseline()),
                fmt(row.pValue()),
                fmt(row.qValue()),
                tsv(row.direction()),
                tsv(row.testName()),
                tsv(mode)));
      }
    }
  }

  /** Non-annotation group comparison results currently in the session (filtered preferred). */
  public static List<GroupStatResult> comparisonResults(AnalysisSession session) {
    List<GroupStatResult> out = new ArrayList<>();
    if (session == null) {
      return out;
    }
    for (AnalysisMode mode : AnalysisMode.values()) {
      GroupStatResult result = session.getFilteredResult(mode);
      if (result == null || result.rows() == null || result.rows().isEmpty()) {
        result = session.getResult(mode);
      }
      if (result == null || result.rows() == null || result.rows().isEmpty()) {
        continue;
      }
      if (SignalAnnotator.isAnnotationResult(result)) {
        continue;
      }
      out.add(result);
    }
    return out;
  }

  public static boolean hasSignalValues(AnalysisSession session) {
    if (session == null) {
      return false;
    }
    FeatureValueBundle bundle = session.getSignalBundle();
    if (bundle != null && !bundle.isEmpty()) {
      return true;
    }
    ValueGrid grid = session.getSignalGrid();
    return grid != null && !grid.isEmpty();
  }

  public static boolean hasComparisonEffects(AnalysisSession session) {
    return !comparisonResults(session).isEmpty();
  }

  public static boolean hasAnnotation(AnalysisSession session) {
    if (session == null) {
      return false;
    }
    GroupStatResult result = session.getAnnotationResult();
    return result != null && result.rows() != null && !result.rows().isEmpty();
  }

  private static Map<String, ValueGrid> signalViews(AnalysisSession session) {
    Map<String, ValueGrid> views = new LinkedHashMap<>();
    FeatureValueBundle bundle = session.getSignalBundle();
    if (bundle != null && !bundle.isEmpty()) {
      List<SignalChannel> channels = bundle.channels();
      if (!channels.isEmpty()) {
        for (SignalChannel ch : channels) {
          FeatureValueMatrix m = bundle.matrix(ch.id());
          if (m != null && !m.isEmpty()) {
            views.put(viewName(ch), m);
          }
        }
        return views;
      }
    }
    ValueGrid grid = session.getSignalGrid();
    if (grid != null && !grid.isEmpty()) {
      views.put("signal", grid);
    }
    return views;
  }

  private static String viewName(SignalChannel ch) {
    if (ch == null) {
      return "signal";
    }
    String label = ch.label();
    if (label != null && !label.isBlank()) {
      return label;
    }
    return ch.id() != null ? ch.id() : "signal";
  }

  private static List<SampleTrack> orderedTracks(ValueGrid grid) {
    if (grid == null) {
      return List.of();
    }
    return new ArrayList<>(grid.tracks());
  }

  private static void writeHeader(Writer w, List<String> headers) throws IOException {
    writeRow(w, headers);
  }

  private static void writeRow(Writer w, List<String> cells) throws IOException {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        w.write('\t');
      }
      w.write(cells.get(i) != null ? cells.get(i) : "");
    }
    w.write('\n');
  }

  private static String tsv(String s) {
    if (s == null) {
      return "";
    }
    if (s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
      return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
    return s;
  }

  private static String fmt(Double v) {
    if (v == null || Double.isNaN(v)) {
      return "";
    }
    return Double.toString(v);
  }
}
