package org.baseplayer.components.sidebars;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.components.AppDialog;
import org.baseplayer.components.PopupComboBoxStyler;
import org.baseplayer.components.SampleTrackControls;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.draw.ReadColorMode;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.MenuIcons;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.services.TrackViewportRegistry;
import org.baseplayer.utils.DrawColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;

@SuppressWarnings("this-escape")
public class SampleTrackListPanel extends TrackListPanel {

  private static final double NAME_PAD_X = 8;
  private static final Font NAME_FONT = Font.font("Segoe UI", 12);
  private static final double GROUP_BAR_WIDTH = 4;
  private static final double GROUP_BAR_GAP = 1;
  private static final double GROUP_BAR_STRIDE = GROUP_BAR_WIDTH + GROUP_BAR_GAP;
  private static final double TAG_SPHERE_DIAMETER = 7;
  private static final double TAG_SPHERE_GAP = 1;
  private static final double TAG_COLUMN_WIDTH = TAG_SPHERE_DIAMETER + 3;
  /** Tall enough for name + tag meta line; below this, tags stay as left spheres. */
  private static final double META_TEXT_MIN_ROW =
      SampleTrackControls.MIN_ROW_HEIGHT_FOR_SIDEBAR;

  private static final double ROW_REORDER_DRAG_THRESHOLD_PX = 5;

  private final SampleRegistry sampleRegistry;
  private final Set<Integer> selectedTrackIndices = new LinkedHashSet<>();
  private int selectionAnchorIndex = -1;

  private boolean rowReorderArmed;
  private boolean rowReorderDragging;
  private boolean suppressClickFromReorder;
  private double rowReorderStartY;
  private int rowReorderPressBackingIndex = -1;
  /** Final backing-list index for the moved block start while dragging, or -1. */
  private int rowReorderInsertIndex = -1;

  public SampleTrackListPanel(StackPane parent) {
    this(parent, ServiceRegistry.getInstance().getSampleRegistry());
  }

  private SampleTrackListPanel(StackPane parent, SampleRegistry sampleRegistry) {
    super(parent, sampleRegistry);
    this.sampleRegistry = sampleRegistry;
    sampleRegistry.setTrackIconActionHandler(this::handleTrackRowIconClick);
    sampleRegistry.sampleGroupsRevisionProperty().addListener((obs, oldVal, newVal) -> {
      Runnable refresh = () -> {
        draw();
        onAfterVisibleTrackRangeChanged();
      };
      if (Platform.isFxApplicationThread()) {
        refresh.run();
      } else {
        Platform.runLater(refresh);
      }
    });
    setupRowReorderHandlers();
  }

  private void setupRowReorderHandlers() {
    reactiveCanvas.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onRowReorderPressed);
    reactiveCanvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onRowReorderDragged);
    reactiveCanvas.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onRowReorderReleased);
    reactiveCanvas.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
      if (suppressClickFromReorder) {
        suppressClickFromReorder = false;
        event.consume();
      }
    });
  }

  private void onRowReorderPressed(MouseEvent event) {
    if (!event.isPrimaryButtonDown() || suppressRowHover(event.getX(), event.getY())) {
      return;
    }
    int backingTrackIndex = findRowAt(event.getY());
    if (backingTrackIndex < 0) {
      return;
    }
    if (findIconAt(event.getX(), event.getY(), backingTrackIndex) != null) {
      return;
    }
    rowReorderArmed = true;
    rowReorderDragging = false;
    rowReorderStartY = event.getY();
    rowReorderPressBackingIndex = backingTrackIndex;
    rowReorderInsertIndex = -1;
  }

  private void onRowReorderDragged(MouseEvent event) {
    if (!rowReorderArmed && !rowReorderDragging) {
      return;
    }
    if (!rowReorderDragging) {
      if (Math.abs(event.getY() - rowReorderStartY) < ROW_REORDER_DRAG_THRESHOLD_PX) {
        return;
      }
      rowReorderDragging = true;
      reactiveCanvas.setCursor(Cursor.CLOSED_HAND);
    }
    rowReorderInsertIndex = computeReorderInsertIndex(event.getY());
    event.consume();
    drawReactive();
  }

  private void onRowReorderReleased(MouseEvent event) {
    if (!rowReorderArmed && !rowReorderDragging) {
      return;
    }
    boolean didDrag = rowReorderDragging;
    int insertIndex = rowReorderInsertIndex;
    int pressIndex = rowReorderPressBackingIndex;
    rowReorderArmed = false;
    rowReorderDragging = false;
    rowReorderPressBackingIndex = -1;
    rowReorderInsertIndex = -1;
    reactiveCanvas.setCursor(Cursor.DEFAULT);

    if (!didDrag) {
      return;
    }
    suppressClickFromReorder = true;
    event.consume();

    List<SampleTrack> moving = tracksForReorder(pressIndex);
    if (!moving.isEmpty() && insertIndex >= 0) {
      sampleRegistry.moveSampleTracks(moving, insertIndex);
      // Selection indices follow reindex via track identity.
      resyncSelectionAfterReorder(moving);
      draw();
      onAfterVisibleTrackRangeChanged();
    } else {
      drawReactive();
    }
  }

  /** Tracks to move: current multi-selection, or the pressed row if it wasn't selected. */
  private List<SampleTrack> tracksForReorder(int pressBackingIndex) {
    if (pressBackingIndex >= 0 && !selectedTrackIndices.contains(pressBackingIndex)) {
      if (pressBackingIndex < sampleRegistry.getSampleTracks().size()) {
        return List.of(sampleRegistry.getSampleTracks().get(pressBackingIndex));
      }
      return List.of();
    }
    return selectedTracksInListOrder();
  }

  private void resyncSelectionAfterReorder(List<SampleTrack> tracks) {
    selectedTrackIndices.clear();
    for (SampleTrack track : tracks) {
      int index = track.getRegistryIndex();
      if (index >= 0) {
        selectedTrackIndices.add(index);
      }
    }
    selectionAnchorIndex = selectedTrackIndices.isEmpty()
        ? -1
        : selectedTrackIndices.iterator().next();
  }

  /**
   * Map pointer Y to the final backing-list index where the moved block should start
   * (insert-before using the row midpoint).
   */
  private int computeReorderInsertIndex(double y) {
    List<Integer> displayed = sampleRegistry.getDisplayedTrackIndices();
    int trackCount = sampleRegistry.getSampleTracks().size();
    if (displayed.isEmpty() || trackCount <= 0) {
      return 0;
    }
    double rowHeight = sampleRegistry.getTrackRowHeightPixels();
    if (rowHeight <= 0) {
      return 0;
    }
    int slot = findVisibleSlotIndexAtVerticalCoordinate(y);
    if (slot < 0) {
      double scroll = sampleRegistry.getVerticalScrollOffsetPixels();
      if (y + scroll <= 0) {
        return displayed.get(0);
      }
      int lastBacking = displayed.get(displayed.size() - 1);
      return Math.min(trackCount, lastBacking + 1);
    }
    double scroll = sampleRegistry.getVerticalScrollOffsetPixels();
    double rowTop = slot * rowHeight - scroll;
    boolean after = (y - rowTop) >= rowHeight * 0.5;
    int insertDisplayedSlot = after ? slot + 1 : slot;
    if (insertDisplayedSlot <= 0) {
      return displayed.get(0);
    }
    if (insertDisplayedSlot >= displayed.size()) {
      int lastBacking = displayed.get(displayed.size() - 1);
      return Math.min(trackCount, lastBacking + 1);
    }
    return displayed.get(insertDisplayedSlot);
  }

  private List<SampleTrack> selectedTracksInListOrder() {
    List<SampleTrack> tracks = new ArrayList<>();
    for (SampleTrack track : sampleRegistry.getSampleTracks()) {
      int index = track.getRegistryIndex();
      if (selectedTrackIndices.contains(index)) {
        tracks.add(track);
      }
    }
    return tracks;
  }

  @Override
  protected TrackViewportRegistry getTrackViewportRegistry() {
    return sampleRegistry;
  }

  @Override
  protected int getDisplayedTrackCount() {
    return sampleRegistry.getDisplayedTrackCount();
  }

  @Override
  protected int getBackingTrackIndexForVisibleSlot(int visibleSlotIndex) {
    List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    return visibleSlotIndex >= 0 && visibleSlotIndex < displayedTrackIndices.size()
        ? displayedTrackIndices.get(visibleSlotIndex)
        : -1;
  }

  @Override
  protected int getVisibleSlotIndexForBackingTrackIndex(int backingTrackIndex) {
    return sampleRegistry.getDisplayedSlotForTrackIndex(backingTrackIndex);
  }

  @Override
  protected void onAfterVisibleTrackRangeChanged() {
    DrawStackManager stacks = ServiceRegistry.getInstance().getDrawStackManager();
    for (org.baseplayer.draw.DrawStack stack : stacks.getStacks()) {
      if (stack.sampleTrackCanvas != null) {
        stack.sampleTrackCanvas.draw();
      }
    }
    draw();
  }

  @Override
  protected boolean handleTrackRowIconClick(
      String iconId, int backingTrackIndex, double screenX, double screenY) {
    if (backingTrackIndex < 0 || backingTrackIndex >= sampleRegistry.getSampleTracks().size()) {
      return false;
    }
    if ("close".equals(iconId)) {
      SampleDataManager.removeSample(backingTrackIndex);
      return true;
    }
    if ("settings".equals(iconId)) {
      showSettingsPopup(backingTrackIndex, screenX, screenY);
      return true;
    }
    if ("add".equals(iconId)) {
      showAddFileMenu(backingTrackIndex, screenX, screenY);
      return true;
    }
    if ("reload".equals(iconId)) {
      SampleTrack sampleTrack = sampleRegistry.getSampleTracks().get(backingTrackIndex);
      for (Sample sample : sampleTrack.getSamples()) {
        if (sample.isSuspended()) {
          sample.resume();
          // AlignmentFile tracks "Loading reads" for each fetch (cancel stops that fetch).
        }
      }
      draw();
      onAfterVisibleTrackRangeChanged();
      return true;
    }
    return false;
  }

  @Override
  protected boolean handleTrackRowClick(MouseEvent event, int backingTrackIndex) {
    if (backingTrackIndex < 0 || backingTrackIndex >= sampleRegistry.getSampleTracks().size()) {
      return false;
    }
    // Double-click still zooms via TrackListPanel.
    if (event.getClickCount() > 1) {
      return false;
    }

    if (event.isShiftDown() && selectionAnchorIndex >= 0) {
      selectDisplayedRange(selectionAnchorIndex, backingTrackIndex);
      draw();
      drawReactive();
      if (selectedTrackIndices.size() >= 2) {
        promptGroupSelectedTracks(event.getScreenX(), event.getScreenY());
      }
      return true;
    }

    selectedTrackIndices.clear();
    selectedTrackIndices.add(backingTrackIndex);
    selectionAnchorIndex = backingTrackIndex;
    draw();
    drawReactive();
    return true;
  }

  private void selectDisplayedRange(int anchorBackingIndex, int targetBackingIndex) {
    List<Integer> displayed = sampleRegistry.getDisplayedTrackIndices();
    int anchorSlot = displayed.indexOf(anchorBackingIndex);
    int targetSlot = displayed.indexOf(targetBackingIndex);
    if (anchorSlot < 0 || targetSlot < 0) {
      selectedTrackIndices.clear();
      selectedTrackIndices.add(targetBackingIndex);
      selectionAnchorIndex = targetBackingIndex;
      return;
    }
    int from = Math.min(anchorSlot, targetSlot);
    int to = Math.max(anchorSlot, targetSlot);
    selectedTrackIndices.clear();
    for (int slot = from; slot <= to; slot++) {
      selectedTrackIndices.add(displayed.get(slot));
    }
  }

  private void promptGroupSelectedTracks(double screenX, double screenY) {
    List<SampleTrack> tracks = selectedTracks();
    if (tracks.size() < 2) {
      return;
    }
    Window owner = canvas.getScene() != null ? canvas.getScene().getWindow() : null;
    Color initial = DrawColors.SAMPLE_GROUP_COLORS[
        sampleRegistry.getSampleGroups().size() % DrawColors.SAMPLE_GROUP_COLORS.length];
    String suggested = sampleRegistry.suggestNextGroupName();
    int groupedCount = countGroupedSelectedTracks();
    SampleGroupDialog.show(
            owner, tracks.size(), suggested, initial, groupedCount > 0, groupedCount)
        .ifPresent(outcome -> {
          if (outcome instanceof SampleGroupDialog.Outcome.Add add) {
            sampleRegistry.createGroupForTracks(tracks, add.name(), add.color());
            if (add.tags() != null && !add.tags().isEmpty()) {
              sampleRegistry.setTracksTags(tracks, add.tags());
            }
          } else if (outcome instanceof SampleGroupDialog.Outcome.Remove) {
            sampleRegistry.clearTracksFromGroups(tracks);
          }
          clearSelection();
          draw();
          onAfterVisibleTrackRangeChanged();
        });
  }

  private List<SampleTrack> selectedTracks() {
    List<SampleTrack> tracks = new ArrayList<>();
    for (int index : selectedTrackIndices) {
      if (index >= 0 && index < sampleRegistry.getSampleTracks().size()) {
        tracks.add(sampleRegistry.getSampleTracks().get(index));
      }
    }
    return tracks;
  }

  private void clearSelection() {
    selectedTrackIndices.clear();
    selectionAnchorIndex = -1;
  }

  private boolean isSidebarSqueezed() {
    return sampleRegistry.isTrackRowHeightTooSmallForLabels();
  }

  @Override
  protected void drawTrackRows(
      double panelWidthPixels, double panelHeightPixels, double rightUiInsetPixels) {
    gc.setStroke(org.baseplayer.ui.theme.AppTheme.chrome().border());
    List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    if (displayedTrackIndices.isEmpty()) {
      return;
    }

    double contentRight = panelWidthPixels - rightUiInsetPixels;
    boolean squeezed = isSidebarSqueezed();
    double rowHeight = sampleRegistry.getTrackRowHeightPixels();
    double scrollOffset = sampleRegistry.getVerticalScrollOffsetPixels();
    int firstSlot = Math.max(0, sampleRegistry.getFirstVisibleTrackSlot());
    int lastSlot = Math.min(
        sampleRegistry.getLastVisibleTrackSlot(), displayedTrackIndices.size() - 1);
    for (int slot = firstSlot; slot <= lastSlot; slot++) {
      int backingTrackIndex = displayedTrackIndices.get(slot);
      double rowY = slot * rowHeight - scrollOffset;
      if (rowY + rowHeight < 0) {
        continue;
      }

      boolean hasTrack = backingTrackIndex < sampleRegistry.getSampleTracks().size();
      SampleTrack sampleTrack = hasTrack
          ? sampleRegistry.getSampleTracks().get(backingTrackIndex)
          : null;
      boolean trackVisible = sampleTrack == null || sampleTrack.isVisible();
      if (!squeezed && rowY >= 0) {
        double snappedY = Math.round(rowY);
        gc.setStroke(org.baseplayer.ui.theme.AppTheme.chrome().border());
        gc.strokeLine(0, snappedY, contentRight, snappedY);
      }

      double fillY = Math.max(rowY, 0);
      double fillH = Math.min(rowY + rowHeight, panelHeightPixels) - fillY;
      boolean isHovered = backingTrackIndex == hoverIndex;
      List<javafx.scene.paint.Color> groupColors = sampleTrack != null
          ? sampleRegistry.getSidebarColorsForTrack(sampleTrack)
          : List.of();
      List<org.baseplayer.samples.SampleTag> tags = sampleTrack != null
          ? new ArrayList<>(sampleTrack.getTags())
          : List.of();
      boolean selected = selectedTrackIndices.contains(backingTrackIndex);
      boolean showWhiteLead = selected || isHovered;
      boolean showMetaDetail = rowHeight >= META_TEXT_MIN_ROW;
      if (fillH > 0) {
        drawMembershipBars(
            gc, fillY, Math.max(fillH, 1), rowHeight, tags, groupColors, showWhiteLead);
      }

      // When rows are too short for a name, only keep the color accent; hover shows the label.
      if (squeezed) {
        continue;
      }

      double textY = rowY + NAME_FONT.getSize() + 2;
      if (textY > 0) {
        String displayName = sampleTrack != null ? sampleTrack.getDisplayName() : "";
        double nameX = nameTextX(tags.size(), groupColors.size(), showWhiteLead);
        gc.save();
        gc.beginPath();
        gc.rect(0, Math.max(rowY, 0), contentRight, rowHeight);
        gc.clip();
        Font nameFont = (isHovered || selected)
            ? Font.font("Segoe UI", FontWeight.BOLD, 13)
            : NAME_FONT;
        Color nameColor = (isHovered || selected)
            ? AppTheme.canvas().overlayInk()
            : (trackVisible ? AppTheme.chrome().text() : AppTheme.chrome().muted());
        gc.setFont(nameFont);
        drawSampleNameWithFilterHighlight(gc, displayName, nameX, textY, nameColor);
        if (showMetaDetail && sampleTrack != null) {
          drawMetaDetail(gc, sampleTrack, nameX, textY + 14, contentRight - nameX);
        }
        gc.restore();
      }

      if (hasTrack) {
        drawSampleTrackContents(
            backingTrackIndex, rowY, textY, rowHeight, panelWidthPixels, rightUiInsetPixels,
            trackVisible);
      }
    }
  }

  private void drawSampleTrackContents(
      int backingTrackIndex, double rowY, double textY, double rowHeight,
      double panelWidth, double rightUiInset, boolean trackVisible) {
    SampleTrack sampleTrack = sampleRegistry.getSampleTracks().get(backingTrackIndex);
    double contentRight = panelWidth - rightUiInset;
    boolean hasSuspendedSamples =
        sampleTrack.getSamples().stream().anyMatch(Sample::isSuspended);

    boolean showSidebarControls = SampleTrackControls.fitsInSidebar(rowHeight)
        && isMouseOverTrackList()
        && backingTrackIndex == hoverIndex;
    if (!showSidebarControls) {
      return;
    }
    double controlsY = rowY + NAME_FONT.getSize() + SampleTrackControls.NAME_TO_CONTROLS_GAP;
    if (controlsY + SampleTrackControls.stripHeight(SampleTrackControls.SIDEBAR_BUTTON_SIZE)
        > rowY + rowHeight - 2) {
      return;
    }
    String hovered = hoveredIcon;
    List<SampleTrackControls.Hit> hits = SampleTrackControls.drawSidebar(
        gc, contentRight, controlsY, trackVisible, hasSuspendedSamples, hovered);
    for (SampleTrackControls.Hit hit : hits) {
      addIconRegion(
          backingTrackIndex, hit.id(), hit.x(), hit.y(), hit.width(), hit.height());
    }
  }

  @Override
  protected void drawHoveredTrackRowReactiveOverlay(
      double panelWidthPixels, double panelHeightPixels) {
    double contentRight = Math.max(0, panelWidthPixels - getRightUiInsetPixels());
    if (rowReorderDragging && rowReorderInsertIndex >= 0) {
      drawReorderInsertLine(panelHeightPixels, contentRight);
      return;
    }

    if (hoverIndex < 0 || hoverIndex >= sampleRegistry.getSampleTracks().size()) {
      return;
    }
    int hoverSlot = sampleRegistry.getDisplayedSlotForTrackIndex(hoverIndex);
    if (hoverSlot < 0) {
      return;
    }

    double rowHeight = sampleRegistry.getTrackRowHeightPixels();
    double rowY =
        hoverSlot * rowHeight - sampleRegistry.getVerticalScrollOffsetPixels();
    if (rowY + rowHeight < 0 || rowY > panelHeightPixels) {
      return;
    }

    if (isSidebarSqueezed()) {
      SampleTrack sampleTrack = sampleRegistry.getSampleTracks().get(hoverIndex);
      String displayName = sampleTrack.getDisplayName();
      // Line marks the hovered track; name sits above it (may overlap prior rows).
      double lineY = Math.floor(rowY) + 0.5;
      reactiveGc.setStroke(AppTheme.canvas().overlayInk());
      reactiveGc.setLineWidth(1);
      reactiveGc.strokeLine(0, lineY, contentRight, lineY);

      reactiveGc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
      javafx.scene.text.Text measure = new javafx.scene.text.Text(displayName);
      measure.setFont(reactiveGc.getFont());
      double textWidth = measure.getLayoutBounds().getWidth() + 10;
      double labelTop = Math.max(0, lineY - 16);
      reactiveGc.setFill(Color.rgb(0, 0, 0, 0.72));
      reactiveGc.fillRoundRect(2, labelTop, Math.min(contentRight - 4, Math.max(24, textWidth)), 15, 3, 3);
      drawSampleNameWithFilterHighlight(
          reactiveGc, displayName, 6, labelTop + 12, AppTheme.canvas().overlayInk());
      return;
    }

    // Hover white lead is already painted on the main canvas when the row is
    // selected; only add it here for hover-without-selection.
    if (!selectedTrackIndices.contains(hoverIndex)) {
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.9));
      reactiveGc.fillRect(0, Math.max(rowY, 0), GROUP_BAR_WIDTH, rowHeight);
    }
    if (hoveredIcon != null) {
      drawIconGlow(hoveredIcon, hoverIndex);
    }
  }

  private void drawReorderInsertLine(double panelHeightPixels, double contentRight) {
    double rowHeight = sampleRegistry.getTrackRowHeightPixels();
    if (rowHeight <= 0) {
      return;
    }
    List<Integer> displayed = sampleRegistry.getDisplayedTrackIndices();
    if (displayed.isEmpty()) {
      return;
    }
    int insertBacking = rowReorderInsertIndex;
    int insertSlot;
    if (insertBacking >= sampleRegistry.getSampleTracks().size()) {
      insertSlot = displayed.size();
    } else {
      insertSlot = sampleRegistry.getDisplayedSlotForTrackIndex(insertBacking);
      if (insertSlot < 0) {
        // Target is filtered out — fall back to nearest displayed boundary.
        insertSlot = 0;
        for (int i = 0; i < displayed.size(); i++) {
          if (displayed.get(i) >= insertBacking) {
            insertSlot = i;
            break;
          }
          insertSlot = i + 1;
        }
      }
    }
    double scroll = sampleRegistry.getVerticalScrollOffsetPixels();
    double lineY = Math.floor(insertSlot * rowHeight - scroll) + 0.5;
    if (lineY < -1 || lineY > panelHeightPixels + 1) {
      return;
    }
    reactiveGc.setStroke(AppTheme.chrome().accent());
    reactiveGc.setLineWidth(2);
    reactiveGc.strokeLine(0, lineY, contentRight, lineY);
    reactiveGc.setFill(AppTheme.chrome().accent());
    reactiveGc.fillOval(-1, lineY - 3.5, 7, 7);
  }

  /**
   * Draw a sample name, highlighting spans that match the active text filter.
   */
  private void drawSampleNameWithFilterHighlight(
      javafx.scene.canvas.GraphicsContext graphics,
      String displayName,
      double nameX,
      double textY,
      Color baseColor) {
    if (displayName == null || displayName.isEmpty()) {
      return;
    }
    List<int[]> spans = sampleRegistry.findActiveFilterMatchSpans(displayName);
    if (spans.isEmpty()) {
      graphics.setFill(baseColor);
      graphics.fillText(displayName, nameX, textY);
      return;
    }

    Font font = graphics.getFont();
    double fontSize = font.getSize();
    Color highlightFill = AppTheme.chrome().warning();
    Color highlightBg = Color.color(
        highlightFill.getRed(), highlightFill.getGreen(), highlightFill.getBlue(), 0.28);

    double cursorX = nameX;
    int cursor = 0;
    for (int[] span : spans) {
      if (span == null || span.length < 2) {
        continue;
      }
      int start = Math.max(cursor, Math.min(displayName.length(), span[0]));
      int end = Math.max(start, Math.min(displayName.length(), span[1]));
      if (start > cursor) {
        String before = displayName.substring(cursor, start);
        graphics.setFill(baseColor);
        graphics.fillText(before, cursorX, textY);
        cursorX += measureTextWidth(before, font);
      }
      if (end > start) {
        String matched = displayName.substring(start, end);
        double matchWidth = measureTextWidth(matched, font);
        graphics.setFill(highlightBg);
        graphics.fillRoundRect(
            cursorX - 1, textY - fontSize + 1, matchWidth + 2, fontSize + 3, 3, 3);
        graphics.setFill(highlightFill);
        graphics.fillText(matched, cursorX, textY);
        cursorX += matchWidth;
      }
      cursor = end;
    }
    if (cursor < displayName.length()) {
      String after = displayName.substring(cursor);
      graphics.setFill(baseColor);
      graphics.fillText(after, cursorX, textY);
    }
  }

  private static double measureTextWidth(String text, Font font) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    javafx.scene.text.Text measure = new javafx.scene.text.Text(text);
    measure.setFont(font);
    return measure.getLayoutBounds().getWidth();
  }

  /**
   * Left accents: optional white lead, stacked tag spheres, then horizontal group bars.
   */
  private void drawMembershipBars(
      javafx.scene.canvas.GraphicsContext graphics,
      double fillY,
      double fillH,
      double rowHeight,
      List<org.baseplayer.samples.SampleTag> tags,
      List<Color> groupColors,
      boolean whiteLead) {
    double x = 0;
    if (whiteLead) {
      graphics.setFill(Color.rgb(255, 255, 255, 0.9));
      graphics.fillRect(x, fillY, GROUP_BAR_WIDTH, fillH);
      x += GROUP_BAR_STRIDE;
    }
    if (tags != null && !tags.isEmpty()) {
      double sphere = Math.min(TAG_SPHERE_DIAMETER, Math.max(4, rowHeight - 2));
      double stackH = tags.size() * sphere + Math.max(0, tags.size() - 1) * TAG_SPHERE_GAP;
      double startY = fillY + Math.max(0, (fillH - stackH) * 0.5);
      for (int i = 0; i < tags.size(); i++) {
        org.baseplayer.samples.SampleTag tag = tags.get(i);
        if (tag == null) {
          continue;
        }
        double sy = startY + i * (sphere + TAG_SPHERE_GAP);
        graphics.setFill(tag.color());
        graphics.fillOval(x, sy, sphere, sphere);
      }
      x += TAG_COLUMN_WIDTH;
    }
    if (groupColors == null || groupColors.isEmpty()) {
      return;
    }
    for (Color color : groupColors) {
      if (color == null) {
        continue;
      }
      graphics.setFill(color);
      graphics.fillRect(x, fillY, GROUP_BAR_WIDTH, fillH);
      x += GROUP_BAR_STRIDE;
    }
  }

  /**
   * Expanded-row detail under the track name: Groups / Tags sections with
   * color bar or sphere before each label.
   */
  private void drawMetaDetail(
      javafx.scene.canvas.GraphicsContext graphics,
      SampleTrack track,
      double x,
      double y,
      double maxWidth) {
    if (maxWidth < 48) {
      return;
    }
    List<SampleGroup> groups = sampleRegistry.getGroupsForTrack(track);
    List<org.baseplayer.samples.SampleTag> tags = new ArrayList<>(track.getTags());
    if (groups.isEmpty() && tags.isEmpty()) {
      return;
    }

    final double lineH = 12;
    final double markerSize = 7;
    final double markerGap = 5;
    double cursorY = y;
    Font headerFont = Font.font("Segoe UI", FontWeight.BOLD, 9);
    Font itemFont = Font.font("Segoe UI", 10);
    Color headerColor = AppTheme.chrome().muted();
    Color itemColor = AppTheme.chrome().text();
    double textMax = Math.max(20, maxWidth - markerSize - markerGap);

    if (!groups.isEmpty()) {
      graphics.setFont(headerFont);
      graphics.setFill(headerColor);
      graphics.fillText("Groups", x, cursorY, maxWidth);
      cursorY += lineH;
      for (SampleGroup group : groups) {
        if (group == null) {
          continue;
        }
        Color color = group.getColor() != null ? group.getColor() : Color.web("#4db8ff");
        graphics.setFill(color);
        graphics.fillRect(x, cursorY - markerSize + 1, 4, markerSize);
        graphics.setFont(itemFont);
        graphics.setFill(itemColor);
        graphics.fillText(group.getName(), x + markerGap + 4, cursorY, textMax);
        cursorY += lineH;
      }
      cursorY += 2;
    }

    if (!tags.isEmpty()) {
      graphics.setFont(headerFont);
      graphics.setFill(headerColor);
      graphics.fillText("Tags", x, cursorY, maxWidth);
      cursorY += lineH;
      for (org.baseplayer.samples.SampleTag tag : tags) {
        if (tag == null) {
          continue;
        }
        graphics.setFill(tag.color());
        graphics.fillOval(x, cursorY - markerSize + 1, markerSize, markerSize);
        graphics.setFont(itemFont);
        graphics.setFill(itemColor);
        graphics.fillText(tag.displayName(), x + markerGap + markerSize, cursorY, textMax);
        cursorY += lineH;
      }
    }
  }

  private static double nameTextX(int tagCount, int groupCount, boolean whiteLead) {
    int lead = whiteLead ? 1 : 0;
    if (tagCount + groupCount + lead <= 0) {
      return NAME_PAD_X;
    }
    double width = lead * GROUP_BAR_STRIDE;
    if (tagCount > 0) {
      width += TAG_COLUMN_WIDTH;
    }
    width += groupCount * GROUP_BAR_STRIDE;
    return width + NAME_PAD_X - GROUP_BAR_GAP;
  }

  private void drawIconGlow(String iconId, int backingTrackIndex) {
    IconRegion region = findIconRegion(iconId, backingTrackIndex);
    if (region == null) {
      return;
    }
    reactiveGc.setFill(Color.rgb(255, 255, 255, 0.24));
    reactiveGc.fillRoundRect(
        region.x() - 2, region.y() - 2, region.width() + 4, region.height() + 4, 5, 5);
    reactiveGc.setStroke(Color.rgb(255, 255, 255, 0.35));
    reactiveGc.strokeRoundRect(
        region.x() - 2, region.y() - 2, region.width() + 4, region.height() + 4, 5, 5);
  }

  private void showSettingsPopup(int sampleIndex, double screenX, double screenY) {
    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIndex);
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle(
        "-fx-background-color: " + AppTheme.chrome().panelHex() + "; -fx-border-color: " + AppTheme.chrome().strokeHex() + "; -fx-border-width: 1;");
    addOpenedFilesMenuItems(settingsMenu, track, sampleIndex);
    addSignalLayoutMenuItems(settingsMenu, track);
    addMoveMenuItems(settingsMenu, track, sampleIndex);
    addSampleGroupMenuItems(settingsMenu, track, sampleIndex);
    addMethylationSettings(settingsMenu, track);
    addHaplotypeInformation(settingsMenu, track);
    addReadRenderingSettings(settingsMenu, track);
    addApplySettingsToAllItem(settingsMenu, track);
    settingsMenu.show(canvas, screenX, screenY);
  }

  private void addMoveMenuItems(ContextMenu settingsMenu, SampleTrack track, int sampleIndex) {
    if (sampleRegistry.getSampleTracks().size() <= 1) {
      return;
    }
    settingsMenu.getItems().add(new SeparatorMenuItem());

    List<SampleTrack> targets = selectedTrackIndices.contains(sampleIndex)
            && selectedTrackIndices.size() > 1
        ? selectedTracksInListOrder()
        : List.of(track);
    int minIndex = Integer.MAX_VALUE;
    int maxIndex = -1;
    for (SampleTrack target : targets) {
      int index = target.getRegistryIndex();
      if (index >= 0) {
        minIndex = Math.min(minIndex, index);
        maxIndex = Math.max(maxIndex, index);
      }
    }
    if (minIndex == Integer.MAX_VALUE) {
      return;
    }

    int trackCount = sampleRegistry.getSampleTracks().size();
    boolean canMoveUp = minIndex > 0;
    boolean canMoveDown = maxIndex >= 0 && maxIndex < trackCount - 1;

    VBox box = new VBox(4);
    box.setPadding(new Insets(4, 8, 4, 8));
    Label header = new Label(targets.size() > 1
        ? "Move " + targets.size() + " samples"
        : "Move sample");
    header.setStyle(
        "-fx-text-fill: " + AppTheme.chrome().mutedHex()
            + "; -fx-font-size: 10; -fx-font-weight: bold;");
    box.getChildren().add(header);

    HBox row = new HBox(4);
    row.setAlignment(Pos.CENTER_LEFT);
    Button toTop = new Button("Top");
    styleMenuActionButton(toTop);
    toTop.setDisable(!canMoveUp);
    toTop.setOnAction(e -> {
      sampleRegistry.moveSampleTracks(targets, 0);
      resyncSelectionAfterReorder(targets);
      draw();
      onAfterVisibleTrackRangeChanged();
      settingsMenu.hide();
    });
    Button up = new Button("Up");
    styleMenuActionButton(up);
    up.setDisable(!canMoveUp);
    int moveUpBefore = minIndex - 1;
    up.setOnAction(e -> {
      sampleRegistry.moveSampleTracks(targets, moveUpBefore);
      resyncSelectionAfterReorder(targets);
      draw();
      onAfterVisibleTrackRangeChanged();
      settingsMenu.hide();
    });
    Button down = new Button("Down");
    styleMenuActionButton(down);
    down.setDisable(!canMoveDown);
    int moveDownBefore = maxIndex + 2;
    down.setOnAction(e -> {
      sampleRegistry.moveSampleTracks(targets, moveDownBefore);
      resyncSelectionAfterReorder(targets);
      draw();
      onAfterVisibleTrackRangeChanged();
      settingsMenu.hide();
    });
    Button toBottom = new Button("Bottom");
    styleMenuActionButton(toBottom);
    toBottom.setDisable(!canMoveDown);
    toBottom.setOnAction(e -> {
      sampleRegistry.moveSampleTracks(targets, trackCount);
      resyncSelectionAfterReorder(targets);
      draw();
      onAfterVisibleTrackRangeChanged();
      settingsMenu.hide();
    });
    row.getChildren().addAll(toTop, up, down, toBottom);
    box.getChildren().add(row);
    settingsMenu.getItems().add(new CustomMenuItem(box, false));
  }

  private void addSignalLayoutMenuItems(ContextMenu settingsMenu, SampleTrack track) {
    boolean hasBigWig = false;
    for (Sample sample : track.getSamples()) {
      if (sample != null && sample.getDataType() == Sample.DataType.BIGWIG) {
        hasBigWig = true;
        break;
      }
    }
    if (!hasBigWig) {
      return;
    }
    settingsMenu.getItems().add(new SeparatorMenuItem());
    Label header = new Label("BigWig layout");
    header.setStyle(
        "-fx-text-fill: " + AppTheme.chrome().mutedHex()
            + "; -fx-font-size: 10; -fx-padding: 4 8 0 8;");
    settingsMenu.getItems().add(new CustomMenuItem(header, false));

    ToggleGroup group = new ToggleGroup();
    RadioMenuItem overlay = new RadioMenuItem("Overlay (transparent)");
    overlay.setToggleGroup(group);
    overlay.setSelected(track.getSignalLayout() != SampleTrack.SignalLayout.STACK);
    overlay.setOnAction(e -> {
      track.setSignalLayout(SampleTrack.SignalLayout.OVERLAY);
      ProjectSessionState.get().markDirty();
      onAfterVisibleTrackRangeChanged();
    });
    RadioMenuItem stack = new RadioMenuItem("Stack lanes");
    stack.setToggleGroup(group);
    stack.setSelected(track.getSignalLayout() == SampleTrack.SignalLayout.STACK);
    stack.setOnAction(e -> {
      track.setSignalLayout(SampleTrack.SignalLayout.STACK);
      ProjectSessionState.get().markDirty();
      onAfterVisibleTrackRangeChanged();
    });
    settingsMenu.getItems().addAll(overlay, stack);
  }

  private void addApplySettingsToAllItem(ContextMenu settingsMenu, SampleTrack track) {
    if (sampleRegistry.getSampleTracks().size() <= 1) {
      return;
    }
    settingsMenu.getItems().add(new SeparatorMenuItem());
    Button applyAll = new Button("Apply settings to all tracks");
    styleMenuActionButton(applyAll);
    applyAll.setMaxWidth(Double.MAX_VALUE);
    applyAll.setOnAction(event -> {
      SampleDataManager.applyTrackSettingsToAll(track);
      draw();
      onAfterVisibleTrackRangeChanged();
      settingsMenu.hide();
    });
    HBox row = new HBox(applyAll);
    row.setPadding(new Insets(4, 8, 6, 8));
    HBox.setHgrow(applyAll, Priority.ALWAYS);
    settingsMenu.getItems().add(new CustomMenuItem(row, false));
  }

  private static void styleMenuActionButton(Button button) {
    button.setStyle(
        "-fx-background-color: " + AppTheme.chrome().elevatedHex()
            + "; -fx-text-fill: " + AppTheme.chrome().textHex()
            + "; -fx-font-size: 11; -fx-font-weight: bold; -fx-padding: 4 10 4 10;"
            + "-fx-border-color: " + AppTheme.chrome().strokeHex()
            + "; -fx-border-width: 1; -fx-background-radius: 3; -fx-border-radius: 3;"
            + "; -fx-cursor: hand;");
  }

  private void addOpenedFilesMenuItems(
      ContextMenu settingsMenu, SampleTrack track, int sampleIndex) {
    VBox filesBox = new VBox(4);
    filesBox.setPadding(new Insets(4, 8, 2, 8));
    Label header = new Label("Opened files");
    header.setStyle("-fx-text-fill: " + AppTheme.chrome().textHex() + "; -fx-font-size: 11; -fx-font-weight: bold;");
    filesBox.getChildren().add(header);

    if (track.getSamples().isEmpty()) {
      Label empty = new Label("No files on this track");
      empty.setStyle("-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 10;");
      filesBox.getChildren().add(empty);
      settingsMenu.getItems().add(new CustomMenuItem(filesBox, false));
      return;
    }

    settingsMenu.getItems().add(new CustomMenuItem(filesBox, false));
    for (int fileIndex = 0; fileIndex < track.getSamples().size(); fileIndex++) {
      settingsMenu.getItems().add(
          buildTrackRow(track.getSamples().get(fileIndex), track, fileIndex, sampleIndex));
    }
  }

  private void addSampleGroupMenuItems(ContextMenu settingsMenu, SampleTrack track, int sampleIndex) {
    settingsMenu.getItems().add(new SeparatorMenuItem());

    VBox section = new VBox(6);
    section.setPadding(new Insets(4, 8, 6, 8));

    HBox tagRow = new HBox(4);
    tagRow.setAlignment(Pos.CENTER_LEFT);
    for (org.baseplayer.samples.SampleTag tag : org.baseplayer.samples.SampleTag.values()) {
      Button chip = new Button(tag.shortLabel());
      boolean on = track.hasTag(tag);
      styleTagChipButton(chip, tag, on);
      chip.setTooltip(new javafx.scene.control.Tooltip(
          tag.displayName() + " — " + tag.description()));
      chip.setOnAction(e -> {
        List<SampleTrack> targets = selectedTrackIndices.contains(sampleIndex)
                && selectedTrackIndices.size() > 1
            ? selectedTracks()
            : List.of(track);
        if (targets.size() == 1) {
          sampleRegistry.toggleTrackTag(track, tag);
        } else if (track.hasTag(tag)) {
          for (SampleTrack t : targets) {
            if (t.hasTag(tag)) {
              sampleRegistry.toggleTrackTag(t, tag);
            }
          }
        } else {
          sampleRegistry.addTagToTracks(targets, tag);
        }
        styleTagChipButton(chip, tag, track.hasTag(tag));
        draw();
        onAfterVisibleTrackRangeChanged();
      });
      tagRow.getChildren().add(chip);
    }
    if (track.hasAnyTag()) {
      Button clearTags = new Button("Clear");
      styleMenuActionButton(clearTags);
      clearTags.setOnAction(e -> {
        sampleRegistry.clearTagsFromTracks(List.of(track));
        draw();
        onAfterVisibleTrackRangeChanged();
        settingsMenu.hide();
      });
      tagRow.getChildren().add(clearTags);
    }
    section.getChildren().add(tagRow);

    List<SampleGroup> memberships = sampleRegistry.getGroupsForTrack(track);
    if (!memberships.isEmpty()) {
      SampleGroup primary = memberships.get(0);
      HBox groupRow = new HBox(6);
      groupRow.setAlignment(Pos.CENTER_LEFT);

      ColorPicker colorPicker = new ColorPicker(primary.getColor());
      colorPicker.setPrefWidth(42);
      colorPicker.setMaxWidth(42);
      colorPicker.setStyle("-fx-color-label-visible: false;");
      colorPicker.valueProperty().addListener((obs, oldColor, newColor) -> {
        if (newColor != null) {
          sampleRegistry.setGroupColor(primary.getId(), newColor);
          draw();
        }
      });

      TextField nameField = new TextField(primary.getName());
      nameField.setPrefWidth(120);
      nameField.setStyle(
          "-fx-background-color: " + AppTheme.chrome().elevatedHex()
              + "; -fx-text-fill: " + AppTheme.chrome().textHex()
              + "; -fx-border-color: " + AppTheme.chrome().strokeHex()
              + "; -fx-font-size: 11; -fx-padding: 3 6 3 6;");
      Runnable commitName = () -> {
        String next = nameField.getText();
        if (next != null && !next.isBlank()) {
          sampleRegistry.renameSampleGroup(primary.getId(), next.trim());
          draw();
        } else {
          nameField.setText(primary.getName());
        }
      };
      nameField.setOnAction(e -> commitName.run());
      nameField.focusedProperty().addListener((obs, was, is) -> {
        if (was && !is) {
          commitName.run();
        }
      });
      HBox.setHgrow(nameField, Priority.ALWAYS);

      groupRow.getChildren().addAll(colorPicker, nameField);
      if (memberships.size() > 1) {
        Label more = new Label("+" + (memberships.size() - 1));
        more.setStyle("-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 10;");
        more.setTooltip(new javafx.scene.control.Tooltip(
            memberships.stream()
                .skip(1)
                .map(SampleGroup::getName)
                .collect(java.util.stream.Collectors.joining(", "))));
        groupRow.getChildren().add(more);
      }
      section.getChildren().add(groupRow);
    }

    Button organize = new Button("Sample Groups…");
    styleMenuActionButton(organize);
    organize.setMaxWidth(Double.MAX_VALUE);
    organize.setOnAction(event -> {
      Window owner = canvas.getScene() != null ? canvas.getScene().getWindow() : null;
      settingsMenu.hide();
      SampleOrganizationWindow.show(owner);
    });
    section.getChildren().add(organize);

    settingsMenu.getItems().add(new CustomMenuItem(section, false));
  }

  private static void styleTagChipButton(
      Button button, org.baseplayer.samples.SampleTag tag, boolean on) {
    button.setStyle(
        "-fx-background-color: "
            + (on ? tag.toCssHex() : AppTheme.chrome().elevatedHex()) + ";"
            + "-fx-text-fill: "
            + (on ? "#111111" : AppTheme.chrome().textHex()) + ";"
            + "-fx-font-size: 10; -fx-font-weight: bold; -fx-padding: 3 8 3 8;"
            + "-fx-background-radius: 3; -fx-border-radius: 3;"
            + "-fx-border-color: "
            + (on ? tag.toCssHex() : AppTheme.chrome().strokeHex()) + ";"
            + "-fx-border-width: 1; -fx-cursor: hand;");
  }

  private int countGroupedSelectedTracks() {
    int count = 0;
    for (SampleTrack selected : selectedTracks()) {
      if (selected.hasGroup()) {
        count++;
      }
    }
    return count;
  }

  private void addMethylationSettings(ContextMenu settingsMenu, SampleTrack track) {
    if (!track.hasBamSample()) {
      return;
    }
    settingsMenu.getItems().add(new SeparatorMenuItem());
    VBox methylationBox = new VBox(4);
    methylationBox.setPadding(new Insets(4, 8, 4, 8));
    Label label = new Label(track.hasMethylationData()
        ? "🧬 Methylation tags detected (MM/ML/XM)"
        : "🧬 Methylation / Bisulfite sequencing");
    label.setStyle(track.hasMethylationData()
        ? "-fx-text-fill: #88ccff; -fx-font-size: 11; -fx-font-weight: bold;"
        : "-fx-text-fill: " + AppTheme.chrome().secondaryHex() + "; -fx-font-size: 11; -fx-font-weight: bold;");
    CheckBox hideMismatches =
        new CheckBox("Hide bisulfite mismatches (C→T / G→A)");
    hideMismatches.setSelected(track.hasMethylationData());
    hideMismatches.getStyleClass().add("dark-checkbox");
    hideMismatches.setStyle("-fx-font-size: 11;");
    hideMismatches.selectedProperty().addListener((observable, oldValue, newValue) -> {
      for (Sample sample : track.getSamples()) {
        if (sample.getDataType() == Sample.DataType.BAM) {
          sample.setSuppressMethylMismatches(newValue);
        }
      }
      onAfterVisibleTrackRangeChanged();
    });
    Label information =
        new Label("Enable for emSeq/WGBS data to hide C→T conversions");
    information.setStyle("-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 9;");

    boolean anyCoverageOnly = false;
    for (Sample sample : track.getSamples()) {
      if (sample.isCoverageOnlyMode()) {
        anyCoverageOnly = true;
        break;
      }
    }
    CheckBox coverageOnly =
        new CheckBox("Coverage only (skip reads — saves memory)");
    coverageOnly.setSelected(anyCoverageOnly);
    coverageOnly.getStyleClass().add("dark-checkbox");
    coverageOnly.setStyle("-fx-font-size: 11;");
    coverageOnly.selectedProperty().addListener((observable, oldValue, newValue) -> {
      for (Sample sample : track.getSamples()) {
        if (sample.getDataType() == Sample.DataType.BAM) {
          sample.setCoverageOnlyMode(Boolean.TRUE.equals(newValue));
        }
      }
      onAfterVisibleTrackRangeChanged();
    });
    Label coverageOnlyInfo =
        new Label("Keeps coverage + methylation lines; does not pack read piles");
    coverageOnlyInfo.setStyle("-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 9;");

    methylationBox.getChildren().addAll(
        label, hideMismatches, information, coverageOnly, coverageOnlyInfo);
    settingsMenu.getItems().add(new CustomMenuItem(methylationBox, false));
  }

  private void addHaplotypeInformation(ContextMenu settingsMenu, SampleTrack track) {
    if (!track.hasHaplotypeData()) {
      return;
    }
    settingsMenu.getItems().add(new SeparatorMenuItem());
    VBox haplotypeBox = new VBox(4);
    haplotypeBox.setPadding(new Insets(4, 8, 4, 8));
    Label label = new Label("\uD83E\uDDE9 Phased haplotype data (HP tags)");
    label.setStyle("-fx-text-fill: #88eebb; -fx-font-size: 11; -fx-font-weight: bold;");
    Label information = new Label("Reads shown in allele-split butterfly view:");
    information.setStyle("-fx-text-fill: " + AppTheme.chrome().secondaryHex() + "; -fx-font-size: 10;");
    Label direction = new Label("HP1 = top (up), HP2 = bottom (down)");
    direction.setStyle("-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 10;");
    haplotypeBox.getChildren().addAll(label, information, direction);
    settingsMenu.getItems().add(new CustomMenuItem(haplotypeBox, false));
  }

  private void addReadRenderingSettings(ContextMenu settingsMenu, SampleTrack track) {
    Sample primaryBam = null;
    for (Sample sample : track.getSamples()) {
      if (sample.getDataType() == Sample.DataType.BAM) {
        primaryBam = sample;
        break;
      }
    }
    if (primaryBam == null) {
      return;
    }
    settingsMenu.getItems().add(new SeparatorMenuItem());
    VBox renderingBox = new VBox(6);
    renderingBox.setPadding(new Insets(4, 8, 4, 8));
    Label renderingLabel = new Label("🎨 Read rendering");
    renderingLabel.setStyle(
        "-fx-text-fill: " + AppTheme.chrome().textHex() + "; -fx-font-size: 11; -fx-font-weight: bold;");

    HBox colorRow = new HBox(6);
    Label colorLabel = new Label("Read color:");
    colorLabel.setStyle("-fx-text-fill: " + AppTheme.chrome().secondaryHex() + "; -fx-font-size: 10;");
    ComboBox<ReadColorMode> colorComboBox = new ComboBox<>();
    colorComboBox.getItems().setAll(ReadColorMode.values());
    colorComboBox.setValue(primaryBam.getReadColorMode());
    colorComboBox.setPrefWidth(190);
    PopupComboBoxStyler.styleDarkComboBox(colorComboBox, settingsMenu);
    colorComboBox.valueProperty().addListener((observable, oldMode, newMode) -> {
      if (newMode != null) {
        for (Sample sample : track.getSamples()) {
          if (sample.getDataType() == Sample.DataType.BAM) {
            sample.setReadColorMode(newMode);
          }
        }
        onAfterVisibleTrackRangeChanged();
      }
    });
    colorRow.getChildren().addAll(colorLabel, colorComboBox);

    HBox stackingRow = new HBox(6);
    Label stackingLabel = new Label("Stacking:");
    stackingLabel.setStyle("-fx-text-fill: " + AppTheme.chrome().secondaryHex() + "; -fx-font-size: 10;");
    ComboBox<AlignmentFile.ReadStackingMode> stackingComboBox = new ComboBox<>();
    stackingComboBox.getItems().setAll(AlignmentFile.ReadStackingMode.values());
    stackingComboBox.setValue(primaryBam.getReadStackingMode());
    stackingComboBox.setPrefWidth(190);
    PopupComboBoxStyler.styleDarkComboBox(stackingComboBox, settingsMenu);
    stackingComboBox.valueProperty().addListener((observable, oldMode, newMode) -> {
      if (newMode != null) {
        for (Sample sample : track.getSamples()) {
          if (sample.getDataType() == Sample.DataType.BAM) {
            sample.setReadStackingMode(newMode);
          }
        }
        onAfterVisibleTrackRangeChanged();
      }
    });
    stackingRow.getChildren().addAll(stackingLabel, stackingComboBox);

    Label stackingInformation =
        new Label("Only one stacking mode can be active at a time.");
    stackingInformation.setStyle("-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 9;");
    renderingBox.getChildren().addAll(
        renderingLabel, colorRow, stackingRow, stackingInformation);
    settingsMenu.getItems().add(new CustomMenuItem(renderingBox, false));
  }

  private void showAddFileMenu(int sampleIndex, double screenX, double screenY) {
    ContextMenu addMenu = new ContextMenu();
    addMenu.setStyle(
        "-fx-background-color: " + AppTheme.chrome().panelHex() + "; -fx-border-color: " + AppTheme.chrome().strokeHex() + "; -fx-border-width: 1;");
    MenuItem bamItem = new MenuItem("Add BAM/CRAM");
    MenuIcons.bam(bamItem);
    bamItem.setOnAction(event -> SampleDataManager.addBamToTrack(sampleIndex));
    MenuItem bedItem = new MenuItem("Add BED");
    MenuIcons.bed(bedItem);
    bedItem.setOnAction(event -> SampleDataManager.addBedToTrack(sampleIndex));
    MenuItem tableItem = new MenuItem("Add table / segments…");
    MenuIcons.bed(tableItem);
    tableItem.setOnAction(event -> SampleDataManager.addTableToTrack(sampleIndex));
    MenuItem vcfItem = new MenuItem("Add VCF");
    MenuIcons.vcf(vcfItem);
    vcfItem.setOnAction(event -> SampleDataManager.addVcfToTrack(sampleIndex));
    MenuItem ab1Item = new MenuItem("Add AB1 / Sanger");
    MenuIcons.ab1(ab1Item);
    ab1Item.setOnAction(event -> SampleDataManager.addAb1ToTrack(sampleIndex));
    addMenu.getItems().addAll(vcfItem, bamItem, bedItem, tableItem, ab1Item);
    addMenu.show(canvas, screenX, screenY);
  }

  private void showRenameDialog(SampleTrack track) {
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(track.getDisplayName());
    dialog.setTitle("Rename Track");
    dialog.setHeaderText("Enter new name for this individual:");
    dialog.setContentText("Name:");
    if (canvas != null && canvas.getScene() != null && canvas.getScene().getWindow() != null) {
      dialog.initOwner(canvas.getScene().getWindow());
    }
    dialog.getDialogPane().setStyle("-fx-background-color: " + AppTheme.chrome().panelHex() + ";");
    dialog.getDialogPane().lookup(".content.label").setStyle("-fx-text-fill: " + AppTheme.chrome().textHex() + ";");
    dialog.getDialogPane().lookup(".header-panel")
        .setStyle("-fx-background-color: " + AppTheme.chrome().elevatedHex() + ";");
    dialog.setOnShown(e -> {
      Window w = dialog.getDialogPane().getScene() != null
          ? dialog.getDialogPane().getScene().getWindow()
          : null;
      AppDialog.scheduleInputFocus(w, dialog.getEditor(), true);
    });
    dialog.showAndWait().ifPresent(newName -> {
      if (!newName.trim().isEmpty()) {
        track.setCustomName(newName.trim());
        draw();
        onAfterVisibleTrackRangeChanged();
      }
    });
  }

  private CustomMenuItem buildTrackRow(
      Sample file, SampleTrack track, int fileIndex, int sampleIndex) {
    HBox row = new HBox(6);
    row.setStyle("-fx-padding: 2 4 2 4;");
    CheckBox visibilityCheckBox = new CheckBox();
    visibilityCheckBox.setSelected(file.visible);
    visibilityCheckBox.getStyleClass().add("dark-checkbox");
    visibilityCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> {
      if (file.getDataType() == Sample.DataType.VCF) {
        SampleDataManager.applyVcfSampleVisibility(track, file, newValue);
      } else {
        file.visible = newValue;
      }
      onAfterVisibleTrackRangeChanged();
    });

    CheckBox transparentCheckBox = new CheckBox("Transparent");
    transparentCheckBox.setSelected(file.overlay);
    transparentCheckBox.getStyleClass().add("dark-checkbox");
    transparentCheckBox.setStyle("-fx-font-size: 10;");
    transparentCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> {
      if (file.getDataType() == Sample.DataType.VCF) {
        SampleDataManager.applyVcfSampleOverlay(file, newValue);
      } else {
        file.overlay = newValue;
        onAfterVisibleTrackRangeChanged();
      }
    });

    Label typeLabel = new Label("[" + file.getDataType().name() + "]");
    typeLabel.setStyle("-fx-text-fill: " + AppTheme.chrome().mutedHex() + "; -fx-font-size: 10;");
    Label nameLabel = new Label(file.getName());
    nameLabel.setStyle("-fx-text-fill: " + AppTheme.chrome().textHex() + "; -fx-font-size: 12; -fx-cursor: hand;");
    nameLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(nameLabel, Priority.ALWAYS);
    nameLabel.setOnMouseClicked(event -> {
      if (event.getClickCount() == 1) {
        showRenameDialog(track);
      }
    });

    Label removeButton = new Label("✕");
    removeButton.setStyle(
        "-fx-text-fill: #cc6666; -fx-cursor: hand; -fx-font-size: 11; -fx-padding: 0 2 0 4;");
    CustomMenuItem menuItem = new CustomMenuItem(row, false);
    removeButton.setOnMouseClicked(event -> {
      event.consume();
      ContextMenu owner = menuItem.getParentPopup();
      if (owner != null) {
        owner.hide();
      }
      SampleDataManager.removeFileFromTrack(sampleIndex, file);
      draw();
      onAfterVisibleTrackRangeChanged();
    });
    row.getChildren().addAll(
        visibilityCheckBox, typeLabel, nameLabel, transparentCheckBox, removeButton);
    return menuItem;
  }
}
