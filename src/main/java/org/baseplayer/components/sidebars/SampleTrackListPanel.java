package org.baseplayer.components.sidebars;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.components.PopupComboBoxStyler;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.draw.ReadColorMode;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.services.TrackViewportRegistry;
import org.baseplayer.utils.DrawColors;

import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;

public class SampleTrackListPanel extends TrackListPanel {

  private static final double ICON_SIZE = 14;
  private static final double ICON_MARGIN = 4;
  private static final Font ICON_FONT = Font.font("Segoe UI Symbol", 12);
  private static final Font NAME_FONT = Font.font("Segoe UI", 12);
  private static final Color TAG_BAM = Color.web("#6699cc");
  private static final Color TAG_BED = Color.web("#cc9966");
  private static final Color TAG_VCF = Color.web("#99cc66");
  private static final Color NAME_VISIBLE = Color.web("#aaaaaa");
  private static final Color NAME_DIM = Color.web("#555555");
  private static final Color OVERLAY_DOT = Color.color(0.6, 0.8, 0.6);
  private static final Color SELECTION_FILL = Color.rgb(77, 184, 255, 0.18);

  private final SampleRegistry sampleRegistry;
  private final Set<Integer> selectedTrackIndices = new LinkedHashSet<>();
  private int selectionAnchorIndex = -1;

  public SampleTrackListPanel(StackPane parent) {
    this(parent, ServiceRegistry.getInstance().getSampleRegistry());
  }

  private SampleTrackListPanel(StackPane parent, SampleRegistry sampleRegistry) {
    super(parent, sampleRegistry);
    this.sampleRegistry = sampleRegistry;
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
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
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
          ThreadRunner.RunnerTask readTask =
              ThreadRunner.get().track("Loading reads: " + sample.getName(), sample::cancelAndSuspend);
          sample.setOnFirstLoadComplete(readTask::complete);
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

  @Override
  protected void drawTrackRows(
      double panelWidthPixels, double panelHeightPixels, double rightUiInsetPixels) {
    gc.setStroke(DrawColors.BORDER);
    List<Integer> displayedTrackIndices = sampleRegistry.getDisplayedTrackIndices();
    if (displayedTrackIndices.isEmpty()) {
      return;
    }

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
      if (rowY >= 0) {
        double snappedY = Math.round(rowY);
        gc.setStroke(DrawColors.BORDER);
        gc.strokeLine(0, snappedY, panelWidthPixels, snappedY);
      }

      double fillY = Math.max(rowY, 0);
      double fillH = Math.min(rowY + rowHeight, panelHeightPixels) - fillY;
      if (fillH > 0) {
        if (selectedTrackIndices.contains(backingTrackIndex)) {
          gc.setFill(SELECTION_FILL);
          gc.fillRect(0, fillY, panelWidthPixels, fillH);
        }
        Color groupColor = sampleTrack != null
            ? sampleRegistry.getSidebarColorForTrack(sampleTrack)
            : null;
        if (groupColor != null) {
          // Accent only — a thin stripe on the left edge of the sample sidebar.
          gc.setFill(groupColor);
          gc.fillRect(0, fillY, 4, fillH);
        }
      }

      double textY = rowY + NAME_FONT.getSize() + 2;
      if (textY > 0) {
        String displayName = sampleTrack != null ? sampleTrack.getDisplayName() : "";
        if (backingTrackIndex == hoverIndex) {
          gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
          double textWidth = displayName.length() * 7.5;
          gc.setFill(Color.rgb(0, 0, 0, 0.3));
          gc.fillRect(8, Math.max(rowY + 2, 0), textWidth + 8, 18);
          gc.setFill(Color.WHITE);
        } else {
          gc.setFont(NAME_FONT);
          gc.setFill(trackVisible ? Color.web("#cccccc") : Color.web("#666666"));
        }
        gc.fillText(displayName, 10, textY);
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
    gc.setFont(Font.font("Segoe UI", 9));
    double fileY = textY + 4;
    for (Sample sample : sampleTrack.getSamples()) {
      fileY += 12;
      if (fileY > rowY + rowHeight - 4) {
        break;
      }
      if (fileY <= 0) {
        continue;
      }

      String tag = sample.getDataType().name();
      Color tagColor = switch (sample.getDataType()) {
        case BAM -> TAG_BAM;
        case BED -> TAG_BED;
        case VCF -> TAG_VCF;
      };
      double alpha = sample.visible ? 1.0 : 0.35;
      gc.setFill(tagColor);
      if (alpha != 1.0) {
        gc.setGlobalAlpha(alpha);
      }
      gc.fillText("[" + tag + "]", 14, fileY);
      if (alpha != 1.0) {
        gc.setGlobalAlpha(1.0);
      }

      gc.setFill(sample.visible ? NAME_VISIBLE : NAME_DIM);
      if (sample.overlay) {
        gc.setGlobalAlpha(0.7);
      }
      gc.fillText(sample.getName(), 44, fileY);
      if (sample.overlay) {
        gc.setGlobalAlpha(1.0);
      }

      if (sample.isSuspended()) {
        gc.setFill(tagColor);
        gc.setGlobalAlpha(0.4);
        gc.fillText("[" + tag + "]", 14, fileY);
        gc.setGlobalAlpha(1.0);
      } else if (sample.overlay) {
        gc.setFill(OVERLAY_DOT);
        gc.setGlobalAlpha(alpha * 0.8);
        gc.fillText("\u25CB", 6, fileY);
        gc.setGlobalAlpha(1.0);
      }
    }

    boolean hasSuspendedSamples =
        sampleTrack.getSamples().stream().anyMatch(Sample::isSuspended);
    if (hasSuspendedSamples) {
      double reloadX = panelWidth - rightUiInset - ICON_SIZE - ICON_MARGIN;
      double reloadY = rowY + rowHeight - ICON_SIZE - ICON_MARGIN;
      gc.setFont(ICON_FONT);
      gc.setFill(Color.web("#ff9944"));
      gc.fillText("\u21ba", reloadX, reloadY + ICON_SIZE - 3);
      addIconRegion(
          backingTrackIndex, "reload", reloadX - 1, reloadY - 1, ICON_SIZE + 2, ICON_SIZE + 2);
    }

    if (isMouseOverTrackList() && backingTrackIndex == hoverIndex) {
      drawSampleButtons(backingTrackIndex, rowY, rowHeight,
          panelWidth - rightUiInset, trackVisible, hasSuspendedSamples);
    }
  }

  @Override
  protected void drawHoveredTrackRowReactiveOverlay(
      double panelWidthPixels, double panelHeightPixels) {
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
    reactiveGc.setFill(Color.rgb(255, 255, 255, 0.05));
    reactiveGc.fillRect(0, Math.max(rowY, 0), panelWidthPixels, rowHeight);
    if (hoveredIcon != null) {
      drawIconGlow(hoveredIcon, hoverIndex);
    }
  }

  private void drawSampleButtons(
      int backingTrackIndex, double rowY, double rowHeight, double availableWidth,
      boolean trackVisible, boolean hasSuspendedSamples) {
    gc.setFont(ICON_FONT);
    double closeX = availableWidth - ICON_SIZE - ICON_MARGIN;
    double closeY = rowY + ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(closeX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
    gc.setFill(Color.web("#cc6666"));
    gc.fillText("✕", closeX + 1, closeY + ICON_SIZE - 3);
    addIconRegion(
        backingTrackIndex, "close", closeX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2);

    double settingsX = closeX - ICON_SIZE - ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(settingsX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
    gc.setFill(trackVisible ? Color.web("#cccccc") : Color.web("#666666"));
    gc.fillText("⚙", settingsX + 1, closeY + ICON_SIZE - 3);
    addIconRegion(
        backingTrackIndex, "settings", settingsX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2);

    double addX = ICON_MARGIN;
    double addY = rowY + rowHeight - ICON_SIZE - ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(addX - 1, addY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
    gc.setFill(Color.web("#88bb88"));
    gc.fillText("+", addX + 2, addY + ICON_SIZE - 3);
    addIconRegion(
        backingTrackIndex, "add", addX - 1, addY - 1, ICON_SIZE + 2, ICON_SIZE + 2);

    if (hasSuspendedSamples) {
      double reloadX = availableWidth - ICON_SIZE - ICON_MARGIN;
      gc.setFill(Color.web("#3c3c3c"));
      gc.fillRoundRect(reloadX - 1, addY - 1, ICON_SIZE + 2, ICON_SIZE + 2, 3, 3);
      gc.setFont(ICON_FONT);
      gc.setFill(Color.web("#ff9944"));
      gc.fillText("\u21ba", reloadX, addY + ICON_SIZE - 3);
    }
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
        "-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");
    for (int fileIndex = 0; fileIndex < track.getSamples().size(); fileIndex++) {
      settingsMenu.getItems().add(
          buildTrackRow(track.getSamples().get(fileIndex), track, fileIndex, sampleIndex));
    }
    addSampleGroupMenuItems(settingsMenu, track, sampleIndex);
    addMethylationSettings(settingsMenu, track);
    addHaplotypeInformation(settingsMenu, track);
    addReadRenderingSettings(settingsMenu, track);
    settingsMenu.show(canvas, screenX, screenY);
  }

  private void addSampleGroupMenuItems(ContextMenu settingsMenu, SampleTrack track, int sampleIndex) {
    settingsMenu.getItems().add(new SeparatorMenuItem());

    VBox groupBox = new VBox(6);
    groupBox.setPadding(new Insets(4, 8, 4, 8));

    Label header = new Label("Sample group");
    header.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11; -fx-font-weight: bold;");
    groupBox.getChildren().add(header);

    SampleGroup current = sampleRegistry.getGroupForTrack(track);
    ColorPicker colorPicker = new ColorPicker(
        current != null
            ? current.getColor()
            : DrawColors.SAMPLE_GROUP_COLORS[
                sampleRegistry.getSampleGroups().size() % DrawColors.SAMPLE_GROUP_COLORS.length]);
    colorPicker.setPrefWidth(150);

    if (current != null) {
      Label currentLabel = new Label("In: " + current.getName());
      currentLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
      groupBox.getChildren().add(currentLabel);

      colorPicker.valueProperty().addListener((obs, oldColor, newColor) -> {
        if (newColor != null) {
          sampleRegistry.setGroupColor(current.getId(), newColor);
          draw();
        }
      });
      Label colorHint = new Label("Group color");
      colorHint.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");
      groupBox.getChildren().addAll(colorHint, colorPicker);
    } else {
      Label colorHint = new Label("Color for new group");
      colorHint.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");
      groupBox.getChildren().addAll(colorHint, colorPicker);
    }

    settingsMenu.getItems().add(new CustomMenuItem(groupBox, false));

    MenuItem addThis = new MenuItem(
        current == null ? "Add this sample to a group…" : "Move to a new group…");
    addThis.setOnAction(event -> {
      Window owner = canvas.getScene() != null ? canvas.getScene().getWindow() : null;
      SampleGroupDialog.show(
          owner, 1, sampleRegistry.suggestNextGroupName(), colorPicker.getValue()).ifPresent(outcome -> {
        if (outcome instanceof SampleGroupDialog.Outcome.Add add) {
          sampleRegistry.createGroupForTracks(List.of(track), add.name(), add.color());
          clearSelection();
          draw();
          onAfterVisibleTrackRangeChanged();
        }
      });
    });
    settingsMenu.getItems().add(addThis);

    if (selectedTrackIndices.size() >= 2 && selectedTrackIndices.contains(sampleIndex)) {
      MenuItem addSelected = new MenuItem(
          "Add " + selectedTrackIndices.size() + " selected samples to a group…");
      addSelected.setOnAction(event -> promptGroupSelectedTracks(0, 0));
      settingsMenu.getItems().add(addSelected);
    }

    int groupedSelectedCount = countGroupedSelectedTracks();
    if (groupedSelectedCount >= 2 && selectedTrackIndices.contains(sampleIndex)) {
      MenuItem removeSelected = new MenuItem(
          "Remove " + groupedSelectedCount + " selected from group");
      removeSelected.setOnAction(event -> {
        sampleRegistry.clearTracksFromGroups(selectedTracks());
        clearSelection();
        draw();
        onAfterVisibleTrackRangeChanged();
      });
      settingsMenu.getItems().add(removeSelected);
    }

    for (SampleGroup group : sampleRegistry.getSampleGroups()) {
      if (current != null && current.getId() == group.getId()) {
        continue;
      }
      MenuItem assign = new MenuItem("Add to " + group.getName());
      assign.setOnAction(event -> {
        List<SampleTrack> targets = selectedTrackIndices.contains(sampleIndex)
                && selectedTrackIndices.size() > 1
            ? selectedTracks()
            : List.of(track);
        sampleRegistry.assignTracksToGroup(targets, group.getId());
        clearSelection();
        draw();
        onAfterVisibleTrackRangeChanged();
      });
      settingsMenu.getItems().add(assign);
    }

    if (current != null) {
      MenuItem clear = new MenuItem(
          selectedTrackIndices.size() > 1 && selectedTrackIndices.contains(sampleIndex)
              ? "Remove this sample from group"
              : "Remove from group");
      clear.setOnAction(event -> {
        sampleRegistry.clearTrackGroup(track);
        draw();
        onAfterVisibleTrackRangeChanged();
      });
      settingsMenu.getItems().add(clear);
    }
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
    if (track.getFirstBam() == null) {
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
        : "-fx-text-fill: #aaaaaa; -fx-font-size: 11; -fx-font-weight: bold;");
    CheckBox hideMismatches =
        new CheckBox("Hide bisulfite mismatches (C→T / G→A)");
    hideMismatches.setSelected(track.hasMethylationData());
    hideMismatches.getStyleClass().add("dark-checkbox");
    hideMismatches.setStyle("-fx-font-size: 11;");
    hideMismatches.selectedProperty().addListener((observable, oldValue, newValue) -> {
      for (Sample sample : track.getSamples()) {
        AlignmentFile alignmentFile = sample.getBamFile();
        if (alignmentFile != null) {
          alignmentFile.setSuppressMethylMismatches(newValue);
        }
      }
      onAfterVisibleTrackRangeChanged();
    });
    Label information =
        new Label("Enable for emSeq/WGBS data to hide C→T conversions");
    information.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");
    methylationBox.getChildren().addAll(label, hideMismatches, information);
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
    information.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
    Label direction = new Label("HP1 = top (up), HP2 = bottom (down)");
    direction.setStyle("-fx-text-fill: #999999; -fx-font-size: 10;");
    haplotypeBox.getChildren().addAll(label, information, direction);
    settingsMenu.getItems().add(new CustomMenuItem(haplotypeBox, false));
  }

  private void addReadRenderingSettings(ContextMenu settingsMenu, SampleTrack track) {
    AlignmentFile primaryAlignmentFile = track.getFirstBam();
    if (primaryAlignmentFile == null) {
      return;
    }
    settingsMenu.getItems().add(new SeparatorMenuItem());
    VBox renderingBox = new VBox(6);
    renderingBox.setPadding(new Insets(4, 8, 4, 8));
    Label renderingLabel = new Label("🎨 Read rendering");
    renderingLabel.setStyle(
        "-fx-text-fill: #cccccc; -fx-font-size: 11; -fx-font-weight: bold;");

    HBox colorRow = new HBox(6);
    Label colorLabel = new Label("Read color:");
    colorLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
    ComboBox<ReadColorMode> colorComboBox = new ComboBox<>();
    colorComboBox.getItems().setAll(primaryAlignmentFile.getAvailableColorModes());
    colorComboBox.setValue(primaryAlignmentFile.getReadColorMode());
    colorComboBox.setPrefWidth(190);
    PopupComboBoxStyler.styleDarkComboBox(colorComboBox, settingsMenu);
    colorComboBox.valueProperty().addListener((observable, oldMode, newMode) -> {
      if (newMode != null) {
        for (Sample sample : track.getSamples()) {
          AlignmentFile alignmentFile = sample.getBamFile();
          if (alignmentFile != null) {
            alignmentFile.setReadColorMode(newMode);
          }
        }
        onAfterVisibleTrackRangeChanged();
      }
    });
    colorRow.getChildren().addAll(colorLabel, colorComboBox);

    HBox stackingRow = new HBox(6);
    Label stackingLabel = new Label("Stacking:");
    stackingLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
    ComboBox<AlignmentFile.ReadStackingMode> stackingComboBox = new ComboBox<>();
    stackingComboBox.getItems().setAll(AlignmentFile.ReadStackingMode.values());
    stackingComboBox.setValue(primaryAlignmentFile.getReadStackingMode());
    stackingComboBox.setPrefWidth(190);
    PopupComboBoxStyler.styleDarkComboBox(stackingComboBox, settingsMenu);
    stackingComboBox.valueProperty().addListener((observable, oldMode, newMode) -> {
      if (newMode != null) {
        for (Sample sample : track.getSamples()) {
          AlignmentFile alignmentFile = sample.getBamFile();
          if (alignmentFile != null) {
            alignmentFile.setReadStackingMode(newMode);
          }
        }
        onAfterVisibleTrackRangeChanged();
      }
    });
    stackingRow.getChildren().addAll(stackingLabel, stackingComboBox);

    Label stackingInformation =
        new Label("Only one stacking mode can be active at a time.");
    stackingInformation.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");
    renderingBox.getChildren().addAll(
        renderingLabel, colorRow, stackingRow, stackingInformation);
    if (primaryAlignmentFile.getDetectedReadGroups().size() > 1) {
      Label readGroupInformation = new Label(
          "Read groups: " + String.join(", ", primaryAlignmentFile.getDetectedReadGroups()));
      readGroupInformation.setStyle("-fx-text-fill: #999999; -fx-font-size: 9;");
      readGroupInformation.setWrapText(true);
      renderingBox.getChildren().add(readGroupInformation);
    }
    settingsMenu.getItems().add(new CustomMenuItem(renderingBox, false));
  }

  private void showAddFileMenu(int sampleIndex, double screenX, double screenY) {
    ContextMenu addMenu = new ContextMenu();
    addMenu.setStyle(
        "-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");
    MenuItem bamItem = new MenuItem("Add BAM/CRAM");
    bamItem.setOnAction(event -> SampleDataManager.addBamToTrack(sampleIndex));
    MenuItem bedItem = new MenuItem("Add BED");
    bedItem.setOnAction(event -> SampleDataManager.addBedToTrack(sampleIndex));
    addMenu.getItems().addAll(bamItem, bedItem);
    addMenu.show(canvas, screenX, screenY);
  }

  private void showRenameDialog(SampleTrack track) {
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(track.getDisplayName());
    dialog.setTitle("Rename Track");
    dialog.setHeaderText("Enter new name for this individual:");
    dialog.setContentText("Name:");
    dialog.getDialogPane().setStyle("-fx-background-color: #2b2b2b;");
    dialog.getDialogPane().lookup(".content.label").setStyle("-fx-text-fill: #cccccc;");
    dialog.getDialogPane().lookup(".header-panel")
        .setStyle("-fx-background-color: #333333;");
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
      file.visible = newValue;
      onAfterVisibleTrackRangeChanged();
    });

    CheckBox transparentCheckBox = new CheckBox("Transparent");
    transparentCheckBox.setSelected(file.overlay);
    transparentCheckBox.getStyleClass().add("dark-checkbox");
    transparentCheckBox.setStyle("-fx-font-size: 10;");
    transparentCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> {
      file.overlay = newValue;
      onAfterVisibleTrackRangeChanged();
    });

    Label typeLabel = new Label("[" + file.getDataType().name() + "]");
    typeLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");
    Label nameLabel = new Label(file.getName());
    nameLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-cursor: hand;");
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
    removeButton.setOnMouseClicked(event -> {
      if (track.getSampleCount() <= 1) {
        SampleDataManager.removeSample(sampleIndex);
      } else {
        track.removeSample(fileIndex);
        draw();
        onAfterVisibleTrackRangeChanged();
      }
    });
    row.getChildren().addAll(
        visibilityCheckBox, typeLabel, nameLabel, transparentCheckBox, removeButton);
    return new CustomMenuItem(row, false);
  }
}
