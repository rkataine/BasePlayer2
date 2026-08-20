package org.baseplayer.variant.annotation;

import org.baseplayer.annotation.CosmicCensusEntry;

public record VariantAnnotation(
    String chromosome,
    long position,
    VariantEffect effect,
    String geneName,
    String transcriptId,
    String aaChange,      // e.g. "p.Met156Thr", null for non-coding
    String codonChange,   // e.g. "c.467T>C", null for non-coding
    int codonNumber,      // 1-based, 0 if not in CDS
    boolean isCancerGene,
    CosmicCensusEntry cosmicEntry
) {
    /** Short human-readable summary, e.g. "BRCA1 p.Met156Thr" or "TP53 Intronic". */
    public String summary() {
        if (geneName == null) return effect.displayName();
        if (aaChange != null) return geneName + " " + aaChange;
        return geneName + " " + effect.displayName();
    }
}
