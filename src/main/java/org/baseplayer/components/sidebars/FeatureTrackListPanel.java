package org.baseplayer.components.sidebars;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.Track;
import org.baseplayer.features.TrackSettingsPopup;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.TrackViewportRegistry;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;

import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

public class FeatureTrackListPanel extends TrackListPanel {

  private static final double ICON_SIZE = 14;
  private static final double ICON_PADDING = 4;

  private final FeatureTrackViewportRegistry featureTrackViewportRegistry;
  private final TrackSettingsPopup settingsPopup = new TrackSettingsPopup();

  public FeatureTrackListPanel(StackPane parent) {
    this(parent, ServiceRegistry.getInstance().getFeatureTrackViewportRegistry());
  }

  private FeatureTrackListPanel(
      StackPane parent, FeatureTrackViewportRegistry featureTrackViewportRegistry) {
    super(parent, featureTrackViewportRegistry);
    this.featureTrackViewportRegistry = featureTrackViewportRegistry;
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
    return visibleSlotIndex >= 0
            && visibleSlotIndex < featureTrackViewportRegistry.getDisplayedTrackCount()
        ? visibleSlotIndex
        : -1;
  }

  @Override
  protected int getVisibleSlotIndexForBackingTrackIndex(int backingTrackIndex) {
    return backingTrackIndex >= 0
            && backingTrackIndex < featureTrackViewportRegistry.getDisplayedTrackCount()
        ? backingTrackIndex
        : -1;
  }

  @Override
  protected void onAfterVisibleTrackRangeChanged() {
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
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
      return true;
    }
    if ("settings".equals(iconId)) {
      showSettingsPopup(track, screenX, screenY);
      return true;
    }
    if ("remove".equals(iconId)) {
      featureTrackViewportRegistry.removeFeatureTrack(track);
      org.baseplayer.project.ProjectSessionState.get().markDirty();
      draw();
      drawReactive();
      onAfterVisibleTrackRangeChanged();
      return true;
    }
    return false;
  }

  @Override
  protected void drawTrackRows(
      double panelWidthPixels, double panelHeightPixels, double rightUiInsetPixels) {
    gc.setStroke(DrawColors.BORDER);
    double rowHeight = featureTrackViewportRegistry.getTrackRowHeightPixels();
    double scrollOffset =
        featureTrackViewportRegistry.getVerticalScrollOffsetPixels();
    int trackCount = featureTrackViewportRegistry.getDisplayedTrackCount();
    int firstSlot =
        Math.max(0, featureTrackViewportRegistry.getFirstVisibleTrackSlot());
    int lastSlot = Math.min(
        featureTrackViewportRegistry.getLastVisibleTrackSlot(), trackCount - 1);

    for (int slot = firstSlot; slot <= lastSlot; slot++) {
      Track track =
          featureTrackViewportRegistry.getFeatureTrackAtBackingIndex(slot);
      if (track == null) {
        continue;
      }
      double rowY = slot * rowHeight - scrollOffset;
      if (rowY + rowHeight < 0 || rowY > panelHeightPixels) {
        continue;
      }
      drawTrackRow(
          slot, track, rowY, rowHeight, panelWidthPixels - rightUiInsetPixels);
    }
  }

  private void drawTrackRow(
      int backingTrackIndex, Track track, double rowY, double rowHeight,
      double availableWidth) {
    boolean trackVisible = track.isVisible();
    if (rowY >= 0) {
      double snappedY = Math.round(rowY);
      gc.setStroke(DrawColors.BORDER);
      gc.strokeLine(0, snappedY, availableWidth, snappedY);
    }

    double eyeX = ICON_PADDING;
    double eyeY = rowY + (rowHeight - ICON_SIZE) / 2;
    drawEyeIcon(eyeX, eyeY, trackVisible);
    addIconRegion(
        backingTrackIndex, "eye", eyeX, eyeY, ICON_SIZE, ICON_SIZE);

    double settingsX = eyeX + ICON_SIZE + ICON_PADDING;
    drawSettingsIcon(settingsX, eyeY, trackVisible);
    addIconRegion(
        backingTrackIndex, "settings", settingsX, eyeY, ICON_SIZE, ICON_SIZE);

    double textX = settingsX + ICON_SIZE + ICON_PADDING + 2;
    gc.setFill(trackVisible ? Color.web("#cccccc") : Color.web("#666666"));
    gc.setFont(AppFonts.getUIFont(9));
    gc.fillText(track.getName(), textX, rowY + 12);
    gc.setFill(trackVisible ? Color.web("#888888") : Color.web("#555555"));
    gc.setFont(AppFonts.getUIFont(8));
    gc.fillText(track.getType(), textX, rowY + 22);

    double removeX = Math.max(
        availableWidth - ICON_SIZE - ICON_PADDING, textX + 20);
    double removeY = rowY + (rowHeight - ICON_SIZE) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(
        removeX - 1, removeY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
    gc.setFill(Color.web("#cc6666"));
    gc.setFont(AppFonts.getUIFont(10));
    gc.fillText("✕", removeX + 3, removeY + ICON_SIZE - 3);
    addIconRegion(
        backingTrackIndex, "remove", removeX - 1, removeY - 1,
        ICON_SIZE + 2, ICON_SIZE + 2);
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
    double rowHeight = featureTrackViewportRegistry.getTrackRowHeightPixels();
    double rowY = hoverSlot * rowHeight
        - featureTrackViewportRegistry.getVerticalScrollOffsetPixels();
    if (rowY + rowHeight < 0 || rowY > panelHeightPixels) {
      return;
    }

    reactiveGc.setFill(Color.rgb(255, 255, 255, 0.05));
    reactiveGc.fillRect(0, Math.max(rowY, 0), panelWidthPixels, rowHeight);
    double textX =
        ICON_PADDING + ICON_SIZE + ICON_PADDING + ICON_SIZE + ICON_PADDING + 2;
    reactiveGc.setFill(Color.WHITE);
    reactiveGc.setFont(AppFonts.getUIFont(9));
    reactiveGc.fillText(hoveredTrack.getName(), textX, rowY + 12);

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

  private void drawSettingsIcon(double x, double y, boolean trackVisible) {
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 12));
    gc.setFill(
        trackVisible ? Color.rgb(140, 140, 140) : Color.rgb(80, 80, 80));
    gc.fillText("⚙", x + 1, y + ICON_SIZE - 2);
  }

  private void showSettingsPopup(Track track, double screenX, double screenY) {
    if (settingsPopup.isShowing()) {
      settingsPopup.hide();
    }
    settingsPopup.show(
        track,
        () -> {
          draw();
          drawReactive();
          onAfterVisibleTrackRangeChanged();
        },
        canvas.getScene().getWindow(),
        screenX,
        screenY);
  }
}
