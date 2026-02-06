package org.baseplayer.tracks;

import org.baseplayer.io.GnomadApiClient.Variant;

import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

/**
 * Popup displaying detailed gnomAD variant information.
 */
public class GnomadVariantPopup extends TrackDataPopup {
  
  private Variant variant;
  
  // Colors for impact levels
  private static final Color LOF_COLOR = Color.rgb(200, 50, 50);
  private static final Color MISSENSE_COLOR = Color.rgb(220, 140, 40);
  private static final Color SYNONYMOUS_COLOR = Color.rgb(80, 140, 200);
  private static final Color OTHER_COLOR = Color.rgb(120, 120, 120);
  
  public GnomadVariantPopup() {
    super();
  }
  
  /**
   * Set the variant to display.
   */
  public void setVariant(Variant variant) {
    this.variant = variant;
  }
  
  @Override
  protected void buildContent() {
    if (variant == null) return;
    
    // Determine variant color based on impact
    Color impactColor = getImpactColor(variant);
    
    // Header: variant name with impact badge
    HBox header = new HBox(10);
    header.setAlignment(Pos.CENTER_LEFT);
    
    // Main variant label (ref > alt)
    String variantLabel = variant.ref() + " > " + variant.alt();
    addHeader(variantLabel, variant.impact(), impactColor);
    
    // Position info
    addInfoRow("Position", String.format("%,d", variant.position()));
    
    // Gene symbol if available
    if (variant.geneSymbol() != null && !variant.geneSymbol().isEmpty()) {
      addClickableInfoRow("Gene", variant.geneSymbol(), 
          () -> openUrl("https://gnomad.broadinstitute.org/gene/" + variant.geneSymbol() + "?dataset=gnomad_r3"));
    }
    
    addSeparator();
    addSectionTitle("Population Frequency");
    
    // Allele Frequency
    addInfoRow("Allele Freq", formatAlleleFrequency(variant.alleleFrequency()), 
        getFrequencyColor(variant.alleleFrequency()));
    
    // Allele counts
    addInfoRow("Allele Count", String.format("%,d / %,d", variant.alleleCount(), variant.alleleNumber()));
    
    // Frequency class
    String freqClass = getFrequencyClass(variant.alleleFrequency());
    addInfoRow("Classification", freqClass);
    
    addSeparator();
    addSectionTitle("Functional Annotation");
    
    // Consequence
    if (variant.consequence() != null) {
      addInfoRow("Consequence", formatConsequence(variant.consequence()), impactColor);
    }
    
    // Impact
    addInfoRow("Impact", variant.impact() != null ? variant.impact() : "Unknown");
    
    // HGVS notation
    if (variant.hgvsc() != null && !variant.hgvsc().isEmpty()) {
      addInfoRow("HGVS (coding)", variant.hgvsc());
    }
    if (variant.hgvsp() != null && !variant.hgvsp().isEmpty()) {
      addInfoRow("HGVS (protein)", variant.hgvsp());
    }
    
    // LoF indicator
    if (variant.isLoF()) {
      addSeparator();
      HBox row = createBadgeRow();
      row.getChildren().add(createBadge("Loss of Function", LOF_COLOR, Color.WHITE));
      content.getChildren().add(row);
    }
    
    addSeparator();
    addSectionTitle("External Links");
    
    // gnomAD link
    String variantId = String.format("%d-%s-%s", variant.position(), variant.ref(), variant.alt());
    addClickableInfoRow("gnomAD", "View in gnomAD",
        () -> openUrl("https://gnomad.broadinstitute.org/variant/" + variantId + "?dataset=gnomad_r3"));
    
    // ClinVar link
    addClickableInfoRow("ClinVar", "Search ClinVar",
        () -> openUrl("https://www.ncbi.nlm.nih.gov/clinvar/?term=" + variantId));
  }
  
  private Color getImpactColor(Variant v) {
    if (v.isLoF()) return LOF_COLOR;
    if (v.isMissense()) return MISSENSE_COLOR;
    if (v.isSynonymous()) return SYNONYMOUS_COLOR;
    return OTHER_COLOR;
  }
  
  private String formatAlleleFrequency(double af) {
    if (af == 0) return "0 (not observed)";
    if (af < 0.0001) return String.format("%.2e (%.4f%%)", af, af * 100);
    if (af < 0.01) return String.format("%.5f (%.3f%%)", af, af * 100);
    return String.format("%.4f (%.2f%%)", af, af * 100);
  }
  
  private Color getFrequencyColor(double af) {
    if (af == 0) return Color.GRAY;
    if (af < 0.0001) return Color.rgb(200, 50, 50);     // Very rare - red
    if (af < 0.001) return Color.rgb(220, 140, 40);      // Rare - orange
    if (af < 0.01) return Color.rgb(180, 180, 80);       // Low frequency - yellow
    if (af < 0.05) return Color.rgb(80, 180, 80);        // Polymorphism - green
    return Color.LIGHTGRAY;                               // Common
  }
  
  private String getFrequencyClass(double af) {
    if (af == 0) return "Not observed";
    if (af < 0.0001) return "Ultra-rare (<0.01%)";
    if (af < 0.001) return "Very rare (<0.1%)";
    if (af < 0.01) return "Rare (<1%)";
    if (af < 0.05) return "Low frequency (<5%)";
    return "Common (≥5%)";
  }
  
  private String formatConsequence(String consequence) {
    if (consequence == null) return "Unknown";
    // Make it more readable
    return consequence.replace("_", " ");
  }
}
