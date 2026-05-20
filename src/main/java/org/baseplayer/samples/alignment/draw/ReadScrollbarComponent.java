package org.baseplayer.samples.alignment.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import javafx.scene.Cursor;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Reusable read-scrollbar renderer + interaction state.
 *
 * <p>State is frame-based: call {@link #beginFrame()} before drawing all
 * read panels, then call {@link #drawScrollbar} for each visible scrollbar.
 * Mouse handlers query/drive this component via {@link #handleMove},
 * {@link #handlePress}, {@link #handleDrag}, and {@link #handleRelease}.</p>
 */
public class ReadScrollbarComponent {

  public enum Section {
    NORMAL,
    TOP,
    BOTTOM
  }

  private record Key(Object owner, Section section) {}

  private static final class Entry {
    private final Key key;
    private final boolean inverted;
    private final double x;
    private final double y;
    private final double width;
    private final double viewportHeight;
    private final double maxScroll;
    private final double thumbY;
    private final double thumbHeight;
    private final DoubleConsumer offsetSetter;

    private Entry(Key key,
                  boolean inverted,
                  double x,
                  double y,
                  double width,
                  double viewportHeight,
                  double maxScroll,
                  double thumbY,
                  double thumbHeight,
                  DoubleConsumer offsetSetter) {
      this.key = key;
      this.inverted = inverted;
      this.x = x;
      this.y = y;
      this.width = width;
      this.viewportHeight = viewportHeight;
      this.maxScroll = maxScroll;
      this.thumbY = thumbY;
      this.thumbHeight = thumbHeight;
      this.offsetSetter = offsetSetter;
    }

    private boolean contains(double px, double py) {
      return px >= x && px <= x + width && py >= y && py <= y + viewportHeight;
    }
  }

  private static final double COLLAPSED_WIDTH = 3.0;
  private static final double EXPANDED_WIDTH = 8.0;

  private final List<Entry> entries = new ArrayList<>();
  private Key hovered = null;
  private Key dragging = null;
  private double dragThumbOffset = 0.0;

  public void beginFrame() {
    entries.clear();
  }

  public void drawScrollbar(GraphicsContext gc,
                            Object owner,
                            Section section,
                            double rightX,
                            double y,
                            double viewportHeight,
                            double contentHeight,
                            double scrollOffset,
                            boolean inverted,
                            DoubleConsumer offsetSetter) {
    if (viewportHeight <= 6 || contentHeight <= viewportHeight + 0.5) return;

    Key key = new Key(owner, section);
    boolean expanded = isExpanded(key);
    double width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
    double x = rightX - width;

    double maxScroll = Math.max(0.0001, contentHeight - viewportHeight);
    double thumbHeight = Math.max(expanded ? 16 : 12, viewportHeight * (viewportHeight / contentHeight));
    thumbHeight = Math.min(viewportHeight, thumbHeight);
    double travel = Math.max(1.0, viewportHeight - thumbHeight);
    double t = Math.max(0.0, Math.min(1.0, scrollOffset / maxScroll));
    if (inverted) t = 1.0 - t;
    double thumbY = y + t * travel;

    gc.setFill(expanded ? Color.rgb(140, 140, 140, 0.45) : Color.rgb(120, 120, 120, 0.28));
    gc.fillRoundRect(x, y, width, viewportHeight, expanded ? 3 : 2, expanded ? 3 : 2);
    gc.setFill(expanded ? Color.rgb(235, 235, 235, 0.95) : Color.rgb(220, 220, 220, 0.78));
    gc.fillRoundRect(x, thumbY, width, thumbHeight, expanded ? 3 : 2, expanded ? 3 : 2);

    entries.add(new Entry(key, inverted, x, y, width, viewportHeight, maxScroll, thumbY, thumbHeight, offsetSetter));
  }

  public boolean handleMove(double mouseX, double mouseY) {
    if (dragging != null) return false;
    Key previous = hovered;
    Entry hit = findAt(mouseX, mouseY);
    hovered = hit != null ? hit.key : null;
    return previous != hovered;
  }

  public boolean handlePress(double mouseX, double mouseY) {
    Entry hit = findAt(mouseX, mouseY);
    if (hit == null) return false;

    dragging = hit.key;
    hovered = hit.key;

    double thumbTop = hit.thumbY;
    double thumbBottom = hit.thumbY + hit.thumbHeight;
    if (mouseY >= thumbTop && mouseY <= thumbBottom) {
      dragThumbOffset = mouseY - thumbTop;
    } else {
      dragThumbOffset = hit.thumbHeight / 2.0;
    }

    applyDrag(hit, mouseY);
    return true;
  }

  public boolean handleDrag(double mouseY) {
    if (dragging == null) return false;
    Entry hit = findByKey(dragging);
    if (hit == null) return false;
    applyDrag(hit, mouseY);
    return true;
  }

  public boolean handleRelease(double mouseX, double mouseY) {
    if (dragging == null) return false;
    dragging = null;
    dragThumbOffset = 0.0;
    Entry hit = findAt(mouseX, mouseY);
    hovered = hit != null ? hit.key : null;
    return true;
  }

  public boolean clearHover() {
    if (dragging != null || hovered == null) return false;
    hovered = null;
    return true;
  }

  public boolean isOver(double mouseX, double mouseY) {
    return findAt(mouseX, mouseY) != null;
  }

  public Cursor cursorFor(double mouseX, double mouseY) {
    if (dragging != null) return Cursor.V_RESIZE;
    return isOver(mouseX, mouseY) ? Cursor.V_RESIZE : Cursor.DEFAULT;
  }

  public boolean hasHoverOrDrag() {
    return hovered != null || dragging != null;
  }

  private boolean isExpanded(Key key) {
    return key.equals(hovered) || key.equals(dragging);
  }

  private Entry findAt(double mouseX, double mouseY) {
    for (Entry entry : entries) {
      if (entry.contains(mouseX, mouseY)) return entry;
    }
    return null;
  }

  private Entry findByKey(Key key) {
    for (Entry entry : entries) {
      if (entry.key.equals(key)) return entry;
    }
    return null;
  }

  private void applyDrag(Entry hit, double mouseY) {
    double travel = Math.max(1.0, hit.viewportHeight - hit.thumbHeight);
    double thumbTop = mouseY - dragThumbOffset;
    thumbTop = Math.max(hit.y, Math.min(hit.y + travel, thumbTop));

    double t = (thumbTop - hit.y) / travel;
    if (hit.inverted) t = 1.0 - t;
    double offset = Math.max(0.0, Math.min(hit.maxScroll, t * hit.maxScroll));
    hit.offsetSetter.accept(offset);
  }
}
