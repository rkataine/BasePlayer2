package org.baseplayer.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;

/**
 * Pure-Java group statistics for any {@link FeatureValueMatrix} (Signal, mutations, CNV, …).
 * Selectable tests via {@link GroupStatTest}; effect vs optional baseline uses log2 fold of means.
 */
public final class GroupStatEngine {

  private static final double LOG2 = Math.log(2.0);
  private static final double PSEUDO = 1e-6;

  private GroupStatEngine() {}

  public static GroupStatResult compute(
      AnalysisMode mode,
      FeatureValueMatrix matrix,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId) {
    return compute(mode, matrix, registry, groupIds, baselineGroupId, GroupStatTest.AUTO, null);
  }

  public static GroupStatResult compute(
      AnalysisMode mode,
      FeatureValueMatrix matrix,
      SampleRegistry registry,
      List<Integer> groupIds,
      Integer baselineGroupId,
      GroupStatTest test,
      String scoreLabel) {
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

    List<GroupStatResult.Row> rows = new ArrayList<>();
    boolean twoGroup = buckets.size() == 2;

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
      for (List<Double> vals : series) {
        if (vals.size() >= 1) {
          usableGroups++;
        }
      }
      if (usableGroups < 2) {
        continue;
      }

      Double pValue = pValueFor(concrete, series, summaries, twoGroup);

      double baselineMean = Double.NaN;
      double otherMean = Double.NaN;
      for (int i = 0; i < buckets.size(); i++) {
        if (buckets.get(i).groupId == baselineId) {
          baselineMean = mean(series.get(i));
        } else if (Double.isNaN(otherMean)) {
          otherMean = mean(series.get(i));
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
      String direction = "similar";
      if (!Double.isNaN(baselineMean) && !Double.isNaN(otherMean)) {
        effect = log2Fold(otherMean, baselineMean);
      }
      if (twoGroup) {
        if (effect != null && !Double.isNaN(effect)) {
          if (effect > 0.05) {
            direction = "up";
          } else if (effect < -0.05) {
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
      rows.add(new GroupStatResult.Row(
          unitId,
          unitId,
          locus != null ? locus.chromosome() : null,
          locus != null ? locus.start1Based() : 0L,
          locus != null ? locus.end1Based() : 0L,
          summaries,
          effect,
          pValue,
          null,
          testName,
          direction));
    }

    applyBenjaminiHochberg(rows);
    rows.sort(Comparator
        .comparing((GroupStatResult.Row r) -> r.qValue() != null ? r.qValue() : Double.POSITIVE_INFINITY)
        .thenComparing(r -> r.pValue() != null ? r.pValue() : Double.POSITIVE_INFINITY));

    String missingPolicy = matrix.getMissingDefault() != null
        ? "missing=" + formatNum(matrix.getMissingDefault())
        : "observed only";
    String score = scoreLabel != null && !scoreLabel.isBlank() ? scoreLabel : mode.name();
    String methodStrip = String.format(
        Locale.ROOT,
        "Score: %s · Missing: %s · Test: %s · FDR: Benjamini–Hochberg · Δ: |max−min| group means",
        score,
        missingPolicy,
        testName);
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
      case WELCH -> twoGroup ? welchP(series.get(0), series.get(1)) : kruskalWallisP(series);
      case RANK -> twoGroup ? mannWhitneyP(series.get(0), series.get(1)) : kruskalWallisP(series);
      case FISHER -> twoGroup ? fisherFromSummaries(summaries.get(0), summaries.get(1)) : kruskalWallisP(series);
      case AUTO -> twoGroup ? welchP(series.get(0), series.get(1)) : kruskalWallisP(series);
    };
  }

  static GroupStatResult.GroupSummary summarizeGroup(
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

  static double mean(List<Double> vals) {
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

  /** Two-sided Mann–Whitney U (normal approximation with tie correction). */
  static Double mannWhitneyP(List<Double> a, List<Double> b) {
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
  static Double welchP(List<Double> a, List<Double> b) {
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

  static void applyBenjaminiHochberg(List<GroupStatResult.Row> rows) {
    List<Integer> indexed = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).pValue() != null && !Double.isNaN(rows.get(i).pValue())) {
        indexed.add(i);
      }
    }
    indexed.sort(Comparator.comparingDouble(i -> rows.get(i).pValue()));
    int m = indexed.size();
    double[] q = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      q[i] = Double.NaN;
    }
    double prev = 1.0;
    for (int rank = m; rank >= 1; rank--) {
      int idx = indexed.get(rank - 1);
      double raw = rows.get(idx).pValue() * m / rank;
      prev = Math.min(prev, Math.min(1.0, raw));
      q[idx] = prev;
    }
    for (int i = 0; i < rows.size(); i++) {
      GroupStatResult.Row old = rows.get(i);
      Double qVal = Double.isNaN(q[i]) ? null : q[i];
      rows.set(i, new GroupStatResult.Row(
          old.unitId(),
          old.geneName(),
          old.chromosome(),
          old.start1Based(),
          old.end1Based(),
          old.groups(),
          old.effectVsBaseline(),
          old.pValue(),
          qVal,
          old.testName(),
          old.direction()));
    }
  }

  /** Continued-fraction regularized incomplete beta I_x(a,b). */
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
    double f = 1;
    double c = 1;
    double d = 1 - (aa + bb) * xx / (aa + 1);
    if (Math.abs(d) < 1e-30) {
      d = 1e-30;
    }
    d = 1 / d;
    f = d;
    for (int i = 1; i <= 200; i++) {
      int m = i / 2;
      double numerator;
      if ((i & 1) == 0) {
        numerator = m * (bb - m) * xx / ((aa + 2 * m - 1) * (aa + 2 * m));
      } else {
        numerator = -((aa + m) * (aa + bb + m) * xx) / ((aa + 2 * m) * (aa + 2 * m + 1));
      }
      d = 1 + numerator * d;
      if (Math.abs(d) < 1e-30) {
        d = 1e-30;
      }
      c = 1 + numerator / c;
      if (Math.abs(c) < 1e-30) {
        c = 1e-30;
      }
      d = 1 / d;
      double delta = c * d;
      f *= delta;
      if (Math.abs(delta - 1) < 1e-10) {
        break;
      }
    }
    double result = front * f;
    return flip ? 1 - result : result;
  }

  /** Lower regularized gamma P(s,x) via series / continued fraction. */
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
    double a0 = 1;
    double a1 = x;
    double b0 = 0;
    double b1 = 1;
    double f = 1;
    for (int n = 1; n <= 200; n++) {
      double an = n * (s - n);
      double bNext = (x + 2 * n - 1 - s) * a1 - an * a0;
      double aNext = (x + 2 * n - 1 - s) * b1 - an * b0;
      a0 = a1;
      b0 = b1;
      a1 = bNext;
      b1 = aNext;
      if (aNext != 0) {
        double next = bNext / aNext;
        if (Math.abs(f - next) < Math.abs(next) * 1e-12) {
          f = next;
          break;
        }
        f = next;
      }
    }
    double cf = Math.exp(-x + s * Math.log(x) - logGamma(s)) * f;
    return 1 - cf;
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
}
