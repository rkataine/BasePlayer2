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
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.*;

/** Variant annotation table with per-chromosome filtering linked to canvas display. */
public class VariantManagerDialog {

    private static final String BG    = "#2b2b2b";
    private static final String BG2   = "#333333";
    private static final String BG3   = "#3c3c3c";
    private static final String TEXT  = "white";
    private static final String ACCENT       = "#0078d4";
    private static final String CANCER_COLOR = "#d16624";

    private final VcfManager vcfManager;
    private final Stage stage;

    // Filter controls
    private CheckBox cancerOnlyCb;
    private TextField minQualityField;
    private CheckBox snvCb, indelCb, mnvCb;
    private CheckBox codingCb, intronicCb, intergenicCb;

    // Tables
    private TableView<AnnotationRow> codingTable;
    private TableView<AnnotationRow> intronicTable;
    private TableView<AnnotationRow> intergenicTable;
    private Tab codingTab, intronicTab, intergenicTab;

    // Working data
    private VariantList sourceVariants;
    private String chromosome;
    
    // Track if we're waiting for cache
    private String waitingForChromosome;
    private javafx.beans.value.ChangeListener<Boolean> updateListener;
    private volatile boolean annotationRunning;
    private volatile Thread annotationThread;
    private int lastBuiltSize = -1;
    private volatile boolean rebuildRunning = false; // one build at a time
    private volatile boolean rebuildNeeded  = false; // retry flag if data changed during build

    private VariantManagerDialog(Window owner, VcfManager vcfManager, Runnable onClose) {
        this.vcfManager = vcfManager;

        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Variant Manager");
        stage.setResizable(true);
        stage.setMinWidth(820);
        stage.setMinHeight(580);

        VBox root = new VBox();
        root.setStyle("-fx-background-color: " + BG + ";");
        root.getChildren().addAll(buildFilterPanel(), buildTabPane());
        VBox.setVgrow(root.getChildren().get(1), Priority.ALWAYS);

        stage.setScene(new Scene(root, 1050, 700));
        stage.setOnHidden(e -> {
            if (annotationThread != null && annotationThread.isAlive()) {
                annotationThread.interrupt();
            }
            GenomicCanvas.update.removeListener(updateListener);
            vcfManager.clearFilter();
            vcfManager.setOnVcfAdded(null);
            if (onClose != null) onClose.run();
        });

        vcfManager.setOnVcfAdded(this::loadData);
        updateListener = (obs, oldVal, newVal) -> Platform.runLater(this::loadData);
        GenomicCanvas.update.addListener(updateListener);

        loadData();
    }

    public static void show(Window owner, VcfManager vcfManager, Runnable onClose) {
        new VariantManagerDialog(owner, vcfManager, onClose).stage.show();
    }

    // ── Filter panel (grid layout) ────────────────────────────────────────────

    private VBox buildFilterPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10, 14, 10, 14));
        panel.setStyle("-fx-background-color: " + BG2 + ";");

        // Row 1 – variant types
        HBox row1 = new HBox(12);
        row1.setAlignment(Pos.CENTER_LEFT);
        snvCb   = styledCheckBox("SNV");
        indelCb = styledCheckBox("Indel");
        mnvCb   = styledCheckBox("MNV");
        Set<VcfVariantType> allowed = vcfManager.getCurrentFilter().getAllowedTypes();
        snvCb.setSelected(allowed.contains(VcfVariantType.SNV));
        indelCb.setSelected(allowed.contains(VcfVariantType.INSERTION)
                         || allowed.contains(VcfVariantType.DELETION));
        mnvCb.setSelected(allowed.contains(VcfVariantType.MNV));
        
        // Apply filter immediately when checkboxes change
        snvCb.setOnAction(e -> applyFilter());
        indelCb.setOnAction(e -> applyFilter());
        mnvCb.setOnAction(e -> applyFilter());
        
        row1.getChildren().addAll(sectionLabel("Variant types:"), snvCb, indelCb, mnvCb);

        // Row 2 – effect categories
        HBox row2 = new HBox(12);
        row2.setAlignment(Pos.CENTER_LEFT);
        codingCb     = styledCheckBox("Coding");
        intronicCb   = styledCheckBox("Intronic");
        intergenicCb = styledCheckBox("Intergenic");
        codingCb.setSelected(vcfManager.getCurrentFilter().isShowCoding());
        intronicCb.setSelected(vcfManager.getCurrentFilter().isShowIntronic());
        intergenicCb.setSelected(vcfManager.getCurrentFilter().isShowIntergenic());
        
        // Apply filter immediately when checkboxes change
        codingCb.setOnAction(e -> applyFilter());
        intronicCb.setOnAction(e -> applyFilter());
        intergenicCb.setOnAction(e -> applyFilter());
        
        row2.getChildren().addAll(sectionLabel("Show effects:"), codingCb, intronicCb, intergenicCb);

        // Row 3 – quality, cancer, buttons
        HBox row3 = new HBox(12);
        row3.setAlignment(Pos.CENTER_LEFT);

        Label qualLabel = styledLabel("Min GQ:");
        minQualityField = new TextField(String.valueOf((int) vcfManager.getCurrentFilter().getMinQuality()));
        minQualityField.setPrefWidth(55);
        minQualityField.setStyle("-fx-background-color: " + BG3 + "; -fx-text-fill: white;"
                               + " -fx-border-color: #555;");
        
        // Apply filter on Enter key in quality field
        minQualityField.setOnAction(e -> applyFilter());

        cancerOnlyCb = styledCheckBox("Cancer genes only (COSMIC)");
        cancerOnlyCb.setSelected(vcfManager.getCurrentFilter().isCancerGenesOnly());
        cancerOnlyCb.setOnAction(e -> applyFilter());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button resetBtn = new Button("Reset");
        resetBtn.setStyle("-fx-background-color: " + BG3 + "; -fx-text-fill: white; -fx-cursor: hand;");
        resetBtn.setOnAction(e -> resetFilter());

        Button applyBtn = new Button("Apply");
        applyBtn.setStyle("-fx-background-color: " + ACCENT + "; -fx-text-fill: white; -fx-cursor: hand;");
        applyBtn.setOnAction(e -> applyFilter());

        row3.getChildren().addAll(qualLabel, minQualityField, cancerOnlyCb, spacer, resetBtn, applyBtn);

        panel.getChildren().addAll(row1, row2, row3);
        return panel;
    }

    // ── Tab pane ──────────────────────────────────────────────────────────────

    private TabPane buildTabPane() {
        codingTable     = buildTable(true);
        intronicTable   = buildTable(false);
        intergenicTable = buildTable(false);

        codingTab     = new Tab("Coding",     wrapTable(codingTable));
        intronicTab   = new Tab("Intronic",   wrapTable(intronicTable));
        intergenicTab = new Tab("Intergenic", wrapTable(intergenicTable));

        for (Tab t : List.of(codingTab, intronicTab, intergenicTab)) {
            t.setClosable(false);
        }

        TabPane tabs = new TabPane(codingTab, intronicTab, intergenicTab);
        tabs.setStyle("-fx-background-color: " + BG + ";");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return tabs;
    }

    private StackPane wrapTable(TableView<?> table) {
        StackPane sp = new StackPane(table);
        sp.setStyle("-fx-background-color: " + BG + ";");
        return sp;
    }

    @SuppressWarnings("unchecked")
    private TableView<AnnotationRow> buildTable(boolean withCodingColumns) {
        TableView<AnnotationRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        styleTable(table);

        // Gene column with COSMIC tier badge
        TableColumn<AnnotationRow, AnnotationRow> geneCol = new TableColumn<>("Gene");
        geneCol.setPrefWidth(130);
        geneCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        geneCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(AnnotationRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null || row.geneName == null) { setGraphic(null); setText(null); return; }
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
                setGraphic(hbox); setText(null);
            }
        });

        TableColumn<AnnotationRow, String> posCol     = textCol("Position",  "position",    150);
        TableColumn<AnnotationRow, String> refAltCol  = textCol("Ref \u2192 Alt", "refAlt", 90);
        TableColumn<AnnotationRow, String> typeCol    = textCol("Type",       "variantType", 65);
        TableColumn<AnnotationRow, String> samplesCol = textCol("Samples",    "sampleCount", 70);
        TableColumn<AnnotationRow, String> qualCol    = textCol("Max GQ",     "maxQuality",  70);

        table.getColumns().addAll(geneCol, posCol, refAltCol, typeCol);

        if (withCodingColumns) {
            table.getColumns().addAll(
                textCol("Effect",       "effectDisplay", 120),
                textCol("AA Change",    "aaChange",      120),
                textCol("Codon Change", "codonChange",   110)
            );
        }

        table.getColumns().addAll(samplesCol, qualCol);
        return table;
    }

    // ── Data loading ──────────────────────────────────────────────────────────

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

    // ── Filter application ────────────────────────────────────────────────────

    private void applyFilter() {
        VariantFilter filter = buildFilterFromUI();
        vcfManager.applyFilter(filter, chromosome);
        scheduleRebuild(filter);
    }

    private void resetFilter() {
        cancerOnlyCb.setSelected(false);
        minQualityField.setText("0");
        snvCb.setSelected(true);  indelCb.setSelected(true);  mnvCb.setSelected(true);
        codingCb.setSelected(true); intronicCb.setSelected(true); intergenicCb.setSelected(true);
        applyFilter();
    }

    private VariantFilter buildFilterFromUI() {
        VariantFilter filter = new VariantFilter();
        filter.setCancerGenesOnly(cancerOnlyCb.isSelected());
        try { filter.setMinQuality(Double.parseDouble(minQualityField.getText().trim())); }
        catch (NumberFormatException ignored) {}

        Set<VcfVariantType> types = new HashSet<>();
        if (snvCb.isSelected())   types.add(VcfVariantType.SNV);
        if (indelCb.isSelected()) { types.add(VcfVariantType.INSERTION); types.add(VcfVariantType.DELETION); }
        if (mnvCb.isSelected())   types.add(VcfVariantType.MNV);
        types.add(VcfVariantType.COMPLEX); // never silently drop complex/SV
        filter.setAllowedTypes(types);

        filter.setShowCoding(codingCb.isSelected());
        filter.setShowIntronic(intronicCb.isSelected());
        filter.setShowIntergenic(intergenicCb.isSelected());
        return filter;
    }

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
        rebuildNeeded  = false;
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
                    if      (effect.isCoding())                coding.add(row);
                    else if (effect.isIntronic())              intronic.add(row);
                    else                                       intergenic.add(row);
                }
                node = node.next;
            }

            Platform.runLater(() -> {
                rebuildRunning = false;
                codingTable.setItems(FXCollections.observableArrayList(coding));
                intronicTable.setItems(FXCollections.observableArrayList(intronic));
                intergenicTable.setItems(FXCollections.observableArrayList(intergenic));

                codingTab.setText("Coding ("      + coding.size()     + ")");
                intronicTab.setText("Intronic ("   + intronic.size()   + ")");
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

    // ── Row model ─────────────────────────────────────────────────────────────

    public static class AnnotationRow {
        public final String position, refAlt, variantType, sampleCount, maxQuality;
        public final String geneName, effectDisplay, aaChange, codonChange;
        public final boolean isCancerGene;
        public final String cosmicTier;

        AnnotationRow(VariantNode node, String chrom, int samples, double maxGq) {
            VariantAnnotation ann = node.annotation;
            position   = chrom + ":" + node.position;
            refAlt     = node.ref + " \u2192 " + (node.alt.isEmpty() ? "." : node.alt);
            variantType = typeLabel(node.type);
            sampleCount = String.valueOf(samples);
            maxQuality  = maxGq >= 0 ? String.format("%.0f", maxGq) : "-";

            geneName      = ann != null ? ann.geneName()  : null;
            isCancerGene  = ann != null && ann.isCancerGene();
            CosmicCensusEntry cosmic = ann != null ? ann.cosmicEntry() : null;
            cosmicTier    = cosmic != null ? cosmic.tier() : null;
            effectDisplay = ann != null ? ann.effect().displayName() : VariantEffect.INTERGENIC.displayName();
            aaChange      = ann != null && ann.aaChange()    != null ? ann.aaChange()    : "";
            codonChange   = ann != null && ann.codonChange() != null ? ann.codonChange() : "";
        }

        public String getPosition()      { return position; }
        public String getRefAlt()        { return refAlt; }
        public String getVariantType()   { return variantType; }
        public String getSampleCount()   { return sampleCount; }
        public String getMaxQuality()    { return maxQuality; }
        public String getEffectDisplay() { return effectDisplay; }
        public String getAaChange()      { return aaChange; }
        public String getCodonChange()   { return codonChange; }
    }

    private static String typeLabel(VcfVariantType type) {
        return switch (type) {
            case SNV -> "SNV"; case INSERTION -> "Ins"; case DELETION -> "Del"; case MNV -> "MNV";
            default -> "Other";
        };
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setPlaceholder(String text) {
        Label lbl = styledLabel(text);
        codingTable.setPlaceholder(lbl);
        intronicTable.setPlaceholder(styledLabel(""));
        intergenicTable.setPlaceholder(styledLabel(""));
        codingTab.setText("Coding");
        intronicTab.setText("Intronic");
        intergenicTab.setText("Intergenic");
    }

    private static Label styledLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + TEXT + ";");
        return l;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #aaaaaa; -fx-min-width: 100;");
        return l;
    }

    private static CheckBox styledCheckBox(String text) {
        CheckBox cb = new CheckBox(text);
        cb.setStyle("-fx-text-fill: " + TEXT + ";");
        cb.setSelected(true);
        return cb;
    }

    private static <T> TableColumn<T, String> textCol(String header, String property, double width) {
        TableColumn<T, String> col = new TableColumn<>(header);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        return col;
    }

    private static void styleTable(TableView<?> table) {
        table.setStyle("-fx-background-color: " + BG + "; -fx-control-inner-background: " + BG
            + "; -fx-table-cell-border-color: #3a3a3a;");
        table.setPlaceholder(styledLabel("No variants"));
    }
}
