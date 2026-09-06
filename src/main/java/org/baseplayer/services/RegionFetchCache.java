package org.baseplayer.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.genome.GenomicRegion;

/** Tracks which genomic intervals have been fetched per data type (e.g. "VCF", "BAM"). */
public class RegionFetchCache {

    private final Map<String, Map<String, List<GenomicRegion>>> cache = new HashMap<>();

    /** Record that a region has been fetched for the given data type and chromosome. */
    public synchronized void markFetched(String dataType, String chrom, long start, long end) {
        cache.computeIfAbsent(dataType, k -> new HashMap<>())
             .computeIfAbsent(chrom, k -> new ArrayList<>())
             .add(new GenomicRegion(chrom, start, end));
    }

    /** Returns true if any already-fetched interval fully contains the query region. */
    public synchronized boolean isFetched(String dataType, String chrom, long start, long end) {
        Map<String, List<GenomicRegion>> byChrom = cache.get(dataType);
        if (byChrom == null) return false;
        List<GenomicRegion> regions = byChrom.get(chrom);
        if (regions == null) return false;
        GenomicRegion query = new GenomicRegion(chrom, start, end);
        for (GenomicRegion r : regions) {
            if (r.contains(query)) return true;
        }
        return false;
    }

    public synchronized List<GenomicRegion> getFetched(String dataType, String chrom) {
        Map<String, List<GenomicRegion>> byChrom = cache.get(dataType);
        if (byChrom == null) return Collections.emptyList();
        List<GenomicRegion> regions = byChrom.get(chrom);
        return regions == null ? Collections.emptyList() : Collections.unmodifiableList(regions);
    }

    public synchronized void clear(String dataType) {
        cache.remove(dataType);
    }

    public synchronized void clearAll() {
        cache.clear();
    }
}
