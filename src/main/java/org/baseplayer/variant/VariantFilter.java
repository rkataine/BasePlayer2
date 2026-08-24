package org.baseplayer.variant;

import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import java.util.EnumSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VariantFilter {

    private double minQuality = 0.0;
    private int minDepth = 0;
    private double minAlleleFraction = 0.0;
    private boolean cancerGenesOnly = false;
    private Set<VcfVariantType> allowedTypes = EnumSet.allOf(VcfVariantType.class);
    private Set<VariantEffect> allowedEffects = EnumSet.allOf(VariantEffect.class);
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

    public Set<VariantEffect> getAllowedEffects() { return allowedEffects; }
    public void setAllowedEffects(Set<VariantEffect> allowedEffects) { this.allowedEffects = allowedEffects; }

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

    /** Create a deep copy so long-running loads can use a stable filter snapshot. */
    public VariantFilter copy() {
        VariantFilter copy = new VariantFilter();
        copy.minQuality = this.minQuality;
        copy.minDepth = this.minDepth;
        copy.minAlleleFraction = this.minAlleleFraction;
        copy.cancerGenesOnly = this.cancerGenesOnly;
        copy.allowedTypes = EnumSet.copyOf(this.allowedTypes);
        copy.allowedEffects = EnumSet.copyOf(this.allowedEffects);
        copy.showCoding = this.showCoding;
        copy.showIntronic = this.showIntronic;
        copy.showIntergenic = this.showIntergenic;
        copy.infoFieldFilters = new HashMap<>(this.infoFieldFilters);
        copy.allowedFilterValues = new HashSet<>(this.allowedFilterValues);
        copy.filterFieldsActive = this.filterFieldsActive;
        return copy;
    }

    /**
     * Filters that can be applied during VCF streaming before annotation is available.
     * Annotation-dependent dimensions (coding/intronic/intergenic, cancer-only, INFO/FILTER)
     * are intentionally deferred until after annotation/pruning.
     */
    public boolean passesLoadTime(VcfVariantType type, double siteQuality, VariantNode.SampleCall call) {
        if (!allowedTypes.contains(type)) return false;

        if (minQuality > 0) {
            if (siteQuality >= 0) {
                if (siteQuality < minQuality) return false;
            } else if (call != null && call.quality >= 0 && call.quality < minQuality) {
                return false;
            }
        }

        if (call != null) {
            if (minDepth > 0 && call.depth >= 0 && call.depth < minDepth) return false;
            if (minAlleleFraction > 0 && call.alleleFraction >= 0 && call.alleleFraction < minAlleleFraction) return false;
        }

        return true;
    }

    /** Whether this filter needs annotation-aware post-load pruning. */
    public boolean requiresPostAnnotationFiltering() {
        return cancerGenesOnly
            || !showCoding
            || !showIntronic
            || !showIntergenic
            || !infoFieldFilters.isEmpty()
            || filterFieldsActive;
    }

    /** Stable key for comparing whether cached chromosome data matches filter settings. */
    public String toStableKey() {
        List<String> typeNames = new ArrayList<>();
        for (VcfVariantType t : allowedTypes) typeNames.add(t.name());
        Collections.sort(typeNames);

        List<String> infoKeys = new ArrayList<>(infoFieldFilters.keySet());
        Collections.sort(infoKeys);
        List<String> infoPairs = new ArrayList<>();
        for (String k : infoKeys) {
            infoPairs.add(k + "=" + infoFieldFilters.get(k));
        }

        List<String> filterVals = new ArrayList<>(allowedFilterValues);
        Collections.sort(filterVals);

        return "minQ=" + minQuality
            + "|minDP=" + minDepth
            + "|minAF=" + minAlleleFraction
            + "|cancerOnly=" + cancerGenesOnly
            + "|showCoding=" + showCoding
            + "|showIntronic=" + showIntronic
            + "|showIntergenic=" + showIntergenic
            + "|types=" + String.join(",", typeNames)
            + "|info=" + String.join(",", infoPairs)
            + "|filterActive=" + filterFieldsActive
            + "|filterValues=" + String.join(",", filterVals);
    }

    /**
     * Returns true if this filter is at least as strict as {@code base}.
     * If false, applying this filter may require reloading data that was previously pruned.
     */
    public boolean isAtLeastAsStrictAs(VariantFilter base) {
        if (base == null) return false;

        if (this.minQuality < base.minQuality) return false;
        if (this.minDepth < base.minDepth) return false;
        if (this.minAlleleFraction < base.minAlleleFraction) return false;

        if (this.allowedTypes == null || base.allowedTypes == null) return false;
        if (!base.allowedTypes.containsAll(this.allowedTypes)) return false;

        if (base.cancerGenesOnly && !this.cancerGenesOnly) return false;

        if (!base.showCoding && this.showCoding) return false;
        if (!base.showIntronic && this.showIntronic) return false;
        if (!base.showIntergenic && this.showIntergenic) return false;

        // INFO/FILTER strictness: conservative handling to avoid false negatives.
        // Existing constraints must remain, and values for shared keys cannot change.
        for (Map.Entry<String, String> e : base.infoFieldFilters.entrySet()) {
            String key = e.getKey();
            String baseVal = e.getValue();
            if (!this.infoFieldFilters.containsKey(key)) return false;
            String newVal = this.infoFieldFilters.get(key);
            if (newVal == null || !newVal.equals(baseVal)) return false;
        }

        if (base.filterFieldsActive && !this.filterFieldsActive) return false;
        if (base.filterFieldsActive && !base.allowedFilterValues.containsAll(this.allowedFilterValues)) return false;

        return true;
    }

    // ── Filtering logic ───────────────────────────────────────────────────────

    public boolean passes(VariantNode node, int sampleTrackIndex) {
        if (!allowedTypes.contains(node.type)) return false;

        if (minQuality > 0) {
            // Prefer record-level QUAL. If QUAL is missing, fall back to sample GQ.
            if (node.siteQuality >= 0) {
                if (node.siteQuality < minQuality) return false;
            }
        }

        VariantNode.SampleCall call = node.getSampleCall(sampleTrackIndex);
        if (call != null) {
            if (minQuality > 0 && node.siteQuality < 0 && call.quality >= 0 && call.quality < minQuality) return false;
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
            // Check if this effect is allowed
            if (!allowedEffects.contains(effect)) return false;
        } else {
            if (cancerGenesOnly) return false;
            // For unannotated variants, treat as intergenic
            if (!allowedEffects.contains(VariantEffect.INTERGENIC)) return false;
        }

        return true;
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
