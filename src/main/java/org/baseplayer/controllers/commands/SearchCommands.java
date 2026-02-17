package org.baseplayer.controllers.commands;

import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.GeneLocation;

/**
 * Handles search operations: gene search, position search.
 */
public class SearchCommands {
  
  /**
   * Search for genes matching the query string.
   * 
   * @param query Search query (gene name prefix)
   * @return List of matching gene names
   */
  public static List<String> searchGenes(String query) {
    if (query == null || query.length() < 2) {
      return List.of();
    }
    return AnnotationData.searchGenes(query);
  }
  
  /**
   * Get gene location by name.
   * 
   * @param geneName Gene name
   * @return Gene location or null if not found
   */
  public static GeneLocation getGeneLocation(String geneName) {
    return AnnotationData.getGeneLocation(geneName);
  }
  
  /**
   * Get gene biotype.
   * 
   * @param geneName Gene name
   * @return Gene biotype string
   */
  public static String getGeneBiotype(String geneName) {
    return AnnotationData.getGeneBiotype(geneName);
  }
  
  /**
   * Highlight a gene in the visualization.
   * 
   * @param location Gene location to highlight
   */
  public static void highlightGene(GeneLocation location) {
    AnnotationData.setHighlightedGene(location);
  }
  
  /**
   * Clear gene highlight.
   */
  public static void clearGeneHighlight() {
    AnnotationData.clearHighlightedGene();
  }
  
  /**
   * Parse position string and navigate to it.
   * Supports formats:
   * - "12345" (single position, centers with minZoom flanks)
   * - "12345-67890" (range)
   * 
   * @param positionText Position string to parse
   * @return true if navigation was successful
   */
  public static boolean navigateToPositionString(String positionText) {
    if (positionText == null || positionText.isEmpty()) {
      return false;
    }
    
    try {
      String text = positionText.trim().replace(",", "");
      if (text.contains("-")) {
        // Range format: start-end
        String[] parts = text.split("-");
        if (parts.length == 2) {
          int start = Integer.parseInt(parts[0].trim());
          int end = Integer.parseInt(parts[1].trim());
          NavigationCommands.navigateToPosition(start, end);
          return true;
        }
      } else {
        // Single position: center on it with minZoom flanks
        int pos = Integer.parseInt(text);
        NavigationCommands.navigateToPosition(pos);
        return true;
      }
    } catch (NumberFormatException ex) {
      // Invalid format
      return false;
    }
    
    return false;
  }
  
  /**
   * Navigate to a gene by name.
   * 
   * @param geneName Gene name
   */
  public static void navigateToGene(String geneName) {
    NavigationCommands.navigateToGene(geneName);
  }
}
