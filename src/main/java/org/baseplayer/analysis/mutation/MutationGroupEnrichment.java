package org.baseplayer.analysis.mutation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.variant.CnvMagnitude;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantOddsRatio;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.ThresholdFilter;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.annotation.VariantEffectVisuals;

/**
 * Gene-level group comparison for filter-passing variants.
 * <p>
 * Scores every gene that has ≥1 overlapping call. Samples without a call contribute
 * the neutral value {@code 0} (sparse storage — not densified). Display filtering is
 * left to {@link ThresholdFilter}.
 * <p>
 * Point / classic SV calls use binary carrier enrichment (0/1). CNV calls use a
 * <strong>signed event burden</strong> per gene×sample ({@code gains − losses}), so
 * scores stay stable when more chromosomes are annotated (each overlapping segment
 * contributes ±1 once, not a diluted mean of logR). Multi-group Diff requires
 * within-group directional coherence on the extreme groups.
 */
public final class MutationGroupEnrichment {

  /** Min fraction of non-zero samples in a group that must share the dominant sign. */
  static final double GROUP_SIGN_COHERENCE = 0.70;

  /** Result plus matrix so the session can recompute tests without re-scanning. */
  public record Outcome(GroupStatResult result, FeatureValueMatrix matrix) {}

  private MutationGroupEnrichment() {}

  /**
   * Accumulates gene×sample carrier / CNV burden across chromosomes for streaming
   * genomewide runs and cache-based analyze.
   */
  public static final class Accumulator {
    private final IdentityHashMap<SampleTrack, Boolean> seen = new IdentityHashMap<>();
    private final List<SampleTrack> tracks = new ArrayList<>();
    private final Map<String, Map<SampleTrack, Integer>> carriers = new HashMap<>();
    private final Map<String, Map<SampleTrack, CnvBurden>> cnvBurden = new HashMap<>();
    private final Map<String, Map<SampleTrack, VariantEffect>> effects = new HashMap<>();
    private final Map<String, FeatureValueMatrix.Locus> loci = new HashMap<>();
    private boolean sawCnvMagnitude;

    public Accumulator(List<SampleTrack> tracks) {
      if (tracks == null) {
        return;
      }
      for (SampleTrack track : tracks) {
        if (track != null && seen.put(track, Boolean.TRUE) == null) {
          this.tracks.add(track);
        }
      }
    }

    public List<SampleTrack> tracks() {
      return List.copyOf(tracks);
    }

    public boolean sawCnvMagnitude() {
      return sawCnvMagnitude;
    }

    /** Fold visible nodes from {@code list} (caller rebuilds visible chain with filter). */
    public void fold(AnalysisMode mode, VariantList list) {
      if (list == null || seen.isEmpty()) {
        return;
      }
      String chrom = list.getChromosome();
      VariantNode node = list.getVisibleHead();
      while (node != null) {
        if (Thread.currentThread().isInterrupted()) {
          return;
        }
        if (!acceptsMode(mode, node.type)) {
          node = node.nextVisible;
          continue;
        }
        List<String> genes = genesOf(node);
        if (!genes.isEmpty()) {
          boolean cnv = VariantTypeVisuals.isCnv(node.type);
          double magnitude = cnv ? CnvMagnitude.signed(node) : 0;
          VariantEffect nodeEffect = nodeEffect(node);
          List<VariantNode.SampleCall> calls = node.getSamples();
          for (String gene : genes) {
            if (cnv) {
              Map<SampleTrack, CnvBurden> row =
                  cnvBurden.computeIfAbsent(gene, key -> new IdentityHashMap<>());
              if (calls != null) {
                for (VariantNode.SampleCall call : calls) {
                  if (call == null) {
                    continue;
                  }
                  SampleTrack track = call.getTrack();
                  if (track == null || !seen.containsKey(track)) {
                    continue;
                  }
                  row.computeIfAbsent(track, key -> new CnvBurden()).addEvent(magnitude);
                  rememberEffect(gene, track, nodeEffect);
                  sawCnvMagnitude = true;
                }
              }
            } else {
              Map<SampleTrack, Integer> row =
                  carriers.computeIfAbsent(gene, key -> new IdentityHashMap<>());
              if (calls != null) {
                for (VariantNode.SampleCall call : calls) {
                  if (call == null || !node.isAltCarrier(call)) {
                    continue;
                  }
                  SampleTrack track = call.getTrack();
                  if (track == null || !seen.containsKey(track)) {
                    continue;
                  }
                  row.put(track, 1);
                  rememberEffect(gene, track, nodeEffect);
                }
              }
            }
            rememberLocus(loci, gene, chrom, node);
          }
        }
        node = node.nextVisible;
      }
    }

    private void rememberEffect(String gene, SampleTrack track, VariantEffect effect) {
      if (gene == null || track == null || effect == null) {
        return;
      }
      Map<SampleTrack, VariantEffect> row =
          effects.computeIfAbsent(gene, key -> new IdentityHashMap<>());
      row.put(track, VariantEffectVisuals.preferSevere(row.get(track), effect));
    }

    public FeatureValueMatrix toMatrix() {
      FeatureValueMatrix matrix = new FeatureValueMatrix();
      matrix.setMissingDefault(0.0);
      for (Map.Entry<String, Map<SampleTrack, CnvBurden>> geneEntry : cnvBurden.entrySet()) {
        String gene = geneEntry.getKey();
        matrix.touch(gene);
        for (Map.Entry<SampleTrack, CnvBurden> e : geneEntry.getValue().entrySet()) {
          CnvBurden burden = e.getValue();
          if (burden != null && burden.hasEvents()) {
            matrix.put(gene, e.getKey(), burden.signedEventScore());
          }
        }
        putResolvedLocus(matrix, gene, loci.get(gene));
      }
      for (Map.Entry<String, Map<SampleTrack, Integer>> geneEntry : carriers.entrySet()) {
        String gene = geneEntry.getKey();
        if (matrix.unitIds().contains(gene)) {
          continue;
        }
        matrix.touch(gene);
        for (Map.Entry<SampleTrack, Integer> e : geneEntry.getValue().entrySet()) {
          if (e.getValue() != null && e.getValue() > 0) {
            matrix.put(gene, e.getKey(), e.getValue());
          }
        }
        putResolvedLocus(matrix, gene, loci.get(gene));
      }
      // Always merge effect layer (CNV-winning genes may still have SNV effects).
      for (String gene : effects.keySet()) {
        putEffects(matrix, gene);
      }
      return matrix;
    }

    private void putEffects(FeatureValueMatrix matrix, String gene) {
      Map<SampleTrack, VariantEffect> row = effects.get(gene);
      if (row == null || matrix == null) {
        return;
      }
      for (Map.Entry<SampleTrack, VariantEffect> e : row.entrySet()) {
        matrix.putEffect(gene, e.getKey(), e.getValue());
      }
    }
  }

  private static VariantEffect nodeEffect(VariantNode node) {
    if (node == null || node.annotation == null) {
      return null;
    }
    return node.annotation.effect();
  }

  /** Point vs SV class gate for enrichment / patterns. */
  public static boolean acceptsMode(AnalysisMode mode, org.baseplayer.variant.VcfVariantType type) {
    if (type == null) {
      return false;
    }
    if (mode == AnalysisMode.MUTATIONS_POINT) {
      return VariantTypeVisuals.isPoint(type);
    }
    if (mode == AnalysisMode.MUTATIONS_SV) {
      return VariantTypeVisuals.isStructural(type);
    }
    return true;
  }

  public static Accumulator accumulatorForGroups(
      SampleRegistry registry, List<Integer> groupIds) {
    List<SampleTrack> allTracks = new ArrayList<>();
    if (registry == null || groupIds == null) {
      return new Accumulator(allTracks);
    }
    IdentityHashMap<SampleTrack, Boolean> seen = new IdentityHashMap<>();
    for (int groupId : groupIds) {
      for (SampleTrack track : ComparisonContext.tracksForGroup(registry, groupId)) {
        if (seen.put(track, Boolean.TRUE) == null) {
          allTracks.add(track);
        }
      }
    }
    return new Accumulator(allTracks);
  }

  public static Outcome analyze(
      AnalysisMode mode,
      List<VariantList> chromosomeLists,
      VariantFilter filter,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId) {
    if (chromosomeLists == null || registry == null || groupIds == null || groupIds.size() < 2) {
      return new Outcome(
          new GroupStatResult(mode, groupIds, baselineGroupId, List.of(), "Need variants and ≥2 groups"),
          null);
    }

    Accumulator acc = accumulatorForGroups(registry, groupIds);
    if (acc.tracks().size() < 2) {
      return new Outcome(
          new GroupStatResult(mode, groupIds, baselineGroupId, List.of(), "Need samples in selected groups"),
          null);
    }

    for (VariantList list : chromosomeLists) {
      if (Thread.currentThread().isInterrupted()) {
        return new Outcome(
            new GroupStatResult(mode, groupIds, baselineGroupId, List.of(), "Cancelled"), null);
      }
      if (list == null) {
        continue;
      }
      if (filter != null) {
        list.rebuildVisibleChain(filter);
      }
      acc.fold(mode, list);
      if (Thread.currentThread().isInterrupted()) {
        return new Outcome(
            new GroupStatResult(mode, groupIds, baselineGroupId, List.of(), "Cancelled"), null);
      }
    }

    return finish(mode, acc, registry, groupIds, baselineGroupId);
  }

  /** Score an accumulated gene×sample matrix (after streaming or cache fold). */
  public static Outcome finish(
      AnalysisMode mode,
      Accumulator acc,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId) {
    if (acc == null) {
      return new Outcome(
          new GroupStatResult(mode, groupIds, baselineGroupId, List.of(), "No enrichment rows"),
          null);
    }
    FeatureValueMatrix matrix = acc.toMatrix();
    boolean sawCnvMagnitude = acc.sawCnvMagnitude();

    String scoreLabel = sawCnvMagnitude
        ? "CNV gains−losses (event burden)"
        : "binary carrier 0/1";
    GroupStatTest test = AnalysisSession.get().getGroupStatTest();
    GroupStatResult stats = GroupStatEngine.compute(
        mode, matrix, registry, groupIds, baselineGroupId, test, scoreLabel);
    if (stats == null) {
      return new Outcome(
          new GroupStatResult(mode, groupIds, baselineGroupId, List.of(), "No enrichment rows"),
          matrix);
    }

    if (sawCnvMagnitude) {
      stats = applyCnvGroupCoherence(stats, matrix, registry, groupIds);
    }

    // Odds-ratio rewrite assumes binary carrier rates — skip for continuous CNV magnitudes.
    if (groupIds.size() == 2 && !sawCnvMagnitude) {
      List<GroupStatResult.Row> enriched = new ArrayList<>(stats.rows().size());
      for (GroupStatResult.Row row : stats.rows()) {
        String extra = row.testName();
        Double log2Or = row.effectVsBaseline();
        if (row.groups() != null && row.groups().size() == 2) {
          GroupStatResult.GroupSummary a = row.groups().get(0);
          GroupStatResult.GroupSummary b = row.groups().get(1);
          int aCarriers = a.nSignal() > 0 ? a.nSignal() : (int) Math.round(a.mean() * a.n());
          int bCarriers = b.nSignal() > 0 ? b.nSignal() : (int) Math.round(b.mean() * b.n());
          double controlAf = b.n() > 0 ? bCarriers / (double) b.n() : 0.0;
          double or = VariantOddsRatio.alleleOddsRatio(aCarriers, Math.max(1, a.n()), controlAf);
          if (!Double.isNaN(or) && or > 0) {
            extra = String.format(Locale.ROOT, "%s; OR≈%.2f", row.testName(), or);
            log2Or = Math.log(or) / Math.log(2);
          }
        }
        enriched.add(new GroupStatResult.Row(
            row.unitId(),
            row.geneName(),
            row.chromosome(),
            row.start1Based(),
            row.end1Based(),
            row.groups(),
            log2Or,
            row.pValue(),
            row.qValue(),
            extra,
            row.direction()));
      }
      String names = String.join(
          " vs ",
          groupIds.stream().map(id -> ComparisonContext.groupName(registry, id)).toList());
      return new Outcome(
          new GroupStatResult(
              mode,
              groupIds,
              baselineGroupId,
              enriched,
              "Mutation enrichment (" + names + "): " + enriched.size() + " genes",
              stats.methodStrip()),
          matrix);
    }

    String names = String.join(
        ", ",
        groupIds.stream().map(id -> ComparisonContext.groupName(registry, id)).toList());
    if (sawCnvMagnitude) {
      String summary = "CNV event burden (" + names + "): " + stats.rows().size()
          + " genes (score = gains−losses per sample; missing = 0; "
          + "Diff needs ≥"
          + String.format(Locale.ROOT, "%.0f%%", GROUP_SIGN_COHERENCE * 100)
          + " within-group sign agreement)";
      String method = stats.methodStrip();
      if (method != null && !method.isBlank()) {
        method = method + " · Diff: ≥"
            + String.format(Locale.ROOT, "%.0f%%", GROUP_SIGN_COHERENCE * 100)
            + " within-group sign agreement";
      }
      return new Outcome(
          new GroupStatResult(
              mode, groupIds, baselineGroupId, stats.rows(), summary, method),
          matrix);
    }
    return new Outcome(
        new GroupStatResult(
            mode,
            groupIds,
            baselineGroupId,
            stats.rows(),
            "Mutation enrichment (" + names + "): " + stats.rows().size() + " genes with calls",
            stats.methodStrip()),
        matrix);
  }

  /**
   * Prefer census gene coordinates; fall back to the first overlapping call span.
   */
  private static void rememberLocus(
      Map<String, FeatureValueMatrix.Locus> loci,
      String gene,
      String chrom,
      VariantNode node) {
    if (loci.containsKey(gene) || gene == null) {
      return;
    }
    GeneLocation geneLoc = AnnotationData.getGeneLocation(gene);
    if (geneLoc != null
        && geneLoc.chrom() != null
        && !geneLoc.chrom().isBlank()
        && geneLoc.end() > 0) {
      loci.put(gene, new FeatureValueMatrix.Locus(
          geneLoc.chrom(), geneLoc.start(), geneLoc.end()));
      return;
    }
    if (chrom == null || node == null) {
      return;
    }
    long end = node.svEnd > node.position ? node.svEnd : node.position;
    loci.put(gene, new FeatureValueMatrix.Locus(chrom, node.position, end));
  }

  private static void putResolvedLocus(
      FeatureValueMatrix matrix, String gene, FeatureValueMatrix.Locus fallback) {
    GeneLocation geneLoc = AnnotationData.getGeneLocation(gene);
    if (geneLoc != null
        && geneLoc.chrom() != null
        && !geneLoc.chrom().isBlank()
        && geneLoc.end() > 0) {
      matrix.putLocus(gene, geneLoc.chrom(), geneLoc.start(), geneLoc.end());
      return;
    }
    if (fallback != null) {
      matrix.putLocus(
          gene, fallback.chromosome(), fallback.start1Based(), fallback.end1Based());
    }
  }

  /**
   * Mark rows {@code similar} when the extreme groups (or both groups in a 2-group test)
   * are not directionally coherent among samples with events.
   */
  static GroupStatResult applyCnvGroupCoherence(
      GroupStatResult stats,
      FeatureValueMatrix matrix,
      SampleRegistry registry,
      List<Integer> groupIds) {
    if (stats == null || matrix == null || registry == null || groupIds == null) {
      return stats;
    }
    List<GroupStatResult.Row> out = new ArrayList<>(stats.rows().size());
    int demoted = 0;
    for (GroupStatResult.Row row : stats.rows()) {
      if (row == null) {
        continue;
      }
      String dir = row.direction() != null ? row.direction() : "similar";
      if ("similar".equals(dir) || dir.isBlank()) {
        out.add(row);
        continue;
      }
      boolean coherent;
      if (groupIds.size() == 2) {
        coherent = isGroupCoherent(matrix, row.unitId(), groupIds.get(0), registry)
            && isGroupCoherent(matrix, row.unitId(), groupIds.get(1), registry);
      } else {
        GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(row.groups());
        if (extremes == null || !extremes.distinct()) {
          coherent = true;
        } else {
          coherent = isGroupCoherent(
                  matrix, row.unitId(), extremes.highest().groupId(), registry)
              && isGroupCoherent(
                  matrix, row.unitId(), extremes.lowest().groupId(), registry);
        }
      }
      if (coherent) {
        out.add(row);
      } else {
        demoted++;
        out.add(new GroupStatResult.Row(
            row.unitId(),
            row.geneName(),
            row.chromosome(),
            row.start1Based(),
            row.end1Based(),
            row.groups(),
            row.effectVsBaseline(),
            row.pValue(),
            row.qValue(),
            row.testName(),
            "similar"));
      }
    }
    String summary = stats.summary();
    if (demoted > 0) {
      summary = summary + " — " + demoted + " incoherent Diff→similar";
    }
    return new GroupStatResult(
        stats.mode(),
        stats.groupIds(),
        stats.baselineGroupId(),
        out,
        summary,
        stats.methodStrip());
  }

  private static boolean isGroupCoherent(
      FeatureValueMatrix matrix,
      String unitId,
      int groupId,
      SampleRegistry registry) {
    List<SampleTrack> tracks = ComparisonContext.tracksForGroup(registry, groupId);
    return isDirectionallyCoherent(matrix.valuesFor(unitId, tracks));
  }

  /**
   * Among samples with a non-zero burden, ≥{@link #GROUP_SIGN_COHERENCE} share the
   * dominant sign. All-zero groups are treated as coherent (neutral).
   */
  static boolean isDirectionallyCoherent(List<Double> values) {
    if (values == null || values.isEmpty()) {
      return true;
    }
    int pos = 0;
    int neg = 0;
    for (Double v : values) {
      if (v == null || Double.isNaN(v) || v == 0.0) {
        continue;
      }
      if (v > 0) {
        pos++;
      } else {
        neg++;
      }
    }
    int nonzero = pos + neg;
    if (nonzero == 0) {
      return true;
    }
    return Math.max(pos, neg) / (double) nonzero >= GROUP_SIGN_COHERENCE;
  }

  /** Genes annotated on the node (overlapping census list, else primary name). */
  private static List<String> genesOf(VariantNode node) {
    if (node == null || node.annotation == null) {
      return List.of();
    }
    org.baseplayer.utils.GeneBiotypeVisibility visibility =
        org.baseplayer.utils.GeneBiotypeVisibility.get();
    List<String> overlapping = node.annotation.overlappingGenes();
    if (overlapping != null && !overlapping.isEmpty()) {
      List<String> genes = new ArrayList<>(overlapping.size());
      for (String name : overlapping) {
        if (name != null && !name.isBlank() && visibility.isVisibleGeneName(name.trim())) {
          genes.add(name.trim());
        }
      }
      if (!genes.isEmpty()) {
        return genes;
      }
    }
    String primary = node.annotation.geneName();
    if (primary == null || primary.isBlank()) {
      return List.of();
    }
    String trimmed = primary.trim();
    return visibility.isVisibleGeneName(trimmed) ? List.of(trimmed) : List.of();
  }

  /**
   * Per-sample CNV collapse: each overlapping segment is one gain (+1) or loss (−1)
   * event. Score {@code gains − losses} is stable under annotate-all (extra segments
   * add discrete events; they do not dilute an existing magnitude mean).
   */
  static final class CnvBurden {
    private int gains;
    private int losses;

    void addEvent(double signedMagnitude) {
      if (Double.isNaN(signedMagnitude) || signedMagnitude == 0.0) {
        return;
      }
      if (signedMagnitude > 0) {
        gains++;
      } else {
        losses++;
      }
    }

    boolean hasEvents() {
      return gains > 0 || losses > 0;
    }

    /** Signed event burden used as the gene×sample matrix value. */
    double signedEventScore() {
      return gains - losses;
    }

    int gains() {
      return gains;
    }

    int losses() {
      return losses;
    }
  }
}
