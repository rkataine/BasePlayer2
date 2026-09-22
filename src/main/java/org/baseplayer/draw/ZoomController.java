package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.baseplayer.samples.alignment.FetchManager;
import org.baseplayer.utils.BaseUtils;
import org.baseplayer.utils.DrawColors;

import javafx.animation.AnimationTimer;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Owns genomic zoom: glass-pane curtain/line painting, scroll/button zoom math,
 * and snapshot zoom animation. Each {@link GenomicCanvas} holds one instance;
 * the main-split glass overlay is shared statically.
 */
public final class ZoomController {

  public static final double MIN_ZOOM_DRAG_PIXELS = 5.0;
  private static final double CLOSE_ZOOM_NO_ANIMATION_FACTOR = 3.0;
  private static final long ZOOM_ANIMATION_NANOS = 120_000_000L;

  private static Canvas glassCanvas;
  private static GraphicsContext glassGc;
  private static Object glassOwner;
  private static StackPane glassHost;

  private final GenomicCanvas canvas;

  private boolean zoomDrag;
  private boolean lineZoomer;
  private AnimationTimer zoomPreviewTimer;
  private double pendingZoomStart = Double.NaN;
  private double pendingZoomEnd = Double.NaN;

  ZoomController(GenomicCanvas canvas) {
    this.canvas = canvas;
  }

  // ── Glass overlay (main split) ───────────────────────────────────────────

  /** Install a mouse-transparent glass canvas over {@code mainSplit}. */
  public static void installGlass(SplitPane mainSplit) {
    if (mainSplit == null || glassCanvas != null) {
      return;
    }
    Parent parent = mainSplit.getParent();
    if (!(parent instanceof VBox vbox)) {
      return;
    }
    int index = vbox.getChildren().indexOf(mainSplit);
    if (index < 0) {
      return;
    }

    StackPane host = new StackPane();
    host.setMinSize(0, 0);
    VBox.setVgrow(host, Priority.ALWAYS);
    vbox.getChildren().set(index, host);
    host.getChildren().add(mainSplit);

    Canvas glass = new Canvas();
    glass.setManaged(false);
    glass.setMouseTransparent(true);
    glass.widthProperty().bind(host.widthProperty());
    glass.heightProperty().bind(host.heightProperty());
    host.getChildren().add(glass);

    glassHost = host;
    glassCanvas = glass;
    glassGc = glass.getGraphicsContext2D();
    glassOwner = null;
    host.widthProperty().addListener((obs, o, n) -> clearGlassPixels());
    host.heightProperty().addListener((obs, o, n) -> clearGlassPixels());
  }

  /** Host StackPane that contains {@code mainSplit} and the zoom glass canvas. */
  public static StackPane getGlassHost() {
    return glassHost;
  }

  public static void clearGlass(Object owner) {
    if (owner != null && owner == glassOwner) {
      glassOwner = null;
      clearGlassPixels();
    }
  }

  private static void clearGlassPixels() {
    if (glassCanvas == null || glassGc == null) {
      return;
    }
    glassGc.clearRect(0, 0, glassCanvas.getWidth(), glassCanvas.getHeight());
  }

  public static boolean drawCurtain(
      Object owner,
      Node sourceNode,
      double localX1,
      double localX2,
      List<? extends Node> targets,
      String spanLabel) {
    if (glassCanvas == null || glassGc == null || sourceNode == null) {
      return false;
    }
    glassOwner = owner;
    clearGlassPixels();

    // X comes only from the gesture source — target canvases can have different
    // widths/offsets, so reusing source localX in each node space misplaces rects.
    Point2D leftSrc = toGlass(sourceNode, localX1, 0);
    Point2D rightSrc = toGlass(sourceNode, localX2, 0);
    if (leftSrc == null || rightSrc == null) {
      return false;
    }
    double x = Math.min(leftSrc.getX(), rightSrc.getX());
    double w = Math.abs(rightSrc.getX() - leftSrc.getX());
    if (w < 0.5) {
      return false;
    }

    List<? extends Node> canvases = (targets == null || targets.isEmpty())
        ? List.of(sourceNode)
        : targets;

    double minY = Double.POSITIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    for (Node node : canvases) {
      if (node == null) {
        continue;
      }
      double height = node instanceof Canvas c ? c.getHeight() : 0;
      Point2D top = toGlass(node, 0, 0);
      Point2D bottom = toGlass(node, 0, height);
      if (top == null || bottom == null) {
        continue;
      }
      minY = Math.min(minY, Math.min(top.getY(), bottom.getY()));
      maxY = Math.max(maxY, Math.max(top.getY(), bottom.getY()));
    }
    if (!(minY < maxY)) {
      return false;
    }
    double h = maxY - minY;
    if (h < 0.5) {
      return false;
    }

    glassGc.setFill(DrawColors.ZOOM_GRADIENT);
    glassGc.setStroke(Color.DODGERBLUE);
    glassGc.fillRect(x, minY, w, h);
    glassGc.strokeRect(x, minY, w, h);

    if (spanLabel != null && !spanLabel.isBlank()) {
      double labelW = spanLabel.length() * 7.2 + 12;
      double labelH = 18;
      double lx = x + w * 0.5 - labelW * 0.5;
      if (lx < 4) {
        lx = 4;
      }
      if (lx + labelW > glassCanvas.getWidth() - 4) {
        lx = glassCanvas.getWidth() - labelW - 4;
      }
      double labelY = minY + 6;
      glassGc.setFill(Color.rgb(20, 20, 26, 0.82));
      glassGc.fillRoundRect(lx, labelY, labelW, labelH, 6, 6);
      glassGc.setStroke(Color.rgb(220, 230, 255, 0.85));
      glassGc.strokeRoundRect(lx + 0.5, labelY + 0.5, labelW - 1, labelH - 1, 6, 6);
      glassGc.setFill(Color.rgb(242, 246, 255, 0.98));
      glassGc.setFont(Font.font("Segoe UI", 11));
      glassGc.fillText(spanLabel, lx + 6, labelY + 12.5);
    }
    return true;
  }

  public static boolean drawLine(
      Object owner, Node sourceNode, double x1, double y1, double x2, double y2) {
    if (glassCanvas == null || glassGc == null || sourceNode == null) {
      return false;
    }
    Point2D a = toGlass(sourceNode, x1, y1);
    Point2D b = toGlass(sourceNode, x2, y2);
    if (a == null || b == null) {
      return false;
    }
    glassOwner = owner;
    clearGlassPixels();
    glassGc.setStroke(Color.DODGERBLUE);
    glassGc.setLineWidth(1.2);
    glassGc.strokeLine(a.getX(), a.getY(), b.getX(), b.getY());
    return true;
  }

  private static Point2D toGlass(Node node, double localX, double localY) {
    Point2D scene = node.localToScene(localX, localY);
    if (scene == null || glassCanvas == null) {
      return null;
    }
    return glassCanvas.sceneToLocal(scene);
  }

  // ── Per-canvas gesture / animation ───────────────────────────────────────

  boolean isOverlayReserved() {
    DrawStack stack = canvas.drawStack;
    return zoomDrag || lineZoomer
        || stack.nav.lineZoomerActive
        || stack.nav.animationRunning;
  }

  boolean isZoomDrag() {
    return zoomDrag;
  }

  boolean isLineZoomer() {
    return lineZoomer;
  }

  /**
   * Primary-button zoom drag (curtain while dragging right; line-zoom while left).
   */
  void onPrimaryDrag(double dragX, double dragY, double mousePressedX, double mousePressedY,
                     double previousDraggedX) {
    zoomDrag = true;
    double mouseDragDeltaX = dragX - previousDraggedX;
    double totalDragPixels = Math.abs(dragX - mousePressedX);
    DrawStack stack = canvas.drawStack;

    if (!lineZoomer && dragX >= mousePressedX) {
      long spanBp = Math.max(1L, Math.round((dragX - mousePressedX) * stack.scale));
      String spanLabel = BaseUtils.formatNumber(spanBp) + " bp";
      drawCurtain(canvas, canvas, mousePressedX, dragX, curtainTargets(stack), spanLabel);
      return;
    }

    if (totalDragPixels < MIN_ZOOM_DRAG_PIXELS) {
      zoomDrag = false;
      clearGlass(canvas);
      return;
    }

    zoomDrag = false;
    lineZoomer = true;
    stack.nav.lineZoomerActive = true;
    drawLine(canvas, canvas, mousePressedX, mousePressedY, dragX, dragY);
    zoom(mouseDragDeltaX, mousePressedX);
  }

  /**
   * Primary-button release after zoom drag / line zoom.
   *
   * @return true if a zoom commit was handled (caller should not treat as a plain click)
   */
  boolean onPrimaryRelease(double mousePressedX, double mouseDraggedX,
                           java.util.function.Function<Double, Integer> screenPosToChromPos) {
    clearGlass(canvas);
    DrawStack stack = canvas.drawStack;

    if (lineZoomer) {
      lineZoomer = false;
      stack.nav.lineZoomerActive = false;
      stack.nav.navigating = false;
      return true;
    }

    if (!zoomDrag) {
      return false;
    }
    zoomDrag = false;
    if (Math.abs(mouseDraggedX - mousePressedX) < MIN_ZOOM_DRAG_PIXELS) {
      return true;
    }
    if (mousePressedX > mouseDraggedX) {
      return true;
    }
    double start = screenPosToChromPos.apply(mousePressedX);
    double end = screenPosToChromPos.apply(mouseDraggedX);
    zoomAnimation(start, end);
    return true;
  }

  void zoom(double zoomDirection, double targetX) {
    if (zoomDirection == 0.0) {
      return;
    }
    DrawStack stack = canvas.drawStack;
    int direction = zoomDirection > 0 ? 1 : -1;
    double pivot = targetX / canvas.getWidth();
    double acceleration = stack.viewLength / canvas.getWidth() * 10;
    double newSize = stack.viewLength - GenomicCanvas.zoomFactor * acceleration * direction;
    if (newSize < GenomicCanvas.minZoom) {
      return;
    }
    double genomicAtCursor = stack.start + targetX * stack.scale;
    double start = Math.max(1, genomicAtCursor - (pivot * newSize));
    double end = Math.min(stack.chromSize + 1, start + newSize);
    if (stack.start == start && stack.end == end) {
      return;
    }
    canvas.setStartEnd(start, end);
  }

  void zoomAnimation(double start, double end) {
    if (end - start < GenomicCanvas.minZoom) {
      return;
    }
    DrawStack stack = canvas.drawStack;
    if (shouldSkipZoomAnimation(start, end)) {
      cancelZoomAnimation(true);
      canvas.setStartEnd(start, end);
      return;
    }

    cancelZoomAnimation(true);
    pendingZoomStart = start;
    pendingZoomEnd = end;

    double overlapStart = Math.max(start, stack.start);
    double overlapEnd = Math.min(end, stack.end);
    double overlapSize = Math.max(0, overlapEnd - overlapStart);
    double currentSize = stack.end - stack.start;
    if (overlapSize / currentSize < 0.3) {
      FetchManager.get().cancelAll();
    }

    final double sourceStart = stack.start;
    final double sourceEnd = stack.end;
    final List<GenomicCanvas> previewCanvases = previewTargets(stack, canvas);
    final Map<GenomicCanvas, Image> snapshots = new IdentityHashMap<>();
    for (GenomicCanvas target : previewCanvases) {
      Image snap = target.snapshot(null, null);
      if (snap != null) {
        snapshots.put(target, snap);
      }
    }

    if (snapshots.isEmpty()) {
      stack.nav.animationRunning = false;
      stack.nav.navigating = false;
      canvas.setStartEnd(start, end);
      pendingZoomStart = Double.NaN;
      pendingZoomEnd = Double.NaN;
      return;
    }

    stack.nav.animationRunning = true;
    stack.nav.navigating = true;
    final long[] startNanos = { -1L };

    zoomPreviewTimer = new AnimationTimer() {
      @Override
      public void handle(long now) {
        if (startNanos[0] < 0) {
          startNanos[0] = now;
        }
        double t = Math.min(1.0, (now - startNanos[0]) / (double) ZOOM_ANIMATION_NANOS);
        double currentStart = sourceStart + (start - sourceStart) * t;
        double currentEnd = sourceEnd + (end - sourceEnd) * t;
        for (Map.Entry<GenomicCanvas, Image> entry : snapshots.entrySet()) {
          paintPreview(entry.getKey(), entry.getValue(),
              sourceStart, sourceEnd, currentStart, currentEnd);
        }
        if (t >= 1.0) {
          stop();
          zoomPreviewTimer = null;
          for (GenomicCanvas target : snapshots.keySet()) {
            target.clearReactive();
          }
          stack.nav.animationRunning = false;
          stack.nav.navigating = false;
          canvas.setStartEnd(start, end);
          pendingZoomStart = Double.NaN;
          pendingZoomEnd = Double.NaN;
        }
      }
    };
    zoomPreviewTimer.start();
  }

  private boolean shouldSkipZoomAnimation(double targetStart, double targetEnd) {
    DrawStack stack = canvas.drawStack;
    double currentView = Math.max(GenomicCanvas.minZoom, stack.end - stack.start);
    double targetView = Math.max(GenomicCanvas.minZoom, targetEnd - targetStart);
    boolean zoomingIn = targetView < currentView;
    double closeZoomThreshold = GenomicCanvas.minZoom * CLOSE_ZOOM_NO_ANIMATION_FACTOR;
    return zoomingIn && (currentView <= closeZoomThreshold || targetView <= closeZoomThreshold);
  }

  void cancelZoomAnimation(boolean snapToPendingTarget) {
    if (zoomPreviewTimer != null) {
      zoomPreviewTimer.stop();
      zoomPreviewTimer = null;
    }

    DrawStack stack = canvas.drawStack;
    boolean hadPreview = stack.nav.animationRunning;
    for (GenomicCanvas target : previewTargets(stack, canvas)) {
      target.clearReactive();
    }
    if (hadPreview) {
      stack.nav.animationRunning = false;
      stack.nav.navigating = false;
    }

    if (snapToPendingTarget
        && !Double.isNaN(pendingZoomStart)
        && !Double.isNaN(pendingZoomEnd)) {
      canvas.setStartEnd(pendingZoomStart, pendingZoomEnd);
    }
    pendingZoomStart = Double.NaN;
    pendingZoomEnd = Double.NaN;
  }

  private static void paintPreview(
      GenomicCanvas target, Image snapshot,
      double sourceStart, double sourceEnd,
      double currentStart, double currentEnd) {
    if (snapshot == null) {
      return;
    }
    double sourceView = sourceEnd - sourceStart;
    double currentView = Math.max(GenomicCanvas.minZoom, currentEnd - currentStart);
    double scaleX = sourceView / currentView;
    double translateX = (sourceStart - currentStart) * (target.getWidth() / currentView);
    GraphicsContext gc = target.reactiveGc;
    gc.setFill(DrawColors.BACKGROUND);
    gc.fillRect(0, 0, target.getWidth(), target.getHeight());
    gc.drawImage(snapshot, translateX, 0, target.getWidth() * scaleX, target.getHeight());
  }

  private static List<GenomicCanvas> previewTargets(DrawStack stack, GenomicCanvas initiator) {
    LinkedHashSet<GenomicCanvas> set = new LinkedHashSet<>();
    if (initiator != null) {
      set.add(initiator);
    }
    if (stack.sampleTrackCanvas != null) {
      set.add(stack.sampleTrackCanvas);
    }
    if (stack.sampleAggregateCanvas != null) {
      set.add(stack.sampleAggregateCanvas);
    }
    if (stack.chromosomeCanvas != null) {
      set.add(stack.chromosomeCanvas);
    }
    if (stack.featureAggregateCanvas != null) {
      set.add(stack.featureAggregateCanvas);
    }
    if (stack.featureTrackCanvas != null) {
      set.add(stack.featureTrackCanvas);
    }
    return new ArrayList<>(set);
  }

  /** Body canvases only — skip aggregates so curtains are not doubled. */
  private static List<GenomicCanvas> curtainTargets(DrawStack stack) {
    LinkedHashSet<GenomicCanvas> set = new LinkedHashSet<>();
    if (stack.chromosomeCanvas != null) {
      set.add(stack.chromosomeCanvas);
    }
    if (stack.featureTrackCanvas != null) {
      set.add(stack.featureTrackCanvas);
    }
    if (stack.sampleTrackCanvas != null) {
      set.add(stack.sampleTrackCanvas);
    }
    return new ArrayList<>(set);
  }
}
