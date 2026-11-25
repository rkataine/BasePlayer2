package org.baseplayer.analysis.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.groupstat.GroupStatExcelExport;
import org.baseplayer.io.VariantTableExcelWriter;
import org.baseplayer.io.VariantTableExcelWriter.SheetSource;

/**
 * Orchestrates writing selected {@link AnalysisExportKind}s to a file or directory.
 */
public final class AnalysisExportService {

  private AnalysisExportService() {}

  public static EnumSet<AnalysisExportKind> availableKinds() {
    return availableKinds(AnalysisSession.get());
  }

  public static EnumSet<AnalysisExportKind> availableKinds(AnalysisSession session) {
    EnumSet<AnalysisExportKind> out = EnumSet.noneOf(AnalysisExportKind.class);
    if (AnalysisLongTsvExport.hasSignalValues(session)) {
      out.add(AnalysisExportKind.SIGNAL_MOFA_LONG);
    }
    if (AnalysisLongTsvExport.hasAnnotation(session)) {
      out.add(AnalysisExportKind.SIGNAL_ANNOTATION);
    }
    if (AnalysisLongTsvExport.hasComparisonEffects(session)) {
      out.add(AnalysisExportKind.COMPARISON_EFFECTS);
    }
    if (hasWideExcelData(session)) {
      out.add(AnalysisExportKind.COMPARISON_WIDE_EXCEL);
    }
    return out;
  }

  public static boolean hasAnythingToExport() {
    return !availableKinds().isEmpty();
  }

  /**
   * Write {@code kinds} to {@code target}.
   *
   * <p>If {@code kinds} has one entry, {@code target} is the output file path.
   * If multiple, {@code target} is a directory and default file names are used.
   *
   * @return paths actually written
   */
  public static List<Path> export(Set<AnalysisExportKind> kinds, Path target) throws IOException {
    return export(kinds, target, AnalysisSession.get());
  }

  public static List<Path> export(
      Set<AnalysisExportKind> kinds, Path target, AnalysisSession session) throws IOException {
    if (kinds == null || kinds.isEmpty()) {
      throw new IllegalArgumentException("No export kinds selected");
    }
    if (target == null) {
      throw new IllegalArgumentException("target required");
    }
    EnumSet<AnalysisExportKind> selected = EnumSet.copyOf(kinds);
    List<Path> written = new ArrayList<>();
    boolean multi = selected.size() > 1;
    if (multi) {
      Files.createDirectories(target);
      if (!Files.isDirectory(target)) {
        throw new IOException("Target must be a directory when exporting multiple kinds: " + target);
      }
    } else {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    }

    for (AnalysisExportKind kind : AnalysisExportKind.values()) {
      if (!selected.contains(kind)) {
        continue;
      }
      Path out = multi ? target.resolve(kind.defaultFileName()) : target;
      switch (kind) {
        case SIGNAL_MOFA_LONG -> AnalysisLongTsvExport.writeSignalMofaLong(out, session);
        case SIGNAL_ANNOTATION -> AnalysisLongTsvExport.writeSignalAnnotation(out, session);
        case COMPARISON_EFFECTS -> AnalysisLongTsvExport.writeComparisonEffects(out, session);
        case COMPARISON_WIDE_EXCEL -> writeWideExcel(out, session);
        default -> throw new IllegalStateException("Unhandled kind: " + kind);
      }
      written.add(out);
    }
    return written;
  }

  private static void writeWideExcel(Path path, AnalysisSession session) throws IOException {
    List<SheetSource> sheets = GroupStatExcelExport.sheetSourcesFromSession(session);
    if (sheets.isEmpty()) {
      throw new IOException("No comparison or annotation rows to export.");
    }
    VariantTableExcelWriter.write(path, sheets, null);
  }

  private static boolean hasWideExcelData(AnalysisSession session) {
    return session != null && !GroupStatExcelExport.sheetSourcesFromSession(session).isEmpty();
  }

  /** Ensure a path ends with the kind's extension (for single-file save dialogs). */
  public static Path ensureExtension(Path chosen, AnalysisExportKind kind) {
    if (chosen == null || kind == null) {
      return chosen;
    }
    String name = chosen.getFileName() != null ? chosen.getFileName().toString() : "";
    String lower = name.toLowerCase(Locale.ROOT);
    String ext = kind.filterExtension().replace("*", "");
    if (ext.isEmpty() || lower.endsWith(ext.toLowerCase(Locale.ROOT))) {
      return chosen;
    }
    Path parent = chosen.getParent();
    String withExt = name + ext;
    return parent != null ? parent.resolve(withExt) : Path.of(withExt);
  }
}
