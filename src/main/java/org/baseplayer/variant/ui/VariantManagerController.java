package org.baseplayer.variant.ui;

import org.baseplayer.MainApp;
import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.controllers.MenuBarController;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.VariantTableExcelWriter;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.LoadingManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.ui.components.AgentPanel;
import org.baseplayer.variant.ui.components.ControlFilesPanel;
import org.baseplayer.variant.ui.components.IntegerRangeSlider;
import org.baseplayer.variant.ui.components.SampleComparisonPanel;
import org.baseplayer.variant.ui.components.SvVariantTable;
import org.baseplayer.variant.ui.components.VariantBusyOverlay;
import org.baseplayer.variant.ui.components.VariantClassWorkspace;
import org.baseplayer.variant.ui.components.VariantExcelExportBuilder;
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
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URL;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

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

    @FXML private Tab pointMutationsTab;
    @FXML private Tab structuralVariantsTab;
    @FXML private SplitPane pointMainSplitPane;
    @FXML private SplitPane svMainSplitPane;
    @FXML private VBox svFiltersHost;
    @FXML private TabPane svResultsTabPane;
    @FXML private Tab svAllTab, svDelTab, svDupTab, svInvTab, svTraTab, svBndTab, svInsTab;
    @FXML private TableView<?> svAllTable, svDelTable, svDupTable, svInvTable, svTraTable, svBndTable, svInsTable;

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
    private AgentPanel agentPanel;
    private VariantBusyOverlay busyOverlay;

    /** Point and SV mode bundles (filters + comparison + control + tools + results). */
    private VariantClassWorkspace pointWorkspace;
    private VariantClassWorkspace svWorkspace;

    private TabPane pointToolTabPane;
    private TabPane svToolTabPane;
    private Tab pointFiltersTab;
    private Tab svFiltersTab;
    private Tab pointComparisonTab;
    private Tab pointControlTab;
    private Tab svComparisonTab;
    private Tab svControlTab;

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
    @FXML private SplitPane variantFiltersSplitPane;
    @FXML private SplitPane sampleComparisonSplitPane;
    @FXML private SplitPane controlFilesSplitPane;
    @FXML private TabPane filterTabPane, resultsTabPane;
    // filterTabPane is the outer mode pane (Point | SV). Kept name for FXML compatibility.
    @FXML private Button annotateAllChromosomesButton;
    @FXML private TextField tableSearchField;
    @FXML private Button excelExportButton;
    private Button svAnnotateAllChromosomesButton;
    private TextField svTableSearchField;
    private Button svExcelExportButton;

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
    private SvVariantTable svVariantTable;

    // ── Initialization ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        filterDebounceTimer = new Timeline(new KeyFrame(Duration.millis(200), e -> applyFiltersNow()));
        filterDebounceTimer.setCycleCount(1);
        immediateFilterApplyTimer = new Timeline(new KeyFrame(Duration.millis(40), e -> applyFiltersNow()));
        immediateFilterApplyTimer.setCycleCount(1);

        pointWorkspace = new VariantClassWorkspace(
            VariantTypeVisuals.VariantClass.POINT, pointMutationsTab);
        svWorkspace = new VariantClassWorkspace(
            VariantTypeVisuals.VariantClass.STRUCTURAL, structuralVariantsTab);

        SampleComparisonPanel sampleComparisonPanel = new SampleComparisonPanel();
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
        pointWorkspace.setComparison(sampleComparisonPanel);

        VariantFiltersPanel variantFiltersPanel = new VariantFiltersPanel();
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
            () -> suppressFilterApplyEvents,
            VariantTypeVisuals.VariantClass.POINT);
        pointWorkspace.setFilters(variantFiltersPanel);

        installStructuralFiltersPanel();

        ControlFilesPanel controlFilesPanel = new ControlFilesPanel();
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
        pointWorkspace.setControl(controlFilesPanel);

        agentPanel = new AgentPanel();
        agentPanel.install(
            new AgentPanel.Nodes(
                filterTabPane,
                null,
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
                annotateLockButtons(),
                reloadBannerButton));
        busyOverlay.setOnCancel(this::handleCancelLoadingModal);

        initializeVariantTable();
        initializeSvVariantTable();
        if (tableSearchField != null) {
            tableSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (variantTable != null) {
                    variantTable.setTableSearchQuery(newVal);
                }
            });
        }
        setupExcelExportButton(excelExportButton, false);

        restructureModeWorkspaces();

        // Agent tab now lives under the Point tool pane.
        if (agentPanel != null && pointToolTabPane != null) {
            agentPanel.install(
                new AgentPanel.Nodes(
                    pointToolTabPane,
                    null,
                    agentTab,
                    apiKeyField,
                    agentModelField,
                    agentPromptArea,
                    agentResponseArea,
                    agentStatusLabel,
                    agentSubmitButton),
                this::buildVariantContext);
        }
    }

    /** Ordered Point then SV workspaces that have been assembled. */
    private List<VariantClassWorkspace> workspaces() {
        List<VariantClassWorkspace> list = new ArrayList<>(2);
        if (pointWorkspace != null) {
            list.add(pointWorkspace);
        }
        if (svWorkspace != null) {
            list.add(svWorkspace);
        }
        return list;
    }

    /**
     * Nest Filters / Sample Comparison / Control / Agent under each mode workspace.
     * Outer {@link #filterTabPane} becomes Point | SV only (Agent moves into Point tools;
     * SV gets its own Comparison/Control; Agent shared via Point for now and duplicated
     * under SV as a second agent tab binding to the same prefs-backed panel is deferred —
     * Agent stays under Point tools and is also linked into SV tools by reusing the tab).
     */
    private void restructureModeWorkspaces() {
        if (filterTabPane == null || pointMainSplitPane == null || svMainSplitPane == null) {
            return;
        }

        // Capture shared sibling tabs before removing them from the mode pane.
        Tab comparisonTab = findTabByText(filterTabPane, "Sample Comparison");
        Tab controlTab = findTabByText(filterTabPane, "Control Files");
        Tab agent = agentTab;

        filterTabPane.getTabs().removeAll(
            comparisonTab != null ? List.of(comparisonTab) : List.of());
        if (controlTab != null) {
            filterTabPane.getTabs().remove(controlTab);
        }
        if (agent != null) {
            filterTabPane.getTabs().remove(agent);
        }

        // ── Point workspace: tool tabs above results ─────────────────────────
        Node pointFilters = null;
        Node pointResults = null;
        if (pointMainSplitPane.getItems().size() >= 2) {
            pointFilters = pointMainSplitPane.getItems().get(0);
            pointResults = pointMainSplitPane.getItems().get(1);
        }

        pointToolTabPane = new TabPane();
        pointToolTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        pointToolTabPane.getStyleClass().add("filter-tabs");

        pointFiltersTab = new Tab("Variant Filters", pointFilters);
        pointFiltersTab.setClosable(false);
        pointComparisonTab = comparisonTab != null ? comparisonTab : new Tab("Sample Comparison");
        pointComparisonTab.setClosable(false);
        pointComparisonTab.setText("Sample Comparison");
        pointControlTab = controlTab != null ? controlTab : new Tab("Control Files");
        pointControlTab.setClosable(false);
        pointControlTab.setText("Control Files");
        if (agent != null) {
            agent.setClosable(false);
        }

        pointToolTabPane.getTabs().add(pointFiltersTab);
        pointToolTabPane.getTabs().add(pointComparisonTab);
        pointToolTabPane.getTabs().add(pointControlTab);
        if (agent != null) {
            pointToolTabPane.getTabs().add(agent);
        }

        if (pointFilters != null && pointResults != null) {
            pointToolTabPane.setMinHeight(48);
            pointToolTabPane.setPrefHeight(Region.USE_COMPUTED_SIZE);
            if (pointResults instanceof Region resultsRegion) {
                resultsRegion.setMinHeight(120);
            }
            pointMainSplitPane.getItems().setAll(pointToolTabPane, pointResults);
            pointMainSplitPane.setDividerPositions(0.45);
            // Allow collapsing filters almost fully so the table can fill the window.
            SplitPane.setResizableWithParent(pointToolTabPane, true);
            SplitPane.setResizableWithParent(pointResults, true);
            wireResultsExpandDivider(pointMainSplitPane);
        }

        // ── SV workspace: tool tabs above results ────────────────────────────
        Node svResults = null;
        if (svMainSplitPane.getItems().size() >= 2) {
            svResults = svMainSplitPane.getItems().get(1);
        }

        svToolTabPane = new TabPane();
        svToolTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        svToolTabPane.getStyleClass().add("filter-tabs");

        // Filters host may already contain the built SV filter UI.
        svFiltersTab = new Tab("Variant Filters", svFiltersHost);
        svFiltersTab.setClosable(false);

        javafx.util.Pair<Node, SampleComparisonPanel.Nodes> svComp =
            SampleComparisonPanel.buildUi();
        SampleComparisonPanel svSampleComparisonPanel = new SampleComparisonPanel();
        svSampleComparisonPanel.install(
            svComp.getValue(),
            this::scheduleFilterUpdate,
            this::scheduleImmediateFilterApply,
            () -> suppressFilterApplyEvents);
        SampleComparisonPanel.wireApplyButton(svComp.getKey(), this::handleApplyComparison);
        if (svWorkspace != null) {
            svWorkspace.setComparison(svSampleComparisonPanel);
        }
        svComparisonTab = new Tab("Sample Comparison", svComp.getKey());
        svComparisonTab.setClosable(false);

        javafx.util.Pair<Node, ControlFilesPanel.Nodes> svCtrl = ControlFilesPanel.buildUi();
        ControlFilesPanel svControlFilesPanel = new ControlFilesPanel();
        svControlFilesPanel.install(svCtrl.getValue());
        if (svWorkspace != null) {
            svWorkspace.setControl(svControlFilesPanel);
        }
        svControlTab = new Tab("Control Files", svCtrl.getKey());
        svControlTab.setClosable(false);

        // Reuse the same Agent tab instance — selecting it under SV shows the same agent UI.
        // When both modes are shown, Agent lives under Point tools; SV gets a lightweight note tab
        // that selects Point's agent (avoid dual ownership of one Node).
        Tab svAgentTab = new Tab("Agent");
        svAgentTab.setClosable(false);
        Label agentHint = new Label(
            "Open the Point mutations → Agent tab to run AI analysis on loaded variants.");
        agentHint.setWrapText(true);
        agentHint.setStyle("-fx-text-fill: #bbbbbb; -fx-padding: 16;");
        svAgentTab.setContent(agentHint);
        if (agent != null) {
            // Prefer putting Agent under SV tools as well by cloning the selection action:
            svAgentTab.setOnSelectionChanged(e -> {
                if (svAgentTab.isSelected() && pointToolTabPane != null && agent != null) {
                    // Keep hint; full dual Agent UI can be added later.
                }
            });
        }

        svToolTabPane.getTabs().addAll(svFiltersTab, svComparisonTab, svControlTab, svAgentTab);

        if (svResults != null) {
            svToolTabPane.setMinHeight(48);
            svToolTabPane.setPrefHeight(Region.USE_COMPUTED_SIZE);
            if (svResults instanceof Region resultsRegion) {
                resultsRegion.setMinHeight(120);
            }
            svMainSplitPane.getItems().setAll(svToolTabPane, svResults);
            svMainSplitPane.setDividerPositions(0.45);
            SplitPane.setResizableWithParent(svToolTabPane, true);
            SplitPane.setResizableWithParent(svResults, true);
            wireResultsExpandDivider(svMainSplitPane);
        }

        if (pointWorkspace != null) {
            pointWorkspace.setToolTabPane(pointToolTabPane);
        }
        if (svWorkspace != null) {
            svWorkspace.setToolTabPane(svToolTabPane);
        }

        // Outer pane should only carry mode tabs now.
        filterTabPane.getTabs().removeIf(t ->
            t != pointMutationsTab && t != structuralVariantsTab);
        if (pointMutationsTab != null && !filterTabPane.getTabs().contains(pointMutationsTab)) {
            filterTabPane.getTabs().add(0, pointMutationsTab);
        }
        if (structuralVariantsTab != null && !filterTabPane.getTabs().contains(structuralVariantsTab)) {
            filterTabPane.getTabs().add(structuralVariantsTab);
        }
    }

    /**
     * Make the vertical results divider easy to use: drag expands the table over filters;
     * double-click maximizes results.
     */
    private static void wireResultsExpandDivider(SplitPane split) {
        if (split == null) {
            return;
        }
        if (!split.getStyleClass().contains("variant-manager-split")) {
            split.getStyleClass().add("variant-manager-split");
        }
        Runnable apply = () -> {
            for (Node child : split.lookupAll(".split-pane-divider")) {
                if (!child.getStyleClass().contains("variant-results-divider")) {
                    child.getStyleClass().add("variant-results-divider");
                }
                if (child instanceof Region region) {
                    // Must call setters — minHeight()/prefHeight() only return properties.
                    region.setMinHeight(18);
                    region.setPrefHeight(18);
                    region.setMaxHeight(18);
                }
                child.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) {
                        // Collapse tool/filter pane; results take nearly the full height.
                        split.setDividerPositions(0.06);
                        e.consume();
                    }
                });
            }
        };
        Platform.runLater(apply);
        // Skin/dividers may appear after first layout.
        split.skinProperty().addListener((obs, o, n) -> Platform.runLater(apply));
    }

    private static Tab findTabByText(TabPane pane, String text) {
        if (pane == null || text == null) {
            return null;
        }
        for (Tab tab : pane.getTabs()) {
            if (text.equals(tab.getText())) {
                return tab;
            }
        }
        return null;
    }

    private void refreshBusyOverlayLockTargets() {
        if (busyOverlay == null) {
            return;
        }
        busyOverlay.setLockTargets(
            new VariantBusyOverlay.LockTargets(
                filterTabPane,
                annotateLockButtons(),
                reloadBannerButton));
    }

    private List<Button> annotateLockButtons() {
        List<Button> buttons = new ArrayList<>(2);
        if (annotateAllChromosomesButton != null) {
            buttons.add(annotateAllChromosomesButton);
        }
        if (svAnnotateAllChromosomesButton != null) {
            buttons.add(svAnnotateAllChromosomesButton);
        }
        return buttons;
    }

    private void installStructuralFiltersPanel() {
        if (svFiltersHost == null) {
            return;
        }
        javafx.util.Pair<Node, VariantFiltersPanel.Nodes> built =
            VariantFiltersPanel.buildStructuralFiltersUi();
        svFiltersHost.getChildren().setAll(built.getKey());
        VBox.setVgrow(built.getKey(), javafx.scene.layout.Priority.ALWAYS);

        VariantFiltersPanel.Nodes svNodes = built.getValue();
        if (svNodes.reloadBannerButton() != null) {
            svNodes.reloadBannerButton().setOnAction(e -> handleReloadFilteredVariants());
        }
        if (svNodes.annotateAllChromosomesButton() != null) {
            svAnnotateAllChromosomesButton = svNodes.annotateAllChromosomesButton();
            svAnnotateAllChromosomesButton.setOnAction(e -> handleAnnotateAllChromosomes());
        }
        if (svNodes.tableSearchField() != null) {
            svTableSearchField = svNodes.tableSearchField();
            svTableSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (svVariantTable != null) {
                    svVariantTable.setSearchQuery(newVal);
                }
            });
        }
        if (svNodes.excelExportButton() != null) {
            svExcelExportButton = svNodes.excelExportButton();
            setupExcelExportButton(svExcelExportButton, true);
        }

        VariantFiltersPanel svFiltersPanel = new VariantFiltersPanel();
        svFiltersPanel.install(
            svNodes,
            this::scheduleFilterUpdate,
            this::scheduleImmediateFilterApply,
            () -> suppressFilterApplyEvents,
            VariantTypeVisuals.VariantClass.STRUCTURAL);
        if (svWorkspace != null) {
            svWorkspace.setFilters(svFiltersPanel);
        }
        refreshBusyOverlayLockTargets();
    }

    private void initializeSvVariantTable() {
        if (svAllTable == null) {
            return;
        }
        Map<VcfVariantType, TableView<?>> typeTables = new EnumMap<>(VcfVariantType.class);
        typeTables.put(VcfVariantType.SV_DELETION, svDelTable);
        typeTables.put(VcfVariantType.SV_DUPLICATION, svDupTable);
        typeTables.put(VcfVariantType.SV_INVERSION, svInvTable);
        typeTables.put(VcfVariantType.SV_TRANSLOCATION, svTraTable);
        typeTables.put(VcfVariantType.SV_BREAKEND, svBndTable);
        typeTables.put(VcfVariantType.SV_INSERTION, svInsTable);

        Map<VcfVariantType, Tab> typeTabs = new EnumMap<>(VcfVariantType.class);
        typeTabs.put(VcfVariantType.SV_DELETION, svDelTab);
        typeTabs.put(VcfVariantType.SV_DUPLICATION, svDupTab);
        typeTabs.put(VcfVariantType.SV_INVERSION, svInvTab);
        typeTabs.put(VcfVariantType.SV_TRANSLOCATION, svTraTab);
        typeTabs.put(VcfVariantType.SV_BREAKEND, svBndTab);
        typeTabs.put(VcfVariantType.SV_INSERTION, svInsTab);

        svVariantTable = new SvVariantTable(
            svAllTable,
            svAllTab,
            typeTables,
            typeTabs,
            this::handlePositionClick,
            this::handleSvRowDoubleClick);
        svVariantTable.initializeColumns();
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

        sourceVariantLists = getCachedVariantSources();
        // Observed types live on each open VcfData; getters union them.

        // Populate variant type filters dynamically
        VariantFilter typeFilter = vcfManager.getCurrentLoadedFilter();
        if (typeFilter == null) {
            typeFilter = vcfManager.getCurrentFilter();
        }
        VariantFilter pointSlice = typeFilter != null && typeFilter.getPointSlice() != null
            ? typeFilter.getPointSlice()
            : typeFilter;
        VariantFilter svSlice = typeFilter != null && typeFilter.getSvSlice() != null
            ? typeFilter.getSvSlice()
            : typeFilter;
        if (pointWorkspace != null) {
            pointWorkspace.populateTypes(sourceVariantLists, pointSlice);
        }
        if (svWorkspace != null) {
            svWorkspace.populateTypes(sourceVariantLists, svSlice);
        }
        updateModeTabVisibility();

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
                for (VariantClassWorkspace ws : workspaces()) {
                    ws.refreshComparisonGroups();
                }
            }));
        registry.sampleGroupsRevisionProperty().addListener((obs, oldVal, newVal) ->
            Platform.runLater(() -> {
                for (VariantClassWorkspace ws : workspaces()) {
                    ws.refreshComparisonGroups();
                }
            }));

        if (filterTabPane != null) {
            filterTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                // no-op: mode switch; tool tabs refresh comparison on demand
            });
        }
        if (pointToolTabPane != null) {
            pointToolTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab != null && "Sample Comparison".equals(newTab.getText())
                    && pointWorkspace != null) {
                    pointWorkspace.refreshComparisonGroups();
                }
            });
        }
        if (svToolTabPane != null) {
            svToolTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab != null && "Sample Comparison".equals(newTab.getText())
                    && svWorkspace != null) {
                    svWorkspace.refreshComparisonGroups();
                }
            });
        }

        setupWindowVisibilityListeners();
        syncBusyOverlay();

        // Keep a balanced workspace: filters on top, tables below.
        Platform.runLater(() -> {
            if (pointMainSplitPane != null) {
                pointMainSplitPane.setDividerPositions(0.45);
            }
            if (svMainSplitPane != null) {
                svMainSplitPane.setDividerPositions(0.45);
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
        if (variantTable != null) {
            variantTable.setZeroTabCounts();
        }
        if (svVariantTable != null) {
            svVariantTable.setBaseTabTitles();
        }
        
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
        for (VariantClassWorkspace ws : workspaces()) {
            ws.clearTypeAndEffectCheckboxes();
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
        if (svTableSearchField != null) {
            svTableSearchField.clear();
        }
        if (variantTable != null) {
            variantTable.setTableSearchQuery("");
        }
        if (svVariantTable != null) {
            svVariantTable.setSearchQuery("");
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
        boolean previousSuppress = suppressFilterApplyEvents;
        suppressFilterApplyEvents = true;
        try {
            for (VariantClassWorkspace ws : workspaces()) {
                ws.syncSharedSampleRangeBounds();
            }
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

    /**
     * Called when sample/VCF data is removed so type checkboxes, mode tabs, and
     * tables drop types that are no longer present in any cached list.
     */
    public static void notifySampleDataChanged() {
        VariantManagerController controller = VariantManagerWindow.getCurrentController();
        if (controller == null) {
            return;
        }
        Platform.runLater(() -> {
            controller.syncSharedSampleRangeBounds();
            // Force loadData to rebuild filters/tables even if the VariantList instance is unchanged.
            controller.sourceVariants = null;
            controller.lastSeenVariantsRevision = -1;
            controller.loadData();
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

    /**
     * Add the given types to the load-time filter (Variant Manager checkboxes),
     * keep them canvas-visible, and reload cached chromosomes so they materialize.
     * Used when aggregate legends request a type that was not included in the last load.
     */
    public static void enableTypesAndReload(Set<VcfVariantType> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        Runnable work = () -> {
            VcfManager vcfManager = VcfManager.getInstance();
            if (!vcfManager.hasLoadedVcf()) {
                return;
            }

            VariantManagerController controller = VariantManagerWindow.getCurrentController();
            VariantFilter target;
            if (controller != null) {
                target = controller.buildFilterFromUI();
            } else {
                VariantFilter current = vcfManager.getCurrentFilter();
                target = current != null ? current.copy() : new VariantFilter();
            }

            // Ensure class slices exist, then add types to the matching slice.
            VariantFilter point = target.getPointSlice() != null
                ? target.getPointSlice().copy()
                : new VariantFilter();
            VariantFilter sv = target.getSvSlice() != null
                ? target.getSvSlice().copy()
                : new VariantFilter();
            if (target.getPointSlice() == null && target.getSvSlice() == null) {
                // Legacy single filter: seed slices from current allowed types only
                // (never invent a full Point/SV inventory).
                EnumSet<VcfVariantType> currentTypes = target.getAllowedTypes() == null
                    ? EnumSet.noneOf(VcfVariantType.class)
                    : EnumSet.copyOf(target.getAllowedTypes());
                EnumSet<VcfVariantType> pointTypes = EnumSet.copyOf(VariantTypeVisuals.VariantClass.POINT.allTypes());
                pointTypes.retainAll(currentTypes);
                EnumSet<VcfVariantType> svTypes = EnumSet.copyOf(VariantTypeVisuals.VariantClass.STRUCTURAL.allTypes());
                svTypes.retainAll(currentTypes);
                point.setAllowedTypes(pointTypes);
                point.setMinQuality(target.getMinQuality());
                point.setMinDepth(target.getMinDepth());
                point.setMinAlleleFraction(target.getMinAlleleFraction());
                point.setAllowedEffects(target.getAllowedEffects());
                point.setCancerGenesOnly(target.isCancerGenesOnly());
                sv.setAllowedTypes(svTypes);
                sv.setMinQuality(target.getMinQuality());
                sv.setAllowedEffects(EnumSet.allOf(VariantEffect.class));
            }

            EnumSet<VcfVariantType> pointAllowed = point.getAllowedTypes() == null || point.getAllowedTypes().isEmpty()
                ? EnumSet.noneOf(VcfVariantType.class)
                : EnumSet.copyOf(point.getAllowedTypes());
            EnumSet<VcfVariantType> svAllowed = sv.getAllowedTypes() == null || sv.getAllowedTypes().isEmpty()
                ? EnumSet.noneOf(VcfVariantType.class)
                : EnumSet.copyOf(sv.getAllowedTypes());
            for (VcfVariantType type : types) {
                if (VariantTypeVisuals.isStructural(type)) {
                    svAllowed.add(type);
                } else {
                    pointAllowed.add(type);
                }
            }
            point.setAllowedTypes(pointAllowed);
            sv.setAllowedTypes(svAllowed);
            target.setClassSlices(point, sv);

            vcfManager.unionSessionAvailableFilters(types, null);
            vcfManager.ensureCanvasTypesVisible(types);
            vcfManager.setCurrentFilterForNextLoad(target);

            if (controller != null) {
                if (controller.pointWorkspace != null) {
                    controller.pointWorkspace.populateTypes(
                        controller.getCachedVariantSources(), point);
                }
                if (controller.svWorkspace != null) {
                    controller.svWorkspace.populateTypes(
                        controller.getCachedVariantSources(), sv);
                }
                controller.loadFilterState(target);
                controller.updateModeTabVisibility();
                controller.handleReloadFilteredVariants();
            } else {
                reloadCachedChromosomesHeadless(vcfManager, target);
            }
        };

        if (Platform.isFxApplicationThread()) {
            work.run();
        } else {
            Platform.runLater(work);
        }
    }

    /** Reload cached chromosomes when Variant Manager is not open. */
    private static void reloadCachedChromosomesHeadless(VcfManager vcfManager, VariantFilter target) {
        List<String> chromosomes = vcfManager.getCachedChromosomesInOrder(null);
        if (chromosomes.isEmpty()) {
            String chrom = vcfManager.getLastLoadedChromosome();
            if (chrom != null && !chrom.isBlank()) {
                chromosomes = List.of(chrom);
            }
        }
        if (chromosomes.isEmpty()) {
            return;
        }

        vcfManager.markAllCachedVariantListsDirty();
        final VariantFilter filterSnapshot = target.copy();
        final List<String> cachedChroms = List.copyOf(chromosomes);
        vcfManager.annotateAllReferenceChromosomes(
            filterSnapshot,
            cachedChroms,
            null,
            result -> {
                if (result != null && !result.cancelled()) {
                    vcfManager.setCurrentFilterForNextLoad(filterSnapshot);
                    vcfManager.applyFilter(filterSnapshot);
                    org.baseplayer.project.ProjectSessionState.get().markDirty();
                }
            });
    }

    public java.util.Set<VcfVariantType> snapshotUiAvailableTypes() {
        java.util.EnumSet<VcfVariantType> types = java.util.EnumSet.noneOf(VcfVariantType.class);
        for (VariantClassWorkspace ws : workspaces()) {
            types.addAll(ws.snapshotShownVariantTypes());
        }
        return types;
    }

    public java.util.Set<org.baseplayer.variant.annotation.VariantEffect> snapshotUiAvailableEffects() {
        java.util.EnumSet<org.baseplayer.variant.annotation.VariantEffect> effects =
            java.util.EnumSet.noneOf(org.baseplayer.variant.annotation.VariantEffect.class);
        for (VariantClassWorkspace ws : workspaces()) {
            effects.addAll(ws.snapshotShownVariantEffects());
        }
        return effects;
    }

    @FXML
    private void handleRefreshComparisonGroups() {
        for (VariantClassWorkspace ws : workspaces()) {
            ws.refreshComparisonGroups();
        }
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
     * Double-click SV row: full span for DEL/DUP/INV; dual-stack for TRA/BND; breakpoint otherwise.
     */
    public void handleSvRowDoubleClick(VariantTable.TableRow row) {
        if (row == null || row.node() == null) {
            return;
        }
        VariantNode node = row.node();
        String rowChromosome = row.chromosome();
        if (rowChromosome == null || rowChromosome.isBlank()) {
            rowChromosome = chromosome;
        }
        final String chrom = rowChromosome;
        List<SampleTrack> tracks = resolveTracksFromCalls(node.getSamples());

        if (node.type == VcfVariantType.SV_TRANSLOCATION || node.type == VcfVariantType.SV_BREAKEND) {
            navigateSvTranslocation(chrom, node, tracks);
            return;
        }

        if ((node.type == VcfVariantType.SV_DELETION
                || node.type == VcfVariantType.SV_DUPLICATION
                || node.type == VcfVariantType.SV_INVERSION)
                && node.svEnd > node.position) {
            long[] view = paddedSvSpan(node.position, node.svEnd);
            final long navStart = view[0];
            final long navEnd = view[1];
            navigateAndApplySampleFilter(
                () -> {
                    NavigationCommands.navigateToPosition(chrom, (int) navStart, (int) navEnd);
                    tryLoadRegionVariants(chrom, navStart, navEnd);
                },
                tracks,
                "SV:" + chrom + ":" + node.position + "-" + node.svEnd);
            return;
        }

        handlePositionClick(row);
    }

    private void navigateSvTranslocation(String primaryChrom, VariantNode node, List<SampleTrack> tracks) {
        long primaryPos = node.position;
        long[] primaryView = paddedBreakpointWindow(primaryPos);
        String mateChrom = node.mateChromosome();
        long matePos = node.matePosition();

        navigateAndApplySampleFilter(
            () -> {
                DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
                List<org.baseplayer.draw.DrawStack> stacks =
                    stackManager != null ? stackManager.getStacks() : List.of();

                if (!stacks.isEmpty()) {
                    stacks.get(0).navigateTo(primaryChrom, primaryView[0], primaryView[1]);
                } else {
                    NavigationCommands.navigateToPosition(
                        primaryChrom, (int) primaryView[0], (int) primaryView[1]);
                }
                tryLoadRegionVariants(primaryChrom, primaryView[0], primaryView[1]);

                if (mateChrom != null && !mateChrom.isBlank() && matePos >= 0) {
                    long[] mateView = paddedBreakpointWindow(matePos);
                    if (stacks.size() >= 2) {
                        stacks.get(1).navigateTo(mateChrom, mateView[0], mateView[1]);
                        tryLoadRegionVariants(mateChrom, mateView[0], mateView[1]);
                    } else {
                        org.baseplayer.controllers.MainController.addStackAtRegion(
                            mateChrom, mateView[0], mateView[1]);
                    }
                }
            },
            tracks,
            "TRA:" + primaryChrom + ":" + primaryPos);
    }

    private static long[] paddedSvSpan(long start, long end) {
        long span = Math.max(1, end - start);
        long pad = Math.max(500, Math.min(50_000, span / 10));
        long viewStart = Math.max(1, start - pad);
        long viewEnd = end + pad;
        return new long[] { viewStart, viewEnd };
    }

    private static long[] paddedBreakpointWindow(long position) {
        long half = 500;
        long start = Math.max(1, position - half);
        return new long[] { start, position + half };
    }

    private void tryLoadRegionVariants(String chrom, long start, long end) {
        if (vcfManager == null || chrom == null || chrom.isBlank()) {
            return;
        }
        try {
            vcfManager.loadRegionVariants(chrom, start, end);
        } catch (Exception ignored) {
            // Navigation still succeeds without a forced VCF load
        }
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
            VariantFilter point = filter != null && filter.getPointSlice() != null
                ? filter.getPointSlice()
                : filter;
            VariantFilter sv = filter != null && filter.getSvSlice() != null
                ? filter.getSvSlice()
                : filter;
            if (pointWorkspace != null) {
                pointWorkspace.loadSlice(point);
            }
            if (svWorkspace != null) {
                svWorkspace.loadSlice(sv);
            }
        } finally {
            suppressFilterApplyEvents = false;
            cancelPendingFilterTimers();
            pendingReloadFilter = null;
            for (VariantClassWorkspace ws : workspaces()) {
                ws.hideReloadBanner();
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

    // ── Action Handlers ───────────────────────────────────────────────────────

    private void setupExcelExportButton(Button button, boolean structural) {
        if (button == null) {
            return;
        }
        FontIcon icon = new FontIcon(FontAwesomeSolid.FILE_EXCEL);
        icon.setIconSize(14);
        icon.setIconColor(Color.web("#217346"));
        button.setText("");
        button.setGraphic(icon);
        if (button.getTooltip() == null) {
            button.setTooltip(new Tooltip("Export to Excel"));
        }
        button.setOnAction(e -> {
            if (structural) {
                exportVariantsToExcel(true);
            } else {
                exportVariantsToExcel(false);
            }
        });
        setExcelExportButtonVisible(button, false);
    }

    private static void setExcelExportButtonVisible(Button button, boolean visible) {
        if (button == null) {
            return;
        }
        button.setVisible(visible);
        button.setManaged(visible);
    }

    @FXML
    private void handleExportExcel() {
        exportVariantsToExcel(false);
    }

    private void exportVariantsToExcel(boolean structural) {
        if (structural) {
            if (svVariantTable == null
                    || svVariantTable.getDisplayedAllRows() == null
                    || svVariantTable.getDisplayedAllRows().isEmpty()) {
                showExcelInfo("No variants available to export.");
                return;
            }
        } else {
            VariantTable table = variantTable();
            if (table == null
                    || (isEmpty(table.getDisplayedCodingRows())
                        && isEmpty(table.getDisplayedIntronicRows())
                        && isEmpty(table.getDisplayedIntergenicRows()))) {
                showExcelInfo("No variants available to export.");
                return;
            }
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Variants to Excel");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Excel Workbook", "*.xlsx"));
        chooser.setInitialFileName(structural ? "structural_variants.xlsx" : "variants.xlsx");
        File lastDir = UserPreferences.getLastDirectory("XLSX");
        if (lastDir != null) {
            try {
                chooser.setInitialDirectory(lastDir);
            } catch (IllegalArgumentException ignored) {
                // Directory unavailable; FileChooser uses its default.
            }
        }

        File chosen = chooser.showSaveDialog(stage != null ? stage : MainApp.stage);
        if (chosen == null) {
            return;
        }
        File target = chosen.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")
            ? chosen
            : new File(chosen.getParentFile(), chosen.getName() + ".xlsx");
        UserPreferences.setLastDirectory("XLSX", target);

        final Path path = target.toPath();
        final boolean exportSv = structural;
        ThreadRunner.get().submit(
            "Exporting to Excel…",
            () -> {
                try {
                    List<VariantTableExcelWriter.SheetData> sheetsToWrite = exportSv
                        ? buildSvExcelSheets()
                        : buildPointExcelSheets();
                    if (sheetsToWrite.isEmpty()) {
                        return new IOException("No variants available to export.");
                    }
                    VariantTableExcelWriter.write(
                        path,
                        sheetsToWrite,
                        (current, total) -> LoadingManager.get().setProgress(current, total));
                    return null;
                } catch (Exception ex) {
                    return ex;
                }
            },
            error -> {
                if (error != null) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Export to Excel");
                    alert.setHeaderText("Failed to write Excel file");
                    alert.setContentText(error.getMessage() != null ? error.getMessage() : error.toString());
                    if (stage != null) {
                        alert.initOwner(stage);
                    }
                    alert.showAndWait();
                }
            });
    }

    private void showExcelInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Export to Excel");
        alert.setHeaderText(null);
        alert.setContentText(message);
        if (stage != null) {
            alert.initOwner(stage);
        }
        alert.showAndWait();
    }

    private static boolean isEmpty(ObservableList<?> rows) {
        return rows == null || rows.isEmpty();
    }

    private List<VariantTableExcelWriter.SheetData> buildPointExcelSheets() {
        VariantTable table = variantTable();
        if (table == null) {
            return List.of();
        }
        VariantFilter filter = table.getDisplayFilter();
        List<VariantTableExcelWriter.SheetData> sheets = new ArrayList<>(3);
        addBuiltSheet(sheets, VariantExcelExportBuilder.buildPointSheet(
            "Coding", table.getDisplayedCodingRows(), filter, false));
        addBuiltSheet(sheets, VariantExcelExportBuilder.buildPointSheet(
            "Intronic", table.getDisplayedIntronicRows(), filter, false));
        addBuiltSheet(sheets, VariantExcelExportBuilder.buildPointSheet(
            "Intergenic", table.getDisplayedIntergenicRows(), filter, true));
        return sheets;
    }

    private List<VariantTableExcelWriter.SheetData> buildSvExcelSheets() {
        if (svVariantTable == null) {
            return List.of();
        }
        VariantTableExcelWriter.SheetData sheet = VariantExcelExportBuilder.buildStructuralSheet(
            "Structural",
            svVariantTable.getDisplayedAllRows(),
            svVariantTable.getDisplayFilter());
        return sheet != null ? List.of(sheet) : List.of();
    }

    private static void addBuiltSheet(
            List<VariantTableExcelWriter.SheetData> sheets,
            VariantTableExcelWriter.SheetData sheet) {
        if (sheet != null) {
            sheets.add(sheet);
        }
    }

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

        boolean reloadNeeded = false;
        for (VariantClassWorkspace ws : workspaces()) {
            if (ws.anyFilterLooserThanSnapshot()) {
                reloadNeeded = true;
                break;
            }
        }
        pendingReloadFilter = reloadNeeded ? filter : null;
        for (VariantClassWorkspace ws : workspaces()) {
            ws.refreshReloadBannerState(reloadNeeded, "Reload needed");
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
        for (VariantClassWorkspace ws : workspaces()) {
            ws.hideReloadBanner();
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
        if (pointWorkspace != null && pointWorkspace.control() != null) {
            pointWorkspace.control().handleApplyControlSettings();
        }
    }

    @FXML
    private void handleAddControlVcf() {
        if (pointWorkspace != null && pointWorkspace.control() != null) {
            pointWorkspace.control().handleAddControlVcf();
        }
    }

    @FXML
    private void handleAddControlBed() {
        if (pointWorkspace != null && pointWorkspace.control() != null) {
            pointWorkspace.control().handleAddControlBed();
        }
    }

    @FXML
    private void handleRemoveControlFile() {
        if (pointWorkspace != null && pointWorkspace.control() != null) {
            pointWorkspace.control().handleRemoveControlFile();
        }
    }

    @FXML
    private void handleAddInfoFilter() {
        if (pointWorkspace != null && pointWorkspace.filters() != null) {
            pointWorkspace.filters().showInfoFilterDialog();
        }
    }

    @FXML
    private void handleAddFilterField() {
        if (pointWorkspace != null && pointWorkspace.filters() != null) {
            pointWorkspace.filters().showFilterFieldDialog();
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
        sourceVariantLists = getCachedVariantSources();

        if (sourceVariantLists.isEmpty()) {
            lastSeenVariantsRevision = revision;
            clearTableItemsForChromosomeSwitch();
            setPlaceholder("No variants available");
            for (VariantClassWorkspace ws : workspaces()) {
                ws.populateTypes(sourceVariantLists, null);
            }
            updateModeTabVisibility();
            refreshReloadBannerState();
            if (!isCurrentChromosomeStillLoading()) {
                pendingScrollToChromosome = null;
            }
            return;
        }

        if (isCurrentChromosomeStillLoading()) {
            // Progressive loads can trigger many update events; defer expensive full table rebuild
            // and full variant-type scans until the chromosome load has completed.
            // Do not stamp lastSeenVariantsRevision so completion re-enters loadData and
            // refreshes mode tabs (Point|SV) once both classes are present.
            rebuildNeeded = true;
            if (busyOverlay != null) {
                busyOverlay.cancelDelayedLoadingModal();
            }
            return;
        }

        lastSeenVariantsRevision = revision;

        // Update variant type filters after load completes to avoid repeated full-list scans
        // during progressive loading.
        VariantFilter typeFilter = vcfManager.getCurrentLoadedFilter();
        if (typeFilter == null) {
            typeFilter = vcfManager.getCurrentFilter();
        }
        VariantFilter pointSnap = typeFilter != null && typeFilter.getPointSlice() != null
            ? typeFilter.getPointSlice()
            : typeFilter;
        VariantFilter svSnap = typeFilter != null && typeFilter.getSvSlice() != null
            ? typeFilter.getSvSlice()
            : typeFilter;
        if (pointWorkspace != null) {
            pointWorkspace.populateTypes(sourceVariantLists, pointSnap);
            pointWorkspace.captureFilterSnapshot(pointSnap);
        }
        if (svWorkspace != null) {
            svWorkspace.populateTypes(sourceVariantLists, svSnap);
            svWorkspace.captureFilterSnapshot(svSnap);
        }
        updateModeTabVisibility();

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
            List<VariantTable.TableRow> svAll = new ArrayList<>();
            Map<VcfVariantType, List<VariantTable.TableRow>> svByType = new EnumMap<>(VcfVariantType.class);
            for (VcfVariantType type : SvVariantTable.TYPE_ORDER) {
                svByType.put(type, new ArrayList<>());
            }

            try {
                boolean lohMode = filterSnapshot.isLohMode();

                for (VcfManager.CachedChromosomeVariants cached : snapshots) {
                    String sourceChromosome = cached.chromosome();
                    VariantList variants = cached.variants();
                    if (variants == null || variants.isEmpty()) {
                        continue;
                    }

                    VariantNode node = variants.getFirst();
                    VariantFilter svSlice = filterSnapshot.getSvSlice() != null
                        ? filterSnapshot.getSvSlice()
                        : filterSnapshot;
                    VariantFilter pointSlice = filterSnapshot.getPointSlice() != null
                        ? filterSnapshot.getPointSlice()
                        : filterSnapshot;
                    boolean svGeneLevel = svSlice.isGeneLevel();
                    boolean pointGeneLevel = pointSlice.isGeneLevel();
                    Map<String, Set<Integer>> geneTracks = null;
                    Map<VcfVariantType, Set<String>> passingGenesByType = null;
                    if (svGeneLevel || pointGeneLevel) {
                        variants.ensureGeneSampleIndex(filterSnapshot);
                    }
                    if (svGeneLevel) {
                        passingGenesByType = variants.computePassingGenesByType(svSlice);
                    }
                    if (pointGeneLevel) {
                        geneTracks = variants.ensureGeneSampleIndex(filterSnapshot);
                    }
                    Map<VariantNode, Set<Integer>> clusterTracks =
                        !svGeneLevel && !pointGeneLevel && filterSnapshot.hasComparisonWindow()
                            ? variants.ensureClusterSampleIndex(filterSnapshot)
                            : null;
                    while (node != null) {
                        if (lohMode) {
                            filterSnapshot.addMissingLohAaCalls(node);
                        }
                        boolean isSv = VariantTypeVisuals.isStructural(node.type);
                        if (isSv && svGeneLevel) {
                            if (!filterSnapshot.passesBaseNodeLevel(node)) {
                                node = node.next;
                                continue;
                            }
                            Set<String> passing = passingGenesByType != null
                                ? passingGenesByType.get(node.type)
                                : null;
                            List<String> displayGenes =
                                VariantList.displayGenesPassing(node, passing);
                            if (displayGenes.isEmpty()) {
                                node = node.next;
                                continue;
                            }
                            if (svSlice.hasActiveGenotypeGroupComparison()
                                    && !svSlice.passesGroupComparison(node)) {
                                node = node.next;
                                continue;
                            }
                            int passSamples = 0;
                            for (VariantNode.SampleCall call : node.getSamples()) {
                                if (filterSnapshot.passesSampleThresholds(node, call)
                                        && node.isAltCarrier(call)) {
                                    passSamples++;
                                }
                            }
                            if (passSamples > 0) {
                                // One table row per overlapping gene so nested grouping
                                // places the SV under every gene it hits (not only primary).
                                for (String gene : displayGenes) {
                                    if (gene == null || gene.isBlank()) {
                                        continue;
                                    }
                                    VariantTable.TableRow row = new VariantTable.TableRow(
                                        sourceChromosome, node, List.of(gene.trim()));
                                    svAll.add(row);
                                    List<VariantTable.TableRow> typeBucket = svByType.get(node.type);
                                    if (typeBucket != null) {
                                        typeBucket.add(row);
                                    }
                                }
                            }
                            node = node.next;
                            continue;
                        }

                        Set<Integer> aggregatedTracks = null;
                        if (geneTracks != null) {
                            aggregatedTracks = VariantList.aggregatedTracksForGenes(node, geneTracks);
                        } else if (clusterTracks != null) {
                            aggregatedTracks = clusterTracks.getOrDefault(node, Set.of());
                        }
                        if (!filterSnapshot.passesNodeLevel(node, aggregatedTracks)) {
                            node = node.next;
                            continue;
                        }

                        int passSamples = 0;
                        for (VariantNode.SampleCall call : node.getSamples()) {
                            if (filterSnapshot.passesSampleThresholds(node, call)
                                    && node.isAltCarrier(call)) {
                                passSamples++;
                            }
                        }
                        if (passSamples > 0) {
                            VariantTable.TableRow row = new VariantTable.TableRow(sourceChromosome, node);
                            if (isSv) {
                                svAll.add(row);
                                List<VariantTable.TableRow> typeBucket = svByType.get(node.type);
                                if (typeBucket != null) {
                                    typeBucket.add(row);
                                }
                            } else {
                                VariantAnnotation ann = node.annotation;
                                VariantEffect effect = ann != null ? ann.effect() : VariantEffect.INTERGENIC;
                                if (effect.isCoding() || effect.isSpliceSite() || effect.isRegulatory()) {
                                    coding.add(row);
                                } else if (effect.isIntronic()) {
                                    intronic.add(row);
                                } else {
                                    intergenic.add(row);
                                }
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
                if (svVariantTable != null) {
                    svVariantTable.setDisplayContext(filterSnapshot);
                    Map<VcfVariantType, ObservableList<VariantTable.TableRow>> byType =
                        new EnumMap<>(VcfVariantType.class);
                    for (VcfVariantType type : SvVariantTable.TYPE_ORDER) {
                        List<VariantTable.TableRow> bucket = svByType.get(type);
                        byType.put(type, FXCollections.observableArrayList(
                            bucket != null ? bucket : List.of()));
                    }
                    svVariantTable.setItems(FXCollections.observableArrayList(svAll), byType);
                }

                boolean pointEmpty = coding.isEmpty() && intronic.isEmpty() && intergenic.isEmpty();
                boolean svEmpty = svAll.isEmpty();
                setExcelExportButtonVisible(excelExportButton, !pointEmpty);
                setExcelExportButtonVisible(svExcelExportButton, !svEmpty);
                if (pointEmpty) {
                    setPlaceholder("No point mutations match current filter settings");
                } else {
                    setTablePlaceholders(null, null, null);
                }
                if (svVariantTable != null) {
                    if (svEmpty) {
                        svVariantTable.setPlaceholders(
                            "No structural variants match current filter settings", "");
                    } else {
                        svVariantTable.setPlaceholders("", "");
                    }
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
        if (variantTable != null) {
            variantTable.setBaseTabTitles();
        }
        if (svVariantTable != null) {
            svVariantTable.setBaseTabTitles();
            svVariantTable.setPlaceholders(text, "");
        }
        setExcelExportButtonVisible(excelExportButton, false);
        setExcelExportButtonVisible(svExcelExportButton, false);
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
            for (VariantClassWorkspace ws : workspaces()) {
                ws.hideReloadBanner();
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
        boolean needed = pendingReloadFilter != null;
        for (VariantClassWorkspace ws : workspaces()) {
            ws.refreshReloadBannerState(needed, "Reload needed");
        }
    }

    /** Show/hide Point mutations vs Structural variants tabs based on loaded data. */
    void updateModeTabVisibility() {
        if (filterTabPane == null) {
            return;
        }
        // Presence from loaded caches (+ session after rebuild). Never invent from filter defaults.
        Set<VcfVariantType> universe = collectLoadedVariantTypes();
        if (vcfManager != null) {
            universe.addAll(vcfManager.getSessionAvailableTypes());
        }

        boolean hasPoint = VariantTypeVisuals.hasClass(universe, VariantTypeVisuals.VariantClass.POINT);
        boolean hasSv = VariantTypeVisuals.hasClass(universe, VariantTypeVisuals.VariantClass.STRUCTURAL);
        // If neither known yet, keep both tabs so the user can configure before load.
        if (!hasPoint && !hasSv) {
            hasPoint = true;
            hasSv = true;
        }

        boolean both = hasPoint && hasSv;
        setTabVisible(pointMutationsTab, hasPoint);
        setTabVisible(structuralVariantsTab, hasSv);
        applyModeChrome(both);

        // Prefer the only available mode; when both exist, leave current selection alone
        // unless it points at a hidden tab.
        Tab selected = filterTabPane.getSelectionModel().getSelectedItem();
        boolean selectedInvalid = selected == null || !filterTabPane.getTabs().contains(selected);
        if (!both) {
            if (hasSv && structuralVariantsTab != null) {
                filterTabPane.getSelectionModel().select(structuralVariantsTab);
            } else if (hasPoint && pointMutationsTab != null) {
                filterTabPane.getSelectionModel().select(pointMutationsTab);
            }
        } else if (selectedInvalid) {
            if (pointMutationsTab != null && filterTabPane.getTabs().contains(pointMutationsTab)) {
                filterTabPane.getSelectionModel().select(pointMutationsTab);
            } else if (structuralVariantsTab != null) {
                filterTabPane.getSelectionModel().select(structuralVariantsTab);
            }
        }
    }

    /** Types actually present in cached chromosome variant lists. */
    private Set<VcfVariantType> collectLoadedVariantTypes() {
        Set<VcfVariantType> types = EnumSet.noneOf(VcfVariantType.class);
        List<VcfManager.CachedChromosomeVariants> sources = sourceVariantLists;
        if (sources == null || sources.isEmpty()) {
            sources = getCachedVariantSources();
        }
        if (sources != null) {
            for (VcfManager.CachedChromosomeVariants cached : sources) {
                if (cached != null && cached.variants() != null) {
                    types.addAll(cached.variants().collectVariantTypes());
                }
            }
        }
        return types;
    }

    /** When only one class is present, hide the outer Point|SV tab header. */
    private void applyModeChrome(boolean showModeTabs) {
        if (filterTabPane == null) {
            return;
        }
        filterTabPane.getStyleClass().remove("single-mode");
        if (!showModeTabs) {
            filterTabPane.getStyleClass().add("single-mode");
            // Collapse header (Variant Manager may not apply CSS-only hide reliably).
            filterTabPane.setTabMinHeight(0);
            filterTabPane.setTabMaxHeight(0);
        } else {
            // USE_COMPUTED_SIZE alone often fails to undo a prior 0 max-height in JavaFX.
            filterTabPane.setTabMinHeight(Region.USE_COMPUTED_SIZE);
            filterTabPane.setTabMaxHeight(Double.MAX_VALUE);
            filterTabPane.applyCss();
            filterTabPane.requestLayout();
        }
    }

    private void setTabVisible(Tab tab, boolean visible) {
        if (tab == null || filterTabPane == null) {
            return;
        }
        if (visible) {
            if (!filterTabPane.getTabs().contains(tab)) {
                // Insert Point before SV before shared tabs.
                int index = 0;
                if (tab == structuralVariantsTab && pointMutationsTab != null
                    && filterTabPane.getTabs().contains(pointMutationsTab)) {
                    index = filterTabPane.getTabs().indexOf(pointMutationsTab) + 1;
                }
                filterTabPane.getTabs().add(Math.min(index, filterTabPane.getTabs().size()), tab);
            }
        } else {
            filterTabPane.getTabs().remove(tab);
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
