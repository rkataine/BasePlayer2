package org.baseplayer.io.readers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.baseplayer.utils.ChromosomeNames;

import javafx.scene.paint.Color;

/**
 * Reads BED format files (BED3, BED6, BED12) into {@link BedFeature} records.
 * Supports both plain and gzipped files. Chromosome keys are stored without a
 * {@code chr} prefix; {@link BedLoad#chromPrefix()} records whether the file used one.
 */
public class BedFileReader {

  private static final Color DEFAULT_FEATURE_COLOR = Color.rgb(70, 130, 180);

  public record BedFeature(
      String chrom,
      long start,
      long end,
      String name,
      double score,
      String strand,
      Color color
  ) {}

  public record BedLoad(Map<String, List<BedFeature>> featuresByChrom, String chromPrefix) {}

  public static Map<String, List<BedFeature>> read(Path filePath) throws IOException {
    return readLoad(filePath, DEFAULT_FEATURE_COLOR).featuresByChrom();
  }

  public static Map<String, List<BedFeature>> read(Path filePath, Color defaultColor) throws IOException {
    return readLoad(filePath, defaultColor).featuresByChrom();
  }

  public static BedLoad readLoad(Path filePath, Color defaultColor) throws IOException {
    Map<String, List<BedFeature>> featuresByChrom = new HashMap<>();
    boolean isGzipped = filePath.toString().endsWith(".gz");
    java.util.ArrayList<String> rawChromNames = new java.util.ArrayList<>();

    try (BufferedReader reader = isGzipped
        ? new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(filePath))))
        : Files.newBufferedReader(filePath)) {

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("#") || line.startsWith("track") || line.startsWith("browser")) {
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length < 3) continue;

        try {
          String rawChrom = parts[0];
          rawChromNames.add(rawChrom);
          String chrom = ChromosomeNames.strip(rawChrom);
          long start = Long.parseLong(parts[1]);
          long end = Long.parseLong(parts[2]);
          String name = parts.length > 3 ? parts[3] : "";
          double score = parts.length > 4 ? parseScore(parts[4]) : 0;
          String strand = parts.length > 5 ? parts[5] : ".";
          Color color = parts.length > 8 ? parseRgbColor(parts[8], defaultColor) : defaultColor;

          featuresByChrom
              .computeIfAbsent(chrom, k -> new ArrayList<>())
              .add(new BedFeature(chrom, start, end, name, score, strand, color));
        } catch (NumberFormatException ignored) {
        }
      }
    }

    for (List<BedFeature> features : featuresByChrom.values()) {
      features.sort(java.util.Comparator
          .comparingLong(BedFeature::start)
          .thenComparingLong(BedFeature::end));
    }

    return new BedLoad(featuresByChrom, ChromosomeNames.detectPrefix(rawChromNames));
  }

  private static double parseScore(String s) {
    try { return Double.parseDouble(s); }
    catch (NumberFormatException ignored) { return 0; }
  }

  private static Color parseRgbColor(String csvRgb, Color fallback) {
    try {
      String[] parts = csvRgb.split(",");
      if (parts.length == 3) {
        return Color.rgb(
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim()));
      }
    } catch (NumberFormatException ignored) {
    }
    return fallback;
  }
}
