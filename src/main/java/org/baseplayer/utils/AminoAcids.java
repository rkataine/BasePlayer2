package org.baseplayer.utils;

import java.util.Map;

import javafx.scene.paint.Color;

/**
 * Central reference data for amino acids.
 *
 * <p>Contains full names, chemical properties, synonymous codons, single-letter
 * colors (Taylor scheme), and three-letter codes — everything needed to render
 * amino acid information across the application.
 */
public final class AminoAcids {

  private AminoAcids() {} // utility class

  // ── Full names ─────────────────────────────────────────────────────────────

  public static final Map<Character, String> NAMES = Map.ofEntries(
      Map.entry('A', "Alanine"),
      Map.entry('C', "Cysteine"),
      Map.entry('D', "Aspartic Acid"),
      Map.entry('E', "Glutamic Acid"),
      Map.entry('F', "Phenylalanine"),
      Map.entry('G', "Glycine"),
      Map.entry('H', "Histidine"),
      Map.entry('I', "Isoleucine"),
      Map.entry('K', "Lysine"),
      Map.entry('L', "Leucine"),
      Map.entry('M', "Methionine"),
      Map.entry('N', "Asparagine"),
      Map.entry('P', "Proline"),
      Map.entry('Q', "Glutamine"),
      Map.entry('R', "Arginine"),
      Map.entry('S', "Serine"),
      Map.entry('T', "Threonine"),
      Map.entry('V', "Valine"),
      Map.entry('W', "Tryptophan"),
      Map.entry('Y', "Tyrosine"),
      Map.entry('*', "Stop Codon")
  );

  // ── Chemical properties ────────────────────────────────────────────────────

  public static final Map<Character, String> PROPERTIES = Map.ofEntries(
      Map.entry('A', "Hydrophobic, small"),
      Map.entry('C', "Polar, sulfur-containing"),
      Map.entry('D', "Charged negative (acidic)"),
      Map.entry('E', "Charged negative (acidic)"),
      Map.entry('F', "Hydrophobic, aromatic"),
      Map.entry('G', "Hydrophobic, smallest"),
      Map.entry('H', "Charged positive (basic), aromatic"),
      Map.entry('I', "Hydrophobic, branched-chain"),
      Map.entry('K', "Charged positive (basic)"),
      Map.entry('L', "Hydrophobic, branched-chain"),
      Map.entry('M', "Hydrophobic, sulfur-containing, start codon"),
      Map.entry('N', "Polar, amide"),
      Map.entry('P', "Hydrophobic, rigid (imino acid)"),
      Map.entry('Q', "Polar, amide"),
      Map.entry('R', "Charged positive (basic)"),
      Map.entry('S', "Polar, hydroxyl"),
      Map.entry('T', "Polar, hydroxyl"),
      Map.entry('V', "Hydrophobic, branched-chain"),
      Map.entry('W', "Hydrophobic, aromatic, largest"),
      Map.entry('Y', "Polar, aromatic"),
      Map.entry('*', "Translation termination")
  );

  // ── Synonymous codons ──────────────────────────────────────────────────────

  public static final Map<Character, String[]> SYNONYMOUS_CODONS = Map.ofEntries(
      Map.entry('A', new String[]{"GCT", "GCC", "GCA", "GCG"}),
      Map.entry('C', new String[]{"TGT", "TGC"}),
      Map.entry('D', new String[]{"GAT", "GAC"}),
      Map.entry('E', new String[]{"GAA", "GAG"}),
      Map.entry('F', new String[]{"TTT", "TTC"}),
      Map.entry('G', new String[]{"GGT", "GGC", "GGA", "GGG"}),
      Map.entry('H', new String[]{"CAT", "CAC"}),
      Map.entry('I', new String[]{"ATT", "ATC", "ATA"}),
      Map.entry('K', new String[]{"AAA", "AAG"}),
      Map.entry('L', new String[]{"TTA", "TTG", "CTT", "CTC", "CTA", "CTG"}),
      Map.entry('M', new String[]{"ATG"}),
      Map.entry('N', new String[]{"AAT", "AAC"}),
      Map.entry('P', new String[]{"CCT", "CCC", "CCA", "CCG"}),
      Map.entry('Q', new String[]{"CAA", "CAG"}),
      Map.entry('R', new String[]{"CGT", "CGC", "CGA", "CGG", "AGA", "AGG"}),
      Map.entry('S', new String[]{"TCT", "TCC", "TCA", "TCG", "AGT", "AGC"}),
      Map.entry('T', new String[]{"ACT", "ACC", "ACA", "ACG"}),
      Map.entry('V', new String[]{"GTT", "GTC", "GTA", "GTG"}),
      Map.entry('W', new String[]{"TGG"}),
      Map.entry('Y', new String[]{"TAT", "TAC"}),
      Map.entry('*', new String[]{"TAA", "TAG", "TGA"})
  );

  // ── Colors (Taylor scheme) ─────────────────────────────────────────────────

  /** Amino acid colors based on chemical properties (Taylor scheme inspired). */
  public static final Map<Character, Color> COLORS = Map.ofEntries(
      // Hydrophobic (yellows/greens)
      Map.entry('A', Color.web("#ccff00")),  // Alanine
      Map.entry('V', Color.web("#99ff00")),  // Valine
      Map.entry('I', Color.web("#66cc00")),  // Isoleucine
      Map.entry('L', Color.web("#33cc00")),  // Leucine
      Map.entry('M', Color.web("#00ff00")),  // Methionine (start)
      Map.entry('F', Color.web("#00ff66")),  // Phenylalanine
      Map.entry('W', Color.web("#00ccff")),  // Tryptophan
      // Polar (oranges/purples/pinks)
      Map.entry('S', Color.web("#ff9900")),  // Serine
      Map.entry('T', Color.web("#ff9933")),  // Threonine
      Map.entry('N', Color.web("#cc66ff")),  // Asparagine
      Map.entry('Q', Color.web("#ff66cc")),  // Glutamine
      // Charged positive (blues)
      Map.entry('K', Color.web("#6666ff")),  // Lysine
      Map.entry('R', Color.web("#0000ff")),  // Arginine
      Map.entry('H', Color.web("#8282d2")),  // Histidine
      // Charged negative (reds)
      Map.entry('D', Color.web("#ff0000")),  // Aspartic acid
      Map.entry('E', Color.web("#cc0000")),  // Glutamic acid
      // Special
      Map.entry('C', Color.web("#ffff00")),  // Cysteine (sulfur)
      Map.entry('G', Color.web("#ff8800")),  // Glycine (small)
      Map.entry('P', Color.web("#ffcc00")),  // Proline (rigid)
      Map.entry('Y', Color.web("#00ffcc")),  // Tyrosine
      // Stop codon
      Map.entry('*', Color.web("#ff0000"))
  );

  // ── Three-letter codes ─────────────────────────────────────────────────────

  public static final Map<Character, String> THREE_LETTER = Map.ofEntries(
      Map.entry('A', "Ala"), Map.entry('C', "Cys"), Map.entry('D', "Asp"),
      Map.entry('E', "Glu"), Map.entry('F', "Phe"), Map.entry('G', "Gly"),
      Map.entry('H', "His"), Map.entry('I', "Ile"), Map.entry('K', "Lys"),
      Map.entry('L', "Leu"), Map.entry('M', "Met"), Map.entry('N', "Asn"),
      Map.entry('P', "Pro"), Map.entry('Q', "Gln"), Map.entry('R', "Arg"),
      Map.entry('S', "Ser"), Map.entry('T', "Thr"), Map.entry('V', "Val"),
      Map.entry('W', "Trp"), Map.entry('Y', "Tyr"), Map.entry('*', "Stp")
  );

  // ── Property groups (for domain-level visualization) ───────────────────────

  /**
   * Broad chemical property groups. Used for canvas coloring so that
   * hydrophobic stretches, charged patches, and polar regions stand out
   * visually when zoomed into the amino-acid level.
   */
  public enum PropertyGroup {
    /** Nonpolar / hydrophobic — A V I L M F W P G */
    HYDROPHOBIC("Hydrophobic",      Color.web("#e69520")),
    /** Polar, uncharged — S T N Q Y C */
    POLAR(      "Polar",             Color.web("#28b8b8")),
    /** Positively charged (basic) — K R H */
    POSITIVE(   "Positive charge",   Color.web("#4d80ff")),
    /** Negatively charged (acidic) — D E */
    NEGATIVE(   "Negative charge",   Color.web("#e03030")),
    /** Stop codon */
    STOP(       "Stop",              Color.web("#303030"));

    public final String label;
    public final Color  color;

    PropertyGroup(String label, Color color) {
      this.label = label;
      this.color = color;
    }
  }

  private static final Map<Character, PropertyGroup> PROPERTY_GROUP_MAP = Map.ofEntries(
      // Hydrophobic / nonpolar
      Map.entry('A', PropertyGroup.HYDROPHOBIC),
      Map.entry('V', PropertyGroup.HYDROPHOBIC),
      Map.entry('I', PropertyGroup.HYDROPHOBIC),
      Map.entry('L', PropertyGroup.HYDROPHOBIC),
      Map.entry('M', PropertyGroup.HYDROPHOBIC),
      Map.entry('F', PropertyGroup.HYDROPHOBIC),
      Map.entry('W', PropertyGroup.HYDROPHOBIC),
      Map.entry('P', PropertyGroup.HYDROPHOBIC),
      Map.entry('G', PropertyGroup.HYDROPHOBIC),
      // Polar / uncharged
      Map.entry('S', PropertyGroup.POLAR),
      Map.entry('T', PropertyGroup.POLAR),
      Map.entry('N', PropertyGroup.POLAR),
      Map.entry('Q', PropertyGroup.POLAR),
      Map.entry('Y', PropertyGroup.POLAR),
      Map.entry('C', PropertyGroup.POLAR),
      // Positively charged
      Map.entry('K', PropertyGroup.POSITIVE),
      Map.entry('R', PropertyGroup.POSITIVE),
      Map.entry('H', PropertyGroup.POSITIVE),
      // Negatively charged
      Map.entry('D', PropertyGroup.NEGATIVE),
      Map.entry('E', PropertyGroup.NEGATIVE),
      // Stop
      Map.entry('*', PropertyGroup.STOP)
  );

  // ── Convenience accessors ──────────────────────────────────────────────────

  /** Full name, e.g. {@code 'A'} → {@code "Alanine"}. */
  public static String getName(char aa) {
    return NAMES.getOrDefault(Character.toUpperCase(aa), "Unknown");
  }

  /** Chemical property description. */
  public static String getProperties(char aa) {
    return PROPERTIES.getOrDefault(Character.toUpperCase(aa), "Unknown");
  }

  /** Three-letter code, e.g. {@code 'A'} → {@code "Ala"}. */
  public static String getThreeLetter(char aa) {
    return THREE_LETTER.getOrDefault(Character.toUpperCase(aa), "???");
  }

  /** Taylor-scheme color (per individual amino acid identity). */
  public static Color getColor(char aa) {
    return COLORS.getOrDefault(Character.toUpperCase(aa), Color.GRAY);
  }

  /** Property group (hydrophobic / polar / positive / negative / stop). */
  public static PropertyGroup getPropertyGroup(char aa) {
    return PROPERTY_GROUP_MAP.getOrDefault(Character.toUpperCase(aa), PropertyGroup.POLAR);
  }

  /**
   * Color based on broad chemical property group — intended for canvas rendering
   * so that secondary-structure domains become visible at amino-acid zoom level.
   */
  public static Color getPropertyColor(char aa) {
    return getPropertyGroup(aa).color;
  }

  /** All synonymous codons for this amino acid, or an empty array if unknown. */
  public static String[] getSynonymousCodons(char aa) {
    return SYNONYMOUS_CODONS.getOrDefault(Character.toUpperCase(aa), new String[0]);
  }

  /**
   * Translates a 3-base DNA codon (case-insensitive) to a single-letter amino-acid code.
   * Returns {@code '*'} for stop codons, {@code '?'} for null/short input, and {@code 'X'} for unknown codons.
   */
  public static char translateCodon(String codon) {
    if (codon == null || codon.length() != 3) return '?';
    codon = codon.toUpperCase();
    return switch (codon) {
      case "TTT", "TTC"                                     -> 'F';
      case "TTA", "TTG", "CTT", "CTC", "CTA", "CTG"        -> 'L';
      case "ATT", "ATC", "ATA"                              -> 'I';
      case "ATG"                                            -> 'M';
      case "GTT", "GTC", "GTA", "GTG"                      -> 'V';
      case "TCT", "TCC", "TCA", "TCG", "AGT", "AGC"        -> 'S';
      case "CCT", "CCC", "CCA", "CCG"                      -> 'P';
      case "ACT", "ACC", "ACA", "ACG"                      -> 'T';
      case "GCT", "GCC", "GCA", "GCG"                      -> 'A';
      case "TAT", "TAC"                                     -> 'Y';
      case "TAA", "TAG", "TGA"                              -> '*';
      case "CAT", "CAC"                                     -> 'H';
      case "CAA", "CAG"                                     -> 'Q';
      case "AAT", "AAC"                                     -> 'N';
      case "AAA", "AAG"                                     -> 'K';
      case "GAT", "GAC"                                     -> 'D';
      case "GAA", "GAG"                                     -> 'E';
      case "TGT", "TGC"                                     -> 'C';
      case "TGG"                                            -> 'W';
      case "CGT", "CGC", "CGA", "CGG", "AGA", "AGG"        -> 'R';
      case "GGT", "GGC", "GGA", "GGG"                      -> 'G';
      default                                               -> 'X';
    };
  }
}
