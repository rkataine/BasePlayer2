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
    COMPLEX,

    /**
     * Synthetic LOH region — homozygous REF (AA) run.
     * Appended after {@link #COMPLEX} so on-disk variant-cache ordinals stay stable.
     */
    LOH_AA,

    /** Synthetic LOH region — homozygous ALT (BB) run */
    LOH_BB,

    /**
     * Copy-number segment ({@code <CNV>} / {@code SVTYPE=CNV}) when gain/loss/neutral
     * cannot be determined. Appended after LOH so cache ordinals stay stable.
     */
    SV_CNV,

    /** Copy-number gain / amplification ({@code TCN_EM > 2} or positive logR). */
    SV_CNV_GAIN,

    /** Copy-number loss ({@code TCN_EM < 2} or negative logR). */
    SV_CNV_LOSS,

    /** Copy-neutral segment ({@code TCN_EM == 2} or near-zero logR). */
    SV_CNV_NEUTRAL
}
