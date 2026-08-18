package org.baseplayer.variant;

/**
 * VCF variant type classification.
 */
public enum VcfVariantType {
    /** Single nucleotide variant */
    SNV,
    
    /** Insertion */
    INSERTION,
    
    /** Deletion */
    DELETION,
    
    /** Multiple nucleotide variant (MNV) */
    MNV,
    
    /** Structural variant - deletion */
    SV_DELETION,
    
    /** Structural variant - insertion */
    SV_INSERTION,
    
    /** Structural variant - duplication */
    SV_DUPLICATION,
    
    /** Structural variant - inversion */
    SV_INVERSION,
    
    /** Structural variant - translocation */
    SV_TRANSLOCATION,
    
    /** Structural variant - breakend */
    SV_BREAKEND,
    
    /** Complex or unknown variant type */
    COMPLEX
}
