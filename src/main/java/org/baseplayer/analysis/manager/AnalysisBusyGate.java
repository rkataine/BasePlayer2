package org.baseplayer.analysis.manager;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.MainApp;
import org.baseplayer.analysis.ui.AnalysisBusyRunner;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.ui.components.VariantBusyOverlay;

import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Single owner for Analysis Manager busy chrome: in-window dimmer
 * ({@link VariantBusyOverlay}) plus {@link org.baseplayer.services.LoadingManager}
 * owner-stage resolution.
 * <p>
 * Do not add timers or suppress flags here — show/hide stays on this gate.
 */
public final class AnalysisBusyGate implements AnalysisBusyRunner {

    private final VariantBusyOverlay overlay = new VariantBusyOverlay();
    private Supplier<Stage> stageSupplier = () -> null;
    private BooleanSupplier disposed = () -> false;
    private Runnable onThreadRunnerIdle;

    public void install(
            VariantBusyOverlay.Nodes nodes,
            VariantBusyOverlay.LockTargets lockTargets,
            Supplier<Stage> stageSupplier,
            BooleanSupplier disposed) {
        this.stageSupplier = stageSupplier != null ? stageSupplier : () -> null;
        this.disposed = disposed != null ? disposed : () -> false;
        overlay.install(nodes);
        overlay.setLockTargets(lockTargets);
    }

    public void setLockTargets(VariantBusyOverlay.LockTargets lockTargets) {
        overlay.setLockTargets(lockTargets);
    }

    /**
     * Optional FX-thread hook when ThreadRunner goes idle (e.g. clear stuck
     * rebuild flags after Cancel). Called from {@link #sync()}.
     */
    public void setOnThreadRunnerIdle(Runnable onThreadRunnerIdle) {
        this.onThreadRunnerIdle = onThreadRunnerIdle;
    }

    @Override
    public void run(String statusMessage, Consumer<Runnable> work) {
        if (disposed.getAsBoolean()) {
            if (work != null) {
                work.accept(() -> { });
            }
            return;
        }
        String message = statusMessage != null && !statusMessage.isBlank()
            ? statusMessage.trim()
            : "Working…";
        overlay.cancelDelayedLoadingModal();
        overlay.setAllChromosomeAnnotationRunning(true);
        overlay.setStatusMessage(message);
        overlay.lockFilterControls(true);

        ThreadRunner.RunnerTask gate = ThreadRunner.get().track(
            message,
            this::finishChrome);
        overlay.setAllChromosomeAnnotationTask(gate);
        overlay.syncBusyOverlay();

        Platform.runLater(() -> {
            if (gate.isCancelled() || gate.isCompleted()) {
                return;
            }
            if (work == null) {
                complete();
                return;
            }
            work.accept(this::complete);
            overlay.syncBusyOverlay();
            ThreadRunner.get().notifyDescriptionChanged();
        });
    }

    public void complete() {
        ThreadRunner.RunnerTask task = overlay.getAllChromosomeAnnotationTask();
        if (task != null) {
            task.setProgressSuffix("");
            if (!task.isCompleted()) {
                task.complete();
            }
        }
        finishChrome();
    }

    /** Clear Analysis Manager dimmer / locks (LoadingPopup follows ThreadRunner). */
    public void finishChrome() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::finishChrome);
            return;
        }
        overlay.setAllChromosomeAnnotationRunning(false);
        overlay.setAllChromosomeAnnotationTask(null);
        overlay.lockFilterControls(false);
        overlay.hide();
        org.baseplayer.project.ProjectSessionState.get().markDirty();
    }

    public void sync() {
        if (onThreadRunnerIdle != null && ThreadRunner.get().getActiveTasks().isEmpty()) {
            onThreadRunnerIdle.run();
        }
        overlay.syncBusyOverlay();
    }

    public void setStatus(String status) {
        overlay.setStatusMessage(status);
        overlay.show();
    }

    public void showAnnotating() {
        setStatus("Annotating");
    }

    public void clearStatus() {
        overlay.clearStatusMessage();
        overlay.syncBusyOverlay();
    }

    public void cancelDelayedLoadingModal() {
        overlay.cancelDelayedLoadingModal();
    }

    public void lockFilterControls(boolean locked) {
        overlay.lockFilterControls(locked);
    }

    public void updateProgress(VcfManager.AllChromosomeProgress progress) {
        overlay.updateProgress(progress);
    }

    public boolean isAllChromosomeAnnotationRunning() {
        return overlay.isAllChromosomeAnnotationRunning();
    }

    public ThreadRunner.RunnerTask getAllChromosomeAnnotationTask() {
        return overlay.getAllChromosomeAnnotationTask();
    }

    public void setAllChromosomeAnnotationTask(ThreadRunner.RunnerTask task) {
        overlay.setAllChromosomeAnnotationTask(task);
    }

    public void setAllChromosomeAnnotationRunning(boolean running) {
        overlay.setAllChromosomeAnnotationRunning(running);
    }

    public void setStatusMessage(String message) {
        overlay.setStatusMessage(message);
    }

    public void hide() {
        overlay.hide();
    }

    /**
     * Prefer Analysis when showing and not iconified; else main stage.
     * Used by {@link org.baseplayer.services.LoadingManager}.
     */
    public Window resolveOwnerWindow() {
        Stage stage = stageSupplier.get();
        if (stage != null && stage.isShowing() && !stage.isIconified()) {
            return stage;
        }
        if (MainApp.stage != null && MainApp.stage.isShowing()) {
            return MainApp.stage;
        }
        return MainApp.stage;
    }

    public Stage getStage() {
        return stageSupplier.get();
    }
}
