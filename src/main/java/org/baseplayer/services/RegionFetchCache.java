package org.baseplayer.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.baseplayer.genome.GenomicRegion;
import org.baseplayer.utils.ChromosomeNames;

/** Tracks which genomic intervals have been fetched per data type (e.g. "VCF", "BAM"). */
public class RegionFetchCache {

    private final Map<String, Map<String, List<GenomicRegion>>> cache = new HashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Record that a region has been fetched for the given data type and chromosome. */
    public synchronized void markFetched(String dataType, String chrom, long start, long end) {
        String key = chromKey(chrom);
        if (key == null) {
            return;
        }
        cache.computeIfAbsent(dataType, k -> new HashMap<>())
             .computeIfAbsent(key, k -> new ArrayList<>())
             .add(new GenomicRegion(key, start, end));
        notifyListeners();
    }

    /** Returns true if any already-fetched interval fully contains the query region. */
    public synchronized boolean isFetched(String dataType, String chrom, long start, long end) {
        Map<String, List<GenomicRegion>> byChrom = cache.get(dataType);
        if (byChrom == null) return false;
        List<GenomicRegion> regions = byChrom.get(chromKey(chrom));
        if (regions == null) return false;
        GenomicRegion query = new GenomicRegion(chromKey(chrom), start, end);
        for (GenomicRegion r : regions) {
            if (r.contains(query)) return true;
        }
        return false;
    }

    public synchronized List<GenomicRegion> getFetched(String dataType, String chrom) {
        Map<String, List<GenomicRegion>> byChrom = cache.get(dataType);
        if (byChrom == null) return Collections.emptyList();
        List<GenomicRegion> regions = byChrom.get(chromKey(chrom));
        return regions == null ? Collections.emptyList() : Collections.unmodifiableList(regions);
    }

    public synchronized void clear(String dataType) {
        cache.remove(dataType);
        notifyListeners();
    }

    public synchronized void clearAll() {
        cache.clear();
        notifyListeners();
    }

    /** Re-run listeners without changing cache contents (e.g. load finished without a new mark). */
    public void notifyChanged() {
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // Keep other listeners running.
            }
        }
    }

    private static String chromKey(String chrom) {
        if (chrom == null || chrom.isBlank()) {
            return null;
        }
        return ChromosomeNames.strip(chrom);
    }
}
