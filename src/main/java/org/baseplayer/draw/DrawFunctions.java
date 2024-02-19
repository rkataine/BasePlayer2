package org.baseplayer.draw;

import java.util.function.Function;

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
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;

public class DrawFunctions extends Canvas {
  static double chromSize = 100000000;
  int minZoom = 40;
  public static double start = 1;
  public static BooleanProperty update = new SimpleBooleanProperty(false);
  public static double end = chromSize + 1;
  static double viewLength = chromSize;
  static double pixelSize = 0;
  static double scale = 0;
  private GraphicsContext gc;
  private GraphicsContext reactivegc;
  private boolean lineZoomer = false;
  public static Color lineColor = new Color(0.5, 0.8, 0.8, 0.5);
  static LinearGradient zoomColor = new LinearGradient(
    0, 0, 1, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
    new Stop(0, javafx.scene.paint.Color.rgb(105, 255, 0, 0.2)),  // Red with 50% transparency
    new Stop(1, javafx.scene.paint.Color.rgb(0, 200, 255, 0.2))   // Blue with 50% transparency
  );
  static Font tekstifont = new Font("Arial", 10);
  
  static Function<Double, Double> chromPosToScreenPos = chromPos -> (chromPos - start) * pixelSize;
  Function<Double, Double> heightToScreen = height -> getHeight() * height;
  static Function<Double, Integer> screenPosToChromPos = screenPos -> (int)(start + screenPos * scale);
  private double mousePressedX;
  private Canvas reactiveCanvas;
  
  private double mouseDraggedX;
  private boolean zoomDrag;
  private double zoomFactor = 20;
  
  public DrawFunctions(Canvas reactiveCanvas, Pane parent) {
    this.reactiveCanvas = reactiveCanvas;
    end = chromSize;
    gc = getGraphicsContext2D();  
    gc.setFont(Font.font("Segoe UI Regular", 12));
    widthProperty().addListener((obs, oldVal, newVal) -> { setStartEnd(start, end); });
    heightProperty().addListener((obs, oldVal, newVal) -> { setStartEnd(start, end); });
    heightProperty().bind(parent.heightProperty());
    widthProperty().bind(parent.widthProperty());
    reactiveCanvas.heightProperty().bind(parent.heightProperty());
    reactiveCanvas.widthProperty().bind(parent.widthProperty());
    setReactiveCanvas(reactiveCanvas);
  }
  public Canvas getReactiveCanvas() { return reactiveCanvas; }

  void setReactiveCanvas(Canvas reactiveCanvas) {
    
    reactivegc = reactiveCanvas.getGraphicsContext2D();
   
    reactiveCanvas.setOnMouseClicked(event -> { });
    reactiveCanvas.setOnMousePressed(event -> mousePressedX = event.getX() );
    reactiveCanvas.setOnMouseDragged(event -> handleDrag(event));
    reactiveCanvas.setOnScroll(event -> handleScroll(event) );
    reactiveCanvas.setOnMouseReleased(event -> { handleMouseRelease(event); });   
  }
  void handleScroll(ScrollEvent event) {
    event.consume();
      
    if (event.isControlDown()) {        
        // Zoom
        double zoomFactor = event.getDeltaY();
        double mousePos = event.getX();
        zoom(zoomFactor, mousePos);
    } else {
        // Scroll
        double acceleration = viewLength/getWidth() * 2;
        double xPos = event.getDeltaX() * acceleration;
        setStart(start - xPos);
    }
  }
  void handleDrag(MouseEvent event) {
    double dragX = event.getX();
  
    if (event.getButton() == MouseButton.SECONDARY) {      
      setStart(start - (dragX - mousePressedX) / pixelSize);
      mousePressedX = dragX;
      return;
    }
    
    reactivegc.setFill(zoomColor);
    reactivegc.setStroke(Color.WHITESMOKE);
    zoomDrag = true;
    mouseDraggedX = dragX;
    clearReactive();
    if (!lineZoomer && mouseDraggedX >= mousePressedX) {
      reactivegc.fillRect(mousePressedX, 0, mouseDraggedX-mousePressedX, getHeight());
      reactivegc.strokeRect(mousePressedX, 0, mouseDraggedX-mousePressedX, getHeight());

    } else {
      zoomDrag = false;
      lineZoomer = true;
      zoom(dragX - mousePressedX, mousePressedX);
      mousePressedX = dragX;
    }
  }
  void handleMouseRelease(MouseEvent event) {
    if (lineZoomer) { lineZoomer = false; return; }
    if (zoomDrag) {
      zoomDrag = false;
      clearReactive();
      if (mousePressedX > mouseDraggedX) return;

      double start = screenPosToChromPos.apply(mousePressedX);
      double end = screenPosToChromPos.apply(mouseDraggedX);
      zoomAnimation(start, end);
    }
  }
 
  void clearReactive() { reactivegc.clearRect(0, 0, getWidth(), getHeight()); }
  void setStart(double start) {
    if (start < 1) start = 1;
   
    if (start + viewLength > chromSize + 1) return;
    setStartEnd(start, start+viewLength);
  }
  void setStartEnd(Double start, double end) {
    if (end - start < minZoom) {
        start = (start+(end-start)/2) - minZoom/2;
        end = start + minZoom;
    };
    if (start < 1) start = 1.0;
    if (end > chromSize) end = chromSize + 1;
    
    DrawSampleData.start = start;
    
    DrawSampleData.end = end;
    viewLength = end - start;
    pixelSize = getWidth() / viewLength;
    scale = viewLength / getWidth();
    update.set(!update.get());
  }
  public void zoomout() { zoomAnimation(1, chromSize); };

  void zoom(double zoomDirection, double mousePos) {
    int direction = zoomDirection > 0 ? 1 : -1;
    double pivot = mousePos / getWidth();
    double acceleration = viewLength/getWidth() * 15;
    double newSize = viewLength - zoomFactor * acceleration * direction;
    if (newSize < minZoom) newSize = minZoom;
    double start = screenPosToChromPos.apply(mousePos) - (pivot * newSize);
    double end = start + newSize;
    setStartEnd(start, end);
  }

  void zoomAnimation(double start, double end) {
    new Thread(() -> {
      final DoubleProperty currentStart = new SimpleDoubleProperty(DrawFunctions.start);
      final DoubleProperty currentEnd = new SimpleDoubleProperty(DrawFunctions.end);
      int startStep = (int)(start - DrawFunctions.start)/10;
      int endStep = (int)(DrawFunctions.end - end)/10;
     
      while (true) {
        Platform.runLater(() -> { setStartEnd(currentStart.get(), currentEnd.get()); });
        currentStart.set(currentStart.get() + startStep);
        currentEnd.set(currentEnd.get() - endStep);
        if ((startStep > 0 && currentStart.get() >= start) || (startStep < 0 && currentStart.get() <= start)) {
          Platform.runLater(() -> { setStartEnd(start, end); });
          break;
        }
        try { Thread.sleep(10); } catch (InterruptedException e) { e.printStackTrace(); break; }
      }
    }).start();
  }
}
