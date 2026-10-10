package org.baseplayer.components.sidebars;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.components.SampleTrackControls;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BedVariantAnnotation;
import org.baseplayer.features.Track;
import org.baseplayer.features.TrackSettingsPopup;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.TrackViewportRegistry;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.AppFonts;

import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class FeatureTrackListPanel extends TrackListPanel {

  private static final Font NAME_FONT = Font.font("Segoe UI", 13);
  private static final double ICON_SIZE = 14;
  private static final double ICON_PADDING = 4;

  private final FeatureTrackViewportRegistry featureTrackViewportRegistry;
  private final TrackSettingsPopup settingsPopup = new TrackSettingsPopup();
  private int selectionAnchorIndex = -1;

  public FeatureTrackListPanel(StackPane parent) {
    this(parent, ServiceRegistry.getInstance().getFeatureTrackViewportRegistry());
  }

  private FeatureTrackListPanel(
      StackPane parent, FeatureTrackViewportRegistry featureTrackViewportRegistry) {
    super(parent, featureTrackViewportRegistry);
    this.featureTrackViewportRegistry = featureTrackViewportRegistry;
    featureTrackViewportRegistry.setTrackIconActionHandler(this::handleTrackRowIconClick);
    featureTrackViewportRegistry.selectionRevisionProperty().addListener((obs, o, n) -> {
      draw();
      drawReactive();
    });
    BedVariantAnnotation.uiPulseProperty().addListener((obs, o, n) -> {
      draw();
      drawReactive();
    });
  }

  @Override
  protected TrackViewportRegistry getTrackViewportRegistry() {
    return featureTrackViewportRegistry;
  }

  @Override
  protected int getDisplayedTrackCount() {
    return featureTrackViewportRegistry.getDisplayedTrackCount();
  }

  @Override
  protected int getBackingTrackIndexForVisibleSlot(int visibleSlotIndex) {
    List<Integer> displayed = featureTrackViewportRegistry.getDisplayedTrackIndices();
    if (visibleSlotIndex < 0 || visibleSlotIndex >= displayed.size()) {
      return -1;
    }
    return displayed.get(visibleSlotIndex);
  }

  @Override
  protected int getVisibleSlotIndexForBackingTrackIndex(int backingTrackIndex) {
    return featureTrackViewportRegistry.getDisplayedSlotForTrackIndex(backingTrackIndex);
  }

  @Override
  protected void onAfterVisibleTrackRangeChanged() {
    DrawStackManager stacks = ServiceRegistry.getInstance().getDrawStackManager();
    for (org.baseplayer.draw.DrawStack stack : stacks.getStacks()) {
      if (stack.featureTrackCanvas != null) {
        stack.featureTrackCanvas.draw();
      }
      if (stack.featureAggregateCanvas != null) {
        stack.featureAggregateCanvas.draw();
      }
    }
    draw();
  }

  @Override
  protected boolean handleTrackRowClick(MouseEvent event, int backingTrackIndex) {
    if (backingTrackIndex < 0
        || backingTrackIndex >= featureTrackViewportRegistry.getFeatureTracks().size()) {
      return false;
    }
    if (event.getClickCount() > 1) {
      return false;
    }

    Set<Integer> selected = new LinkedHashSet<>(
        featureTrackViewportRegistry.getSelectedTrackIndices());
    if (event.isShiftDown() && selectionAnchorIndex >= 0) {
      selectDisplayedRange(selectionAnchorIndex, backingTrackIndex, selected);
      featureTrackViewportRegistry.setSelectedTrackIndices(selected);
      return true;
    }

    selected.clear();
    selected.add(backingTrackIndex);
    selectionAnchorIndex = backingTrackIndex;
    featureTrackViewportRegistry.setSelectedTrackIndices(selected);
    return true;
  }

  private void selectDisplayedRange(
      int anchorBackingIndex, int targetBackingIndex, Set<Integer> into) {
    List<Integer> displayed = featureTrackViewportRegistry.getDisplayedTrackIndices();
    int anchorSlot = displayed.indexOf(anchorBackingIndex);
    int targetSlot = displayed.indexOf(targetBackingIndex);
    into.clear();
    if (anchorSlot < 0 || targetSlot < 0) {
      into.add(targetBackingIndex);
      selectionAnchorIndex = targetBackingIndex;
      return;
    }
    int from = Math.min(anchorSlot, targetSlot);
    int to = Math.max(anchorSlot, targetSlot);
    for (int slot = from; slot <= to; slot++) {
      into.add(displayed.get(slot));
    }
  }

  @Override
  protected boolean handleTrackRowIconClick(
      String iconId, int backingTrackIndex, double screenX, double screenY) {
    Track track =
        featureTrackViewportRegistry.getFeatureTrackAtBackingIndex(backingTrackIndex);
    if (track == null) {
      return false;
    }
    if ("eye".equals(iconId)) {
      track.setVisible(!track.isVisible());
      org.baseplayer.project.ProjectSessionState.get().markDirty();
      featureTrackViewportRegistry.bumpAggregateRevision();
      if (track.isVisible()) {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (org.baseplayer.draw.DrawStack stack : stackManager.getStacks()) {
          if (stack.featureTrackCanvas != null) {
            stack.featureTrackCanvas.forceNotifyRegionChanged();
          }
        }
      }
      draw();
      drawReactive();
      onAfterVisibleTrackRangeChanged();
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      return true;
    }
    if ("settings".equals(iconId)) {
      showSettingsPopup(track, screenX, screenY);
      return true;
    }
    if ("bed-filter-toggle".equals(iconId)) {
      if (track instanceof BedTrack bed
          && bed.getVariantAnnotationMode().filtersVisibility()) {
        BedVariantAnnotation.toggleFilterRunning();
        draw();
        drawReactive();
      }
      return true;
    }
    if ("close".equals(iconId) || "remove".equals(iconId)) {
      featureTrackViewportRegistry.removeFeatureTrack(track);
      org.baseplayer.project.ProjectSessionState.get().markDirty();
      draw();
      drawReactive();
      onAfterVisibleTrackRangeChanged();
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      return true;
    }
    if ("add".equals(iconId)) {
      // Master add menu covers file open; per-row add is a no-op for features.
      return true;
    }
    return false;
  }

  @Override
  protected void drawTrackRows(
      double panelWidthPixels, double panelHeightPixels, double rightUiInsetPixels) {
    gc.setStroke(AppTheme.chrome().border());
    List<Integer> displayed = featureTrackViewportRegistry.getDisplayedTrackIndices();
    if (displayed.isEmpty()) {
      return;
    }

    double contentRight = panelWidthPixels - rightUiInsetPixels;
    boolean squeezed = featureTrackViewportRegistry.isTrackRowHeightTooSmallForLabels();
    double rowHeight = featureTrackViewportRegistry.getTrackRowHeightPixels();
    double scrollOffset = featureTrackViewportRegistry.getVerticalScrollOffsetPixels();
    int firstSlot = Math.max(0, featureTrackViewportRegistry.getFirstVisibleTrackSlot());
    int lastSlot = Math.min(
        featureTrackViewportRegistry.getLastVisibleTrackSlot(), displayed.size() - 1);
    Set<Integer> selected = featureTrackViewportRegistry.getSelectedTrackIndices();

    for (int slot = firstSlot; slot <= lastSlot; slot++) {
      int backingTrackIndex = displayed.get(slot);
      Track track =
          featureTrackViewportRegistry.getFeatureTrackAtBackingIndex(backingTrackIndex);
      if (track == null) {
        continue;
      }
      double rowY = slot * rowHeight - scrollOffset;
      if (rowY + rowHeight < 0 || rowY > panelHeightPixels) {
        continue;
      }

      boolean trackVisible = track.isVisible();
      boolean aggregateDisabled = track.isAggregateDisabled();
      boolean isSelected = selected.contains(backingTrackIndex);
      boolean isHovered = backingTrackIndex == hoverIndex;

      double fillY = Math.max(rowY, 0);
      double fillH = Math.min(rowY + rowHeight, panelHeightPixels) - fillY;
      if (fillH > 0 && (isSelected || isHovered)) {
        gc.setFill(isSelected
            ? Color.rgb(70, 120, 180, 0.22)
            : Color.rgb(255, 255, 255, 0.05));
        gc.fillRect(0, fillY, contentRight, fillH);
      }

      if (!squeezed && rowY >= 0) {
        double snappedY = Math.round(rowY);
        gc.setStroke(AppTheme.chrome().border());
        gc.strokeLine(0, snappedY, contentRight, snappedY);
      }

      boolean filterTrack = track instanceof BedTrack bed
          && bed.getVariantAnnotationMode().filtersVisibility();
      double eyeX = ICON_PADDING;
      double eyeY = rowY + Math.max(2, (rowHeight - ICON_SIZE) * 0.5);
      if (eyeY + ICON_SIZE > rowY + rowHeight - 1) {
        eyeY = rowY + 2;
      }

      if (squeezed) {
        continue;
      }

      drawEyeIcon(eyeX, eyeY, trackVisible && !aggregateDisabled);
      addIconRegion(backingTrackIndex, "eye", eyeX, eyeY, ICON_SIZE, ICON_SIZE);

      double textX = eyeX + ICON_SIZE + ICON_PADDING + 2;
      double textY = rowY + NAME_FONT.getSize() + 2;
      gc.save();
      gc.beginPath();
      gc.rect(0, Math.max(rowY, 0), contentRight, rowHeight);
      gc.clip();
      Font nameFont = (isHovered || isSelected)
          ? Font.font("Segoe UI", FontWeight.BOLD, 13)
          : NAME_FONT;
      Color nameColor;
      if (aggregateDisabled) {
        nameColor = AppTheme.chrome().muted();
      } else if (isHovered || isSelected) {
        nameColor = AppTheme.canvas().overlayInk();
      } else if (trackVisible) {
        nameColor = AppTheme.chrome().text();
      } else {
        nameColor = AppTheme.chrome().muted();
      }
      gc.setFont(nameFont);
      gc.setFill(nameColor);
      String displayName = track.getName() + (aggregateDisabled ? "  · agg off" : "");
      drawNameWithFilterHighlight(displayName, textX, textY, nameColor);
      gc.setFill(trackVisible && !aggregateDisabled
          ? AppTheme.chrome().secondary()
          : AppTheme.chrome().stroke());
      gc.setFont(AppFonts.getUIFont(8));
      gc.fillText(track.getType(), textX, textY + 14);
      gc.restore();

      boolean showSidebarControls = SampleTrackControls.fitsInSidebar(rowHeight)
          && isMouseOverTrackList()
          && backingTrackIndex == hoverIndex;
      if (!showSidebarControls) {
        continue;
      }
      double controlsY = rowY + NAME_FONT.getSize() + SampleTrackControls.NAME_TO_CONTROLS_GAP;
      if (controlsY + SampleTrackControls.stripHeight(SampleTrackControls.SIDEBAR_BUTTON_SIZE)
          > rowY + rowHeight - 2) {
        continue;
      }
      List<SampleTrackControls.Hit> hits = SampleTrackControls.drawSidebar(
          gc,
          contentRight,
          controlsY,
          trackVisible,
          false,
          filterTrack,
          BedVariantAnnotation.isFilterRunning(),
          BedVariantAnnotation.isFilterBusy(),
          BedVariantAnnotation.busyAngleDeg(),
          hoveredIcon);
      for (SampleTrackControls.Hit hit : hits) {
        addIconRegion(
            backingTrackIndex, hit.id(), hit.x(), hit.y(), hit.width(), hit.height());
      }
    }
  }

  private void drawNameWithFilterHighlight(
      String displayName, double textX, double textY, Color nameColor) {
    if (displayName == null || displayName.isEmpty()) {
      return;
    }
    List<int[]> spans = featureTrackViewportRegistry.findActiveFilterMatchSpans(displayName);
    if (spans.isEmpty()) {
      gc.setFill(nameColor);
      gc.fillText(displayName, textX, textY);
      return;
    }
    Font font = gc.getFont();
    Color highlightFill = AppTheme.chrome().warning();
    Color highlightBg = Color.color(
        highlightFill.getRed(), highlightFill.getGreen(), highlightFill.getBlue(), 0.28);
    double cursorX = textX;
    int cursor = 0;
    for (int[] span : spans) {
      if (span == null || span.length < 2) {
        continue;
      }
      int start = Math.max(cursor, Math.max(0, span[0]));
      int end = Math.min(displayName.length(), span[1]);
      if (start >= end) {
        continue;
      }
      if (start > cursor) {
        String before = displayName.substring(cursor, start);
        gc.setFill(nameColor);
        gc.fillText(before, cursorX, textY);
        cursorX += measureTextWidth(before, font);
      }
      String match = displayName.substring(start, end);
      double matchW = measureTextWidth(match, font);
      gc.setFill(highlightBg);
      gc.fillRoundRect(cursorX - 1, textY - 11, matchW + 2, 14, 3, 3);
      gc.setFill(nameColor);
      gc.fillText(match, cursorX, textY);
      cursorX += matchW;
      cursor = end;
    }
    if (cursor < displayName.length()) {
      gc.setFill(nameColor);
      gc.fillText(displayName.substring(cursor), cursorX, textY);
    }
  }

  private static double measureTextWidth(String text, Font font) {
    javafx.scene.text.Text measure = new javafx.scene.text.Text(text == null ? "" : text);
    measure.setFont(font);
    return measure.getLayoutBounds().getWidth();
  }

  @Override
  protected void drawHoveredTrackRowReactiveOverlay(
      double panelWidthPixels, double panelHeightPixels) {
    Track hoveredTrack =
        featureTrackViewportRegistry.getFeatureTrackAtBackingIndex(hoverIndex);
    if (hoveredTrack == null) {
      return;
    }
    int hoverSlot = getVisibleSlotIndexForBackingTrackIndex(hoverIndex);
    if (hoverSlot < 0) {
      return;
    }
    double rowHeight = featureTrackViewportRegistry.getTrackRowHeightPixels();
    double rowY = hoverSlot * rowHeight
        - featureTrackViewportRegistry.getVerticalScrollOffsetPixels();
    if (rowY + rowHeight < 0 || rowY > panelHeightPixels) {
      return;
    }

    double contentRight = panelWidthPixels - getRightUiInsetPixels();
    if (!featureTrackViewportRegistry.getSelectedTrackIndices().contains(hoverIndex)) {
      reactiveGc.setFill(Color.rgb(255, 255, 255, 0.05));
      reactiveGc.fillRect(0, Math.max(rowY, 0), contentRight, rowHeight);
    }

    if (featureTrackViewportRegistry.isTrackRowHeightTooSmallForLabels()) {
      reactiveGc.setFill(AppTheme.canvas().overlayInk());
      reactiveGc.setFont(AppFonts.getUIFont(10));
      reactiveGc.fillText(hoveredTrack.getName(), ICON_PADDING, rowY + 12);
    }

    if (hoveredIcon != null) {
      IconRegion region = findIconRegion(hoveredIcon, hoverIndex);
      if (region != null) {
        reactiveGc.setFill(Color.rgb(255, 255, 255, 0.15));
        reactiveGc.fillRoundRect(
            region.x() - 2, region.y() - 2,
            region.width() + 4, region.height() + 4, 4, 4);
      }
    }
  }

  private void drawEyeIcon(double x, double y, boolean visible) {
    double centerX = x + ICON_SIZE / 2;
    double centerY = y + ICON_SIZE / 2;
    if (visible) {
      gc.setFill(Color.rgb(100, 160, 220));
      gc.setStroke(Color.rgb(100, 160, 220));
      gc.setLineWidth(1.2);
      gc.strokeOval(centerX - 5, centerY - 2.5, 10, 5);
      gc.fillOval(centerX - 2, centerY - 2, 4, 4);
    } else {
      gc.setStroke(Color.rgb(100, 100, 100));
      gc.setLineWidth(1.2);
      gc.strokeOval(centerX - 5, centerY - 2.5, 10, 5);
      gc.strokeLine(x + 2, y + ICON_SIZE - 2, x + ICON_SIZE - 2, y + 2);
    }
    gc.setLineWidth(1);
  }

  private void showSettingsPopup(Track track, double screenX, double screenY) {
    if (settingsPopup.isShowing()) {
      settingsPopup.hide();
    }
    settingsPopup.show(
        track,
        () -> {
          featureTrackViewportRegistry.bumpAggregateRevision();
          draw();
          drawReactive();
          onAfterVisibleTrackRangeChanged();
          GenomicCanvas.update.set(!GenomicCanvas.update.get());
        },
        canvas.getScene().getWindow(),
        screenX,
        screenY);
  }
}
