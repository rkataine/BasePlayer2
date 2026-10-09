package org.baseplayer.services;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTag;
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
    private GeneLocation focusedGeneLocus = null;
    private final IntegerProperty geneFocusRevision = new SimpleIntegerProperty(0);
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
            // Keep session dirty + live document sampleTracks in sync.
            Runnable sync = () -> {
                ProjectSessionState.get().markDirty();
                if (ProjectSessionState.get().isSuppressingDirty()) {
                    return;
                }
                org.baseplayer.project.SessionDocumentSync.writeSampleTracksFromRuntime(
                    ProjectSessionState.get().getFile());
                org.baseplayer.project.SessionDocumentSync.writeSampleGroupsFromRegistry();
            };
            if (Platform.isFxApplicationThread()) {
                sync.run();
            } else {
                Platform.runLater(sync);
            }
        });
    }

    @Override
    protected void onVisibleTrackRangeOrRowHeightChanged() {
        // Viewport range / row height only affects which sample rows are drawn.
        // Aggregate density is over the displayed subset (filter / gene focus), not the
        // visible window — do not clear or recompute density here (causes flashing).
        invalidateSampleTrackVariantIndexes();
        if (!ProjectSessionState.get().isSuppressingDirty()) {
            org.baseplayer.project.SessionDocumentSync.writeViewportsFromRuntime();
        }
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
        focusedGeneLocus = null;
        bumpGeneFocusRevision();
        clearSampleGroups();
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
            // Same revision the subset banner listens to for gene focus.
            bumpGeneFocusRevision();
            notifyVariantIndexDirty();
            org.baseplayer.project.SessionDocumentSync.writeSampleFilter(
                this.activeSampleFilterQuery, focusedGeneName);
        }
    }

    public boolean hasFocusedTracks() {
        return focusedTracks != null;
    }

    /** Tracks currently included by gene focus (empty if none). */
    public List<SampleTrack> getFocusedTracks() {
        if (focusedTracks == null || focusedTracks.isEmpty()) {
            return List.of();
        }
        return List.copyOf(focusedTracks);
    }

    public String getFocusedGeneName() {
        return focusedGeneName;
    }

    /** Genomic locus for the current gene focus, if resolvable. */
    public GeneLocation getFocusedGeneLocus() {
        return focusedGeneLocus;
    }

    /** True when a named gene (not a position click) currently drives the sample subset. */
    public boolean hasGeneFocusBanner() {
        return hasFocusedTracks()
            && focusedGeneName != null
            && !focusedGeneName.isBlank()
            && !focusedGeneName.startsWith("Position:");
    }

    public IntegerProperty geneFocusRevisionProperty() {
        return geneFocusRevision;
    }

    private void bumpGeneFocusRevision() {
        geneFocusRevision.set(geneFocusRevision.get() + 1);
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
        String oldFeatureName = focusedGeneName;

        if (tracks == null || tracks.isEmpty()) {
            focusedTracks = null;
            focusedGeneName = null;
            focusedGeneLocus = null;
        } else {
            focusedTracks = new LinkedHashSet<>();
            for (SampleTrack track : tracks) {
                if (track != null) {
                    focusedTracks.add(track);
                }
            }
            if (focusedTracks.isEmpty()) {
                focusedTracks = null;
                focusedGeneName = null;
                focusedGeneLocus = null;
            } else {
                focusedGeneName = featureName;
                focusedGeneLocus = resolveGeneLocus(featureName);
            }
        }

        invalidateDisplayedTrackIndicesCache();
        normalizeVisibleRangeAfterDisplayedTrackCountChange();
        bumpGeneFocusRevision();
        notifyVariantIndexDirty();
        boolean changed = (hadFocusedTracks != (focusedTracks != null))
            || (oldFeatureName == null ? focusedGeneName != null : !oldFeatureName.equals(focusedGeneName));
        if (changed) {
            org.baseplayer.project.SessionDocumentSync.writeSampleFilter(
                activeSampleFilterQuery, focusedGeneName);
        }
    }

    private static GeneLocation resolveGeneLocus(String featureName) {
        if (featureName == null || featureName.isBlank() || featureName.startsWith("Position:")) {
            return null;
        }
        return AnnotationData.getGeneLocation(featureName);
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
            focusedGeneLocus = null;
            changed = true;
        }

        invalidateDisplayedTrackIndicesCache();
        normalizeVisibleRangeAfterDisplayedTrackCountChange();
        if (changed) {
            bumpGeneFocusRevision();
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

    /** Sample tracks currently shown under text filter and/or gene focus. */
    public List<SampleTrack> getDisplayedTracks() {
        List<Integer> indices = getDisplayedTrackIndicesCached();
        if (indices.isEmpty()) {
            return List.of();
        }
        List<SampleTrack> tracks = new ArrayList<>(indices.size());
        for (int index : indices) {
            if (index >= 0 && index < sampleTracks.size()) {
                SampleTrack track = sampleTracks.get(index);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }
        return tracks;
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

    private void invalidateSampleTrackVariantIndexes() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.sampleTrackCanvas != null) {
                stack.sampleTrackCanvas.invalidateVariantIndex();
            }
        }
    }

    private void notifyVariantIndexDirty() {
        invalidateSampleTrackVariantIndexes();
        // Density bins are keyed by displayed-sample subset; gene focus / text
        // filter / track-list changes must recompute or the aggregate band keeps
        // the old heights. Visible-range scrolling must not call this.
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.sampleAggregateCanvas != null) {
                stack.sampleAggregateCanvas.forceCalculateDensity();
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

    // ── Sample groups + fixed tags (sidebar; independent of text/gene subsets) ──

    /** How directory segments map to lineage group names. */
    public enum DirectorySegmentMode {
        /** Immediate parent folder of the sample file. */
        PARENT_FOLDER,
        /**
         * Strip {@code commonBasePath} and use the first remaining path segment
         * (“root” under that base).
         */
        FIRST_UNDER_BASE
    }

    public IntegerProperty sampleGroupsRevisionProperty() {
        return sampleGroupsRevision;
    }

    private void bumpSampleGroupsRevision() {
        // Property listeners (sidebar draw, toolbar) must run on the FX thread.
        // Project restore bumps this from a background ThreadRunner job.
        Runnable bump = () -> {
            sampleGroupsRevision.set(sampleGroupsRevision.get() + 1);
            if (!ProjectSessionState.get().isSuppressingDirty()) {
                org.baseplayer.project.SessionDocumentSync.writeSampleGroupsFromRegistry();
            }
        };
        if (Platform.isFxApplicationThread()) {
            bump.run();
        } else {
            Platform.runLater(bump);
        }
    }

    /** Drop all group definitions and clear membership on every track. */
    public void clearSampleGroups() {
        if (sampleGroups.isEmpty() && nextSampleGroupId == 1) {
            for (SampleTrack track : sampleTracks) {
                if (track != null && track.hasGroup()) {
                    track.clearGroup();
                }
            }
            return;
        }
        for (SampleTrack track : sampleTracks) {
            if (track != null) {
                track.clearGroup();
            }
        }
        sampleGroups.clear();
        nextSampleGroupId = 1;
        bumpSampleGroupsRevision();
    }

    /**
     * Replace group definitions from a project snapshot (preserves ids).
     * Does not assign track membership — callers set {@link SampleTrack#addGroupId} after.
     */
    public void replaceSampleGroups(List<SampleGroup> groups) {
        sampleGroups.clear();
        int maxId = 0;
        if (groups != null) {
            for (SampleGroup group : groups) {
                if (group == null || group.getId() < 0) {
                    continue;
                }
                sampleGroups.put(group.getId(), group);
                maxId = Math.max(maxId, group.getId());
            }
        }
        nextSampleGroupId = maxId + 1;
        bumpSampleGroupsRevision();
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

    /** All groups this track belongs to, in sidebar bar order. */
    public List<SampleGroup> getGroupsForTrack(SampleTrack track) {
        if (track == null || !track.hasGroup()) {
            return List.of();
        }
        List<SampleGroup> groups = new ArrayList<>();
        for (int groupId : track.getGroupIds()) {
            SampleGroup group = sampleGroups.get(groupId);
            if (group != null) {
                groups.add(group);
            }
        }
        return groups;
    }

    public Color getSidebarColorForTrack(SampleTrack track) {
        SampleGroup group = getGroupForTrack(track);
        return group != null ? group.getColor() : null;
    }

    /** Flat group accent colors in membership order (no subgroup nesting). */
    public List<Color> getSidebarColorsForTrack(SampleTrack track) {
        List<SampleGroup> groups = getGroupsForTrack(track);
        if (groups.isEmpty()) {
            return List.of();
        }
        List<Color> colors = new ArrayList<>(groups.size());
        for (SampleGroup group : groups) {
            if (group != null && group.getColor() != null) {
                colors.add(group.getColor());
            }
        }
        return colors;
    }

    /** True when any named group exists or any track has a fixed tag. */
    public boolean hasGroupsOrTags() {
        if (!sampleGroups.isEmpty()) {
            return true;
        }
        for (SampleTrack track : sampleTracks) {
            if (track != null && track.hasAnyTag()) {
                return true;
            }
        }
        return false;
    }

    public void setTrackTags(SampleTrack track, Set<SampleTag> tags) {
        if (track == null) {
            return;
        }
        Set<SampleTag> before = track.getTags();
        track.setTags(tags);
        boolean changed = !before.equals(track.getTags());
        if (track.hasTag(SampleTag.PARENTAL)) {
            changed |= tagGroupMatesAsChild(track);
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    public void toggleTrackTag(SampleTrack track, SampleTag tag) {
        if (track == null || tag == null) {
            return;
        }
        track.toggleTag(tag);
        if (tag == SampleTag.PARENTAL && track.hasTag(SampleTag.PARENTAL)) {
            tagGroupMatesAsChild(track);
        }
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    public void setTracksTags(List<SampleTrack> tracks, Set<SampleTag> tags) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (SampleTrack track : tracks) {
            if (track == null) {
                continue;
            }
            Set<SampleTag> before = track.getTags();
            track.setTags(tags);
            if (!before.equals(track.getTags())) {
                changed = true;
            }
            if (track.hasTag(SampleTag.PARENTAL)) {
                changed |= tagGroupMatesAsChild(track);
            }
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    public void addTagToTracks(List<SampleTrack> tracks, SampleTag tag) {
        if (tracks == null || tag == null) {
            return;
        }
        boolean changed = false;
        for (SampleTrack track : tracks) {
            if (track != null && !track.hasTag(tag)) {
                track.addTag(tag);
                changed = true;
            }
        }
        if (tag == SampleTag.PARENTAL) {
            for (SampleTrack track : tracks) {
                if (track != null && track.hasTag(SampleTag.PARENTAL)) {
                    changed |= tagGroupMatesAsChild(track);
                }
            }
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    /**
     * LOH convenience: when a track is tagged Parental, other members of its
     * sample groups that are not Parental/Marker get Child.
     *
     * @return true if any tag was added
     */
    private boolean tagGroupMatesAsChild(SampleTrack parentalTrack) {
        if (parentalTrack == null || !parentalTrack.hasTag(SampleTag.PARENTAL)
            || !parentalTrack.hasGroup()) {
            return false;
        }
        boolean changed = false;
        for (int groupId : parentalTrack.getGroupIds()) {
            for (SampleTrack other : getTracksInGroup(groupId)) {
                if (other == null || other == parentalTrack) {
                    continue;
                }
                if (other.hasTag(SampleTag.PARENTAL) || other.hasTag(SampleTag.MARKER)) {
                    continue;
                }
                if (!other.hasTag(SampleTag.CHILD)) {
                    other.addTag(SampleTag.CHILD);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public void clearTagsFromTracks(List<SampleTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (SampleTrack track : tracks) {
            if (track != null && track.hasAnyTag()) {
                track.clearTags();
                changed = true;
            }
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    public int countTracksInGroup(int groupId) {
        int count = 0;
        for (SampleTrack track : sampleTracks) {
            if (track.isInGroup(groupId)) {
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

    /** Add tracks to a group without removing existing memberships. */
    public void assignTracksToGroup(List<SampleTrack> tracks, int groupId) {
        if (tracks == null || !sampleGroups.containsKey(groupId)) {
            return;
        }
        boolean changed = false;
        for (SampleTrack track : tracks) {
            if (track == null || track.isInGroup(groupId)) {
                continue;
            }
            track.addGroupId(groupId);
            changed = true;
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    /**
     * Replace each track's group membership with exactly {@code groupId}
     * (one solid sidebar accent). Used when opening directories into groups.
     */
    public void setTracksExclusiveGroup(List<SampleTrack> tracks, int groupId) {
        if (tracks == null || !sampleGroups.containsKey(groupId)) {
            return;
        }
        boolean changed = false;
        for (SampleTrack track : tracks) {
            if (track == null) {
                continue;
            }
            List<Integer> current = track.getGroupIds();
            if (current.size() == 1 && current.get(0) == groupId) {
                continue;
            }
            track.setGroupId(groupId);
            changed = true;
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
    }

    public void assignTrackToGroup(SampleTrack track, int groupId) {
        if (track == null) {
            return;
        }
        if (groupId < 0) {
            clearTrackGroup(track);
            return;
        }
        if (!sampleGroups.containsKey(groupId) || track.isInGroup(groupId)) {
            return;
        }
        track.addGroupId(groupId);
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

    /** Remove a track from one group; prune the group if emptied. */
    public void removeTrackFromGroup(SampleTrack track, int groupId) {
        if (track == null || !track.isInGroup(groupId)) {
            return;
        }
        track.removeGroupId(groupId);
        pruneEmptyGroup(groupId);
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    public void clearTrackGroup(SampleTrack track) {
        if (track == null || !track.hasGroup()) {
            return;
        }
        Set<Integer> previous = new LinkedHashSet<>(track.getGroupIds());
        track.clearGroup();
        for (int groupId : previous) {
            pruneEmptyGroup(groupId);
        }
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    /** Remove several tracks from all groups they belong to; delete emptied groups. */
    public void clearTracksFromGroups(List<SampleTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        Set<Integer> touchedGroups = new LinkedHashSet<>();
        for (SampleTrack track : tracks) {
            if (track != null && track.hasGroup()) {
                touchedGroups.addAll(track.getGroupIds());
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
            if (track.isInGroup(groupId)) {
                track.removeGroupId(groupId);
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

    public SampleGroup findSampleGroupByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim();
        for (SampleGroup group : sampleGroups.values()) {
            if (group != null && key.equals(group.getName())) {
                return group;
            }
        }
        return null;
    }

    public List<SampleTrack> getTracksInGroup(int groupId) {
        List<SampleTrack> tracks = new ArrayList<>();
        for (SampleTrack track : sampleTracks) {
            if (track != null && track.isInGroup(groupId)) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    /** Primary file path for a track (first sample with a path). */
    public static Path primaryPathOf(SampleTrack track) {
        if (track == null) {
            return null;
        }
        for (Sample sample : track.getSamples()) {
            if (sample != null && sample.getPath() != null) {
                return sample.getPath();
            }
        }
        return null;
    }

    /**
     * Directory segment used as lineage group name for {@code track}.
     *
     * @param commonBasePath only used with {@link DirectorySegmentMode#FIRST_UNDER_BASE}
     */
    public static String directorySegmentFor(
        SampleTrack track, DirectorySegmentMode mode, Path commonBasePath) {
        Path path = primaryPathOf(track);
        if (path == null) {
            return null;
        }
        DirectorySegmentMode resolved = mode != null ? mode : DirectorySegmentMode.PARENT_FOLDER;
        if (resolved == DirectorySegmentMode.FIRST_UNDER_BASE && commonBasePath != null) {
            try {
                Path absolute = path.toAbsolutePath().normalize();
                Path base = commonBasePath.toAbsolutePath().normalize();
                if (absolute.startsWith(base)) {
                    Path relative = base.relativize(absolute);
                    if (relative.getNameCount() >= 1) {
                        Path first = relative.getName(0);
                        String name = first != null ? first.toString() : "";
                        return name.isBlank() ? null : name;
                    }
                }
            } catch (Exception ignored) {
                // fall through to parent folder
            }
        }
        Path parent = path.getParent();
        if (parent == null) {
            return null;
        }
        Path fileName = parent.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        return name.isBlank() ? null : name;
    }

    /** Preview of directory → group names and member counts (does not mutate). */
    public Map<String, List<SampleTrack>> previewGroupsFromDirectory(
        DirectorySegmentMode mode, Path commonBasePath) {
        Map<String, List<SampleTrack>> bySegment = new LinkedHashMap<>();
        for (SampleTrack track : sampleTracks) {
            if (track == null) {
                continue;
            }
            String segment = directorySegmentFor(track, mode, commonBasePath);
            if (segment == null || segment.isBlank()) {
                continue;
            }
            bySegment.computeIfAbsent(segment, key -> new ArrayList<>()).add(track);
        }
        return bySegment;
    }

    /**
     * Create (or reuse by name) a lineage group per distinct directory segment and
     * assign tracks. Existing memberships are kept; tracks are added to the lineage group.
     *
     * @return number of groups created or updated with new members
     */
    public int createGroupsFromDirectory(DirectorySegmentMode mode, Path commonBasePath) {
        Map<String, List<SampleTrack>> bySegment = previewGroupsFromDirectory(mode, commonBasePath);
        if (bySegment.isEmpty()) {
            return 0;
        }
        int touched = 0;
        boolean changed = false;
        for (Map.Entry<String, List<SampleTrack>> entry : bySegment.entrySet()) {
            String groupName = entry.getKey();
            List<SampleTrack> members = entry.getValue();
            if (members == null || members.isEmpty()) {
                continue;
            }
            SampleGroup group = findSampleGroupByName(groupName);
            boolean created = false;
            if (group == null) {
                group = createSampleGroup(groupName, null);
                created = true;
                changed = true;
            }
            boolean added = false;
            for (SampleTrack track : members) {
                if (track != null && !track.isInGroup(group.getId())) {
                    track.addGroupId(group.getId());
                    added = true;
                    changed = true;
                }
            }
            if (created || added) {
                touched++;
            }
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
        return touched;
    }

    public void renameSampleGroup(int groupId, String name) {
        SampleGroup group = sampleGroups.get(groupId);
        if (group == null || name == null || name.isBlank()) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.equals(group.getName())) {
            return;
        }
        group.setName(trimmed);
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }
}
