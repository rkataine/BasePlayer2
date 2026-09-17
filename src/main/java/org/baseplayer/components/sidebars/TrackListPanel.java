package org.baseplayer.components.sidebars;

import java.util.function.DoubleUnaryOperator;

import org.baseplayer.services.TrackViewportRegistry;
import org.baseplayer.utils.DrawColors;

import javafx.animation.AnimationTimer;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

public abstract class TrackListPanel extends SidebarContentPanel {

  private static final double TRACK_SCROLLBAR_WIDTH = 8;
  private static final double TRACK_SCROLLBAR_MARGIN = 3;
  private static final double TRACK_SCROLLBAR_MINIMUM_THUMB_HEIGHT = 18;
  private static final long SCROLL_ANIMATION_DURATION_NANOSECONDS = 180_000_000L;
  private static final DoubleUnaryOperator EASE_OUT_CUBIC =
      progress -> 1 - Math.pow(1 - progress, 3);

  private final TrackViewportRegistry trackViewportRegistry;
  private AnimationTimer scrollAnimation;
  private long scrollAnimationStartNanoseconds;
  private int animationTargetFirstSlot = -1;
  private int animationTargetLastSlot = -1;
  private int animationWindowSize = -1;
  private boolean trackScrollbarVisible;
  private boolean trackScrollbarDragging;
  private boolean suppressClickFromScrollbar;
  private boolean mouseOverTrackList;
  private double trackScrollbarX;
  private double trackScrollbarTop;
  private double trackScrollbarHeight;
  private double trackScrollbarThumbY;
  private double trackScrollbarThumbHeight;
  private int trackScrollbarMaximumFirstSlot;
  private double trackScrollbarDragOffsetY;
  private int dragScrollbarWindowSize = 1;
  private double dragScrollbarLockedRowHeight;

  protected TrackListPanel(StackPane parent, TrackViewportRegistry trackViewportRegistry) {
    super(parent);
    this.trackViewportRegistry = trackViewportRegistry;
    setupHoverHandlers();
    setupMouseTracking();
    setupScrollAndClickHandlers();

    trackViewportRegistry.hoveredTrackIndexProperty().addListener((observable, oldValue, newValue) -> {
      int newHoveredTrackIndex = newValue.intValue();
      if (hoverIndex != newHoveredTrackIndex) {
        hoverIndex = newHoveredTrackIndex;
        draw();
        drawReactive();
      }
    });
  }

  protected abstract TrackViewportRegistry getTrackViewportRegistry();

  protected abstract int getDisplayedTrackCount();

  protected abstract int getBackingTrackIndexForVisibleSlot(int visibleSlotIndex);

  protected abstract int getVisibleSlotIndexForBackingTrackIndex(int backingTrackIndex);

  protected abstract void drawTrackRows(
      double panelWidthPixels, double panelHeightPixels, double rightUiInsetPixels);

  protected abstract void drawHoveredTrackRowReactiveOverlay(
      double panelWidthPixels, double panelHeightPixels);

  protected abstract boolean handleTrackRowIconClick(
      String iconId, int backingTrackIndex, double screenX, double screenY);

  protected abstract void onAfterVisibleTrackRangeChanged();

  protected final boolean isMouseOverTrackList() {
    return mouseOverTrackList;
  }

  protected final double getRightUiInsetPixels() {
    return trackScrollbarVisible
        ? TRACK_SCROLLBAR_WIDTH + TRACK_SCROLLBAR_MARGIN + 2
        : 0;
  }

  protected final int findVisibleSlotIndexAtVerticalCoordinate(double verticalCoordinate) {
    double rowHeight = trackViewportRegistry.getTrackRowHeightPixels();
    if (rowHeight <= 0 || getDisplayedTrackCount() <= 0) {
      return -1;
    }
    int slot = (int) ((verticalCoordinate
        + trackViewportRegistry.getVerticalScrollOffsetPixels()) / rowHeight);
    return slot >= 0 && slot < getDisplayedTrackCount() ? slot : -1;
  }

  @Override
  protected final int findRowAt(double verticalCoordinate) {
    int visibleSlotIndex = findVisibleSlotIndexAtVerticalCoordinate(verticalCoordinate);
    return visibleSlotIndex < 0
        ? -1
        : getBackingTrackIndexForVisibleSlot(visibleSlotIndex);
  }

  @Override
  protected final String findIconAt(double x, double y, int backingTrackIndex) {
    return findIconFromRegions(x, y, backingTrackIndex);
  }

  @Override
  protected void onHoverRowChanged(int previousRow, int currentRow) {
    trackViewportRegistry.setHoveredTrackIndex(currentRow);
    draw();
  }

  @Override
  public final void draw() {
    double panelWidth = canvas.getWidth();
    double panelHeight = canvas.getHeight();

    gc.setFill(DrawColors.SIDEBAR);
    gc.fillRect(0, 0, panelWidth, panelHeight);
    clearIconRegions();

    ensureVisibleRangeInitialized(panelHeight);
    updateTrackScrollbarGeometry(panelWidth, panelHeight);
    drawTrackRows(panelWidth, panelHeight, getRightUiInsetPixels());
    drawTrackScrollbar();
  }

  @Override
  protected final void drawReactive() {
    double panelWidth = reactiveCanvas.getWidth();
    double panelHeight = reactiveCanvas.getHeight();
    reactiveGc.clearRect(0, 0, panelWidth, panelHeight);
    drawHoveredTrackRowReactiveOverlay(panelWidth, panelHeight);
  }

  private void ensureVisibleRangeInitialized(double viewportHeight) {
    int trackCount = getDisplayedTrackCount();
    if (trackCount <= 0 || trackViewportRegistry.getFirstVisibleTrackSlot() >= 0) {
      return;
    }
    trackViewportRegistry.setVisibleTrackRange(0, trackCount - 1, viewportHeight);
  }

  private void setupMouseTracking() {
    reactiveCanvas.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> mouseOverTrackList = true);
    reactiveCanvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> mouseOverTrackList = false);
  }

  private void setupScrollAndClickHandlers() {
    reactiveCanvas.addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
      if (!trackScrollbarVisible) {
        if (!trackScrollbarDragging) {
          reactiveCanvas.setCursor(Cursor.DEFAULT);
        }
      } else if (trackScrollbarDragging) {
        reactiveCanvas.setCursor(Cursor.CLOSED_HAND);
      } else if (isPointInTrackScrollbarThumb(event.getX(), event.getY())) {
        reactiveCanvas.setCursor(Cursor.OPEN_HAND);
      } else if (isPointInTrackScrollbar(event.getX(), event.getY())) {
        reactiveCanvas.setCursor(Cursor.HAND);
      } else {
        reactiveCanvas.setCursor(Cursor.DEFAULT);
      }
    });

    reactiveCanvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
      if (!trackScrollbarDragging) {
        reactiveCanvas.setCursor(Cursor.DEFAULT);
      }
    });

    reactiveCanvas.setOnMousePressed(event -> {
      if (trackScrollbarDragging) {
        event.consume();
        return;
      }
      if (!isPointInTrackScrollbar(event.getX(), event.getY())) {
        return;
      }

      event.consume();
      suppressClickFromScrollbar = true;
      if (isPointInTrackScrollbarThumb(event.getX(), event.getY())) {
        trackScrollbarDragging = true;
        trackScrollbarDragOffsetY = event.getY() - trackScrollbarThumbY;
        int[] baseRange = getBaseRange();
        dragScrollbarWindowSize = Math.max(1, baseRange[1] - baseRange[0] + 1);
        dragScrollbarLockedRowHeight = trackViewportRegistry.getTrackRowHeightPixels();
        cancelTransientScrollState();
        trackViewportRegistry.lockAndKeepTrackRowHeight(dragScrollbarLockedRowHeight);
        reactiveCanvas.setCursor(Cursor.CLOSED_HAND);
      } else {
        int nextFirstSlot =
            firstSlotFromScrollbarThumbTop(event.getY() - trackScrollbarThumbHeight * 0.5);
        applyIntervalAtFirstSlot(nextFirstSlot, true);
      }
    });

    reactiveCanvas.setOnMouseDragged(event -> {
      if (!trackScrollbarDragging) {
        return;
      }
      event.consume();
      double nextFirstPosition =
          firstSlotPositionFromScrollbarThumbTop(event.getY() - trackScrollbarDragOffsetY);
      previewIntervalAtFirstSlot(nextFirstPosition);
    });

    reactiveCanvas.setOnMouseReleased(event -> {
      if (trackScrollbarDragging) {
        finishContinuousScrollbarDrag();
      }
      trackScrollbarDragging = false;
      trackScrollbarDragOffsetY = 0;
      if (isPointInTrackScrollbarThumb(event.getX(), event.getY())) {
        reactiveCanvas.setCursor(Cursor.OPEN_HAND);
      } else if (isPointInTrackScrollbar(event.getX(), event.getY())) {
        reactiveCanvas.setCursor(Cursor.HAND);
      } else {
        reactiveCanvas.setCursor(Cursor.DEFAULT);
      }
    });

    reactiveCanvas.setOnScroll(event -> {
      event.consume();
      if (trackScrollbarDragging) {
        return;
      }

      int trackCount = getDisplayedTrackCount();
      double rowHeight = trackViewportRegistry.getTrackRowHeightPixels();
      if (trackCount <= 0 || rowHeight <= 0) {
        return;
      }

      int stepDirection = event.getDeltaY() < 0 ? 1 : event.getDeltaY() > 0 ? -1 : 0;
      if (stepDirection == 0) {
        return;
      }

      int baseFirstSlot;
      int baseLastSlot;
      int windowSize;
      if (scrollAnimation != null
          && animationTargetFirstSlot >= 0
          && animationTargetLastSlot >= 0) {
        baseFirstSlot = animationTargetFirstSlot;
        baseLastSlot = animationTargetLastSlot;
        windowSize = Math.max(1, Math.min(trackCount, animationWindowSize));
      } else {
        baseFirstSlot = Math.max(
            0, Math.min(trackCount - 1, trackViewportRegistry.getFirstVisibleTrackSlot()));
        baseLastSlot = Math.max(baseFirstSlot,
            Math.min(trackCount - 1, trackViewportRegistry.getLastVisibleTrackSlot()));
        if (baseFirstSlot != trackViewportRegistry.getFirstVisibleTrackSlot()
            || baseLastSlot != trackViewportRegistry.getLastVisibleTrackSlot()) {
          trackViewportRegistry.setVisibleTrackRange(
              baseFirstSlot, baseLastSlot, rowHeight, Double.NaN, 0);
        }
        windowSize = Math.max(1, Math.min(trackCount, baseLastSlot - baseFirstSlot + 1));
      }

      int maximumFirstSlot = Math.max(0, trackCount - windowSize);
      if ((stepDirection < 0 && baseFirstSlot <= 0)
          || (stepDirection > 0 && baseFirstSlot >= maximumFirstSlot)) {
        settleScrollBoundary(baseFirstSlot, windowSize, rowHeight, trackCount);
        return;
      }

      int nextFirstSlot =
          Math.max(0, Math.min(maximumFirstSlot, baseFirstSlot + stepDirection * windowSize));
      int nextLastSlot = Math.min(trackCount - 1, nextFirstSlot + windowSize - 1);
      animatePagedScroll(nextFirstSlot, nextLastSlot, rowHeight, windowSize);
    });

    reactiveCanvas.setOnMouseClicked(event -> {
      if (trackScrollbarDragging) {
        event.consume();
        return;
      }
      if (suppressClickFromScrollbar) {
        suppressClickFromScrollbar = false;
        return;
      }
      if (isPointInTrackScrollbar(event.getX(), event.getY())) {
        return;
      }

      int backingTrackIndex = findRowAt(event.getY());
      if (backingTrackIndex >= 0) {
        String iconId = findIconAt(event.getX(), event.getY(), backingTrackIndex);
        if (iconId != null && handleTrackRowIconClick(
            iconId, backingTrackIndex, event.getScreenX(), event.getScreenY())) {
          return;
        }
        if (handleTrackRowClick(event, backingTrackIndex)) {
          return;
        }
      }

      if (event.getClickCount() == 2) {
        toggleSingleTrackZoom(backingTrackIndex);
      }
    });
  }

  /**
   * Optional row-click handling (e.g. multi-select). Return true if the click was consumed.
   */
  protected boolean handleTrackRowClick(MouseEvent event, int backingTrackIndex) {
    return false;
  }

  private void toggleSingleTrackZoom(int backingTrackIndex) {
    int trackCount = getDisplayedTrackCount();
    if (trackCount <= 0) {
      return;
    }

    double viewportHeight = getTrackViewportHeight();
    if (trackViewportRegistry.getFirstVisibleTrackSlot()
        == trackViewportRegistry.getLastVisibleTrackSlot()) {
      trackViewportRegistry.setVisibleTrackRange(0, trackCount - 1, viewportHeight);
    } else {
      int targetBackingTrackIndex = backingTrackIndex >= 0
          ? backingTrackIndex
          : trackViewportRegistry.getHoveredTrackIndex();
      int visibleSlotIndex = getVisibleSlotIndexForBackingTrackIndex(targetBackingTrackIndex);
      if (visibleSlotIndex >= 0) {
        trackViewportRegistry.setVisibleTrackRange(
            visibleSlotIndex, visibleSlotIndex, viewportHeight);
      }
    }
    onAfterVisibleTrackRangeChanged();
  }

  private double getTrackViewportHeight() {
    double canvasHeight = Math.max(0, canvas.getHeight());
    if (canvasHeight > 0) {
      return canvasHeight;
    }
    double storedHeight = trackViewportRegistry.getTrackViewportHeightPixels();
    if (storedHeight > 0) {
      return storedHeight;
    }
    return trackViewportRegistry.getTrackRowHeightPixels()
        * Math.max(1, trackViewportRegistry.getVisibleTrackSlotCount());
  }

  private void animatePagedScroll(
      int nextFirstSlot, int nextLastSlot, double lockedRowHeight, int windowSize) {
    cancelTransientScrollState();
    animationTargetFirstSlot = nextFirstSlot;
    animationTargetLastSlot = nextLastSlot;
    animationWindowSize = Math.max(1, windowSize);

    trackViewportRegistry.lockTrackRowHeight();
    trackViewportRegistry.setVisibleTrackRange(
        nextFirstSlot, nextLastSlot, lockedRowHeight, Double.NaN, 0);

    double viewportHeight = getTrackViewportHeight();
    double startScroll = trackViewportRegistry.getVerticalScrollOffsetPixels();
    double targetScroll = trackViewportRegistry.clampVerticalScrollOffsetPixels(
        nextFirstSlot * lockedRowHeight, viewportHeight);
    if (Math.abs(targetScroll - startScroll) < 0.5) {
      trackViewportRegistry.setVerticalScrollOffsetPixels(targetScroll, viewportHeight);
      trackViewportRegistry.unlockTrackRowHeight();
      clearAnimationTargets();
      onAfterVisibleTrackRangeChanged();
      return;
    }

    scrollAnimationStartNanoseconds = System.nanoTime();
    scrollAnimation = new AnimationTimer() {
      @Override
      public void handle(long now) {
        double progress = (double) (now - scrollAnimationStartNanoseconds)
            / SCROLL_ANIMATION_DURATION_NANOSECONDS;
        if (progress >= 1.0) {
          trackViewportRegistry.setVerticalScrollOffsetPixels(targetScroll, viewportHeight);
          trackViewportRegistry.unlockTrackRowHeight();
          clearAnimationTargets();
          stop();
          scrollAnimation = null;
          onAfterVisibleTrackRangeChanged();
          return;
        }

        double easedProgress = EASE_OUT_CUBIC.applyAsDouble(Math.max(0.0, progress));
        double currentScroll = startScroll + (targetScroll - startScroll) * easedProgress;
        trackViewportRegistry.setVerticalScrollOffsetPixels(currentScroll, viewportHeight);
        onAfterVisibleTrackRangeChanged();
      }
    };
    scrollAnimation.start();
  }

  private void settleScrollBoundary(
      int requestedFirstSlot, int windowSize, double rowHeight, int trackCount) {
    int normalizedWindowSize = Math.max(1, Math.min(trackCount, windowSize));
    int maximumFirstSlot = Math.max(0, trackCount - normalizedWindowSize);
    int clampedFirstSlot = Math.max(0, Math.min(maximumFirstSlot, requestedFirstSlot));
    int clampedLastSlot =
        Math.min(trackCount - 1, clampedFirstSlot + normalizedWindowSize - 1);
    int previousFirstSlot = trackViewportRegistry.getFirstVisibleTrackSlot();
    int previousLastSlot = trackViewportRegistry.getLastVisibleTrackSlot();
    double previousScroll = trackViewportRegistry.getVerticalScrollOffsetPixels();
    boolean hadTransientState = scrollAnimation != null
        || animationTargetFirstSlot >= 0
        || animationTargetLastSlot >= 0
        || trackViewportRegistry.isTrackRowHeightLocked();

    cancelTransientScrollState();
    trackViewportRegistry.unlockTrackRowHeight();
    trackViewportRegistry.setVisibleTrackRange(
        clampedFirstSlot, clampedLastSlot, rowHeight, Double.NaN, 0);
    if (hadTransientState
        || previousFirstSlot != clampedFirstSlot
        || previousLastSlot != clampedLastSlot
        || Math.abs(previousScroll
            - trackViewportRegistry.getVerticalScrollOffsetPixels()) > 0.5) {
      onAfterVisibleTrackRangeChanged();
    }
  }

  private void applyIntervalAtFirstSlot(int requestedFirstSlot, boolean animated) {
    int trackCount = getDisplayedTrackCount();
    double rowHeight = trackViewportRegistry.getTrackRowHeightPixels();
    if (trackCount <= 0 || rowHeight <= 0) {
      return;
    }

    int[] baseRange = getBaseRange();
    int windowSize = Math.max(1, Math.min(trackCount, baseRange[1] - baseRange[0] + 1));
    int maximumFirstSlot = Math.max(0, trackCount - windowSize);
    int nextFirstSlot = Math.max(0, Math.min(maximumFirstSlot, requestedFirstSlot));
    int nextLastSlot = Math.min(trackCount - 1, nextFirstSlot + windowSize - 1);
    if (nextFirstSlot == baseRange[0] && nextLastSlot == baseRange[1]) {
      return;
    }

    if (animated) {
      animatePagedScroll(nextFirstSlot, nextLastSlot, rowHeight, windowSize);
      return;
    }

    cancelTransientScrollState();
    trackViewportRegistry.unlockTrackRowHeight();
    trackViewportRegistry.setVisibleTrackRange(
        nextFirstSlot, nextLastSlot, rowHeight, Double.NaN, 0);
    onAfterVisibleTrackRangeChanged();
  }

  private int[] getBaseRange() {
    int trackCount = getDisplayedTrackCount();
    if (trackCount <= 0) {
      return new int[] {0, 0};
    }
    if (scrollAnimation != null
        && animationTargetFirstSlot >= 0
        && animationTargetLastSlot >= 0) {
      int firstSlot = Math.max(0, Math.min(trackCount - 1, animationTargetFirstSlot));
      int lastSlot =
          Math.max(firstSlot, Math.min(trackCount - 1, animationTargetLastSlot));
      return new int[] {firstSlot, lastSlot};
    }
    int firstSlot = Math.max(
        0, Math.min(trackCount - 1, trackViewportRegistry.getFirstVisibleTrackSlot()));
    int lastSlot = Math.max(
        firstSlot, Math.min(trackCount - 1, trackViewportRegistry.getLastVisibleTrackSlot()));
    return new int[] {firstSlot, lastSlot};
  }

  private void previewIntervalAtFirstSlot(double requestedFirstPosition) {
    int trackCount = getDisplayedTrackCount();
    double rowHeight = dragScrollbarLockedRowHeight > 0
        ? dragScrollbarLockedRowHeight
        : trackViewportRegistry.getTrackRowHeightPixels();
    if (trackCount <= 0 || rowHeight <= 0) {
      return;
    }

    int windowSize = Math.max(1, Math.min(trackCount, dragScrollbarWindowSize));
    int maximumFirstSlot = Math.max(0, trackCount - windowSize);
    double firstPosition = Math.max(0, Math.min(maximumFirstSlot, requestedFirstPosition));
    int previewFirstSlot = (int) Math.floor(firstPosition);
    int previewLastSlot =
        Math.min(trackCount - 1, previewFirstSlot + windowSize - 1);
    trackViewportRegistry.setVisibleTrackRange(
        previewFirstSlot, previewLastSlot, rowHeight,
        firstPosition * rowHeight, rowHeight * windowSize);
    onAfterVisibleTrackRangeChanged();
  }

  private void finishContinuousScrollbarDrag() {
    int trackCount = getDisplayedTrackCount();
    double rowHeight = dragScrollbarLockedRowHeight > 0
        ? dragScrollbarLockedRowHeight
        : trackViewportRegistry.getTrackRowHeightPixels();
    if (trackCount <= 0 || rowHeight <= 0) {
      trackViewportRegistry.unlockTrackRowHeight();
      return;
    }

    int windowSize = Math.max(1, Math.min(trackCount, dragScrollbarWindowSize));
    int maximumFirstSlot = Math.max(0, trackCount - windowSize);
    int snappedFirstSlot =
        (int) Math.round(trackViewportRegistry.getVerticalScrollOffsetPixels() / rowHeight);
    snappedFirstSlot = Math.max(0, Math.min(maximumFirstSlot, snappedFirstSlot));
    applyIntervalAtFirstSlot(snappedFirstSlot, false);
    dragScrollbarWindowSize = 1;
    dragScrollbarLockedRowHeight = 0;
  }

  private void updateTrackScrollbarGeometry(double canvasWidth, double canvasHeight) {
    int trackCount = getDisplayedTrackCount();
    if (trackCount <= 0) {
      trackScrollbarVisible = false;
      return;
    }

    int firstSlot = Math.max(
        0, Math.min(trackCount - 1, trackViewportRegistry.getFirstVisibleTrackSlot()));
    int lastSlot = Math.max(
        firstSlot, Math.min(trackCount - 1, trackViewportRegistry.getLastVisibleTrackSlot()));
    int windowSize = Math.max(1, Math.min(trackCount, lastSlot - firstSlot + 1));
    trackScrollbarMaximumFirstSlot = Math.max(0, trackCount - windowSize);
    trackScrollbarVisible = windowSize < trackCount;
    if (!trackScrollbarVisible) {
      return;
    }

    trackScrollbarX = canvasWidth - TRACK_SCROLLBAR_WIDTH - TRACK_SCROLLBAR_MARGIN;
    trackScrollbarTop = 0;
    trackScrollbarHeight = Math.max(0, canvasHeight);
    trackScrollbarThumbHeight = Math.max(TRACK_SCROLLBAR_MINIMUM_THUMB_HEIGHT,
        trackScrollbarHeight * windowSize / trackCount);
    trackScrollbarThumbHeight =
        Math.min(trackScrollbarHeight, trackScrollbarThumbHeight);
    double travel = Math.max(1, trackScrollbarHeight - trackScrollbarThumbHeight);
    double progress = trackScrollbarMaximumFirstSlot > 0
        ? firstSlot / (double) trackScrollbarMaximumFirstSlot
        : 0;
    trackScrollbarThumbY = trackScrollbarTop + progress * travel;
  }

  private void drawTrackScrollbar() {
    if (!trackScrollbarVisible) {
      return;
    }
    gc.setFill(Color.rgb(120, 120, 120, 0.26));
    gc.fillRoundRect(trackScrollbarX, trackScrollbarTop,
        TRACK_SCROLLBAR_WIDTH, trackScrollbarHeight, 4, 4);
    gc.setFill(Color.rgb(225, 225, 225, 0.82));
    gc.fillRoundRect(trackScrollbarX, trackScrollbarThumbY,
        TRACK_SCROLLBAR_WIDTH, trackScrollbarThumbHeight, 4, 4);

    double centerX = trackScrollbarX + TRACK_SCROLLBAR_WIDTH * 0.5;
    double middleY = trackScrollbarThumbY + trackScrollbarThumbHeight * 0.5;
    gc.setStroke(Color.rgb(60, 60, 60, 0.82));
    gc.strokeLine(centerX - 2, middleY - 3, centerX + 2, middleY - 3);
    gc.strokeLine(centerX - 2, middleY, centerX + 2, middleY);
    gc.strokeLine(centerX - 2, middleY + 3, centerX + 2, middleY + 3);
  }

  private boolean isPointInTrackScrollbar(double x, double y) {
    return trackScrollbarVisible
        && x >= trackScrollbarX - 1
        && x <= trackScrollbarX + TRACK_SCROLLBAR_WIDTH + 1
        && y >= trackScrollbarTop
        && y <= trackScrollbarTop + trackScrollbarHeight;
  }

  private boolean isPointInTrackScrollbarThumb(double x, double y) {
    return trackScrollbarVisible
        && x >= trackScrollbarX - 1
        && x <= trackScrollbarX + TRACK_SCROLLBAR_WIDTH + 1
        && y >= trackScrollbarThumbY
        && y <= trackScrollbarThumbY + trackScrollbarThumbHeight;
  }

  private int firstSlotFromScrollbarThumbTop(double thumbTopY) {
    return (int) Math.round(firstSlotPositionFromScrollbarThumbTop(thumbTopY));
  }

  private double firstSlotPositionFromScrollbarThumbTop(double thumbTopY) {
    if (!trackScrollbarVisible || trackScrollbarMaximumFirstSlot <= 0) {
      return Math.max(0, Math.min(getDisplayedTrackCount() - 1,
          trackViewportRegistry.getFirstVisibleTrackSlot()));
    }
    double travel = Math.max(1, trackScrollbarHeight - trackScrollbarThumbHeight);
    double clampedTop =
        Math.max(trackScrollbarTop, Math.min(trackScrollbarTop + travel, thumbTopY));
    return (clampedTop - trackScrollbarTop) / travel * trackScrollbarMaximumFirstSlot;
  }

  private void cancelTransientScrollState() {
    if (scrollAnimation != null) {
      scrollAnimation.stop();
      scrollAnimation = null;
    }
    clearAnimationTargets();
  }

  private void clearAnimationTargets() {
    animationTargetFirstSlot = -1;
    animationTargetLastSlot = -1;
    animationWindowSize = -1;
  }
}
