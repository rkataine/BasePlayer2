package org.baseplayer.analysis.pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.groupstat.GroupStatEngine;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.groupstat.GroupStatTest;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.variant.annotation.VariantEffect;

/**
 * Unsupervised sample-pattern discovery on a {@link ValueGrid}:
 * top-variable features → feature z-scores → sample correlation →
 * average-linkage hierarchical clustering → k groups, plus cluster-vs-rest markers.
 */
public final class SamplePatternEngine {

  public record Settings(int topNFeatures, int k, double minMean, boolean zScoreFeatures) {
    public static Settings defaults() {
      return new Settings(2000, 2, 0.0, true);
    }

    public Settings sanitized(int sampleCount) {
      int n = Math.max(50, topNFeatures);
      int kk = Math.max(2, Math.min(k, Math.max(2, sampleCount)));
      return new Settings(n, kk, Math.max(0.0, minMean), zScoreFeatures);
    }
  }

  public record Result(
      List<SampleTrack> tracks,
      int[] clusterOfTrack,
      int k,
      List<String> selectedFeatures,
      GroupStatResult markers,
      String summary,
      /** Dendrogram / cohort leaf order: indices into {@link #tracks()}. */
      int[] leafOrder,
      /** Rows = samples (same order as {@link #tracks()}), cols = {@link #selectedFeatures()}. */
      double[][] sampleFeature,
      boolean zScored,
      /**
       * Optional labels for {@link #clusterOfTrack()} ids (Pattern 1…k or group names).
       * Empty → heatmap falls back to {@code Pattern N}.
       */
      List<String> clusterLabels,
      /**
       * Optional mutation-effect layer (same shape as {@link #sampleFeature()}).
       * When present, heatmaps color cells by effect instead of continuous values.
       */
      VariantEffect[][] cellEffects) {
    public Result(
        List<SampleTrack> tracks,
        int[] clusterOfTrack,
        int k,
        List<String> selectedFeatures,
        GroupStatResult markers,
        String summary,
        int[] leafOrder,
        double[][] sampleFeature,
        boolean zScored) {
      this(
          tracks,
          clusterOfTrack,
          k,
          selectedFeatures,
          markers,
          summary,
          leafOrder,
          sampleFeature,
          zScored,
          List.of(),
          null);
    }

    public Result(
        List<SampleTrack> tracks,
        int[] clusterOfTrack,
        int k,
        List<String> selectedFeatures,
        GroupStatResult markers,
        String summary,
        int[] leafOrder,
        double[][] sampleFeature,
        boolean zScored,
        List<String> clusterLabels) {
      this(
          tracks,
          clusterOfTrack,
          k,
          selectedFeatures,
          markers,
          summary,
          leafOrder,
          sampleFeature,
          zScored,
          clusterLabels,
          null);
    }

    public boolean hasMutationEffects() {
      return cellEffects != null
          && tracks != null
          && selectedFeatures != null
          && cellEffects.length == tracks.size();
    }

    /** Copy with mutation-effect colors for heatmaps. */
    public Result withCellEffects(VariantEffect[][] effects) {
      return new Result(
          tracks,
          clusterOfTrack,
          k,
          selectedFeatures,
          markers,
          summary,
          leafOrder,
          sampleFeature,
          zScored,
          clusterLabels,
          effects);
    }

    public List<SampleTrack> tracksInCluster(int cluster) {
      List<SampleTrack> out = new ArrayList<>();
      if (tracks == null || clusterOfTrack == null) {
        return out;
      }
      for (int i = 0; i < tracks.size(); i++) {
        if (clusterOfTrack[i] == cluster) {
          out.add(tracks.get(i));
        }
      }
      return out;
    }

    public boolean hasHeatmapData() {
      return tracks != null && !tracks.isEmpty()
          && selectedFeatures != null && selectedFeatures.size() >= 2
          && sampleFeature != null && sampleFeature.length == tracks.size()
          && leafOrder != null && leafOrder.length == tracks.size();
    }

    /** Display name for a cluster / cohort id. */
    public String clusterLabel(int cluster) {
      if (clusterLabels != null && cluster >= 0 && cluster < clusterLabels.size()) {
        String name = clusterLabels.get(cluster);
        if (name != null && !name.isBlank()) {
          return name;
        }
      }
      return "Pattern " + (cluster + 1);
    }
  }

  /** Cluster ids 0..k-1 plus dendrogram leaf order (indices into the sample list). */
  record HierarchicalCut(int[] clusterOf, int[] leafOrder) {}

  private SamplePatternEngine() {}

  public static Result discover(
      ValueGrid grid,
      List<SampleTrack> tracks,
      Settings settings,
      EffectMode effectMode,
      SignalValueType valueType,
      GroupStatTest test) {
    if (grid == null || grid.isEmpty() || tracks == null || tracks.size() < 2) {
      return emptyResult("Need ≥2 samples with values");
    }
    List<SampleTrack> used = new ArrayList<>();
    for (SampleTrack t : tracks) {
      if (t != null) {
        used.add(t);
      }
    }
    if (used.size() < 2) {
      return emptyResult("Need ≥2 samples with values");
    }
    Settings cfg = (settings != null ? settings : Settings.defaults()).sanitized(used.size());

    List<String> features = selectTopVariableFeatures(grid, used, cfg.topNFeatures(), cfg.minMean());
    if (features.size() < 2) {
      int[] identity = identityOrder(used.size());
      return new Result(used, new int[used.size()], 1, features, null,
          "Need ≥2 variable features after filtering",
          identity, new double[used.size()][0], cfg.zScoreFeatures());
    }

    double[][] sampleFeature = buildSampleFeatureMatrix(grid, used, features, cfg.zScoreFeatures());
    double[][] dist = sampleDistanceFromCorrelation(sampleFeature);
    HierarchicalCut cut = hierarchicalCut(dist, cfg.k());
    int[] clusters = cut.clusterOf();

    GroupStatResult markers = clusterMarkers(
        grid, used, clusters, cfg.k(), test, effectMode, valueType);

    FeatureOrder ordered = orderFeaturesByMarkers(features, sampleFeature, markers);
    features = ordered.features();
    sampleFeature = ordered.matrix();

    int[] sizes = new int[cfg.k()];
    for (int c : clusters) {
      if (c >= 0 && c < sizes.length) {
        sizes[c]++;
      }
    }
    StringBuilder sizeTxt = new StringBuilder();
    for (int i = 0; i < sizes.length; i++) {
      if (i > 0) {
        sizeTxt.append(", ");
      }
      sizeTxt.append("P").append(i + 1).append("=").append(sizes[i]);
    }
    String summary = String.format(Locale.ROOT,
        "Patterns: k=%d · features=%d · sizes [%s]%s",
        cfg.k(),
        features.size(),
        sizeTxt,
        markers != null ? " · markers " + markers.rows().size() : "");
    return new Result(
        used,
        clusters,
        cfg.k(),
        List.copyOf(features),
        markers,
        summary,
        cut.leafOrder(),
        sampleFeature,
        cfg.zScoreFeatures());
  }

  private static Result emptyResult(String summary) {
    return new Result(List.of(), new int[0], 0, List.of(), null, summary,
        new int[0], new double[0][0], true, List.of(), null);
  }

  /**
   * Build a samples×features effect layer from a mutation {@link FeatureValueMatrix}.
   * Returns null when the matrix has no effects.
   */
  public static VariantEffect[][] cellEffectsFromMatrix(
      FeatureValueMatrix matrix,
      List<SampleTrack> tracks,
      List<String> features) {
    if (matrix == null || !matrix.hasEffects()
        || tracks == null || tracks.isEmpty()
        || features == null || features.isEmpty()) {
      return null;
    }
    VariantEffect[][] out = new VariantEffect[tracks.size()][features.size()];
    for (int s = 0; s < tracks.size(); s++) {
      SampleTrack track = tracks.get(s);
      for (int f = 0; f < features.size(); f++) {
        out[s][f] = matrix.getEffect(features.get(f), track);
      }
    }
    return out;
  }

  /** Attach mutation effects from {@code matrix} onto an existing pattern/heatmap result. */
  public static Result withEffectsFromMatrix(Result result, FeatureValueMatrix matrix) {
    if (result == null) {
      return null;
    }
    VariantEffect[][] effects = cellEffectsFromMatrix(
        matrix, result.tracks(), result.selectedFeatures());
    return effects != null ? result.withCellEffects(effects) : result;
  }

  private static int[] identityOrder(int n) {
    int[] order = new int[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    return order;
  }

  /**
   * Heatmap payload from a Group comparison result: DE features × samples,
   * columns ordered by comparison cohort, z-scored values.
   */
  public static Result fromGroupComparison(
      ValueGrid grid,
      SampleRegistry registry,
      List<Integer> groupIds,
      GroupStatResult deResult) {
    if (grid == null || grid.isEmpty() || registry == null
        || groupIds == null || groupIds.size() < 2
        || deResult == null || deResult.rows() == null || deResult.rows().size() < 2) {
      return emptyResult("Need ≥2 groups and ≥2 DE features for heatmap");
    }

    List<String> features = new ArrayList<>();
    for (GroupStatResult.Row row : deResult.rows()) {
      if (row == null || row.unitId() == null || row.unitId().isBlank()) {
        continue;
      }
      features.add(row.unitId());
      if (features.size() >= 5_000) {
        break;
      }
    }
    if (features.size() < 2) {
      return emptyResult("Need ≥2 DE features for heatmap");
    }

    List<String> labels = new ArrayList<>(groupIds.size());
    List<SampleTrack> tracks = new ArrayList<>();
    List<Integer> clusterOf = new ArrayList<>();
    Map<SampleTrack, Boolean> seen = new IdentityHashMap<>();
    for (int g = 0; g < groupIds.size(); g++) {
      int groupId = groupIds.get(g);
      labels.add(ComparisonContext.groupName(registry, groupId));
      for (SampleTrack track : ComparisonContext.tracksForGroup(registry, groupId)) {
        if (track == null || seen.put(track, Boolean.TRUE) != null) {
          continue;
        }
        tracks.add(track);
        clusterOf.add(g);
      }
    }
    if (tracks.size() < 2) {
      return emptyResult("Need ≥2 samples in compared groups");
    }

    int[] clusters = new int[clusterOf.size()];
    for (int i = 0; i < clusterOf.size(); i++) {
      clusters[i] = clusterOf.get(i);
    }
    // Stable cohort order: already appended group-by-group.
    int[] leafOrder = identityOrder(tracks.size());
    double[][] matrix = buildSampleFeatureMatrix(grid, tracks, features, true);
    String summary = String.format(Locale.ROOT,
        "Group comparison heatmap: %d features · %d samples · %d groups",
        features.size(),
        tracks.size(),
        groupIds.size());
    VariantEffect[][] cellEffects = null;
    if (grid instanceof FeatureValueMatrix fvm && fvm.hasEffects()) {
      cellEffects = cellEffectsFromMatrix(fvm, tracks, features);
    }
    return new Result(
        List.copyOf(tracks),
        clusters,
        groupIds.size(),
        List.copyOf(features),
        deResult,
        summary,
        leafOrder,
        matrix,
        true,
        List.copyOf(labels),
        cellEffects);
  }

  private record FeatureOrder(List<String> features, double[][] matrix) {}

  /** Strongest markers first (by q then p); remaining features keep variance order. */
  static FeatureOrder orderFeaturesByMarkers(
      List<String> features, double[][] sampleFeature, GroupStatResult markers) {
    if (features == null || features.isEmpty() || sampleFeature == null) {
      return new FeatureOrder(
          features != null ? features : List.of(),
          sampleFeature != null ? sampleFeature : new double[0][0]);
    }
    if (markers == null || markers.rows() == null || markers.rows().isEmpty()) {
      return new FeatureOrder(features, sampleFeature);
    }
    java.util.Map<String, Integer> rank = new java.util.HashMap<>();
    int r = 0;
    for (GroupStatResult.Row row : markers.rows()) {
      if (row == null || row.unitId() == null) {
        continue;
      }
      rank.putIfAbsent(row.unitId(), r++);
    }
    List<Integer> idxs = new ArrayList<>(features.size());
    for (int i = 0; i < features.size(); i++) {
      idxs.add(i);
    }
    idxs.sort(Comparator
        .comparingInt((Integer i) -> rank.getOrDefault(features.get(i), Integer.MAX_VALUE))
        .thenComparingInt(i -> i));
    List<String> ordered = new ArrayList<>(features.size());
    double[][] m = new double[sampleFeature.length][features.size()];
    for (int newCol = 0; newCol < idxs.size(); newCol++) {
      int oldCol = idxs.get(newCol);
      ordered.add(features.get(oldCol));
      for (int s = 0; s < sampleFeature.length; s++) {
        m[s][newCol] = sampleFeature[s][oldCol];
      }
    }
    return new FeatureOrder(ordered, m);
  }

  /** Top-N features by variance across samples (optional min mean). */
  static List<String> selectTopVariableFeatures(
      ValueGrid grid, List<SampleTrack> tracks, int topN, double minMean) {
    record Cand(String id, double var) {}
    List<Cand> cands = new ArrayList<>();
    for (String unitId : grid.unitIds()) {
      List<Double> vals = grid.valuesFor(unitId, tracks);
      if (vals.size() < 2) {
        continue;
      }
      double mean = GroupStatEngine.mean(vals);
      if (minMean > 0 && (!(mean >= minMean))) {
        continue;
      }
      double var = variance(vals, mean);
      if (!Double.isFinite(var) || var <= 0) {
        continue;
      }
      cands.add(new Cand(unitId, var));
    }
    cands.sort(Comparator.comparingDouble(Cand::var).reversed());
    int n = Math.min(topN, cands.size());
    List<String> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(cands.get(i).id());
    }
    return out;
  }

  /**
   * Rows = samples, cols = features. Optionally z-score each feature column.
   */
  static double[][] buildSampleFeatureMatrix(
      ValueGrid grid,
      List<SampleTrack> tracks,
      List<String> features,
      boolean zScoreFeatures) {
    int nS = tracks.size();
    int nF = features.size();
    double[][] m = new double[nS][nF];
    for (int f = 0; f < nF; f++) {
      String unit = features.get(f);
      double[] col = new double[nS];
      double sum = 0;
      int obs = 0;
      for (int s = 0; s < nS; s++) {
        Double v = grid.get(unit, tracks.get(s));
        if (v == null || !Double.isFinite(v)) {
          col[s] = Double.NaN;
        } else {
          col[s] = v;
          sum += v;
          obs++;
        }
      }
      double fill = obs > 0 ? sum / obs : 0.0;
      for (int s = 0; s < nS; s++) {
        if (!Double.isFinite(col[s])) {
          col[s] = fill;
        }
      }
      if (zScoreFeatures) {
        double mean = 0;
        for (double v : col) {
          mean += v;
        }
        mean /= nS;
        double ss = 0;
        for (double v : col) {
          double d = v - mean;
          ss += d * d;
        }
        double sd = nS > 1 ? Math.sqrt(ss / (nS - 1.0)) : 0.0;
        if (sd < 1e-12) {
          Arrays.fill(col, 0.0);
        } else {
          for (int s = 0; s < nS; s++) {
            col[s] = (col[s] - mean) / sd;
          }
        }
      }
      for (int s = 0; s < nS; s++) {
        m[s][f] = col[s];
      }
    }
    return m;
  }

  /** Distance = 1 − Pearson correlation; clipped to [0, 2]. */
  static double[][] sampleDistanceFromCorrelation(double[][] sampleFeature) {
    int n = sampleFeature.length;
    double[][] dist = new double[n][n];
    for (int i = 0; i < n; i++) {
      dist[i][i] = 0;
      for (int j = i + 1; j < n; j++) {
        double corr = pearson(sampleFeature[i], sampleFeature[j]);
        if (!Double.isFinite(corr)) {
          corr = 0;
        }
        double d = 1.0 - corr;
        if (d < 0) {
          d = 0;
        }
        if (d > 2) {
          d = 2;
        }
        dist[i][j] = d;
        dist[j][i] = d;
      }
    }
    return dist;
  }

  static double pearson(double[] a, double[] b) {
    if (a == null || b == null || a.length != b.length || a.length < 2) {
      return Double.NaN;
    }
    int n = a.length;
    double meanA = 0;
    double meanB = 0;
    for (int i = 0; i < n; i++) {
      meanA += a[i];
      meanB += b[i];
    }
    meanA /= n;
    meanB /= n;
    double num = 0;
    double denA = 0;
    double denB = 0;
    for (int i = 0; i < n; i++) {
      double da = a[i] - meanA;
      double db = b[i] - meanB;
      num += da * db;
      denA += da * da;
      denB += db * db;
    }
    double den = Math.sqrt(denA * denB);
    if (den < 1e-18) {
      return 0;
    }
    return num / den;
  }

  /**
   * Average-linkage (UPGMA) hierarchical clustering; cut to exactly {@code k} clusters.
   * Returns cluster id 0..k-1 per sample and leaf order (concatenated surviving clusters).
   */
  static HierarchicalCut hierarchicalCut(double[][] dist, int k) {
    int n = dist.length;
    int kk = Math.max(1, Math.min(k, n));
    if (n == 0) {
      return new HierarchicalCut(new int[0], new int[0]);
    }
    if (kk >= n) {
      int[] ids = new int[n];
      for (int i = 0; i < n; i++) {
        ids[i] = i;
      }
      return new HierarchicalCut(ids, identityOrder(n));
    }

    List<List<Integer>> members = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      List<Integer> m = new ArrayList<>(1);
      m.add(i);
      members.add(m);
    }
    boolean[] alive = new boolean[n];
    Arrays.fill(alive, true);
    double[][] d = new double[n][n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(dist[i], 0, d[i], 0, n);
    }

    int aliveCount = n;
    while (aliveCount > kk) {
      double best = Double.POSITIVE_INFINITY;
      int bi = -1;
      int bj = -1;
      for (int i = 0; i < n; i++) {
        if (!alive[i]) {
          continue;
        }
        for (int j = i + 1; j < n; j++) {
          if (!alive[j]) {
            continue;
          }
          double dij = d[i][j];
          if (dij < best) {
            best = dij;
            bi = i;
            bj = j;
          }
        }
      }
      if (bi < 0) {
        break;
      }
      // Merge bj into bi (average linkage).
      int nI = members.get(bi).size();
      int nJ = members.get(bj).size();
      for (int t = 0; t < n; t++) {
        if (!alive[t] || t == bi || t == bj) {
          continue;
        }
        double updated = (nI * d[bi][t] + nJ * d[bj][t]) / (double) (nI + nJ);
        d[bi][t] = updated;
        d[t][bi] = updated;
      }
      members.get(bi).addAll(members.get(bj));
      members.get(bj).clear();
      alive[bj] = false;
      aliveCount--;
    }

    int[] clusterOf = new int[n];
    Arrays.fill(clusterOf, -1);
    int[] leafOrder = new int[n];
    int leafAt = 0;
    int cid = 0;
    for (int i = 0; i < n; i++) {
      if (!alive[i]) {
        continue;
      }
      for (int sample : members.get(i)) {
        clusterOf[sample] = cid;
        leafOrder[leafAt++] = sample;
      }
      cid++;
    }
    // Safety: assign any leftovers.
    for (int i = 0; i < n; i++) {
      if (clusterOf[i] < 0) {
        clusterOf[i] = 0;
        if (leafAt < n) {
          leafOrder[leafAt++] = i;
        }
      }
    }
    while (leafAt < n) {
      leafOrder[leafAt] = leafAt;
      leafAt++;
    }
    return new HierarchicalCut(clusterOf, leafOrder);
  }

  /**
   * For each feature, test each cluster vs the rest (moderated/Welch); keep the
   * strongest (lowest p) contrast as the row, with per-cluster summaries.
   */
  static GroupStatResult clusterMarkers(
      ValueGrid grid,
      List<SampleTrack> tracks,
      int[] clusterOf,
      int k,
      GroupStatTest test,
      EffectMode effectMode,
      SignalValueType valueType) {
    GroupStatTest requested = test != null ? test : GroupStatTest.AUTO;
    EffectMode reqEffect = effectMode != null ? effectMode : EffectMode.AUTO;
    SignalValueType type = valueType != null ? valueType : SignalValueType.RAW;
    EffectMode concreteEffect = reqEffect.resolve(type);

    List<List<SampleTrack>> buckets = new ArrayList<>(k);
    for (int c = 0; c < k; c++) {
      buckets.add(new ArrayList<>());
    }
    for (int i = 0; i < tracks.size(); i++) {
      int c = clusterOf[i];
      if (c >= 0 && c < k) {
        buckets.get(c).add(tracks.get(i));
      }
    }

    GroupStatTest concreteTwo = requested.resolve(grid, 2);
    boolean useModerated = concreteTwo == GroupStatTest.MODERATED;
    boolean useRank = concreteTwo == GroupStatTest.RANK;

    List<GroupStatResult.Row> rows = new ArrayList<>();
    List<double[]> residualVars = useModerated ? new ArrayList<>() : null;
    List<UnitPrep> preps = new ArrayList<>();

    for (String unitId : grid.unitIds()) {
      List<List<Double>> series = new ArrayList<>(k);
      List<GroupStatResult.GroupSummary> summaries = new ArrayList<>(k);
      int usable = 0;
      for (int c = 0; c < k; c++) {
        List<Double> vals = grid.valuesFor(unitId, buckets.get(c));
        series.add(vals);
        if (!vals.isEmpty()) {
          usable++;
        }
        summaries.add(GroupStatEngine.summarizeGroup(
            c, "Pattern " + (c + 1), buckets.get(c).size(), vals.size(), vals));
      }
      if (usable < 2) {
        continue;
      }

      int bestCluster = -1;
      Double bestP = null;
      Double bestEffect = null;
      String bestDir = "similar";

      for (int c = 0; c < k; c++) {
        List<Double> in = series.get(c);
        if (in.size() < 2) {
          continue;
        }
        List<Double> out = new ArrayList<>();
        for (int o = 0; o < k; o++) {
          if (o != c) {
            out.addAll(series.get(o));
          }
        }
        if (out.size() < 2) {
          continue;
        }
        double inMean = GroupStatEngine.mean(in);
        double outMean = GroupStatEngine.mean(out);
        Double effect = GroupStatEngine.effectValue(concreteEffect, inMean, outMean);
        String dir = "similar";
        if (effect != null && Double.isFinite(effect)) {
          if (effect > 0.05) {
            dir = "up";
          } else if (effect < -0.05) {
            dir = "down";
          }
        }
        if (useModerated) {
          GroupStatEngine.PooledVar pv = GroupStatEngine.pooledVariance(in, out);
          if (pv != null && pv.variance() > 0) {
            residualVars.add(new double[] {pv.variance()});
          }
          preps.add(new UnitPrep(unitId, summaries, c, in, out, effect, dir));
        } else {
          Double p = useRank
              ? GroupStatEngine.mannWhitneyP(in, out)
              : GroupStatEngine.welchP(in, out);
          if (p == null) {
            continue;
          }
          if (bestP == null || p < bestP) {
            bestP = p;
            bestCluster = c;
            bestEffect = effect;
            bestDir = dir;
          }
        }
      }

      if (!useModerated && bestCluster >= 0) {
        FeatureValueMatrix.Locus locus = grid.locusOf(unitId);
        rows.add(new GroupStatResult.Row(
            unitId,
            unitId,
            locus != null ? locus.chromosome() : null,
            locus != null ? locus.start1Based() : 0L,
            locus != null ? locus.end1Based() : 0L,
            summaries,
            bestEffect,
            bestP,
            null,
            "Pattern " + (bestCluster + 1) + " vs rest",
            bestDir));
      }
    }

    if (useModerated) {
      List<Double> vars = new ArrayList<>();
      if (residualVars != null) {
        for (double[] v : residualVars) {
          vars.add(v[0]);
        }
      }
      GroupStatEngine.ModeratedPrior prior = GroupStatEngine.estimateModeratedPrior(vars);
      // Collapse preps: per unit keep best (lowest) moderated p across cluster-vs-rest.
      java.util.Map<String, GroupStatResult.Row> best = new java.util.LinkedHashMap<>();
      for (UnitPrep prep : preps) {
        Double p = GroupStatEngine.moderatedP(prep.in, prep.out, prior.s0sq(), prior.d0());
        if (p == null) {
          continue;
        }
        GroupStatResult.Row existing = best.get(prep.unitId);
        if (existing != null && existing.pValue() != null && existing.pValue() <= p) {
          continue;
        }
        FeatureValueMatrix.Locus locus = grid.locusOf(prep.unitId);
        best.put(prep.unitId, new GroupStatResult.Row(
            prep.unitId,
            prep.unitId,
            locus != null ? locus.chromosome() : null,
            locus != null ? locus.start1Based() : 0L,
            locus != null ? locus.end1Based() : 0L,
            prep.summaries,
            prep.effect,
            p,
            null,
            "Pattern " + (prep.cluster + 1) + " vs rest",
            prep.direction));
      }
      rows.addAll(best.values());
    }

    GroupStatEngine.applyBenjaminiHochberg(rows);
    rows.sort(Comparator
        .comparing((GroupStatResult.Row r) -> r.qValue() != null ? r.qValue() : Double.POSITIVE_INFINITY)
        .thenComparing(r -> r.pValue() != null ? r.pValue() : Double.POSITIVE_INFINITY));

    String method = String.format(Locale.ROOT,
        "Pattern markers · cluster vs rest · Test: %s · Effect: %s · FDR: BH",
        useModerated ? "Moderated t" : requested.displayName(),
        reqEffect.resolvedLabel(type));
    String summary = String.format(Locale.ROOT, "Pattern markers: %d features, %d clusters", rows.size(), k);
    List<Integer> groupIds = new ArrayList<>();
    for (int c = 0; c < k; c++) {
      groupIds.add(c);
    }
    return new GroupStatResult(AnalysisMode.SIGNAL, groupIds, 0, rows, summary, method);
  }

  private static double variance(List<Double> vals, double mean) {
    if (vals == null || vals.size() < 2 || Double.isNaN(mean)) {
      return 0;
    }
    double sum = 0;
    for (double v : vals) {
      double d = v - mean;
      sum += d * d;
    }
    return sum / (vals.size() - 1.0);
  }

  private record UnitPrep(
      String unitId,
      List<GroupStatResult.GroupSummary> summaries,
      int cluster,
      List<Double> in,
      List<Double> out,
      Double effect,
      String direction) {}
}
