package org.baseplayer.variant.ui.components;

import java.util.List;

import org.baseplayer.io.VcfManager;
import org.baseplayer.services.ThreadRunner;

import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;

/**
 * Variant Manager busy overlay: dims the window and shows spinner + status while
 * ThreadRunner work (load / annotate / table update) or annotate-all is running.
 */
public class VariantBusyOverlay {

    public record Nodes(VBox loadingModal, ProgressIndicator spinner, Label statusLabel) {}

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
    /** Explicit status (e.g. "Annotating"); otherwise derived from active ThreadRunner tasks. */
    private String statusMessage;

    public void install(Nodes nodes) {
        this.nodes = nodes;
        applyStatusChrome(false);
    }

    public void setLockTargets(LockTargets lockTargets) {
        this.lockTargets = lockTargets;
    }

    public void setAllChromosomeAnnotationRunning(boolean running) {
        this.allChromosomeAnnotationRunning = running;
        if (running) {
            statusMessage = "Annotating";
        } else if ("Annotating".equals(statusMessage)) {
            statusMessage = null;
        }
        applyStatusChrome(isOverlayVisible());
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

    /** Show spinner + status text (e.g. gene annotation after a region load). */
    public void showAnnotating() {
        setStatusMessage("Annotating");
        show();
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message == null || message.isBlank() ? null : message.trim();
        applyStatusChrome(isOverlayVisible());
    }

    public void clearStatusMessage() {
        setStatusMessage(null);
    }

    /** Show the dimmer overlay with spinner + status. */
    public void show() {
        if (nodes != null && nodes.loadingModal() != null) {
            nodes.loadingModal().setVisible(true);
            nodes.loadingModal().setManaged(true);
        }
        applyStatusChrome(true);
    }

    public void hide() {
        clearStatusMessage();
        hideVisualOnly();
    }

    public void hideVisualOnly() {
        if (nodes != null && nodes.loadingModal() != null) {
            nodes.loadingModal().setVisible(false);
            nodes.loadingModal().setManaged(false);
        }
        applyStatusChrome(false);
    }

    /**
     * Forward all-chromosome progress to the ThreadRunner / LoadingPopup.
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
        if ("Annotating".equals(statusMessage) || statusMessage == null) {
            // Keep VM label short; detail stays in LoadingPopup.
            statusMessage = "Annotating";
            applyStatusChrome(true);
        }
    }

    /**
     * Sync overlay + lock targets with ThreadRunner busy state and annotation flag.
     */
    public void syncBusyOverlay() {
        boolean tasksRunning = !ThreadRunner.get().getActiveTasks().isEmpty();
        if (!tasksRunning) {
            allChromosomeAnnotationRunning = false;
            allChromosomeAnnotationTask = null;
            statusMessage = null;
        } else if (allChromosomeAnnotationRunning && statusMessage == null) {
            statusMessage = "Annotating";
        }
        boolean busy = tasksRunning || allChromosomeAnnotationRunning;
        setControlsLocked(busy);
        if (busy) {
            if (nodes != null && nodes.loadingModal() != null) {
                nodes.loadingModal().setVisible(true);
                nodes.loadingModal().setManaged(true);
            }
            applyStatusChrome(true);
        } else {
            hideVisualOnly();
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

    private boolean isOverlayVisible() {
        return nodes != null
            && nodes.loadingModal() != null
            && nodes.loadingModal().isVisible();
    }

    private void applyStatusChrome(boolean overlayVisible) {
        if (nodes == null) {
            return;
        }
        String text = resolveStatusText(overlayVisible);
        boolean showStatus = overlayVisible && text != null;
        if (nodes.spinner() != null) {
            nodes.spinner().setVisible(showStatus);
            nodes.spinner().setManaged(showStatus);
        }
        if (nodes.statusLabel() != null) {
            if (showStatus) {
                nodes.statusLabel().setText(text);
            }
            nodes.statusLabel().setVisible(showStatus);
            nodes.statusLabel().setManaged(showStatus);
        }
    }

    /** Prefer explicit status; else a short label from the latest ThreadRunner task. */
    private String resolveStatusText(boolean overlayVisible) {
        if (!overlayVisible) {
            return null;
        }
        if (statusMessage != null) {
            return statusMessage;
        }
        if (allChromosomeAnnotationRunning) {
            return "Annotating";
        }
        List<ThreadRunner.RunnerTask> tasks = ThreadRunner.get().getActiveTasks();
        if (tasks.isEmpty()) {
            return "Working…";
        }
        return shortenTaskLabel(tasks.get(tasks.size() - 1).getDescription());
    }

    private static String shortenTaskLabel(String description) {
        if (description == null || description.isBlank()) {
            return "Working…";
        }
        String text = description.trim();
        String lower = text.toLowerCase();
        if (lower.contains("control af")) {
            return "Annotating control AF…";
        }
        if (lower.contains("updating tables") || lower.contains("building tables")) {
            return "Updating tables…";
        }
        if (lower.contains("annotat")) {
            return "Annotating";
        }
        if (lower.startsWith("loading variants") || lower.startsWith("appending variants")) {
            return "Loading variants…";
        }
        if (text.length() > 42) {
            return text.substring(0, 41) + "…";
        }
        return text;
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
