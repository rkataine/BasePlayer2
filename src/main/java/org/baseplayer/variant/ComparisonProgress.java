package org.baseplayer.variant;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Throttled progress reporter for genotype-heavy / LOH comparison rebuilds.
 * Drives {@link org.baseplayer.services.LoadingManager} ETA via fine-grained units
 * (1000 units per chromosome) and a human-readable stage suffix.
 *
 * <p>Stages map to non-overlapping fractions of each chromosome so the bar never
 * moves backwards within a chrom.
 */
public final class ComparisonProgress {

  private static final int UNITS_PER_CHROM = 1000;
  private static final long MIN_NOTIFY_MS = 120;

  private final int chromCount;
  private final Consumer<String> onMessage;
  private final BiConsumer<Integer, Integer> onProgressUnits;
  private final String cohortSummary;

  private int chromIndex;
  private String chrom = "";
  private String stage = "";
  private int nodeTotal;
  private double stageStart;
  private double stageEnd = 1.0;
  private long lastNotifyMs;
  private int lastUnits = -1;

  public ComparisonProgress(
      int chromCount,
      String cohortSummary,
      Consumer<String> onMessage,
      BiConsumer<Integer, Integer> onProgressUnits) {
    this.chromCount = Math.max(1, chromCount);
    this.cohortSummary = cohortSummary == null || cohortSummary.isBlank() ? "" : cohortSummary;
    this.onMessage = onMessage != null ? onMessage : s -> {};
    this.onProgressUnits = onProgressUnits != null ? onProgressUnits : (c, t) -> {};
  }

  public void beginChrom(int oneBasedIndex, String chromosome, int variantCount) {
    this.chromIndex = Math.max(1, oneBasedIndex);
    this.chrom = chromosome == null ? "?" : chromosome;
    this.nodeTotal = Math.max(0, variantCount);
    this.stageStart = 0;
    this.stageEnd = 1.0;
    setStage("Starting", 0, 0.02);
    publish(true);
  }

  public void setStage(String stageName) {
    setStage(stageName, stageStart, stageEnd);
  }

  /**
   * Begin a stage covering {@code [start, end)} of the current chromosome's progress
   * (0..1). Subsequent {@link #reportNodes} fill that range.
   */
  public void setStage(String stageName, double startFraction, double endFraction) {
    this.stage = stageName == null ? "" : stageName;
    this.stageStart = Math.max(0, Math.min(1, startFraction));
    this.stageEnd = Math.max(this.stageStart, Math.min(1, endFraction));
    publish(false);
  }

  /** Within-stage node progress for the current stage (0..nodeTotal). */
  public void reportNodes(int processed) {
    reportStageProgress(processed, nodeTotal);
  }

  /** Within-stage progress with an explicit total (e.g. LOH region count). */
  public void reportStageProgress(int processed, int totalInStage) {
    if (totalInStage <= 0) {
      publishUnits(unitsForFraction(stageEnd));
      publish(false);
      return;
    }
    int clamped = Math.max(0, Math.min(processed, totalInStage));
    double frac = (double) clamped / totalInStage;
    double overall = stageStart + (stageEnd - stageStart) * frac;
    publishUnits(unitsForFraction(overall));
    publish(false);
  }

  public void finishChrom() {
    publishUnits(chromIndex * UNITS_PER_CHROM);
    publish(true);
  }

  public void done() {
    publishUnits(chromCount * UNITS_PER_CHROM);
    stage = "done";
    stageStart = 1;
    stageEnd = 1;
    publish(true);
  }

  private int unitsForFraction(double fractionOfChrom) {
    int within = (int) Math.round(Math.max(0, Math.min(1, fractionOfChrom)) * UNITS_PER_CHROM);
    return (chromIndex - 1) * UNITS_PER_CHROM + within;
  }

  private void publishUnits(int units) {
    if (units < lastUnits) {
      // Never move backwards within a session.
      return;
    }
    if (units == lastUnits) {
      return;
    }
    lastUnits = units;
    onProgressUnits.accept(units, chromCount * UNITS_PER_CHROM);
  }

  private void publish(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - lastNotifyMs < MIN_NOTIFY_MS) {
      return;
    }
    lastNotifyMs = now;
    StringBuilder sb = new StringBuilder();
    if (!stage.isEmpty()) {
      sb.append(stage);
    }
    if (!chrom.isEmpty()) {
      if (sb.length() > 0) {
        sb.append(" · ");
      }
      sb.append(chrom).append(" (").append(chromIndex).append('/').append(chromCount).append(')');
    }
    if (lastUnits >= 0) {
      int within = lastUnits - (chromIndex - 1) * UNITS_PER_CHROM;
      int pct = Math.max(0, Math.min(100, within * 100 / UNITS_PER_CHROM));
      sb.append(' ').append(pct).append('%');
    }
    if (!cohortSummary.isEmpty() && chromIndex <= 1) {
      sb.append(" · ").append(cohortSummary);
    }
    onMessage.accept(sb.toString());
  }
}
