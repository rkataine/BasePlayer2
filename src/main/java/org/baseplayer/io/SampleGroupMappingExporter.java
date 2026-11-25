package org.baseplayer.io;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.baseplayer.MainApp;
import org.baseplayer.components.AppDialog;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Window;

/**
 * Export current sample→group memberships as a TSV that
 * {@link SampleGroupMappingImporter} can re-import (multi-group aware).
 *
 * <pre>
 * sample_name	group_name
 * SampleA	Tumor
 * SampleB	Normal
 * SampleA	Control
 * </pre>
 */
public final class SampleGroupMappingExporter {

  private SampleGroupMappingExporter() {}

  /**
   * Show a save dialog and write the mapping. Returns {@code true} on success.
   */
  public static boolean chooseAndExport(Window owner) {
    Window resolved = owner != null ? owner : MainApp.stage;
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<String[]> rows = collectRows(registry);
    if (rows.isEmpty()) {
      AppDialog.info(
          resolved,
          "No groups to export",
          "Create sample groups first, then export the sample→group mapping.",
          null);
      return false;
    }

    FileChooser chooser = new FileChooser();
    chooser.setTitle("Export Sample Groups");
    chooser.getExtensionFilters().addAll(
        new ExtensionFilter("TSV (tab-separated)", "*.tsv", "*.txt"),
        new ExtensionFilter("CSV", "*.csv"),
        new ExtensionFilter("All Files", "*.*"));
    chooser.setInitialFileName("sample_groups.tsv");
    File lastDir = UserPreferences.getLastDirectory("SAMPLE_GROUPS");
    if (lastDir != null) {
      try {
        chooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException ignored) {
        // Directory unavailable.
      }
    }
    File file = chooser.showSaveDialog(resolved);
    if (file == null) {
      return false;
    }
    UserPreferences.setLastDirectory("SAMPLE_GROUPS", file.getParentFile());

    try {
      writeMappingFile(file, rows);
      AppDialog.info(
          resolved,
          "Sample groups exported",
          "Wrote " + rows.size() + " sample→group row(s) to:\n" + file.getName(),
          "Re-import with File → Import Sample Groups… to apply the same grouping.");
      return true;
    } catch (IOException ex) {
      AppDialog.info(
          resolved,
          "Export failed",
          "Could not write " + file.getName(),
          ex.getMessage());
      return false;
    }
  }

  /** One {@code [sampleName, groupName]} row per track membership (group order). */
  public static List<String[]> collectRows(SampleRegistry registry) {
    List<String[]> rows = new ArrayList<>();
    if (registry == null) {
      return rows;
    }
    for (SampleGroup group : registry.getSampleGroups()) {
      if (group == null || group.getName() == null || group.getName().isBlank()) {
        continue;
      }
      String groupName = group.getName().trim();
      for (SampleTrack track : registry.getSampleTracks()) {
        if (track == null || !track.isInGroup(group.getId())) {
          continue;
        }
        String sampleName = track.getDisplayName();
        if (sampleName == null || sampleName.isBlank()) {
          sampleName = track.getName();
        }
        if (sampleName == null || sampleName.isBlank()) {
          continue;
        }
        rows.add(new String[] { sampleName.trim(), groupName });
      }
    }
    return rows;
  }

  static void writeMappingFile(File file, List<String[]> rows) throws IOException {
    if (file == null) {
      throw new IOException("No output file");
    }
    boolean csv = file.getName() != null
        && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".csv");
    String sep = csv ? "," : "\t";
    try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
      writer.write(csv ? "sample_name,group_name" : "sample_name\tgroup_name");
      writer.newLine();
      if (rows == null) {
        return;
      }
      for (String[] row : rows) {
        if (row == null || row.length < 2) {
          continue;
        }
        writer.write(escapeField(row[0], csv));
        writer.write(sep);
        writer.write(escapeField(row[1], csv));
        writer.newLine();
      }
    }
  }

  private static String escapeField(String value, boolean csv) {
    String v = value != null ? value : "";
    if (!csv) {
      return v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
    if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
      return '"' + v.replace("\"", "\"\"") + '"';
    }
    return v;
  }
}
