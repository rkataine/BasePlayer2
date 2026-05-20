package org.baseplayer.utils;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Paint;

/**
 * Shared utility for drawing strand-oriented arrow shapes. A single rectangle
 * with one pointed end is rendered when there is enough horizontal room for an
 * arrowhead — otherwise the shape degenerates into a plain rectangle.
 *
 * <p>The same primitive is used for reads (orientation of an alignment), mismatch
 * boxes (orientation of the underlying read), and gene/exon bars (transcript
 * direction).
 */
public final class ArrowShape {

  private ArrowShape() {}

  /**
   * Compute the arrowhead pixel width to use for a shape of the given screen
   * width and height. Returns 0 when the shape is too narrow or short for an
   * arrowhead to be visually distinguishable — callers should then draw a plain
   * rectangle.
   *
   * @param shapeWidth  total width of the shape (px)
   * @param shapeHeight total height of the shape (px)
   * @return arrowhead width in px, or 0 to indicate "no arrowhead"
   */
  public static double arrowheadWidth(double shapeWidth, double shapeHeight) {
    if (shapeHeight < 3) return 0;
    // The arrowhead width should be roughly half the height (45° tip) but not
    // so wide that it eats more than ~40% of the shape itself.
    double headByHeight = shapeHeight * 0.5;
    double headByWidth  = shapeWidth  * 0.4;
    double head         = Math.min(headByHeight, headByWidth);
    return head < 2 ? 0 : head;
  }

  /**
   * Fills an arrow-shaped polygon. When {@code reverse} is true the arrowhead
   * points left ({@code 5'} at right side); otherwise it points right.
   *
   * <p>The body of the arrow occupies {@code [x, x + width - head]} (or
   * {@code [x + head, x + width]} for reverse). The tip extends to {@code x + width}
   * (or {@code x} for reverse).
   *
   * <p>If the shape is too small for an arrowhead (see {@link #arrowheadWidth}),
   * a plain {@code fillRect} is drawn so callers can use this method
   * unconditionally.
   *
   * @param paint   fill paint (color or gradient)
   */
  public static void fillArrow(GraphicsContext gc, double x, double y,
                               double width, double height,
                               boolean reverse, Paint paint) {
    gc.setFill(paint);
    double head = arrowheadWidth(width, height);
    if (head <= 0) {
      gc.fillRect(x, y, width, height);
      return;
    }
    double bodyEnd = x + width - head;     // forward: body right edge
    double bodyStart = x + head;            // reverse: body left edge
    double midY = y + height / 2;
    if (reverse) {
      gc.fillPolygon(
          new double[]{x + width, bodyStart, x,    bodyStart, x + width},
          new double[]{y,         y,         midY, y + height, y + height},
          5);
    } else {
      gc.fillPolygon(
          new double[]{x, bodyEnd, x + width, bodyEnd,    x},
          new double[]{y, y,       midY,      y + height, y + height},
          5);
    }
  }

  /**
   * Strokes the outline of the same arrow shape that {@link #fillArrow} draws.
   * Falls back to a {@code strokeRect} when the shape is too small for an
   * arrowhead.
   */
  public static void strokeArrow(GraphicsContext gc, double x, double y,
                                  double width, double height,
                                  boolean reverse, Paint stroke) {
    gc.setStroke(stroke);
    double head = arrowheadWidth(width, height);
    if (head <= 0) {
      gc.strokeRect(x, y, width, height);
      return;
    }
    double bodyEnd   = x + width - head;
    double bodyStart = x + head;
    double midY      = y + height / 2;
    if (reverse) {
      gc.strokePolygon(
          new double[]{x + width, bodyStart, x,    bodyStart, x + width},
          new double[]{y,         y,         midY, y + height, y + height},
          5);
    } else {
      gc.strokePolygon(
          new double[]{x,    bodyEnd, x + width, bodyEnd,    x},
          new double[]{y,    y,       midY,      y + height, y + height},
          5);
    }
  }
}
