package org.baseplayer.draw;

import java.util.function.Function;

import org.baseplayer.SharedModel;
import org.baseplayer.controllers.MainController;
import org.baseplayer.reads.bam.FetchManager;
import org.baseplayer.utils.DrawColors;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class DrawFunctions extends Canvas {
  //private Pane parent;
  public DrawStack drawStack;
  public static int minZoom = 40;
  public static BooleanProperty update = new SimpleBooleanProperty(false);
 
  private final GraphicsContext gc;
  public GraphicsContext reactivegc;

  
  Function<Double, Double> chromPosToScreenPos = chromPos -> (chromPos - drawStack.start) * drawStack.pixelSize;
  Function<Double, Double> heightToScreen = height -> getHeight()/SharedModel.visibleSamples().getAsInt() * height;
  Function<Double, Integer> screenPosToChromPos = screenPos -> (int)(drawStack.start + screenPos * drawStack.scale);
  private double mousePressedX;
  private final Canvas reactiveCanvas;
  private double mouseDraggedX;
	private double mouseDragDeltaX = 0;
  private double mousePressedY;
  private boolean lineZoomer = false;
  public static volatile boolean lineZoomerActive = false; // Global flag to block fetches during line zoom
  private boolean zoomDrag;
  public static double zoomFactor = 10;
  public int zoomY = - 1;
  public static boolean resizing = false;
  public static boolean animationRunning = false;
  /** True while the user is actively dragging, scrolling, or zoom-animating. BAM fetches are deferred. */
  public static volatile boolean navigating = false;
  /** Timer to clear navigating after scroll wheel stops */
  private PauseTransition scrollIdleTimer;
  
  private double scrollVelocity = 0;
  private long lastScrollTime = 0;
  private long lastFrameTime = 0;
  private AnimationTimer momentumTimer;
  private boolean momentumTimerRunning = false;
  private static final double MAX_VELOCITY = 50000.0; // Cap maximum scroll velocity
  private static final long SCROLL_IDLE_THRESHOLD = 50_000_000; // 50ms in nanoseconds

  public DrawFunctions(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    this.reactiveCanvas = reactiveCanvas;
    this.drawStack = drawStack;
    gc = getGraphicsContext2D();  
    gc.setFont(Font.font("Segoe UI Regular", 12));
    heightProperty().bind(parent.heightProperty());
    widthProperty().bind(parent.widthProperty());
    reactiveCanvas.heightProperty().bind(parent.heightProperty());
    reactiveCanvas.widthProperty().bind(parent.widthProperty());
    parent.widthProperty().addListener((obs, oldVal, newVal) -> { 
      resizing = true; update.set(!update.get()); resizing = false;
    });
    reactiveCanvas.setOnMouseEntered(event -> { MainController.hoverStack = drawStack; update.set(!update.get()); });

    parent.heightProperty().addListener((obs, oldVal, newVal) -> { resizing = true; update.set(!update.get()); resizing = false; });
   
    initializeMomentumTimer();
    setupReactiveCanvas();
  }

  protected void draw() {
    
    if (MainController.drawStacks.size() > 1 && drawStack.equals(MainController.hoverStack)) {
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
          navigating = false;
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
    
    reactivegc = reactiveCanvas.getGraphicsContext2D();
   
    reactiveCanvas.setOnMouseClicked(event -> { });
    reactiveCanvas.setOnMousePressed(event -> { 
      mousePressedX = event.getX(); 
      mousePressedY = event.getY();
      mouseDraggedX = 0; // Reset for delta calculation
    });
    reactiveCanvas.setOnMouseDragged(event -> { navigating = true; handleDrag(event); });
    reactiveCanvas.setOnScroll(event -> { navigating = true; handleScroll(event); } );
    reactiveCanvas.setOnMouseReleased(event -> { handleMouseRelease(event); });
  }
  void handleScroll(ScrollEvent event) {
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
          double masterOffset = SharedModel.masterTrackHeight;
          double sampleH = SharedModel.sampleHeight;
          if (sampleH > 0 && mouseY > masterOffset) {
            int sampleIdx = (int)((mouseY - masterOffset + SharedModel.scrollBarPosition) / sampleH);
            if (sampleIdx >= 0 && sampleIdx < SharedModel.sampleTracks.size()) {
              org.baseplayer.sample.SampleTrack track = SharedModel.sampleTracks.get(sampleIdx);
              org.baseplayer.reads.bam.SampleFile sf = track.getFirstBam();
              if (sf != null) {
                sf.readScrollOffset = Math.max(0, sf.readScrollOffset - deltaY);
              }
              // Trigger redraw
              update.set(!update.get());
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
  void handleDrag(MouseEvent event) {
    double dragX = event.getX();
		double dragY = event.getY();
    if (event.getButton() == MouseButton.SECONDARY) {
      // Calculate delta from last drag position for smooth scrolling
      double deltaX = dragX - (mouseDraggedX != 0 ? mouseDraggedX : mousePressedX);
      setStart(drawStack.start - deltaX * drawStack.scale);
      mouseDraggedX = dragX;
      return;
    }
    
    reactivegc.setFill(DrawColors.ZOOM_GRADIENT);
    reactivegc.setStroke(Color.DODGERBLUE);
    zoomDrag = true;
		mouseDragDeltaX = dragX - mouseDraggedX;
    mouseDraggedX = dragX;
    if (!lineZoomer && mouseDraggedX >= mousePressedX) {
      clearReactive();
      reactivegc.fillRect(mousePressedX, zoomY, mouseDraggedX-mousePressedX, getHeight());
      reactivegc.strokeRect(mousePressedX, zoomY, mouseDraggedX-mousePressedX, getHeight() + 2);
    } else {
      zoomDrag = false;
      lineZoomer = true;
      lineZoomerActive = true; // Block all fetches during line zoom
			clearReactive();
			reactivegc.strokeLine(mousePressedX, mousePressedY, mouseDraggedX, dragY);
      zoom(mouseDragDeltaX, mousePressedX);
    }
  }
  void handleMouseRelease(MouseEvent event) {
    clearReactive();
   
    if (lineZoomer) { 
      lineZoomer = false;
      lineZoomerActive = false; // Re-enable fetches
      navigating = false; // Allow fetches now that lineZoomer is done
      update.set(!update.get()); 
      return; 
    }
    
    navigating = false; // Normal case - drag/scroll ended
    
    if (zoomDrag) {
      zoomDrag = false;
     
      if (mousePressedX > mouseDraggedX) { update.set(!update.get()); return; }

      double start = screenPosToChromPos.apply(mousePressedX);
      double end = screenPosToChromPos.apply(mouseDraggedX);
      zoomAnimation(start, end);
    } else {
      // Right-click pan release or no-op — trigger redraw to start BAM fetch
      update.set(!update.get());
    }
  }
  void clearReactive() { reactivegc.clearRect(0, 0, getWidth(), getHeight()); }

  /** Start or restart a short timer that clears navigating after scroll events stop. */
  private void resetScrollIdleTimer() {
    if (scrollIdleTimer == null) {
      scrollIdleTimer = new PauseTransition(javafx.util.Duration.millis(200));
      scrollIdleTimer.setOnFinished(e -> {
        navigating = false;
        update.set(!update.get());
      });
    }
    scrollIdleTimer.playFromStart();
  }
  void setStart(double start) {
    if (start < 1) start = 1;
    if (start + drawStack.viewLength > drawStack.chromSize + 1) return;
    setStartEnd(start, start+drawStack.viewLength);
  }
  public void setStartEnd(Double start, double end) {
    if (end - start < minZoom) {
			start = drawStack.middlePos() - minZoom / 2;
			end = drawStack.middlePos() + minZoom / 2;
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
      for (var track : org.baseplayer.SharedModel.sampleTracks) {
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
      for (var track : org.baseplayer.SharedModel.sampleTracks) {
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


  void zoom(double zoomDirection, double targetX) {
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
    
    new Thread(() -> {
      animationRunning = true;
      navigating = true;
      final DoubleProperty currentStart = new SimpleDoubleProperty(drawStack.start);
      final DoubleProperty currentEnd = new SimpleDoubleProperty(drawStack.end);
      double startStep = (start - drawStack.start)/10;
      double endStep = (drawStack.end - end)/10;
      final boolean[] ended = {false};
      for(int i = 0; i < 10; i++) {
        Platform.runLater(() -> { setStartEnd(currentStart.get(), currentEnd.get()); });
        currentStart.set(currentStart.get() + startStep);
        currentEnd.set(currentEnd.get() - endStep);
        if ((startStep > 0 && currentStart.get() >= start) || (startStep < 0 && currentStart.get() <= start)) {
          ended[0] = true;
          break;
        }
        
        try { 
          Thread.sleep(10); 
        } catch (InterruptedException e) { 
          Thread.currentThread().interrupt();
          break; 
        }
      }
      
      // Schedule final position update and flag clearing together
      Platform.runLater(() -> {
        // Set final position (either target or current depending on early termination)
        setStartEnd(ended[0] ? start : drawStack.start, end);
        animationRunning = false;
        navigating = false;
        update.set(!update.get());
      });
    }).start();
  }
}
