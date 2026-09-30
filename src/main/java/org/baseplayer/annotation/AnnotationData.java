package org.baseplayer.annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.Cytoband;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.genome.gene.Transcript;

/**
 * Holds loaded annotation data (genes, cytobands) for application-wide access.
 * Data is loaded by AnnotationLoader and accessed from here.
 *
 * <p>Gene maps/lists are replaced atomically after a full load so the UI never
 * observes a half-built collection (avoids ConcurrentModificationException).
 */
public final class AnnotationData {
  
  private AnnotationData() {} // Utility class
  
  // Cytoband data
  private static final List<Cytoband> cytobands = new ArrayList<>();
  private static boolean cytobandsLoaded = false;
  
  // Gene annotation data (volatile snapshots; replaced as a unit after load)
  private static volatile Map<String, List<Gene>> genesByChrom = Map.of();
  private static volatile boolean genesLoaded = false;
  private static volatile boolean genesLoading = false;
  
  // Gene search data
  private static volatile Map<String, GeneLocation> geneSearchMap = Map.of();
  private static volatile Map<String, String> geneBiotypeMap = Map.of();
  private static volatile List<String> geneNames = List.of();
  
  // Non-MANE transcripts (loaded on demand from separate cache)
  private static volatile Map<String, List<Transcript>> nonManeTranscripts = null;
  private static volatile boolean nonManeTranscriptsLoaded = false;
  
  // Highlighted gene location (for search preview)
  private static volatile GeneLocation highlightedGeneLocation = null;
  
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

  /**
   * Gene function / product description from already-loaded GFF annotation, or null.
   * Does not load new data — only looks up genes currently in memory.
   */
  public static String getGeneDescription(String geneName) {
    if (geneName == null || geneName.isBlank()) {
      return null;
    }
    GeneLocation loc = getGeneLocation(geneName);
    if (loc == null || loc.chrom() == null) {
      return null;
    }
    List<Gene> genes = genesByChrom.get(loc.chrom());
    if (genes == null || genes.isEmpty()) {
      return null;
    }
    String key = geneName.toLowerCase();
    for (Gene gene : genes) {
      if (gene.name() != null && key.equals(gene.name().toLowerCase())) {
        String description = gene.description();
        if (description == null || description.isBlank()) {
          return null;
        }
        return description.replaceAll("\\s*\\[Source:.*?\\]", "").trim();
      }
    }
    return null;
  }
  
  // --- Non-MANE transcripts (lazy loading) ---
  
  public static List<Transcript> getNonManeTranscripts(String geneId) {
    Map<String, List<Transcript>> map = nonManeTranscripts;
    if (map == null) return List.of();
    return map.getOrDefault(geneId, List.of());
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
    List<String> names = geneNames;
    return names.stream()
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
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }
  
  public static void clearHighlightedGene() {
    highlightedGeneLocation = null;
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }
  
  /**
   * Clear all data (useful for testing or when loading new genome).
   */
  public static void clear() {
    clearGenes();
    cytobands.clear();
    cytobandsLoaded = false;
  }

  /**
   * Atomically publish a fully built gene annotation snapshot.
   * Callers must not mutate the maps/lists after publishing.
   */
  public static void replaceGeneAnnotation(
      Map<String, List<Gene>> byChrom,
      Map<String, GeneLocation> searchMap,
      Map<String, String> biotypeMap,
      List<String> names) {
    genesByChrom = byChrom != null ? byChrom : Map.of();
    geneSearchMap = searchMap != null ? searchMap : Map.of();
    geneBiotypeMap = biotypeMap != null ? biotypeMap : Map.of();
    geneNames = names != null ? names : List.of();
    nonManeTranscripts = null;
    nonManeTranscriptsLoaded = false;
    highlightedGeneLocation = null;
    genesLoaded = true;
  }

  /** Drop gene annotation (empty snapshots). Does not clear {@code genesLoading}. */
  public static void clearGenes() {
    genesByChrom = Map.of();
    geneSearchMap = Map.of();
    geneBiotypeMap = Map.of();
    geneNames = List.of();
    genesLoaded = false;
    nonManeTranscripts = null;
    nonManeTranscriptsLoaded = false;
    highlightedGeneLocation = null;
  }
}
