package org.baseplayer.services;

/**
 * Per-{@link org.baseplayer.draw.DrawStack} navigation / rendering state flags.
 *
 * <p>Each {@code DrawStack} owns one instance so that panning or animating one
 * viewport does not suppress fetches in other viewports.
 *
 * <p>These flags are read by drawing classes to defer expensive operations
 * (e.g. BAM fetches) while the user is actively navigating (panning,
 * scrolling, zooming) in that specific stack.
 */
public final class NavigationState {

  /** True while the user is actively panning, scrolling, or zoom-animating. Defers BAM fetches. */
  public volatile boolean navigating = false;

  /** True while a zoom animation is running. Defers BAM fetches. */
  public volatile boolean animationRunning = false;

  /** True while line-zoom mode is active (right-drag zoom). Blocks all BAM fetches. */
  public volatile boolean lineZoomerActive = false;
}
