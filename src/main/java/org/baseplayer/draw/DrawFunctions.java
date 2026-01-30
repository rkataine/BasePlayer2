package org.baseplayer.draw;

import java.util.function.Function;

import org.baseplayer.SharedModel;
import org.baseplayer.controllers.MainController;

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
  static int minZoom = 40;
  public static BooleanProperty update = new SimpleBooleanProperty(false);
 
  private final GraphicsContext gc;
  public GraphicsContext reactivegc;
  private boolean lineZoomer = false;
  public static Color lineColor = new Color(0.5, 0.8, 0.8, 0.5);
  public static Color backgroundColor = new Color(0.2, 0.2, 0.2, 1);
  static LinearGradient zoomColor = new LinearGradient(
    0, 0, 1, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
    new Stop(0, javafx.scene.paint.Color.rgb(105, 255, 0, 0.2)),  
    new Stop(1, javafx.scene.paint.Color.rgb(0, 200, 255, 0.2))
  );

  
  Function<Double, Double> chromPosToScreenPos = chromPos -> (chromPos - drawStack.start) * drawStack.pixelSize;
  Function<Double, Double> heightToScreen = height -> getHeight()/SharedModel.visibleSamples().getAsInt() * height;
  Function<Double, Integer> screenPosToChromPos = screenPos -> (int)(drawStack.start + screenPos * drawStack.scale);
  private double mousePressedX;
  private final Canvas reactiveCanvas;
  private double mouseDraggedX;
  private boolean zoomDrag;
  public static double zoomFactor = 20;
  public int zoomY = - 1;
  public static boolean resizing = false;
  public static boolean animationRunning = false;

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
    reactiveCanvas.setOnMouseEntered(event -> { MainController.hoverStack = drawStack; resizing = true; update.set(!update.get()); resizing = false; });

    parent.heightProperty().addListener((obs, oldVal, newVal) -> { resizing = true; update.set(!update.get()); resizing = false; });
   
    setupReactiveCanvas();
    // Platform.runLater(() -> { setStartEnd(drawStack.start, end); });
  }

  void draw() {
    
    if (MainController.drawStacks.size() > 1 && drawStack.equals(MainController.hoverStack)) {
      gc.setStroke(Color.WHITESMOKE);
      gc.strokeRect(1, -1, getWidth()-2, getHeight()+2);
    }
  }
  public Canvas getReactiveCanvas() { return reactiveCanvas; }
  
  private void setupReactiveCanvas() {
    
    reactivegc = reactiveCanvas.getGraphicsContext2D();
   
    reactiveCanvas.setOnMouseClicked(event -> { });
    reactiveCanvas.setOnMousePressed(event -> { mousePressedX = event.getX(); } );
    reactiveCanvas.setOnMouseDragged(event -> handleDrag(event));
    reactiveCanvas.setOnScroll(event -> handleScroll(event) );
    //reactiveCanvas.setOnMouseMoved(event -> { });
    reactiveCanvas.setOnMouseReleased(event -> { handleMouseRelease(event); });
  }
  void handleScroll(ScrollEvent event) {
    event.consume();
      
    if (event.isControlDown()) {        
        // Zoom
        double scrollZoom = event.getDeltaY();
        double mousePos = event.getX();
        zoom(scrollZoom, mousePos);
    } else {
        // Scroll
        double acceleration = drawStack.viewLength/getWidth() * 2;
        double xPos = event.getDeltaX() * acceleration;
        setStart(drawStack.start - xPos);
    }
  }
  void handleDrag(MouseEvent event) {
    double dragX = event.getX();
    
    if (event.getButton() == MouseButton.SECONDARY) {      
      setStart(drawStack.start - (dragX - mousePressedX) / drawStack.pixelSize);
      mousePressedX = dragX;
      return;
    }
    
    reactivegc.setFill(zoomColor);
    reactivegc.setStroke(Color.INDIANRED);
    zoomDrag = true;
    mouseDraggedX = dragX;
    //clearReactive();
    if (!lineZoomer && mouseDraggedX >= mousePressedX) {
      clearReactive();
      reactivegc.fillRect(mousePressedX, zoomY, mouseDraggedX-mousePressedX, getHeight());
      reactivegc.strokeRect(mousePressedX, zoomY, mouseDraggedX-mousePressedX, getHeight() + 2);
    } else {
      zoomDrag = false;
      lineZoomer = true;
      
      zoom(dragX - mousePressedX, mousePressedX);
      mousePressedX = dragX;
    }
  }
  void handleMouseRelease(MouseEvent event) {
    clearReactive();
   
    if (lineZoomer) { lineZoomer = false; return; }
    if (zoomDrag) {
      zoomDrag = false;
     
      if (mousePressedX > mouseDraggedX) return;

      double start = screenPosToChromPos.apply(mousePressedX);
      double end = screenPosToChromPos.apply(mouseDraggedX);
      zoomAnimation(start, end);
    }
  }
  void clearReactive() { reactivegc.clearRect(0, 0, getWidth(), getHeight()); }
  void setStart(double start) {
    if (start < 1) start = 1;
    if (start + drawStack.viewLength > drawStack.chromSize + 1) return;
    setStartEnd(start, start+drawStack.viewLength);
  }
  void setStartEnd(Double start, double end) {
    if (end - start < minZoom) {
        start = (start+(end-start)/2) - minZoom/2;
        end = start + minZoom;
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


  void zoom(double zoomDirection, double mousePos) {
   
    int direction = zoomDirection > 0 ? 1 : -1;
    double pivot = mousePos / getWidth();
    double acceleration = drawStack.viewLength/getWidth() * 15;
    double newSize = drawStack.viewLength - zoomFactor * acceleration * direction;
    if (newSize < minZoom) newSize = minZoom;
    double start = Math.max(1, screenPosToChromPos.apply(mousePos) - (pivot * newSize));
    double end = Math.min(drawStack.chromSize + 1, start + newSize);
    if (drawStack.start == start && drawStack.end == end) return;
    setStartEnd(start, end);
  }

  public void zoomAnimation(double start, double end) {
    new Thread(() -> {
      animationRunning = true;
      final DoubleProperty currentStart = new SimpleDoubleProperty(drawStack.start);
      final DoubleProperty currentEnd = new SimpleDoubleProperty(drawStack.end);
      int startStep = (int)(start - drawStack.start)/10;
      int endStep = (int)(drawStack.end - end)/10;
      boolean ended = false;
      for(int i = 0; i < 10; i++) {
        Platform.runLater(() -> { setStartEnd(currentStart.get(), currentEnd.get()); });
        currentStart.set(currentStart.get() + startStep);
        currentEnd.set(currentEnd.get() - endStep);
        if ((startStep > 0 && currentStart.get() >= start) || (startStep < 0 && currentStart.get() <= start)) {
          animationRunning = false;
          ended = true;
          Platform.runLater(() -> setStartEnd(start, end) );
          break;
        }
        
        try { 
          Thread.sleep(10); 
        } catch (InterruptedException e) { 
          Thread.currentThread().interrupt();
          break; 
        }
      }
      if (!ended) Platform.runLater(() -> setStartEnd(drawStack.start, end) );
    }).start();
  }
}
