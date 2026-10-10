package org.baseplayer.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.baseplayer.features.AbstractTrack;
import org.baseplayer.features.Track;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.project.SessionDocumentSync;

import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

public class FeatureTrackViewportRegistry extends TrackViewportRegistry {

  /** Handles feature-track icon actions (close / settings / add) from sidebar or canvas overlay. */
  @FunctionalInterface
  public interface TrackIconActionHandler {
    boolean handle(String iconId, int trackIndex, double screenX, double screenY);
  }

  private final ObservableList<Track> featureTracks = FXCollections.observableArrayList();
  private String activeFilterQuery = "";
  private final List<Integer> cachedDisplayedTrackIndices = new ArrayList<>();
  private boolean displayedTrackIndicesDirty = true;
  private final DoubleProperty filterStripHeightPixels = new SimpleDoubleProperty(0);
  private final Set<Integer> selectedTrackIndices = new LinkedHashSet<>();
  private final IntegerProperty selectionRevision = new SimpleIntegerProperty(0);
  private final IntegerProperty aggregateRevision = new SimpleIntegerProperty(0);
  /** Feature names toggled off in the aggregate legend (empty name key = ""). */
  private final Set<String> legendHiddenFeatureNames = new LinkedHashSet<>();
  private TrackIconActionHandler trackIconActionHandler;

  public FeatureTrackViewportRegistry() {
    featureTracks.addListener((ListChangeListener<Track>) change -> {
      invalidateDisplayedTrackIndicesCache();
      pruneSelectionAfterListChange();
      normalizeVisibleRangeAfterDisplayedTrackCountChange();
      bumpAggregateRevision();
      if (!ProjectSessionState.get().isSuppressingDirty()) {
        SessionDocumentSync.writeFeatureTracksFromRuntime(ProjectSessionState.get().getFile());
      }
    });
  }

  @Override
  protected void onVisibleTrackRangeOrRowHeightChanged() {
    if (!ProjectSessionState.get().isSuppressingDirty()) {
      SessionDocumentSync.writeViewportsFromRuntime();
    }
  }

  public ObservableList<Track> getFeatureTracks() {
    return featureTracks;
  }

  public void setTrackIconActionHandler(TrackIconActionHandler handler) {
    this.trackIconActionHandler = handler;
  }

  public boolean handleTrackIconAction(
      String iconId, int trackIndex, double screenX, double screenY) {
    return trackIconActionHandler != null
        && trackIconActionHandler.handle(iconId, trackIndex, screenX, screenY);
  }

  public void addFeatureTrack(Track track) {
    if (track == null) {
      throw new IllegalArgumentException("Feature track cannot be null");
    }
    featureTracks.add(track);
  }

  public void removeFeatureTrack(Track track) {
    if (track == null) {
      return;
    }
    org.baseplayer.features.BedVariantAnnotation.clearIfTrack(track);
    int backingIndex = featureTracks.indexOf(track);
    int removedDisplayedSlot = getDisplayedSlotForTrackIndex(backingIndex);
    int previousFirstSlot = getFirstVisibleTrackSlot();
    int previousWindowSize = getVisibleTrackSlotCount();
    selectedTrackIndices.remove(backingIndex);
    track.dispose();
    featureTracks.remove(track);
    reindexSelectionAfterRemoval(backingIndex);
    if (removedDisplayedSlot >= 0) {
      adjustWindowAfterTrackRemoval(removedDisplayedSlot, previousFirstSlot, previousWindowSize);
    }
    bumpSelectionRevision();
  }

  public void clearFeatureTracks() {
    boolean hadAnnotation =
        !org.baseplayer.features.BedVariantAnnotation.annotationTracks().isEmpty();
    for (Track track : new ArrayList<>(featureTracks)) {
      if (track instanceof org.baseplayer.features.BedTrack bed) {
        bed.setVariantAnnotationMode(org.baseplayer.features.BedVariantAnnotation.Mode.OFF);
      }
      track.dispose();
    }
    featureTracks.clear();
    selectedTrackIndices.clear();
    legendHiddenFeatureNames.clear();
    clearVisibleTrackRange();
    setHoveredTrackIndex(-1);
    bumpSelectionRevision();
    bumpAggregateRevision();
    if (hadAnnotation) {
      org.baseplayer.features.BedVariantAnnotation.bumpAndRefreshVariants();
    }
  }

  public int getFeatureTrackIndex(Track track) {
    if (track == null) {
      return -1;
    }
    return featureTracks.indexOf(track);
  }

  @Override
  public int getDisplayedTrackCount() {
    return getDisplayedTrackIndicesCached().size();
  }

  public List<Integer> getDisplayedTrackIndices() {
    return List.copyOf(getDisplayedTrackIndicesCached());
  }

  public List<VisibleTrackSlot> getVisibleTrackSlots() {
    List<Integer> displayed = getDisplayedTrackIndicesCached();
    if (displayed.isEmpty()) {
      return List.of();
    }
    return buildVisibleTrackSlotList(displayed);
  }

  public Track getFeatureTrackAtBackingIndex(int backingTrackIndex) {
    if (backingTrackIndex < 0 || backingTrackIndex >= featureTracks.size()) {
      return null;
    }
    return featureTracks.get(backingTrackIndex);
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

  // ── Text filter ──────────────────────────────────────────────────────────

  public void applyTextSubsetQuery(String query) {
    String normalized = query == null ? "" : query.trim();
    this.activeFilterQuery = normalized;
    invalidateDisplayedTrackIndicesCache();
    normalizeVisibleRangeAfterDisplayedTrackCountChange();
  }

  public String getActiveFilterQuery() {
    return activeFilterQuery;
  }

  public boolean hasActiveFilterQuery() {
    return activeFilterQuery != null && !activeFilterQuery.isBlank();
  }

  public void clearTextFilter() {
    if (!hasActiveFilterQuery()) {
      return;
    }
    applyTextSubsetQuery("");
  }

  public int replaceInTrackNames(String find, String replacement, boolean displayedOnly) {
    if (find == null || find.isEmpty()) {
      return 0;
    }
    String repl = replacement == null ? "" : replacement;
    int renamed = 0;
    if (displayedOnly && hasActiveFilterQuery()) {
      for (int trackIndex : getDisplayedTrackIndices()) {
        if (renameTrackSubstring(getFeatureTrackAtBackingIndex(trackIndex), find, repl)) {
          renamed++;
        }
      }
    } else {
      for (Track track : featureTracks) {
        if (renameTrackSubstring(track, find, repl)) {
          renamed++;
        }
      }
    }
    if (renamed > 0) {
      invalidateDisplayedTrackIndicesCache();
      ProjectSessionState.get().markDirty();
    }
    return renamed;
  }

  private static boolean renameTrackSubstring(Track track, String find, String replacement) {
    if (!(track instanceof AbstractTrack abstractTrack)) {
      return false;
    }
    String name = abstractTrack.getName();
    if (name == null || name.isEmpty() || find == null || find.isEmpty()) {
      return false;
    }
    String next;
    if (find.indexOf('*') >= 0) {
      Pattern pattern = compileGlobPattern(find, true);
      if (pattern == null) {
        return false;
      }
      Matcher matcher = pattern.matcher(name);
      if (!matcher.find()) {
        return false;
      }
      StringBuffer rewritten = new StringBuffer(name.length());
      try {
        do {
          matcher.appendReplacement(rewritten, replacement);
        } while (matcher.find());
        matcher.appendTail(rewritten);
      } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
        return false;
      }
      next = rewritten.toString().trim();
    } else {
      String lowerName = name.toLowerCase(Locale.ROOT);
      String lowerFind = find.toLowerCase(Locale.ROOT);
      int idx = lowerName.indexOf(lowerFind);
      if (idx < 0) {
        return false;
      }
      StringBuilder rewritten = new StringBuilder(name.length());
      int from = 0;
      int findLen = find.length();
      while (idx >= 0) {
        rewritten.append(name, from, idx);
        rewritten.append(replacement);
        from = idx + findLen;
        idx = lowerName.indexOf(lowerFind, from);
      }
      rewritten.append(name, from, name.length());
      next = rewritten.toString().trim();
    }
    if (next.isEmpty() || next.equals(name)) {
      return false;
    }
    abstractTrack.setName(next);
    return true;
  }

  public List<int[]> findActiveFilterMatchSpans(String text) {
    return findFilterMatchSpans(text, activeFilterQuery);
  }

  public static List<int[]> findFilterMatchSpans(String text, String query) {
    if (text == null || text.isEmpty() || query == null) {
      return List.of();
    }
    String trimmed = query.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    List<int[]> spans = new ArrayList<>();
    if (trimmed.indexOf('*') >= 0) {
      Pattern pattern = compileGlobPattern(trimmed, false);
      if (pattern == null) {
        return List.of();
      }
      Matcher matcher = pattern.matcher(text);
      while (matcher.find()) {
        if (matcher.end() > matcher.start()) {
          spans.add(new int[] { matcher.start(), matcher.end() });
        }
      }
      return spans;
    }
    String lowerText = text.toLowerCase(Locale.ROOT);
    String lowerFind = trimmed.toLowerCase(Locale.ROOT);
    int from = 0;
    while (from < lowerText.length()) {
      int idx = lowerText.indexOf(lowerFind, from);
      if (idx < 0) {
        break;
      }
      spans.add(new int[] { idx, idx + lowerFind.length() });
      from = idx + Math.max(1, lowerFind.length());
    }
    return spans;
  }

  private List<Integer> getDisplayedTrackIndicesCached() {
    if (!displayedTrackIndicesDirty) {
      return cachedDisplayedTrackIndices;
    }
    cachedDisplayedTrackIndices.clear();
    if (featureTracks.isEmpty()) {
      displayedTrackIndicesDirty = false;
      return cachedDisplayedTrackIndices;
    }
    String query = activeFilterQuery == null ? "" : activeFilterQuery.trim();
    if (query.isEmpty()) {
      for (int i = 0; i < featureTracks.size(); i++) {
        cachedDisplayedTrackIndices.add(i);
      }
    } else {
      String needle = query.toLowerCase(Locale.ROOT);
      Pattern globPattern = query.indexOf('*') >= 0 ? compileGlobPattern(query, false) : null;
      for (int i = 0; i < featureTracks.size(); i++) {
        Track track = featureTracks.get(i);
        if (matchesFilter(track, needle, globPattern)) {
          cachedDisplayedTrackIndices.add(i);
        }
      }
    }
    displayedTrackIndicesDirty = false;
    return cachedDisplayedTrackIndices;
  }

  private void invalidateDisplayedTrackIndicesCache() {
    displayedTrackIndicesDirty = true;
  }

  private static boolean matchesFilter(Track track, String needle, Pattern globPattern) {
    if (track == null) {
      return false;
    }
    return textMatches(track.getName(), needle, globPattern)
        || textMatches(track.getType(), needle, globPattern);
  }

  private static boolean textMatches(String text, String needleLower, Pattern globPattern) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    if (globPattern != null) {
      return globPattern.matcher(text).find();
    }
    return text.toLowerCase(Locale.ROOT).contains(needleLower);
  }

  private static Pattern compileGlobPattern(String glob, boolean capturing) {
    if (glob == null || glob.isEmpty()) {
      return null;
    }
    StringBuilder regex = new StringBuilder(glob.length() * 2);
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        regex.append(capturing ? "(.*)" : ".*");
      } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
        regex.append('\\').append(c);
      } else {
        regex.append(c);
      }
    }
    try {
      return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    } catch (PatternSyntaxException e) {
      return null;
    }
  }

  // ── Filter strip / aggregate height ──────────────────────────────────────

  public double getFilterStripHeightPixels() {
    return filterStripHeightPixels.get();
  }

  public DoubleProperty filterStripHeightProperty() {
    return filterStripHeightPixels;
  }

  public void setFilterStripHeightPixels(double heightPixels) {
    filterStripHeightPixels.set(Math.max(0, heightPixels));
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

  public double getAggregateBandHeightPixels() {
    return getMasterTrackHeight() + getFilterStripHeightPixels();
  }

  public DoubleBinding aggregateBandHeightProperty() {
    return masterBandHeightProperty().add(filterStripHeightPixels);
  }

  // ── Selection ────────────────────────────────────────────────────────────

  public Set<Integer> getSelectedTrackIndices() {
    return selectedTrackIndices;
  }

  public IntegerProperty selectionRevisionProperty() {
    return selectionRevision;
  }

  public void setSelectedTrackIndices(Set<Integer> indices) {
    selectedTrackIndices.clear();
    if (indices != null) {
      for (Integer index : indices) {
        if (index != null && index >= 0 && index < featureTracks.size()) {
          selectedTrackIndices.add(index);
        }
      }
    }
    bumpSelectionRevision();
  }

  public void clearSelection() {
    if (selectedTrackIndices.isEmpty()) {
      return;
    }
    selectedTrackIndices.clear();
    bumpSelectionRevision();
  }

  public List<Track> getSelectedTracks() {
    List<Track> tracks = new ArrayList<>(selectedTrackIndices.size());
    for (int index : selectedTrackIndices) {
      Track track = getFeatureTrackAtBackingIndex(index);
      if (track != null) {
        tracks.add(track);
      }
    }
    return tracks;
  }

  private void pruneSelectionAfterListChange() {
    selectedTrackIndices.removeIf(i -> i < 0 || i >= featureTracks.size());
  }

  private void reindexSelectionAfterRemoval(int removedBackingIndex) {
    Set<Integer> next = new LinkedHashSet<>();
    for (int index : selectedTrackIndices) {
      if (index < removedBackingIndex) {
        next.add(index);
      } else if (index > removedBackingIndex) {
        next.add(index - 1);
      }
    }
    selectedTrackIndices.clear();
    selectedTrackIndices.addAll(next);
  }

  private void bumpSelectionRevision() {
    selectionRevision.set(selectionRevision.get() + 1);
    bumpAggregateRevision();
  }

  // ── Aggregate eligibility / legend ───────────────────────────────────────

  public IntegerProperty aggregateRevisionProperty() {
    return aggregateRevision;
  }

  public void bumpAggregateRevision() {
    aggregateRevision.set(aggregateRevision.get() + 1);
  }

  public boolean contributesToAggregate(Track track) {
    return track != null && track.isVisible() && !track.isAggregateDisabled();
  }

  public List<Track> getAggregateEligibleTracks() {
    List<Track> eligible = new ArrayList<>();
    for (Track track : featureTracks) {
      if (contributesToAggregate(track)) {
        eligible.add(track);
      }
    }
    return eligible;
  }

  public void setAggregateDisabled(Track track, boolean disabled) {
    if (track == null) {
      return;
    }
    track.setAggregateDisabled(disabled);
    bumpAggregateRevision();
  }

  public boolean isLegendFeatureNameVisible(String featureName) {
    String key = featureName == null ? "" : featureName;
    return !legendHiddenFeatureNames.contains(key);
  }

  public void toggleLegendFeatureName(String featureName) {
    String key = featureName == null ? "" : featureName;
    if (!legendHiddenFeatureNames.add(key)) {
      legendHiddenFeatureNames.remove(key);
    }
    bumpAggregateRevision();
  }

  public void setAllLegendFeatureNamesVisible(Iterable<String> names, boolean visible) {
    if (names == null) {
      return;
    }
    if (visible) {
      for (String name : names) {
        legendHiddenFeatureNames.remove(name == null ? "" : name);
      }
    } else {
      for (String name : names) {
        legendHiddenFeatureNames.add(name == null ? "" : name);
      }
    }
    bumpAggregateRevision();
  }

  public Set<String> getLegendHiddenFeatureNames() {
    return Set.copyOf(legendHiddenFeatureNames);
  }
}
