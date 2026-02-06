package org.baseplayer.annotation;

/**
 * Represents a cytogenetic band on a chromosome.
 */
public record Cytoband(
    String chrom, 
    long start, 
    long end, 
    String name, 
    String stain
) {}
