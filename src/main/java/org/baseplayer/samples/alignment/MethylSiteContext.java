package org.baseplayer.samples.alignment;

/**
 * Bisulfite / cytosine methylation sequence contexts (CpG, CHG, CHH).
 * Classified from reference bases around a C (or complementary G) site.
 */
public enum MethylSiteContext {
  CPG("CpG"),
  CHG("CHG"),
  CHH("CHH");

  private final String shortLabel;

  MethylSiteContext(String shortLabel) {
    this.shortLabel = shortLabel;
  }

  public String shortLabel() {
    return shortLabel;
  }

  /**
   * Classify a cytosine-context site at {@code index} in {@code refBases}
   * (uppercase-insensitive). Returns {@code null} when the base is not C or G.
   * <ul>
   *   <li>C: look downstream (CG → CpG, C[ACT]G → CHG, else CHH)</li>
   *   <li>G: reverse-strand cytosine; look upstream (CG → CpG, C?G → CHG, else CHH)</li>
   * </ul>
   */
  public static MethylSiteContext fromRef(CharSequence refBases, int index) {
    if (refBases == null || index < 0 || index >= refBases.length()) {
      return null;
    }
    char base = Character.toUpperCase(refBases.charAt(index));
    if (base == 'C') {
      char n1 = peek(refBases, index + 1);
      if (n1 == 'G') {
        return CPG;
      }
      char n2 = peek(refBases, index + 2);
      if (isH(n1) && n2 == 'G') {
        return CHG;
      }
      return CHH;
    }
    if (base == 'G') {
      char p1 = peek(refBases, index - 1);
      if (p1 == 'C') {
        return CPG;
      }
      char p2 = peek(refBases, index - 2);
      if (p2 == 'C' && isH(p1)) {
        return CHG;
      }
      return CHH;
    }
    return null;
  }

  /**
   * Bismark-style XM call letter → context ({@code z/Z}=CpG, {@code x/X}=CHG, {@code h/H}=CHH).
   */
  public static MethylSiteContext fromXmChar(char xm) {
    return switch (xm) {
      case 'z', 'Z' -> CPG;
      case 'x', 'X' -> CHG;
      case 'h', 'H' -> CHH;
      default -> null;
    };
  }

  private static char peek(CharSequence refBases, int index) {
    if (index < 0 || index >= refBases.length()) {
      return 'N';
    }
    return Character.toUpperCase(refBases.charAt(index));
  }

  private static boolean isH(char base) {
    return base == 'A' || base == 'C' || base == 'T';
  }
}
