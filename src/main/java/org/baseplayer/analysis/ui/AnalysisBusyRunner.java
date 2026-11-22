package org.baseplayer.analysis.ui;

import java.util.function.Consumer;

/**
 * Shared Analysis Manager busy gate for Signal / Point / SV long ops.
 * Shows the in-window dimmer (and keeps ThreadRunner's loading popup anchored
 * to Analysis Manager) for Discover, Run differential, annotate-all, etc.
 */
@FunctionalInterface
public interface AnalysisBusyRunner {

  /**
   * Show busy chrome with {@code statusMessage}, then invoke {@code work} on the
   * FX thread. {@code work} receives an {@code onComplete} callback that must be
   * called when nested async work finishes (or immediately if nothing started).
   */
  void run(String statusMessage, Consumer<Runnable> work);
}
