package org.baseplayer.analysis.manager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.baseplayer.io.VcfManager;
import org.baseplayer.services.LoadingManager;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.ComparisonProgress;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.TableRow;
import org.baseplayer.variant.ui.components.LohVariantTable;
import org.baseplayer.variant.ui.components.VariantClassWorkspace;

import javafx.scene.control.Alert;
import javafx.util.Duration;

/**
 * Filter apply, debounce, on-demand field reload, annotate-all / reload-filtered chromosome
 * batches (Point + Signal), and on-demand LOH calculation.
 * Chromosome batches call {@link ControlAnnotationOrchestrator#afterChromosomeHook()} per list.
 */
final class AnalysisBatchActions {

    private final ManagerContext ctx;
    private final TableUiCoordinator tables;
    private final ControlAnnotationOrchestrator control;
    private final Runnable loadData;

    /** Slider changes: apply 200 ms after the last move. */
    private final DebouncedAction filterDebounce =
        new DebouncedAction(Duration.millis(200), this::applyFiltersNow);
    /** Checkbox/filter actions: short delay so the UI paints first. */
    private final DebouncedAction immediateFilterApply =
        new DebouncedAction(Duration.millis(40), this::applyFiltersNow, true);
    /** Signal Sample Comparison: debounced slider → session settings (not VCF applyFiltersNow). */
    private final DebouncedAction signalComparisonDebounce =
        new DebouncedAction(Duration.millis(200), this::applySignalComparison);
    /** Signal Sample Comparison: short-delay apply for gene/tags/window/Apply. */
    private final DebouncedAction signalComparisonImmediate =
        new DebouncedAction(Duration.millis(40), this::applySignalComparison, true);
    /** Coalesce rapid Add-field actions into one on-demand field-value reload. */
    private final DebouncedAction onDemandFieldReload =
        new DebouncedAction(Duration.millis(600), this::runOnDemandFieldReload);
    private boolean pendingOnDemandFieldReload;

    AnalysisBatchActions(
            ManagerContext ctx,
            TableUiCoordinator tables,
            ControlAnnotationOrchestrator control,
            Runnable loadData) {
        this.ctx = ctx;
        this.tables = tables;
        this.control = control;
        this.loadData = loadData;
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private boolean filterEventsBlocked() {
        return ctx.suppressFilterApplyEvents || ctx.isAllChromosomeAnnotationRunning();
    }

    /** Debounced filter update: rapid slider movements trigger one update after the last change. */
    void scheduleFilterUpdate() {
        if (!filterEventsBlocked()) {
            filterDebounce.schedule();
        }
    }

    void scheduleImmediateFilterApply() {
        if (!filterEventsBlocked()) {
            immediateFilterApply.schedule();
        }
    }

    /** Debounced Signal Sample Comparison apply (slider); writes {@code SignalComparisonSettings}. */
    void scheduleSignalComparisonUpdate() {
        if (!ctx.suppressFilterApplyEvents && !ctx.isAllChromosomeAnnotationRunning()) {
            signalComparisonDebounce.schedule();
        }
    }

    /** Immediate Signal Sample Comparison apply (gene/tags/window/Apply button). */
    void scheduleImmediateSignalComparisonApply() {
        if (!ctx.suppressFilterApplyEvents && !ctx.isAllChromosomeAnnotationRunning()) {
            signalComparisonImmediate.schedule();
        }
    }

    private void applySignalComparison() {
        ctx.modeHost.applySignalComparison();
    }

    void cancelPendingFilterTimers() {
        filterDebounce.stop();
        immediateFilterApply.stop();
        signalComparisonDebounce.stop();
        signalComparisonImmediate.stop();
        onDemandFieldReload.stop();
        pendingOnDemandFieldReload = false;
    }

    public void loadFilterState(VariantFilter filter) {
        ctx.suppressFilterApplyEvents = true;
        try {
            VariantFilter point = filter != null && filter.getPointSlice() != null
                ? filter.getPointSlice()
                : filter;
            VariantFilter sv = filter != null && filter.getSvSlice() != null
                ? filter.getSvSlice()
                : filter;
            if (ctx.modeHost.point() != null) {
                ctx.modeHost.point().loadSlice(point);
            }
            if (ctx.modeHost.sv() != null) {
                ctx.modeHost.sv().loadSlice(sv);
            }
        } finally {
            ctx.suppressFilterApplyEvents = false;
            cancelPendingFilterTimers();
            ctx.pendingReloadFilter = null;
            for (VariantClassWorkspace ws : ctx.workspaces()) {
                ws.hideReloadBanner();
            }
        }
    }

    void applyFiltersNow() {
        if (ctx.suppressFilterApplyEvents) {
            return;
        }

        VariantFilter filter = ctx.buildFilterFromUI();
        // New INFO/FORMAT/FILTER values are not on nodes until reload — coalesce into one load.
        if (needsOnDemandFieldReload(filter)) {
            if (ctx.vcfManager != null) {
                ctx.vcfManager.setCurrentFilterForNextLoad(filter);
            }
            scheduleOnDemandFieldReload();
        }

        if (ctx.isAllChromosomeAnnotationRunning()) {
            return;
        }

        // Genotype roles (het/hom/present) use the same visible-chain filter path as
        // AF/GQ/etc. LOH region calling is only via Calculate LOH — never on Apply.
        if (ctx.vcfManager != null) {
            ctx.vcfManager.setCurrentFilterForNextLoad(filter);
            ctx.vcfManager.applyFilter(filter);
        }

        boolean reloadNeeded = false;
        for (VariantClassWorkspace ws : ctx.workspaces()) {
            if (ws.anyFilterLooserThanSnapshot()) {
                reloadNeeded = true;
                break;
            }
        }
        // On-demand field reload owns the banner while pending.
        if (!pendingOnDemandFieldReload) {
            ctx.pendingReloadFilter = reloadNeeded ? filter : null;
            for (VariantClassWorkspace ws : ctx.workspaces()) {
                ws.refreshReloadBannerState(reloadNeeded, "Reload needed");
            }
        }
        tables.scheduleRebuild(filter);
    }

    private void scheduleOnDemandFieldReload() {
        pendingOnDemandFieldReload = true;
        for (VariantClassWorkspace ws : ctx.workspaces()) {
            ws.refreshReloadBannerState(true, "Loading filter fields…");
        }
        onDemandFieldReload.schedule();
    }

    private void runOnDemandFieldReload() {
        if (!pendingOnDemandFieldReload) {
            return;
        }
        if (ctx.suppressFilterApplyEvents) {
            return;
        }
        if (ctx.isAllChromosomeAnnotationRunning()) {
            // Retry after the in-flight load finishes (see completeAllChromosomeAnnotation).
            return;
        }
        VariantFilter filter = ctx.buildFilterFromUI();
        if (!needsOnDemandFieldReload(filter)) {
            pendingOnDemandFieldReload = false;
            for (VariantClassWorkspace ws : ctx.workspaces()) {
                ws.hideReloadBanner();
            }
            return;
        }
        pendingOnDemandFieldReload = false;
        ctx.pendingReloadFilter = filter;
        reloadFilteredVariants();
    }

    /**
     * True when the UI asks for INFO/FORMAT keys or the FILTER column that were not
     * persisted on the last load — those values are kept out of memory until requested.
     */
    private boolean needsOnDemandFieldReload(VariantFilter filter) {
        if (filter == null || ctx.vcfManager == null) {
            return false;
        }
        VariantFilter loaded = ctx.vcfManager.getCurrentLoadedFilter();
        if (filter.shouldPersistVcfFilter()
                && (loaded == null || !loaded.shouldPersistVcfFilter())) {
            return true;
        }
        // INFO/FORMAT keys live on point/SV slices of the merged filter — never read the
        // empty parent maps with getInfoFieldFilters()/getFormatFieldFilters().
        if (keysMissingIgnoreCase(
                filter.infoFilterKeys(),
                loaded != null ? loaded.infoFilterKeys() : Set.of())) {
            return true;
        }
        return keysMissingIgnoreCase(
            filter.formatKeysToPersist(),
            loaded != null ? loaded.formatKeysToPersist() : Set.of());
    }

    private static boolean keysMissingIgnoreCase(Set<String> needed, Set<String> have) {
        if (needed == null || needed.isEmpty()) {
            return false;
        }
        if (have == null) {
            have = Set.of();
        }
        for (String key : needed) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (have.contains(key)) {
                continue;
            }
            boolean found = false;
            for (String existing : have) {
                if (existing != null && existing.equalsIgnoreCase(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return true;
            }
        }
        return false;
    }

    // ── Annotate-all / reload ─────────────────────────────────────────────────

    void annotateAllChromosomes() {
        if (ctx.isAllChromosomeAnnotationRunning()) {
            return;
        }
        // Signal mode: same button contract, different collect implementation.
        if (ctx.modeHost.isSignalModeSelected()) {
            annotateAllSignal();
            return;
        }
        if (ctx.vcfManager == null) {
            return;
        }
        List<String> chromosomes = ctx.referenceChromosomeOrder();
        if (chromosomes.isEmpty()) {
            tables.setPlaceholder("No chromosomes available in dropdown");
            return;
        }
        runChromosomeBatch(
            "Annotating all chromosomes…",
            chromosomes,
            ctx::buildFilterFromUI,
            "No chromosomes available for annotation");
    }

    private void annotateAllSignal() {
        ctx.modeHost.syncActiveSignalPointers();
        if (ctx.modeHost.signalWorkspace() == null) {
            return;
        }
        ctx.modeHost.signalWorkspace().activateSessionSlice();
        ctx.modeHost.refreshSignalAllChromosomesButton();
        org.baseplayer.analysis.ui.SignalAnalysisWorkspace.AllChromAction action =
            ctx.modeHost.signalWorkspace().actionForToolTab(
                ctx.modeHost.signalToolTabPane() != null
                    ? ctx.modeHost.signalToolTabPane().getSelectionModel().getSelectedItem()
                    : null);
        ctx.busyGate.run(action.buttonText() + "…", onDone -> {
            // Keep the busy gate task — do not replace it with the nested annotate/
            // discover task, or LoadingPopup can drop when the nested task is null
            // (CompactTableGrid fast path) or finishes mid-chain.
            ThreadRunner.RunnerTask task = ctx.modeHost.signalWorkspace().runAllChromosomes(action, onDone);
            if (task == null
                && action
                    == org.baseplayer.analysis.ui.SignalAnalysisWorkspace.AllChromAction.ANNOTATE) {
                // Annotate always returns a task when work starts; null = nothing to run.
                onDone.run();
            } else if (task == null
                && ctx.busyGate != null
                && !ctx.busyGate.isAllChromosomeAnnotationRunning()) {
                onDone.run();
            } else if (ctx.busyGate != null) {
                ctx.busyGate.setStatusMessage(action.buttonText() + "…");
                ctx.busyGate.sync();
                ThreadRunner.get().notifyDescriptionChanged();
            }
        });
    }

    void reloadFilteredVariants() {
        if (ctx.vcfManager == null || ctx.isAllChromosomeAnnotationRunning()) {
            return;
        }
        VariantFilter target =
            ctx.pendingReloadFilter != null ? ctx.pendingReloadFilter : ctx.buildFilterFromUI();
        ctx.pendingReloadFilter = null;
        ctx.hideReloadBanners();

        List<String> chromosomes =
            ctx.vcfManager.getCachedChromosomesInOrder(ctx.referenceChromosomeOrder());
        if (chromosomes.isEmpty() && ctx.chromosome != null && !ctx.chromosome.isBlank()) {
            chromosomes = List.of(ctx.chromosome);
        }
        if (chromosomes.isEmpty()) {
            return;
        }

        ctx.sourceVariants = null;
        tables.clearTableItemsForChromosomeSwitch();
        tables.setPlaceholder("Reloading variants…");
        ctx.vcfManager.setCurrentFilterForNextLoad(target);
        ctx.vcfManager.markAllCachedVariantListsDirty();

        final VariantFilter filterSnapshot = target.copy();
        runChromosomeBatch(
            "Reloading variants…",
            List.copyOf(chromosomes),
            () -> filterSnapshot,
            "No cached chromosomes to reload");
    }

    /**
     * Shared annotate-all / reload body: busy chrome, per-chromosome control AF hook,
     * then {@link #finishAllChromosomeAnnotationResult}.
     */
    private void runChromosomeBatch(
            String status,
            List<String> chromosomes,
            Supplier<VariantFilter> filter,
            String emptyMessage) {
        ctx.busyGate.run(status, onDone -> {
            ThreadRunner.RunnerTask task = ctx.vcfManager.annotateAllReferenceChromosomes(
                filter.get(),
                chromosomes,
                progress -> ctx.busyGate.updateProgress(progress),
                result -> {
                    finishAllChromosomeAnnotationResult(result);
                    onDone.run();
                },
                control.afterChromosomeHook());
            if (task == null) {
                tables.setPlaceholder(emptyMessage);
                onDone.run();
                return;
            }
            ThreadRunner.RunnerTask gate = ctx.busyGate.getAllChromosomeAnnotationTask();
            if (gate != null) {
                gate.setProgressSuffix("0/" + chromosomes.size() + " chromosomes, rows: 0");
                ThreadRunner.get().notifyDescriptionChanged();
            }
        });
    }

    /** Point/SV annotate-all / reload completion (busy chrome released by {@code onDone}). */
    private void finishAllChromosomeAnnotationResult(
        VcfManager.AllChromosomeAnnotationResult result) {
        // Annotate-all already materializes every chromosome with the current UI filter,
        // so a leftover "Reload needed" banner from soft filter apply is stale — unless
        // more INFO/FORMAT/FILTER keys were added while the load was running.
        if (result != null && !result.cancelled()) {
            ctx.pendingReloadFilter = null;
            VariantFilter filter = ctx.buildFilterFromUI();
            if (ctx.vcfManager != null) {
                ctx.vcfManager.setCurrentFilterForNextLoad(filter);
                ctx.vcfManager.applyFilter(filter);
            }
            if (pendingOnDemandFieldReload || needsOnDemandFieldReload(filter)) {
                scheduleOnDemandFieldReload();
            } else {
                for (VariantClassWorkspace ws : ctx.workspaces()) {
                    ws.hideReloadBanner();
                }
            }
        }

        ctx.sourceVariants = null;
        ctx.lastSeenVariantsRevision = -1;
        if (result != null && !result.cancelled() && result.completedChromosomes() > 0) {
            org.baseplayer.project.ProjectSessionState.get().markDirty();
            // Variant cache is gene-annotated — auto-run group comparison when groups exist.
            org.baseplayer.analysis.mutation.MutationGroupComparisonRunner.runAutoAfterAnnotation();
        }
        loadData.run();
    }

    // ── LOH ───────────────────────────────────────────────────────────────────

    private record LohCalculateResult(
        VariantFilter filter,
        List<TableRow> lohAll,
        Map<VcfVariantType, List<TableRow>> lohByType) {}

    /**
     * On-demand LOH region calling. Roles alone never build regions — only this action does.
     */
    void calculateLohRegions() {
        final VariantFilter filterSnapshot;
        try {
            filterSnapshot = ctx.buildFilterFromUI();
        } catch (RuntimeException ex) {
            return;
        }
        if (filterSnapshot == null || !filterSnapshot.isLohMode()) {
            showLohInfo("Set Parental/Marker = heterozygous and Child = homozygous, then try again.");
            return;
        }
        final List<VcfManager.CachedChromosomeVariants> snapshots =
            ctx.sourceVariantLists != null ? new ArrayList<>(ctx.sourceVariantLists) : List.of();
        if (snapshots.isEmpty()) {
            showLohInfo("No cached chromosome variants to calculate LOH on.");
            return;
        }

        tables.trackTask(ThreadRunner.get().submit(
            "Calculating LOH regions…",
            () -> {
                List<TableRow> lohAll = new ArrayList<>();
                Map<VcfVariantType, List<TableRow>> lohByType = new EnumMap<>(VcfVariantType.class);
                for (VcfVariantType type : LohVariantTable.TYPE_ORDER) {
                    lohByType.put(type, new ArrayList<>());
                }
                int total = Math.max(1, snapshots.size());
                ComparisonProgress progress = new ComparisonProgress(
                    total,
                    filterSnapshot.comparisonCohortSummary(),
                    tables::updateTableRebuildProgress,
                    (cur, tot) -> LoadingManager.get().setProgress(cur, tot));
                int index = 0;
                for (VcfManager.CachedChromosomeVariants cached : snapshots) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    index++;
                    String chrom = cached.chromosome();
                    VariantList variants = cached.variants();
                    if (variants == null || variants.isEmpty()) {
                        progress.beginChrom(index, chrom, 0);
                        progress.finishChrom();
                        continue;
                    }
                    progress.beginChrom(index, chrom, variants.size());
                    variants.calculateLohRegions(filterSnapshot, progress);
                    TableRebuildService.appendLohTableRows(
                        chrom, variants, filterSnapshot, lohAll, lohByType);
                    progress.finishChrom();
                }
                progress.done();
                if (ctx.vcfManager != null) {
                    ctx.vcfManager.unionSessionAvailableFilters(
                        VariantTypeVisuals.lohRegionTypes(), null);
                }
                return new LohCalculateResult(filterSnapshot, lohAll, lohByType);
            },
            result -> {
                tables.trackTask(null);
                if (result != null) {
                    tables.applyLohCalculateResult(result.filter(), result.lohAll(), result.lohByType());
                }
            }));
    }

    private void showLohInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Calculate LOH");
        alert.setHeaderText(null);
        alert.setContentText(message);
        if (ctx.stage != null) {
            alert.initOwner(ctx.stage);
        }
        alert.showAndWait();
    }
}
