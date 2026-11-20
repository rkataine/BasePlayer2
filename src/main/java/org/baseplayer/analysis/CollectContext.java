package org.baseplayer.analysis;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.baseplayer.services.LoadingManager;
import org.baseplayer.services.ThreadRunner;

import javafx.application.Platform;

/**
 * Progress + cooperative cancellation for Analysis Manager value collection.
 * Drives {@link LoadingManager} (overall bar → ETA) and optional status text.
 */
public final class CollectContext {

  private final ThreadRunner.RunnerTask task;
  private final Consumer<String> onStatus;
  private final AtomicBoolean cancelled;
  private final long startMs = System.currentTimeMillis();
  private volatile int lastPct = -1;
  private volatile long lastUiMs;

  public CollectContext(ThreadRunner.RunnerTask task, Consumer<String> onStatus) {
    this.task = task;
    this.onStatus = onStatus;
    this.cancelled = new AtomicBoolean(false);
  }

  public void markCancelled() {
    cancelled.set(true);
  }

  public boolean isCancelled() {
    if (cancelled.get()) {
      return true;
    }
    if (task != null && task.isCancelled()) {
      cancelled.set(true);
      return true;
    }
    if (Thread.currentThread().isInterrupted()) {
      cancelled.set(true);
      return true;
    }
    return false;
  }

  /** Throws {@link CancellationException} when cancel was requested. */
  public void checkCancelled() {
    if (isCancelled()) {
      throw new CancellationException("Analysis cancelled");
    }
  }

  /**
   * Update overall progress {@code done}/{@code total} (drives ETA) and a short message.
   * Throttled to avoid flooding the FX thread.
   */
  public void progress(int done, int total, String message) {
    int safeTotal = Math.max(1, total);
    int safeDone = Math.max(0, Math.min(done, safeTotal));
    double frac = safeDone / (double) safeTotal;
    int pct = (int) Math.floor(frac * 100.0);
    long now = System.currentTimeMillis();
    boolean force = safeDone >= safeTotal || safeDone == 0;
    if (!force && pct == lastPct && now - lastUiMs < 200) {
      return;
    }
    lastPct = pct;
    lastUiMs = now;

    LoadingManager.get().setProgress(safeDone, safeTotal);

    String eta = etaText(frac, now - startMs);
    String detail = message != null ? message : "";
    String suffix = safeDone + "/" + safeTotal
        + (detail.isBlank() ? "" : " — " + detail)
        + (eta.isBlank() ? "" : " · ETA " + eta);
    if (task != null) {
      task.setProgressSuffix(suffix);
      task.setDetailMessage(detail);
      ThreadRunner.get().notifyDescriptionChanged();
    }
    LoadingManager.get().setDetailMessage(detail);
    if (onStatus != null) {
      Platform.runLater(() -> onStatus.accept(suffix));
    }
  }

  public void clearUi() {
    LoadingManager.get().clearDetail();
    LoadingManager.get().setProgress(0.0);
    if (task != null) {
      task.setProgressSuffix("");
      task.setDetailMessage("");
    }
  }

  private static String etaText(double frac, long elapsedMs) {
    if (frac <= 0.02 || frac >= 1.0 || elapsedMs < 800) {
      return "";
    }
    long remainingMs = (long) (elapsedMs / frac - elapsedMs);
    long seconds = Math.max(0, remainingMs / 1000);
    if (seconds < 60) {
      return seconds + "s";
    }
    return (seconds / 60) + "m " + (seconds % 60) + "s";
  }
}
