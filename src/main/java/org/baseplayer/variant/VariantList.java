package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.List;
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
        int previousCount = visibleByPosition.size();
        for (VariantNode node : visibleByPosition) {
            node.nextVisible = null;
            node.prevVisible = null;
        }
        visibleHead = null;
        visibleByPosition.clear();
        visibleSvByPosition.clear();
        visibleFilterKey = null;
        visibleChainGeneration++;
        System.out.println("[VisibleChain] CLEAR chrom=" + chromosome
            + " clearedNodes=" + previousCount
            + " gen=" + visibleChainGeneration);
    }

    /**
     * Rebuild {@link VariantNode#nextVisible}/{@link VariantNode#prevVisible} and seek indexes
     * for {@code filter}. A node is visible if it passes node-level checks and has at least one
     * sample call passing sample thresholds (same rule as drawing).
     */
    public void rebuildVisibleChain(VariantFilter filter) {
        long t0 = System.nanoTime();
        clearVisibleChain();

        VariantNode prevVisible = null;
        VariantNode current = head;
        int scanned = 0;
        while (current != null) {
            scanned++;
            if (isDrawableUnderFilter(current, filter)) {
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
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("[VisibleChain] REBUILD chrom=" + chromosome
            + " scanned=" + scanned
            + " visible=" + visibleByPosition.size()
            + " sv=" + visibleSvByPosition.size()
            + " head=" + (visibleHead == null ? "null" : ("pos=" + visibleHead.position))
            + " filterKey=" + visibleFilterKey
            + " gen=" + visibleChainGeneration
            + " " + ms + "ms");
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
        System.out.println("[VisibleChain] ENSURE rebuild needed chrom=" + chromosome
            + " oldKey=" + visibleFilterKey
            + " newKey=" + key);
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

    private static boolean isDrawableUnderFilter(VariantNode node, VariantFilter filter) {
        if (node == null || node.getSampleCount() == 0) {
            return false;
        }
        if (filter == null) {
            return true;
        }
        if (!filter.passesNodeLevel(node)) {
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
