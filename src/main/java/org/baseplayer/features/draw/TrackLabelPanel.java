package org.baseplayer.features.draw;

import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
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

public class TrackLabelPanel {
  SidebarPanel sidebar;
  GraphicsContext gc;
  
  // Services
  private final SampleRegistry sampleRegistry;
  private static final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();

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

  public TrackLabelPanel(SidebarPanel sidebar) {
    this.sidebar = sidebar;
    this.gc = sidebar.sideCanvas.getGraphicsContext2D();
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();

    // Build the add-data context menu
    addDataMenu = createAddDataMenu();

    sidebar.sideCanvas.setOnMouseMoved((event) -> {
      double y = event.getY();
      // Change cursor when hovering over master track bottom edge
      if (Math.abs(y - sampleRegistry.getMasterTrackHeight()) < 4) {
        sidebar.sideCanvas.setCursor(javafx.scene.Cursor.V_RESIZE);
      } else {
        sidebar.sideCanvas.setCursor(javafx.scene.Cursor.DEFAULT);
      }
      
      int idx = sampleIndexAtY(y);
      if (idx != hoverIndex) {
        hoverIndex = idx;
        draw();
      }
      sampleRegistry.hoverSampleProperty().set(idx);
    });
    sidebar.sideCanvas.setOnMouseExited((event) -> {
      hoverIndex = -1;
      sidebar.sideCanvas.setCursor(javafx.scene.Cursor.DEFAULT);
      draw();
    });
    sidebar.sideCanvas.setOnScroll((event) -> { 
      sampleRegistry.setScrollBarPosition(sampleRegistry.getScrollBarPosition() - event.getDeltaY());
      
      if (sampleRegistry.getScrollBarPosition() < 0) sampleRegistry.setScrollBarPosition(0);
      if (sampleRegistry.getScrollBarPosition() > (sampleRegistry.getSampleTracks().size() - 1) * sampleRegistry.getSampleHeight()) 
        sampleRegistry.setScrollBarPosition((sampleRegistry.getSampleTracks().size() - 1) * sampleRegistry.getSampleHeight());
      sampleRegistry.setFirstVisibleSample(Math.max(0, (int)(sampleRegistry.getScrollBarPosition() / sampleRegistry.getSampleHeight())));
      sampleRegistry.setLastVisibleSample(Math.min(sampleRegistry.getSampleTracks().size() - 1,
        (int)((sampleRegistry.getScrollBarPosition() + sidebar.sideCanvas.getHeight() - sampleRegistry.getMasterTrackHeight()) / sampleRegistry.getSampleHeight())));
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
    });
    sidebar.sideCanvas.setOnMousePressed(event -> {
      double y = event.getY();
      // Check if pressing on master track bottom edge
      if (Math.abs(y - sampleRegistry.getMasterTrackHeight()) < 4) {
        isDraggingMasterEdge = true;
        dragStartY = y;
        dragStartHeight = sampleRegistry.getMasterTrackHeight();
        event.consume();
      }
    });
    sidebar.sideCanvas.setOnMouseDragged(event -> {
      if (isDraggingMasterEdge) {
        double deltaY = event.getY() - dragStartY;
        sampleRegistry.setMasterTrackHeight(Math.max(20, Math.min(200, dragStartHeight + deltaY)));
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
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
      if (y < sampleRegistry.getMasterTrackHeight()) {
        handleMasterTrackClick(x, y, sideW);
        return;
      }

      int idx = sampleIndexAtY(y);

      if (idx >= 0 && idx < sampleRegistry.getSampleTracks().size()) {
        double sampleY = sampleRegistry.getMasterTrackHeight() + idx * sampleRegistry.getSampleHeight() - sampleRegistry.getScrollBarPosition();

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

        // Check add-file button (+) — bottom-left of sample strip
        double overlayX = ICON_MARGIN;
        double overlayY = sampleY + sampleRegistry.getSampleHeight() - ICON_SIZE - ICON_MARGIN;
        if (x >= overlayX && x <= overlayX + ICON_SIZE && y >= overlayY && y <= overlayY + ICON_SIZE) {
          showAddFileMenu(idx, event.getScreenX(), event.getScreenY());
          return;
        }
      }

      // Double-click zoom to sample
      if (event.getClickCount() == 2) {
        if (sampleRegistry.getFirstVisibleSample() == sampleRegistry.getLastVisibleSample()) {
          sampleRegistry.setFirstVisibleSample(0);
          sampleRegistry.setLastVisibleSample(sampleRegistry.getSampleTracks().size() - 1);
        } else {
          sampleRegistry.setFirstVisibleSample(sampleRegistry.hoverSampleProperty().get());
          sampleRegistry.setLastVisibleSample(sampleRegistry.hoverSampleProperty().get());
        }
        sampleRegistry.setSampleHeight((sidebar.sideCanvas.getHeight() - sampleRegistry.getMasterTrackHeight())
            / sampleRegistry.getVisibleSampleCount());
        sampleRegistry.setScrollBarPosition(sampleRegistry.getFirstVisibleSample() * sampleRegistry.getSampleHeight());
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    });
    sampleRegistry.hoverSampleProperty().addListener((obs, oldVal, newVal) -> { if (oldVal != newVal) draw(); });
  }

  private ContextMenu createAddDataMenu() {
    ContextMenu menu = new ContextMenu();

    MenuItem bamItem = new MenuItem("BAM/CRAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamFiles());

    MenuItem vcfItem = new MenuItem("VCF");
    vcfItem.setDisable(true);

    MenuItem bedItem = new MenuItem("BED");
    bedItem.setOnAction(e -> SampleDataManager.addBedFile());

    MenuItem bigwigItem = new MenuItem("BigWig");
    bigwigItem.setOnAction(e -> SampleDataManager.addBigWigFile());

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
   * Show a settings popup for an individual listing all data files.
   * Each gets: visible toggle and remove button.
   * Shows methylation/allele indicators and settings when auto-detected.
   */
  private void showSettingsPopup(int sampleIdx, double screenX, double screenY) {
    SampleTrack track = sampleRegistry.getSampleTracks().get(sampleIdx);
    ContextMenu settingsMenu = new ContextMenu();
    settingsMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    // All data file rows (equal treatment)
    for (int i = 0; i < track.getSamples().size(); i++) {
      Sample sample = track.getSamples().get(i);
      settingsMenu.getItems().add(buildTrackRow(sample, track, i, sampleIdx));
    }

    // Methylation settings (always available, shows detection status)
    settingsMenu.getItems().add(new SeparatorMenuItem());

    VBox methylBox = new VBox(4);
    methylBox.setPadding(new Insets(4, 8, 4, 8));

    if (track.hasMethylationData()) {
      Label methylLabel = new Label("🧬 Methylation tags detected (MM/ML/XM)");
      methylLabel.setStyle("-fx-text-fill: #88ccff; -fx-font-size: 11; -fx-font-weight: bold;");
      methylBox.getChildren().add(methylLabel);
    } else {
      Label methylLabel = new Label("🧬 Methylation / Bisulfite sequencing");
      methylLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11; -fx-font-weight: bold;");
      methylBox.getChildren().add(methylLabel);
    }

    CheckBox suppressMethylCb = new CheckBox("Hide bisulfite mismatches (C→T / G→A)");
    Boolean currentSetting = track.hasMethylationData();
    suppressMethylCb.setSelected(currentSetting);
    suppressMethylCb.getStyleClass().add("dark-checkbox");
    suppressMethylCb.setStyle("-fx-font-size: 11;");
    suppressMethylCb.selectedProperty().addListener((obs, o, n) -> {
      // Apply to all BAM samples in this track
      for (Sample s : track.getSamples()) {
        AlignmentFile bamFile = s.getBamFile();
        if (bamFile != null) bamFile.setSuppressMethylMismatches(n);
      }
      redraw();
    });

    Label info = new Label("Enable for emSeq/WGBS data to hide C→T conversions");
    info.setStyle("-fx-text-fill: #888888; -fx-font-size: 9;");

    methylBox.getChildren().addAll(suppressMethylCb, info);
    settingsMenu.getItems().add(new CustomMenuItem(methylBox, false));

    // Allele/haplotype settings (shown if HP tags detected)
    if (track.hasHaplotypeData()) {
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

    // Read group split (shown if any BAM has multiple read groups)
    AlignmentFile primaryBam = track.getFirstBam();
    if (primaryBam != null && primaryBam.getDetectedReadGroups().size() > 1) {
      settingsMenu.getItems().add(new SeparatorMenuItem());

      VBox rgBox = new VBox(4);
      rgBox.setPadding(new Insets(4, 8, 4, 8));

      Label rgLabel = new Label("📋 Read groups (" + primaryBam.getDetectedReadGroups().size() + " detected)");
      rgLabel.setStyle("-fx-text-fill: #ccaa88; -fx-font-size: 11; -fx-font-weight: bold;");

      CheckBox rgSplitCb = new CheckBox("Split pileup by read group");
      rgSplitCb.setSelected(primaryBam.isSplitByReadGroup());
      rgSplitCb.getStyleClass().add("dark-checkbox");
      rgSplitCb.setStyle("-fx-font-size: 11;");
      rgSplitCb.selectedProperty().addListener((obs, o, n) -> {
        primaryBam.setSplitByReadGroup(n);
        redraw();
      });

      StringBuilder rgList = new StringBuilder();
      for (String rg : primaryBam.getDetectedReadGroups()) {
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
   * Show a menu to choose file type to add (BAM or BED) to an individual's track.
   */
  private void showAddFileMenu(int sampleIdx, double screenX, double screenY) {
    ContextMenu addMenu = new ContextMenu();
    addMenu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    MenuItem bamItem = new MenuItem("Add BAM/CRAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamToTrack(sampleIdx));

    MenuItem bedItem = new MenuItem("Add BED");
    bedItem.setOnAction(e -> SampleDataManager.addBedToTrack(sampleIdx));

    addMenu.getItems().addAll(bamItem, bedItem);
    addMenu.show(sidebar.sideCanvas, screenX, screenY);
  }

  /**
   * Show a dialog to rename an individual's track.
   */
  private void showRenameDialog(SampleTrack track) {
    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(track.getDisplayName());
    dialog.setTitle("Rename Track");
    dialog.setHeaderText("Enter new name for this individual:");
    dialog.setContentText("Name:");

    // Apply dark theme
    dialog.getDialogPane().setStyle("-fx-background-color: #2b2b2b;");
    dialog.getDialogPane().lookup(".content.label").setStyle("-fx-text-fill: #cccccc;");
    dialog.getDialogPane().lookup(".header-panel").setStyle("-fx-background-color: #333333;");

    dialog.showAndWait().ifPresent(newName -> {
      if (!newName.trim().isEmpty()) {
        track.setCustomName(newName.trim());
        draw();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    });
  }

  /**
   * Build a settings row for a single data file with visible and remove controls.
   * @param file        the Sample this row controls
   * @param track       the owning SampleTrack
   * @param fileIdx     index in track.getSamples()
   * @param sampleIdx   index in sampleRegistry.getSampleTracks()
   */
  private CustomMenuItem buildTrackRow(Sample file, SampleTrack track, int fileIdx, int sampleIdx) {
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

    // Overlay (transparent) checkbox
    CheckBox ovrCb = new CheckBox("Transparent");
    ovrCb.setSelected(file.overlay);
    ovrCb.getStyleClass().add("dark-checkbox");
    ovrCb.setStyle("-fx-font-size: 10;");
    ovrCb.selectedProperty().addListener((obs, o, n) -> {
      file.overlay = n;
      redraw();
    });

    // Data type label
    Label typeLabel = new Label("[" + file.getDataType().name() + "]");
    typeLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");

    // Name label (clickable to rename the individual)
    Label nameLabel = new Label(file.getName());
    nameLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12; -fx-cursor: hand;");
    nameLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(nameLabel, Priority.ALWAYS);
    
    // Click to rename the individual (track-level)
    nameLabel.setOnMouseClicked(e -> {
      if (e.getClickCount() == 1) {
        showRenameDialog(track);
      }
    });

    // Remove button (✕)
    Label removeBtn = new Label("✕");
    removeBtn.setStyle("-fx-text-fill: #cc6666; -fx-cursor: hand; -fx-font-size: 11; -fx-padding: 0 2 0 4;");
    removeBtn.setOnMouseClicked(e -> {
      if (track.getSampleCount() <= 1) {
        // Removing the last file removes the whole individual
        SampleDataManager.removeSample(sampleIdx);
      } else {
        track.removeSample(fileIdx);
        draw();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    });

    row.getChildren().addAll(visCb, typeLabel, nameLabel, ovrCb, removeBtn);

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
          for (SampleTrack strack : sampleRegistry.getSampleTracks()) {
            for (Sample s : strack.getSamples()) {
              if (s.getBamFile() != null) {
                s.getBamFile().clearSampledCoverageCache();
              }
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
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private int sampleIndexAtY(double y) {
    if (y < sampleRegistry.getMasterTrackHeight()) return -1;
    if (sampleRegistry.getSampleHeight() <= 0 || sampleRegistry.getSampleTracks().isEmpty()) return -1;
    int idx = (int)((y - sampleRegistry.getMasterTrackHeight() + sampleRegistry.getScrollBarPosition()) / sampleRegistry.getSampleHeight());
    if (idx < 0 || idx >= sampleRegistry.getSampleTracks().size()) return -1;
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

    if (sampleRegistry.getSampleTracks().isEmpty()) return;

    for (int i = sampleRegistry.getFirstVisibleSample(); i <= sampleRegistry.getLastVisibleSample() && i < sampleRegistry.getSampleTracks().size(); i++) {
      double sampleY = sampleRegistry.getMasterTrackHeight() + i * sampleRegistry.getSampleHeight() - sampleRegistry.getScrollBarPosition();
      boolean isHover = (i == hoverIndex);
      boolean hasTrack = (i < sampleRegistry.getSampleTracks().size());
      boolean isVisible = !hasTrack || sampleRegistry.getSampleTracks().get(i).isVisible();

      // Skip if completely above master track
      if (sampleY + sampleRegistry.getSampleHeight() < sampleRegistry.getMasterTrackHeight()) continue;

      // Hover highlight
      if (isHover) {
        gc.setFill(Color.web("#2a2d2e"));
        gc.fillRect(0, Math.max(sampleY, sampleRegistry.getMasterTrackHeight()), w, sampleRegistry.getSampleHeight());
      }

      // Divider line
      if (sampleY >= sampleRegistry.getMasterTrackHeight()) {
        gc.strokeLine(0, sampleY, w, sampleY);
      }
      for (DrawStack stack : stackManager.getStacks()) {
        stack.alignmentCanvas.getGraphicsContext2D().setStroke(sampleRegistry.hoverSampleProperty().get() == i ? Color.WHITE : DrawColors.BORDER);
        stack.alignmentCanvas.getGraphicsContext2D().strokeLine(0, sampleY, stack.alignmentCanvas.getWidth(), sampleY);
      }

      // Sample name
      gc.setFont(NAME_FONT);
      gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
      double textY = sampleY + gc.getFont().getSize() + 2;
      if (textY > sampleRegistry.getMasterTrackHeight()) {
        String displayName = hasTrack ? sampleRegistry.getSampleTracks().get(i).getDisplayName() : "";
        gc.fillText(displayName, 10, textY);
      }

      // Show individual data files under the track name
      if (hasTrack) {
        SampleTrack strack = sampleRegistry.getSampleTracks().get(i);
        Font fileFont = Font.font("Segoe UI", 9);
        gc.setFont(fileFont);
        double fileY = textY + 4;
        for (Sample sf : strack.getSamples()) {
          fileY += 12;
          if (fileY > sampleY + sampleRegistry.getSampleHeight() - 4) break; // don't overflow track
          if (fileY <= sampleRegistry.getMasterTrackHeight()) continue;
          // Type tag color
          String tag = sf.getDataType().name();
          Color tagColor = switch (sf.getDataType()) {
            case BAM -> Color.web("#6699cc");
            case BED -> Color.web("#cc9966");
            case VCF -> Color.web("#99cc66");
          };
          // Dim hidden files
          double alpha = sf.visible ? 1.0 : 0.35;
          gc.setFill(new Color(tagColor.getRed(), tagColor.getGreen(), tagColor.getBlue(), alpha));
          gc.fillText("[" + tag + "]", 14, fileY);
          // File name
          Color nameColor = sf.visible ? Color.web("#aaaaaa") : Color.web("#555555");
          if (sf.overlay) nameColor = new Color(nameColor.getRed(), nameColor.getGreen(), nameColor.getBlue(), 0.7);
          gc.setFill(nameColor);
          gc.fillText(sf.getName(), 44, fileY);
          // Overlay indicator
          if (sf.overlay) {
            gc.setFill(new Color(0.6, 0.8, 0.6, alpha * 0.8));
            gc.fillText("\u25CB", 6, fileY); // small circle = transparent
          }
        }
      }

      // Per-sample buttons (only shown on hover for BAM files)
      if (isHover && hasTrack) {
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
    gc.fillRect(0, 0, sideW, sampleRegistry.getMasterTrackHeight());

    // Settings button (⚙) on left
    double settingsX = 4;
    double settingsY = (sampleRegistry.getMasterTrackHeight() - 18) / 2;
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
    String label = sampleRegistry.getSampleTracks().isEmpty() ? "Tracks" : "Tracks (" + sampleRegistry.getSampleTracks().size() + ")";
    gc.fillText(label, 30, sampleRegistry.getMasterTrackHeight() / 2 + 4);

    // "+" button on right
    double plusX = sideW - 22;
    double plusY = (sampleRegistry.getMasterTrackHeight() - 18) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setFont(Font.font("Segoe UI", 14));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("+", plusX + 4, plusY + 14);

    // Bottom border
    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(0, sampleRegistry.getMasterTrackHeight(), sideW, sampleRegistry.getMasterTrackHeight());
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

    // --- Add file button (+) bottom-left ---
    double overlayX = ICON_MARGIN;
    double overlayY = sampleY + sampleRegistry.getSampleHeight() - ICON_SIZE - ICON_MARGIN;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRect(overlayX - 1, overlayY - 1, ICON_SIZE + 2, ICON_SIZE + 2);
    gc.setFill(Color.web("#88bb88"));
    gc.fillText("+", overlayX + 2, overlayY + ICON_SIZE - 3);
  }
}
