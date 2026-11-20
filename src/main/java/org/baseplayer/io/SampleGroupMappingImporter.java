package org.baseplayer.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.components.AppDialog;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.SampleRegistry.GroupMappingApplyResult;
import org.baseplayer.services.ServiceRegistry;

import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Window;

/**
 * Import a text mapping of sample name → group name and assign loaded tracks.
 *
 * <p>Supported lines (tab, comma, or whitespace separated):
 * <pre>
 * sampleA	Tumor
 * sampleB,Normal
 * sampleC Control
 * # comments and blank lines are ignored
 * </pre>
 */
public final class SampleGroupMappingImporter {

  private SampleGroupMappingImporter() {}

  /**
   * Show a file chooser, apply the mapping, and report the result.
   *
   * @return apply result, or {@code null} if cancelled / failed before apply
   */
  public static GroupMappingApplyResult chooseAndImport(Window owner) {
    Window resolved = owner != null ? owner : MainApp.stage;
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Import Sample Groups");
    chooser.getExtensionFilters().addAll(
        new ExtensionFilter("Text / TSV / CSV", "*.txt", "*.tsv", "*.csv"),
        new ExtensionFilter("All Files", "*.*"));
    File lastDir = UserPreferences.getLastDirectory("SAMPLE_GROUPS");
    if (lastDir != null) {
      try {
        chooser.setInitialDirectory(lastDir);
      } catch (IllegalArgumentException ignored) {
        // Directory unavailable; FileChooser uses its default.
      }
    }
    File file = chooser.showOpenDialog(resolved);
    if (file == null) {
      return null;
    }
    UserPreferences.setLastDirectory("SAMPLE_GROUPS", file.getParentFile());

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    if (registry.getSampleTracks().isEmpty()) {
      AppDialog.info(
          resolved,
          "No samples loaded",
          "Open sample files first, then import a sample→group mapping.",
          null);
      return null;
    }

    try {
      List<String[]> pairs = parseMappingFile(file.toPath());
      if (pairs.isEmpty()) {
        AppDialog.info(
            resolved,
            "Empty mapping",
            "No sample/group rows found in:\n" + file.getName(),
            "Use two columns: sample name and group name (tab, comma, or space separated).");
        return null;
      }
      GroupMappingApplyResult result = registry.applyGroupsFromMapping(pairs);
      AppDialog.info(
          resolved,
          "Sample groups imported",
          summarize(result, file.getName()),
          unmatchedHint(result));
      return result;
    } catch (IOException ex) {
      AppDialog.info(
          resolved,
          "Import failed",
          "Could not read " + file.getName(),
          ex.getMessage());
      return null;
    }
  }

  private static String summarize(GroupMappingApplyResult result, String fileName) {
    return "From " + fileName + ":\n"
        + result.groupsCreated() + " group(s) created, "
        + result.groupsUpdated() + " existing group(s) used,\n"
        + result.tracksAssigned() + " sample(s) assigned"
        + (result.unmatchedSamples().isEmpty()
            ? "."
            : ", " + result.unmatchedSamples().size() + " name(s) unmatched.");
  }

  private static String unmatchedHint(GroupMappingApplyResult result) {
    if (result.unmatchedSamples().isEmpty()) {
      return "Format: sample_name <tab|comma|space> group_name";
    }
    List<String> names = result.unmatchedSamples();
    int show = Math.min(8, names.size());
    StringBuilder sb = new StringBuilder("Unmatched: ");
    for (int i = 0; i < show; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(names.get(i));
    }
    if (names.size() > show) {
      sb.append(", …");
    }
    return sb.toString();
  }

  /**
   * Parse mapping rows as {@code [sampleName, groupName]}. Skips blanks, comments,
   * and an optional header row.
   */
  public static List<String[]> parseMappingFile(Path path) throws IOException {
    List<String[]> pairs = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      boolean firstDataLine = true;
      while ((line = reader.readLine()) != null) {
        String trimmed = stripBom(line).trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
          continue;
        }
        String[] columns = splitMappingLine(trimmed);
        if (columns == null) {
          continue;
        }
        if (firstDataLine && looksLikeHeader(columns[0], columns[1])) {
          firstDataLine = false;
          continue;
        }
        firstDataLine = false;
        pairs.add(columns);
      }
    }
    return pairs;
  }

  /** Resolve mapping rows to tracks grouped by group name (file order). */
  public static Map<String, List<SampleTrack>> resolveMapping(
      SampleRegistry registry, List<String[]> pairs, List<String> unmatchedOut) {
    Map<String, List<SampleTrack>> byGroup = new LinkedHashMap<>();
    Map<String, Set<SampleTrack>> seenInGroup = new LinkedHashMap<>();
    if (pairs == null) {
      return byGroup;
    }
    for (String[] pair : pairs) {
      if (pair == null || pair.length < 2) {
        continue;
      }
      String sampleName = pair[0] != null ? pair[0].trim() : "";
      String groupName = pair[1] != null ? pair[1].trim() : "";
      if (sampleName.isEmpty() || groupName.isEmpty()) {
        continue;
      }
      SampleTrack track = registry.findTrackMatchingName(sampleName);
      if (track == null) {
        if (unmatchedOut != null) {
          unmatchedOut.add(sampleName);
        }
        continue;
      }
      Set<SampleTrack> seen = seenInGroup.computeIfAbsent(
          groupName, key -> Collections.newSetFromMap(new IdentityHashMap<>()));
      if (seen.add(track)) {
        byGroup.computeIfAbsent(groupName, key -> new ArrayList<>()).add(track);
      }
    }
    if (unmatchedOut != null) {
      // Unique unmatched names, preserve order
      Set<String> unique = new LinkedHashSet<>(unmatchedOut);
      unmatchedOut.clear();
      unmatchedOut.addAll(unique);
    }
    return byGroup;
  }

  static String[] splitMappingLine(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    String sample;
    String group;
    int tab = line.indexOf('\t');
    if (tab >= 0) {
      sample = line.substring(0, tab).trim();
      group = line.substring(tab + 1).trim();
    } else {
      int comma = line.indexOf(',');
      if (comma >= 0) {
        sample = line.substring(0, comma).trim();
        group = line.substring(comma + 1).trim();
      } else {
        String[] parts = line.trim().split("\\s+", 2);
        if (parts.length < 2) {
          return null;
        }
        sample = parts[0].trim();
        group = parts[1].trim();
      }
    }
    if (sample.isEmpty() || group.isEmpty()) {
      return null;
    }
    // Drop a trailing comment on the group field.
    int hash = group.indexOf('#');
    if (hash >= 0) {
      group = group.substring(0, hash).trim();
    }
    if (group.isEmpty()) {
      return null;
    }
    return new String[] {sample, group};
  }

  private static boolean looksLikeHeader(String sampleCol, String groupCol) {
    String a = sampleCol.toLowerCase(Locale.ROOT).replace(' ', '_');
    String b = groupCol.toLowerCase(Locale.ROOT).replace(' ', '_');
    boolean sampleHeader = a.equals("sample")
        || a.equals("sample_name")
        || a.equals("name")
        || a.equals("track")
        || a.equals("track_name");
    boolean groupHeader = b.equals("group")
        || b.equals("group_name")
        || b.equals("cohort")
        || b.equals("category");
    return sampleHeader && groupHeader;
  }

  private static String stripBom(String line) {
    if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
      return line.substring(1);
    }
    return line;
  }
}
