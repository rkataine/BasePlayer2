package org.baseplayer.analysis.manager;

import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

import org.baseplayer.MainApp;
import org.baseplayer.components.ColoredToggle;
import org.baseplayer.controllers.MenuBarController;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.ui.components.ControlFilesPanel;
import org.baseplayer.variant.ui.components.IntegerRangeSlider;
import org.baseplayer.variant.ui.components.SampleComparisonPanel;
import org.baseplayer.variant.ui.components.VariantBusyOverlay;
import org.baseplayer.variant.ui.components.VariantClassWorkspace;
import org.baseplayer.variant.ui.components.VariantFiltersPanel;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * FXML shell for Analysis Manager: holds injected nodes, builds the collaborators
 * ({@link ModeHost}, {@link VariantTableChrome}, {@link TableUiCoordinator}, {@link AnnotationFeatureTabs},
 * {@link ControlAnnotationOrchestrator}, {@link AnalysisBatchActions}, {@link ExportCoordinator},
 * {@link AnalysisBusyGate}) and forwards FXML actions and the few public entry points.
 */
public class AnalysisManagerController implements Initializable, PostLoadTablePort {

    // Filter Tab: Variant Filters
    @FXML private FlowPane variantTypesContainer;
    @FXML private FlowPane effectCategoriesContainer;
    @FXML private ColoredToggle selectAllTypesCheckBox;
    @FXML private ColoredToggle selectAllEffectsCheckBox;
    @FXML private Slider qualitySlider, coverageSlider;
    @FXML private TextField qualityField, coverageField;
    @FXML private Label qualityValueLabel, coverageValueLabel;
    @FXML private org.baseplayer.variant.ui.components.DualRangeSlider alleleFreqRangeSlider;
    @FXML private ColoredToggle cancerOnlyCheckBox;
    @FXML private VBox advancedFiltersContainer;
    @FXML private Button addInfoFilterButton;
    @FXML private Button addFilterFieldButton; // optional legacy; custom filter uses addInfoFilterButton
    @FXML private HBox reloadBanner;
    @FXML private Label reloadBannerLabel;
    @FXML private Button reloadBannerButton;

    @FXML private Tab pointMutationsTab;
    @FXML private Tab structuralVariantsTab;
    @FXML private Tab lohRegionsTab;
    @FXML private SplitPane pointMainSplitPane;
    @FXML private SplitPane svMainSplitPane;
    @FXML private VBox svFiltersHost;
    @FXML private TabPane svResultsTabPane;
    @FXML private TabPane lohResultsTabPane;
    @FXML private Tab svAllTab, svDelTab, svDupTab, svCnvGainTab, svCnvLossTab, svCnvNeutralTab,
        svInvTab, svTraTab, svBndTab, svInsTab;
    @FXML private TableView<?> svAllTable, svDelTable, svDupTable, svCnvGainTable, svCnvLossTable,
        svCnvNeutralTable, svInvTable, svTraTable, svBndTable, svInsTable;
    @FXML private Tab lohAllTab, lohAaTab, lohBbTab;
    @FXML private TableView<?> lohAllTable, lohAaTable, lohBbTable;

    // Loading Modal
    @FXML private VBox loadingModal;
    @FXML private ProgressIndicator loadingSpinner;
    @FXML private Label loadingStatusLabel;

    @FXML private HBox minimizedPane;
    @FXML private Button minimizedExpandButton;
    @FXML private Button minimizeButton;

    // Filter Tab: Sample Comparison
    @FXML private IntegerRangeSlider sharedSampleRangeSlider;
    @FXML private ColoredToggle geneLevelComparisonCheckBox;
    @FXML private TextField comparisonWindowField;
    @FXML private Button comparisonWindowApplyButton;
    @FXML private Label commonVariantsHelpLabel;
    @FXML private VBox comparisonGroupsContainer;
    @FXML private Label groupComparisonSummaryLabel;
    @FXML private Button refreshComparisonGroupsButton;
    @FXML private RadioButton presentMatchAllRadio, presentMatchAnyRadio;
    @FXML private Button calculateLohButton;

    // Filter Tab: Control Files
    @FXML private Label controlFilePathLabel;
    @FXML private Button addControlVcfButton;
    @FXML private Button removeControlFileButton;
    @FXML private VBox controlOptionsBox;
    @FXML private ColoredToggle filterByPopFreqCheckBox;
    @FXML private TextField maxPopFreqField;
    @FXML private ColoredToggle selectAllPopulationsCheckBox;
    @FXML private VBox controlPopulationBox;
    @FXML private Label clinVarPathLabel;
    @FXML private Button addClinVarButton;
    @FXML private Button removeClinVarButton;
    @FXML private javafx.scene.control.Hyperlink clinVarDownloadLink;
    @FXML private VBox clinVarOptionsBox;
    @FXML private ColoredToggle filterByClinVarSigCheckBox;
    @FXML private ColoredToggle clinVarPathogenicCheckBox;
    @FXML private ColoredToggle clinVarVusCheckBox;
    @FXML private ColoredToggle clinVarBenignCheckBox;
    @FXML private ColoredToggle clinVarOtherCheckBox;
    @FXML private VBox controlFilesRoot;
    @FXML private SplitPane controlFilesSplitPane;

    // Results Tables
    @FXML private TableView<VariantNode> codingTable, intronicTable, intergenicTable;
    @FXML private Tab codingTab, intronicTab, intergenicTab;

    // Table Columns - Coding
    @FXML private TableColumn<VariantNode, VariantNode> codingGeneColumn;
    @FXML private TableColumn<VariantNode, String> codingPositionColumn, codingRefAltColumn, codingTypeColumn;
    @FXML private TableColumn<VariantNode, String> codingEffectColumn, codingAaChangeColumn, codingCodonChangeColumn;
    @FXML private TableColumn<VariantNode, String> codingSamplesColumn, codingQualityColumn;

    // Table Columns - Intronic
    @FXML private TableColumn<VariantNode, VariantNode> intronicGeneColumn;
    @FXML private TableColumn<VariantNode, String> intronicPositionColumn, intronicRefAltColumn, intronicTypeColumn;
    @FXML private TableColumn<VariantNode, String> intronicSamplesColumn, intronicQualityColumn;

    // Table Columns - Intergenic
    @FXML private TableColumn<VariantNode, VariantNode> intergenicGeneColumn;
    @FXML private TableColumn<VariantNode, String> intergenicPositionColumn, intergenicRefAltColumn, intergenicTypeColumn;
    @FXML private TableColumn<VariantNode, String> intergenicSamplesColumn, intergenicQualityColumn;

    // Filter & Results tab panes
    @FXML private SplitPane variantFiltersSplitPane;
    @FXML private SplitPane sampleComparisonSplitPane;
    @FXML private TabPane filterTabPane, resultsTabPane;
    @FXML private Button annotateAllChromosomesButton;
    @FXML private TextField tableSearchField;
    @FXML private Button excelExportButton;
    @FXML private Button svAnnotateAllChromosomesButton;
    @FXML private TextField svTableSearchField;
    @FXML private Button svExcelExportButton;

    private final ModeHost modeHost = new ModeHost();
    private final ExportCoordinator exportCoordinator = new ExportCoordinator();
    private final AnalysisBusyGate busyGate = new AnalysisBusyGate();
    private final ManagerContext ctx = new ManagerContext(modeHost, busyGate);
    private VariantTableChrome tableChrome;
    private AnnotationFeatureTabs features;
    private TableUiCoordinator tables;
    private ControlAnnotationOrchestrator control;
    private AnalysisBatchActions batch;

    private ChangeListener<Boolean> updateListener;
    private ListChangeListener<SampleTrack> sampleTracksListener;
    private ChangeListener<? super Number> sampleGroupsRevisionListener;

    // ── Initialization ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        VariantClassWorkspace pointWorkspace = new VariantClassWorkspace(
            VariantTypeVisuals.VariantClass.POINT, pointMutationsTab);
        VariantClassWorkspace svWorkspace = new VariantClassWorkspace(
            VariantTypeVisuals.VariantClass.STRUCTURAL, structuralVariantsTab);
        modeHost.setPointWorkspace(pointWorkspace);
        modeHost.setSvWorkspace(svWorkspace);

        buildCollaborators();

        SampleComparisonPanel sampleComparisonPanel = new SampleComparisonPanel();
        sampleComparisonPanel.install(
            new SampleComparisonPanel.Nodes(
                sharedSampleRangeSlider, geneLevelComparisonCheckBox, comparisonWindowField,
                comparisonWindowApplyButton, commonVariantsHelpLabel, comparisonGroupsContainer,
                groupComparisonSummaryLabel, refreshComparisonGroupsButton,
                presentMatchAllRadio, presentMatchAnyRadio, calculateLohButton, null),
            batch::scheduleFilterUpdate,
            batch::scheduleImmediateFilterApply,
            () -> ctx.suppressFilterApplyEvents);
        pointWorkspace.setComparison(sampleComparisonPanel);

        VariantFiltersPanel variantFiltersPanel = new VariantFiltersPanel();
        variantFiltersPanel.install(
            new VariantFiltersPanel.Nodes(
                variantTypesContainer, selectAllTypesCheckBox, selectAllEffectsCheckBox,
                effectCategoriesContainer, qualitySlider, coverageSlider, alleleFreqRangeSlider,
                qualityField, coverageField, qualityValueLabel, coverageValueLabel,
                cancerOnlyCheckBox, advancedFiltersContainer, addInfoFilterButton,
                addFilterFieldButton, reloadBanner, reloadBannerLabel, reloadBannerButton),
            batch::scheduleFilterUpdate,
            batch::scheduleImmediateFilterApply,
            () -> ctx.suppressFilterApplyEvents,
            VariantTypeVisuals.VariantClass.POINT);
        pointWorkspace.setFilters(variantFiltersPanel);
        modeHost.installStructuralFilters();

        ControlFilesPanel controlFilesPanel = new ControlFilesPanel();
        controlFilesPanel.install(
            new ControlFilesPanel.Nodes(
                controlFilePathLabel, addControlVcfButton, removeControlFileButton,
                controlOptionsBox, filterByPopFreqCheckBox, maxPopFreqField,
                selectAllPopulationsCheckBox, controlPopulationBox, clinVarPathLabel,
                addClinVarButton, removeClinVarButton, clinVarDownloadLink, clinVarOptionsBox,
                filterByClinVarSigCheckBox, clinVarPathogenicCheckBox, clinVarVusCheckBox,
                clinVarBenignCheckBox, clinVarOtherCheckBox));
        // Early restore for Point; SV is restored after ModeHost.restructureWorkspaces().
        control.attachPointPanel(controlFilesPanel);
        pointWorkspace.setControl(controlFilesPanel);

        exportCoordinator.bind(() -> ctx.stage, tableChrome::variantTable, tableChrome::svVariantTable);
        busyGate.install(
            new VariantBusyOverlay.Nodes(loadingModal, loadingSpinner, loadingStatusLabel),
            modeHost.lockTargets(),
            () -> ctx.stage,
            () -> ctx.disposed);
        busyGate.setOnThreadRunnerIdle(tables::clearStuck);
        AnalysisManagerFacade.bindBusyGate(busyGate);

        tableChrome.install();
        tables.install();
        exportCoordinator.installButtons(excelExportButton, svExcelExportButton);

        modeHost.restructureWorkspaces();
        modeHost.installExtensions();
    }

    /** Create collaborators; cross-references go through lambdas so construction order is free. */
    private void buildCollaborators() {
        tableChrome = new VariantTableChrome(ctx, new VariantTableChrome.Nodes(
            codingTable, intronicTable, intergenicTable, codingTab, intronicTab, intergenicTab,
            lohRegionsTab, resultsTabPane, tableSearchField,
            svAllTable, svAllTab,
            new TableView<?>[] {
                svDelTable, svDupTable, svCnvGainTable, svCnvLossTable, svCnvNeutralTable,
                svInvTable, svTraTable, svBndTable, svInsTable },
            new Tab[] {
                svDelTab, svDupTab, svCnvGainTab, svCnvLossTab, svCnvNeutralTab,
                svInvTab, svTraTab, svBndTab, svInsTab },
            svResultsTabPane, svTableSearchField,
            lohAllTable, lohAllTab,
            new TableView<?>[] { lohAaTable, lohBbTable },
            new Tab[] { lohAaTab, lohBbTab }));
        features = new AnnotationFeatureTabs(
            ctx, tableChrome,
            new AnnotationFeatureTabs.Nodes(resultsTabPane, intergenicTab, tableSearchField),
            filter -> tables.scheduleRebuild(filter));
        tableChrome.bindFeatureTabs(features);
        tables = new TableUiCoordinator(ctx, tableChrome, features, exportCoordinator,
            new TableUiCoordinator.Nodes(resultsTabPane, intergenicTab, lohRegionsTab, tableSearchField));
        control = new ControlAnnotationOrchestrator(
            ctx,
            () -> controlFilesRoot != null
                ? controlFilesRoot
                : controlFilePathLabel != null ? controlFilePathLabel : pointMainSplitPane,
            () -> batch.applyFiltersNow(),
            () -> batch.scheduleImmediateFilterApply(),
            filter -> tables.scheduleRebuild(filter));
        batch = new AnalysisBatchActions(ctx, tables, control, this::loadData);
        modeHost.bind(
            new ModeHost.Nodes(
                filterTabPane, pointMutationsTab, structuralVariantsTab, lohRegionsTab,
                pointMainSplitPane, svMainSplitPane, svFiltersHost, resultsTabPane, svResultsTabPane,
                annotateAllChromosomesButton, svAnnotateAllChromosomesButton, reloadBannerButton),
            new ModeHost.Callbacks(
                () -> ctx.suppressFilterApplyEvents,
                batch::scheduleFilterUpdate,
                batch::scheduleImmediateFilterApply,
                batch::scheduleSignalComparisonUpdate,
                batch::scheduleImmediateSignalComparisonApply,
                batch::calculateLohRegions,
                batch::annotateAllChromosomes,
                batch::reloadFilteredVariants,
                control::wireControlFilesPanel,
                control::controlPanelsReady,
                exportCoordinator::showButtonsForGroupSheets,
                this::updateModeTabVisibility),
            busyGate);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called after FXML initialization to set up the dialog with VcfManager. */
    public void setup(Stage stage, VcfManager vcfManager, Runnable onClose) {
        ctx.stage = stage;
        ctx.vcfManager = vcfManager;

        batch.loadFilterState(vcfManager.getCurrentFilter());
        ctx.sourceVariantLists = ctx.cachedVariantSources();
        // Observed types live on each open VcfData; getters union them.
        modeHost.syncWorkspaceTypesFromFilter(
            ctx.sourceVariantLists, ctx.loadedOrCurrentFilter(), false);
        updateModeTabVisibility();

        vcfManager.setOnVcfAdded(this::loadData);
        updateListener = (obs, oldVal, newVal) -> Platform.runLater(this::loadData);
        GenomicCanvas.update.addListener(updateListener);
        loadData();

        ctx.syncSharedSampleRangeBounds();
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        sampleTracksListener = c -> Platform.runLater(() -> {
            if (!ctx.disposed) {
                ctx.syncSharedSampleRangeBounds();
                handleRefreshComparisonGroups();
            }
        });
        registry.getSampleTracks().addListener(sampleTracksListener);
        sampleGroupsRevisionListener = (obs, oldVal, newVal) -> Platform.runLater(() -> {
            if (!ctx.disposed) {
                handleRefreshComparisonGroups();
            }
        });
        registry.sampleGroupsRevisionProperty().addListener(sampleGroupsRevisionListener);
        modeHost.wireComparisonRefresh();

        for (Stage window : new Stage[] { stage, MainApp.stage }) {
            if (window != null) {
                window.focusedProperty().addListener((obs, o, n) -> hostWindowStateChanged());
                window.iconifiedProperty().addListener((obs, o, n) -> hostWindowStateChanged());
                window.showingProperty().addListener((obs, o, n) -> hostWindowStateChanged());
            }
        }
        busyGate.sync();

        // Keep a balanced workspace: filters on top, tables below.
        Platform.runLater(() -> {
            setDividers(0.45, pointMainSplitPane, svMainSplitPane);
            setDividers(0.5, variantFiltersSplitPane, sampleComparisonSplitPane);
            setDividers(0.55, controlFilesSplitPane);
            hostWindowStateChanged();
        });
    }

    private static void setDividers(double position, SplitPane... panes) {
        for (SplitPane pane : panes) {
            if (pane != null) {
                pane.setDividerPositions(position);
            }
        }
    }

    private void hostWindowStateChanged() {
        MenuBarController.updateAnalysisManagerButtonVisibility();
    }

    /** Update VcfManager reference when window is reused (singleton behavior). */
    public void updateVcfManager(VcfManager vcfManager) {
        ctx.vcfManager = vcfManager;
        vcfManager.setOnVcfAdded(this::loadData);
        loadData();
    }

    public void cleanup() {
        ctx.disposed = true;
        AnalysisManagerFacade.bindBusyGate(null);
        tableChrome.stopSearchTimers();
        ThreadRunner.RunnerTask task = busyGate.getAllChromosomeAnnotationTask();
        if (task != null && !task.isCompleted()) {
            task.cancel();
        }
        if (updateListener != null) {
            GenomicCanvas.update.removeListener(updateListener);
            updateListener = null;
        }
        tables.dispose();
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        if (sampleTracksListener != null) {
            registry.getSampleTracks().removeListener(sampleTracksListener);
            sampleTracksListener = null;
        }
        if (sampleGroupsRevisionListener != null) {
            registry.sampleGroupsRevisionProperty().removeListener(sampleGroupsRevisionListener);
            sampleGroupsRevisionListener = null;
        }
        busyGate.cancelDelayedLoadingModal();
        // Release table rows / source VariantLists so New Project can GC SV graphs
        // even if the disposed Stage is still briefly reachable.
        busyGate.setAllChromosomeAnnotationTask(null);
        ctx.lastSeenVariantsRevision = -1;
        ctx.sourceVariants = null;
        ctx.sourceVariantLists = List.of();
        tableChrome.variantTable().setZeroTabCounts();
        if (tableChrome.svVariantTable() != null) {
            tableChrome.svVariantTable().setBaseTabTitles();
        }
        tables.clearTableItemsForChromosomeSwitch();
        ctx.vcfManager.clearFilter();
        ctx.vcfManager.setOnVcfAdded(null);
        registry.clearSubsetSource(SampleRegistry.SubsetSource.GENE_FOCUS);
        MinimizedAnalysisManagerWindow.handleCleanup();
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadData() {
        VcfManager vcf = ctx.vcfManager;
        if (vcf == null || ctx.isAllChromosomeAnnotationRunning()) {
            return;
        }
        // A non-manual region/gene navigation should not trigger table autoscroll.
        if (!vcf.wasLastLoadManualChromosomeSelection()) {
            ctx.pendingScrollToChromosome = null;
        }
        String activeChromosome = vcf.getLastLoadedChromosome();
        if (!Objects.equals(ctx.chromosome, activeChromosome)) {
            ctx.chromosome = activeChromosome;
            ctx.lastSeenVariantsRevision = -1;
            ctx.pendingScrollToChromosome = null;
            // Same contract as Point/SV: chrom change → annotate current chromosome for Signal.
            if (modeHost.signalWorkspace() != null) {
                modeHost.signalWorkspace().annotateCurrentChromosome();
            }
        }
        if (activeChromosome != null
                && !activeChromosome.isBlank()
                && vcf.consumeManualChromosomeScrollRequest(activeChromosome)) {
            ctx.pendingScrollToChromosome = activeChromosome;
        }

        VariantList fresh = vcf.getCachedVariants();
        long revision = vcf.getVariantsRevision();
        // Skip rebuild if variant list and revision have not changed.
        if (fresh == ctx.sourceVariants && revision == ctx.lastSeenVariantsRevision) {
            tables.scrollToPendingChromosome();
            return;
        }

        ctx.sourceVariants = fresh;
        ctx.sourceVariantLists = ctx.cachedVariantSources();
        if (ctx.sourceVariantLists.isEmpty()) {
            ctx.lastSeenVariantsRevision = revision;
            tables.clearTableItemsForChromosomeSwitch();
            tables.setPlaceholder("No variants available");
            for (VariantClassWorkspace ws : ctx.workspaces()) {
                ws.populateTypes(ctx.sourceVariantLists, null);
            }
            updateModeTabVisibility();
            ctx.refreshReloadBannerState();
            if (!ctx.isCurrentChromosomeStillLoading()) {
                ctx.pendingScrollToChromosome = null;
            }
            return;
        }
        if (ctx.isCurrentChromosomeStillLoading()) {
            // Progressive loads fire many update events; defer the full rebuild and type scans
            // until the chromosome completes. Do not stamp lastSeenVariantsRevision so completion
            // re-enters loadData and refreshes mode tabs (Point|SV) once both classes are present.
            tables.markRebuildNeeded();
            busyGate.cancelDelayedLoadingModal();
            return;
        }
        ctx.lastSeenVariantsRevision = revision;
        modeHost.syncWorkspaceTypesFromFilter(ctx.sourceVariantLists, ctx.loadedOrCurrentFilter(), true);
        updateModeTabVisibility();
        ctx.refreshReloadBannerState();
        control.annotateControlAfThenRebuild();
    }

    // ── PostLoadTablePort ─────────────────────────────────────────────────────

    /**
     * Background continuation of a region load (same ThreadRunner task as gene annotate):
     * optional control AF, then table row construction. Must not touch the scene graph.
     */
    @Override
    public Object buildPostLoadTablesOnWorker(
            VariantList list, String chrom, VariantFilter filter, ThreadRunner.RunnerTask loadTask) {
        if (list == null || ctx.disposed) {
            return null;
        }
        control.annotatePostLoad(list, loadTask);
        ctx.postLoadPhase(loadTask, "Updating tables…");
        return tables.buildPostLoadResult(list, chrom, filter);
    }

    /**
     * Apply tables built on the region-load worker. Stamps revision so {@link #loadData}
     * does not start another annotate/table task.
     */
    @Override
    public void applyPostLoadTables(Object opaqueResult, VariantList list) {
        if (ctx.disposed || !(opaqueResult instanceof TableRebuildService.TableRebuildResult result)) {
            return;
        }
        ctx.sourceVariants = list;
        ctx.sourceVariantLists = ctx.cachedVariantSources();
        if (ctx.vcfManager != null) {
            ctx.lastSeenVariantsRevision = ctx.vcfManager.getVariantsRevision();
            String activeChromosome = ctx.vcfManager.getLastLoadedChromosome();
            if (activeChromosome != null) {
                ctx.chromosome = activeChromosome;
            }
        }
        VariantFilter typeFilter = result.filter();
        if (typeFilter == null && ctx.vcfManager != null) {
            typeFilter = ctx.vcfManager.getCurrentFilter();
        }
        modeHost.syncWorkspaceTypesFromFilter(ctx.sourceVariantLists, typeFilter, true);
        updateModeTabVisibility();
        ctx.refreshReloadBannerState();
        tables.finishPostLoad(result);
    }

    // ── Public entry points (callers: Facade, window, session, canvases) ──────

    public void loadFilterState(VariantFilter filter) {
        batch.loadFilterState(filter);
    }

    /** Snapshot of the filter currently expressed by the Variant Manager UI. */
    public VariantFilter snapshotUiFilter() {
        return ctx.buildFilterFromUI();
    }

    public void writeControlFilesToDocument(
            org.baseplayer.project.ProjectDocument.ControlFilesSpec spec) {
        control.writeControlFilesToDocument(spec);
    }

    public void syncSignalDatasetTabs() {
        modeHost.syncSignalDatasetTabs();
    }

    public void selectSignalMode() {
        modeHost.selectSignalMode();
    }

    public void selectSignalDataset(String datasetId) {
        modeHost.selectSignalDataset(datasetId);
    }

    public void refreshSignalSources() {
        modeHost.refreshSignalSources();
    }

    public void refreshSignalDataset(String datasetId) {
        modeHost.refreshSignalDataset(datasetId);
    }

    /** Show/hide Point mutations vs Structural variants tabs based on loaded data. */
    public void updateModeTabVisibility() {
        if (filterTabPane == null) {
            return;
        }
        // Presence from loaded caches (+ session after rebuild). Never invent from filter defaults.
        java.util.Set<org.baseplayer.variant.VcfVariantType> universe = ctx.collectLoadedVariantTypes();
        if (ctx.vcfManager != null) {
            universe.addAll(ctx.vcfManager.getSessionAvailableTypes());
        }
        modeHost.syncSignalDatasetTabs();
        modeHost.updateModeTabVisibility(
            ctx.vcfManager, universe, filter -> tables.updateLohTabVisibility(filter, false));
        modeHost.syncActiveSignalPointers();
    }

    /** Gene-canvas biotype legend toggled — rebuild variant tables and refilter Signal. */
    public void onGeneBiotypeVisibilityChanged() {
        if (ctx.disposed) {
            return;
        }
        modeHost.refreshAfterGeneVisibility();
        if (ctx.vcfManager != null) {
            tables.scheduleRebuild(ctx.vcfManager.getCurrentFilter());
        }
    }

    /** Facade: VCF visibility checkbox changed (FX thread). */
    void onSampleVisibilityChanged() {
        ctx.syncSharedSampleRangeBounds();
        if (!ctx.suppressFilterApplyEvents) {
            batch.scheduleFilterUpdate();
        }
    }

    /** Facade: sample/VCF data removed (FX thread). */
    void onSampleDataChanged() {
        ctx.syncSharedSampleRangeBounds();
        // Force loadData to rebuild filters/tables even if the VariantList instance is unchanged.
        ctx.sourceVariants = null;
        ctx.lastSeenVariantsRevision = -1;
        loadData();
        if (!ctx.suppressFilterApplyEvents) {
            batch.scheduleFilterUpdate();
        }
    }

    /** Facade: types enabled from a legend — repopulate checkboxes, then reload cached chromosomes. */
    void applyEnabledTypes(VariantFilter target, VariantFilter point, VariantFilter sv) {
        modeHost.point().populateTypes(ctx.cachedVariantSources(), point);
        modeHost.sv().populateTypes(ctx.cachedVariantSources(), sv);
        batch.loadFilterState(target);
        updateModeTabVisibility();
        batch.reloadFilteredVariants();
    }

    // ── FXML actions ──────────────────────────────────────────────────────────

    @FXML
    private void handleExportExcel() {
        exportCoordinator.exportVariantsToExcel(false);
    }

    @FXML
    private void handleAnnotateAllChromosomes() {
        batch.annotateAllChromosomes();
    }

    @FXML
    private void handleReloadFilteredVariants() {
        batch.reloadFilteredVariants();
    }

    @FXML
    private void handleApplyComparison() {
        batch.scheduleImmediateFilterApply();
    }

    @FXML
    private void handleCalculateLohRegions() {
        batch.calculateLohRegions();
    }

    @FXML
    private void handleRefreshComparisonGroups() {
        for (VariantClassWorkspace ws : ctx.workspaces()) {
            ws.refreshComparisonGroups();
        }
    }

    /** Point and SV share ClinVar; control VCF add/remove buttons belong to the Point panel. */
    @FXML
    private void handleAddControlVcf() {
        if (ctx.pointControl() != null) {
            ctx.pointControl().handleAddControlVcf();
        }
    }

    @FXML
    private void handleRemoveControlFile() {
        if (ctx.pointControl() != null) {
            ctx.pointControl().handleRemoveControlFile();
        }
    }

    @FXML
    private void handleAddClinVarVcf() {
        if (ctx.pointControl() != null) {
            ctx.pointControl().handleAddClinVarVcf();
        }
    }

    @FXML
    private void handleRemoveClinVarFile() {
        if (ctx.pointControl() != null) {
            ctx.pointControl().handleRemoveClinVarFile();
        }
    }

    @FXML
    private void handleAddCustomFilter() {
        VariantClassWorkspace point = modeHost.point();
        if (point != null && point.filters() != null) {
            point.filters().showCustomFilterDialog();
        }
    }

    @FXML
    private void handleAddInfoFilter() {
        handleAddCustomFilter();
    }

    @FXML
    private void handleAddFilterField() {
        handleAddCustomFilter();
    }

    public void handleMinimize() {
        MinimizedAnalysisManagerWindow.handleMinimize(ctx.stage);
    }

    public void handleExpandMinimized() {
        MinimizedAnalysisManagerWindow.handleExpand();
    }
}
