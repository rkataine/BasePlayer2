package org.baseplayer.services;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
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
    private boolean sampleHeightLocked = false;
    private String activeSampleFilterQuery = "";
    public static final double DEFAULT_MASTER_TRACK_HEIGHT = 28;
    private final DoubleProperty masterTrackHeight = new SimpleDoubleProperty(DEFAULT_MASTER_TRACK_HEIGHT);
    
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
        int max = Math.max(0, getDisplayedTrackCount() - 1);
        this.firstVisibleSample = Math.max(0, Math.min(max, index));
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
        int max = Math.max(0, getDisplayedTrackCount() - 1);
        this.lastVisibleSample = Math.max(0, Math.min(max, index));
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
     * Get total vertical content height for sample rows.
     */
    public double getTotalSampleContentHeight() {
        return getDisplayedTrackCount() * sampleHeight;
    }

    /**
     * Get max scroll position for a viewport height.
     */
    public double getMaxScrollBarPosition(double viewportHeight) {
        if (getDisplayedTrackCount() <= 0) {
            return 0;
        }
        double safeViewportHeight = Math.max(0, viewportHeight);
        return Math.max(0, getTotalSampleContentHeight() - safeViewportHeight);
    }

    /**
     * Clamp an arbitrary scroll position to valid bounds for a viewport height.
     */
    public double clampScrollBarPosition(double position, double viewportHeight) {
        return Math.max(0, Math.min(position, getMaxScrollBarPosition(viewportHeight)));
    }

    /**
     * Clamp current scroll position in place for the given viewport height.
     */
    public void clampScrollBarPositionInPlace(double viewportHeight) {
        scrollBarPosition = clampScrollBarPosition(scrollBarPosition, viewportHeight);
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
     * Prevent automatic sample-height recalculation during animated transitions.
     */
    public void lockSampleHeight() {
        sampleHeightLocked = true;
    }

    /**
     * Re-enable automatic sample-height recalculation.
     */
    public void unlockSampleHeight() {
        sampleHeightLocked = false;
    }

    /**
     * Whether sample height is currently locked from automatic recalculation.
     */
    public boolean isSampleHeightLocked() {
        return sampleHeightLocked;
    }

    /**
     * Current active free-text sample filter query for master track controls.
     */
    public String getActiveSampleFilterQuery() {
        return activeSampleFilterQuery;
    }

    /**
     * Set active sample filter query (trimmed). Empty string disables filter mode.
     */
    public void setActiveSampleFilterQuery(String query) {
        this.activeSampleFilterQuery = query == null ? "" : query.trim();

        int displayed = getDisplayedTrackCount();
        if (displayed <= 0) {
            firstVisibleSample = 0;
            lastVisibleSample = 0;
            scrollBarPosition = 0;
            return;
        }

        firstVisibleSample = Math.max(0, Math.min(displayed - 1, firstVisibleSample));
        lastVisibleSample = Math.max(firstVisibleSample, Math.min(displayed - 1, lastVisibleSample));
    }

    /**
     * Clear active sample filter query.
     */
    public void clearActiveSampleFilterQuery() {
        setActiveSampleFilterQuery("");
    }

    /**
     * Whether master track is currently in filter label mode.
     */
    public boolean hasActiveSampleFilterQuery() {
        return !activeSampleFilterQuery.isEmpty();
    }

    /**
     * Ordered list of track indices currently displayed in the samples view.
     * With active filter this contains only matching tracks, otherwise all tracks.
     */
    public List<Integer> getDisplayedTrackIndices() {
        List<Integer> indices = new ArrayList<>();
        if (sampleTracks.isEmpty()) {
            return indices;
        }

        String query = activeSampleFilterQuery == null ? "" : activeSampleFilterQuery.trim();
        if (query.isEmpty()) {
            for (int i = 0; i < sampleTracks.size(); i++) {
                indices.add(i);
            }
            return indices;
        }

        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < sampleTracks.size(); i++) {
            if (matchesSampleFilter(sampleTracks.get(i), needle)) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * Number of tracks shown in the current displayed (possibly filtered) view.
     */
    public int getDisplayedTrackCount() {
        return getDisplayedTrackIndices().size();
    }

    /**
     * Resolve a displayed slot index to backing sampleTracks index.
     */
    public int getDisplayedTrackIndexBySlot(int slot) {
        List<Integer> displayed = getDisplayedTrackIndices();
        if (slot < 0 || slot >= displayed.size()) {
            return -1;
        }
        return displayed.get(slot);
    }

    /**
     * Resolve backing sampleTracks index to displayed slot index, or -1 if hidden by filter.
     */
    public int getDisplayedSlotForTrackIndex(int trackIndex) {
        if (trackIndex < 0) {
            return -1;
        }
        List<Integer> displayed = getDisplayedTrackIndices();
        for (int slot = 0; slot < displayed.size(); slot++) {
            if (displayed.get(slot) == trackIndex) {
                return slot;
            }
        }
        return -1;
    }

    private boolean matchesSampleFilter(SampleTrack track, String needle) {
        String displayName = track.getDisplayName();
        if (displayName != null && displayName.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }

        String trackName = track.getName();
        if (trackName != null && trackName.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }

        for (Sample sample : track.getSamples()) {
            String sampleName = sample.getName();
            if (sampleName != null && sampleName.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get the height of the master track.
     */
    public double getMasterTrackHeight() {
        return masterTrackHeight.get();
    }
    
    public DoubleProperty masterTrackHeightProperty() {
        return masterTrackHeight;
    }
    
    /**
     * Set the height of the master track.
     */
    public void setMasterTrackHeight(double height) {
        this.masterTrackHeight.set(Math.max(0, height));
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
