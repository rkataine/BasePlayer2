package org.baseplayer.services;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;

public abstract class TrackViewportRegistry {

  public record VisibleTrackSlot(int slotIndex, int backingTrackIndex) {
    public int slot() {
      return slotIndex;
    }

    public int trackIndex() {
      return backingTrackIndex;
    }
  }

  public static final double DEFAULT_MASTER_BAND_HEIGHT_PIXELS = 28;
  public static final double MINIMUM_TRACK_ROW_HEIGHT_PIXELS = 20;

  private final IntegerProperty hoveredTrackIndex = new SimpleIntegerProperty(-1);
  private final DoubleProperty masterBandHeightPixels =
      new SimpleDoubleProperty(DEFAULT_MASTER_BAND_HEIGHT_PIXELS);

  private int firstVisibleTrackSlot = -1;
  private int lastVisibleTrackSlot = -1;
  private double verticalScrollOffsetPixels = 0;
  private double trackRowHeightPixels = 0;
  private double trackViewportHeightPixels = 0;
  private boolean trackRowHeightLocked = false;

  public abstract int getDisplayedTrackCount();

  protected abstract void onVisibleTrackRangeOrRowHeightChanged();

  public int getHoveredTrackIndex() {
    return hoveredTrackIndex.get();
  }

  public void setHoveredTrackIndex(int index) {
    hoveredTrackIndex.set(index);
  }

  public IntegerProperty hoveredTrackIndexProperty() {
    return hoveredTrackIndex;
  }

  public int getFirstVisibleTrackSlot() {
    return firstVisibleTrackSlot;
  }

  public int getLastVisibleTrackSlot() {
    return lastVisibleTrackSlot;
  }

  public int getVisibleTrackSlotCount() {
    if (firstVisibleTrackSlot < 0 || lastVisibleTrackSlot < 0) {
      return 0;
    }
    return Math.max(0, lastVisibleTrackSlot - firstVisibleTrackSlot + 1);
  }

  public double getVerticalScrollOffsetPixels() {
    return verticalScrollOffsetPixels;
  }

  public double getTrackRowHeightPixels() {
    return trackRowHeightPixels;
  }

  public double getTrackViewportHeightPixels() {
    return trackViewportHeightPixels;
  }

  public double getTotalTrackContentHeightPixels() {
    return getDisplayedTrackCount() * trackRowHeightPixels;
  }

  public double getMaxVerticalScrollOffsetPixels(double viewportHeightPixels) {
    if (getDisplayedTrackCount() <= 0) {
      return 0;
    }
    return Math.max(0, getTotalTrackContentHeightPixels() - Math.max(0, viewportHeightPixels));
  }

  public double clampVerticalScrollOffsetPixels(double offsetPixels, double viewportHeightPixels) {
    return Math.max(0, Math.min(offsetPixels, getMaxVerticalScrollOffsetPixels(viewportHeightPixels)));
  }

  public void lockTrackRowHeight() {
    trackRowHeightLocked = true;
  }

  public void unlockTrackRowHeight() {
    trackRowHeightLocked = false;
  }

  public boolean isTrackRowHeightLocked() {
    return trackRowHeightLocked;
  }

  public void setVisibleTrackRange(int firstSlot, int lastSlot, double viewportHeightPixels) {
    setVisibleTrackRange(firstSlot, lastSlot, Double.NaN, Double.NaN, viewportHeightPixels);
  }

  public void setVisibleTrackRange(int firstSlot, int lastSlot, double rowHeightPixels,
                                   double scrollOffsetPixels, double viewportHeightPixels) {
    int trackCount = getDisplayedTrackCount();

    if (trackCount <= 0 || firstSlot < 0 || lastSlot < 0) {
      boolean rangeChanged = firstVisibleTrackSlot != -1 || lastVisibleTrackSlot != -1;
      boolean heightChanged = trackRowHeightPixels != 0;
      firstVisibleTrackSlot = -1;
      lastVisibleTrackSlot = -1;
      trackRowHeightPixels = 0;
      verticalScrollOffsetPixels = 0;
      if (viewportHeightPixels > 0) {
        trackViewportHeightPixels = viewportHeightPixels;
      }
      if (rangeChanged || heightChanged) {
        onVisibleTrackRangeOrRowHeightChanged();
      }
      return;
    }

    int newFirst = Math.min(firstSlot, lastSlot);
    int newLast = Math.max(firstSlot, lastSlot);
    newFirst = Math.max(0, Math.min(trackCount - 1, newFirst));
    newLast = Math.max(newFirst, Math.min(trackCount - 1, newLast));
    int windowSize = newLast - newFirst + 1;

    double newRowHeight;
    double effectiveViewport = viewportHeightPixels;
    if (Double.isNaN(rowHeightPixels)) {
      if (effectiveViewport <= 0) {
        effectiveViewport = trackViewportHeightPixels;
      }
      newRowHeight = effectiveViewport > 0
          ? effectiveViewport / windowSize
          : trackRowHeightPixels;
    } else {
      newRowHeight = Math.max(0, rowHeightPixels);
      if (effectiveViewport <= 0) {
        effectiveViewport = newRowHeight > 0
            ? newRowHeight * windowSize
            : trackViewportHeightPixels;
      }
    }

    if (effectiveViewport > 0) {
      trackViewportHeightPixels = effectiveViewport;
    }

    double desiredScroll = Double.isNaN(scrollOffsetPixels)
        ? newFirst * newRowHeight
        : scrollOffsetPixels;
    double maxScroll = Math.max(0, trackCount * newRowHeight - Math.max(0, trackViewportHeightPixels));
    double newScroll = Math.max(0, Math.min(desiredScroll, maxScroll));

    boolean rangeChanged = newFirst != firstVisibleTrackSlot || newLast != lastVisibleTrackSlot;
    boolean heightChanged = Math.abs(newRowHeight - trackRowHeightPixels) > 1e-9;

    firstVisibleTrackSlot = newFirst;
    lastVisibleTrackSlot = newLast;
    trackRowHeightPixels = newRowHeight;
    verticalScrollOffsetPixels = newScroll;

    if (rangeChanged || heightChanged) {
      onVisibleTrackRangeOrRowHeightChanged();
    }
  }

  public void clearVisibleTrackRange() {
    trackRowHeightLocked = false;
    setVisibleTrackRange(-1, -1, 0, 0, 0);
  }

  public void setVerticalScrollOffsetPixels(double scrollOffsetPixels, double viewportHeightPixels) {
    if (firstVisibleTrackSlot < 0 || lastVisibleTrackSlot < 0) {
      return;
    }
    setVisibleTrackRange(
        firstVisibleTrackSlot, lastVisibleTrackSlot,
        trackRowHeightPixels, scrollOffsetPixels, viewportHeightPixels);
  }

  public void clampVerticalScrollOffsetForViewport(double viewportHeightPixels) {
    if (firstVisibleTrackSlot < 0 || lastVisibleTrackSlot < 0) {
      setVisibleTrackRange(-1, -1, 0, 0, viewportHeightPixels);
      return;
    }
    setVisibleTrackRange(
        firstVisibleTrackSlot, lastVisibleTrackSlot,
        trackRowHeightPixels, verticalScrollOffsetPixels, viewportHeightPixels);
  }

  public void lockAndKeepTrackRowHeight(double rowHeightPixels) {
    trackRowHeightLocked = true;
    if (firstVisibleTrackSlot < 0 || lastVisibleTrackSlot < 0) {
      return;
    }
    setVisibleTrackRange(
        firstVisibleTrackSlot, lastVisibleTrackSlot,
        rowHeightPixels, verticalScrollOffsetPixels, trackViewportHeightPixels);
  }

  /**
   * Sync viewport math to the current body-canvas height.
   *
   * <p>When row height is unlocked, visible rows are refit so they always fill
   * {@code availableHeightPixels} (or the visible window is shrunk if the pane
   * is shorter than {@link #MINIMUM_TRACK_ROW_HEIGHT_PIXELS} per row). When
   * locked (e.g. during scrollbar drag), only scroll is clamped.
   */
  public void ensureTrackRowHeightFitsViewport(double availableHeightPixels) {
    if (firstVisibleTrackSlot < 0 || lastVisibleTrackSlot < 0) {
      clampVerticalScrollOffsetForViewport(availableHeightPixels);
      return;
    }

    if (trackRowHeightLocked) {
      clampVerticalScrollOffsetForViewport(availableHeightPixels);
      return;
    }

    int visibleCount = getVisibleTrackSlotCount();
    double rawHeight = availableHeightPixels / Math.max(1, visibleCount);
    if (rawHeight < MINIMUM_TRACK_ROW_HEIGHT_PIXELS) {
      int tracksFit = Math.max(1, (int) (availableHeightPixels / MINIMUM_TRACK_ROW_HEIGHT_PIXELS));
      int firstVis = Math.max(0, firstVisibleTrackSlot);
      // NaN scroll: snap to first*height. Passing a stale offset after a height/window
      // change desyncs the window from the rail and looks like the range was cleared.
      setVisibleTrackRange(
          firstVis, firstVis + tracksFit - 1,
          MINIMUM_TRACK_ROW_HEIGHT_PIXELS, Double.NaN, availableHeightPixels);
    } else {
      double alignedScroll = firstVisibleTrackSlot * rawHeight;
      boolean heightUnchanged = Math.abs(rawHeight - trackRowHeightPixels) <= 1e-9
          && Math.abs(availableHeightPixels - trackViewportHeightPixels) <= 1e-9;
      boolean scrollAligned = Math.abs(verticalScrollOffsetPixels - alignedScroll) <= 0.5;
      if (heightUnchanged && scrollAligned) {
        return;
      }
      setVisibleTrackRange(
          firstVisibleTrackSlot, lastVisibleTrackSlot,
          rawHeight, Double.NaN, availableHeightPixels);
    }
  }

  public void showAllTracksAndResetRowHeight() {
    trackRowHeightLocked = false;
    setVisibleTrackRange(0, Integer.MAX_VALUE, 0, 0, trackViewportHeightPixels);
  }

  public void includeNewTracksAtEndAndResetRowHeight() {
    trackRowHeightLocked = false;
    int first = firstVisibleTrackSlot < 0 ? 0 : firstVisibleTrackSlot;
    setVisibleTrackRange(
        first, Integer.MAX_VALUE, 0,
        firstVisibleTrackSlot < 0 ? 0 : verticalScrollOffsetPixels,
        trackViewportHeightPixels);
  }

  public void adjustWindowAfterTrackRemoval(int removedDisplayedSlot, int previousFirstSlot,
                                            int previousWindowSize) {
    int newCount = getDisplayedTrackCount();
    if (newCount <= 0) {
      clearVisibleTrackRange();
      return;
    }

    int newWindow = Math.max(1, Math.min(previousWindowSize, newCount));
    int newFirst = previousFirstSlot;
    if (removedDisplayedSlot >= 0 && removedDisplayedSlot < previousFirstSlot) {
      newFirst = previousFirstSlot - 1;
    }
    setVisibleTrackRange(
        newFirst, newFirst + newWindow - 1,
        trackRowHeightPixels, Double.NaN, trackRowHeightPixels * newWindow);
  }

  protected void normalizeVisibleRangeAfterDisplayedTrackCountChange() {
    int displayed = getDisplayedTrackCount();
    if (displayed <= 0) {
      setVisibleTrackRange(-1, -1, trackRowHeightPixels, 0, trackViewportHeightPixels);
      return;
    }

    int first = firstVisibleTrackSlot < 0 ? 0 : firstVisibleTrackSlot;
    int last = lastVisibleTrackSlot < 0 ? first : lastVisibleTrackSlot;
    setVisibleTrackRange(
        first, last, trackRowHeightPixels, verticalScrollOffsetPixels, trackViewportHeightPixels);
  }

  public double getMasterBandHeightPixels() {
    return masterBandHeightPixels.get();
  }

  public DoubleProperty masterBandHeightProperty() {
    return masterBandHeightPixels;
  }

  public void setMasterBandHeightPixels(double heightPixels) {
    masterBandHeightPixels.set(Math.max(0, heightPixels));
    onVisibleTrackRangeOrRowHeightChanged();
  }

  public List<VisibleTrackSlot> buildVisibleTrackSlotList(List<Integer> displayedBackingTrackIndices) {
    List<VisibleTrackSlot> visibleSlots = new ArrayList<>();
    if (displayedBackingTrackIndices == null || displayedBackingTrackIndices.isEmpty()) {
      return visibleSlots;
    }
    if (firstVisibleTrackSlot < 0 || lastVisibleTrackSlot < firstVisibleTrackSlot) {
      return visibleSlots;
    }

    int slotCount = displayedBackingTrackIndices.size();
    int clampedFirst = Math.max(0, Math.min(slotCount - 1, firstVisibleTrackSlot));
    int clampedLast = Math.max(clampedFirst, Math.min(slotCount - 1, lastVisibleTrackSlot));
    for (int slot = clampedFirst; slot <= clampedLast; slot++) {
      int backingIndex = displayedBackingTrackIndices.get(slot);
      if (backingIndex >= 0) {
        visibleSlots.add(new VisibleTrackSlot(slot, backingIndex));
      }
    }
    return visibleSlots;
  }
}
