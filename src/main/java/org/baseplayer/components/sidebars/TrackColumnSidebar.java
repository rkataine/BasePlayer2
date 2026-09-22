package org.baseplayer.components.sidebars;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.components.SampleTrackControls;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.services.TrackViewportRegistry;

import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Window;

public abstract class TrackColumnSidebar extends SidebarBase {

  public static record HitBox(double x, double y, double w, double h) {
    public boolean contains(double px, double py) {
      return px >= x && px <= x + w && py >= y && py <= y + h;
    }
  }

  public record ExpandedControlsRenderResult(
      HitBox rangeStartHandleHit, HitBox rangeEndHandleHit, HitBox rangeLabelHit) {
  }

  public record MasterHeaderRenderState(
      int trackCount,
      boolean controlsExpanded,
      boolean canReload,
      int firstVisible,
      int lastVisible,
      String hoveredControlId,
      boolean highlightRangeLabel) {
    public static MasterHeaderRenderState empty() {
      return new MasterHeaderRenderState(0, false, false, 0, 0, null, false);
    }
  }

  protected static final double EXPANDED_MASTER_HEIGHT = 82;

  protected final TrackViewportRegistry trackViewportRegistry;
  protected final Canvas masterHeaderCanvas;
  protected final Canvas masterHeaderReactiveCanvas;
  protected final InfoPopup rangeControlsPopup = new InfoPopup(310, 250, false);

  private MasterHeaderRenderState masterHeaderRenderState = MasterHeaderRenderState.empty();
  private ContextMenu addTrackMenu;
  private ContextMenu sidebarSettingsMenu;

  private boolean isDraggingResize;
  private double dragStartScreenY;
  private double dragStartHeight;

  private final List<SampleTrackControls.Hit> masterControlHits = new ArrayList<>();
  private String hoveredMasterControlId;
  private boolean rangeLabelHovered;

  private boolean draggingRangeStart;
  private boolean draggingRangeEnd;
  private boolean pendingSingleHandleResolve;
  private int pendingSingleHandleAnchor = -1;

  private boolean masterMouseDragged;
  private double pressX;
  private double pressY;

  private HitBox rangeStartHandleHit;
  private HitBox rangeEndHandleHit;
  private HitBox rangeLabelHit;

  protected TrackColumnSidebar(StackPane parent, TrackViewportRegistry trackViewportRegistry) {
    super(parent, trackViewportRegistry.getMasterBandHeightPixels());
    this.trackViewportRegistry = trackViewportRegistry;

    headerPane.minHeightProperty().unbind();
    headerPane.maxHeightProperty().unbind();
    headerPane.minHeightProperty().bind(trackViewportRegistry.masterBandHeightProperty());
    headerPane.maxHeightProperty().bind(trackViewportRegistry.masterBandHeightProperty());

    replaceHeaderContent();
    masterHeaderCanvas = new Canvas();
    masterHeaderReactiveCanvas = new Canvas();
    masterHeaderCanvas.widthProperty().bind(headerPane.widthProperty());
    masterHeaderCanvas.heightProperty().bind(headerPane.heightProperty());
    masterHeaderReactiveCanvas.widthProperty().bind(headerPane.widthProperty());
    masterHeaderReactiveCanvas.heightProperty().bind(headerPane.heightProperty());
    masterHeaderCanvas.setManaged(false);
    masterHeaderReactiveCanvas.setManaged(false);
    headerPane.getChildren().addAll(masterHeaderCanvas, masterHeaderReactiveCanvas);

    masterHeaderCanvas.widthProperty().addListener((obs, o, n) -> drawMasterHeader());
    masterHeaderCanvas.heightProperty().addListener((obs, o, n) -> drawMasterHeader());
    masterHeaderReactiveCanvas.widthProperty().addListener((obs, o, n) -> drawMasterHeader());
    masterHeaderReactiveCanvas.heightProperty().addListener((obs, o, n) -> drawMasterHeader());

    installMasterHeaderHandlers();
  }

  protected abstract TrackListPanel getTrackListPanel();

  protected abstract double estimateTrackBodyViewportHeightPixels();

  protected abstract ContextMenu buildAddTrackMenu();

  protected abstract ContextMenu buildSidebarSettingsMenu();

  protected boolean canShowReloadButton() {
    return false;
  }

  protected void onReloadButtonClicked() {
  }

  protected String getVisibleTracksRangeLabelTitle() {
    return "Visible tracks";
  }

  protected boolean highlightRangeLabelOnHover() {
    return false;
  }

  protected HitBox paintExpandedRangeLabelArea(
      GraphicsContext gc,
      double panelWidth,
      double headerBarHeight,
      int trackCount,
      int firstVisibleSlot,
      int lastVisibleSlot) {
    gc.setFont(Font.font("Segoe UI", 10));
    gc.setFill(Color.web("#9ea7b3"));
    gc.fillText(getVisibleTracksRangeLabelTitle(), 8, headerBarHeight + 14);
    gc.setFill(Color.web("#7f8791"));

    String rangeText = firstVisibleSlot == lastVisibleSlot
        ? String.valueOf(firstVisibleSlot + 1)
        : (firstVisibleSlot + 1) + "-" + (lastVisibleSlot + 1);
    String label = rangeText + " / " + trackCount;
    double labelX = 96;
    gc.fillText(label, labelX, headerBarHeight + 14);
    double labelWidth = Math.max(80, label.length() * 8.5);
    return new HitBox(labelX - 6, headerBarHeight + 1, labelWidth, 18);
  }

  protected boolean handleExpandedRangeLabelClick(double screenX, double screenY) {
    return false;
  }

  protected void appendRangeInputPopupExtras(VBox panel) {
  }

  protected void handleMasterHeaderScroll(ScrollEvent event) {
  }

  protected Canvas getMasterHeaderCanvas() {
    return masterHeaderCanvas;
  }

  protected MasterHeaderRenderState getMasterHeaderRenderState() {
    return masterHeaderRenderState;
  }

  protected final TrackViewportRegistry getTrackViewportRegistry() {
    return trackViewportRegistry;
  }

  @Override
  public void draw() {
    syncMasterHeaderRenderState();
    drawMasterHeader();
    getTrackListPanel().draw();
  }

  @Override
  protected void drawContent() {
    getTrackListPanel().draw();
  }

  @Override
  protected void onSettingsClicked(double screenX, double screenY) {
    ensureMenusInitialized();
    sidebarSettingsMenu.show(masterHeaderCanvas.getScene().getWindow(), screenX, screenY);
  }

  @Override
  protected void onAddClicked(double screenX, double screenY) {
    ensureMenusInitialized();
    addTrackMenu.show(masterHeaderCanvas, screenX, screenY);
  }

  protected final void applyVisibleTrackRange(int firstSlot, int lastSlot) {
    int trackCount = trackViewportRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      trackViewportRegistry.clearVisibleTrackRange();
      trackViewportRegistry.setMasterBandHeightPixels(
          TrackViewportRegistry.DEFAULT_MASTER_BAND_HEIGHT_PIXELS);
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      return;
    }

    double viewportHeight = estimateTrackBodyViewportHeightPixels();
    trackViewportRegistry.setVisibleTrackRange(firstSlot, lastSlot, viewportHeight);
    // While the range handles are dragging, height stays locked so the window can
    // temporarily violate min row height. Outside that, settle immediately so a
    // later track-body mouse-enter redraw cannot rewrite the range.
    if (!trackViewportRegistry.isTrackRowHeightLocked() && viewportHeight > 0) {
      trackViewportRegistry.ensureTrackRowHeightFitsViewport(viewportHeight);
    }
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  protected final boolean isControlsExpanded() {
    return trackViewportRegistry.getMasterBandHeightPixels()
        > TrackViewportRegistry.DEFAULT_MASTER_BAND_HEIGHT_PIXELS + 1;
  }

  protected final double headerBarHeight() {
    return Math.min(
        TrackViewportRegistry.DEFAULT_MASTER_BAND_HEIGHT_PIXELS,
        masterHeaderCanvas.getHeight());
  }

  private void ensureMenusInitialized() {
    if (addTrackMenu == null) {
      addTrackMenu = buildAddTrackMenu();
    }
    if (sidebarSettingsMenu == null) {
      sidebarSettingsMenu = buildSidebarSettingsMenu();
    }
  }

  private void syncMasterHeaderRenderState() {
    int trackCount = trackViewportRegistry.getDisplayedTrackCount();

    if ((trackCount <= 0 || !shouldAutoExpandMasterHeader(trackCount)) && isControlsExpanded()) {
      trackViewportRegistry.setMasterBandHeightPixels(
          TrackViewportRegistry.DEFAULT_MASTER_BAND_HEIGHT_PIXELS);
    }
    if (shouldAutoExpandMasterHeader(trackCount) && !isControlsExpanded()) {
      trackViewportRegistry.setMasterBandHeightPixels(EXPANDED_MASTER_HEIGHT);
    }

    int firstVisible = 0;
    int lastVisible = 0;
    if (trackCount > 0) {
      firstVisible = Math.max(0, Math.min(trackCount - 1,
          trackViewportRegistry.getFirstVisibleTrackSlot()));
      lastVisible = Math.max(firstVisible, Math.min(trackCount - 1,
          trackViewportRegistry.getLastVisibleTrackSlot()));
    }

    masterHeaderRenderState = new MasterHeaderRenderState(
        trackCount,
        trackCount > 0 && isControlsExpanded(),
        canShowReloadButton(),
        firstVisible,
        lastVisible,
        hoveredMasterControlId,
        rangeLabelHovered && highlightRangeLabelOnHover());
  }

  protected boolean shouldAutoExpandMasterHeader(int displayedTrackCount) {
    return displayedTrackCount > 1;
  }

  private void installMasterHeaderHandlers() {
    masterHeaderReactiveCanvas.setOnMouseEntered(
        event -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
    masterHeaderReactiveCanvas.setOnMouseMoved(this::handleMasterMouseMoved);
    masterHeaderReactiveCanvas.setOnMouseExited(this::handleMasterMouseExited);
    masterHeaderReactiveCanvas.setOnMousePressed(this::handleMasterMousePressed);
    masterHeaderReactiveCanvas.setOnMouseDragged(this::handleMasterMouseDragged);
    masterHeaderReactiveCanvas.setOnMouseReleased(this::handleMasterMouseReleased);
    masterHeaderReactiveCanvas.setOnScroll(event -> {
      event.consume();
      handleMasterHeaderScroll(event);
    });
  }

  private void handleMasterMouseMoved(MouseEvent event) {
    double edgeZone = masterHeaderCanvas.getHeight() - 4;
    boolean inResizeZone = event.getY() >= edgeZone;
    boolean overRangeHandle = isControlsExpanded() && isOverRangeHandle(event.getX(), event.getY());
    SampleTrackControls.Hit controlHit =
        SampleTrackControls.findHit(masterControlHits, event.getX(), event.getY());
    if (inResizeZone) {
      masterHeaderReactiveCanvas.setCursor(Cursor.V_RESIZE);
    } else if (overRangeHandle) {
      masterHeaderReactiveCanvas.setCursor(Cursor.H_RESIZE);
    } else if (controlHit != null) {
      masterHeaderReactiveCanvas.setCursor(Cursor.HAND);
    } else {
      masterHeaderReactiveCanvas.setCursor(Cursor.DEFAULT);
    }

    String prevHovered = hoveredMasterControlId;
    boolean prevRangeLabelHover = rangeLabelHovered;

    if (inResizeZone) {
      hoveredMasterControlId = null;
      rangeLabelHovered = false;
    } else if (event.getY() <= headerBarHeight()) {
      hoveredMasterControlId = controlHit != null ? controlHit.id() : null;
      rangeLabelHovered = false;
    } else {
      hoveredMasterControlId = null;
      rangeLabelHovered = isControlsExpanded()
          && rangeLabelHit != null
          && rangeLabelHit.contains(event.getX(), event.getY());
    }

    if (!java.util.Objects.equals(prevHovered, hoveredMasterControlId)
        || prevRangeLabelHover != rangeLabelHovered) {
      drawMasterHeader();
    }
  }

  private void handleMasterMouseExited(MouseEvent event) {
    masterHeaderReactiveCanvas.setCursor(Cursor.DEFAULT);
    if (hoveredMasterControlId != null || rangeLabelHovered) {
      hoveredMasterControlId = null;
      rangeLabelHovered = false;
      drawMasterHeader();
    }
  }

  private void handleMasterMousePressed(MouseEvent event) {
    pressX = event.getX();
    pressY = event.getY();
    masterMouseDragged = false;

    if (event.getY() >= masterHeaderCanvas.getHeight() - 4) {
      isDraggingResize = true;
      dragStartScreenY = event.getScreenY();
      dragStartHeight = trackViewportRegistry.getMasterBandHeightPixels();
      return;
    }

    if (isControlsExpanded() && beginRangeHandleDrag(event.getX(), event.getY())) {
      trackViewportRegistry.lockTrackRowHeight();
    }
  }

  private void handleMasterMouseDragged(MouseEvent event) {
    if (Math.abs(event.getX() - pressX) > 2 || Math.abs(event.getY() - pressY) > 2) {
      masterMouseDragged = true;
    }

    if (isDraggingResize) {
      double delta = event.getScreenY() - dragStartScreenY;
      trackViewportRegistry.setMasterBandHeightPixels(
          Math.max(20, Math.min(200, dragStartHeight + delta)));
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      return;
    }

    if (draggingRangeStart || draggingRangeEnd || pendingSingleHandleResolve) {
      updateRangeFromHandleDrag(event.getX());
      masterMouseDragged = true;
    }
  }

  private void handleMasterMouseReleased(MouseEvent event) {
    boolean wasResizing = isDraggingResize;
    if (isDraggingResize) {
      isDraggingResize = false;
      masterHeaderReactiveCanvas.setCursor(Cursor.DEFAULT);
    }

    boolean finishedRangeDrag =
        draggingRangeStart || draggingRangeEnd || pendingSingleHandleResolve;
    if (finishedRangeDrag) {
      trackViewportRegistry.unlockTrackRowHeight();
      // Apply min-height / scroll snap now. Range drag keeps height locked so
      // ensure() cannot fight the handles; without this, the first body redraw
      // (e.g. mouse-enter on tracks) would rewrite the window and look like the
      // visibility slider was cancelled.
      double viewportHeight = estimateTrackBodyViewportHeightPixels();
      if (viewportHeight > 0) {
        trackViewportRegistry.ensureTrackRowHeightFitsViewport(viewportHeight);
      }
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }

    boolean shouldHandleClick = event.getButton() == MouseButton.PRIMARY
        && !masterMouseDragged
        && !wasResizing;

    draggingRangeStart = false;
    draggingRangeEnd = false;
    pendingSingleHandleResolve = false;
    pendingSingleHandleAnchor = -1;
    masterMouseDragged = false;

    if (shouldHandleClick) {
      handleMasterHeaderClick(event.getX(), event.getY(), event.getScreenX(), event.getScreenY());
    }
  }

  private void handleMasterHeaderClick(double x, double y, double screenX, double screenY) {
    if (isControlsExpanded() && rangeLabelHit != null && rangeLabelHit.contains(x, y)) {
      if (handleExpandedRangeLabelClick(screenX, screenY)) {
        return;
      }
      showRangeInputPopup(screenX, screenY);
      return;
    }

    SampleTrackControls.Hit controlHit = SampleTrackControls.findHit(masterControlHits, x, y);
    if (controlHit != null) {
      switch (controlHit.id()) {
        case "settings" -> onSettingsClicked(screenX, screenY);
        case "add" -> onAddClicked(screenX, screenY);
        case "reload" -> onReloadButtonClicked();
        default -> { }
      }
      return;
    }

    if (y <= headerBarHeight()) {
      onMasterHeaderBarClicked(screenX, screenY);
    }
  }

  protected void onMasterHeaderBarClicked(double screenX, double screenY) {
  }

  private boolean isOverRangeHandle(double x, double y) {
    return (rangeStartHandleHit != null && rangeStartHandleHit.contains(x, y))
        || (rangeEndHandleHit != null && rangeEndHandleHit.contains(x, y));
  }

  private boolean beginRangeHandleDrag(double x, double y) {
    boolean startHit = rangeStartHandleHit != null && rangeStartHandleHit.contains(x, y);
    boolean endHit = rangeEndHandleHit != null && rangeEndHandleHit.contains(x, y);

    int trackCount = trackViewportRegistry.getDisplayedTrackCount();
    int first = trackCount <= 0 ? 0 : Math.max(0, Math.min(trackCount - 1,
        trackViewportRegistry.getFirstVisibleTrackSlot()));
    int last = trackCount <= 0 ? 0 : Math.max(first, Math.min(trackCount - 1,
        trackViewportRegistry.getLastVisibleTrackSlot()));

    if (startHit && endHit && first == last) {
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
    int trackCount = trackViewportRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      return;
    }

    double railX = 12;
    double railW = Math.max(10, masterHeaderCanvas.getWidth() - 24);
    int mapped = mapMouseXToTrackSlotIndex(mouseX, railX, railW, trackCount);

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

    int first = Math.max(0, Math.min(trackCount - 1,
        trackViewportRegistry.getFirstVisibleTrackSlot()));
    int last = Math.max(first, Math.min(trackCount - 1,
        trackViewportRegistry.getLastVisibleTrackSlot()));

    if (draggingRangeStart) {
      first = Math.min(mapped, last);
    } else if (draggingRangeEnd) {
      last = Math.max(mapped, first);
    }

    applyVisibleTrackRange(first, last);
  }

  private int mapMouseXToTrackSlotIndex(double x, double railX, double railW, int trackCount) {
    if (trackCount <= 1) {
      return 0;
    }
    double t = (x - railX) / railW;
    t = Math.max(0.0, Math.min(1.0, t));
    return (int) Math.round(t * (trackCount - 1));
  }

  protected void showRangeInputPopup(double screenX, double screenY) {
    if (rangeControlsPopup.isShowing()) {
      rangeControlsPopup.hide();
    }

    int trackCount = trackViewportRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      return;
    }

    int first = Math.max(0, Math.min(trackCount - 1,
        trackViewportRegistry.getFirstVisibleTrackSlot()));
    int last = Math.max(first, Math.min(trackCount - 1,
        trackViewportRegistry.getLastVisibleTrackSlot()));

    HBox row = new HBox(6);
    row.setPadding(new Insets(6));

    Label fromLabel = new Label("From");
    fromLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
    TextField fromField = new TextField(String.valueOf(first + 1));
    fromField.setPrefWidth(52);
    fromField.setStyle(
        "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");

    Label toLabel = new Label("To");
    toLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
    TextField toField = new TextField(String.valueOf(last + 1));
    toField.setPrefWidth(52);
    toField.setStyle(
        "-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");

    Button applyBtn = new Button("Apply");
    applyBtn.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #cccccc; -fx-font-size: 11;"
        + "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");

    Runnable applyRange = () -> {
      int displayedCount = trackViewportRegistry.getDisplayedTrackCount();
      if (displayedCount <= 0) {
        return;
      }
      Integer parsedStart = parseOneBasedIndex(fromField.getText(), displayedCount);
      Integer parsedEnd = parseOneBasedIndex(toField.getText(), displayedCount);
      if (parsedStart == null || parsedEnd == null) {
        fromField.setText(String.valueOf(trackViewportRegistry.getFirstVisibleTrackSlot() + 1));
        toField.setText(String.valueOf(trackViewportRegistry.getLastVisibleTrackSlot() + 1));
        return;
      }
      int startSlot = Math.min(parsedStart, parsedEnd);
      int endSlot = Math.max(parsedStart, parsedEnd);
      startSlot = Math.max(0, Math.min(displayedCount - 1, startSlot));
      endSlot = Math.max(startSlot, Math.min(displayedCount - 1, endSlot));
      applyVisibleTrackRange(startSlot, endSlot);
      rangeControlsPopup.hide();
    };

    applyBtn.setOnAction(e -> applyRange.run());
    fromField.setOnAction(e -> applyRange.run());
    toField.setOnAction(e -> applyRange.run());
    row.getChildren().addAll(fromLabel, fromField, toLabel, toField, applyBtn);

    VBox panel = new VBox(4, row);
    appendRangeInputPopupExtras(panel);

    PopupContent content = new PopupContent().node(panel);
    Window owner = masterHeaderCanvas.getScene() != null
        ? masterHeaderCanvas.getScene().getWindow()
        : null;
    if (owner == null) {
      return;
    }
    rangeControlsPopup.show(content, owner, screenX, screenY);
  }

  private Integer parseOneBasedIndex(String value, int trackCount) {
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed < 1) {
        parsed = 1;
      }
      if (parsed > trackCount) {
        parsed = trackCount;
      }
      return parsed - 1;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void drawMasterHeader() {
    double w = masterHeaderCanvas.getWidth();
    double h = masterHeaderCanvas.getHeight();
    if (w <= 0 || h <= 0) {
      return;
    }

    int trackCount = Math.max(0, masterHeaderRenderState.trackCount());
    GraphicsContext gc = masterHeaderCanvas.getGraphicsContext2D();
    double headerBarH = Math.min(
        TrackViewportRegistry.DEFAULT_MASTER_BAND_HEIGHT_PIXELS, h);
    SidebarBase.drawStandardHeader(gc, w, headerBarH, getTitle(), trackCount, false);

    masterControlHits.clear();
    masterControlHits.addAll(SampleTrackControls.drawMaster(
        gc, w, headerBarH, masterHeaderRenderState.canReload(), hoveredMasterControlId));

    ExpandedControlsRenderResult expandedResult =
        new ExpandedControlsRenderResult(null, null, null);
    if (masterHeaderRenderState.controlsExpanded()) {
      expandedResult = renderExpandedControls(
          gc, w, h, headerBarH, trackCount,
          masterHeaderRenderState.firstVisible(),
          masterHeaderRenderState.lastVisible());
    }

    rangeStartHandleHit = expandedResult.rangeStartHandleHit();
    rangeEndHandleHit = expandedResult.rangeEndHandleHit();
    rangeLabelHit = expandedResult.rangeLabelHit();
    drawMasterHeaderHover(expandedResult.rangeLabelHit());
  }

  private ExpandedControlsRenderResult renderExpandedControls(
      GraphicsContext gc,
      double w,
      double h,
      double headerBarH,
      int trackCount,
      int firstVisible,
      int lastVisible) {
    if (h <= headerBarH + 2) {
      return new ExpandedControlsRenderResult(null, null, null);
    }

    gc.setFill(Color.web("#202327"));
    gc.fillRect(0, headerBarH, w, Math.max(0, h - headerBarH));
    gc.setStroke(Color.web("#3e444d"));
    gc.strokeLine(0, headerBarH, w, headerBarH);

    if (trackCount <= 0) {
      return new ExpandedControlsRenderResult(null, null, null);
    }

    HitBox labelHit = paintExpandedRangeLabelArea(
        gc, w, headerBarH, trackCount, firstVisible, lastVisible);

    double railX = 12;
    double railW = Math.max(10, w - 24);
    double railY = headerBarH + 29;
    double railH = 8;

    gc.setFill(Color.web("#2f353e"));
    gc.fillRoundRect(railX, railY, railW, railH, 4, 4);

    double startX = railX
        + (trackCount <= 1 ? 0 : (firstVisible / (double) (trackCount - 1)) * railW);
    double endX = railX
        + (trackCount <= 1 ? railW : (lastVisible / (double) (trackCount - 1)) * railW);
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

    HitBox startHandle =
        new HitBox(startX - handleW / 2 - 2, handleY - 2, handleW + 4, handleH + 4);
    HitBox endHandle =
        new HitBox(endX - handleW / 2 - 2, handleY - 2, handleW + 4, handleH + 4);
    return new ExpandedControlsRenderResult(startHandle, endHandle, labelHit);
  }

  private void drawMasterHeaderHover(HitBox rangeLabelHit) {
    GraphicsContext reactiveGc = masterHeaderReactiveCanvas.getGraphicsContext2D();
    double w = masterHeaderReactiveCanvas.getWidth();
    double h = masterHeaderReactiveCanvas.getHeight();
    reactiveGc.clearRect(0, 0, w, h);

    if (rangeLabelHovered && highlightRangeLabelOnHover() && rangeLabelHit != null) {
      reactiveGc.setFill(Color.rgb(255, 165, 0, 0.2));
      reactiveGc.fillRect(
          rangeLabelHit.x(), rangeLabelHit.y(), rangeLabelHit.w(), rangeLabelHit.h());
    }
  }
}
