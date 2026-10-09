package org.baseplayer.variant;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Wall-clock stage timings + counters for LOH / comparison rebuilds.
 * Prints one {@code [LOH]} line per chromosome to stderr when enabled.
 *
 * <p>Enabled when the filter is in LOH mode, or when
 * {@code -Dbaseplayer.loh.timing=true} is set. Disable with
 * {@code -Dbaseplayer.loh.timing=false}.
 */
public final class LohTiming {

  private static final String PROP = "baseplayer.loh.timing";

  private final String chrom;
  private final Map<String, Long> stageNanos = new LinkedHashMap<>();
  private final Map<String, Long> counts = new LinkedHashMap<>();
  private String activeStage;
  private long activeStartNanos;
  private final long sessionStartNanos = System.nanoTime();

  private LohTiming(String chrom) {
    this.chrom = chrom == null || chrom.isBlank() ? "?" : chrom;
  }

  /** Create a timing recorder when LOH timing should run; otherwise {@code null}. */
  public static LohTiming startIfEnabled(String chromosome, VariantFilter filter) {
    if (!isEnabled(filter)) {
      return null;
    }
    return new LohTiming(chromosome);
  }

  public static boolean isEnabled(VariantFilter filter) {
    String prop = System.getProperty(PROP);
    if (prop != null) {
      return !"false".equalsIgnoreCase(prop.trim()) && !"0".equals(prop.trim());
    }
    return filter != null && filter.isLohMode();
  }

  public void begin(String stage) {
    endActive();
    activeStage = stage;
    activeStartNanos = System.nanoTime();
  }

  public void end(String stage) {
    if (activeStage != null && activeStage.equals(stage)) {
      endActive();
    }
  }

  public void count(String key, long value) {
    counts.put(key, value);
  }

  public void addCount(String key, long delta) {
    counts.merge(key, delta, Long::sum);
  }

  /** Finish the chromosome and print one summary line. */
  public void finish() {
    endActive();
    long totalMs = (System.nanoTime() - sessionStartNanos) / 1_000_000L;
    StringBuilder sb = new StringBuilder(160);
    sb.append("[LOH] ").append(chrom);
    appendCount(sb, "nodes");
    appendCount(sb, "aaAdded");
    appendCount(sb, "informative");
    appendCount(sb, "visible");
    appendCount(sb, "regions");
    for (Map.Entry<String, Long> e : stageNanos.entrySet()) {
      sb.append(' ').append(e.getKey()).append('=').append(e.getValue() / 1_000_000L).append("ms");
    }
    sb.append(" total=").append(totalMs).append("ms");
    System.err.println(sb);
  }

  private void appendCount(StringBuilder sb, String key) {
    Long v = counts.get(key);
    if (v != null) {
      sb.append(' ').append(key).append('=').append(formatCount(v));
    }
  }

  private static String formatCount(long v) {
    if (v >= 1_000_000L) {
      return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0);
    }
    if (v >= 10_000L) {
      return String.format(Locale.ROOT, "%.1fk", v / 1_000.0);
    }
    return Long.toString(v);
  }

  private void endActive() {
    if (activeStage == null) {
      return;
    }
    long elapsed = System.nanoTime() - activeStartNanos;
    stageNanos.merge(activeStage, elapsed, Long::sum);
    activeStage = null;
  }
}
