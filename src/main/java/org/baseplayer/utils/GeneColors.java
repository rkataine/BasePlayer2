package org.baseplayer.utils;

import org.baseplayer.annotation.CosmicGenes;

import javafx.scene.paint.Color;

/**
 * Shared gene coloring utilities used across the application.
 */
public final class GeneColors {
  
  private GeneColors() {} // Utility class
  
  /** Color for UTR (untranslated regions) */
  public static final Color UTR_COLOR = Color.web("#888888");
  
  /** Color for start codon (ATG/M) */
  public static final Color START_CODON_COLOR = Color.web("#00aa00");
  
  /** Color for stop codons (TAA, TAG, TGA) */
  public static final Color STOP_CODON_COLOR = Color.web("#ff0000");
  
  /** Get color for a single amino acid character. Delegates to {@link AminoAcids#getColor}. */
  public static Color getAminoAcidColor(char aminoAcid) {
    return AminoAcids.getColor(aminoAcid);
  }

  /** Get three-letter code for an amino acid. Delegates to {@link AminoAcids#getThreeLetter}. */
  public static String getAminoAcidThreeLetter(char aminoAcid) {
    return AminoAcids.getThreeLetter(aminoAcid);
  }
  
  /**
   * Get contrasting text color (black or white) for a given background color.
   * Uses luminance calculation to determine readability.
   */
  public static Color getContrastingTextColor(Color background) {
    // Calculate relative luminance using sRGB formula
    double luminance = 0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue();
    return luminance > 0.5 ? Color.BLACK : Color.WHITE;
  }
  
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
