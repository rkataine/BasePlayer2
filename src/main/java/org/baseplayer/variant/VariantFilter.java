package org.baseplayer.variant;

import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import java.util.EnumSet;
import java.util.Set;

public class VariantFilter {

    private double minQuality = 0.0;
    private boolean cancerGenesOnly = false;
    private Set<VcfVariantType> allowedTypes = EnumSet.allOf(VcfVariantType.class);
    private boolean showCoding = true;
    private boolean showIntronic = true;
    private boolean showIntergenic = true;

    // ── Getters/setters ───────────────────────────────────────────────────────

    public double getMinQuality() { return minQuality; }
    public void setMinQuality(double minQuality) { this.minQuality = minQuality; }

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

    // ── Filtering logic ───────────────────────────────────────────────────────

    public boolean passes(VariantNode node, int sampleTrackIndex) {
        if (!allowedTypes.contains(node.type)) return false;

        VariantNode.SampleCall call = node.getSampleCall(sampleTrackIndex);
        if (call != null && minQuality > 0 && call.quality >= 0 && call.quality < minQuality) return false;

        VariantAnnotation ann = node.annotation;
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
            && !cancerGenesOnly
            && showCoding && showIntronic && showIntergenic
            && allowedTypes.equals(EnumSet.allOf(VcfVariantType.class));
    }
}
