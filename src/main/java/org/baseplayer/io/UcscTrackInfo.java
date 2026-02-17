package org.baseplayer.io;

/**
 * Metadata for a UCSC track.
 * 
 * @param trackName Internal track name (used in API calls)
 * @param shortLabel Short display label
 * @param longLabel Full description
 * @param type Track type (bigWig, bigBed, vcfTabix, etc.)
 * @param group Track group/category (genes, variation, regulation, etc.)
 */
public record UcscTrackInfo(
    String trackName,
    String shortLabel,
    String longLabel,
    String type,
    String group
) {
  /**
   * Get a user-friendly group name.
   */
  public String getGroupLabel() {
    return switch (group.toLowerCase()) {
      case "genes" -> "Gene Annotations";
      case "varRep" -> "Variation & Repeats";
      case "regulation" -> "Regulation";
      case "comparative" -> "Comparative Genomics";
      case "expression" -> "Expression";
      case "map" -> "Mapping & Sequencing";
      case "phenDis" -> "Phenotype & Disease";
      default -> capitalize(group);
    };
  }
  
  private static String capitalize(String s) {
    if (s == null || s.isEmpty()) return s;
    return s.substring(0, 1).toUpperCase() + s.substring(1);
  }
  
  /**
   * Check if this track type is supported for display.
   */
  public boolean isSupported() {
    String typeLower = type.toLowerCase();
    // Support bigWig, bigBed, and vcfTabix tracks for now
    return typeLower.startsWith("bigwig") || 
           typeLower.startsWith("bigbed") || 
           typeLower.contains("vcftabix");
  }
  
  /**
   * Get a simplified type label for UI.
   */
  public String getTypeLabel() {
    String typeLower = type.toLowerCase();
    if (typeLower.startsWith("bigwig")) return "Continuous";
    if (typeLower.startsWith("bigbed")) return "Regions";
    if (typeLower.contains("vcf")) return "Variants";
    return type;
  }
}
