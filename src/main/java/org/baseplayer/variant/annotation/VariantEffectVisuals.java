package org.baseplayer.variant.annotation;

import javafx.scene.paint.Color;

/**
 * Shared mutation-effect colors (aligned with Variant Filters effect toggles)
 * and severity ranking for gene×sample collapse.
 */
public final class VariantEffectVisuals {

  public static final Color ABSENT = Color.web("#2a2a2a");

  private VariantEffectVisuals() {}

  public static Color color(VariantEffect effect) {
    if (effect == null) {
      return ABSENT;
    }
    return switch (effect) {
      case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT, SPLICE_SITE
          -> Color.web("#e81123");
      case CODING_MISSENSE, CODING_INFRAME -> Color.web("#c9a000");
      case CODING_SYNONYMOUS -> Color.web("#107c10");
      case CODING_OTHER -> Color.web("#8a8886");
      case UTR5, UTR3 -> Color.web("#7a9bb8");
      case NONCODING_GENE -> Color.web("#8a8886");
      case INTRONIC -> Color.web("#6b7280");
      case INTERGENIC -> Color.web("#9ca3af");
    };
  }

  /** Higher = more severe (kept when multiple calls hit the same gene×sample). */
  public static int severity(VariantEffect effect) {
    if (effect == null) {
      return -1;
    }
    return switch (effect) {
      case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT -> 100;
      case SPLICE_SITE -> 90;
      case CODING_MISSENSE -> 70;
      case CODING_INFRAME -> 60;
      case CODING_OTHER -> 50;
      case UTR5, UTR3 -> 40;
      case NONCODING_GENE -> 30;
      case INTRONIC -> 20;
      case CODING_SYNONYMOUS -> 10;
      case INTERGENIC -> 0;
    };
  }

  public static VariantEffect preferSevere(VariantEffect a, VariantEffect b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return severity(b) > severity(a) ? b : a;
  }

  /** Compact legend entries (label + color) for heatmaps. */
  public static LegendEntry[] legendEntries() {
    return new LegendEntry[] {
        new LegendEntry("Stop/FS/Splice", color(VariantEffect.CODING_FRAMESHIFT)),
        new LegendEntry("Missense", color(VariantEffect.CODING_MISSENSE)),
        new LegendEntry("Synonymous", color(VariantEffect.CODING_SYNONYMOUS)),
        new LegendEntry("In-frame", color(VariantEffect.CODING_INFRAME)),
        new LegendEntry("UTR", color(VariantEffect.UTR5)),
        new LegendEntry("Intronic", color(VariantEffect.INTRONIC)),
        new LegendEntry("Other", color(VariantEffect.CODING_OTHER)),
        new LegendEntry("Absent", ABSENT)
    };
  }

  public record LegendEntry(String label, Color color) {}
}
