package org.baseplayer.analysis.manager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BedVariantAnnotation;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.LoadingManager;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.GeneGroup;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.TableRow;
import org.baseplayer.variant.ui.components.LohVariantTable;
import org.baseplayer.variant.ui.components.SvVariantTable;
import org.baseplayer.variant.ui.components.VariantTable;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;

/**
 * Table UI pipeline for Analysis Manager: schedules worker rebuilds ({@link TableRebuildService}),
 * applies results on the FX thread (Point / SV / LOH / annotation-feature tabs), and owns
 * the tab-sync / fan-out helpers for BED annotation feature tabs.
 */
final class TableUiCoordinator {

    private static final String TEXT = "white";

    record Nodes(
            TabPane resultsTabPane,
            Tab intergenicTab,
            Tab lohRegionsTab,
            TextField tableSearchField) {}

    private final ManagerContext ctx;
    private final VariantTableChrome chrome;
    private final ExportCoordinator export;
    private final Nodes n;

    private volatile boolean rebuildRunning = false;
    private volatile boolean rebuildNeeded = false;
    private volatile ThreadRunner.RunnerTask tableRebuildTask;

    private final AnnotationFeatureTabs features;

    TableUiCoordinator(
            ManagerContext ctx,
            VariantTableChrome chrome,
            AnnotationFeatureTabs features,
            ExportCoordinator export,
            Nodes nodes) {
        this.ctx = ctx;
        this.chrome = chrome;
        this.features = features;
        this.export = export;
        this.n = nodes;
    }

    void install() {
        features.install();
    }

    void dispose() {
        features.dispose();
    }

    /** Clear stuck rebuild flag when ThreadRunner is idle after Cancel. */
    void clearStuck() {
        if (rebuildRunning) {
            ThreadRunner.RunnerTask t = tableRebuildTask;
            if (t == null || t.isCompleted() || t.isCancelled()) {
                tableRebuildTask = null;
                rebuildRunning = false;
            }
        }
    }

    /** A rebuild was requested while the chromosome is still loading; run it on completion. */
    void markRebuildNeeded() {
        rebuildNeeded = true;
    }

    /** Task used for progress suffix while LOH is calculated (see batch actions). */
    void trackTask(ThreadRunner.RunnerTask task) {
        tableRebuildTask = task;
    }

    // ── Rebuild scheduling ────────────────────────────────────────────────────

    void scheduleRebuild(VariantFilter filter) {
        rebuildNeeded = true;
        if (ctx.isCurrentChromosomeStillLoading()) {
            return;
        }
        if (!rebuildRunning) rebuildTables(filter);
    }

    private void rebuildTables(VariantFilter filter) {
        final VariantFilter filterSnapshot;
        try {
            filterSnapshot = filter == null ? new VariantFilter() : filter.copy();
        } catch (RuntimeException ex) {
            rebuildNeeded = false;
            if (ctx.busyGate != null) {
                ctx.busyGate.cancelDelayedLoadingModal();
            }
            return;
        }
        if (ctx.sourceVariantLists == null || ctx.sourceVariantLists.isEmpty()) {
            rebuildNeeded = false;
            if (ctx.busyGate != null) {
                ctx.busyGate.cancelDelayedLoadingModal();
            }
            return;
        }
        final List<VcfManager.CachedChromosomeVariants> snapshots = new ArrayList<>(ctx.sourceVariantLists);
        rebuildRunning = true;
        rebuildNeeded = false;

        // Track via ThreadRunner so Variant Manager overlay + LoadingPopup show
        // spinner / "Updating tables…" while gene groups are built.
        tableRebuildTask = ThreadRunner.get().submit(
            "Updating tables…",
            () -> TableRebuildService.build(
                snapshots, filterSnapshot, false, null, null),
            this::finishTableRebuild);
    }

    private void finishTableRebuild(TableRebuildService.TableRebuildResult result) {
        if (result == null) {
            tableRebuildTask = null;
            rebuildRunning = false;
            if (ctx.busyGate != null) {
                ctx.busyGate.cancelDelayedLoadingModal();
            }
            if (rebuildNeeded && ctx.vcfManager != null) {
                scheduleRebuild(ctx.vcfManager.getCurrentFilter());
            }
            return;
        }

        // Same path as AF/GQ: build collapsed gene groups on FX (sample lists deferred).
        // Heavy/modal path kept for callers that set redrawCanvases (legacy comparison).
        if (result.redrawCanvases()) {
            finishTableRebuildHeavy(result);
            return;
        }

        tableRebuildTask = null;
        rebuildRunning = false;
        try {
            applyTableRebuildResultLight(result, null);
            // Slider/filter light path used to skip canvas redraw; early applyFilter redraw
            // can race the background chain rebuild and leave tracks stale until hover.
            if (ctx.vcfManager != null) {
                ctx.vcfManager.redrawSampleCanvases();
            }
        } catch (Throwable t) {
            System.err.println("Variant table apply failed: " + t.getMessage());
            t.printStackTrace();
        } finally {
            if (ctx.busyGate != null) {
                ctx.busyGate.cancelDelayedLoadingModal();
            }
            if (rebuildNeeded && ctx.vcfManager != null) {
                scheduleRebuild(ctx.vcfManager.getCurrentFilter());
            }
        }
    }

    /**
     * Second loading-modal phase after comparison: prebuild {@link GeneGroup}s off FX,
     * bind them with a light FX apply, then defer canvas redraw so the popup never freezes.
     */
    private void finishTableRebuildHeavy(TableRebuildService.TableRebuildResult result) {
        // Prebuild unfiltered groups; selected-tab search is re-applied after bind.
        tableRebuildTask = ThreadRunner.get().submit(
            "Updating tables…",
            () -> buildPrebuiltTableUi(result, "", "", ""),
            prebuilt -> {
                try {
                    if (prebuilt != null) {
                        applyTableRebuildResultLight(result, prebuilt);
                    } else {
                        applyTableRebuildResultLight(result, null);
                    }
                    chrome.reapplySearchAfterRebuild();
                } catch (Throwable t) {
                    System.err.println("Prebuilt table apply failed: " + t.getMessage());
                    t.printStackTrace();
                    try {
                        applyTableRebuildResultLight(result, null);
                    } catch (Throwable fallback) {
                        System.err.println("Fallback table apply failed: " + fallback.getMessage());
                    }
                } finally {
                    tableRebuildTask = null;
                    rebuildRunning = false;
                    deferPostHeavyUi(result.redrawCanvases());
                }
            });
    }

    private void deferPostHeavyUi(boolean redrawCanvases) {
        Platform.runLater(() -> {
            try {
                if (redrawCanvases && ctx.vcfManager != null) {
                    ctx.vcfManager.redrawSampleCanvases();
                }
            } catch (Throwable t) {
                System.err.println("Canvas redraw after comparison failed: " + t.getMessage());
                t.printStackTrace();
            }
            if (ctx.busyGate != null) {
                ctx.busyGate.cancelDelayedLoadingModal();
            }
            if (rebuildNeeded && ctx.vcfManager != null) {
                scheduleRebuild(ctx.vcfManager.getCurrentFilter());
            }
        });
    }

    /** Worker side of a region load: build table rows for a freshly loaded list. */
    TableRebuildService.TableRebuildResult buildPostLoadResult(
            VariantList list, String chrom, VariantFilter filter) {
        VariantFilter filterSnapshot;
        try {
            filterSnapshot = filter == null ? new VariantFilter() : filter.copy();
        } catch (RuntimeException ex) {
            return null;
        }
        List<VcfManager.CachedChromosomeVariants> snapshots =
            ctx.vcfManager != null
                ? ctx.vcfManager.getCachedVariantListsInOrder(ctx.referenceChromosomeOrder())
                : List.of();
        if (snapshots.isEmpty() && chrom != null && !chrom.isBlank()) {
            snapshots = List.of(new VcfManager.CachedChromosomeVariants(chrom, list));
        }
        return TableRebuildService.build(snapshots, filterSnapshot, false, null, null);
    }

    /** FX side of a region load; stamps idle so {@code loadData} does not rebuild again. */
    void finishPostLoad(TableRebuildService.TableRebuildResult result) {
        rebuildRunning = false;
        rebuildNeeded = false;
        finishTableRebuild(result);
    }

    // ── Prebuilt groups (worker) ──────────────────────────────────────────────

    private record TypedGroups(List<GeneGroup> all, Map<VcfVariantType, List<GeneGroup>> byType) {}

    private record PrebuiltTableUi(
        List<GeneGroup> codingGroups,
        List<GeneGroup> intronicGroups,
        List<GeneGroup> intergenicGroups,
        TypedGroups sv,
        TypedGroups loh,
        List<AnnotationFeatureTabs.AnnotationFeaturePrebuilt> annotationFeatures) {}

    private PrebuiltTableUi buildPrebuiltTableUi(
            TableRebuildService.TableRebuildResult result,
            String pointSearch,
            String svSearch,
            String lohSearch) {
        VariantFilter filter = result.filter();
        boolean pointExpand = AbstractNestedVariantTable.isActiveSearchQuery(pointSearch);

        List<BedTrack> annotationTracks = BedVariantAnnotation.annotationTracks();
        // Point + feature tabs + SV All + SV types + LOH All + LOH types
        int totalSteps = 3 + annotationTracks.size() + 1 + SvVariantTable.TYPE_ORDER.length
            + 1 + LohVariantTable.TYPE_ORDER.length;
        int[] step = {0};
        Consumer<String> progress = label -> reportUiBuildProgress(++step[0], totalSteps, label);

        try {
            progress.accept("Gene groups");
            List<TableRow> codingRows = AbstractNestedVariantTable.rowsMatchingSearch(
                result.coding(), pointSearch, filter);
            List<GeneGroup> codingGroups = AbstractNestedVariantTable.buildGeneGroups(
                codingRows, false, filter, pointExpand);

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            progress.accept("Intronic groups");
            List<TableRow> intronicRows = AbstractNestedVariantTable.rowsMatchingSearch(
                result.intronic(), pointSearch, filter);
            List<GeneGroup> intronicGroups = AbstractNestedVariantTable.buildGeneGroups(
                intronicRows, false, filter, pointExpand);

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            progress.accept("Intergenic groups");
            List<TableRow> intergenicRows = AbstractNestedVariantTable.rowsMatchingSearch(
                result.intergenic(), pointSearch, filter);
            List<GeneGroup> intergenicGroups = AbstractNestedVariantTable.buildGeneGroups(
                intergenicRows, true, filter, pointExpand);

            List<AnnotationFeatureTabs.AnnotationFeaturePrebuilt> annotationFeatures =
                AnnotationFeatureTabs.prebuild(
                annotationTracks, codingRows, intronicRows, intergenicRows,
                filter, pointExpand, progress);
            if (annotationFeatures == null) {
                return null;
            }

            TypedGroups sv = prebuildTypedGroups(
                "SV", SvVariantTable.TYPE_ORDER, result.svAll(), result.svByType(),
                svSearch, filter, progress);
            if (sv == null) {
                return null;
            }
            TypedGroups loh = prebuildTypedGroups(
                "LOH", LohVariantTable.TYPE_ORDER, result.lohAll(), result.lohByType(),
                lohSearch, filter, progress);
            if (loh == null) {
                return null;
            }

            LoadingManager.get().setProgress(totalSteps, totalSteps);
            updateTableRebuildProgress("");
            return new PrebuiltTableUi(
                codingGroups, intronicGroups, intergenicGroups, sv, loh, annotationFeatures);
        } catch (Throwable t) {
            System.err.println("Prebuilding table gene groups failed: " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }

    /** All-tab + per-type gene groups for SV or LOH; {@code null} when interrupted. */
    private static TypedGroups prebuildTypedGroups(
            String label,
            VcfVariantType[] order,
            List<TableRow> allRows,
            Map<VcfVariantType, List<TableRow>> byType,
            String search,
            VariantFilter filter,
            Consumer<String> progress) {
        boolean expand = AbstractNestedVariantTable.isActiveSearchQuery(search);
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        if (progress != null) {
            progress.accept(label + " groups");
        }
        List<GeneGroup> allGroups = AbstractNestedVariantTable.buildGeneGroups(
            AbstractNestedVariantTable.rowsMatchingSearch(allRows, search, filter),
            false, filter, expand);
        Map<VcfVariantType, List<GeneGroup>> byTypeGroups = new EnumMap<>(VcfVariantType.class);
        for (VcfVariantType type : order) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            if (progress != null) {
                progress.accept(label + " " + VariantTypeVisuals.shortLabel(type));
            }
            List<TableRow> bucket = byType.get(type);
            List<TableRow> filtered = AbstractNestedVariantTable.rowsMatchingSearch(
                bucket != null ? bucket : List.of(), search, filter);
            byTypeGroups.put(type, AbstractNestedVariantTable.buildGeneGroups(
                filtered, false, filter, expand));
        }
        return new TypedGroups(allGroups, byTypeGroups);
    }

    private void reportUiBuildProgress(int step, int totalSteps, String label) {
        updateTableRebuildProgress("(" + step + "/" + totalSteps + ") " + label);
        LoadingManager.get().setProgress(step, totalSteps);
    }

    void updateTableRebuildProgress(String stage) {
        ThreadRunner.RunnerTask task = tableRebuildTask;
        if (task == null) {
            List<ThreadRunner.RunnerTask> tasks = ThreadRunner.get().getActiveTasks();
            if (!tasks.isEmpty()) {
                task = tasks.get(tasks.size() - 1);
            }
        }
        if (task != null) {
            task.setProgressSuffix(stage);
            ThreadRunner.get().notifyDescriptionChanged();
        }
    }

    // ── FX apply ──────────────────────────────────────────────────────────────

    /**
     * FX-thread bind of row lists + optional prebuilt gene groups.
     * When {@code prebuilt} is null, falls back to {@code setItems} (builds groups on FX).
     */
    private void applyTableRebuildResultLight(
            TableRebuildService.TableRebuildResult result, PrebuiltTableUi prebuilt) {
        VariantFilter filterSnapshot = result.filter();
        VariantTable pointTable = chrome.variantTable();
        SvVariantTable svTable = chrome.svVariantTable;
        LohVariantTable lohTable = chrome.lohVariantTable;

        pointTable.setDisplayContext(filterSnapshot);
        // Bind unfiltered groups; selected-tab search runs after this method returns.
        pointTable.rememberSearchQuery("");
        if (lohTable != null) {
            lohTable.rememberSearchQuery("");
        }
        if (svTable != null) {
            svTable.rememberSearchQuery("");
        }
        ObservableList<TableRow> codingObs = FXCollections.observableArrayList(result.coding());
        ObservableList<TableRow> intronicObs = FXCollections.observableArrayList(result.intronic());
        ObservableList<TableRow> intergenicObs =
            FXCollections.observableArrayList(result.intergenic());
        if (prebuilt != null) {
            pointTable.setItemsWithPrebuiltGroups(
                codingObs, intronicObs, intergenicObs,
                prebuilt.codingGroups(), prebuilt.intronicGroups(), prebuilt.intergenicGroups());
        } else {
            setTableItems(codingObs, intronicObs, intergenicObs);
        }

        if (svTable != null) {
            svTable.setDisplayContext(filterSnapshot);
            bindTypedTable(
                SvVariantTable.TYPE_ORDER, result.svAll(), result.svByType(),
                prebuilt != null ? prebuilt.sv() : null,
                svTable::setItems, svTable::setItemsWithPrebuiltGroups);
        }
        if (lohTable != null) {
            lohTable.setDisplayContext(filterSnapshot);
            bindTypedTable(
                LohVariantTable.TYPE_ORDER, result.lohAll(), result.lohByType(),
                prebuilt != null ? prebuilt.loh() : null,
                lohTable::setItems, lohTable::setItemsWithPrebuiltGroups);
        }

        boolean pointEmpty = result.coding().isEmpty()
            && result.intronic().isEmpty()
            && result.intergenic().isEmpty();
        boolean svEmpty = result.svAll().isEmpty();
        boolean lohEmpty = result.lohAll().isEmpty();
        export.refreshButtonVisibility(pointEmpty, svEmpty);
        // Point-only empty must NOT call setPlaceholder() — that resets SV tab titles to (0).
        if (pointEmpty) {
            Label emptyPoint = new Label("No point mutations match current filter settings");
            emptyPoint.setStyle("-fx-text-fill: " + TEXT + ";");
            setTablePlaceholders(emptyPoint, new Label(""), new Label(""));
            pointTable.setBaseTabTitles();
        } else {
            setTablePlaceholders(null, null, null);
        }
        if (svTable != null) {
            svTable.setPlaceholders(
                svEmpty ? "No structural variants match current filter settings" : "", "");
        }
        if (lohTable != null) {
            lohTable.setPlaceholders(
                lohEmpty
                    ? "Press Calculate LOH regions after setting Parental/Marker = het and Child = hom"
                    : "",
                "");
        }
        updateLohTabVisibility(filterSnapshot, !lohEmpty);

        if (prebuilt != null) {
            features.applyAnnotationFeatureGroups(prebuilt.annotationFeatures());
        } else {
            features.applyAnnotationFeatureGroups(features.buildOnFx(
                result.coding(), result.intronic(), result.intergenic(), filterSnapshot));
        }

        scrollToPendingChromosome();
    }

    /** After a manual chromosome pick: scroll the Point table to that chromosome once rows exist. */
    void scrollToPendingChromosome() {
        if (ctx.pendingScrollToChromosome == null || ctx.pendingScrollToChromosome.isBlank()) {
            return;
        }
        final String scrollTarget = ctx.pendingScrollToChromosome;
        Platform.runLater(() -> {
            boolean scrolled = chrome.variantTable().scrollToFirstVariantForChromosome(scrollTarget);
            if (scrolled && Objects.equals(ctx.pendingScrollToChromosome, scrollTarget)) {
                ctx.pendingScrollToChromosome = null;
            }
        });
    }

    /** Shared SV / LOH bind: rows to observable lists, then prebuilt groups when available. */
    private static void bindTypedTable(
            VcfVariantType[] order,
            List<TableRow> all,
            Map<VcfVariantType, List<TableRow>> byType,
            TypedGroups groups,
            BiConsumer<ObservableList<TableRow>, Map<VcfVariantType, ObservableList<TableRow>>> setItems,
            PrebuiltBinder prebuiltBinder) {
        Map<VcfVariantType, ObservableList<TableRow>> byTypeObs = new EnumMap<>(VcfVariantType.class);
        for (VcfVariantType type : order) {
            List<TableRow> bucket = byType.get(type);
            byTypeObs.put(type, FXCollections.observableArrayList(
                bucket != null ? bucket : List.of()));
        }
        ObservableList<TableRow> allObs = FXCollections.observableArrayList(all);
        if (groups != null) {
            prebuiltBinder.bind(allObs, byTypeObs, groups.all(), groups.byType());
        } else {
            setItems.accept(allObs, byTypeObs);
        }
    }

    @FunctionalInterface
    private interface PrebuiltBinder {
        void bind(
            ObservableList<TableRow> all,
            Map<VcfVariantType, ObservableList<TableRow>> byType,
            List<GeneGroup> allGroups,
            Map<VcfVariantType, List<GeneGroup>> byTypeGroups);
    }

    /** Bind freshly calculated LOH regions (groups built here, on FX). */
    void applyLohCalculateResult(
            VariantFilter filter,
            List<TableRow> lohAll,
            Map<VcfVariantType, List<TableRow>> lohByType) {
        LohVariantTable lohTable = chrome.lohVariantTable;
        if (lohTable == null) {
            return;
        }
        TypedGroups groups = prebuildTypedGroups(
            "LOH", LohVariantTable.TYPE_ORDER, lohAll, lohByType, "", filter, null);
        bindTypedTable(
            LohVariantTable.TYPE_ORDER, lohAll, lohByType, groups,
            lohTable::setItems, lohTable::setItemsWithPrebuiltGroups);
        lohTable.setDisplayContext(filter);
        boolean empty = lohAll.isEmpty();
        lohTable.setPlaceholders(empty ? "No LOH regions found for current roles" : "", "");
        updateLohTabVisibility(filter, !empty);
        if (n.lohRegionsTab() != null && n.resultsTabPane() != null
                && n.resultsTabPane().getTabs().contains(n.lohRegionsTab())) {
            n.resultsTabPane().getSelectionModel().select(n.lohRegionsTab());
        }
        if (ctx.vcfManager != null) {
            Platform.runLater(() -> {
                try {
                    ctx.vcfManager.redrawSampleCanvases();
                } catch (Throwable t) {
                    System.err.println("Canvas redraw after LOH calculate failed: " + t.getMessage());
                }
            });
        }
    }

    // ── Placeholders / items ──────────────────────────────────────────────────

    void setPlaceholder(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: " + TEXT + ";");
        setTablePlaceholders(lbl, new Label(""), new Label(""));
        if (chrome.variantTable != null) {
            chrome.variantTable.setBaseTabTitles();
        }
        if (chrome.svVariantTable != null) {
            chrome.svVariantTable.setBaseTabTitles();
            chrome.svVariantTable.setPlaceholders(text, "");
        }
        export.refreshButtonVisibility(true, true);
    }

    void clearTableItemsForChromosomeSwitch() {
        setTableItems(
            FXCollections.<VariantTable.TableRow>observableArrayList(),
            FXCollections.<VariantTable.TableRow>observableArrayList(),
            FXCollections.<VariantTable.TableRow>observableArrayList());
    }

    private void setTableItems(
        ObservableList<VariantTable.TableRow> codingItems,
        ObservableList<VariantTable.TableRow> intronicItems,
        ObservableList<VariantTable.TableRow> intergenicItems) {

        chrome.variantTable().setItems(codingItems, intronicItems, intergenicItems);
    }

    private void setTablePlaceholders(Node codingPlaceholder, Node intronicPlaceholder, Node intergenicPlaceholder) {
        chrome.variantTable().setPlaceholders(codingPlaceholder, intronicPlaceholder, intergenicPlaceholder);
    }

    // ── LOH tab ───────────────────────────────────────────────────────────────

    /**
     * Show the LOH results sub-tab only after regions have been calculated.
     */
    void updateLohTabVisibility(VariantFilter filter, boolean hasRegions) {
        if (n.resultsTabPane() == null || n.lohRegionsTab() == null) {
            return;
        }
        boolean show = hasRegions;
        if (show) {
            if (!n.resultsTabPane().getTabs().contains(n.lohRegionsTab())) {
                int index = features.indexAfterAnnotationFeatureTabs();
                n.resultsTabPane().getTabs().add(
                    Math.min(index, n.resultsTabPane().getTabs().size()), n.lohRegionsTab());
            }
        } else {
            n.resultsTabPane().getTabs().remove(n.lohRegionsTab());
        }
    }
}
