package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.baseplayer.samples.SampleTrack;

/**
 * Sorted linked list of variants by genomic position.
 * Memory-efficient: one node per unique genomic position, shared across all samples.
 * 
 * Single source of truth for all variant data and metadata for a chromosome:
 * - Variant nodes (linked list)
 * - Loaded regions (which parts have been fetched)
 * - Filter used to load these variants
 * - Annotation status
 * 
 * This structure is optimized for:
 * - Sequential iteration during drawing (cache-friendly)
 * - Memory efficiency (shared nodes across samples)
 * - Fast range queries (start/end position bounds)
 * - Incremental loading (track what's loaded, add new VCF samples to existing lists)
 */
public class VariantList {
    
    private VariantNode head;
    private VariantNode tail;
    private int size;
    
    /** Genomic region bounds for this variant list */
    private final String chromosome;
    private long startPosition;
    private long endPosition;
    
    /** Tracks which genomic regions have been loaded */
    private final List<LoadedRegion> loadedRegions = new ArrayList<>();
    
    /** Stable key for the filter used to load these variants (for detecting filter changes) */
    private String loadedFilterKey;
    
    /** Filter that was used to load these variants */
    private VariantFilter loadedFilter;
    
    /** Whether these variants have been annotated */
    private boolean annotated = false;
    
    private int vcfCountWhenLoaded = 0;

    /** First drawable node under {@link #visibleFilterKey}. */
    private VariantNode visibleHead;
    /** Drawable nodes in genomic order (for binary seek). */
    private final ArrayList<VariantNode> visibleByPosition = new ArrayList<>();
    /** Drawable spanning SVs in genomic order. */
    private final ArrayList<VariantNode> visibleSvByPosition = new ArrayList<>();
    /** Stable key of the filter used to build the visible chain; null if unset/dirty. */
    private String visibleFilterKey;
    /** Bumped on every visible-chain clear/rebuild so draw seekers can detect staleness. */
    private int visibleChainGeneration;

    /**
     * Gene name (lower case) → track indices of samples with a qualifying mutation in that gene.
     * Built for the current {@link #geneSampleIndexFilterKey}; used for gene-level filtering
     * and gene-click sample subsets. Not stored on annotation gene objects.
     */
    private Map<String, Set<Integer>> geneSampleIndex = Map.of();
    private String geneSampleIndexFilterKey;

    /**
     * Soft-match cluster → union of mutated sample track indices (identity-keyed by node).
     * Built when {@link VariantFilter#hasComparisonWindow()} is true.
     */
    private Map<VariantNode, Set<Integer>> clusterSampleIndex = Map.of();
    private String clusterSampleIndexFilterKey;
    
    /**
     * Tracks a genomic region that has been loaded.
     * Used to detect if a new region needs loading when viewing different parts of the chromosome.
     */
    public static class LoadedRegion {
        public final long start;
        public final long end;
        
        public LoadedRegion(long start, long end) {
            this.start = start;
            this.end = end;
        }
        
        @Override
        public String toString() {
            return String.format("[%d-%d]", start, end);
        }
    }
    
    public VariantList(String chromosome) {
        this.chromosome = chromosome;
        this.head = null;
        this.tail = null;
        this.size = 0;
        this.startPosition = Long.MAX_VALUE;
        this.endPosition = Long.MIN_VALUE;
        this.loadedFilterKey = null;
        this.loadedFilter = null;
        this.annotated = false;
        this.vcfCountWhenLoaded = 0;
    }
    
    public VariantNode addVariant(long position, String ref, String alt,
                                   VcfVariantType type, VariantNode.SampleCall call) {
        if (call == null || call.getTrackIndex() < 0) {
            return null;
        }

        if (position < startPosition) startPosition = position;
        if (position > endPosition) endPosition = position;

        if (head == null) {
            head = new VariantNode(position, ref, alt, type);
            head.addSample(call);
            tail = head;
            size = 1;
            invalidateSampleIndexes();
            return head;
        }

        VariantNode prev = null;
        VariantNode current = head;

        while (current != null && current.position < position) {
            prev = current;
            current = current.next;
        }

        if (current != null && current.position == position &&
            current.ref.equals(ref) && current.alt.equals(alt)) {
            current.addSample(call);
            invalidateSampleIndexes();
            return current;
        }

        VariantNode newNode = new VariantNode(position, ref, alt, type);
        newNode.addSample(call);

        if (prev == null) {
            newNode.next = head;
            head = newNode;
        } else {
            newNode.next = current;
            prev.next = newNode;
            if (current == null) {
                tail = newNode;
            }
        }

        size++;
        invalidateSampleIndexes();
        return newNode;
    }

    /**
     * Like addVariant, but starts scanning from {@code cursor} instead of from the list head.
     * Since VCF records arrive in position-sorted order, passing the previously returned node
     * as the cursor reduces each insertion from O(n) to O(1) amortised.
     *
     * @param cursor last node returned by a previous call (null = start from head)
     * @return the inserted or updated node – pass it as cursor to the next call
     */
    public VariantNode addVariantWithCursor(VariantNode cursor, long position, String ref,
            String alt, VcfVariantType type, VariantNode.SampleCall call) {
        if (call == null || call.getTrackIndex() < 0) {
            return cursor;
        }

        if (position < startPosition) startPosition = position;
        if (position > endPosition) endPosition = position;

        if (head == null) {
            head = new VariantNode(position, ref, alt, type);
            head.addSample(call);
            tail = head;
            size = 1;
            invalidateSampleIndexes();
            return head;
        }

        VariantNode prev;
        VariantNode current;
        if (cursor != null && cursor.position <= position) {
            prev = cursor;
            current = cursor.next;
        } else {
            prev = null;
            current = head;
        }

        while (current != null && current.position < position) {
            prev = current;
            current = current.next;
        }

        if (current != null && current.position == position
                && current.ref.equals(ref) && current.alt.equals(alt)) {
            current.addSample(call);
            invalidateSampleIndexes();
            return current;
        }

        VariantNode newNode = new VariantNode(position, ref, alt, type);
        newNode.addSample(call);
        if (prev == null) {
            newNode.next = head;
            head = newNode;
        } else {
            newNode.next = current;
            prev.next = newNode;
            if (current == null) tail = newNode;
        }
        size++;
        invalidateSampleIndexes();
        return newNode;
    }

    /**
     * Get the first variant node in the list.
     */
    public VariantNode getFirst() {
        return head;
    }
    
    /**
     * Get the last variant node in the list.
     */
    public VariantNode getLast() {
        return tail;
    }
    
    /**
     * Find the first variant node at or after the given position.
     * Returns null if no such node exists.
     * Prefer {@link #findFirstVisibleAtOrAfter(long)} for drawing.
     */
    public VariantNode findFirstAfter(long position) {
        VariantNode current = head;
        while (current != null && current.position < position) {
            current = current.next;
        }
        
        return current;
    }

    /**
     * Invalidate the filter-visible skip chain. Next {@link #ensureVisibleChain} rebuilds it.
     */
    public void clearVisibleChain() {
        for (VariantNode node : visibleByPosition) {
            node.nextVisible = null;
            node.prevVisible = null;
        }
        visibleHead = null;
        visibleByPosition.clear();
        visibleSvByPosition.clear();
        visibleFilterKey = null;
        visibleChainGeneration++;
        // Mutations and filter rebuilds both go through here; drop stale gene→sample unions.
        clearGeneSampleIndex();
        clearClusterSampleIndex();
    }

    /**
     * Rebuild {@link VariantNode#nextVisible}/{@link VariantNode#prevVisible} and seek indexes
     * for {@code filter}. A node is visible if it passes node-level checks and has at least one
     * sample call passing sample thresholds (same rule as drawing).
     */
    public void rebuildVisibleChain(VariantFilter filter) {
        clearVisibleChain();

        Map<String, Set<Integer>> geneTracks = null;
        Map<VariantNode, Set<Integer>> clusterTracks = null;
        if (filter != null && filter.isGeneLevel()) {
            geneTracks = ensureGeneSampleIndex(filter);
        } else if (filter != null && filter.hasComparisonWindow()) {
            clusterTracks = ensureClusterSampleIndex(filter);
        }

        VariantNode prevVisible = null;
        VariantNode current = head;
        while (current != null) {
            if (isDrawableUnderFilter(current, filter, geneTracks, clusterTracks)) {
                current.nextVisible = null;
                current.prevVisible = prevVisible;
                if (prevVisible == null) {
                    visibleHead = current;
                } else {
                    prevVisible.nextVisible = current;
                }
                prevVisible = current;
                visibleByPosition.add(current);
                if (current.svEnd > current.position) {
                    visibleSvByPosition.add(current);
                }
            } else {
                current.nextVisible = null;
                current.prevVisible = null;
            }
            current = current.next;
        }

        visibleFilterKey = filterKeyOf(filter);
    }

    /**
     * Build or reuse gene → mutated sample track indices under {@code filter}'s base
     * (type/effect/Q/DP/AF) rules. Safe to call when gene-level mode is off (still useful
     * for gene-click subsets).
     */
    public Map<String, Set<Integer>> ensureGeneSampleIndex(VariantFilter filter) {
        String key = geneSampleIndexKey(filter);
        if (key.equals(geneSampleIndexFilterKey) && geneSampleIndex != null) {
            return geneSampleIndex;
        }
        Map<String, Set<Integer>> index = new HashMap<>();
        VariantNode current = head;
        while (current != null) {
            if (filter == null || filter.passesBaseNodeLevel(current)) {
                String gene = geneKeyOf(current);
                if (gene != null) {
                    Set<Integer> tracks = index.computeIfAbsent(gene, g -> new HashSet<>());
                    for (VariantNode.SampleCall call : current.getSamples()) {
                        if (call == null) continue;
                        if (filter != null && !filter.passesSampleThresholds(current, call)) {
                            continue;
                        }
                        int trackIndex = call.getTrackIndex();
                        if (trackIndex >= 0) {
                            tracks.add(trackIndex);
                        }
                    }
                }
            }
            current = current.next;
        }
        geneSampleIndex = index;
        geneSampleIndexFilterKey = key;
        return index;
    }

    /** Track indices mutated in {@code geneName} under the filter's base sample rules. */
    public Set<Integer> getGeneSampleTracks(String geneName, VariantFilter filter) {
        if (geneName == null || geneName.isBlank()) {
            return Set.of();
        }
        Map<String, Set<Integer>> index = ensureGeneSampleIndex(filter);
        Set<Integer> tracks = index.get(geneName.toLowerCase(Locale.ROOT));
        return tracks != null ? tracks : Set.of();
    }

    public void clearGeneSampleIndex() {
        geneSampleIndex = Map.of();
        geneSampleIndexFilterKey = null;
    }

    /**
     * Build or reuse per-variant local window → sample track unions for comparison window.
     * For each allele, samples are the union of all soft-matching alleles within the window
     * (non-transitive), so min/max shared-sample bounds apply to local hotspot density —
     * including dropping fragile/repeat clusters via the max thumb.
     * Gene-level mode takes precedence and should not call this.
     */
    public Map<VariantNode, Set<Integer>> ensureClusterSampleIndex(VariantFilter filter) {
        if (filter == null || !filter.hasComparisonWindow()) {
            return Map.of();
        }
        String key = clusterSampleIndexKey(filter);
        if (key.equals(clusterSampleIndexFilterKey) && clusterSampleIndex != null) {
            return clusterSampleIndex;
        }

        ArrayList<VariantNode> nodes = new ArrayList<>();
        VariantNode current = head;
        while (current != null) {
            if (filter.passesBaseNodeLevel(current)) {
                nodes.add(current);
            }
            current = current.next;
        }

        int n = nodes.size();
        @SuppressWarnings("unchecked")
        Set<Integer>[] nodeTracks = new Set[n];
        for (int i = 0; i < n; i++) {
            Set<Integer> tracks = new HashSet<>();
            for (VariantNode.SampleCall call : nodes.get(i).getSamples()) {
                if (call == null) continue;
                if (!filter.passesSampleThresholds(nodes.get(i), call)) continue;
                int trackIndex = call.getTrackIndex();
                if (trackIndex >= 0) {
                    tracks.add(trackIndex);
                }
            }
            nodeTracks[i] = tracks;
        }

        // Sweep within compatible families; indel/SV cross-match uses a shared "svish" bucket.
        Map<String, ArrayList<Integer>> byFamily = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String family = sweepBucket(nodes.get(i).type);
            byFamily.computeIfAbsent(family, f -> new ArrayList<>()).add(i);
        }

        int windowBp = filter.getComparisonWindowBp();
        Map<VariantNode, Set<Integer>> index = new java.util.IdentityHashMap<>(Math.max(16, n * 2));
        for (ArrayList<Integer> idxs : byFamily.values()) {
            for (int a = 0; a < idxs.size(); a++) {
                int i = idxs.get(a);
                VariantNode ni = nodes.get(i);
                Set<Integer> union = new HashSet<>(nodeTracks[i]);
                long iStart = ni.position;
                long iEnd = VariantComparisonClusters.clusterEnd(ni);
                long leftBound = iStart - 2L * windowBp;
                long rightBound = iEnd + 2L * windowBp;

                for (int b = a - 1; b >= 0; b--) {
                    int j = idxs.get(b);
                    VariantNode nj = nodes.get(j);
                    if (VariantComparisonClusters.clusterEnd(nj) < leftBound) {
                        break;
                    }
                    if (VariantComparisonClusters.softMatch(ni, nj, windowBp)) {
                        union.addAll(nodeTracks[j]);
                    }
                }
                for (int b = a + 1; b < idxs.size(); b++) {
                    int j = idxs.get(b);
                    VariantNode nj = nodes.get(j);
                    if (nj.position > rightBound) {
                        break;
                    }
                    if (VariantComparisonClusters.softMatch(ni, nj, windowBp)) {
                        union.addAll(nodeTracks[j]);
                    }
                }
                index.put(ni, union);
            }
        }

        clusterSampleIndex = index;
        clusterSampleIndexFilterKey = key;
        return index;
    }

    /** Sweep bucket: SNVs alone; indels+SVs together for fragile-site soft-match. */
    private static String sweepBucket(VcfVariantType type) {
        String family = VariantComparisonClusters.typeFamily(type);
        if ("del".equals(family) || "ins".equals(family) || "sv".equals(family)) {
            return "svish";
        }
        return family;
    }

    public void clearClusterSampleIndex() {
        clusterSampleIndex = Map.of();
        clusterSampleIndexFilterKey = null;
    }

    private void invalidateSampleIndexes() {
        geneSampleIndexFilterKey = null;
        clusterSampleIndexFilterKey = null;
    }

    private static String geneKeyOf(VariantNode node) {
        if (node == null || node.annotation == null || node.annotation.geneName() == null) {
            return null;
        }
        String name = node.annotation.geneName().trim();
        return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
    }

    /**
     * Index key ignores shared-sample / group / geneLevel / window flags so the same index
     * serves gene-level filtering and gene-click extraction.
     */
    private static String geneSampleIndexKey(VariantFilter filter) {
        if (filter == null) {
            return "";
        }
        return "base|"
            + filter.getMinQuality()
            + "|" + filter.getMinDepth()
            + "|" + filter.getMinAlleleFraction()
            + "|" + filter.isCancerGenesOnly()
            + "|" + filter.isShowCoding()
            + "|" + filter.isShowIntronic()
            + "|" + filter.isShowIntergenic()
            + "|" + filter.getAllowedTypes()
            + "|" + filter.getAllowedEffects()
            + "|" + filter.getInfoFieldFilters()
            + "|" + filter.getAllowedFilterValues()
            + "|" + filter.isFilterFieldsActive();
    }

    private static String clusterSampleIndexKey(VariantFilter filter) {
        return geneSampleIndexKey(filter) + "|cmpWindow=" + (filter == null ? 0 : filter.getComparisonWindowBp());
    }

    /** Generation counter for the filter-visible skip chain; seekers use this to invalidate. */
    public int getVisibleChainGeneration() {
        return visibleChainGeneration;
    }

    /** Number of nodes currently linked in the filter-visible skip chain. */
    public int getVisibleNodeCount() {
        return visibleByPosition.size();
    }

    /**
     * Rebuild the visible chain if it is missing or built for a different filter.
     */
    public void ensureVisibleChain(VariantFilter filter) {
        String key = filterKeyOf(filter);
        if (key.equals(visibleFilterKey)) {
            return;
        }
        rebuildVisibleChain(filter);
    }

    /**
     * First drawable node at or after {@code position}, via binary seek on the visible index.
     */
    public VariantNode findFirstVisibleAtOrAfter(long position) {
        if (visibleByPosition.isEmpty()) {
            return null;
        }
        int lo = 0;
        int hi = visibleByPosition.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (visibleByPosition.get(mid).position < position) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo < visibleByPosition.size() ? visibleByPosition.get(lo) : null;
    }

    public VariantNode getVisibleHead() {
        return visibleHead;
    }

    /**
     * Drawable spanning SVs (svEnd &gt; position), genomic order. Used to paint
     * spans that start upstream of the view without scanning from chromosome start.
     */
    public List<VariantNode> getVisibleSvByPosition() {
        return visibleSvByPosition;
    }

    public String getVisibleFilterKey() {
        return visibleFilterKey;
    }

    private static String filterKeyOf(VariantFilter filter) {
        return filter == null ? "" : filter.toStableKey();
    }


    private static boolean isDrawableUnderFilter(
            VariantNode node,
            VariantFilter filter,
            Map<String, Set<Integer>> geneSampleIndex,
            Map<VariantNode, Set<Integer>> clusterSampleIndex) {
        if (node == null || node.getSampleCount() == 0) {
            return false;
        }
        if (filter == null) {
            return true;
        }
        Set<Integer> aggregatedTracks = null;
        if (filter.isGeneLevel() && geneSampleIndex != null) {
            String gene = geneKeyOf(node);
            if (gene != null) {
                aggregatedTracks = geneSampleIndex.getOrDefault(gene, Set.of());
            } else {
                aggregatedTracks = Set.of();
            }
        } else if (filter.hasComparisonWindow() && clusterSampleIndex != null) {
            aggregatedTracks = clusterSampleIndex.getOrDefault(node, Set.of());
        }
        if (!filter.passesNodeLevel(node, aggregatedTracks)) {
            return false;
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (filter.passesSampleThresholds(node, call)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get variants in a specific genomic range.
     * Returns a list for compatibility, but iteration via nodes is more efficient.
     */
    public List<VariantNode> getVariantsInRange(long start, long end) {
        List<VariantNode> result = new ArrayList<>();
        VariantNode current = findFirstAfter(start);
        
        while (current != null && current.position <= end) {
            result.add(current);
            current = current.next;
        }
        
        return result;
    }
    
    /**
     * Count variants in the list.
     */
    public int size() {
        return size;
    }
    
    /**
     * Check if the list is empty.
     */
    public boolean isEmpty() {
        return head == null;
    }
    
    /**
     * Remove all variants for a specific sample track object.
     * If a variant has no more samples after removal, it's removed from the list.
     * @param track The sample track to remove
     * @return The number of variant nodes removed
     */
    public int removeTrack(SampleTrack track) {
        if (head == null || track == null) return 0;

        int nodesRemoved = 0;
        VariantNode prev = null;
        VariantNode current = head;

        while (current != null) {
            VariantNode next = current.next;
            boolean isEmpty = current.removeSample(track);

            if (isEmpty) {
                if (prev == null) {
                    head = next;
                } else {
                    prev.next = next;
                }

                if (current == tail) {
                    tail = prev;
                }

                size--;
                nodesRemoved++;
            } else {
                prev = current;
            }

            current = next;
        }

        clearVisibleChain();
        invalidateSampleIndexes();
        return nodesRemoved;
    }

    /**
     * Retain only sample calls that satisfy {@code keepPredicate}. Variant nodes with no
     * remaining samples are removed from the list.
     */
    public void retainSamples(BiPredicate<VariantNode, VariantNode.SampleCall> keepPredicate) {
        if (head == null || keepPredicate == null) return;

        VariantNode prev = null;
        VariantNode current = head;

        while (current != null) {
            VariantNode next = current.next;

            // Snapshot because getSamples() returns an unmodifiable live view.
            List<VariantNode.SampleCall> calls = new ArrayList<>(current.getSamples());
            for (VariantNode.SampleCall call : calls) {
                if (!keepPredicate.test(current, call)) {
                    current.removeSample(call);
                }
            }

            if (current.getSampleCount() == 0) {
                if (prev == null) {
                    head = next;
                } else {
                    prev.next = next;
                }
                if (current == tail) {
                    tail = prev;
                }
                size--;
            } else {
                prev = current;
            }

            current = next;
        }

        recalculateBounds();
        clearVisibleChain();
    }

    /**
     * Remove variants that don't satisfy {@code keepPredicate}.
     * Useful for filtering variants based on annotation (e.g., effect type).
     */
    public void retainVariants(java.util.function.Predicate<VariantNode> keepPredicate) {
        if (head == null || keepPredicate == null) return;

        VariantNode prev = null;
        VariantNode current = head;

        while (current != null) {
            VariantNode next = current.next;

            if (!keepPredicate.test(current)) {
                // Remove this node
                if (prev == null) {
                    head = next;
                } else {
                    prev.next = next;
                }
                if (current == tail) {
                    tail = prev;
                }
                size--;
            } else {
                prev = current;
            }

            current = next;
        }

        recalculateBounds();
        clearVisibleChain();
    }

    private void recalculateBounds() {
        if (head == null) {
            tail = null;
            startPosition = Long.MAX_VALUE;
            endPosition = Long.MIN_VALUE;
            return;
        }
        startPosition = head.position;
        VariantNode cur = head;
        long maxPos = head.position;
        VariantNode last = head;
        while (cur != null) {
            if (cur.position > maxPos) maxPos = cur.position;
            last = cur;
            cur = cur.next;
        }
        endPosition = maxPos;
        tail = last;
    }
    
    /**
     * Clear all variants from the list and reset all metadata.
     */
    public void clear() {
        clearVisibleChain();
        clearGeneSampleIndex();
        head = null;
        tail = null;
        size = 0;
        startPosition = Long.MAX_VALUE;
        endPosition = Long.MIN_VALUE;
        loadedRegions.clear();
        loadedFilterKey = null;
        loadedFilter = null;
        annotated = false;
        vcfCountWhenLoaded = 0;
    }
    
    /**
     * Get the chromosome this list covers.
     */
    public String getChromosome() {
        return chromosome;
    }
    
    /**
     * Get the start position of variants in this list.
     */
    public long getStartPosition() {
        return startPosition;
    }
    
    /**
     * Get the end position of variants in this list.
     */
    public long getEndPosition() {
        return endPosition;
    }
    
    /**
     * Check if this list covers the given genomic range.
     */
    public boolean coversRange(long start, long end) {
        return !isEmpty() && startPosition <= start && endPosition >= end;
    }
    
    /**
     * Get statistics about this variant list.
     */
    public String getStats() {
        if (isEmpty()) {
            return "Empty variant list";
        }
        
        int totalSampleOccurrences = 0;
        int minSamples = Integer.MAX_VALUE;
        int maxSamples = 0;
        
        VariantNode current = head;
        while (current != null) {
            int count = current.getSampleCount();
            totalSampleOccurrences += count;
            minSamples = Math.min(minSamples, count);
            maxSamples = Math.max(maxSamples, count);
            current = current.next;
        }
        
        double avgSamplesPerVariant = (double) totalSampleOccurrences / size;
        
        return String.format(
            "VariantList: %d variants on %s:%d-%d, avg %.1f samples/variant (min=%d, max=%d)",
            size, chromosome, startPosition, endPosition, 
            avgSamplesPerVariant, minSamples, maxSamples);
    }
    
    @Override
    public String toString() {
        return String.format("VariantList[%s:%d-%d, n=%d]", 
            chromosome, startPosition, endPosition, size);
    }
    
    /**
     * Check if the given region has been loaded.
     * Returns true if the range [start, end] is fully covered by loaded regions.
     */
    public boolean isRegionLoaded(long start, long end) {
        for (LoadedRegion region : loadedRegions) {
            if (region.start <= start && region.end >= end) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Record that a genomic region has been loaded.
     * Merges overlapping/adjacent regions to keep the list compact.
     */
    public void addLoadedRegion(long start, long end) {
        if (start > end) return;
        
        // Merge with overlapping or adjacent regions
        long mergedStart = start;
        long mergedEnd = end;
        List<LoadedRegion> toRemove = new ArrayList<>();
        
        for (LoadedRegion r : loadedRegions) {
            if (r.end < mergedStart - 1 || r.start > mergedEnd + 1) {
                continue;  // No overlap or adjacency
            }
            // Overlapping or adjacent: merge
            mergedStart = Math.min(mergedStart, r.start);
            mergedEnd = Math.max(mergedEnd, r.end);
            toRemove.add(r);
        }
        
        loadedRegions.removeAll(toRemove);
        loadedRegions.add(new LoadedRegion(mergedStart, mergedEnd));
    }
    
    /**
     * Get all loaded regions for this chromosome.
     */
    public List<LoadedRegion> getLoadedRegions() {
        return new ArrayList<>(loadedRegions);
    }
    
    /**
     * Set the filter key that was used to load these variants.
     * The key is a stable identifier used to detect when the filter changes.
     */
    public void setLoadedFilterKey(String filterKey) {
        this.loadedFilterKey = filterKey;
    }
    
    /**
     * Get the filter key used to load these variants.
     */
    public String getLoadedFilterKey() {
        return loadedFilterKey;
    }
    
    /**
     * Set the filter that was used to load these variants.
     * Used to detect when filter changes require a reload.
     */
    public void setLoadedFilter(VariantFilter filter) {
        this.loadedFilter = filter;
    }
    
    /**
     * Get the filter used to load these variants.
     */
    public VariantFilter getLoadedFilter() {
        return loadedFilter;
    }
    
    /**
     * Mark whether these variants have been annotated.
     */
    public void setAnnotated(boolean annotated) {
        this.annotated = annotated;
        // Gene names drive the sample index; invalidate when annotation state changes.
        clearGeneSampleIndex();
    }
    
    /**
     * Check if these variants have been annotated.
     */
    public boolean isAnnotated() {
        return annotated;
    }

    public void setVcfCountWhenLoaded(int count) {
        this.vcfCountWhenLoaded = count;
    }

    public int getVcfCountWhenLoaded() {
        return vcfCountWhenLoaded;
    }

    /**
     * Append a fully-built node from session cache. Caller must add nodes in
     * non-decreasing genomic position order. Does not rebuild visible chains.
     */
    public void appendNodeFromCache(VariantNode node) {
        if (node == null) return;
        node.next = null;
        node.nextVisible = null;
        node.prevVisible = null;

        if (head == null) {
            head = node;
            tail = node;
            size = 1;
            startPosition = node.position;
            endPosition = node.position;
            return;
        }

        tail.next = node;
        tail = node;
        size++;
        if (node.position < startPosition) startPosition = node.position;
        if (node.position > endPosition) endPosition = node.position;
    }
    
    /**
     * Collect all unique variant types present in this list.
     * Useful for dynamically generating filter UI based on actual data.
     */
    public java.util.Set<VcfVariantType> collectVariantTypes() {
        java.util.Set<VcfVariantType> types = new java.util.HashSet<>();
        VariantNode current = head;
        while (current != null) {
            types.add(current.type);
            current = current.next;
        }
        return types;
    }
}
