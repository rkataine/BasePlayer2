package org.baseplayer.variant;

/**
 * Converts left-aligned VCF alleles (with shared padding base) to a minimal
 * display form, e.g. {@code POS=10 REF=CT ALT=C} → {@code POS=11 REF=T ALT=-}.
 */
public final class VcfAlleleNormalizer {

  public record Alleles(long position, String ref, String alt) {}

  private VcfAlleleNormalizer() {}

  /**
   * Strip common prefix/suffix shared by REF/ALT. Empty allele after trim becomes {@code "-"}.
   * Symbolic / breakend ALTs are returned unchanged.
   */
  public static Alleles normalize(long position, String ref, String alt) {
    if (ref == null) {
      ref = "";
    }
    if (alt == null || alt.isEmpty()) {
      alt = ".";
    }
    if (isSymbolicOrBreakend(alt) || isSymbolicOrBreakend(ref)) {
      return new Alleles(position, ref, alt);
    }

    String r = ref.toUpperCase();
    String a = alt.toUpperCase();
    if (".".equals(a)) {
      return new Alleles(position, r.isEmpty() ? "-" : r, "-");
    }

    int prefix = 0;
    int maxPrefix = Math.min(r.length(), a.length());
    while (prefix < maxPrefix && r.charAt(prefix) == a.charAt(prefix)) {
      prefix++;
    }

    int suffix = 0;
    int maxSuffix = Math.min(r.length() - prefix, a.length() - prefix);
    while (suffix < maxSuffix
        && r.charAt(r.length() - 1 - suffix) == a.charAt(a.length() - 1 - suffix)) {
      suffix++;
    }

    long newPos = position + prefix;
    String newRef = r.substring(prefix, r.length() - suffix);
    String newAlt = a.substring(prefix, a.length() - suffix);
    if (newRef.isEmpty()) {
      newRef = "-";
      // Pure insertion: keep the last matched anchor base (VCF POS), not POS+1.
      // Otherwise Position is one base downstream of the codon/HGVS anchor.
      if (prefix > 0) {
        newPos = position + prefix - 1;
      }
    }
    if (newAlt.isEmpty()) {
      newAlt = "-";
      // Pure deletion: newPos is already the first deleted base (POS + prefix).
    }
    return new Alleles(newPos, newRef, newAlt);
  }

  public static Alleles normalize(VariantNode node) {
    if (node == null) {
      return new Alleles(0, "", ".");
    }
    return normalize(node.position, node.ref, node.alt);
  }

  /** True when REF or ALT is empty / dash (pure insertion or deletion after normalize). */
  public static boolean isGap(String allele) {
    return allele == null || allele.isEmpty() || "-".equals(allele) || ".".equals(allele);
  }

  private static boolean isSymbolicOrBreakend(String allele) {
    if (allele == null || allele.isEmpty()) {
      return false;
    }
    return allele.indexOf('<') >= 0
        || allele.indexOf('>') >= 0
        || allele.indexOf('[') >= 0
        || allele.indexOf(']') >= 0
        || allele.indexOf('.') == 0 && allele.length() > 1;
  }
}
