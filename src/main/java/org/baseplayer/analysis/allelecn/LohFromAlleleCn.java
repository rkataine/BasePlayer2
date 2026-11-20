package org.baseplayer.analysis.allelecn;

/**
 * Shared LOH classification from allele-specific copy numbers.
 * Independent of file format (ASCAT, FACETS, …).
 */
public final class LohFromAlleleCn {

  public enum ScoreMode {
    /** 1 = LOH, 0 = not LOH. */
    BINARY,
    /** {@code 1 - minor/(minor+major)} when classifiable; 0 when not LOH. */
    CONTINUOUS
  }

  private final double epsilon;
  private final double expectedPloidy;
  private final double ploidyTolerance;
  private final ScoreMode scoreMode;
  private final boolean requireCopyNeutral;

  public LohFromAlleleCn() {
    this(0.0, 2.0, 0.25, ScoreMode.BINARY, false);
  }

  public LohFromAlleleCn(
      double epsilon,
      double expectedPloidy,
      double ploidyTolerance,
      ScoreMode scoreMode,
      boolean requireCopyNeutral) {
    this.epsilon = Math.max(0.0, epsilon);
    this.expectedPloidy = expectedPloidy;
    this.ploidyTolerance = Math.max(0.0, ploidyTolerance);
    this.scoreMode = scoreMode != null ? scoreMode : ScoreMode.BINARY;
    this.requireCopyNeutral = requireCopyNeutral;
  }

  public static LohFromAlleleCn binary() {
    return new LohFromAlleleCn();
  }

  public static LohFromAlleleCn continuous() {
    return new LohFromAlleleCn(0.0, 2.0, 0.25, ScoreMode.CONTINUOUS, false);
  }

  public ScoreMode scoreMode() {
    return scoreMode;
  }

  /**
   * LOH when minor allele CN is lost (≤ epsilon) and major allele CN remains (> epsilon).
   * Optionally require copy-neutral total ≈ expected ploidy.
   */
  public boolean isLoh(AlleleCnSegment segment) {
    if (segment == null) {
      return false;
    }
    if (!(segment.minorCn() <= epsilon) || !(segment.majorCn() > epsilon)) {
      return false;
    }
    if (!requireCopyNeutral) {
      return true;
    }
    double total = segment.totalCn() != null
        ? segment.totalCn()
        : segment.totalAlleleCn();
    return Math.abs(total - expectedPloidy) <= ploidyTolerance;
  }

  /** Analysis score for a segment; {@code NaN} when the segment should be ignored. */
  public double score(AlleleCnSegment segment) {
    if (segment == null) {
      return Double.NaN;
    }
    // Homozygous-null / no-call: both alleles ~0
    if (segment.minorCn() <= epsilon && segment.majorCn() <= epsilon) {
      return Double.NaN;
    }
    if (!isLoh(segment)) {
      return scoreMode == ScoreMode.BINARY ? 0.0 : 0.0;
    }
    if (scoreMode == ScoreMode.BINARY) {
      return 1.0;
    }
    double sum = segment.totalAlleleCn();
    if (sum <= epsilon) {
      return Double.NaN;
    }
    double intensity = 1.0 - (segment.minorCn() / sum);
    if (intensity < 0.0) {
      return 0.0;
    }
    if (intensity > 1.0) {
      return 1.0;
    }
    return intensity;
  }
}
