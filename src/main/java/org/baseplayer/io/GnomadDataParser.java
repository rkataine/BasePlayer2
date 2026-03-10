package org.baseplayer.io;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.components.PopupContent.Badge;
import org.baseplayer.io.APIs.GnomadApiClient;
import org.baseplayer.io.APIs.GnomadApiClient.Variant;
import org.baseplayer.io.APIs.GnomadApiClient.VariantData;
import org.baseplayer.io.APIs.UcscApiClient.ConservationData;

import javafx.scene.paint.Color;

/**
 * Parses gnomAD variant data into the generic {@link ConservationData} format
 * so it can be displayed by a generic {@link org.baseplayer.features.FeatureTrack}.
 *
 * <p>This is a stateful class: it retains the raw variant list from the last fetch
 * so that the popup builder can look up full variant details at a clicked position.
 *
 * <p>Each bin in the output scores array represents the maximum allele frequency
 * of variants falling within that genomic bin. This gives a visual overview
 * of population variant frequency across the region.
 */
public final class GnomadDataParser {

  /** Cached raw variants from the most recent successful fetch. */
  private volatile List<Variant> currentVariants = List.of();

  /**
   * Fetch gnomAD variants and convert to binned ConservationData.
   * Uses -log10(AF) as the score so that rare variants produce higher bars.
   */
  public CompletableFuture<ConservationData> fetch(
      String chromosome, long start, long end, int bins) {

    if (end - start > GnomadApiClient.getMaxRegionSize()) {
      return CompletableFuture.completedFuture(ConservationData.empty(start, end, bins));
    }

    return GnomadApiClient.fetchVariants(chromosome, start, end)
        .thenApply(variantData -> {
          // Store raw variants for popup lookups
          if (variantData.hasData() && variantData.variants() != null) {
            currentVariants = variantData.variants();
          } else {
            currentVariants = List.of();
          }
          return convertToBinned(variantData, start, end, bins);
        });
  }

  /**
   * Build popup content for a gnomAD variant at the given genomic position.
   * Looks up the closest variant in the cached data and shows full details.
   */
  public PopupContent buildPopupContent(String chromosome, long position, double score, int binIndex) {
    Variant variant = findClosestVariant(position);
    if (variant == null) {
      return null; // fall back to default popup
    }
    return buildVariantPopup(chromosome, variant);
  }

  // ── Variant lookup ────────────────────────────────────────────────────────

  private Variant findClosestVariant(long position) {
    List<Variant> variants = currentVariants;
    if (variants.isEmpty()) return null;

    Variant closest = null;
    long closestDist = Long.MAX_VALUE;

    for (Variant v : variants) {
      long dist = Math.abs(v.position() - position);
      if (dist < closestDist) {
        closest = v;
        closestDist = dist;
      }
    }

    // Only return if within a reasonable distance (5 bases)
    if (closestDist > 5) return null;
    return closest;
  }

  // ── Popup building ────────────────────────────────────────────────────────

  private static PopupContent buildVariantPopup(String chromosome, Variant v) {
    PopupContent c = new PopupContent();

    Color impactColor = getImpactColor(v);
    String variantLabel = v.ref() + " > " + v.alt();
    c.title(variantLabel, v.impact() != null ? v.impact() : "", impactColor);

    c.row("Position", String.format("chr%s:%,d", chromosome, v.position()));
    if (v.geneSymbol() != null && !v.geneSymbol().isEmpty()) {
      c.link("Gene", v.geneSymbol(),
          () -> InfoPopup.openUrl(
              "https://gnomad.broadinstitute.org/gene/" + v.geneSymbol() + "?dataset=gnomad_r4"));
    }

    // Population frequency
    c.separator();
    c.section("Population Frequency");
    c.row("Allele Freq", formatAlleleFrequency(v.alleleFrequency()),
        getFrequencyColor(v.alleleFrequency()));
    c.row("Allele Count", String.format("%,d / %,d", v.alleleCount(), v.alleleNumber()));
    c.row("Classification", getFrequencyClass(v.alleleFrequency()));

    // Functional annotation
    c.separator();
    c.section("Functional Annotation");
    if (v.consequence() != null) {
      c.row("Consequence", formatConsequence(v.consequence()), impactColor);
    }
    c.row("Impact", v.impact() != null ? v.impact() : "Unknown");
    if (v.hgvsc() != null && !v.hgvsc().isEmpty()) c.row("HGVS (coding)", v.hgvsc());
    if (v.hgvsp() != null && !v.hgvsp().isEmpty()) c.row("HGVS (protein)", v.hgvsp());

    if (v.isLoF()) {
      c.separator();
      c.badges(new Badge("Loss of Function", Color.rgb(220, 60, 60), Color.WHITE));
    }

    // External links
    c.separator();
    c.section("External Links");
    String variantId = String.format("%d-%s-%s", v.position(), v.ref(), v.alt());
    c.link("gnomAD", "View in gnomAD",
        () -> InfoPopup.openUrl(
            "https://gnomad.broadinstitute.org/variant/" + variantId + "?dataset=gnomad_r4"));
    c.link("ClinVar", "Search ClinVar",
        () -> InfoPopup.openUrl(
            "https://www.ncbi.nlm.nih.gov/clinvar/?term=" + variantId));

    return c;
  }

  // ── Binning ───────────────────────────────────────────────────────────────

  static ConservationData convertToBinned(VariantData variantData,
                                          long start, long end, int bins) {
    if (variantData.hasError()) {
      return ConservationData.error(start, end, bins, variantData.errorMessage());
    }

    List<Variant> variants = variantData.variants();
    if (variants == null || variants.isEmpty()) {
      return ConservationData.empty(start, end, bins);
    }

    double[] scores = new double[bins];
    double binSize = (double) (end - start) / bins;

    double minScore = Double.MAX_VALUE;
    double maxScore = Double.MIN_VALUE;
    boolean hasValues = false;

    for (Variant v : variants) {
      long pos = v.position();
      if (pos < start || pos >= end) continue;

      int binIdx = (int) ((pos - start) / binSize);
      if (binIdx < 0 || binIdx >= bins) continue;

      double af = Math.max(v.alleleFrequency(), 1e-8);
      double s = -Math.log10(af);

      if (s > scores[binIdx]) {
        scores[binIdx] = s;
      }
      if (s < minScore) minScore = s;
      if (s > maxScore) maxScore = s;
      hasValues = true;
    }

    if (!hasValues) {
      return ConservationData.empty(start, end, bins);
    }

    return new ConservationData(start, end, scores, minScore, maxScore, true, null);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static Color getImpactColor(Variant v) {
    if (v.isLoF()) return Color.rgb(220, 60, 60);
    if (v.isMissense()) return Color.rgb(230, 160, 50);
    if (v.isSynonymous()) return Color.rgb(80, 140, 220);
    return Color.rgb(140, 140, 140);
  }

  private static String formatAlleleFrequency(double af) {
    if (af == 0) return "0 (not observed)";
    if (af < 0.0001) return String.format("%.2e (%.4f%%)", af, af * 100);
    if (af < 0.01) return String.format("%.5f (%.3f%%)", af, af * 100);
    return String.format("%.4f (%.2f%%)", af, af * 100);
  }

  private static Color getFrequencyColor(double af) {
    if (af == 0) return Color.GRAY;
    if (af < 0.0001) return Color.rgb(200, 50, 50);
    if (af < 0.001) return Color.rgb(220, 140, 40);
    if (af < 0.01) return Color.rgb(180, 180, 80);
    if (af < 0.05) return Color.rgb(80, 180, 80);
    return Color.LIGHTGRAY;
  }

  private static String getFrequencyClass(double af) {
    if (af == 0) return "Not observed";
    if (af < 0.0001) return "Ultra-rare (<0.01%)";
    if (af < 0.001) return "Very rare (<0.1%)";
    if (af < 0.01) return "Rare (<1%)";
    if (af < 0.05) return "Low frequency (<5%)";
    return "Common (\u22655%)";
  }

  private static String formatConsequence(String consequence) {
    return consequence.replace("_", " ");
  }
}
