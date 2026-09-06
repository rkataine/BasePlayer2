package org.baseplayer.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.features.FeatureTracksCanvas;

import javafx.scene.layout.StackPane;

/**
 * Manages the collection of {@link DrawStack}s and tracks which stack is
 * currently under the mouse pointer.
 *
 * <p>Extracted from the former static fields in {@code MainController} to
 * decouple the data model from the FXML controller lifecycle and to make the
 * dependency explicit via the {@link ServiceRegistry}.</p>
 */
public class DrawStackManager {

  private final List<DrawStack> drawStacks = new ArrayList<>();

  /** Fired when a new stack is added; used by data loaders to attach per-stack listeners. */
  private Consumer<DrawStack> onStackAdded;

  /** The stack currently under the user's mouse pointer (may be {@code null}). */
  private volatile DrawStack hoverStack;
  
  /** The main viewport StackPane (alignmentOverlayPane) - set by MainController. */
  private StackPane alignmentOverlayPane;

  // ── Stack collection ──────────────────────────────────────────────────────────

  /** Returns an unmodifiable snapshot of the current stacks. */
  public List<DrawStack> getStacks() {
    return Collections.unmodifiableList(drawStacks);
  }

  /** Returns the mutable backing list.  Prefer {@link #getStacks()} for iteration. */
  public List<DrawStack> getStacksMutable() {
    return drawStacks;
  }

  public int size() { return drawStacks.size(); }

  public void add(DrawStack stack) {
    drawStacks.add(stack);
    if (onStackAdded != null) onStackAdded.accept(stack);
  }

  public void setOnStackAdded(Consumer<DrawStack> callback) { this.onStackAdded = callback; }

  public void remove(DrawStack stack) { drawStacks.remove(stack); }

  public void removeLast() { drawStacks.removeLast(); }

  public boolean isEmpty() { return drawStacks.isEmpty(); }

  public DrawStack getFirst() { return drawStacks.getFirst(); }

  // ── Hover stack ───────────────────────────────────────────────────────────────

  public DrawStack getHoverStack() { return hoverStack; }

  public void setHoverStack(DrawStack stack) { this.hoverStack = stack; }

  // ── Feature tracks convenience ────────────────────────────────────────────────

  /**
   * Returns the {@link FeatureTracksCanvas} from the first stack,
   * or {@code null} if no stacks exist.
   */
  public FeatureTracksCanvas getFeatureTracksCanvas() {
    if (drawStacks.isEmpty()) return null;
    return drawStacks.getFirst().featureTracksCanvas;
  }

  public void setAlignmentOverlayPane(StackPane pane) {
    this.alignmentOverlayPane = pane;
  }

  public StackPane getAlignmentOverlayPane() {
    return alignmentOverlayPane;
  }
}
