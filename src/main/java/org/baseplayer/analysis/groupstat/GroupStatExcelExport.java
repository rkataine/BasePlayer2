package org.baseplayer.analysis.groupstat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import org.baseplayer.io.VariantTableExcelWriter;
import org.baseplayer.io.VariantTableExcelWriter.SheetSource;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ThresholdFilter;

/** Wide Excel export for {@link GroupStatResult} (all multiomics modes). */
public final class GroupStatExcelExport {

  private GroupStatExcelExport() {}

  /** Standalone workbook write (legacy callers). */
  public static void write(GroupStatResult result, File file) throws IOException {
    if (result == null || file == null) {
      throw new IllegalArgumentException("result/file required");
    }
    List<SheetSource> sheets = sheetSources(result);
    if (sheets.isEmpty()) {
      throw new IOException("No group comparison rows to export.");
    }
    VariantTableExcelWriter.write(file.toPath(), sheets, null);
  }

  /**
   * Sheets for the shared variant-table Excel button: one results tab per mode
   * plus a short Method tab.
   */
  public static List<SheetSource> sheetSources(GroupStatResult result) {
    if (result == null || result.rows() == null || result.rows().isEmpty()) {
      return List.of();
    }
    List<String> groupNames = orderedGroupNames(result);
    List<String> headers = buildHeaders(groupNames);
    String modeName = result.mode() != null ? result.mode().name() : "GROUP";
    String resultsName = safeSheetName("Group - " + modeName);
    List<GroupStatResult.Row> rows = List.copyOf(result.rows());

    SheetSource results = new SheetSource() {
      @Override
      public String name() {
        return resultsName;
      }

      @Override
      public List<String> headers() {
        return headers;
      }

      @Override
      public int estimatedRows() {
        return rows.size();
      }

      @Override
      public void writeRows(Consumer<List<String>> rowConsumer) {
        for (GroupStatResult.Row row : rows) {
          rowConsumer.accept(toCells(row, result, groupNames));
        }
      }
    };

    String methodText = buildMethodText(result);
    SheetSource method = new SheetSource() {
      @Override
      public String name() {
        return safeSheetName("Group method - " + modeName);
      }

      @Override
      public List<String> headers() {
        return List.of("Method");
      }

      @Override
      public int estimatedRows() {
        return 1;
      }

      @Override
      public void writeRows(Consumer<List<String>> rowConsumer) {
        rowConsumer.accept(List.of(methodText));
      }
    };

    return List.of(results, method);
  }

  /** All non-empty analysis modes currently in the session. */
  public static List<SheetSource> sheetSourcesFromSession() {
    return sheetSourcesFromSession(AnalysisSession.get());
  }

  /**
   * Sheets for every non-empty mode result plus the Signal annotation listing when present.
   */
  public static List<SheetSource> sheetSourcesFromSession(AnalysisSession session) {
    List<SheetSource> out = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
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
      appendSheets(out, used, result);
    }
    GroupStatResult annotation = session.getAnnotationResult();
    if (annotation != null
        && annotation.rows() != null
        && !annotation.rows().isEmpty()) {
      // Prefer a stable sheet name; sheetSources() would label by mode (often SIGNAL).
      List<SheetSource> annSheets = sheetSources(annotation);
      if (!annSheets.isEmpty()) {
        SheetSource results = rename(annSheets.get(0), uniqueName("Signals - annotation", used));
        used.add(results.name().toLowerCase(Locale.ROOT));
        out.add(results);
        if (annSheets.size() > 1) {
          SheetSource method = rename(annSheets.get(1), uniqueName("Signals method", used));
          used.add(method.name().toLowerCase(Locale.ROOT));
          out.add(method);
        }
      }
    }
    return out;
  }

  private static void appendSheets(
      List<SheetSource> out, Set<String> used, GroupStatResult result) {
    for (SheetSource sheet : sheetSources(result)) {
      String name = uniqueName(sheet.name(), used);
      used.add(name.toLowerCase(Locale.ROOT));
      out.add(rename(sheet, name));
    }
  }

  private static SheetSource rename(SheetSource sheet, String name) {
    return new SheetSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public List<String> headers() {
        return sheet.headers();
      }

      @Override
      public int estimatedRows() {
        return sheet.estimatedRows();
      }

      @Override
      public void writeRows(Consumer<List<String>> rowConsumer) {
        sheet.writeRows(rowConsumer);
      }
    };
  }

  private static String uniqueName(String base, Set<String> usedLower) {
    String candidate = base != null ? base : "Group";
    if (!usedLower.contains(candidate.toLowerCase(Locale.ROOT))) {
      return candidate;
    }
    int n = 2;
    while (usedLower.contains((candidate + " (" + n + ")").toLowerCase(Locale.ROOT))) {
      n++;
    }
    return candidate + " (" + n + ")";
  }

  private static List<String> buildHeaders(List<String> groupNames) {
    List<String> headers = new ArrayList<>();
    headers.add("Unit");
    headers.add("Chromosome");
    headers.add("Start");
    headers.add("End");
    headers.add("effect");
    headers.add("Delta");
    headers.add("p");
    headers.add("q");
    headers.add("Diff");
    headers.add("Test");
    headers.add("Mode");
    for (String gName : groupNames) {
      String prefix = safePrefix(gName);
      headers.add(prefix + "_nTotal");
      headers.add(prefix + "_nObserved");
      headers.add(prefix + "_nSignal");
      headers.add(prefix + "_pctSignal");
      headers.add(prefix + "_nPos");
      headers.add(prefix + "_nNeg");
      headers.add(prefix + "_mean");
      headers.add(prefix + "_median");
      headers.add(prefix + "_meanSignal");
      headers.add(prefix + "_sd");
      headers.add(prefix + "_min");
      headers.add(prefix + "_max");
      headers.add(prefix + "_p10");
      headers.add(prefix + "_p90");
      headers.add(prefix + "_p95");
      headers.add(prefix + "_meanTop10Pct");
      headers.add(prefix + "_meanTop5Pct");
      headers.add(prefix + "_meanBottom10Pct");
      headers.add(prefix + "_meanBottom5Pct");
    }
    return headers;
  }

  private static List<String> toCells(
      GroupStatResult.Row row,
      GroupStatResult result,
      List<String> groupNames) {
    List<String> cells = new ArrayList<>(32 + groupNames.size() * 19);
    cells.add(nz(row.geneName()));
    cells.add(nz(row.chromosome()));
    cells.add(Long.toString(row.start1Based()));
    cells.add(Long.toString(row.end1Based()));
    cells.add(fmt(row.effectVsBaseline()));
    cells.add(fmt(ThresholdFilter.differenceMagnitude(row)));
    cells.add(fmt(row.pValue()));
    cells.add(fmt(row.qValue()));
    cells.add(GroupComparisonDiffFormat.formatDiffPlain(result.mode(), row));
    cells.add(nz(row.testName()));
    cells.add(result.mode() != null ? result.mode().name() : "");
    for (String gName : groupNames) {
      GroupStatResult.GroupSummary g = findGroup(row.groups(), gName);
      if (g == null) {
        for (int i = 0; i < 19; i++) {
          cells.add("");
        }
        continue;
      }
      cells.add(Integer.toString(g.nTotal()));
      cells.add(Integer.toString(g.nObserved()));
      cells.add(Integer.toString(g.nSignal()));
      cells.add(fmt(g.pctSignal()));
      cells.add(Integer.toString(g.nPos()));
      cells.add(Integer.toString(g.nNeg()));
      cells.add(fmt(g.mean()));
      cells.add(fmt(g.median()));
      cells.add(fmt(g.meanSignal()));
      cells.add(fmt(g.sd()));
      cells.add(fmt(g.min()));
      cells.add(fmt(g.max()));
      cells.add(fmt(g.p10()));
      cells.add(fmt(g.p90()));
      cells.add(fmt(g.p95()));
      cells.add(fmt(g.meanTop10Pct()));
      cells.add(fmt(g.meanTop5Pct()));
      cells.add(fmt(g.meanBottom10Pct()));
      cells.add(fmt(g.meanBottom5Pct()));
    }
    return cells;
  }

  private static String buildMethodText(GroupStatResult result) {
    StringBuilder sb = new StringBuilder();
    if (result.summary() != null) {
      sb.append(result.summary());
    }
    String strip = result.methodStrip();
    if (strip != null && !strip.isBlank()) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(strip);
    }
    if (sb.length() > 0) {
      sb.append('\n');
    }
    sb.append("Top/bottom 5%/10% means are exploratory only and do not drive Δ / p / q.\n");
    sb.append("nSignal = samples with non-zero value; nObserved = explicit matrix values; ");
    sb.append("nTotal = group roster.\n");
    if (GroupStatResult.usesPolarityRateDiff(result.mode())) {
      sb.append("Diff for mutations/SV: direction split (gain vs loss) or hit-rate extremes ");
      sb.append("(e.g. group3 −80% · group1 −10%); flags mixed +/- within a group. ");
      sb.append("Hits show polarity + percent only. Δ is |max−min| roster means (zeros dilute).");
    } else {
      sb.append("Diff for Signal: ↑/↓ mark highest/lowest group means. ");
      sb.append("Δ is |max−min| roster means.");
    }
    return sb.toString();
  }

  private static List<String> orderedGroupNames(GroupStatResult result) {
    Set<String> names = new LinkedHashSet<>();
    if (result.rows() != null) {
      for (GroupStatResult.Row row : result.rows()) {
        if (row.groups() == null) {
          continue;
        }
        for (GroupStatResult.GroupSummary g : row.groups()) {
          if (g != null && g.groupName() != null) {
            names.add(g.groupName());
          }
        }
      }
    }
    return new ArrayList<>(names);
  }

  private static GroupStatResult.GroupSummary findGroup(
      List<GroupStatResult.GroupSummary> groups, String name) {
    if (groups == null || name == null) {
      return null;
    }
    for (GroupStatResult.GroupSummary g : groups) {
      if (g != null && name.equals(g.groupName())) {
        return g;
      }
    }
    return null;
  }

  private static String safePrefix(String name) {
    if (name == null || name.isBlank()) {
      return "G";
    }
    return name.replaceAll("[^A-Za-z0-9]+", "_");
  }

  private static String safeSheetName(String name) {
    if (name == null || name.isBlank()) {
      return "Results";
    }
    String cleaned = name.replaceAll("[\\\\/?*\\[\\]]", "_");
    return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
  }

  private static String nz(String s) {
    return s != null ? s : "";
  }

  private static String fmt(Double v) {
    if (v == null || Double.isNaN(v)) {
      return "";
    }
    return Double.toString(v);
  }

  private static String fmt(double v) {
    if (Double.isNaN(v)) {
      return "";
    }
    return Double.toString(v);
  }
}
