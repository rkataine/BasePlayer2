package org.baseplayer.draw;

import java.util.ArrayList;

import org.baseplayer.SharedModel;
import org.baseplayer.controllers.MainController;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.reads.bam.SampleFile;
import org.baseplayer.utils.DrawColors;

import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class TrackInfo {
  SideBarStack sidebar;
  GraphicsContext gc;
  ArrayList<String> tracks;

  // Button sizes and layout constants
  private static final double ICON_SIZE = 14;
  private static final double ICON_MARGIN = 4;
  private static final Font ICON_FONT = Font.font("Segoe UI Symbol", 12);
  private static final Font NAME_FONT = Font.font("Segoe UI", 12);
  private static final Font MASTER_FONT = Font.font("Segoe UI", 11);

  // Track which sample the mouse is hovering over for showing buttons
  private int hoverIndex = -1;

  // Master track resize state
  private boolean isDraggingMasterEdge = false;
  private double dragStartY = 0;
  private double dragStartHeight = 0;

  // Context menu for adding data types (lives in master track)
  private final ContextMenu addDataMenu;

  public TrackInfo(SideBarStack sidebar) {
    this.sidebar = sidebar;
    this.gc = sidebar.sideCanvas.getGraphicsContext2D();
    this.tracks = SharedModel.sampleList;

    // Build the add-data context menu
    addDataMenu = createAddDataMenu();

    sidebar.sideCanvas.setOnMouseMoved((event) -> {
      double y = event.getY();
      // Change cursor when hovering over master track bottom edge
      if (Math.abs(y - SharedModel.masterTrackHeight) < 4) {
        sidebar.sideCanvas.setCursor(javafx.scene.Cursor.V_RESIZE);
      } else {
        sidebar.sideCanvas.setCursor(javafx.scene.Cursor.DEFAULT);
      }
      
      int idx = sampleIndexAtY(y);
      if (idx != hoverIndex) {
        hoverIndex = idx;
        draw();
      }
      SharedModel.hoverSample.set(idx);
    });
    sidebar.sideCanvas.setOnMouseExited((event) -> {
      hoverIndex = -1;
      sidebar.sideCanvas.setCursor(javafx.scene.Cursor.DEFAULT);
      draw();
    });
    sidebar.sideCanvas.setOnScroll((event) -> { 
      SharedModel.scrollBarPosition -= event.getDeltaY();
      
      if (SharedModel.scrollBarPosition < 0) SharedModel.scrollBarPosition = 0;
      if (SharedModel.scrollBarPosition > (SharedModel.sampleList.size() - 1) * SharedModel.sampleHeight) SharedModel.scrollBarPosition = (SharedModel.sampleList.size() - 1) * SharedModel.sampleHeight;
      SharedModel.firstVisibleSample = Math.max(0, (int)(SharedModel.scrollBarPosition / SharedModel.sampleHeight));
      SharedModel.lastVisibleSample = Math.min(tracks.size() - 1,
        (int)((SharedModel.scrollBarPosition + sidebar.sideCanvas.getHeight() - SharedModel.masterTrackHeight) / SharedModel.sampleHeight));
      DrawFunctions.update.set(!DrawFunctions.update.get());
    });
    sidebar.sideCanvas.setOnMousePressed(event -> {
      double y = event.getY();
      // Check if pressing on master track bottom edge
      if (Math.abs(y - SharedModel.masterTrackHeight) < 4) {
        isDraggingMasterEdge = true;
        dragStartY = y;
        dragStartHeight = SharedModel.masterTrackHeight;
        event.consume();
      }
    });
    sidebar.sideCanvas.setOnMouseDragged(event -> {
      if (isDraggingMasterEdge) {
        double deltaY = event.getY() - dragStartY;
        SharedModel.masterTrackHeight = Math.max(20, Math.min(200, dragStartHeight + deltaY));
        DrawFunctions.update.set(!DrawFunctions.update.get());
        event.consume();
      }
    });
    sidebar.sideCanvas.setOnMouseReleased(event -> {
      if (isDraggingMasterEdge) {
        isDraggingMasterEdge = false;
        event.consume();
      }
    });
    sidebar.sideCanvas.setOnMouseClicked(event -> {
      double x = event.getX();
      double y = event.getY();
      double sideW = sidebar.sideCanvas.getWidth();

      // Click in master track area
      if (y < SharedModel.masterTrackHeight) {
        handleMasterTrackClick(x, y, sideW);
        return;
      }

      int idx = sampleIndexAtY(y);

      if (idx >= 0 && idx < SharedModel.bamFiles.size()) {
        double sampleY = SharedModel.masterTrackHeight + idx * SharedModel.sampleHeight - SharedModel.scrollBarPosition;

        // Check close button (✕) — top-right corner of sample strip
        double closeX = sideW - ICON_SIZE - ICON_MARGIN;
        double closeY = sampleY + ICON_MARGIN;
        if (x >= closeX && x <= closeX + ICON_SIZE && y >= closeY && y <= closeY + ICON_SIZE) {
          SampleDataManager.removeSample(idx);
          return;
        }

        // Check settings button (⚙) — next to close
        double settingsX = closeX - ICON_SIZE - ICON_MARGIN;
        if (x >= settingsX && x <= settingsX + ICON_SIZE && y >= closeY && y <= closeY + ICON_SIZE) {
          showSettingsPopup(idx, event.getScreenX(), event.getScreenY());
          return;
        }

        // Check overlay add button (+) — bottom-left of sample strip
        double overlayX = ICON_MARGIN;
        double overlayY = sampleY + SharedModel.sampleHeight - ICON_SIZE - ICON_MARGIN;
        if (x >= overlayX && x <= overlayX + ICON_SIZE && y >= overlayY && y <= overlayY + ICON_SIZE) {
          SampleDataManager.addOverlayBam(idx);
          return;
        }
      }

      // Double-click zoom to sample
      if (event.getClickCount() == 2) {
        if (SharedModel.firstVisibleSample == SharedModel.lastVisibleSample) {
          SharedModel.firstVisibleSample = 0;
          SharedModel.lastVisibleSample = tracks.size() - 1;
        } else {
          SharedModel.firstVisibleSample = SharedModel.hoverSample.get();
          SharedModel.lastVisibleSample = SharedModel.hoverSample.get();
        }
        SharedModel.sampleHeight = (sidebar.sideCanvas.getHeight() - SharedModel.masterTrackHeight)
            / SharedModel.visibleSamples().getAsInt();
        SharedModel.scrollBarPosition = SharedModel.firstVisibleSample * SharedModel.sampleHeight;
        DrawFunctions.update.set(!DrawFunctions.update.get());
      }
    });
    SharedModel.hoverSample.addListener((obs, oldVal, newVal) -> { if (oldVal != newVal) draw(); });
  }

  private ContextMenu createAddDataMenu() {
    ContextMenu menu = new ContextMenu();

    MenuItem bamItem = new MenuItem("BAM/CRAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamFiles());

    MenuItem vcfItem = new MenuItem("VCF");
    vcfItem.setDisable(true);

    MenuItem bedItem = new MenuItem("BED");
    bedItem.setDisable(true);

    MenuItem bigwigItem = new MenuItem("BigWig");
    bigwigItem.setDisable(true);

    menu.getItems().addAll(bamItem, vcfItem, bedItem, bigwigItem);
    return menu;
  }

  private void handleMasterTrackClick(double x, double y, double sideW) {
    // Settings button (⚙) - left side of master track
    double settingsX = 0;
    double settingsW = 28;
    if (x >= settingsX && x < settingsX + settingsW) {
      Point2D pt = sidebar.sideCanvas.localToScreen(x, y);
      if (pt != null) {
        showGlobalSettingsMenu(pt.getX(), pt.getY());
      }
      return;
    }
    
    // "+" button on right side of master track
    double plusX = sideW - 22;
    if (x >= plusX) {
      Point2D pt = sidebar.sideCanvas.localToScreen(x, y);
      if (pt != null) {
        addDataMenu.show(sidebar.sideCanvas, pt.getX(), pt.getY());
      }
    }
  }

  /**
   * Show a settings popup for a sample listing the main track and all sub-tracks.
   * Each gets: visible toggle, overlay (transparent) toggle, and remove button.
   * Shows methylation/allele indicators and settings when auto-detected.
   */
  private void showSettingsPopup(int sampleIdx, double screenX, double screenY) {
    SampleFile sample = SharedModel.bamFiles.get(sampleIdx);
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    // Main sample row
    settingsMenu.getItems().add(buildTrackRow(sample, null, -1, sampleIdx));

    // Sub-track rows
    if (!sample.getOverlays().isEmpty()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());
      for (int i = 0; i < sample.getOverlays().size(); i++) {
        SampleFile sub = sample.getOverlays().get(i);
        settingsMenu.getItems().add(buildTrackRow(sub, sample, i, sampleIdx));
      }
    }

    // Methylation settings (always available, shows detection status)
    settingsMenu.getItems().add(new SeparatorMenuItem());

    VBox methylBox = new VBox(4);
    methylBox.setPadding(new Insets(4, 8, 4, 8));

    if (sample.isMethylationData()) {
      Label methylLabel = new Label("🧬 Methylation tags detected (MM/ML/XM)");
      methylLabel.setStyle("-fx-text-fill: #88ccff; -fx-font-size: 11; -fx-font-weight: bold;");
      methylBox.getChildren().add(methylLabel);
    } else {
      Label methylLabel = new Label("🧬 Methylation / Bisulfite sequencing");
      methylLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11; -fx-font-weight: bold;");
      methylBox.getChildren().add(methylLabel);
    }

    CheckBox suppressMethylCb = new CheckBox("Hide bisulfite mismatches (C→T / G→A)");
    Boolean currentSetting = sample.isMethylationData();
    suppressMethylCb.setSelected(currentSetting != null && currentSetting);
    suppressMethylCb.getStyleClass().add("dark-checkbox");
    suppressMethylCb.setStyle("-fx-font-size: 11;");
    suppressMethylCb.selectedProperty().addListener((obs, o, n) -> {
      sample.setSuppressMethylMismatches(n);
      redraw();
    });

    Label info = new Label("Enable for emSeq/WGBS data to hide C→T conversions");
    info.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");

    methylBox.getChildren().addAll(suppressMethylCb, info);
    settingsMenu.getItems().add(new CustomMenuItem(methylBox, false));

    // Allele/haplotype settings (shown if HP tags detected)
    if (sample.isHaplotypeData()) {
      settingsMenu.getItems().add(new SeparatorMenuItem());

      VBox hpBox = new VBox(4);
      hpBox.setPadding(new Insets(4, 8, 4, 8));

      Label hpLabel = new Label("\uD83E\uDDE9 Phased haplotype data (HP tags)");
      hpLabel.setStyle("-fx-text-fill: #88eebb; -fx-font-size: 11; -fx-font-weight: bold;");

      Label hpInfo = new Label("Reads shown in allele-split butterfly view:");
      hpInfo.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
      Label hpInfo2 = new Label("HP1 = top (up), HP2 = bottom (down)");
      hpInfo2.setStyle("-fx-text-fill: #999999; -fx-font-size: 10;");

      hpBox.getChildren().addAll(hpLabel, hpInfo, hpInfo2);
      settingsMenu.getItems().add(new CustomMenuItem(hpBox, false));
    }

    // Read group split (shown if multiple read groups detected)
    if (sample.getDetectedReadGroups().size() > 1) {
      settingsMenu.getItems().add(new SeparatorMenuItem());

      VBox rgBox = new VBox(4);
      rgBox.setPadding(new Insets(4, 8, 4, 8));

      Label rgLabel = new Label("📋 Read groups (" + sample.getDetectedReadGroups().size() + " detected)");
      rgLabel.setStyle("-fx-text-fill: #ccaa88; -fx-font-size: 11; -fx-font-weight: bold;");

      CheckBox rgSplitCb = new CheckBox("Split pileup by read group");
      rgSplitCb.setSelected(sample.isSplitByReadGroup());
      rgSplitCb.getStyleClass().add("dark-checkbox");
      rgSplitCb.setStyle("-fx-font-size: 11;");
      rgSplitCb.selectedProperty().addListener((obs, o, n) -> {
        sample.setSplitByReadGroup(n);
        redraw();
      });

      StringBuilder rgList = new StringBuilder();
      for (String rg : sample.getDetectedReadGroups()) {
        rgList.append("• ").append(rg).append("\n");
      }
      Label rgInfo = new Label(rgList.toString().trim());
      rgInfo.setStyle("-fx-text-fill: #999999; -fx-font-size: 9;");

      rgBox.getChildren().addAll(rgLabel, rgSplitCb, rgInfo);
      settingsMenu.getItems().add(new CustomMenuItem(rgBox, false));
    }

    settingsMenu.show(sidebar.sideCanvas, screenX, screenY);
  }

  /**
   * Build a settings row for a single data file with visible, overlay, and remove controls.
   * @param file        the SampleFile this row controls
   * @param parent      null for the main track; the owning SampleFile for sub-tracks
   * @param overlayIdx  index in parent.getOverlays(), or -1 for main track
   * @param sampleIdx   index in SharedModel.bamFiles
   */
  private CustomMenuItem buildTrackRow(SampleFile file, SampleFile parent, int overlayIdx, int sampleIdx) {
    HBox row = new HBox(6);
    row.setStyle("-fx-padding: 2 4 2 4;");

    // Visible checkbox
    CheckBox visCb = new CheckBox();
    visCb.setSelected(file.visible);
    visCb.getStyleClass().add("dark-checkbox");
    visCb.selectedProperty().addListener((obs, o, n) -> {
      file.visible = n;
      redraw();
    });

    // Overlay checkbox
    CheckBox ovrCb = new CheckBox("Overlay");
    ovrCb.setSelected(file.overlay);
    ovrCb.getStyleClass().add("dark-checkbox");
    ovrCb.setStyle("-fx-font-size: 11;");
    ovrCb.selectedProperty().addListener((obs, o, n) -> {
      file.overlay = n;
      redraw();
    });

    // Name label
    Label nameLabel = new Label(file.name);
    nameLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12;");
    nameLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(nameLabel, Priority.ALWAYS);

    // Remove button (✕)
    Label removeBtn = new Label("✕");
    removeBtn.setStyle("-fx-text-fill: #cc6666; -fx-cursor: hand; -fx-font-size: 11; -fx-padding: 0 2 0 4;");
    removeBtn.setOnMouseClicked(e -> {
      if (parent == null) {
        // Removing the main track removes the whole sample
        SampleDataManager.removeSample(sampleIdx);
      } else {
        parent.removeOverlay(overlayIdx);
        draw();
        DrawFunctions.update.set(!DrawFunctions.update.get());
      }
    });

    row.getChildren().addAll(visCb, nameLabel, ovrCb, removeBtn);

    CustomMenuItem item = new CustomMenuItem(row, false);
    return item;
  }

  private void showGlobalSettingsMenu(double screenX, double screenY) {
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    // Title item
    Label titleLabel = new Label("Global Settings");
    titleLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 13; -fx-font-weight: bold; -fx-padding: 4 8 2 8;");
    CustomMenuItem titleItem = new CustomMenuItem(titleLabel, false);
    settingsMenu.getItems().add(titleItem);
    settingsMenu.getItems().add(new SeparatorMenuItem());

    // Sampled coverage enable/disable checkbox
    VBox sampledCoverageBox = new VBox(4);
    sampledCoverageBox.setPadding(new Insets(4, 8, 4, 8));

    CheckBox enableSampledCoverageCb = new CheckBox("Enable sampled coverage");
    enableSampledCoverageCb.setSelected(Settings.get().isEnableSampledCoverage());
    enableSampledCoverageCb.getStyleClass().add("dark-checkbox");
    enableSampledCoverageCb.setStyle("-fx-font-size: 12;");
    enableSampledCoverageCb.selectedProperty().addListener((obs, oldVal, newVal) -> {
      Settings.get().setEnableSampledCoverage(newVal);
      redraw();
    });

    // Sample points text field with refresh button
    HBox samplePointsBox = new HBox(6);
    Label samplePointsLabel = new Label("Sample points:");
    samplePointsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11;");
    
    TextField samplePointsField = new TextField(String.valueOf(Settings.get().getSampledCoveragePoints()));
    samplePointsField.setStyle("-fx-background-color: #333; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-font-size: 11;");
    samplePointsField.setPrefWidth(80);
    
    Button refreshButton = new Button("Refresh");
    refreshButton.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #cccccc; -fx-font-size: 11; " +
                          "-fx-padding: 2 8 2 8; -fx-border-color: #666; -fx-cursor: hand;");
    refreshButton.setOnAction(e -> {
      try {
        int value = Integer.parseInt(samplePointsField.getText());
        if (value > 0 && value <= 10000) {
          Settings.get().setSampledCoveragePoints(value);
          // Clear cached sampled coverage for all sample files
          for (SampleFile sample : SharedModel.bamFiles) {
            sample.clearSampledCoverageCache();
            for (SampleFile overlay : sample.getOverlays()) {
              overlay.clearSampledCoverageCache();
            }
          }
          redraw();
          settingsMenu.hide();
        }
      } catch (NumberFormatException ex) {
        samplePointsField.setText(String.valueOf(Settings.get().getSampledCoveragePoints()));
      }
    });
    
    samplePointsBox.getChildren().addAll(samplePointsLabel, samplePointsField, refreshButton);
    
    sampledCoverageBox.getChildren().addAll(enableSampledCoverageCb, samplePointsBox);
    CustomMenuItem sampledCoverageItem = new CustomMenuItem(sampledCoverageBox, false);
    settingsMenu.getItems().add(sampledCoverageItem);

    settingsMenu.show(sidebar.sideCanvas.getScene().getWindow(), screenX, screenY);
  }

  private void redraw() {
    draw();
    DrawFunctions.update.set(!DrawFunctions.update.get());
  }

  private int sampleIndexAtY(double y) {
    if (y < SharedModel.masterTrackHeight) return -1;
    if (SharedModel.sampleHeight <= 0 || tracks.isEmpty()) return -1;
    int idx = (int)((y - SharedModel.masterTrackHeight + SharedModel.scrollBarPosition) / SharedModel.sampleHeight);
    if (idx < 0 || idx >= tracks.size()) return -1;
    return idx;
  }

  public void draw() {
    double w = sidebar.sideCanvas.getWidth();
    double h = sidebar.sideCanvas.getHeight();
    
    // Fill background
    gc.setFill(DrawColors.SIDEBAR);
    gc.fillRect(0, 0, w, h);
    gc.setStroke(DrawColors.BORDER);

    // Always draw master track header
    drawMasterTrack(w);

    if (tracks.isEmpty()) return;

    for (int i = SharedModel.firstVisibleSample; i <= SharedModel.lastVisibleSample && i < tracks.size(); i++) {
      double sampleY = SharedModel.masterTrackHeight + i * SharedModel.sampleHeight - SharedModel.scrollBarPosition;
      boolean isHover = (i == hoverIndex);
      boolean isBam = (i < SharedModel.bamFiles.size());
      boolean isVisible = !isBam || SharedModel.bamFiles.get(i).visible;

      // Skip if completely above master track
      if (sampleY + SharedModel.sampleHeight < SharedModel.masterTrackHeight) continue;

      // Hover highlight
      if (isHover) {
        gc.setFill(Color.web("#2a2d2e"));
        gc.fillRect(0, Math.max(sampleY, SharedModel.masterTrackHeight), w, SharedModel.sampleHeight);
      }

      // Divider line
      if (sampleY >= SharedModel.masterTrackHeight) {
        gc.strokeLine(0, sampleY, w, sampleY);
      }
      for (DrawStack stack : MainController.drawStacks) {
        stack.drawCanvas.getGraphicsContext2D().setStroke(SharedModel.hoverSample.get() == i ? Color.WHITE : DrawColors.BORDER);
        stack.drawCanvas.getGraphicsContext2D().strokeLine(0, sampleY, stack.drawCanvas.getWidth(), sampleY);
      }

      // Sample name
      gc.setFont(NAME_FONT);
      gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
      double textY = sampleY + gc.getFont().getSize() + 2;
      if (textY > SharedModel.masterTrackHeight) {
        gc.fillText(tracks.get(i), 10, textY);
      }

      // Overlay count indicator
      if (isBam) {
        SampleFile sample = SharedModel.bamFiles.get(i);
        int overlayCount = sample.getOverlays().size();
        if (overlayCount > 0) {
          gc.setFont(Font.font("Segoe UI", 10));
          gc.setFill(Color.web("#888888"));
          double infoY = sampleY + gc.getFont().getSize() + 18;
          if (infoY > SharedModel.masterTrackHeight) {
            gc.fillText("+" + overlayCount + " overlay" + (overlayCount > 1 ? "s" : ""), 10, infoY);
          }
        }
      }

      // Per-sample buttons (only shown on hover for BAM files)
      if (isHover && isBam) {
        drawSampleButtons(sampleY, w, isVisible);
      }
    }
  }

  /**
   * Draw the master track header at the top of the sidebar.
   * Contains the "+" button for adding data and a track count label.
   */
  private void drawMasterTrack(double sideW) {
    // Background — slightly different shade
    gc.setFill(Color.web("#2b2d30"));
    gc.fillRect(0, 0, sideW, SharedModel.masterTrackHeight);

    // Settings button (⚙) on left
    double settingsX = 4;
    double settingsY = (SharedModel.masterTrackHeight - 18) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(settingsX, settingsY, 18, 18, 4, 4);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(settingsX, settingsY, 18, 18, 4, 4);
    gc.setFont(Font.font("Segoe UI", 12));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("⚙", settingsX + 3, settingsY + 13);

    // Label
    gc.setFont(MASTER_FONT);
    gc.setFill(Color.web("#999999"));
    String label = tracks.isEmpty() ? "Tracks" : "Tracks (" + tracks.size() + ")";
    gc.fillText(label, 30, SharedModel.masterTrackHeight / 2 + 4);

    // "+" button on right
    double plusX = sideW - 22;
    double plusY = (SharedModel.masterTrackHeight - 18) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setFont(Font.font("Segoe UI", 14));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("+", plusX + 4, plusY + 14);

    // Bottom border
    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(0, SharedModel.masterTrackHeight, sideW, SharedModel.masterTrackHeight);
  }

  /**
   * Draw the close (✕), settings (⚙), and overlay (+) buttons for a sample.
   */
  private void drawSampleButtons(double sampleY, double sideW, boolean isVisible) {
    gc.setFont(ICON_FONT);
    
    // --- Close button (✕) top-right ---
    double closeX = sideW - ICON_SIZE - ICON_MARGIN;
    double closeY = sampleY + ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRect(closeX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2);
    gc.setFill(Color.web("#cc6666"));
    gc.fillText("✕", closeX + 1, closeY + ICON_SIZE - 3);

    // --- Settings button (⚙) next to close, top-right ---
    double settingsX = closeX - ICON_SIZE - ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRect(settingsX - 1, closeY - 1, ICON_SIZE + 2, ICON_SIZE + 2);
    gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
    gc.fillText("⚙", settingsX + 1, closeY + ICON_SIZE - 3);

    // --- Overlay add button (+) bottom-left ---
    double overlayX = ICON_MARGIN;
    double overlayY = sampleY + SharedModel.sampleHeight - ICON_SIZE - ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRect(overlayX - 1, overlayY - 1, ICON_SIZE + 2, ICON_SIZE + 2);
    gc.setFill(Color.web("#88bb88"));
    gc.fillText("+", overlayX + 2, overlayY + ICON_SIZE - 3);
  }
}
