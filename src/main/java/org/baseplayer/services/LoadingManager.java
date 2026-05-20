package org.baseplayer.services;

import java.util.List;

import org.baseplayer.MainApp;
import org.baseplayer.components.LoadingPopup;

/**
 * Owns the single application-wide {@link LoadingPopup} and keeps it in sync
 * with {@link ThreadRunner}'s active task list.
 *
 * <p>When {@link ThreadRunner} has active tasks the popup is shown; when the
 * list empties the popup is hidden. The popup's Cancel button calls
 * {@link ThreadRunner#cancelAll()}.
 *
 * <p>Must be initialised on the JavaFX thread (or before any tasks are submitted).
 * {@link ServiceRegistry} initialises it eagerly on startup.
 */
public final class LoadingManager {

  private static final LoadingManager INSTANCE = new LoadingManager();

  public static LoadingManager get() {
    return INSTANCE;
  }

  private LoadingPopup popup;  // lazily created on first use (always on FX thread)

  private LoadingManager() {
    ThreadRunner.get().setOnTasksChanged(this::onTasksChanged);
  }

  // ── Private ────────────────────────────────────────────────────────────────

  /** Called on the FX thread whenever the ThreadRunner active-task list changes. */
  private void onTasksChanged() {
    List<ThreadRunner.RunnerTask> tasks = ThreadRunner.get().getActiveTasks();
    if (tasks.isEmpty()) {
      if (popup != null) popup.hide();
    } else {
      ensurePopup();
      String message = buildMessage(tasks);
      if (!popup.isShowing()) {
        popup.show(message, MainApp.stage, ThreadRunner.get()::cancelAll);
      } else {
        popup.setMessage(message);
      }
    }
  }

  private void ensurePopup() {
    if (popup == null) {
      popup = new LoadingPopup();
    }
  }

  private String buildMessage(List<ThreadRunner.RunnerTask> tasks) {
    if (tasks.size() == 1) {
      return tasks.get(0).getDescription();
    }
    return tasks.get(0).getDescription() + " (+" + (tasks.size() - 1) + " more)";
  }
}
