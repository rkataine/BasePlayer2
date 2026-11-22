package org.baseplayer.analysis.groupstat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.EffectMode;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.grid.ValueGrid;
import org.baseplayer.analysis.signal.SignalValueType;

/**
 * Pure-Java group statistics for any {@link ValueGrid} (Signal, mutations, CNV, …).
 * Selectable tests via {@link GroupStatTest}; effect vs baseline via {@link EffectMode}.
 */
public final class GroupStatEngine {

  private static final double LOG2 = Math.log(2.0);
  private static final double PSEUDO = 1e-6;
  /** Direction call threshold on the chosen effect metric. */
  private static final double EFFECT_DIR_EPS = 0.05;

  private GroupStatEngine() {}

  public static GroupStatResult compute(
      AnalysisMode mode,
      FeatureValueMatrix matrix,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId) {
    return compute(
        mode, (ValueGrid) matrix, registry, groupIds, baselineGroupId,
        GroupStatTest.AUTO, null, EffectMode.AUTO, SignalValueType.RAW, 0.0);
  }

  public static GroupStatResult compute(
      AnalysisMode mode,
      FeatureValueMatrix matrix,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId,
      GroupStatTest test,
      String scoreLabel) {
    return compute(
        mode, (ValueGrid) matrix, registry, groupIds, baselineGroupId,
        test, scoreLabel, EffectMode.AUTO, SignalValueType.RAW, 0.0);
  }

  public static GroupStatResult compute(
      AnalysisMode mode,
      ValueGrid matrix,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId,
      GroupStatTest test,
      String scoreLabel) {
    return compute(
        mode, matrix, registry, groupIds, baselineGroupId,
        test, scoreLabel, EffectMode.AUTO, SignalValueType.RAW, 0.0);
  }

  public static GroupStatResult compute(
      AnalysisMode mode,
      ValueGrid matrix,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId,
      GroupStatTest test,
      String scoreLabel,
      EffectMode effectMode,
      SignalValueType valueType,
      double minMeanPrefilter) {
    if (matrix == null || matrix.isEmpty() || registry == null || groupIds == null || groupIds.size() < 2) {
      return new GroupStatResult(mode, groupIds, baselineGroupId, List.of(), "Need ≥2 groups and values");
    }

    List<GroupBucket> buckets = new ArrayList<>();
    for (int groupId : groupIds) {
      List<SampleTrack> tracks = ComparisonContext.tracksForGroup(registry, groupId);
      buckets.add(new GroupBucket(
          groupId,
          ComparisonContext.groupName(registry, groupId),
          tracks != null ? tracks : List.of()));
    }

    Integer baseline = baselineGroupId;
    if (baseline == null || !groupIds.contains(baseline)) {
      baseline = groupIds.get(0);
    }
    final Integer baselineId = baseline;

    GroupStatTest requested = test != null ? test : GroupStatTest.AUTO;
    GroupStatTest concrete = requested.resolve(matrix, buckets.size());
    String testName = requested.resolvedLabel(matrix, buckets.size());
    EffectMode requestedEffect = effectMode != null ? effectMode : EffectMode.AUTO;
    SignalValueType type = valueType != null ? valueType : SignalValueType.RAW;
    EffectMode concreteEffect = requestedEffect.resolve(type);
    String effectLabel = requestedEffect.resolvedLabel(type);
    double minMean = Math.max(0.0, minMeanPrefilter);

    boolean twoGroup = buckets.size() == 2;
    boolean useModerated = concrete == GroupStatTest.MODERATED && twoGroup;

    List<UnitScratch> scratches = new ArrayList<>();
    for (String unitId : matrix.unitIds()) {
      List<GroupStatResult.GroupSummary> summaries = new ArrayList<>();
      List<List<Double>> series = new ArrayList<>();
      for (GroupBucket bucket : buckets) {
        List<Double> vals = matrix.valuesFor(unitId, bucket.tracks);
        series.add(vals);
        int nObserved = matrix.observedCount(unitId, bucket.tracks);
        summaries.add(summarizeGroup(
            bucket.groupId, bucket.name, bucket.tracks.size(), nObserved, vals));
      }

      int usableGroups = 0;
      int observedTotal = 0;
      double sumAll = 0;
      for (List<Double> vals : series) {
        if (vals.size() >= 1) {
          usableGroups++;
        }
        for (Double v : vals) {
          if (v != null && Double.isFinite(v)) {
            sumAll += v;
            observedTotal++;
          }
        }
      }
      if (usableGroups < 2) {
        continue;
      }
      if (minMean > 0 && observedTotal > 0 && (sumAll / observedTotal) < minMean) {
        continue;
      }

      double baselineMean = Double.NaN;
      double otherMean = Double.NaN;
      int baselineIdx = -1;
      int otherIdx = -1;
      for (int i = 0; i < buckets.size(); i++) {
        if (buckets.get(i).groupId == baselineId) {
          baselineMean = mean(series.get(i));
          baselineIdx = i;
        } else if (otherIdx < 0) {
          otherMean = mean(series.get(i));
          otherIdx = i;
        }
      }
      if (!twoGroup) {
        List<Double> nonBase = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
          if (buckets.get(i).groupId != baselineId) {
            nonBase.addAll(series.get(i));
          }
        }
        otherMean = mean(nonBase);
      }

      Double effect = null;
      if (!Double.isNaN(baselineMean) && !Double.isNaN(otherMean)) {
        effect = effectValue(concreteEffect, otherMean, baselineMean);
      }

      String direction = "similar";
      if (twoGroup) {
        if (effect != null && !Double.isNaN(effect)) {
          if (effect > EFFECT_DIR_EPS) {
            direction = "up";
          } else if (effect < -EFFECT_DIR_EPS) {
            direction = "down";
          }
        }
      } else {
        GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(summaries);
        if (extremes != null && extremes.distinct()) {
          double spread = Math.abs(extremes.highest().mean() - extremes.lowest().mean());
          if (spread > 1e-6) {
            direction = "different";
          }
        }
      }

      FeatureValueMatrix.Locus locus = matrix.locusOf(unitId);
      scratches.add(new UnitScratch(
          unitId,
          locus,
          summaries,
          series,
          baselineIdx,
          otherIdx,
          effect,
          direction));
    }

    double priorVar = Double.NaN;
    double priorDf = 4.0;
    if (useModerated) {
      List<Double> residualVars = new ArrayList<>();
      for (UnitScratch s : scratches) {
        if (s.baselineIdx < 0 || s.otherIdx < 0) {
          continue;
        }
        PooledVar pv = pooledVariance(s.series.get(s.baselineIdx), s.series.get(s.otherIdx));
        if (pv != null && pv.variance > 0 && Double.isFinite(pv.variance)) {
          residualVars.add(pv.variance);
        }
      }
      ModeratedPrior prior = estimateModeratedPrior(residualVars);
      priorVar = prior.s0sq();
      priorDf = prior.d0();
    }

    List<GroupStatResult.Row> rows = new ArrayList<>(scratches.size());
    for (UnitScratch s : scratches) {
      Double pValue;
      if (useModerated && s.baselineIdx >= 0 && s.otherIdx >= 0) {
        pValue = moderatedP(
            s.series.get(s.baselineIdx),
            s.series.get(s.otherIdx),
            priorVar,
            priorDf);
      } else {
        pValue = pValueFor(concrete, s.series, s.summaries, twoGroup);
      }
      rows.add(new GroupStatResult.Row(
          s.unitId,
          s.unitId,
          s.locus != null ? s.locus.chromosome() : null,
          s.locus != null ? s.locus.start1Based() : 0L,
          s.locus != null ? s.locus.end1Based() : 0L,
          s.summaries,
          s.effect,
          pValue,
          null,
          testName,
          s.direction));
    }

    applyBenjaminiHochberg(rows);
    rows.sort(Comparator
        .comparing((GroupStatResult.Row r) -> r.qValue() != null ? r.qValue() : Double.POSITIVE_INFINITY)
        .thenComparing(r -> r.pValue() != null ? r.pValue() : Double.POSITIVE_INFINITY));

    Double missingDefault = matrix instanceof FeatureValueMatrix fvm
        ? fvm.getMissingDefault()
        : null;
    String missingPolicy = missingDefault != null
        ? "missing=" + formatNum(missingDefault)
        : "observed only";
    String score = scoreLabel != null && !scoreLabel.isBlank() ? scoreLabel : mode.name();
    String methodStrip = String.format(
        Locale.ROOT,
        "Score: %s · Missing: %s · Test: %s · FDR: Benjamini–Hochberg · Effect: %s%s",
        score,
        missingPolicy,
        testName,
        effectLabel,
        minMean > 0
            ? String.format(Locale.ROOT, " · min mean ≥ %.4g", minMean)
            : "");
    String summary = String.format(
        Locale.ROOT,
        "%s: %d units across %d groups (%s)",
        mode,
        rows.size(),
        buckets.size(),
        testName);
    return new GroupStatResult(mode, groupIds, baselineId, rows, summary, methodStrip);
  }

  private static Double pValueFor(
      GroupStatTest concrete,
      List<List<Double>> series,
      List<GroupStatResult.GroupSummary> summaries,
      boolean twoGroup) {
    return switch (concrete) {
      case MODERATED, WELCH, AUTO ->
          twoGroup ? welchP(series.get(0), series.get(1)) : kruskalWallisP(series);
      case RANK -> twoGroup ? mannWhitneyP(series.get(0), series.get(1)) : kruskalWallisP(series);
      case FISHER -> twoGroup ? fisherFromSummaries(summaries.get(0), summaries.get(1))
          : kruskalWallisP(series);
    };
  }

  public static double effectValue(EffectMode mode, double otherMean, double baselineMean) {
    EffectMode concrete = mode != null ? mode : EffectMode.LOG2_FOLD;
    if (concrete == EffectMode.MEAN_DELTA || concrete == EffectMode.ALREADY_LOG) {
      return otherMean - baselineMean;
    }
    return log2Fold(otherMean, baselineMean);
  }

  public static GroupStatResult.GroupSummary summarizeGroup(
      int groupId,
      String name,
      int nTotal,
      int nObserved,
      List<Double> vals) {
    int nSignal = 0;
    int nPos = 0;
    int nNeg = 0;
    if (vals != null) {
      for (Double v : vals) {
        if (v == null || Double.isNaN(v) || v == 0.0) {
          continue;
        }
        nSignal++;
        if (v > 0) {
          nPos++;
        } else {
          nNeg++;
        }
      }
    }
    List<Double> signalVals = new ArrayList<>();
    if (vals != null) {
      for (Double v : vals) {
        if (v != null && !Double.isNaN(v) && v != 0.0) {
          signalVals.add(v);
        }
      }
    }
    double meanAll = mean(vals);
    double medAll = median(vals);
    double meanSig = mean(signalVals);
    double min = min(vals);
    double max = max(vals);
    double sd = sd(vals, meanAll);
    return new GroupStatResult.GroupSummary(
        groupId,
        name,
        nTotal,
        nObserved,
        nSignal,
        nPos,
        nNeg,
        meanAll,
        medAll,
        meanSig,
        min,
        max,
        sd,
        percentile(vals, 0.10),
        percentile(vals, 0.90),
        percentile(vals, 0.95),
        meanTopFraction(vals, 0.10),
        meanTopFraction(vals, 0.05),
        meanBottomFraction(vals, 0.10),
        meanBottomFraction(vals, 0.05));
  }

  public static double mean(List<Double> vals) {
    if (vals == null || vals.isEmpty()) {
      return Double.NaN;
    }
    double sum = 0;
    for (double v : vals) {
      sum += v;
    }
    return sum / vals.size();
  }

  static double median(List<Double> vals) {
    if (vals == null || vals.isEmpty()) {
      return Double.NaN;
    }
    List<Double> sorted = new ArrayList<>(vals);
    Collections.sort(sorted);
    int n = sorted.size();
    if ((n & 1) == 1) {
      return sorted.get(n / 2);
    }
    return 0.5 * (sorted.get(n / 2 - 1) + sorted.get(n / 2));
  }

  static double min(List<Double> vals) {
    if (vals == null || vals.isEmpty()) {
      return Double.NaN;
    }
    double m = Double.POSITIVE_INFINITY;
    for (double v : vals) {
      if (!Double.isNaN(v)) {
        m = Math.min(m, v);
      }
    }
    return m == Double.POSITIVE_INFINITY ? Double.NaN : m;
  }

  static double max(List<Double> vals) {
    if (vals == null || vals.isEmpty()) {
      return Double.NaN;
    }
    double m = Double.NEGATIVE_INFINITY;
    for (double v : vals) {
      if (!Double.isNaN(v)) {
        m = Math.max(m, v);
      }
    }
    return m == Double.NEGATIVE_INFINITY ? Double.NaN : m;
  }

  static double sd(List<Double> vals, double mean) {
    if (vals == null || vals.size() < 2 || Double.isNaN(mean)) {
      return Double.NaN;
    }
    return Math.sqrt(variance(vals, mean));
  }

  /** Empirical percentile in [0,1] over sorted values. */
  static double percentile(List<Double> vals, double p) {
    if (vals == null || vals.isEmpty() || Double.isNaN(p)) {
      return Double.NaN;
    }
    List<Double> sorted = new ArrayList<>(vals.size());
    for (Double v : vals) {
      if (v != null && !Double.isNaN(v)) {
        sorted.add(v);
      }
    }
    if (sorted.isEmpty()) {
      return Double.NaN;
    }
    Collections.sort(sorted);
    double pct = Math.max(0, Math.min(1, p));
    double idx = pct * (sorted.size() - 1);
    int lo = (int) Math.floor(idx);
    int hi = (int) Math.ceil(idx);
    if (lo == hi) {
      return sorted.get(lo);
    }
    double w = idx - lo;
    return sorted.get(lo) * (1 - w) + sorted.get(hi) * w;
  }

  static double meanTopFraction(List<Double> vals, double fraction) {
    return meanTail(vals, fraction, true);
  }

  static double meanBottomFraction(List<Double> vals, double fraction) {
    return meanTail(vals, fraction, false);
  }

  private static double meanTail(List<Double> vals, double fraction, boolean top) {
    if (vals == null || vals.isEmpty() || fraction <= 0) {
      return Double.NaN;
    }
    List<Double> sorted = new ArrayList<>(vals.size());
    for (Double v : vals) {
      if (v != null && !Double.isNaN(v)) {
        sorted.add(v);
      }
    }
    if (sorted.isEmpty()) {
      return Double.NaN;
    }
    Collections.sort(sorted);
    int k = Math.max(1, (int) Math.ceil(fraction * sorted.size()));
    k = Math.min(k, sorted.size());
    double sum = 0;
    if (top) {
      for (int i = sorted.size() - k; i < sorted.size(); i++) {
        sum += sorted.get(i);
      }
    } else {
      for (int i = 0; i < k; i++) {
        sum += sorted.get(i);
      }
    }
    return sum / k;
  }

  private static String formatNum(double v) {
    if (Double.isNaN(v)) {
      return "NaN";
    }
    if (v == Math.rint(v) && Math.abs(v) < 1e9) {
      return String.format(Locale.ROOT, "%.0f", v);
    }
    return String.format(Locale.ROOT, "%.4g", v);
  }

  static double log2Fold(double numerator, double denominator) {
    return Math.log((Math.abs(numerator) + PSEUDO) / (Math.abs(denominator) + PSEUDO)) / LOG2
        * Math.signum(numerator >= 0 && denominator >= 0 ? 1 : (numerator - denominator));
  }

  /**
   * Limma-inspired moderated two-sample t-test using a shared variance prior.
   * Falls back to Welch when the prior is undefined.
   */
  public static Double moderatedP(List<Double> a, List<Double> b, double priorVar, double priorDf) {
    if (a == null || b == null || a.size() < 2 || b.size() < 2) {
      return null;
    }
    if (!Double.isFinite(priorVar) || priorVar <= 0 || !Double.isFinite(priorDf) || priorDf <= 0) {
      return welchP(a, b);
    }
    PooledVar pooled = pooledVariance(a, b);
    if (pooled == null) {
      return null;
    }
    double meanA = mean(a);
    double meanB = mean(b);
    double df = pooled.df;
    double s2tilde = (priorDf * priorVar + df * pooled.variance) / (priorDf + df);
    if (!(s2tilde > 0) || Double.isNaN(s2tilde)) {
      return meanA == meanB ? 1.0 : null;
    }
    double se = Math.sqrt(s2tilde * (1.0 / a.size() + 1.0 / b.size()));
    if (!(se > 0)) {
      return meanA == meanB ? 1.0 : null;
    }
    double t = (meanA - meanB) / se;
    double dfMod = priorDf + df;
    if (dfMod < 1) {
      dfMod = 1;
    }
    return 2.0 * studentTCdf(-Math.abs(t), dfMod);
  }

  public static ModeratedPrior estimateModeratedPrior(List<Double> residualVars) {
    if (residualVars == null || residualVars.isEmpty()) {
      return new ModeratedPrior(Double.NaN, 4.0);
    }
    List<Double> logVars = new ArrayList<>(residualVars.size());
    for (double v : residualVars) {
      if (v > 0 && Double.isFinite(v)) {
        logVars.add(Math.log(v));
      }
    }
    if (logVars.isEmpty()) {
      return new ModeratedPrior(Double.NaN, 4.0);
    }
    double meanLog = mean(logVars);
    double varLog = variance(logVars, meanLog);
    double d0 = 4.0;
    if (varLog > 1e-8 && Double.isFinite(varLog)) {
      // Rough empirical-Bayes prior df from dispersion of log-variances.
      d0 = Math.max(1.0, Math.min(100.0, 2.0 / varLog));
    }
    double s0sq = Math.exp(meanLog);
    return new ModeratedPrior(s0sq, d0);
  }

  public static PooledVar pooledVariance(List<Double> a, List<Double> b) {
    if (a == null || b == null || a.size() < 2 || b.size() < 2) {
      return null;
    }
    double meanA = mean(a);
    double meanB = mean(b);
    double varA = variance(a, meanA);
    double varB = variance(b, meanB);
    double df = a.size() + b.size() - 2.0;
    if (df < 1) {
      return null;
    }
    double pooled = ((a.size() - 1.0) * varA + (b.size() - 1.0) * varB) / df;
    return new PooledVar(pooled, df);
  }

  /** Two-sided Mann–Whitney U (normal approximation with tie correction). */
  public static Double mannWhitneyP(List<Double> a, List<Double> b) {
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      return null;
    }
    int n1 = a.size();
    int n2 = b.size();
    List<double[]> all = new ArrayList<>(n1 + n2);
    for (double v : a) {
      all.add(new double[] {v, 0});
    }
    for (double v : b) {
      all.add(new double[] {v, 1});
    }
    all.sort(Comparator.comparingDouble(x -> x[0]));
    double[] ranks = averageRanks(all);
    double r1 = 0;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i)[1] == 0) {
        r1 += ranks[i];
      }
    }
    double u1 = r1 - n1 * (n1 + 1) / 2.0;
    double u2 = n1 * n2 - u1;
    double u = Math.min(u1, u2);
    double mu = n1 * n2 / 2.0;
    // Tie correction
    double tieSum = 0;
    int i = 0;
    int n = all.size();
    while (i < n) {
      int j = i;
      while (j + 1 < n && all.get(j + 1)[0] == all.get(i)[0]) {
        j++;
      }
      int t = j - i + 1;
      if (t > 1) {
        tieSum += t * t * t - t;
      }
      i = j + 1;
    }
    double sigma2 = n1 * n2 * (n + 1.0) / 12.0;
    if (tieSum > 0 && n > 1) {
      sigma2 -= n1 * n2 * tieSum / (12.0 * n * (n - 1.0));
    }
    if (sigma2 <= 0) {
      return u1 == u2 ? 1.0 : null;
    }
    double z = (u - mu + 0.5) / Math.sqrt(sigma2); // continuity correction
    return 2.0 * standardNormalSf(Math.abs(z));
  }

  private static Double fisherFromSummaries(
      GroupStatResult.GroupSummary a, GroupStatResult.GroupSummary b) {
    if (a == null || b == null) {
      return null;
    }
    int aHit = a.nSignal();
    int aMiss = Math.max(0, a.nTotal() - aHit);
    int bHit = b.nSignal();
    int bMiss = Math.max(0, b.nTotal() - bHit);
    return fisherExactP(aHit, aMiss, bHit, bMiss);
  }

  /** Two-sided Fisher exact test on a 2×2 table. */
  static Double fisherExactP(int a, int b, int c, int d) {
    if (a < 0 || b < 0 || c < 0 || d < 0) {
      return null;
    }
    int n = a + b + c + d;
    if (n == 0) {
      return 1.0;
    }
    double pObs = hypergeomPmf(a, a + b, a + c, n);
    double sum = 0;
    int maxA = Math.min(a + b, a + c);
    int minA = Math.max(0, (a + b) + (a + c) - n);
    for (int x = minA; x <= maxA; x++) {
      double px = hypergeomPmf(x, a + b, a + c, n);
      if (px <= pObs + 1e-15) {
        sum += px;
      }
    }
    return Math.min(1.0, sum);
  }

  private static double hypergeomPmf(int k, int k1, int n1, int n) {
    // P(X=k) for population n, K successes k1? Standard: draws n1 from n with K marked.
    // Table: row1 = k1 = a+b, col1 = n1 = a+c, total n, cell a = k.
    if (k < 0 || k > k1 || k > n1 || n1 > n || k1 > n) {
      return 0;
    }
    return Math.exp(
        logChoose(k1, k) + logChoose(n - k1, n1 - k) - logChoose(n, n1));
  }

  private static double logChoose(int n, int k) {
    if (k < 0 || k > n) {
      return Double.NEGATIVE_INFINITY;
    }
    k = Math.min(k, n - k);
    double sum = 0;
    for (int i = 1; i <= k; i++) {
      sum += Math.log(n - k + i) - Math.log(i);
    }
    return sum;
  }

  private static double standardNormalSf(double z) {
    // 1 - Φ(z) via erfc
    return 0.5 * erfc(z / Math.sqrt(2.0));
  }

  private static double erfc(double x) {
    // Abramowitz–Stegun approximation
    double z = Math.abs(x);
    double t = 1.0 / (1.0 + 0.5 * z);
    double ans = t * Math.exp(-z * z - 1.26551223
        + t * (1.00002368
        + t * (0.37409196
        + t * (0.09678418
        + t * (-0.18628806
        + t * (0.27886807
        + t * (-1.13520398
        + t * (1.48851587
        + t * (-0.82215223
        + t * 0.17087277)))))))));
    return x >= 0 ? ans : 2 - ans;
  }

  /** Two-sided Welch t-test p-value; null when undefined. */
  public static Double welchP(List<Double> a, List<Double> b) {
    if (a == null || b == null || a.size() < 2 || b.size() < 2) {
      return null;
    }
    double meanA = mean(a);
    double meanB = mean(b);
    double varA = variance(a, meanA);
    double varB = variance(b, meanB);
    int nA = a.size();
    int nB = b.size();
    double se2 = varA / nA + varB / nB;
    if (se2 <= 0 || Double.isNaN(se2)) {
      return meanA == meanB ? 1.0 : null;
    }
    double t = (meanA - meanB) / Math.sqrt(se2);
    double num = se2 * se2;
    double den = 0;
    if (nA > 1) {
      den += (varA / nA) * (varA / nA) / (nA - 1.0);
    }
    if (nB > 1) {
      den += (varB / nB) * (varB / nB) / (nB - 1.0);
    }
    double df = den > 0 ? num / den : (nA + nB - 2.0);
    if (df < 1) {
      df = 1;
    }
    return 2.0 * studentTCdf(-Math.abs(t), df);
  }

  static Double kruskalWallisP(List<List<Double>> groups) {
    List<double[]> ranked = new ArrayList<>();
    int n = 0;
    for (int g = 0; g < groups.size(); g++) {
      for (double v : groups.get(g)) {
        ranked.add(new double[] {v, g});
        n++;
      }
    }
    if (n < 3) {
      return null;
    }
    ranked.sort(Comparator.comparingDouble(a -> a[0]));
    double[] ranks = averageRanks(ranked);
    int k = groups.size();
    double[] rankSum = new double[k];
    int[] counts = new int[k];
    for (int i = 0; i < ranked.size(); i++) {
      int g = (int) ranked.get(i)[1];
      rankSum[g] += ranks[i];
      counts[g]++;
    }
    double h = 0;
    for (int g = 0; g < k; g++) {
      if (counts[g] == 0) {
        continue;
      }
      double rg = rankSum[g] / counts[g];
      h += counts[g] * (rg - (n + 1) / 2.0) * (rg - (n + 1) / 2.0);
    }
    h *= 12.0 / (n * (n + 1.0));
    int df = 0;
    for (int c : counts) {
      if (c > 0) {
        df++;
      }
    }
    df = Math.max(1, df - 1);
    return chiSquareSf(h, df);
  }

  private static double[] averageRanks(List<double[]> sorted) {
    int n = sorted.size();
    double[] ranks = new double[n];
    int i = 0;
    while (i < n) {
      int j = i;
      while (j + 1 < n && sorted.get(j + 1)[0] == sorted.get(i)[0]) {
        j++;
      }
      double avg = 0.5 * ((i + 1) + (j + 1));
      for (int k = i; k <= j; k++) {
        ranks[k] = avg;
      }
      i = j + 1;
    }
    return ranks;
  }

  private static double variance(List<Double> vals, double mean) {
    if (vals.size() < 2) {
      return 0;
    }
    double sum = 0;
    for (double v : vals) {
      double d = v - mean;
      sum += d * d;
    }
    return sum / (vals.size() - 1.0);
  }

  /** Approximate two-sided Student-t survival via regularized incomplete beta. */
  static double studentTCdf(double t, double df) {
    if (Double.isNaN(t) || df <= 0) {
      return Double.NaN;
    }
    double x = df / (df + t * t);
    double a = df / 2.0;
    double b = 0.5;
    double ib = regularizedIncompleteBeta(x, a, b);
    if (t >= 0) {
      return 1.0 - 0.5 * ib;
    }
    return 0.5 * ib;
  }

  /** Upper survival P(X > x) for chi-square with {@code df} degrees. */
  static double chiSquareSf(double x, int df) {
    if (x <= 0) {
      return 1.0;
    }
    if (df <= 0) {
      return Double.NaN;
    }
    return 1.0 - regularizedIncompleteGamma(df / 2.0, x / 2.0);
  }

  public static void applyBenjaminiHochberg(List<GroupStatResult.Row> rows) {
    List<Integer> indexed = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      Double p = rows.get(i).pValue();
      if (p != null && Double.isFinite(p)) {
        indexed.add(i);
      }
    }
    // Clamp numerical CDF noise so slightly negative / >1 p-values do not poison FDR.
    indexed.sort(Comparator.comparingDouble(i -> clampUnitInterval(rows.get(i).pValue())));
    int m = indexed.size();
    double[] q = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      q[i] = Double.NaN;
    }
    double prev = 1.0;
    for (int rank = m; rank >= 1; rank--) {
      int idx = indexed.get(rank - 1);
      double p = clampUnitInterval(rows.get(idx).pValue());
      double raw = p * m / (double) rank;
      prev = Math.min(prev, Math.min(1.0, raw));
      q[idx] = prev;
    }
    for (int i = 0; i < rows.size(); i++) {
      GroupStatResult.Row old = rows.get(i);
      Double qVal = Double.isNaN(q[i]) ? null : clampUnitInterval(q[i]);
      Double pVal = old.pValue() != null && Double.isFinite(old.pValue())
          ? clampUnitInterval(old.pValue())
          : old.pValue();
      rows.set(i, new GroupStatResult.Row(
          old.unitId(),
          old.geneName(),
          old.chromosome(),
          old.start1Based(),
          old.end1Based(),
          old.groups(),
          old.effectVsBaseline(),
          pVal,
          qVal,
          old.testName(),
          old.direction()));
    }
  }

  /** Clamp a probability into {@code [0, 1]} (numerical CDF tails). */
  static double clampUnitInterval(double v) {
    if (!Double.isFinite(v)) {
      return Double.NaN;
    }
    if (v < 0.0) {
      return 0.0;
    }
    if (v > 1.0) {
      return 1.0;
    }
    return v;
  }

  /**
   * Regularized incomplete beta I_x(a,b) via Lentz continued fraction
   * (Numerical Recipes {@code betacf} ordering: seed then even/odd pairs for m=1..).
   */
  private static double regularizedIncompleteBeta(double x, double a, double b) {
    if (x <= 0) {
      return 0;
    }
    if (x >= 1) {
      return 1;
    }
    double lnBeta = logGamma(a) + logGamma(b) - logGamma(a + b);
    boolean flip = x > (a + 1) / (a + b + 2);
    double xx = flip ? 1 - x : x;
    double aa = flip ? b : a;
    double bb = flip ? a : b;
    double front = Math.exp(Math.log(xx) * aa + Math.log(1 - xx) * bb - lnBeta) / aa;
    // Seed with first continued-fraction coefficient; loop must NOT repeat m=0 odd term.
    final double tiny = 1e-30;
    double c = 1.0;
    double d = 1.0 - (aa + bb) * xx / (aa + 1.0);
    if (Math.abs(d) < tiny) {
      d = tiny;
    }
    d = 1.0 / d;
    double f = d;
    for (int m = 1; m <= 200; m++) {
      int m2 = 2 * m;
      // Even step
      double numerator = m * (bb - m) * xx / ((aa + m2 - 1.0) * (aa + m2));
      d = 1.0 + numerator * d;
      if (Math.abs(d) < tiny) {
        d = tiny;
      }
      c = 1.0 + numerator / c;
      if (Math.abs(c) < tiny) {
        c = tiny;
      }
      d = 1.0 / d;
      f *= d * c;
      // Odd step
      numerator = -((aa + m) * (aa + bb + m) * xx) / ((aa + m2) * (aa + m2 + 1.0));
      d = 1.0 + numerator * d;
      if (Math.abs(d) < tiny) {
        d = tiny;
      }
      c = 1.0 + numerator / c;
      if (Math.abs(c) < tiny) {
        c = tiny;
      }
      d = 1.0 / d;
      double delta = d * c;
      f *= delta;
      if (Math.abs(delta - 1.0) < 1e-10) {
        break;
      }
    }
    double result = front * f;
    return flip ? 1.0 - result : result;
  }

  /**
   * Lower regularized gamma P(s,x). Series for small x; Lentz continued fraction for Q
   * when {@code x >= s + 1}, returning {@code 1 - Q}.
   */
  private static double regularizedIncompleteGamma(double s, double x) {
    if (x <= 0) {
      return 0;
    }
    if (x < s + 1) {
      double sum = 1 / s;
      double term = sum;
      for (int n = 1; n <= 200; n++) {
        term *= x / (s + n);
        sum += term;
        if (term < sum * 1e-12) {
          break;
        }
      }
      return sum * Math.exp(-x + s * Math.log(x) - logGamma(s));
    }
    // Lentz CF for upper incomplete gamma Q(s,x) / (e^{-x} x^s / Γ(s))
    final double tiny = 1e-300;
    double b = x + 1.0 - s;
    double c = 1.0 / tiny;
    double d = 1.0 / b;
    double h = d;
    for (int i = 1; i <= 200; i++) {
      double an = -i * (i - s);
      b += 2.0;
      d = an * d + b;
      if (Math.abs(d) < tiny) {
        d = tiny;
      }
      c = b + an / c;
      if (Math.abs(c) < tiny) {
        c = tiny;
      }
      d = 1.0 / d;
      double del = d * c;
      h *= del;
      if (Math.abs(del - 1.0) < 1e-12) {
        break;
      }
    }
    double q = Math.exp(-x + s * Math.log(x) - logGamma(s)) * h;
    return 1.0 - q;
  }

  private static double logGamma(double z) {
    // Lanczos approximation
    double[] p = {
        676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012,
        9.9843696540789804e-6, 1.5056327351493116e-7
    };
    if (z < 0.5) {
      return Math.log(Math.PI / Math.sin(Math.PI * z)) - logGamma(1 - z);
    }
    z -= 1;
    double x = 0.99999999999980993;
    for (int i = 0; i < p.length; i++) {
      x += p[i] / (z + i + 1);
    }
    double t = z + p.length - 0.5;
    return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(x);
  }

  private record GroupBucket(int groupId, String name, List<SampleTrack> tracks) {}

  private record UnitScratch(
      String unitId,
      FeatureValueMatrix.Locus locus,
      List<GroupStatResult.GroupSummary> summaries,
      List<List<Double>> series,
      int baselineIdx,
      int otherIdx,
      Double effect,
      String direction) {}

  public record PooledVar(double variance, double df) {}

  public record ModeratedPrior(double s0sq, double d0) {}
}
