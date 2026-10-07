package org.baseplayer.services;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.genome.gene.GeneLocation;
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

    // ── Sample groups (sidebar coloring; independent of text/gene subsets) ──

    /** Flat cohort used by Sample Comparison after {@link #syncComparisonCohorts()}. */
    public static final String PARENTALS_COHORT_NAME = "Parentals";
    /** Flat cohort used by Sample Comparison after {@link #syncComparisonCohorts()}. */
    public static final String EVOLVED_COHORT_NAME = "Evolved";
    public static final Color PARENTALS_COHORT_COLOR = Color.web("#4db8ff");
    public static final Color EVOLVED_COHORT_COLOR = Color.web("#f0a030");

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

    /** Name-match mode for marking parentals. */
    public enum ParentalNameMatchMode {
        CONTAINS,
        STARTS_WITH,
        REGEX
    }

    public IntegerProperty sampleGroupsRevisionProperty() {
        return sampleGroupsRevision;
    }

    private void bumpSampleGroupsRevision() {
        sampleGroupsRevision.set(sampleGroupsRevision.get() + 1);
        if (!ProjectSessionState.get().isSuppressingDirty()) {
            org.baseplayer.project.SessionDocumentSync.writeSampleGroupsFromRegistry();
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

    /**
     * One sidebar accent column per root lineage. Subgroup membership expands to the
     * parent root so the upper group still shows; subgroup color is drawn indented
     * after a gap in the sidebar.
     */
    public record SidebarLineageAccent(Color rootColor, Color subgroupColor) {
        public boolean hasSubgroup() {
            return subgroupColor != null;
        }
    }

    /**
     * Lineage-aware sidebar accents: one column per root the track belongs to
     * (directly or via a subgroup). Unrelated multi-group roots stay separate columns.
     */
    public List<SidebarLineageAccent> getSidebarLineageAccents(SampleTrack track) {
        if (track == null || !track.hasGroup()) {
            return List.of();
        }
        // rootId -> first subgroup color encountered (null if root-only so far)
        LinkedHashMap<Integer, Color> subgroupByRoot = new LinkedHashMap<>();
        for (int groupId : track.getGroupIds()) {
            SampleGroup group = sampleGroups.get(groupId);
            if (group == null) {
                continue;
            }
            int rootId;
            Color subgroupColor = null;
            if (group.isSubgroup()) {
                rootId = group.getParentGroupId();
                subgroupColor = group.getColor();
            } else {
                rootId = group.getId();
            }
            if (!sampleGroups.containsKey(rootId)) {
                // Orphan subgroup — treat the group itself as the column.
                rootId = group.getId();
                subgroupColor = null;
            }
            if (!subgroupByRoot.containsKey(rootId)) {
                subgroupByRoot.put(rootId, subgroupColor);
            } else if (subgroupByRoot.get(rootId) == null && subgroupColor != null) {
                // Prefer showing subgroup when both root and subgroup membership exist.
                subgroupByRoot.put(rootId, subgroupColor);
            }
        }
        List<SidebarLineageAccent> accents = new ArrayList<>(subgroupByRoot.size());
        for (Map.Entry<Integer, Color> entry : subgroupByRoot.entrySet()) {
            SampleGroup root = sampleGroups.get(entry.getKey());
            if (root == null || root.getColor() == null) {
                continue;
            }
            accents.add(new SidebarLineageAccent(root.getColor(), entry.getValue()));
        }
        return accents;
    }

    public Color getSidebarColorForTrack(SampleTrack track) {
        SampleGroup group = getGroupForTrack(track);
        return group != null ? group.getColor() : null;
    }

    /**
     * Flat root colors for each lineage column (one per root). Prefer
     * {@link #getSidebarLineageAccents} when drawing compound subgroup bars.
     */
    public List<Color> getSidebarColorsForTrack(SampleTrack track) {
        List<SidebarLineageAccent> accents = getSidebarLineageAccents(track);
        if (accents.isEmpty()) {
            return List.of();
        }
        List<Color> colors = new ArrayList<>(accents.size());
        for (SidebarLineageAccent accent : accents) {
            colors.add(accent.rootColor());
        }
        return colors;
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
        return createSampleGroup(name, color, SampleGroup.NO_PARENT);
    }

    /**
     * Create a group. When {@code parentGroupId} is a root group, the new group is a
     * one-level subgroup. Nested subgroups are rejected ({@code null} returned).
     */
    public SampleGroup createSampleGroup(String name, Color color, int parentGroupId) {
        int resolvedParent = SampleGroup.NO_PARENT;
        if (parentGroupId >= 0) {
            SampleGroup parent = sampleGroups.get(parentGroupId);
            if (parent == null || parent.isSubgroup()) {
                return null;
            }
            resolvedParent = parentGroupId;
        }
        int id = nextSampleGroupId++;
        Color resolved = color != null
            ? color
            : DrawColors.SAMPLE_GROUP_COLORS[(id - 1) % DrawColors.SAMPLE_GROUP_COLORS.length];
        String resolvedName = (name == null || name.isBlank()) ? ("Group " + id) : name.trim();
        SampleGroup group = new SampleGroup(id, resolvedName, resolved);
        group.setParentGroupId(resolvedParent);
        sampleGroups.put(id, group);
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
        return group;
    }

    public SampleGroup createSampleGroup(String name) {
        return createSampleGroup(name, null, SampleGroup.NO_PARENT);
    }

    /** Top-level groups only (no parent). */
    public List<SampleGroup> getRootGroups() {
        List<SampleGroup> roots = new ArrayList<>();
        for (SampleGroup group : sampleGroups.values()) {
            if (group != null && group.isRoot()) {
                roots.add(group);
            }
        }
        return roots;
    }

    /** Direct subgroups of {@code parentId}. */
    public List<SampleGroup> getChildGroups(int parentId) {
        if (parentId < 0) {
            return List.of();
        }
        List<SampleGroup> children = new ArrayList<>();
        for (SampleGroup group : sampleGroups.values()) {
            if (group != null && group.getParentGroupId() == parentId) {
                children.add(group);
            }
        }
        return children;
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
        // Deleting a root also deletes its subgroups.
        List<Integer> toRemove = new ArrayList<>();
        toRemove.add(groupId);
        for (SampleGroup child : getChildGroups(groupId)) {
            toRemove.add(child.getId());
        }
        for (int id : toRemove) {
            for (SampleTrack track : sampleTracks) {
                if (track.isInGroup(id)) {
                    track.removeGroupId(id);
                }
            }
            sampleGroups.remove(id);
        }
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    /** Drop a group definition when it no longer has any member tracks (and no children). */
    private void pruneEmptyGroup(int groupId) {
        if (!sampleGroups.containsKey(groupId)) {
            return;
        }
        if (countTracksInGroup(groupId) == 0 && getChildGroups(groupId).isEmpty()) {
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

    /** Result of {@link #addSubgroupToAllRoots}. */
    public record BulkSubgroupResult(int subgroupsCreated, int tracksMoved) {}

    /**
     * Under every root group, ensure a child subgroup named {@code subgroupName}, then
     * move name-matching members from the root into that subgroup.
     * Idempotent: reuses an existing child with the same name under each root.
     */
    public BulkSubgroupResult addSubgroupToAllRoots(
        String subgroupName,
        String pattern,
        ParentalNameMatchMode matchMode) {
        String resolvedName = (subgroupName == null || subgroupName.isBlank())
            ? "Parental"
            : subgroupName.trim();
        if (pattern == null || pattern.isBlank()) {
            return new BulkSubgroupResult(0, 0);
        }
        ParentalNameMatchMode mode = matchMode != null ? matchMode : ParentalNameMatchMode.CONTAINS;
        Pattern regex = null;
        if (mode == ParentalNameMatchMode.REGEX) {
            try {
                regex = Pattern.compile(pattern);
            } catch (PatternSyntaxException ex) {
                return new BulkSubgroupResult(0, 0);
            }
        }

        int created = 0;
        int moved = 0;
        boolean changed = false;

        for (SampleGroup root : getRootGroups()) {
            if (root == null) {
                continue;
            }
            SampleGroup child = findChildGroupByName(root.getId(), resolvedName);
            if (child == null) {
                child = createSampleGroupSilent(resolvedName, null, root.getId());
                if (child == null) {
                    continue;
                }
                created++;
                changed = true;
            }

            List<SampleTrack> rootMembers = new ArrayList<>(getTracksInGroup(root.getId()));
            SampleTrack firstMatch = null;
            for (SampleTrack track : rootMembers) {
                if (track == null || !matchesParentalName(track, pattern, mode, regex)) {
                    continue;
                }
                if (firstMatch == null) {
                    firstMatch = track;
                }
                boolean added = false;
                if (!track.isInGroup(child.getId())) {
                    track.addGroupId(child.getId());
                    added = true;
                }
                if (track.isInGroup(root.getId())) {
                    track.removeGroupId(root.getId());
                    added = true;
                }
                if (added) {
                    moved++;
                    changed = true;
                }
            }
            if (firstMatch != null) {
                String key = firstMatch.getName() != null
                    ? firstMatch.getName()
                    : firstMatch.getDisplayName();
                if (!Objects.equals(child.getParentalTrackName(), key)) {
                    child.setParentalTrackName(key);
                    changed = true;
                }
            }
        }

        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
        return new BulkSubgroupResult(created, moved);
    }

    private SampleGroup findChildGroupByName(int parentId, String name) {
        if (name == null || name.isBlank() || parentId < 0) {
            return null;
        }
        String key = name.trim();
        for (SampleGroup child : getChildGroups(parentId)) {
            if (child != null && key.equals(child.getName())) {
                return child;
            }
        }
        return null;
    }

    /** Create a group without bumping revision (for batched bulk ops). */
    private SampleGroup createSampleGroupSilent(String name, Color color, int parentGroupId) {
        int resolvedParent = SampleGroup.NO_PARENT;
        if (parentGroupId >= 0) {
            SampleGroup parent = sampleGroups.get(parentGroupId);
            if (parent == null || parent.isSubgroup()) {
                return null;
            }
            resolvedParent = parentGroupId;
        }
        int id = nextSampleGroupId++;
        Color resolved = color != null
            ? color
            : DrawColors.SAMPLE_GROUP_COLORS[(id - 1) % DrawColors.SAMPLE_GROUP_COLORS.length];
        String resolvedName = (name == null || name.isBlank()) ? ("Group " + id) : name.trim();
        SampleGroup group = new SampleGroup(id, resolvedName, resolved);
        group.setParentGroupId(resolvedParent);
        sampleGroups.put(id, group);
        return group;
    }

    /**
     * For each track matching {@code pattern}, set parental on every lineage group
     * it belongs to (skips Parentals/Evolved cohort names). Does not create a mega-group.
     *
     * @return number of group parental assignments updated
     */
    public int markParentalsByNamePattern(String pattern, ParentalNameMatchMode matchMode) {
        if (pattern == null || pattern.isBlank()) {
            return 0;
        }
        ParentalNameMatchMode mode = matchMode != null ? matchMode : ParentalNameMatchMode.CONTAINS;
        Pattern regex = null;
        if (mode == ParentalNameMatchMode.REGEX) {
            try {
                regex = Pattern.compile(pattern);
            } catch (PatternSyntaxException ex) {
                return 0;
            }
        }
        int updated = 0;
        boolean changed = false;
        for (SampleTrack track : sampleTracks) {
            if (track == null || !matchesParentalName(track, pattern, mode, regex)) {
                continue;
            }
            for (int groupId : track.getGroupIds()) {
                SampleGroup group = sampleGroups.get(groupId);
                if (group == null || isComparisonCohortName(group.getName())) {
                    continue;
                }
                String key = track.getName() != null ? track.getName() : track.getDisplayName();
                if (!Objects.equals(group.getParentalTrackName(), key)) {
                    group.setParentalTrackName(key);
                    updated++;
                    changed = true;
                }
            }
        }
        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }
        return updated;
    }

    private static boolean matchesParentalName(
        SampleTrack track, String pattern, ParentalNameMatchMode mode, Pattern regex) {
        String name = track.getDisplayName();
        String raw = track.getName();
        return matchesOneName(name, pattern, mode, regex)
            || (raw != null && !raw.equals(name) && matchesOneName(raw, pattern, mode, regex));
    }

    private static boolean matchesOneName(
        String name, String pattern, ParentalNameMatchMode mode, Pattern regex) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return switch (mode) {
            case CONTAINS -> name.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
            case STARTS_WITH -> name.toLowerCase(Locale.ROOT).startsWith(pattern.toLowerCase(Locale.ROOT));
            case REGEX -> regex != null && regex.matcher(name).find();
        };
    }

    private static boolean isComparisonCohortName(String name) {
        return PARENTALS_COHORT_NAME.equals(name) || EVOLVED_COHORT_NAME.equals(name);
    }

    /** Set the parental track for a lineage group (by display/raw name key). */
    public void setGroupParental(int groupId, SampleTrack track) {
        SampleGroup group = sampleGroups.get(groupId);
        if (group == null) {
            return;
        }
        if (track == null) {
            clearGroupParental(groupId);
            return;
        }
        String key = track.getName() != null ? track.getName() : track.getDisplayName();
        if (Objects.equals(group.getParentalTrackName(), key)) {
            return;
        }
        group.setParentalTrackName(key);
        if (!track.isInGroup(groupId)) {
            track.addGroupId(groupId);
        }
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
    }

    public void clearGroupParental(int groupId) {
        SampleGroup group = sampleGroups.get(groupId);
        if (group == null || group.getParentalTrackName() == null) {
            return;
        }
        group.clearParentalTrackName();
        ProjectSessionState.get().markDirty();
        bumpSampleGroupsRevision();
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

    /**
     * Ensure flat {@code Parentals} and {@code Evolved} cohorts from lineage parental flags.
     * Every designated parental joins Parentals; every other member of a lineage group joins Evolved.
     * Multi-group membership is preserved.
     *
     * @return summary text for the UI
     */
    public String syncComparisonCohorts() {
        SampleGroup parentals = ensureCohortGroup(PARENTALS_COHORT_NAME, PARENTALS_COHORT_COLOR);
        SampleGroup evolved = ensureCohortGroup(EVOLVED_COHORT_NAME, EVOLVED_COHORT_COLOR);
        int parentalsId = parentals.getId();
        int evolvedId = evolved.getId();

        Set<SampleTrack> parentalTracks = new LinkedHashSet<>();
        Set<SampleTrack> evolvedTracks = new LinkedHashSet<>();

        for (SampleGroup group : sampleGroups.values()) {
            if (group == null || isComparisonCohortName(group.getName())) {
                continue;
            }
            List<SampleTrack> members = getTracksInGroup(group.getId());
            if (members.isEmpty()) {
                continue;
            }
            SampleTrack parental = resolveParentalTrack(group, members);
            if (parental == null) {
                // No parental designated — leave cohort membership unchanged for this lineage
                continue;
            }
            for (SampleTrack track : members) {
                if (track == null) {
                    continue;
                }
                if (track == parental || group.isParental(track)) {
                    parentalTracks.add(track);
                } else {
                    evolvedTracks.add(track);
                }
            }
        }

        // A track marked parental in any lineage should not stay only in Evolved.
        evolvedTracks.removeAll(parentalTracks);

        boolean changed = false;
        for (SampleTrack track : sampleTracks) {
            if (track == null) {
                continue;
            }
            boolean wantParental = parentalTracks.contains(track);
            boolean wantEvolved = evolvedTracks.contains(track);
            if (wantParental && !track.isInGroup(parentalsId)) {
                track.addGroupId(parentalsId);
                changed = true;
            } else if (!wantParental && track.isInGroup(parentalsId)) {
                track.removeGroupId(parentalsId);
                changed = true;
            }
            if (wantEvolved && !track.isInGroup(evolvedId)) {
                track.addGroupId(evolvedId);
                changed = true;
            } else if (!wantEvolved && track.isInGroup(evolvedId)) {
                track.removeGroupId(evolvedId);
                changed = true;
            }
        }

        pruneEmptyGroup(parentalsId);
        pruneEmptyGroup(evolvedId);

        if (changed) {
            ProjectSessionState.get().markDirty();
            bumpSampleGroupsRevision();
        }

        int parentalCount = countTracksInGroup(parentalsId);
        int evolvedCount = countTracksInGroup(evolvedId);
        return "Parentals: " + parentalCount + ", Evolved: " + evolvedCount;
    }

    private SampleGroup ensureCohortGroup(String name, Color color) {
        SampleGroup existing = findSampleGroupByName(name);
        if (existing != null) {
            if (color != null && !existing.getColor().equals(color)) {
                existing.setColor(color);
            }
            return existing;
        }
        return createSampleGroup(name, color);
    }

    private SampleTrack resolveParentalTrack(SampleGroup group, List<SampleTrack> members) {
        if (group.getParentalTrackName() == null) {
            return null;
        }
        for (SampleTrack track : members) {
            if (group.isParental(track)) {
                return track;
            }
        }
        // Parental key set but track not in group — still try global match
        String key = group.getParentalTrackName();
        for (SampleTrack track : sampleTracks) {
            if (track != null
                && (key.equals(track.getName()) || key.equals(track.getDisplayName()))) {
                return track;
            }
        }
        return null;
    }
}
