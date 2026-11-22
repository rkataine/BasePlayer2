package org.baseplayer.services;

import java.util.List;

import org.baseplayer.MainApp;
import org.baseplayer.components.LoadingPopup;

import javafx.application.Platform;
import javafx.stage.Window;

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

  /**
   * Update the overall progress bar in the loading popup.
   * Safe to call from any thread.
   */
  public void setProgress(int current, int total) {
    if (Platform.isFxApplicationThread()) {
      ensurePopup();
      popup.setProgress(current, total);
      return;
    }
    Platform.runLater(() -> {
      ensurePopup();
      popup.setProgress(current, total);
    });
  }

  /**
   * Update the overall progress bar directly.
   * Safe to call from any thread.
   */
  public void setProgress(double progress) {
    if (Platform.isFxApplicationThread()) {
      ensurePopup();
      popup.setProgress(progress);
      return;
    }
    Platform.runLater(() -> {
      ensurePopup();
      popup.setProgress(progress);
    });
  }

  /**
   * Per-step detail under the overall bar (e.g. ClinVar windows within a chromosome).
   * Safe from any thread.
   */
  public void setDetailMessage(String detail) {
    if (Platform.isFxApplicationThread()) {
      ensurePopup();
      popup.setDetailMessage(detail);
      return;
    }
    Platform.runLater(() -> {
      ensurePopup();
      popup.setDetailMessage(detail);
    });
  }

  /** Fine-grained detail progress bar. Safe from any thread. */
  public void setDetailProgress(int current, int total) {
    if (Platform.isFxApplicationThread()) {
      ensurePopup();
      popup.setDetailProgress(current, total);
      return;
    }
    Platform.runLater(() -> {
      ensurePopup();
      popup.setDetailProgress(current, total);
    });
  }

  public void setDetailProgress(double progress) {
    if (Platform.isFxApplicationThread()) {
      ensurePopup();
      popup.setDetailProgress(progress);
      return;
    }
    Platform.runLater(() -> {
      ensurePopup();
      popup.setDetailProgress(progress);
    });
  }

  /** Hide detail message and detail progress bar. Safe from any thread. */
  public void clearDetail() {
    if (Platform.isFxApplicationThread()) {
      if (popup != null) {
        popup.clearDetail();
      }
      return;
    }
    Platform.runLater(() -> {
      if (popup != null) {
        popup.clearDetail();
      }
    });
  }

  /**
   * Hide the loading popup while a modal dialog is shown (must run on FX thread).
   * Nestable; pair each call with {@link #endModalSuppress()}.
   */
  public void beginModalSuppress() {
    ensurePopup();
    popup.beginModalSuppress();
  }

  /** Restore the loading popup after a modal dialog (must run on FX thread). */
  public void endModalSuppress() {
    if (popup != null) {
      popup.endModalSuppress();
    }
  }

  // ── Private ────────────────────────────────────────────────────────────────

  /** Called on the FX thread whenever the ThreadRunner active-task list changes. */
  private void onTasksChanged() {
    List<ThreadRunner.RunnerTask> tasks = ThreadRunner.get().getActiveTasks();

    if (tasks.isEmpty()) {
      if (popup != null) {
        popup.hide();
      }
    } else {
      Window owner = resolveOwnerWindow();
      ensurePopupForOwner(owner);
      String message = buildMessage(tasks);
      String detail = buildDetail(tasks);
      if (!popup.isShowing()) {
        popup.show(message, owner, ThreadRunner.get()::cancelAll);
        popup.setDetailMessage(detail);
      } else {
        popup.setMessage(message);
        popup.setDetailMessage(detail);
        // Re-anchor if the previous owner (e.g. Variant Manager) was hidden.
        popup.ensureVisibleOver(owner);
      }
    }
    org.baseplayer.variant.ui.VariantManagerController controller =
        org.baseplayer.variant.ui.VariantManagerWindow.getCurrentController();
    if (controller != null) {
      controller.syncBusyOverlay();
    }
  }

  /**
   * Prefer Analysis Manager when it is open so annotate/discover-all loading stays
   * over that window. Fall back to the main stage when VM is hidden/minimized so
   * the popup is not orphaned on a non-showing owner.
   */
  private static Window resolveOwnerWindow() {
    org.baseplayer.variant.ui.VariantManagerController controller =
        org.baseplayer.variant.ui.VariantManagerWindow.getCurrentController();
    if (controller != null) {
      Window vm = controller.getStage();
      if (vm instanceof javafx.stage.Stage stage
          && stage.isShowing()
          && !stage.isIconified()) {
        return stage;
      }
    }
    if (MainApp.stage != null && MainApp.stage.isShowing()) {
      return MainApp.stage;
    }
    return MainApp.stage;
  }

  private void ensurePopup() {
    if (popup == null) {
      if (!Platform.isFxApplicationThread()) {
        throw new IllegalStateException("LoadingPopup must be created on the JavaFX thread");
      }
      popup = new LoadingPopup();
    }
  }

  /**
   * JavaFX allows {@code initOwner} only once. If Analysis Manager should own the
   * popup but an earlier MainApp-owned instance exists, dispose and recreate.
   */
  private void ensurePopupForOwner(Window owner) {
    if (popup != null && owner != null) {
      Window current = popup.stageOwner();
      if (current != null && current != owner) {
        popup.dispose();
        popup = null;
      }
    }
    ensurePopup();
  }

  private String buildMessage(List<ThreadRunner.RunnerTask> tasks) {
    if (tasks.size() == 1) {
      return tasks.get(0).getDescription();
    }
    ThreadRunner.RunnerTask latest = tasks.get(tasks.size() - 1);
    return latest.getDescription() + " (+" + (tasks.size() - 1) + " more)";
  }

  private static String buildDetail(List<ThreadRunner.RunnerTask> tasks) {
    if (tasks == null || tasks.isEmpty()) {
      return "";
    }
    ThreadRunner.RunnerTask latest = tasks.get(tasks.size() - 1);
    String detail = latest.getDetailMessage();
    return detail != null ? detail : "";
  }
}
