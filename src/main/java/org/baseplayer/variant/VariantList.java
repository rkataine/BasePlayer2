package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

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
                                   VcfVariantType type, int sampleTrackIndex) {
        return addVariant(position, ref, alt, type, sampleTrackIndex, null);
    }

    public VariantNode addVariant(long position, String ref, String alt,
                                   VcfVariantType type, int sampleTrackIndex,
                                   VariantNode.SampleCall call) {
        if (position < startPosition) startPosition = position;
        if (position > endPosition) endPosition = position;

        if (head == null) {
            head = new VariantNode(position, ref, alt, type);
            head.addSample(sampleTrackIndex, call);
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
            current.addSample(sampleTrackIndex, call);
            return current;
        }

        VariantNode newNode = new VariantNode(position, ref, alt, type);
        newNode.addSample(sampleTrackIndex, call);

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
            String alt, VcfVariantType type, int sampleTrackIndex,
            VariantNode.SampleCall call) {
        if (position < startPosition) startPosition = position;
        if (position > endPosition) endPosition = position;

        if (head == null) {
            head = new VariantNode(position, ref, alt, type);
            head.addSample(sampleTrackIndex, call);
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
            current.addSample(sampleTrackIndex, call);
            return current;
        }

        VariantNode newNode = new VariantNode(position, ref, alt, type);
        newNode.addSample(sampleTrackIndex, call);
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
     */
    public VariantNode findFirstAfter(long position) {
        VariantNode current = head;
        while (current != null && current.position < position) {
            current = current.next;
        }
        
        return current;
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
     * Remove all variants for a specific track index.
     * If a variant has no more samples after removal, it's removed from the list.
     * @param trackIndex The track index to remove
     * @return The number of variant nodes removed
     */
    public int removeTrackIndex(int trackIndex) {
        if (head == null) return 0;
        
        int nodesRemoved = 0;
        VariantNode prev = null;
        VariantNode current = head;
        
        while (current != null) {
            VariantNode next = current.next;
            boolean isEmpty = current.removeSample(trackIndex);
            
            if (isEmpty) {
                // Remove this node from the list
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
                    current.removeSample(call.trackIndex);
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
