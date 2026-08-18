package org.baseplayer.variant;

import java.util.List;
import java.util.Map;

/**
 * Represents a SNV, small indel, or MNV from a VCF file.
 * Optimized for compact variants where all bases are explicitly specified.
 */
public class VcfSnvIndel {
    private final String chromosome;
    private final long position;
    private final String id;
    private final String ref;
    private final List<String> alt;
    private final double quality;
    private final List<String> filters;
    private final Map<String, Object> info;
    private final VcfVariantType type;
    
    // Genotype information (if present)
    private final Map<String, Map<String, Object>> genotypes;

    public VcfSnvIndel(
            String chromosome,
            long position,
            String id,
            String ref,
            List<String> alt,
            double quality,
            List<String> filters,
            Map<String, Object> info,
            VcfVariantType type,
            Map<String, Map<String, Object>> genotypes) {
        this.chromosome = chromosome;
        this.position = position;
        this.id = id;
        this.ref = ref;
        this.alt = alt;
        this.quality = quality;
        this.filters = filters;
        this.info = info;
        this.type = type;
        this.genotypes = genotypes;
    }

    public String getChromosome() { return chromosome; }
    public long getPosition() { return position; }
    public String getId() { return id; }
    public String getRef() { return ref; }
    public List<String> getAlt() { return alt; }
    public double getQuality() { return quality; }
    public List<String> getFilters() { return filters; }
    public Map<String, Object> getInfo() { return info; }
    public VcfVariantType getType() { return type; }
    public Map<String, Map<String, Object>> getGenotypes() { return genotypes; }
    
    /**
     * Get the end position of this variant.
     * For SNVs, this is the same as position.
     * For indels, this accounts for the reference allele length.
     */
    public long getEnd() {
        return position + ref.length() - 1;
    }
    
    /**
     * Check if this variant passed all filters.
     */
    public boolean isPassed() {
        return filters.isEmpty() || 
               (filters.size() == 1 && "PASS".equals(filters.get(0)));
    }
    
    /**
     * Get specific INFO field value.
     */
    public Object getInfoField(String key) {
        return info.get(key);
    }
    
    /**
     * Get genotype for a specific sample.
     */
    public Map<String, Object> getGenotype(String sampleName) {
        return genotypes != null ? genotypes.get(sampleName) : null;
    }

    @Override
    public String toString() {
        return String.format("%s:%d %s>%s [%s]", 
            chromosome, position, ref, alt, type);
    }
}
