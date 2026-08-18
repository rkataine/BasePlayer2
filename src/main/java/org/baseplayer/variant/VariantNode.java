package org.baseplayer.variant;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in a linked list of variants at a specific genomic position.
 * Memory-efficient design: one node per position, shared across all samples.
 * 
 * Uses BitSet for sample presence to minimize memory when many samples share variants.
 * For rare variants affecting few samples, the BitSet is still space-efficient.
 */
public class VariantNode {
    
    /** Genomic position (1-based) */
    public final long position;
    
    /** Reference allele */
    public final String ref;
    
    /** Alternate alleles (typically 1-2, but can be more for multi-allelic sites) */
    public final List<String> alt;
    
    /** Variant type (SNV, INSERTION, DELETION, etc.) */
    public final VcfVariantType type;
    
    /** Which sample track indices have this variant (set bits = present) */
    private final BitSet samplePresence;
    
    /** Optional: genotype info per sample index (only populated if needed) */
    private Map<Integer, GenotypeInfo> genotypeMap;
    
    /** Link to next variant node (for linked list) */
    public VariantNode next;
    
    /**
     * Compact genotype information for one sample at this variant.
     */
    public static class GenotypeInfo {
        public final String gt;          // Genotype string (e.g., "0/1", "1|1")
        public final double quality;     // Genotype quality (GQ) or -1 if not available
        public final int depth;          // Read depth (DP) or -1 if not available
        public final boolean isPhased;   // Whether genotype is phased (|) vs unphased (/)
        
        public GenotypeInfo(String gt, double quality, int depth) {
            this.gt = gt;
            this.quality = quality;
            this.depth = depth;
            this.isPhased = gt != null && gt.contains("|");
        }
        
        public static GenotypeInfo fromMap(Map<String, Object> gtMap) {
            if (gtMap == null) return null;
            String gt = (String) gtMap.get("GT");
            double gq = gtMap.containsKey("GQ") ? ((Number) gtMap.get("GQ")).doubleValue() : -1;
            int dp = gtMap.containsKey("DP") ? ((Number) gtMap.get("DP")).intValue() : -1;
            return new GenotypeInfo(gt, gq, dp);
        }
    }
    
    public VariantNode(long position, String ref, List<String> alt, VcfVariantType type) {
        this.position = position;
        this.ref = ref;
        this.alt = alt;
        this.type = type;
        this.samplePresence = new BitSet();
        this.next = null;
    }
    
    /**
     * Mark that a sample has this variant.
     * @param sampleTrackIndex Index in the SampleRegistry's track list
     */
    public void addSample(int sampleTrackIndex) {
        samplePresence.set(sampleTrackIndex);
    }
    
    /**
     * Add sample with genotype information.
     */
    public void addSample(int sampleTrackIndex, GenotypeInfo genotype) {
        samplePresence.set(sampleTrackIndex);
        if (genotype != null) {
            if (genotypeMap == null) {
                genotypeMap = new HashMap<>();
            }
            genotypeMap.put(sampleTrackIndex, genotype);
        }
    }
    
    /**
     * Check if a specific sample has this variant.
     */
    public boolean hasSample(int sampleTrackIndex) {
        return samplePresence.get(sampleTrackIndex);
    }
    
    /**
     * Get all sample indices that have this variant.
     */
    public BitSet getSamplePresence() {
        return (BitSet) samplePresence.clone();
    }
    
    /**
     * Get genotype info for a specific sample, or null if not stored.
     */
    public GenotypeInfo getGenotype(int sampleTrackIndex) {
        return genotypeMap != null ? genotypeMap.get(sampleTrackIndex) : null;
    }
    
    /**
     * Count how many samples have this variant.
     */
    public int getSampleCount() {
        return samplePresence.cardinality();
    }
    
    /**
     * Check if this is a heterozygous variant for the given sample.
     */
    public boolean isHeterozygous(int sampleTrackIndex) {
        GenotypeInfo gt = getGenotype(sampleTrackIndex);
        if (gt == null || gt.gt == null) return false;
        // Simple heuristic: het if GT contains both 0 and non-0 alleles
        return gt.gt.matches(".*[01].*[/|].*[01].*") && 
               gt.gt.contains("0") && 
               (gt.gt.contains("1") || gt.gt.contains("2"));
    }
    
    /**
     * Check if this is a homozygous alternate variant for the given sample.
     */
    public boolean isHomozygousAlt(int sampleTrackIndex) {
        GenotypeInfo gt = getGenotype(sampleTrackIndex);
        if (gt == null || gt.gt == null) return false;
        // Hom alt: both alleles are non-reference (e.g., "1/1", "1|1", "2|2")
        return gt.gt.matches("[1-9][/|][1-9]");
    }
    
    @Override
    public String toString() {
        return String.format("VariantNode{pos=%d, %s>%s, type=%s, samples=%d}", 
            position, ref, alt, type, getSampleCount());
    }
}
