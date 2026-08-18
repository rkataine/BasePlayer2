package org.baseplayer.variant;

import java.util.List;

/**
 * Lightweight drawing index for efficiently rendering variants in visible samples.
 * 
 * Rebuilds on-demand when visibility changes (filtering, scrolling) to map
 * visible sample slots to their screen Y positions and first relevant variant node.
 * 
 * Key optimization: When drawing, we iterate the VariantList once and use this
 * index to quickly determine which screen Y positions to draw at, avoiding
 * iteration over hidden samples.
 */
public class VisibleVariantIndex {
    
    /** Y position for each visible sample slot */
    private double[] yPositions;
    
    /** Sample track index for each visible slot */
    private int[] sampleTrackIndices;
    
    /** Number of visible slots */
    private int visibleCount;
    
    /** Whether this index needs to be rebuilt before drawing */
    private boolean dirty;
    
    /** Last rebuild parameters (to detect when rebuild is needed) */
    private int lastFirstVisible = -1;
    private int lastLastVisible = -1;
    private double lastScrollBarPosition = Double.NaN;
    private double lastSampleHeight = Double.NaN;
    private int lastDisplayedTrackCount = -1;
    
    public VisibleVariantIndex() {
        this.dirty = true;
    }
    
    /**
     * Rebuild the index for the current visibility state.
     * 
     * @param displayedTrackIndices List of track indices currently displayed (after filtering)
     * @param firstVisibleSample First visible sample slot (in displayed list)
     * @param lastVisibleSample Last visible sample slot (in displayed list)
     * @param sampleHeight Height per sample in pixels
     * @param scrollBarPosition Vertical scroll offset
     * @param masterTrackHeight Height of master track at top
     */
    public void rebuild(List<Integer> displayedTrackIndices,
                       int firstVisibleSample, 
                       int lastVisibleSample,
                       double sampleHeight,
                       double scrollBarPosition,
                       double masterTrackHeight) {
        
        // Validate inputs
        if (displayedTrackIndices.isEmpty() || 
            firstVisibleSample < 0 || 
            lastVisibleSample < firstVisibleSample ||
            sampleHeight <= 0) {
            visibleCount = 0;
            yPositions = new double[0];
            sampleTrackIndices = new int[0];
            dirty = false;
            return;
        }
        
        // Calculate visible range
        int first = Math.max(0, firstVisibleSample);
        int last = Math.min(displayedTrackIndices.size() - 1, lastVisibleSample);
        visibleCount = last - first + 1;
        
        if (visibleCount <= 0) {
            visibleCount = 0;
            yPositions = new double[0];
            sampleTrackIndices = new int[0];
            dirty = false;
            return;
        }
        
        // Allocate arrays
        yPositions = new double[visibleCount];
        sampleTrackIndices = new int[visibleCount];
        
        // Compute Y positions and track indices for each visible slot
        for (int slot = first; slot <= last; slot++) {
            int arrayIndex = slot - first;
            int trackIndex = displayedTrackIndices.get(slot);
            
            // Calculate Y position for this sample's variant line
            // Formula matches AlignmentCanvas sample positioning
            double sampleY = masterTrackHeight + slot * sampleHeight - scrollBarPosition;
            
            yPositions[arrayIndex] = sampleY;
            sampleTrackIndices[arrayIndex] = trackIndex;
        }
        
        // Cache rebuild parameters
        lastFirstVisible = firstVisibleSample;
        lastLastVisible = lastVisibleSample;
        lastScrollBarPosition = scrollBarPosition;
        lastSampleHeight = sampleHeight;
        lastDisplayedTrackCount = displayedTrackIndices.size();
        
        dirty = false;
    }
    
    /**
     * Check if a sample track index is visible in this index.
     * Returns the array index if visible, -1 if not.
     */
    public int findVisibleSlot(int sampleTrackIndex) {
        for (int i = 0; i < visibleCount; i++) {
            if (sampleTrackIndices[i] == sampleTrackIndex) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Get the Y position for a visible slot.
     */
    public double getYPosition(int visibleSlot) {
        if (visibleSlot < 0 || visibleSlot >= visibleCount) {
            return -1;
        }
        return yPositions[visibleSlot];
    }
    
    /**
     * Get the sample track index for a visible slot.
     */
    public int getSampleTrackIndex(int visibleSlot) {
        if (visibleSlot < 0 || visibleSlot >= visibleCount) {
            return -1;
        }
        return sampleTrackIndices[visibleSlot];
    }
    
    /**
     * Get number of visible samples in this index.
     */
    public int getVisibleCount() {
        return visibleCount;
    }
    
    /**
     * Get all Y positions (for bulk iteration).
     */
    public double[] getYPositions() {
        return yPositions;
    }
    
    /**
     * Get all sample track indices (for bulk iteration).
     */
    public int[] getSampleTrackIndices() {
        return sampleTrackIndices;
    }
    
    /**
     * Mark this index as needing rebuild.
     */
    public void markDirty() {
        dirty = true;
    }
    
    /**
     * Check if this index needs to be rebuilt.
     */
    public boolean isDirty() {
        return dirty;
    }
    
    /**
     * Check if rebuild is needed based on current parameters.
     * Uses thresholds to avoid rebuilding on small changes (e.g., minor scrolling).
     */
    public boolean needsRebuild(List<Integer> displayedTrackIndices,
                                int firstVisibleSample,
                                int lastVisibleSample,
                                double scrollBarPosition,
                                double sampleHeight) {
        
        if (dirty) return true;
        
        // Rebuild if displayed track count changed (filter change)
        if (displayedTrackIndices.size() != lastDisplayedTrackCount) {
            return true;
        }
        
        // Rebuild if visible range changed at all
        // Any change in firstVisible or lastVisible affects Y position calculations
        if (firstVisibleSample != lastFirstVisible || lastVisibleSample != lastLastVisible) {
            return true;
        }
        
        // Rebuild if sample height changed
        if (Math.abs(sampleHeight - lastSampleHeight) > 0.1) {
            return true;
        }
        
        // Rebuild if scrolled significantly (more than 1 pixel to avoid floating point noise)
        double scrollDelta = Math.abs(scrollBarPosition - lastScrollBarPosition);
        if (scrollDelta > 1.0) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Get statistics about this index.
     */
    public String getStats() {
        return String.format("VisibleVariantIndex: %d visible samples, dirty=%s", 
            visibleCount, dirty);
    }
    
    @Override
    public String toString() {
        return String.format("VisibleVariantIndex[n=%d, dirty=%s]", visibleCount, dirty);
    }
}
