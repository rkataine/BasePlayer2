package org.baseplayer.io;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Persists user preferences (last-used directories, etc.) across sessions.
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
  private static final String KEY_RECENT_FILES = "recent.files";
  private static final int MAX_RECENT_FILES = 12;
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
      // Check if directory exists, is a directory, and is accessible (readable)
      // The canRead() check is important for unmounted directories that may technically exist as mount points
      if (dir.exists() && dir.isDirectory() && dir.canRead()) {
        try {
          // Extra check: try to list files to ensure the directory is truly accessible
          // This prevents hangs on unmounted network drives or disconnected devices
          dir.list();
          return dir;
        } catch (SecurityException e) {
          // Directory is not accessible, fall through to return null
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
    }
  }

  /**
   * Add a file to recent-files list (most recent first), deduplicated by absolute path.
   */
  public static void addRecentFile(String fileType, File file) {
    if (file == null) return;
    String path = file.getAbsolutePath();
    List<RecentFile> recent = getRecentFiles();
    recent.removeIf(r -> r.path().equals(path));
    recent.add(0, new RecentFile(fileType.toUpperCase(), path));
    if (recent.size() > MAX_RECENT_FILES) {
      recent = new ArrayList<>(recent.subList(0, MAX_RECENT_FILES));
    }
    saveRecentFiles(recent);
  }

  /**
   * Returns recent files in descending recency order.
   */
  public static List<RecentFile> getRecentFiles() {
    String raw = prefs.get(KEY_RECENT_FILES, "");
    List<RecentFile> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) return out;

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
    return out;
  }

  public static void removeRecentFile(String path) {
    if (path == null || path.isBlank()) return;
    List<RecentFile> recent = getRecentFiles();
    recent.removeIf(r -> r.path().equals(path));
    saveRecentFiles(recent);
  }

  public static void clearRecentFiles() {
    prefs.remove(KEY_RECENT_FILES);
  }

  private static void saveRecentFiles(List<RecentFile> recent) {
    StringBuilder sb = new StringBuilder();
    for (RecentFile rf : recent) {
      if (rf == null || rf.fileType() == null || rf.path() == null) continue;
      if (sb.length() > 0) sb.append(RECENT_LINE_SEPARATOR);
      sb.append(rf.fileType()).append(RECENT_FIELD_SEPARATOR).append(rf.path());
    }
    prefs.put(KEY_RECENT_FILES, sb.toString());
  }

  private static String keyFor(String fileType) {
    return switch (fileType.toUpperCase()) {
      case "BAM"    -> KEY_BAM_DIR;
      case "VCF"    -> KEY_VCF_DIR;
      case "BED"    -> KEY_BED_DIR;
      case "CRAM"   -> KEY_CRAM_DIR;
      case "BIGWIG" -> KEY_BIGWIG_DIR;
      case "JSON"   -> KEY_JSON_DIR;
      case "CTRL"   -> KEY_CTRL_DIR;
      default       -> "lastDir." + fileType.toLowerCase();
    };
  }
}
