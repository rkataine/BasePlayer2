package org.baseplayer.variant.annotation;

import java.util.List;

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
    CosmicCensusEntry cosmicEntry,
    List<String> overlappingGenes  // CGC symbols for SVs; empty for typical point calls
) {
    public VariantAnnotation {
        overlappingGenes = overlappingGenes == null ? List.of() : List.copyOf(overlappingGenes);
    }

    /** Short human-readable summary, e.g. "BRCA1 p.Met156Thr" or "TP53 Intronic". */
    public String summary() {
        if (geneName == null) return effect.displayName();
        if (aaChange != null) return geneName + " " + aaChange;
        return geneName + " " + effect.displayName();
    }

    /** Comma-joined overlapping / nearest census genes, or primary geneName. */
    public String genesDisplay() {
        if (overlappingGenes != null && !overlappingGenes.isEmpty()) {
            return String.join(", ", overlappingGenes);
        }
        return geneName;
    }
}
