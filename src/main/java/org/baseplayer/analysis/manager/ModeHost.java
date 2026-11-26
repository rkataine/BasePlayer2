package org.baseplayer.analysis.manager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.baseplayer.analysis.signal.SignalDataset;
import org.baseplayer.analysis.signal.SignalDatasetCoordinator;
import org.baseplayer.analysis.signal.SignalDatasetRegistry;
import org.baseplayer.analysis.ui.SignalAnalysisWorkspace;
import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.ui.components.SampleComparisonPanel;
import org.baseplayer.variant.ui.components.VariantClassWorkspace;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Region;

/**
 * Owns Point / SV / Signal mode tab lifecycle for Analysis Manager.
 * Panel install and one-shot FXML nesting stay in the shell controller.
 */
public final class ModeHost {

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
    private final Map<String, SignalModeBundle> signalModes = new LinkedHashMap<>();

    private TabPane filterTabPane;
    private Tab pointMutationsTab;
    private Tab structuralVariantsTab;

    /** Mirrors {@link #activeSignalBundle()} for existing call sites. */
    private TabPane signalToolTabPane;
    private SignalAnalysisWorkspace signalWorkspace;
    private SampleComparisonPanel signalSampleComparisonPanel;

    public void bindChrome(TabPane filterTabPane, Tab pointMutationsTab, Tab structuralVariantsTab) {
        this.filterTabPane = filterTabPane;
        this.pointMutationsTab = pointMutationsTab;
        this.structuralVariantsTab = structuralVariantsTab;
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

    public Map<String, SignalModeBundle> signalModes() {
        return signalModes;
    }

    public TabPane signalToolTabPane() {
        return signalToolTabPane;
    }

    public SignalAnalysisWorkspace signalWorkspace() {
        return signalWorkspace;
    }

    public SampleComparisonPanel signalSampleComparisonPanel() {
        return signalSampleComparisonPanel;
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

    public SignalModeBundle activeSignalBundle() {
        Tab selected = filterTabPane != null
            ? filterTabPane.getSelectionModel().getSelectedItem()
            : null;
        if (selected != null) {
            for (SignalModeBundle bundle : signalModes.values()) {
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

    public void syncActiveSignalPointers() {
        SignalModeBundle bundle = activeSignalBundle();
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

    /**
     * Sync Analysis outer tabs with {@link SignalDatasetRegistry}.
     *
     * @param ensureTab creates a missing dataset tab (shell owns panel wiring)
     * @param afterChange e.g. refresh busy-overlay lock targets
     */
    public void syncSignalDatasetTabs(
            Consumer<SignalDataset> ensureTab,
            Runnable afterChange) {
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
            if (ensureTab != null) {
                ensureTab.accept(ds);
            }
        }
        Iterator<Map.Entry<String, SignalModeBundle>> it = signalModes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SignalModeBundle> entry = it.next();
            if (!wanted.contains(entry.getKey())) {
                filterTabPane.getTabs().remove(entry.getValue().modeTab);
                it.remove();
            }
        }
        syncActiveSignalPointers();
        if (afterChange != null) {
            afterChange.run();
        }
    }

    public void closeSignalDatasetTab(String datasetId, Runnable afterChange) {
        if (datasetId == null) {
            return;
        }
        SignalModeBundle bundle = signalModes.remove(datasetId);
        if (bundle != null && filterTabPane != null) {
            filterTabPane.getTabs().remove(bundle.modeTab);
        }
        SignalDatasetRegistry.get().remove(datasetId);
        org.baseplayer.analysis.AnalysisSession.get().clearSignalSlice(datasetId);
        syncActiveSignalPointers();
        if (afterChange != null) {
            afterChange.run();
        }
    }

    public void refreshSignalAllChromosomesButton() {
        syncActiveSignalPointers();
        if (signalWorkspace == null || signalToolTabPane == null) {
            return;
        }
        Tab tool = signalToolTabPane.getSelectionModel().getSelectedItem();
        signalWorkspace.setAllChromosomesAction(signalWorkspace.actionForToolTab(tool));
    }

    public void selectSignalMode(
            Consumer<SignalDataset> ensureTab,
            Runnable afterSync) {
        selectSignalDataset(
            org.baseplayer.analysis.AnalysisSession.get().getActiveSignalDatasetId(),
            ensureTab,
            afterSync);
    }

    public void selectSignalDataset(
            String datasetId,
            Consumer<SignalDataset> ensureTab,
            Runnable afterSync) {
        if (filterTabPane == null) {
            return;
        }
        syncSignalDatasetTabs(ensureTab, afterSync);
        SignalModeBundle bundle = datasetId != null ? signalModes.get(datasetId) : null;
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
    }

    public void refreshSignalSources(
            Consumer<SignalDataset> ensureTab,
            Runnable afterSync,
            Runnable onExcelVisibility) {
        SignalDatasetCoordinator.ensureDatasetsForOpenTracks();
        syncSignalDatasetTabs(ensureTab, afterSync);
        syncActiveSignalPointers();
        if (signalWorkspace != null) {
            signalWorkspace.refreshFromData();
        }
        if (onExcelVisibility != null) {
            onExcelVisibility.run();
        }
    }

    public void refreshSignalDataset(
            String datasetId,
            Consumer<SignalDataset> ensureTab,
            Runnable afterSync,
            Runnable onExcelVisibility) {
        syncSignalDatasetTabs(ensureTab, afterSync);
        SignalModeBundle bundle = datasetId != null ? signalModes.get(datasetId) : null;
        if (bundle != null) {
            org.baseplayer.analysis.AnalysisSession.get().setActiveSignalDatasetId(datasetId);
            filterTabPane.getSelectionModel().select(bundle.modeTab);
            syncActiveSignalPointers();
            bundle.workspace.refreshFromData();
        } else {
            refreshSignalSources(ensureTab, afterSync, onExcelVisibility);
            return;
        }
        if (onExcelVisibility != null) {
            onExcelVisibility.run();
        }
    }

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
            SignalModeBundle signal = activeSignalBundle();
            if (signal != null && filterTabPane.getTabs().contains(signal.modeTab)) {
                filterTabPane.getSelectionModel().select(signal.modeTab);
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
                boolean isSignalTab = false;
                for (SignalModeBundle bundle : signalModes.values()) {
                    if (bundle.modeTab == tab) {
                        isSignalTab = true;
                        break;
                    }
                }
                if (isSignalTab) {
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
