package org.baseplayer.tracks;

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

import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Track for displaying BED format files.
 * Supports BED3, BED6, and BED12 formats, both plain and gzipped.
 */
public class BedTrack extends AbstractTrack {
  
  /**
   * A BED feature record.
   */
  public record BedFeature(
      String chrom,
      long start,      // 0-based
      long end,        // exclusive
      String name,
      double score,
      String strand,
      Color color
  ) {}
  
  private final Path filePath;
  private final Map<String, List<BedFeature>> featuresByChrom = new HashMap<>();
  private boolean loaded = false;
  
  public BedTrack(Path filePath) throws IOException {
    super(filePath.getFileName().toString(), "BED");
    this.filePath = filePath;
    this.preferredHeight = 25;
    this.color = Color.rgb(70, 130, 180); // Steel blue
    
    // Load the file
    loadFile();
  }
  
  private void loadFile() throws IOException {
    boolean isGzipped = filePath.toString().endsWith(".gz");
    
    try (BufferedReader reader = isGzipped 
        ? new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(filePath))))
        : Files.newBufferedReader(filePath)) {
      
      String line;
      while ((line = reader.readLine()) != null) {
        // Skip comments and track lines
        if (line.startsWith("#") || line.startsWith("track") || line.startsWith("browser")) {
          continue;
        }
        
        String[] parts = line.split("\t");
        if (parts.length < 3) continue;
        
        try {
          String chrom = parts[0].replace("chr", "");
          long start = Long.parseLong(parts[1]);
          long end = Long.parseLong(parts[2]);
          
          String name = parts.length > 3 ? parts[3] : "";
          double score = parts.length > 4 ? parseScore(parts[4]) : 0;
          String strand = parts.length > 5 ? parts[5] : ".";
          Color featureColor = parts.length > 8 ? parseColor(parts[8]) : this.color;
          
          BedFeature feature = new BedFeature(chrom, start, end, name, score, strand, featureColor);
          featuresByChrom.computeIfAbsent(chrom, k -> new ArrayList<>()).add(feature);
          
        } catch (NumberFormatException e) {
          // Skip malformed lines
        }
      }
    }
    
    loaded = true;
    System.out.println("Loaded BED track: " + name + " with " + 
        featuresByChrom.values().stream().mapToInt(List::size).sum() + " features");
  }
  
  private double parseScore(String s) {
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
  
  private Color parseColor(String s) {
    try {
      String[] rgb = s.split(",");
      if (rgb.length == 3) {
        return Color.rgb(
            Integer.parseInt(rgb[0].trim()),
            Integer.parseInt(rgb[1].trim()),
            Integer.parseInt(rgb[2].trim())
        );
      }
    } catch (Exception e) {
      // Fall through
    }
    return this.color;
  }
  
  @Override
  public void onRegionChanged(String chromosome, long start, long end) {
    // BED files are loaded entirely, so nothing to fetch
  }
  
  @Override
  public void draw(GraphicsContext gc, double x, double y, double width, double height,
                   String chromosome, double start, double end) {
    
    // Track background
    gc.setFill(Color.rgb(28, 28, 32));
    gc.fillRect(x, y, width, height);
    
    // Track label
    gc.setFill(Color.GRAY);
    gc.setFont(AppFonts.getUIFont(9));
    gc.setTextAlign(TextAlignment.LEFT);
    gc.fillText(name, x + 4, y + 10);
    
    if (!loaded) {
      gc.setFill(Color.rgb(80, 80, 80));
      gc.fillText("Loading...", x + width / 2 - 30, y + height / 2 + 4);
      return;
    }
    
    List<BedFeature> features = featuresByChrom.get(chromosome);
    if (features == null || features.isEmpty()) {
      return;
    }
    
    // Draw features
    double featureY = y + 14;
    double featureHeight = height - 18;
    double viewLength = end - start;
    
    for (BedFeature feature : features) {
      // Skip if outside view (BED is 0-based, our view is 1-based)
      if (feature.end() < start || feature.start() + 1 > end) continue;
      
      double featureX1 = x + ((feature.start() + 1 - start) / viewLength) * width;
      double featureX2 = x + ((feature.end() - start) / viewLength) * width;
      
      // Clamp to view
      featureX1 = Math.max(x, featureX1);
      featureX2 = Math.min(x + width, featureX2);
      
      double featureWidth = Math.max(1, featureX2 - featureX1);
      
      // Draw feature box
      gc.setFill(feature.color());
      gc.fillRect(featureX1, featureY, featureWidth, featureHeight);
      
      // Draw name if there's space
      if (featureWidth > 30 && feature.name() != null && !feature.name().isEmpty()) {
        gc.setFill(Color.WHITE);
        gc.setFont(AppFonts.getUIFont(8));
        gc.fillText(feature.name(), featureX1 + 2, featureY + featureHeight / 2 + 3);
      }
    }
    
    gc.setTextAlign(TextAlignment.LEFT);
  }
  
  /**
   * Get features for a chromosome.
   */
  public List<BedFeature> getFeatures(String chromosome) {
    return featuresByChrom.getOrDefault(chromosome, List.of());
  }
  
  /**
   * Get all chromosomes with features.
   */
  public java.util.Set<String> getChromosomes() {
    return featuresByChrom.keySet();
  }
}
