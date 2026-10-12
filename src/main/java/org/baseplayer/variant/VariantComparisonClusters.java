package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Transitive gap-chained clusters for Sample Comparison window size.
 * <p>
 * When window &gt; 0, compatible alleles are merged into chains where each consecutive
 * gap (or span soft-overlap) is within the window. Singletons are not clusters.
 */
public final class VariantComparisonClusters {

  private VariantComparisonClusters() {}

  /**
   * One chained cluster: genomic first/last allele, allele count, and the union of
   * mutated sample track indices across members.
   */
  public static final class ComparisonCluster {
    public final VariantNode first;
    public final VariantNode last;
    public final int size;
    public final Set<Integer> sampleTracks;
    /** Exclusive genomic end covering every member (SNV = POS+1, SV = svEnd). */
    private final long endExclusive;

    public ComparisonCluster(VariantNode first, VariantNode last, int size, Set<Integer> sampleTracks) {
      this(first, last, size, sampleTracks, paintEndExclusive(last));
    }

    public ComparisonCluster(
        VariantNode first,
        VariantNode last,
        int size,
        Set<Integer> sampleTracks,
        long endExclusive) {
      this.first = Objects.requireNonNull(first, "first");
      this.last = Objects.requireNonNull(last, "last");
      this.size = size;
      this.sampleTracks = sampleTracks == null || sampleTracks.isEmpty()
          ? Set.of()
          : Set.copyOf(sampleTracks);
      this.endExclusive = Math.max(first.position + 1, endExclusive);
    }

    public long genomicStart() {
      return first.position;
    }

    /** Exclusive end for painting — covers the last allele, not just its start. */
    public long genomicEnd() {
      return endExclusive;
    }

    public int sampleCount() {
      return sampleTracks.size();
    }
  }

  /**
   * Inclusive index range into a sorted node list for one emitted chain (before sample union).
   */
  static final class ChainRange {
    final int fromInclusive;
    final int toExclusive;

    ChainRange(int fromInclusive, int toExclusive) {
      this.fromInclusive = fromInclusive;
      this.toExclusive = toExclusive;
    }

    int size() {
      return toExclusive - fromInclusive;
    }
  }

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
           SV_TRANSLOCATION, SV_BREAKEND,
           SV_CNV, SV_CNV_GAIN, SV_CNV_LOSS, SV_CNV_NEUTRAL -> "sv";
      case LOH_AA, LOH_BB -> "loh";
      case COMPLEX -> "complex";
    };
  }

  /** Sweep bucket: SNVs alone; indels+SVs together for fragile-site chaining. */
  static String sweepBucket(VcfVariantType type) {
    String family = typeFamily(type);
    if ("del".equals(family) || "ins".equals(family) || "sv".equals(family)) {
      return "svish";
    }
    return family;
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
   * Exclusive paint end so a highlight covers the allele (SNV/INS = 1 bp;
   * DEL/MNV/COMPLEX = REF length; spanning SV = {@code svEnd}).
   */
  static long paintEndExclusive(VariantNode node) {
    if (node == null) {
      return 1;
    }
    if (node.svEnd > node.position) {
      return node.svEnd;
    }
    if (node.type == VcfVariantType.DELETION
        || node.type == VcfVariantType.MNV
        || node.type == VcfVariantType.COMPLEX) {
      int refLen = node.ref != null ? node.ref.length() : 1;
      return node.position + Math.max(1, refLen);
    }
    return node.position + 1;
  }

  /**
   * Sort key: genomic start, then end, then identity hash for stability.
   */
  static final Comparator<VariantNode> GENOMIC_ORDER = Comparator
      .comparingLong((VariantNode n) -> n.position)
      .thenComparingLong(VariantComparisonClusters::clusterEnd)
      .thenComparingInt(System::identityHashCode);

  /**
   * Emit transitive chains within {@code sorted} (must be {@link #GENOMIC_ORDER}).
   * Consecutive nodes merge while {@link #canExtendChain} holds for the running extent.
   */
  static List<ChainRange> chainRanges(List<VariantNode> sorted, int windowBp) {
    if (sorted == null || sorted.isEmpty() || windowBp <= 0) {
      return List.of();
    }
    List<ChainRange> ranges = new ArrayList<>();
    int start = 0;
    long extentStart = sorted.get(0).position;
    long extentEnd = clusterEnd(sorted.get(0));
    for (int i = 1; i < sorted.size(); i++) {
      VariantNode next = sorted.get(i);
      if (canExtendChain(extentStart, extentEnd, next, windowBp)) {
        extentStart = Math.min(extentStart, next.position);
        extentEnd = Math.max(extentEnd, clusterEnd(next));
      } else {
        ranges.add(new ChainRange(start, i));
        start = i;
        extentStart = next.position;
        extentEnd = clusterEnd(next);
      }
    }
    ranges.add(new ChainRange(start, sorted.size()));
    return ranges;
  }

  /**
   * True when {@code next} (sorted after the current extent) continues the chain under
   * {@code windowBp}: gap from extent end to next start ≤ window, or expanded intervals overlap.
   */
  static boolean canExtendChain(long extentStart, long extentEnd, VariantNode next, int windowBp) {
    if (next == null || windowBp <= 0) {
      return false;
    }
    long nextStart = next.position;
    long nextEnd = clusterEnd(next);
    // Sorted by start → only need next vs current extent (transitive via consecutive merges).
    return nextStart <= extentEnd + (long) windowBp
        && extentStart - (long) windowBp <= nextEnd;
  }

  static boolean compatibleFamilies(VcfVariantType a, VcfVariantType b) {
    String fa = typeFamily(a);
    String fb = typeFamily(b);
    if (fa.equals(fb)) {
      return true;
    }
    boolean aIndelOrSv = "del".equals(fa) || "ins".equals(fa) || "sv".equals(fa);
    boolean bIndelOrSv = "del".equals(fb) || "ins".equals(fb) || "sv".equals(fb);
    return aIndelOrSv && bIndelOrSv;
  }

  /** Sort a mutable list in genomic order (no-op if null/empty). */
  static void sortGenomic(List<VariantNode> nodes) {
    if (nodes == null || nodes.size() < 2) {
      return;
    }
    Collections.sort(nodes, GENOMIC_ORDER);
  }
}
