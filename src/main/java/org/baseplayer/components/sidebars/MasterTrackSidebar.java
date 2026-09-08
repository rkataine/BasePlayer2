package org.baseplayer.components.sidebars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.LoadRegionButton;
import org.baseplayer.components.PopupComboBoxStyler;
import org.baseplayer.components.PopupContent;
import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.io.VcfManager;
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
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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

/**
 * Dedicated sidebar owner for master-track header and sample list content.
 */
public class MasterTrackSidebar extends SidebarBase {

  // ── Inner interfaces and records ────────────────────────────────────────
  public static record HitBox(double x, double y, double w, double h) {
    public boolean contains(double px, double py) {
      return px >= x && px <= x + w && py >= y && py <= y + h;
    }
  }

  public record ExpandedControlsRenderResult(HitBox rangeStartHandleHit, HitBox rangeEndHandleHit, HitBox rangeLabelHit) {
  }

  public record RenderState(
      int trackCount,
      boolean controlsExpanded,
      boolean canReload,
      int firstVisible,
      int lastVisible,
      String focusedGene,
      boolean hasActiveSampleFilterQuery,
      boolean settingsHovered,
      boolean reloadHovered,
      boolean addHovered,
      boolean highlightRangeLabel) {
    public static RenderState empty() {
      return new RenderState(0, false, false, 0, 0, null, false, false, false, false, false);
    }
  }

  private static final double EXPANDED_MASTER_HEIGHT = 82;
  private static final double HEADER_BTN_SIZE = 18;
  private static final double HEADER_BTN_LEFT_X = 4;

  private Canvas masterTrackCanvas;
  private Canvas masterTrackReactiveCanvas;
  private DrawStack masterTrackDrawStack;
  private RenderState masterTrackRenderState = RenderState.empty();
  public final LoadRegionButton loadRegionButton;
  public final SampleListPanel sampleList;

  private final SampleRegistry sampleRegistry;
  private final DrawStackManager stackManager;
  private final ContextMenu addDataMenu;
  private final InfoPopup rangeControlsPopup = new InfoPopup(310, 250, false);

  private boolean isDraggingResize = false;
  private double dragStartScreenY = 0;
  private double dragStartHeight = 0;

  private boolean settingsHovered = false;
  private boolean addHovered = false;
  private boolean reloadHovered = false;
  private boolean rangeLabelHovered = false;

  private boolean draggingRangeStart = false;
  private boolean draggingRangeEnd = false;
  private boolean pendingSingleHandleResolve = false;
  private int pendingSingleHandleAnchor = -1;

  private boolean masterMouseDragged = false;
  private double pressX = 0;
  private double pressY = 0;

  private HitBox rangeStartHandleHit = null;
  private HitBox rangeEndHandleHit = null;
  private HitBox rangeLabelHit = null;

  public MasterTrackSidebar(StackPane parent) {
    super(parent, SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT);

    sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    addDataMenu = createAddDataMenu();

    // Rebind header height to the dynamic property.
    headerPane.minHeightProperty().bind(sampleRegistry.masterTrackHeightProperty());
    headerPane.maxHeightProperty().bind(sampleRegistry.masterTrackHeightProperty());

    // Replace default header canvases with master track canvases.
    replaceHeaderContent();
    masterTrackCanvas = new Canvas();
    masterTrackReactiveCanvas = new Canvas();
    masterTrackDrawStack = new DrawStack();
    loadRegionButton = new LoadRegionButton();

    // Bind canvases to header pane size so they render properly.
    masterTrackCanvas.widthProperty().bind(headerPane.widthProperty());
    masterTrackCanvas.heightProperty().bind(headerPane.heightProperty());
    masterTrackReactiveCanvas.widthProperty().bind(headerPane.widthProperty());
    masterTrackReactiveCanvas.heightProperty().bind(headerPane.heightProperty());

    // These canvases are bound to headerPane size; avoid layout feedback loops.
    masterTrackCanvas.setManaged(false);
    masterTrackReactiveCanvas.setManaged(false);

    headerPane.getChildren().addAll(masterTrackCanvas, masterTrackReactiveCanvas);

    // Keep visuals anchored correctly on resize.
    masterTrackCanvas.widthProperty().addListener((obs, oldVal, newVal) -> drawMasterTrack());
    masterTrackCanvas.heightProperty().addListener((obs, oldVal, newVal) -> drawMasterTrack());
    masterTrackReactiveCanvas.widthProperty().addListener((obs, oldVal, newVal) -> drawMasterTrack());
    masterTrackReactiveCanvas.heightProperty().addListener((obs, oldVal, newVal) -> drawMasterTrack());

    Platform.runLater(loadRegionButton::attachRegionListener);
    installMasterHandlers();

    // Sample list in content pane.
    sampleList = new SampleListPanel(contentPane);
    
    // Trigger initial draw to render the master track.
    Platform.runLater(this::draw);
  }

  public void initializeLoadRegionButton() {
    Platform.runLater(loadRegionButton::attachRegionListener);
  }

  // -- SidebarBase contract -------------------------------------------------

  @Override
  protected String getTitle() {
    return "Tracks";
  }

  @Override
  protected int getItemCount() {
    return ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks().size();
  }

  @Override
  protected void onSettingsClicked(double screenX, double screenY) {
    // Header handling is custom in MasterTrackSidebar.
  }

  @Override
  protected void onAddClicked(double screenX, double screenY) {
    // Header handling is custom in MasterTrackSidebar.
  }

  @Override
  protected void drawContent() {
    sampleList.draw();
  }

  /** Full repaint -- header + content (SampleListPanel). */
  @Override
  public void draw() {
    syncRenderState();
    drawMasterTrack();
    sampleList.draw();
  }

  private void syncRenderState() {
    int trackCount = sampleRegistry.getDisplayedTrackCount();

    if (trackCount <= 0 && isControlsExpanded()) {
      sampleRegistry.setMasterTrackHeight(SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT);
    }

    if (trackCount > 1 && !isControlsExpanded()) {
      sampleRegistry.setMasterTrackHeight(EXPANDED_MASTER_HEIGHT);
    }

    int firstVisible = 0;
    int lastVisible = 0;
    if (trackCount > 0) {
      firstVisible = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
      lastVisible = Math.max(firstVisible, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));
    }

    masterTrackRenderState = new RenderState(
        trackCount,
        trackCount > 0 && isControlsExpanded(),
        hasAnySuspended(),
        firstVisible,
        lastVisible,
        sampleRegistry.getFocusedGeneName(),
        sampleRegistry.hasActiveSampleFilterQuery(),
        settingsHovered,
        reloadHovered,
        addHovered,
        rangeLabelHovered && sampleRegistry.hasFocusedTrackIndices());
  }

  // -- Master header interactions ------------------------------------------

  private void installMasterHandlers() {
    // Keep hover-stack ownership with alignment canvases.
    masterTrackReactiveCanvas.setOnMouseEntered(event -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
    masterTrackReactiveCanvas.setOnMouseMoved(this::handleMasterMouseMoved);
    masterTrackReactiveCanvas.setOnMouseExited(this::handleMasterMouseExited);
    masterTrackReactiveCanvas.setOnMousePressed(this::handleMasterMousePressed);
    masterTrackReactiveCanvas.setOnMouseDragged(this::handleMasterMouseDragged);
    masterTrackReactiveCanvas.setOnMouseReleased(this::handleMasterMouseReleased);
    masterTrackReactiveCanvas.setOnScroll(this::handleMasterScroll);
  }

  private void handleMasterMouseMoved(MouseEvent event) {
    double edgeZone = masterTrackCanvas.getHeight() - 4;
    boolean inResizeZone = event.getY() >= edgeZone;
    boolean overRangeHandle = isControlsExpanded() && isOverRangeHandle(event.getX(), event.getY());
    masterTrackReactiveCanvas.setCursor(inResizeZone ? Cursor.V_RESIZE : (overRangeHandle ? Cursor.H_RESIZE : Cursor.DEFAULT));

    boolean prevSettings = settingsHovered;
    boolean prevAdd = addHovered;
    boolean prevReload = reloadHovered;
    boolean prevRangeLabelHover = rangeLabelHovered;

    if (inResizeZone) {
      settingsHovered = false;
      addHovered = false;
      reloadHovered = false;
      rangeLabelHovered = false;
    } else if (event.getY() <= headerBarHeight()) {
      double sy = (headerBarHeight() - headerBtnSize()) / 2;
      settingsHovered = inHeaderBtn(
          event.getX(), event.getY(), headerBtnLeftX(), sy);
      reloadHovered = hasAnySuspended() && inHeaderBtn(
          event.getX(), event.getY(), reloadBtnX(masterTrackCanvas.getWidth()), sy);
      addHovered = inHeaderBtn(
          event.getX(), event.getY(), masterTrackCanvas.getWidth() - headerBtnSize() - 4, sy);
      rangeLabelHovered = false;
    } else {
      settingsHovered = false;
      addHovered = false;
      reloadHovered = false;
      rangeLabelHovered = isControlsExpanded()
          && rangeLabelHit != null
          && rangeLabelHit.contains(event.getX(), event.getY());
    }

    if (prevSettings != settingsHovered
        || prevAdd != addHovered
        || prevReload != reloadHovered
        || prevRangeLabelHover != rangeLabelHovered) {
      drawMasterTrack();
    }
  }

  private void handleMasterMouseExited(MouseEvent event) {
    masterTrackReactiveCanvas.setCursor(Cursor.DEFAULT);

    if (settingsHovered || addHovered || reloadHovered || rangeLabelHovered) {
      settingsHovered = false;
      addHovered = false;
      reloadHovered = false;
      rangeLabelHovered = false;
      drawMasterTrack();
    }
  }

  private void handleMasterMousePressed(MouseEvent event) {
    pressX = event.getX();
    pressY = event.getY();
    masterMouseDragged = false;

    if (event.getY() >= masterTrackCanvas.getHeight() - 4) {
      isDraggingResize = true;
      dragStartScreenY = event.getScreenY();
      dragStartHeight = sampleRegistry.getMasterTrackHeight();
      return;
    }

    if (isControlsExpanded() && beginRangeHandleDrag(event.getX(), event.getY())) {
      sampleRegistry.lockSampleHeight();
    }
  }

  private void handleMasterMouseDragged(MouseEvent event) {
    if (Math.abs(event.getX() - pressX) > 2 || Math.abs(event.getY() - pressY) > 2) {
      masterMouseDragged = true;
    }

    if (isDraggingResize) {
      double delta = event.getScreenY() - dragStartScreenY;
      sampleRegistry.setMasterTrackHeight(Math.max(20, Math.min(200, dragStartHeight + delta)));
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
      masterTrackReactiveCanvas.setCursor(Cursor.DEFAULT);
    }

    if (draggingRangeStart || draggingRangeEnd || pendingSingleHandleResolve) {
      sampleRegistry.unlockSampleHeight();
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
      handleMasterClick(event.getX(), event.getY(), event.getScreenX(), event.getScreenY());
    }
  }

  private void handleMasterScroll(ScrollEvent event) {
    event.consume();
    syncDrawStack();
    masterTrackDrawStack.nav.navigating = true;

    if (event.isControlDown()) {
      zoomAt(event.getDeltaY(), event.getX());
      return;
    }

    double delta = event.getDeltaX() != 0 ? event.getDeltaX() : event.getDeltaY();
    double genomeDelta = delta * 0.3 * masterTrackDrawStack.scale;
    panByGenomeDelta(genomeDelta);
  }

  private void syncDrawStack() {
    DrawStack hover = stackManager.getHoverStack();
    if (hover != null) {
      masterTrackDrawStack = hover;
    } else if (!stackManager.isEmpty()) {
      masterTrackDrawStack = stackManager.getFirst();
    }
  }

  private void handleMasterClick(double x, double y, double screenX, double screenY) {
    if (isControlsExpanded() && rangeLabelHit != null && rangeLabelHit.contains(x, y)) {
      if (sampleRegistry.hasFocusedTrackIndices()) {
        sampleRegistry.clearFocusedTrackIndices();
        int trackCount = sampleRegistry.getDisplayedTrackCount();
        if (trackCount > 0) {
          sampleRegistry.setFirstVisibleSample(0);
          sampleRegistry.setLastVisibleSample(trackCount - 1);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
        return;
      }
      showRangeInputPopup(screenX, screenY);
      return;
    }

    double sy = (headerBarHeight() - headerBtnSize()) / 2;

    if (inHeaderBtn(x, y, headerBtnLeftX(), sy)) {
      showGlobalSettingsMenu(screenX, screenY);
      return;
    }

    if (hasAnySuspended() && inHeaderBtn(x, y, reloadBtnX(masterTrackCanvas.getWidth()), sy)) {
      resumeSuspendedReads();
      return;
    }

    if (inHeaderBtn(
        x,
        y,
        masterTrackCanvas.getWidth() - headerBtnSize() - 4,
        sy)) {
      addDataMenu.show(masterTrackCanvas, screenX, screenY);
    }
  }

  private void resumeSuspendedReads() {
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
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private boolean isControlsExpanded() {
    return sampleRegistry.getMasterTrackHeight() > SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT + 1;
  }

  private double headerBarHeight() {
    return Math.min(SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT, masterTrackCanvas.getHeight());
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
    if (trackCount <= 0) {
      return;
    }

    double railX = 12;
    double railW = Math.max(10, masterTrackCanvas.getWidth() - 24);
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
    if (trackCount <= 1) {
      return 0;
    }

    double t = (x - railX) / railW;
    t = Math.max(0.0, Math.min(1.0, t));
    return (int) Math.round(t * (trackCount - 1));
  }

  private void showRangeInputPopup(double screenX, double screenY) {
    if (rangeControlsPopup.isShowing()) {
      rangeControlsPopup.hide();
    }

    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      return;
    }

    int first = Math.max(0, Math.min(trackCount - 1, sampleRegistry.getFirstVisibleSample()));
    int last = Math.max(first, Math.min(trackCount - 1, sampleRegistry.getLastVisibleSample()));

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
      rangeControlsPopup.hide();
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
      rangeControlsPopup.hide();
    });

    row.getChildren().addAll(fromLabel, fromField, toLabel, toField, applyBtn, clearBtn);
    filterRow.getChildren().addAll(filterLabel, filterField);
    VBox panel = new VBox(4, row, filterRow);

    PopupContent content = new PopupContent().node(panel);
    Window owner = masterTrackCanvas.getScene() != null ? masterTrackCanvas.getScene().getWindow() : null;
    if (owner == null) {
      return;
    }

    rangeControlsPopup.show(content, owner, screenX, screenY);
    Platform.runLater(filterField::requestFocus);
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

  private void applyVisibleRange(int first, int last) {
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    if (trackCount <= 0) {
      sampleRegistry.setFirstVisibleSample(-1);
      sampleRegistry.setLastVisibleSample(-1);
      sampleRegistry.setScrollBarPosition(0);
      sampleRegistry.setMasterTrackHeight(SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT);
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
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
      sampleRegistry.setScrollBarPosition(sampleRegistry.clampScrollBarPosition(targetScroll, viewportHeight));
    }

    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private double estimateSampleViewportHeight() {
    if (!stackManager.isEmpty() && stackManager.getFirst().alignmentCanvas != null) {
      double fromCanvas = stackManager.getFirst().alignmentCanvas.getHeight() - sampleRegistry.getMasterTrackHeight();
      if (fromCanvas > 0) {
        return fromCanvas;
      }
    }

    double derived = sampleRegistry.getSampleHeight() * Math.max(1, sampleRegistry.getVisibleSampleCount());
    if (derived > 0) {
      return derived;
    }

    return 0;
  }

  private boolean hasAnySuspended() {
    for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
      for (Sample s : strack.getSamples()) {
        if (s.isSuspended()) {
          return true;
        }
      }
    }
    return false;
  }

  private ExpandedControlsRenderResult renderExpandedControls(
      GraphicsContext gc,
      double w,
      double h,
      double headerBarH,
      int trackCount,
      int firstVisible,
      int lastVisible,
      String focusedGene,
      boolean hasActiveSampleFilterQuery) {
    if (h <= headerBarH + 2) {
      return new ExpandedControlsRenderResult(null, null, null);
    }

    if (trackCount <= 0) {
      gc.setFill(Color.web("#202327"));
      gc.fillRect(0, headerBarH, w, Math.max(0, h - headerBarH));
      gc.setStroke(Color.web("#3e444d"));
      gc.strokeLine(0, headerBarH, w, headerBarH);
      return new ExpandedControlsRenderResult(null, null, null);
    }

    gc.setFill(Color.web("#202327"));
    gc.fillRect(0, headerBarH, w, h - headerBarH);
    gc.setStroke(Color.web("#3e444d"));
    gc.strokeLine(0, headerBarH, w, headerBarH);

    gc.setFont(Font.font("Segoe UI", 10));
    gc.setFill(Color.web("#9ea7b3"));

    HitBox labelHit;
    if (focusedGene != null && !focusedGene.isBlank()) {
      gc.fillText("Gene focus: " + focusedGene, 8, headerBarH + 14);
      gc.setFill(Color.web("#ffa500"));
      gc.fillText("[clear x]", w - 70, headerBarH + 14);
      labelHit = new HitBox(w - 72, headerBarH + 1, 70, 18);
    } else {
      gc.fillText("Visible samples", 8, headerBarH + 14);
      gc.setFill(Color.web("#7f8791"));

      String label;
      if (hasActiveSampleFilterQuery) {
        label = "Filter";
      } else {
        String rangeText = firstVisible == lastVisible
            ? String.valueOf(firstVisible + 1)
            : (firstVisible + 1) + "-" + (lastVisible + 1);
        label = rangeText + " / " + trackCount;
      }

      double labelX = 96;
      double labelY = headerBarH + 14;
      gc.fillText(label, labelX, labelY);
      double labelWidth = Math.max(80, label.length() * 8.5);
      labelHit = new HitBox(labelX - 6, headerBarH + 1, labelWidth, 18);
    }

    double railX = 12;
    double railW = Math.max(10, w - 24);
    double railY = headerBarH + 29;
    double railH = 8;

    gc.setFill(Color.web("#2f353e"));
    gc.fillRoundRect(railX, railY, railW, railH, 4, 4);

    double startX = railX + (trackCount <= 1 ? 0 : (firstVisible / (double) (trackCount - 1)) * railW);
    double endX = railX + (trackCount <= 1 ? railW : (lastVisible / (double) (trackCount - 1)) * railW);
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

    HitBox rangeStartHandleHit =
        new HitBox(startX - handleW / 2 - 2, handleY - 2, handleW + 4, handleH + 4);
    HitBox rangeEndHandleHit =
        new HitBox(endX - handleW / 2 - 2, handleY - 2, handleW + 4, handleH + 4);

    return new ExpandedControlsRenderResult(rangeStartHandleHit, rangeEndHandleHit, labelHit);
  }

  private void renderHeaderHover(
      GraphicsContext reactiveGc,
      double w,
      double headerBarH,
      double headerBtnSize,
      double headerBtnLeftX,
      boolean settingsHovered,
      boolean reloadHovered,
      boolean addHovered,
      boolean canReload,
      boolean highlightRangeLabel,
      HitBox rangeLabelHit) {
    if (highlightRangeLabel && rangeLabelHit != null) {
      reactiveGc.setFill(Color.rgb(255, 165, 0, 0.2));
      reactiveGc.fillRect(rangeLabelHit.x(), rangeLabelHit.y(), rangeLabelHit.w(), rangeLabelHit.h());
    }

    double sy = (headerBarH - headerBtnSize) / 2;
    if (settingsHovered) {
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
      reactiveGc.fillRoundRect(headerBtnLeftX, sy, headerBtnSize, headerBtnSize, 4, 4);
    }
    if (reloadHovered && canReload) {
      double rx = w - 2 * (headerBtnSize + 4);
      reactiveGc.setFill(Color.rgb(255, 165, 30, 0.25));
      reactiveGc.fillRoundRect(rx, sy, headerBtnSize, headerBtnSize, 4, 4);
    }
    if (addHovered) {
      double plusX = w - headerBtnSize - 4;
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
      reactiveGc.fillRoundRect(plusX, sy, headerBtnSize, headerBtnSize, 4, 4);
    }
  }

  private ContextMenu createAddDataMenu() {
    ContextMenu menu = new ContextMenu();

    MenuItem bamItem = new MenuItem("BAM/CRAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamFiles());

    MenuItem vcfItem = new MenuItem("VCF");
    vcfItem.setOnAction(e -> SampleDataManager.addVcfFile());

    MenuItem variantManagerItem = new MenuItem("Variant Manager");
    variantManagerItem.setOnAction(e ->
        org.baseplayer.variant.ui.VariantManagerWindow.openVariantManager(
            masterTrackCanvas.getScene().getWindow(),
            VcfManager.getInstance(),
            null));

    MenuItem bedItem = new MenuItem("BED");
    bedItem.setOnAction(e -> SampleDataManager.addBedSampleFile());

    MenuItem bigwigItem = new MenuItem("BigWig");
    bigwigItem.setOnAction(e -> SampleDataManager.addBigWigFile());

    menu.getItems().addAll(bamItem, vcfItem, variantManagerItem, new SeparatorMenuItem(), bedItem, bigwigItem);
    return menu;
  }

  private void showGlobalSettingsMenu(double screenX, double screenY) {
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    Label titleLabel = new Label("Global Settings");
    titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 13; -fx-font-weight: bold; -fx-padding: 4 8 2 8;");
    settingsMenu.getItems().addAll(new CustomMenuItem(titleLabel, false), new SeparatorMenuItem());

    // Gather BAM files once for global controls.
    List<AlignmentFile> bamFiles = new ArrayList<>();
    for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
      for (Sample s : strack.getSamples()) {
        if (s.getBamFile() != null) {
          bamFiles.add(s.getBamFile());
        }
      }
    }

    // Sampled coverage settings (BAM-specific)
    if (!bamFiles.isEmpty()) {
      VBox sampledCoverageBox = new VBox(4);
      sampledCoverageBox.setPadding(new Insets(4, 8, 4, 8));

      CheckBox enableCoverageCb = new CheckBox("Enable sampled coverage");
      enableCoverageCb.setSelected(Settings.get().isEnableSampledCoverage());
      enableCoverageCb.getStyleClass().add("dark-checkbox");
      enableCoverageCb.setStyle("-fx-font-size: 12;");
      enableCoverageCb.selectedProperty().addListener((obs, o, n) -> {
        Settings.get().setEnableSampledCoverage(n);
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
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
                if (s.getBamFile() != null) {
                  s.getBamFile().clearSampledCoverageCache();
                }
              }
            }
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
            settingsMenu.hide();
          }
        } catch (NumberFormatException ex) {
          samplePointsField.setText(String.valueOf(Settings.get().getSampledCoveragePoints()));
        }
      });
      samplePointsBox.getChildren().addAll(samplePointsLabel, samplePointsField, refreshButton);
      sampledCoverageBox.getChildren().addAll(enableCoverageCb, samplePointsBox);
      settingsMenu.getItems().add(new CustomMenuItem(sampledCoverageBox, false));
    }

    // Mismatch filtering controls (apply to all BAM files)
    if (!bamFiles.isEmpty()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      VBox mismatchBox = new VBox(4);
      mismatchBox.setPadding(new Insets(4, 8, 4, 8));

      Label mismatchLabel = new Label("Mismatch filtering (all tracks)");
      mismatchLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-font-weight: bold;");

      CheckBox suppressMethylCb = new CheckBox("Hide bisulfite mismatches (C->T / G->A)");
      boolean anyMethylSuppressed = bamFiles.stream().anyMatch(AlignmentFile::isMethylationData);
      suppressMethylCb.setSelected(anyMethylSuppressed);
      suppressMethylCb.getStyleClass().add("dark-checkbox");
      suppressMethylCb.setStyle("-fx-font-size: 11;");
      suppressMethylCb.selectedProperty().addListener((obs, o, n) -> {
        for (AlignmentFile bam : bamFiles) {
          bam.setSuppressMethylMismatches(n);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });

      HBox mmFractionRow = new HBox(6);
      Label mmFractionLabel = new Label("Min fraction (0-1):");
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
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
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
      colorCombo.getItems().setAll(bamFiles.get(0).getAvailableColorModes());
      colorCombo.setValue(bamFiles.get(0).getReadColorMode());
      colorCombo.setPrefWidth(200);
      PopupComboBoxStyler.styleDarkComboBox(colorCombo, settingsMenu);
      colorCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) {
          return;
        }
        for (AlignmentFile bam : bamFiles) {
          bam.setReadColorMode(newMode);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
      colorRow.getChildren().addAll(colorLabel, colorCombo);

      HBox stackRow = new HBox(6);
      Label stackLabel = new Label("Stacking:");
      stackLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
      ComboBox<AlignmentFile.ReadStackingMode> stackCombo = new ComboBox<>();
      stackCombo.getItems().setAll(AlignmentFile.ReadStackingMode.values());
      stackCombo.setValue(bamFiles.get(0).getReadStackingMode());
      stackCombo.setPrefWidth(200);
      PopupComboBoxStyler.styleDarkComboBox(stackCombo, settingsMenu);
      stackCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
        if (newMode == null) {
          return;
        }
        for (AlignmentFile bam : bamFiles) {
          bam.setReadStackingMode(newMode);
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      });
      stackRow.getChildren().addAll(stackLabel, stackCombo);

      Label stackInfo = new Label("Stacking modes are mutually exclusive.");
      stackInfo.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");
      readRenderBox.getChildren().addAll(readRenderLabel, colorRow, stackRow, stackInfo);
      settingsMenu.getItems().add(new CustomMenuItem(readRenderBox, false));
    }

    boolean hasVcfData = VcfManager.getInstance().hasVcfLoaded();
    if (!bamFiles.isEmpty() || hasVcfData) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      MenuItem circosItem = new MenuItem("Circos plot (split reads + discordant pairs)...");
      circosItem.setStyle("-fx-text-fill: #cccccc;");
      circosItem.setOnAction(e -> {
        settingsMenu.hide();
        openCircosPlot();
      });
      settingsMenu.getItems().add(circosItem);
    }

    settingsMenu.show(masterTrackCanvas.getScene().getWindow(), screenX, screenY);
  }

  /**
   * Collect split-read (SA tag) and inter-chromosomal discordant-pair links
   * across every visible sample and open a {@link CircosPlot} window. Uses
   * cached reads when available; otherwise sweeps the BAM/CRAM at the current
   * locus via a streaming query (no caching).
   */
  private void openCircosPlot() {
    final List<DrawStack> stacks = new ArrayList<>(stackManager.getStacks());
    final List<SampleSweep> sweeps = new ArrayList<>();
    for (SampleTrack track : sampleRegistry.getSampleTracks()) {
      for (Sample sample : track.getSamples()) {
        AlignmentFile bamFile = sample.getBamFile();
        if (bamFile == null) {
          continue;
        }
        sweeps.add(new SampleSweep(bamFile, sample.getName(), bamFile.getReader().getRefNames()));
      }
    }

    final List<String> chromNames;
    final Map<String, Long> chromLengths = new LinkedHashMap<>();
    ReferenceGenomeService refSvc = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (refSvc != null && refSvc.getCurrentGenome() != null) {
      chromNames = refSvc.getCurrentGenome().getStandardChromosomeNames();
      for (String n : chromNames) {
        try {
          chromLengths.put(n, refSvc.getChromosomeLength(n));
        } catch (Exception ignore) {
        }
      }
    } else {
      chromNames = new ArrayList<>();
    }

    final String currentChrom = masterTrackDrawStack != null ? masterTrackDrawStack.getChromosome() : null;

    Thread t = new Thread(() -> {
      List<CircosPlot.Link> links = new ArrayList<>();
      for (SampleSweep sw : sweeps) {
        for (DrawStack stack : stacks) {
          String stackChrom = stack.getChromosome();
          if (stackChrom == null) {
            continue;
          }

          List<BAMRecord> cached = sw.file.getCachedReads(stack);
          if (cached != null && !cached.isEmpty()) {
            for (BAMRecord r : cached) {
              extractLinks(r, stackChrom, sw.refNames, sw.name, links);
            }
          } else {
            int start = (int) Math.max(0, stack.getViewStart());
            int end = (int) Math.max(start + 1, stack.getViewEnd());
            try {
              sw.file.getReader().queryStreaming(stackChrom, start, end, r -> {
                extractLinks(r, stackChrom, sw.refNames, sw.name, links);
                return true;
              });
            } catch (java.io.IOException ignore) {
            }
          }
        }
      }

      Platform.runLater(() -> {
        CircosPlot plot = new CircosPlot(
            links,
            chromNames,
            chromLengths,
            currentChrom,
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
    if (r.isSecondary() || r.isSupplementary() || r.isUnmapped()) {
      return;
    }

    if (r.saTag != null && !r.saTag.isBlank()) {
      for (String entry : r.saTag.split(";")) {
        if (entry.isBlank()) {
          continue;
        }
        String[] parts = entry.split(",", -1);
        if (parts.length < 2) {
          continue;
        }
        try {
          int saPos = Integer.parseInt(parts[1]);
          out.add(new CircosPlot.Link(
              stackChrom,
              r.pos + 1,
              parts[0],
              saPos,
              CircosPlot.LinkType.SPLIT_READ,
              sampleName));
        } catch (NumberFormatException ignore) {
        }
      }
    }

    if (r.isPaired() && r.mateRefID >= 0 && r.mateRefID != r.refID
        && r.mateRefID < refNames.length && r.matePos >= 0) {
      out.add(new CircosPlot.Link(
          stackChrom,
          r.pos + 1,
          refNames[r.mateRefID],
          r.matePos + 1,
          CircosPlot.LinkType.DISCORDANT_PAIR,
          sampleName));
    }
  }

  /** Snapshot of a sample's alignment file for off-thread sweeping. */
  private record SampleSweep(AlignmentFile file, String name, String[] refNames) {
  }

  private void drawMasterTrack() {
    double w = masterTrackCanvas.getWidth();
    double h = masterTrackCanvas.getHeight();
    if (w <= 0 || h <= 0) return;

    int trackCount = Math.max(0, masterTrackRenderState.trackCount());

    GraphicsContext gc = masterTrackCanvas.getGraphicsContext2D();
    double headerBarH = Math.min(org.baseplayer.services.SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT, h);
    SidebarBase.drawStandardHeader(gc, w, headerBarH, "Tracks", trackCount);
    double sy = (headerBarH - HEADER_BTN_SIZE) / 2;

    if (masterTrackRenderState.canReload()) {
      double reloadX = reloadBtnX(w);
      gc.setFont(Font.font("Segoe UI Symbol", 14));
      gc.setFill(Color.web("#ff9944"));
      gc.fillText("\u21ba", reloadX + 1, sy + HEADER_BTN_SIZE - 3);
    }

    ExpandedControlsRenderResult expandedResult = new ExpandedControlsRenderResult(null, null, null);
    if (masterTrackRenderState.controlsExpanded()) {
      expandedResult = renderExpandedControls(
          gc,
          w,
          h,
          headerBarH,
          trackCount,
          masterTrackRenderState.firstVisible(),
          masterTrackRenderState.lastVisible(),
          masterTrackRenderState.focusedGene(),
          masterTrackRenderState.hasActiveSampleFilterQuery());
    }

    // Update hit boxes
    rangeStartHandleHit = expandedResult != null ? expandedResult.rangeStartHandleHit() : null;
    rangeEndHandleHit = expandedResult != null ? expandedResult.rangeEndHandleHit() : null;
    rangeLabelHit = expandedResult != null ? expandedResult.rangeLabelHit() : null;

    drawHeaderHover(expandedResult.rangeLabelHit());
  }

  private void drawHeaderHover(HitBox rangeLabelHit) {
    GraphicsContext reactiveGc = masterTrackReactiveCanvas.getGraphicsContext2D();
    double w = masterTrackReactiveCanvas.getWidth();
    double h = masterTrackReactiveCanvas.getHeight();
    reactiveGc.clearRect(0, 0, w, h);

    double headerBarH = Math.min(org.baseplayer.services.SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT, masterTrackCanvas.getHeight());
    renderHeaderHover(
        reactiveGc,
        w,
        headerBarH,
        HEADER_BTN_SIZE,
        HEADER_BTN_LEFT_X,
        masterTrackRenderState.settingsHovered(),
        masterTrackRenderState.reloadHovered(),
        masterTrackRenderState.addHovered(),
        masterTrackRenderState.canReload(),
        masterTrackRenderState.highlightRangeLabel(),
        rangeLabelHit);
  }

  // \u2500\u2500 Master track utility methods \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  private static double headerBtnSize() {
    return HEADER_BTN_SIZE;
  }

  private static double headerBtnLeftX() {
    return HEADER_BTN_LEFT_X;
  }

  private static double reloadBtnX(double canvasWidth) {
    return canvasWidth - 2 * (HEADER_BTN_SIZE + 4);
  }

  private static boolean inHeaderBtn(double mx, double my, double bx, double by) {
    return mx >= bx && mx <= bx + HEADER_BTN_SIZE && my >= by && my <= by + HEADER_BTN_SIZE;
  }

  private double getMasterTrackWidth() {
    return masterTrackCanvas.getWidth();
  }

  private void zoomAt(double zoomDirection, double targetX) {
    if (zoomDirection == 0.0 || masterTrackDrawStack.alignmentCanvas == null) return;
    // Use the protected zoom() method via reflection or use zoomAnimation with calculated bounds
    // For now, calculate new zoom bounds manually
    int direction = zoomDirection > 0 ? 1 : -1;
    double acceleration = Math.log(Math.abs(zoomDirection) + 1) / 4.0;
    double currentLength = masterTrackDrawStack.getViewEnd() - masterTrackDrawStack.getViewStart();
    double newSize = currentLength - GenomicCanvas.zoomFactor * acceleration * direction;
    double start = masterTrackDrawStack.getViewStart() + (currentLength - newSize) * (targetX / getMasterTrackWidth());
    double end = start + newSize;
    masterTrackDrawStack.alignmentCanvas.setStartEnd(start, end);
  }

  private void panByGenomeDelta(double genomeDelta) {
    if (masterTrackDrawStack.alignmentCanvas != null) {
      masterTrackDrawStack.alignmentCanvas.setStart(masterTrackDrawStack.getViewStart() - genomeDelta);
    }
  }
}
