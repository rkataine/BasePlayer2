package org.baseplayer.draw;

import java.util.function.Function;

import org.baseplayer.SharedModel;
import org.baseplayer.controllers.MainController;

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
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;

public class DrawFunctions extends Canvas {
  //private Pane parent;
  public DrawStack drawStack;
  public static int minZoom = 40;
  public static BooleanProperty update = new SimpleBooleanProperty(false);
 
  private final GraphicsContext gc;
  public GraphicsContext reactivegc;
  public static Color lineColor = new Color(0.5, 0.8, 0.8, 0.5);
  // VS Code dark mode colors
  public static Color backgroundColor = Color.web("#1e1e1e");        // Editor background
  public static Color sidebarColor = Color.web("#252526");           // Sidebar background
  public static Color borderColor = Color.web("#3c3c3c");            // Border color
  static LinearGradient zoomColor = new LinearGradient(
    0, 0, 1, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
    new Stop(0, javafx.scene.paint.Color.rgb(30, 144, 255, 0.3)),  
    new Stop(1, javafx.scene.paint.Color.rgb(100, 180, 255, 0.3))
  );

  
  Function<Double, Double> chromPosToScreenPos = chromPos -> (chromPos - drawStack.start) * drawStack.pixelSize;
  Function<Double, Double> heightToScreen = height -> getHeight()/SharedModel.visibleSamples().getAsInt() * height;
  Function<Double, Integer> screenPosToChromPos = screenPos -> (int)(drawStack.start + screenPos * drawStack.scale);
  private double mousePressedX;
  private final Canvas reactiveCanvas;
  private double mouseDraggedX;
	private double mouseDragDeltaX = 0;
  private double mousePressedY;
  private boolean lineZoomer = false;
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
        
        // Dynamic deceleration: faster decay when zoomed in, slower when zoomed out
        // At 40bp: ~0.92 (fast decay), at 100k: ~0.96, at 1M+: ~0.98
        double deceleration = 0.90 + Math.min(0.08, logScale * 0.015);
        
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
    reactiveCanvas.setOnMouseReleased(event -> { navigating = false; handleMouseRelease(event); });
  }
  void handleScroll(ScrollEvent event) {
    event.consume();
      
    if (event.isControlDown()) {        
        double scrollZoom = event.getDeltaY();
        zoom(scrollZoom, event.getX());
        // Reset scroll-idle timer to clear navigating after zoom stops
        resetScrollIdleTimer();
    } else {
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
              // Smooth velocity accumulation
              double newVelocity = genomeDelta / timeDelta;
              newVelocity = Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, newVelocity));
              // Blend with existing velocity for smoother transitions
              scrollVelocity = scrollVelocity * 0.3 + newVelocity * 0.7;
            }
          } else {
            // First scroll event - estimate initial velocity
            scrollVelocity = genomeDelta * 60; // Assume ~60fps
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
    
    reactivegc.setFill(zoomColor);
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
			clearReactive();
			reactivegc.strokeLine(mousePressedX, mousePressedY, mouseDraggedX, dragY);
      zoom(mouseDragDeltaX, mousePressedX);
    }
  }
  void handleMouseRelease(MouseEvent event) {
    clearReactive();
   
    if (lineZoomer) { 
      lineZoomer = false; 
      update.set(!update.get()); 
      return; 
    }
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
