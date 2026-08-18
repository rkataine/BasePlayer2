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
    
    /**
     * Add a variant to the list, maintaining sorted order by position.
     * If a variant already exists at this position with matching ref/alt, 
     * add the sample to that node instead of creating a new one.
     * 
     * @return The VariantNode that was added or updated
     */
    public VariantNode addVariant(long position, String ref, List<String> alt, 
                                   VcfVariantType type, int sampleTrackIndex) {
        return addVariant(position, ref, alt, type, sampleTrackIndex, null);
    }
    
    /**
     * Add a variant with genotype information.
     */
    public VariantNode addVariant(long position, String ref, List<String> alt, 
                                   VcfVariantType type, int sampleTrackIndex,
                                   VariantNode.GenotypeInfo genotype) {
        // Update bounds
        if (position < startPosition) startPosition = position;
        if (position > endPosition) endPosition = position;
        
        // Empty list case
        if (head == null) {
            head = new VariantNode(position, ref, alt, type);
            head.addSample(sampleTrackIndex, genotype);
            tail = head;
            size = 1;
            return head;
        }
        
        // Search for existing node or insertion point
        VariantNode prev = null;
        VariantNode current = head;
        
        while (current != null && current.position < position) {
            prev = current;
            current = current.next;
        }
        
        // Check if we found an exact match (same position, ref, and alt)
        if (current != null && current.position == position && 
            current.ref.equals(ref) && current.alt.equals(alt)) {
            // Add sample to existing node
            current.addSample(sampleTrackIndex, genotype);
            return current;
        }
        
        // Create new node
        VariantNode newNode = new VariantNode(position, ref, alt, type);
        newNode.addSample(sampleTrackIndex, genotype);
        
        // Insert at beginning
        if (prev == null) {
            newNode.next = head;
            head = newNode;
        } else {
            // Insert in middle or at end
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
