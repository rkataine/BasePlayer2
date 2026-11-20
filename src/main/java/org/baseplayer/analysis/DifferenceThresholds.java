package org.baseplayer.analysis;

/**
 * Post-stat filters so only meaningful differences are shown / painted / exported.
 * Direction flags always apply; numeric thresholds apply when {@link #isEnabled()}.
 */
public final class DifferenceThresholds {

  /** When false, every computed gene/feature row is shown (no numeric/direction cut). */
  private boolean enabled = false;
  private double minAbsLog2Fc = 0.5;
  private double maxQ = 0.05;
  private int minNPerGroup = 2;
  /**
   * Keep at most this many rows after other filters, ranked by difference magnitude.
   * {@code 0} = unlimited.
   */
  private int maxRows = 0;
  /** Effect &gt; 0 vs baseline (higher outside baseline). */
  private boolean showUp = true;
  /** Effect &lt; 0 vs baseline (higher in baseline). */
  private boolean showDown = true;
  /** Near-zero effect. */
  private boolean showSimilar = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public double getMinAbsLog2Fc() {
    return minAbsLog2Fc;
  }

  public void setMinAbsLog2Fc(double minAbsLog2Fc) {
    this.minAbsLog2Fc = Math.max(0, minAbsLog2Fc);
  }

  public double getMaxQ() {
    return maxQ;
  }

  public void setMaxQ(double maxQ) {
    this.maxQ = Math.max(0, Math.min(1, maxQ));
  }

  public int getMinNPerGroup() {
    return minNPerGroup;
  }

  public void setMinNPerGroup(int minNPerGroup) {
    this.minNPerGroup = Math.max(1, minNPerGroup);
  }

  public int getMaxRows() {
    return maxRows;
  }

  public void setMaxRows(int maxRows) {
    this.maxRows = Math.max(0, maxRows);
  }

  public boolean isShowUp() {
    return showUp;
  }

  public void setShowUp(boolean showUp) {
    this.showUp = showUp;
  }

  public boolean isShowDown() {
    return showDown;
  }

  public void setShowDown(boolean showDown) {
    this.showDown = showDown;
  }

  public boolean isShowSimilar() {
    return showSimilar;
  }

  public void setShowSimilar(boolean showSimilar) {
    this.showSimilar = showSimilar;
  }

  public DifferenceThresholds copy() {
    DifferenceThresholds t = new DifferenceThresholds();
    t.enabled = enabled;
    t.minAbsLog2Fc = minAbsLog2Fc;
    t.maxQ = maxQ;
    t.minNPerGroup = minNPerGroup;
    t.maxRows = maxRows;
    t.showUp = showUp;
    t.showDown = showDown;
    t.showSimilar = showSimilar;
    return t;
  }
}
