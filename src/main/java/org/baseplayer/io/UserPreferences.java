package org.baseplayer.io;

import java.io.File;
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
