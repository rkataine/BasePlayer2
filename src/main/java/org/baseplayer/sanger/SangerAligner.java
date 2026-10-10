package org.baseplayer.sanger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import org.baseplayer.genome.ReferenceGenomeService;

/**
 * Seeds + affine-gap local alignment of an AB1 base-call sequence to a reference window.
 * Port of the visual-alignment path from the classic BasePlayer {@code SangerData}.
 */
public final class SangerAligner {

  private static final int MATCH = 5;
  private static final int MISMATCH = -4;
  private static final int GAP_OPEN = -12;
  private static final int GAP_EXTEND = -2;
  private static final int NEG_INF = -999_999_999;
  /** Extra bases around the current view when searching for a seed hit. */
  private static final int VIEW_PAD_BP = 100_000;

  private SangerAligner() {}

  /**
   * Align {@code trace} against a padded window around the current view, if a
   * reference genome is loaded and the trace is not already aligned to {@code chromosome}.
   *
   * @return true if an alignment was found (or already present)
   */
  public static boolean alignToView(
      SangerTrace trace,
      ReferenceGenomeService refService,
      String chromosome,
      long viewStart,
      long viewEnd) {
    if (trace == null || refService == null || !refService.hasGenome() || chromosome == null) {
      return false;
    }
    if (trace.isAligned() && chromosome.equals(trace.getChromosome())) {
      return true;
    }

    long chromLen;
    try {
      chromLen = refService.getChromosomeLength(chromosome);
    } catch (RuntimeException e) {
      return false;
    }
    if (chromLen <= 0) {
      return false;
    }

    long pad = Math.max(VIEW_PAD_BP, Math.max(1, viewEnd - viewStart));
    long start = Math.max(1, viewStart - pad);
    long end = Math.min(chromLen, viewEnd + pad);
    if (end - start < 40) {
      return false;
    }

    String bases;
    try {
      bases = refService.getBases(chromosome, (int) start, (int) end);
    } catch (RuntimeException e) {
      return false;
    }
    if (bases == null || bases.length() < 20) {
      return false;
    }
    return alignToReference(trace, chromosome, bases, (int) start);
  }

  /**
   * Align {@code trace} to {@code reference} starting at genomic coordinate
   * {@code refStartPos} (1-based inclusive). On success updates the trace alignment
   * state; on failure leaves it unchanged.
   *
   * @return true if an alignment was found
   */
  public static boolean alignToReference(SangerTrace trace, String chromosome, String reference, int refStartPos) {
    if (trace == null || chromosome == null || reference == null || reference.length() < 20) {
      return false;
    }
    if (trace.isAligned() && chromosome.equals(trace.getChromosome())) {
      return true;
    }

    Ab1Reader reader = trace.getReader();
    String abiSeq = reader.getSequence();
    if (abiSeq == null || abiSeq.length() < 20) {
      return false;
    }

    String refString = reference.toUpperCase();
    abiSeq = abiSeq.toUpperCase();

    int seedLen = Math.min(15, abiSeq.length());
    List<Integer> candidateStarts = getCandidateSeedStarts(reader, abiSeq, seedLen);

    int bestScore = Integer.MIN_VALUE;
    int bestOffset = -1;
    boolean bestReversed = false;
    String bestTarget = null;
    int bestTargetStart = -1;

    for (int abiStart : candidateStarts) {
      String seed = abiSeq.substring(abiStart, abiStart + seedLen);

      int bestFwdIndex = -1;
      int minFwdMismatches = Integer.MAX_VALUE;
      for (int i = 0; i <= refString.length() - seedLen; i++) {
        int mismatches = 0;
        for (int j = 0; j < seedLen; j++) {
          if (refString.charAt(i + j) != seed.charAt(j)) {
            mismatches++;
          }
          if (mismatches > minFwdMismatches) {
            break;
          }
        }
        if (mismatches < minFwdMismatches) {
          minFwdMismatches = mismatches;
          bestFwdIndex = i;
        }
      }

      if (minFwdMismatches <= 5 && bestFwdIndex >= 0) {
        int start = Math.max(0, bestFwdIndex - abiStart - 100);
        int end = Math.min(refString.length(), bestFwdIndex - abiStart + abiSeq.length() + 100);
        String target = refString.substring(start, end);
        int score = alignmentScore(abiSeq, target);
        if (score > bestScore) {
          bestScore = score;
          bestOffset = refStartPos + bestFwdIndex - abiStart;
          bestReversed = false;
          bestTarget = target;
          bestTargetStart = refStartPos + start;
        }
      }

      String revSeed = reverseComplement(seed).toUpperCase();
      int bestRevIndex = -1;
      int minRevMismatches = Integer.MAX_VALUE;
      for (int i = 0; i <= refString.length() - seedLen; i++) {
        int mismatches = 0;
        for (int j = 0; j < seedLen; j++) {
          if (refString.charAt(i + j) != revSeed.charAt(j)) {
            mismatches++;
          }
          if (mismatches > minRevMismatches) {
            break;
          }
        }
        if (mismatches < minRevMismatches) {
          minRevMismatches = mismatches;
          bestRevIndex = i;
        }
      }

      if (minRevMismatches <= 5 && bestRevIndex >= 0) {
        int start = Math.max(0, bestRevIndex - (abiSeq.length() - abiStart) - 100);
        int end = Math.min(refString.length(), bestRevIndex + abiStart + 100);
        String target = refString.substring(start, end);
        String targetRc = reverseComplement(target).toUpperCase();
        int score = alignmentScore(abiSeq, targetRc);
        if (score > bestScore) {
          bestScore = score;
          bestOffset = refStartPos + bestRevIndex + abiStart + seedLen - 1;
          bestReversed = true;
          bestTarget = targetRc;
          bestTargetStart = refStartPos + start;
        }
      }
    }

    if (bestScore == Integer.MIN_VALUE || bestTarget == null) {
      return false;
    }

    double[] mapping = alignAndMap(abiSeq, bestTarget, bestTargetStart, bestReversed);
    if (mapping == null) {
      mapping = new double[abiSeq.length()];
      for (int k = 0; k < abiSeq.length(); k++) {
        mapping[k] = bestReversed ? bestOffset - k : bestOffset + k;
      }
    }

    trace.setAlignment(chromosome, bestOffset, bestReversed, mapping);
    return true;
  }

  private static List<Integer> getCandidateSeedStarts(Ab1Reader reader, String abiSeq, int seedLen) {
    List<Integer> candidates = new ArrayList<>();
    int startBuffer = 20;
    int endBuffer = 20;

    if (abiSeq.length() < startBuffer + endBuffer + seedLen) {
      int mid = abiSeq.length() / 2;
      candidates.add(Math.max(0, mid - seedLen / 2));
      return candidates;
    }

    TreeMap<Double, Integer> scoreMap = new TreeMap<>(Collections.reverseOrder());
    int[] traceA = reader.getTraceA();
    int[] traceC = reader.getTraceC();
    int[] traceG = reader.getTraceG();
    int[] traceT = reader.getTraceT();
    List<Integer> baseCalls = reader.getBaseCalls();
    boolean hasTraces = traceA != null && baseCalls != null;

    int stride = Math.max(1, seedLen / 2);
    for (int i = startBuffer; i <= abiSeq.length() - endBuffer - seedLen; i += stride) {
      String candidate = abiSeq.substring(i, i + seedLen);
      if (candidate.indexOf('N') >= 0) {
        continue;
      }
      double score = 0;
      if (hasTraces) {
        for (int k = 0; k < seedLen; k++) {
          int baseIndex = i + k;
          if (baseIndex >= baseCalls.size()) {
            break;
          }
          int traceIdx = baseCalls.get(baseIndex);
          if (traceIdx < 0 || traceIdx >= traceA.length) {
            break;
          }
          int a = traceA[traceIdx];
          int c = traceC[traceIdx];
          int g = traceG[traceIdx];
          int t = traceT[traceIdx];
          int max = Math.max(Math.max(a, c), Math.max(g, t));
          int sum = a + c + g + t;
          int noise = sum - max;
          if (noise == 0) {
            noise = 1;
          }
          score += (double) max / noise;
        }
      } else {
        score = 1.0;
      }
      scoreMap.put(score, i);
    }

    int count = 0;
    for (Integer start : scoreMap.values()) {
      candidates.add(start);
      if (++count >= 5) {
        break;
      }
    }
    if (candidates.isEmpty()) {
      int mid = abiSeq.length() / 2;
      candidates.add(Math.max(0, mid - seedLen / 2));
    }
    return candidates;
  }

  private static int alignmentScore(String query, String target) {
    int n = query.length();
    int m = target.length();
    int[][] mMat = new int[n + 1][m + 1];
    int[][] ix = new int[n + 1][m + 1];
    int[][] iy = new int[n + 1][m + 1];

    for (int i = 0; i <= n; i++) {
      mMat[i][0] = (i == 0) ? 0 : NEG_INF;
      ix[i][0] = (i == 0) ? NEG_INF : GAP_OPEN + (i - 1) * GAP_EXTEND;
      iy[i][0] = NEG_INF;
    }
    for (int j = 0; j <= m; j++) {
      mMat[0][j] = 0;
      ix[0][j] = NEG_INF;
      iy[0][j] = NEG_INF;
    }

    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        int score = (query.charAt(i - 1) == target.charAt(j - 1)) ? MATCH : MISMATCH;
        mMat[i][j] = Math.max(mMat[i - 1][j - 1] + score,
            Math.max(ix[i - 1][j - 1] + score, iy[i - 1][j - 1] + score));
        ix[i][j] = Math.max(mMat[i - 1][j] + GAP_OPEN, ix[i - 1][j] + GAP_EXTEND);
        iy[i][j] = Math.max(mMat[i][j - 1] + GAP_OPEN, iy[i][j - 1] + GAP_EXTEND);
      }
    }

    int maxScore = NEG_INF;
    for (int j = 1; j <= m; j++) {
      maxScore = Math.max(maxScore, Math.max(mMat[n][j], Math.max(ix[n][j], iy[n][j])));
    }
    return maxScore;
  }

  private static double[] alignAndMap(String query, String target, int targetStartGenomicPos, boolean targetIsRc) {
    int n = query.length();
    int m = target.length();

    int[][] mMat = new int[n + 1][m + 1];
    int[][] ix = new int[n + 1][m + 1];
    int[][] iy = new int[n + 1][m + 1];
    byte[][] traceM = new byte[n + 1][m + 1];
    byte[][] traceX = new byte[n + 1][m + 1];
    byte[][] traceY = new byte[n + 1][m + 1];

    for (int i = 0; i <= n; i++) {
      mMat[i][0] = (i == 0) ? 0 : NEG_INF;
      ix[i][0] = (i == 0) ? NEG_INF : GAP_OPEN + (i - 1) * GAP_EXTEND;
      iy[i][0] = NEG_INF;
      if (i > 0) {
        traceX[i][0] = 2;
      }
    }
    for (int j = 0; j <= m; j++) {
      mMat[0][j] = 0;
      ix[0][j] = NEG_INF;
      iy[0][j] = NEG_INF;
    }

    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        int score = (query.charAt(i - 1) == target.charAt(j - 1)) ? MATCH : MISMATCH;

        int mFromM = mMat[i - 1][j - 1] + score;
        int mFromX = ix[i - 1][j - 1] + score;
        int mFromY = iy[i - 1][j - 1] + score;
        int maxM = mFromM;
        byte tM = 1;
        if (mFromX > maxM) {
          maxM = mFromX;
          tM = 2;
        }
        if (mFromY > maxM) {
          maxM = mFromY;
          tM = 3;
        }
        mMat[i][j] = maxM;
        traceM[i][j] = tM;

        int xFromM = mMat[i - 1][j] + GAP_OPEN;
        int xFromX = ix[i - 1][j] + GAP_EXTEND;
        if (xFromM >= xFromX) {
          ix[i][j] = xFromM;
          traceX[i][j] = 1;
        } else {
          ix[i][j] = xFromX;
          traceX[i][j] = 2;
        }

        int yFromM = mMat[i][j - 1] + GAP_OPEN;
        int yFromY = iy[i][j - 1] + GAP_EXTEND;
        if (yFromM >= yFromY) {
          iy[i][j] = yFromM;
          traceY[i][j] = 1;
        } else {
          iy[i][j] = yFromY;
          traceY[i][j] = 3;
        }
      }
    }

    int maxScore = NEG_INF;
    int maxJ = m;
    int state = 1;
    for (int j = 1; j <= m; j++) {
      if (mMat[n][j] > maxScore) {
        maxScore = mMat[n][j];
        maxJ = j;
        state = 1;
      }
      if (ix[n][j] > maxScore) {
        maxScore = ix[n][j];
        maxJ = j;
        state = 2;
      }
      if (iy[n][j] > maxScore) {
        maxScore = iy[n][j];
        maxJ = j;
        state = 3;
      }
    }

    double[] mapping = new double[n];
    Arrays.fill(mapping, -1.0);
    int i = n;
    int j = maxJ;
    while (i > 0) {
      if (state == 1) {
        mapping[i - 1] = genomicPos(j - 1, target.length(), targetStartGenomicPos, targetIsRc);
        state = traceM[i][j];
        i--;
        j--;
      } else if (state == 2) {
        state = traceX[i][j];
        i--;
      } else {
        state = traceY[i][j];
        j--;
      }
    }

    for (int k = 0; k < n; k++) {
      if (mapping[k] != -1.0) {
        continue;
      }
      int start = k;
      while (k < n && mapping[k] == -1.0) {
        k++;
      }
      int end = k;
      double startPos;
      double endPos;
      if (start == 0) {
        startPos = end < n ? mapping[end] - (targetIsRc ? -1 : 1) : 0;
      } else {
        startPos = mapping[start - 1];
      }
      if (end == n) {
        endPos = start > 0 ? mapping[start - 1] + (targetIsRc ? -1 : 1) : 0;
      } else {
        endPos = mapping[end];
      }
      int gapSize = end - start;
      double step = (endPos - startPos) / (gapSize + 1);
      for (int p = 0; p < gapSize; p++) {
        mapping[start + p] = startPos + step * (p + 1);
      }
    }
    return mapping;
  }

  private static double genomicPos(int targetIndex, int targetLen, int targetStartGenomicPos, boolean targetIsRc) {
    if (!targetIsRc) {
      return targetStartGenomicPos + targetIndex;
    }
    return targetStartGenomicPos + (targetLen - 1 - targetIndex);
  }

  /** Reverse-complement including common IUPAC ambiguity codes. */
  static String reverseComplement(String sequence) {
    StringBuilder sb = new StringBuilder(sequence.length());
    for (int i = sequence.length() - 1; i >= 0; i--) {
      sb.append(complement(sequence.charAt(i)));
    }
    return sb.toString();
  }

  static char complement(char c) {
    return switch (Character.toUpperCase(c)) {
      case 'A' -> 'T';
      case 'T' -> 'A';
      case 'C' -> 'G';
      case 'G' -> 'C';
      case 'R' -> 'Y';
      case 'Y' -> 'R';
      case 'S' -> 'S';
      case 'W' -> 'W';
      case 'K' -> 'M';
      case 'M' -> 'K';
      case 'B' -> 'V';
      case 'D' -> 'H';
      case 'H' -> 'D';
      case 'V' -> 'B';
      default -> c;
    };
  }
}
