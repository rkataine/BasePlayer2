package org.baseplayer.variant.ui;

import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.net.URL;
import java.util.*;

/**
 * Controller for the FXML-based Variant Manager dialog.
 * Provides tabbed filtering interface and variant annotation tables.
 */
public class VariantManagerController implements Initializable {

    private static final String TEXT         = "white";
    private static final String ACCENT       = "#0078d4";
    private static final String CANCER_COLOR = "#d16624";

    // ── FXML Components ───────────────────────────────────────────────────────

    // Filter Tab: Variant Filters
    @FXML private CheckBox snvCheckBox, indelCheckBox, mnvCheckBox;
    @FXML private CheckBox codingCheckBox, intronicCheckBox, intergenicCheckBox;
    @FXML private Slider qualitySlider, coverageSlider, alleleFreqSlider;
    @FXML private TextField qualityField, coverageField, alleleFreqField;
    @FXML private Label qualityValueLabel, coverageValueLabel, alleleFreqValueLabel;
    @FXML private CheckBox cancerOnlyCheckBox;

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
    private int lastBuiltSize = -1;
    private volatile boolean rebuildRunning = false;
    private volatile boolean rebuildNeeded = false;

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

        // Set up listeners
        vcfManager.setOnVcfAdded(this::loadData);
        updateListener = (obs, oldVal, newVal) -> Platform.runLater(this::loadData);
        GenomicCanvas.update.addListener(updateListener);

        // Load initial data
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
        vcfManager.clearFilter();
        vcfManager.setOnVcfAdded(null);
        if (onClose != null) {
            onClose.run();
        }
    }

    // ── Slider Bindings ───────────────────────────────────────────────────────

    private void setupSliderBindings() {
        // Quality slider
        qualitySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int val = newVal.intValue();
            qualityValueLabel.setText(String.valueOf(val));
            qualityField.setText(String.valueOf(val));
        });
        qualityField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                double val = Double.parseDouble(newVal);
                qualitySlider.setValue(val);
            } catch (NumberFormatException ignored) {}
        });

        // Coverage slider
        coverageSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int val = newVal.intValue();
            coverageValueLabel.setText(String.valueOf(val));
            coverageField.setText(String.valueOf(val));
        });
        coverageField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                double val = Double.parseDouble(newVal);
                coverageSlider.setValue(val);
            } catch (NumberFormatException ignored) {}
        });

        // Allele frequency slider
        alleleFreqSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            double val = newVal.doubleValue();
            String formatted = String.format("%.2f", val);
            alleleFreqValueLabel.setText(formatted);
            alleleFreqField.setText(formatted);
        });
        alleleFreqField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                double val = Double.parseDouble(newVal);
                alleleFreqSlider.setValue(val);
            } catch (NumberFormatException ignored) {}
        });
    }

    private void setupAutoFilterListeners() {
        // Type checkboxes
        snvCheckBox.setOnAction(e -> handleApplyFilters());
        indelCheckBox.setOnAction(e -> handleApplyFilters());
        mnvCheckBox.setOnAction(e -> handleApplyFilters());

        // Effect checkboxes
        codingCheckBox.setOnAction(e -> handleApplyFilters());
        intronicCheckBox.setOnAction(e -> handleApplyFilters());
        intergenicCheckBox.setOnAction(e -> handleApplyFilters());

        // Cancer filter
        cancerOnlyCheckBox.setOnAction(e -> handleApplyFilters());

        // Quality field (apply on Enter)
        qualityField.setOnAction(e -> handleApplyFilters());
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
                lbl.setStyle("-fx-text-fill: " + (row.isCancerGene ? CANCER_COLOR : TEXT) + ";");
                hbox.getChildren().add(lbl);
                if (row.isCancerGene && row.cosmicTier != null) {
                    Label tier = new Label("T" + row.cosmicTier);
                    tier.setStyle("-fx-background-color: " + CANCER_COLOR + "; -fx-text-fill: white;"
                            + " -fx-padding: 0 3 0 3; -fx-font-size: 9; -fx-background-radius: 3;");
                    hbox.getChildren().add(tier);
                }
                setGraphic(hbox);
                setText(null);
            }
        });
    }

    private void setupTextColumn(TableColumn<AnnotationRow, String> column, String property) {
        column.setCellValueFactory(new PropertyValueFactory<>(property));
    }

    // ── Filter State Management ───────────────────────────────────────────────

    private void loadFilterState(VariantFilter filter) {
        Set<VcfVariantType> types = filter.getAllowedTypes();
        snvCheckBox.setSelected(types.contains(VcfVariantType.SNV));
        indelCheckBox.setSelected(types.contains(VcfVariantType.INSERTION)
                || types.contains(VcfVariantType.DELETION));
        mnvCheckBox.setSelected(types.contains(VcfVariantType.MNV));

        codingCheckBox.setSelected(filter.isShowCoding());
        intronicCheckBox.setSelected(filter.isShowIntronic());
        intergenicCheckBox.setSelected(filter.isShowIntergenic());

        qualitySlider.setValue(filter.getMinQuality());
        cancerOnlyCheckBox.setSelected(filter.isCancerGenesOnly());
    }

    private VariantFilter buildFilterFromUI() {
        VariantFilter filter = new VariantFilter();

        // Variant types
        Set<VcfVariantType> types = new HashSet<>();
        if (snvCheckBox.isSelected()) types.add(VcfVariantType.SNV);
        if (indelCheckBox.isSelected()) {
            types.add(VcfVariantType.INSERTION);
            types.add(VcfVariantType.DELETION);
        }
        if (mnvCheckBox.isSelected()) types.add(VcfVariantType.MNV);
        types.add(VcfVariantType.COMPLEX); // Always include complex/SV
        filter.setAllowedTypes(types);

        // Effect categories
        filter.setShowCoding(codingCheckBox.isSelected());
        filter.setShowIntronic(intronicCheckBox.isSelected());
        filter.setShowIntergenic(intergenicCheckBox.isSelected());

        // Quality
        try {
            filter.setMinQuality(Double.parseDouble(qualityField.getText().trim()));
        } catch (NumberFormatException ignored) {}

        // Cancer genes
        filter.setCancerGenesOnly(cancerOnlyCheckBox.isSelected());

        return filter;
    }

    // ── Action Handlers ───────────────────────────────────────────────────────

    @FXML
    private void handleApplyFilters() {
        VariantFilter filter = buildFilterFromUI();
        vcfManager.applyFilter(filter, chromosome);
        scheduleRebuild(filter);
    }

    @FXML
    private void handleResetFilters() {
        snvCheckBox.setSelected(true);
        indelCheckBox.setSelected(true);
        mnvCheckBox.setSelected(true);
        codingCheckBox.setSelected(true);
        intronicCheckBox.setSelected(true);
        intergenicCheckBox.setSelected(true);
        qualitySlider.setValue(0);
        coverageSlider.setValue(0);
        alleleFreqSlider.setValue(0);
        cancerOnlyCheckBox.setSelected(false);
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

        VariantList fresh = vcfManager.getCachedVariants(chrom);
        int freshSize = fresh != null ? fresh.size() : 0;

        // Skip only when same chrom, same size, annotation done, and no annotation in flight
        if (chrom.equals(chromosome) && fresh == sourceVariants && sourceVariants != null
                && freshSize == lastBuiltSize && !annotationRunning
                && vcfManager.isAnnotated(chromosome)) {
            return;
        }

        if (!chrom.equals(chromosome)) lastBuiltSize = -1;
        chromosome = chrom;
        sourceVariants = fresh;

        if (sourceVariants == null || sourceVariants.isEmpty()) {
            setPlaceholder("Loading variants for " + chromosome + "…");
            // Register callback to retry when this chromosome's variants are cached
            if (!chromosome.equals(waitingForChromosome)) {
                waitingForChromosome = chromosome;
                vcfManager.setOnChromosomeVariantsReady(() -> {
                    waitingForChromosome = null;
                    Platform.runLater(this::loadData);
                });
            }
            return;
        }

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
            return;
        }
        rebuildRunning = true;
        rebuildNeeded = false;
        final VariantList snapshot = sourceVariants;
        final String capturedChrom = chromosome;

        Thread buildThread = new Thread(() -> {
            List<AnnotationRow> coding = new ArrayList<>();
            List<AnnotationRow> intronic = new ArrayList<>();
            List<AnnotationRow> intergenic = new ArrayList<>();

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
                    if (effect.isCoding()) coding.add(row);
                    else if (effect.isIntronic()) intronic.add(row);
                    else intergenic.add(row);
                }
                node = node.next;
            }

            Platform.runLater(() -> {
                rebuildRunning = false;
                codingTable.setItems(FXCollections.observableArrayList(coding));
                intronicTable.setItems(FXCollections.observableArrayList(intronic));
                intergenicTable.setItems(FXCollections.observableArrayList(intergenic));

                codingTab.setText("Coding (" + coding.size() + ")");
                intronicTab.setText("Intronic (" + intronic.size() + ")");
                intergenicTab.setText("Intergenic (" + intergenic.size() + ")");

                if (coding.isEmpty() && intronic.isEmpty() && intergenic.isEmpty()) {
                    setPlaceholder("No variants match current filter settings");
                } else {
                    codingTable.setPlaceholder(null);
                    intronicTable.setPlaceholder(null);
                    intergenicTable.setPlaceholder(null);
                }
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
        codingTab.setText("Coding");
        intronicTab.setText("Intronic");
        intergenicTab.setText("Intergenic");
    }

    // ── Row Model ─────────────────────────────────────────────────────────────

    public static class AnnotationRow {
        public final String position, refAlt, variantType, sampleCount, maxQuality;
        public final String geneName, effectDisplay, aaChange, codonChange;
        public final boolean isCancerGene;
        public final String cosmicTier;

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
            effectDisplay = ann != null ? ann.effect().displayName() : VariantEffect.INTERGENIC.displayName();
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
