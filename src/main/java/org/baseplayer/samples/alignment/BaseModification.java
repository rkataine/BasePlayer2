package org.baseplayer.samples.alignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents base modifications parsed from MM:Z and ML:B:C tags.
 * Each BaseModification corresponds to one modification type tracked
 * in the MM tag (e.g., "C+m" for 5-Methylcytosine on the forward strand).
 */
public class BaseModification {
  
  /** Unmodified base ('A', 'C', 'G', 'T', 'U', or 'N') */
  public final char baseType;
  
  /** Strand: '+' for forward/same as SEQ, '-' for reverse/opposite strand */
  public final char strand;
  
  /** 
   * Modification code(s). Single modification like "m" (5mC) or multiple like "mh".
   * May also be a ChEBI numeric code (stored as string, e.g. "76792").
   */
  public final String modCode;
  
  /**
   * Flag indicating how skipped bases should be interpreted:
   * '.' or missing = skipped bases assumed low probability of modification
   * '?' = no information about skipped bases
   */
  public final char implicitFlag;
  
  /**
   * Delta-encoded positions of modified bases within the sequence.
   * Each value is the number of bases of baseType to skip from the last position.
   * First value starts from position 0 (5' end of uncomplemented SEQ).
   * 
   * Example: [5, 12, 0] means modifications at the 6th, 19th, and 20th occurrence
   * of the base type (1-indexed).
   */
  public final int[] deltaPositions;
  
  /**
   * Modification probabilities (0-255) corresponding to each position in deltaPositions.
   * From ML:B:C tag. May be null if ML tag is absent or empty.
   * Values 0-255 map to probabilities 0.0-1.0 (value N represents range N/256 to (N+1)/256).
   */
  public byte[] probabilities;
  
  public BaseModification(char baseType, char strand, String modCode, char implicitFlag, int[] deltaPositions) {
    this.baseType = baseType;
    this.strand = strand;
    this.modCode = modCode;
    this.implicitFlag = implicitFlag;
    this.deltaPositions = deltaPositions;
  }
  
  /**
   * Parse MM:Z tag string into a list of BaseModification objects.
   * Format: ([ACGTUN][-+]([a-z]+|[0-9]+)[.?]?(,[0-9]+)*;)*
   * 
   * Example: "C+m,5,12,0;G-m,14;" parses to two modifications.
   * 
   * @param mmTag the MM:Z tag value (without "MM:Z:" prefix)
   * @return list of parsed modifications, or empty list if parsing fails
   */
  public static List<BaseModification> parseMMTag(String mmTag) {
    List<BaseModification> mods = new ArrayList<>();
    if (mmTag == null || mmTag.isEmpty()) return mods;
    
    // Split by semicolon to get individual modification entries
    String[] entries = mmTag.split(";");
    for (String entry : entries) {
      entry = entry.trim();
      if (entry.isEmpty()) continue;
      
      try {
        // Parse base type (first character)
        if (entry.length() < 3) continue;
        char baseType = entry.charAt(0);
        if ("ACGTUN".indexOf(baseType) < 0) continue;
        
        // Parse strand (+ or -)
        char strand = entry.charAt(1);
        if (strand != '+' && strand != '-') continue;
        
        // Parse modification code(s) and implicit flag
        int pos = 2;
        StringBuilder modCodeBuilder = new StringBuilder();
        while (pos < entry.length()) {
          char ch = entry.charAt(pos);
          if (ch == '.' || ch == '?' || ch == ',') break;
          modCodeBuilder.append(ch);
          pos++;
        }
        String modCode = modCodeBuilder.toString();
        if (modCode.isEmpty()) continue;
        
        // Parse optional implicit flag (. or ?)
        char implicitFlag = '.'; // default
        if (pos < entry.length() && (entry.charAt(pos) == '.' || entry.charAt(pos) == '?')) {
          implicitFlag = entry.charAt(pos);
          pos++;
        }
        
        // Parse delta positions (comma-separated integers)
        List<Integer> deltaList = new ArrayList<>();
        if (pos < entry.length()) {
          if (entry.charAt(pos) == ',') pos++; // skip leading comma
          String[] deltaParts = entry.substring(pos).split(",");
          for (String part : deltaParts) {
            part = part.trim();
            if (!part.isEmpty()) {
              try {
                deltaList.add(Integer.parseInt(part));
              } catch (NumberFormatException e) {
                // Skip invalid delta value
              }
            }
          }
        }
        
        // Convert delta list to array
        int[] deltaPositions = new int[deltaList.size()];
        for (int i = 0; i < deltaList.size(); i++) {
          deltaPositions[i] = deltaList.get(i);
        }
        
        mods.add(new BaseModification(baseType, strand, modCode, implicitFlag, deltaPositions));
        
      } catch (Exception e) {
        // Skip malformed entry
        System.err.println("Warning: Failed to parse MM tag entry: " + entry + " - " + e.getMessage());
      }
    }
    
    return mods;
  }
  
  /**
   * Convert delta positions to absolute base-type indices.
   * Returns array of indices (0-based) within the sequence of bases of this modification's baseType.
   * 
   * Example: deltaPositions [5, 12, 0] returns [5, 18, 19]
   * (the 6th, 19th, and 20th occurrence of the base, using 0-based indexing).
   * 
   * @return array of absolute 0-based indices for bases of this type
   */
  public int[] getAbsoluteIndices() {
    if (deltaPositions == null || deltaPositions.length == 0) {
      return new int[0];
    }
    
    int[] absolute = new int[deltaPositions.length];
    int current = 0;
    for (int i = 0; i < deltaPositions.length; i++) {
      current += deltaPositions[i];
      absolute[i] = current;
      current++; // move past the current modified base
    }
    return absolute;
  }
  
  /**
   * Map modification positions to actual read indices (0-based) in the SEQ field.
   * This accounts for the base type - we count only occurrences of the specified base.
   * 
   * @param sequence the read sequence (from SEQ field, may be reverse complemented)
   * @param isReverseComplemented whether SEQ has been reverse complemented (FLAG 0x10)
   * @return array of read indices where modifications occur, or empty array if no matches
   */
  public int[] mapToReadIndices(char[] sequence, boolean isReverseComplemented) {
    if (sequence == null || deltaPositions == null || deltaPositions.length == 0) {
      return new int[0];
    }
    
    // Get absolute indices within bases of this type
    int[] absoluteIndices = getAbsoluteIndices();
    
    // Count occurrences of the base type in sequence and map indices
    List<Integer> readIndices = new ArrayList<>();
    int baseCount = 0;
    
    // MM tag always refers to as-sequenced orientation (5' end)
    // If FLAG 0x10 is set, sequence has been reverse complemented, but
    // modification positions still count from the original 5' end
    for (int readIdx = 0; readIdx < sequence.length; readIdx++) {
      char seqBase = sequence[readIdx];
      
      // Check if this base matches our modification base type
      // Note: If N is the base type, it matches any base
      boolean matches = false;
      if (baseType == 'N') {
        matches = true;
      } else if (isReverseComplemented && strand == '-') {
        // When reverse complemented, opposite strand modifications need base complement check
        matches = seqBase == complementBase(baseType);
      } else if (isReverseComplemented && strand == '+') {
        // When reverse complemented, same strand modifications are on what's now the opposite strand
        matches = seqBase == baseType;
      } else {
        // Not reverse complemented: direct match
        matches = seqBase == baseType;
      }
      
      if (matches) {
        // Check if this base count matches any of our absolute indices
        for (int absIdx : absoluteIndices) {
          if (baseCount == absIdx) {
            readIndices.add(readIdx);
            break;
          }
        }
        baseCount++;
      }
    }
    
    // Convert to array
    int[] result = new int[readIndices.size()];
    for (int i = 0; i < readIndices.size(); i++) {
      result[i] = readIndices.get(i);
    }
    return result;
  }
  
  /**
   * Get the complement of a DNA base.
   */
  private static char complementBase(char base) {
    return switch (base) {
      case 'A' -> 'T';
      case 'T' -> 'A';
      case 'C' -> 'G';
      case 'G' -> 'C';
      case 'U' -> 'A'; // RNA
      default -> base;
    };
  }
  
  /**
   * Get the probability (0.0-1.0) for a modification at the given index.
   * 
   * @param index index into deltaPositions/probabilities array (NOT read index)
   * @return probability 0.0-1.0, or -1.0 if probabilities are not available
   */
  public double getProbability(int index) {
    if (probabilities == null || index < 0 || index >= probabilities.length) {
      return -1.0;
    }
    int value = probabilities[index] & 0xFF; // unsigned byte 0-255
    return value / 256.0; // map to probability range
  }
  
  /**
   * Get human-readable modification name from code.
   * Returns full name if known, otherwise returns the code itself.
   */
  public String getModificationName() {
    if (modCode.length() == 1) {
      return switch (modCode.charAt(0)) {
        case 'm' -> baseType == 'C' ? "5-Methylcytosine" : "6-Methyladenine";
        case 'h' -> "5-Hydroxymethylcytosine";
        case 'f' -> "5-Formylcytosine";
        case 'c' -> "5-Carboxylcytosine";
        case 'g' -> "5-Hydroxymethyluracil";
        case 'e' -> "5-Formyluracil";
        case 'b' -> "5-Carboxyuracil";
        case 'a' -> "6-Methyladenine";
        case 'o' -> "8-Oxoguanine";
        case 'n' -> "Xanthosine";
        default -> {
          // Ambiguity codes
          if ("ACGTUN".indexOf(modCode.charAt(0)) >= 0) {
            yield "Unspecified " + modCode.charAt(0) + " modification";
          }
          yield modCode;
        }
      };
    }
    // Multiple modifications or ChEBI code
    return modCode;
  }
  
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(baseType).append(strand).append(modCode);
    if (implicitFlag != '.') sb.append(implicitFlag);
    sb.append(" [");
    for (int i = 0; i < deltaPositions.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(deltaPositions[i]);
    }
    sb.append("]");
    if (probabilities != null) {
      sb.append(" probs:[");
      for (int i = 0; i < Math.min(probabilities.length, 10); i++) {
        if (i > 0) sb.append(", ");
        sb.append(probabilities[i] & 0xFF);
      }
      if (probabilities.length > 10) sb.append("...");
      sb.append("]");
    }
    return sb.toString();
  }
}
