package org.baseplayer.utils;

import java.util.Map;

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
  
  /**
   * Amino acid colors based on chemical properties (Taylor scheme inspired).
   * Hydrophobic: warm colors (yellows, oranges)
   * Polar: cool colors (greens, cyans)  
   * Charged: blues (positive) and reds (negative)
   * Special: distinct colors
   */
  private static final Map<Character, Color> AMINO_ACID_COLORS = Map.ofEntries(
      // Hydrophobic (yellows/oranges)
      Map.entry('A', Color.web("#ccff00")),  // Alanine
      Map.entry('V', Color.web("#99ff00")),  // Valine
      Map.entry('I', Color.web("#66cc00")),  // Isoleucine
      Map.entry('L', Color.web("#33cc00")),  // Leucine
      Map.entry('M', Color.web("#00ff00")),  // Methionine (start)
      Map.entry('F', Color.web("#00ff66")),  // Phenylalanine
      Map.entry('W', Color.web("#00ccff")),  // Tryptophan
      // Polar (cyans/light blues)
      Map.entry('S', Color.web("#ff9900")),  // Serine
      Map.entry('T', Color.web("#ff9933")),  // Threonine
      Map.entry('N', Color.web("#cc66ff")),  // Asparagine
      Map.entry('Q', Color.web("#ff66cc")),  // Glutamine
      // Charged positive (blues)
      Map.entry('K', Color.web("#6666ff")),  // Lysine
      Map.entry('R', Color.web("#0000ff")),  // Arginine
      Map.entry('H', Color.web("#8282d2")),  // Histidine
      // Charged negative (reds/pinks)
      Map.entry('D', Color.web("#ff0000")),  // Aspartic acid
      Map.entry('E', Color.web("#cc0000")),  // Glutamic acid
      // Special
      Map.entry('C', Color.web("#ffff00")),  // Cysteine (sulfur)
      Map.entry('G', Color.web("#ff8800")),  // Glycine (small)
      Map.entry('P', Color.web("#ffcc00")),  // Proline (rigid)
      Map.entry('Y', Color.web("#00ffcc")),  // Tyrosine
      // Stop codon
      Map.entry('*', Color.web("#ff0000"))   // Stop
  );
  
  /**
   * Get color for a single amino acid character.
   */
  public static Color getAminoAcidColor(char aminoAcid) {
    return AMINO_ACID_COLORS.getOrDefault(Character.toUpperCase(aminoAcid), Color.GRAY);
  }
  
  /**
   * Three-letter amino acid codes.
   */
  private static final Map<Character, String> AMINO_ACID_THREE_LETTER = Map.ofEntries(
      Map.entry('A', "Ala"),
      Map.entry('C', "Cys"),
      Map.entry('D', "Asp"),
      Map.entry('E', "Glu"),
      Map.entry('F', "Phe"),
      Map.entry('G', "Gly"),
      Map.entry('H', "His"),
      Map.entry('I', "Ile"),
      Map.entry('K', "Lys"),
      Map.entry('L', "Leu"),
      Map.entry('M', "Met"),
      Map.entry('N', "Asn"),
      Map.entry('P', "Pro"),
      Map.entry('Q', "Gln"),
      Map.entry('R', "Arg"),
      Map.entry('S', "Ser"),
      Map.entry('T', "Thr"),
      Map.entry('V', "Val"),
      Map.entry('W', "Trp"),
      Map.entry('Y', "Tyr"),
      Map.entry('*', "Stp")
  );
  
  /**
   * Get three-letter code for an amino acid.
   */
  public static String getAminoAcidThreeLetter(char aminoAcid) {
    return AMINO_ACID_THREE_LETTER.getOrDefault(Character.toUpperCase(aminoAcid), "???");
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
