package org.baseplayer.variant;

/**
 * Numeric CNV amplitude used by canvas drawing and group analyses.
 * Same priority as the sample-track CNV bars: logR → TCN/CN−2 → type sign.
 */
public final class CnvMagnitude {

  /** |CNLR_MEDIAN| that maps to full half-height on the canvas. */
  public static final double LOGR_FULL_SCALE = 1.0;
  /** |TCN−2| that maps to full half-height when logR is absent. */
  public static final double TCN_FULL_SCALE = 4.0;

  private CnvMagnitude() {}

  /** Signed amplitude: logR when present, else TCN relative to diploid (2). */
  public static double signed(VariantNode node) {
    if (node == null) {
      return 0;
    }
    Double logr = parseInfoDouble(node, "CNLR_MEDIAN");
    if (logr == null) {
      logr = parseInfoDouble(node, "CNLR_MEDIAN_CLUST");
    }
    if (logr != null) {
      return logr;
    }
    Double tcn = parseInfoDouble(node, "TCN_EM");
    if (tcn == null) {
      tcn = parseInfoDouble(node, "TCN");
    }
    if (tcn == null) {
      tcn = parseInfoDouble(node, "CN");
    }
    if (tcn != null) {
      return tcn - 2.0;
    }
    if (node.type == VcfVariantType.SV_CNV_GAIN) {
      return 1.0;
    }
    if (node.type == VcfVariantType.SV_CNV_LOSS) {
      return -1.0;
    }
    return 0;
  }

  /** Full half-height maps to this |magnitude| (logR ±1 or |TCN−2| = 4). */
  public static double displayUnit(VariantNode node) {
    if (node != null
        && (node.getInfoValue("CNLR_MEDIAN") != null
            || node.getInfoValue("CNLR_MEDIAN_CLUST") != null)) {
      return LOGR_FULL_SCALE;
    }
    return TCN_FULL_SCALE;
  }

  public static Double parseInfoDouble(VariantNode node, String key) {
    if (node == null || key == null) {
      return null;
    }
    String raw = node.getInfoValue(key);
    if (raw == null || raw.isBlank() || ".".equals(raw) || "NA".equalsIgnoreCase(raw)) {
      return null;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
