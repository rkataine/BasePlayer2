package org.baseplayer.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

/**
 * Central executor for all background loading operations.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #submit} — submits a {@link Supplier} to the shared thread pool,
 *       fires {@code onSuccess} on the FX thread when done, and registers the
 *       task so the loading popup can track it.</li>
 *   <li>{@link #track} — registers a task that is already running in an external
 *       executor (e.g. BAM read fetches in {@code AlignmentFile.fetchPool}).
 *       The task is tracked for display and cancellation, but no new thread is
 *       started.</li>
 * </ul>
 *
 * <p>All tasks are tracked in the active task list. When the list changes, the
 * {@code onTasksChanged} callback is fired on the FX thread so
 * {@link LoadingManager} can update the popup.
 */
public final class ThreadRunner {

  private static final ThreadRunner INSTANCE = new ThreadRunner();

  public static ThreadRunner get() {
    return INSTANCE;
  }

  // Shared pool for file-open operations (not for read fetches — those stay in
  // AlignmentFile.fetchPool which is per-file and long-lived).
  private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
    Thread t = new Thread(r, "loader-pool");
    t.setDaemon(true);
    return t;
  });

  private final List<RunnerTask> activeTasks =
      Collections.synchronizedList(new ArrayList<>());

  private volatile Runnable onTasksChanged;

  private ThreadRunner() {}

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Callback fired (on the FX thread) whenever the active task list changes.
   * Called when a task is added, completed, or cancelled.
   */
  public void setOnTasksChanged(Runnable callback) {
    this.onTasksChanged = callback;
  }

  /**
   * Submit a blocking {@link Supplier} to the thread pool.
   *
   * <p>The task is registered immediately. {@code onSuccess} is called on the
   * FX thread if the task completes without being cancelled; the task is then
   * automatically removed from the active list. If the supplier throws an
   * exception the task is silently completed (removed from the list).
   *
   * <p>The {@code onSuccess} consumer receives {@code null} if the supplier
   * returned {@code null} or threw an exception — callers should check for null.
   *
   * @param description human-readable label shown in the popup
   * @param supplier    blocking work to run on the pool thread
   * @param onSuccess   called on the FX thread with the supplier result
   * @return the {@link RunnerTask} for the registered task
   */
  public <T> RunnerTask submit(String description,
                               Supplier<T> supplier,
                               Consumer<T> onSuccess) {
    RunnerTask task = new RunnerTask(description);

    Future<?> future = pool.submit(() -> {
      T result = null;
      try {
        result = supplier.get();
      } catch (Exception e) {
        System.err.println("ThreadRunner [" + description + "]: " + e.getMessage());
      }
      final T finalResult = result;
      if (!task.isCancelled()) {
        Platform.runLater(() -> {
          if (!task.isCancelled()) {
            onSuccess.accept(finalResult);
          }
          task.complete();
        });
      } else {
        task.complete();
      }
    });

    task.setFuture(future);
    register(task);
    return task;
  }

  /**
   * Track an externally-running task (e.g. a BAM read fetch running in
   * {@code AlignmentFile.fetchPool}). No thread is started; {@code cancelAction}
   * is invoked when the user cancels.
   *
   * <p>The caller is responsible for calling {@link RunnerTask#complete()} when
   * the external work finishes.
   *
   * @param description  human-readable label shown in the popup
   * @param cancelAction called (on whatever thread) when the task is cancelled
   * @return the {@link RunnerTask}; call {@link RunnerTask#complete()} when done
   */
  public RunnerTask track(String description, Runnable cancelAction) {
    RunnerTask task = new RunnerTask(description);
    task.setExternalCancel(cancelAction);
    register(task);
    return task;
  }

  /** Cancel all currently active tasks. */
  public void cancelAll() {
    List<RunnerTask> snapshot;
    synchronized (activeTasks) {
      snapshot = new ArrayList<>(activeTasks);
    }
    for (RunnerTask t : snapshot) {
      t.cancel();
    }
  }

  /** Snapshot of currently active tasks (unmodifiable, safe to read from any thread). */
  public List<RunnerTask> getActiveTasks() {
    synchronized (activeTasks) {
      return Collections.unmodifiableList(new ArrayList<>(activeTasks));
    }
  }

  // ── Private ────────────────────────────────────────────────────────────────

  private void register(RunnerTask task) {
    activeTasks.add(task);
    notifyChanged();
    task.setOnComplete(() -> {
      activeTasks.remove(task);
      notifyChanged();
    });
  }

  private void notifyChanged() {
    Runnable cb = onTasksChanged;
    if (cb == null) return;
    if (Platform.isFxApplicationThread()) {
      cb.run();
    } else {
      Platform.runLater(cb);
    }
  }

  // ── Inner class ────────────────────────────────────────────────────────────

  /** Represents one tracked loading operation. */
  public static final class RunnerTask {

    private final String id = UUID.randomUUID().toString();
    private final String description;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile Future<?> future;
    private volatile Runnable extraCancel;
    private volatile Runnable onComplete;

    RunnerTask(String description) {
      this.description = description;
    }

    public String getId()          { return id; }
    public String getDescription() { return description; }
    public boolean isCancelled()   { return cancelled.get(); }
    public boolean isCompleted()   { return completed.get(); }

    /** Cancel this task. Interrupts the pool thread (if any) and calls the extra cancel action. Thread-safe. */
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        Future<?> f = future;
        if (f != null) f.cancel(true);
        Runnable ec = extraCancel;
        if (ec != null) ec.run();
        complete();
      }
    }

    /**
     * Mark this task as finished and remove it from the active list.
     * Idempotent — safe to call multiple times. Thread-safe.
     */
    public void complete() {
      if (completed.compareAndSet(false, true)) {
        Runnable cb = onComplete;
        if (cb != null) cb.run();
      }
    }

    void setFuture(Future<?> future) {
      this.future = future;
    }

    void setExternalCancel(Runnable cancelAction) {
      this.extraCancel = cancelAction;
    }

    void setOnComplete(Runnable onComplete) {
      this.onComplete = onComplete;
    }
  }
}
