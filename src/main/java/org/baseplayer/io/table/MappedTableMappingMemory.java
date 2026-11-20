package org.baseplayer.io.table;

import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Remembers column-role mappings keyed by normalized header fingerprint so
 * similar tables opened later reuse the user's choices.
 */
public final class MappedTableMappingMemory {

  private static final Preferences PREFS =
      Preferences.userNodeForPackage(MappedTableMappingMemory.class).node("mappedTableColumns");
  private static final int MAX_ENTRIES = 80;

  static {
    // Older builds used U+0001 as a fingerprint delimiter; that key cannot be
    // written to Preferences XML and poisons sync/flush for the whole node.
    purgeInvalidKeys();
  }

  private MappedTableMappingMemory() {}

  public static void remember(String[] headers, MappedTableColumnMapping mapping) {
    if (mapping == null || !mapping.hasRequired()) {
      return;
    }
    boolean headerRow = mapping.headerRow();
    String fp = MappedTableColumnMapping.fingerprint(headers, headerRow);
    if (fp.isBlank() || !isPrefsSafe(fp)) {
      return;
    }
    String serialized = mapping.serialize();
    try {
      purgeInvalidKeys();
      PREFS.put(fp, serialized);
      trimIfNeeded();
      PREFS.flush();
    } catch (Throwable t) {
      // Never fail the table open path because prefs could not persist.
      System.err.println("MappedTableMappingMemory: could not remember mapping: " + t.getMessage());
      try {
        // Poisoned node (e.g. legacy U+0001 keys): wipe and store only this entry.
        PREFS.clear();
        PREFS.put(fp, serialized);
        PREFS.flush();
      } catch (Throwable ignored) {
        // ignore — mapping still applies for this open
      }
    }
  }

  public static Optional<MappedTableColumnMapping> recall(String[] headers) {
    return recall(headers, true).or(() -> recall(headers, false));
  }

  public static Optional<MappedTableColumnMapping> recall(String[] headers, boolean headerRow) {
    String fp = MappedTableColumnMapping.fingerprint(headers, headerRow);
    if (fp.isBlank() || !isPrefsSafe(fp)) {
      return Optional.empty();
    }
    String raw;
    try {
      raw = PREFS.get(fp, null);
    } catch (Throwable t) {
      return Optional.empty();
    }
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    MappedTableColumnMapping mapping = MappedTableColumnMapping.deserialize(raw);
    if (!mapping.hasRequired()) {
      return Optional.empty();
    }
    // Indexes must still be in range for this header width.
    int n = headers != null ? headers.length : 0;
    for (Integer idx : mapping.indexes().values()) {
      if (idx == null || idx < 0 || idx >= n) {
        return Optional.empty();
      }
    }
    return Optional.of(mapping);
  }

  private static void trimIfNeeded() {
    try {
      String[] keys = PREFS.keys();
      if (keys.length <= MAX_ENTRIES) {
        return;
      }
      // Preferences has no LRU; drop arbitrary excess from the front.
      int drop = keys.length - MAX_ENTRIES;
      for (int i = 0; i < drop; i++) {
        PREFS.remove(keys[i]);
      }
    } catch (Exception ignored) {
      // ignore
    }
  }

  /** Drop keys that cannot be serialized to Preferences XML (e.g. legacy U+0001 fingerprints). */
  private static void purgeInvalidKeys() {
    try {
      for (String key : PREFS.keys()) {
        if (!isPrefsSafe(key)) {
          PREFS.remove(key);
        }
      }
    } catch (BackingStoreException ignored) {
      // ignore
    }
  }

  /**
   * Preferences persists as XML 1.0 attributes; control chars other than tab/LF/CR are illegal.
   */
  private static boolean isPrefsSafe(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
        return false;
      }
      if (c == 0xFFFE || c == 0xFFFF) {
        return false;
      }
    }
    return true;
  }
}
