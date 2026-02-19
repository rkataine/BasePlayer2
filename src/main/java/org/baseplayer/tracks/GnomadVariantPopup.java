package org.baseplayer.tracks;

import org.baseplayer.io.APIs.GnomadApiClient.Variant;
import org.baseplayer.ui.InfoPopup;
import org.baseplayer.ui.PopupContent;
import org.baseplayer.ui.PopupContent.Badge;

import javafx.scene.paint.Color;

/**
 * Builds {@link PopupContent} for a gnomAD variant.
 * Used together with a shared {@link InfoPopup} instance.
 */
public final class GnomadVariantPopup {

  private static final Color LOF_COLOR       = Color.rgb(200, 50, 50);
  private static final Color MISSENSE_COLOR  = Color.rgb(220, 140, 40);
  private static final Color SYNONYMOUS_COLOR = Color.rgb(80, 140, 200);
  private static final Color OTHER_COLOR     = Color.rgb(120, 120, 120);

  private GnomadVariantPopup() {} // utility class

  /**
   * Build popup content for the given variant.
   */
  public static PopupContent buildContent(Variant variant) {
    PopupContent c = new PopupContent();
    if (variant == null) return c;

    Color impactColor = getImpactColor(variant);

    // Header
    String variantLabel = variant.ref() + " > " + variant.alt();
    c.title(variantLabel, variant.impact(), impactColor);

    c.row("Position", String.format("%,d", variant.position()));

    if (variant.geneSymbol() != null && !variant.geneSymbol().isEmpty()) {
      c.link("Gene", variant.geneSymbol(),
          () -> InfoPopup.openUrl("https://gnomad.broadinstitute.org/gene/" + variant.geneSymbol() + "?dataset=gnomad_r4"));
    }

    c.separator();
    c.section("Population Frequency");
    c.row("Allele Freq", formatAlleleFrequency(variant.alleleFrequency()),
        getFrequencyColor(variant.alleleFrequency()));
    c.row("Allele Count", String.format("%,d / %,d", variant.alleleCount(), variant.alleleNumber()));
    c.row("Classification", getFrequencyClass(variant.alleleFrequency()));

    c.separator();
    c.section("Functional Annotation");
    if (variant.consequence() != null) {
      c.row("Consequence", formatConsequence(variant.consequence()), impactColor);
    }
    c.row("Impact", variant.impact() != null ? variant.impact() : "Unknown");
    if (variant.hgvsc() != null && !variant.hgvsc().isEmpty()) c.row("HGVS (coding)", variant.hgvsc());
    if (variant.hgvsp() != null && !variant.hgvsp().isEmpty()) c.row("HGVS (protein)", variant.hgvsp());

    if (variant.isLoF()) {
      c.separator();
      c.badges(new Badge("Loss of Function", LOF_COLOR, Color.WHITE));
    }

    c.separator();
    c.section("External Links");
    String variantId = String.format("%d-%s-%s", variant.position(), variant.ref(), variant.alt());
    c.link("gnomAD", "View in gnomAD",
        () -> InfoPopup.openUrl("https://gnomad.broadinstitute.org/variant/" + variantId + "?dataset=gnomad_r4"));
    c.link("ClinVar", "Search ClinVar",
        () -> InfoPopup.openUrl("https://www.ncbi.nlm.nih.gov/clinvar/?term=" + variantId));

    return c;
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private static Color getImpactColor(Variant v) {
    if (v.isLoF()) return LOF_COLOR;
    if (v.isMissense()) return MISSENSE_COLOR;
    if (v.isSynonymous()) return SYNONYMOUS_COLOR;
    return OTHER_COLOR;
  }

  private static String formatAlleleFrequency(double af) {
    if (af == 0) return "0 (not observed)";
    if (af < 0.0001) return String.format("%.2e (%.4f%%)", af, af * 100);
    if (af < 0.01) return String.format("%.5f (%.3f%%)", af, af * 100);
    return String.format("%.4f (%.2f%%)", af, af * 100);
  }

  private static Color getFrequencyColor(double af) {
    if (af == 0)      return Color.GRAY;
    if (af < 0.0001)  return Color.rgb(200, 50, 50);
    if (af < 0.001)   return Color.rgb(220, 140, 40);
    if (af < 0.01)    return Color.rgb(180, 180, 80);
    if (af < 0.05)    return Color.rgb(80, 180, 80);
    return Color.LIGHTGRAY;
  }

  private static String getFrequencyClass(double af) {
    if (af == 0)      return "Not observed";
    if (af < 0.0001)  return "Ultra-rare (<0.01%)";
    if (af < 0.001)   return "Very rare (<0.1%)";
    if (af < 0.01)    return "Rare (<1%)";
    if (af < 0.05)    return "Low frequency (<5%)";
    return "Common (\u22655%)";
  }

  private static String formatConsequence(String consequence) {
    return consequence == null ? "Unknown" : consequence.replace("_", " ");
  }
}
