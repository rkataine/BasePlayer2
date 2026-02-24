package org.baseplayer.components;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.SampleDataManager;
import org.baseplayer.io.Settings;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;

import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Canvas for the "master track" header in the samples sidebar.
 *
 * <p>Extends {@link GenomicCanvas} so that mouse drag and scroll on this area
 * navigate the genome (pan/zoom) exactly like the alignment canvas.
 * Vertical scroll is remapped to horizontal panning so regular scroll-wheel
 * navigation works here too.</p>
 *
 * <p>The bottom edge can be dragged vertically to resize the master track area.</p>
 */
public class MasterTrackCanvas extends GenomicCanvas {

  private static final Font MASTER_FONT = Font.font("Segoe UI", 11);

  private final DrawStackManager stackManager;
  private final ContextMenu addDataMenu;

  // Resize-drag state
  private boolean isDraggingResize = false;
  private double dragStartScreenY = 0;
  private double dragStartHeight = 0;

  public MasterTrackCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack initialDrawStack) {
    super(reactiveCanvas, parent, initialDrawStack);
    this.stackManager = ServiceRegistry.getInstance().getDrawStackManager();
    this.addDataMenu = createAddDataMenu();
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
      reactiveCanvas.setCursor(event.getY() >= edgeZone ? Cursor.V_RESIZE : Cursor.DEFAULT);
    });

    reactiveCanvas.setOnMousePressed(event -> {
      if (event.getY() >= getHeight() - 4) {
        isDraggingResize = true;
        dragStartScreenY = event.getScreenY();
        dragStartHeight = sampleRegistry.getMasterTrackHeight();
      } else {
        // Standard GenomicCanvas navigation tracking
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
      } else {
        syncDrawStack();
        mouseDragged = true;
        drawStack.nav.navigating = true;
        handleDrag(event);
        onDragActive();
      }
    });

    reactiveCanvas.setOnMouseReleased(event -> {
      if (isDraggingResize) {
        isDraggingResize = false;
        reactiveCanvas.setCursor(Cursor.DEFAULT);
      } else {
        syncDrawStack();
        handleMouseRelease(event);
      }
    });

    reactiveCanvas.setOnScroll(event -> {
      syncDrawStack();
      drawStack.nav.navigating = true;
      handlePanScroll(event);
    });

    reactiveCanvas.setOnMouseClicked(event -> {
      if (!mouseDragged) {
        handleMasterClick(event.getX(), event.getScreenX(), event.getScreenY());
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

    GraphicsContext gc = getGraphicsContext2D();
    gc.setFill(Color.web("#2b2d30"));
    gc.fillRect(0, 0, w, h);

    // Settings (⚙) button — left
    double settingsX = 4;
    double settingsY = (h - 18) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(settingsX, settingsY, 18, 18, 4, 4);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(settingsX, settingsY, 18, 18, 4, 4);
    gc.setFont(Font.font("Segoe UI", 12));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("⚙", settingsX + 3, settingsY + 13);

    // Track count label — center
    gc.setFont(MASTER_FONT);
    gc.setFill(Color.web("#999999"));
    String label = sampleRegistry.getSampleTracks().isEmpty()
        ? "Tracks"
        : "Tracks (" + sampleRegistry.getSampleTracks().size() + ")";
    gc.fillText(label, 30, h / 2 + 4);

    // Add (+) button — right
    double plusX = w - 22;
    double plusY = (h - 18) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(plusX, plusY, 18, 18, 4, 4);
    gc.setFont(Font.font("Segoe UI", 14));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("+", plusX + 4, plusY + 14);

    // Bottom border
    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(0, h - 1, w, h - 1);
  }

  // ── Click handling ────────────────────────────────────────────────────────

  private void handleMasterClick(double x, double screenX, double screenY) {
    // Settings (⚙) — left 28px
    if (x < 28) {
      showGlobalSettingsMenu(screenX, screenY);
      return;
    }
    // Add (+) — right 22px
    if (x >= getWidth() - 22) {
      addDataMenu.show(this, screenX, screenY);
    }
  }

  // ── Context menus ─────────────────────────────────────────────────────────

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

    settingsMenu.show(getScene().getWindow(), screenX, screenY);
  }
}
