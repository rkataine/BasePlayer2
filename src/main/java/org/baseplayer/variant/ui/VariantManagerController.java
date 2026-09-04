package org.baseplayer.variant.ui;

import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.Duration;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.prefs.Preferences;

/**
 * Controller for the FXML-based Variant Manager dialog.
 * Provides tabbed filtering interface and variant annotation tables.
 */
public class VariantManagerController implements Initializable {

    private static final String TEXT           = "white";
    private static final String CANCER_COLOR    = "#d16624";
    // Effect-based text colors
    private static final String COLOR_SYNONYMOUS  = "#4caf50";  // Green
    private static final String COLOR_MISSENSE    = "#ff9800";  // Orange
    private static final String COLOR_TRUNCATING  = "#f44336";  // Red
    private static final String COLOR_NONCODING   = "#9e9e9e";  // Light gray

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
    @FXML private HBox reloadBanner;
    @FXML private Label reloadBannerLabel;
    @FXML private Button reloadBannerButton;

    // Loading Modal
    @FXML private VBox loadingModal;
    @FXML private ProgressIndicator loadingSpinner;
    @FXML private Label loadingLabel;

    // Filter Tab: Sample Comparison
    @FXML private RadioButton showAllSamplesRadio, sharedVariantsRadio, uniqueVariantsRadio, differentialRadio;
    @FXML private ListView<String> groupASampleList, groupBSampleList;
    @FXML private CheckBox homozygousCheckBox, heterozygousCheckBox;
    private ToggleGroup comparisonModeGroup;

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
    @FXML private TableView<AnnotationRow> codingTable, intronicTable, intergenicTable;
    @FXML private Tab codingTab, intronicTab, intergenicTab;

    // Table Columns - Coding
    @FXML private TableColumn<AnnotationRow, AnnotationRow> codingGeneColumn;
    @FXML private TableColumn<AnnotationRow, String> codingPositionColumn, codingRefAltColumn, codingTypeColumn;
    @FXML private TableColumn<AnnotationRow, String> codingEffectColumn, codingAaChangeColumn, codingCodonChangeColumn;
    @FXML private TableColumn<AnnotationRow, String> codingSamplesColumn, codingQualityColumn;

    // Table Columns - Intronic
    @FXML private TableColumn<AnnotationRow, AnnotationRow> intronicGeneColumn;
    @FXML private TableColumn<AnnotationRow, String> intronicPositionColumn, intronicRefAltColumn, intronicTypeColumn;
    @FXML private TableColumn<AnnotationRow, String> intronicSamplesColumn, intronicQualityColumn;

    // Table Columns - Intergenic
    @FXML private TableColumn<AnnotationRow, AnnotationRow> intergenicGeneColumn;
    @FXML private TableColumn<AnnotationRow, String> intergenicPositionColumn, intergenicRefAltColumn, intergenicTypeColumn;
    @FXML private TableColumn<AnnotationRow, String> intergenicSamplesColumn, intergenicQualityColumn;

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

    // ── State ─────────────────────────────────────────────────────────────────

    private VcfManager vcfManager;
    private Runnable onClose;
    private Stage stage;

    private VariantList sourceVariants;
    private String chromosome;
    private String waitingForChromosome;
    private ChangeListener<Boolean> updateListener;
    private volatile boolean annotationRunning;
    private volatile Thread annotationThread;
    
    // Debounce timer for real-time slider updates (200ms delay after last change)
    private Timeline filterDebounceTimer;
    // Short-delay timer for checkbox/filter actions so UI paints first
    private Timeline immediateFilterApplyTimer;
    // Delay showing loading modal so quick updates don't flash a spinner
    private Timeline loadingModalDelayTimer;
    private int lastBuiltSize = -1;
    private volatile boolean rebuildRunning = false;
    private volatile boolean rebuildNeeded = false;
    private boolean suppressFilterApplyEvents = false;
    private VariantFilter pendingReloadFilter;
    private static final String PREF_API_KEY   = "gemini_api_key";
    private static final String PREF_API_MODEL = "gemini_model";
    private volatile boolean agentRunning = false;
    
    // Dynamic variant type checkboxes
    private java.util.Map<VcfVariantType, CheckBox> variantTypeCheckBoxes = new java.util.HashMap<>();

    // ── Initialization ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Initialize radio button group for comparison mode
        comparisonModeGroup = new ToggleGroup();
        showAllSamplesRadio.setToggleGroup(comparisonModeGroup);
        sharedVariantsRadio.setToggleGroup(comparisonModeGroup);
        uniqueVariantsRadio.setToggleGroup(comparisonModeGroup);
        differentialRadio.setToggleGroup(comparisonModeGroup);

        // Bind sliders to text fields and labels
        setupSliderBindings();

        // Set up table columns
        setupTableColumns();

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
        this.onClose = onClose;

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

    /**
     * Called when the dialog is closed.
     */
    public void cleanup() {
        if (annotationThread != null && annotationThread.isAlive()) {
            annotationThread.interrupt();
        }
        if (updateListener != null) {
            GenomicCanvas.update.removeListener(updateListener);
        }
        cancelDelayedLoadingModal();
        vcfManager.clearFilter();
        vcfManager.setOnVcfAdded(null);
        ServiceRegistry.getInstance().getSampleRegistry().clearFocusedTrackIndices();
        if (onClose != null) {
            onClose.run();
        }
    }

    // ── Slider Bindings ───────────────────────────────────────────────────────

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
    
    /**
     * Schedule a debounced filter update. Restarts the timer on each call,
     * so rapid slider movements only trigger one update 200ms after the last change.
     */
    private void scheduleFilterUpdate() {
        if (suppressFilterApplyEvents) {
            return;
        }
        if (filterDebounceTimer != null) {
            filterDebounceTimer.stop();
            filterDebounceTimer.playFromStart();
        }
    }

    private void scheduleImmediateFilterApply() {
        if (suppressFilterApplyEvents) {
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
        suppressFilterApplyEvents = true;
        boolean allSelected = missenseCheckBox.isSelected()
            && synonymousCheckBox.isSelected()
            && stopFrameshiftCheckBox.isSelected()
            && spliceSiteCheckBox.isSelected()
            && utrCheckBox.isSelected()
            && noncodingCheckBox.isSelected()
            && intronicCheckBox.isSelected()
            && intergenicCheckBox.isSelected();
        selectAllEffectsCheckBox.setSelected(allSelected);
        suppressFilterApplyEvents = false;
    }
    
    /** Update the "select all types" checkbox state based on individual type checkboxes. */
    private void updateSelectAllTypesState() {
        suppressFilterApplyEvents = true;
        boolean allSelected = true;
        for (CheckBox cb : variantTypeCheckBoxes.values()) {
            if (!cb.isSelected()) {
                allSelected = false;
                break;
            }
        }
        selectAllTypesCheckBox.setSelected(allSelected);
        suppressFilterApplyEvents = false;
    }
    
    /**
     * Update variant type checkboxes based on types in loaded VCFs.
     * Keeps all existing checkboxes and adds new ones if needed.
     * Never deletes checkboxes when switching chromosomes.
     */
    private void populateVariantTypeFilters() {
        if (variantTypesContainer == null) return;
        
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
        VariantFilter currentFilter = vcfManager.getCurrentFilter();
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
        java.util.Set<CheckBox> alreadyInGrid = new java.util.HashSet<>();
        
        // Collect existing checkboxes from the grid
        for (javafx.scene.Node node : variantTypesContainer.getChildren()) {
            if (node instanceof CheckBox) {
                alreadyInGrid.add((CheckBox) node);
            }
        }
        
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
    }
    
    /**
     * Collect all variant types present in the currently loaded chromosome data.
     */
    private java.util.Set<VcfVariantType> collectPresentVariantTypes() {
        if (sourceVariants != null) {
            return sourceVariants.collectVariantTypes();
        }
        return java.util.Collections.emptySet();
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

    private void setupTableColumns() {
        // Coding table
        setupGeneColumn(codingGeneColumn);
        setupTextColumn(codingPositionColumn, "position");
        setupTextColumn(codingRefAltColumn, "refAlt");
        setupTextColumn(codingTypeColumn, "variantType");
        setupTextColumn(codingEffectColumn, "effectDisplay");
        setupTextColumn(codingAaChangeColumn, "aaChange");
        setupTextColumn(codingCodonChangeColumn, "codonChange");
        setupTextColumn(codingSamplesColumn, "sampleCount");
        setupTextColumn(codingQualityColumn, "maxQuality");

        // Intronic table
        setupGeneColumn(intronicGeneColumn);
        setupTextColumn(intronicPositionColumn, "position");
        setupTextColumn(intronicRefAltColumn, "refAlt");
        setupTextColumn(intronicTypeColumn, "variantType");
        setupTextColumn(intronicSamplesColumn, "sampleCount");
        setupTextColumn(intronicQualityColumn, "maxQuality");

        // Intergenic table
        setupGeneColumn(intergenicGeneColumn);
        setupTextColumn(intergenicPositionColumn, "position");
        setupTextColumn(intergenicRefAltColumn, "refAlt");
        setupTextColumn(intergenicTypeColumn, "variantType");
        setupTextColumn(intergenicSamplesColumn, "sampleCount");
        setupTextColumn(intergenicQualityColumn, "maxQuality");

        // Set up row coloring based on variant effect
        setupTableRowFactory(codingTable);
        setupTableRowFactory(intronicTable);
        setupTableRowFactory(intergenicTable);
    }

    private void setupGeneColumn(TableColumn<AnnotationRow, AnnotationRow> column) {
        column.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(AnnotationRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null || row.geneName == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                HBox hbox = new HBox(4);
                hbox.setAlignment(Pos.CENTER_LEFT);
                Label lbl = new Label(row.geneName);
                // Use cancer color if cancer gene, otherwise use effect-based color
                String textColor = row.isCancerGene ? CANCER_COLOR : row.getRowTextColor();
                lbl.setStyle("-fx-text-fill: " + textColor + ";");
                hbox.getChildren().add(lbl);
                if (row.isCancerGene && row.cosmicTier != null) {
                    Label tier = new Label("T" + row.cosmicTier);
                    tier.setStyle("-fx-background-color: " + CANCER_COLOR + "; -fx-text-fill: white;"
                            + " -fx-padding: 0 3 0 3; -fx-font-size: 9; -fx-background-radius: 3;");
                    hbox.getChildren().add(tier);
                }
                setGraphic(hbox);
                setText(null);

                setOnMouseClicked(event -> {
                    if (event.getClickCount() != 2 || row == null || row.geneName == null || row.geneName.isBlank()) {
                        return;
                    }
                    handleGeneRowDoubleClick(row);
                    event.consume();
                });
            }
        });
    }

    private void handleGeneRowDoubleClick(AnnotationRow row) {
        String geneName = row.geneName;
        if (geneName == null || geneName.isBlank()) {
            return;
        }

        NavigationCommands.navigateToGene(geneName);

        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        Set<Integer> geneTrackIndices = collectVisibleTracksForGene(geneName);
        registry.setFocusedTrackIndices(geneTrackIndices, geneName);

        int displayedCount = registry.getDisplayedTrackCount();
        if (displayedCount <= 0) {
            registry.setFirstVisibleSample(-1);
            registry.setLastVisibleSample(-1);
            registry.setScrollBarPosition(0);
            GenomicCanvas.update.set(!GenomicCanvas.update.get());
            return;
        }

        registry.setFirstVisibleSample(0);
        registry.setLastVisibleSample(displayedCount - 1);
        double viewportHeight = estimateSampleViewportHeight(registry);
        if (viewportHeight > 0) {
            registry.setSampleHeight(viewportHeight / Math.max(1, displayedCount));
        }
        registry.setScrollBarPosition(0);
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
    }

    private Set<Integer> collectVisibleTracksForGene(String geneName) {
        Set<Integer> trackIndices = new HashSet<>();
        if (sourceVariants == null || sourceVariants.isEmpty() || geneName == null || geneName.isBlank()) {
            return trackIndices;
        }

        VariantFilter filter = vcfManager.getCurrentFilter();
        VariantNode node = sourceVariants.getFirst();
        while (node != null) {
            VariantAnnotation ann = node.annotation;
            if (ann != null && ann.geneName() != null && ann.geneName().equalsIgnoreCase(geneName)) {
                for (VariantNode.SampleCall call : node.getSamples()) {
                    if (filter.passes(node, call.trackIndex)) {
                        trackIndices.add(call.trackIndex);
                    }
                }
            }
            node = node.next;
        }
        return trackIndices;
    }

    private double estimateSampleViewportHeight(SampleRegistry registry) {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (!stackManager.isEmpty() && stackManager.getFirst().alignmentCanvas != null) {
            double fromCanvas = stackManager.getFirst().alignmentCanvas.getHeight() - registry.getMasterTrackHeight();
            if (fromCanvas > 0) {
                return fromCanvas;
            }
        }
        double derived = registry.getSampleHeight() * Math.max(1, registry.getVisibleSampleCount());
        return Math.max(0, derived);
    }

    private void setupTextColumn(TableColumn<AnnotationRow, String> column, String property) {
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        column.setCellFactory(col -> new TableCell<AnnotationRow, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : item);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setStyle("");
                } else {
                    AnnotationRow row = getTableRow().getItem();
                    String textColor = row.getRowTextColor();
                    setStyle("-fx-text-fill: " + textColor + ";");
                }
            }
        });
    }

    private void setupTableRowFactory(TableView<AnnotationRow> table) {
        // Row factory no longer needed for text coloring, but kept for potential future use
        // Coloring is now handled by individual cell factories
    }

    // ── Filter State Management ───────────────────────────────────────────────

    private void loadFilterState(VariantFilter filter) {
        suppressFilterApplyEvents = true;
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
        suppressFilterApplyEvents = false;
    }

    private VariantFilter buildFilterFromUI() {
        VariantFilter filter = new VariantFilter();

        // Variant types - collect from dynamic checkboxes
        Set<VcfVariantType> types = new HashSet<>();
        for (java.util.Map.Entry<VcfVariantType, CheckBox> entry : variantTypeCheckBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                types.add(entry.getKey());
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

    private void applyFiltersNow() {
        if (suppressFilterApplyEvents) {
            return;
        }

        VariantFilter filter = buildFilterFromUI();

        if (chromosome == null || chromosome.isBlank() || sourceVariants == null) {
            vcfManager.setCurrentFilterForNextLoad(filter);
            hideReloadBanner();
            return;
        }

        if (vcfManager.canApplyFilterWithoutReload(filter, chromosome)) {
            pendingReloadFilter = null;
            hideReloadBanner();
            vcfManager.applyFilter(filter, chromosome);
            scheduleRebuild(filter);
            return;
        }

        // Looser than loaded dataset: keep UI change, but require reload to include newly allowed variants.
        pendingReloadFilter = filter;
        vcfManager.setCurrentFilterForNextLoad(filter);
        showReloadBanner("Reload needed");
    }

    @FXML
    private void handleReloadFilteredVariants() {
        if (chromosome == null || chromosome.isBlank()) {
            return;
        }
        VariantFilter target = pendingReloadFilter != null ? pendingReloadFilter : buildFilterFromUI();
        pendingReloadFilter = null;
        hideReloadBanner();
        lastBuiltSize = -1;
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
        coverageSlider.setValue(0);
        alleleFreqSlider.setValue(0);
        cancerOnlyCheckBox.setSelected(false);
        
        // Clear advanced filters
        if (advancedFiltersContainer != null) {
            advancedFiltersContainer.getChildren().clear();
        }
        
        handleApplyFilters();
    }

    @FXML
    private void handleApplyComparison() {
        // TODO: Implement sample comparison logic
        System.out.println("Sample comparison not yet implemented");
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
        VariantList variants = sourceVariants;
        String chrom = chromosome;
        if (variants == null || variants.isEmpty()) {
            return "No variants currently loaded.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Chromosome: ").append(chrom).append("\n");
        sb.append("Total variants: ").append(variants.size()).append("\n\n");
        sb.append("Variant list (position, ref\u2192alt, type, gene, effect, maxGQ):\n");

        VariantFilter filter = vcfManager.getCurrentFilter();
        int count = 0;
        VariantNode node = variants.getFirst();
        while (node != null && count < 300) {
            double maxGq = -1;
            boolean passes = false;
            for (VariantNode.SampleCall call : node.getSamples()) {
                if (filter.passes(node, call.trackIndex)) {
                    passes = true;
                    if (call.quality > maxGq) maxGq = call.quality;
                }
            }
            if (passes) {
                VariantAnnotation ann = node.annotation;
                String gene   = (ann != null && ann.geneName() != null) ? ann.geneName() : "-";
                String effect = ann != null ? ann.effect().displayName() : "intergenic";
                sb.append(chrom).append(":").append(node.position)
                  .append("\t").append(node.ref).append("\u2192").append(node.alt.isEmpty() ? "." : node.alt)
                  .append("\t").append(typeLabel(node.type))
                  .append("\tgene=").append(gene)
                  .append("\teffect=").append(effect)
                  .append("\tGQ=").append(maxGq >= 0 ? String.format("%.0f", maxGq) : "-")
                  .append("\n");
                count++;
            }
            node = node.next;
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
        // Get the currently displayed chromosome from the active DrawStack
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (stackManager.isEmpty()) {
            setPlaceholder("No chromosome currently displayed.");
            return;
        }

        DrawStack firstStack = stackManager.getFirst();
        String chrom = firstStack.chromosome;
        if (chrom == null) {
            setPlaceholder("No chromosome currently displayed.");
            return;
        }

        // When switching chromosomes, reload to ensure fresh data
        boolean chromosomeChanged = !chrom.equals(chromosome);
        if (chromosomeChanged) {
            chromosome = chrom;  // Update immediately to prevent re-triggering reload
            lastBuiltSize = -1;
            clearTableItemsForChromosomeSwitch();
            
            // Force reload of the chromosome data with current filter
            vcfManager.reloadCurrentChromosome();
            // Set up callback to retry loadData when reload completes
            vcfManager.setOnChromosomeVariantsReady(() -> {
                Platform.runLater(this::loadData);
            });
            return;  // Exit early; loadData will be called again when data is ready
        }

        VariantList fresh = vcfManager.getCachedVariants(chrom);
        int freshSize = fresh != null ? fresh.size() : 0;

        // Skip only when same chrom, same size, annotation done, and no annotation in flight
        if (fresh == sourceVariants && sourceVariants != null
                && freshSize == lastBuiltSize && !annotationRunning
                && vcfManager.isAnnotated(chromosome)) {
            return;
        }

        sourceVariants = fresh;

        // Update variant type filters based on loaded data
        populateVariantTypeFilters();

        if (sourceVariants == null || sourceVariants.isEmpty()) {
            clearTableItemsForChromosomeSwitch();
            if (sourceVariants == null || vcfManager.isLoadingChromosome(chromosome)) {
                setPlaceholder("Loading variants for " + chromosome + "…");
                // Register callback to retry when this chromosome's variants are cached
                if (!chromosome.equals(waitingForChromosome)) {
                    waitingForChromosome = chromosome;
                    vcfManager.setOnChromosomeVariantsReady(() -> {
                        waitingForChromosome = null;
                        Platform.runLater(this::loadData);
                    });
                }
            } else {
                setPlaceholder("No variants match current filter settings");
            }
            refreshReloadBannerState();
            return;
        }

        refreshReloadBannerState();

        // Coalesce rapid updates: rebuild only when not already running
        if (freshSize != lastBuiltSize) {
            lastBuiltSize = freshSize;
            scheduleRebuild(vcfManager.getCurrentFilter());
        }

        if (!annotationRunning && !vcfManager.isAnnotated(chromosome)) {
            annotationRunning = true;
            final String capturedChrom = chromosome;
            final VariantList capturedVariants = sourceVariants;
            annotationThread = new Thread(() -> {
                if (Thread.currentThread().isInterrupted()) {
                    Platform.runLater(() -> annotationRunning = false);
                    return;
                }
                vcfManager.ensureAnnotated(capturedChrom);
                Platform.runLater(() -> {
                    annotationRunning = false;
                    if (!capturedChrom.equals(chromosome) || capturedVariants != sourceVariants) {
                        loadData();
                        return;
                    }
                    scheduleRebuild(vcfManager.getCurrentFilter());
                    lastBuiltSize = sourceVariants != null ? sourceVariants.size() : 0;
                });
            }, "variant-annotator");
            annotationThread.setDaemon(true);
            annotationThread.start();
        }
    }

    // ── Table Rebuilding ──────────────────────────────────────────────────────

    private void scheduleRebuild(VariantFilter filter) {
        rebuildNeeded = true;
        if (!rebuildRunning) rebuildTables(filter);
    }

    private void rebuildTables(VariantFilter filter) {
        if (sourceVariants == null) {
            rebuildNeeded = false;
            cancelDelayedLoadingModal();
            return;
        }
        rebuildRunning = true;
        rebuildNeeded = false;

        // Show loading modal only if rebuild takes longer than 500ms.
        scheduleDelayedLoadingModal("Rebuilding variant tables...");
        
        final VariantList snapshot = sourceVariants;
        final String capturedChrom = chromosome;

        Thread buildThread = new Thread(() -> {
            List<AnnotationRow> coding = new ArrayList<>();
            List<AnnotationRow> intronic = new ArrayList<>();
            List<AnnotationRow> intergenic = new ArrayList<>();

            try {
                VariantNode node = snapshot.getFirst();
                while (node != null) {
                    int passSamples = 0;
                    double maxGq = -1;
                    for (VariantNode.SampleCall call : node.getSamples()) {
                        if (filter.passes(node, call.trackIndex)) {
                            passSamples++;
                            if (call.quality > maxGq) maxGq = call.quality;
                        }
                    }
                    if (passSamples > 0) {
                        AnnotationRow row = new AnnotationRow(node, capturedChrom, passSamples, maxGq);
                        VariantAnnotation ann = node.annotation;
                        VariantEffect effect = ann != null ? ann.effect() : VariantEffect.INTERGENIC;
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
            } catch (Throwable t) {
                Platform.runLater(() -> {
                    rebuildRunning = false;
                    cancelDelayedLoadingModal();
                    // Retry if new data arrived while this build was running
                    if (rebuildNeeded) rebuildTables(vcfManager.getCurrentFilter());
                });
                return;
            }

            Platform.runLater(() -> {
                rebuildRunning = false;
                codingTable.setItems(FXCollections.observableArrayList(coding));
                intronicTable.setItems(FXCollections.observableArrayList(intronic));
                intergenicTable.setItems(FXCollections.observableArrayList(intergenic));

                codingTab.setText("Gene (" + coding.size() + ")");
                intronicTab.setText("Intronic (" + intronic.size() + ")");
                intergenicTab.setText("Intergenic (" + intergenic.size() + ")");

                if (coding.isEmpty() && intronic.isEmpty() && intergenic.isEmpty()) {
                    setPlaceholder("No variants match current filter settings");
                } else {
                    codingTable.setPlaceholder(null);
                    intronicTable.setPlaceholder(null);
                    intergenicTable.setPlaceholder(null);
                }
                cancelDelayedLoadingModal();
                // Retry if new data arrived while this build was running
                if (rebuildNeeded) rebuildTables(vcfManager.getCurrentFilter());
            });
        }, "variant-table-build");
        buildThread.setDaemon(true);
        buildThread.start();
    }

    private void setPlaceholder(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: " + TEXT + ";");
        codingTable.setPlaceholder(lbl);
        intronicTable.setPlaceholder(new Label(""));
        intergenicTable.setPlaceholder(new Label(""));
        codingTab.setText("Gene");
        intronicTab.setText("Intronic");
        intergenicTab.setText("Intergenic");
    }

    private void showReloadBanner(String message) {
        if (reloadBannerLabel != null) {
            reloadBannerLabel.setText(message);
        }
        if (reloadBanner != null) {
            reloadBanner.setManaged(true);
            reloadBanner.setVisible(true);
        }
    }

    private void hideReloadBanner() {
        if (reloadBanner != null) {
            reloadBanner.setVisible(false);
            reloadBanner.setManaged(false);
        }
    }

    private void showLoadingModal(String message) {
        if (loadingModal != null) {
            loadingLabel.setText(message);
            loadingModal.setVisible(true);
            loadingModal.setManaged(true);
        }
    }

    private void scheduleDelayedLoadingModal(String message) {
        cancelDelayedLoadingModal();
        if (loadingModal == null) {
            return;
        }
        loadingModalDelayTimer = new Timeline(new KeyFrame(Duration.millis(500), e -> showLoadingModal(message)));
        loadingModalDelayTimer.setCycleCount(1);
        loadingModalDelayTimer.playFromStart();
    }

    private void hideLoadingModal() {
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
        hideLoadingModal();
    }

    private void refreshReloadBannerState() {
        if (vcfManager == null || chromosome == null || chromosome.isBlank() || sourceVariants == null) {
            hideReloadBanner();
            return;
        }
        VariantFilter uiFilter = buildFilterFromUI();
        if (vcfManager.canApplyFilterWithoutReload(uiFilter, chromosome)) {
            pendingReloadFilter = null;
            hideReloadBanner();
        } else {
            pendingReloadFilter = uiFilter;
            showReloadBanner("Reload needed");
        }
    }

    private void clearTableItemsForChromosomeSwitch() {
        codingTable.setItems(FXCollections.observableArrayList());
        intronicTable.setItems(FXCollections.observableArrayList());
        intergenicTable.setItems(FXCollections.observableArrayList());
    }

    // ── Row Model ─────────────────────────────────────────────────────────────

    public static class AnnotationRow {
        public final String position, refAlt, variantType, sampleCount, maxQuality;
        public final String geneName, effectDisplay, aaChange, codonChange;
        public final boolean isCancerGene;
        public final String cosmicTier;
        public final VariantEffect effect;

        AnnotationRow(VariantNode node, String chrom, int samples, double maxGq) {
            VariantAnnotation ann = node.annotation;
            position = chrom + ":" + node.position;
            refAlt = node.ref + " → " + (node.alt.isEmpty() ? "." : node.alt);
            variantType = typeLabel(node.type);
            sampleCount = String.valueOf(samples);
            maxQuality = maxGq >= 0 ? String.format("%.0f", maxGq) : "-";

            geneName = ann != null ? ann.geneName() : null;
            isCancerGene = ann != null && ann.isCancerGene();
            CosmicCensusEntry cosmic = ann != null ? ann.cosmicEntry() : null;
            cosmicTier = cosmic != null ? cosmic.tier() : null;
            effect = ann != null ? ann.effect() : VariantEffect.INTERGENIC;
            effectDisplay = effect.displayName();
            aaChange = ann != null && ann.aaChange() != null ? ann.aaChange() : "";
            codonChange = ann != null && ann.codonChange() != null ? ann.codonChange() : "";
        }

        public String getPosition() { return position; }
        public String getRefAlt() { return refAlt; }
        public String getVariantType() { return variantType; }
        public String getSampleCount() { return sampleCount; }
        public String getMaxQuality() { return maxQuality; }
        public String getEffectDisplay() { return effectDisplay; }
        public String getAaChange() { return aaChange; }
        public String getCodonChange() { return codonChange; }

        public boolean isSpliceSite() {
            return effect == VariantEffect.SPLICE_SITE;
        }

        /**
         * Get text color for row based on variant effect:
         * - Synonymous: green
         * - Missense/inframe indel: orange
         * - Stop/frameshift/truncating/splice site: red
         * - UTR/intronic/non-coding gene: light gray
         * - Intergenic: default (white)
         */
        public String getRowTextColor() {
            return switch (effect) {
                case CODING_SYNONYMOUS -> COLOR_SYNONYMOUS;
                case CODING_MISSENSE, CODING_INFRAME -> COLOR_MISSENSE;
                case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT, SPLICE_SITE -> COLOR_TRUNCATING;  // Red for highly damaging
                case CODING_OTHER, UTR5, UTR3, INTRONIC, NONCODING_GENE -> COLOR_NONCODING;  // Light gray for non-coding variants
                default -> TEXT; // Default white for intergenic
            };
        }
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
