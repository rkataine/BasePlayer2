package org.baseplayer.genome.gene;

/**
 * Simple genomic location for gene search results.
 */
public record GeneLocation(
    String chrom, 
    long start, 
    long end
) {}
