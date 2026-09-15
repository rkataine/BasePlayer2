package org.baseplayer.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
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

    public record VisibleTrackSlot(int slot, int trackIndex) {}

    public enum SubsetSource {
        TEXT_FILTER,
        GENE_FOCUS
    }
    
    // Observable list for UI binding
    private final ObservableList<SampleTrack> sampleTracks = FXCollections.observableArrayList();
    
    // Legacy sample names list (consider removing if not needed)
    private final List<String> sampleList = new ArrayList<>();
    
    // Hover state - which sample is currently hovered over (-1 = none)
    private final IntegerProperty hoverSample = new SimpleIntegerProperty(-1);
    
    // Visible sample range in the viewport
    private int firstVisibleSample = -1;
    private int lastVisibleSample = -1;
    
    // UI layout state
    private double scrollBarPosition = 0;
    private double sampleHeight = 0;
    private double sampleViewportHeight = 0;
    private boolean sampleHeightLocked = false;
    private String activeSampleFilterQuery = "";
    private Set<SampleTrack> focusedTracks = null; // null = no explicit focus filter
    private String focusedGeneName = null; // gene name currently focused, or null
    private final List<Integer> cachedDisplayedTrackIndices = new ArrayList<>();
    private boolean displayedTrackIndicesDirty = true;
    public static final double DEFAULT_MASTER_TRACK_HEIGHT = 28;
    private final DoubleProperty masterTrackHeight = new SimpleDoubleProperty(DEFAULT_MASTER_TRACK_HEIGHT);
    
    public SampleRegistry() {
        sampleTracks.addListener((ListChangeListener<SampleTrack>) change -> {
            invalidateDisplayedTrackIndicesCache();
            normalizeVisibleRangeAfterSubsetChange();
        });
    }
    
    public ObservableList<SampleTrack> getSampleTracks() {
        return sampleTracks;
    }

    public int getTrackIndex(SampleTrack track) {
        if (track == null) {
            return -1;
        }
        return sampleTracks.indexOf(track);
    }
    
    public void addSampleTrack(SampleTrack track) {
        if (track == null) {
            throw new IllegalArgumentException("Sample track cannot be null");
        }
        sampleTracks.add(track);
        invalidateDisplayedTrackIndicesCache();
    }
    
    public void removeSampleTrack(SampleTrack track) {
        sampleTracks.remove(track);
        invalidateDisplayedTrackIndicesCache();
    }
    
    public void clearSampleTracks() {
        sampleTracks.clear();
        sampleList.clear();
        activeSampleFilterQuery = "";
        focusedTracks = null;
        focusedGeneName = null;
        invalidateDisplayedTrackIndicesCache();
        clearVisibleRange();
        hoverSample.set(-1);
    }
    
    public List<String> getSampleList() {
        return sampleList;
    }
    
    public int getHoverSample() {
        return hoverSample.get();
    }
    
    public void setHoverSample(int index) {
        this.hoverSample.set(index);
    }
    
    public IntegerProperty hoverSampleProperty() {
        return hoverSample;
    }
    
    public int getFirstVisibleSample() {
        return firstVisibleSample;
    }

    public int getLastVisibleSample() {
        return lastVisibleSample;
    }

    public int getVisibleSampleCount() {
        if (firstVisibleSample < 0 || lastVisibleSample < 0) {
            return 0;
        }
        return Math.max(0, lastVisibleSample - firstVisibleSample + 1);
    }

    public double getScrollBarPosition() {
        return scrollBarPosition;
    }

    public double getTotalSampleContentHeight() {
        return getDisplayedTrackCount() * sampleHeight;
    }

    public double getMaxScrollBarPosition(double viewportHeight) {
        if (getDisplayedTrackCount() <= 0) {
            return 0;
        }
        return Math.max(0, getTotalSampleContentHeight() - Math.max(0, viewportHeight));
    }

    public double clampScrollBarPosition(double position, double viewportHeight) {
        return Math.max(0, Math.min(position, getMaxScrollBarPosition(viewportHeight)));
    }

    public double getSampleHeight() {
        return sampleHeight;
    }

    public void lockSampleHeight() {
        sampleHeightLocked = true;
    }

    public void unlockSampleHeight() {
        sampleHeightLocked = false;
    }

    public boolean isSampleHeightLocked() {
        return sampleHeightLocked;
    }

    public void setVisibleSamples(int first, int last, double viewportHeight) {
        setVisibleSamples(first, last, Double.NaN, Double.NaN, viewportHeight);
    }

    public void setVisibleSamples(int first, int last, double height, double scroll,
                                  double viewportHeight) {
        int trackCount = getDisplayedTrackCount();

        if (trackCount <= 0 || first < 0 || last < 0) {
            boolean rangeChanged = firstVisibleSample != -1 || lastVisibleSample != -1;
            boolean heightChanged = sampleHeight != 0;
            firstVisibleSample = -1;
            lastVisibleSample = -1;
            sampleHeight = 0;
            scrollBarPosition = 0;
            if (viewportHeight > 0) {
                sampleViewportHeight = viewportHeight;
            }
            if (rangeChanged || heightChanged) {
                notifyVariantIndexDirty();
            }
            return;
        }

        int newFirst = Math.min(first, last);
        int newLast = Math.max(first, last);
        newFirst = Math.max(0, Math.min(trackCount - 1, newFirst));
        newLast = Math.max(newFirst, Math.min(trackCount - 1, newLast));
        int window = newLast - newFirst + 1;

        double newHeight;
        double effectiveViewport = viewportHeight;
        if (Double.isNaN(height)) {
            if (effectiveViewport <= 0) {
                effectiveViewport = sampleViewportHeight;
            }
            newHeight = effectiveViewport > 0 ? effectiveViewport / window : sampleHeight;
        } else {
            newHeight = Math.max(0, height);
            if (effectiveViewport <= 0) {
                effectiveViewport = newHeight > 0 ? newHeight * window : sampleViewportHeight;
            }
        }

        if (effectiveViewport > 0) {
            sampleViewportHeight = effectiveViewport;
        }

        double desiredScroll = Double.isNaN(scroll) ? newFirst * newHeight : scroll;
        double maxScroll = Math.max(0, trackCount * newHeight - Math.max(0, sampleViewportHeight));
        double newScroll = Math.max(0, Math.min(desiredScroll, maxScroll));

        boolean rangeChanged = newFirst != firstVisibleSample || newLast != lastVisibleSample;
        boolean heightChanged = Math.abs(newHeight - sampleHeight) > 1e-9;

        firstVisibleSample = newFirst;
        lastVisibleSample = newLast;
        sampleHeight = newHeight;
        scrollBarPosition = newScroll;

        if (rangeChanged || heightChanged) {
            notifyVariantIndexDirty();
        }
    }

    /** Empty viewport: no visible samples. */
    public void clearVisibleRange() {
        sampleHeightLocked = false;
        setVisibleSamples(-1, -1, 0, 0, 0);
    }

    /** Scroll-only update (animation frames). Keeps first/last/height. */
    public void setScrollOffset(double scroll, double viewportHeight) {
        if (firstVisibleSample < 0 || lastVisibleSample < 0) {
            return;
        }
        setVisibleSamples(firstVisibleSample, lastVisibleSample, sampleHeight, scroll, viewportHeight);
    }

    /** Re-apply current window against an updated viewport height. */
    public void clampScrollToViewport(double viewportHeight) {
        if (firstVisibleSample < 0 || lastVisibleSample < 0) {
            setVisibleSamples(-1, -1, 0, 0, viewportHeight);
            return;
        }
        setVisibleSamples(
            firstVisibleSample, lastVisibleSample, sampleHeight, scrollBarPosition, viewportHeight);
    }

    public void lockAndKeepSampleHeight(double height) {
        sampleHeightLocked = true;
        if (firstVisibleSample < 0 || lastVisibleSample < 0) {
            return;
        }
        setVisibleSamples(firstVisibleSample, lastVisibleSample, height, scrollBarPosition, sampleViewportHeight);
    }

    /**
     * Draw-time height initialization when height is still 0. Shrinks the window
     * if needed to enforce a minimum row height.
     */
    public void ensureSampleHeightForViewport(double availableHeight) {
        if (sampleHeightLocked || sampleHeight != 0 || firstVisibleSample < 0 || lastVisibleSample < 0) {
            clampScrollToViewport(availableHeight);
            return;
        }

        int visibleCount = getVisibleSampleCount();
        double rawHeight = availableHeight / Math.max(1, visibleCount);
        if (rawHeight < 20) {
            int tracksFit = Math.max(1, (int) (availableHeight / 20));
            int firstVis = Math.max(0, firstVisibleSample);
            setVisibleSamples(firstVis, firstVis + tracksFit - 1, 20, scrollBarPosition, availableHeight);
        } else {
            setVisibleSamples(
                firstVisibleSample, lastVisibleSample, rawHeight, scrollBarPosition, availableHeight);
        }
    }

    /** Show all displayed tracks and reset height so layout can recompute. */
    public void showAllTracksResetHeight() {
        sampleHeightLocked = false;
        setVisibleSamples(0, Integer.MAX_VALUE, 0, 0, sampleViewportHeight);
    }

    public void includeNewTracksAtEndResetHeight() {
        sampleHeightLocked = false;
        int first = firstVisibleSample < 0 ? 0 : firstVisibleSample;
        setVisibleSamples(
            first, Integer.MAX_VALUE, 0,
            firstVisibleSample < 0 ? 0 : scrollBarPosition,
            sampleViewportHeight);
    }

    /**
     * After removing a displayed track, preserve window size and snap scroll.
     *
     * @param removedDisplayedSlot slot of the removed track in the pre-removal
     *        displayed list, or -1 if it was not displayed
     * @param previousFirst        first visible slot before removal
     * @param previousWindow       visible window size before removal
     */
    public void adjustWindowAfterTrackRemoval(int removedDisplayedSlot, int previousFirst, int previousWindow) {
        int newCount = getDisplayedTrackCount();
        if (newCount <= 0) {
            clearVisibleRange();
            return;
        }

        int newWindow = Math.max(1, Math.min(previousWindow, newCount));
        int newFirst = previousFirst;
        if (removedDisplayedSlot >= 0 && removedDisplayedSlot < previousFirst) {
            newFirst = previousFirst - 1;
        }
        setVisibleSamples(newFirst, newFirst + newWindow - 1, sampleHeight, Double.NaN, sampleHeight * newWindow);
    }

    public String getActiveSampleFilterQuery() {
        return activeSampleFilterQuery;
    }

    public boolean hasActiveSampleFilterQuery() {
        return !activeSampleFilterQuery.isEmpty();
    }

    public void applyTextSubsetQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        String oldQuery = this.activeSampleFilterQuery;
        this.activeSampleFilterQuery = normalized;
        invalidateDisplayedTrackIndicesCache();

        normalizeVisibleRangeAfterSubsetChange();
        if (!oldQuery.equals(this.activeSampleFilterQuery)) {
            notifyVariantIndexDirty();
        }
    }

    public boolean hasFocusedTracks() {
        return focusedTracks != null;
    }

    public String getFocusedGeneName() {
        return focusedGeneName;
    }

    public void applyGeneSubset(List<SampleTrack> tracks, String featureName) {
        boolean hadFocusedTracks = focusedTracks != null;
        boolean hasFocusedTracks = tracks != null && !tracks.isEmpty();
        String oldFeatureName = focusedGeneName;

        if (tracks == null || tracks.isEmpty()) {
            focusedTracks = null;
            focusedGeneName = null;
        } else {
            focusedTracks = new LinkedHashSet<>();
            for (SampleTrack track : tracks) {
                if (track != null) {
                    focusedTracks.add(track);
                }
            }
            if (focusedTracks.isEmpty()) {
                focusedTracks = null;
            }
            focusedGeneName = featureName;
        }

        invalidateDisplayedTrackIndicesCache();

        normalizeVisibleRangeAfterSubsetChange();

        boolean changed = (hadFocusedTracks != hasFocusedTracks)
            || (oldFeatureName == null ? focusedGeneName != null : !oldFeatureName.equals(focusedGeneName));
        if (changed) {
            notifyVariantIndexDirty();
        }
    }

    public boolean hasActiveSubset() {
        return hasActiveSampleFilterQuery() || hasFocusedTracks();
    }

    public boolean hasActiveSubsetSource(SubsetSource source) {
        return switch (source) {
            case TEXT_FILTER -> hasActiveSampleFilterQuery();
            case GENE_FOCUS -> hasFocusedTracks();
        };
    }

    public void clearSubsetSource(SubsetSource source) {
        switch (source) {
            case TEXT_FILTER -> applyTextSubsetQuery("");
            case GENE_FOCUS -> applyGeneSubset(null, null);
        }
    }

    public void clearAllSubsetSources() {
        boolean changed = false;

        if (!activeSampleFilterQuery.isEmpty()) {
            activeSampleFilterQuery = "";
            changed = true;
        }
        if (focusedTracks != null || focusedGeneName != null) {
            focusedTracks = null;
            focusedGeneName = null;
            changed = true;
        }

        invalidateDisplayedTrackIndicesCache();

        normalizeVisibleRangeAfterSubsetChange();
        if (changed) {
            notifyVariantIndexDirty();
        }
    }

    public List<Integer> getDisplayedTrackIndices() {
        return List.copyOf(getDisplayedTrackIndicesCached());
    }

    public int getDisplayedTrackCount() {
        return getDisplayedTrackIndicesCached().size();
    }

    /**
     * Returns visible slots and backing track indices to use for visibility checks.
     * Rule: if subset is active, iterate subset slots; otherwise iterate all track
     * slots. In both cases, clamp to current visible slot range.
     */
    public List<VisibleTrackSlot> getVisibleTrackSlotsForChecks() {
        List<VisibleTrackSlot> visibleSlots = new ArrayList<>();
        if (sampleTracks.isEmpty()) {
            return visibleSlots;
        }

        boolean subsetActive = hasActiveSubset();
        List<Integer> displayed = subsetActive ? getDisplayedTrackIndicesCached() : null;
        int slotCount = subsetActive ? displayed.size() : sampleTracks.size();
        if (slotCount <= 0) {
            return visibleSlots;
        }

        int first = firstVisibleSample;
        int last = lastVisibleSample;
        if (first < 0 || last < first) {
            return visibleSlots;
        }

        int clampedFirst = Math.max(0, Math.min(slotCount - 1, first));
        int clampedLast = Math.max(clampedFirst, Math.min(slotCount - 1, last));
        for (int slot = clampedFirst; slot <= clampedLast; slot++) {
            int trackIndex = subsetActive ? displayed.get(slot) : slot;
            if (trackIndex >= 0 && trackIndex < sampleTracks.size()) {
                visibleSlots.add(new VisibleTrackSlot(slot, trackIndex));
            }
        }
        return visibleSlots;
    }

    /**
     * Returns backing track indices for currently visible slots, using subset-aware rules.
     */
    public List<Integer> getVisibleTrackIndicesForChecks() {
        List<VisibleTrackSlot> visibleSlots = getVisibleTrackSlotsForChecks();
        List<Integer> indices = new ArrayList<>(visibleSlots.size());
        for (VisibleTrackSlot slot : visibleSlots) {
            indices.add(slot.trackIndex());
        }
        return indices;
    }

		// TODO Where this is used? Can be replaced?
    /**
     * Resolve backing sampleTracks index to displayed slot index, or -1 if hidden by filter.
     */
    public int getDisplayedSlotForTrackIndex(int trackIndex) {
        if (trackIndex < 0) {
            return -1;
        }
        List<Integer> displayed = getDisplayedTrackIndicesCached();
        for (int slot = 0; slot < displayed.size(); slot++) {
            if (displayed.get(slot) == trackIndex) {
                return slot;
            }
        }
        return -1;
    }

    private List<Integer> getDisplayedTrackIndicesCached() {
        if (!displayedTrackIndicesDirty) {
            return cachedDisplayedTrackIndices;
        }

        cachedDisplayedTrackIndices.clear();
        if (sampleTracks.isEmpty()) {
            displayedTrackIndicesDirty = false;
            return cachedDisplayedTrackIndices;
        }

        String query = activeSampleFilterQuery == null ? "" : activeSampleFilterQuery.trim();
        if (query.isEmpty()) {
            for (int i = 0; i < sampleTracks.size(); i++) {
                cachedDisplayedTrackIndices.add(i);
            }
        } else {
            String needle = query.toLowerCase(Locale.ROOT);
            for (int i = 0; i < sampleTracks.size(); i++) {
                if (matchesSampleFilter(sampleTracks.get(i), needle)) {
                    cachedDisplayedTrackIndices.add(i);
                }
            }
        }

        if (focusedTracks != null) {
            cachedDisplayedTrackIndices.removeIf(index -> !focusedTracks.contains(sampleTracks.get(index)));
        }

        displayedTrackIndicesDirty = false;
        return cachedDisplayedTrackIndices;
    }

    private void invalidateDisplayedTrackIndicesCache() {
        displayedTrackIndicesDirty = true;
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

    private void normalizeVisibleRangeAfterSubsetChange() {
        int displayed = getDisplayedTrackCount();
        if (displayed <= 0) {
            setVisibleSamples(-1, -1, sampleHeight, 0, sampleViewportHeight);
            return;
        }

        int first = firstVisibleSample < 0 ? 0 : firstVisibleSample;
        int last = lastVisibleSample < 0 ? first : lastVisibleSample;
        setVisibleSamples(first, last, sampleHeight, scrollBarPosition, sampleViewportHeight);
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
        // Mark variant index as dirty since master track height affects variant Y positions
        notifyVariantIndexDirty();
    }
    
    // ── Operations ─────────────────────────────────────────────────────────
    
    /**
     * Notify all alignment canvases to invalidate their variant drawing indices.
     * Called when sample visibility changes (filter, significant scroll).
     */
    private void notifyVariantIndexDirty() {
        // Get all DrawStacks and invalidate their variant indices
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (org.baseplayer.draw.DrawStack stack : stackManager.getStacks()) {
            if (stack.alignmentCanvas != null) {
                stack.alignmentCanvas.invalidateVariantIndex();
            }
        }
    }
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
