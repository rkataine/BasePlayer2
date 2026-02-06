package org.baseplayer.annotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds loaded annotation data (genes, cytobands) for application-wide access.
 * Data is loaded by AnnotationLoader and accessed from here.
 */
public final class AnnotationData {
  
  private AnnotationData() {} // Utility class
  
  // Cytoband data
  private static final List<Cytoband> cytobands = new ArrayList<>();
  private static boolean cytobandsLoaded = false;
  
  // Gene annotation data
  private static final Map<String, List<Gene>> genesByChrom = new HashMap<>();
  private static boolean genesLoaded = false;
  private static boolean genesLoading = false;
  
  // Gene search data
  private static final Map<String, GeneLocation> geneSearchMap = new HashMap<>(); // name (lowercase) -> location
  private static final Map<String, String> geneBiotypeMap = new HashMap<>(); // name (lowercase) -> biotype
  private static final List<String> geneNames = new ArrayList<>(); // sorted list of all gene names
  
  // Non-MANE transcripts (loaded on demand from separate cache)
  private static Map<String, List<Transcript>> nonManeTranscripts = null; // geneId -> transcripts
  private static boolean nonManeTranscriptsLoaded = false;
  
  // Highlighted gene location (for search preview)
  private static GeneLocation highlightedGeneLocation = null;
  
  // --- Cytoband accessors ---
  
  public static List<Cytoband> getCytobands() {
    return cytobands;
  }
  
  public static boolean isCytobandsLoaded() {
    return cytobandsLoaded;
  }
  
  public static void setCytobandsLoaded(boolean loaded) {
    cytobandsLoaded = loaded;
  }
  
  // --- Gene accessors ---
  
  public static Map<String, List<Gene>> getGenesByChrom() {
    return genesByChrom;
  }
  
  public static boolean isGenesLoaded() {
    return genesLoaded;
  }
  
  public static void setGenesLoaded(boolean loaded) {
    genesLoaded = loaded;
  }
  
  public static boolean isGenesLoading() {
    return genesLoading;
  }
  
  public static void setGenesLoading(boolean loading) {
    genesLoading = loading;
  }
  
  // --- Gene search accessors ---
  
  public static Map<String, GeneLocation> getGeneSearchMap() {
    return geneSearchMap;
  }
  
  public static Map<String, String> getGeneBiotypeMap() {
    return geneBiotypeMap;
  }
  
  public static List<String> getGeneNames() {
    return geneNames;
  }
  
  public static String getGeneBiotype(String geneName) {
    if (geneName == null) return null;
    return geneBiotypeMap.get(geneName.toLowerCase());
  }
  
  public static GeneLocation getGeneLocation(String geneName) {
    if (geneName == null) return null;
    return geneSearchMap.get(geneName.toLowerCase());
  }
  
  // --- Non-MANE transcripts (lazy loading) ---
  
  public static List<Transcript> getNonManeTranscripts(String geneId) {
    if (nonManeTranscripts == null) return List.of();
    return nonManeTranscripts.getOrDefault(geneId, List.of());
  }
  
  public static void setNonManeTranscripts(Map<String, List<Transcript>> transcripts) {
    nonManeTranscripts = transcripts;
    nonManeTranscriptsLoaded = true;
  }
  
  public static boolean isNonManeTranscriptsLoaded() {
    return nonManeTranscriptsLoaded;
  }
  
  private static int getGeneSortPriority(String geneName) {
    // COSMIC genes first (0), then protein_coding (1), then miRNA (2), then others (3)
    if (CosmicGenes.isCosmicGene(geneName)) return 0;
    String biotype = getGeneBiotype(geneName);
    if (biotype == null) return 3;
    return switch (biotype) {
      case "protein_coding" -> 1;
      case "miRNA", "snRNA", "snoRNA" -> 2;
      default -> 3;
    };
  }
  
  public static List<String> searchGenes(String prefix) {
    if (!genesLoaded || prefix == null || prefix.length() < 2) return List.of();
    String lowerPrefix = prefix.toLowerCase();
    return geneNames.stream()
        .filter(name -> name.toLowerCase().startsWith(lowerPrefix))
        .sorted((a, b) -> {
          int priorityA = getGeneSortPriority(a);
          int priorityB = getGeneSortPriority(b);
          if (priorityA != priorityB) return Integer.compare(priorityA, priorityB);
          return a.compareToIgnoreCase(b);
        })
        .limit(10)
        .toList();
  }
  
  // --- Highlighted gene ---
  
  public static GeneLocation getHighlightedGeneLocation() {
    return highlightedGeneLocation;
  }
  
  public static void setHighlightedGene(GeneLocation loc) {
    highlightedGeneLocation = loc;
    org.baseplayer.draw.DrawFunctions.resizing = true;
    org.baseplayer.draw.DrawFunctions.update.set(!org.baseplayer.draw.DrawFunctions.update.get());
    org.baseplayer.draw.DrawFunctions.resizing = false;
  }
  
  public static void clearHighlightedGene() {
    highlightedGeneLocation = null;
    org.baseplayer.draw.DrawFunctions.resizing = true;
    org.baseplayer.draw.DrawFunctions.update.set(!org.baseplayer.draw.DrawFunctions.update.get());
    org.baseplayer.draw.DrawFunctions.resizing = false;
  }
  
  /**
   * Clear all data (useful for testing or when loading new genome).
   */
  public static void clear() {
    cytobands.clear();
    cytobandsLoaded = false;
    genesByChrom.clear();
    genesLoaded = false;
    genesLoading = false;
    geneSearchMap.clear();
    geneBiotypeMap.clear();
    geneNames.clear();
    nonManeTranscripts = null;
    nonManeTranscriptsLoaded = false;
    highlightedGeneLocation = null;
  }
}
