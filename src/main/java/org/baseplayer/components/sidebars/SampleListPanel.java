package org.baseplayer.components.sidebars;

import org.baseplayer.components.MasterTrackCanvas;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.draw.ReadColorMode;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.utils.DrawColors;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.animation.AnimationTimer;
import java.util.List;

/**
 * Canvas panel that renders individual sample track rows in the samples sidebar.
 *
 * <p>Each row shows the track name, data file list, and on hover: close (✕),
 * settings (⚙), and add-file (+) buttons.  Coordinates are relative to this
 * panel — the master track header lives in a separate {@link MasterTrackCanvas}
 * pane above and is not included here.</p>
 */
public class SampleListPanel extends SidebarContentPanel {

  // Layout constants
  private static final double ICON_SIZE   = 14;
  private static final double ICON_MARGIN = 4;
  private static final Font   ICON_FONT   = Font.font("Segoe UI Symbol", 12);
  private static final Font   NAME_FONT   = Font.font("Segoe UI", 12);
  private static final double TRACK_SCROLLBAR_WIDTH = 8;
  private static final double TRACK_SCROLLBAR_MARGIN = 3;
  private static final double TRACK_SCROLLBAR_MIN_THUMB_HEIGHT = 18;

  private final SampleRegistry sampleRegistry;
  private AnimationTimer scrollAnimation;
  private long scrollAnimStartNanos;
  private int animationTargetFirst = -1;
  private int animationTargetLast = -1;
  private int animationWindowSize = -1;
  private boolean trackScrollbarVisible = false;
  private ScrollbarDragMode scrollbarDragMode = ScrollbarDragMode.NONE;
  private boolean suppressClickFromScrollbar = false;
  private double trackScrollbarX = 0;
  private double trackScrollbarTop = 0;
  private double trackScrollbarHeight = 0;
  private double trackScrollbarThumbY = 0;
  private double trackScrollbarThumbHeight = 0;
  private int trackScrollbarMaxFirst = 0;
  private double trackScrollbarDragOffsetY = 0;
  private int dragScrollbarWindow = 1;
  private double dragScrollbarLockedSampleHeight = 0;
  private static final long SCROLL_ANIM_DURATION_NS = 180_000_000L;
  private static final java.util.function.DoubleUnaryOperator EASE_OUT_CUBIC =
      t -> 1 - Math.pow(1 - t, 3);
  private static final DrawStackManager stackManager =
      ServiceRegistry.getInstance().getDrawStackManager();

  private enum ScrollbarDragMode {
    NONE,
    MOVE
  }

  private boolean isScrollbarDragActive() {
    return scrollbarDragMode != ScrollbarDragMode.NONE;
  }

  private void cancelTransientScrollState() {
    if (scrollAnimation != null) {
      scrollAnimation.stop();
      scrollAnimation = null;
    }
    clearAnimationTargets();
  }

  public SampleListPanel(StackPane parent) {
    super(parent);
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();

    setupHoverHandlers();
    setupScrollAndClickHandlers();
  }

  @Override
  protected void onHoverRowChanged(int previousRow, int currentRow) {
    // Keep alignment divider highlighting synchronized with the hovered sample row.
    sampleRegistry.hoverSampleProperty().set(currentRow);
    // Repaint static layer so hover-row buttons appear/disappear immediately.
    draw();
  }

  private void setupScrollAndClickHandlers() {
    reactiveCanvas.addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
      if (!trackScrollbarVisible) {
        if (scrollbarDragMode == ScrollbarDragMode.NONE) {
          reactiveCanvas.setCursor(Cursor.DEFAULT);
        }
        return;
      }
      if (scrollbarDragMode == ScrollbarDragMode.MOVE) {
        reactiveCanvas.setCursor(Cursor.CLOSED_HAND);
        return;
      }
      if (isPointInTrackScrollbarThumb(event.getX(), event.getY())) {
        reactiveCanvas.setCursor(Cursor.OPEN_HAND);
      } else if (isPointInTrackScrollbar(event.getX(), event.getY())) {
        reactiveCanvas.setCursor(Cursor.HAND);
      } else {
        reactiveCanvas.setCursor(Cursor.DEFAULT);
      }
    });

    reactiveCanvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
      if (scrollbarDragMode == ScrollbarDragMode.NONE) {
        reactiveCanvas.setCursor(Cursor.DEFAULT);
      }
    });

    reactiveCanvas.setOnMousePressed(event -> {
      if (isScrollbarDragActive()) {
        event.consume();
        return;
      }

      if (!isPointInTrackScrollbar(event.getX(), event.getY())) {
        return;
      }

      event.consume();
      suppressClickFromScrollbar = true;

      if (isPointInTrackScrollbarThumb(event.getX(), event.getY())) {
        scrollbarDragMode = ScrollbarDragMode.MOVE;
        trackScrollbarDragOffsetY = event.getY() - trackScrollbarThumbY;
        int[] base = getBaseRange();
        dragScrollbarWindow = Math.max(1, base[1] - base[0] + 1);
        dragScrollbarLockedSampleHeight = sampleRegistry.getSampleHeight();
        cancelTransientScrollState();
        sampleRegistry.lockSampleHeight();
        sampleRegistry.setSampleHeight(dragScrollbarLockedSampleHeight);
        reactiveCanvas.setCursor(Cursor.CLOSED_HAND);
      } else {
        int nextFirst = firstFromScrollbarThumbTop(event.getY() - trackScrollbarThumbHeight * 0.5);
        applyIntervalAtFirst(nextFirst, true);
      }
    });

    reactiveCanvas.setOnMouseDragged(event -> {
      if (scrollbarDragMode != ScrollbarDragMode.MOVE) {
        return;
      }

      event.consume();
      double nextFirstPos = firstPositionFromScrollbarThumbTop(event.getY() - trackScrollbarDragOffsetY);
      previewIntervalAtFirst(nextFirstPos);
    });

    reactiveCanvas.setOnMouseReleased(event -> {
      if (scrollbarDragMode == ScrollbarDragMode.MOVE) {
        finishContinuousScrollbarDrag();
      }
      scrollbarDragMode = ScrollbarDragMode.NONE;
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

      // Exclusive interaction mode: when dragging/expanding/shrinking with the scrollbar,
      // ignore wheel paging to prevent competing state updates.
      if (isScrollbarDragActive()) {
        return;
      }

      int trackCount = sampleRegistry.getDisplayedTrackCount();
      double sampleH = sampleRegistry.getSampleHeight();
      if (trackCount <= 0 || sampleH <= 0) {
        return;
      }

      int stepDir = event.getDeltaY() < 0 ? 1 : (event.getDeltaY() > 0 ? -1 : 0);
      if (stepDir == 0) {
        return;
      }

      int baseFirst;
      int baseLast;
      int window;
      if (scrollAnimation != null && animationTargetFirst >= 0 && animationTargetLast >= 0) {
        baseFirst = animationTargetFirst;
        baseLast = animationTargetLast;
        window = Math.max(1, Math.min(trackCount, animationWindowSize));
      } else {
        baseFirst = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
        baseLast = Math.max(baseFirst, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));
        if (baseFirst != sampleRegistry.getFirstVisibleSample()
            || baseLast != sampleRegistry.getLastVisibleSample()) {
          sampleRegistry.setFirstVisibleSample(baseFirst);
          sampleRegistry.setLastVisibleSample(baseLast);
        }
        window = Math.max(1, Math.min(trackCount, baseLast - baseFirst + 1));
      }

      int maxFirst = Math.max(0, trackCount - window);

      // When already at an edge, settle to a canonical boundary state and do not animate further.
      if ((stepDir < 0 && baseFirst <= 0) || (stepDir > 0 && baseFirst >= maxFirst)) {
        settleScrollBoundary(baseFirst, window, sampleH, trackCount);
        return;
      }

      int nextFirst = Math.max(0, Math.min(maxFirst, baseFirst + stepDir * window));
      int nextLast = Math.min(trackCount - 1, nextFirst + window - 1);

      if (nextFirst != baseFirst || nextLast != baseLast) {
        animatePagedScroll(nextFirst, nextLast, sampleH, window);
      }
    });

    reactiveCanvas.setOnMouseClicked(event -> {
      if (isScrollbarDragActive()) {
        event.consume();
        return;
      }

      if (suppressClickFromScrollbar) {
        suppressClickFromScrollbar = false;
        return;
      }

      double x   = event.getX();
      double y   = event.getY();

      if (isPointInTrackScrollbar(x, y)) {
        return;
      }

      int idx = findRowAt(y);

      if (idx >= 0 && idx < sampleRegistry.getSampleTracks().size()) {
        String icon = findIconAt(x, y, idx);
        if ("close".equals(icon)) {
          SampleDataManager.removeSample(idx);
          return;
        }
        if ("settings".equals(icon)) {
          showSettingsPopup(idx, event.getScreenX(), event.getScreenY());
          return;
        }
        if ("add".equals(icon)) {
          showAddFileMenu(idx, event.getScreenX(), event.getScreenY());
          return;
        }
        if ("reload".equals(icon)) {
          // Reload all suspended files in this track
          SampleTrack strack = sampleRegistry.getSampleTracks().get(idx);
          for (Sample sf : strack.getSamples()) {
            if (sf.isSuspended()) {
              sf.resume();
              ThreadRunner.RunnerTask readTask =
                  ThreadRunner.get().track("Loading reads: " + sf.getName(), sf::cancelAndSuspend);
              sf.setOnFirstLoadComplete(readTask::complete);
            }
          }
          draw();
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
          return;
        }
      }

      // Double-click: zoom to single sample or zoom back out
      if (event.getClickCount() == 2) {
        double viewportHeight = getSampleViewportHeight();
        int displayedCount = sampleRegistry.getDisplayedTrackCount();
        if (displayedCount <= 0) {
          return;
        }
        if (sampleRegistry.getFirstVisibleSample() == sampleRegistry.getLastVisibleSample()) {
          sampleRegistry.setFirstVisibleSample(0);
          sampleRegistry.setLastVisibleSample(displayedCount - 1);
        } else {
          int hoveredTrackIndex = sampleRegistry.hoverSampleProperty().get();
          int hoveredSlot = sampleRegistry.getDisplayedSlotForTrackIndex(hoveredTrackIndex);
          if (hoveredSlot >= 0) {
            sampleRegistry.setFirstVisibleSample(hoveredSlot);
            sampleRegistry.setLastVisibleSample(hoveredSlot);
          }
        }
        int visible = sampleRegistry.getVisibleSampleCount();
        sampleRegistry.setSampleHeight(viewportHeight / Math.max(1, visible));
        double targetScroll = sampleRegistry.getFirstVisibleSample() * sampleRegistry.getSampleHeight();
        sampleRegistry.setScrollBarPosition(
            sampleRegistry.clampScrollBarPosition(targetScroll, viewportHeight));
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    });
  }

  private void animatePagedScroll(int nextFirst, int nextLast, double lockedSampleHeight, int windowSize) {
    if (scrollAnimation != null) {
      scrollAnimation.stop();
      scrollAnimation = null;
    }

    animationTargetFirst = nextFirst;
    animationTargetLast = nextLast;
    animationWindowSize = Math.max(1, windowSize);

    sampleRegistry.setFirstVisibleSample(nextFirst);
    sampleRegistry.setLastVisibleSample(nextLast);
    sampleRegistry.lockSampleHeight();
    sampleRegistry.setSampleHeight(lockedSampleHeight);

    double viewportHeight = getSampleViewportHeight();
    double startScroll = sampleRegistry.getScrollBarPosition();
    double targetScroll = sampleRegistry.clampScrollBarPosition(nextFirst * lockedSampleHeight, viewportHeight);
    if (Math.abs(targetScroll - startScroll) < 0.5) {
      sampleRegistry.setScrollBarPosition(targetScroll);
      sampleRegistry.unlockSampleHeight();
      clearAnimationTargets();
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      return;
    }

    scrollAnimStartNanos = System.nanoTime();
    scrollAnimation = new AnimationTimer() {
      @Override
      public void handle(long now) {
        double t = (double) (now - scrollAnimStartNanos) / SCROLL_ANIM_DURATION_NS;
        if (t >= 1.0) {
          sampleRegistry.setScrollBarPosition(targetScroll);
          sampleRegistry.unlockSampleHeight();
          clearAnimationTargets();
          stop();
          scrollAnimation = null;
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
          return;
        }

        double eased = EASE_OUT_CUBIC.applyAsDouble(Math.max(0.0, t));
        double current = startScroll + (targetScroll - startScroll) * eased;
        sampleRegistry.setScrollBarPosition(current);
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    };
    scrollAnimation.start();
  }

  private void clearAnimationTargets() {
    animationTargetFirst = -1;
    animationTargetLast = -1;
    animationWindowSize = -1;
  }

  private void settleScrollBoundary(int requestedFirst, int window, double sampleH, int trackCount) {
    if (trackCount <= 0 || sampleH <= 0) {
      return;
    }

    int normalizedWindow = Math.max(1, Math.min(trackCount, window));
    int maxFirst = Math.max(0, trackCount - normalizedWindow);
    int clampedFirst = Math.max(0, Math.min(maxFirst, requestedFirst));
    int clampedLast = Math.min(trackCount - 1, clampedFirst + normalizedWindow - 1);

    int prevFirst = sampleRegistry.getFirstVisibleSample();
    int prevLast = sampleRegistry.getLastVisibleSample();
    double prevScroll = sampleRegistry.getScrollBarPosition();
    boolean hadTransientState = scrollAnimation != null
        || animationTargetFirst >= 0
        || animationTargetLast >= 0
        || sampleRegistry.isSampleHeightLocked();

    if (scrollAnimation != null) {
      scrollAnimation.stop();
      scrollAnimation = null;
    }
    clearAnimationTargets();
    sampleRegistry.unlockSampleHeight();

    sampleRegistry.setFirstVisibleSample(clampedFirst);
    sampleRegistry.setLastVisibleSample(clampedLast);

    double viewportHeight = sampleH * normalizedWindow;
    double snappedScroll = sampleRegistry.clampScrollBarPosition(clampedFirst * sampleH, viewportHeight);
    sampleRegistry.setScrollBarPosition(snappedScroll);

    if (hadTransientState
        || prevFirst != clampedFirst
        || prevLast != clampedLast
        || Math.abs(prevScroll - snappedScroll) > 0.5) {
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }
  }

  private void applyIntervalAtFirst(int requestedFirst, boolean animated) {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    double sampleH = sampleRegistry.getSampleHeight();
    if (trackCount <= 0 || sampleH <= 0) {
      return;
    }

    int baseFirst;
    int baseLast;
    int window;
    int[] base = getBaseRange();
    baseFirst = base[0];
    baseLast = base[1];
    window = Math.max(1, Math.min(trackCount, baseLast - baseFirst + 1));

    int maxFirst = Math.max(0, trackCount - window);
    int nextFirst = Math.max(0, Math.min(maxFirst, requestedFirst));
    int nextLast = Math.min(trackCount - 1, nextFirst + window - 1);

    if (nextFirst == baseFirst && nextLast == baseLast) {
      return;
    }

    if (animated) {
      animatePagedScroll(nextFirst, nextLast, sampleH, window);
      return;
    }

    if (scrollAnimation != null) {
      scrollAnimation.stop();
      scrollAnimation = null;
    }
    clearAnimationTargets();

    sampleRegistry.unlockSampleHeight();
    sampleRegistry.setFirstVisibleSample(nextFirst);
    sampleRegistry.setLastVisibleSample(nextLast);
    double viewportHeight = getSampleViewportHeight();
    double targetScroll = sampleRegistry.clampScrollBarPosition(nextFirst * sampleH, viewportHeight);
    sampleRegistry.setScrollBarPosition(targetScroll);
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private int[] getBaseRange() {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      return new int[] {0, 0};
    }
    if (scrollAnimation != null && animationTargetFirst >= 0 && animationTargetLast >= 0) {
      int first = Math.max(0, Math.min(trackCount - 1, animationTargetFirst));
      int last = Math.max(first, Math.min(trackCount - 1, animationTargetLast));
      return new int[] {first, last};
    }
    int first = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
    int last = Math.max(first, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));
    return new int[] {first, last};
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

  private int firstFromScrollbarThumbTop(double thumbTopY) {
    return (int) Math.round(firstPositionFromScrollbarThumbTop(thumbTopY));
  }

  private double firstPositionFromScrollbarThumbTop(double thumbTopY) {
    if (!trackScrollbarVisible || trackScrollbarMaxFirst <= 0) {
      return Math.max(0, Math.min(sampleRegistry.getDisplayedTrackCount() - 1,
          sampleRegistry.getFirstVisibleSample()));
    }

    double travel = Math.max(1, trackScrollbarHeight - trackScrollbarThumbHeight);
    double clampedTop = Math.max(trackScrollbarTop,
        Math.min(trackScrollbarTop + travel, thumbTopY));
    double t = (clampedTop - trackScrollbarTop) / travel;
    return t * trackScrollbarMaxFirst;
  }

  private void previewIntervalAtFirst(double requestedFirstPosition) {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    double sampleH = dragScrollbarLockedSampleHeight > 0
        ? dragScrollbarLockedSampleHeight
        : sampleRegistry.getSampleHeight();
    if (trackCount <= 0 || sampleH <= 0) {
      return;
    }

    int window = Math.max(1, Math.min(trackCount, dragScrollbarWindow));
    int maxFirst = Math.max(0, trackCount - window);
    double firstPos = Math.max(0.0, Math.min(maxFirst, requestedFirstPosition));

    int previewFirst = (int) Math.floor(firstPos);
    int previewLast = previewFirst + window - 1;
    previewLast = Math.max(previewFirst, Math.min(trackCount - 1, previewLast));

    sampleRegistry.setSampleHeight(sampleH);
    sampleRegistry.setFirstVisibleSample(previewFirst);
    sampleRegistry.setLastVisibleSample(previewLast);

    double viewportHeight = sampleH * window;
    double targetScroll = sampleRegistry.clampScrollBarPosition(firstPos * sampleH, viewportHeight);
    sampleRegistry.setScrollBarPosition(targetScroll);
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private void finishContinuousScrollbarDrag() {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    double sampleH = dragScrollbarLockedSampleHeight > 0
        ? dragScrollbarLockedSampleHeight
        : sampleRegistry.getSampleHeight();
    if (trackCount <= 0 || sampleH <= 0) {
      sampleRegistry.unlockSampleHeight();
      return;
    }

    int window = Math.max(1, Math.min(trackCount, dragScrollbarWindow));
    int maxFirst = Math.max(0, trackCount - window);
    double rawFirstPos = sampleRegistry.getScrollBarPosition() / sampleH;
    int snappedFirst = (int) Math.round(rawFirstPos);
    snappedFirst = Math.max(0, Math.min(maxFirst, snappedFirst));

    applyIntervalAtFirst(snappedFirst, false);
    dragScrollbarWindow = 1;
    dragScrollbarLockedSampleHeight = 0;
  }

  private void updateTrackScrollbarGeometry(double canvasWidth, double canvasHeight) {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      trackScrollbarVisible = false;
      return;
    }

    int first = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
    int last = Math.max(first, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));
    int window = Math.max(1, Math.min(trackCount, last - first + 1));
    trackScrollbarMaxFirst = Math.max(0, trackCount - window);

    trackScrollbarVisible = window < trackCount;
    if (!trackScrollbarVisible) {
      return;
    }

    trackScrollbarX = canvasWidth - TRACK_SCROLLBAR_WIDTH - TRACK_SCROLLBAR_MARGIN;
    trackScrollbarTop = 0;
    trackScrollbarHeight = Math.max(0, canvasHeight);

    trackScrollbarThumbHeight = Math.max(TRACK_SCROLLBAR_MIN_THUMB_HEIGHT,
        trackScrollbarHeight * (window / (double) trackCount));
    trackScrollbarThumbHeight = Math.min(trackScrollbarHeight, trackScrollbarThumbHeight);

    double travel = Math.max(1, trackScrollbarHeight - trackScrollbarThumbHeight);
    double t = trackScrollbarMaxFirst > 0 ? first / (double) trackScrollbarMaxFirst : 0;
    trackScrollbarThumbY = trackScrollbarTop + t * travel;
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

    double cx = trackScrollbarX + TRACK_SCROLLBAR_WIDTH * 0.5;
    double midY = trackScrollbarThumbY + trackScrollbarThumbHeight * 0.5;
    gc.setStroke(Color.rgb(60, 60, 60, 0.82));
    gc.strokeLine(cx - 2, midY - 3, cx + 2, midY - 3);
    gc.strokeLine(cx - 2, midY, cx + 2, midY);
    gc.strokeLine(cx - 2, midY + 3, cx + 2, midY + 3);
  }

  private double getRightUiInset() {
    return trackScrollbarVisible ? (TRACK_SCROLLBAR_WIDTH + TRACK_SCROLLBAR_MARGIN + 2) : 0;
  }

  private double getSampleViewportHeight() {
    double sampleH = sampleRegistry.getSampleHeight();
    int visible = Math.max(1, sampleRegistry.getVisibleSampleCount());
    double derived = sampleH * visible;
    if (derived > 0) {
      return derived;
    }
    return Math.max(0, canvas.getHeight());
  }

  // ── Rendering ─────────────────────────────────────────────────────────────

  @Override
  public void draw() {
    double w = canvas.getWidth();
    double h = canvas.getHeight();

    gc.setFill(DrawColors.SIDEBAR);
    gc.fillRect(0, 0, w, h);
    gc.setStroke(DrawColors.BORDER);
    clearIconRegions();

    updateTrackScrollbarGeometry(w, h);
    double rightInset = getRightUiInset();

     List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
     if (displayedTrackIndices.isEmpty()) return;

     for (int slot = sampleRegistry.getFirstVisibleSample();
        slot <= sampleRegistry.getLastVisibleSample() && slot < displayedTrackIndices.size();
        slot++) {
      int i = displayedTrackIndices.get(slot);

      // Y is relative to this panel's top — no master track offset
      double sampleY = slot * sampleRegistry.getSampleHeight() - sampleRegistry.getScrollBarPosition();
      boolean hasTrack  = (i < sampleRegistry.getSampleTracks().size());
      boolean isVisible = !hasTrack || sampleRegistry.getSampleTracks().get(i).isVisible();

      // Skip rows that scrolled above the panel top
      if (sampleY + sampleRegistry.getSampleHeight() < 0) continue;

      // Divider line
      if (sampleY >= 0) {
        gc.setStroke(DrawColors.BORDER);
        gc.strokeLine(0, sampleY, w, sampleY);
      }

      // Mirror divider line onto each alignment canvas
      for (DrawStack stack : stackManager.getStacks()) {
        GraphicsContext alignGc = stack.alignmentCanvas.getGraphicsContext2D();
        // The Y in the alignment canvas includes the master track offset
        double alignY = sampleRegistry.getMasterTrackHeight() + sampleY;
        alignGc.setStroke(
            sampleRegistry.hoverSampleProperty().get() == i ? Color.WHITE : DrawColors.BORDER);
        alignGc.strokeLine(0, alignY, stack.alignmentCanvas.getWidth(), alignY);
      }

      // Track name
      gc.setFont(NAME_FONT);
      gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
      double textY = sampleY + gc.getFont().getSize() + 2;
      if (textY > 0) {
        String displayName = hasTrack
            ? sampleRegistry.getSampleTracks().get(i).getDisplayName()
            : "";
        gc.fillText(displayName, 10, textY);
      }

      // Per-file sub-rows + action buttons both need strack — keep them in the same block.
      if (hasTrack) {
        SampleTrack strack = sampleRegistry.getSampleTracks().get(i);
        Font fileFont = Font.font("Segoe UI", 9);
        gc.setFont(fileFont);
        double fileY = textY + 4;
        for (int fileIdx = 0; fileIdx < strack.getSamples().size(); fileIdx++) {
          Sample sf = strack.getSamples().get(fileIdx);
          fileY += 12;
          if (fileY > sampleY + sampleRegistry.getSampleHeight() - 4) break;
          if (fileY <= 0) continue;

          String tag = sf.getDataType().name();
          Color tagColor = switch (sf.getDataType()) {
            case BAM -> Color.web("#6699cc");
            case BED -> Color.web("#cc9966");
            case VCF -> Color.web("#99cc66");
          };
          double alpha = sf.visible ? 1.0 : 0.35;
          gc.setFill(new Color(tagColor.getRed(), tagColor.getGreen(), tagColor.getBlue(), alpha));
          gc.fillText("[" + tag + "]", 14, fileY);

          Color nameColor = sf.visible ? Color.web("#aaaaaa") : Color.web("#555555");
          if (sf.overlay)
            nameColor = new Color(nameColor.getRed(), nameColor.getGreen(), nameColor.getBlue(), 0.7);
          gc.setFill(nameColor);
          gc.fillText(sf.getName(), 44, fileY);

          if (sf.isSuspended()) {
            // Greyed-out indicator — full reload button drawn below
            gc.setFill(new Color(tagColor.getRed(), tagColor.getGreen(), tagColor.getBlue(), 0.4));
            gc.fillText("[" + tag + "]", 14, fileY);
          } else if (sf.overlay) {
            gc.setFill(new Color(0.6, 0.8, 0.6, alpha * 0.8));
            gc.fillText("\u25CB", 6, fileY);
          }
        }

        // Compute before hover/reload checks so both can use it
        boolean hasSuspended = strack.getSamples().stream().anyMatch(Sample::isSuspended);

        // Always-visible reload button at bottom-right when any file is suspended
        if (hasSuspended) {
          double reloadX = w - rightInset - ICON_SIZE - ICON_MARGIN;
          double reloadBtnY = sampleY + sampleRegistry.getSampleHeight() - ICON_SIZE - ICON_MARGIN;
          gc.setFont(ICON_FONT);
          gc.setFill(Color.web("#ff9944"));
          gc.fillText("\u21ba", reloadX, reloadBtnY + ICON_SIZE - 3);
          addIconRegion(i, "reload", reloadX - 1, reloadBtnY - 1, ICON_SIZE + 2, ICON_SIZE + 2);
        }

        // Action buttons — drawn on the static canvas for the hovered row so the
        // reactive overlay can add a clean glow on top (same pattern as SidebarBase).
        if (i == hoverIndex) {
          drawSampleButtons(i, sampleY, w - rightInset, isVisible, hasSuspended);
        }
      }
    }

    drawTrackScrollbar();
  }

  // ── Reactive overlay ──────────────────────────────────────────────────────

  @Override
  protected void drawReactive() {
    double w = reactiveCanvas.getWidth();
    double h = reactiveCanvas.getHeight();
    reactiveGc.clearRect(0, 0, w, h);

    if (hoverIndex < 0 || hoverIndex >= sampleRegistry.getSampleTracks().size()) return;

    int hoverSlot = sampleRegistry.getDisplayedSlotForTrackIndex(hoverIndex);
    if (hoverSlot < 0) return;

    double sampleY = hoverSlot * sampleRegistry.getSampleHeight() - sampleRegistry.getScrollBarPosition();
    if (sampleY + sampleRegistry.getSampleHeight() < 0 || sampleY > h) return;

    // Subtle row highlight (exactly like FeatureTracksPanel)
    reactiveGc.setFill(Color.rgb(255, 255, 255, 0.05));
    reactiveGc.fillRect(0, Math.max(sampleY, 0), w, sampleRegistry.getSampleHeight());

    // Icon-specific glow
    if (hoveredIcon != null) {
      drawIconGlow(hoveredIcon, hoverIndex);
    }
  }

  /** Draw the per-sample close/settings/add-file (and optional reload) buttons on the STATIC canvas. */
  private void drawSampleButtons(int rowIdx, double sampleY, double sideW,
                                  boolean isVisible, boolean hasSuspended) {
    gc.setFont(ICON_FONT);

    // Close (✕) — top-right
    double closeX = sideW - ICON_SIZE - ICON_MARGIN;
    double closeY = sampleY + ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(closeX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
    gc.setFill(Color.web("#cc6666"));
    gc.fillText("✕", closeX + 1, closeY + ICON_SIZE - 3);
    addIconRegion(rowIdx, "close", closeX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2);

    // Settings (⚙) — next to close
    double settingsX = closeX - ICON_SIZE - ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(settingsX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
    gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
    gc.fillText("⚙", settingsX + 1, closeY + ICON_SIZE - 3);
    addIconRegion(rowIdx, "settings", settingsX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2);

    // Add-file (+) — bottom-left
    double overlayX = ICON_MARGIN;
    double overlayY = sampleY + sampleRegistry.getSampleHeight() - ICON_SIZE - ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(overlayX - 1, overlayY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
    gc.setFill(Color.web("#88bb88"));
    gc.fillText("+", overlayX + 2, overlayY + ICON_SIZE - 3);
    addIconRegion(rowIdx, "add", overlayX - 1, overlayY - 1, ICON_SIZE + 2, ICON_SIZE + 2);

    // Reload (↺) — bottom-right, only when the track has suspended files.
    // The icon text is drawn always in draw(); here we just add the dark background on hover.
    if (hasSuspended) {
      double reloadX = sideW - ICON_SIZE - ICON_MARGIN;
      double reloadY = overlayY;
      gc.setFill(Color.web("#3c3c3c"));
      gc.fillRoundRect(reloadX - 1, reloadY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
      gc.setFont(ICON_FONT);
      gc.setFill(Color.web("#ff9944"));
      gc.fillText("\u21ba", reloadX, reloadY + ICON_SIZE - 3);
      // Icon region already registered in main draw loop; no duplicate needed.
    }
  }

  private void drawIconGlow(String iconType, int rowIdx) {
    IconRegion region = findIconRegion(iconType, rowIdx);
    if (region == null) return;

    reactiveGc.setFill(Color.rgb(255, 255, 255, 0.24));
    reactiveGc.fillRoundRect(region.x() - 2, region.y() - 2,
        region.width() + 4, region.height() + 4, 5, 5);
    reactiveGc.setStroke(Color.rgb(255, 255, 255, 0.35));
    reactiveGc.strokeRoundRect(region.x() - 2, region.y() - 2,
        region.width() + 4, region.height() + 4, 5, 5);
  }

  @Override
  protected String findIconAt(double x, double y, int rowIdx) {
    return findIconFromRegions(x, y, rowIdx);
  }

  @Override
  protected int findRowAt(double y) {
    if (sampleRegistry.getSampleHeight() <= 0) return -1;
    List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    if (displayedTrackIndices.isEmpty()) return -1;
    int slot = (int) ((y + sampleRegistry.getScrollBarPosition()) / sampleRegistry.getSampleHeight());
    if (slot < 0 || slot >= displayedTrackIndices.size()) return -1;
    return displayedTrackIndices.get(slot);
  }

  // ── Per-sample menus ──────────────────────────────────────────────────────

  /** Show a file-level settings popup for a sample track. */
  private void showSettingsPopup(int sampleIdx, double screenX, double screenY) {
    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIdx);
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    for (int i = 0; i < track.getSamples().size(); i++) {
      settingsMenu.getItems().add(buildTrackRow(track.getSamples().get(i), track, i, sampleIdx));
    }

    // Methylation section
    settingsMenu.getItems().add(new SeparatorMenuItem());
    VBox methylBox = new VBox(4);
    methylBox.setPadding(new Insets(4, 8, 4, 8));
    if (track.hasMethylationData()) {
      Label lbl = new Label("🧬 Methylation tags detected (MM/ML/XM)");
      lbl.setStyle("-fx-text-fill: #88ccff; -fx-font-size: 11; -fx-font-weight: bold;");
      methylBox.getChildren().add(lbl);
    } else {
      Label lbl = new Label("🧬 Methylation / Bisulfite sequencing");
      lbl.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11; -fx-font-weight: bold;");
      methylBox.getChildren().add(lbl);
    }
    CheckBox suppressMethylCb = new CheckBox("Hide bisulfite mismatches (C→T / G→A)");
    suppressMethylCb.setSelected(track.hasMethylationData());
    suppressMethylCb.getStyleClass().add("dark-checkbox");
    suppressMethylCb.setStyle("-fx-font-size: 11;");
    suppressMethylCb.selectedProperty().addListener((obs, o, n) -> {
      for (Sample s : track.getSamples()) {
        AlignmentFile bam = s.getBamFile();
        if (bam != null) bam.setSuppressMethylMismatches(n);
      }
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    });
    Label methylInfo = new Label("Enable for emSeq/WGBS data to hide C→T conversions");
    methylInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");
    methylBox.getChildren().addAll(suppressMethylCb, methylInfo);
    settingsMenu.getItems().add(new CustomMenuItem(methylBox, false));

    // Haplotype section
    if (track.hasHaplotypeData()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      VBox hpBox = new VBox(4);
      hpBox.setPadding(new Insets(4, 8, 4, 8));
      Label hpLabel = new Label("\uD83E\uDDE9 Phased haplotype data (HP tags)");
      hpLabel.setStyle("-fx-text-fill: #88eebb; -fx-font-size: 11; -fx-font-weight: bold;");
      Label hpInfo = new Label("Reads shown in allele-split butterfly view:");
      hpInfo.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
      Label hpInfo2 = new Label("HP1 = top (up), HP2 = bottom (down)");
      hpInfo2.setStyle("-fx-text-fill: #999999; -fx-font-size: 10;");
      hpBox.getChildren().addAll(hpLabel, hpInfo, hpInfo2);
      settingsMenu.getItems().add(new CustomMenuItem(hpBox, false));
    }

    // Read color + stacking mode section
    AlignmentFile primaryBam = track.getFirstBam();
    if (primaryBam != null) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      VBox renderBox = new VBox(6);
      renderBox.setPadding(new Insets(4, 8, 4, 8));

      Label renderLabel = new Label("🎨 Read rendering");
      renderLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11; -fx-font-weight: bold;");

      HBox colorRow = new HBox(6);
      Label colorLabel = new Label("Read color:");
      colorLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
      ComboBox<ReadColorMode> colorCombo = new ComboBox<>();
      // Use available modes from primary BAM (filters out modes for missing tags)
      colorCombo.getItems().setAll(primaryBam.getAvailableColorModes());
      colorCombo.setValue(primaryBam.getReadColorMode());
      colorCombo.setPrefWidth(190);
      styleDarkComboBox(colorCombo, settingsMenu);
      colorCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) return;
        for (Sample s : track.getSamples()) {
          AlignmentFile bam = s.getBamFile();
          if (bam != null) bam.setReadColorMode(newMode);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
      colorRow.getChildren().addAll(colorLabel, colorCombo);

      HBox stackRow = new HBox(6);
      Label stackLabel = new Label("Stacking:");
      stackLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
      ComboBox<AlignmentFile.ReadStackingMode> stackCombo = new ComboBox<>();
      stackCombo.getItems().setAll(AlignmentFile.ReadStackingMode.values());
      stackCombo.setValue(primaryBam.getReadStackingMode());
      stackCombo.setPrefWidth(190);
      styleDarkComboBox(stackCombo, settingsMenu);
      stackCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) return;
        for (Sample s : track.getSamples()) {
          AlignmentFile bam = s.getBamFile();
          if (bam != null) bam.setReadStackingMode(newMode);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
      stackRow.getChildren().addAll(stackLabel, stackCombo);

      Label stackInfo = new Label("Only one stacking mode can be active at a time.");
      stackInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");
      renderBox.getChildren().addAll(renderLabel, colorRow, stackRow, stackInfo);

      if (primaryBam.getDetectedReadGroups().size() > 1) {
        StringBuilder sb = new StringBuilder("Read groups: ");
        for (int i = 0; i < primaryBam.getDetectedReadGroups().size(); i++) {
          if (i > 0) sb.append(", ");
          sb.append(primaryBam.getDetectedReadGroups().get(i));
        }
        Label rgInfo = new Label(sb.toString());
        rgInfo.setStyle("-fx-text-fill: #999999; -fx-font-size: 9;");
        rgInfo.setWrapText(true);
        renderBox.getChildren().add(rgInfo);
      }

      settingsMenu.getItems().add(new CustomMenuItem(renderBox, false));
    }

    settingsMenu.show(canvas, screenX, screenY);
  }

  private static <T> void styleDarkComboBox(ComboBox<T> comboBox, ContextMenu parentMenu) {
    comboBox.setStyle(
        "-fx-background-color: #333333;"
            + "-fx-control-inner-background: #333333;"
            + "-fx-text-fill: #dddddd;"
            + "-fx-prompt-text-fill: #bbbbbb;"
            + "-fx-mark-color: #dddddd;");

    comboBox.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      parentMenu.setAutoHide(false);
      if (!comboBox.isShowing()) {
        comboBox.show();
      }
      e.consume();
    });
    comboBox.setOnShowing(e -> parentMenu.setAutoHide(false));
    comboBox.setOnHidden(e -> javafx.application.Platform.runLater(() -> parentMenu.setAutoHide(true)));
    comboBox.addEventFilter(ActionEvent.ACTION, ActionEvent::consume);

    comboBox.setButtonCell(createDarkComboCell());
    comboBox.setCellFactory(listView -> createDarkComboCell());
  }

  private static <T> ListCell<T> createDarkComboCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(T item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
          setStyle("-fx-text-fill: #dddddd;");
          return;
        }
        setText(item.toString());
        setStyle("-fx-text-fill: #dddddd;");
      }
    };
  }

  private void showAddFileMenu(int sampleIdx, double screenX, double screenY) {
    ContextMenu addMenu = new ContextMenu();
    addMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");
    MenuItem bamItem = new MenuItem("Add BAM/CRAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamToTrack(sampleIdx));
    MenuItem bedItem = new MenuItem("Add BED");
    bedItem.setOnAction(e -> SampleDataManager.addBedToTrack(sampleIdx));
    addMenu.getItems().addAll(bamItem, bedItem);
    addMenu.show(canvas, screenX, screenY);
  }

  private void showRenameDialog(SampleTrack track) {
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(track.getDisplayName());
    dialog.setTitle("Rename Track");
    dialog.setHeaderText("Enter new name for this individual:");
    dialog.setContentText("Name:");
    dialog.getDialogPane().setStyle("-fx-background-color: #2b2b2b;");
    dialog.getDialogPane().lookup(".content.label")
        .setStyle("-fx-text-fill: #cccccc;");
    dialog.getDialogPane().lookup(".header-panel")
        .setStyle("-fx-background-color: #333333;");
    dialog.showAndWait().ifPresent(newName -> {
      if (!newName.trim().isEmpty()) {
        track.setCustomName(newName.trim());
        draw();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    });
  }

  private CustomMenuItem buildTrackRow(Sample file, SampleTrack track, int fileIdx, int sampleIdx) {
    HBox row = new HBox(6);
    row.setStyle("-fx-padding: 2 4 2 4;");

    CheckBox visCb = new CheckBox();
    visCb.setSelected(file.visible);
    visCb.getStyleClass().add("dark-checkbox");
    visCb.selectedProperty().addListener((obs, o, n) -> {
      file.visible = n;
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    });

    CheckBox ovrCb = new CheckBox("Transparent");
    ovrCb.setSelected(file.overlay);
    ovrCb.getStyleClass().add("dark-checkbox");
    ovrCb.setStyle("-fx-font-size: 10;");
    ovrCb.selectedProperty().addListener((obs, o, n) -> {
      file.overlay = n;
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    });

    Label typeLabel = new Label("[" + file.getDataType().name() + "]");
    typeLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");

    Label nameLabel = new Label(file.getName());
    nameLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-cursor: hand;");
    nameLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(nameLabel, Priority.ALWAYS);
    nameLabel.setOnMouseClicked(e -> {
      if (e.getClickCount() == 1) showRenameDialog(track);
    });

    Label removeBtn = new Label("✕");
    removeBtn.setStyle(
        "-fx-text-fill: #cc6666; -fx-cursor: hand; -fx-font-size: 11; -fx-padding: 0 2 0 4;");
    removeBtn.setOnMouseClicked(e -> {
      if (track.getSampleCount() <= 1) {
        SampleDataManager.removeSample(sampleIdx);
      } else {
        track.removeSample(fileIdx);
        draw();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    });

    row.getChildren().addAll(visCb, typeLabel, nameLabel, ovrCb, removeBtn);
    return new CustomMenuItem(row, false);
  }
}
