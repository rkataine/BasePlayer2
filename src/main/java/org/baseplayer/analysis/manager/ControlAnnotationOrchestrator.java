package org.baseplayer.analysis.manager;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.ui.components.ControlFilesPanel;

import javafx.scene.Node;

/**
 * Control-file AF / ClinVar annotation for Point and SV Control Files panels.
 * Policy (who runs what) lives in {@link ControlAnnotationPolicy}; this class owns the
 * async run, panel wiring, session restore, and the post-load hook.
 */
final class ControlAnnotationOrchestrator {

    private final ManagerContext ctx;
    private final Supplier<Node> windowAnchor;
    private final Runnable applyFilters;
    private final Runnable scheduleImmediateApply;
    private final Consumer<VariantFilter> rebuildTables;

    ControlAnnotationOrchestrator(
            ManagerContext ctx,
            Supplier<Node> windowAnchor,
            Runnable applyFilters,
            Runnable scheduleImmediateApply,
            Consumer<VariantFilter> rebuildTables) {
        this.ctx = ctx;
        this.windowAnchor = windowAnchor;
        this.applyFilters = applyFilters;
        this.scheduleImmediateApply = scheduleImmediateApply;
        this.rebuildTables = rebuildTables;
    }

    // ── Panel wiring ──────────────────────────────────────────────────────────

    void wireControlFilesPanel(ControlFilesPanel panel) {
        if (panel == null) {
            return;
        }
        panel.setWindowSupplier(() -> {
            Node anchor = windowAnchor.get();
            return anchor != null && anchor.getScene() != null ? anchor.getScene().getWindow() : null;
        });
        panel.setSourcesSupplier(() -> ctx.sourceVariantLists != null ? ctx.sourceVariantLists : List.of());
        panel.setOnFilterSettingsChanged(scheduleImmediateApply);
        panel.setAnnotateExecutor(this::runControlAnnotationAsync);
        panel.setAfterAnnotate(changed -> {
            if (Boolean.TRUE.equals(changed)) {
                applyFilters.run();
            }
        });
    }

    /** Wire + restore the Point panel while the shell is still assembling. */
    void attachPointPanel(ControlFilesPanel panel) {
        wireControlFilesPanel(panel);
        restoreControlFilesFromSession(panel);
    }

    /** SV panel is wired by ModeHost; link ClinVar mirroring and restore both from the session. */
    void controlPanelsReady() {
        wireControlFilesPanelMirroring();
        restoreControlFilesFromSession(ctx.pointControl());
        restoreControlFilesFromSession(ctx.svControl());
    }

    /** Point and SV Control Files tabs share ClinVar settings only (not control VCFs). */
    private void wireControlFilesPanelMirroring() {
        ControlFilesPanel point = ctx.modeHost.point() != null ? ctx.modeHost.point().control() : null;
        ControlFilesPanel sv = ctx.modeHost.sv() != null ? ctx.modeHost.sv().control() : null;
        if (point != null) {
            point.setOnSettingsMirrored(source -> {
                if (sv != null && source != sv) {
                    sv.mirrorClinVarSettingsFrom(source);
                }
            });
        }
        if (sv != null) {
            sv.setOnSettingsMirrored(source -> {
                if (point != null && source != point) {
                    point.mirrorClinVarSettingsFrom(source);
                }
            });
        }
    }

    private void restoreControlFilesFromSession(ControlFilesPanel panel) {
        if (panel == null) {
            return;
        }
        org.baseplayer.project.ProjectDocument doc =
            org.baseplayer.project.ProjectSessionState.get().getDocument();
        if (doc == null || doc.controlFiles == null) {
            return;
        }
        boolean previous = ctx.suppressFilterApplyEvents;
        ctx.suppressFilterApplyEvents = true;
        try {
            panel.restoreFromDocument(doc.controlFiles);
        } finally {
            ctx.suppressFilterApplyEvents = previous;
        }
    }

    /** Called from session save to persist control VCF path + pop-freq toggles. */
    void writeControlFilesToDocument(org.baseplayer.project.ProjectDocument.ControlFilesSpec spec) {
        if (spec == null) {
            return;
        }
        // Point writes point control + ClinVar; SV writes SV control fields (and ClinVar again).
        if (ctx.pointControl() != null) {
            ctx.pointControl().writeToDocument(spec);
        }
        if (ctx.svControl() != null) {
            ctx.svControl().writeToDocument(spec);
        }
    }

    // ── Annotation runs ───────────────────────────────────────────────────────

    boolean hasOpenControlOrClinVar() {
        return ControlAnnotationPolicy.hasOpenControlOrClinVar(ctx.pointControl())
            || ControlAnnotationPolicy.hasOpenControlOrClinVar(ctx.svControl());
    }

    /** Per-chromosome hook for annotate-all / reload-all; {@code null} when no control is open. */
    Consumer<VariantList> afterChromosomeHook() {
        return ControlAnnotationPolicy.afterChromosomeHook(ctx.pointControl(), ctx.svControl());
    }

    /**
     * Run control-file AF annotation under the shared busy gate so the app-wide
     * {@code LoadingPopup} shows progress; tables refresh when it finishes.
     *
     * @param work receives a progress-suffix updater for the active RunnerTask
     */
    void runControlAnnotationAsync(Consumer<Consumer<String>> work) {
        if (work == null) {
            return;
        }
        ctx.busyGate.run("Annotating control data…", onDone -> {
            final AtomicReference<ThreadRunner.RunnerTask> taskRef = new AtomicReference<>();
            taskRef.set(ThreadRunner.get().submit(
                "Annotating control data…",
                () -> {
                    work.accept(suffix -> {
                        ThreadRunner.RunnerTask t = taskRef.get();
                        if (t == null || t.isCancelled()) {
                            return;
                        }
                        t.setProgressSuffix(suffix != null ? suffix : "");
                        ThreadRunner.get().notifyDescriptionChanged();
                    });
                    return Boolean.TRUE;
                },
                result -> {
                    onDone.run();
                    if (ctx.disposed) {
                        return;
                    }
                    // null result = supplier threw; still refresh so the manager is not left stale.
                    VariantFilter filter = ctx.buildFilterFromUI();
                    if (ctx.vcfManager != null) {
                        ctx.vcfManager.applyFilter(filter);
                    }
                    rebuildTables.accept(filter);
                }));
        });
    }

    /**
     * After chrom load / switch: when a control or ClinVar VCF is open and this chrom
     * lacks annotations, annotate (modal). Otherwise rebuild tables only.
     */
    void annotateControlAfThenRebuild() {
        if (needsAnyControlAnnotation()) {
            runControlAnnotationAsync(progress -> ControlAnnotationPolicy.annotateAllOpen(
                ctx.pointControl(), ctx.svControl(), progress));
            return;
        }
        // Controlling off, or the cache already has annotations: just show tables.
        rebuildTables.accept(
            ctx.vcfManager != null ? ctx.vcfManager.getCurrentFilter() : ctx.buildFilterFromUI());
    }

    /**
     * Worker-thread control annotation for a freshly loaded region list, before the table build.
     */
    void annotatePostLoad(VariantList list, ThreadRunner.RunnerTask loadTask) {
        ControlFilesPanel point = ctx.pointControl();
        ControlFilesPanel sv = ctx.svControl();
        boolean needsPoint = needsControlOrClinVarForList(point, list);
        boolean needsSv = needsControlOrClinVarForList(sv, list);
        if (!needsPoint && !needsSv) {
            return;
        }
        ctx.postLoadPhase(loadTask, "Annotating control data…");
        Consumer<String> progress = suffix -> {
            if (loadTask != null) {
                loadTask.setDetailMessage(suffix != null ? suffix : "");
                ThreadRunner.get().notifyDescriptionChanged();
            }
        };
        try {
            ControlAnnotationPolicy.annotatePostLoadList(
                point, sv, list, progress, p -> needsPoint, s -> needsSv);
        } catch (Throwable t) {
            System.err.println("Control annotation during post-load failed: " + t.getMessage());
        }
    }

    // ── Needs checks ──────────────────────────────────────────────────────────

    private boolean needsAnyControlAnnotation() {
        return hasOpenControlOrClinVar()
            && (needsControlOrClinVar(ctx.pointControl()) || needsControlOrClinVar(ctx.svControl()));
    }

    private boolean needsControlOrClinVar(ControlFilesPanel panel) {
        if (panel == null) {
            return false;
        }
        if (panel.isControlOpen()
                && (panel.needsControlAnnotation(ctx.sourceVariants)
                    || ControlAnnotationPolicy.anySourceNeeds(
                        panel, ctx.sourceVariantLists, panel::needsControlAnnotation))) {
            return true;
        }
        return panel.clinVarAnnotator().isOpen()
            && (panel.clinVarAnnotator().needsAnnotation(ctx.sourceVariants)
                || ControlAnnotationPolicy.anySourceNeeds(
                    panel, ctx.sourceVariantLists, panel.clinVarAnnotator()::needsAnnotation));
    }

    private static boolean needsControlOrClinVarForList(
            ControlFilesPanel panel, VariantList list) {
        if (panel == null || list == null) {
            return false;
        }
        if (panel.isControlOpen() && panel.needsControlAnnotation(list)) {
            return true;
        }
        return panel.clinVarAnnotator().isOpen()
            && panel.clinVarAnnotator().needsAnnotation(list);
    }
}
