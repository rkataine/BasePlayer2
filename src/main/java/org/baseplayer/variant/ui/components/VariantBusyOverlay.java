package org.baseplayer.variant.ui.components;

import java.util.List;

import org.baseplayer.io.VcfManager;
import org.baseplayer.services.ThreadRunner;

import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;

/**
 * Loading / busy overlay for variant manager: show/hide, progress text, cancel.
 */
public class VariantBusyOverlay {

    public record Nodes(
        VBox loadingModal,
        ProgressIndicator loadingSpinner,
        Label loadingLabel,
        ProgressBar loadingProgressBar,
        Label loadingEtaLabel,
        Button loadingCancelButton
    ) {}

    public record LockTargets(
        TabPane filterTabPane,
        Button annotateAllChromosomesButton,
        Button reloadBannerButton
    ) {}

    private Nodes nodes;
    private LockTargets lockTargets;
    private Runnable onCancel;
    private Timeline loadingModalDelayTimer;
    private volatile boolean allChromosomeAnnotationRunning;
    private volatile ThreadRunner.RunnerTask allChromosomeAnnotationTask;

    public void install(Nodes nodes) {
        this.nodes = nodes;
        if (nodes != null && nodes.loadingCancelButton() != null) {
            nodes.loadingCancelButton().setOnAction(e -> {
                if (onCancel != null) {
                    onCancel.run();
                }
            });
        }
    }

    public void setLockTargets(LockTargets lockTargets) {
        this.lockTargets = lockTargets;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public void setAllChromosomeAnnotationRunning(boolean running) {
        this.allChromosomeAnnotationRunning = running;
    }

    public boolean isAllChromosomeAnnotationRunning() {
        return allChromosomeAnnotationRunning;
    }

    public void setAllChromosomeAnnotationTask(ThreadRunner.RunnerTask task) {
        this.allChromosomeAnnotationTask = task;
    }

    public ThreadRunner.RunnerTask getAllChromosomeAnnotationTask() {
        return allChromosomeAnnotationTask;
    }

    public void show(String message) {
        applyLoadingModalVisuals(message);
        if (nodes != null && nodes.loadingModal() != null) {
            nodes.loadingModal().setVisible(true);
            nodes.loadingModal().setManaged(true);
        }
    }

    public void hide() {
        hideVisualOnly();
    }

    public void hideVisualOnly() {
        if (nodes != null && nodes.loadingModal() != null) {
            nodes.loadingModal().setVisible(false);
            nodes.loadingModal().setManaged(false);
        }
    }

    /**
     * Update overlay message from all-chromosome annotation progress.
     * Also updates the active ThreadRunner task suffix when one is set.
     */
    public void updateProgress(VcfManager.AllChromosomeProgress progress) {
        if (!allChromosomeAnnotationRunning || progress == null) {
            return;
        }

        int completed = progress.completedChromosomes();
        String message = progress.chromosome()
            + " (" + completed + "/" + progress.totalChromosomes() + ")"
            + ", rows: " + progress.totalRows();
        ThreadRunner.RunnerTask task = allChromosomeAnnotationTask;
        if (task != null) {
            task.setProgressSuffix(message);
            ThreadRunner.get().notifyDescriptionChanged();
        }
        applyLoadingModalVisuals("Annotating all chromosomes… " + message);
    }

    /**
     * Sync overlay + lock targets with ThreadRunner busy state and annotation flag.
     */
    public void syncBusyOverlay() {
        boolean tasksRunning = !ThreadRunner.get().getActiveTasks().isEmpty();
        if (!tasksRunning) {
            allChromosomeAnnotationRunning = false;
            allChromosomeAnnotationTask = null;
        }
        boolean busy = tasksRunning || allChromosomeAnnotationRunning;
        if (busy) {
            setControlsLocked(true);
            showBusyOverlay();
        } else {
            setControlsLocked(false);
            hide();
        }
    }

    /**
     * Lock filter-related controls; when locking, also show the busy overlay.
     */
    public void lockFilterControls(boolean locked) {
        setControlsLocked(locked);
        if (locked) {
            showBusyOverlay();
        }
    }

    public void showBusyOverlay() {
        String message = "Loading";
        List<ThreadRunner.RunnerTask> tasks = ThreadRunner.get().getActiveTasks();
        if (!tasks.isEmpty()) {
            message = tasks.get(tasks.size() - 1).getDescription();
        } else if (allChromosomeAnnotationRunning) {
            message = "Annotating all chromosomes…";
        }
        show(message);
    }

    public void cancelDelayedLoadingModal() {
        if (loadingModalDelayTimer != null) {
            loadingModalDelayTimer.stop();
            loadingModalDelayTimer = null;
        }
        if (!allChromosomeAnnotationRunning) {
            hide();
        }
    }

    public void applyLoadingModalVisuals(String message) {
        if (nodes == null || nodes.loadingModal() == null) {
            return;
        }
        nodes.loadingLabel().setText(message == null || message.isBlank() ? "Loading" : message);
        if (nodes.loadingSpinner() != null) {
            nodes.loadingSpinner().setManaged(false);
            nodes.loadingSpinner().setVisible(false);
        }
        if (nodes.loadingProgressBar() != null) {
            nodes.loadingProgressBar().setManaged(false);
            nodes.loadingProgressBar().setVisible(false);
        }
        if (nodes.loadingEtaLabel() != null) {
            nodes.loadingEtaLabel().setManaged(false);
            nodes.loadingEtaLabel().setVisible(false);
        }
        if (nodes.loadingCancelButton() != null) {
            nodes.loadingCancelButton().setManaged(true);
            nodes.loadingCancelButton().setVisible(true);
            nodes.loadingCancelButton().setDisable(false);
        }
    }

    private void setControlsLocked(boolean locked) {
        if (lockTargets == null) {
            return;
        }
        if (lockTargets.filterTabPane() != null) {
            lockTargets.filterTabPane().setDisable(locked);
        }
        if (lockTargets.annotateAllChromosomesButton() != null) {
            lockTargets.annotateAllChromosomesButton().setDisable(locked);
        }
        if (lockTargets.reloadBannerButton() != null) {
            lockTargets.reloadBannerButton().setDisable(locked);
        }
    }
}
