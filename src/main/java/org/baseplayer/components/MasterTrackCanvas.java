package org.baseplayer.components;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.components.sidebars.SidebarBase;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.BAMRecord;
import org.baseplayer.samples.alignment.draw.CircosPlot;
import org.baseplayer.samples.alignment.draw.ReadColorMode;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Canvas for the "master track" header in the samples sidebar.
 *
 * <p>Extends {@link GenomicCanvas} for shared stack-aware navigation helpers,
 * but drag gestures are intentionally disabled in this header area so button
 * interactions (⚙ / +) and resize-handle interactions are predictable.
 * Scroll-wheel navigation is still supported.</p>
 *
 * <p>The bottom edge can be dragged vertically to resize the master track area.</p>
 */
public class MasterTrackCanvas extends GenomicCanvas {

  private final DrawStackManager stackManager;
  private final ContextMenu addDataMenu;

  // Resize-drag state
  private boolean isDraggingResize = false;
  private double dragStartScreenY = 0;
  private double dragStartHeight = 0;

  // Header button hover state (matches SidebarBase behavior)
  private boolean settingsHovered = false;
  private boolean addHovered      = false;
  private boolean reloadHovered   = false;
  private boolean expandHovered   = false;
  private boolean draggingRangeStart = false;
  private boolean draggingRangeEnd = false;
  private boolean pendingSingleHandleResolve = false;
  private int pendingSingleHandleAnchor = -1;

  private HitBox rangeStartHandleHit = null;
  private HitBox rangeEndHandleHit = null;
  private HitBox rangeLabelHit = null;
  private ContextMenu rangeInputMenu = null;

  private static final double EXPANDED_MASTER_HEIGHT = 82;

  private static final double HEADER_BTN_SIZE = 18;
  private static final double HEADER_BTN_LEFT_X = 4;

  private record HitBox(double x, double y, double w, double h) {
    private boolean contains(double px, double py) {
      return px >= x && px <= x + w && py >= y && py <= y + h;
    }
  }

  public MasterTrackCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack initialDrawStack) {
    super(reactiveCanvas, parent, initialDrawStack);
    this.stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    this.addDataMenu = createAddDataMenu();

    // Keep header controls anchored correctly on live resize.
    widthProperty().addListener((obs, oldVal, newVal) -> draw());
    heightProperty().addListener((obs, oldVal, newVal) -> {
      draw();
      drawHeaderHover();
    });
    reactiveCanvas.widthProperty().addListener((obs, oldVal, newVal) -> drawHeaderHover());
    reactiveCanvas.heightProperty().addListener((obs, oldVal, newVal) -> drawHeaderHover());

    setupMasterHandlers(reactiveCanvas);
  }

  // ── Navigation helpers ────────────────────────────────────────────────────

  /**
   * Update {@link #drawStack} to whichever stack is currently hovered or, if
   * none, the first available stack.  Called before every navigation operation
   * so that pan/zoom targets the correct stack.
   */
  private void syncDrawStack() {
    DrawStack hover = stackManager.getHoverStack();
    if (hover != null) {
      drawStack = hover;
    } else if (!stackManager.isEmpty()) {
      drawStack = stackManager.getFirst();
    }
  }

  // ── Event handler setup ───────────────────────────────────────────────────

  private void setupMasterHandlers(Canvas reactiveCanvas) {
    // Don't change the hover stack when mouse enters the sidebar header
    reactiveCanvas.setOnMouseEntered(event -> update.set(!update.get()));

    reactiveCanvas.setOnMouseMoved(event -> {
      double edgeZone = getHeight() - 4;
      boolean inResizeZone = event.getY() >= edgeZone;
      boolean overRangeHandle = isControlsExpanded() && isOverRangeHandle(event.getX(), event.getY());
      reactiveCanvas.setCursor(inResizeZone ? Cursor.V_RESIZE : (overRangeHandle ? Cursor.H_RESIZE : Cursor.DEFAULT));
      double headerBarH = getHeaderBarHeight();

      boolean prevSettings = settingsHovered;
      boolean prevAdd = addHovered;
      boolean prevReload = reloadHovered;
      boolean prevExpand = expandHovered;

      if (inResizeZone) {
        settingsHovered = false;
        addHovered = false;
        reloadHovered = false;
        expandHovered = false;
      } else if (event.getY() <= headerBarH) {
        double sy = (headerBarH - HEADER_BTN_SIZE) / 2;
        settingsHovered = inHeaderBtn(event.getX(), event.getY(), HEADER_BTN_LEFT_X, sy);
        reloadHovered = hasAnySuspended() &&
            inHeaderBtn(event.getX(), event.getY(), reloadBtnX(getWidth()), sy);
        expandHovered = sampleRegistry.getSampleTracks().size() > 1
            && inHeaderBtn(event.getX(), event.getY(), expandBtnX(getWidth()), sy);
        addHovered = inHeaderBtn(event.getX(), event.getY(), getWidth() - HEADER_BTN_SIZE - 4, sy);
      } else {
        settingsHovered = false;
        addHovered = false;
        reloadHovered = false;
        expandHovered = false;
      }

      if (prevSettings != settingsHovered
          || prevAdd != addHovered
          || prevReload != reloadHovered
          || prevExpand != expandHovered) {
        drawHeaderHover();
      }
    });

    reactiveCanvas.setOnMouseExited(event -> {
      reactiveCanvas.setCursor(Cursor.DEFAULT);
      if (settingsHovered || addHovered || reloadHovered || expandHovered) {
        settingsHovered = false;
        addHovered = false;
        reloadHovered = false;
        expandHovered = false;
        drawHeaderHover();
      }
    });

    reactiveCanvas.setOnMousePressed(event -> {
      if (event.getY() >= getHeight() - 4) {
        isDraggingResize = true;
        dragStartScreenY = event.getScreenY();
        dragStartHeight = sampleRegistry.getMasterTrackHeight();
      } else if (isControlsExpanded() && beginRangeHandleDrag(event.getX(), event.getY())) {
        mouseDragged = false;
      } else {
        // Header body: clicks are allowed, drag-navigation is intentionally disabled.
        mousePressedX = event.getX();
        mousePressedY = event.getY();
        mouseDraggedX = 0;
        mouseDragged = false;
      }
    });

    reactiveCanvas.setOnMouseDragged(event -> {
      if (isDraggingResize) {
        double delta = event.getScreenY() - dragStartScreenY;
        sampleRegistry.setMasterTrackHeight(Math.max(20, Math.min(200, dragStartHeight + delta)));
        update.set(!update.get());
      } else if (draggingRangeStart || draggingRangeEnd || pendingSingleHandleResolve) {
        updateRangeFromHandleDrag(event.getX());
        mouseDragged = true;
      } else {
        // Suppress click after drag movement, but do not pan/zoom from header body.
        mouseDragged = true;
      }
    });

    reactiveCanvas.setOnMouseReleased(event -> {
      if (isDraggingResize) {
        isDraggingResize = false;
        reactiveCanvas.setCursor(Cursor.DEFAULT);
      }
      draggingRangeStart = false;
      draggingRangeEnd = false;
      pendingSingleHandleResolve = false;
      pendingSingleHandleAnchor = -1;
    });

    reactiveCanvas.setOnScroll(event -> {
      syncDrawStack();
      drawStack.nav.navigating = true;
      handlePanScroll(event);
    });

    reactiveCanvas.setOnMouseClicked(event -> {
      if (!mouseDragged) {
        handleMasterClick(event.getX(), event.getY(), event.getScreenX(), event.getScreenY());
      }
    });
  }

  /**
   * Scroll handler that maps both vertical (deltaY) and horizontal (deltaX)
   * scroll to horizontal genomic panning, and Ctrl+scroll to zoom.
   */
  private void handlePanScroll(ScrollEvent event) {
    event.consume();
    if (event.isControlDown()) {
      zoom(event.getDeltaY(), event.getX());
    } else {
      double delta = event.getDeltaX() != 0 ? event.getDeltaX() : event.getDeltaY();
      double genomeDelta = delta * 0.3 * drawStack.scale;
      setStart(drawStack.start - genomeDelta);
    }
  }

  // ── Rendering ─────────────────────────────────────────────────────────────

  @Override
	public void draw() {
    double w = getWidth();
    double h = getHeight();
    if (w <= 0 || h <= 0) return;

    int trackCount = sampleRegistry.getDisplayedTrackCount();

    if (trackCount > 1 && !isControlsExpanded()) {
      sampleRegistry.setMasterTrackHeight(EXPANDED_MASTER_HEIGHT);
      h = getHeight();
    }

    GraphicsContext gc = getGraphicsContext2D();
    double headerBarH = getHeaderBarHeight();
    SidebarBase.drawStandardHeader(gc, w, headerBarH, "Tracks", trackCount);
    double sy = (headerBarH - HEADER_BTN_SIZE) / 2;

    // Reload (↺) button — shown when any sample has suspended reads
    if (hasAnySuspended()) {
      double reloadX = reloadBtnX(w);
      gc.setFont(Font.font("Segoe UI Symbol", 14));
      gc.setFill(Color.web("#ff9944"));
      gc.fillText("\u21ba", reloadX + 1, sy + HEADER_BTN_SIZE - 3);
    }

    if (trackCount > 1) {
      double ex = expandBtnX(w);
      gc.setFill(Color.web("#3c3c3c"));
      gc.fillRoundRect(ex, sy, HEADER_BTN_SIZE, HEADER_BTN_SIZE, 4, 4);
      gc.setStroke(Color.web("#555555"));
      gc.strokeRoundRect(ex, sy, HEADER_BTN_SIZE, HEADER_BTN_SIZE, 4, 4);
      gc.setFont(Font.font("Segoe UI Symbol", 12));
      gc.setFill(Color.web("#cccccc"));
      gc.fillText(isControlsExpanded() ? "▾" : "▸", ex + 4, sy + HEADER_BTN_SIZE - 5);
    }

    if (isControlsExpanded()) {
      drawExpandedControls(gc, w, h, headerBarH);
    } else {
      rangeStartHandleHit = null;
      rangeEndHandleHit = null;
      rangeLabelHit = null;
    }

    drawHeaderHover();
  }

  private void drawExpandedControls(GraphicsContext gc, double w, double h, double headerBarH) {
    if (h <= headerBarH + 2) {
      rangeStartHandleHit = null;
      rangeEndHandleHit = null;
      rangeLabelHit = null;
      return;
    }

    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      rangeStartHandleHit = null;
      rangeEndHandleHit = null;
      rangeLabelHit = null;
      return;
    }

    int first = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
    int last = Math.max(first, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));

    gc.setFill(Color.web("#202327"));
    gc.fillRect(0, headerBarH, w, h - headerBarH);
    gc.setStroke(Color.web("#3e444d"));
    gc.strokeLine(0, headerBarH, w, headerBarH);

    gc.setFont(Font.font("Segoe UI", 10));
    gc.setFill(Color.web("#9ea7b3"));
    gc.fillText("Visible samples", 8, headerBarH + 14);
    gc.setFill(Color.web("#7f8791"));
    String label;
    if (sampleRegistry.hasActiveSampleFilterQuery()) {
      label = "Filter";
    } else {
      String rangeText = first == last
          ? String.valueOf(first + 1)
          : (first + 1) + "-" + (last + 1);
      label = rangeText + " / " + trackCount;
    }
    double labelX = 96;
    double labelY = headerBarH + 14;
    gc.fillText(label, labelX, labelY);
    rangeLabelHit = new HitBox(labelX - 4, headerBarH + 3, Math.max(48, label.length() * 7.0), 14);

    double railX = 12;
    double railW = Math.max(10, w - 24);
    double railY = headerBarH + 29;
    double railH = 8;

    gc.setFill(Color.web("#2f353e"));
    gc.fillRoundRect(railX, railY, railW, railH, 4, 4);

    double startX = railX + (trackCount <= 1 ? 0 : (first / (double) (trackCount - 1)) * railW);
    double endX = railX + (trackCount <= 1 ? railW : (last / (double) (trackCount - 1)) * railW);
    if (trackCount == 1) {
      startX = railX;
      endX = railX + railW;
    }

    gc.setFill(Color.web("#4b5f7f"));
    gc.fillRoundRect(startX, railY, Math.max(2, endX - startX), railH, 4, 4);

    double handleW = 8;
    double handleH = 16;
    double handleY = railY - 4;
    gc.setFill(Color.web("#d8e3f5"));
    gc.fillRoundRect(startX - handleW / 2, handleY, handleW, handleH, 3, 3);
    gc.fillRoundRect(endX - handleW / 2, handleY, handleW, handleH, 3, 3);

    rangeStartHandleHit = new HitBox(startX - handleW / 2 - 2, handleY - 2, handleW + 4, handleH + 4);
    rangeEndHandleHit = new HitBox(endX - handleW / 2 - 2, handleY - 2, handleW + 4, handleH + 4);

  }

  private void drawHeaderHover() {
    if (reactiveGc == null) return;
    double w = getReactiveCanvas().getWidth();
    double h = getReactiveCanvas().getHeight();
    reactiveGc.clearRect(0, 0, w, h);

    double sy = (getHeaderBarHeight() - HEADER_BTN_SIZE) / 2;
    if (settingsHovered) {
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
      reactiveGc.fillRoundRect(HEADER_BTN_LEFT_X, sy, HEADER_BTN_SIZE, HEADER_BTN_SIZE, 4, 4);
    }
    if (expandHovered && sampleRegistry.getSampleTracks().size() > 1) {
      double ex = expandBtnX(w);
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
      reactiveGc.fillRoundRect(ex, sy, HEADER_BTN_SIZE, HEADER_BTN_SIZE, 4, 4);
    }
    if (reloadHovered && hasAnySuspended()) {
      double rx = reloadBtnX(w);
      reactiveGc.setFill(Color.rgb(255, 165, 30, 0.25));
      reactiveGc.fillRoundRect(rx, sy, HEADER_BTN_SIZE, HEADER_BTN_SIZE, 4, 4);
    }
    if (addHovered) {
      double plusX = w - HEADER_BTN_SIZE - 4;
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
      reactiveGc.fillRoundRect(plusX, sy, HEADER_BTN_SIZE, HEADER_BTN_SIZE, 4, 4);
    }
  }

  // ── Click handling ────────────────────────────────────────────────────────

  private void handleMasterClick(double x, double y, double screenX, double screenY) {
    if (isControlsExpanded() && rangeLabelHit != null && rangeLabelHit.contains(x, y)) {
      showRangeInputMenu(screenX, screenY);
      return;
    }

    double sy = (getHeaderBarHeight() - HEADER_BTN_SIZE) / 2;

    // Settings (⚙)
    if (inHeaderBtn(x, y, HEADER_BTN_LEFT_X, sy)) {
      showGlobalSettingsMenu(screenX, screenY);
      return;
    }

    // Expand/collapse master controls
    if (sampleRegistry.getSampleTracks().size() > 1 && inHeaderBtn(x, y, expandBtnX(getWidth()), sy)) {
      sampleRegistry.setMasterTrackHeight(isControlsExpanded()
          ? SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT
          : EXPANDED_MASTER_HEIGHT);
      update.set(!update.get());
      return;
    }

    // Reload (↺) — resume all suspended samples
    if (hasAnySuspended() && inHeaderBtn(x, y, reloadBtnX(getWidth()), sy)) {
      for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
        for (Sample sample : strack.getSamples()) {
          if (sample.isSuspended()) {
            sample.resume();
            ThreadRunner.RunnerTask readTask =
                ThreadRunner.get().track("Loading reads: " + sample.getName(), sample::cancelAndSuspend);
            sample.setOnFirstLoadComplete(readTask::complete);
          }
        }
      }
      update.set(!update.get());
      return;
    }

    // Add (+)
    if (inHeaderBtn(x, y, getWidth() - HEADER_BTN_SIZE - 4, sy)) {
      addDataMenu.show(this, screenX, screenY);
    }
  }

  private double reloadBtnX(double canvasWidth) {
    return canvasWidth - 2 * (HEADER_BTN_SIZE + 4);
  }

  private double expandBtnX(double canvasWidth) {
    return hasAnySuspended()
        ? canvasWidth - 3 * (HEADER_BTN_SIZE + 4)
        : canvasWidth - 2 * (HEADER_BTN_SIZE + 4);
  }

  private double getHeaderBarHeight() {
    return Math.min(SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT, getHeight());
  }

  private boolean isControlsExpanded() {
    return sampleRegistry.getMasterTrackHeight() > SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT + 1;
  }

  private boolean isOverRangeHandle(double x, double y) {
    return (rangeStartHandleHit != null && rangeStartHandleHit.contains(x, y))
        || (rangeEndHandleHit != null && rangeEndHandleHit.contains(x, y));
  }

  private boolean beginRangeHandleDrag(double x, double y) {
    boolean startHit = rangeStartHandleHit != null && rangeStartHandleHit.contains(x, y);
    boolean endHit = rangeEndHandleHit != null && rangeEndHandleHit.contains(x, y);

    int trackCount = sampleRegistry.getDisplayedTrackCount();
    int first = trackCount <= 0 ? 0 : Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
    int last = trackCount <= 0 ? 0 : Math.max(first, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));

    if (startHit && endHit && first == last) {
      // Handles overlap in single-sample mode. Resolve intent on first drag direction:
      // right chooses end-handle, left chooses start-handle.
      draggingRangeStart = false;
      draggingRangeEnd = false;
      pendingSingleHandleResolve = true;
      pendingSingleHandleAnchor = first;
      return true;
    }

    pendingSingleHandleResolve = false;
    pendingSingleHandleAnchor = -1;

    if (startHit) {
      draggingRangeStart = true;
      draggingRangeEnd = false;
      return true;
    }
    if (endHit) {
      draggingRangeStart = false;
      draggingRangeEnd = true;
      return true;
    }
    return false;
  }

  private void updateRangeFromHandleDrag(double mouseX) {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) return;
    double railX = 12;
    double railW = Math.max(10, getWidth() - 24);

    int mapped = mapMouseXToSampleIndex(mouseX, railX, railW, trackCount);

    if (pendingSingleHandleResolve) {
      if (mapped > pendingSingleHandleAnchor) {
        draggingRangeStart = false;
        draggingRangeEnd = true;
        pendingSingleHandleResolve = false;
      } else if (mapped < pendingSingleHandleAnchor) {
        draggingRangeStart = true;
        draggingRangeEnd = false;
        pendingSingleHandleResolve = false;
      } else {
        return;
      }
    }

    int first = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
    int last = Math.max(first, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));
    if (draggingRangeStart) {
      first = Math.min(mapped, last);
    } else if (draggingRangeEnd) {
      last = Math.max(mapped, first);
    }

    applyVisibleRange(first, last);
  }

  private int mapMouseXToSampleIndex(double x, double railX, double railW, int trackCount) {
    if (trackCount <= 1) return 0;
    double t = (x - railX) / railW;
    t = Math.max(0.0, Math.min(1.0, t));
    return (int) Math.round(t * (trackCount - 1));
  }

  private void showRangeInputMenu(double screenX, double screenY) {
    if (rangeInputMenu != null && rangeInputMenu.isShowing()) {
      rangeInputMenu.hide();
    }

    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) return;

    int first = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
    int last = Math.max(first, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));

    ContextMenu menu = new ContextMenu();
    menu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    HBox row = new HBox(6);
    row.setPadding(new Insets(6));
    Label fromLabel = new Label("From");
    fromLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
    TextField fromField = new TextField(String.valueOf(first + 1));
    fromField.setPrefWidth(52);
    fromField.setStyle("-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
    Label toLabel = new Label("To");
    toLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
    TextField toField = new TextField(String.valueOf(last + 1));
    toField.setPrefWidth(52);
    toField.setStyle("-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");

    HBox filterRow = new HBox(6);
    filterRow.setPadding(new Insets(0, 6, 6, 6));
    Label filterLabel = new Label("Filter");
    filterLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
    TextField filterField = new TextField(sampleRegistry.getActiveSampleFilterQuery());
    filterField.setPromptText("sample name contains...");
    filterField.setPrefWidth(170);
    filterField.setStyle("-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");

    Button applyBtn = new Button("Apply");
    applyBtn.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #cccccc; -fx-font-size: 11;"
        + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");

    Button clearBtn = new Button("Clear");
    clearBtn.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #bbbbbb; -fx-font-size: 11;"
        + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");

    Runnable applyRange = () -> {
      int displayedCount = sampleRegistry.getDisplayedTrackCount();
      if (displayedCount <= 0) {
        return;
      }

      Integer parsedStart = parseOneBasedIndex(fromField.getText(), displayedCount);
      Integer parsedEnd = parseOneBasedIndex(toField.getText(), displayedCount);
      if (parsedStart == null || parsedEnd == null) {
        fromField.setText(String.valueOf(sampleRegistry.getFirstVisibleSample() + 1));
        toField.setText(String.valueOf(sampleRegistry.getLastVisibleSample() + 1));
        return;
      }

      int s = Math.min(parsedStart, parsedEnd);
      int e = Math.max(parsedStart, parsedEnd);
      s = Math.max(0, Math.min(displayedCount - 1, s));
      e = Math.max(s, Math.min(displayedCount - 1, e));
      applyVisibleRange(s, e);
      menu.hide();
    };

    Runnable applyFilterLive = () -> {
      String filterQuery = filterField.getText() == null ? "" : filterField.getText().trim();
      if (!filterQuery.isEmpty()) {
        sampleRegistry.setActiveSampleFilterQuery(filterQuery);
        int filteredCount = sampleRegistry.getDisplayedTrackCount();
        if (filteredCount > 0) {
          applyVisibleRange(0, filteredCount - 1);
        } else {
          applyVisibleRange(0, 0);
        }
        return;
      }

      int prevFirst = sampleRegistry.getFirstVisibleSample();
      int prevLast = sampleRegistry.getLastVisibleSample();
      sampleRegistry.clearActiveSampleFilterQuery();
      applyVisibleRange(prevFirst, prevLast);
    };

    applyBtn.setOnAction(e -> applyRange.run());
    fromField.setOnAction(e -> applyRange.run());
    toField.setOnAction(e -> applyRange.run());
    filterField.textProperty().addListener((obs, oldValue, newValue) -> applyFilterLive.run());
    filterField.setOnAction(e -> applyFilterLive.run());
    clearBtn.setOnAction(e -> {
      filterField.clear();
      applyFilterLive.run();
      menu.hide();
    });

    row.getChildren().addAll(fromLabel, fromField, toLabel, toField, applyBtn, clearBtn);
    filterRow.getChildren().addAll(filterLabel, filterField);
    VBox panel = new VBox(4, row, filterRow);
    menu.getItems().add(new CustomMenuItem(panel, false));
    menu.show(this, screenX, screenY);
    rangeInputMenu = menu;
    Platform.runLater(filterField::requestFocus);
  }

  private Integer parseOneBasedIndex(String value, int trackCount) {
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed < 1) parsed = 1;
      if (parsed > trackCount) parsed = trackCount;
      return parsed - 1;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void applyVisibleRange(int first, int last) {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      sampleRegistry.setFirstVisibleSample(0);
      sampleRegistry.setLastVisibleSample(0);
      sampleRegistry.setScrollBarPosition(0);
      update.set(!update.get());
      return;
    }

    int clampedFirst = Math.max(0, Math.min(trackCount - 1, first));
    int clampedLast = Math.max(clampedFirst, Math.min(trackCount - 1, last));
    sampleRegistry.setFirstVisibleSample(clampedFirst);
    sampleRegistry.setLastVisibleSample(clampedLast);

    double viewportHeight = estimateSampleViewportHeight();
    int visibleCount = clampedLast - clampedFirst + 1;
    if (viewportHeight > 0) {
      sampleRegistry.setSampleHeight(viewportHeight / Math.max(1, visibleCount));
      double targetScroll = clampedFirst * sampleRegistry.getSampleHeight();
      sampleRegistry.setScrollBarPosition(
          sampleRegistry.clampScrollBarPosition(targetScroll, viewportHeight));
    }

    update.set(!update.get());
  }

  private double estimateSampleViewportHeight() {
    double derived = sampleRegistry.getSampleHeight() * Math.max(1, sampleRegistry.getVisibleSampleCount());
    if (derived > 0) return derived;
    if (!stackManager.isEmpty() && stackManager.getFirst().alignmentCanvas != null) {
      double fromCanvas = stackManager.getFirst().alignmentCanvas.getHeight() - sampleRegistry.getMasterTrackHeight();
      if (fromCanvas > 0) return fromCanvas;
    }
    return 0;
  }

  private boolean hasAnySuspended() {
    for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
      for (Sample s : strack.getSamples()) {
        if (s.isSuspended()) return true;
      }
    }
    return false;
  }

  private boolean inHeaderBtn(double mx, double my, double bx, double by) {
    return mx >= bx && mx <= bx + HEADER_BTN_SIZE && my >= by && my <= by + HEADER_BTN_SIZE;
  }

  // ── Context menus ─────────────────────────────────────────────────────────

  private ContextMenu createAddDataMenu() {
    ContextMenu menu = new ContextMenu();

    MenuItem bamItem = new MenuItem("BAM/CRAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamFiles());

    MenuItem vcfItem = new MenuItem("VCF");
    vcfItem.setDisable(true);

    MenuItem bedItem = new MenuItem("BED");
    bedItem.setOnAction(e -> SampleDataManager.addBedSampleFile());

    MenuItem bigwigItem = new MenuItem("BigWig");
    bigwigItem.setOnAction(e -> SampleDataManager.addBigWigFile());

    menu.getItems().addAll(bamItem, vcfItem, bedItem, bigwigItem);
    return menu;
  }

  private void showGlobalSettingsMenu(double screenX, double screenY) {
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    Label titleLabel = new Label("Global Settings");
    titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 13; -fx-font-weight: bold; -fx-padding: 4 8 2 8;");
    settingsMenu.getItems().addAll(new CustomMenuItem(titleLabel, false), new SeparatorMenuItem());

    // Sampled coverage settings
    VBox sampledCoverageBox = new VBox(4);
    sampledCoverageBox.setPadding(new Insets(4, 8, 4, 8));

    CheckBox enableCoverageCb = new CheckBox("Enable sampled coverage");
    enableCoverageCb.setSelected(Settings.get().isEnableSampledCoverage());
    enableCoverageCb.getStyleClass().add("dark-checkbox");
    enableCoverageCb.setStyle("-fx-font-size: 12;");
    enableCoverageCb.selectedProperty().addListener((obs, o, n) -> {
      Settings.get().setEnableSampledCoverage(n);
      update.set(!update.get());
    });

    HBox samplePointsBox = new HBox(6);
    Label samplePointsLabel = new Label("Sample points:");
    samplePointsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
    TextField samplePointsField = new TextField(String.valueOf(Settings.get().getSampledCoveragePoints()));
    samplePointsField.setStyle(
        "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
    samplePointsField.setPrefWidth(80);
    Button refreshButton = new Button("Refresh");
    refreshButton.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #cccccc; -fx-font-size: 11; "
        + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");
    refreshButton.setOnAction(e -> {
      try {
        int value = Integer.parseInt(samplePointsField.getText());
        if (value > 0 && value <= 10000) {
          Settings.get().setSampledCoveragePoints(value);
          for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
            for (Sample s : strack.getSamples()) {
              if (s.getBamFile() != null) s.getBamFile().clearSampledCoverageCache();
            }
          }
          update.set(!update.get());
          settingsMenu.hide();
        }
      } catch (NumberFormatException ex) {
        samplePointsField.setText(String.valueOf(Settings.get().getSampledCoveragePoints()));
      }
    });
    samplePointsBox.getChildren().addAll(samplePointsLabel, samplePointsField, refreshButton);
    sampledCoverageBox.getChildren().addAll(enableCoverageCb, samplePointsBox);
    settingsMenu.getItems().add(new CustomMenuItem(sampledCoverageBox, false));

    // Gather BAM files once for global controls.
    List<AlignmentFile> bamFiles = new ArrayList<>();
    for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
      for (Sample s : strack.getSamples()) {
        if (s.getBamFile() != null) bamFiles.add(s.getBamFile());
      }
    }

    // Mismatch filtering controls (apply to all BAM files)
    if (!bamFiles.isEmpty()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      VBox mismatchBox = new VBox(4);
      mismatchBox.setPadding(new Insets(4, 8, 4, 8));

      Label mismatchLabel = new Label("Mismatch filtering (all tracks)");
      mismatchLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-font-weight: bold;");

      CheckBox suppressMethylCb = new CheckBox("Hide bisulfite mismatches (C→T / G→A)");
      boolean anyMethylSuppressed = bamFiles.stream().anyMatch(AlignmentFile::isMethylationData);
      suppressMethylCb.setSelected(anyMethylSuppressed);
      suppressMethylCb.getStyleClass().add("dark-checkbox");
      suppressMethylCb.setStyle("-fx-font-size: 11;");
      suppressMethylCb.selectedProperty().addListener((obs, o, n) -> {
        for (AlignmentFile bam : bamFiles) {
          bam.setSuppressMethylMismatches(n);
        }
        update.set(!update.get());
      });

      HBox mmFractionRow = new HBox(6);
      Label mmFractionLabel = new Label("Min fraction (0–1):");
      mmFractionLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      TextField mmFractionField = new TextField(String.valueOf(Settings.get().getMismatchMinFraction()));
      mmFractionField.setStyle(
          "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
      mmFractionField.setPrefWidth(80);
      mmFractionRow.getChildren().addAll(mmFractionLabel, mmFractionField);

      HBox mmCountRow = new HBox(6);
      Label mmCountLabel = new Label("Min read count:");
      mmCountLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      TextField mmCountField = new TextField(String.valueOf(Settings.get().getMismatchMinCount()));
      mmCountField.setStyle(
          "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
      mmCountField.setPrefWidth(80);
      mmCountRow.getChildren().addAll(mmCountLabel, mmCountField);

      Button applyMismatchButton = new Button("Apply mismatch thresholds");
      applyMismatchButton.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #cccccc; -fx-font-size: 11; "
          + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");
      Runnable applyMismatchThresholds = () -> {
        try {
          double frac = Double.parseDouble(mmFractionField.getText());
          int count = Integer.parseInt(mmCountField.getText());
          frac = Math.max(0.0, Math.min(1.0, frac));
          count = Math.max(1, Math.min(100, count));
          Settings.get().setMismatchMinFraction(frac);
          Settings.get().setMismatchMinCount(count);
          mmFractionField.setText(String.valueOf(frac));
          mmCountField.setText(String.valueOf(count));
          update.set(!update.get());
        } catch (NumberFormatException ex) {
          mmFractionField.setText(String.valueOf(Settings.get().getMismatchMinFraction()));
          mmCountField.setText(String.valueOf(Settings.get().getMismatchMinCount()));
        }
      };
      applyMismatchButton.setOnAction(e -> applyMismatchThresholds.run());
      mmFractionField.setOnAction(e -> applyMismatchThresholds.run());
      mmCountField.setOnAction(e -> applyMismatchThresholds.run());

      Label mismatchInfo = new Label("Use for emSeq/WGBS to suppress bisulfite-conversion mismatches.");
      mismatchInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");

      mismatchBox.getChildren().addAll(mismatchLabel, suppressMethylCb, mmFractionRow, mmCountRow,
          applyMismatchButton, mismatchInfo);
      settingsMenu.getItems().add(new CustomMenuItem(mismatchBox, false));
    }

    // Read coloring + stacking controls (apply to all BAM files)
    if (!bamFiles.isEmpty()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      VBox readRenderBox = new VBox(6);
      readRenderBox.setPadding(new Insets(4, 8, 4, 8));

      Label readRenderLabel = new Label("Read rendering (all tracks)");
      readRenderLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-font-weight: bold;");

      HBox colorRow = new HBox(6);
      Label colorLabel = new Label("Read color:");
      colorLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      ComboBox<ReadColorMode> colorCombo = new ComboBox<>();
      // Use available modes from first BAM (filters out modes for missing tags)
      colorCombo.getItems().setAll(bamFiles.get(0).getAvailableColorModes());
      colorCombo.setValue(bamFiles.get(0).getReadColorMode());
      colorCombo.setPrefWidth(200);
      styleDarkComboBox(colorCombo, settingsMenu);
      colorCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) return;
        for (AlignmentFile bam : bamFiles) {
          bam.setReadColorMode(newMode);
        }
        update.set(!update.get());
      });
      colorRow.getChildren().addAll(colorLabel, colorCombo);

      HBox stackRow = new HBox(6);
      Label stackLabel = new Label("Stacking:");
      stackLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      ComboBox<AlignmentFile.ReadStackingMode> stackCombo = new ComboBox<>();
      stackCombo.getItems().setAll(AlignmentFile.ReadStackingMode.values());
      stackCombo.setValue(bamFiles.get(0).getReadStackingMode());
      stackCombo.setPrefWidth(200);
      styleDarkComboBox(stackCombo, settingsMenu);
      stackCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) return;
        for (AlignmentFile bam : bamFiles) {
          bam.setReadStackingMode(newMode);
        }
        update.set(!update.get());
      });
      stackRow.getChildren().addAll(stackLabel, stackCombo);

      Label stackInfo = new Label("Stacking modes are mutually exclusive.");
      stackInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");
      readRenderBox.getChildren().addAll(readRenderLabel, colorRow, stackRow, stackInfo);
      settingsMenu.getItems().add(new CustomMenuItem(readRenderBox, false));
    }

    // Circos plot of split reads & inter-chromosomal discordant pairs
    settingsMenu.getItems().add(new SeparatorMenuItem());
    MenuItem circosItem = new MenuItem("Circos plot (split reads + discordant pairs)\u2026");
    circosItem.setStyle("-fx-text-fill: #cccccc;");
    circosItem.setOnAction(e -> {
      settingsMenu.hide();
      openCircosPlot();
    });
    settingsMenu.getItems().add(circosItem);

    settingsMenu.show(getScene().getWindow(), screenX, screenY);
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
    comboBox.setOnHidden(e -> Platform.runLater(() -> parentMenu.setAutoHide(true)));
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

  /**
   * Collect split-read (SA tag) and inter-chromosomal discordant-pair links
   * across every visible sample and open a {@link CircosPlot} window. Uses
   * cached reads when available; otherwise sweeps the BAM/CRAM at the current
   * locus via a streaming query (no caching).
   */
  private void openCircosPlot() {
    // Snapshot everything the background sweep needs (JFX-thread only data)
    final List<DrawStack> stacks = new ArrayList<>(stackManager.getStacks());
    final List<SampleSweep> sweeps = new ArrayList<>();
    for (SampleTrack track : sampleRegistry.getSampleTracks()) {
      for (Sample sample : track.getSamples()) {
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) continue;
        sweeps.add(new SampleSweep(bamFile, sample.getName(),
            bamFile.getReader().getRefNames()));
      }
    }

    // Resolve chromosome names + lengths (cheap, on JFX thread)
    final List<String> chromNames;
    final Map<String, Long> chromLengths = new LinkedHashMap<>();
    ReferenceGenomeService refSvc = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (refSvc != null && refSvc.getCurrentGenome() != null) {
      chromNames = refSvc.getCurrentGenome().getStandardChromosomeNames();
      for (String n : chromNames) {
        try { chromLengths.put(n, refSvc.getChromosomeLength(n)); }
        catch (Exception ignore) { }
      }
    } else {
      chromNames = new ArrayList<>();
    }
    final String currentChrom = drawStack != null ? drawStack.chromosome : null;

    // Heavy work on a background thread: cache hits are iterated directly;
    // misses stream reads from disk at the current locus.
    Thread t = new Thread(() -> {
      List<CircosPlot.Link> links = new ArrayList<>();
      for (SampleSweep sw : sweeps) {
        for (DrawStack stack : stacks) {
          String stackChrom = stack.chromosome;
          if (stackChrom == null) continue;

          List<BAMRecord> cached = sw.file.getCachedReads(stack);
          if (cached != null && !cached.isEmpty()) {
            for (BAMRecord r : cached) {
              extractLinks(r, stackChrom, sw.refNames, sw.name, links);
            }
          } else {
            int start = (int) Math.max(0, stack.start);
            int end   = (int) Math.max(start + 1, stack.end);
            try {
              sw.file.getReader().queryStreaming(stackChrom, start, end, r -> {
                extractLinks(r, stackChrom, sw.refNames, sw.name, links);
                return true;
              });
            } catch (java.io.IOException ignore) { }
          }
        }
      }
      Platform.runLater(() -> {
        CircosPlot plot = new CircosPlot(
            links, chromNames, chromLengths, currentChrom,
            (chr, pos) -> Platform.runLater(() -> MainController.addStackAtPosition(chr, pos)));
        plot.show();
      });
    }, "circos-sweep");
    t.setDaemon(true);
    t.start();
  }

  /** Extract split-read (SA) and inter-chromosomal discordant-pair links from one record. */
  private static void extractLinks(BAMRecord r, String stackChrom, String[] refNames,
                                   String sampleName, List<CircosPlot.Link> out) {
    if (r.isSecondary() || r.isSupplementary() || r.isUnmapped()) return;

    if (r.saTag != null && !r.saTag.isBlank()) {
      for (String entry : r.saTag.split(";")) {
        if (entry.isBlank()) continue;
        String[] parts = entry.split(",", -1);
        if (parts.length < 2) continue;
        try {
          int saPos = Integer.parseInt(parts[1]);
          out.add(new CircosPlot.Link(
              stackChrom, r.pos + 1,
              parts[0], saPos,
              CircosPlot.LinkType.SPLIT_READ, sampleName));
        } catch (NumberFormatException ignore) { }
      }
    }

    if (r.isPaired() && r.mateRefID >= 0 && r.mateRefID != r.refID
        && r.mateRefID < refNames.length && r.matePos >= 0) {
      out.add(new CircosPlot.Link(
          stackChrom, r.pos + 1,
          refNames[r.mateRefID], r.matePos + 1,
          CircosPlot.LinkType.DISCORDANT_PAIR, sampleName));
    }
  }

  /** Snapshot of a sample's alignment file for off-thread sweeping. */
  private record SampleSweep(AlignmentFile file, String name, String[] refNames) {}
}
