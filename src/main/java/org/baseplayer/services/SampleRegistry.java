package org.baseplayer.services;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.sample.Sample;
import org.baseplayer.sample.SampleTrack;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Manages sample tracks and their visibility state.
 * Replaces SharedModel sample-related fields.
 * 
 * This is the single source of truth for:
 * - All loaded sample tracks
 * - Which samples are currently visible
 * - Hover state
 * - UI layout parameters (scroll position, heights)
 */
public class SampleRegistry {
    
    // Observable list for UI binding
    private final ObservableList<SampleTrack> sampleTracks = FXCollections.observableArrayList();
    
    // Legacy sample names list (consider removing if not needed)
    private final List<String> sampleList = new ArrayList<>();
    
    // Hover state - which sample is currently hovered over (-1 = none)
    private final IntegerProperty hoverSample = new SimpleIntegerProperty(-1);
    
    // Visible sample range in the viewport
    private int firstVisibleSample = 0;
    private int lastVisibleSample = 0;
    
    // UI layout state
    private double scrollBarPosition = 0;
    private double sampleHeight = 0;
    private double masterTrackHeight = 28;
    
    public SampleRegistry() {
        // Initialize with empty state
    }
    
    // ── Sample Track Management ────────────────────────────────────────────
    
    /**
     * Get all sample tracks (observable for UI binding).
     */
    public ObservableList<SampleTrack> getSampleTracks() {
        return sampleTracks;
    }
    
    /**
     * Add a sample track to the registry.
     */
    public void addSampleTrack(SampleTrack track) {
        if (track == null) {
            throw new IllegalArgumentException("Sample track cannot be null");
        }
        sampleTracks.add(track);
    }
    
    /**
     * Remove a sample track from the registry.
     */
    public void removeSampleTrack(SampleTrack track) {
        sampleTracks.remove(track);
    }
    
    /**
     * Clear all sample tracks.
     */
    public void clearSampleTracks() {
        sampleTracks.clear();
        sampleList.clear();
    }
    
    // ── Sample List (Legacy) ───────────────────────────────────────────────
    
    /**
     * Get the legacy sample list (consider migrating away from this).
     */
    public List<String> getSampleList() {
        return sampleList;
    }
    
    // ── Hover State ────────────────────────────────────────────────────────
    
    /**
     * Get the currently hovered sample index (-1 if none).
     */
    public int getHoverSample() {
        return hoverSample.get();
    }
    
    /**
     * Set which sample is currently hovered.
     */
    public void setHoverSample(int index) {
        this.hoverSample.set(index);
    }
    
    /**
     * Get the hover sample property for UI binding.
     */
    public IntegerProperty hoverSampleProperty() {
        return hoverSample;
    }
    
    // ── Visibility State ───────────────────────────────────────────────────
    
    /**
     * Get the index of the first visible sample in the viewport.
     */
    public int getFirstVisibleSample() {
        return firstVisibleSample;
    }
    
    /**
     * Set the first visible sample index.
     */
    public void setFirstVisibleSample(int index) {
        this.firstVisibleSample = Math.max(0, index);
    }
    
    /**
     * Get the index of the last visible sample in the viewport.
     */
    public int getLastVisibleSample() {
        return lastVisibleSample;
    }
    
    /**
     * Set the last visible sample index.
     */
    public void setLastVisibleSample(int index) {
        this.lastVisibleSample = Math.max(0, index);
    }
    
    /**
     * Get the number of currently visible samples.
     */
    public int getVisibleSampleCount() {
        return Math.max(0, lastVisibleSample - firstVisibleSample + 1);
    }
    
    // ── UI Layout State ────────────────────────────────────────────────────
    
    /**
     * Get the current scroll bar position.
     */
    public double getScrollBarPosition() {
        return scrollBarPosition;
    }
    
    /**
     * Set the scroll bar position.
     */
    public void setScrollBarPosition(double position) {
        this.scrollBarPosition = position;
    }
    
    /**
     * Get the height allocated for each sample row.
     */
    public double getSampleHeight() {
        return sampleHeight;
    }
    
    /**
     * Set the height for each sample row.
     */
    public void setSampleHeight(double height) {
        this.sampleHeight = Math.max(0, height);
    }
    
    /**
     * Get the height of the master track.
     */
    public double getMasterTrackHeight() {
        return masterTrackHeight;
    }
    
    /**
     * Set the height of the master track.
     */
    public void setMasterTrackHeight(double height) {
        this.masterTrackHeight = Math.max(0, height);
    }
    
    // ── Operations ─────────────────────────────────────────────────────────
    
    /**
     * Repack cached BAM reads for the given stack to optimize row usage.
     * Call after zoom operations complete.
     */
    public void repackBamReadsForStack(DrawStack stack) {
        for (SampleTrack track : sampleTracks) {
            for (Sample sample : track.getSamples()) {
                if (sample.getBamFile() != null) {
                    sample.getBamFile().repackReads(stack);
                }
            }
        }
    }
}
