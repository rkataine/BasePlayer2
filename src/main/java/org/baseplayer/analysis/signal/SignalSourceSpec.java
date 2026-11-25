package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.ValueAggregation;

/**
 * User choices for a Signal comparison run.
 */
public final class SignalSourceSpec {

  public static final int DEFAULT_WINDOW_BP = 10_000;
  public static final int MAX_WINDOW_UNITS = 250_000;

  private SignalValueKind valueKind = SignalValueKind.BED_SCORES;
  private ComparisonUnit unit = ComparisonUnit.GENE;
  private GenomicScope scope = GenomicScope.CURRENT_CHROM;
  private ValueAggregation aggregation = ValueAggregation.MEAN;
  private BigWigCompanionRule bigWigRule = BigWigCompanionRule.FIRST;
  private String bigWigNameContains = "";
  private int windowBp = DEFAULT_WINDOW_BP;
  private boolean requireBedScores = true;
  /**
   * When true and value kind is allele-CN LOH, only LOH segments contribute to genes
   * (diploid 1/1 etc. are skipped at collect time). Legacy single-channel LOH path.
   */
  private boolean lohOnly = true;
  /** Multi-value channels (Minor/Major/…). Empty → single-matrix ValueSource path. */
  private List<SignalChannel> channels = List.of();
  /**
   * Allele-CN event types shown in annotation / Sample Comparison (Gain/Loss/LOH).
   * Never includes {@link SignalEventDirection#NONE}. Default: all three.
   */
  private EnumSet<SignalEventDirection> allowedEventDirections =
      SignalEventDirection.defaultAllowed();

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

  public boolean isLohOnly() {
    return lohOnly;
  }

  public void setLohOnly(boolean lohOnly) {
    this.lohOnly = lohOnly;
  }

  public List<SignalChannel> getChannels() {
    return channels;
  }

  public void setChannels(List<SignalChannel> channels) {
    if (channels == null || channels.isEmpty()) {
      this.channels = List.of();
      return;
    }
    List<SignalChannel> copy = new ArrayList<>(channels.size());
    for (SignalChannel ch : channels) {
      if (ch != null) {
        copy.add(ch.copy());
      }
    }
    this.channels = List.copyOf(copy);
  }

  public boolean hasChannels() {
    return !channels.isEmpty();
  }

  /** Defensive copy of allowed allele-CN event directions (Gain/Loss/LOH). */
  public Set<SignalEventDirection> getAllowedEventDirections() {
    return allowedEventDirections.clone();
  }

  public void setAllowedEventDirections(Collection<SignalEventDirection> directions) {
    EnumSet<SignalEventDirection> next = EnumSet.noneOf(SignalEventDirection.class);
    if (directions != null) {
      for (SignalEventDirection d : directions) {
        if (d != null && d != SignalEventDirection.NONE) {
          next.add(d);
        }
      }
    }
    this.allowedEventDirections = next;
  }

  public boolean allowsEventDirection(SignalEventDirection direction) {
    return direction != null
        && direction != SignalEventDirection.NONE
        && allowedEventDirections.contains(direction);
  }
}
