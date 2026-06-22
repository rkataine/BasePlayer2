package org.baseplayer.samples.alignment.draw;

import javafx.scene.paint.Color;

/**
 * Color schemes for rendering base modifications from MM/ML tags.
 */
public enum ModificationColorScheme {
  /** Each modification type gets a distinct color (5mC=red, 5hmC=orange, 6mA=purple, etc.) */
  BY_TYPE("By modification type"),
  
  /** Simple probability gradient: blue (low) to red (high), same for all modification types */
  PROBABILITY_GRADIENT("Probability gradient"),
  
  /** Binary: red=modified (prob>0.5), blue=unmodified (prob<0.5) */
  BINARY("Binary (modified/unmodified)");

  private final String label;

  ModificationColorScheme(String label) { this.label = label; }

  public String getLabel() { return label; }

  @Override 
  public String toString() { return label; }

  /**
   * Get color for a modification based on the scheme.
   * 
   * @param modCode modification code (e.g., "m", "h", "a")
   * @param probability modification probability 0.0-1.0
   * @param baseType unmodified base type (A, C, G, T, U, N)
   * @return color to render this modification
   */
  public Color getColor(String modCode, double probability, char baseType) {
    return switch (this) {
      case BY_TYPE -> getModificationTypeColor(modCode, baseType, probability);
      case PROBABILITY_GRADIENT -> probabilityGradient(probability);
      case BINARY -> probability > 0.5 ? Color.web("#cc5555") : Color.web("#5588cc");
    };
  }

  /**
   * Get a distinct color for each modification type.
   * Color intensity varies with probability.
   */
  private static Color getModificationTypeColor(String modCode, char baseType, double prob) {
    // Clamp probability to [0.3, 1.0] so colors are always visible
    double intensity = 0.3 + (prob * 0.7);
    
    // Single character codes
    if (modCode.length() == 1) {
      char code = modCode.charAt(0);
      return switch (code) {
        // Cytosine modifications
        case 'm' -> Color.color(0.9 * intensity, 0.2 * intensity, 0.2 * intensity); // Red - 5mC
        case 'h' -> Color.color(1.0 * intensity, 0.5 * intensity, 0.1 * intensity); // Orange - 5hmC
        case 'f' -> Color.color(1.0 * intensity, 0.7 * intensity, 0.0 * intensity); // Yellow - 5fC
        case 'c' -> Color.color(1.0 * intensity, 0.8 * intensity, 0.3 * intensity); // Gold - 5caC
        
        // Thymine/Uracil modifications
        case 'g' -> Color.color(0.4 * intensity, 0.8 * intensity, 0.4 * intensity); // Green - 5hmU
        case 'e' -> Color.color(0.3 * intensity, 0.7 * intensity, 0.5 * intensity); // Teal - 5fU
        case 'b' -> Color.color(0.5 * intensity, 0.9 * intensity, 0.6 * intensity); // Light green - 5caU
        
        // Adenine modifications
        case 'a' -> Color.color(0.6 * intensity, 0.3 * intensity, 0.8 * intensity); // Purple - 6mA
        
        // Guanine modifications
        case 'o' -> Color.color(0.3 * intensity, 0.5 * intensity, 0.9 * intensity); // Blue - 8oxoG
        
        // Xanthosine
        case 'n' -> Color.color(0.8 * intensity, 0.4 * intensity, 0.6 * intensity); // Pink - Xanthosine
        
        // Ambiguity codes - use base-type color
        case 'C' -> Color.color(0.9 * intensity, 0.4 * intensity, 0.4 * intensity); // Light red
        case 'T', 'U' -> Color.color(0.5 * intensity, 0.9 * intensity, 0.5 * intensity); // Light green
        case 'A' -> Color.color(0.7 * intensity, 0.4 * intensity, 0.9 * intensity); // Light purple
        case 'G' -> Color.color(0.4 * intensity, 0.6 * intensity, 1.0 * intensity); // Light blue
        case 'N' -> Color.color(0.7 * intensity, 0.7 * intensity, 0.7 * intensity); // Gray
        
        default -> Color.color(0.6 * intensity, 0.6 * intensity, 0.6 * intensity); // Gray fallback
      };
    }
    
    // Multi-modification codes or ChEBI - use mixed color based on first character
    if (modCode.length() > 0) {
      return getModificationTypeColor(String.valueOf(modCode.charAt(0)), baseType, prob);
    }
    
    // Fallback
    return Color.color(0.6 * intensity, 0.6 * intensity, 0.6 * intensity);
  }

  /**
   * Blue to red gradient through yellow based on probability.
   * 0.0 = blue (low probability), 1.0 = red (high probability).
   */
  private static Color probabilityGradient(double prob) {
    prob = Math.max(0.0, Math.min(1.0, prob));
    
    if (prob < 0.5) {
      // Blue to yellow (0.0-0.5)
      double t = prob * 2.0;
      return Color.color(t, t, 1.0 - t);
    } else {
      // Yellow to red (0.5-1.0)
      double t = (prob - 0.5) * 2.0;
      return Color.color(1.0, 1.0 - t, 0.0);
    }
  }

  /**
   * Get a human-readable description of the color scheme.
   */
  public String getDescription() {
    return switch (this) {
      case BY_TYPE -> "Each modification type (5mC, 5hmC, 6mA, etc.) uses a distinct color. Color intensity reflects probability.";
      case PROBABILITY_GRADIENT -> "Blue (low probability) → Yellow → Red (high probability). Same gradient for all modification types.";
      case BINARY -> "Red = modified (probability > 50%), Blue = unmodified (probability < 50%).";
    };
  }
}
