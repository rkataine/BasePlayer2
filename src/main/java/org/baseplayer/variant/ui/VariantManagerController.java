package org.baseplayer.variant.ui;

import org.baseplayer.MainApp;
import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleGroup;
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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.geometry.Pos;
import javafx.stage.Stage;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.prefs.Preferences;

/**
 * Controller for the FXML-based Variant Manager dialog.
 * Provides tabbed filtering interface and variant annotation tables.
 */
public class VariantManagerController implements Initializable {

    private static final String TEXT           = "white";

    // ── FXML Components ───────────────────────────────────────────────────────

    // Filter Tab: Variant Filters
    @FXML private GridPane variantTypesContainer;  // Container for dynamic type checkboxes
    @FXML private CheckBox selectAllTypesCheckBox;
    @FXML private CheckBox selectAllEffectsCheckBox;
    @FXML private CheckBox missenseCheckBox, synonymousCheckBox, stopFrameshiftCheckBox;
    @FXML private CheckBox spliceSiteCheckBox, utrCheckBox, noncodingCheckBox;
    @FXML private CheckBox intronicCheckBox, intergenicCheckBox;
    @FXML private Slider qualitySlider, coverageSlider, alleleFreqSlider;
    @FXML private TextField qualityField, coverageField, alleleFreqField;
    @FXML private Label qualityValueLabel, coverageValueLabel, alleleFreqValueLabel;
    @FXML private CheckBox cancerOnlyCheckBox;
    @FXML private VBox advancedFiltersContainer;
    @FXML private Button addInfoFilterButton, addFilterFieldButton;
    @FXML private Button annotateAllChromosomesButton;
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
    private ToggleGroup presentMatchModeGroup;
    /** Role per group id: IGNORE / PRESENT / ABSENT */
    private final Map<Integer, VariantFilter.GroupRole> comparisonGroupRoles = new HashMap<>();

    // Filter Tab: Control Files
    @FXML private CheckBox filterByPopFreqCheckBox, useGnomadCheckBox, use1000GenomesCheckBox, useExacCheckBox;
    @FXML private TextField maxPopFreqField;
    @FXML private CheckBox showPathogenicCheckBox, hideBenignCheckBox;
    @FXML private TableView<ControlFileEntry> controlFilesTable;
    @FXML private TableColumn<ControlFileEntry, Boolean> controlFileEnabledColumn;
    @FXML private TableColumn<ControlFileEntry, String> controlFileNameColumn;
    @FXML private TableColumn<ControlFileEntry, String> controlFileTypeColumn;
    @FXML private TableColumn<ControlFileEntry, String> controlFileActionsColumn;

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
    // Delay showing loading modal so quick updates don't flash a spinner
    private Timeline loadingModalDelayTimer;
    private volatile boolean rebuildRunning = false;
    private volatile boolean rebuildNeeded = false;
    private volatile boolean allChromosomeAnnotationRunning = false;
    private volatile ThreadRunner.RunnerTask allChromosomeAnnotationTask;
    private boolean suppressFilterApplyEvents = false;
    private VariantFilter pendingReloadFilter;
    private static final String PREF_API_KEY   = "gemini_api_key";
    private static final String PREF_API_MODEL = "gemini_model";
    private volatile boolean agentRunning = false;
    private VariantTable variantTable;
    
    // Dynamic variant type checkboxes
    private java.util.Map<VcfVariantType, CheckBox> variantTypeCheckBoxes = new java.util.HashMap<>();

    // ── Initialization ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupSliderBindings();
        setupSampleComparisonBindings();
        refreshComparisonGroupsUI();

        initializeVariantTable();

        // Apply filters automatically on checkbox change
        setupAutoFilterListeners();

        setupAgentTab();
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
        populateVariantTypeFilters();

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
                refreshComparisonGroupsUI();
            }));
        registry.sampleGroupsRevisionProperty().addListener((obs, oldVal, newVal) ->
            Platform.runLater(this::refreshComparisonGroupsUI));

        if (filterTabPane != null) {
            filterTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab != null && "Sample Comparison".equals(newTab.getText())) {
                    refreshComparisonGroupsUI();
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
        ThreadRunner.RunnerTask task = allChromosomeAnnotationTask;
        if (task != null && !task.isCompleted()) {
            task.cancel();
        }
        if (updateListener != null) {
            GenomicCanvas.update.removeListener(updateListener);
        }
        cancelDelayedLoadingModal();
        vcfManager.clearFilter();
        vcfManager.setOnVcfAdded(null);
        ServiceRegistry.getInstance().getSampleRegistry().clearSubsetSource(SampleRegistry.SubsetSource.GENE_FOCUS);
				MinimizedVariantManagerWindow.handleCleanup();
    }

    public void clearBatchAnnotationResults() {
        allChromosomeAnnotationTask = null;
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
        hideReloadBanner();

        variantTypeCheckBoxes.clear();
        if (variantTypesContainer != null) {
            variantTypesContainer.getChildren().clear();
        }
        if (selectAllTypesCheckBox != null) {
            selectAllTypesCheckBox.setSelected(true);
        }

        VariantFilter defaults = vcfManager != null
            ? vcfManager.getCurrentFilter()
            : new VariantFilter();
        if (defaults == null) {
            defaults = new VariantFilter();
        }
        loadFilterState(defaults);
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

    private void setupSliderBindings() {
        // Initialize debounce timer for real-time filter updates
        filterDebounceTimer = new Timeline(new KeyFrame(Duration.millis(200), e -> applyFiltersNow()));
        filterDebounceTimer.setCycleCount(1);
        immediateFilterApplyTimer = new Timeline(new KeyFrame(Duration.millis(40), e -> applyFiltersNow()));
        immediateFilterApplyTimer.setCycleCount(1);
        
        // Quality slider - update UI and trigger debounced filter update
        qualitySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int val = newVal.intValue();
            qualityValueLabel.setText(String.valueOf(val));
            qualityField.setText(String.valueOf(val));
            scheduleFilterUpdate();
        });
        qualityField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                double val = Double.parseDouble(newVal);
                qualitySlider.setValue(val);
            } catch (NumberFormatException ignored) {}
        });

        // Coverage slider - update UI and trigger debounced filter update
        coverageSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int val = newVal.intValue();
            coverageValueLabel.setText(String.valueOf(val));
            coverageField.setText(String.valueOf(val));
            scheduleFilterUpdate();
        });
        coverageField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                double val = Double.parseDouble(newVal);
                coverageSlider.setValue(val);
            } catch (NumberFormatException ignored) {}
        });

        // Allele frequency slider - update UI and trigger debounced filter update
        alleleFreqSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            double val = newVal.doubleValue();
            String formatted = String.format("%.2f", val);
            alleleFreqValueLabel.setText(formatted);
            alleleFreqField.setText(formatted);
            scheduleFilterUpdate();
        });
        alleleFreqField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                double val = Double.parseDouble(newVal);
                alleleFreqSlider.setValue(val);
            } catch (NumberFormatException ignored) {}
        });
    }

    private void setupSampleComparisonBindings() {
        presentMatchModeGroup = new ToggleGroup();
        if (presentMatchAllRadio != null) {
            presentMatchAllRadio.setToggleGroup(presentMatchModeGroup);
            presentMatchAnyRadio.setToggleGroup(presentMatchModeGroup);
            presentMatchModeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
                updateGroupComparisonSummaryLabel();
                if (!suppressFilterApplyEvents) {
                    scheduleImmediateFilterApply();
                }
            });
        }

        if (geneLevelComparisonCheckBox != null) {
            geneLevelComparisonCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                updateComparisonWindowEnabled();
                updateCommonVariantsHelpLabel();
                if (!suppressFilterApplyEvents) {
                    scheduleImmediateFilterApply();
                }
            });
            updateComparisonWindowEnabled();
            updateCommonVariantsHelpLabel();
        }

        if (comparisonWindowField != null) {
            comparisonWindowField.textProperty().addListener((obs, oldVal, newVal) -> {
                updateCommonVariantsHelpLabel();
                if (suppressFilterApplyEvents) {
                    return;
                }
                scheduleFilterUpdate();
            });
        }

        if (sharedSampleRangeSlider == null) {
            return;
        }

        ChangeListener<Number> rangeListener = (obs, oldVal, newVal) -> {
            if (suppressFilterApplyEvents) {
                return;
            }
            scheduleFilterUpdate();
        };
        sharedSampleRangeSlider.lowValueProperty().addListener(rangeListener);
        sharedSampleRangeSlider.highValueProperty().addListener(rangeListener);
    }

    private void updateCommonVariantsHelpLabel() {
        if (commonVariantsHelpLabel == null) {
            return;
        }
        boolean geneLevel = geneLevelComparisonCheckBox != null && geneLevelComparisonCheckBox.isSelected();
        int windowBp = readComparisonWindowBp();
        if (sharedSampleRangeSlider != null) {
            if (geneLevel) {
                sharedSampleRangeSlider.setSummaryUnit("samples (gene)");
            } else if (windowBp > 0) {
                sharedSampleRangeSlider.setSummaryUnit("samples (window)");
            } else {
                sharedSampleRangeSlider.setSummaryUnit("samples");
            }
        }
        if (geneLevel) {
            commonVariantsHelpLabel.setText(
                "Keep genes mutated in at least this many samples (left) and at most this many (right). All variants in a matching gene are shown.");
        } else if (windowBp > 0) {
            commonVariantsHelpLabel.setText(
                "Counts samples with a soft-matching call within " + windowBp
                    + " bp (same type family; indels/SVs may match across types). "
                    + "Use the right thumb to drop hotspot / fragile / repeat clusters shared by too many samples.");
        } else {
            commonVariantsHelpLabel.setText(
                "Keep variants shared by at least this many samples (left) and at most this many (right). Use this to drop private variants or those shared by everyone.");
        }
    }

    private void updateComparisonWindowEnabled() {
        if (comparisonWindowField == null) {
            return;
        }
        boolean geneLevel = geneLevelComparisonCheckBox != null && geneLevelComparisonCheckBox.isSelected();
        comparisonWindowField.setDisable(geneLevel);
    }

    private int readComparisonWindowBp() {
        if (comparisonWindowField == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(comparisonWindowField.getText().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void syncSharedSampleRangeBounds() {
        if (sharedSampleRangeSlider == null) {
            return;
        }
        int sampleCount = Math.max(1, ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks().size());
        boolean previousSuppress = suppressFilterApplyEvents;
        suppressFilterApplyEvents = true;
        try {
            sharedSampleRangeSlider.setAbsoluteMax(sampleCount);
        } finally {
            suppressFilterApplyEvents = previousSuppress;
        }
    }

    @FXML
    private void handleRefreshComparisonGroups() {
        refreshComparisonGroupsUI();
    }

    private void refreshComparisonGroupsUI() {
        if (comparisonGroupsContainer == null) {
            return;
        }
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        comparisonGroupsContainer.getChildren().clear();

        // Preserve roles for groups that still exist.
        Set<Integer> validIds = new HashSet<>();
        validIds.add(VariantFilter.UNGROUPED_COHORT_ID);
        for (SampleGroup group : registry.getSampleGroups()) {
            validIds.add(group.getId());
        }
        comparisonGroupRoles.keySet().removeIf(id -> !validIds.contains(id));

        int ungroupedCount = 0;
        for (SampleTrack track : registry.getSampleTracks()) {
            if (!track.hasGroup()) {
                ungroupedCount++;
            }
        }
        comparisonGroupsContainer.getChildren().add(
            buildComparisonGroupRow(
                VariantFilter.UNGROUPED_COHORT_ID,
                "Ungrouped",
                ungroupedCount,
                Color.web("#888888")));

        for (SampleGroup group : registry.getSampleGroups()) {
            comparisonGroupsContainer.getChildren().add(
                buildComparisonGroupRow(
                    group.getId(),
                    group.getName(),
                    registry.countTracksInGroup(group.getId()),
                    group.getColor()));
        }

        if (registry.getSampleGroups().isEmpty() && ungroupedCount == 0) {
            Label empty = new Label("No samples loaded yet.");
            empty.getStyleClass().add("subsection-label");
            comparisonGroupsContainer.getChildren().add(empty);
        }

        updateGroupComparisonSummaryLabel();
    }

    private HBox buildComparisonGroupRow(int groupId, String name, int memberCount, Color color) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Label swatch = new Label("  ");
        String hex = String.format("#%02x%02x%02x",
            (int) Math.round(color.getRed() * 255),
            (int) Math.round(color.getGreen() * 255),
            (int) Math.round(color.getBlue() * 255));
        swatch.setStyle(
            "-fx-background-color: " + hex + "; -fx-background-radius: 2;"
                + "-fx-min-width: 12; -fx-min-height: 12;");

        Label nameLabel = new Label(name + " (" + memberCount + ")");
        nameLabel.getStyleClass().add("subsection-label");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        ComboBox<String> roleBox = new ComboBox<>();
        roleBox.getItems().addAll("Ignore", "Must be present", "Must be absent");
        VariantFilter.GroupRole currentRole =
            comparisonGroupRoles.getOrDefault(groupId, VariantFilter.GroupRole.IGNORE);
        roleBox.setValue(roleLabel(currentRole));
        roleBox.setPrefWidth(140);
        roleBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            VariantFilter.GroupRole role = roleFromLabel(newVal);
            if (role == VariantFilter.GroupRole.IGNORE) {
                comparisonGroupRoles.remove(groupId);
            } else {
                comparisonGroupRoles.put(groupId, role);
            }
            updateGroupComparisonSummaryLabel();
            if (!suppressFilterApplyEvents) {
                scheduleImmediateFilterApply();
            }
        });

        row.getChildren().addAll(swatch, nameLabel, roleBox);
        return row;
    }

    private static String roleLabel(VariantFilter.GroupRole role) {
        if (role == null) {
            return "Ignore";
        }
        return switch (role) {
            case PRESENT -> "Must be present";
            case ABSENT -> "Must be absent";
            default -> "Ignore";
        };
    }

    private static VariantFilter.GroupRole roleFromLabel(String label) {
        if ("Must be present".equals(label)) {
            return VariantFilter.GroupRole.PRESENT;
        }
        if ("Must be absent".equals(label)) {
            return VariantFilter.GroupRole.ABSENT;
        }
        return VariantFilter.GroupRole.IGNORE;
    }

    private void updateGroupComparisonSummaryLabel() {
        if (groupComparisonSummaryLabel == null) {
            return;
        }
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        for (Map.Entry<Integer, VariantFilter.GroupRole> entry : comparisonGroupRoles.entrySet()) {
            String label = groupDisplayName(registry, entry.getKey());
            if (entry.getValue() == VariantFilter.GroupRole.PRESENT) {
                present.add(label);
            } else if (entry.getValue() == VariantFilter.GroupRole.ABSENT) {
                absent.add(label);
            }
        }
        if (present.isEmpty() && absent.isEmpty()) {
            groupComparisonSummaryLabel.setText("No group constraints");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!present.isEmpty()) {
            String joiner = selectedPresentMatchMode() == VariantFilter.PresentMatchMode.ANY
                ? " or " : " and ";
            sb.append("Present: ").append(String.join(joiner, present));
        }
        if (!absent.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("Absent: ").append(String.join(" and ", absent));
        }
        groupComparisonSummaryLabel.setText(sb.toString());
    }

    private static String groupDisplayName(SampleRegistry registry, int groupId) {
        if (groupId == VariantFilter.UNGROUPED_COHORT_ID) {
            return "Ungrouped";
        }
        SampleGroup group = registry.getSampleGroup(groupId);
        return group != null ? group.getName() : ("Group " + groupId);
    }

    private VariantFilter.PresentMatchMode selectedPresentMatchMode() {
        if (presentMatchAnyRadio != null && presentMatchAnyRadio.isSelected()) {
            return VariantFilter.PresentMatchMode.ANY;
        }
        return VariantFilter.PresentMatchMode.ALL;
    }

    private void applyPresentMatchModeToRadios(VariantFilter.PresentMatchMode mode) {
        if (presentMatchAllRadio == null) {
            return;
        }
        if (mode == VariantFilter.PresentMatchMode.ANY) {
            presentMatchAnyRadio.setSelected(true);
        } else {
            presentMatchAllRadio.setSelected(true);
        }
    }

    private Map<Integer, Set<Integer>> resolveGroupTrackIndices(Set<Integer> groupIds) {
        Map<Integer, Set<Integer>> byGroup = new HashMap<>();
        if (groupIds == null || groupIds.isEmpty()) {
            return byGroup;
        }
        for (Integer id : groupIds) {
            byGroup.put(id, new HashSet<>());
        }
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<SampleTrack> tracks = registry.getSampleTracks();
        for (int i = 0; i < tracks.size(); i++) {
            SampleTrack track = tracks.get(i);
            int cohortId = track.hasGroup() ? track.getGroupId() : VariantFilter.UNGROUPED_COHORT_ID;
            Set<Integer> indices = byGroup.get(cohortId);
            if (indices != null) {
                indices.add(i);
            }
        }
        return byGroup;
    }

    /**
     * Schedule a debounced filter update. Restarts the timer on each call,
     * so rapid slider movements only trigger one update 200ms after the last change.
     */
    private void scheduleFilterUpdate() {
        if (suppressFilterApplyEvents || allChromosomeAnnotationRunning) {
            return;
        }
        if (filterDebounceTimer != null) {
            filterDebounceTimer.stop();
            filterDebounceTimer.playFromStart();
        }
    }

    private void scheduleImmediateFilterApply() {
        if (suppressFilterApplyEvents || allChromosomeAnnotationRunning) {
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

    private void setupAutoFilterListeners() {
        // Select All Types checkbox
        selectAllTypesCheckBox.setOnAction(e -> {
            boolean selectAll = selectAllTypesCheckBox.isSelected();
            suppressFilterApplyEvents = true;
            for (CheckBox cb : new HashSet<>(variantTypeCheckBoxes.values())) {
                cb.setSelected(selectAll);
            }
            suppressFilterApplyEvents = false;
            scheduleImmediateFilterApply();
        });
        
        // Select All Effects checkbox
        selectAllEffectsCheckBox.setOnAction(e -> {
            boolean selectAll = selectAllEffectsCheckBox.isSelected();
            suppressFilterApplyEvents = true;
            missenseCheckBox.setSelected(selectAll);
            synonymousCheckBox.setSelected(selectAll);
            stopFrameshiftCheckBox.setSelected(selectAll);
            spliceSiteCheckBox.setSelected(selectAll);
            utrCheckBox.setSelected(selectAll);
            noncodingCheckBox.setSelected(selectAll);
            intronicCheckBox.setSelected(selectAll);
            intergenicCheckBox.setSelected(selectAll);
            suppressFilterApplyEvents = false;
            scheduleImmediateFilterApply();
        });
        
        // Effect checkboxes - also update selectAllEffectsCheckBox state
        ChangeListener<Boolean> effectCheckListener = (obs, oldVal, newVal) -> {
            updateSelectAllEffectsState();
            scheduleImmediateFilterApply();
        };
        missenseCheckBox.selectedProperty().addListener(effectCheckListener);
        synonymousCheckBox.selectedProperty().addListener(effectCheckListener);
        stopFrameshiftCheckBox.selectedProperty().addListener(effectCheckListener);
        spliceSiteCheckBox.selectedProperty().addListener(effectCheckListener);
        utrCheckBox.selectedProperty().addListener(effectCheckListener);
        noncodingCheckBox.selectedProperty().addListener(effectCheckListener);
        intronicCheckBox.selectedProperty().addListener(effectCheckListener);
        intergenicCheckBox.selectedProperty().addListener(effectCheckListener);

        // Cancer filter
        cancerOnlyCheckBox.setOnAction(e -> scheduleImmediateFilterApply());

        // Quality field (apply on Enter)
        qualityField.setOnAction(e -> scheduleImmediateFilterApply());
    }
    
    /** Update the "select all effects" checkbox state based on individual effect checkboxes. */
    private void updateSelectAllEffectsState() {
        boolean previousSuppressState = suppressFilterApplyEvents;
        suppressFilterApplyEvents = true;
        try {
            boolean allSelected = missenseCheckBox.isSelected()
                && synonymousCheckBox.isSelected()
                && stopFrameshiftCheckBox.isSelected()
                && spliceSiteCheckBox.isSelected()
                && utrCheckBox.isSelected()
                && noncodingCheckBox.isSelected()
                && intronicCheckBox.isSelected()
                && intergenicCheckBox.isSelected();
            selectAllEffectsCheckBox.setSelected(allSelected);
        } finally {
            suppressFilterApplyEvents = previousSuppressState;
        }
    }
    
    /** Update the "select all types" checkbox state based on individual type checkboxes. */
    private void updateSelectAllTypesState() {
        boolean previousSuppressState = suppressFilterApplyEvents;
        suppressFilterApplyEvents = true;
        try {
            boolean allSelected = true;
            for (CheckBox cb : variantTypeCheckBoxes.values()) {
                if (!cb.isSelected()) {
                    allSelected = false;
                    break;
                }
            }
            selectAllTypesCheckBox.setSelected(allSelected);
        } finally {
            suppressFilterApplyEvents = previousSuppressState;
        }
    }
    
    /**
     * Update variant type checkboxes based on types in loaded VCFs.
     * Keeps all existing checkboxes and adds new ones if needed.
     * Never deletes checkboxes when switching chromosomes.
     */
    private void populateVariantTypeFilters() {
        if (variantTypesContainer == null) return;
        boolean previousSuppressState = suppressFilterApplyEvents;
        suppressFilterApplyEvents = true;
        try {
        
        // Get variant types from the loaded data
        java.util.Set<VcfVariantType> presentTypes = collectPresentVariantTypes();
        
        // Detect if this is an SV-dominant file (has any actual SV types present)
        boolean hasSvTypes = presentTypes.stream().anyMatch(t -> 
            t == VcfVariantType.SV_DELETION || t == VcfVariantType.SV_INSERTION ||
            t == VcfVariantType.SV_DUPLICATION || t == VcfVariantType.SV_INVERSION ||
            t == VcfVariantType.SV_TRANSLOCATION || t == VcfVariantType.SV_BREAKEND);
        
        // Group types to avoid duplicates (e.g., both INSERTION and SV_INSERTION)
        java.util.Set<VcfVariantType> typesToShow = new java.util.LinkedHashSet<>();
        
        for (VcfVariantType type : presentTypes) {
            switch (type) {
                case SNV:
                case MNV:
                case COMPLEX:
                    // Always show these if present
                    typesToShow.add(type);
                    break;
                case INSERTION:
                    // Only show small INSERTION if there's no SV version or if this is not an SV file
                    if (!hasSvTypes || !presentTypes.contains(VcfVariantType.SV_INSERTION)) {
                        typesToShow.add(type);
                    }
                    break;
                case DELETION:
                    // Only show small DELETION if there's no SV version or if this is not an SV file
                    if (!hasSvTypes || !presentTypes.contains(VcfVariantType.SV_DELETION)) {
                        typesToShow.add(type);
                    }
                    break;
                case SV_INSERTION:
                case SV_DELETION:
                case SV_DUPLICATION:
                case SV_INVERSION:
                case SV_TRANSLOCATION:
                case SV_BREAKEND:
                    // Only show SV types if this is actually an SV file
                    if (hasSvTypes) {
                        typesToShow.add(type);
                    }
                    break;
            }
        }
        
        // Track which types need new checkboxes
        java.util.Set<VcfVariantType> addedInThisCall = new java.util.HashSet<>();
        
        // Add any new types we haven't seen before
        VariantFilter currentFilter = vcfManager.getCurrentLoadedFilter();
        if (currentFilter == null) {
            currentFilter = vcfManager.getCurrentFilter();
        }
        for (VcfVariantType type : typesToShow) {
            if (!variantTypeCheckBoxes.containsKey(type)) {
                // Create new checkbox for this type
                CheckBox cb = new CheckBox(getVariantTypeLabel(type));
                // Initialize based on current filter state
                boolean isTypeSelected = currentFilter != null && currentFilter.getAllowedTypes().contains(type);
                cb.setSelected(isTypeSelected);
                cb.getStyleClass().add("filter-checkbox");
                
                // Add listener to update "Select All" state and apply filters
                cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
                    updateSelectAllTypesState();
                    scheduleImmediateFilterApply();
                });
                
                // Map the type to this checkbox
                variantTypeCheckBoxes.put(type, cb);
                addedInThisCall.add(type);
                
                // For consolidated types, also map small versions to the same checkbox
                if (type == VcfVariantType.SV_INSERTION && presentTypes.contains(VcfVariantType.INSERTION)) {
                    variantTypeCheckBoxes.put(VcfVariantType.INSERTION, cb);
                } else if (type == VcfVariantType.SV_DELETION && presentTypes.contains(VcfVariantType.DELETION)) {
                    variantTypeCheckBoxes.put(VcfVariantType.DELETION, cb);
                }
            }
        }
        
        // Rebuild grid layout with all current checkboxes (only add new ones)
        int columnCount = 3;
        int row = 0;
        int col = 0;
        
        // Add newly created checkboxes to the grid
        for (VcfVariantType type : addedInThisCall) {
            CheckBox cb = variantTypeCheckBoxes.get(type);
            // Calculate position to append
            row = variantTypesContainer.getChildren().size() / columnCount;
            col = variantTypesContainer.getChildren().size() % columnCount;
            variantTypesContainer.add(cb, col, row);
        }
        
        // Update "Select All" checkbox state
        updateSelectAllTypesState();
        } finally {
            suppressFilterApplyEvents = previousSuppressState;
        }
    }
    
    /**
     * Collect all variant types present in cached chromosome data.
     */
    private java.util.Set<VcfVariantType> collectPresentVariantTypes() {
        java.util.Set<VcfVariantType> combined = EnumSet.noneOf(VcfVariantType.class);
        for (VcfManager.CachedChromosomeVariants cached : sourceVariantLists) {
            VariantList variants = cached.variants();
            if (variants != null && !variants.isEmpty()) {
                combined.addAll(variants.collectVariantTypes());
            }
        }
        return combined;
    }
    
    /**
     * Get user-friendly label for a variant type.
     */
    private String getVariantTypeLabel(VcfVariantType type) {
        return switch (type) {
            case SNV -> "SNV";
            case INSERTION -> "INS";
            case DELETION -> "DEL";
            case MNV -> "MNV";
            case SV_DELETION -> "DEL";
            case SV_INSERTION -> "INS";
            case SV_DUPLICATION -> "DUP";
            case SV_INVERSION -> "INV";
            case SV_TRANSLOCATION -> "TRA";
            case SV_BREAKEND -> "BND";
            case COMPLEX -> "Complex";
        };
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
            Set<VcfVariantType> types = filter.getAllowedTypes();
            
            // Update dynamic type checkboxes
            for (java.util.Map.Entry<VcfVariantType, CheckBox> entry : variantTypeCheckBoxes.entrySet()) {
                entry.getValue().setSelected(types.contains(entry.getKey()));
            }

            // Load effect checkboxes based on allowedEffects
            Set<VariantEffect> effects = filter.getAllowedEffects();
            
            // Set specific coding sub-checkboxes
            missenseCheckBox.setSelected(effects.contains(VariantEffect.CODING_MISSENSE));
            synonymousCheckBox.setSelected(effects.contains(VariantEffect.CODING_SYNONYMOUS));
            stopFrameshiftCheckBox.setSelected(effects.contains(VariantEffect.CODING_STOP_GAIN) || effects.contains(VariantEffect.CODING_FRAMESHIFT));
            spliceSiteCheckBox.setSelected(effects.contains(VariantEffect.SPLICE_SITE));
            utrCheckBox.setSelected(effects.contains(VariantEffect.UTR5) || effects.contains(VariantEffect.UTR3));
            noncodingCheckBox.setSelected(effects.contains(VariantEffect.NONCODING_GENE));
            intronicCheckBox.setSelected(effects.contains(VariantEffect.INTRONIC));
            intergenicCheckBox.setSelected(effects.contains(VariantEffect.INTERGENIC));

            qualitySlider.setValue(filter.getMinQuality());
            coverageSlider.setValue(filter.getMinDepth());
            alleleFreqSlider.setValue(filter.getMinAlleleFraction());
            cancerOnlyCheckBox.setSelected(filter.isCancerGenesOnly());

            if (sharedSampleRangeSlider != null) {
                syncSharedSampleRangeBounds();
                int maxShare = filter.getMaxSharedSamples();
                if (maxShare == Integer.MAX_VALUE) {
                    maxShare = sharedSampleRangeSlider.getAbsoluteMax();
                }
                sharedSampleRangeSlider.setRange(filter.getMinSharedSamples(), maxShare);
            }

            if (geneLevelComparisonCheckBox != null) {
                geneLevelComparisonCheckBox.setSelected(filter.isGeneLevel());
                updateComparisonWindowEnabled();
                updateCommonVariantsHelpLabel();
            }
            if (comparisonWindowField != null) {
                comparisonWindowField.setText(Integer.toString(Math.max(0, filter.getComparisonWindowBp())));
            }

            // Restore group comparison UI
            comparisonGroupRoles.clear();
            comparisonGroupRoles.putAll(filter.getGroupRoles());
            applyPresentMatchModeToRadios(filter.getPresentMatchMode());
            refreshComparisonGroupsUI();
            
            // Load advanced filters
            if (advancedFiltersContainer != null) {
                advancedFiltersContainer.getChildren().clear();
                for (java.util.Map.Entry<String, String> entry : filter.getInfoFieldFilters().entrySet()) {
                    addInfoFilterRule(entry.getKey(), entry.getValue());
                }
                for (String filterValue : filter.getAllowedFilterValues()) {
                    addFilterFieldRule(filterValue);
                }
            }
            
            // Update select all effects checkbox state
            updateSelectAllEffectsState();
        } finally {
            suppressFilterApplyEvents = false;
            cancelPendingFilterTimers();
            pendingReloadFilter = null;
            hideReloadBanner();
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

        // Variant types - collect from dynamic checkboxes
        Set<VcfVariantType> types = new HashSet<>();
        if (variantTypeCheckBoxes.isEmpty()) {
            types.addAll(EnumSet.allOf(VcfVariantType.class));
        } else {
            for (java.util.Map.Entry<VcfVariantType, CheckBox> entry : variantTypeCheckBoxes.entrySet()) {
                if (entry.getValue().isSelected()) {
                    types.add(entry.getKey());
                }
            }
        }
        filter.setAllowedTypes(types);

        // Effect categories - collect specific effects
        Set<VariantEffect> effects = EnumSet.noneOf(VariantEffect.class);
        
        // Add coding effect sub-types
        if (missenseCheckBox.isSelected()) effects.add(VariantEffect.CODING_MISSENSE);
        if (synonymousCheckBox.isSelected()) effects.add(VariantEffect.CODING_SYNONYMOUS);
        if (stopFrameshiftCheckBox.isSelected()) {
            effects.add(VariantEffect.CODING_STOP_GAIN);
            effects.add(VariantEffect.CODING_STOP_LOSS);
            effects.add(VariantEffect.CODING_FRAMESHIFT);
        }
        
        // Add regulatory effects
        if (spliceSiteCheckBox.isSelected()) effects.add(VariantEffect.SPLICE_SITE);
        if (utrCheckBox.isSelected()) {
            effects.add(VariantEffect.UTR5);
            effects.add(VariantEffect.UTR3);
        }
        if (noncodingCheckBox.isSelected()) effects.add(VariantEffect.NONCODING_GENE);
        
        // Add intronic
        if (intronicCheckBox.isSelected()) effects.add(VariantEffect.INTRONIC);
        
        // Add intergenic
        if (intergenicCheckBox.isSelected()) effects.add(VariantEffect.INTERGENIC);
        
        filter.setAllowedEffects(effects);
        // Quality
        try {
            filter.setMinQuality(Double.parseDouble(qualityField.getText().trim()));
        } catch (NumberFormatException ignored) {}
        
        // Depth
        try {
            filter.setMinDepth(Integer.parseInt(coverageField.getText().trim()));
        } catch (NumberFormatException ignored) {}
        
        // Allele fraction
        try {
            filter.setMinAlleleFraction(Double.parseDouble(alleleFreqField.getText().trim()));
        } catch (NumberFormatException ignored) {}

        // Cancer genes
        filter.setCancerGenesOnly(cancerOnlyCheckBox.isSelected());

        // Shared sample count range
        if (sharedSampleRangeSlider != null) {
            filter.setMinSharedSamples(sharedSampleRangeSlider.getLowValue());
            int high = sharedSampleRangeSlider.getHighValue();
            int total = sharedSampleRangeSlider.getAbsoluteMax();
            // Full range means no upper bound so new samples do not silently filter out
            filter.setMaxSharedSamples(high >= total ? Integer.MAX_VALUE : high);
        }

        filter.setGeneLevel(geneLevelComparisonCheckBox != null && geneLevelComparisonCheckBox.isSelected());
        filter.setComparisonWindowBp(readComparisonWindowBp());

        // Named-group comparison roles
        Map<Integer, VariantFilter.GroupRole> roles = new HashMap<>();
        for (Map.Entry<Integer, VariantFilter.GroupRole> entry : comparisonGroupRoles.entrySet()) {
            if (entry.getValue() != null && entry.getValue() != VariantFilter.GroupRole.IGNORE) {
                roles.put(entry.getKey(), entry.getValue());
            }
        }
        filter.setGroupRoles(roles);
        filter.setGroupTrackIndices(resolveGroupTrackIndices(roles.keySet()));
        filter.setPresentMatchMode(selectedPresentMatchMode());

        // Advanced filters - extract from UI
        java.util.Map<String, String> infoFilters = new java.util.HashMap<>();
        java.util.Set<String> filterValues = new java.util.HashSet<>();
        
        for (javafx.scene.Node node : advancedFiltersContainer.getChildren()) {
            if (node instanceof javafx.scene.layout.HBox) {
                javafx.scene.layout.HBox ruleBox = (javafx.scene.layout.HBox) node;
                if (!ruleBox.getChildren().isEmpty() && ruleBox.getChildren().get(0) instanceof javafx.scene.control.Label) {
                    javafx.scene.control.Label label = (javafx.scene.control.Label) ruleBox.getChildren().get(0);
                    String text = label.getText();
                    if (text.startsWith("INFO.")) {
                        // Parse "INFO.FIELD = VALUE"
                        String[] parts = text.substring(5).split(" = ");
                        if (parts.length == 2) {
                            infoFilters.put(parts[0], parts[1]);
                        }
                    } else if (text.startsWith("FILTER = ")) {
                        // Parse "FILTER = VALUE"
                        filterValues.add(text.substring(9));
                    }
                }
            }
        }
        
        filter.setInfoFieldFilters(infoFilters);
        filter.setAllowedFilterValues(filterValues);

        return filter;
    }

    // ── Action Handlers ───────────────────────────────────────────────────────

    @FXML
    private void handleApplyFilters() {
        scheduleImmediateFilterApply();
    }

    @FXML
    private void handleAnnotateAllChromosomes() {
        if (vcfManager == null || allChromosomeAnnotationRunning) {
            return;
        }

        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        List<String> chromosomes = new ArrayList<>();
        if (!stackManager.isEmpty()) {
            org.baseplayer.draw.DrawStack drawStack = stackManager.getFirst();
            if (drawStack != null && drawStack.chromosomeDropdown != null) {
                chromosomes.addAll(drawStack.chromosomeDropdown.getItems());
            }
        }

        if (chromosomes.isEmpty()) {
            setPlaceholder("No chromosomes available in dropdown");
            return;
        }

        cancelDelayedLoadingModal();
        allChromosomeAnnotationRunning = true;
        allChromosomeAnnotationTask = null;

        lockFilterControls(true);
        syncBusyOverlay();

        // Defer the expensive snapshot creation and task submission to let modal display first
        Platform.runLater(() -> {
            VariantFilter filterSnapshot = buildFilterFromUI();
            allChromosomeAnnotationTask = vcfManager.annotateAllReferenceChromosomes(
                filterSnapshot,
                chromosomes,
                this::updateAllChromosomeProgress,
                this::completeAllChromosomeAnnotation);

            if (allChromosomeAnnotationTask != null) {
                allChromosomeAnnotationTask.setProgressSuffix(
                    "0/" + chromosomes.size() + " chromosomes, rows: 0");
                ThreadRunner.get().notifyDescriptionChanged();
            } else {
                allChromosomeAnnotationRunning = false;
                lockFilterControls(false);
                hideLoadingModal();
                setPlaceholder("No chromosomes available for annotation");
            }
        });
    }

    @FXML
    private void handleCancelLoadingModal() {
        ThreadRunner.RunnerTask task = allChromosomeAnnotationTask;
        if (task != null) {
            task.cancel();
        } else {
            ThreadRunner.get().cancelAll();
        }
        allChromosomeAnnotationRunning = false;
        allChromosomeAnnotationTask = null;
        lockFilterControls(false);
        hideLoadingModal();
    }

    private void applyFiltersNow() {
        if (suppressFilterApplyEvents || allChromosomeAnnotationRunning) {
            return;
        }

        VariantFilter filter = buildFilterFromUI();

        if (vcfManager != null) {
            vcfManager.setCurrentFilterForNextLoad(filter);
            vcfManager.applyFilter(filter);
        }
        pendingReloadFilter = filter;
        showReloadBanner("Reload needed");
        scheduleRebuild(filter);
    }

    @FXML
    private void handleReloadFilteredVariants() {
        if (chromosome == null || chromosome.isBlank()) {
            return;
        }
        VariantFilter target = pendingReloadFilter != null ? pendingReloadFilter : buildFilterFromUI();
        pendingReloadFilter = null;
        hideReloadBanner();
        sourceVariants = null;
        clearTableItemsForChromosomeSwitch();
        setPlaceholder("Reloading variants for " + chromosome + "…");
        vcfManager.setCurrentFilterForNextLoad(target);
        vcfManager.clearCurrentChromosomeVariants();
        vcfManager.reloadCurrentChromosome();
    }

    @FXML
    private void handleResetFilters() {
        // Reset all dynamic type checkboxes
        for (CheckBox cb : variantTypeCheckBoxes.values()) {
            cb.setSelected(true);
        }
        selectAllTypesCheckBox.setSelected(true);
        // Reset all effect checkboxes
        missenseCheckBox.setSelected(true);
        synonymousCheckBox.setSelected(true);
        stopFrameshiftCheckBox.setSelected(true);
        spliceSiteCheckBox.setSelected(true);
        utrCheckBox.setSelected(true);
        noncodingCheckBox.setSelected(true);
        intronicCheckBox.setSelected(true);
        intergenicCheckBox.setSelected(true);
        selectAllEffectsCheckBox.setSelected(true);
        qualitySlider.setValue(0);
        coverageSlider.setValue(0);
        alleleFreqSlider.setValue(0);
        cancerOnlyCheckBox.setSelected(false);

        if (sharedSampleRangeSlider != null) {
            syncSharedSampleRangeBounds();
            sharedSampleRangeSlider.resetToFullRange();
        }
        if (geneLevelComparisonCheckBox != null) {
            geneLevelComparisonCheckBox.setSelected(false);
            updateComparisonWindowEnabled();
            updateCommonVariantsHelpLabel();
        }
        if (comparisonWindowField != null) {
            comparisonWindowField.setText("0");
        }
        comparisonGroupRoles.clear();
        applyPresentMatchModeToRadios(VariantFilter.PresentMatchMode.ALL);
        refreshComparisonGroupsUI();
        
        // Clear advanced filters
        if (advancedFiltersContainer != null) {
            advancedFiltersContainer.getChildren().clear();
        }
        
        handleApplyFilters();
    }

    @FXML
    private void handleApplyComparison() {
        scheduleImmediateFilterApply();
    }

    @FXML
    private void handleApplyControlSettings() {
        // TODO: Implement control file settings
        System.out.println("Control file settings not yet implemented");
    }

    @FXML
    private void handleAddControlVcf() {
        // TODO: Implement add control VCF
        System.out.println("Add control VCF not yet implemented");
    }

    @FXML
    private void handleAddControlBed() {
        // TODO: Implement add control BED
        System.out.println("Add control BED not yet implemented");
    }

    @FXML
    private void handleRemoveControlFile() {
        // TODO: Implement remove control file
        System.out.println("Remove control file not yet implemented");
    }

    @FXML
    private void handleAddInfoFilter() {
        showInfoFilterDialog();
    }

    @FXML
    private void handleAddFilterField() {
        showFilterFieldDialog();
    }

    private void showInfoFilterDialog() {
        javafx.scene.control.Dialog<javafx.util.Pair<String, String>> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Add INFO Field Filter");
        dialog.setHeaderText("Specify an INFO field and expected value\n\nNote: INFO/FILTER filtering will be applied once VariantNode stores these fields.");
        
        javafx.scene.control.ButtonType addButtonType = new javafx.scene.control.ButtonType("Add", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, javafx.scene.control.ButtonType.CANCEL);
        
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(20, 150, 10, 10));
        
        javafx.scene.control.TextField fieldName = new javafx.scene.control.TextField();
        fieldName.setPromptText("e.g., SVTYPE");
        javafx.scene.control.TextField fieldValue = new javafx.scene.control.TextField();
        fieldValue.setPromptText("e.g., DEL");
        
        grid.add(new javafx.scene.control.Label("INFO Field Name:"), 0, 0);
        grid.add(fieldName, 1, 0);
        grid.add(new javafx.scene.control.Label("Expected Value:"), 0, 1);
        grid.add(fieldValue, 1, 1);
        
        dialog.getDialogPane().setContent(grid);
        javafx.application.Platform.runLater(() -> fieldName.requestFocus());
        
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == addButtonType) {
                return new javafx.util.Pair<>(fieldName.getText().trim(), fieldValue.getText().trim());
            }
            return null;
        });
        
        dialog.showAndWait().ifPresent(pair -> {
            if (!pair.getKey().isEmpty() && !pair.getValue().isEmpty()) {
                addInfoFilterRule(pair.getKey(), pair.getValue());
                handleApplyFilters();
            }
        });
    }

    private void showFilterFieldDialog() {
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Add FILTER Field Value");
        dialog.setHeaderText("Specify allowed FILTER values (e.g., PASS, LowQual)\n\nNote: INFO/FILTER filtering will be applied once VariantNode stores these fields.");
        
        javafx.scene.control.ButtonType addButtonType = new javafx.scene.control.ButtonType("Add", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, javafx.scene.control.ButtonType.CANCEL);
        
        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(10);
        vbox.setPadding(new javafx.geometry.Insets(20, 150, 10, 10));
        
        javafx.scene.control.TextField filterValue = new javafx.scene.control.TextField();
        filterValue.setPromptText("e.g., PASS");
        javafx.scene.control.Label hint = new javafx.scene.control.Label("Only variants with this FILTER value will be shown.");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        
        vbox.getChildren().addAll(new javafx.scene.control.Label("FILTER Value:"), filterValue, hint);
        
        dialog.getDialogPane().setContent(vbox);
        javafx.application.Platform.runLater(() -> filterValue.requestFocus());
        
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == addButtonType) {
                return filterValue.getText().trim();
            }
            return null;
        });
        
        dialog.showAndWait().ifPresent(value -> {
            if (!value.isEmpty()) {
                addFilterFieldRule(value);
                handleApplyFilters();
            }
        });
    }

    private void addInfoFilterRule(String fieldName, String fieldValue) {
        // Add to UI
        javafx.scene.layout.HBox ruleBox = new javafx.scene.layout.HBox(10);
        ruleBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        javafx.scene.control.Label ruleLabel = new javafx.scene.control.Label("INFO." + fieldName + " = " + fieldValue);
        ruleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");
        
        javafx.scene.control.Button removeBtn = new javafx.scene.control.Button("×");
        removeBtn.setStyle("-fx-font-size: 14px; -fx-padding: 0 5 0 5;");
        removeBtn.getStyleClass().add("secondary-button");
        removeBtn.setOnAction(e -> {
            advancedFiltersContainer.getChildren().remove(ruleBox);
            handleApplyFilters();
        });
        
        ruleBox.getChildren().addAll(ruleLabel, removeBtn);
        advancedFiltersContainer.getChildren().add(ruleBox);
    }

    private void addFilterFieldRule(String filterValue) {
        // Add to UI
        javafx.scene.layout.HBox ruleBox = new javafx.scene.layout.HBox(10);
        ruleBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        javafx.scene.control.Label ruleLabel = new javafx.scene.control.Label("FILTER = " + filterValue);
        ruleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");
        
        javafx.scene.control.Button removeBtn = new javafx.scene.control.Button("×");
        removeBtn.setStyle("-fx-font-size: 14px; -fx-padding: 0 5 0 5;");
        removeBtn.getStyleClass().add("secondary-button");
        removeBtn.setOnAction(e -> {
            advancedFiltersContainer.getChildren().remove(ruleBox);
            handleApplyFilters();
        });
        
        ruleBox.getChildren().addAll(ruleLabel, removeBtn);
        advancedFiltersContainer.getChildren().add(ruleBox);
    }

    // ── Agent (AI Analysis) ───────────────────────────────────────────────────

    private void setupAgentTab() {
        Preferences prefs = Preferences.userNodeForPackage(VariantManagerController.class);
        apiKeyField.setText(prefs.get(PREF_API_KEY, ""));
        agentModelField.setText(prefs.get(PREF_API_MODEL, "gemini-2.0-flash"));

        filterTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            boolean isAgent = (newTab == agentTab);
            resultsTabPane.setVisible(!isAgent);
            resultsTabPane.setManaged(!isAgent);
            VBox.setVgrow(filterTabPane, isAgent ? Priority.ALWAYS : Priority.NEVER);
        });
    }

    @FXML
    private void handleAgentSubmit() {
        String apiKey = apiKeyField.getText().trim();
        if (apiKey.isEmpty()) {
            agentStatusLabel.setText("Please enter an AI Studio API key.");
            return;
        }
        String model = agentModelField.getText().trim();
        if (model.isEmpty()) model = "gemini-2.0-flash";
        String prompt = agentPromptArea.getText().trim();
        if (prompt.isEmpty()) {
            agentStatusLabel.setText("Please enter a prompt.");
            return;
        }
        if (agentRunning) return;

        Preferences prefs = Preferences.userNodeForPackage(VariantManagerController.class);
        prefs.put(PREF_API_KEY, apiKey);
        prefs.put(PREF_API_MODEL, model);

        agentRunning = true;
        agentSubmitButton.setDisable(true);
        agentStatusLabel.setText("Analyzing…");
        agentResponseArea.clear();

        final String capturedModel   = model;
        final String capturedContext = buildVariantContext();
        final String fullPrompt = "You are a genomics expert assistant. Below is a summary of the genomic variants currently loaded in the BasePlayer2 viewer.\n\n"
                + capturedContext + "\n\nUser question: " + prompt;

        Thread thread = new Thread(() -> {
            try {
                String response = callGeminiApi(apiKey, capturedModel, fullPrompt);
                Platform.runLater(() -> {
                    agentResponseArea.setText(response);
                    agentStatusLabel.setText("Done.");
                    agentRunning = false;
                    agentSubmitButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    agentStatusLabel.setText("Error: " + e.getMessage());
                    agentRunning = false;
                    agentSubmitButton.setDisable(false);
                });
            }
        }, "agent-api-call");
        thread.setDaemon(true);
        thread.start();
    }

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

    private String callGeminiApi(String apiKey, String model, String prompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(message);
        JsonObject body = new JsonObject();
        body.add("contents", contents);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String errorMsg;
            try {
                JsonObject err = JsonParser.parseString(response.body()).getAsJsonObject();
                errorMsg = err.getAsJsonObject("error").get("message").getAsString();
            } catch (Exception ignored) {
                errorMsg = "HTTP " + response.statusCode();
            }
            throw new RuntimeException(errorMsg);
        }

        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        return responseJson.getAsJsonArray("candidates")
                .get(0).getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    // ── Data Loading ──────────────────────────────────────────────────────────

    private void loadData() {
        if (vcfManager == null) {
            return;
        }

        if (allChromosomeAnnotationRunning) {
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
            cancelDelayedLoadingModal();
            return;
        }

        // Update variant type filters after load completes to avoid repeated full-list scans
        // during progressive loading.
        populateVariantTypeFilters();

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
            cancelDelayedLoadingModal();
            return;
        }
        rebuildRunning = true;
        rebuildNeeded = false;
        final List<VcfManager.CachedChromosomeVariants> snapshots = new ArrayList<>(sourceVariantLists);
        final VariantFilter filterSnapshot = filter == null ? new VariantFilter() : filter.copy();

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
                    cancelDelayedLoadingModal();
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

                cancelDelayedLoadingModal();
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

    private void showReloadBanner(String message) {
        if (reloadBannerLabel != null) {
            reloadBannerLabel.setText(message);
        }
        if (reloadBanner != null) {
            reloadBanner.setVisible(true);
        }
    }

    private void hideReloadBanner() {
        if (reloadBanner != null) {
            reloadBanner.setVisible(false);
        }
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
        // In-window overlay stays with the Variant Manager scene; do not hide it on focus changes.
    }

    private void applyLoadingModalVisuals(String message) {
        if (loadingModal == null) {
            return;
        }
        loadingLabel.setText(message == null || message.isBlank() ? "Loading" : message);
        if (loadingSpinner != null) {
            loadingSpinner.setManaged(false);
            loadingSpinner.setVisible(false);
        }
        if (loadingProgressBar != null) {
            loadingProgressBar.setManaged(false);
            loadingProgressBar.setVisible(false);
        }
        if (loadingEtaLabel != null) {
            loadingEtaLabel.setManaged(false);
            loadingEtaLabel.setVisible(false);
        }
        if (loadingCancelButton != null) {
            loadingCancelButton.setManaged(true);
            loadingCancelButton.setVisible(true);
            loadingCancelButton.setDisable(false);
        }
    }

    private void updateAllChromosomeProgress(VcfManager.AllChromosomeProgress progress) {
        if (!allChromosomeAnnotationRunning) {
            return;
        }

        int completed = progress.completedChromosomes();
        String message = progress.chromosome()
            + " (" + completed + "/" + progress.totalChromosomes() + ")"
            + ", rows: " + progress.totalRows();
        ThreadRunner.RunnerTask task = allChromosomeAnnotationTask;
        if (task != null) {
            task.setProgressSuffix(message);
            ThreadRunner.get().notifyDescriptionChanged();
        }
        applyLoadingModalVisuals("Annotating all chromosomes… " + message);
    }

    private void completeAllChromosomeAnnotation(VcfManager.AllChromosomeAnnotationResult result) {
        ThreadRunner.RunnerTask task = allChromosomeAnnotationTask;
        if (task != null) {
            task.setProgressSuffix("");
            ThreadRunner.get().notifyDescriptionChanged();
        }

        allChromosomeAnnotationRunning = false;
        allChromosomeAnnotationTask = null;
        lockFilterControls(false);
        hideLoadingModal();

        // Annotate-all already materializes every chromosome with the current UI filter,
        // so a leftover "Reload needed" banner from soft filter apply is stale.
        if (result != null && !result.cancelled()) {
            pendingReloadFilter = null;
            hideReloadBanner();
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

    private void lockFilterControls(boolean locked) {
        if (filterTabPane != null) {
            filterTabPane.setDisable(locked);
        }
        if (annotateAllChromosomesButton != null) {
            annotateAllChromosomesButton.setDisable(locked);
        }
        if (reloadBannerButton != null) {
            reloadBannerButton.setDisable(locked);
        }
        if (locked) {
            showBusyOverlay();
        }
    }

    public void syncBusyOverlay() {
        boolean tasksRunning = !ThreadRunner.get().getActiveTasks().isEmpty();
        if (!tasksRunning) {
            allChromosomeAnnotationRunning = false;
            allChromosomeAnnotationTask = null;
        }
        boolean busy = tasksRunning || allChromosomeAnnotationRunning;
        if (busy) {
            if (filterTabPane != null) {
                filterTabPane.setDisable(true);
            }
            if (annotateAllChromosomesButton != null) {
                annotateAllChromosomesButton.setDisable(true);
            }
            if (reloadBannerButton != null) {
                reloadBannerButton.setDisable(true);
            }
            showBusyOverlay();
        } else {
            if (filterTabPane != null) {
                filterTabPane.setDisable(false);
            }
            if (annotateAllChromosomesButton != null) {
                annotateAllChromosomesButton.setDisable(false);
            }
            if (reloadBannerButton != null) {
                reloadBannerButton.setDisable(false);
            }
            hideLoadingModal();
        }
    }

    private void showBusyOverlay() {
        String message = "Loading";
        List<ThreadRunner.RunnerTask> tasks = ThreadRunner.get().getActiveTasks();
        if (!tasks.isEmpty()) {
            message = tasks.get(tasks.size() - 1).getDescription();
        } else if (allChromosomeAnnotationRunning) {
            message = "Annotating all chromosomes…";
        }
        applyLoadingModalVisuals(message);
        if (loadingModal != null) {
            loadingModal.setVisible(true);
            loadingModal.setManaged(true);
        }
    }

    private void hideLoadingModal() {
        hideLoadingModalVisualOnly();
    }

    private void hideLoadingModalVisualOnly() {
        if (loadingModal != null) {
            loadingModal.setVisible(false);
            loadingModal.setManaged(false);
        }
    }

    private void cancelDelayedLoadingModal() {
        if (loadingModalDelayTimer != null) {
            loadingModalDelayTimer.stop();
            loadingModalDelayTimer = null;
        }
        if (!allChromosomeAnnotationRunning) {
            hideLoadingModal();
        }
    }

    private void refreshReloadBannerState() {
        if (pendingReloadFilter != null) {
            showReloadBanner("Reload needed");
						return;
        }
            hideReloadBanner();
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

    // ── Control File Model ────────────────────────────────────────────────────

    public static class ControlFileEntry {
        private boolean enabled;
        private String fileName;
        private String fileType;

        public ControlFileEntry(boolean enabled, String fileName, String fileType) {
            this.enabled = enabled;
            this.fileName = fileName;
            this.fileType = fileType;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
    }
}
