package org.baseplayer.draw;

import java.util.ArrayList;

import org.baseplayer.SharedModel;
import org.baseplayer.controllers.MainController;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.reads.bam.SampleFile;

import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class TrackInfo {
  SideBarStack sidebar;
  GraphicsContext gc;
  GraphicsContext reactivegc;
  ArrayList<String> tracks;

  // Button sizes and layout constants
  private static final double ICON_SIZE = 14;
  private static final double ICON_MARGIN = 4;
  private static final Font ICON_FONT = Font.font("Segoe UI Symbol", 12);
  private static final Font NAME_FONT = Font.font("Segoe UI", 12);
  private static final Font MASTER_FONT = Font.font("Segoe UI", 11);

  // Master track height (matches SharedModel constant)
  private static final double MASTER_TRACK_HEIGHT = SharedModel.MASTER_TRACK_HEIGHT;

  // Track which sample the mouse is hovering over for showing buttons
  private int hoverIndex = -1;

  // Context menu for adding data types (lives in master track)
  private final ContextMenu addDataMenu;

  public TrackInfo(SideBarStack sidebar) {
    this.sidebar = sidebar;
    this.gc = sidebar.sideCanvas.getGraphicsContext2D();
    this.reactivegc = sidebar.reactiveCanvas.getGraphicsContext2D();
    this.tracks = SharedModel.sampleList;

    // Build the add-data context menu
    addDataMenu = createAddDataMenu();

    sidebar.sideCanvas.setOnMouseMoved((event) -> {
      int idx = sampleIndexAtY(event.getY());
      if (idx != hoverIndex) {
        hoverIndex = idx;
        draw();
      }
      SharedModel.hoverSample.set(idx);
    });
    sidebar.sideCanvas.setOnMouseExited((event) -> {
      hoverIndex = -1;
      draw();
    });
    sidebar.sideCanvas.setOnScroll((event) -> { 
      SharedModel.scrollBarPosition -= event.getDeltaY();
      
      if (SharedModel.scrollBarPosition < 0) SharedModel.scrollBarPosition = 0;
      if (SharedModel.scrollBarPosition > (SharedModel.sampleList.size() - 1) * SharedModel.sampleHeight) SharedModel.scrollBarPosition = (SharedModel.sampleList.size() - 1) * SharedModel.sampleHeight;
      SharedModel.firstVisibleSample = Math.max(0, (int)(SharedModel.scrollBarPosition / SharedModel.sampleHeight));
      SharedModel.lastVisibleSample = Math.min(tracks.size() - 1,
        (int)((SharedModel.scrollBarPosition + sidebar.sideCanvas.getHeight() - MASTER_TRACK_HEIGHT) / SharedModel.sampleHeight));
      DrawFunctions.update.set(!DrawFunctions.update.get());
    });
    sidebar.sideCanvas.setOnMouseClicked(event -> {
      double x = event.getX();
      double y = event.getY();
      double sideW = sidebar.sideCanvas.getWidth();

      // Click in master track area
      if (y < MASTER_TRACK_HEIGHT) {
        handleMasterTrackClick(x, y, sideW);
        return;
      }

      int idx = sampleIndexAtY(y);

      if (idx >= 0 && idx < SharedModel.bamFiles.size()) {
        double sampleY = MASTER_TRACK_HEIGHT + idx * SharedModel.sampleHeight - SharedModel.scrollBarPosition;

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
        SharedModel.sampleHeight = (sidebar.sideCanvas.getHeight() - MASTER_TRACK_HEIGHT)
            / SharedModel.visibleSamples().getAsInt();
        SharedModel.scrollBarPosition = SharedModel.firstVisibleSample * SharedModel.sampleHeight;
        DrawFunctions.update.set(!DrawFunctions.update.get());
      }
    });
    SharedModel.hoverSample.addListener((obs, oldVal, newVal) -> { if (oldVal != newVal) draw(); });
  }

  private ContextMenu createAddDataMenu() {
    ContextMenu menu = new ContextMenu();

    MenuItem bamItem = new MenuItem("BAM");
    bamItem.setOnAction(e -> SampleDataManager.addBamFiles());

    MenuItem cramItem = new MenuItem("CRAM");
    cramItem.setDisable(true);

    MenuItem vcfItem = new MenuItem("VCF");
    vcfItem.setDisable(true);

    MenuItem bedItem = new MenuItem("BED");
    bedItem.setDisable(true);

    MenuItem bigwigItem = new MenuItem("BigWig");
    bigwigItem.setDisable(true);

    menu.getItems().addAll(bamItem, cramItem, vcfItem, bedItem, bigwigItem);
    return menu;
  }

  private void handleMasterTrackClick(double x, double y, double sideW) {
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
   */
  private void showSettingsPopup(int sampleIdx, double screenX, double screenY) {
    SampleFile sample = SharedModel.bamFiles.get(sampleIdx);
    ContextMenu settingsMenu = new ContextMenu();

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
    visCb.setStyle("-fx-text-fill: white; -fx-mark-color: white; -fx-mark-highlight-color: white; " +
                   "-fx-background-color: #333; -fx-border-color: #666;");
    visCb.selectedProperty().addListener((obs, o, n) -> {
      file.visible = n;
      redraw();
    });

    // Overlay checkbox
    CheckBox ovrCb = new CheckBox("Overlay");
    ovrCb.setSelected(file.overlay);
    ovrCb.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11; -fx-mark-color: #88bb88; " +
                   "-fx-mark-highlight-color: #88bb88; -fx-background-color: #333; -fx-border-color: #666;");
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

  private void redraw() {
    draw();
    DrawFunctions.update.set(!DrawFunctions.update.get());
  }

  private int sampleIndexAtY(double y) {
    if (y < MASTER_TRACK_HEIGHT) return -1;
    if (SharedModel.sampleHeight <= 0 || tracks.isEmpty()) return -1;
    int idx = (int)((y - MASTER_TRACK_HEIGHT + SharedModel.scrollBarPosition) / SharedModel.sampleHeight);
    if (idx < 0 || idx >= tracks.size()) return -1;
    return idx;
  }

  public void draw() {
    double w = sidebar.sideCanvas.getWidth();
    double h = sidebar.sideCanvas.getHeight();
    
    // Fill background
    gc.setFill(DrawFunctions.sidebarColor);
    gc.fillRect(0, 0, w, h);
    gc.setStroke(DrawFunctions.borderColor);

    // Always draw master track header
    drawMasterTrack(w);

    if (tracks.isEmpty()) return;

    for (int i = SharedModel.firstVisibleSample; i <= SharedModel.lastVisibleSample && i < tracks.size(); i++) {
      double sampleY = MASTER_TRACK_HEIGHT + i * SharedModel.sampleHeight - SharedModel.scrollBarPosition;
      boolean isHover = (i == hoverIndex);
      boolean isBam = (i < SharedModel.bamFiles.size());
      boolean isVisible = !isBam || SharedModel.bamFiles.get(i).visible;

      // Skip if completely above master track
      if (sampleY + SharedModel.sampleHeight < MASTER_TRACK_HEIGHT) continue;

      // Hover highlight
      if (isHover) {
        gc.setFill(Color.web("#2a2d2e"));
        gc.fillRect(0, Math.max(sampleY, MASTER_TRACK_HEIGHT), w, SharedModel.sampleHeight);
      }

      // Divider line
      if (sampleY >= MASTER_TRACK_HEIGHT) {
        gc.strokeLine(0, sampleY, w, sampleY);
      }
      for (DrawStack stack : MainController.drawStacks) {
        stack.drawCanvas.getGraphicsContext2D().setStroke(SharedModel.hoverSample.get() == i ? Color.WHITE : DrawFunctions.borderColor);
        stack.drawCanvas.getGraphicsContext2D().strokeLine(0, sampleY, stack.drawCanvas.getWidth(), sampleY);
      }

      // Sample name
      gc.setFont(NAME_FONT);
      gc.setFill(isVisible ? Color.web("#cccccc") : Color.web("#666666"));
      double textY = sampleY + gc.getFont().getSize() + 2;
      if (textY > MASTER_TRACK_HEIGHT) {
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
          if (infoY > MASTER_TRACK_HEIGHT) {
            gc.fillText("+" + overlayCount + " overlay" + (overlayCount > 1 ? "s" : ""), 10, infoY);
          }
        }
      }

      // Per-sample buttons (only shown on hover for BAM files)
      if (isHover && isBam) {
        drawSampleButtons(i, sampleY, w, isVisible);
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
    gc.fillRect(0, 0, sideW, MASTER_TRACK_HEIGHT);

    // Label
    gc.setFont(MASTER_FONT);
    gc.setFill(Color.web("#999999"));
    String label = tracks.isEmpty() ? "Tracks" : "Tracks (" + tracks.size() + ")";
    gc.fillText(label, 8, MASTER_TRACK_HEIGHT / 2 + 4);

    // "+" button on right
    double plusX = sideW - 22;
    double plusY = (MASTER_TRACK_HEIGHT - 18) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setFont(Font.font("Segoe UI", 14));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("+", plusX + 4, plusY + 14);

    // Bottom border
    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(0, MASTER_TRACK_HEIGHT, sideW, MASTER_TRACK_HEIGHT);
  }

  /**
   * Draw the close (✕), settings (⚙), and overlay (+) buttons for a sample.
   */
  private void drawSampleButtons(int idx, double sampleY, double sideW, boolean isVisible) {
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
