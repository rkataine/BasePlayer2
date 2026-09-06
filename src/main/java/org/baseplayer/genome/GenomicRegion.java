package org.baseplayer.genome;

/** Immutable genomic coordinate range (1-based, inclusive). */
public record GenomicRegion(String chrom, long start, long end) {

    public boolean overlaps(GenomicRegion other) {
        return chrom.equals(other.chrom) && start <= other.end && end >= other.start;
    }

    public boolean contains(GenomicRegion other) {
        return chrom.equals(other.chrom) && start <= other.start && end >= other.end;
    }
}
