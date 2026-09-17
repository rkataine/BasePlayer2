package org.baseplayer.services;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.features.Track;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

public class FeatureTrackViewportRegistry extends TrackViewportRegistry {

  private final ObservableList<Track> featureTracks = FXCollections.observableArrayList();

  public FeatureTrackViewportRegistry() {
    featureTracks.addListener((ListChangeListener<Track>) change -> {
      normalizeVisibleRangeAfterDisplayedTrackCountChange();
    });
  }

  @Override
  protected void onVisibleTrackRangeOrRowHeightChanged() {
    // Callers that need a frame (range apply, sidebar scroll, add/remove) already
    // toggle GenomicCanvas.update. Avoid toggling here — ensure() runs during draw
    // and a nested update would re-enter redrawAll on every height refit.
  }

  public ObservableList<Track> getFeatureTracks() {
    return featureTracks;
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
    int removedDisplayedSlot = featureTracks.indexOf(track);
    int previousFirstSlot = getFirstVisibleTrackSlot();
    int previousWindowSize = getVisibleTrackSlotCount();
    track.dispose();
    featureTracks.remove(track);
    if (removedDisplayedSlot >= 0) {
      adjustWindowAfterTrackRemoval(removedDisplayedSlot, previousFirstSlot, previousWindowSize);
    }
  }

  public void clearFeatureTracks() {
    for (Track track : new ArrayList<>(featureTracks)) {
      track.dispose();
    }
    featureTracks.clear();
    clearVisibleTrackRange();
    setHoveredTrackIndex(-1);
  }

  public int getFeatureTrackIndex(Track track) {
    if (track == null) {
      return -1;
    }
    return featureTracks.indexOf(track);
  }

  @Override
  public int getDisplayedTrackCount() {
    return featureTracks.size();
  }

  public List<VisibleTrackSlot> getVisibleTrackSlots() {
    List<Integer> allIndices = new ArrayList<>(featureTracks.size());
    for (int i = 0; i < featureTracks.size(); i++) {
      allIndices.add(i);
    }
    return buildVisibleTrackSlotList(allIndices);
  }

  public Track getFeatureTrackAtBackingIndex(int backingTrackIndex) {
    if (backingTrackIndex < 0 || backingTrackIndex >= featureTracks.size()) {
      return null;
    }
    return featureTracks.get(backingTrackIndex);
  }
}
