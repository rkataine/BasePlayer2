package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.AppFonts;

import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Shared clickable color-swatch legend used by the sample aggregate density band.
 */
public final class CanvasColorLegend {

  /** Hit id for the leading select/deselect-all checkbox. */
  public static final Object SELECT_ALL = new Object() {
    @Override
    public String toString() {
      return "SELECT_ALL";
    }
  };

  public record Item(Object id, String label, Color color, boolean enabled) {
  }

  public record Hit(Object id, double x, double y, double w, double h) {
    public boolean contains(double px, double py) {
      return px >= x && px <= x + w && py >= y && py <= y + h;
    }
  }

  private final List<Hit> hits = new ArrayList<>();

  public void clear() {
    hits.clear();
  }

  public List<Hit> hits() {
    return hits;
  }

  public Object hitTest(double x, double y) {
    for (Hit hit : hits) {
      if (hit.contains(x, y)) {
        return hit.id();
      }
    }
    return null;
  }

  public boolean isOver(double x, double y) {
    return hitTest(x, y) != null;
  }

  public static boolean isSelectAll(Object id) {
    return id == SELECT_ALL;
  }

  /**
   * Draw an "All" checkbox followed by legend items starting at
   * {@code startX}/{@code startY}, wrapping when an item would exceed
   * {@code maxRight}. Stops when the next row would exceed {@code maxBottom}.
   * Clears and rebuilds hit boxes for this draw.
   */
  public void draw(
      GraphicsContext gc,
      List<Item> items,
      double startX,
      double startY,
      double maxRight,
      double maxBottom) {
    clear();
    if (gc == null || items == null || items.isEmpty()) {
      return;
    }

    var chrome = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    gc.setFont(AppFonts.getFont("Segoe UI", 11));
    gc.setTextBaseline(VPos.TOP);

    double legendX = startX;
    double legendY = startY;
    double swatchW = 12;
    double swatchH = 10;
    double gap = 10;
    double rowH = 16;
    double wrapX = startX;

    boolean allEnabled = true;
    int drawnItems = 0;
    for (Item item : items) {
      if (item == null || item.id() == null) {
        continue;
      }
      drawnItems++;
      if (!item.enabled()) {
        allEnabled = false;
      }
    }
    if (drawnItems == 0) {
      return;
    }

    // Leading select/deselect-all checkbox
    String allLabel = "All";
    double allTextW = Math.max(18, allLabel.length() * 7.2);
    double allItemW = swatchW + 5 + allTextW;
    if (legendY + swatchH <= maxBottom && legendX + allItemW <= maxRight) {
      drawCheckbox(gc, legendX, legendY, swatchW, swatchH, allEnabled, chrome, canvas);
      gc.setFill(allEnabled ? canvas.axisInk() : chrome.muted());
      gc.fillText(allLabel, legendX + swatchW + 4, legendY - 1);
      hits.add(new Hit(SELECT_ALL, legendX - 2, legendY - 2, allItemW + 4, swatchH + 4));
      legendX += allItemW + gap;
    }

    for (Item item : items) {
      if (item == null || item.id() == null) {
        continue;
      }
      String label = item.label() != null ? item.label() : "?";
      double textW = Math.max(22, label.length() * 7.2);
      double itemW = swatchW + 5 + textW;

      if (legendX + itemW > maxRight) {
        legendX = wrapX;
        legendY += rowH;
        if (legendY + swatchH > maxBottom) {
          break;
        }
      }

      Color typeColor = item.color() != null ? item.color() : Color.GRAY;
      boolean enabled = item.enabled();
      double alpha = enabled ? 0.95 : 0.28;
      gc.setFill(Color.color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), alpha));
      gc.fillRoundRect(legendX, legendY, swatchW, swatchH, 2, 2);
      if (!enabled) {
        gc.setStroke(chrome.muted());
        gc.setLineWidth(1.2);
        gc.strokeLine(legendX + 1, legendY + swatchH - 1, legendX + swatchW - 1, legendY + 1);
      }

      gc.setFill(enabled ? canvas.axisInk() : chrome.muted());
      gc.fillText(label, legendX + swatchW + 4, legendY - 1);

      hits.add(new Hit(item.id(), legendX - 2, legendY - 2, itemW + 4, swatchH + 4));
      legendX += itemW + gap;
    }
  }

  private static void drawCheckbox(
      GraphicsContext gc,
      double x,
      double y,
      double w,
      double h,
      boolean checked,
      AppTheme.ChromePalette chrome,
      AppTheme.CanvasPalette canvas) {
    double box = Math.min(w, h);
    double boxX = x + (w - box) / 2.0;
    double boxY = y + (h - box) / 2.0;

    gc.setStroke(checked ? canvas.axisInk() : chrome.muted());
    gc.setLineWidth(1.2);
    gc.strokeRoundRect(boxX, boxY, box, box, 2, 2);

    if (checked) {
      gc.setStroke(canvas.axisInk());
      gc.setLineWidth(1.6);
      double x1 = boxX + box * 0.2;
      double y1 = boxY + box * 0.55;
      double x2 = boxX + box * 0.42;
      double y2 = boxY + box * 0.78;
      double x3 = boxX + box * 0.82;
      double y3 = boxY + box * 0.22;
      gc.strokeLine(x1, y1, x2, y2);
      gc.strokeLine(x2, y2, x3, y3);
    }
  }
}
