package org.baseplayer.variant;

/**
 * Soft-match helpers for Sample Comparison window size: nearby alleles / overlapping
 * SV spans contribute to the same local sample count for shared-sample min/max and groups.
 */
final class VariantComparisonClusters {

  private VariantComparisonClusters() {}

  /**
   * Bucket for sweep clustering. Small variants stay type-restricted; all SV_* share one
   * bucket so mixed breakends/dels/dups in fragile regions count together.
   */
  static String typeFamily(VcfVariantType type) {
    if (type == null) {
      return "unknown";
    }
    return switch (type) {
      case SNV, MNV -> "snv";
      case INSERTION -> "ins";
      case DELETION -> "del";
      case SV_DELETION, SV_INSERTION, SV_DUPLICATION, SV_INVERSION,
           SV_TRANSLOCATION, SV_BREAKEND -> "sv";
      case COMPLEX -> "complex";
    };
  }

  /** Inclusive end used for clustering (POS for point calls; END for spanning SVs). */
  static long clusterEnd(VariantNode node) {
    if (node == null) {
      return 0;
    }
    if (node.svEnd > node.position) {
      return node.svEnd;
    }
    return node.position;
  }

  /**
   * True when two calls should share sample counts under window {@code windowBp}.
   * Same family always; small DEL/INS may also soft-match SV spans (fragile/repeat sites).
   * Spanning calls: expand intervals by {@code windowBp} and require overlap.
   * Point calls: {@code |posA - posB| <= windowBp}.
   */
  static boolean softMatch(VariantNode a, VariantNode b, int windowBp) {
    if (a == null || b == null || windowBp < 0) {
      return false;
    }
    if (windowBp == 0) {
      return a == b;
    }
    if (!compatibleFamilies(a.type, b.type)) {
      return false;
    }
    long aStart = a.position;
    long aEnd = clusterEnd(a);
    long bStart = b.position;
    long bEnd = clusterEnd(b);
    boolean aSpan = aEnd > aStart;
    boolean bSpan = bEnd > bStart;
    if (aSpan || bSpan) {
      long a0 = aStart - windowBp;
      long a1 = aEnd + windowBp;
      long b0 = bStart - windowBp;
      long b1 = bEnd + windowBp;
      return a0 <= b1 && b0 <= a1;
    }
    return Math.abs(aStart - bStart) <= windowBp;
  }

  private static boolean compatibleFamilies(VcfVariantType a, VcfVariantType b) {
    String fa = typeFamily(a);
    String fb = typeFamily(b);
    if (fa.equals(fb)) {
      return true;
    }
    // Indel near SV in the same locus — common in fragile / repeat regions.
    boolean aIndelOrSv = "del".equals(fa) || "ins".equals(fa) || "sv".equals(fa);
    boolean bIndelOrSv = "del".equals(fb) || "ins".equals(fb) || "sv".equals(fb);
    return aIndelOrSv && bIndelOrSv;
  }
}
