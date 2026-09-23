package org.baseplayer.variant.ui;

import org.baseplayer.MainApp;
import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.controllers.MenuBarController;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.ui.components.AgentPanel;
import org.baseplayer.variant.ui.components.ControlFilesPanel;
import org.baseplayer.variant.ui.components.IntegerRangeSlider;
import org.baseplayer.variant.ui.components.SampleComparisonPanel;
import org.baseplayer.variant.ui.components.VariantBusyOverlay;
import org.baseplayer.variant.ui.components.VariantFiltersPanel;
import org.baseplayer.variant.ui.components.VariantTable;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.util.Duration;

import java.util.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URL;

/**
 * Controller for the FXML-based Variant Manager dialog.
 * Composes filter/comparison/control/agent/table panels and runs data orchestration.
 */
public class VariantManagerController implements Initializable {

    private static final String TEXT           = "white";

    // ── FXML Components ───────────────────────────────────────────────────────

    // Filter Tab: Variant Filters
    @FXML private GridPane variantTypesContainer;  // Container for dynamic type checkboxes
    @FXML private GridPane effectCategoriesContainer;  // Container for dynamic effect checkboxes
    @FXML private CheckBox selectAllTypesCheckBox;
    @FXML private CheckBox selectAllEffectsCheckBox;
    @FXML private Slider qualitySlider, coverageSlider, alleleFreqSlider;
    @FXML private TextField qualityField, coverageField, alleleFreqField;
    @FXML private Label qualityValueLabel, coverageValueLabel, alleleFreqValueLabel;
    @FXML private CheckBox cancerOnlyCheckBox;
    @FXML private VBox advancedFiltersContainer;
    @FXML private Button addInfoFilterButton, addFilterFieldButton;
    @FXML private HBox reloadBanner;
    @FXML private Label reloadBannerLabel;
    @FXML private Button reloadBannerButton;

    // Loading Modal
    @FXML private VBox loadingModal;
    @FXML private ProgressIndicator loadingSpinner;
    @FXML private Label loadingLabel;
    @FXML private ProgressBar loadingProgressBar;
    @FXML private Label loadingEtaLabel;
    @FXML private Button loadingCancelButton;
    
    @FXML private HBox minimizedPane;
    @FXML private Button minimizedExpandButton;
    @FXML private Button minimizeButton;

    // Filter Tab: Sample Comparison
    @FXML private IntegerRangeSlider sharedSampleRangeSlider;
    @FXML private CheckBox geneLevelComparisonCheckBox;
    @FXML private TextField comparisonWindowField;
    @FXML private Label commonVariantsHelpLabel;
    @FXML private VBox comparisonGroupsContainer;
    @FXML private Label groupComparisonSummaryLabel;
    @FXML private Button refreshComparisonGroupsButton;
    @FXML private RadioButton presentMatchAllRadio, presentMatchAnyRadio;
    private SampleComparisonPanel sampleComparisonPanel;
    private VariantFiltersPanel variantFiltersPanel;
    private ControlFilesPanel controlFilesPanel;
    private AgentPanel agentPanel;
    private VariantBusyOverlay busyOverlay;

    // Filter Tab: Control Files
    @FXML private CheckBox filterByPopFreqCheckBox, useGnomadCheckBox, use1000GenomesCheckBox, useExacCheckBox;
    @FXML private TextField maxPopFreqField;
    @FXML private CheckBox showPathogenicCheckBox, hideBenignCheckBox;
    @FXML private TableView<ControlFilesPanel.ControlFileEntry> controlFilesTable;
    @FXML private TableColumn<ControlFilesPanel.ControlFileEntry, Boolean> controlFileEnabledColumn;
    @FXML private TableColumn<ControlFilesPanel.ControlFileEntry, String> controlFileNameColumn;
    @FXML private TableColumn<ControlFilesPanel.ControlFileEntry, String> controlFileTypeColumn;
    @FXML private TableColumn<ControlFilesPanel.ControlFileEntry, String> controlFileActionsColumn;

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
    @FXML private SplitPane mainSplitPane;
    @FXML private SplitPane variantFiltersSplitPane;
    @FXML private SplitPane sampleComparisonSplitPane;
    @FXML private SplitPane controlFilesSplitPane;
    @FXML private TabPane filterTabPane, resultsTabPane;
    @FXML private Button annotateAllChromosomesButton;
    @FXML private TextField tableSearchField;

    // Agent Tab
    @FXML private Tab agentTab;
    @FXML private PasswordField apiKeyField;
    @FXML private TextField agentModelField;
    @FXML private TextArea agentPromptArea, agentResponseArea;
    @FXML private Label agentStatusLabel;
    @FXML private Button agentSubmitButton;

    private VcfManager vcfManager;
    private Stage stage;

    private VariantList sourceVariants;
    private List<VcfManager.CachedChromosomeVariants> sourceVariantLists = List.of();
    private String chromosome;
    private ChangeListener<Boolean> updateListener;
    private volatile boolean annotationRunning;
    private volatile Thread annotationThread;
    private long lastSeenVariantsRevision = -1;
    private String pendingScrollToChromosome;
    
    // Debounce timer for real-time slider updates (200ms delay after last change)
    private Timeline filterDebounceTimer;
    // Short-delay timer for checkbox/filter actions so UI paints first
    private Timeline immediateFilterApplyTimer;
    private volatile boolean rebuildRunning = false;
    private volatile boolean rebuildNeeded = false;
    private boolean suppressFilterApplyEvents = false;
    private VariantFilter pendingReloadFilter;
    private VariantTable variantTable;

    // ── Initialization ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        filterDebounceTimer = new Timeline(new KeyFrame(Duration.millis(200), e -> applyFiltersNow()));
        filterDebounceTimer.setCycleCount(1);
        immediateFilterApplyTimer = new Timeline(new KeyFrame(Duration.millis(40), e -> applyFiltersNow()));
        immediateFilterApplyTimer.setCycleCount(1);

        sampleComparisonPanel = new SampleComparisonPanel();
        sampleComparisonPanel.install(
            new SampleComparisonPanel.Nodes(
                sharedSampleRangeSlider,
                geneLevelComparisonCheckBox,
                comparisonWindowField,
                commonVariantsHelpLabel,
                comparisonGroupsContainer,
                groupComparisonSummaryLabel,
                refreshComparisonGroupsButton,
                presentMatchAllRadio,
                presentMatchAnyRadio),
            this::scheduleFilterUpdate,
            this::scheduleImmediateFilterApply,
            () -> suppressFilterApplyEvents);

        variantFiltersPanel = new VariantFiltersPanel();
        variantFiltersPanel.install(
            new VariantFiltersPanel.Nodes(
                variantTypesContainer,
                selectAllTypesCheckBox,
                selectAllEffectsCheckBox,
                effectCategoriesContainer,
                qualitySlider,
                coverageSlider,
                alleleFreqSlider,
                qualityField,
                coverageField,
                alleleFreqField,
                qualityValueLabel,
                coverageValueLabel,
                alleleFreqValueLabel,
                cancerOnlyCheckBox,
                advancedFiltersContainer,
                addInfoFilterButton,
                addFilterFieldButton,
                reloadBanner,
                reloadBannerLabel,
                reloadBannerButton),
            this::scheduleFilterUpdate,
            this::scheduleImmediateFilterApply,
            () -> suppressFilterApplyEvents);

        controlFilesPanel = new ControlFilesPanel();
        controlFilesPanel.install(
            new ControlFilesPanel.Nodes(
                filterByPopFreqCheckBox,
                useGnomadCheckBox,
                use1000GenomesCheckBox,
                useExacCheckBox,
                maxPopFreqField,
                showPathogenicCheckBox,
                hideBenignCheckBox,
                controlFilesTable,
                controlFileEnabledColumn,
                controlFileNameColumn,
                controlFileTypeColumn,
                controlFileActionsColumn));

        agentPanel = new AgentPanel();
        agentPanel.install(
            new AgentPanel.Nodes(
                filterTabPane,
                resultsTabPane,
                agentTab,
                apiKeyField,
                agentModelField,
                agentPromptArea,
                agentResponseArea,
                agentStatusLabel,
                agentSubmitButton),
            this::buildVariantContext);

        busyOverlay = new VariantBusyOverlay();
        busyOverlay.install(
            new VariantBusyOverlay.Nodes(
                loadingModal,
                loadingSpinner,
                loadingLabel,
                loadingProgressBar,
                loadingEtaLabel,
                loadingCancelButton));
        busyOverlay.setLockTargets(
            new VariantBusyOverlay.LockTargets(
                filterTabPane,
                annotateAllChromosomesButton,
                reloadBannerButton));
        busyOverlay.setOnCancel(this::handleCancelLoadingModal);

        initializeVariantTable();
        if (tableSearchField != null) {
            tableSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (variantTable != null) {
                    variantTable.setTableSearchQuery(newVal);
                }
            });
        }
    }

    /**
     * Called after FXML initialization to set up the dialog with VcfManager.
     */
    public void setup(Stage stage, VcfManager vcfManager, Runnable onClose) {
        this.stage = stage;
        this.vcfManager = vcfManager;

        // Load current filter state into UI
        VariantFilter currentFilter = vcfManager.getCurrentFilter();
        loadFilterState(currentFilter);

        // Populate variant type filters dynamically
        VariantFilter typeFilter = vcfManager.getCurrentLoadedFilter();
        if (typeFilter == null) {
            typeFilter = vcfManager.getCurrentFilter();
        }
        if (variantFiltersPanel != null) {
            variantFiltersPanel.populateVariantTypes(sourceVariantLists, typeFilter);
        }

        // Set up listeners
        vcfManager.setOnVcfAdded(this::loadData);
        updateListener = (obs, oldVal, newVal) -> Platform.runLater(this::loadData);
        GenomicCanvas.update.addListener(updateListener);

        // Load initial data
        loadData();

        syncSharedSampleRangeBounds();
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        registry.getSampleTracks().addListener((javafx.collections.ListChangeListener<SampleTrack>) c ->
            Platform.runLater(() -> {
                syncSharedSampleRangeBounds();
                if (sampleComparisonPanel != null) sampleComparisonPanel.refreshGroups();
            }));
        registry.sampleGroupsRevisionProperty().addListener((obs, oldVal, newVal) ->
            Platform.runLater(() -> {
                if (sampleComparisonPanel != null) sampleComparisonPanel.refreshGroups();
            }));

        if (filterTabPane != null) {
            filterTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab != null && "Sample Comparison".equals(newTab.getText())
                    && sampleComparisonPanel != null) {
                    sampleComparisonPanel.refreshGroups();
                }
            });
        }

        setupWindowVisibilityListeners();
        syncBusyOverlay();

        // Keep a balanced workspace: filters on top, tables below.
        Platform.runLater(() -> {
            if (mainSplitPane != null) {
                mainSplitPane.setDividerPositions(0.5);
            }
            if (variantFiltersSplitPane != null) {
                variantFiltersSplitPane.setDividerPositions(0.5);
            }
            if (sampleComparisonSplitPane != null) {
                sampleComparisonSplitPane.setDividerPositions(0.5);
            }
            if (controlFilesSplitPane != null) {
                controlFilesSplitPane.setDividerPositions(0.5);
            }
            handleHostWindowStateChanged();
        });
    }

    /**
     * Update VcfManager reference when window is reused (singleton behavior).
     */
    public void updateVcfManager(VcfManager vcfManager) {
        this.vcfManager = vcfManager;
        vcfManager.setOnVcfAdded(this::loadData);
        loadData();
    }

    public void cleanup() {
        if (annotationThread != null && annotationThread.isAlive()) {
            annotationThread.interrupt();
        }
        ThreadRunner.RunnerTask task = busyOverlay != null
            ? busyOverlay.getAllChromosomeAnnotationTask()
            : null;
        if (task != null && !task.isCompleted()) {
            task.cancel();
        }
        if (updateListener != null) {
            GenomicCanvas.update.removeListener(updateListener);
        }
        if (busyOverlay != null) {
            busyOverlay.cancelDelayedLoadingModal();
        }
        vcfManager.clearFilter();
        vcfManager.setOnVcfAdded(null);
        ServiceRegistry.getInstance().getSampleRegistry().clearSubsetSource(SampleRegistry.SubsetSource.GENE_FOCUS);
				MinimizedVariantManagerWindow.handleCleanup();
    }

    public void clearBatchAnnotationResults() {
        if (busyOverlay != null) {
            busyOverlay.setAllChromosomeAnnotationTask(null);
        }
        lastSeenVariantsRevision = -1;
        sourceVariants = null;
        sourceVariantLists = List.of();
        variantTable().setZeroTabCounts();
        
        setTableItems(
            FXCollections.<VariantTable.TableRow>observableArrayList(),
            FXCollections.<VariantTable.TableRow>observableArrayList(),
            FXCollections.<VariantTable.TableRow>observableArrayList());
    }

    /** Re-sync checkboxes from {@link VcfManager#getCurrentFilter()} after Clear All. */
    public void reloadFilterUiFromManager() {
        if (vcfManager == null) {
            return;
        }
        loadFilterState(vcfManager.getCurrentFilter());
    }

    /**
     * New Project / Clear All: wipe dynamic type checkboxes and restore pass-all filter UI
     * so the next VCF is not hidden by the previous project's type selections.
     */
    public void resetToProjectDefaults() {
        clearBatchAnnotationResults();
        cancelPendingFilterTimers();
        pendingReloadFilter = null;
        if (variantFiltersPanel != null) {
            variantFiltersPanel.hideReloadBanner();
            variantFiltersPanel.getVariantTypeCheckBoxes().clear();
            variantFiltersPanel.clearEffectCategoryCheckBoxes();
        }
        if (variantTypesContainer != null) {
            variantTypesContainer.getChildren().clear();
        }
        if (effectCategoriesContainer != null) {
            effectCategoriesContainer.getChildren().clear();
        }
        if (selectAllTypesCheckBox != null) {
            selectAllTypesCheckBox.setSelected(true);
        }
        if (selectAllEffectsCheckBox != null) {
            selectAllEffectsCheckBox.setSelected(true);
        }

        VariantFilter defaults = vcfManager != null
            ? vcfManager.getCurrentFilter()
            : new VariantFilter();
        if (defaults == null) {
            defaults = new VariantFilter();
        }
        loadFilterState(defaults);
        if (tableSearchField != null) {
            tableSearchField.clear();
        }
        if (variantTable != null) {
            variantTable.setTableSearchQuery("");
        }
        clearTableItemsForChromosomeSwitch();
        setPlaceholder("Open a VCF to see variants");
    }

    /** Snapshot of the filter currently expressed by the Variant Manager UI. */
    public VariantFilter snapshotUiFilter() {
        return buildFilterFromUI();
    }

    public void handleMinimize() {
        MinimizedVariantManagerWindow.handleMinimize(stage);
    }

    public void handleExpandMinimized() {
        MinimizedVariantManagerWindow.handleExpand();
    }

    private void syncSharedSampleRangeBounds() {
        if (sampleComparisonPanel == null) return;
        boolean previousSuppress = suppressFilterApplyEvents;
        suppressFilterApplyEvents = true;
        try {
            sampleComparisonPanel.syncSharedSampleRangeBounds();
        } finally {
            suppressFilterApplyEvents = previousSuppress;
        }
    }

    /**
     * Called when a VCF file visibility checkbox changes so the common-variant
     * slider max and filtered table match currently visible VCF tracks.
     */
    public static void notifySampleVisibilityChanged() {
        VariantManagerController controller = VariantManagerWindow.getCurrentController();
        if (controller == null) {
            return;
        }
        Platform.runLater(() -> {
            controller.syncSharedSampleRangeBounds();
            if (!controller.suppressFilterApplyEvents) {
                controller.scheduleFilterUpdate();
            }
        });
    }

    /** Sync checkboxes when allowed types are toggled outside the Variant Manager UI. */
    public static void syncFilterUiFromExternal(VariantFilter filter) {
        VariantManagerController controller = VariantManagerWindow.getCurrentController();
        if (controller == null || filter == null) {
            return;
        }
        Platform.runLater(() -> controller.loadFilterState(filter));
    }

    @FXML
    private void handleRefreshComparisonGroups() {
        if (sampleComparisonPanel != null) sampleComparisonPanel.refreshGroups();
    }

    /**
     * Schedule a debounced filter update. Restarts the timer on each call,
     * so rapid slider movements only trigger one update 200ms after the last change.
     */
    private void scheduleFilterUpdate() {
        if (suppressFilterApplyEvents || isAllChromosomeAnnotationRunning()) {
            return;
        }
        if (filterDebounceTimer != null) {
            filterDebounceTimer.stop();
            filterDebounceTimer.playFromStart();
        }
    }

    private void scheduleImmediateFilterApply() {
        if (suppressFilterApplyEvents || isAllChromosomeAnnotationRunning()) {
            return;
        }
        Platform.requestNextPulse();
        if (immediateFilterApplyTimer != null) {
            immediateFilterApplyTimer.stop();
            immediateFilterApplyTimer.playFromStart();
        } else {
            applyFiltersNow();
        }
    }

    private boolean isAllChromosomeAnnotationRunning() {
        return busyOverlay != null && busyOverlay.isAllChromosomeAnnotationRunning();
    }

    // ── Table Setup ───────────────────────────────────────────────────────────

    private void initializeVariantTable() {
        variantTable = new VariantTable(
            codingTable,
            intronicTable,
            intergenicTable,
            codingTab,
            intronicTab,
            intergenicTab,
            this::handleGeneRowDoubleClick,
            this::handlePositionClick);

        variantTable.initializeColumns();
    }

    private VariantTable variantTable() {
        if (variantTable == null) {
            initializeVariantTable();
        }
        return variantTable;
    }

    private void handleGeneRowDoubleClick(VariantTable.TableRow row) {
        if (row == null || row.node() == null) {
            return;
        }

        String geneName = VariantTable.rowGeneName(row);
        if (geneName == null || geneName.isBlank()) {
            return;
        }

        navigateAndApplySampleFilterForGene(geneName);
    }

    public void handlePositionClick(VariantTable.TableRow row) {
        if (row == null || row.node() == null) {
            return;
        }

        VariantNode node = row.node();
        String rowChromosome = row.chromosome();
        if (rowChromosome == null || rowChromosome.isBlank()) {
            rowChromosome = chromosome;
        }

        long position = node.position;
        long minViewLength = 40;
        long start = Math.max(1, position - minViewLength / 2);
        long end = position + minViewLength / 2;

        // Make effectively final for lambda
        final String navChromosome = rowChromosome;
        final long navStart = start;
        final long navEnd = end;

        navigateAndApplySampleFilter(
            () -> NavigationCommands.navigateToPosition(navChromosome, (int)navStart, (int)navEnd),
            resolveTracksFromCalls(node.getSamples()),
            "Position:" + position);
    }

    /**
     * Resolve unique sample tracks from per-variant sample calls.
     */
    private List<SampleTrack> resolveTracksFromCalls(java.util.List<VariantNode.SampleCall> samples) {
        if (samples == null || samples.isEmpty()) {
            return Collections.emptyList();
        }

        Set<SampleTrack> uniqueTracks = new LinkedHashSet<>();

        for (VariantNode.SampleCall call : samples) {
            if (call.getTrack() != null) {
                uniqueTracks.add(call.getTrack());
            }
        }
        return new ArrayList<>(uniqueTracks);
    }

    private List<SampleTrack> extractSamplesWithGeneVariants(String geneName) {
        if (geneName == null || geneName.isBlank()) {
            return Collections.emptyList();
        }

        VariantFilter filter = resolveActiveDisplayFilter();
        Set<SampleTrack> tracksWithGeneVariant = new LinkedHashSet<>();

        // Prefer Variant Manager's currently loaded/annotated chromosome lists.
        List<VcfManager.CachedChromosomeVariants> sources = sourceVariantLists;
        if (sources == null || sources.isEmpty()) {
            sources = getCachedVariantSources();
        }
        if (sources != null) {
            for (VcfManager.CachedChromosomeVariants cached : sources) {
                collectTracksForGene(cached.variants(), geneName, filter, tracksWithGeneVariant);
            }
        }

        // Fallback: gene chromosome cache (may be unannotated if never opened).
        if (tracksWithGeneVariant.isEmpty()) {
            GeneLocation geneLoc = AnnotationData.getGeneLocation(geneName);
            if (geneLoc != null) {
                VariantList variantList = VcfManager.getInstance().getCachedVariants(geneLoc.chrom());
                collectTracksForGene(variantList, geneName, filter, tracksWithGeneVariant);
            }
        }

        return new ArrayList<>(tracksWithGeneVariant);
    }

    private VariantFilter resolveActiveDisplayFilter() {
        if (vcfManager != null && vcfManager.getCurrentFilter() != null) {
            return vcfManager.getCurrentFilter();
        }
        return buildFilterFromUI();
    }

    private static void collectTracksForGene(
            VariantList variantList,
            String geneName,
            VariantFilter filter,
            Set<SampleTrack> out) {
        if (variantList == null || variantList.isEmpty() || geneName == null || out == null) {
            return;
        }
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<SampleTrack> allTracks = registry.getSampleTracks();
        Set<Integer> trackIndices = variantList.getGeneSampleTracks(geneName, filter);
        for (Integer trackIndex : trackIndices) {
            if (trackIndex == null || trackIndex < 0 || trackIndex >= allTracks.size()) {
                continue;
            }
            out.add(allTracks.get(trackIndex));
        }
    }

    private void navigateAndApplySampleFilterForGene(String geneName) {
        // Extract from currently loaded/annotated data BEFORE navigation, which
        // switches chromosome and starts an async region load that would otherwise
        // leave the cache empty or unannotated at extract time.
        List<SampleTrack> tracks = extractSamplesWithGeneVariants(geneName);
        navigateAndApplySampleFilter(
            () -> NavigationCommands.navigateToGene(geneName, true),
            tracks,
            geneName);
    }

    /**
     * Navigate to a location and apply sample filtering.
     * Accepts sample objects directly; SampleRegistry handles track index extraction.
     *
     * @param navigationAction The navigation action to perform
     * @param tracksWithFeature List of sample tracks containing the feature (may be null for gene filtering)
     * @param featureName Human-readable name of the feature for the sample subset
     */
    private void navigateAndApplySampleFilter(
            Runnable navigationAction,
             List<SampleTrack> tracksWithFeature,
            String featureName) {

        List<SampleTrack> finalTracks = tracksWithFeature;
        if ((finalTracks == null || finalTracks.isEmpty())
            && featureName != null
            && !featureName.isBlank()
            && !featureName.startsWith("Position:")) {
            finalTracks = extractSamplesWithGeneVariants(featureName);
        }

        // Perform the navigation after extracting tracks from current caches.
        navigationAction.run();

        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        if (finalTracks != null && !finalTracks.isEmpty()) {
            registry.applyGeneSubset(finalTracks, featureName);

            int displayedCount = registry.getDisplayedTrackCount();
            if (displayedCount > 0) {
                double viewportHeight = estimateSampleViewportHeight(registry);
                registry.setVisibleSamples(0, displayedCount - 1, viewportHeight);
            }
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
        MinimizedVariantManagerWindow.handleMinimize(stage);
    }

    private double estimateSampleViewportHeight(SampleRegistry registry) {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (!stackManager.isEmpty() && stackManager.getFirst().sampleTrackCanvas != null) {
            double fromCanvas = stackManager.getFirst().sampleTrackCanvas.getHeight();
            if (fromCanvas > 0) {
                return fromCanvas;
            }
        }
        double derived = registry.getSampleHeight() * Math.max(1, registry.getVisibleSampleCount());
        return Math.max(0, derived);
    }

    private void setTableItems(
        ObservableList<VariantTable.TableRow> codingItems,
        ObservableList<VariantTable.TableRow> intronicItems,
        ObservableList<VariantTable.TableRow> intergenicItems) {

        variantTable().setItems(codingItems, intronicItems, intergenicItems);
    }

    private void setTablePlaceholders(Node codingPlaceholder, Node intronicPlaceholder, Node intergenicPlaceholder) {
        variantTable().setPlaceholders(codingPlaceholder, intronicPlaceholder, intergenicPlaceholder);
    }

    // ── Filter State Management ───────────────────────────────────────────────

    private void loadFilterState(VariantFilter filter) {
        suppressFilterApplyEvents = true;
        try {
            if (variantFiltersPanel != null) {
                variantFiltersPanel.loadFrom(filter);
            }
            if (sampleComparisonPanel != null) {
                sampleComparisonPanel.loadFrom(filter);
            }
        } finally {
            suppressFilterApplyEvents = false;
            cancelPendingFilterTimers();
            pendingReloadFilter = null;
            if (variantFiltersPanel != null) {
                variantFiltersPanel.hideReloadBanner();
            }
        }
    }

    private void cancelPendingFilterTimers() {
        if (filterDebounceTimer != null) {
            filterDebounceTimer.stop();
        }
        if (immediateFilterApplyTimer != null) {
            immediateFilterApplyTimer.stop();
        }
    }

    private VariantFilter buildFilterFromUI() {
        VariantFilter filter = new VariantFilter();
        if (variantFiltersPanel != null) {
            variantFiltersPanel.writeTo(filter);
        }
        if (sampleComparisonPanel != null) {
            sampleComparisonPanel.writeTo(filter);
        }
        return filter;
    }

    // ── Action Handlers ───────────────────────────────────────────────────────

    @FXML
    private void handleAnnotateAllChromosomes() {
        if (vcfManager == null || isAllChromosomeAnnotationRunning()) {
            return;
        }

        List<String> chromosomes = getReferenceChromosomeOrder();
        if (chromosomes.isEmpty()) {
            setPlaceholder("No chromosomes available in dropdown");
            return;
        }

        busyOverlay.cancelDelayedLoadingModal();
        busyOverlay.setAllChromosomeAnnotationRunning(true);
        busyOverlay.setAllChromosomeAnnotationTask(null);
        busyOverlay.lockFilterControls(true);
        busyOverlay.syncBusyOverlay();

        Platform.runLater(() -> {
            VariantFilter filterSnapshot = buildFilterFromUI();
            ThreadRunner.RunnerTask task = vcfManager.annotateAllReferenceChromosomes(
                filterSnapshot,
                chromosomes,
                progress -> busyOverlay.updateProgress(progress),
                this::completeAllChromosomeAnnotation);
            busyOverlay.setAllChromosomeAnnotationTask(task);

            if (task != null) {
                task.setProgressSuffix(
                    "0/" + chromosomes.size() + " chromosomes, rows: 0");
                ThreadRunner.get().notifyDescriptionChanged();
            } else {
                busyOverlay.setAllChromosomeAnnotationRunning(false);
                busyOverlay.lockFilterControls(false);
                busyOverlay.hide();
                setPlaceholder("No chromosomes available for annotation");
            }
        });
    }

    @FXML
    private void handleCancelLoadingModal() {
        ThreadRunner.RunnerTask task = busyOverlay != null
            ? busyOverlay.getAllChromosomeAnnotationTask()
            : null;
        if (task != null) {
            task.cancel();
        } else {
            ThreadRunner.get().cancelAll();
        }
        if (busyOverlay != null) {
            busyOverlay.setAllChromosomeAnnotationRunning(false);
            busyOverlay.setAllChromosomeAnnotationTask(null);
            busyOverlay.lockFilterControls(false);
            busyOverlay.hide();
        }
    }

    private void applyFiltersNow() {
        if (suppressFilterApplyEvents || isAllChromosomeAnnotationRunning()) {
            return;
        }

        VariantFilter filter = buildFilterFromUI();

        if (vcfManager != null) {
            vcfManager.setCurrentFilterForNextLoad(filter);
            vcfManager.applyFilter(filter);
        }

        boolean reloadNeeded = variantFiltersPanel != null
            && variantFiltersPanel.anyFilterLooserThanSnapshot();
        pendingReloadFilter = reloadNeeded ? filter : null;
        if (variantFiltersPanel != null) {
            variantFiltersPanel.refreshReloadBannerState(reloadNeeded, "Reload needed");
        }
        scheduleRebuild(filter);
    }

    @FXML
    private void handleReloadFilteredVariants() {
        if (vcfManager == null || isAllChromosomeAnnotationRunning()) {
            return;
        }
        VariantFilter target = pendingReloadFilter != null ? pendingReloadFilter : buildFilterFromUI();
        pendingReloadFilter = null;
        if (variantFiltersPanel != null) {
            variantFiltersPanel.hideReloadBanner();
        }

        List<String> chromosomes = vcfManager.getCachedChromosomesInOrder(getReferenceChromosomeOrder());
        if (chromosomes.isEmpty() && chromosome != null && !chromosome.isBlank()) {
            chromosomes = List.of(chromosome);
        }
        if (chromosomes.isEmpty()) {
            return;
        }

        sourceVariants = null;
        clearTableItemsForChromosomeSwitch();
        setPlaceholder("Reloading variants…");
        vcfManager.setCurrentFilterForNextLoad(target);
        vcfManager.markAllCachedVariantListsDirty();

        busyOverlay.cancelDelayedLoadingModal();
        busyOverlay.setAllChromosomeAnnotationRunning(true);
        busyOverlay.setAllChromosomeAnnotationTask(null);
        busyOverlay.lockFilterControls(true);
        busyOverlay.syncBusyOverlay();

        final VariantFilter filterSnapshot = target.copy();
        final List<String> cachedChroms = List.copyOf(chromosomes);
        Platform.runLater(() -> {
            ThreadRunner.RunnerTask task = vcfManager.annotateAllReferenceChromosomes(
                filterSnapshot,
                cachedChroms,
                progress -> busyOverlay.updateProgress(progress),
                this::completeAllChromosomeAnnotation);
            busyOverlay.setAllChromosomeAnnotationTask(task);

            if (task != null) {
                task.setProgressSuffix(
                    "0/" + cachedChroms.size() + " chromosomes, rows: 0");
                ThreadRunner.get().notifyDescriptionChanged();
            } else {
                busyOverlay.setAllChromosomeAnnotationRunning(false);
                busyOverlay.lockFilterControls(false);
                busyOverlay.hide();
                setPlaceholder("No cached chromosomes to reload");
            }
        });
    }

    @FXML
    private void handleApplyComparison() {
        scheduleImmediateFilterApply();
    }

    @FXML
    private void handleApplyControlSettings() {
        if (controlFilesPanel != null) {
            controlFilesPanel.handleApplyControlSettings();
        }
    }

    @FXML
    private void handleAddControlVcf() {
        if (controlFilesPanel != null) {
            controlFilesPanel.handleAddControlVcf();
        }
    }

    @FXML
    private void handleAddControlBed() {
        if (controlFilesPanel != null) {
            controlFilesPanel.handleAddControlBed();
        }
    }

    @FXML
    private void handleRemoveControlFile() {
        if (controlFilesPanel != null) {
            controlFilesPanel.handleRemoveControlFile();
        }
    }

    @FXML
    private void handleAddInfoFilter() {
        if (variantFiltersPanel != null) {
            variantFiltersPanel.showInfoFilterDialog();
        }
    }

    @FXML
    private void handleAddFilterField() {
        if (variantFiltersPanel != null) {
            variantFiltersPanel.showFilterFieldDialog();
        }
    }

    @FXML
    private void handleAgentSubmit() {
        if (agentPanel != null) {
            agentPanel.handleAgentSubmit();
        }
    }

    // ── Agent (AI Analysis) ───────────────────────────────────────────────────

    private String buildVariantContext() {
        List<VcfManager.CachedChromosomeVariants> cachedSources = getCachedVariantSources();
        if (cachedSources.isEmpty()) {
            return "No variants currently loaded.";
        }

        int totalVariants = 0;
        for (VcfManager.CachedChromosomeVariants cached : cachedSources) {
            VariantList variants = cached.variants();
            if (variants != null) {
                totalVariants += variants.size();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Cached chromosomes: ").append(cachedSources.size()).append("\n");
        sb.append("Total variants: ").append(totalVariants).append("\n\n");
        sb.append("Variant list (chromosome:position, ref→alt, type, gene, effect, maxGQ):\n");

        VariantFilter filter = vcfManager.getCurrentFilter();
        int count = 0;
        for (VcfManager.CachedChromosomeVariants cached : cachedSources) {
            if (count >= 300) {
                break;
            }
            String sourceChromosome = cached.chromosome();
            VariantList variants = cached.variants();
            if (variants == null || variants.isEmpty()) {
                continue;
            }

            VariantNode node = variants.getFirst();
            while (node != null && count < 300) {
                double maxGq = -1;
                boolean passes = false;
                for (VariantNode.SampleCall call : node.getSamples()) {
                    if (filter.passes(node, call)) {
                        passes = true;
                        if (call.quality > maxGq) maxGq = call.quality;
                    }
                }

                if (passes) {
                    VariantAnnotation ann = node.annotation;
                    String gene = (ann != null && ann.geneName() != null) ? ann.geneName() : "-";
                    String effect = ann != null ? ann.effect().displayName() : "intergenic";
                    sb.append(sourceChromosome).append(":").append(node.position)
                        .append("\t").append(node.ref).append("→").append(node.alt.isEmpty() ? "." : node.alt)
                        .append("\t").append(typeLabel(node.type))
                        .append("\tgene=").append(gene)
                        .append("\teffect=").append(effect)
                        .append("\tGQ=").append(maxGq >= 0 ? String.format("%.0f", maxGq) : "-")
                        .append("\n");
                    count++;
                }
                node = node.next;
            }
        }
        if (count == 300) sb.append("... (truncated to 300 variants)\n");
        return sb.toString();
    }

    // ── Data Loading ──────────────────────────────────────────────────────────

    private void loadData() {
        if (vcfManager == null) {
            return;
        }

        if (isAllChromosomeAnnotationRunning()) {
            return;
        }

        // A non-manual region/gene navigation should not trigger table autoscroll.
        if (!vcfManager.wasLastLoadManualChromosomeSelection()) {
            pendingScrollToChromosome = null;
        }

        String activeChromosome = vcfManager.getLastLoadedChromosome();
        boolean chromosomeChanged = !Objects.equals(chromosome, activeChromosome);
        if (chromosomeChanged) {
            chromosome = activeChromosome;
            lastSeenVariantsRevision = -1;
            pendingScrollToChromosome = null;
        }

        if (activeChromosome != null
            && !activeChromosome.isBlank()
            && vcfManager.consumeManualChromosomeScrollRequest(activeChromosome)) {
            pendingScrollToChromosome = activeChromosome;
        }

        // Get the current variant list from VcfManager and rebuild tables if it changed
        VariantList fresh = vcfManager.getCachedVariants();
        long revision = vcfManager.getVariantsRevision();
        
        // Skip rebuild if variant list and revision have not changed.
        if (fresh == sourceVariants && revision == lastSeenVariantsRevision) {
            if (pendingScrollToChromosome != null && !pendingScrollToChromosome.isBlank()) {
                final String scrollTarget = pendingScrollToChromosome;
                Platform.runLater(() -> {
                    boolean scrolled = variantTable().scrollToFirstVariantForChromosome(scrollTarget);
                    if (scrolled && Objects.equals(pendingScrollToChromosome, scrollTarget)) {
                        pendingScrollToChromosome = null;
                    }
                });
            }
            return;
        }
        
        sourceVariants = fresh;
        lastSeenVariantsRevision = revision;
        sourceVariantLists = getCachedVariantSources();

        if (sourceVariantLists.isEmpty()) {
            clearTableItemsForChromosomeSwitch();
            setPlaceholder("No variants available");
            refreshReloadBannerState();
            if (!isCurrentChromosomeStillLoading()) {
                pendingScrollToChromosome = null;
            }
            return;
        }

        if (isCurrentChromosomeStillLoading()) {
            // Progressive loads can trigger many update events; defer expensive full table rebuild
            // and full variant-type scans until the chromosome load has completed.
            rebuildNeeded = true;
            if (busyOverlay != null) {
                busyOverlay.cancelDelayedLoadingModal();
            }
            return;
        }

        // Update variant type filters after load completes to avoid repeated full-list scans
        // during progressive loading.
        VariantFilter typeFilter = vcfManager.getCurrentLoadedFilter();
        if (typeFilter == null) {
            typeFilter = vcfManager.getCurrentFilter();
        }
        if (variantFiltersPanel != null) {
            variantFiltersPanel.populateVariantTypes(sourceVariantLists, typeFilter);
            // Snapshot min Q/DP/AF from the filter used to materialize the cache.
            variantFiltersPanel.captureFilterSnapshot(typeFilter);
        }

        refreshReloadBannerState();
        scheduleRebuild(vcfManager.getCurrentFilter());

        if (!annotationRunning
            && chromosome != null
            && !chromosome.isBlank()
            && !vcfManager.isAnnotated(chromosome)
            && !isCurrentChromosomeStillLoading()) {
            annotationRunning = true;
            final String capturedChrom = chromosome;
            final VariantList capturedVariants = sourceVariants;
            annotationThread = new Thread(() -> {
                boolean interrupted = Thread.currentThread().isInterrupted();
                if (!interrupted) {
                    try {
                        vcfManager.ensureAnnotated(capturedChrom);
                    } catch (Throwable ignored) {
                        // Keep table refreshes alive even if annotation fails.
                    }
                }

                Platform.runLater(() -> {
                    annotationRunning = false;
                    if (interrupted) {
                        return;
                    }
                    if (!Objects.equals(capturedChrom, chromosome) || capturedVariants != sourceVariants) {
                        loadData();
                        return;
                    }
                    scheduleRebuild(vcfManager.getCurrentFilter());
                });
            }, "variant-annotator");
            annotationThread.setDaemon(true);
            annotationThread.start();
        }
    }

    private void scheduleRebuild(VariantFilter filter) {
        rebuildNeeded = true;
        if (isCurrentChromosomeStillLoading()) {
            return;
        }
        if (!rebuildRunning) rebuildTables(filter);
    }

    private void rebuildTables(VariantFilter filter) {
        if (sourceVariantLists == null || sourceVariantLists.isEmpty()) {
            rebuildNeeded = false;
            if (busyOverlay != null) {
                busyOverlay.cancelDelayedLoadingModal();
            }
            return;
        }
        final List<VcfManager.CachedChromosomeVariants> snapshots = new ArrayList<>(sourceVariantLists);
        final VariantFilter filterSnapshot;
        try {
            filterSnapshot = filter == null ? new VariantFilter() : filter.copy();
        } catch (RuntimeException ex) {
            rebuildNeeded = false;
            if (busyOverlay != null) {
                busyOverlay.cancelDelayedLoadingModal();
            }
            return;
        }
        rebuildRunning = true;
        rebuildNeeded = false;

        Thread buildThread = new Thread(() -> {
            List<VariantTable.TableRow> coding = new ArrayList<>();
            List<VariantTable.TableRow> intronic = new ArrayList<>();
            List<VariantTable.TableRow> intergenic = new ArrayList<>();

            try {
                for (VcfManager.CachedChromosomeVariants cached : snapshots) {
                    String sourceChromosome = cached.chromosome();
                    VariantList variants = cached.variants();
                    if (variants == null || variants.isEmpty()) {
                        continue;
                    }

                    VariantNode node = variants.getFirst();
                    Map<String, Set<Integer>> geneTracks = filterSnapshot.isGeneLevel()
                        ? variants.ensureGeneSampleIndex(filterSnapshot)
                        : null;
                    Map<VariantNode, Set<Integer>> clusterTracks =
                        !filterSnapshot.isGeneLevel() && filterSnapshot.hasComparisonWindow()
                            ? variants.ensureClusterSampleIndex(filterSnapshot)
                            : null;
                    while (node != null) {
                        Set<Integer> aggregatedTracks = null;
                        if (geneTracks != null) {
                            String gene = node.annotation != null && node.annotation.geneName() != null
                                ? node.annotation.geneName().trim().toLowerCase(java.util.Locale.ROOT)
                                : null;
                            aggregatedTracks = gene != null && !gene.isEmpty()
                                ? geneTracks.getOrDefault(gene, Set.of())
                                : Set.of();
                        } else if (clusterTracks != null) {
                            aggregatedTracks = clusterTracks.getOrDefault(node, Set.of());
                        }
                        if (!filterSnapshot.passesNodeLevel(node, aggregatedTracks)) {
                            node = node.next;
                            continue;
                        }

                        int passSamples = 0;
                        for (VariantNode.SampleCall call : node.getSamples()) {
                            if (filterSnapshot.passesSampleThresholds(node, call)) {
                                passSamples++;
                            }
                        }
                        if (passSamples > 0) {
                            VariantAnnotation ann = node.annotation;
                            VariantEffect effect = ann != null ? ann.effect() : VariantEffect.INTERGENIC;
                            VariantTable.TableRow row = new VariantTable.TableRow(sourceChromosome, node);
                            if (effect.isCoding() || effect.isSpliceSite() || effect.isRegulatory()) {
                                // Gene tab: coding, splice sites, UTR, and non-coding genes
                                coding.add(row);
                            } else if (effect.isIntronic()) {
                                // Intronic tab: only true intronic
                                intronic.add(row);
                            } else {
                                intergenic.add(row);
                            }
                        }
                        node = node.next;
                    }
                }
            } catch (Throwable t) {
                Platform.runLater(() -> {
                    rebuildRunning = false;
                    if (busyOverlay != null) {
                        busyOverlay.cancelDelayedLoadingModal();
                    }
                    // Retry if new data arrived while this build was running
                    if (rebuildNeeded) scheduleRebuild(vcfManager.getCurrentFilter());
                });
                return;
            }

            Platform.runLater(() -> {
                rebuildRunning = false;
                variantTable().setDisplayContext(filterSnapshot);
                setTableItems(
                    FXCollections.observableArrayList(coding),
                    FXCollections.observableArrayList(intronic),
                    FXCollections.observableArrayList(intergenic));

                if (coding.isEmpty() && intronic.isEmpty() && intergenic.isEmpty()) {
                    setPlaceholder("No variants match current filter settings");
                } else {
                    setTablePlaceholders(null, null, null);
                }

                if (pendingScrollToChromosome != null && !pendingScrollToChromosome.isBlank()) {
                    final String scrollTarget = pendingScrollToChromosome;
                    Platform.runLater(() -> {
                        boolean scrolled = variantTable().scrollToFirstVariantForChromosome(scrollTarget);
                        if (scrolled && Objects.equals(pendingScrollToChromosome, scrollTarget)) {
                            pendingScrollToChromosome = null;
                        }
                    });
                }

                if (busyOverlay != null) {
                    busyOverlay.cancelDelayedLoadingModal();
                }
                // Retry if new data arrived while this build was running
                if (rebuildNeeded) scheduleRebuild(vcfManager.getCurrentFilter());
            });
        }, "variant-table-build");
        buildThread.setDaemon(true);
        buildThread.start();
    }

    private boolean isCurrentChromosomeStillLoading() {
        return chromosome != null && !chromosome.isBlank() && vcfManager.isLoadingChromosome(chromosome);
    }

    private List<String> getReferenceChromosomeOrder() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (!stackManager.isEmpty()) {
            org.baseplayer.draw.DrawStack drawStack = stackManager.getFirst();
            if (drawStack != null && drawStack.chromosomeDropdown != null) {
                return new ArrayList<>(drawStack.chromosomeDropdown.getItems());
            }
        }
        return List.of();
    }

    private List<VcfManager.CachedChromosomeVariants> getCachedVariantSources() {
        if (vcfManager == null) {
            return List.of();
        }
        return vcfManager.getCachedVariantListsInOrder(getReferenceChromosomeOrder());
    }

    private void setPlaceholder(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: " + TEXT + ";");
        setTablePlaceholders(lbl, new Label(""), new Label(""));
        variantTable().setBaseTabTitles();
    }

    private void setupWindowVisibilityListeners() {
        if (stage != null) {
            stage.focusedProperty().addListener((obs, oldVal, newVal) -> handleHostWindowStateChanged());
            stage.iconifiedProperty().addListener((obs, oldVal, newVal) -> handleHostWindowStateChanged());
            stage.showingProperty().addListener((obs, oldVal, newVal) -> handleHostWindowStateChanged());
        }
        if (MainApp.stage != null) {
            MainApp.stage.focusedProperty().addListener((obs, oldVal, newVal) -> handleHostWindowStateChanged());
            MainApp.stage.iconifiedProperty().addListener((obs, oldVal, newVal) -> handleHostWindowStateChanged());
            MainApp.stage.showingProperty().addListener((obs, oldVal, newVal) -> handleHostWindowStateChanged());
        }
    }

    private void handleHostWindowStateChanged() {
        MenuBarController.updateVariantManagerButtonVisibility();
    }

    private void completeAllChromosomeAnnotation(VcfManager.AllChromosomeAnnotationResult result) {
        ThreadRunner.RunnerTask task = busyOverlay != null
            ? busyOverlay.getAllChromosomeAnnotationTask()
            : null;
        if (task != null) {
            task.setProgressSuffix("");
            ThreadRunner.get().notifyDescriptionChanged();
        }

        if (busyOverlay != null) {
            busyOverlay.setAllChromosomeAnnotationRunning(false);
            busyOverlay.setAllChromosomeAnnotationTask(null);
            busyOverlay.lockFilterControls(false);
            busyOverlay.hide();
        }

        // Annotate-all already materializes every chromosome with the current UI filter,
        // so a leftover "Reload needed" banner from soft filter apply is stale.
        if (result != null && !result.cancelled()) {
            pendingReloadFilter = null;
            if (variantFiltersPanel != null) {
                variantFiltersPanel.hideReloadBanner();
            }
            if (vcfManager != null) {
                VariantFilter filter = buildFilterFromUI();
                vcfManager.setCurrentFilterForNextLoad(filter);
                vcfManager.applyFilter(filter);
            }
        }

        sourceVariants = null;
        lastSeenVariantsRevision = -1;
        if (result != null && !result.cancelled() && result.completedChromosomes() > 0) {
            org.baseplayer.project.ProjectSessionState.get().markDirty();
        }
        loadData();
    }

    public void syncBusyOverlay() {
        if (busyOverlay != null) {
            busyOverlay.syncBusyOverlay();
        }
    }

    private void refreshReloadBannerState() {
        if (variantFiltersPanel != null) {
            variantFiltersPanel.refreshReloadBannerState(
                pendingReloadFilter != null, "Reload needed");
        }
    }

    private void clearTableItemsForChromosomeSwitch() {
        setTableItems(
            FXCollections.<VariantTable.TableRow>observableArrayList(),
            FXCollections.<VariantTable.TableRow>observableArrayList(),
            FXCollections.<VariantTable.TableRow>observableArrayList());
    }

    private static String typeLabel(VcfVariantType type) {
        return switch (type) {
            case SNV -> "SNV";
            case INSERTION -> "Ins";
            case DELETION -> "Del";
            case MNV -> "MNV";
            default -> "Other";
        };
    }
}
