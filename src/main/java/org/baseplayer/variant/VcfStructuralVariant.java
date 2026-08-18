package org.baseplayer.variant;

import java.util.List;
import java.util.Map;

/**
 * Represents a structural variant from a VCF file.
 * Structural variants use symbolic alleles and typically span larger regions.
 */
public class VcfStructuralVariant {
    private final String chromosome;
    private final long position;
    private final String id;
    private final String ref;
    private final List<String> alt;
    private final double quality;
    private final List<String> filters;
    private final Map<String, Object> info;
    private final VcfVariantType type;
    
    // SV-specific fields
    private final Long end;  // Often in INFO field
    private final Integer svLen;  // SV length (may be negative for deletions)
    private final String svType;  // DEL, INS, DUP, INV, BND, etc.
    private final String chr2;  // For translocations
    private final Long end2;  // For translocations
    
    // Genotype information
    private final Map<String, Map<String, Object>> genotypes;

    public VcfStructuralVariant(
            String chromosome,
            long position,
            String id,
            String ref,
            List<String> alt,
            double quality,
            List<String> filters,
            Map<String, Object> info,
            VcfVariantType type,
            Long end,
            Integer svLen,
            String svType,
            String chr2,
            Long end2,
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
        this.end = end;
        this.svLen = svLen;
        this.svType = svType;
        this.chr2 = chr2;
        this.end2 = end2;
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
    public Long getEnd() { return end; }
    public Integer getSvLen() { return svLen; }
    public String getSvType() { return svType; }
    public String getChr2() { return chr2; }
    public Long getEnd2() { return end2; }
    public Map<String, Map<String, Object>> getGenotypes() { return genotypes; }
    
    /**
     * Check if this is an intra-chromosomal SV.
     */
    public boolean isIntraChromosomal() {
        return chr2 == null || chr2.equals(chromosome);
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
    
    /**
     * Get the absolute SV length.
     */
    public int getAbsLength() {
        return svLen != null ? Math.abs(svLen) : 
               (end != null ? (int)(end - position + 1) : 0);
    }

    @Override
    public String toString() {
        return String.format("%s:%d-%s %s [%s] len=%d", 
            chromosome, position, end != null ? end : "?", 
            svType != null ? svType : type, type,
            getAbsLength());
    }
}
