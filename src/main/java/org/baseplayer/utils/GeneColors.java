package org.baseplayer.utils;

import org.baseplayer.annotation.CosmicGenes;

import javafx.scene.paint.Color;

/**
 * Shared gene coloring utilities used across the application.
 */
public final class GeneColors {
  
  private GeneColors() {} // Utility class
  
  /**
   * Get the color for a gene based on its biotype and COSMIC status.
   * COSMIC census genes are colored in a distinct orange-red color.
   */
  public static Color getGeneColor(String geneName, String biotype) {
    // COSMIC census genes are colored orange-red
    if (CosmicGenes.isCosmicGene(geneName)) {
      return Color.web("#d16624");
    }
    if (biotype == null) return Color.CORNFLOWERBLUE;
    return switch (biotype) {
      case "protein_coding" -> Color.web("#5a9a8a");  // Muted teal
      case "lncRNA", "lincRNA" -> Color.GRAY;
      case "miRNA", "snRNA", "snoRNA" -> Color.LIGHTCORAL;
      case "pseudogene", "processed_pseudogene", "transcribed_unitary_pseudogene" -> Color.LIGHTGRAY;
      default -> Color.CORNFLOWERBLUE;
    };
  }
  
  /**
   * Get gene color using only the gene name (looks up biotype from AnnotationData).
   */
  public static Color getGeneColor(String geneName) {
    String biotype = org.baseplayer.annotation.AnnotationData.getGeneBiotype(geneName);
    return getGeneColor(geneName, biotype);
  }
  
  /**
   * Convert a JavaFX Color to hex string for CSS styling.
   */
  public static String toHexString(Color color) {
    return String.format("#%02x%02x%02x",
        (int) (color.getRed() * 255),
        (int) (color.getGreen() * 255),
        (int) (color.getBlue() * 255));
  }
}
