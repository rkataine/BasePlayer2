package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.utils.ChromosomeNames;

/**
 * Once-per-SV-VCF index of inter-chromosomal TRA/BND mates.
 *
 * <p>Many SV VCFs report each translocation once; the mate lives only in
 * {@code INFO/CHR2}+{@code END2} or breakend ALT. Tabix cannot query by mate, so this
 * index is built with a single full-file scan and answered by binary search on
 * mate chromosome + position.
 */
public final class MateBreakpointIndex {

    /**
     * One mate breakpoint projected from a primary TRA/BND record.
     *
     * @param matePos       1-based mate coordinate
     * @param primaryChrom  stripped primary contig
     * @param primaryPos    1-based primary POS
     * @param ref           REF allele from the primary record
     * @param alt           ALT allele (breakend / symbolic) from the primary record
     * @param type          {@link VcfVariantType#SV_TRANSLOCATION} or {@link VcfVariantType#SV_BREAKEND}
     * @param vcfId         VCF ID column, or null
     * @param calls         non-empty sample calls to attach when materializing
     */
    public record Hit(
            long matePos,
            String primaryChrom,
            long primaryPos,
            String ref,
            String alt,
            VcfVariantType type,
            String vcfId,
            List<VariantNode.SampleCall> calls) {
        public Hit {
            if (calls == null || calls.isEmpty()) {
                throw new IllegalArgumentException("calls");
            }
            calls = List.copyOf(calls);
            primaryChrom = ChromosomeNames.strip(primaryChrom);
            ref = ref != null ? ref : "N";
            alt = alt != null ? alt : "<TRA>";
            if (type == null) {
                type = VcfVariantType.SV_TRANSLOCATION;
            }
        }
    }

    private final Map<String, Hit[]> byMateChrom;

    private MateBreakpointIndex(Map<String, Hit[]> byMateChrom) {
        this.byMateChrom = byMateChrom;
    }

    /** Empty index (no inter-chrom mates found). */
    public static MateBreakpointIndex empty() {
        return new MateBreakpointIndex(Map.of());
    }

    /**
     * Build an index from unsorted hits. Groups by stripped mate chrom and sorts by matePos.
     */
    public static MateBreakpointIndex fromHits(List<IndexedHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return empty();
        }
        Map<String, List<Hit>> grouped = new LinkedHashMap<>();
        for (IndexedHit indexed : hits) {
            if (indexed == null || indexed.mateChrom() == null || indexed.hit() == null) {
                continue;
            }
            String key = ChromosomeNames.strip(indexed.mateChrom());
            if (key == null || key.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(indexed.hit());
        }
        if (grouped.isEmpty()) {
            return empty();
        }
        Map<String, Hit[]> sorted = new LinkedHashMap<>(grouped.size());
        for (Map.Entry<String, List<Hit>> e : grouped.entrySet()) {
            List<Hit> list = e.getValue();
            list.sort(Comparator.comparingLong(Hit::matePos));
            sorted.put(e.getKey(), list.toArray(Hit[]::new));
        }
        return new MateBreakpointIndex(Collections.unmodifiableMap(sorted));
    }

    /** Hit plus mate-chrom key used while collecting before sort. */
    public record IndexedHit(String mateChrom, Hit hit) {}

    public boolean isEmpty() {
        return byMateChrom.isEmpty();
    }

    public int size() {
        int n = 0;
        for (Hit[] arr : byMateChrom.values()) {
            n += arr.length;
        }
        return n;
    }

    /**
     * Hits whose mate lands on {@code mateChrom} inside inclusive {@code [start, end]}.
     * Chromosome names are stripped. Returns an empty list when nothing matches.
     */
    public List<Hit> query(String mateChrom, long start, long end) {
        String key = ChromosomeNames.strip(mateChrom);
        if (key == null || key.isBlank()) {
            return List.of();
        }
        Hit[] arr = byMateChrom.get(key);
        if (arr == null || arr.length == 0) {
            return List.of();
        }
        long lo = Math.min(start, end);
        long hi = Math.max(start, end);
        int from = lowerBound(arr, lo);
        int to = upperBound(arr, hi);
        if (from >= to) {
            return List.of();
        }
        return Arrays.asList(arr).subList(from, to);
    }

    /** First index with {@code matePos >= pos}. */
    private static int lowerBound(Hit[] arr, long pos) {
        int lo = 0;
        int hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].matePos() < pos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** First index with {@code matePos > pos}. */
    private static int upperBound(Hit[] arr, long pos) {
        int lo = 0;
        int hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].matePos() <= pos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
