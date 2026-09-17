package org.baseplayer.io;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Persists user preferences (last-used directories, etc.) across app launches.
 * Uses java.util.prefs.Preferences, which stores to ~/.java on Linux
 * and the registry on Windows — no config files to manage.
 */
public class UserPreferences {

  private static final Preferences prefs = Preferences.userNodeForPackage(UserPreferences.class);

  // Keys for last-used directories per file type
  private static final String KEY_BAM_DIR  = "lastDir.bam";
  private static final String KEY_VCF_DIR  = "lastDir.vcf";
  private static final String KEY_BED_DIR  = "lastDir.bed";
  private static final String KEY_CRAM_DIR = "lastDir.cram";
  private static final String KEY_BIGWIG_DIR = "lastDir.bigwig";
  private static final String KEY_JSON_DIR = "lastDir.json";
  private static final String KEY_CTRL_DIR = "lastDir.ctrl";

  /** Legacy mixed recent list (migrated once into project + sample lists). */
  private static final String KEY_RECENT_FILES_LEGACY = "recent.files";
  private static final String KEY_RECENT_COUNT = "recent.count";
  private static final String KEY_RECENT_PROJECTS_COUNT = "recent.projects.count";
  private static final String KEY_RECENT_SAMPLES_COUNT = "recent.samples.count";
  private static final String KEY_RECENT_SPLIT_DONE = "recent.split.v1";

  private static final int MAX_RECENT_PROJECTS = 12;
  private static final int MAX_RECENT_SAMPLE_FILES = 20;
  private static final String RECENT_LINE_SEPARATOR = "\n";
  private static final String RECENT_FIELD_SEPARATOR = "\t";

  public record RecentFile(String fileType, String path) {}

  /**
   * Get the last-used directory for a file type.
   * Returns null if no directory was saved previously or if the directory is not accessible.
   */
  public static File getLastDirectory(String fileType) {
    String key = keyFor(fileType);
    String path = prefs.get(key, null);
    if (path != null) {
      File dir = new File(path);
      if (dir.exists() && dir.isDirectory() && dir.canRead()) {
        try {
          dir.list();
          return dir;
        } catch (SecurityException e) {
          // Directory is not accessible
        }
      }
    }
    return null;
  }

  /**
   * Save the last-used directory for a file type.
   */
  public static void setLastDirectory(String fileType, File directory) {
    if (directory == null) return;
    File dir = directory.isDirectory() ? directory : directory.getParentFile();
    if (dir != null && dir.exists()) {
      prefs.put(keyFor(fileType), dir.getAbsolutePath());
      flushPrefs();
    }
  }

  /**
   * Add a recent entry, routing projects and sample files to separate lists.
   */
  public static void addRecentFile(String fileType, File file) {
    if (file == null || fileType == null || fileType.isBlank()) return;
    RecentFile entry = toRecentEntry(fileType, file);
    if (entry == null) return;
    if (isProjectEntry(entry)) {
      addRecentProject(file);
    } else {
      addRecentSampleFile(fileType, file);
    }
  }

  /** Record an opened/saved project ({@code .bpproj} / session JSON). */
  public static void addRecentProject(File file) {
    if (file == null) return;
    File absolute = file.getAbsoluteFile();
    String path = absolute.getPath();
    if (path == null || path.isBlank()) return;

    ensureRecentListsMigrated();
    List<RecentFile> projects = loadList(KEY_RECENT_PROJECTS_COUNT, "recent.projects.");
    projects.removeIf(r -> r.path() != null && pathsEqual(r.path(), path));
    String type = path.toLowerCase().endsWith(".bpproj") ? "PROJECT" : "JSON";
    projects.add(0, new RecentFile(type, path));
    if (projects.size() > MAX_RECENT_PROJECTS) {
      projects = new ArrayList<>(projects.subList(0, MAX_RECENT_PROJECTS));
    }
    saveList(KEY_RECENT_PROJECTS_COUNT, "recent.projects.", projects, MAX_RECENT_PROJECTS);
  }

  /** Record an opened sample/track file (BAM, VCF, BED, BigWig, …). */
  public static void addRecentSampleFile(String fileType, File file) {
    if (file == null || fileType == null || fileType.isBlank()) return;
    File absolute = file.getAbsoluteFile();
    String path = absolute.getPath();
    if (path == null || path.isBlank()) return;

    ensureRecentListsMigrated();
    List<RecentFile> samples = loadList(KEY_RECENT_SAMPLES_COUNT, "recent.samples.");
    samples.removeIf(r -> r.path() != null && pathsEqual(r.path(), path));
    samples.add(0, new RecentFile(fileType.toUpperCase(), path));
    if (samples.size() > MAX_RECENT_SAMPLE_FILES) {
      samples = new ArrayList<>(samples.subList(0, MAX_RECENT_SAMPLE_FILES));
    }
    saveList(KEY_RECENT_SAMPLES_COUNT, "recent.samples.", samples, MAX_RECENT_SAMPLE_FILES);
  }

  /** Recent sample/track files only (BAM, VCF, BED, …). */
  public static List<RecentFile> getRecentSampleFiles() {
    ensureRecentListsMigrated();
    return loadList(KEY_RECENT_SAMPLES_COUNT, "recent.samples.");
  }

  /**
   * @deprecated use {@link #getRecentSampleFiles()} or {@link #getRecentProjects()}
   */
  @Deprecated
  public static List<RecentFile> getRecentFiles() {
    List<RecentFile> out = new ArrayList<>();
    out.addAll(getRecentProjects());
    out.addAll(getRecentSampleFiles());
    return out;
  }

  /** Recent projects only ({@code .bpproj} / session JSON). */
  public static List<RecentFile> getRecentProjects() {
    ensureRecentListsMigrated();
    return loadList(KEY_RECENT_PROJECTS_COUNT, "recent.projects.");
  }

  public static void removeRecentFile(String path) {
    removeRecentProject(path);
    removeRecentSampleFile(path);
  }

  public static void removeRecentProject(String path) {
    if (path == null || path.isBlank()) return;
    ensureRecentListsMigrated();
    List<RecentFile> projects = loadList(KEY_RECENT_PROJECTS_COUNT, "recent.projects.");
    projects.removeIf(r -> r.path() != null && pathsEqual(r.path(), path));
    saveList(KEY_RECENT_PROJECTS_COUNT, "recent.projects.", projects, MAX_RECENT_PROJECTS);
  }

  public static void removeRecentSampleFile(String path) {
    if (path == null || path.isBlank()) return;
    ensureRecentListsMigrated();
    List<RecentFile> samples = loadList(KEY_RECENT_SAMPLES_COUNT, "recent.samples.");
    samples.removeIf(r -> r.path() != null && pathsEqual(r.path(), path));
    saveList(KEY_RECENT_SAMPLES_COUNT, "recent.samples.", samples, MAX_RECENT_SAMPLE_FILES);
  }

  public static void clearRecentFiles() {
    clearRecentProjects();
    clearRecentSampleFiles();
  }

  public static void clearRecentProjects() {
    clearList(KEY_RECENT_PROJECTS_COUNT, "recent.projects.", MAX_RECENT_PROJECTS);
  }

  public static void clearRecentSampleFiles() {
    clearList(KEY_RECENT_SAMPLES_COUNT, "recent.samples.", MAX_RECENT_SAMPLE_FILES);
  }

  private static RecentFile toRecentEntry(String fileType, File file) {
    File absolute = file.getAbsoluteFile();
    String path = absolute.getPath();
    if (path == null || path.isBlank()) return null;
    return new RecentFile(fileType.toUpperCase(), path);
  }

  private static boolean isProjectEntry(RecentFile rf) {
    if (rf == null) return false;
    String type = rf.fileType() != null ? rf.fileType().toUpperCase() : "";
    if ("JSON".equals(type) || "SES".equals(type) || "PROJECT".equals(type)) {
      return true;
    }
    String lower = rf.path() != null ? rf.path().toLowerCase() : "";
    return lower.endsWith(".bpproj") || lower.endsWith(".json");
  }

  private static void ensureRecentListsMigrated() {
    if (prefs.getBoolean(KEY_RECENT_SPLIT_DONE, false)) {
      return;
    }
    List<RecentFile> legacy = readLegacyMixedRecent();
    List<RecentFile> projects = new ArrayList<>();
    List<RecentFile> samples = new ArrayList<>();
    for (RecentFile rf : legacy) {
      if (rf == null || rf.path() == null || rf.path().isBlank()) continue;
      if (isProjectEntry(rf)) {
        projects.add(rf);
      } else {
        samples.add(rf);
      }
    }
    if (projects.size() > MAX_RECENT_PROJECTS) {
      projects = new ArrayList<>(projects.subList(0, MAX_RECENT_PROJECTS));
    }
    if (samples.size() > MAX_RECENT_SAMPLE_FILES) {
      samples = new ArrayList<>(samples.subList(0, MAX_RECENT_SAMPLE_FILES));
    }
    saveList(KEY_RECENT_PROJECTS_COUNT, "recent.projects.", projects, MAX_RECENT_PROJECTS);
    saveList(KEY_RECENT_SAMPLES_COUNT, "recent.samples.", samples, MAX_RECENT_SAMPLE_FILES);
    clearLegacyMixedRecent();
    prefs.putBoolean(KEY_RECENT_SPLIT_DONE, true);
    flushPrefs();
  }

  private static List<RecentFile> readLegacyMixedRecent() {
    int count = prefs.getInt(KEY_RECENT_COUNT, -1);
    if (count >= 0) {
      List<RecentFile> out = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        String type = prefs.get(recentTypeKey(i), null);
        String path = prefs.get(recentPathKey(i), null);
        if (type == null || type.isBlank() || path == null || path.isBlank()) {
          continue;
        }
        out.add(new RecentFile(type, path));
      }
      return out;
    }
    return migrateLegacyBlobRecent();
  }

  private static List<RecentFile> migrateLegacyBlobRecent() {
    String raw = prefs.get(KEY_RECENT_FILES_LEGACY, "");
    List<RecentFile> out = new ArrayList<>();
    if (raw != null && !raw.isBlank()) {
      String[] lines = raw.split(RECENT_LINE_SEPARATOR);
      for (String line : lines) {
        if (line == null || line.isBlank()) continue;
        int sep = line.indexOf(RECENT_FIELD_SEPARATOR);
        if (sep <= 0 || sep >= line.length() - 1) continue;
        String type = line.substring(0, sep).trim();
        String path = line.substring(sep + 1).trim();
        if (type.isEmpty() || path.isEmpty()) continue;
        out.add(new RecentFile(type, path));
      }
    }
    return out;
  }

  private static void clearLegacyMixedRecent() {
    int previous = Math.max(prefs.getInt(KEY_RECENT_COUNT, 0), 40);
    for (int i = 0; i < previous; i++) {
      prefs.remove(recentTypeKey(i));
      prefs.remove(recentPathKey(i));
    }
    prefs.remove(KEY_RECENT_COUNT);
    prefs.remove(KEY_RECENT_FILES_LEGACY);
  }

  private static List<RecentFile> loadList(String countKey, String prefix) {
    int count = prefs.getInt(countKey, 0);
    List<RecentFile> out = new ArrayList<>(Math.max(0, count));
    for (int i = 0; i < count; i++) {
      String type = prefs.get(prefix + i + ".type", null);
      String path = prefs.get(prefix + i + ".path", null);
      if (type == null || type.isBlank() || path == null || path.isBlank()) {
        continue;
      }
      out.add(new RecentFile(type, path));
    }
    return out;
  }

  private static void saveList(String countKey, String prefix, List<RecentFile> entries, int maxSlots) {
    int previous = Math.max(prefs.getInt(countKey, 0), maxSlots);
    for (int i = 0; i < previous; i++) {
      prefs.remove(prefix + i + ".type");
      prefs.remove(prefix + i + ".path");
    }
    int stored = 0;
    for (RecentFile rf : entries) {
      if (rf == null || rf.fileType() == null || rf.path() == null) continue;
      if (rf.fileType().isBlank() || rf.path().isBlank()) continue;
      prefs.put(prefix + stored + ".type", rf.fileType());
      prefs.put(prefix + stored + ".path", rf.path());
      stored++;
    }
    prefs.putInt(countKey, stored);
    flushPrefs();
  }

  private static void clearList(String countKey, String prefix, int maxSlots) {
    int previous = Math.max(prefs.getInt(countKey, 0), maxSlots);
    for (int i = 0; i < previous; i++) {
      prefs.remove(prefix + i + ".type");
      prefs.remove(prefix + i + ".path");
    }
    prefs.remove(countKey);
    flushPrefs();
  }

  private static String recentTypeKey(int index) {
    return "recent." + index + ".type";
  }

  private static String recentPathKey(int index) {
    return "recent." + index + ".path";
  }

  private static boolean pathsEqual(String a, String b) {
    try {
      return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath());
    } catch (Exception e) {
      return a.equals(b);
    }
  }

  private static void flushPrefs() {
    try {
      prefs.flush();
    } catch (BackingStoreException e) {
      System.err.println("UserPreferences: failed to flush preferences: " + e.getMessage());
    }
  }

  private static String keyFor(String fileType) {
    return switch (fileType.toUpperCase()) {
      case "BAM"    -> KEY_BAM_DIR;
      case "VCF"    -> KEY_VCF_DIR;
      case "BED"    -> KEY_BED_DIR;
      case "CRAM"   -> KEY_CRAM_DIR;
      case "BIGWIG" -> KEY_BIGWIG_DIR;
      case "JSON", "PROJECT", "SES" -> KEY_JSON_DIR;
      case "CTRL"   -> KEY_CTRL_DIR;
      default       -> "lastDir." + fileType.toLowerCase();
    };
  }
}
