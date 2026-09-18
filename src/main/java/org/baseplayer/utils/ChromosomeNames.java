package org.baseplayer.utils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

/**
 * Canonical chromosome naming: internally always without a {@code chr} prefix
 * ({@code "1"}, {@code "X"}, {@code "MT"}). Data sources that use {@code chr1}
 * store their own {@code prefix} ({@code ""} or {@code "chr"}) and call
 * {@link #forData(String, String)} when querying that source.
 */
public final class ChromosomeNames {

  public static final String CHR_PREFIX = "chr";
  public static final String NONE = "";

  private ChromosomeNames() {}

  /** Strip a leading {@code chr}/{@code CHR} prefix for internal use. */
  public static String strip(String chrom) {
    if (chrom == null || chrom.isEmpty()) {
      return chrom;
    }
    if (chrom.length() > 3 && chrom.regionMatches(true, 0, "chr", 0, 3)) {
      return chrom.substring(3);
    }
    return chrom;
  }

  /**
   * Map an internal (unprefixed) chromosome name to the form expected by a data source.
   * Defensively strips any incoming prefix before applying {@code prefix}.
   */
  public static String forData(String chrom, String prefix) {
    if (chrom == null) {
      return null;
    }
    String bare = strip(chrom);
    if (prefix == null || prefix.isEmpty()) {
      return bare;
    }
    return prefix + bare;
  }

  /** Case-insensitive equality after stripping {@code chr}. */
  public static boolean equals(String a, String b) {
    if (a == null || b == null) {
      return a == b;
    }
    return strip(a).equalsIgnoreCase(strip(b));
  }

  /** Lowercase stripped key for maps / de-dupe sets. */
  public static String key(String chrom) {
    String bare = strip(chrom);
    return bare == null ? "" : bare.toLowerCase(Locale.ROOT);
  }

  /**
   * Detect {@code "chr"} vs {@code ""} from contig/sequence names in a file or API.
   * Prefers {@code "chr"} when any standard contig is prefixed.
   */
  public static String detectPrefix(Iterable<String> contigNames) {
    if (contigNames == null) {
      return NONE;
    }
    boolean sawChr = false;
    for (String name : contigNames) {
      if (name == null || name.isBlank()) {
        continue;
      }
      String bare = strip(name);
      if (!isStandardBare(bare)) {
        continue;
      }
      if (name.length() > 3 && name.regionMatches(true, 0, "chr", 0, 3)) {
        sawChr = true;
        break;
      }
    }
    return sawChr ? CHR_PREFIX : NONE;
  }

  public static String detectPrefix(String[] contigNames) {
    if (contigNames == null || contigNames.length == 0) {
      return NONE;
    }
    return detectPrefix(Arrays.asList(contigNames));
  }

  public static String detectPrefix(Collection<String> contigNames) {
    return detectPrefix((Iterable<String>) contigNames);
  }

  /** Bare name looks like a standard human contig (1–22, X, Y, M/MT). */
  public static boolean isStandardBare(String bare) {
    return bare != null && bare.matches("^(\\d{1,2}|X|Y|MT?)$");
  }

  /** Display form with {@code chr} for standard contigs (UI / clipboard). */
  public static String forDisplay(String chrom) {
    String bare = strip(chrom);
    if (bare == null || bare.isEmpty()) {
      return chrom;
    }
    if (isStandardBare(bare)) {
      return CHR_PREFIX + bare;
    }
    return bare;
  }
}
