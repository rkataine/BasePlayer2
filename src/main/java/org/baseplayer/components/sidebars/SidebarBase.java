package org.baseplayer.components.sidebars;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.MasterTrackCanvas;

import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Abstract base class for all sidebar panels.
 *
 * <p>Provides a standard two-zone layout inside a parent {@link StackPane}:
 * <ol>
 *   <li><b>Header pane</b> — fixed height, renders ⚙ (left), title (center),
 *       + (right) buttons.  Subclasses can replace the header content entirely
 *       via {@link #replaceHeaderContent}.</li>
 *   <li><b>Content pane</b> — fills remaining space; subclasses populate this
 *       with their own canvases, controls, or child panels.</li>
 * </ol>
 *
 * <p>The pattern is similar to {@link InfoPopup}: callers describe <em>what</em>
 * to show and the base class handles layout and shared chrome.</p>
 */
public abstract class SidebarBase {

  // ── Layout ────────────────────────────────────────────────────────────────

  protected final StackPane headerPane;
  protected final StackPane contentPane;

  // ── Default header (canvas-based) ─────────────────────────────────────────

  private Canvas headerCanvas;
  private Canvas headerReactiveCanvas;
  private boolean settingsHovered;
  private boolean addHovered;
  private boolean hasDefaultHeader = true;

  // ── Constants ─────────────────────────────────────────────────────────────

  /** Standard sidebar header height in pixels. */
  public static final double DEFAULT_HEADER_HEIGHT = 22;
  private static final Font HEADER_FONT = Font.font("Segoe UI", 11);
  private static final double BTN_SIZE = 18;
  private static final double BTN_RADIUS = 4;
  private static final double BTN_LEFT_X = 4;

  // ── Construction ──────────────────────────────────────────────────────────

  /**
   * @param parent       the FXML-injected StackPane this sidebar lives in
   * @param headerHeight initial header height (use 0 for no header)
   */
  protected SidebarBase(StackPane parent, double headerHeight) {
    headerPane  = new StackPane();
    contentPane = new StackPane();

    // Anchor to top-left so content doesn't center at negative X when sidebar shrinks
    parent.setAlignment(Pos.TOP_LEFT);

    VBox layout = new VBox();
    layout.setMinWidth(0);
    layout.setMaxWidth(Double.MAX_VALUE);
    layout.setMaxHeight(Double.MAX_VALUE);

    if (headerHeight > 0) {
      headerPane.setMaxWidth(Double.MAX_VALUE);
      headerPane.setMinHeight(headerHeight);
      headerPane.setMaxHeight(headerHeight);
      layout.getChildren().add(headerPane);
      installDefaultHeader();
    } else {
      hasDefaultHeader = false;
    }

    contentPane.setMaxWidth(Double.MAX_VALUE);
    contentPane.setMinHeight(0);
    VBox.setVgrow(contentPane, Priority.ALWAYS);
    layout.getChildren().add(contentPane);

    parent.getChildren().add(layout);
  }

  // ── Default header implementation ─────────────────────────────────────────

  private void installDefaultHeader() {
    headerCanvas = new Canvas();
    headerReactiveCanvas = new Canvas();
    headerCanvas.widthProperty().bind(headerPane.widthProperty());
    headerCanvas.heightProperty().bind(headerPane.heightProperty());
    headerReactiveCanvas.widthProperty().bind(headerPane.widthProperty());
    headerReactiveCanvas.heightProperty().bind(headerPane.heightProperty());
    headerPane.getChildren().addAll(headerCanvas, headerReactiveCanvas);
    setupHeaderHandlers();
  }

  /**
   * Replace the default header canvas with custom nodes.
   * After this call the base class will no longer draw/handle the header.
   */
  protected void replaceHeaderContent() {
    headerPane.getChildren().clear();
    headerCanvas = null;
    headerReactiveCanvas = null;
    hasDefaultHeader = false;
  }

  // ── Public API ────────────────────────────────────────────────────────────

  /** Repaint the whole sidebar. */
  public void draw() {
    drawHeader();
    drawContent();
  }

  // ── Header drawing ────────────────────────────────────────────────────────

  /**
   * Draw the standard header bar.  Does nothing if the subclass replaced the
   * header content via {@link #replaceHeaderContent}.
   */
  protected void drawHeader() {
    if (!hasDefaultHeader || headerCanvas == null) return;

    double w = headerCanvas.getWidth();
    double h = headerCanvas.getHeight();
    if (w <= 0 || h <= 0) return;

    GraphicsContext gc = headerCanvas.getGraphicsContext2D();
    drawStandardHeader(gc, w, h, getTitle(), getItemCount());
  }

  /**
   * Shared utility for rendering the standard sidebar header bar.
   *
   * <p>Can also be called from non-SidebarBase classes (e.g.
   * {@link MasterTrackCanvas}) to ensure a consistent look.</p>
   *
   * @param gc    target graphics context
   * @param w     canvas width
   * @param h     canvas height
   * @param title header title text
   * @param count item count shown in parentheses (0 → no count)
   */
  public static void drawStandardHeader(GraphicsContext gc, double w, double h,
                                         String title, int count) {
    // Background
    gc.setFill(Color.web("#2b2d30"));
    gc.fillRect(0, 0, w, h);

    // Settings (⚙) button — left
    double sy = (h - BTN_SIZE) / 2;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(BTN_LEFT_X, sy, BTN_SIZE, BTN_SIZE, BTN_RADIUS, BTN_RADIUS);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(BTN_LEFT_X, sy, BTN_SIZE, BTN_SIZE, BTN_RADIUS, BTN_RADIUS);
    gc.setFont(Font.font("Segoe UI", 12));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("⚙", BTN_LEFT_X + 3, sy + 13);

    // Title (+ count)
    gc.setFont(HEADER_FONT);
    gc.setFill(Color.web("#999999"));
    String label = count > 0 ? title + " (" + count + ")" : title;
    gc.fillText(label, BTN_LEFT_X + BTN_SIZE + 6, h / 2 + 4);

    // Add (+) button — right
    double px = w - BTN_SIZE - 4;
    gc.setFill(Color.web("#3c3c3c"));
    gc.fillRoundRect(px, sy, BTN_SIZE, BTN_SIZE, BTN_RADIUS, BTN_RADIUS);
    gc.setStroke(Color.web("#555555"));
    gc.strokeRoundRect(px, sy, BTN_SIZE, BTN_SIZE, BTN_RADIUS, BTN_RADIUS);
    gc.setFont(Font.font("Segoe UI", 14));
    gc.setFill(Color.web("#cccccc"));
    gc.fillText("+", px + 4, sy + 14);

    // Bottom border
    gc.setStroke(Color.web("#444444"));
    gc.strokeLine(0, h - 1, w, h - 1);
  }

  // ── Header hover overlay ──────────────────────────────────────────────────

  private void drawHeaderHover() {
    if (!hasDefaultHeader || headerReactiveCanvas == null) return;

    double w = headerReactiveCanvas.getWidth();
    double h = headerReactiveCanvas.getHeight();
    GraphicsContext gc = headerReactiveCanvas.getGraphicsContext2D();
    gc.clearRect(0, 0, w, h);

    double sy = (h - BTN_SIZE) / 2;
    if (settingsHovered) {
      gc.setFill(Color.rgb(255, 255, 255, 0.15));
      gc.fillRoundRect(BTN_LEFT_X, sy, BTN_SIZE, BTN_SIZE, BTN_RADIUS, BTN_RADIUS);
    }
    if (addHovered) {
      double px = w - BTN_SIZE - 4;
      gc.setFill(Color.rgb(255, 255, 255, 0.15));
      gc.fillRoundRect(px, sy, BTN_SIZE, BTN_SIZE, BTN_RADIUS, BTN_RADIUS);
    }
  }

  // ── Header mouse handlers ─────────────────────────────────────────────────

  private void setupHeaderHandlers() {
    headerReactiveCanvas.setOnMouseMoved(event -> {
      double x = event.getX();
      double y = event.getY();
      double h = headerReactiveCanvas.getHeight();
      double sy = (h - BTN_SIZE) / 2;

      boolean prevSettings = settingsHovered;
      boolean prevAdd = addHovered;
      settingsHovered = inBtn(x, y, BTN_LEFT_X, sy);
      addHovered      = inBtn(x, y, headerReactiveCanvas.getWidth() - BTN_SIZE - 4, sy);

      if (prevSettings != settingsHovered || prevAdd != addHovered) drawHeaderHover();
    });

    headerReactiveCanvas.setOnMouseExited(event -> {
      if (settingsHovered || addHovered) {
        settingsHovered = false;
        addHovered = false;
        drawHeaderHover();
      }
    });

    headerReactiveCanvas.setOnMouseClicked(event -> {
      if (event.getButton() != MouseButton.PRIMARY) return;
      double x = event.getX();
      double y = event.getY();
      double h = headerReactiveCanvas.getHeight();
      double sy = (h - BTN_SIZE) / 2;

      if (inBtn(x, y, BTN_LEFT_X, sy)) {
        onSettingsClicked(event.getScreenX(), event.getScreenY());
      } else if (inBtn(x, y, headerReactiveCanvas.getWidth() - BTN_SIZE - 4, sy)) {
        onAddClicked(event.getScreenX(), event.getScreenY());
      }
    });
  }

  private static boolean inBtn(double mx, double my, double bx, double by) {
    return mx >= bx && mx <= bx + BTN_SIZE && my >= by && my <= by + BTN_SIZE;
  }

  // ── Abstract contract ─────────────────────────────────────────────────────

  /** Title shown in the header bar. */
  protected abstract String getTitle();

  /** Item count shown in parentheses next to the title (0 → hidden). */
  protected abstract int getItemCount();

  /** Called when the ⚙ button in the header is clicked. */
  protected abstract void onSettingsClicked(double screenX, double screenY);

  /** Called when the + button in the header is clicked. */
  protected abstract void onAddClicked(double screenX, double screenY);

  /** Render the content area below the header. */
  protected abstract void drawContent();
}
