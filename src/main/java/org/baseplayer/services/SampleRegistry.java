package org.baseplayer.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.utils.DrawColors;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

public class SampleRegistry extends TrackViewportRegistry {

    public static final double DEFAULT_MASTER_TRACK_HEIGHT = DEFAULT_MASTER_BAND_HEIGHT_PIXELS;

    public enum SubsetSource {
        TEXT_FILTER,
        GENE_FOCUS
    }

    private final ObservableList<SampleTrack> sampleTracks = FXCollections.observableArrayList();
    private final List<String> sampleList = new ArrayList<>();
    private String activeSampleFilterQuery = "";
    private Set<SampleTrack> focusedTracks = null;
    private String focusedGeneName = null;
    private final List<Integer> cachedDisplayedTrackIndices = new ArrayList<>();
    private boolean displayedTrackIndicesDirty = true;
    private final Map<Integer, SampleGroup> sampleGroups = new LinkedHashMap<>();
    private int nextSampleGroupId = 1;
    /** Bumps whenever sample groups are created, assigned, cleared, recolored, or removed. */
    private final IntegerProperty sampleGroupsRevision = new SimpleIntegerProperty(0);

    public SampleRegistry() {
        sampleTracks.addListener((ListChangeListener<SampleTrack>) change -> {
            invalidateDisplayedTrackIndicesCache();
            normalizeVisibleRangeAfterDisplayedTrackCountChange();
            notifyVariantIndexDirty();
            // Keep session dirty in sync even when callers forget markDirty().
            Runnable mark = () -> ProjectSessionState.get().markDirty();
            if (Platform.isFxApplicationThread()) {
                mark.run();
            } else {
                Platform.runLater(mark);
            }
        });
    }

    @Override
    protected void onVisibleTrackRangeOrRowHeightChanged() {
        notifyVariantIndexDirty();
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
        setHoveredTrackIndex(-1);
    }

    public List<String> getSampleList() {
        return sampleList;
    }

    public int getHoverSample() {
        return getHoveredTrackIndex();
    }

    public void setHoverSample(int index) {
        setHoveredTrackIndex(index);
    }

    public IntegerProperty hoverSampleProperty() {
        return hoveredTrackIndexProperty();
    }

    /** Handles sample-track icon actions (close / settings / add / reload) from sidebar or canvas. */
    @FunctionalInterface
    public interface TrackIconActionHandler {
        boolean handle(String iconId, int trackIndex, double screenX, double screenY);
    }

    private TrackIconActionHandler trackIconActionHandler;

    public void setTrackIconActionHandler(TrackIconActionHandler handler) {
        this.trackIconActionHandler = handler;
    }

    public boolean handleTrackIconAction(
        String iconId, int trackIndex, double screenX, double screenY) {
        return trackIconActionHandler != null
            && trackIconActionHandler.handle(iconId, trackIndex, screenX, screenY);
    }

    public int getFirstVisibleSample() {
        return getFirstVisibleTrackSlot();
    }

    public int getLastVisibleSample() {
        return getLastVisibleTrackSlot();
    }

    public int getVisibleSampleCount() {
        return getVisibleTrackSlotCount();
    }

    public double getScrollBarPosition() {
        return getVerticalScrollOffsetPixels();
    }

    public double getTotalSampleContentHeight() {
        return getTotalTrackContentHeightPixels();
    }

    public double getMaxScrollBarPosition(double viewportHeight) {
        return getMaxVerticalScrollOffsetPixels(viewportHeight);
    }

    public double clampScrollBarPosition(double position, double viewportHeight) {
        return clampVerticalScrollOffsetPixels(position, viewportHeight);
    }

    public double getSampleHeight() {
        return getTrackRowHeightPixels();
    }

    public void lockSampleHeight() {
        lockTrackRowHeight();
    }

    public void unlockSampleHeight() {
        unlockTrackRowHeight();
    }

    public boolean isSampleHeightLocked() {
        return isTrackRowHeightLocked();
    }

    public void setVisibleSamples(int first, int last, double viewportHeight) {
        setVisibleTrackRange(first, last, viewportHeight);
    }

    public void setVisibleSamples(int first, int last, double height, double scroll,
                                  double viewportHeight) {
        setVisibleTrackRange(first, last, height, scroll, viewportHeight);
    }

    public void clearVisibleRange() {
        clearVisibleTrackRange();
    }

    public void setScrollOffset(double scroll, double viewportHeight) {
        setVerticalScrollOffsetPixels(scroll, viewportHeight);
    }

    public void clampScrollToViewport(double viewportHeight) {
        clampVerticalScrollOffsetForViewport(viewportHeight);
    }

    public void lockAndKeepSampleHeight(double height) {
        lockAndKeepTrackRowHeight(height);
    }

    public void ensureSampleHeightForViewport(double availableHeight) {
        ensureTrackRowHeightFitsViewport(availableHeight);
    }

    public void showAllTracksResetHeight() {
        showAllTracksAndResetRowHeight();
    }

    public void includeNewTracksAtEndResetHeight() {
        includeNewTracksAtEndAndResetRowHeight();
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

        normalizeVisibleRangeAfterDisplayedTrackCountChange();
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

    /** Suggested group name: focused gene if any, otherwise {@code Group N} for the next id. */
    public String suggestNextGroupName() {
        if (focusedGeneName != null && !focusedGeneName.isBlank()) {
            return focusedGeneName.trim();
        }
        return "Group " + nextSampleGroupId;
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
        normalizeVisibleRangeAfterDisplayedTrackCountChange();

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
        normalizeVisibleRangeAfterDisplayedTrackCountChange();
        if (changed) {
            notifyVariantIndexDirty();
        }
    }

    public List<Integer> getDisplayedTrackIndices() {
        return List.copyOf(getDisplayedTrackIndicesCached());
    }

    @Override
    public int getDisplayedTrackCount() {
        return getDisplayedTrackIndicesCached().size();
    }

    public List<VisibleTrackSlot> getVisibleTrackSlotsForChecks() {
        if (sampleTracks.isEmpty()) {
            return List.of();
        }

        boolean subsetActive = hasActiveSubset();
        List<Integer> displayed = subsetActive ? getDisplayedTrackIndicesCached() : null;
        int slotCount = subsetActive ? displayed.size() : sampleTracks.size();
        if (slotCount <= 0) {
            return List.of();
        }

        List<Integer> indices = new ArrayList<>(slotCount);
        if (subsetActive) {
            indices.addAll(displayed);
        } else {
            for (int i = 0; i < sampleTracks.size(); i++) {
                indices.add(i);
            }
        }
        return buildVisibleTrackSlotList(indices);
    }

    public List<Integer> getVisibleTrackIndicesForChecks() {
        List<VisibleTrackSlot> visibleSlots = getVisibleTrackSlotsForChecks();
        List<Integer> indices = new ArrayList<>(visibleSlots.size());
        for (VisibleTrackSlot slot : visibleSlots) {
            indices.add(slot.backingTrackIndex());
        }
        return indices;
    }

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

    public double getMasterTrackHeight() {
        return getMasterBandHeightPixels();
    }

    public DoubleProperty masterTrackHeightProperty() {
        return masterBandHeightProperty();
    }

    public void setMasterTrackHeight(double height) {
        setMasterBandHeightPixels(height);
    }

    private void notifyVariantIndexDirty() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.sampleTrackCanvas != null) {
                stack.sampleTrackCanvas.invalidateVariantIndex();
            }
        }
    }

    public void repackBamReadsForStack(DrawStack stack) {
        for (SampleTrack track : sampleTracks) {
            for (Sample sample : track.getSamples()) {
                if (sample.getBamFile() != null) {
                    sample.getBamFile().repackReads(stack);
                }
            }
        }
    }

    // ── Sample groups (sidebar coloring; independent of text/gene subsets) ──

    public IntegerProperty sampleGroupsRevisionProperty() {
        return sampleGroupsRevision;
    }

    private void bumpSampleGroupsRevision() {
        sampleGroupsRevision.set(sampleGroupsRevision.get() + 1);
    }

    public List<SampleGroup> getSampleGroups() {
        return List.copyOf(sampleGroups.values());
    }

    public SampleGroup getSampleGroup(int groupId) {
        return sampleGroups.get(groupId);
    }

    public SampleGroup getGroupForTrack(SampleTrack track) {
        if (track == null || !track.hasGroup()) {
            return null;
        }
        return sampleGroups.get(track.getGroupId());
    }

    public Color getSidebarColorForTrack(SampleTrack track) {
        SampleGroup group = getGroupForTrack(track);
        return group != null ? group.getColor() : null;
    }

    public int countTracksInGroup(int groupId) {
        int count = 0;
        for (SampleTrack track : sampleTracks) {
            if (track.getGroupId() == groupId) {
                count++;
            }
        }
        return count;
    }

    public SampleGroup createSampleGroup(String name, Color color) {
        int id = nextSampleGroupId++;
        Color resolved = color != null
            ? color
            : DrawColors.SAMPLE_GROUP_COLORS[(id - 1) % DrawColors.SAMPLE_GROUP_COLORS.length];
        String resolvedName = (name == null || name.isBlank()) ? ("Group " + id) : name.trim();
        SampleGroup group = new SampleGroup(id, resolvedName, resolved);
        sampleGroups.put(id, group);
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
        return group;
    }

    public SampleGroup createSampleGroup(String name) {
        return createSampleGroup(name, null);
    }

    public void assignTracksToGroup(List<SampleTrack> tracks, int groupId) {
        if (tracks == null || !sampleGroups.containsKey(groupId)) {
            return;
        }
        Set<Integer> vacatedGroups = new LinkedHashSet<>();
        for (SampleTrack track : tracks) {
            if (track == null) {
                continue;
            }
            if (track.hasGroup() && track.getGroupId() != groupId) {
                vacatedGroups.add(track.getGroupId());
            }
            track.setGroupId(groupId);
        }
        for (int vacatedId : vacatedGroups) {
            pruneEmptyGroup(vacatedId);
        }
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    public void assignTrackToGroup(SampleTrack track, int groupId) {
        if (track == null) {
            return;
        }
        if (groupId < 0) {
            clearTrackGroup(track);
            return;
        }
        if (!sampleGroups.containsKey(groupId)) {
            return;
        }
        int previousGroupId = track.getGroupId();
        track.setGroupId(groupId);
        if (previousGroupId >= 0 && previousGroupId != groupId) {
            pruneEmptyGroup(previousGroupId);
        }
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    public SampleGroup createGroupForTracks(List<SampleTrack> tracks, String name, Color color) {
        if (tracks == null || tracks.isEmpty()) {
            return null;
        }
        SampleGroup group = createSampleGroup(name, color);
        assignTracksToGroup(tracks, group.getId());
        return group;
    }

    public void clearTrackGroup(SampleTrack track) {
        if (track == null || !track.hasGroup()) {
            return;
        }
        int groupId = track.getGroupId();
        track.clearGroup();
        pruneEmptyGroup(groupId);
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    /** Remove several tracks from whatever groups they belong to; delete emptied groups. */
    public void clearTracksFromGroups(List<SampleTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        Set<Integer> touchedGroups = new LinkedHashSet<>();
        for (SampleTrack track : tracks) {
            if (track != null && track.hasGroup()) {
                touchedGroups.add(track.getGroupId());
                track.clearGroup();
            }
        }
        for (int groupId : touchedGroups) {
            pruneEmptyGroup(groupId);
        }
        if (!touchedGroups.isEmpty()) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    public void setGroupColor(int groupId, Color color) {
        SampleGroup group = sampleGroups.get(groupId);
        if (group != null && color != null) {
            group.setColor(color);
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    public void removeSampleGroup(int groupId) {
        if (!sampleGroups.containsKey(groupId)) {
            return;
        }
        for (SampleTrack track : sampleTracks) {
            if (track.getGroupId() == groupId) {
                track.clearGroup();
            }
        }
        sampleGroups.remove(groupId);
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    /** Drop a group definition when it no longer has any member tracks. */
    private void pruneEmptyGroup(int groupId) {
        if (!sampleGroups.containsKey(groupId)) {
            return;
        }
        if (countTracksInGroup(groupId) == 0) {
            sampleGroups.remove(groupId);
        }
    }
}
