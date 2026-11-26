package org.baseplayer.analysis.manager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.signal.SignalDataset;
import org.baseplayer.analysis.signal.SignalDatasetRegistry;
import org.baseplayer.analysis.ui.MutationEnrichmentPanel;
import org.baseplayer.analysis.ui.SignalAnalysisWorkspace;
import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.ui.components.ControlFilesPanel;
import org.baseplayer.variant.ui.components.SampleComparisonPanel;
import org.baseplayer.variant.ui.components.VariantBusyOverlay;
import org.baseplayer.variant.ui.components.VariantClassWorkspace;
import org.baseplayer.variant.ui.components.VariantFiltersPanel;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Pair;

/**
 * Owns Point / SV / Signal mode chrome for Analysis Manager: outer mode tabs, per-mode
 * tool-tab + results splits, Group comparison tabs, and Signal dataset tabs.
 * <p>
 * The shell supplies FXML nodes ({@link Nodes}) and callbacks ({@link Callbacks}); ModeHost
 * assembles the mode UI and calls back for busy runs, filter apply, Excel visibility, etc.
 */
public final class ModeHost {

    /** FXML nodes the shell hands over once, after injection. */
    public record Nodes(
            TabPane filterTabPane,
            Tab pointMutationsTab,
            Tab structuralVariantsTab,
            Tab lohRegionsTab,
            SplitPane pointMainSplitPane,
            SplitPane svMainSplitPane,
            VBox svFiltersHost,
            TabPane resultsTabPane,
            TabPane svResultsTabPane,
            Button pointAnnotateAllButton,
            Button svAnnotateAllButton,
            Button reloadBannerButton) {}

    /** Shell behaviour ModeHost triggers but does not own. */
    public record Callbacks(
            BooleanSupplier suppressFilterEvents,
            Runnable scheduleFilterUpdate,
            Runnable scheduleImmediateFilterApply,
            Runnable scheduleSignalComparison,
            Runnable scheduleImmediateSignalComparison,
            Runnable calculateLoh,
            Runnable annotateAll,
            Runnable reloadFiltered,
            Consumer<ControlFilesPanel> wireControlPanel,
            Runnable controlPanelsReady,
            Runnable excelVisibilityFromSignal,
            Runnable updateModeTabVisibility) {}

    /** Group comparison tool tab + results tab + panel for one Point/SV mode. */
    public record GroupComparison(MutationEnrichmentPanel panel, Tab toolTab, Tab resultsTab) {}

    /** One Analysis outer tab per registered {@link SignalDataset}. */
    public static final class SignalModeBundle {
        public final String datasetId;
        public final Tab modeTab;
        public final TabPane toolTabPane;
        public final SignalAnalysisWorkspace workspace;
        public SampleComparisonPanel sampleComparisonPanel;

        public SignalModeBundle(
                String datasetId,
                Tab modeTab,
                TabPane toolTabPane,
                SignalAnalysisWorkspace workspace) {
            this.datasetId = datasetId;
            this.modeTab = modeTab;
            this.toolTabPane = toolTabPane;
            this.workspace = workspace;
        }
    }

    private VariantClassWorkspace pointWorkspace;
    private VariantClassWorkspace svWorkspace;

    private Nodes nodes;
    private Callbacks cb;
    private AnalysisBusyGate busy;

    private TabPane filterTabPane;
    private Tab pointMutationsTab;
    private Tab structuralVariantsTab;
    private TabPane resultsTabPane;
    private TabPane pointToolTabPane;
    private TabPane svToolTabPane;
    private GroupComparison pointGroup;
    private GroupComparison svGroup;

    private SignalModeTabs signal;

    // ── Binding ───────────────────────────────────────────────────────────────

    public void bind(Nodes nodes, Callbacks callbacks, AnalysisBusyGate busy) {
        this.nodes = nodes;
        this.cb = callbacks;
        this.busy = busy;
        this.filterTabPane = nodes.filterTabPane();
        this.pointMutationsTab = nodes.pointMutationsTab();
        this.structuralVariantsTab = nodes.structuralVariantsTab();
        this.resultsTabPane = nodes.resultsTabPane();
        this.signal = new SignalModeTabs(filterTabPane, callbacks, busy, this::refreshBusyLockTargets);
    }

    public void setPointWorkspace(VariantClassWorkspace workspace) {
        this.pointWorkspace = workspace;
    }

    public void setSvWorkspace(VariantClassWorkspace workspace) {
        this.svWorkspace = workspace;
    }

    public VariantClassWorkspace point() {
        return pointWorkspace;
    }

    public VariantClassWorkspace sv() {
        return svWorkspace;
    }

    public TabPane pointToolTabPane() {
        return pointToolTabPane;
    }

    public TabPane svToolTabPane() {
        return svToolTabPane;
    }

    public MutationEnrichmentPanel pointEnrichmentPanel() {
        return pointGroup != null ? pointGroup.panel() : null;
    }

    public MutationEnrichmentPanel svEnrichmentPanel() {
        return svGroup != null ? svGroup.panel() : null;
    }

    public Tab pointGroupResultsTab() {
        return pointGroup != null ? pointGroup.resultsTab() : null;
    }

    public Tab svGroupResultsTab() {
        return svGroup != null ? svGroup.resultsTab() : null;
    }

    public TabPane signalToolTabPane() {
        return signal.signalToolTabPane();
    }

    public SignalAnalysisWorkspace signalWorkspace() {
        return signal.signalWorkspace();
    }

    public List<VariantClassWorkspace> workspaces() {
        List<VariantClassWorkspace> list = new ArrayList<>(2);
        if (pointWorkspace != null) {
            list.add(pointWorkspace);
        }
        if (svWorkspace != null) {
            list.add(svWorkspace);
        }
        return list;
    }

    // ── Filter snapshot / type population ─────────────────────────────────────

    public VariantFilter buildFilterFromUI() {
        // Unobserved class panels write empty allowedTypes. Load paths expand via
        // ensureUnobservedClassSlicesPassAll so a later VCF of that class is not blocked.
        // Legends / checkboxes use session observed types only — never invent here.
        VariantFilter point = pointWorkspace != null
            ? pointWorkspace.writeSlice()
            : emptyUnobservedSlice(VariantTypeVisuals.VariantClass.POINT);
        VariantFilter sv = svWorkspace != null
            ? svWorkspace.writeSlice()
            : emptyUnobservedSlice(VariantTypeVisuals.VariantClass.STRUCTURAL);

        VariantFilter merged = new VariantFilter();
        merged.setClassSlices(point, sv);
        return merged;
    }

    private static VariantFilter emptyUnobservedSlice(VariantTypeVisuals.VariantClass mode) {
        VariantFilter slice = new VariantFilter();
        slice.setAllowedTypes(EnumSet.noneOf(VcfVariantType.class));
        if (mode == VariantTypeVisuals.VariantClass.STRUCTURAL) {
            slice.setAllowedEffects(EnumSet.allOf(VariantEffect.class));
        }
        return slice;
    }

    /**
     * Populate type/effect checkboxes from {@code typeFilter}'s class slices and optionally
     * capture the load-time snapshot used for the "Reload needed" banner.
     */
    public void syncWorkspaceTypesFromFilter(
            List<VcfManager.CachedChromosomeVariants> sources,
            VariantFilter typeFilter,
            boolean captureSnapshot) {
        VariantFilter pointSnap = typeFilter != null && typeFilter.getPointSlice() != null
            ? typeFilter.getPointSlice()
            : typeFilter;
        VariantFilter svSnap = typeFilter != null && typeFilter.getSvSlice() != null
            ? typeFilter.getSvSlice()
            : typeFilter;
        if (pointWorkspace != null) {
            pointWorkspace.populateTypes(sources, pointSnap);
            if (captureSnapshot) {
                pointWorkspace.captureFilterSnapshot(pointSnap);
            }
        }
        if (svWorkspace != null) {
            svWorkspace.populateTypes(sources, svSnap);
            if (captureSnapshot) {
                svWorkspace.captureFilterSnapshot(svSnap);
            }
        }
    }

    // ── Busy lock targets ─────────────────────────────────────────────────────

    public VariantBusyOverlay.LockTargets lockTargets() {
        List<Button> buttons = new ArrayList<>();
        if (nodes.pointAnnotateAllButton() != null) {
            buttons.add(nodes.pointAnnotateAllButton());
        }
        if (nodes.svAnnotateAllButton() != null) {
            buttons.add(nodes.svAnnotateAllButton());
        }
        for (SignalModeBundle bundle : signal.signalModes().values()) {
            if (bundle.workspace != null && bundle.workspace.getAnnotateAllButton() != null) {
                buttons.add(bundle.workspace.getAnnotateAllButton());
            }
        }
        return new VariantBusyOverlay.LockTargets(
            filterTabPane, buttons, nodes.reloadBannerButton());
    }

    private void refreshBusyLockTargets() {
        busy.setLockTargets(lockTargets());
    }

    // ── One-shot assembly (shell calls from initialize) ───────────────────────

    /**
     * Nest Filters / Sample Comparison / Control under each mode workspace; outer
     * {@link #filterTabPane} becomes Point | SV (Signal tabs are added afterward).
     */
    public void restructureWorkspaces() {
        if (filterTabPane == null
                || nodes.pointMainSplitPane() == null
                || nodes.svMainSplitPane() == null) {
            return;
        }
        SplitPane pointSplit = nodes.pointMainSplitPane();
        SplitPane svSplit = nodes.svMainSplitPane();

        // Capture shared sibling tabs before removing them from the mode pane.
        Tab comparisonTab = ModeSplitChrome.findTabByText(filterTabPane, "Sample Comparison");
        Tab controlTab = ModeSplitChrome.findTabByText(filterTabPane, "Control Files");
        if (comparisonTab != null) {
            filterTabPane.getTabs().remove(comparisonTab);
        }
        if (controlTab != null) {
            filterTabPane.getTabs().remove(controlTab);
        }

        // ── Point workspace ──────────────────────────────────────────────────
        Node pointFilters = null;
        Node pointResults = null;
        if (pointSplit.getItems().size() >= 2) {
            pointFilters = pointSplit.getItems().get(0);
            pointResults = pointSplit.getItems().get(1);
        }
        pointToolTabPane = newToolTabPane();
        Tab pointFiltersTab = fixedTab(new Tab("Variant Filters", pointFilters));
        Tab pointComparisonTab = fixedTab(
            comparisonTab != null ? comparisonTab : new Tab("Sample Comparison"));
        pointComparisonTab.setText("Sample Comparison");
        Tab pointControlTab = fixedTab(controlTab != null ? controlTab : new Tab("Control Files"));
        pointControlTab.setText("Control Files");
        pointToolTabPane.getTabs().addAll(pointFiltersTab, pointComparisonTab, pointControlTab);
        if (pointFilters != null && pointResults != null) {
            ModeSplitChrome.mount(pointSplit, pointToolTabPane, pointResults, 0.45);
        }

        // ── SV workspace ─────────────────────────────────────────────────────
        Node svResults = svSplit.getItems().size() >= 2 ? svSplit.getItems().get(1) : null;
        svToolTabPane = newToolTabPane();
        // Filters host may already contain the built SV filter UI.
        Tab svFiltersTab = fixedTab(new Tab("Variant Filters", nodes.svFiltersHost()));

        Pair<Node, SampleComparisonPanel.Nodes> svComp = SampleComparisonPanel.buildUi();
        SampleComparisonPanel svSampleComparisonPanel = new SampleComparisonPanel();
        svSampleComparisonPanel.install(
            svComp.getValue(),
            cb.scheduleFilterUpdate(),
            cb.scheduleImmediateFilterApply(),
            cb.suppressFilterEvents());
        SampleComparisonPanel.wireApplyButton(svComp.getKey(), cb.scheduleImmediateFilterApply());
        SampleComparisonPanel.wireCalculateLohButton(svComp.getKey(), cb.calculateLoh());
        svWorkspace.setComparison(svSampleComparisonPanel);
        Tab svComparisonTab = fixedTab(new Tab("Sample Comparison", svComp.getKey()));

        Pair<Node, ControlFilesPanel.Nodes> svCtrl = ControlFilesPanel.buildUi();
        ControlFilesPanel svControlFilesPanel = new ControlFilesPanel();
        svControlFilesPanel.install(svCtrl.getValue(), true);
        svCtrl.getKey().setUserData(svControlFilesPanel);
        cb.wireControlPanel().accept(svControlFilesPanel);
        svWorkspace.setControl(svControlFilesPanel);
        cb.controlPanelsReady().run();
        Tab svControlTab = fixedTab(new Tab("Control Files", svCtrl.getKey()));

        svToolTabPane.getTabs().addAll(svFiltersTab, svComparisonTab, svControlTab);
        if (svResults != null) {
            ModeSplitChrome.mount(svSplit, svToolTabPane, svResults, 0.45);
        }

        pointWorkspace.setToolTabPane(pointToolTabPane);
        svWorkspace.setToolTabPane(svToolTabPane);

        // Outer pane should only carry mode tabs now (Point / SV).
        filterTabPane.getTabs().removeIf(t ->
            t != pointMutationsTab && t != structuralVariantsTab);
        if (pointMutationsTab != null && !filterTabPane.getTabs().contains(pointMutationsTab)) {
            filterTabPane.getTabs().add(0, pointMutationsTab);
        }
        if (structuralVariantsTab != null && !filterTabPane.getTabs().contains(structuralVariantsTab)) {
            filterTabPane.getTabs().add(structuralVariantsTab);
        }
        // LOH lives under Point results (beside Intergenic); hide until LOH mode is on.
        if (nodes.lohRegionsTab() != null && resultsTabPane != null) {
            resultsTabPane.getTabs().remove(nodes.lohRegionsTab());
        }
    }

    private static TabPane newToolTabPane() {
        TabPane tabs = new TabPane();
        // SELECTED_TAB so the dynamic Variant details tab can close; fixed tabs stay non-closable.
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        tabs.getStyleClass().add("filter-tabs");
        return tabs;
    }

    private static Tab fixedTab(Tab tab) {
        tab.setClosable(false);
        return tab;
    }

    /** SV filter panel lives in {@code svFiltersHost}; Point filters come from FXML. */
    public void installStructuralFilters() {
        VBox host = nodes.svFiltersHost();
        if (host == null) {
            return;
        }
        Pair<Node, VariantFiltersPanel.Nodes> built = VariantFiltersPanel.buildStructuralFiltersUi();
        host.getChildren().setAll(built.getKey());
        VBox.setVgrow(built.getKey(), Priority.ALWAYS);

        VariantFiltersPanel.Nodes svNodes = built.getValue();
        if (svNodes.reloadBannerButton() != null) {
            svNodes.reloadBannerButton().setOnAction(e -> cb.reloadFiltered().run());
        }
        VariantFiltersPanel svFiltersPanel = new VariantFiltersPanel();
        svFiltersPanel.install(
            svNodes,
            cb.scheduleFilterUpdate(),
            cb.scheduleImmediateFilterApply(),
            cb.suppressFilterEvents(),
            VariantTypeVisuals.VariantClass.STRUCTURAL);
        if (svWorkspace != null) {
            svWorkspace.setFilters(svFiltersPanel);
        }
    }

    /** Refresh comparison groups when the user opens a Sample Comparison tool tab. */
    public void wireComparisonRefresh() {
        refreshComparisonOnSelect(pointToolTabPane, pointWorkspace);
        refreshComparisonOnSelect(svToolTabPane, svWorkspace);
    }

    private static void refreshComparisonOnSelect(TabPane tools, VariantClassWorkspace workspace) {
        if (tools == null || workspace == null) {
            return;
        }
        tools.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null && "Sample Comparison".equals(newTab.getText())) {
                workspace.refreshComparisonGroups();
            }
        });
    }

    /**
     * Signal mode workspace + Point/SV Group comparison tabs.
     * All sample groups are compared automatically (no group-picker chrome).
     */
    public void installExtensions() {
        org.baseplayer.analysis.ComparisonContext.get().ensureBound();
        org.baseplayer.analysis.ComparisonContext.get().loadFromDocument(
            org.baseplayer.project.ProjectSessionState.get().getDocument());

        if (filterTabPane != null) {
            // Drop legacy Expression / Methylation / Signal comparison tabs.
            filterTabPane.getTabs().removeIf(t ->
                t != null && ("Expression".equals(t.getText())
                    || "Methylation".equals(t.getText())
                    || "Signal comparison".equals(t.getText())));
        }

        signal.syncSignalDatasetTabs();
        pointGroup = installGroupComparison(
            AnalysisMode.MUTATIONS_POINT, pointToolTabPane, resultsTabPane);
        svGroup = installGroupComparison(
            AnalysisMode.MUTATIONS_SV,
            svToolTabPane,
            nodes.svResultsTabPane() != null ? nodes.svResultsTabPane() : resultsTabPane);
        org.baseplayer.analysis.mutation.MutationGroupComparisonRunner.setAnnotateAllRunningCheck(
            busy::isAllChromosomeAnnotationRunning);
    }

    /**
     * Point/SV tool-tab run controls, with results hosted in the variant results TabPane
     * (same pattern as Signal → Signals table).
     */
    private GroupComparison installGroupComparison(
            AnalysisMode mode, TabPane toolTabs, TabPane resultsPane) {
        if (toolTabs == null) {
            return null;
        }
        MutationEnrichmentPanel panel = new MutationEnrichmentPanel(mode);
        panel.setAnalysisBusyRunner(busy);
        Tab toolTab = fixedTab(new Tab("Group comparison", panel));
        toolTabs.getTabs().add(toolTab);

        Tab resultsTab = fixedTab(new Tab("Group comparison", panel.getResultTable()));
        panel.setResultsTab(resultsTab);
        if (resultsPane != null && !resultsPane.getTabs().contains(resultsTab)) {
            resultsPane.getTabs().add(resultsTab);
        }
        panel.setOnResultReady(result -> {
            if (resultsPane != null) {
                if (!resultsPane.getTabs().contains(resultsTab)) {
                    resultsPane.getTabs().add(resultsTab);
                }
                resultsPane.getSelectionModel().select(resultsTab);
            }
        });
        return new GroupComparison(panel, toolTab, resultsTab);
    }

    // ── Signal dataset tabs (see SignalModeTabs) ──────────────────────────────

    public void syncSignalDatasetTabs() {
        signal.syncSignalDatasetTabs();
    }

    public void syncActiveSignalPointers() {
        signal.syncActiveSignalPointers();
    }

    public boolean isSignalModeSelected() {
        return signal.isSignalModeSelected();
    }

    public void refreshSignalAllChromosomesButton() {
        signal.refreshSignalAllChromosomesButton();
    }

    public void applySignalComparison() {
        signal.applySignalComparison();
    }

    /** Focus the active / first Signal dataset tab. */
    public void selectSignalMode() {
        signal.selectSignalMode();
    }

    /** Focus a specific named Signal dataset tab. */
    public void selectSignalDataset(String datasetId) {
        signal.selectSignalDataset(datasetId);
    }

    /** Refresh Signal Value kinds after opening BAM companions / tables. */
    public void refreshSignalSources() {
        signal.refreshSignalSources();
    }

    /** Refresh a specific dataset workspace after open. */
    public void refreshSignalDataset(String datasetId) {
        signal.refreshSignalDataset(datasetId);
    }

    /**
     * Gene-canvas biotype legend toggled: refilter Signal annotation and group comparisons
     * to the same visible gene types (no VCF reload / recollect).
     */
    public void refreshAfterGeneVisibility() {
        signal.refreshAfterGeneVisibility();
        if (pointEnrichmentPanel() != null) {
            pointEnrichmentPanel().refreshAfterGeneVisibility();
        }
        if (svEnrichmentPanel() != null) {
            svEnrichmentPanel().refreshAfterGeneVisibility();
        }
    }

    // ── Mode tab visibility ───────────────────────────────────────────────────

    /**
     * Show/hide Point / SV tabs and repair selection. Caller should
     * {@link #syncSignalDatasetTabs} first when Signal tabs may have changed.
     */
    public void updateModeTabVisibility(
            VcfManager vcfManager,
            Set<VcfVariantType> universe,
            Consumer<VariantFilter> updateLoh) {
        if (filterTabPane == null) {
            return;
        }
        Set<VcfVariantType> types = universe != null ? universe : Set.of();

        boolean hasPoint = VariantTypeVisuals.hasClass(types, VariantTypeVisuals.VariantClass.POINT);
        boolean hasSv = VariantTypeVisuals.hasClass(types, VariantTypeVisuals.VariantClass.STRUCTURAL);
        boolean hasSignalDatasets = !SignalDatasetRegistry.get().isEmpty();
        boolean hasSignal = hasSignalDatasets || AnalysisManagerFacade.hasComparableSignalData();
        // Only invent Point/SV tabs when nothing comparable is open yet (pre-load config).
        // Signal-only sessions must not show empty mutation tabs.
        if (!hasPoint && !hasSv && !hasSignal) {
            hasPoint = true;
            hasSv = true;
        }

        boolean both = hasPoint && hasSv;
        setTabVisible(pointMutationsTab, hasPoint);
        setTabVisible(structuralVariantsTab, hasSv);
        VariantFilter current = vcfManager != null ? vcfManager.getCurrentFilter() : null;
        if (updateLoh != null) {
            updateLoh.accept(current);
        }
        // Keep mode chrome when Signal is present even if only one VCF class.
        int modeCount = filterTabPane.getTabs().size();
        applyModeChrome(both || modeCount > 1);

        Tab selected = filterTabPane.getSelectionModel().getSelectedItem();
        boolean selectedInvalid = selected == null || !filterTabPane.getTabs().contains(selected);
        if (selectedInvalid) {
            SignalModeBundle activeSignal = signal.activeSignalBundle();
            if (activeSignal != null && filterTabPane.getTabs().contains(activeSignal.modeTab)) {
                filterTabPane.getSelectionModel().select(activeSignal.modeTab);
            } else if (pointMutationsTab != null && filterTabPane.getTabs().contains(pointMutationsTab)) {
                filterTabPane.getSelectionModel().select(pointMutationsTab);
            } else if (structuralVariantsTab != null
                && filterTabPane.getTabs().contains(structuralVariantsTab)) {
                filterTabPane.getSelectionModel().select(structuralVariantsTab);
            }
        }
    }

    /** When only one class is present, hide the outer Point|SV tab header. */
    public void applyModeChrome(boolean showModeTabs) {
        if (filterTabPane == null) {
            return;
        }
        filterTabPane.getStyleClass().remove("single-mode");
        if (!showModeTabs) {
            filterTabPane.getStyleClass().add("single-mode");
            filterTabPane.setTabMinHeight(0);
            filterTabPane.setTabMaxHeight(0);
        } else {
            filterTabPane.setTabMinHeight(Region.USE_COMPUTED_SIZE);
            filterTabPane.setTabMaxHeight(Double.MAX_VALUE);
            filterTabPane.applyCss();
            filterTabPane.requestLayout();
        }
    }

    public void setTabVisible(Tab tab, boolean visible) {
        if (tab == null || filterTabPane == null) {
            return;
        }
        if (visible) {
            if (!filterTabPane.getTabs().contains(tab)) {
                int index = 0;
                if (signal.isSignalTab(tab)) {
                    index = filterTabPane.getTabs().size();
                } else if (tab == structuralVariantsTab && pointMutationsTab != null
                    && filterTabPane.getTabs().contains(pointMutationsTab)) {
                    index = filterTabPane.getTabs().indexOf(pointMutationsTab) + 1;
                }
                filterTabPane.getTabs().add(Math.min(index, filterTabPane.getTabs().size()), tab);
            }
        } else {
            filterTabPane.getTabs().remove(tab);
        }
    }
}
