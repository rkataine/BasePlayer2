package org.baseplayer.io.readers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.baseplayer.utils.FeatureNameColors;

import javafx.scene.paint.Color;

/**
 * Samples BED feature names (column 4) for the pre-load filter dialog.
 * Scans the first {@link #SAMPLE_DATA_LINES} data rows and lists every unique
 * name found there (covers most types for files like RepeatMasker). An
 * {@link #OTHER_NAME} bucket covers names that appear only later in the file.
 */
public final class BedFeatureNameCatalog {

  /** Sentinel name for “everything not in the listed sample types”. */
  public static final String OTHER_NAME = "\0__OTHER__";
  /** How many BED data rows to scan for the type-selection dialog. */
  public static final int SAMPLE_DATA_LINES = 1_000;

  public record Entry(String name, Color color, long count, boolean otherBucket) {
    public String displayLabel() {
      if (otherBucket) {
        return "Other types";
      }
      return name == null || name.isEmpty() ? "(unnamed)" : name;
    }
  }

  /**
   * @param sampleRows how many data rows were scanned (denominator for % labels)
   */
  public record Catalog(
      List<Entry> entries, Set<String> listedNames, boolean hasOtherBucket, int sampleRows) {
  }

  private BedFeatureNameCatalog() {}

  /**
   * Build a dialog catalog from the first {@link #SAMPLE_DATA_LINES} data rows.
   * Empty when the sample has no named features.
   */
  public static Optional<Catalog> scanForDialog(Path filePath) throws IOException {
    if (filePath == null) {
      return Optional.empty();
    }
    Map<String, Mutable> seen = new LinkedHashMap<>();
    boolean isGzipped = filePath.toString().endsWith(".gz");
    int dataLines = 0;
    boolean hitSampleLimit = false;
    try (BufferedReader reader = isGzipped
        ? new BufferedReader(new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(filePath))))
        : Files.newBufferedReader(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()
            || line.charAt(0) == '#'
            || line.startsWith("track")
            || line.startsWith("browser")) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 3) {
          continue;
        }
        if (dataLines >= SAMPLE_DATA_LINES) {
          hitSampleLimit = true;
          break;
        }
        dataLines++;
        String name = parts.length > 3 ? parts[3].trim() : "";
        if (name.isEmpty()) {
          continue;
        }
        Color itemRgb = parts.length > 8 ? parseItemRgb(parts[8]) : null;
        Mutable m = seen.get(name);
        if (m == null) {
          Color color = itemRgb != null ? itemRgb : FeatureNameColors.colorForName(name);
          seen.put(name, new Mutable(color, 1));
        } else {
          m.count++;
          if (m.color == null && itemRgb != null) {
            m.color = itemRgb;
          }
        }
      }
    }
    if (seen.isEmpty()) {
      return Optional.empty();
    }

    List<Map.Entry<String, Mutable>> ranked = new ArrayList<>(seen.entrySet());
    ranked.sort(Comparator
        .comparingLong((Map.Entry<String, Mutable> e) -> e.getValue().count)
        .reversed()
        .thenComparing(Map.Entry::getKey));

    List<Entry> entries = new ArrayList<>(ranked.size() + 1);
    Set<String> listed = new LinkedHashSet<>();
    for (Map.Entry<String, Mutable> e : ranked) {
      Mutable m = e.getValue();
      Color color = m.color != null ? m.color : FeatureNameColors.colorForName(e.getKey());
      entries.add(new Entry(e.getKey(), color, m.count, false));
      listed.add(e.getKey());
    }

    // More of the file exists — names outside this sample map to Other.
    boolean hasOther = hitSampleLimit;
    if (hasOther) {
      entries.add(new Entry(
          OTHER_NAME,
          Color.rgb(140, 140, 150),
          0,
          true));
    }

    // Denominator for % labels: the scanned window size (up to SAMPLE_DATA_LINES).
    int sampleRows = Math.max(1, dataLines);
    return Optional.of(new Catalog(
        List.copyOf(entries), Set.copyOf(listed), hasOther, sampleRows));
  }

  private static Color parseItemRgb(String csvRgb) {
    if (csvRgb == null || csvRgb.isBlank() || ".".equals(csvRgb.trim())) {
      return null;
    }
    try {
      String[] parts = csvRgb.split(",");
      if (parts.length == 3) {
        int r = Integer.parseInt(parts[0].trim());
        int g = Integer.parseInt(parts[1].trim());
        int b = Integer.parseInt(parts[2].trim());
        if (r == 0 && g == 0 && b == 0) {
          return null;
        }
        return Color.rgb(r, g, b);
      }
    } catch (RuntimeException ignored) {
    }
    return null;
  }

  private static final class Mutable {
    Color color;
    long count;

    Mutable(Color color, long count) {
      this.color = color;
      this.count = count;
    }
  }
}
