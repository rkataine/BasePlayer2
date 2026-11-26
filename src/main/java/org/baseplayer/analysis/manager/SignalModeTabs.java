package org.baseplayer.analysis.manager;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.baseplayer.analysis.signal.SignalDataset;
import org.baseplayer.analysis.signal.SignalDatasetCoordinator;
import org.baseplayer.analysis.signal.SignalDatasetRegistry;
import org.baseplayer.analysis.ui.SignalAnalysisWorkspace;
import org.baseplayer.variant.ui.components.ControlFilesPanel;
import org.baseplayer.variant.ui.components.SampleComparisonPanel;

import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Signal dataset mode tabs: one Analysis outer tab per registered {@code SignalDataset},
 * each with Filters / Sample Comparison / Control Files / Group comparison above a Signals table.
 * Assembled and owned by {@link ModeHost}.
 */
final class SignalModeTabs {

    private final Map<String, ModeHost.SignalModeBundle> signalModes = new LinkedHashMap<>();
    private final TabPane filterTabPane;
    private final ModeHost.Callbacks cb;
    private final AnalysisBusyGate busy;
    private final Runnable refreshLockTargets;

    /** Mirrors {@link #activeSignalBundle()} for existing call sites. */
    private TabPane signalToolTabPane;
    private SignalAnalysisWorkspace signalWorkspace;
    private SampleComparisonPanel signalSampleComparisonPanel;

    SignalModeTabs(
            TabPane filterTabPane,
            ModeHost.Callbacks cb,
            AnalysisBusyGate busy,
            Runnable refreshLockTargets) {
        this.filterTabPane = filterTabPane;
        this.cb = cb;
        this.busy = busy;
        this.refreshLockTargets = refreshLockTargets;
    }

    Map<String, ModeHost.SignalModeBundle> signalModes() {
        return signalModes;
    }

    TabPane signalToolTabPane() {
        return signalToolTabPane;
    }

    SignalAnalysisWorkspace signalWorkspace() {
        return signalWorkspace;
    }

    boolean isSignalTab(Tab tab) {
        for (ModeHost.SignalModeBundle bundle : signalModes.values()) {
            if (bundle.modeTab == tab) {
                return true;
            }
        }
        return false;
    }

    ModeHost.SignalModeBundle activeSignalBundle() {
        Tab selected = filterTabPane != null
            ? filterTabPane.getSelectionModel().getSelectedItem()
            : null;
        if (selected != null) {
            for (ModeHost.SignalModeBundle bundle : signalModes.values()) {
                if (bundle.modeTab == selected) {
                    return bundle;
                }
            }
        }
        String activeId = org.baseplayer.analysis.AnalysisSession.get().getActiveSignalDatasetId();
        if (activeId != null && signalModes.containsKey(activeId)) {
            return signalModes.get(activeId);
        }
        return signalModes.isEmpty() ? null : signalModes.values().iterator().next();
    }

    public boolean isSignalModeSelected() {
        syncActiveSignalPointers();
        Tab selected = filterTabPane != null
            ? filterTabPane.getSelectionModel().getSelectedItem()
            : null;
        if (selected == null) {
            return false;
        }
        for (ModeHost.SignalModeBundle bundle : signalModes.values()) {
            if (bundle.modeTab == selected) {
                return true;
            }
        }
        return false;
    }

    public void syncActiveSignalPointers() {
        ModeHost.SignalModeBundle bundle = activeSignalBundle();
        if (bundle == null) {
            signalToolTabPane = null;
            signalWorkspace = null;
            signalSampleComparisonPanel = null;
            return;
        }
        signalToolTabPane = bundle.toolTabPane;
        signalWorkspace = bundle.workspace;
        signalSampleComparisonPanel = bundle.sampleComparisonPanel;
        bundle.workspace.activateSessionSlice();
    }

    /** Sync Analysis outer tabs with {@link SignalDatasetRegistry}. */
    public void syncSignalDatasetTabs() {
        if (filterTabPane == null) {
            return;
        }
        SignalDatasetRegistry registry = SignalDatasetRegistry.get();
        Set<String> wanted = new LinkedHashSet<>();
        for (SignalDataset ds : registry.all()) {
            if (ds == null || ds.isEmpty()) {
                continue;
            }
            wanted.add(ds.getId());
            ensureSignalDatasetTab(ds);
        }
        Iterator<Map.Entry<String, ModeHost.SignalModeBundle>> it = signalModes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ModeHost.SignalModeBundle> entry = it.next();
            if (!wanted.contains(entry.getKey())) {
                filterTabPane.getTabs().remove(entry.getValue().modeTab);
                it.remove();
            }
        }
        syncActiveSignalPointers();
        refreshLockTargets.run();
    }

    /** Peer mode: named dataset Filters | Group comparison above a Signals results table. */
    private void ensureSignalDatasetTab(org.baseplayer.analysis.signal.SignalDataset dataset) {
        if (filterTabPane == null || dataset == null) {
            return;
        }
        ModeHost.SignalModeBundle existing = signalModes.get(dataset.getId());
        if (existing != null) {
            if (!dataset.getDisplayName().equals(existing.modeTab.getText())) {
                existing.modeTab.setText(dataset.getDisplayName());
            }
            if (!filterTabPane.getTabs().contains(existing.modeTab)) {
                filterTabPane.getTabs().add(existing.modeTab);
            }
            return;
        }

        org.baseplayer.analysis.AnalysisSession.get().ensureSignalSlice(dataset.getId());
        org.baseplayer.analysis.ui.SignalAnalysisWorkspace workspace =
            new org.baseplayer.analysis.ui.SignalAnalysisWorkspace(dataset);
        workspace.setOnResultUpdated(result -> cb.excelVisibilityFromSignal().run());
        workspace.setAnalysisBusyRunner(busy);

        TabPane toolTabs = new TabPane();
        toolTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        toolTabs.getStyleClass().add("filter-tabs");

        Tab signalFiltersTab = new Tab("Filters", workspace.createSignalPane());
        signalFiltersTab.setClosable(false);

        javafx.util.Pair<Node, SampleComparisonPanel.Nodes> signalComp =
            SampleComparisonPanel.buildUiForSignal();
        SampleComparisonPanel comparisonPanel = new SampleComparisonPanel();
        comparisonPanel.install(
            signalComp.getValue(),
            cb.scheduleSignalComparison(),
            cb.scheduleImmediateSignalComparison(),
            cb.suppressFilterEvents());
        comparisonPanel.configurePresenceOnlyForSignal();
        comparisonPanel.syncSharedSampleRangeBoundsForSignal();
        org.baseplayer.analysis.AnalysisSession.get().setActiveSignalDatasetId(dataset.getId());
        comparisonPanel.loadFrom(
            org.baseplayer.analysis.AnalysisSession.get().getSignalComparison());
        SampleComparisonPanel.wireApplyButton(
            signalComp.getKey(), cb.scheduleImmediateSignalComparison());
        workspace.mountPatternsInto(signalComp.getValue().differentialHost());
        Tab signalComparisonTab = new Tab("Sample Comparison", signalComp.getKey());
        signalComparisonTab.setClosable(false);

        javafx.util.Pair<Node, ControlFilesPanel.Nodes> signalCtrl = ControlFilesPanel.buildUi();
        ControlFilesPanel signalControlFiles = new ControlFilesPanel();
        signalControlFiles.install(signalCtrl.getValue(), true);
        signalCtrl.getKey().setUserData(signalControlFiles);
        cb.wireControlPanel().accept(signalControlFiles);
        Node signalControlContent = ModeSplitChrome.wrapSignalControlNote(signalCtrl.getKey());
        Tab signalControlTab = new Tab("Control Files", signalControlContent);
        signalControlTab.setClosable(false);

        Tab signalGroupTab = new Tab("Group comparison", workspace.createComparisonPane());
        signalGroupTab.setClosable(false);

        toolTabs.getTabs().addAll(
            signalFiltersTab, signalComparisonTab, signalControlTab, signalGroupTab);

        Node resultsArea = workspace.createResultsArea();
        if (resultsArea instanceof Region resultsRegion) {
            resultsRegion.setMinHeight(120);
            VBox.setVgrow(resultsRegion, Priority.ALWAYS);
        }

        SplitPane signalSplit = new SplitPane();
        ModeSplitChrome.mount(signalSplit, toolTabs, resultsArea, 0.38);

        Tab modeTab = new Tab(dataset.getDisplayName(), signalSplit);
        modeTab.setClosable(true);
        modeTab.setOnCloseRequest(e -> {
            e.consume();
            closeSignalDatasetTab(dataset.getId());
        });

        ModeHost.SignalModeBundle bundle = new ModeHost.SignalModeBundle(
            dataset.getId(), modeTab, toolTabs, workspace);
        bundle.sampleComparisonPanel = comparisonPanel;
        signalModes.put(dataset.getId(), bundle);

        toolTabs.getSelectionModel().selectedItemProperty().addListener(
            (obs, was, is) -> {
                if (filterTabPane.getSelectionModel().getSelectedItem() == modeTab) {
                    refreshSignalAllChromosomesButton();
                }
            });
        modeTab.selectedProperty().addListener((obs, was, is) -> {
            if (Boolean.TRUE.equals(is)) {
                workspace.activateSessionSlice();
                syncActiveSignalPointers();
                refreshSignalAllChromosomesButton();
            }
        });

        workspace.getAnnotateAllButton().setOnAction(e -> cb.annotateAll().run());
        filterTabPane.getTabs().add(modeTab);
        syncActiveSignalPointers();
        refreshSignalAllChromosomesButton();
        refreshLockTargets.run();
    }

    private void closeSignalDatasetTab(String datasetId) {
        if (datasetId == null) {
            return;
        }
        ModeHost.SignalModeBundle bundle = signalModes.remove(datasetId);
        if (bundle != null && filterTabPane != null) {
            filterTabPane.getTabs().remove(bundle.modeTab);
        }
        SignalDatasetRegistry.get().remove(datasetId);
        org.baseplayer.analysis.AnalysisSession.get().clearSignalSlice(datasetId);
        syncActiveSignalPointers();
        refreshLockTargets.run();
        cb.updateModeTabVisibility().run();
    }

    public void refreshSignalAllChromosomesButton() {
        syncActiveSignalPointers();
        if (signalWorkspace == null || signalToolTabPane == null) {
            return;
        }
        Tab tool = signalToolTabPane.getSelectionModel().getSelectedItem();
        signalWorkspace.setAllChromosomesAction(signalWorkspace.actionForToolTab(tool));
    }

    /** Focus the active / first Signal dataset tab. */
    public void selectSignalMode() {
        selectSignalDataset(
            org.baseplayer.analysis.AnalysisSession.get().getActiveSignalDatasetId());
    }

    /** Focus a specific named Signal dataset tab. */
    public void selectSignalDataset(String datasetId) {
        if (filterTabPane == null) {
            return;
        }
        syncSignalDatasetTabs();
        ModeHost.SignalModeBundle bundle = datasetId != null ? signalModes.get(datasetId) : null;
        if (bundle == null) {
            bundle = activeSignalBundle();
        }
        if (bundle != null && filterTabPane.getTabs().contains(bundle.modeTab)) {
            org.baseplayer.analysis.AnalysisSession.get().setActiveSignalDatasetId(bundle.datasetId);
            filterTabPane.getSelectionModel().select(bundle.modeTab);
            syncActiveSignalPointers();
            if (bundle.toolTabPane != null && !bundle.toolTabPane.getTabs().isEmpty()) {
                bundle.toolTabPane.getSelectionModel().select(0);
            }
        }
        cb.updateModeTabVisibility().run();
    }

    /** Refresh Signal Value kinds after opening BAM companions / tables. */
    public void refreshSignalSources() {
        refreshSignalSourcesQuiet();
        cb.updateModeTabVisibility().run();
    }

    private void refreshSignalSourcesQuiet() {
        SignalDatasetCoordinator.ensureDatasetsForOpenTracks();
        syncSignalDatasetTabs();
        syncActiveSignalPointers();
        if (signalWorkspace != null) {
            signalWorkspace.refreshFromData();
        }
        cb.excelVisibilityFromSignal().run();
    }

    /** Refresh a specific dataset workspace after open. */
    public void refreshSignalDataset(String datasetId) {
        syncSignalDatasetTabs();
        ModeHost.SignalModeBundle bundle = datasetId != null ? signalModes.get(datasetId) : null;
        if (bundle == null) {
            refreshSignalSources();
            return;
        }
        org.baseplayer.analysis.AnalysisSession.get().setActiveSignalDatasetId(datasetId);
        filterTabPane.getSelectionModel().select(bundle.modeTab);
        syncActiveSignalPointers();
        bundle.workspace.refreshFromData();
        cb.excelVisibilityFromSignal().run();
        cb.updateModeTabVisibility().run();
    }

    /** Signal Sample Comparison Apply — updates Signal settings and refreshes annotation. */
    public void applySignalComparison() {
        syncActiveSignalPointers();
        if (signalSampleComparisonPanel == null) {
            return;
        }
        if (signalWorkspace != null) {
            signalWorkspace.activateSessionSlice();
        }
        signalSampleComparisonPanel.syncSharedSampleRangeBoundsForSignal();
        org.baseplayer.analysis.signal.SignalComparisonSettings settings =
            new org.baseplayer.analysis.signal.SignalComparisonSettings();
        signalSampleComparisonPanel.writeTo(settings);
        org.baseplayer.analysis.AnalysisSession.get().setSignalComparison(settings);
        org.baseplayer.analysis.ComparisonContext.get().writeToDocument(
            org.baseplayer.project.ProjectSessionState.get().getDocument());
        org.baseplayer.project.ProjectSessionState.get().markDirty();
        if (signalWorkspace != null) {
            signalWorkspace.refreshAnnotationFromSession();
            // Clear stale group comparison table; user re-Runs with filtered matrix.
            signalWorkspace.getComparisonResultTable().setResult(null);
        }
        org.baseplayer.draw.GenomicCanvas.update.set(
            !org.baseplayer.draw.GenomicCanvas.update.get());
    }

    /** Refilter every Signal dataset's annotation to the currently visible gene types. */
    void refreshAfterGeneVisibility() {
        org.baseplayer.analysis.AnalysisSession session =
            org.baseplayer.analysis.AnalysisSession.get();
        String previousActive = session.getActiveSignalDatasetId();
        for (ModeHost.SignalModeBundle bundle : signalModes.values()) {
            bundle.workspace.activateSessionSlice();
            session.refilterAnnotationForVisibility();
            org.baseplayer.analysis.groupstat.GroupStatResult signal = session.getAnnotationResult();
            if (signal != null) {
                bundle.workspace.getResultTable().setResult(signal);
            }
            bundle.workspace.refreshComparisonAfterGeneVisibility();
        }
        if (previousActive != null) {
            session.setActiveSignalDatasetId(previousActive);
        } else if (signalModes.isEmpty()) {
            session.refilterAnnotationForVisibility();
        }
        syncActiveSignalPointers();
    }
}
