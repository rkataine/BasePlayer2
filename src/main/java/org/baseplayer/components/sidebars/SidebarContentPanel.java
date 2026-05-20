package org.baseplayer.components.sidebars;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;

/**
 * Abstract base class for sidebar content panels.
 *
 * <p>Provides the two-canvas pattern (main {@code canvas} + transparent
 * {@code reactiveCanvas} overlay), shared hover-state fields ({@link #hoverIndex},
 * {@link #hoveredIcon}), and the standard mouse-moved / mouse-exited handler
 * wiring via {@link #setupHoverHandlers()}.</p>
 *
 * <p>Subclasses implement panel-specific row/icon lookup and drawing.</p>
 */
public abstract class SidebarContentPanel {

  protected static record IconRegion(
      int rowIndex, String iconType, double x, double y, double width, double height) {
    boolean contains(double mx, double my) {
      return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }
  }

  protected final Canvas          canvas;
  protected final Canvas          reactiveCanvas;
  protected final GraphicsContext gc;
  protected final GraphicsContext reactiveGc;
  protected final List<IconRegion> iconRegions = new ArrayList<>();

  /** Index of the currently hovered row (-1 = none). */
  protected int    hoverIndex  = -1;
  /** Name of the currently hovered icon within the row (null = none). */
  protected String hoveredIcon = null;

  protected SidebarContentPanel(StackPane parent) {
    canvas         = new Canvas();
    reactiveCanvas = new Canvas();

    // These canvases are explicitly bound to parent size; keep them out of
    // layout size calculations to avoid width feedback/lock-in when panes resize.
    canvas.setManaged(false);
    reactiveCanvas.setManaged(false);

    canvas.widthProperty().bind(parent.widthProperty());
    canvas.heightProperty().bind(parent.heightProperty());
    reactiveCanvas.widthProperty().bind(parent.widthProperty());
    reactiveCanvas.heightProperty().bind(parent.heightProperty());
    reactiveCanvas.setMouseTransparent(false);

    gc         = canvas.getGraphicsContext2D();
    reactiveGc = reactiveCanvas.getGraphicsContext2D();

    parent.getChildren().addAll(canvas, reactiveCanvas);
  }

  /**
   * Install mouse-moved and mouse-exited handlers on the reactive canvas.
   * Delegates row/icon detection to {@link #findRowAt} and {@link #findIconAt},
   * then calls {@link #drawReactive()} whenever hover state changes.
   *
   * <p>Call this from the subclass constructor after all fields are initialised.</p>
   */
  protected final void setupHoverHandlers() {
    reactiveCanvas.setOnMouseMoved(event -> {
      int previousRow = hoverIndex;
      String previousIcon = hoveredIcon;
      int currentRow = findRowAt(event.getY());

      if (currentRow != previousRow) {
        hoverIndex = currentRow;
        onHoverRowChanged(previousRow, currentRow);
      }

      String currentIcon = findIconAt(event.getX(), event.getY(), currentRow);
      if (currentRow != previousRow || !Objects.equals(currentIcon, previousIcon)) {
        hoveredIcon = currentIcon;
        onHoverStateChanged(previousRow, previousIcon, currentRow, currentIcon);
        drawReactive();
      }
    });

    reactiveCanvas.setOnMouseExited(event -> {
      int previousRow = hoverIndex;
      String previousIcon = hoveredIcon;
      if (previousRow != -1 || previousIcon != null) {
        hoverIndex = -1;
        hoveredIcon = null;
        onHoverRowChanged(previousRow, -1);
        onHoverStateChanged(previousRow, previousIcon, -1, null);
        drawReactive();
      }
    });
  }

  /** Hook for side effects when hovered row changes (default: no-op). */
  protected void onHoverRowChanged(int previousRow, int currentRow) {
    // Optional hook for subclasses.
  }

  /** Hook for side effects when hovered row/icon changes (default: no-op). */
  protected void onHoverStateChanged(int previousRow, String previousIcon,
                                     int currentRow, String currentIcon) {
    // Optional hook for subclasses.
  }

  protected final void clearIconRegions() {
    iconRegions.clear();
  }

  protected final void addIconRegion(int rowIndex, String iconType,
                                     double x, double y, double width, double height) {
    iconRegions.add(new IconRegion(rowIndex, iconType, x, y, width, height));
  }

  protected final String findIconFromRegions(double x, double y, int rowIdx) {
    for (IconRegion region : iconRegions) {
      if (region.rowIndex() == rowIdx && region.contains(x, y)) {
        return region.iconType();
      }
    }
    return null;
  }

  protected final IconRegion findIconRegion(String iconType, int rowIdx) {
    if (iconType == null || rowIdx < 0) return null;
    for (IconRegion region : iconRegions) {
      if (region.rowIndex() == rowIdx && iconType.equals(region.iconType())) {
        return region;
      }
    }
    return null;
  }

  /** Map a Y coordinate to a row index, or -1 if no row is under y. */
  protected abstract int findRowAt(double y);

  /** Map (x, y) to an icon name for the given row index, or null if no icon. */
  protected abstract String findIconAt(double x, double y, int rowIdx);

  /** Full repaint of static content onto {@link #gc}. */
  public abstract void draw();

  /** Lightweight repaint of hover state onto {@link #reactiveGc}. */
  protected abstract void drawReactive();
}
