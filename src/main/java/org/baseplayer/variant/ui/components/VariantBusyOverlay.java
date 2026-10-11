package org.baseplayer.variant.ui.components;

import java.util.List;

import org.baseplayer.io.VcfManager;
import org.baseplayer.services.ThreadRunner;

import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;

/**
 * Variant Manager busy dimmer only — no text, spinner, or cancel on the window.
 * Progress and cancel live in the app-wide {@code LoadingPopup} via {@link ThreadRunner}.
 */
public class VariantBusyOverlay {

    public record Nodes(VBox loadingModal) {}

    public record LockTargets(
        TabPane filterTabPane,
        List<Button> annotateAllChromosomesButtons,
        Button reloadBannerButton
    ) {}

    private Nodes nodes;
    private LockTargets lockTargets;
    private Timeline loadingModalDelayTimer;
    private volatile boolean allChromosomeAnnotationRunning;
    private volatile ThreadRunner.RunnerTask allChromosomeAnnotationTask;

    public void install(Nodes nodes) {
        this.nodes = nodes;
        stripOverlayChrome();
    }

    public void setLockTargets(LockTargets lockTargets) {
        this.lockTargets = lockTargets;
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

    /** Show the dimmer overlay (no message). */
    public void show() {
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
     * Forward all-chromosome progress to the ThreadRunner / LoadingPopup only.
     * Does not paint text on the Variant Manager overlay.
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
    }

    /**
     * Sync overlay + lock targets with ThreadRunner busy state and annotation flag.
     *
     * <p>When ThreadRunner work is active, {@code LoadingPopup} shows progress —
     * the Variant Manager only dims.
     */
    public void syncBusyOverlay() {
        boolean tasksRunning = !ThreadRunner.get().getActiveTasks().isEmpty();
        if (!tasksRunning) {
            allChromosomeAnnotationRunning = false;
            allChromosomeAnnotationTask = null;
        }
        boolean busy = tasksRunning || allChromosomeAnnotationRunning;
        setControlsLocked(busy);
        if (busy) {
            show();
        } else {
            hide();
        }
    }

    /**
     * Lock filter-related controls; when locking, also show the dimmer.
     */
    public void lockFilterControls(boolean locked) {
        setControlsLocked(locked);
        if (locked) {
            show();
        }
    }

    public void cancelDelayedLoadingModal() {
        if (loadingModalDelayTimer != null) {
            loadingModalDelayTimer.stop();
            loadingModalDelayTimer = null;
        }
        if (!allChromosomeAnnotationRunning
                && ThreadRunner.get().getActiveTasks().isEmpty()) {
            hide();
        } else {
            syncBusyOverlay();
        }
    }

    /** Hide any leftover chrome inside the dimmer node (labels, buttons, etc.). */
    private void stripOverlayChrome() {
        if (nodes == null || nodes.loadingModal() == null) {
            return;
        }
        for (javafx.scene.Node child : nodes.loadingModal().getChildren()) {
            child.setVisible(false);
            child.setManaged(false);
        }
    }

    private void setControlsLocked(boolean locked) {
        if (lockTargets == null) {
            return;
        }
        if (lockTargets.filterTabPane() != null) {
            lockTargets.filterTabPane().setDisable(locked);
        }
        if (lockTargets.annotateAllChromosomesButtons() != null) {
            for (Button button : lockTargets.annotateAllChromosomesButtons()) {
                if (button != null) {
                    button.setDisable(locked);
                }
            }
        }
        if (lockTargets.reloadBannerButton() != null) {
            lockTargets.reloadBannerButton().setDisable(locked);
        }
    }
}
