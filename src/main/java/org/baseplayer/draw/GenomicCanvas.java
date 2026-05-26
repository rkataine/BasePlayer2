package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import org.baseplayer.samples.alignment.FetchManager;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.DrawColors;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class GenomicCanvas extends Canvas {
  //private Pane parent;
  public DrawStack drawStack;
  public static int minZoom = 40;
  public static BooleanProperty update = new SimpleBooleanProperty(false);
  
  // Services
  protected final SampleRegistry sampleRegistry;
  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
 
  private final GraphicsContext gc;
  public GraphicsContext reactiveGc;

  
  protected Function<Double, Double> chromPosToScreenPos = chromPos -> (chromPos - drawStack.start) * drawStack.pixelSize;
  // Note: heightToScreen currently unused, kept for potential future use
  protected Function<Double, Double> heightToScreen = height -> height; // Placeholder
  protected Function<Double, Integer> screenPosToChromPos = screenPos -> (int)(drawStack.start + screenPos * drawStack.scale);
  protected double mousePressedX;
  private final Canvas reactiveCanvas;
  protected double mouseDraggedX;
	protected double mouseDragDeltaX = 0;
  protected double mousePressedY;
  private boolean lineZoomer = false;
  private boolean zoomDrag;
  private boolean secondaryDragAnchorInitialized = false;
  private boolean primaryDragAnchorInitialized = false;
  private static final double MIN_ZOOM_DRAG_PIXELS = 5.0;
  /** True while the mouse is being dragged in this canvas; cleared shortly after release. */
  protected volatile boolean mouseDragged = false;
  public static double zoomFactor = 10;
  public int zoomY = - 1;
  /** Timer to clear navigating after scroll wheel stops */
  private PauseTransition scrollIdleTimer;
  
  private double scrollVelocity = 0;
  private long lastScrollTime = 0;
  private long lastFrameTime = 0;
  private AnimationTimer momentumTimer;
  private boolean momentumTimerRunning = false;
  private static final double MAX_VELOCITY = 50000.0; // Cap maximum scroll velocity
  private static final long SCROLL_IDLE_THRESHOLD = 50_000_000; // 50ms in nanoseconds

  /** Reused after mouse release to clear {@link #mouseDragged}. */
  private PauseTransition dragFlagClearTimer;

  // Snapshot-transform preview used only during animated zoom.
  private Image interactionSnapshot;
  private boolean zoomPreviewActive = false;
  private AnimationTimer zoomPreviewTimer;
  private double pendingZoomStart = Double.NaN;
  private double pendingZoomEnd = Double.NaN;
  private static final double CLOSE_ZOOM_NO_ANIMATION_FACTOR = 3.0;

  public GenomicCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    this.reactiveCanvas = reactiveCanvas;
    this.drawStack = drawStack;
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    gc = getGraphicsContext2D();  
    gc.setFont(Font.font("Segoe UI Regular", 12));
    heightProperty().bind(parent.heightProperty());
    widthProperty().bind(parent.widthProperty());
    reactiveCanvas.heightProperty().bind(parent.heightProperty());
    reactiveCanvas.widthProperty().bind(parent.widthProperty());
    parent.widthProperty().addListener((obs, oldVal, newVal) -> update.set(!update.get()));
    reactiveCanvas.setOnMouseEntered(event -> { stackManager.setHoverStack(drawStack); update.set(!update.get()); });

    setupParentHeightListener(parent);
   
    initializeMomentumTimer();
    setupReactiveCanvas();
  }

  /**
   * Hook for parent-height change → redraw.  ChromosomeCanvas overrides this
   * to avoid a feedback loop (it sets its own height during draw()).
   */
  protected final void setupParentHeightListener(StackPane parent) {
    parent.heightProperty().addListener((obs, oldVal, newVal) -> update.set(!update.get()));
  }

  protected void draw() {
    
    if (stackManager.getStacks().size() > 1 && drawStack.equals(stackManager.getHoverStack())) {
      gc.setStroke(Color.WHITESMOKE);
      gc.strokeRect(1, -1, getWidth()-2, getHeight()+2);
    }
    
    drawMiddleLines();
  }
  
  protected void drawMiddleLines() {
    if (drawStack.viewLength < 200) {
      gc.setStroke(Color.GRAY);
      gc.setLineDashes(2, 4);
      double lineStart = getWidth() / 2 - drawStack.pixelSize / 2;
      double lineEnd = lineStart + drawStack.pixelSize;
      gc.strokeLine(lineStart, 0, lineStart, getHeight());
      gc.strokeLine(lineEnd, 0, lineEnd, getHeight());
      gc.setLineDashes(0);
    }
  }
  public Canvas getReactiveCanvas() { return reactiveCanvas; }
  
  private void initializeMomentumTimer() {
    momentumTimer = new AnimationTimer() {
      @Override
      public void handle(long now) {
        // Only apply momentum if user has stopped scrolling
        if (now - lastScrollTime < SCROLL_IDLE_THRESHOLD) {
          lastFrameTime = now;
          return;
        }
        
        // Dynamic minimum velocity based on zoom level (logarithmic scaling)
        // At 40bp: minVelocity ~40, at 100k: ~500, at 1M: ~2000
        double logScale = Math.log10(Math.max(10, drawStack.viewLength));
        double minVelocity = Math.max(10.0, drawStack.viewLength / (500.0 / logScale));
        
        // Dynamic deceleration with more friction: faster decay at all zoom levels
        // At 40bp: ~0.88 (faster decay), at 100k: ~0.91, at 1M+: ~0.93
        double deceleration = 0.88 + Math.min(0.05, logScale * 0.012);
        
        if (Math.abs(scrollVelocity) > minVelocity) {
          // Calculate actual time delta for smooth animation
          double deltaTime = lastFrameTime > 0 ? (now - lastFrameTime) / 1_000_000_000.0 : 0.016;
          deltaTime = Math.min(deltaTime, 0.05); // Cap delta time to avoid jumps
          
          double movement = -scrollVelocity * deltaTime;
          setStart(drawStack.start + movement);
          scrollVelocity *= deceleration;
          lastFrameTime = now;
        } else {
          setStart(Math.round(drawStack.start) + 0.5);
          scrollVelocity = 0;
          lastFrameTime = 0;
          momentumTimerRunning = false;
          drawStack.nav.navigating = false;
          update.set(!update.get());
          stop();
        }
      }
      
      @Override
      public void start() {
        momentumTimerRunning = true;
        lastFrameTime = System.nanoTime();
        super.start();
      }
      
      @Override
      public void stop() {
        momentumTimerRunning = false;
        lastFrameTime = 0;
        super.stop();
      }
    };
  }
  
  private void setupReactiveCanvas() {
    
    reactiveGc = reactiveCanvas.getGraphicsContext2D();
   
    reactiveCanvas.setOnMouseClicked(event -> { });
    reactiveCanvas.setOnMousePressed(event -> { 
      mousePressedX = event.getX(); 
      mousePressedY = event.getY();
      mouseDraggedX = event.getX(); // Use press position so first drag delta is correct
      mouseDragged = false;
      secondaryDragAnchorInitialized = false;
      primaryDragAnchorInitialized = event.isPrimaryButtonDown();
    });
    reactiveCanvas.setOnMouseDragged(event -> { mouseDragged = true; drawStack.nav.navigating = true; handleDrag(event); onDragActive(); });
    reactiveCanvas.setOnScroll(event -> { drawStack.nav.navigating = true; handleScroll(event); } );
    reactiveCanvas.setOnMouseReleased(event -> {
      handleMouseRelease(event);
      // Clear the drag flag shortly after release so the click event (which fires
      // after release) can still observe it, then normal interaction resumes.
      if (dragFlagClearTimer == null) {
        dragFlagClearTimer = new PauseTransition(javafx.util.Duration.millis(50));
        dragFlagClearTimer.setOnFinished(e -> mouseDragged = false);
      }
      dragFlagClearTimer.playFromStart();
    });
  }
  protected void handleScroll(ScrollEvent event) {
    event.consume();
      
    if (event.isControlDown()) {        
        double scrollZoom = event.getDeltaY();
        zoom(scrollZoom, event.getX());
        // Reset scroll-idle timer to clear navigating after zoom stops
        resetScrollIdleTimer();
    } else {
        // Vertical scroll (deltaY) — scroll reads within the sample track under cursor
        double deltaY = event.getDeltaY();
        if (deltaY != 0) {
          double mouseY = event.getY();
          double masterOffset = sampleRegistry.getMasterTrackHeight();
          double sampleH = sampleRegistry.getSampleHeight();
          if (sampleH > 0 && mouseY > masterOffset) {
            int sampleIdx = (int)((mouseY - masterOffset + sampleRegistry.getScrollBarPosition()) / sampleH);
            if (sampleIdx >= 0 && sampleIdx < sampleRegistry.getSampleTracks().size()) {
              org.baseplayer.samples.SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIdx);
              org.baseplayer.samples.alignment.AlignmentFile sf = track.getFirstBam();
              if (sf != null) {
                double readGap = org.baseplayer.io.Settings.get().getReadGap();
                double readHeight = org.baseplayer.io.Settings.get().getReadHeight();
                double rowPitch = Math.max(1.0, readHeight + readGap);
                int totalRows = Math.max(0, sf.getMaxRow(drawStack) + 1);
                double coverageH = Math.max(30,
                    Math.min(60, sampleH * org.baseplayer.io.Settings.get().getCoverageFraction()));
                double readsH = Math.max(1.0, sampleH - coverageH);

                // Check if in butterfly layout and which half the mouse is in
                double sampleY = masterOffset + sampleIdx * sampleH - sampleRegistry.getScrollBarPosition();
                double middleY = sampleY + sampleH / 2;
                int hp2Start = sf.getHP2StartRow(drawStack);
                int strandStart = sf.getStrandSplitStartRow(drawStack);
                int discStart = sf.getDiscordantSplitStartRow(drawStack);
                boolean isButterfly = (hp2Start >= 0 && track.getSamples().stream().anyMatch(s -> s.isHaplotypeData()))
                    || (strandStart >= 0 && sf.isSplitByStrand())
                    || (discStart >= 0 && sf.isSplitByDiscordant());

                int splitRow;
                if (discStart >= 0 && sf.isSplitByDiscordant()) {
                  splitRow = discStart;
                } else if (strandStart >= 0 && sf.isSplitByStrand()) {
                  splitRow = strandStart;
                } else {
                  splitRow = hp2Start;
                }

                if (isButterfly) {
                  double halfReadsH = Math.max(1.0, readsH / 2.0);
                  int topRows = Math.max(0, Math.min(totalRows, splitRow));
                  int bottomRows = Math.max(0, totalRows - topRows);
                  double maxTop = Math.max(0, topRows * rowPitch - halfReadsH);
                  double maxBottom = Math.max(0, bottomRows * rowPitch - halfReadsH);
                  if (mouseY < middleY) {
                    // Top half (forward / HP1): scroll up reveals more rows
                    double next = Math.max(0, Math.min(maxTop, sf.getReadScrollOffsetTop(drawStack) + deltaY));
                    sf.setReadScrollOffsetTop(drawStack, next);
                  } else {
                    // Bottom half (reverse / HP2): scroll down reveals more rows
                    double next = Math.max(0, Math.min(maxBottom, sf.getReadScrollOffsetBottom(drawStack) - deltaY));
                    sf.setReadScrollOffsetBottom(drawStack, next);
                  }
                } else {
                  double maxScroll = Math.max(0, totalRows * rowPitch - readsH);
                  double next = Math.max(0, Math.min(maxScroll, sf.getReadScrollOffset(drawStack) - deltaY));
                  sf.setReadScrollOffset(drawStack, next);
                }
              }
              // Trigger redraw
              update.set(!update.get());
              resetScrollIdleTimer();
            }
          }
        }

        // Horizontal scroll (deltaX) — pan genomic position
        double scrollDelta = event.getDeltaX();
        if (scrollDelta == 0) return;
        
        double scrollMultiplier = 0.3;
        double adjustedDelta = scrollDelta * scrollMultiplier;
        
        double genomeDelta = adjustedDelta * drawStack.scale;
        setStart(drawStack.start - genomeDelta);
        
        if (drawStack.viewLength < 5000000) { // Enable momentum up to 5M bp
          long currentTime = System.nanoTime();
          
          // Calculate velocity based on scroll events
          if (lastScrollTime > 0) {
            double timeDelta = (currentTime - lastScrollTime) / 1_000_000_000.0;
            if (timeDelta > 0 && timeDelta < 0.2) {
              // Smooth velocity accumulation - reduced blending to lower momentum buildup
              double newVelocity = genomeDelta / timeDelta;
              newVelocity = Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, newVelocity));
              // Blend with less retention of old velocity for less momentum
              scrollVelocity = scrollVelocity * 0.1 + newVelocity * 0.9;
            }
          } else {
            // First scroll event - estimate initial velocity with reduced coefficient
            scrollVelocity = genomeDelta * 30; // Reduced from 60fps assumption for less initial momentum
            scrollVelocity = Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, scrollVelocity));
          }
          // Cap velocity proportional to view size — prevents close-zoom from feeling frictionless.
          // At ≤100 bp view velocity stays below velocityThreshold so momentum won't fire at all.
          double dynamicMaxVelocity = Math.min(MAX_VELOCITY, drawStack.viewLength * 1.0);
          scrollVelocity = Math.max(-dynamicMaxVelocity, Math.min(dynamicMaxVelocity, scrollVelocity));
          lastScrollTime = currentTime;
          
          // Dynamic velocity threshold based on zoom level (logarithmic)
          double logScale = Math.log10(Math.max(10, drawStack.viewLength));
          double velocityThreshold = Math.max(100.0, drawStack.viewLength / (200.0 / logScale));
          
          // Start momentum timer if not running and velocity is significant
          if (Math.abs(scrollVelocity) > velocityThreshold && !momentumTimerRunning) {
            momentumTimer.start();
          }
        } else {
          scrollVelocity = 0;
          lastScrollTime = 0;
          resetScrollIdleTimer();
        }
    }
  }
  protected void handleDrag(MouseEvent event) {
    double dragX = event.getX();
		double dragY = event.getY();
    if (event.isSecondaryButtonDown()) {
      // First drag frame may arrive without a matching press on canvas (e.g. popup auto-hide).
      // Initialize the anchor here to prevent a stale-coordinate jump.
      if (!secondaryDragAnchorInitialized) {
        mouseDraggedX = dragX;
        secondaryDragAnchorInitialized = true;
        return;
      }
      double deltaX = dragX - mouseDraggedX;
      if (deltaX != 0) {
        setStart(drawStack.start - deltaX * drawStack.scale);
      }
      mouseDraggedX = dragX;
      return;
    }
    secondaryDragAnchorInitialized = false;
    if (!primaryDragAnchorInitialized) {
      // First primary-drag frame may arrive without a matching press on canvas
      // (e.g. popup auto-hide consumed the initial click). Anchor here.
      mousePressedX = dragX;
      mousePressedY = dragY;
      mouseDraggedX = dragX;
      primaryDragAnchorInitialized = true;
      return;
    }
    
    reactiveGc.setFill(DrawColors.ZOOM_GRADIENT);
    reactiveGc.setStroke(Color.DODGERBLUE);
    zoomDrag = true;
		mouseDragDeltaX = dragX - mouseDraggedX;
    mouseDraggedX = dragX;
    double totalDragPixels = Math.abs(mouseDraggedX - mousePressedX);
    if (!lineZoomer && mouseDraggedX >= mousePressedX) {
      clearReactive();
      double curtainX = mousePressedX;
      double curtainW = mouseDraggedX - mousePressedX;
      reactiveGc.fillRect(curtainX, zoomY, curtainW, getHeight());
      reactiveGc.strokeRect(curtainX, zoomY, curtainW, getHeight() + 2);

      // Show selected span width while dragging the zoom curtain.
      long spanBp = Math.max(1L, Math.round(curtainW * drawStack.scale));
      String spanLabel = BaseUtils.formatNumber(spanBp) + " bp";
      double labelW = spanLabel.length() * 7.2 + 12;
      double labelH = 18;
      double labelX = curtainX + curtainW * 0.5 - labelW * 0.5;
      if (labelX < 4) labelX = 4;
      if (labelX + labelW > getWidth() - 4) labelX = getWidth() - labelW - 4;
      double labelY = 6;

      reactiveGc.setFill(Color.rgb(20, 20, 26, 0.82));
      reactiveGc.fillRoundRect(labelX, labelY, labelW, labelH, 6, 6);
      reactiveGc.setStroke(Color.rgb(220, 230, 255, 0.85));
      reactiveGc.strokeRoundRect(labelX + 0.5, labelY + 0.5, labelW - 1, labelH - 1, 6, 6);
      reactiveGc.setFill(Color.rgb(242, 246, 255, 0.98));
      reactiveGc.setFont(Font.font("Segoe UI", 11));
      reactiveGc.fillText(spanLabel, labelX + 6, labelY + 12.5);
    } else {
      if (totalDragPixels < MIN_ZOOM_DRAG_PIXELS) {
        zoomDrag = false;
        clearReactive();
        return;
      }
      zoomDrag = false;
      lineZoomer = true;
      drawStack.nav.lineZoomerActive = true; // Block all fetches during line zoom
			clearReactive();
			reactiveGc.strokeLine(mousePressedX, mousePressedY, mouseDraggedX, dragY);
      zoom(mouseDragDeltaX, mousePressedX);
    }
  }
  protected void handleMouseRelease(MouseEvent event) {
    secondaryDragAnchorInitialized = false;
    primaryDragAnchorInitialized = false;
    clearReactive();
   
    if (lineZoomer) { 
      lineZoomer = false;
      drawStack.nav.lineZoomerActive = false; // Re-enable fetches
      drawStack.nav.navigating = false; // Allow fetches now that lineZoomer is done
      // Stop momentum timer to prevent stale velocity from triggering fetches
      scrollVelocity = 0;
      if (momentumTimerRunning) {
        momentumTimer.stop();
      }
      update.set(!update.get()); 
      return; 
    }
    
    drawStack.nav.navigating = false; // Normal case - drag/scroll ended
    
    if (zoomDrag) {
      zoomDrag = false;
      if (Math.abs(mouseDraggedX - mousePressedX) < MIN_ZOOM_DRAG_PIXELS) {
        update.set(!update.get());
        return;
      }
     
      if (mousePressedX > mouseDraggedX) { update.set(!update.get()); return; }

      double start = screenPosToChromPos.apply(mousePressedX);
      double end = screenPosToChromPos.apply(mouseDraggedX);
      zoomAnimation(start, end);
    } else {
      // Right-click pan release or no-op — trigger redraw to start BAM fetch
      update.set(!update.get());
    }
  }
  protected void clearReactive() { reactiveGc.clearRect(0, 0, getWidth(), getHeight()); }

  /**
   * Returns true while the user is dragging (or just released a drag).
   * Subclasses should use this to suppress hover effects and click handling.
   */
  protected boolean isDragging() {
    return mouseDragged || drawStack.nav.lineZoomerActive || drawStack.nav.navigating;
  }

  /**
   * True while the reactive canvas is used by GenomicCanvas for zoom visuals
   * (drag rectangle / line zoom). Subclasses can use this to decide whether
   * it is safe to draw their own reactive overlays.
   */
  protected boolean isReactiveOverlayReserved() {
    return zoomDrag || lineZoomer || drawStack.nav.lineZoomerActive || zoomPreviewActive;
  }

  /**
   * Called on every drag event. Subclasses can override to react while dragging
   * (e.g. clearing hover highlights).
   */
  protected void onDragActive() {}

  /** Start or restart a short timer that clears navigating after scroll events stop. */
  private void resetScrollIdleTimer() {
    if (scrollIdleTimer == null) {
      scrollIdleTimer = new PauseTransition(javafx.util.Duration.millis(200));
      scrollIdleTimer.setOnFinished(e -> {
        drawStack.nav.navigating = false;
        update.set(!update.get());
      });
    }
    scrollIdleTimer.playFromStart();
  }
  public void setStart(double start) {
    if (start < 1) start = 1;
    if (start + drawStack.viewLength > drawStack.chromSize + 1) return;
    setStartEnd(start, start+drawStack.viewLength);
  }
  public void setStartEnd(Double start, double end) {
    if (end - start < minZoom) {
      double requestedCenter = (start + end) / 2.0;
      start = requestedCenter - minZoom / 2.0;
      end = requestedCenter + minZoom / 2.0;
    }
    if (start < 1) start = 1.0;
    if (end >= drawStack.chromSize - 1) end = drawStack.chromSize + 1;

    double oldViewLength = drawStack.viewLength;
    double newViewLength = end - start;
    
    // Cancel fetches only if we jumped to a different region (low overlap), not on zoom-in
    double overlapStart = Math.max(start, drawStack.start);
    double overlapEnd = Math.min(end, drawStack.end);
    double overlapSize = Math.max(0, overlapEnd - overlapStart);
    double overlapRatio = overlapSize / Math.max(oldViewLength, newViewLength);
    
    // Cancel and clear caches only if regions have <30% overlap
    if (overlapRatio < 0.3) {
      FetchManager.get().cancelAll();
      // Clear read/coverage caches for this stack to free memory
      for (var track : sampleRegistry.getSampleTracks()) {
        for (var sample : track.getSamples()) {
          if (sample.getBamFile() != null) {
            sample.getBamFile().clearAllCaches(drawStack);
          }
        }
      }
    }
    
    // If zooming out beyond coverage view threshold, clear detailed caches
    if (newViewLength > org.baseplayer.io.Settings.get().getMaxCoverageViewLength() && 
        oldViewLength <= org.baseplayer.io.Settings.get().getMaxCoverageViewLength()) {
      // Zoomed out past coverage limit - clear read cache to free memory
      for (var track : sampleRegistry.getSampleTracks()) {
        for (var sample : track.getSamples()) {
          if (sample.getBamFile() != null) {
            sample.getBamFile().clearReadCache(drawStack);
          }
        }
      }
    }
    
    // If zooming out beyond read view threshold, clear read caches (reads won't be drawn)
    if (newViewLength > org.baseplayer.io.Settings.get().getMaxReadViewLength() && 
        oldViewLength <= org.baseplayer.io.Settings.get().getMaxReadViewLength()) {
      for (var track : sampleRegistry.getSampleTracks()) {
        for (var sample : track.getSamples()) {
          if (sample.getBamFile() != null) {
            sample.getBamFile().clearReadCache(drawStack);
          }
        }
      }
    }

    drawStack.start = start;
    drawStack.end = end;
    drawStack.viewLength = end - start;
    drawStack.pixelSize = getWidth() / drawStack.viewLength;
    drawStack.scale = drawStack.viewLength / getWidth();
    update.set(!update.get());
  }


  protected void zoom(double zoomDirection, double targetX) {
		if (zoomDirection == 0.0) return;
    int direction = zoomDirection > 0 ? 1 : -1;
    double pivot = targetX / getWidth();
    double acceleration = drawStack.viewLength/getWidth() * 10;
    double newSize = drawStack.viewLength - zoomFactor * acceleration * direction;
    double start = Math.max(1, screenPosToChromPos.apply(targetX) - (pivot * newSize));
    double end = Math.min(drawStack.chromSize + 1, start + newSize);
    if (drawStack.start == start && drawStack.end == end) return;
    setStartEnd(start, end);
  }

  public void zoomAnimation(double start, double end) {
    if (shouldSkipZoomAnimation(start, end)) {
      cancelZoomAnimation(true);
      setStartEnd(start, end);
      return;
    }

    // Preempt an in-flight zoom so repeated zoom-in clicks cannot leave the
    // viewport in an intermediate state.
    cancelZoomAnimation(true);

    pendingZoomStart = start;
    pendingZoomEnd = end;

    // Only cancel fetches if we're jumping to a different region (not zooming in on same area)
    double overlapStart = Math.max(start, drawStack.start);
    double overlapEnd = Math.min(end, drawStack.end);
    double overlapSize = Math.max(0, overlapEnd - overlapStart);
    double currentSize = drawStack.end - drawStack.start;
    double overlapRatio = overlapSize / currentSize;
    
    // Cancel only if regions have <30% overlap (indicates a jump, not a zoom)
    if (overlapRatio < 0.3) {
      FetchManager.get().cancelAll();
    }

    // Animate by transforming the current canvas snapshot and commit genomic
    // coordinates only once at the end.
    final double sourceStart = drawStack.start;
    final double sourceEnd = drawStack.end;

    final List<GenomicCanvas> previewCanvases = collectStackPreviewCanvases();
    boolean hasPreviewSnapshot = false;
    for (GenomicCanvas canvas : previewCanvases) {
      Image snap = canvas.snapshot(null, null);
      if (snap == null) continue;
      canvas.interactionSnapshot = snap;
      canvas.zoomPreviewActive = true;
      hasPreviewSnapshot = true;
    }

    if (!hasPreviewSnapshot) {
      drawStack.nav.animationRunning = false;
      drawStack.nav.navigating = false;
      setStartEnd(start, end);
      pendingZoomStart = Double.NaN;
      pendingZoomEnd = Double.NaN;
      return;
    }

    drawStack.nav.animationRunning = true;
    drawStack.nav.navigating = true;

    final long durationNanos = 120_000_000L; // 120 ms
    final long[] startNanos = { -1L };

    zoomPreviewTimer = new AnimationTimer() {
      @Override
      public void handle(long now) {
        if (startNanos[0] < 0) {
          startNanos[0] = now;
        }
        double t = Math.min(1.0, (now - startNanos[0]) / (double) durationNanos);

        double currentStart = sourceStart + (start - sourceStart) * t;
        double currentEnd = sourceEnd + (end - sourceEnd) * t;
        for (GenomicCanvas canvas : previewCanvases) {
          if (canvas.interactionSnapshot == null) continue;
          canvas.drawZoomPreview(sourceStart, sourceEnd, currentStart, currentEnd);
        }

        if (t >= 1.0) {
          stop();
          zoomPreviewTimer = null;
          for (GenomicCanvas canvas : previewCanvases) {
            canvas.zoomPreviewActive = false;
            canvas.interactionSnapshot = null;
            canvas.clearReactive();
          }
          drawStack.nav.animationRunning = false;
          drawStack.nav.navigating = false;
          setStartEnd(start, end);
          pendingZoomStart = Double.NaN;
          pendingZoomEnd = Double.NaN;
        }
      }
    };
    zoomPreviewTimer.start();
  }

  private boolean shouldSkipZoomAnimation(double targetStart, double targetEnd) {
    double currentView = Math.max(minZoom, drawStack.end - drawStack.start);
    double targetView = Math.max(minZoom, targetEnd - targetStart);
    boolean zoomingIn = targetView < currentView;
    double closeZoomThreshold = minZoom * CLOSE_ZOOM_NO_ANIMATION_FACTOR;
    return zoomingIn && (currentView <= closeZoomThreshold || targetView <= closeZoomThreshold);
  }

  private void cancelZoomAnimation(boolean snapToPendingTarget) {
    if (zoomPreviewTimer != null) {
      zoomPreviewTimer.stop();
      zoomPreviewTimer = null;
    }

    boolean hadPreview = false;
    for (GenomicCanvas canvas : collectStackPreviewCanvases()) {
      if (canvas.zoomPreviewActive || canvas.interactionSnapshot != null) {
        hadPreview = true;
      }
      canvas.zoomPreviewActive = false;
      canvas.interactionSnapshot = null;
      canvas.clearReactive();
    }

    if (hadPreview) {
      drawStack.nav.animationRunning = false;
      drawStack.nav.navigating = false;
    }

    if (snapToPendingTarget && !Double.isNaN(pendingZoomStart) && !Double.isNaN(pendingZoomEnd)) {
      setStartEnd(pendingZoomStart, pendingZoomEnd);
    }

    pendingZoomStart = Double.NaN;
    pendingZoomEnd = Double.NaN;
  }

  private List<GenomicCanvas> collectStackPreviewCanvases() {
    LinkedHashSet<GenomicCanvas> set = new LinkedHashSet<>();
    set.add(this);
    if (drawStack.alignmentCanvas != null) set.add(drawStack.alignmentCanvas);
    if (drawStack.chromosomeCanvas != null) set.add(drawStack.chromosomeCanvas);
    if (drawStack.featureTracksCanvas != null) set.add(drawStack.featureTracksCanvas);
    return new ArrayList<>(set);
  }

  private void drawZoomPreview(double sourceStart, double sourceEnd,
                               double currentStart, double currentEnd) {
    if (interactionSnapshot == null) return;

    double sourceView = sourceEnd - sourceStart;
    double currentView = Math.max(minZoom, currentEnd - currentStart);
    double scaleX = sourceView / currentView;
    double translateX = (sourceStart - currentStart) * (getWidth() / currentView);

    reactiveGc.setFill(DrawColors.BACKGROUND);
    reactiveGc.fillRect(0, 0, getWidth(), getHeight());
    reactiveGc.drawImage(interactionSnapshot, translateX, 0, getWidth() * scaleX, getHeight());
  }
}
