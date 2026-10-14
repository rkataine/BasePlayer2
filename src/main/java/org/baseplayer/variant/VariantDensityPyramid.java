package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.samples.SampleTrack;

/**
 * Multi-resolution unique-sample density for one chromosome variant list.
 * Built once after load / filter apply; pan and zoom only slice a level.
 * Close-zoom allele bars stay live in {@code MasterTrackPainter}.
 */
public final class VariantDensityPyramid {

  /** Coarse → fine bin sizes (bp). Finest is built with BitSets; coarser filled in the same pass. */
  public static final long[] BIN_SIZES_BP = { 1_000_000L, 100_000L, 10_000L, 1_000L };

  public enum Channel {
    SNV, INDEL, DEL, CNV_LOSS, INV, DUP, CNV_GAIN, INS, TRA, BND, LOH_AA, LOH_BB
  }

  public record SvSpan(long start, long end, VcfVariantType type, int sampleCount) {}

  public record Level(long binSizeBp, int binCount, int[][] countsByChannel, int maxCount) {
    public int[] channel(Channel ch) {
      return countsByChannel[ch.ordinal()];
    }
  }

  private final VariantList sourceList;
  private final int filterGeneration;
  private final int chainGeneration;
  private final int displayedFingerprint;
  private final long chromStartBp;
  private final long chromEndBp;
  private final Level[] levels;
  private final List<SvSpan> svSpans;
  private final int globalMax;

  private VariantDensityPyramid(
      VariantList sourceList,
      int filterGeneration,
      int chainGeneration,
      int displayedFingerprint,
      long chromStartBp,
      long chromEndBp,
      Level[] levels,
      List<SvSpan> svSpans,
      int globalMax) {
    this.sourceList = sourceList;
    this.filterGeneration = filterGeneration;
    this.chainGeneration = chainGeneration;
    this.displayedFingerprint = displayedFingerprint;
    this.chromStartBp = chromStartBp;
    this.chromEndBp = chromEndBp;
    this.levels = levels;
    this.svSpans = svSpans;
    this.globalMax = Math.max(1, globalMax);
  }

  public boolean matches(
      VariantList list,
      int filterGeneration,
      int chainGeneration,
      int displayedFingerprint) {
    return sourceList == list
        && this.filterGeneration == filterGeneration
        && this.chainGeneration == chainGeneration
        && this.displayedFingerprint == displayedFingerprint;
  }

  public int globalMax() {
    return globalMax;
  }

  public List<SvSpan> svSpans() {
    return svSpans;
  }

  public long chromStartBp() {
    return chromStartBp;
  }

  public long chromEndBp() {
    return chromEndBp;
  }

  /**
   * Pick the level whose bin size is closest to ~2 screen pixels of genome
   * (Bitwig-style: coarser when zoomed out, finer when zoomed in).
   */
  public Level pickLevel(double viewLengthBp, double canvasWidthPx) {
    if (levels == null || levels.length == 0) {
      return null;
    }
    double bpPerPixel = viewLengthBp / Math.max(1.0, canvasWidthPx);
    double targetBin = Math.max(BIN_SIZES_BP[BIN_SIZES_BP.length - 1], bpPerPixel * 2.0);
    Level best = levels[0];
    double bestDist = Double.POSITIVE_INFINITY;
    for (Level level : levels) {
      double dist = Math.abs(Math.log(level.binSizeBp() / targetBin));
      if (dist < bestDist) {
        bestDist = dist;
        best = level;
      }
    }
    return best;
  }

  /** Max unique-sample count in {@code [viewStart, viewEnd]} for visible channels. */
  public int maxInView(Level level, double viewStart, double viewEnd, boolean[] channelVisible) {
    if (level == null) {
      return 1;
    }
    int b0 = genomicToBin(viewStart, level);
    int b1 = genomicToBin(viewEnd, level);
    b0 = Math.max(0, Math.min(level.binCount() - 1, b0));
    b1 = Math.max(0, Math.min(level.binCount() - 1, b1));
    if (b1 < b0) {
      return 1;
    }
    int max = 1;
    for (Channel ch : Channel.values()) {
      if (channelVisible != null && !channelVisible[ch.ordinal()]) {
        continue;
      }
      int[] arr = level.channel(ch);
      for (int b = b0; b <= b1; b++) {
        max = Math.max(max, arr[b]);
      }
    }
    return max;
  }

  public int genomicToBin(double genomicPos, Level level) {
    long rel = (long) genomicPos - chromStartBp;
    if (rel < 0) {
      return 0;
    }
    long bin = rel / level.binSizeBp();
    if (bin >= level.binCount()) {
      return level.binCount() - 1;
    }
    return (int) bin;
  }

  /**
   * Order-independent fingerprint of displayed track indices. Must not hash
   * {@code IdentityHashMap#values()} iteration order — that differs from the
   * registry list and used to make {@link #matches} fail every frame (rebuild loop).
   */
  public static int fingerprintDisplayedTracks(List<Integer> displayedIndices) {
    if (displayedIndices == null || displayedIndices.isEmpty()) {
      return 0;
    }
    int[] sorted = new int[displayedIndices.size()];
    for (int i = 0; i < displayedIndices.size(); i++) {
      Integer v = displayedIndices.get(i);
      sorted[i] = v != null ? v : -1;
    }
    java.util.Arrays.sort(sorted);
    int h = 1;
    for (int idx : sorted) {
      h = 31 * h + idx;
    }
    return 31 * h + sorted.length;
  }

  /**
   * One pass over the visible chain (+ LOH / upstream SV spans), filling every level.
   * Call off the FX thread. {@code chainGeneration} / {@code displayedFingerprint}
   * must be the same values the FX thread will use in {@link #matches}.
   */
  public static VariantDensityPyramid build(
      VariantList variants,
      VariantFilter filter,
      Map<SampleTrack, Integer> displayedTrackToIndex,
      long chromLengthBp,
      int filterGeneration,
      int chainGeneration,
      int displayedFingerprint) {
    if (variants == null || variants.isEmpty() || chromLengthBp < 2) {
      return empty(
          variants, filterGeneration, chainGeneration, displayedFingerprint,
          1L, Math.max(2L, chromLengthBp));
    }

    variants.ensureVisibleChain(filter);

    long chromStart = 1;
    long chromEnd = Math.max(chromStart + 1, chromLengthBp);

    int nLevels = BIN_SIZES_BP.length;
    int trackCapacity = 64;
    if (displayedTrackToIndex != null) {
      for (Integer idx : displayedTrackToIndex.values()) {
        if (idx != null) {
          trackCapacity = Math.max(trackCapacity, idx + 1);
        }
      }
    }

    BitSet[][][] bits = new BitSet[nLevels][Channel.values().length][];
    int[] binCounts = new int[nLevels];
    for (int li = 0; li < nLevels; li++) {
      long binSize = BIN_SIZES_BP[li];
      int nBins = (int) Math.max(1, ((chromEnd - chromStart) + binSize - 1) / binSize);
      binCounts[li] = nBins;
      for (int c = 0; c < Channel.values().length; c++) {
        bits[li][c] = new BitSet[nBins];
      }
    }

    BitSet passing = new BitSet(trackCapacity);
    List<SvSpan> spans = new ArrayList<>();
    Map<SampleTrack, Integer> displayed =
        displayedTrackToIndex != null ? displayedTrackToIndex : Map.of();

    // Full visible chain once — SV spans paint across bins via node.svEnd.
    VariantNode walk = variants.getVisibleHead();
    while (walk != null) {
      accumulate(variants, walk, filter, displayed, passing, trackCapacity,
          chromStart, chromEnd, bits, binCounts, spans, false);
      walk = walk.nextVisible;
    }

    for (VariantNode node : variants.getLohRegions()) {
      if (node == null || !VariantTypeVisuals.isLohRegion(node.type)) {
        continue;
      }
      accumulate(variants, node, filter, displayed, passing, trackCapacity,
          chromStart, chromEnd, bits, binCounts, spans, true);
    }

    Level[] levels = new Level[nLevels];
    int globalMax = 1;
    for (int li = 0; li < nLevels; li++) {
      int nBins = binCounts[li];
      int[][] counts = new int[Channel.values().length][nBins];
      int levelMax = 1;
      for (int c = 0; c < Channel.values().length; c++) {
        BitSet[] channel = bits[li][c];
        for (int b = 0; b < nBins; b++) {
          BitSet set = channel[b];
          if (set != null) {
            int card = set.cardinality();
            counts[c][b] = card;
            levelMax = Math.max(levelMax, card);
          }
        }
      }
      levels[li] = new Level(BIN_SIZES_BP[li], nBins, counts, levelMax);
      globalMax = Math.max(globalMax, levelMax);
    }

    return new VariantDensityPyramid(
        variants,
        filterGeneration,
        chainGeneration,
        displayedFingerprint,
        chromStart,
        chromEnd,
        levels,
        List.copyOf(spans),
        globalMax);
  }

  private static VariantDensityPyramid empty(
      VariantList list,
      int filterGeneration,
      int chainGeneration,
      int displayedFp,
      long chromStart,
      long chromEnd) {
    Level[] levels = new Level[BIN_SIZES_BP.length];
    for (int i = 0; i < levels.length; i++) {
      levels[i] = new Level(BIN_SIZES_BP[i], 1, new int[Channel.values().length][1], 1);
    }
    return new VariantDensityPyramid(
        list, filterGeneration, chainGeneration, displayedFp,
        chromStart, Math.max(chromStart + 1, chromEnd), levels, List.of(), 1);
  }

  private static void accumulate(
      VariantList variants,
      VariantNode node,
      VariantFilter filter,
      Map<SampleTrack, Integer> displayed,
      BitSet passing,
      int trackCapacity,
      long chromStart,
      long chromEnd,
      BitSet[][][] bits,
      int[] binCounts,
      List<SvSpan> spans,
      boolean treatAsSvSpan) {
    if (node == null) {
      return;
    }
    passing.clear();
    int passCount = variants.collectDisplayTrackIndices(node, filter, displayed, passing);
    if (passCount <= 0) {
      return;
    }

    boolean isSvWithSpan = treatAsSvSpan
        || (node.svEnd > node.position
            && (node.type == VcfVariantType.SV_DELETION
                || node.type == VcfVariantType.SV_INSERTION
                || node.type == VcfVariantType.SV_DUPLICATION
                || node.type == VcfVariantType.SV_INVERSION
                || VariantTypeVisuals.isCnv(node.type)
                || VariantTypeVisuals.isLohRegion(node.type)));

    Channel channel = channelFor(node.type, isSvWithSpan);
    if (channel == null) {
      return;
    }
    int ci = channel.ordinal();

    if (isSvWithSpan && node.svEnd >= chromStart && node.position <= chromEnd) {
      long s = Math.max(chromStart, node.position);
      long e = Math.min(chromEnd, node.svEnd);
      for (int li = 0; li < BIN_SIZES_BP.length; li++) {
        long binSize = BIN_SIZES_BP[li];
        int b0 = (int) Math.max(0, Math.min(binCounts[li] - 1, (s - chromStart) / binSize));
        int b1 = (int) Math.max(0, Math.min(binCounts[li] - 1, (e - chromStart) / binSize));
        BitSet[] arr = bits[li][ci];
        for (int b = b0; b <= b1; b++) {
          orInto(arr, b, passing, trackCapacity);
        }
      }
      spans.add(new SvSpan(node.position, node.svEnd, node.type, passCount));
    } else if (node.position >= chromStart && node.position <= chromEnd) {
      for (int li = 0; li < BIN_SIZES_BP.length; li++) {
        long binSize = BIN_SIZES_BP[li];
        int bin = (int) Math.max(0, Math.min(binCounts[li] - 1, (node.position - chromStart) / binSize));
        orInto(bits[li][ci], bin, passing, trackCapacity);
      }
    }
  }

  private static Channel channelFor(VcfVariantType type, boolean svSpan) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case SNV -> Channel.SNV;
      case INSERTION, DELETION, MNV, COMPLEX -> Channel.INDEL;
      case SV_DELETION -> Channel.DEL;
      case SV_CNV_LOSS -> Channel.CNV_LOSS;
      case SV_INVERSION -> Channel.INV;
      case SV_DUPLICATION -> Channel.DUP;
      case SV_CNV_GAIN -> Channel.CNV_GAIN;
      case SV_INSERTION -> Channel.INS;
      case SV_TRANSLOCATION -> Channel.TRA;
      case SV_BREAKEND -> Channel.BND;
      case LOH_AA -> Channel.LOH_AA;
      case LOH_BB -> Channel.LOH_BB;
      // Untyped / copy-neutral CNV: no dedicated density channel (keep out of DEL/DUP).
      case SV_CNV, SV_CNV_NEUTRAL -> null;
      default -> svSpan ? Channel.DEL : null;
    };
  }

  private static void orInto(BitSet[] bins, int bin, BitSet passing, int trackCapacity) {
    if (bins[bin] == null) {
      bins[bin] = new BitSet(trackCapacity);
    }
    bins[bin].or(passing);
  }

  /** Snapshot displayed track → index map for builders. */
  public static Map<SampleTrack, Integer> displayedTrackMap(
      List<Integer> displayedIndices,
      List<SampleTrack> allTracks) {
    Map<SampleTrack, Integer> map = new IdentityHashMap<>(
        Math.max(16, displayedIndices.size() * 2));
    if (displayedIndices == null || allTracks == null) {
      return map;
    }
    for (int idx : displayedIndices) {
      if (idx < 0 || idx >= allTracks.size()) {
        continue;
      }
      SampleTrack track = allTracks.get(idx);
      if (track != null) {
        map.put(track, idx);
      }
    }
    return map;
  }
}
