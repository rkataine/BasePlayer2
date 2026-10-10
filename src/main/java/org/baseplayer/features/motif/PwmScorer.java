package org.baseplayer.features.motif;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplified MOODS-style PWM scoring: log-odds vs background, reverse complement,
 * Staden DP p-value threshold, and naive sliding-window scan.
 */
public final class PwmScorer {

  public static final double DEFAULT_PVALUE = 1e-4;
  public static final double DEFAULT_PSEUDOCOUNT = 0.0001;
  private static final double[] FLAT_BG = {0.25, 0.25, 0.25, 0.25};
  /** Quantization granularity for threshold DP (nat-log score units). */
  private static final double SCORE_GRANULARITY = 0.05;

  public record Hit(
      String motifId,
      String motifName,
      int start1Based,
      int end1Based,
      char strand,
      double score) {
  }

  /** Prepared PWM ready for scanning. */
  public static final class PreparedMotif {
    final MotifMatrix matrix;
    final double[][] logOdds; // [4][len]
    final double[][] logOddsRc;
    final double threshold;

    PreparedMotif(
        MotifMatrix matrix, double[][] logOdds, double[][] logOddsRc, double threshold) {
      this.matrix = matrix;
      this.logOdds = logOdds;
      this.logOddsRc = logOddsRc;
      this.threshold = threshold;
    }

    public MotifMatrix matrix() {
      return matrix;
    }

    public double threshold() {
      return threshold;
    }
  }

  private PwmScorer() {}

  public static PreparedMotif prepare(MotifMatrix matrix, double pvalue) {
    return prepare(matrix, pvalue, DEFAULT_PSEUDOCOUNT, FLAT_BG);
  }

  public static PreparedMotif prepare(
      MotifMatrix matrix, double pvalue, double pseudocount, double[] bg) {
    double[][] lo = toLogOdds(matrix, pseudocount, bg);
    MotifMatrix rc = matrix.reverseComplement();
    double[][] loRc = toLogOdds(rc, pseudocount, bg);
    double thr = thresholdFromP(lo, bg, pvalue);
    return new PreparedMotif(matrix, lo, loRc, thr);
  }

  public static List<PreparedMotif> prepareAll(List<MotifMatrix> matrices, double pvalue) {
    List<PreparedMotif> out = new ArrayList<>(matrices.size());
    for (MotifMatrix m : matrices) {
      out.add(prepare(m, pvalue));
    }
    return out;
  }

  /**
   * Scan {@code sequence} (ACGT, case-insensitive). Hit coordinates are 1-based
   * inclusive relative to {@code regionStart1Based} (first base of {@code sequence}).
   */
  public static List<Hit> scan(
      String sequence,
      int regionStart1Based,
      List<PreparedMotif> motifs) {
    if (sequence == null || sequence.isEmpty() || motifs == null || motifs.isEmpty()) {
      return List.of();
    }
    int n = sequence.length();
    byte[] bases = encode(sequence);
    List<Hit> hits = new ArrayList<>();
    for (PreparedMotif motif : motifs) {
      int len = motif.matrix.length();
      if (len > n) {
        continue;
      }
      for (int i = 0; i <= n - len; i++) {
        double fwd = scoreWindow(bases, i, motif.logOdds);
        if (fwd >= motif.threshold) {
          int start = regionStart1Based + i;
          hits.add(new Hit(
              motif.matrix.id(),
              motif.matrix.name(),
              start,
              start + len - 1,
              '+',
              fwd));
        }
        double rev = scoreWindow(bases, i, motif.logOddsRc);
        if (rev >= motif.threshold) {
          int start = regionStart1Based + i;
          hits.add(new Hit(
              motif.matrix.id(),
              motif.matrix.name(),
              start,
              start + len - 1,
              '-',
              rev));
        }
      }
    }
    hits.sort((a, b) -> {
      int c = Integer.compare(a.start1Based, b.start1Based);
      if (c != 0) {
        return c;
      }
      return Double.compare(b.score, a.score);
    });
    return hits;
  }

  static double[][] toLogOdds(MotifMatrix matrix, double pseudocount, double[] bg) {
    int len = matrix.length();
    double[][] lo = new double[4][len];
    for (int i = 0; i < len; i++) {
      double colSum = 0;
      double[] smoothed = new double[4];
      for (int b = 0; b < 4; b++) {
        smoothed[b] = matrix.count(b, i) + pseudocount * bg[b];
        colSum += smoothed[b];
      }
      for (int b = 0; b < 4; b++) {
        double p = smoothed[b] / colSum;
        lo[b][i] = Math.log(p / bg[b]);
      }
    }
    return lo;
  }

  /**
   * Staden / Wu-style DP: find minimum score s such that P(score ≥ s) ≤ p under
   * independent background draws (quantized).
   */
  static double thresholdFromP(double[][] logOdds, double[] bg, double pvalue) {
    int len = logOdds[0].length;
    double minScore = 0;
    double maxScore = 0;
    for (int i = 0; i < len; i++) {
      double colMin = Double.POSITIVE_INFINITY;
      double colMax = Double.NEGATIVE_INFINITY;
      for (int b = 0; b < 4; b++) {
        colMin = Math.min(colMin, logOdds[b][i]);
        colMax = Math.max(colMax, logOdds[b][i]);
      }
      minScore += colMin;
      maxScore += colMax;
    }
    int offset = (int) Math.floor(minScore / SCORE_GRANULARITY);
    int maxIdx = (int) Math.ceil(maxScore / SCORE_GRANULARITY) - offset;
    if (maxIdx < 1) {
      return maxScore;
    }
    // Before position 0, only score 0 has mass.
    double[] dist = new double[maxIdx + 1];
    int zeroBin = -offset;
    if (zeroBin < 0 || zeroBin > maxIdx) {
      return maxScore * 0.7;
    }
    dist[zeroBin] = 1.0;

    for (int pos = 0; pos < len; pos++) {
      double[] next = new double[maxIdx + 1];
      for (int s = 0; s <= maxIdx; s++) {
        double mass = dist[s];
        if (mass <= 0) {
          continue;
        }
        for (int b = 0; b < 4; b++) {
          int delta = (int) Math.round(logOdds[b][pos] / SCORE_GRANULARITY);
          int ns = s + delta;
          if (ns < 0) {
            ns = 0;
          } else if (ns > maxIdx) {
            ns = maxIdx;
          }
          next[ns] += mass * bg[b];
        }
      }
      dist = next;
    }

    double tail = 0;
    for (int s = maxIdx; s >= 0; s--) {
      tail += dist[s];
      if (tail >= pvalue) {
        return (s + offset) * SCORE_GRANULARITY;
      }
    }
    return minScore;
  }

  private static double scoreWindow(byte[] bases, int start, double[][] logOdds) {
    int len = logOdds[0].length;
    double score = 0;
    for (int i = 0; i < len; i++) {
      int b = bases[start + i];
      if (b < 0) {
        return Double.NEGATIVE_INFINITY;
      }
      score += logOdds[b][i];
    }
    return score;
  }

  private static byte[] encode(String sequence) {
    byte[] out = new byte[sequence.length()];
    for (int i = 0; i < sequence.length(); i++) {
      out[i] = switch (sequence.charAt(i)) {
        case 'A', 'a' -> 0;
        case 'C', 'c' -> 1;
        case 'G', 'g' -> 2;
        case 'T', 't' -> 3;
        default -> -1;
      };
    }
    return out;
  }
}
