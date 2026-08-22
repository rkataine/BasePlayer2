package org.baseplayer.variant;

import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import java.util.EnumSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

public class VariantFilter {

    private double minQuality = 0.0;
    private int minDepth = 0;
    private double minAlleleFraction = 0.0;
    private boolean cancerGenesOnly = false;
    private Set<VcfVariantType> allowedTypes = EnumSet.allOf(VcfVariantType.class);
    private boolean showCoding = true;
    private boolean showIntronic = true;
    private boolean showIntergenic = true;
    
    // Advanced filters for INFO and FILTER fields
    private Map<String, String> infoFieldFilters = new HashMap<>();  // Field name -> expected value
    private Set<String> allowedFilterValues = new HashSet<>();        // E.g., "PASS", "LowQual", etc.
    private boolean filterFieldsActive = false;                       // Whether to apply FILTER field filtering

    // ── Getters/setters ───────────────────────────────────────────────────────

    public double getMinQuality() { return minQuality; }
    public void setMinQuality(double minQuality) { this.minQuality = minQuality; }
    
    public int getMinDepth() { return minDepth; }
    public void setMinDepth(int minDepth) { this.minDepth = minDepth; }
    
    public double getMinAlleleFraction() { return minAlleleFraction; }
    public void setMinAlleleFraction(double minAlleleFraction) { this.minAlleleFraction = minAlleleFraction; }

    public boolean isCancerGenesOnly() { return cancerGenesOnly; }
    public void setCancerGenesOnly(boolean cancerGenesOnly) { this.cancerGenesOnly = cancerGenesOnly; }

    public Set<VcfVariantType> getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(Set<VcfVariantType> allowedTypes) { this.allowedTypes = allowedTypes; }

    public boolean isShowCoding() { return showCoding; }
    public void setShowCoding(boolean showCoding) { this.showCoding = showCoding; }

    public boolean isShowIntronic() { return showIntronic; }
    public void setShowIntronic(boolean showIntronic) { this.showIntronic = showIntronic; }

    public boolean isShowIntergenic() { return showIntergenic; }
    public void setShowIntergenic(boolean showIntergenic) { this.showIntergenic = showIntergenic; }
    
    public Map<String, String> getInfoFieldFilters() { return infoFieldFilters; }
    public void setInfoFieldFilters(Map<String, String> filters) { this.infoFieldFilters = filters; }
    
    public Set<String> getAllowedFilterValues() { return allowedFilterValues; }
    public void setAllowedFilterValues(Set<String> values) { 
        this.allowedFilterValues = values; 
        this.filterFieldsActive = !values.isEmpty();
    }
    
    public boolean isFilterFieldsActive() { return filterFieldsActive; }

    // ── Filtering logic ───────────────────────────────────────────────────────

    public boolean passes(VariantNode node, int sampleTrackIndex) {
        if (!allowedTypes.contains(node.type)) return false;

        VariantNode.SampleCall call = node.getSampleCall(sampleTrackIndex);
        if (call != null) {
            if (minQuality > 0 && call.quality >= 0 && call.quality < minQuality) return false;
            if (minDepth > 0 && call.depth >= 0 && call.depth < minDepth) return false;
            if (minAlleleFraction > 0 && call.alleleFraction >= 0 && call.alleleFraction < minAlleleFraction) return false;
        }

        VariantAnnotation ann = node.annotation;
        
        // TODO: INFO and FILTER field filtering
        // Once VariantNode stores INFO/FILTER fields, apply those filters here:
        // - Check infoFieldFilters against node.infoFields
        // - Check allowedFilterValues against node.filterField
        if (ann != null) {
            if (cancerGenesOnly && !ann.isCancerGene()) return false;

            VariantEffect effect = ann.effect();
            if (effect.isCoding() && !showCoding) return false;
            if (effect.isIntronic() && !showIntronic) return false;
            if (effect == VariantEffect.INTERGENIC && !showIntergenic) return false;
        } else {
            if (cancerGenesOnly) return false;
            if (!showIntergenic) return false;
        }

        return true;
    }

    public VariantList applyToList(VariantList source) {
        if (source == null) return null;

        VariantList filtered = new VariantList(source.getChromosome());
        VariantNode node = source.getFirst();

        while (node != null) {
            for (VariantNode.SampleCall call : node.getSamples()) {
                if (passes(node, call.trackIndex)) {
                    filtered.addVariant(node.position, node.ref, node.alt, node.type,
                                        call.trackIndex, call);
                }
            }
            node = node.next;
        }

        return filtered;
    }

    /** Returns true if all filters are at default (pass-all) state. */
    public boolean isPassAll() {
        return minQuality == 0.0
            && minDepth == 0
            && minAlleleFraction == 0.0
            && !cancerGenesOnly
            && showCoding && showIntronic && showIntergenic
            && allowedTypes.equals(EnumSet.allOf(VcfVariantType.class))
            && infoFieldFilters.isEmpty()
            && !filterFieldsActive;
    }
}
