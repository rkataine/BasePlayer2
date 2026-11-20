package org.baseplayer.analysis;

/**
 * User choices for a Signal comparison run.
 */
public final class SignalSourceSpec {

  public static final int DEFAULT_WINDOW_BP = 10_000;
  public static final int MAX_WINDOW_UNITS = 250_000;

  private SignalValueKind valueKind = SignalValueKind.BED_SCORES;
  private ComparisonUnit unit = ComparisonUnit.GENE;
  private GenomicScope scope = GenomicScope.ALL_CHROMS;
  private ValueAggregation aggregation = ValueAggregation.MEAN;
  private BigWigCompanionRule bigWigRule = BigWigCompanionRule.FIRST;
  private String bigWigNameContains = "";
  private int windowBp = DEFAULT_WINDOW_BP;
  private boolean requireBedScores = true;

  public SignalValueKind getValueKind() {
    return valueKind;
  }

  public void setValueKind(SignalValueKind valueKind) {
    if (valueKind != null) {
      this.valueKind = valueKind;
    }
  }

  public ComparisonUnit getUnit() {
    return unit;
  }

  public void setUnit(ComparisonUnit unit) {
    if (unit != null) {
      this.unit = unit;
    }
  }

  public GenomicScope getScope() {
    return scope;
  }

  public void setScope(GenomicScope scope) {
    if (scope != null) {
      this.scope = scope;
    }
  }

  public ValueAggregation getAggregation() {
    return aggregation;
  }

  public void setAggregation(ValueAggregation aggregation) {
    if (aggregation != null) {
      this.aggregation = aggregation;
    }
  }

  public BigWigCompanionRule getBigWigRule() {
    return bigWigRule;
  }

  public void setBigWigRule(BigWigCompanionRule bigWigRule) {
    if (bigWigRule != null) {
      this.bigWigRule = bigWigRule;
    }
  }

  public String getBigWigNameContains() {
    return bigWigNameContains;
  }

  public void setBigWigNameContains(String bigWigNameContains) {
    this.bigWigNameContains = bigWigNameContains != null ? bigWigNameContains : "";
  }

  public int getWindowBp() {
    return windowBp;
  }

  public void setWindowBp(int windowBp) {
    this.windowBp = Math.max(100, windowBp);
  }

  public boolean isRequireBedScores() {
    return requireBedScores;
  }

  public void setRequireBedScores(boolean requireBedScores) {
    this.requireBedScores = requireBedScores;
  }
}
