package org.baseplayer.variant;

import java.util.ArrayList;
import java.util.List;

/**
 * Sorted linked list of variants by genomic position.
 * Memory-efficient: one node per unique genomic position, shared across all samples.
 * 
 * This structure is optimized for:
 * - Sequential iteration during drawing (cache-friendly)
 * - Memory efficiency (shared nodes across samples)
 * - Fast range queries (start/end position bounds)
 */
public class VariantList {
    
    private VariantNode head;
    private VariantNode tail;
    private int size;
    
    /** Genomic region bounds for this variant list */
    private final String chromosome;
    private long startPosition;
    private long endPosition;
    
    public VariantList(String chromosome) {
        this.chromosome = chromosome;
        this.head = null;
        this.tail = null;
        this.size = 0;
        this.startPosition = Long.MAX_VALUE;
        this.endPosition = Long.MIN_VALUE;
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
     * Clear all variants from the list.
     */
    public void clear() {
        head = null;
        tail = null;
        size = 0;
        startPosition = Long.MAX_VALUE;
        endPosition = Long.MIN_VALUE;
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
}
