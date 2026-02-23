package org.baseplayer.annotation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps various cancer type names and abbreviations to standardized display names.
 * Handles variations like "CRC", "colorectal", "colon cancer" -> "colorectal cancer".
 *
 * <p>Mappings are loaded from the resource file {@code cancer_type_mappings.tsv}
 * (tab-separated: variation → standard name). The file can be edited without
 * recompiling Java code.</p>
 */
public final class CancerTypeMapper {

  private static final String MAPPINGS_RESOURCE = "/org/baseplayer/cancer_type_mappings.tsv";
  private static final Map<String, String> CANCER_TYPE_MAP = new HashMap<>();

  static {
    loadMappings();
  }

  /**
   * Load cancer-type mappings from the TSV resource file.
   * Each non-comment line should be: {@code variation<TAB>standard_name}.
   */
  private static void loadMappings() {
    try (InputStream is = CancerTypeMapper.class.getResourceAsStream(MAPPINGS_RESOURCE)) {
      if (is == null) {
        System.err.println("CancerTypeMapper: resource not found: " + MAPPINGS_RESOURCE);
        return;
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank() || line.startsWith("#")) continue;
          int tab = line.indexOf('\t');
          if (tab < 0) continue;
          String variation = line.substring(0, tab).trim();
          String standardName = line.substring(tab + 1).trim();
          if (!variation.isEmpty() && !standardName.isEmpty()) {
            CANCER_TYPE_MAP.put(variation.toLowerCase(), standardName);
          }
        }
      }
    } catch (IOException e) {
      System.err.println("CancerTypeMapper: failed to load mappings: " + e.getMessage());
    }
  }

  /**
   * Map a cancer type name to its standardized display name.
   * Returns the input unchanged if no mapping is found.
   */
  public static String mapCancerType(String cancerType) {
    if (cancerType == null || cancerType.isEmpty()) {
      return cancerType;
    }

    String trimmed = cancerType.trim();
    String mapped = CANCER_TYPE_MAP.get(trimmed.toLowerCase());

    return mapped != null ? mapped : trimmed;
  }

  /**
   * Check if a cancer type has a standardized mapping.
   */
  public static boolean hasMapping(String cancerType) {
    if (cancerType == null || cancerType.isEmpty()) {
      return false;
    }
    return CANCER_TYPE_MAP.containsKey(cancerType.trim().toLowerCase());
  }

  private CancerTypeMapper() {} // Utility class
}
